package app.pixlink.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.pixlink.link.LinkHub
import app.pixlink.link.msg
import app.pixlink.pairing.PairedStore
import kotlinx.serialization.json.put

/**
 * Clipboard between phone and PC.
 * - PC → phone: automatic (the PC sends what you copy; any app can write the clipboard).
 * - Phone → PC: Android only lets the app you're using read the clipboard, so it's sent when you
 *   open Pixlink, tap "Send clipboard" in its notification, or use the quick settings tile.
 * - Sign-in codes: picked out of incoming notifications and copied on the PC automatically.
 */
object ClipboardSync {
    @Volatile private var lastFromPc: String? = null
    @Volatile private var lastSent: String? = null

    fun fromPc(context: Context, text: String) {
        lastFromPc = text
        app.pixlink.activity.ActivityLog.add(context, app.pixlink.activity.ActivityLog.Entry(app.pixlink.activity.ActivityLog.Kind.ClipboardReceived, "Copied from your PC", text))
        Handler(Looper.getMainLooper()).post {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("From your PC", text))
        }
    }

    /** Reads the clipboard (only works while a Pixlink screen is in front) and sends it to every connected PC. */
    fun sendCurrent(context: Context, quiet: Boolean = false): Boolean {
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return false
        if (quiet && (text == lastFromPc || text == lastSent)) return false
        lastSent = text
        LinkHub.send(msg("clipboard.text") { put("text", text); put("auto", quiet) })
        app.pixlink.activity.ActivityLog.add(context, app.pixlink.activity.ActivityLog.Entry(app.pixlink.activity.ActivityLog.Kind.ClipboardSent, "Clipboard sent to your PC", text))
        return true
    }

    // "Your code is 123456", "G-123456 is your Google verification code", "OTP: 4821"…
    // A number only counts as a code when a code word is right next to it (not just somewhere in the
    // notification), and never when it looks like part of an address, phone number, price or order.
    private val keyword = Regex("""\b(codes?|otp|passcode|pin|verification|verify|one[- ]time|2fa|mfa|authenticat\w*|security code|login code|sign[- ]in code)\b""", RegexOption.IGNORE_CASE)
    private val strongKeyword = Regex("""\b(codes?|otp|passcode|pin|2fa|mfa)\b""", RegexOption.IGNORE_CASE)
    private val candidate = Regex("""(?<![\w$€£¥#/.:+(-])(?:[A-Z]{1,3}-)?(\d{3}[- ]\d{3}|\d{4,8})(?![\d%]|[.,:/]\d|\w)""")
    private val notACode = Regex(
        """\b(st|street|rd|road|ave|avenue|unit|suite|ste|level|lvl|floor|apt|apartment|box|drive|dr|lane|ln|way|blvd|boulevard|court|ct|place|pl|crescent|cres|parade|pde|highway|hwy|terrace|tce|close|circuit|square|sq|nsw|vic|qld|tas|act|nt|wa|sa|postcode|post code|zip|order|invoice|ref|reference|account|acct|ticket|tracking|flight|booking|ending|card|phone|tel|call|ph|fax|abn|acn|mobile|room|seat|version|v|build|total|amount|balance|aud|usd|eur|gbp|km|kg|mb|gb|px)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val sentenceEnd = Regex("""[.;!?]\s""")
    private val sentenceEndAfter = Regex("""[.;!?](?:\s|$)""")

    fun findCode(fullText: String): String? {
        val text = fullText.take(500) // subject and first lines; email footers (addresses!) come later
        val keywords = keyword.findAll(text).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        val strong = strongKeyword.findAll(text).map { it.range }.toList()
        fun gap(r: IntRange, s: Int, e: Int) = if (r.last < s) s - r.last - 1 else if (r.first >= e) r.first - e else 0
        var best: Pair<Int, String>? = null
        for (m in candidate.findAll(text)) {
            val group = m.groups[1] ?: continue
            val code = group.value.replace(" ", "").replace("-", "")
            if (code.length !in 4..8) continue
            if (code.length == 4 && code.toInt() in 1990..2099) continue // a year
            val s = m.range.first
            val e = m.range.last + 1
            // The words right around it, within the same sentence.
            val before = text.substring(maxOf(0, s - 14), s).split(sentenceEnd).last()
            val after = text.substring(e, minOf(text.length, e + 14)).split(sentenceEndAfter).first()
            if (notACode.containsMatchIn(before) || notACode.containsMatchIn(after)) continue
            if (Regex("""\d[\s-]?$""").containsMatchIn(before) || Regex("""^[\s-]?\d{2}""").containsMatchIn(after)) continue // phone number
            val distance = keywords.minOf { gap(it, s, e) }
            if (code.length < 6 && strong.none { gap(it, s, e) <= 25 }) continue // short numbers need "code"/"PIN" right there
            if (distance > (if (code.length >= 6) 50 else 25)) continue
            if (best == null || distance < best.first) best = distance to code
        }
        return best?.second
    }

    /** Called for every new notification: if it carries a sign-in code, copy it on the PC. */
    fun maybeSendCode(context: Context, app: String, title: String, text: String) {
        if (!PairedStore(context).sendCodes) return
        val code = findCode("$title $text") ?: return
        LinkHub.send(msg("clipboard.code") { put("code", code); put("app", app) })
    }
}
