using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Closed scalar input vocabulary, shared by all SDK tool facades.</summary>
public abstract record ToolValue
{
    private protected ToolValue() { }
    public sealed record Text(string Value) : ToolValue;
    public sealed record Number(double Value) : ToolValue;
    public sealed record Boolean(bool Value) : ToolValue;
}
public sealed record ToolParameter(string Name, string Type, string Description, bool Required);
public sealed record ToolDeclaration(string Name, string Description, IReadOnlyList<ToolParameter> Parameters, int TimeoutSeconds);
public sealed record ToolBinding(string Project, string Provider, string Account);
public sealed record ToolCall(string InvocationId, string Project, string Provider, string Tool,
    string Account, string Run, string Call, string Deadline, IReadOnlyDictionary<string, ToolValue> Arguments);
public sealed record ToolResult(string State, string Text);
public sealed record RegisteredTool(ToolDeclaration Declaration, Func<ToolCall, CancellationToken, Task<ToolResult>> Handler);
public sealed record ToolReceipt(string InvocationId, string Request, string Phase, ToolResult? Result, string? OccurredAt);

/// <summary>Exclusive provider-owned store. Save must commit durably before returning.</summary>
public interface IToolJournal
{
    string Identity { get; }
    Task<IReadOnlyList<ToolReceipt>> AllAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(ToolReceipt receipt, CancellationToken cancellationToken = default);
}
public interface IToolRelayPorts
{
    Task<RelayBatchDto> ConsumeAsync(RelayConsumeRequest request, CancellationToken token);
    Task<RelayAckResultDto> AcknowledgeAsync(RelayAckRequest request, CancellationToken token);
    Task<RelayPublishResultDto> PublishAsync(RelayPublishRequest request, CancellationToken token);
    Task<RelayLogResultDto> LogAsync(RelayLogRequest request, CancellationToken token);
}
/// <summary>Public SDK composition; no alternative transport or automatic replay.</summary>
public sealed class SdkToolRelayPorts(Client client) : IToolRelayPorts
{
    public async Task<RelayBatchDto> ConsumeAsync(RelayConsumeRequest request, CancellationToken token) => (await client.RelayConsumeAsync(request, token)).RequirePayload();
    public async Task<RelayAckResultDto> AcknowledgeAsync(RelayAckRequest request, CancellationToken token) => (await client.RelayAckAsync(request, token)).RequirePayload();
    public async Task<RelayPublishResultDto> PublishAsync(RelayPublishRequest request, CancellationToken token) => (await client.RelayPublishAsync(request, token)).RequirePayload();
    public async Task<RelayLogResultDto> LogAsync(RelayLogRequest request, CancellationToken token) => (await client.RelayLogAsync(request, token)).RequirePayload();
}

