# Plowshare C# SDK

`Plowshare.Sdk` targets .NET 8+ and depends only on the framework's WebSocket and
JSON implementations. Build a local NuGet package with
`dotnet pack Plowshare.Sdk -o ../../build/distributions`. Nothing is published remotely.

```csharp
using Plowshare.Sdk;

await using var client = await Client.ConnectAsync(origin, token);
var reply = await client.ProjectListAsync(new ProjectListRequest());
var projects = reply.RequirePayload();
```

`Protocol.Operations` is generated from the shared WS catalog. Each operation has
a concrete request record and returns `Reply<Result>`. `RequirePayload` returns
the validated DTO or raises `RefusalException` with its code and text. Nested
records and closed unions replace raw JSON; union factories select typed variants.
A default `Optional<T>` field is omitted; a selected `Unit` variant represents
explicit JSON null. Unknown output fields are omitted. Malformed inputs fail
before submission. `ACCEPTED` is not completion.

Bearer credentials use upgrade headers; redirects, credential persistence, refresh,
HTTP application fallback and implicit reconnect are absent. Requests are bounded
to 64 outstanding and a 30-second default deadline. Transport failures expose
`Delivery`. Caller cancellation raises `RequestCancelledException`, retaining both
the cancellation token and delivery uncertainty. No mutation is automatically replayed.

`Pushes` is a bounded 256-entry `ChannelReader<ServerPush>` for bare/enveloped
notifications; `DroppedPushes` counts overflow. Reconcile through durable WS reads.
Retain caller-created outgoing request UUIDs before sending. Filesystem/process
presence and binary uploads are optional platform concerns outside this SDK.
See [the SDK contract and roadmap](../../docs/sdks.md).

Follow [the native SDK standard](../../docs/native-sdk-standards.md), including nullable and warnings-as-errors compilation.

Named external tools use the same declaration, handler, durable journal and
read-only reconciliation façade in every language. See [Relay tools](../../docs/relay-tools.md)
for configuration, grants, examples and recovery semantics.
