using System.Net.WebSockets;
using System.Text.Json;
using System.Threading.Channels;

namespace Plowshare.Sdk;

public enum Delivery { NotSubmitted, Unknown, InvalidResponse }
public sealed class TransportException(Delivery delivery, string message) : IOException(message)
{
    public Delivery Delivery { get; } = delivery;
}
public sealed class RequestCancelledException(Delivery delivery, CancellationToken token)
    : OperationCanceledException("Request cancelled; delivery may be unknown; no request was replayed", token)
{
    public Delivery Delivery { get; } = delivery;
}
public sealed class RefusalException(Reply reply) : Exception(reply.Outcome.TryGetProperty("said", out var said) && said.ValueKind == JsonValueKind.String ? said.GetString() : reply.Code)
{
    public Reply Reply { get; } = reply;
}
public sealed record Reply(JsonElement Raw)
{
    public JsonElement Outcome => Raw.GetProperty("payload");
    public string Code => Outcome.GetProperty("code").GetString()!;
    public bool Successful => Code is "OK" or "CREATED" or "ACCEPTED" or "NO_CONTENT";
    public JsonElement RequirePayload()
    {
        if (!Successful) throw new RefusalException(this);
        if (!Outcome.TryGetProperty("payload", out var payload) || payload.ValueKind == JsonValueKind.Null)
            throw new TransportException(Delivery.InvalidResponse, "Successful response omitted a required payload; outcome is unresolved");
        return payload;
    }
}

/// <summary>One header-authenticated socket. No implicit reconnect or mutation replay.</summary>
public sealed class Client : IAsyncDisposable
{
    private sealed record Pending(string Operation, TaskCompletionSource<Reply> Answer);
    private readonly ClientWebSocket socket;
    private readonly HttpMessageInvoker http;
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim writer = new(1, 1);
    private readonly object gate = new();
    private readonly Dictionary<string, Pending> pending = [];
    private readonly Channel<JsonElement> pushes = Channel.CreateBounded<JsonElement>(new BoundedChannelOptions(256) { FullMode = BoundedChannelFullMode.Wait, SingleWriter = true });
    private readonly TimeSpan timeout;
    private readonly Task reader;
    private bool closed;
    private bool disposed;
    private long droppedPushes;
    public string Session { get; }
    public long DroppedPushes => Interlocked.Read(ref droppedPushes);
    public ChannelReader<JsonElement> Pushes => pushes.Reader;

    private Client(ClientWebSocket socket, HttpMessageInvoker http, string session, TimeSpan timeout)
    {
        this.socket = socket; this.http = http; Session = session; this.timeout = timeout;
        reader = ReadAsync();
    }

