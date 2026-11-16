package app.pixlink.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import app.pixlink.link.LinkHub
import app.pixlink.link.LinkService
import app.pixlink.pairing.PairedStore

/**
 * Connect when you get near your PC.
 *
 * While no PC is connected, the phone asks its Bluetooth chip to watch for the PC's Bluetooth LE
 * signal (the PC sends it when "Connect when my phone is nearby" is on). Most phones do this
 * matching in the Bluetooth chip itself, so it costs almost no battery. When the PC shows up,
 * Tethr Link reconnects straight away instead of waiting for its next retry. Once connected the
 * watch stops.
 */
object NearbyWatcher {
    private const val TAG = "NearbyWatcher"
    private val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f61")
    const val ACTION_NEARBY = "app.pixlink.NEARBY"
    @Volatile private var scanning = false

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 71, Intent(ctx, NearbyReceiver::class.java).setAction(ACTION_NEARBY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the system adds the scan result
    )

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops watching to match the current situation. Cheap to call often. */
    @SuppressLint("MissingPermission")
    fun update(ctx: Context, screenOn: Boolean = true) {
        val store = PairedStore(ctx)
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val offloaded = adapter?.isOffloadedFilteringSupported == true
        val want = store.nearbyConnect && allowed(ctx) && adapter?.isEnabled == true &&
            store.pcs.any { it.nearby } && !LinkHub.isConnected &&
            (offloaded || screenOn) // without chip-side matching, only watch while the screen is on
        if (want == scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        runCatching {
            if (want) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .apply { if (offloaded) setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH) }
                    .build()
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()), settings, pendingIntent(ctx))
            } else {
                scanner.stopScan(pendingIntent(ctx))
            }
            scanning = want
            Log.i(TAG, if (want) "Watching for your PC nearby" else "Stopped watching")
        }.onFailure { Log.w(TAG, "Couldn't change the nearby watch: ${it.message}") }
    }
}

/** The PC's Bluetooth signal was seen: reconnect now (at most every 30 s). */
class NearbyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < 30_000) return
        lastWake = now
        if (LinkHub.isConnected) return
        Log.i("NearbyWatcher", "Your PC is nearby: connecting")
        if (!LinkService.wakeNow()) runCatching { LinkService.start(context) }
    }

    companion object { @Volatile private var lastWake = 0L }
}
