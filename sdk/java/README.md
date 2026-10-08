# Plowshare Java SDK

Java 21 library for `plowshare-v1`. No CLI, MCP, Spring, filesystem provider or server implementation dependencies.

```java
try (var client = new WsServerClient(origin, token)) {
    List<ServerClient.ProjectView> projects = client.projects();
    var orchestration = new OrchestrationClient(client.connection());
    Orchestration.Status status = orchestration.status(runId);
}
```

`WsServerClient` implements typed memory, document, information, conversation, job
and project methods. It connects lazily and is `AutoCloseable`. `AgentClient`,
`AdministrationClient`, `OrchestrationClient`, `UsageClient`, `IncomingClient` and
`OutgoingClient`
use an existing authenticated `Plowshare` connection. Wire JSON parsing stays inside
the SDK; application callers consume validated immutable DTOs. Information and
orchestration input codecs reject unsupported fields and malformed nested values.

`Plowshare.connect(origin, token, timeout, Consumer<ServerPush>)` accepts typed push
notifications. The generic wire request API is package-private. Missing required
values, malformed replies and foreign receipt identities are errors; primitive
counts and flags are never invented from missing reply fields. Optional limits
retain their defined null semantics.

Requests use unique envelope IDs and share one socket, with up to 64 pending calls.
Replies must match ID, type and protocol version. Typed methods throw when the server refuses a request or omits its result. Push callbacks run off
OkHttp's network thread. The queue holds 256 pushes; `droppedPushes()` reports loss,
which callers reconcile through durable reads. Callback exceptions are isolated.

No request is automatically replayed. `TransportException.delivery()` distinguishes
`NOT_SUBMITTED`, `UNKNOWN` and `INVALID_RESPONSE`. Timeout, interruption and connection
loss leave submitted mutations uncertain. Retain operation-level request IDs where
provided. Opening another connection permits new calls; it does not replay prior calls.
The typed facade reconnects for subsequent calls only. Caller-managed token renewal
requires a fresh connection. Bearer tokens travel in the upgrade header, never the URL.

All supported application operations use `/v1/events`. The typed facade's
`uploadImage` uses multipart `POST /v1/images`, the existing server's explicit binary
upload exception. It disables retries and redirects; there is no HTTP fallback for WS.
Authentication/bootstrap and `/v1/files` presence remain separate platform concerns.
The superseded Java CLI/MCP module has been removed. CLI and MCP entrypoints
use TypeScript; Java consumers and external adapters use this SDK.

Build: `./gradlew :plowshare-sdk:build`. Maven coordinates:
`io.aeyer:plowshare-sdk:0.1.0-SNAPSHOT`, with a transitive `plowshare-protocol` artifact.
The artifacts are configured for publication, but have not been published remotely.
To install both locally, run
`./gradlew :plowshare-protocol:publishToMavenLocal :plowshare-sdk:publishToMavenLocal`.

Adapter integrations use `IncomingClient` for durable receive/status/cancel calls
and `OutgoingClient` for outbound work, over the same authenticated WebSocket.
The separate [A2A adapter](../../integrations/a2a/README.md) implements HTTP serving; see
the [receiving manual](../../docs/a2a-receiving.md) for client identities and scope.

Named external tools use the same declaration, handler, durable journal and
read-only reconciliation façade in every language. See [Relay tools](../../docs/relay-tools.md)
for configuration, grants, examples and recovery semantics.
