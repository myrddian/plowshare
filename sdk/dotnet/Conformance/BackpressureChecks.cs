using System.Text.Json;
using Plowshare.Sdk;

// Drives every shared capacity case over the public typed client. The fixture also
// checks frame counts, same-ID resubmission, FIFO order and refused negotiations;
// these caller-side assertions establish deadlines and honest delivery outcomes.
internal static class BackpressureChecks
{
    private static void Check(bool valid, string message)
    {
        if (!valid) throw new Exception(message);
    }

    private static string DeliveryName(Delivery delivery) => delivery switch
    {
        Delivery.NotSubmitted => "NOT_SUBMITTED",
        Delivery.Unknown => "UNKNOWN",
        Delivery.InvalidResponse => "INVALID_RESPONSE",
        _ => throw new ArgumentOutOfRangeException(nameof(delivery))
    };

    private static async Task FailureAsync(Task call, JsonElement test, string field = "delivery", bool deadline = false)
    {
        try { await call; throw new Exception("Expected transport failure: " + test.GetProperty("name").GetString()); }
        catch (TransportException error)
        {
            var honest = new List<string?> { test.GetProperty(field).GetString() };
            if (test.TryGetProperty("deliveryAlternates", out var alternates))
                honest.AddRange(alternates.EnumerateArray().Select(value => value.GetString()));
            Check(honest.Contains(DeliveryName(error.Delivery)), "Wrong capacity delivery: " + error.Delivery);
            Check(!deadline || error.Message.Contains("deadline", StringComparison.OrdinalIgnoreCase), "A teardown must not pass as deadline expiry");
        }
    }

    private static void Empty(Reply<IReadOnlyList<ProjectViewDto>> reply) =>
        Check(reply.RequirePayload().Count == 0, "A capacity refusal lost its eventual answer");

    internal static async Task RunAsync(string origin, JsonElement cases)
    {
        var failures = new List<Exception>();
        foreach (var test in cases.EnumerateArray().Where(row => row.TryGetProperty("capacity", out var flag) && flag.GetBoolean()))
        {
            var name = test.GetProperty("name").GetString()!;
            Task<Client> Open(TimeSpan? timeout = null) => Client.ConnectAsync(origin, "sdk-fixture-token",
                "typed-dotnet/" + name, timeout ?? TimeSpan.FromSeconds(5));
            try
            {
                switch (name)
                {
                    case "capacity-refuse-once":
                    {
                        await using var client = await Open();
                        Empty(await client.ProjectListAsync(new ProjectListRequest()));
                        break;
                    }
                    case "capacity-refuse-always":
                    {
                        await using var client = await Open(TimeSpan.FromMilliseconds(600));
                        await FailureAsync(client.ProjectListAsync(new ProjectListRequest()), test, deadline: true);
                        break;
                    }
                    case "capacity-window-one":
                    {
                        await using var client = await Open();
                        var first = client.ProjectListAsync(new ProjectListRequest());
                        Check((await client.Pushes.ReadAsync()).Notification.Variant3.IsSet, "Window-full hint missing");
                        var second = client.ProjectListAsync(new ProjectListRequest());
                        await Task.Yield();
                        Check(!second.IsCompleted, "The second request must park behind the held request");
                        Empty(await first);
                        Empty(await second);
                        break;
                    }
                    case "capacity-slot-order":
                    {
                        await using var client = await Open();
                        var first = client.ProjectListAsync(new ProjectListRequest());
                        var second = client.ProjectListAsync(new ProjectListRequest());
                        Empty(await first);
                        Empty(await second);
                        break;
                    }
                    case "capacity-deadline-survives-waiting":
                    {
                        await using var client = await Open();
                        var held = client.ProjectListAsync(new ProjectListRequest());
                        Check((await client.Pushes.ReadAsync()).Notification.Variant3.IsSet, "Window-full hint missing");
                        var probe = Task.Delay(test.GetProperty("waitingProbeMs").GetInt32());
                        var waiting = client.ProjectListAsync(new ProjectListRequest(), options: new RequestOptions
                        { Timeout = TimeSpan.FromMilliseconds(test.GetProperty("waitingTimeoutMs").GetInt32()) });
                        var sibling = client.ProjectListAsync(new ProjectListRequest());
                        try
                        {
                            Empty(await held);
                            Check(await Task.WhenAny(waiting, probe) == waiting, "Waiting request lost its own transmit-time deadline");
                            await FailureAsync(waiting, test, "waitingDelivery", deadline: true);
                            Check(!sibling.IsCompleted, "Connection-timeout sibling must survive the waiting request's expiry");
                            // Allow the newly admitted sibling to transmit before close. The
                            // shared fixture requires three distinct frames, not a timed guess.
                            Check(await Task.WhenAny(sibling, Task.Delay(300)) != sibling, "Silent fixture unexpectedly answered the sibling");
                        }
                        finally
                        {
                            await client.DisposeAsync();
                            await FailureAsync(sibling, test, "waitingDelivery");
                        }
                        break;
                    }
                    case "capacity-handshake-refuse":
                        await FailureAsync(Open(), test);
                        break;
                    case "capacity-non-blocking":
                    {
                        await using var client = await Open();
                        var options = new RequestOptions { WaitForCapacity = false };
                        var refused = await client.ProjectListAsync(new ProjectListRequest(), options: options);
                        Check(refused.Code == test.GetProperty("surfacedCode").GetString(), "Explicit non-waiting form absorbed the refusal");
                        var held = client.ProjectListAsync(new ProjectListRequest());
                        Check((await client.Pushes.ReadAsync()).Notification.Variant3.IsSet, "Window-full hint missing");
                        await FailureAsync(client.ProjectListAsync(new ProjectListRequest(), options: options), test);
                        Check(!held.IsCompleted, "Non-waiting form waited for a full window");
                        Empty(await held);
                        break;
                    }
                    case "capacity-close-while-parked":
                    {
                        await using var client = await Open();
                        var held = client.ProjectListAsync(new ProjectListRequest());
                        Check((await client.Pushes.ReadAsync()).Notification.Variant3.IsSet, "Window-full hint missing");
                        var parked = client.ProjectListAsync(new ProjectListRequest());
                        await Task.Yield();
                        Check(!parked.IsCompleted, "Full window refused instead of parking");
                        await FailureAsync(held, test);
                        try { await parked; throw new Exception("Closed parked request succeeded"); }
                        catch (TransportException error) { Check(error.Delivery == Delivery.NotSubmitted, "A parked request was never transmitted"); }
                        break;
                    }
                    default:
                        throw new Exception("Undriven shared capacity case: " + name);
                }
            }
            catch (Exception error) { failures.Add(new Exception(name, error)); }
        }
        if (failures.Count != 0) throw new AggregateException(".NET dispatch backpressure cases failed", failures);
    }
}
