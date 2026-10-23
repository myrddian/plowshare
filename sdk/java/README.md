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

Runtime admission requires an explicit destination. Pass a non-null project to
`openConversation`, `uploadImage`, fresh `AgentClient.Run` work
and `OrchestrationClient.start`. To continue an existing conversation, supply its
ID and omit the project; conflicting selectors fail locally. Personal clients
must deliberately select their own Personal project identity. External systems
use a configured named project and an Application/service credential.
`conversations(null)` explicitly reads Global history and cannot create work. See
[explicit execution scope](../../docs/sdks.md#explicit-execution-scope).

`Plowshare.connect(origin, token, timeout, Consumer<ServerPush>)` accepts typed push
notifications. The generic wire request API is package-private. Missing required
values, malformed replies and foreign receipt identities are errors; primitive
counts and flags are never invented from missing reply fields. Optional limits
retain their defined null semantics.

Requests use unique envelope IDs and share one socket, with up to 64 pending calls.
Replies must match ID, type and protocol version. Typed methods throw when the server refuses a request or omits its result. Push callbacks run off
OkHttp's network thread. The queue holds 256 pushes; `droppedPushes()` reports loss,
which callers reconcile through durable reads. Callback exceptions are isolated.

The server advertises the connection's dispatch window and applies backpressure
within it, and `RequestOptions.Capacity` is where a call site chooses how to meet a
full one. The waiting form is the default: the call does not complete until the
request has been transmitted, a full window parks the caller, and a server
`CAPACITY_EXHAUSTED` reply is absorbed and resubmitted under the same request id
after a jittered pause (100ms base, doubling, 5s cap), so a caller sees a reply or
a timeout and never the code. `RequestOptions.nonBlocking(timeout)`, or
`withoutCapacityWait()` on options that already carry a cancellation, selects the
fail-fast form for a caller that would rather shed load than be held: a full window
throws `TransportException` with `NOT_SUBMITTED` in the same call, and a refusal is
returned as the request's own outcome with no backoff and no resubmission. In both
forms **the request deadline arms when the frame is written, exactly once**: the
wait for a window slot and the wait in the writer queue are charged against
nothing, and a resubmission runs against what is left of the original deadline
rather than a fresh one, so repeated refusals end in the caller's own timeout.
Interrupt a blocking caller, or fire the `RequestCancellation` in its
`RequestOptions`, to leave a wait for capacity; that reports `NOT_SUBMITTED`,
because nothing was written. The dispatch negotiation is never resubmitted, in
either form.

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

## Optional saved user login

```java
var store = Credentials.resolve(origin, account, connectionName, null);
var session = store.authenticated(null); // null selects native HTTP auth
try (var client = new WsServerClient(session.origin(), session.tokens().access())) {
    var projects = client.projects();
}
```

`Credentials` also accepts an explicit account/directory. `UserAuthentication`
is the typed login, status, refresh and logout capability, with native
`HttpAuthentication`. Existing `AuthClient.login` remains access-only and compatible.
`session()` reads local state; `authenticated(null)` checks server status and renews
only after explicit rejection. `renew(auth, rejectedAccess)` reloads under the
shared lock and reuses a pair already replaced by another client; null rejected
access explicitly rotates. Passwords are never saved and token diagnostics redact
secrets.

The native registry, private version-1 files, locks and migration/logout markers
interoperate with Node, Python, Go and .NET. Every failed refresh conservatively
requires fresh login. Interruption and failed persistence never replay refresh; an
unproven final write retains its lock. Never steal stale locks. Renewal does not
reconnect or replay application operations. Service credentials stay explicit,
and application recovery preserves original UUIDs and payloads. See
[the native auth contract](../../docs/decisions/0036-native-sdk-shared-login.md).
