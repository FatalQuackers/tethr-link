package app.pixlink.link

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Second internet route, through Supabase Realtime: for networks that block Cloudflare's
 * workers.dev addresses (seen on school Wi-Fi) but allow Supabase.
 *
 * Joins the PC's private broadcast room (named by a hash of the relay secret), says "hello",
 * waits for the PC's "ready", then carries the normal TLS link as numbered base64 messages.
 * Like [RelayTunnel], it exposes a one-time local port so the TLS client runs unchanged.
 */
class SupabaseTunnel private constructor(private val server: ServerSocket) : Tunnel {
    override val localPort: Int get() = server.localPort
    @Volatile private var local: Socket? = null
    @Volatile private var ws: WebSocket? = null
    private lateinit var topic: String
    private val sid = UUID.randomUUID().toString()
    private val ref = AtomicInteger(1)
    private var nextOut = 1L
    private var nextIn = 1L
    private val early = ConcurrentHashMap<Long, ByteArray>()
    private val pending = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { send("bye", 0, null) }
        runCatching { server.close() }
        runCatching { local?.close() }
        runCatching { ws?.close(1000, null) }
    }

    private fun frame(event: String, payload: JsonObject, topicName: String = topic) = buildJsonObject {
        put("topic", topicName); put("event", event); put("ref", ref.incrementAndGet().toString()); put("join_ref", "1")
        put("payload", payload)
    }.toString()

    private fun send(type: String, seq: Long, data: ByteArray?, off: Int = 0, len: Int = data?.size ?: 0): Boolean {
        val inner = buildJsonObject {
            put("s", sid); put("f", "phone"); put("t", type); put("n", seq)
            if (data != null) put("b", Base64.encodeToString(data, off, len, Base64.NO_WRAP))
        }
        return ws?.send(frame("broadcast", buildJsonObject { put("type", "broadcast"); put("event", "m"); put("payload", inner) })) == true
    }

    private fun deliver(seq: Long, data: ByteArray) = synchronized(early) {
        if (seq < nextIn) return
        early[seq] = data
        while (true) {
            val next = early.remove(nextIn) ?: break
            nextIn++
            val s = local
            if (s == null) pending.add(next) else runCatching { s.getOutputStream().write(next) }.onFailure { close() }
        }
    }

    private fun startLocal() = thread(name = "pixlink-supabase-accept", isDaemon = true) {
        try {
            server.soTimeout = 10_000
            val s = server.accept()
            synchronized(early) {
                local = s
                while (true) s.getOutputStream().write(pending.poll() ?: break)
            }
            runCatching { server.close() }
            val input = s.getInputStream()
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (!send("d", nextOut++, buf, 0, n)) break
            }
        } catch (_: Exception) { }
        close()
    }

    companion object {
        private const val URL = "wss://zljbvimcfrpeouscccvw.supabase.co/realtime/v1/websocket?apikey=sb_publishable_0Rk8-U2mMEexU9uivUjlIA_242OB-YU&vsn=1.0.0"
        private const val CHUNK = 24 * 1024

        private val http by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
                .connectTimeout(12, TimeUnit.SECONDS).build()
        }

        fun topicFor(key: String): String =
            "realtime:tethr-" + MessageDigest.getInstance("SHA-256").digest("tethr-rt:$key".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(40)

        /** Blocking: joins the PC's room, waits for it to answer, and returns a tunnel for TLS. */
        fun open(key: String): SupabaseTunnel {
            val t = SupabaseTunnel(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
            t.topic = topicFor(key)
            val joined = CountDownLatch(1)
            val ready = CountDownLatch(1)
            var failure: String? = null
            t.ws = http.newWebSocket(Request.Builder().url(URL).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildJsonObject {
                        put("topic", t.topic); put("event", "phx_join"); put("ref", "1"); put("join_ref", "1")
                        putJsonObject("payload") {
                            putJsonObject("config") {
                                putJsonObject("broadcast") { put("self", false); put("ack", false) }
                                putJsonObject("presence") { put("key", "") }
                                put("private", false)
                            }
                        }
                    }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    when (m["event"]?.jsonPrimitive?.content) {
                        "phx_reply" -> if (m["ref"]?.jsonPrimitive?.content == "1") {
                            val ok = m["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "ok"
                            if (!ok) failure = "Supabase wouldn't let this phone join"
                            joined.countDown()
                        }
                        "broadcast" -> {
                            val p = m["payload"]?.jsonObject?.get("payload")?.jsonObject ?: return
                            if (p["f"]?.jsonPrimitive?.content != "pc" || p["s"]?.jsonPrimitive?.content != t.sid) return
                            when (p["t"]?.jsonPrimitive?.content) {
                                "ready" -> ready.countDown()
                                "d" -> {
                                    val n = p["n"]?.jsonPrimitive?.longOrNull ?: return
                                    val b = p["b"]?.jsonPrimitive?.content ?: return
                                    t.deliver(n, Base64.decode(b, Base64.NO_WRAP))
                                }
                                "bye" -> t.close()
                            }
                        }
                        "phx_error", "phx_close" -> { failure = failure ?: "Supabase closed the room"; t.close(); joined.countDown(); ready.countDown() }
                    }
                }

                override fun onFailure(webSocket: WebSocket, tr: Throwable, response: Response?) {
                    failure = failure ?: "Couldn't reach Supabase: ${tr.message ?: "no internet"}"
                    t.close(); joined.countDown(); ready.countDown()
                }
            })
            if (!joined.await(15, TimeUnit.SECONDS)) { t.close(); throw IOException("Supabase didn't answer") }
            failure?.let { t.close(); throw IOException(it) }
            // Heartbeats keep the room open while the link is idle.
            thread(name = "pixlink-supabase-heartbeat", isDaemon = true) {
                while (!t.closed) {
                    Thread.sleep(25_000)
                    if (!t.closed) t.ws?.send(t.frame("heartbeat", JsonObject(emptyMap()), "phoenix"))
                }
            }
            // Say hello until the PC answers (broadcasts aren't stored, so repeat a few times).
            repeat(4) {
                if (ready.count == 0L || t.closed) return@repeat
                t.send("hello", 0, null)
                ready.await(2, TimeUnit.SECONDS)
            }
            failure?.let { t.close(); throw IOException(it) }
            if (ready.count != 0L) { t.close(); throw IOException("Your PC isn't online over the internet (open Tethr Link on it)") }
            t.startLocal()
            return t
        }
    }
}

/** A one-time local port that carries the TLS link over some other route (Bluetooth, the internet). */
interface Tunnel {
    val localPort: Int
    fun close()
}
