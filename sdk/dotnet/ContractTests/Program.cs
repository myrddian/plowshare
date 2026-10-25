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
        ("application.child.destroy", $$"""{"child":"{{child}}","collected":["{{child}}"],"sources":2}""", false) })
    {
        using var answer = JsonDocument.Parse(reply);
        bool admitted = true;
        try { Codec.CorrelateDeployment(operation, containedAsked.RootElement, answer.RootElement); } catch (JsonException) { admitted = false; }
        if (admitted != accepted) throw new Exception($"Contained reply correlation wrong for {operation} {reply}");
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
