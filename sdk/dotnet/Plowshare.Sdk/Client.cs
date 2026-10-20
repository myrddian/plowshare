using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Text.Json;
using System.Threading.Channels;

namespace Plowshare.Sdk;

public enum Delivery { NotSubmitted, Unknown, InvalidResponse }
public enum DispatchMode { Concurrent, Ordered }
public sealed class TransportException(Delivery delivery, string message) : IOException(message)
{
    public Delivery Delivery { get; } = delivery;
}
public sealed class RequestCancelledException(Delivery delivery, CancellationToken token)
    : OperationCanceledException("Request cancelled; delivery may be unknown; no request was replayed", token)
{
    public Delivery Delivery { get; } = delivery;
}
public sealed class RefusalException(string code, string? said) : Exception(said ?? code)
{
    public string Code { get; } = code;
    public string? Said { get; } = said;
}
/// <summary>An outcome paired with the operation's completely validated DTO.</summary>
public sealed class Reply<T>(string code, string? said, T payload)
{
    public string Code { get; } = code;
    public string? Said { get; } = said;
    public bool Successful => Code is "OK" or "CREATED" or "ACCEPTED" or "NO_CONTENT";
    public T RequirePayload() => Successful ? payload : throw new RefusalException(Code, Said);
}
internal sealed record WireOutcome(string Code, string? Said, JsonElement Payload);

