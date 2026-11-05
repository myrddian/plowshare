using System.Text.Json;
using Plowshare.Sdk;

using var fixture = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_DTO_FIXTURES")!));
foreach (var test in fixture.RootElement.EnumerateArray())
{
    bool accepted = true;
    try
    {
        var boundary = test.GetProperty("boundary").GetString(); var value = test.GetProperty("value");
        if (boundary == "input") Codec.Input(test.GetProperty("operation").GetString()!, value);
        else if (boundary == "result") Codec.Result<JsonElement>(test.GetProperty("operation").GetString()!, value);
        else Codec.Push(value);
    }
    catch (JsonException) { accepted = false; }
    if (accepted != test.GetProperty("valid").GetBoolean()) throw new Exception("Boundary case failed: " + test.GetRawText());
}
using(var askedTool=JsonDocument.Parse("""{"project":"atlas","invocation":"11111111-1111-1111-1111-111111111111"}""")) {
 foreach(var id in new[]{"11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222"}) {
  bool accepted=true;try{Codec.CorrelateDeployment("tool.invocation.read",askedTool.RootElement,JsonSerializer.SerializeToElement(new{id}));}catch(JsonException){accepted=false;}
  if(accepted!=(id=="11111111-1111-1111-1111-111111111111"))throw new Exception("Foreign invocation result accepted");
 }
}
// Reflection checks the full public operation surface, independently of schema decoding.
var methods = typeof(Operations).GetMethods(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static);
if (methods.Length != Protocol.Operations.Count) throw new Exception("Missing typed operations");
foreach (var method in methods)
    if (method.GetParameters().Any(p => p.ParameterType == typeof(object) || p.ParameterType == typeof(JsonElement))) throw new Exception("Raw public request");
