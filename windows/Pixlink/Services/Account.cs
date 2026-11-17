using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pixlink.Services;

/// <summary>
/// Optional Tethr Link account (Supabase Auth). Everything works without one; signing in adds:
///  • add your phone without a QR code (the phone finds this PC in your account),
///  • your settings follow you to a new PC.
/// The session is saved encrypted for this Windows user (DPAPI) in account.bin.
/// </summary>
public sealed class Account
{
    public const string Url = "https://zljbvimcfrpeouscccvw.supabase.co";
    /// <summary>Publishable key: safe to ship in apps. Every table is locked to its owner by row-level security.</summary>
    public const string Key = "sb_publishable_0Rk8-U2mMEexU9uivUjlIA_242OB-YU";
    private const int CallbackPort = 47199;
    private static readonly string CallbackUrl = $"http://127.0.0.1:{CallbackPort}/auth/callback";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private static string FilePath => Path.Combine(AppSettings.Folder, "account.bin");

    private Session? _session;
    public event Action? Changed;

    public bool SignedIn => _session is not null;
    public string? Email => _session?.Email;

    private sealed record Session(string AccessToken, string RefreshToken, DateTimeOffset ExpiresAt, string UserId, string? Email);

    public Account()
    {
        try { _session = LoadSession(); } catch { _session = null; } // never let the optional account stop the app
    }

    // ---------- Sign in ----------

    /// <summary>Opens the browser to sign in with "google", "github" or "discord", and waits for it to come back to this PC.</summary>
    public Task SignInWithProviderAsync(string provider, CancellationToken ct = default) => BrowserSignInAsync(verifier =>
        $"{Url}/auth/v1/authorize?provider={provider}&redirect_to={Uri.EscapeDataString(CallbackUrl)}" +
        $"&code_challenge={Challenge(verifier)}&code_challenge_method=s256", null, ct);

    /// <summary>
    /// Emails a sign-in link. Opening it on this PC finishes signing in (the link comes back to Tethr Link here).
    /// </summary>
    public Task SignInWithEmailAsync(string email, CancellationToken ct = default) => BrowserSignInAsync(null, async verifier =>
    {
        var body = new JsonObject
        {
            ["email"] = email.Trim(),
            ["create_user"] = true,
            ["code_challenge"] = Challenge(verifier),
            ["code_challenge_method"] = "s256",
        };
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{Url}/auth/v1/otp?redirect_to={Uri.EscapeDataString(CallbackUrl)}")
        { Content = Json(body) };
        req.Headers.Add("apikey", Key);
        var res = await Http.SendAsync(req, ct);
        if (!res.IsSuccessStatusCode) throw new AccountException(await ErrorText(res));
    }, ct);

    private async Task BrowserSignInAsync(Func<string, string>? authorizeUrl, Func<string, Task>? sendEmail, CancellationToken ct)
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        using var listener = new HttpListener();
        listener.Prefixes.Add($"http://127.0.0.1:{CallbackPort}/auth/");
        listener.Start();

        if (authorizeUrl is not null) Process.Start(new ProcessStartInfo(authorizeUrl(verifier)) { UseShellExecute = true });
        if (sendEmail is not null) await sendEmail(verifier);

