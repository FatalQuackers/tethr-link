package app.pixlink.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Optional Tethr Link account (Supabase Auth). Nothing needs it; signing in lets this phone
 * add a PC from the same account without scanning a QR code.
 * Sign-in happens in the browser and comes back through pixlink://auth-callback.
 */
object Account {
    const val URL_BASE = "https://zljbvimcfrpeouscccvw.supabase.co"
    /** Publishable key: safe to ship. Every table only shows rows to their owner (row-level security). */
    private const val KEY = "sb_publishable_0Rk8-U2mMEexU9uivUjlIA_242OB-YU"
    const val CALLBACK = "pixlink://auth-callback"

    data class Pc(val id: String, val name: String, val fingerprint: String, val host: String?, val port: Int, val token: String?) {
        /** The same link the PC's QR code holds, so pairing works exactly like a scan. */
        fun pairingUri(): String? {
            val h = host ?: return null
            val t = token ?: return null
            return Uri.Builder().scheme("pixlink").authority("pair")
                .appendQueryParameter("v", "1").appendQueryParameter("host", h)
                .appendQueryParameter("port", port.toString()).appendQueryParameter("token", t)
                .appendQueryParameter("fp", "SHA256:$fingerprint").appendQueryParameter("name", name)
                .build().toString()
        }
    }

    class AccountException(message: String) : Exception(message)

    private val _email = MutableStateFlow<String?>(null)
    /** The signed-in email, or null when signed out. */
    val email: StateFlow<String?> = _email
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        _email.value = if (p.getString("refresh", null) != null) p.getString("email", null) ?: "your account" else null
    }

    fun signedIn(ctx: Context): Boolean { load(ctx); return _email.value != null }

    // ---------- Sign in ----------

    /** Opens the browser to sign in with "google", "github" or "discord". */
    fun signInWith(ctx: Context, provider: String) {
        val verifier = newVerifier(ctx)
        val url = "$URL_BASE/auth/v1/authorize?provider=$provider&redirect_to=${Uri.encode(CALLBACK)}" +
            "&code_challenge=${challenge(verifier)}&code_challenge_method=s256"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Emails a sign-in link. Opening it on this phone comes back to Tethr Link and finishes signing in. */
    suspend fun sendEmailLink(ctx: Context, email: String) = withContext(Dispatchers.IO) {
        val verifier = newVerifier(ctx)
        post("/auth/v1/otp?redirect_to=${Uri.encode(CALLBACK)}", buildJsonObject {
            put("email", email.trim())
            put("create_user", true)
            put("code_challenge", challenge(verifier))
            put("code_challenge_method", "s256")
        }, token = null)
        Unit
    }

    /** Called with the pixlink://auth-callback link the browser opened. */
    suspend fun finishSignIn(ctx: Context, link: Uri) = withContext(Dispatchers.IO) {
        val error = link.getQueryParameter("error_description") ?: link.getQueryParameter("error")
        val code = link.getQueryParameter("code") ?: throw AccountException(error ?: "Sign-in was cancelled.")
        val verifier = prefs(ctx).getString("verifier", null) ?: throw AccountException("Start signing in again from Tethr Link.")
        val res = post("/auth/v1/token?grant_type=pkce", buildJsonObject {
            put("auth_code", code); put("code_verifier", verifier)
        }, token = null)
        prefs(ctx).edit().remove("verifier").apply()
        save(ctx, res)
    }

    suspend fun signOut(ctx: Context) = withContext(Dispatchers.IO) {
        val token = prefs(ctx).getString("access", null)
        prefs(ctx).edit().clear().apply()
        _email.value = null
        if (token != null) runCatching { post("/auth/v1/logout", JsonObject(emptyMap()), token) }
        Unit
    }

    // ---------- Data ----------

    /** PCs signed in to this account, newest first. */
    suspend fun pcs(ctx: Context): List<Pc> = withContext(Dispatchers.IO) {
        val token = accessToken(ctx) ?: return@withContext emptyList()
        val body = request("GET", "/rest/v1/pcs?select=pc_id,name,fingerprint,host,port,pair_token&order=updated_at.desc", null, token)
        (Json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            Pc(
                id = o["pc_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: "PC",
                fingerprint = o["fingerprint"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null,
                host = o["host"]?.jsonPrimitive?.contentOrNullSafe(),
                port = o["port"]?.jsonPrimitive?.intOrNull ?: 47100,
                token = o["pair_token"]?.jsonPrimitive?.contentOrNullSafe(),
            )
        }
    }

    // ---------- Tokens ----------

    private suspend fun accessToken(ctx: Context): String? {
        val p = prefs(ctx)
        val access = p.getString("access", null) ?: return null
        if (p.getLong("expires", 0) > System.currentTimeMillis() + 60_000) return access
        val refresh = p.getString("refresh", null) ?: return null
        return try {
            save(ctx, post("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", refresh) }, null))
            p.getString("access", null)
        } catch (e: AccountException) {
            signOut(ctx) // signed out elsewhere, or the account was deleted
            null
        }
    }

    private fun save(ctx: Context, t: JsonObject) {
        val user = t["user"] as? JsonObject
        val email = user?.get("email")?.jsonPrimitive?.contentOrNullSafe() ?: prefs(ctx).getString("email", null)
        prefs(ctx).edit()
            .putString("access", t["access_token"]?.jsonPrimitive?.content)
            .putString("refresh", t["refresh_token"]?.jsonPrimitive?.content)
            .putLong("expires", System.currentTimeMillis() + (t["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L) * 1000)
            .putString("email", email)
            .apply()
        _email.value = email ?: "your account"
    }

    // ---------- HTTP ----------

    private fun post(path: String, body: JsonObject, token: String?): JsonObject =
        Json.parseToJsonElement(request("POST", path, body, token).ifBlank { "{}" }).jsonObject

    private fun request(method: String, path: String, body: JsonObject?, token: String?): String {
        val conn = URL(URL_BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("apikey", KEY)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AccountException(errorText(code, text))
            return text
        } catch (e: java.io.IOException) {
            throw AccountException("Couldn't reach the sign-in server. Check your internet connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(code: Int, text: String): String {
        if (code == 429) return "Too many tries. Wait a minute and try again."
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        return listOf("msg", "error_description", "message", "error")
            .firstNotNullOfOrNull { o?.get(it)?.jsonPrimitive?.contentOrNullSafe() } ?: "The sign-in server said $code."
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun newVerifier(ctx: Context): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val v = b64(bytes)
        prefs(ctx).edit().putString("verifier", v).apply()
        return v
    }

    private fun challenge(verifier: String) = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
