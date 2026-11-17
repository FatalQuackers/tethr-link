package app.pixlink.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Connects to the PC over Bluetooth and exposes the link on a one-time local port, so the
 * normal TLS client runs over it unchanged: same encryption, same certificate check as Wi-Fi.
 * The local port accepts exactly one connection, from this app, then closes.
 */
class BluetoothTunnel private constructor(private val bt: BluetoothSocket, private val server: ServerSocket) {

    val localPort: Int get() = server.localPort
    @Volatile private var local: Socket? = null

    private fun start() {
        thread(name = "pixlink-bt-accept", isDaemon = true) {
            try {
                server.soTimeout = 10_000
                val s = server.accept()
                local = s
                runCatching { server.close() }
                pump(s.getInputStream(), bt.outputStream, "pixlink-bt-up")
                pump(bt.inputStream, s.getOutputStream(), "pixlink-bt-down")
            } catch (_: Exception) { close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream, name: String) = thread(name = name, isDaemon = true) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) { }
        close()
    }

    fun close() {
        runCatching { server.close() }
        runCatching { local?.close() }
        runCatching { bt.close() }
    }

    companion object {
        /** Must match BluetoothLinkServer.ServiceUuid on the PC. */
        val SERVICE_UUID: UUID = UUID.fromString("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f60")

        /** Is this phone paired (bonded) with the PC's Bluetooth? */
        @SuppressLint("MissingPermission")
        fun isPaired(context: Context, address: String?): Boolean = address != null && runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.bondState ==
                android.bluetooth.BluetoothDevice.BOND_BONDED
        }.getOrDefault(false)

        /** Starts Bluetooth pairing with the PC: both the phone and Windows show a pairing prompt. */
        @SuppressLint("MissingPermission")
        fun pair(context: Context, address: String): Boolean = runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            if (!adapter.isEnabled) return false
            adapter.cancelDiscovery()
            adapter.getRemoteDevice(address).createBond()
        }.getOrDefault(false)

        /** Blocking: opens the Bluetooth link to [address] and returns a tunnel to connect TLS through. */
        @SuppressLint("MissingPermission")
        fun open(context: Context, address: String): BluetoothTunnel {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IllegalStateException("This phone has no Bluetooth")
            if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
            val device = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()
            val bonded = device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
            // Paired devices use an encrypted, authenticated link; otherwise try the insecure socket.
            // Either way the TLS layer on top authenticates the PC. Android's first Bluetooth connect
            // often fails while it looks the PC up, so try a few times.
            var last: Exception? = null
            var socket: BluetoothSocket? = null
            for (attempt in 0 until 3) {
                socket = try {
                    (if (bonded) device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                     else device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)).also { it.connect() }
                } catch (e: Exception) {
                    last = e
                    if (bonded) runCatching { device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() } }.getOrNull()
                    else null
                }
                if (socket != null) break
                Thread.sleep(1_500)
            }
            if (socket == null) {
                throw IllegalStateException(
                    if (!bonded) "Pair this phone with your PC over Bluetooth first (Settings → Connect using → Pair over Bluetooth)"
                    else "Your PC didn't answer over Bluetooth. Is Tethr Link open on it and Bluetooth on? (${last?.message ?: "no reply"})"
                )
            }
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            return BluetoothTunnel(socket, server).also { it.start() }
        }
    }
}
