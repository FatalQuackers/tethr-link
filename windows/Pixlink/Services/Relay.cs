using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;

namespace Pixlink.Services;

/// <summary>
/// Connect from anywhere: when the phone can't reach this PC on Wi-Fi or Bluetooth (school or work
/// Wi-Fi, mobile data), it goes through the Tethr Link relay on Cloudflare instead.
///
/// The PC keeps one idle WebSocket open in its room. When the phone joins, the relay says "start"
/// and the normal TLS link runs over the socket, with the same certificate pinning as on Wi-Fi,
/// so the relay only ever sees encrypted bytes. The room's name is a hash of a secret that only
/// this PC and its paired phone know (sent to the phone over the encrypted link in pc.info).
/// </summary>
public sealed class Relay
{
    /// <summary>The relay Worker (relay/ in the repo, deployed on Cloudflare's free plan).</summary>
    public const string Host = "tethr-relay.shadowledgendog.workers.dev";

    private readonly LinkServer _link;
    private readonly Func<string?> _key;
    private readonly Func<bool> _enabled;

    public Relay(LinkServer link, Func<string?> key, Func<bool> enabled)
    {
        _link = link;
        _key = key;
        _enabled = enabled;
    }

    public static string NewKey() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>Room name for a key: the relay never learns the key itself.</summary>
    public static string RoomFor(string key) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("tethr-relay:" + key))).ToLowerInvariant();

    private CancellationTokenSource? _wait;

    /// <summary>Network changed (new Wi-Fi, Wi-Fi back after a dropout): try the relay again now, not after the backoff.</summary>
    public void Wake() { try { _wait?.Cancel(); } catch { } }

    private async Task WaitAsync(TimeSpan delay, CancellationToken ct)
    {
        _wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
        try { await Task.Delay(delay, _wait.Token); }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }
    }

    public async Task RunAsync(CancellationToken ct)
    {
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += (_, _) => Wake();
        System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged += (_, _) => Wake();
        var backoff = TimeSpan.FromSeconds(5);
        var logged = false;
        while (!ct.IsCancellationRequested)
        {
            var key = _key();
            if (key is null || !_enabled() || Host.Contains("RELAY_SUBDOMAIN"))
            {
                await Task.Delay(TimeSpan.FromSeconds(3), ct); // quick: the pairing QR may open any moment
                continue;
            }
            var ws = new ClientWebSocket();
            ws.Options.KeepAliveInterval = TimeSpan.Zero; // our own "ping" text keeps it alive for free
            try
            {
                await ws.ConnectAsync(new Uri($"wss://{Host}/r/{RoomFor(key)}?role=pc"), ct);
                if (!logged) { Log.Info("Ready for your phone over the internet (relay)"); logged = true; }
                backoff = TimeSpan.FromSeconds(5);
                var stream = new WebSocketStream(ws);
                using var pinger = CancellationTokenSource.CreateLinkedTokenSource(ct);
                _ = PingAsync(stream, pinger.Token);
                if (!await stream.WaitForStartAsync(ct)) continue; // relay closed the idle socket: reconnect
                Log.Info("Phone connecting over the internet");
                await _link.AcceptAsync(stream, stream, "internet", ct);
                pinger.Cancel();
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                if (logged) Log.Info($"Internet connections paused: {ex.GetBaseException().Message}");
                logged = false;
                try { await WaitAsync(backoff, ct); } catch { return; }
                // School networks drop out often: keep retrying at least every minute.
                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 60));
            }
            finally { ws.Dispose(); }
        }
    }

    private static async Task PingAsync(WebSocketStream s, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(50), ct);
                await s.SendTextAsync("ping", ct);
            }
        }
        catch { }
    }

    /// <summary>Binary WebSocket messages as a byte stream for SslStream. Text messages are relay control.</summary>
    private sealed class WebSocketStream(ClientWebSocket ws) : Stream
    {
        private readonly SemaphoreSlim _send = new(1, 1);
        private readonly byte[] _buf = new byte[64 * 1024];
        private int _pos, _len;
        private bool _closed;

        public async Task<bool> WaitForStartAsync(CancellationToken ct)
        {
            while (true)
            {
                var r = await ws.ReceiveAsync(_buf, ct);
                if (r.MessageType == WebSocketMessageType.Close) return false;
                if (r.MessageType == WebSocketMessageType.Text && Encoding.UTF8.GetString(_buf, 0, r.Count) == "start") return true;
                if (r.MessageType == WebSocketMessageType.Binary) { _pos = 0; _len = r.Count; return true; }
            }
        }

        public async Task SendTextAsync(string text, CancellationToken ct)
        {
            await _send.WaitAsync(ct);
            try { await ws.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct); }
            finally { _send.Release(); }
        }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            while (_pos >= _len)
            {
                if (_closed) return 0;
                var r = await ws.ReceiveAsync(_buf, ct);
                if (r.MessageType == WebSocketMessageType.Close) { _closed = true; return 0; }
                if (r.MessageType == WebSocketMessageType.Text) continue; // "pong", "start"
                _pos = 0;
                _len = r.Count;
            }
            var n = Math.Min(buffer.Length, _len - _pos);
            _buf.AsMemory(_pos, n).CopyTo(buffer);
            _pos += n;
            return n;
        }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
        {
            await _send.WaitAsync(ct);
            try { await ws.SendAsync(buffer, WebSocketMessageType.Binary, true, ct); }
            finally { _send.Release(); }
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) => ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();
        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) => WriteAsync(buffer.AsMemory(offset, count), ct).AsTask();
        public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer, offset, count, default).GetAwaiter().GetResult();
        public override void Write(byte[] buffer, int offset, int count) => WriteAsync(buffer, offset, count, default).GetAwaiter().GetResult();
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override Task FlushAsync(CancellationToken ct) => Task.CompletedTask;
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                try { ws.Abort(); } catch { }
                ws.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
