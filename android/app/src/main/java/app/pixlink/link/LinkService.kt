package app.pixlink.link

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pixlink.BuildConfig
import app.pixlink.MainActivity
import app.pixlink.PixlinkApp
import app.pixlink.R
import app.pixlink.pairing.Identity
import app.pixlink.pairing.PairedPc
import app.pixlink.pairing.PairedStore
import app.pixlink.pairing.PairingInfo
import app.pixlink.power.PowerMode
import app.pixlink.status.StatusReporter
import app.pixlink.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that keeps a link to every paired PC alive (work laptop, home PC…),
 * each reconnecting on its own with backoff. Pairing a new PC runs as a separate,
 * short-lived connection.
 */
class LinkService : LifecycleService() {

    private lateinit var store: PairedStore
    private lateinit var status: StatusReporter
    private val loops = ConcurrentHashMap<String, Job>()
    /** Wakes a PC's reconnect loop early, e.g. when it announces a new address. */
    private val wakes = ConcurrentHashMap<String, Channel<Unit>>()
    private var pairJob: Job? = null
    @Volatile private var beaconSocket: java.net.DatagramSocket? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = PairedStore(this)
        status = StatusReporter(this)

        lifecycleScope.launch(Dispatchers.IO) { listenForPcs() }
        watchNetworkAndScreen()
        app.pixlink.messages.SmsBridge.startWatching(this)

