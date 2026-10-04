# Plowshare C# SDK

`Plowshare.Sdk` targets .NET 8+ and depends only on the framework's WebSocket and
JSON implementations. Build a local NuGet package with
`dotnet pack Plowshare.Sdk -o ../build/distributions`. Nothing is published remotely.

```csharp
using Plowshare.Sdk;

await using var client = await Client.ConnectAsync(origin, token);
var reply = await client.RequestAsync("project.list");
var projects = reply.RequirePayload();
```

`Protocol.Operations` is generated from the shared WS catalog. `RequestAsync`
accesses every registered operation and retains the complete JSON envelope,
nullable values, opaque results and future fields. Conversation, agent-run, job and
outgoing-message convenience methods use the same transport. Refusals remain replies;
`RequirePayload` explicitly raises `RefusalException`. `ACCEPTED` is not completion.

Bearer credentials use upgrade headers; redirects, credential persistence, refresh,
HTTP application fallback and implicit reconnect are absent. Requests are bounded
to 64 outstanding and a 30-second default deadline. Transport failures expose
`Delivery`. Caller cancellation raises `RequestCancelledException`, retaining both
the cancellation token and delivery uncertainty. No mutation is automatically replayed.

`Pushes` is a bounded 256-entry `ChannelReader<JsonElement>` for bare/enveloped
notifications; `DroppedPushes` counts overflow. Reconcile through durable WS reads.
Retain caller-created outgoing request UUIDs before sending. Filesystem/process
presence and binary uploads are optional platform concerns outside this SDK.
See [the SDK contract and roadmap](../docs/sdks.md).
