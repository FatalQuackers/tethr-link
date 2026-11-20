using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace Pixlink.Services;

/// <summary>
/// Second internet route, through Supabase Realtime (the same service as the optional account).
/// Some networks block Cloudflare's workers.dev addresses but allow Supabase, which many school
/// and work apps use.
///
/// The PC joins a private broadcast room whose name is a hash of the relay secret. When the
/// phone says "hello", the PC answers "ready" and the normal TLS link runs over broadcast
/// messages (base64, numbered so they're put back in order). Supabase only sees encrypted bytes.
/// </summary>
public sealed class SupabaseRelay(LinkServer link, Func<string?> key, Func<bool> enabled)
{
    private static readonly string Url =
        $"wss://zljbvimcfrpeouscccvw.supabase.co/realtime/v1/websocket?apikey={Account.Key}&vsn=1.0.0";
    private const int ChunkBytes = 32 * 1024;

    public static string TopicFor(string key) =>
        "realtime:tethr-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("tethr-rt:" + key))).ToLowerInvariant()[..40];

    private ClientWebSocket? _ws;
    private string _topic = "";
    private readonly SemaphoreSlim _send = new(1, 1);
    private int _ref;
    private Session? _session;
    private CancellationTokenSource? _wait;

    public void Wake() { try { _wait?.Cancel(); } catch { } }