    public static async Task<Client> ConnectAsync(string origin, string token, string? session = null, TimeSpan? timeout = null, CancellationToken cancellationToken = default)
    {
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var url) || url.Scheme is not ("http" or "https") || url.UserInfo != "" || url.AbsolutePath != "/" || url.Query != "" || url.Fragment != "")
            throw new ArgumentException("An HTTP(S) origin without credentials, path, query or fragment is required", nameof(origin));
        var deadline = timeout ?? TimeSpan.FromSeconds(30);
        session ??= Guid.NewGuid().ToString();
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(session) || deadline <= TimeSpan.Zero || deadline.TotalMilliseconds > int.MaxValue)
            throw new ArgumentException("Token, session and a positive finite timeout are required");
        var address = new UriBuilder(url) { Scheme = url.Scheme == "https" ? "wss" : "ws", Path = "/v1/events", Query = "session=" + Uri.EscapeDataString(session), Port = url.Port }.Uri;
        var ws = new ClientWebSocket();
        var http = new HttpMessageInvoker(new SocketsHttpHandler { AllowAutoRedirect = false });
        ws.Options.SetRequestHeader("Authorization", "Bearer " + token);
        using var opening = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        opening.CancelAfter(deadline);
        try { await ws.ConnectAsync(address, http, opening.Token); }
        catch { ws.Dispose(); http.Dispose(); throw new TransportException(Delivery.NotSubmitted, "Plowshare WebSocket upgrade failed; no application request was submitted"); }
        return new Client(ws, http, session, deadline);
    }

    public async Task<Reply> RequestAsync(string operation, object? payload = null, CancellationToken cancellationToken = default)
    {
        if (!Protocol.IsOperation(operation)) throw new ArgumentException("Unknown Plowshare operation", nameof(operation));
        var body = JsonSerializer.SerializeToElement(payload ?? new { });
        if (body.ValueKind != JsonValueKind.Object) throw new ArgumentException("Request payload must be an object", nameof(payload));
        var id = Guid.NewGuid().ToString();
        var wire = JsonSerializer.SerializeToUtf8Bytes(new { id, type = operation, protocol_version = Protocol.Version, payload = body });
        var answer = new TaskCompletionSource<Reply>(TaskCreationOptions.RunContinuationsAsynchronously);
        var submitted = false;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        try
        {
            await writer.WaitAsync(deadline.Token);
            try
            {
                lock (gate)
                {
                    if (closed || pending.Count >= 64) throw new TransportException(Delivery.NotSubmitted, "Connection closed or request capacity exceeded; request was not submitted");
                    pending.Add(id, new Pending(operation, answer));
                }
                submitted = true;
                await socket.SendAsync(wire.AsMemory(), WebSocketMessageType.Text, true, deadline.Token);
            }
            finally { writer.Release(); }
            return await answer.Task.WaitAsync(deadline.Token);
        }
        catch (TransportException) { throw; }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        { throw new RequestCancelledException(submitted ? Delivery.Unknown : Delivery.NotSubmitted, cancellationToken); }
        catch { throw new TransportException(submitted ? Delivery.Unknown : Delivery.NotSubmitted, "Request did not obtain a readable reply; no request was replayed"); }
        finally { lock (gate) pending.Remove(id); }
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
                JsonDocument json;
                try { json = JsonDocument.Parse(message.ToArray()); } catch (JsonException) { continue; }
                using (json)
                {
                    var frame = json.RootElement;
                    if (frame.ValueKind != JsonValueKind.Object) continue;
                    if (!frame.TryGetProperty("protocol_version", out _) || frame.TryGetProperty("id", out var pushId) && pushId.ValueKind == JsonValueKind.Null && Text(frame, "protocol_version") == Protocol.Version)
                    {
                        if (!pushes.Writer.TryWrite(frame.Clone())) Interlocked.Increment(ref droppedPushes);
                        continue;
                    }
                    var id = Text(frame, "id");
                    Pending? waiting;
                    lock (gate) { if (id is null || !pending.Remove(id, out waiting)) continue; }
                    var hasBody = frame.TryGetProperty("payload", out var body);
                    var valid = Text(frame, "protocol_version") == Protocol.Version && Text(frame, "type") == waiting.Operation
                        && hasBody && body.ValueKind == JsonValueKind.Object;
                    valid = valid && Text(body, "code") is string code && Protocol.IsCode(code)
                        && (!body.TryGetProperty("said", out var said) || said.ValueKind is JsonValueKind.Null or JsonValueKind.String);
                    if (valid) waiting.Answer.TrySetResult(new Reply(frame.Clone()));
                    else waiting.Answer.TrySetException(new TransportException(Delivery.InvalidResponse, "Unreadable response; outcome is unknown"));
                }
            }
        }
        catch { /* Uncorrelated transport failure cannot establish a mutation outcome. */ }
        finally
        {
            lock (gate)
            {
                closed = true;
                foreach (var waiting in pending.Values) waiting.Answer.TrySetException(new TransportException(Delivery.Unknown, "Socket closed before reply; no request was replayed"));
                pending.Clear();
            }
            pushes.Writer.TryComplete();
        }
    }

    public Task<Reply> JobStatusAsync(string job, CancellationToken ct = default) => RequestAsync("job.status", new { job }, ct);
    public Task<Reply> CancelJobAsync(string job, CancellationToken ct = default) => RequestAsync("job.cancel", new { job }, ct);
    public Task<Reply> OpenConversationAsync(object? payload = null, CancellationToken ct = default) => RequestAsync("conversation.open", payload, ct);
    public Task<Reply> RunAgentAsync(string agent, string task, Dictionary<string, object?>? scope = null, CancellationToken ct = default)
    {
        var payload = new Dictionary<string, object?>(scope ?? []) { ["agent"] = agent, ["task"] = task };
        payload.TryAdd("session", Session);
        return RequestAsync("agent.run", payload, ct);
    }
    public Task<Reply> SendOutgoingAsync(Guid requestId, string peer, object message, string? project = null, string? conversation = null, CancellationToken ct = default)
        => RequestAsync("outgoing.send", new { requestId, peer, message, project, conversation }, ct);
    public Task<Reply> OutgoingStatusAsync(Guid id, CancellationToken ct = default) => RequestAsync("outgoing.status", new { id }, ct);
    public Task<Reply> CancelOutgoingAsync(Guid id, CancellationToken ct = default) => RequestAsync("outgoing.cancel", new { id }, ct);
    public async ValueTask DisposeAsync()
    {
        lock (gate) { if (disposed) return; disposed = true; closed = true; }
        lifetime.Cancel(); socket.Abort();
        await reader;
        socket.Dispose(); http.Dispose(); lifetime.Dispose();
    }
}
