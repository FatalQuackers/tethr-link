package app.pixlink.status

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import kotlinx.serialization.json.put

/** Sends battery, charging and Do Not Disturb state to the PC (shown in the phone panel). */
class StatusReporter(private val context: Context) {
    private var registered = false

    // Android sends battery updates every few seconds (temperature, voltage…); only real changes go to the PC.
    private var last: Triple<Int, Boolean, Boolean>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) send(intent)
            else resend(force = false)
        }
    }

    fun start() {
        if (registered) return
        val sticky = context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        })
        registered = true
        sticky?.let(::send)
    }

    /** Sends the current battery state again (e.g. to a PC that just connected). */
    fun resend(force: Boolean = true) {
        if (force) last = null
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::send)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun send(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val nm = context.getSystemService(NotificationManager::class.java)
        val now = Triple(if (level >= 0) level * 100 / scale else -1, plugged,
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        if (now == last) return
        last = now
        LinkHub.send(msg("status") {
            put("battery", now.first)
            put("charging", now.second)
            put("dnd", now.third)
        })
    }
}
