# Plowshare Java SDK

Java 21 library for `plowshare-v1`. No CLI, MCP, Spring, filesystem provider or server implementation dependencies.

```java
try (var sdk = Plowshare.connect(origin, token, Duration.ofSeconds(30), push -> {
    // Job events/deltas and enveloped notifications retain their complete JSON.
})) {
    var reply = sdk.request("project.list", Map.of());
    JsonNode projects = reply.requirePayload();
    // reply.code(), said(), raw() preserve the server's outcome and complete envelope JSON.
}
```

`io.aeyer.plowshare.sdk.WsServerClient` supplies typed memory, document, conversation,
job and project methods. It connects lazily, implements `ServerClient`, and is
`AutoCloseable`. Job types include conversation identity, limits, resumability and
pace. Nullable limits mean unlimited/absent; they never become zero.
`connection().request(...)` exposes the entire registered WS surface, including
operations beyond the typed convenience methods. `OutgoingClient` supplies typed
external-work operations over an existing `Plowshare` connection.

Requests use unique envelope IDs and share one socket, with up to 64 pending calls.
Replies must match ID, type and protocol version. Refusals remain structured replies;
`requirePayload()` throws when refused or missing a payload. Push callbacks run off
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
The old Java CLI/MCP facades delegate to this library; `HttpServerClient` remains an
explicit legacy compatibility class, unused by their production entrypoints.

Build: `./gradlew :plowshare-sdk:build`. Maven coordinates:
`io.aeyer:plowshare-sdk:0.1.0-SNAPSHOT`, with a transitive `plowshare-protocol` artifact.
The artifacts are configured for publication, but have not been published remotely.
To install both locally, run
`./gradlew :plowshare-protocol:publishToMavenLocal :plowshare-sdk:publishToMavenLocal`.

Adapter integrations use `IncomingClient` for durable receive/status/cancel calls
and `OutgoingClient` for outbound work, over the same authenticated WebSocket.
The separate [A2A adapter](../plowshare-a2a/README.md) implements HTTP serving; see
the [receiving manual](../docs/a2a-receiving.md) for client identities and scope.
