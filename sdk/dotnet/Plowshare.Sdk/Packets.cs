using System.Diagnostics;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Plowshare.Sdk;

/// <summary>Connection-scoped byte framing. Credits permit the next segment, never a domain effect.</summary>
internal sealed class Packets : IDisposable
{
    internal const string Protocol = "plowshare-segments-v1";
    internal const int Chunk = 65536, MaxMessage = 320 * 1024 * 1024, MaxPacket = 128 * 1024;
    private static readonly object processGate = new();
    private static long processHeld;
    private readonly object gate = new();
    private readonly SemaphoreSlim writer = new(1, 1);
    private readonly ClientWebSocket socket;
    private readonly Timer expiry;
    private readonly Dictionary<string, Assembly> incoming = [];
    private readonly HashSet<string> completed = [];
    private long held;
    private bool closed;
    private string? sending;
    private int expected;
    private TaskCompletionSource? credit;
    private sealed record Segment(string Transfer, int Number, int Count, int Offset, int Total, string Hash, byte[] Bytes);
    private sealed class Assembly(Segment first)
    {
        internal readonly Segment First = first;
        internal readonly byte[] Bytes = new byte[first.Total];
        internal readonly HashSet<int> Ranges = [];
        internal readonly long Started = Stopwatch.GetTimestamp();
        internal long Touched = Stopwatch.GetTimestamp();
    }
    internal Packets(ClientWebSocket socket)
    {
        this.socket = socket;
        expiry = new Timer(_ => Expire(), null, 1000, 1000);
    }
    private void Reserve(int bytes)
    {
        lock (processGate)
        {
            if (bytes > 640L * 1024 * 1024 - held || bytes * 3L > 2L * 1024 * 1024 * 1024 - processHeld)
                throw new TransportException(Delivery.NotSubmitted, "Packet memory capacity exceeded before submission");
            held += bytes; processHeld += bytes * 3L;
        }
    }
    private void Release(int bytes) { lock (processGate) { held -= bytes; processHeld -= bytes * 3L; } }
    private async Task WriteAsync<T>(T packet, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(packet);
        await writer.WaitAsync(cancellationToken);
        try { await socket.SendAsync(bytes.AsMemory(), WebSocketMessageType.Text, true, cancellationToken); }
        finally { writer.Release(); }
    }
    // The lease starts before waiting for the logical writer and ends when the caller drops its
    // encoded request. This keeps queued request buffers inside the same aggregate allowance.
    internal IDisposable ReserveOutgoing(int size)
    {
        lock (gate) { if (closed) throw new TransportException(Delivery.NotSubmitted, "Packet connection closed"); Reserve(size); }
        return new Reservation(this, size);
    }
    private sealed class Reservation(Packets owner, int size) : IDisposable
    {
        private Packets? heldBy = owner;
        public void Dispose()
        {
            var held = Interlocked.Exchange(ref heldBy, null);
            if (held is not null) lock (held.gate) held.Release(size);
        }
    }
    // The Client owns the logical writer; this class's writer only locks small physical messages.
    internal async Task SendAsync(byte[] bytes, CancellationToken cancellationToken)
    {
        lock (gate) { if (closed) throw new IOException("Packet connection closed"); }
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(60));
        try
        {
            var id = Guid.NewGuid().ToString();
            var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var count = (bytes.Length + Chunk - 1) / Chunk;
            for (var number = 1; number <= count; number++)
            {
                var offset = (number - 1) * Chunk;
                TaskCompletionSource received = new(TaskCreationOptions.RunContinuationsAsynchronously);
                lock (gate) { if (closed) throw new IOException("Packet connection closed"); sending = id; expected = number; credit = received; }
                await WriteAsync(new { kind = "transport.segment", version = 1, transferId = id, segmentNumber = number, segmentCount = count, byteOffset = offset, totalBytes = bytes.Length, sha256 = hash, data = Convert.ToBase64String(bytes, offset, Math.Min(Chunk, bytes.Length - offset)) }, deadline.Token);
                await received.Task.WaitAsync(TimeSpan.FromSeconds(15), deadline.Token);
            }
        }
        catch { Dispose(); socket.Abort(); throw; }
        finally { lock (gate) { sending = null; credit = null; } }
    }
    private static string Text(JsonElement frame, string key)
        => frame.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString()! : throw new IOException("Invalid packet text");
    private static int Int(JsonElement frame, string key)
        => frame.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var result) ? result : throw new IOException("Invalid packet integer");
    private static string Identity(JsonElement frame)
    {
        var value = Text(frame, "transferId");
        return Guid.TryParseExact(value, "D", out var parsed) && parsed.ToString() == value ? value : throw new IOException("Invalid transfer identity");
    }
    private static void Fields(JsonElement frame, params string[] expectedFields)
    {
        if (frame.ValueKind != JsonValueKind.Object) throw new IOException("Invalid packet object");
        HashSet<string> seen = [];
        foreach (var field in frame.EnumerateObject())
            if (!seen.Add(field.Name) || !expectedFields.Contains(field.Name)) throw new IOException("Invalid packet fields");
        if (seen.Count != expectedFields.Length) throw new IOException("Missing packet field");
    }
    internal async Task<byte[]?> AcceptAsync(byte[] wire, CancellationToken cancellationToken)
    {
        if (wire.Length > MaxPacket) throw new IOException("Oversized packet");
        using var json = JsonDocument.Parse(wire);
        var frame = json.RootElement;
        var kind = Text(frame, "kind");
        if (kind == "transport.credit")
        {
            Fields(frame, "kind", "version", "transferId", "segmentNumber");
            var id = Identity(frame); var number = Int(frame, "segmentNumber");
            lock (gate)
            {
                if (closed || Int(frame, "version") != 1 || id != sending || number != expected || credit is null) throw new IOException("Unexpected packet credit");
                credit.TrySetResult();
            }
            return null;
        }
        Fields(frame, "kind", "version", "transferId", "segmentNumber", "segmentCount", "byteOffset", "totalBytes", "sha256", "data");
        var total = Int(frame, "totalBytes"); var count = Int(frame, "segmentCount"); var index = Int(frame, "segmentNumber"); var offset = Int(frame, "byteOffset");
        var hash = Text(frame, "sha256"); var data = Text(frame, "data"); var transfer = Identity(frame);
        if (kind != "transport.segment" || Int(frame, "version") != 1 || total <= 0 || total > MaxMessage || count != (total + Chunk - 1) / Chunk || index < 1 || index > count || offset != (index - 1) * Chunk || hash.Length != 64 || hash.Any(c => !"0123456789abcdef".Contains(c)) || data.Length > 87384) throw new IOException("Invalid segment metadata");
        var decoded = Convert.FromBase64String(data);
        if (decoded.Length != Math.Min(Chunk, total - offset) || Convert.ToBase64String(decoded) != data) throw new IOException("Invalid segment bytes");
        var part = new Segment(transfer, index, count, offset, total, hash, decoded);
        byte[]? message = null;
        lock (gate)
        {
            if (closed || completed.Contains(transfer)) throw new IOException("Completed transfer identity reused");
            if (!incoming.TryGetValue(transfer, out var assembly))
            {
                if (incoming.Count >= 4 || incoming.Count + completed.Count >= 4096) throw new IOException("Transfer capacity exceeded");
                Reserve(total); assembly = new Assembly(part); incoming.Add(transfer, assembly);
            }
            if ((total, count, hash) != (assembly.First.Total, assembly.First.Count, assembly.First.Hash)) throw new IOException("Conflicting transfer metadata");
            if (assembly.Ranges.Contains(index))
            {
                if (!assembly.Bytes.AsSpan(offset, decoded.Length).SequenceEqual(decoded)) throw new IOException("Conflicting duplicate range");
            }
            else { decoded.CopyTo(assembly.Bytes, offset); assembly.Ranges.Add(index); }
            assembly.Touched = Stopwatch.GetTimestamp();
            if (assembly.Ranges.Count == count)
            {
                if (Convert.ToHexString(SHA256.HashData(assembly.Bytes)).ToLowerInvariant() != hash) throw new IOException("Transfer hash mismatch");
                // Check UTF-8 before forwarding the original bytes to the ordinary response codec.
                _ = new UTF8Encoding(false, true).GetCharCount(assembly.Bytes);
                message = assembly.Bytes; incoming.Remove(transfer); Release(total); Reserve(256); completed.Add(transfer);
            }
        }
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        await WriteAsync(new { kind = "transport.credit", version = 1, transferId = transfer, segmentNumber = index }, deadline.Token);
        return message;
    }
    private void Expire()
    {
        bool stale;
        lock (gate) stale = !closed && incoming.Values.Any(a => Stopwatch.GetElapsedTime(a.Started) >= TimeSpan.FromSeconds(60) || Stopwatch.GetElapsedTime(a.Touched) >= TimeSpan.FromSeconds(15));
        if (stale) { Dispose(); socket.Abort(); }
    }
    public void Dispose()
    {
        lock (gate)
        {
            if (closed) return;
            closed = true; expiry.Dispose();
            foreach (var assembly in incoming.Values) Release(assembly.First.Total);
            incoming.Clear(); Release(completed.Count * 256); completed.Clear(); credit?.TrySetException(new IOException("Packet connection closed"));
        }
    }
}