var absent = Codec.Input("memory.index", new MemoryIndexRequest());
if (absent.TryGetProperty("project", out _)) throw new Exception("Absent field encoded");
var explicitNull = Codec.Input("memory.index", new MemoryIndexRequest { Project = TierDtoProject.FromVariant1(default) });
if (explicitNull.GetProperty("project").ValueKind != JsonValueKind.Null) throw new Exception("Explicit null lost");
using var deploymentRequest = JsonDocument.Parse("""{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","revision":"22222222-2222-2222-2222-222222222222"}""");
using var deploymentReceipt = JsonDocument.Parse("""{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","release":{"revision":"22222222-2222-2222-2222-222222222222"}}""");
Codec.CorrelateDeployment("application.activate",deploymentRequest.RootElement,deploymentReceipt.RootElement);
foreach (var changed in new[] { """{"project":"other","requestId":"11111111-1111-1111-1111-111111111111","revision":"22222222-2222-2222-2222-222222222222"}""", """{"project":"app","requestId":"33333333-3333-3333-3333-333333333333","revision":"22222222-2222-2222-2222-222222222222"}""", """{"project":"app","requestId":"11111111-1111-1111-1111-111111111111","revision":"33333333-3333-3333-3333-333333333333"}""" })
{
    using var foreign = JsonDocument.Parse(changed);
    bool refused = false;
    try { Codec.CorrelateDeployment("application.activate",foreign.RootElement,deploymentReceipt.RootElement); } catch (JsonException) { refused = true; }
    if (!refused) throw new Exception("Foreign deployment receipt accepted");
}
using var credentialRequest = JsonDocument.Parse("""{"project":"app","requestId":"11111111-1111-1111-1111-111111111111"}""");
var credentialSecret="pss_"+new string('1',48);
var ownedCredential=new ApplicationCredentialDto { Credential=credentialSecret,Receipt=new ApplicationTokenReceiptDto { Project="app",RequestId="11111111-1111-1111-1111-111111111111",TokenId="22222222-2222-2222-2222-222222222222",Principal="@service/22222222-2222-2222-2222-222222222222",ExpiresAt="2026-11-01T00:00:00Z" } };
if(ownedCredential.ToString().Contains(credentialSecret))throw new Exception("Credential leaked in diagnostic");
var credentialResult=JsonSerializer.SerializeToElement(ownedCredential);
Codec.CorrelateDeployment("application.token.issue",credentialRequest.RootElement,credentialResult);
using var foreignCredential=JsonDocument.Parse("""{"project":"foreign","requestId":"11111111-1111-1111-1111-111111111111"}""");
bool foreignCredentialRefused=false;
try {Codec.CorrelateDeployment("application.token.issue",foreignCredential.RootElement,credentialResult);}catch(JsonException){foreignCredentialRefused=true;}
if(!foreignCredentialRefused)throw new Exception("Foreign credential receipt accepted");
{
    const string child = "11111111-1111-1111-1111-111111111111", foreign = "33333333-3333-3333-3333-333333333333";
    using var containedAsked = JsonDocument.Parse($$"""{"conversation":"c","child":"{{child}}"}""");
    foreach (var (operation, reply, accepted) in new[] {
        ("application.child.ceiling", $$"""{"child":"{{child}}"}""", true),
        ("application.child.ceiling", $$"""{"child":"{{foreign}}"}""", false),
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{foreign}}","{{child}}"],"sources":2}""", true),
        ("application.child.destroy", $$"""{"child":"{{foreign}}","collected":["{{foreign}}"],"sources":1}""", false),
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{foreign}}"],"sources":1}""", false),
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{child}}"],"sources":2}""", false),
        // A non-numeric count is a refusal, not an InvalidOperationException escaping the gate.
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{child}}"],"sources":"1"}""", false),
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{child}}"],"sources":null}""", false),
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{child}}"],"sources":true}""", false) })
    {
        using var answer = JsonDocument.Parse(reply);
        bool admitted = true;
        try { Codec.CorrelateDeployment(operation, containedAsked.RootElement, answer.RootElement); } catch (JsonException) { admitted = false; }
        if (admitted != accepted) throw new Exception($"Contained reply correlation wrong for {operation} {reply}");
    }
}
{
    // A started run is a handle and not an outcome; a cancellation echoes the child asked of.
    // Request-side semantic bounds (the agent name, the task, the turn cap) live in the
    // TypeScript/Node request gate, as every other contained operation's do.
    const string child = "11111111-1111-1111-1111-111111111111", foreign = "33333333-3333-3333-3333-333333333333";
    var startAsked = Codec.Input("application.child.run", new ApplicationChildRunRequest { Conversation = "c", Child = child, Agent = "worker", Task = "do it", MaxTurns = ScheduleDefinitionDtoLimitsDtoMaxModelCalls.FromVariant2(3) });
    if (startAsked.GetProperty("agent").GetString() != "worker" || startAsked.GetProperty("task").GetString() != "do it" || startAsked.GetProperty("maxTurns").GetDouble() != 3) throw new Exception("Run request lost a field");
    // null is the one absence the shape allows: it leaves the agent's own turn cap.
    var undecided = Codec.Input("application.child.run", new ApplicationChildRunRequest { Conversation = "c", Child = child, Agent = "worker", Task = "do it", MaxTurns = ScheduleDefinitionDtoLimitsDtoMaxModelCalls.FromVariant1(default) });
    if (undecided.GetProperty("maxTurns").ValueKind != JsonValueKind.Null) throw new Exception("An undecided turn cap was not sent as null");
    // The turn cap and the agent name ARE bounded by the generated schema, so this SDK refuses
    // them without the TypeScript gate's help; `task` is a bare string shape and is the one
    // request-side bound that is TypeScript-only. UNCOMPILED: no dotnet toolchain here.
    foreach (var badTurns in new[] { 0d, -1d, 1.5d, 2147483648d })
    {
        bool refused = false;
        try { Codec.Input("application.child.run", new ApplicationChildRunRequest { Conversation = "c", Child = child, Agent = "worker", Task = "do it", MaxTurns = ScheduleDefinitionDtoLimitsDtoMaxModelCalls.FromVariant2(badTurns) }); } catch (JsonException) { refused = true; }
        if (!refused) throw new Exception($"Accepted an out-of-bounds turn cap: {badTurns}");
    }
    foreach (var blankAgent in new[] { "", " " })
    {
        bool refused = false;
        try { Codec.Input("application.child.run", new ApplicationChildRunRequest { Conversation = "c", Child = child, Agent = blankAgent, Task = "do it", MaxTurns = ScheduleDefinitionDtoLimitsDtoMaxModelCalls.FromVariant2(3) }); } catch (JsonException) { refused = true; }
        if (!refused) throw new Exception("Accepted a blank agent name");
    }
    // The one that is not bounded here, asserted so the ADR's "one of three" is checkable.
    if (Codec.Input("application.child.run", new ApplicationChildRunRequest { Conversation = "c", Child = child, Agent = "worker", Task = "", MaxTurns = ScheduleDefinitionDtoLimitsDtoMaxModelCalls.FromVariant2(3) }).GetProperty("task").GetString() != "") throw new Exception("A blank task is refused by the TypeScript gate alone");
    using var startedReply = JsonDocument.Parse($$"""{"child":"{{child}}","job":"job_1","conversation":"cnv_child","agent":"worker"}""");
    var started = Codec.Result<ContainedApplicationRunningDto>("application.child.run", startedReply.RootElement);
    // The conversation answered is the child's own, never the container's that was named.
    if (started.Job != "job_1" || started.Conversation != "cnv_child" || started.Agent != "worker" || started.Child != child) throw new Exception("Run reply decoded wrongly");
    foreach (var bad in new[] { $$"""{"child":"{{child}}","job":"job_1","conversation":"cnv_child"}""", $$"""{"child":"{{child}}","job":"job_1","agent":"worker"}""" })
    {
        using var refusedReply = JsonDocument.Parse(bad);
        bool refused = false;
        try { Codec.Result<ContainedApplicationRunningDto>("application.child.run", refusedReply.RootElement); } catch (JsonException) { refused = true; }
        if (!refused) throw new Exception("Incomplete run reply accepted: " + bad);
    }
    var cancelAsked = Codec.Input("application.child.cancel", new ApplicationChildCancelRequest { Conversation = "c", Child = child });
    if (cancelAsked.GetProperty("child").GetString() != child) throw new Exception("Cancel request lost a field");
    using var runAsked = JsonDocument.Parse($$"""{"conversation":"c","child":"{{child}}"}""");
    foreach (var (operation, reply, accepted) in new[] {
        ("application.child.run", $$"""{"child":"{{child}}","job":"job_1","conversation":"cnv_child","agent":"worker"}""", true),
        ("application.child.run", $$"""{"child":"{{foreign}}","job":"job_1","conversation":"cnv_child","agent":"worker"}""", false),
        ("application.child.cancel", $$"""{"child":"{{child}}"}""", true),
        ("application.child.cancel", $$"""{"child":"{{foreign}}"}""", false) })
    {
        using var answer = JsonDocument.Parse(reply);
        bool admitted = true;
        try { Codec.CorrelateDeployment(operation, runAsked.RootElement, answer.RootElement); } catch (JsonException) { admitted = false; }
        if (admitted != accepted) throw new Exception($"Contained reply correlation wrong for {operation} {reply}");
    }
}
{
    // application.child.resources describes a child's source files and never carries their content.
    const string child = "11111111-1111-1111-1111-111111111111", revision = "44444444-4444-4444-4444-444444444444";
    var asked = Codec.Input("application.child.resources", new ApplicationChildResourcesRequest { Conversation = "c", Child = child });
    if (asked.GetProperty("conversation").GetString() != "c" || asked.GetProperty("child").GetString() != child) throw new Exception("Resources request lost a field");
    using var listing = JsonDocument.Parse($$"""{"resources":[{"path":"plowshare.json","kind":"manifest","revision":"{{revision}}","bytes":12}]}""");
    var resources = Codec.Result<ContainedApplicationResourcesDto>("application.child.resources", listing.RootElement).Resources;
    if (resources.Count != 1 || resources[0].Path != "plowshare.json" || resources[0].Kind != "manifest" || resources[0].Revision != revision || resources[0].Bytes != 12) throw new Exception("Resources reply decoded wrongly");
    // The generated schema types bytes as a plain number, so only the TypeScript SDK bounds it to a
    // non-negative integer; this pins what the .NET schema expresses.
    foreach (var bad in new[] { "\"kind\":\"directory\",\"bytes\":12", "\"kind\":\"file\"" })
    {
        using var refusedListing = JsonDocument.Parse("{\"resources\":[{\"path\":\"plowshare.json\",\"revision\":\"" + revision + "\"," + bad + "}]}");
        bool refused = false;
        try { Codec.Result<ContainedApplicationResourcesDto>("application.child.resources", refusedListing.RootElement); } catch (JsonException) { refused = true; }
        if (!refused) throw new Exception("Malformed resources reply accepted: " + bad);
    }
}
const int maximumRelayBytes = 50 * 1024 * 1024;
foreach (var text in new[] { new string('x', maximumRelayBytes), new string('é', maximumRelayBytes / 2) })
{
    var asked = new RelayPublishRequest { Project="fixture", Topic="large.events", RequestId="11111111-1111-1111-1111-111111111111", OccurredAt="2026-10-09T00:00:00Z", Text=text };
    Codec.Input("relay.publish", asked);
    bool refused = false;
    try { Codec.Input("relay.publish", asked with { Text=text + "x" }); } catch (JsonException) { refused=true; }
    if (!refused) throw new Exception("Oversized UTF-8 Relay text accepted");
}
Console.WriteLine(".NET DTO contract checks passed");

await ToolFacadeChecks.RunAsync();
await ConversionGateChecks.RunAsync();
await DrainChecks.RunAsync();

ToolSchemaChecks.Run();

using(var hosted=JsonDocument.Parse(File.ReadAllText(Path.Combine(Path.GetDirectoryName(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_DTO_FIXTURES"))!,"hosted-scope-correlations.json"))))
{
 foreach(var row in hosted.RootElement.EnumerateArray()){
  bool accepted=true;try{Codec.CorrelateDeployment(row.GetProperty("operation").GetString()!,row.GetProperty("request"),row.GetProperty("reply"));}catch(JsonException){accepted=false;}
  if(accepted!=row.GetProperty("valid").GetBoolean())throw new Exception("Hosted coordinates differ");
  if(row.GetProperty("operation").GetString()=="tool.scope.worker.credential"){
   var value=Codec.Result<HostedToolCredentialDto>("tool.scope.worker.credential",row.GetProperty("reply"));
   if(value.ToString().Contains(value.Value,StringComparison.Ordinal))throw new Exception("Hosted secret leaked");
  }
 }
}
await AuthChecks.RunAsync();
