// Exercise the published typed API against the shared deterministic WebSocket fixture.
using System.Text.Json;
using Plowshare.Sdk;

static void Check(bool valid, string message) { if (!valid) throw new Exception(message); }
var origin = args[0];
using var fixture = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_FIXTURES")!));
using var catalog = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_CATALOG")!));
Check(Protocol.Operations.SequenceEqual(catalog.RootElement.GetProperty("operations").EnumerateArray().Select(v => v.GetString()!)), "catalog differs");
try { await using var invalid = await Client.ConnectAsync(origin + "/wrong", "sdk-fixture-token"); throw new Exception("invalid origin accepted"); } catch (ArgumentException) { }
try { await using var redirect = await Client.ConnectAsync(origin, "sdk-redirect-fixture", "dotnet-redirect", TimeSpan.FromMilliseconds(300)); throw new Exception("redirect followed"); } catch (TransportException e) { Check(e.Delivery == Delivery.NotSubmitted, "wrong redirect state"); }
async Task<Client> Open(string name) => await Client.ConnectAsync(origin, "sdk-fixture-token", "typed-dotnet/" + name, TimeSpan.FromMilliseconds(300));
await using (var client = await Open("multiplex"))
{
    var one = client.ProjectListAsync(new ProjectListRequest());
    var two = client.ProjectListAsync(new ProjectListRequest());
    var results = await Task.WhenAll(one, two);
    Check(results[0].RequirePayload()[0].Name == "first" && results[1].RequirePayload()[0].Name == "second", "correlation failed");
}
var tests = fixture.RootElement.GetProperty("cases").EnumerateArray().Select(t => (Name: t.GetProperty("name").GetString()!, Delivery: t.TryGetProperty("delivery", out var d) ? d.GetString() : null)).ToList();
tests.Add(("malformed-nested", "INVALID_RESPONSE")); tests.Add(("missing-payload", "INVALID_RESPONSE"));
foreach (var test in tests)
{
    await using var client = await Open(test.Name);
    try
    {
        var reply = await client.ProjectListAsync(new ProjectListRequest());
        Check(test.Delivery is null, "invalid response became success");
        if (test.Name == "refusal")
        {
            try { reply.RequirePayload(); throw new Exception("refusal became success"); }
            catch (RefusalException e) { Check(e.Code == "CONFLICT" && e.Said == "fixture refusal", "refusal lost"); }
        }
        else Check(reply.RequirePayload()[0].Name == test.Name, "wrong typed result");
    }
    catch (TransportException e)
    {
        var delivery = e.Delivery switch { Delivery.Unknown => "UNKNOWN", Delivery.InvalidResponse => "INVALID_RESPONSE", _ => "NOT_SUBMITTED" };
        Check(delivery == test.Delivery, "wrong delivery: " + test.Name);
    }
    if (test.Name == "push")
    {
        var bare = await client.Pushes.ReadAsync(); Check(bare.Notification.Variant3.IsSet && bare.Notification.Variant3.Value.Unread == 1, "invalid push escaped");
        var envelope = await client.Pushes.ReadAsync(); Check(envelope.Notification.Variant11.IsSet && envelope.Notification.Variant11.Value.Type == "usage.closed", "enveloped hint missing");
    }
    if (test.Name == "disconnect")
    {
        await client.Pushes.Completion.WaitAsync(TimeSpan.FromSeconds(1));
        try { await client.ProjectListAsync(new ProjectListRequest()); throw new Exception("closed client submitted"); }
        catch (TransportException e) { Check(e.Delivery == Delivery.NotSubmitted, "wrong closed state"); }
    }
}
await using (var client = await Open("invalid-input"))
{
    try { await client.JobStatusAsync(new JobStatusRequest { Job = "\0bad" }); throw new Exception("invalid identifier submitted"); } catch (JsonException) { }
}
await using (var client = await Open("cancel"))
{
    using var cancel = new CancellationTokenSource();
    var task = client.ProjectListAsync(new ProjectListRequest(), cancel.Token);
    var ack = await client.Pushes.ReadAsync(); Check(ack.Notification.Variant3.IsSet, "submission hint missing");
    cancel.Cancel();
    try { await task; throw new Exception("cancelled request succeeded"); }
    catch (RequestCancelledException e) { Check(e.Delivery == Delivery.Unknown && e.CancellationToken == cancel.Token, "cancellation lost delivery"); }
}
Console.WriteLine(".NET typed SDK conformance passed");