        // Automatic updates: check now and every 6 hours while Pixlink is running.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(10_000)
            while (true) {
                runCatching { AppUpdater.autoUpdate(this@LinkService) }
                delay(6 * 60 * 60 * 1000L)
            }
        }

        // Tell the PCs whenever Power mode turns on or off, so screen control follows along.
        lifecycleScope.launch {
            PowerMode.state.collect { st ->
                LinkHub.send(msg("power.state") {
                    put("enabled", st == PowerMode.State.Ready)
                    put("canControl", st == PowerMode.State.Ready || app.pixlink.mirror.ControlService.isEnabled(this@LinkService))
                })
            }
        }

        // Keep the status-bar notification in step with the connections.
        lifecycleScope.launch {
            LinkHub.states.collect {
                refreshNotification()
                app.pixlink.companion.NearbyWatcher.update(this@LinkService, screenOn)
                runCatching { app.pixlink.widget.PixlinkWidget.update(this@LinkService) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Quiet mode runs without the notification (Android keeps us alive as the PC's companion app).
        // Started with startForegroundService (normal mode, or quiet mode wasn't allowed) → must show it.
        val foreground = intent?.getBooleanExtra(EXTRA_FOREGROUND, true) ?: !app.pixlink.companion.QuietMode.isActive(this)
        if (foreground) {
            startForeground(NOTIF_ID, buildNotification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            isForeground = true
            refreshNotification()
        } else if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        when (intent?.action) {
            ACTION_PAIR -> intent.getStringExtra(EXTRA_PAIR_URI)?.let(PairingInfo::parse)?.let(::startPairing)
        }
        syncLoops()
        stopIfIdle()
        return START_STICKY
    }

    /** Starts a connection for every paired PC that doesn't have one; stops forgotten ones. */
    private fun syncLoops() {
        val ids = store.pcs.map { it.id }.toSet()
        for (id in ids) {
            if (loops[id]?.isActive != true) loops[id] = lifecycleScope.launch(Dispatchers.IO) { connectLoop(id) }
        }
        for ((id, job) in loops.entries.toList()) {
            if (id !in ids) {
                job.cancel()
                LinkHub.client(id)?.close()
                loops.remove(id)
                wakes.remove(id)
                LinkHub.clearState(id)
                LinkHub.lastDisconnectReason.remove(id)
            }
        }
    }

    private fun stopIfIdle() {
        if (store.pcs.isEmpty() && pairJob?.isActive != true) stopSelf()
    }

    // ---------- Known PCs ----------

    private suspend fun connectLoop(pcId: String) {
        var backoffMs = 1_000L
        val wake = wakes.getOrPut(pcId) { Channel(Channel.CONFLATED) }
        while (currentCoroutineContext().isActive) {
            val pc = store.pc(pcId) ?: return
            LinkHub.setState(pcId, LinkState.Connecting(pc.name))

            // Routes to try, in order: Wi-Fi, then Bluetooth when Wi-Fi can't reach the PC.
            // No Wi-Fi at all? Don't even try it (saves waking the radio for nothing).
            // Wi-Fi first, then the internet relay (connect from anywhere), then Bluetooth.
            val routes = when (store.connectVia) {
                "wifi" -> listOf(ROUTE_WIFI)
                "bluetooth" -> listOf(ROUTE_BT)
                else -> listOf(ROUTE_WIFI, ROUTE_INTERNET, ROUTE_BT)
            }.filter { r ->
                when (r) {
                    ROUTE_WIFI -> onWifi
                    ROUTE_BT -> pc.bt != null
                    else -> pc.relay != null && pc.relayHost != null && store.allowInternet
                }
            }
            if (routes.isEmpty()) {
                LinkHub.lastDisconnectReason[pcId] = if (pc.bt == null)
                    "Not on Wi-Fi. Bluetooth isn't set up for ${pc.name} yet: connect once on the same Wi-Fi first."
                else "Not on Wi-Fi"
                if (LinkHub.states.value[pcId] !is LinkState.Error) LinkHub.setState(pcId, LinkState.Disconnected)
                wake.receive() // until Wi-Fi comes back
                backoffMs = 1_000L
                continue
            }

            for (route in routes) {
                val bluetooth = route == ROUTE_BT
                var connected = false
                var tunnel: BluetoothTunnel? = null
                var relay: RelayTunnel? = null
                lateinit var client: LinkClient
                try {
                    val (host, port) = when (route) {
                        ROUTE_BT -> {
                            tunnel = BluetoothTunnel.open(this, pc.bt!!)
                            "127.0.0.1" to tunnel.localPort
                        }
                        ROUTE_INTERNET -> {
                            relay = RelayTunnel.open(pc.relayHost!!, pc.relay!!)
                            "127.0.0.1" to relay.localPort
                        }
                        else -> pc.host to pc.port
                    }
                    client = LinkClient(
                        host, port, pc.fingerprint,
                        onConnected = { c ->
                            connected = true
                            backoffMs = 1_000L
                            LinkHub.attach(pcId, c)
                            sendHello(c, pcId, pc.name, route)
                        },
                        onMessage = MessageRouter(this, store, pcId, out = { client.send(it) })::handle,
                    )
                    try {
                        client.run() // blocks until the connection ends
                        LinkHub.lastDisconnectReason[pcId] = client.closeReason ?: "Connection closed"
                    } finally {
                        client.close()
                        LinkHub.detach(pcId, client)
                        if (!LinkHub.isConnected) status.stop()
                    }
                } catch (e: Exception) {
                    LinkHub.lastDisconnectReason[pcId] =
                        when (route) {
                            ROUTE_BT -> "Bluetooth: ${e.message ?: "couldn't reach ${pc.name}"}"
                            ROUTE_INTERNET -> "Internet: ${e.message ?: "couldn't reach ${pc.name}"}"
                            else -> describe(e, pc.name, pc.host, pc.port)
                        }
                    Log.w(TAG, "Connection to ${pc.name} failed: ${LinkHub.lastDisconnectReason[pcId]}")
                    if (e is javax.net.ssl.SSLHandshakeException) {
                        LinkHub.setState(pcId, LinkState.Error("${pc.name} no longer recognises your phone. Pair again."))
                    }
                } finally {
                    tunnel?.close()
                    relay?.close()
                }
                // A session that actually connected ended normally: start again from Wi-Fi.
                if (connected || LinkHub.states.value[pcId] is LinkState.Error) break
            }

            if (!currentCoroutineContext().isActive) return
            if (LinkHub.states.value[pcId] !is LinkState.Error) LinkHub.setState(pcId, LinkState.Disconnected)
            // Wait before trying again: short at first, then up to 1 minute with the screen on, 10 minutes off.
            // Wi-Fi reconnecting, the screen turning on or the PC's beacon all cut the wait short.
            if (withTimeoutOrNull(backoffMs) { wake.receive() } != null) backoffMs = 1_000L
            else backoffMs = (backoffMs * 2).coerceAtMost(if (screenOn) 60_000L else 600_000L)
        }
    }

    private fun sendHello(client: LinkClient, pcId: String, pcName: String, route: String = ROUTE_WIFI) {
        client.send(msg(MessageTypes.HELLO) {
            put("via", route)
            put("phoneName", store.displayName)
            put("model", Build.MODEL)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("powerMode", PowerMode.isReady)
            put("canControl", PowerMode.isReady || app.pixlink.mirror.ControlService.isEnabled(this@LinkService))
        })
        LinkHub.setState(pcId, LinkState.Connected(pcName, viaBluetooth = route == ROUTE_BT, viaInternet = route == ROUTE_INTERNET))
        status.start()
        status.resend() // the newly connected PC gets battery etc. straight away
    }

    // ---------- Pairing a new PC ----------

    /**
     * Connects once to a PC showing a pairing code. On success the PC is saved; this pairing
     * connection then closes and the PC's normal connection takes over a moment later.
     */
    private fun startPairing(info: PairingInfo) {
        pairJob?.cancel()
        LinkHub.setPairing(LinkState.Connecting(info.pcName))
        pairJob = lifecycleScope.launch(Dispatchers.IO) {
            lateinit var client: LinkClient
            var paired: PairedPc? = null
            client = LinkClient(
                info.host, info.port, info.fingerprint,
                onConnected = { c ->
                    c.send(msg(MessageTypes.PAIR_REQUEST) {
                        put("token", info.token)
                        put("phoneName", store.displayName)
                        put("model", Build.MODEL)
                        put("androidVersion", Build.VERSION.RELEASE)
                        put("phoneFingerprint", Identity.fingerprint)
                    })
                },
                onMessage = MessageRouter(
                    this@LinkService, store, pcId = null, out = { client.send(it) },
                    onPaired = { name, id ->
                        val pc = PairedPc(info.host, info.port, info.fingerprint, name, id.ifEmpty { info.fingerprint })
                        store.add(pc)
                        paired = pc
                        LinkHub.setPairing(LinkState.Connected(name))
                        client.close() // the normal connection takes over
                    },
                    onRejected = { reason ->
                        LinkHub.setPairing(LinkState.Error(reason))
                        client.close()
                    },
                )::handle,
            )
            try {
                client.run()
            } catch (e: Exception) {
                if (paired == null && LinkHub.pairing.value !is LinkState.Error) {
                    LinkHub.setPairing(LinkState.Error(describe(e, info.pcName, info.host, info.port)))
                }
            } finally {
                client.close()
            }
            if (paired == null && LinkHub.pairing.value is LinkState.Connecting) {
                LinkHub.setPairing(LinkState.Error("${info.pcName} closed the connection. Show a new code and try again."))
            }
            launch(Dispatchers.Main) { syncLoops(); stopIfIdle() }
        }
    }

    private fun describe(e: Exception, name: String, host: String, port: Int) = when (e) {
        is javax.net.ssl.SSLHandshakeException -> "Security check failed with $name"
        is java.net.SocketTimeoutException -> "Couldn't reach $name at $host:$port. Same Wi-Fi? Firewall?"
        is java.net.ConnectException -> "$name refused the connection. Is Tethr Link open on the PC?"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    // ---------- Finding PCs that moved ----------

    /**
     * Each PC broadcasts "I'm here" on UDP 47102 every few seconds. If a paired PC shows up
     * at a new address (new Wi-Fi, or it joined this phone's hotspot), switch to it and reconnect.
     */
    private suspend fun listenForPcs() {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        // Hearing broadcasts needs a multicast lock, which stops Wi-Fi from dozing, so it's only held
        // while a PC is actually missing (and we're on Wi-Fi). Connected = no lock, no listening.
        val lock = wifi.createMulticastLock("pixlink-beacon").apply { setReferenceCounted(false) }
        try {
            java.net.DatagramSocket(null).use { sock ->
                beaconSocket = sock
                sock.reuseAddress = true
                sock.bind(java.net.InetSocketAddress(BEACON_PORT))
                sock.soTimeout = 5_000
                val buf = ByteArray(1024)
                while (currentCoroutineContext().isActive) {
                    if (!beaconNeeded()) {
                        if (lock.isHeld) lock.release()
                        LinkHub.states.first { beaconNeeded() || !currentCoroutineContext().isActive }
                        continue
                    }
                    if (!lock.isHeld) lock.acquire()
                    val packet = java.net.DatagramPacket(buf, buf.size)
                    try { sock.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val obj = runCatching { Codec.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    val id = obj["pcId"]?.jsonPrimitive?.content ?: continue
                    val pc = store.pc(id) ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (host != pc.host && LinkHub.states.value[id] !is LinkState.Connected) {
                        Log.i(TAG, "${pc.name} moved to $host")
                        store.updateHost(id, host)
                        wakes[id]?.trySend(Unit)
                    }
                    // On Bluetooth but the PC is reachable over Wi-Fi again: switch to the faster link.
                    val st = LinkHub.states.value[id]
                    if (st is LinkState.Connected && st.remote && store.connectVia == "auto") {
                        Log.i(TAG, "${pc.name} is on Wi-Fi again: switching to Wi-Fi")
                        if (host != pc.host) store.updateHost(id, host)
                        LinkHub.client(id)?.close()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Beacon listener stopped: ${e.message}")
        } finally {
            runCatching { lock.release() }
        }
    }

    /**
     * The beacon is only useful while a paired PC isn't connected over Wi-Fi: to find it at a new
     * address, or to move from Bluetooth back to Wi-Fi. With the screen off it stops after 10 minutes
     * of searching (Wi-Fi coming back or the screen turning on starts it again).
     */
    private fun beaconNeeded(): Boolean {
        if (!onWifi || store.pcs.isEmpty()) return false
        if (!screenOn && System.currentTimeMillis() - searchingSince > 10 * 60_000L) return false
        val states = LinkHub.states.value
        val missing = store.pcs.any { pc ->
            val st = states[pc.id]
            st !is LinkState.Connected || (st.remote && store.connectVia == "auto")
        }
        if (!missing) searchingSince = System.currentTimeMillis()
        return missing
    }

    @Volatile private var onWifi = true
    @Volatile private var screenOn = true
    @Volatile private var searchingSince = System.currentTimeMillis()
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) { searchingSince = System.currentTimeMillis(); wakeAll() }
            LinkHub.nudge()
        }
    }

    private fun wakeAll() = wakes.values.forEach { it.trySend(Unit) }


    /** Reconnect as soon as Wi-Fi is back or the screen turns on, instead of on a timer. */
    private fun watchNetworkAndScreen() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        onWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: true
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                onWifi = true; searchingSince = System.currentTimeMillis(); wakeAll(); LinkHub.nudge()
            }
            override fun onLost(network: android.net.Network) {
                onWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                LinkHub.nudge()
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            netCallback = cb
        }
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        netCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        app.pixlink.messages.SmsBridge.stopWatching(this)
        runCatching { beaconSocket?.close() } // unblocks the beacon listener
        loops.values.forEach { it.cancel() }
        pairJob?.cancel()
        for (pc in store.pcs) {
            LinkHub.client(pc.id)?.close()
            if (LinkHub.states.value[pc.id] !is LinkState.Error) LinkHub.setState(pc.id, LinkState.Disconnected)
        }
        status.stop()
        super.onDestroy()
    }

    // ---------- Status bar notification ----------

    private fun refreshNotification() {
        if (!isForeground) return // quiet mode: no notification at all
        val connected = LinkHub.states.value.values.filterIsInstance<LinkState.Connected>().map { it.pcName }
        // No "searching…" noise: while nothing is connected the notification just says Pixlink is ready.
        val text = if (connected.isNotEmpty()) "Connected to ${connected.joinToString()}" else "Ready. Connects when your PC is nearby."
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, connected.isNotEmpty()))
    }

    private fun buildNotification(text: String, connected: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, PixlinkApp.CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(if (connected) "Tethr Link" else "Tethr Link is ready")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
        if (connected) {
            val send = PendingIntent.getActivity(this, 3,
                Intent(this, app.pixlink.clipboard.ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Send clipboard", send)
        }
        return b.build()
    }

    companion object {
        private const val TAG = "LinkService"
        private const val NOTIF_ID = 1
        private const val BEACON_PORT = 47102
        private const val ACTION_PAIR = "app.pixlink.PAIR"
        private const val EXTRA_PAIR_URI = "uri"

        private const val EXTRA_FOREGROUND = "foreground"
        private const val ROUTE_WIFI = "wifi"
        private const val ROUTE_INTERNET = "internet"
        private const val ROUTE_BT = "bluetooth"
        @Volatile private var instance: LinkService? = null

        /** Reconnect every PC now (e.g. it just came into Bluetooth range). False if the service isn't running. */
        fun wakeNow(): Boolean {
            val s = instance ?: return false
            s.searchingSince = System.currentTimeMillis()
            s.wakeAll()
            return true
        }

        /** Starts (or refreshes) connections to every paired PC. */
        fun start(context: Context) = launch(context, Intent(context, LinkService::class.java))

        /** Restarts the service, e.g. to switch quiet mode on or off. */
        fun restart(context: Context) {
            stop(context)
            start(context)
        }

        /** Quiet mode: a plain service with no notification. Otherwise (or if Android says no): a foreground service. */
        private fun launch(context: Context, intent: Intent) {
            if (app.pixlink.companion.QuietMode.isActive(context) &&
                runCatching { context.startService(Intent(intent).putExtra(EXTRA_FOREGROUND, false)) }.isSuccess) return
            context.startForegroundService(intent.putExtra(EXTRA_FOREGROUND, true))
        }

        /** Pairs with a new PC (from a QR code, a typed code or a pasted link). */
        fun pair(context: Context, uri: String) {
            LinkHub.setPairing(LinkState.Connecting(PairingInfo.parse(uri)?.pcName ?: "your PC"))
            launch(context, Intent(context, LinkService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_PAIR_URI, uri))
        }

        /** Forgets one PC and drops its connection. */
        fun forget(context: Context, pcId: String) {
            PairedStore(context).remove(pcId)
            LinkHub.clearState(pcId)
            launch(context, Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