    public async Task RunAsync(CancellationToken ct)
    {
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += (_, _) => Wake();
        var backoff = TimeSpan.FromSeconds(5);
        var logged = false;
        while (!ct.IsCancellationRequested)
        {
            var k = key();
            if (k is null || !enabled()) { await Task.Delay(TimeSpan.FromSeconds(3), ct); continue; }
            using var ws = new ClientWebSocket();
            ws.Options.KeepAliveInterval = TimeSpan.Zero;
            _ws = ws;
            _topic = TopicFor(k);
            using var conn = CancellationTokenSource.CreateLinkedTokenSource(ct);
            try
            {
                await ws.ConnectAsync(new Uri(Url), ct);
                await SendAsync(new JsonObject
                {
                    ["topic"] = _topic, ["event"] = "phx_join", ["ref"] = "1", ["join_ref"] = "1",
                    ["payload"] = new JsonObject
                    {
                        ["config"] = new JsonObject
                        {
                            ["broadcast"] = new JsonObject { ["self"] = false, ["ack"] = false },
                            ["presence"] = new JsonObject { ["key"] = "" },
                            ["private"] = false,
                        },
                    },
                });
                _ = HeartbeatAsync(conn.Token);
                await ReceiveLoopAsync(ws, () =>
                {
                    if (!logged) { Log.Info("Ready for your phone over the internet (Supabase route)"); logged = true; }
                    backoff = TimeSpan.FromSeconds(5);
                }, ct);
                throw new WebSocketException("Supabase closed the connection");
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                if (logged) Log.Info($"Supabase route paused: {ex.GetBaseException().Message}");
                logged = false;
                _session?.End();
                _session = null;
                conn.Cancel();
                _wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
                try { await Task.Delay(backoff, _wait.Token); } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { } catch { return; }
                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 60));
            }
            finally { _ws = null; }
        }
    }

    private async Task HeartbeatAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(25), ct);
                await SendAsync(new JsonObject { ["topic"] = "phoenix", ["event"] = "heartbeat", ["payload"] = new JsonObject(), ["ref"] = NextRef() });
            }
        }
        catch { }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket ws, Action joined, CancellationToken ct)
    {
        var buf = new byte[256 * 1024];
        using var msg = new MemoryStream();
        while (ws.State == WebSocketState.Open)
        {
            var r = await ws.ReceiveAsync(buf, ct);
            if (r.MessageType == WebSocketMessageType.Close) return;
            msg.Write(buf, 0, r.Count);
            if (!r.EndOfMessage) continue;
            var text = Encoding.UTF8.GetString(msg.GetBuffer(), 0, (int)msg.Length);
            msg.SetLength(0);
            if (JsonNode.Parse(text) is not JsonObject m) continue;
            var ev = (string?)m["event"];
            if (ev == "phx_reply" && (string?)m["ref"] == "1")
            {
                if ((string?)m["payload"]?["status"] == "ok") joined();
                else throw new InvalidOperationException("Supabase wouldn't let this PC join its room");
            }
            else if (ev == "broadcast" && m["payload"]?["payload"] is JsonObject p && (string?)p["f"] == "phone")
            {
                Handle(p, ct);
            }
            else if (ev is "phx_error" or "phx_close") return;
        }
    }

    private void Handle(JsonObject p, CancellationToken ct)
    {
        var sid = (string?)p["s"];
        if (sid is null) return;
        switch ((string?)p["t"])
        {
            case "hello":
                _session?.End();
                var s = new Session(this, sid);
                _session = s;
                Log.Info("Phone connecting over the internet (Supabase route)");
                _ = SendDataAsync(sid, "ready", 0, null);
                _ = Task.Run(async () =>
                {
                    try { await link.AcceptAsync(s, s, "internet", ct); }
                    catch { }
                    finally
                    {
                        if (_session == s) _session = null;
                        _ = SendDataAsync(sid, "bye", 0, null);
                    }
                });
                break;
            case "d":
                if (_session is { } cur && cur.Id == sid && (string?)p["b"] is { } b && p["n"] is { } n)
                    cur.Deliver((long)n, Convert.FromBase64String(b));
                break;
            case "bye":
                if (_session is { } live && live.Id == sid) { live.End(); _session = null; }
                break;
        }
    }

    private string NextRef() => Interlocked.Increment(ref _ref).ToString();

    internal Task SendDataAsync(string sid, string type, long seq, ReadOnlyMemory<byte>? data)
    {
        var inner = new JsonObject { ["s"] = sid, ["f"] = "pc", ["t"] = type, ["n"] = seq };
        if (data is { } d) inner["b"] = Convert.ToBase64String(d.Span);
        return SendAsync(new JsonObject
        {
            ["topic"] = _topic, ["event"] = "broadcast", ["ref"] = NextRef(), ["join_ref"] = "1",
            ["payload"] = new JsonObject { ["type"] = "broadcast", ["event"] = "m", ["payload"] = inner },
        });
    }

    private async Task SendAsync(JsonObject m)
    {
        var ws = _ws ?? throw new WebSocketException("Not connected");
        var bytes = Encoding.UTF8.GetBytes(m.ToJsonString());
        await _send.WaitAsync();
        try { await ws.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None); }
        finally { _send.Release(); }
    }

    /// <summary>One phone session as a byte stream for SslStream, put back in order by sequence number.</summary>
    private sealed class Session(SupabaseRelay relay, string id) : Stream
    {
        public string Id { get; } = id;
        private readonly Channel<byte[]> _in = Channel.CreateUnbounded<byte[]>();
        private readonly ConcurrentDictionary<long, byte[]> _early = new();
        private readonly object _order = new();
        private long _nextIn = 1, _nextOut = 1;
        private byte[]? _cur;
        private int _pos;

        public void Deliver(long seq, byte[] data)
        {
            lock (_order)
            {
                if (seq < _nextIn) return;
                _early[seq] = data;
                while (_early.TryRemove(_nextIn, out var next)) { _in.Writer.TryWrite(next); _nextIn++; }
            }
        }

        public void End() => _in.Writer.TryComplete();

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            while (_cur is null || _pos >= _cur.Length)
            {
                if (!await _in.Reader.WaitToReadAsync(ct)) return 0;
                if (_in.Reader.TryRead(out var next)) { _cur = next; _pos = 0; }
            }
            var n = Math.Min(buffer.Length, _cur.Length - _pos);
            _cur.AsMemory(_pos, n).CopyTo(buffer);
            _pos += n;
            return n;
        }

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
        {
            for (var off = 0; off < buffer.Length; off += ChunkBytes)
            {
                var part = buffer.Slice(off, Math.Min(ChunkBytes, buffer.Length - off));
                long seq;
                lock (_order) seq = _nextOut++;
                await relay.SendDataAsync(Id, "d", seq, part);
            }
        }

        public override Task<int> ReadAsync(byte[] b, int o, int c, CancellationToken ct) => ReadAsync(b.AsMemory(o, c), ct).AsTask();
        public override Task WriteAsync(byte[] b, int o, int c, CancellationToken ct) => WriteAsync(b.AsMemory(o, c), ct).AsTask();
        public override int Read(byte[] b, int o, int c) => ReadAsync(b, o, c, default).GetAwaiter().GetResult();
        public override void Write(byte[] b, int o, int c) => WriteAsync(b, o, c, default).GetAwaiter().GetResult();
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override Task FlushAsync(CancellationToken ct) => Task.CompletedTask;
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { End(); base.Dispose(disposing); }
    }
}