        // Wait up to 15 minutes for the browser to come back (emails can be slow).
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromMinutes(15));
        using var reg = timeout.Token.Register(() => { try { listener.Stop(); } catch { } });
        HttpListenerContext ctx;
        try { ctx = await listener.GetContextAsync(); }
        catch when (timeout.IsCancellationRequested) { throw new OperationCanceledException(); }

        var q = System.Web.HttpUtility.ParseQueryString(ctx.Request.Url?.Query ?? "");
        var code = q["code"];
        var error = q["error_description"] ?? q["error"];
        await Reply(ctx, code is not null && error is null
            ? "You're signed in to Tethr Link. You can close this tab."
            : "Sign-in didn't finish. Go back to Tethr Link and try again.");
        if (code is null) throw new AccountException(error ?? "Sign-in was cancelled.");

        var token = await PostToken("pkce", new JsonObject { ["auth_code"] = code, ["code_verifier"] = verifier }, ct);
        SetSession(token);
    }

    private static async Task Reply(HttpListenerContext ctx, string text)
    {
        var html = "<!doctype html><meta charset=utf-8><title>Tethr Link</title>" +
                   "<body style=\"font:16px system-ui;background:#101418;color:#e6e8ea;display:grid;place-items:center;height:90vh\">" +
                   $"<p>{WebUtility.HtmlEncode(text)}</p>";
        var bytes = Encoding.UTF8.GetBytes(html);
        ctx.Response.ContentType = "text/html; charset=utf-8";
        ctx.Response.ContentLength64 = bytes.Length;
        await ctx.Response.OutputStream.WriteAsync(bytes);
        ctx.Response.Close();
    }

    public async Task SignOutAsync()
    {
        var s = _session;
        _session = null;
        try { File.Delete(FilePath); } catch { }
        Changed?.Invoke();
        if (s is null) return;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, $"{Url}/auth/v1/logout");
            req.Headers.Add("apikey", Key);
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", s.AccessToken);
            await Http.SendAsync(req);
        }
        catch { /* offline: the token expires on its own */ }
    }

    // ---------- Data (each row is only visible to its owner) ----------

    /// <summary>
    /// Puts this PC in your account so a phone signed in to the same account can add it without a QR code.
    /// The token works like the QR code's, but only someone signed in to your account can read it.
    /// </summary>
    public Task PublishPcAsync(string pcId, string name, string fingerprint, string? host, int port, string pairToken) =>
        Rest(HttpMethod.Post, "pcs?on_conflict=pc_id", new JsonObject
        {
            ["pc_id"] = pcId, ["name"] = name, ["fingerprint"] = fingerprint,
            ["host"] = host, ["port"] = port, ["pair_token"] = pairToken,
            ["updated_at"] = DateTimeOffset.UtcNow.ToString("O"),
        }, upsert: true);

    public Task RemovePcAsync(string pcId) => Rest(HttpMethod.Delete, $"pcs?pc_id=eq.{Uri.EscapeDataString(pcId)}", null);

    public async Task<JsonObject?> LoadSettingsAsync()
    {
        var res = await Rest(HttpMethod.Get, "user_settings?select=data", null);
        return (JsonNode.Parse(res ?? "[]") as JsonArray)?.FirstOrDefault()?["data"] as JsonObject;
    }

    public Task SaveSettingsAsync(JsonObject data) =>
        Rest(HttpMethod.Post, "user_settings?on_conflict=user_id", new JsonObject
        {
            ["user_id"] = _session?.UserId, ["data"] = data, ["updated_at"] = DateTimeOffset.UtcNow.ToString("O"),
        }, upsert: true);

    private async Task<string?> Rest(HttpMethod method, string path, JsonObject? body, bool upsert = false)
    {
        var token = await AccessTokenAsync();
        if (token is null) return null;
        using var req = new HttpRequestMessage(method, $"{Url}/rest/v1/{path}");
        req.Headers.Add("apikey", Key);
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (upsert) req.Headers.Add("Prefer", "resolution=merge-duplicates,return=minimal");
        if (body is not null) req.Content = Json(body);
        var res = await Http.SendAsync(req);
        if (!res.IsSuccessStatusCode) throw new AccountException(await ErrorText(res));
        return await res.Content.ReadAsStringAsync();
    }

    // ---------- Tokens ----------

    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    private async Task<string?> AccessTokenAsync()
    {
        var s = _session;
        if (s is null) return null;
        if (s.ExpiresAt > DateTimeOffset.UtcNow.AddMinutes(1)) return s.AccessToken;
        await _refreshLock.WaitAsync();
        try
        {
            s = _session;
            if (s is null) return null;
            if (s.ExpiresAt > DateTimeOffset.UtcNow.AddMinutes(1)) return s.AccessToken;
            try
            {
                SetSession(await PostToken("refresh_token", new JsonObject { ["refresh_token"] = s.RefreshToken }, default));
            }
            catch (AccountException)
            {
                // Refresh token revoked (signed out elsewhere, or the account was deleted).
                await SignOutAsync();
                return null;
            }
            return _session?.AccessToken;
        }
        finally { _refreshLock.Release(); }
    }

    private static async Task<JsonObject> PostToken(string grant, JsonObject body, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{Url}/auth/v1/token?grant_type={grant}") { Content = Json(body) };
        req.Headers.Add("apikey", Key);
        var res = await Http.SendAsync(req, ct);
        if (!res.IsSuccessStatusCode) throw new AccountException(await ErrorText(res));
        return JsonNode.Parse(await res.Content.ReadAsStringAsync()) as JsonObject ?? throw new AccountException("Bad reply from the sign-in server.");
    }

    private void SetSession(JsonObject t)
    {
        var user = t["user"] as JsonObject;
        _session = new Session(
            (string)t["access_token"]!,
            (string)t["refresh_token"]!,
            DateTimeOffset.UtcNow.AddSeconds((double?)t["expires_in"] ?? 3600),
            (string?)user?["id"] ?? _session?.UserId ?? "",
            (string?)user?["email"] ?? _session?.Email);
        SaveSession(_session);
        Changed?.Invoke();
    }

    private static void SaveSession(Session s)
    {
        try
        {
            Directory.CreateDirectory(AppSettings.Folder);
            var json = JsonSerializer.SerializeToUtf8Bytes(s);
            File.WriteAllBytes(FilePath, ProtectedData.Protect(json, null, DataProtectionScope.CurrentUser));
        }
        catch (Exception ex) { Log.Info($"Couldn't save the account sign-in: {ex.Message}"); }
    }

    private static Session? LoadSession()
    {
        try
        {
            if (!File.Exists(FilePath)) return null;
            var json = ProtectedData.Unprotect(File.ReadAllBytes(FilePath), null, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<Session>(json);
        }
        catch { return null; }
    }

    // ---------- Helpers ----------

    private static StringContent Json(JsonNode body) => new(body.ToJsonString(), Encoding.UTF8, "application/json");

    private static string Base64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string Challenge(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

    private static async Task<string> ErrorText(HttpResponseMessage res)
    {
        try
        {
            var j = JsonNode.Parse(await res.Content.ReadAsStringAsync());
            var msg = (string?)j?["msg"] ?? (string?)j?["error_description"] ?? (string?)j?["message"] ?? (string?)j?["error"];
            if (res.StatusCode == HttpStatusCode.TooManyRequests) return "Too many tries. Wait a minute and try again.";
            if (!string.IsNullOrWhiteSpace(msg)) return msg!;
        }
        catch { }
        return $"The sign-in server said {(int)res.StatusCode}.";
    }
}

public sealed class AccountException(string message) : Exception(message);