/// <summary>Owns tool declaration and TEXT envelope conversion; raw JSON remains private.</summary>
public static class RelayTools
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    internal static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();
    internal static string Identity(string value)
    {
        if (string.IsNullOrEmpty(value) || value.Length > 256 || value != value.Trim() || value.Any(c => char.IsControl(c) || c is '\u2028' or '\u2029')) throw new ArgumentException("Invalid tool identity");
        return value;
    }
    internal static string Text(string value, int maximum, bool nonblank = false)
    {
        if (value is null || value.Length > maximum || value.Contains('\0') || nonblank && string.IsNullOrWhiteSpace(value)) throw new ArgumentException("Invalid tool text");
        return value;
    }
    internal static DateTimeOffset Instant(string value)
    {
        if (!value.EndsWith('Z') || !DateTimeOffset.TryParse(value, System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out var parsed)) throw new ArgumentException("Invalid UTC tool timestamp");
        return parsed;
    }
    private static void Declaration(ToolDeclaration tool)
    {
        if (!Regex.IsMatch(tool.Name, "^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$") || tool.Name.Length > 64 || tool.TimeoutSeconds is < 1 or > 300 || tool.Parameters.Count > 32 || tool.Parameters.Select(p => p.Name).Distinct().Count() != tool.Parameters.Count) throw new ArgumentException("Invalid tool declaration");
        Text(tool.Description, 4096, true);
        foreach (var parameter in tool.Parameters)
        {
            if (!Regex.IsMatch(parameter.Name, "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$") || parameter.Type is not ("STRING" or "NUMBER" or "INTEGER" or "BOOLEAN")) throw new ArgumentException("Invalid tool parameter");
            Text(parameter.Description, 4096);
        }
    }
    private static string NormalizedDecimal(string source)
    {
        var parts = source.ToLowerInvariant().Split('e');
        var negative = parts[0].StartsWith('-');
        var raw = parts[0].Replace("-", "").Replace(".", "");
        var digits = raw.TrimStart('0').TrimEnd('0');
        if (digits.Length == 0) return "0";
        var exponent = parts.Length == 2 ? int.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture) : 0;
        var fraction = parts[0].Contains('.') ? parts[0].Split('.')[1].Length : 0;
        return (negative ? "-" : "") + digits + "e" + (exponent - fraction + raw.Length - raw.TrimEnd('0').Length).ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    private static bool PortableNumber(double value)
    {
        if (!double.IsFinite(value) || Math.Abs(value) > 9007199254740991) return false;
        var parts = value.ToString("R", System.Globalization.CultureInfo.InvariantCulture).ToLowerInvariant().Split('e');
        var exponent = parts.Length == 2 ? int.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture) : 0;
        var fraction = parts[0].Contains('.') ? parts[0].Split('.')[1].TrimEnd('0').Length : 0;
        return fraction - exponent <= 18;
    }

    public static void ValidateArguments(ToolDeclaration tool, IReadOnlyDictionary<string, ToolValue> arguments)
    {
        Declaration(tool);
        if (arguments.Keys.Any(name => !tool.Parameters.Any(p => p.Name == name))) throw new ArgumentException("Unknown tool argument");
        foreach (var parameter in tool.Parameters)
        {
            if (!arguments.TryGetValue(parameter.Name, out var value)) { if (parameter.Required) throw new ArgumentException("Required tool argument missing"); continue; }
            var valid = parameter.Type switch
            {
                "STRING" => value is ToolValue.Text,
                "BOOLEAN" => value is ToolValue.Boolean,
                "NUMBER" => value is ToolValue.Number number && PortableNumber(number.Value),
                "INTEGER" => value is ToolValue.Number number && PortableNumber(number.Value) && Math.Truncate(number.Value) == number.Value,
                _ => false
            };
            if (!valid) throw new ArgumentException("Tool argument type mismatch");
            if (value is ToolValue.Text text) Text(text.Value, 4096);
        }
    }
    /// <summary>Export operator-installed bindings. Declarations never grant permission.</summary>
    public static string DeploymentConfig(ToolBinding binding, IReadOnlyList<ToolDeclaration> tools)
    {
        Identity(binding.Project); Identity(binding.Account);
        if (!Regex.IsMatch(binding.Provider, "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$") || binding.Provider.Length > 48 || tools.Count is < 1 or > 256 || tools.Select(t => t.Name).Distinct().Count() != tools.Count) throw new ArgumentException("Invalid tool binding");
        foreach (var tool in tools) Declaration(tool);
        var bindings = tools.Select(t => new { binding.Project, binding.Provider, binding.Account, t.Name, t.Description, t.Parameters, t.TimeoutSeconds }).ToArray();
        return JsonSerializer.Serialize(new { plowshare = new { relay = new { tools = new { bindings } } } }, Json);
    }
    public static string ResultRequestId(string invocationId)
    {
        if (!Guid.TryParseExact(invocationId, "D", out var id) || id.ToString() != invocationId) throw new ArgumentException("Invalid tool UUID");
        var hash = Hash("tool-result:" + invocationId)[..32];
        return $"{hash[..8]}-{hash[8..12]}-{hash[12..16]}-{hash[16..20]}-{hash[20..]}";
    }
    private static void Unique(JsonElement value, int depth)
    {
        if (depth > 8) throw new ArgumentException("Tool envelope exceeds depth bound");
        if (value.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>();
            foreach (var property in value.EnumerateObject()) { if (!names.Add(property.Name)) throw new ArgumentException("Duplicate tool field"); Unique(property.Value, depth + 1); }
        }
        else if (value.ValueKind == JsonValueKind.Array) foreach (var item in value.EnumerateArray()) Unique(item, depth + 1);
    }
    public static ToolCall DecodeCall(string source)
    {
        Text(source, 32768, true);
        using var document = JsonDocument.Parse(source);
        var row = document.RootElement; Unique(row, 0);
        string[] fields = ["schema", "invocationId", "project", "provider", "tool", "account", "run", "call", "deadline", "arguments"];
        if (row.ValueKind != JsonValueKind.Object || row.EnumerateObject().Count() != fields.Length || fields.Any(name => !row.TryGetProperty(name, out _)) || row.GetProperty("schema").GetString() != "plowshare-tool/1") throw new ArgumentException("Invalid tool envelope");
        string Read(string name) => Identity(row.GetProperty(name).GetString() ?? throw new ArgumentException("Invalid tool field"));
        var id = Read("invocationId"); ResultRequestId(id);
        var deadline = Read("deadline"); Instant(deadline);
        var args = row.GetProperty("arguments");
        if (args.ValueKind != JsonValueKind.Object || args.EnumerateObject().Count() > 32) throw new ArgumentException("Invalid tool arguments");
        var values = new Dictionary<string, ToolValue>();
        foreach (var property in args.EnumerateObject())
        {
            if (!Regex.IsMatch(property.Name, "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$")) throw new ArgumentException("Invalid tool argument name");
            values.Add(property.Name, property.Value.ValueKind switch
            {
                JsonValueKind.String => new ToolValue.Text(Text(property.Value.GetString() ?? "", 4096)),
                JsonValueKind.Number when property.Value.TryGetDouble(out var number) && PortableNumber(number) && NormalizedDecimal(property.Value.GetRawText()) == NormalizedDecimal(number.ToString("R", System.Globalization.CultureInfo.InvariantCulture)) => new ToolValue.Number(number),
                JsonValueKind.True => new ToolValue.Boolean(true),
                JsonValueKind.False => new ToolValue.Boolean(false),
                _ => throw new ArgumentException("Tool arguments must be declared scalars")
            });
        }
        var provider = Read("provider"); var toolName = Read("tool");
        if (!Regex.IsMatch(provider, "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$") || provider.Length > 48 || !Regex.IsMatch(toolName, "^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$") || toolName.Length > 64) throw new ArgumentException("Invalid tool binding");
        return new ToolCall(id, Read("project"), provider, toolName, Read("account"), Read("run"), Read("call"), deadline, new ReadOnlyDictionary<string, ToolValue>(values));
    }
    internal static string ResultText(ToolCall call, ToolResult? result)
    {
        if (result is null || result.State is not ("COMPLETED" or "REJECTED" or "UNKNOWN")) throw new ArgumentException("Missing tool result");
        Text(result.Text, 16384, true);
        var encoded = JsonSerializer.Serialize(new { schema = "plowshare-tool/1", call.InvocationId, call.Project, call.Provider, call.Tool, result.State, result.Text }, Json);
        return Text(encoded, 32768, true);
    }
}