/// <summary>One header-authenticated socket. No implicit reconnect or mutation replay.</summary>
public sealed class Client : IAsyncDisposable
{
    private sealed record Pending(string Operation, TaskCompletionSource<WireOutcome> Answer)
    {
        public volatile bool Submitted;
    }
    // Admission owns queued writers and retained responses, not only IDs already sent.
    private sealed class Admission : IDisposable
    {
        private readonly Client owner;
        internal Admission(Client owner)
        {
            this.owner = owner;
            ConnectionCancellation = owner.lifetime.Token;
        }
        private long bytes;
        private int released;
        private IDisposable? conversion;
        internal CancellationToken ConnectionCancellation { get; }
        private readonly long started = Stopwatch.GetTimestamp();
        internal TimeSpan Remaining => owner.timeout - Stopwatch.GetElapsedTime(started);
        internal async Task EnterConversionAsync(CancellationToken cancellationToken)
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, ConnectionCancellation);
            var remaining = Remaining;
            if (remaining <= TimeSpan.Zero) throw new TransportException(Delivery.NotSubmitted, "Request deadline expired before conversion");
            deadline.CancelAfter(remaining);
            try { conversion = await owner.converter.EnterAsync(deadline.Token); deadline.Token.ThrowIfCancellationRequested(); }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            { throw new RequestCancelledException(Delivery.NotSubmitted, cancellationToken); }
            catch (OperationCanceledException)
            { throw new TransportException(Delivery.NotSubmitted, "Request expired or connection closed before conversion"); }
        }
        internal void ReleaseConversion() => Interlocked.Exchange(ref conversion, null)?.Dispose();
        internal void Charge(long count)
        {
            lock (owner.gate)
            {
                if (owner.closed || count > 640L * 1024 * 1024 - owner.held)
                    throw new TransportException(Delivery.NotSubmitted, "Pending request byte capacity exceeded");
                owner.held += count; bytes = count;
            }
        }
        public void Dispose()
        {
            if (Interlocked.Exchange(ref released, 1) != 0) return;
            ReleaseConversion();
            TaskCompletionSource? drained;
            lock (owner.gate)
            {
                owner.admitted--; owner.held -= bytes;
                drained = owner.admitted == 0 ? owner.idle : null;
            }
            drained?.TrySetResult();
        }
    }
    private readonly ClientWebSocket socket;
    private readonly HttpMessageInvoker http;
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim writer = new(1, 1);
    private readonly ConversionGate converter = new();
    private readonly object gate = new();
    private readonly ConcurrentDictionary<string, Pending> pending = new();
    private int admitted;
    private int maximum = 64;
    private long held;
    // One shared completion per nonempty admission generation. Close stops new
    // admission before capturing it; continuations never execute under the gate.
    private TaskCompletionSource? idle;
    private readonly Channel<ServerPush> pushes = Channel.CreateBounded<ServerPush>(new BoundedChannelOptions(256) { FullMode = BoundedChannelFullMode.Wait, SingleWriter = true });
    private readonly TimeSpan timeout;
    private readonly Task reader;
    private readonly Packets? packets;
    private bool closed;
    private bool disposed;
    private readonly TaskCompletionSource disposal = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private long droppedPushes;
    public string Session { get; }
    public long DroppedPushes => Interlocked.Read(ref droppedPushes);
    public ChannelReader<ServerPush> Pushes => pushes.Reader;

    private Client(ClientWebSocket socket, HttpMessageInvoker http, string session, TimeSpan timeout)
    {
        this.socket = socket; this.http = http; Session = session; this.timeout = timeout;
        packets = socket.SubProtocol == Packets.Protocol ? new Packets(socket) : null;
        reader = ReadAsync();
    }

    public static async Task<Client> ConnectAsync(string origin, string token, string? session = null, TimeSpan? timeout = null, CancellationToken cancellationToken = default, bool legacyTransport = false, DispatchMode dispatchMode = DispatchMode.Concurrent)
    {
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var url) || url.Scheme is not ("http" or "https") || url.UserInfo != "" || url.AbsolutePath != "/" || url.Query != "" || url.Fragment != "")
            throw new ArgumentException("An HTTP(S) origin without credentials, path, query or fragment is required", nameof(origin));
        if (!Enum.IsDefined(dispatchMode)) throw new ArgumentException("Choose a dispatch mode", nameof(dispatchMode));
        var deadline = timeout ?? TimeSpan.FromSeconds(30);
        session ??= Guid.NewGuid().ToString();
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(session) || deadline <= TimeSpan.Zero || deadline.TotalMilliseconds > int.MaxValue)
            throw new ArgumentException("Token, session and a positive finite timeout are required");
        var address = new UriBuilder(url) { Scheme = url.Scheme == "https" ? "wss" : "ws", Path = "/v1/events", Query = "session=" + Uri.EscapeDataString(session), Port = url.Port }.Uri;
        var ws = new ClientWebSocket();
        var http = new HttpMessageInvoker(new SocketsHttpHandler { AllowAutoRedirect = false });
        ws.Options.SetRequestHeader("Authorization", "Bearer " + token);
        if (!legacyTransport) ws.Options.AddSubProtocol(Packets.Protocol);
        using var opening = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        opening.CancelAfter(deadline);
        try { await ws.ConnectAsync(address, http, opening.Token); }
        catch { ws.Dispose(); http.Dispose(); throw new TransportException(Delivery.NotSubmitted, "Plowshare WebSocket upgrade failed; no application request was submitted"); }
        if (!legacyTransport && ws.SubProtocol != Packets.Protocol) { ws.Abort(); ws.Dispose(); http.Dispose(); throw new TransportException(Delivery.NotSubmitted, "Server does not support segmented transport; explicitly select legacy mode"); }
        var client = new Client(ws, http, session, deadline);
        if (dispatchMode == DispatchMode.Concurrent)
        {
            try
            {
                using var admission = client.Admit();
                await admission.EnterConversionAsync(opening.Token);
                var reply = await client.RequestWireAsync("transport.dispatch", JsonSerializer.SerializeToElement(new {mode = "concurrent-v1"}), opening.Token, null, admission);
                if (reply.Code != "OK" || reply.Payload.ValueKind != JsonValueKind.Object || Text(reply.Payload, "mode") != "concurrent-v1" ||
                    !reply.Payload.TryGetProperty("maxPending", out var count) || count.ValueKind != JsonValueKind.Number || !count.TryGetInt32(out var maximum) || maximum < 1 || maximum > 64 ||
                    !reply.Payload.TryGetProperty("maxIdBytes", out var size) || size.ValueKind != JsonValueKind.Number || !size.TryGetInt32(out var idBytes) || idBytes != 128)
                    throw new TransportException(Delivery.NotSubmitted, "Server did not acknowledge concurrent dispatch; select ordered mode explicitly");
                client.maximum = maximum;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            { await client.DisposeAsync(); throw new RequestCancelledException(Delivery.NotSubmitted, cancellationToken); }
            catch { await client.DisposeAsync(); throw new TransportException(Delivery.NotSubmitted, "Concurrent dispatch negotiation failed; no application request was submitted; select ordered mode explicitly"); }
        }
        return client;
    }

    private Admission Admit()
    {
        lock (gate)
        {
            if (closed || admitted >= maximum) throw new TransportException(Delivery.NotSubmitted, "Connection closed or request capacity exceeded");
            if (admitted == 0) idle = new(TaskCreationOptions.RunContinuationsAsynchronously);
            admitted++;
            return new Admission(this);
        }
    }

    internal async Task<Reply<TResponse>> RequestTypedAsync<TRequest, TResponse>(string operation, TRequest request, CancellationToken cancellationToken, RequestOptions? options = null)
    {
        using var admission = Admit();
        await admission.EnterConversionAsync(cancellationToken);
        // Validate before a write; count admission also bounds concurrent conversion/writer waiters.
        var body = Codec.Input(operation, request);
        var outcome = await RequestWireAsync(operation, body, cancellationToken, options, admission);
        if (outcome.Code is not ("OK" or "CREATED" or "ACCEPTED" or "NO_CONTENT"))
            return new Reply<TResponse>(outcome.Code, outcome.Said, default!);
        try { var result = Codec.Result<TResponse>(operation, outcome.Payload); Codec.CorrelateDeployment(operation, body, outcome.Payload); return new Reply<TResponse>(outcome.Code, outcome.Said, result); }
        catch (JsonException) { throw new TransportException(Delivery.InvalidResponse, "Unreadable response; outcome is unknown"); }
    }

    private async Task<WireOutcome> RequestWireAsync(string operation, JsonElement body, CancellationToken cancellationToken, RequestOptions? options, Admission admission)
    {
        var id = Guid.NewGuid().ToString();
        if (options is not null) ArgumentNullException.ThrowIfNull(options.ActingFor);
        var wire = options is null
            ? JsonSerializer.SerializeToUtf8Bytes(new { id, type = operation, protocol_version = Protocol.Version, payload = body })
            : JsonSerializer.SerializeToUtf8Bytes(new { id, type = operation, protocol_version = Protocol.Version, payload = body, actingFor = new { context = options.ActingFor.Context.ToString() } });
        if (wire.Length == 0 || wire.Length > (packets is null ? 1048576 : Packets.MaxMessage)) throw new TransportException(Delivery.NotSubmitted, "Encoded request exceeds negotiated allowance");
        admission.Charge(wire.Length);
        admission.ReleaseConversion();
        using var reservation = packets?.ReserveOutgoing(wire.Length);
        var answer = new TaskCompletionSource<WireOutcome>(TaskCreationOptions.RunContinuationsAsynchronously);
        var waiting = new Pending(operation, answer);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, admission.ConnectionCancellation);
        var remaining = admission.Remaining;
        if (remaining <= TimeSpan.Zero) throw new TransportException(Delivery.NotSubmitted, "Request deadline expired before writer admission");
        deadline.CancelAfter(remaining);
        try
        {
            lock (gate)
            {
                if (closed) throw new TransportException(Delivery.NotSubmitted, "Connection closed before writer admission");
                if (!pending.TryAdd(id, waiting)) throw new TransportException(Delivery.NotSubmitted, "Request identity collision");
            }
            await writer.WaitAsync(deadline.Token);
            try
            {
                if (closed) throw new TransportException(Delivery.NotSubmitted, "Connection closed before submission");
                deadline.Token.ThrowIfCancellationRequested();
                if (packets is not null) await packets.SendAsync(wire, deadline.Token, () => { waiting.Submitted = true; });
                else { waiting.Submitted = true; await socket.SendAsync(wire.AsMemory(), WebSocketMessageType.Text, true, deadline.Token); }
            }
            finally { writer.Release(); }
            return await answer.Task.WaitAsync(deadline.Token);
        }
        catch (TransportException) { throw; }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        { throw new RequestCancelledException(waiting.Submitted ? Delivery.Unknown : Delivery.NotSubmitted, cancellationToken); }
        catch { throw new TransportException(waiting.Submitted ? Delivery.Unknown : Delivery.NotSubmitted, "Request did not obtain a readable reply; no request was replayed"); }
        finally { pending.TryRemove(id, out _); }
    }

    private static string? Text(JsonElement value, string field) => value.TryGetProperty(field, out var item) && item.ValueKind == JsonValueKind.String ? item.GetString() : null;
    private async Task ReadAsync()
    {
        var buffer = new byte[8192];
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                using var message = new MemoryStream();
                ValueWebSocketReceiveResult received;
                do
                {
                    received = await socket.ReceiveAsync(buffer.AsMemory(), lifetime.Token);
                    if (received.MessageType == WebSocketMessageType.Close) return;
                    if (message.Length + received.Count > 1048576) throw new IOException("Response exceeds 1 MiB");
                    message.Write(buffer, 0, received.Count);
                } while (!received.EndOfMessage);
                if (received.MessageType != WebSocketMessageType.Text) continue;
                var wire = message.ToArray();
                if (packets is not null) { var assembled = await packets.AcceptAsync(wire, lifetime.Token); if (assembled is null) continue; wire = assembled; }
                JsonDocument json;
                try { json = JsonDocument.Parse(wire); } catch (JsonException) { continue; }
                using (json)
                {
                    var frame = json.RootElement;
                    if (frame.ValueKind != JsonValueKind.Object) continue;
                    if (!frame.TryGetProperty("protocol_version", out _) || frame.TryGetProperty("id", out var pushId) && pushId.ValueKind == JsonValueKind.Null && Text(frame, "protocol_version") == Protocol.Version)
                    {
                        try { if (!pushes.Writer.TryWrite(Codec.Push(frame))) Interlocked.Increment(ref droppedPushes); }
                        catch (JsonException) { /* Invalid hints never reach consumers; reconcile durable reads. */ }
                        continue;
                    }
                    var id = Text(frame, "id");
                    Pending? waiting;
                    if (id is null || !pending.TryRemove(id, out waiting)) continue;
                    var hasBody = frame.TryGetProperty("payload", out var body);
                    var valid = Text(frame, "protocol_version") == Protocol.Version && Text(frame, "type") == waiting.Operation
                        && hasBody && body.ValueKind == JsonValueKind.Object;
                    valid = valid && Text(body, "code") is string code && Protocol.IsCode(code)
                        && (!body.TryGetProperty("said", out var said) || said.ValueKind is JsonValueKind.Null or JsonValueKind.String);
                    if (valid) waiting.Answer.TrySetResult(new WireOutcome(Text(body, "code")!, Text(body, "said"), body.TryGetProperty("payload", out var result) ? result.Clone() : default));
                    else waiting.Answer.TrySetException(new TransportException(Delivery.InvalidResponse, "Unreadable response; outcome is unknown"));
                }
            }
        }
        catch { /* Uncorrelated transport failure cannot establish a mutation outcome. */ }
        finally
        {
            packets?.Dispose();
            if (packets is not null) socket.Abort();
            Pending[] stranded;
            lock (gate)
            {
                closed = true;
                stranded = pending.Values.ToArray();
                pending.Clear();
            }
            // EOF also cancels converters/writers that have not reached pending.
            // The reader must never wait for its own admission drain.
            lifetime.Cancel();
            foreach (var waiting in stranded)
                waiting.Answer.TrySetException(new TransportException(waiting.Submitted ? Delivery.Unknown : Delivery.NotSubmitted, "Socket closed before reply; no request was replayed"));
            pushes.Writer.TryComplete();
        }
    }

    /// <summary>Stops admission and settles outstanding requests, then waits for
    /// admitted conversion/writer and byte ownership to drain. Repeated disposal
    /// observes the same shutdown without waiting for application continuations.</summary>
    public async ValueTask DisposeAsync()
    {
        bool ownsDrain;
        Task transportIdle;
        lock (gate)
        {
            ownsDrain = !disposed;
            if (ownsDrain) { disposed = true; closed = true; }
            transportIdle = idle?.Task ?? Task.CompletedTask;
        }
        if (!ownsDrain) { await disposal.Task; return; }
        try
        {
            packets?.Dispose(); lifetime.Cancel(); socket.Abort();
            await reader;
            // Includes queued conversions and physical writers/byte leases, but
            // excludes caller continuations after RequestTypedAsync has settled.
            await transportIdle;
            socket.Dispose(); http.Dispose(); lifetime.Dispose();
            disposal.TrySetResult();
        }
        catch (Exception failure) { disposal.TrySetException(failure); throw; }
    }
}

/// <summary>Explicit selection on one request; never changes the socket's actor.</summary>
public sealed record ActingSelection(Guid Context);
public sealed record RequestOptions(ActingSelection ActingFor);
