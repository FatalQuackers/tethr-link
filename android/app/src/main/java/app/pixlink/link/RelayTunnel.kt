package app.pixlink.link

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Connect from anywhere: reaches the PC through the Tethr Link relay (Cloudflare) when Wi-Fi and
 * Bluetooth can't. Like [BluetoothTunnel], it exposes the link on a one-time local port so the
 * normal TLS client runs over it unchanged: the relay only ever sees encrypted bytes, and the PC's
 * certificate is checked exactly as on Wi-Fi.
 */
class RelayTunnel private constructor(private val server: ServerSocket) {
    val localPort: Int get() = server.localPort
    @Volatile private var local: Socket? = null
    @Volatile private var ws: WebSocket? = null
    private val pending = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    fun close() {
        runCatching { server.close() }
        runCatching { local?.close() }
        runCatching { ws?.close(1000, null) }
    }

    private fun startLocal() = thread(name = "pixlink-relay-accept", isDaemon = true) {
        try {
            server.soTimeout = 10_000
            val s = server.accept()
            local = s
            runCatching { server.close() }
            // Bytes that arrived before the TLS client attached.
            while (true) s.getOutputStream().write(pending.poll() ?: break)
            pending.clear()
            val input = s.getInputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (ws?.send(buf.toByteString(0, n)) != true) break
            }
        } catch (_: Exception) { }
        close()
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS) // the TLS link's own pings keep it alive
                .readTimeout(0, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        fun roomFor(key: String): String =
            MessageDigest.getInstance("SHA-256").digest("tethr-relay:$key".toByteArray()).joinToString("") { "%02x".format(it) }

        /** Blocking: joins the PC's relay room and returns a tunnel to connect TLS through. */
        fun open(host: String, key: String): RelayTunnel {
            val tunnel = RelayTunnel(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
            val opened = CountDownLatch(1)
            var failure: String? = null
            val request = Request.Builder().url("wss://$host/r/${roomFor(key)}?role=phone").build()
            tunnel.ws = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    val s = tunnel.local
                    if (s == null) tunnel.pending.add(data)
                    else runCatching { s.getOutputStream().write(data) }.onFailure { tunnel.close() }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 4404) failure = "Your PC isn't online (open Tethr Link on it)"
                    webSocket.close(1000, null)
                    tunnel.close()
                    opened.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = failure ?: "Couldn't reach the relay: ${t.message ?: "no internet"}"
                    tunnel.close()
                    opened.countDown()
                }
            })
            if (!opened.await(20, TimeUnit.SECONDS)) { tunnel.close(); throw IOException("The relay didn't answer") }
            failure?.let { throw IOException(it) }
            tunnel.startLocal()
            return tunnel
        }
    }
}
