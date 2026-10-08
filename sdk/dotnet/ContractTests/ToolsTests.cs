using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Plowshare.Sdk;

internal static class ToolFacadeChecks
{
    internal static readonly ToolBinding Binding = new("fixture", "scanner", "provider");
    internal static readonly ToolDeclaration Declaration = new("inspect", "Inspect fixture", [new("value", "NUMBER", "", true)], 30);
    internal const string Id = "11111111-1111-1111-1111-111111111111";
    private static string Hash(string source) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(source))).ToLowerInvariant();
    public static async Task RunAsync()
    {
        var directory = Path.GetDirectoryName(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_DTO_FIXTURES"))!;
        using var fixture = JsonDocument.Parse(File.ReadAllText(Path.Combine(directory, "relay-tools.json")));
        if (RelayTools.ResultRequestId(Id) != fixture.RootElement.GetProperty("resultRequestId").GetString()) throw new Exception("Tool identity differs");
        foreach (var sample in fixture.RootElement.GetProperty("cases").EnumerateArray())
        {
            bool accepted = true;
            try { RelayTools.ValidateArguments(Declaration, RelayTools.DecodeCall(sample.GetProperty("source").GetString()!).Arguments); }
            catch (Exception error) when (error is ArgumentException or JsonException or InvalidOperationException or FormatException) { accepted = false; }
            if (accepted != sample.GetProperty("valid").GetBoolean()) throw new Exception("Tool boundary differs: " + sample.GetProperty("name").GetString());
        }
        var source = fixture.RootElement.GetProperty("cases")[0].GetProperty("source").GetString()!;
        var journal = new Journal(); var wire = new Ports(source) { LoseReply = true }; var executions = 0;
        RegisteredTool[] tools = [new(Declaration, (call, token) => { executions++; return Task.FromResult(new ToolResult("COMPLETED", "observed")); })];
        var provider = new ToolProvider(wire, Binding, tools, journal);
        await MustFailAsync(() => provider.PollAsync());
        if (journal.Rows[Id].Phase != "publishing") throw new Exception("Lost publication intent not retained");
        var restarted = new ToolProvider(wire, Binding, tools, journal);
        await MustFailAsync(() => restarted.PollAsync());
        if (await restarted.ReconcileAsync() != 1) throw new Exception("Result did not reconcile");
        await restarted.PollAsync();
        if (executions != 1 || wire.Publications != 1 || journal.Rows[Id].Phase != "done") throw new Exception("Tool effects repeated");
        journal = new Journal(); wire = new Ports(source); executions = 0;
        await journal.SaveAsync(new ToolReceipt(Id, source, "executing", null, null));
        await new ToolProvider(wire, Binding, tools, journal).PollAsync();
        if (executions != 0 || wire.Publications != 1 || journal.Rows[Id].Result?.State != "UNKNOWN") throw new Exception("Interrupted work repeated");
        Console.WriteLine(".NET tool facade checks passed");
    }
    private static async Task MustFailAsync(Func<Task<int>> action)
    {
        try { await action(); }
        catch (Exception error) when (error is IOException or InvalidOperationException) { return; }
        throw new Exception("Expected unresolved publication refusal");
    }
    private sealed class Journal : IToolJournal
    {
        internal Dictionary<string, ToolReceipt> Rows { get; } = [];
        public string Identity => Hash(RelayTools.DeploymentConfig(Binding, [Declaration]));
        public Task<IReadOnlyList<ToolReceipt>> AllAsync(CancellationToken token = default) => Task.FromResult<IReadOnlyList<ToolReceipt>>(Rows.Values.ToArray());
        public Task SaveAsync(ToolReceipt receipt, CancellationToken token = default) { Rows[receipt.InvocationId] = receipt; return Task.CompletedTask; }
    }
    private sealed class Ports(string source) : IToolRelayPorts
    {
        internal bool LoseReply { get; set; }
        internal int Publications { get; private set; }
        private RelayPublishRequest? published;
        private static T Decode<T>(object value) => JsonSerializer.Deserialize<T>(JsonSerializer.Serialize(value)) ?? throw new Exception("Fixture DTO missing");
        private object Event(bool result = false) => new {
            position = "1", eventId = result ? published!.RequestId : Id,
            publisher = result ? "sdk:" + Hash(Binding.Account) : "tool-runtime",
            occurredAt = result ? published!.OccurredAt : "2026-10-08T00:00:00Z", publishedAt = "2026-10-08T00:00:00Z",
            correlationId = Id, causationId = Id, causation = (object?)null,
            payload = new { kind = "TEXT", text = result ? published!.Text : source, schedule = (string?)null, emits = (string?)null, fireAt = (string?)null }
        };
        public Task<RelayBatchDto> ConsumeAsync(RelayConsumeRequest request, CancellationToken token) => Task.FromResult(Decode<RelayBatchDto>(new {
            project = request.Project, topic = request.Topic, group = request.Group, consumerId = request.ConsumerId,
            status = "DATA", batchId = Id, fence = "1", through = "1", expiresAt = "2099-01-01T00:00:00Z", expiredThrough = (string?)null, events = new[] { Event() }
        }));
        public Task<RelayAckResultDto> AcknowledgeAsync(RelayAckRequest request, CancellationToken token) => Task.FromResult(new RelayAckResultDto {
            Project = request.Project, Topic = request.Topic, Group = request.Group, BatchId = request.BatchId, Through = "1", Gap = false
        });
        public Task<RelayPublishResultDto> PublishAsync(RelayPublishRequest request, CancellationToken token)
        {
            Publications++; published = request;
            if (LoseReply) throw new IOException("Result published but reply lost");
            return Task.FromResult(new RelayPublishResultDto { Project = request.Project, Topic = request.Topic, RequestId = request.RequestId, Position = "1", PublishedAt = "2026-10-08T00:00:00Z" });
        }
        public Task<RelayLogResultDto> LogAsync(RelayLogRequest request, CancellationToken token) => Task.FromResult(Decode<RelayLogResultDto>(new {
            scope = new { project = Binding.Project, system = false },
            topic = new { name = published!.Topic, kind = "TEXT", retentionSeconds = "345600", maxRecords = (string?)null, through = "1", expiredThrough = "0" },
            after = "0", next = "1", gapThrough = (string?)null, events = new[] { Event(true) }, subscribers = Array.Empty<object>(), branches = Array.Empty<object>()
        }));
    }
}
