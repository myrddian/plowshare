using System.Text.Json;
using System.Text.Json.Nodes;
using Plowshare.Sdk;
static void Check(bool value, string message) { if (!value) throw new Exception(message); }
var fixture = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_FIXTURES")!)).RootElement;
var origin = args[0];
var catalog = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_CATALOG")!)).RootElement;
Check(Protocol.Operations.SequenceEqual(catalog.GetProperty("operations").EnumerateArray().Select(operation => operation.GetString()!)), "Incomplete operation catalog");
try { await Client.ConnectAsync(origin + "/wrong", "sdk-fixture-token"); throw new Exception("Invalid origin accepted"); } catch (ArgumentException) { }
try { await Client.ConnectAsync(origin, "sdk-redirect-fixture", "dotnet-redirect", TimeSpan.FromMilliseconds(300)); throw new Exception("Redirect followed"); }
catch (TransportException error) { Check(error.Delivery == Delivery.NotSubmitted, "Upgrade submitted work"); }
await using var client = await Client.ConnectAsync(origin, "sdk-fixture-token", "dotnet", TimeSpan.FromMilliseconds(300));
var tasks = await Task.WhenAll(client.RequestAsync("project.list", new { scenario = "multiplex-one" }), client.RequestAsync("project.list", new { scenario = "multiplex-two" }));
Check(tasks[0].RequirePayload().GetProperty("sequence").GetInt32() == 1 && tasks[1].RequirePayload().GetProperty("sequence").GetInt32() == 2, "Replies miscorrelated");
foreach (var test in fixture.GetProperty("cases").EnumerateArray())
{
    var name = test.GetProperty("name").GetString()!;
    try
    {
        var reply = await client.RequestAsync("project.list", new { scenario = name });
        Check(!test.TryGetProperty("delivery", out _), "Uncertain work became successful");
        Check(JsonNode.DeepEquals(JsonNode.Parse(reply.Outcome.GetRawText()), JsonNode.Parse(test.GetProperty("response").GetRawText())), "Opaque outcome changed");
        Check(reply.Raw.GetProperty("futureEnvelope").GetBoolean(), "Future envelope field lost");
        if (name == "success") Check(reply.RequirePayload().GetProperty("nullable").ValueKind == JsonValueKind.Null, "Null coerced");
        if (name == "refusal") { try { reply.RequirePayload(); throw new Exception("Refusal became success"); } catch (RefusalException) { } }
    }
    catch (TransportException error)
    {
        Check(test.GetProperty("delivery").GetString() == (error.Delivery == Delivery.InvalidResponse ? "INVALID_RESPONSE" : error.Delivery == Delivery.Unknown ? "UNKNOWN" : "NOT_SUBMITTED"), "Wrong delivery state: " + name);
    }
}
Check((await client.Pushes.ReadAsync()).GetProperty("kind").GetString() == "fixture-push", "Bare push lost");
Check((await client.Pushes.ReadAsync()).GetProperty("type").GetString() == "usage.snapshot", "Enveloped push lost");
try { await client.RequestAsync("project.list"); throw new Exception("Closed client submitted work"); } catch (TransportException error) { Check(error.Delivery == Delivery.NotSubmitted, "Closed delivery unknown"); }
await using var cancelled = await Client.ConnectAsync(origin, "sdk-fixture-token", "dotnet-cancel");
using var cancellation = new CancellationTokenSource();
var abandoned = cancelled.RequestAsync("project.list", new { scenario = "cancel" }, cancellation.Token);
Check((await cancelled.Pushes.ReadAsync()).GetProperty("kind").GetString() == "fixture-submitted", "Cancel fixture not submitted");
cancellation.Cancel();
try { await abandoned; throw new Exception("Cancelled request returned success"); }
catch (RequestCancelledException error) { Check(error.Delivery == Delivery.Unknown && error.CancellationToken == cancellation.Token, "Cancellation lost delivery state"); }
Console.WriteLine("C# SDK conformance passed");
