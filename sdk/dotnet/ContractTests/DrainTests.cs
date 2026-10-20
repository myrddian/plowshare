using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using Plowshare.Sdk;

internal static class DrainChecks
{
    private static T Field<T>(object owner, string name) =>
        (T)(owner.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(owner)
            ?? throw new Exception("Missing transport ownership field: " + name));

    private static async Task UntilAsync(Func<bool> condition)
    {
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        while (!condition()) { deadline.Token.ThrowIfCancellationRequested(); await Task.Yield(); }
    }

    private static void Idle(Client client)
    {
        lock (Field<object>(client, "gate"))
        {
            if (Field<int>(client, "admitted") != 0 || Field<long>(client, "held") != 0)
                throw new Exception("Dispose returned before admission/byte ownership drained");
        }
        if (Field<long>(Field<Packets>(client, "packets"), "held") != 0)
            throw new Exception("Dispose returned before packet byte ownership drained");
    }

    private static async Task NotSubmittedAsync(Task request)
    {
        try { await request; throw new Exception("Closed queued request succeeded"); }
        catch (TransportException failure) when (failure.Delivery == Delivery.NotSubmitted) { }
    }

    internal static async Task RunAsync()
    {
        await QueuedOwnersAsync();
        await ActiveConversionAsync();
        Console.WriteLine(".NET shared close/admission drain checks passed");
    }

    private static async Task QueuedOwnersAsync()
    {
        await using var fixture = new Fixture();
        await using var client = await fixture.OpenAsync();
        var writer = Field<SemaphoreSlim>(client, "writer");
        await writer.WaitAsync();
        try
        {
            var first = client.MemoryIndexAsync(new MemoryIndexRequest());
            await UntilAsync(() => Field<long>(client, "held") > 0);
            using var conversion = await Field<ConversionGate>(client, "converter").EnterAsync(CancellationToken.None);
            var second = client.MemoryIndexAsync(new MemoryIndexRequest());
            await UntilAsync(() => Field<int>(client, "admitted") == 2);
            // Wait for both closes before collecting request results. User code
            // consuming failures must not be part of the transport drain.
            await Task.WhenAll(client.DisposeAsync().AsTask(), client.DisposeAsync().AsTask()).WaitAsync(TimeSpan.FromSeconds(3));
            Idle(client);
            await NotSubmittedAsync(first);
            await NotSubmittedAsync(second);
        }
        finally { writer.Release(); }
    }

    [JsonConverter(typeof(HeldEncoder))]
    internal sealed record HeldRequest(ManualResetEventSlim Entered, ManualResetEventSlim Release);

    internal sealed class HeldEncoder : JsonConverter<HeldRequest>
    {
        public override HeldRequest Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options) => throw new NotSupportedException();
        public override void Write(Utf8JsonWriter writer, HeldRequest request, JsonSerializerOptions options)
        {
            request.Entered.Set();
            request.Release.Wait();
            writer.WriteStartObject(); writer.WriteEndObject();
        }
    }

    private static async Task ActiveConversionAsync()
    {
        await using var fixture = new Fixture();
        await using var client = await fixture.OpenAsync();
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var request = Task.Run(() => client.RequestTypedAsync<HeldRequest, IReadOnlyList<ObservedIndexDto>>(
            "memory.index", new HeldRequest(entered, release), CancellationToken.None));
        Task? closing = null;
        try
        {
            await UntilAsync(() => entered.IsSet);
            closing = client.DisposeAsync().AsTask();
            await Field<Task>(client, "reader").WaitAsync(TimeSpan.FromSeconds(3));
            if (closing.IsCompleted) throw new Exception("Dispose returned while a converter still owned an admission");
        }
        finally { release.Set(); }
        await (closing ?? throw new Exception("Close did not start")).WaitAsync(TimeSpan.FromSeconds(3));
        Idle(client);
        await NotSubmittedAsync(request);
    }

    // A real upgraded socket keeps the production reader/writer lifetime intact;
    // no database or server runtime is required for transport ownership checks.
    private sealed class Fixture : IAsyncDisposable
    {
        private readonly HttpListener listener = new();
        private readonly CancellationTokenSource stop = new();
        private readonly Task server;
        private readonly string origin;

        internal Fixture()
        {
            using var probe = new TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            var port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();
            origin = $"http://127.0.0.1:{port}";
            listener.Prefixes.Add(origin + "/"); listener.Start();
            server = ServeAsync();
        }

        internal Task<Client> OpenAsync() => Client.ConnectAsync(origin, "drain-fixture", timeout: TimeSpan.FromSeconds(3), dispatchMode: DispatchMode.Ordered);

        private async Task ServeAsync()
        {
            try
            {
                var context = await listener.GetContextAsync();
                using var socket = (await context.AcceptWebSocketAsync(Packets.Protocol)).WebSocket;
                var buffer = new byte[8192];
                while (!stop.IsCancellationRequested)
                    if ((await socket.ReceiveAsync(buffer.AsMemory(), stop.Token)).MessageType == WebSocketMessageType.Close) return;
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
            catch (WebSocketException) { /* Client abort is the expected shutdown boundary. */ }
            catch (HttpListenerException) when (stop.IsCancellationRequested) { }
            catch (ObjectDisposedException) when (stop.IsCancellationRequested) { }
        }

        public async ValueTask DisposeAsync()
        {
            stop.Cancel(); listener.Close();
            await server.WaitAsync(TimeSpan.FromSeconds(3));
            stop.Dispose();
        }
    }
}