/// <summary>Named handlers over Relay. Lost submission acknowledgements are reconciled by reads.</summary>
public sealed class ToolProvider
{
    private readonly IToolRelayPorts ports;
    private readonly ToolBinding binding;
    private readonly IReadOnlyList<RegisteredTool> tools;
    private readonly IToolJournal journal;
    private readonly string consumer = Guid.NewGuid().ToString();
    private readonly SemaphoreSlim gate = new(1, 1);
    public ToolProvider(IToolRelayPorts ports, ToolBinding binding, IReadOnlyList<RegisteredTool> tools, IToolJournal journal)
    {
        var config = RelayTools.DeploymentConfig(binding, tools.Select(t => t.Declaration).ToArray());
        if (journal.Identity != RelayTools.Hash(config)) throw new ArgumentException("Foreign tool journal");
        this.ports = ports; this.binding = binding; this.tools = tools.Select(t => t with { Declaration = t.Declaration with { Parameters = Array.AsReadOnly(t.Declaration.Parameters.ToArray()) } }).ToArray(); this.journal = journal;
    }
    private async Task<IReadOnlyList<ToolReceipt>> ReceiptsAsync(CancellationToken token)
    {
        var rows = await journal.AllAsync(token);
        if (rows.Count > 1000 || rows.Select(r => r.InvocationId).Distinct().Count() != rows.Count) throw new ArgumentException("Invalid tool journal size or identities");
        foreach (var row in rows)
        {
            var call = RelayTools.DecodeCall(row.Request);
            var declaration = tools.FirstOrDefault(t => t.Declaration.Name == call.Tool)?.Declaration;
            if (call.InvocationId != row.InvocationId || call.Project != binding.Project || call.Provider != binding.Provider || declaration is null) throw new ArgumentException("Foreign tool receipt");
            RelayTools.ValidateArguments(declaration, call.Arguments);
            if (row.Phase is not ("executing" or "ready" or "publishing" or "done") || (row.Phase == "executing") != (row.Result is null) || (row.Phase is "publishing" or "done") != (row.OccurredAt is not null)) throw new ArgumentException("Invalid tool receipt phase");
            if (row.Result is not null) RelayTools.ResultText(call, row.Result);
            if (row.OccurredAt is not null) RelayTools.Instant(row.OccurredAt);
        }
        return rows;
    }
    private string Topic(string tool, string kind) => $"tool.{binding.Provider}.{tool}.{kind}";
    private static string Required(TierDtoProject value) => value.Variant2.IsSet ? value.Variant2.Value : throw new ArgumentException("Required tool field is missing");
    public async Task<int> PollAsync(CancellationToken token = default)
    {
        if (!await gate.WaitAsync(0, token)) throw new InvalidOperationException("Tool provider already has an active pass");
        try
        {
            foreach (var receipt in await ReceiptsAsync(token))
            {
                if (receipt.Phase == "publishing") throw new InvalidOperationException("Uncertain tool result; reconcile before polling");
                var pending = receipt;
                if (receipt.Phase == "executing")
                {
                    pending = receipt with { Phase = "ready", Result = new ToolResult("UNKNOWN", "Provider restarted after execution intent; external effects may have occurred."), OccurredAt = null };
                    await journal.SaveAsync(pending, token);
                }
                if (pending.Phase == "ready") await PublishAsync(pending, token);
            }
            var count = 0;
            foreach (var registered in tools)
            {
                var name = registered.Declaration.Name; var topic = Topic(name, "request");
                var batch = await ports.ConsumeAsync(new RelayConsumeRequest { Project = binding.Project, Topic = topic, Group = "tool-provider", ConsumerId = consumer, Start = "OLDEST_RETAINED", Limit = new(1), WaitMs = new(0) }, token);
                if (batch.Status == "GAP") throw new InvalidOperationException("Tool history expired; inspect before acknowledging");
                if (batch.Status != "DATA") continue;
                if (batch.Project != binding.Project || batch.Topic != topic || batch.Group != "tool-provider" || batch.ConsumerId != consumer || batch.Events.Count != 1) throw new ArgumentException("Foreign tool batch");
                var publication = batch.Events[0];
                if (publication.Publisher != "tool-runtime" || publication.Payload.Kind != "TEXT") throw new ArgumentException("Invalid tool publisher or payload");
                var source = Required(publication.Payload.Text); var call = RelayTools.DecodeCall(source);
                if (call.Project != binding.Project || call.Provider != binding.Provider || call.Tool != name || call.InvocationId != publication.EventId || call.InvocationId != Required(publication.CorrelationId)) throw new ArgumentException("Foreign tool invocation");
                RelayTools.ValidateArguments(registered.Declaration, call.Arguments);
                var receipt = (await ReceiptsAsync(token)).FirstOrDefault(r => r.InvocationId == call.InvocationId);
                var fresh = receipt is null;
                if (receipt is not null && receipt.Request != source) throw new ArgumentException("Conflicting tool invocation identity");
                if (receipt is null) { receipt = new ToolReceipt(call.InvocationId, source, "executing", null, null); await journal.SaveAsync(receipt, token); }
                var acknowledgement = await ports.AcknowledgeAsync(new RelayAckRequest { Project = binding.Project, Topic = topic, Group = "tool-provider", ConsumerId = consumer, BatchId = Required(batch.BatchId), Fence = Required(batch.Fence) }, token);
                if (acknowledgement.Project != binding.Project || acknowledgement.Topic != topic || acknowledgement.Group != "tool-provider" || acknowledgement.BatchId != Required(batch.BatchId) || acknowledgement.Gap) throw new ArgumentException("Foreign tool acknowledgement");
                if (fresh)
                {
                    var remaining = RelayTools.Instant(call.Deadline) - DateTimeOffset.UtcNow;
                    var result = new ToolResult("REJECTED", "Tool deadline expired before execution; no handler ran.");
                    if (remaining > TimeSpan.Zero)
                    {
                        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
                        deadline.CancelAfter(remaining < TimeSpan.FromSeconds(registered.Declaration.TimeoutSeconds) ? remaining : TimeSpan.FromSeconds(registered.Declaration.TimeoutSeconds));
                        try { result = await registered.Handler(call, deadline.Token).WaitAsync(deadline.Token); RelayTools.ResultText(call, result); }
                        catch (Exception) { result = new ToolResult("UNKNOWN", "Handler ended after execution intent; external effects may have occurred."); }
                    }
                    receipt = receipt with { Phase = "ready", Result = result };
                    // Persist uncertainty even if the caller has cancelled while awaiting the handler.
                    await journal.SaveAsync(receipt, CancellationToken.None);
                }
                if (receipt.Phase == "ready") await PublishAsync(receipt, token);
                count++;
            }
            return count;
        }
        finally { gate.Release(); }
    }
    private async Task PublishAsync(ToolReceipt receipt, CancellationToken token)
    {
        var call = RelayTools.DecodeCall(receipt.Request); var text = RelayTools.ResultText(call, receipt.Result);
        var id = RelayTools.ResultRequestId(call.InvocationId); var occurred = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", System.Globalization.CultureInfo.InvariantCulture);
        var intent = receipt with { Phase = "publishing", OccurredAt = occurred };
        await journal.SaveAsync(intent, token);
        RelayPublishResultDto reply;
        try
        {
            reply = await ports.PublishAsync(new RelayPublishRequest { Project = binding.Project, Topic = Topic(call.Tool, "result"), RequestId = id, OccurredAt = occurred, CorrelationId = new(TierDtoProject.FromVariant2(call.InvocationId)), ParentTopic = new(TierDtoProject.FromVariant2(Topic(call.Tool, "request"))), ParentEventId = new(TierDtoProject.FromVariant2(call.InvocationId)), Text = text }, token);
        }
        catch (TransportException error) when (error.Delivery == Delivery.NotSubmitted)
        {
            await journal.SaveAsync(receipt, CancellationToken.None); throw;
        }
        if (reply.Project != binding.Project || reply.Topic != Topic(call.Tool, "result") || reply.RequestId != id) throw new ArgumentException("Foreign tool result receipt");
        await journal.SaveAsync(intent with { Phase = "done" }, CancellationToken.None);
    }
    /// <summary>Read-only settlement of exact retained results; never invokes or republishes.</summary>
    public async Task<int> ReconcileAsync(CancellationToken token = default)
    {
        if (!await gate.WaitAsync(0, token)) throw new InvalidOperationException("Tool provider already has an active pass");
        try
        {
            var count = 0;
            foreach (var receipt in await ReceiptsAsync(token))
            {
                if (receipt.Phase != "publishing") continue;
                var call = RelayTools.DecodeCall(receipt.Request); var id = RelayTools.ResultRequestId(call.InvocationId); var after = "0"; var settled = false;
                for (var pageNumber = 0; pageNumber < 100; pageNumber++)
                {
                    var page = await ports.LogAsync(new RelayLogRequest(RelayLogPayload.FromVariant1(new RelayLogPayloadVariant1Dto { Project = binding.Project, Topic = Topic(call.Tool, "result"), System = new(false), After = new(after), Limit = new(100) })), token);
                    var publication = page.Events.FirstOrDefault(e => e.EventId == id);
                    if (publication is not null)
                    {
                        if (publication.Publisher != "sdk:" + RelayTools.Hash(binding.Account) || publication.Payload.Kind != "TEXT" || Required(publication.Payload.Text) != RelayTools.ResultText(call, receipt.Result) || Required(publication.CorrelationId) != call.InvocationId || Required(publication.CausationId) != call.InvocationId || receipt.OccurredAt is null || RelayTools.Instant(publication.OccurredAt) != RelayTools.Instant(receipt.OccurredAt)) throw new ArgumentException("Conflicting retained tool result");
                        await journal.SaveAsync(receipt with { Phase = "done" }, token); count++; settled = true; break;
                    }
                    if (page.Events.Count == 0 || page.Events[^1].Position == after) { settled = true; break; }
                    after = page.Events[^1].Position;
                }
                if (!settled) throw new InvalidOperationException("Tool reconciliation exceeded retained read bound");
            }
            return count;
        }
        finally { gate.Release(); }
    }
}
