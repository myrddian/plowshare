# Plowshare Go SDK

Go 1.23+ library. The module path is `io.aeyer/plowshare/sdk`; this checkout is
not hosted at a Go module discovery endpoint or published to a module registry.
Until release hosting is configured, consumers use a local `replace` directive:

```go
replace io.aeyer/plowshare/sdk => /path/to/plowshare/sdk/go
```

```go
client, err := plowshare.Connect(ctx, origin, token, plowshare.Options{})
if err != nil { return err }
defer client.Close()
reply, err := client.ProjectList(ctx, plowshare.ProjectListRequest{})
```

`Operations()` returns the generated WS catalog. Every operation takes a concrete
request struct and returns `Reply[Result]`. `RequirePayload` returns the validated
result or a `RefusalError` with its code and text. Nested structs and closed unions
replace raw JSON; select exactly one union variant. A nil optional pointer means
omitted; a selected `Unit` variant represents explicit JSON null. Unknown output
fields are omitted. Malformed inputs fail before submission. `ACCEPTED` indicates
pending work and is never converted to completed work.

Tokens are caller-managed and sent in upgrade headers. Redirects, HTTP application
fallback, implicit reconnect and mutation replay are absent from the WS client.
Requests use caller contexts plus a 30-second default deadline, with at most 64
outstanding. `TransportError.Delivery` distinguishes not submitted, unknown and
invalid responses. `errors.Is` retains the underlying context cancellation/deadline.
Retain request UUIDs before outgoing submission; recovery is an explicit caller action.

`Pushes()` is a 256-entry channel of validated `ServerPush` DTOs; `DroppedPushes()` counts overflow. Durable reads
reconcile dropped notifications. Filesystem/process presence and binary uploads
are optional platform concerns outside this SDK. See [the SDK contract](../../docs/sdks.md).

Follow [the native SDK standard](../../docs/native-sdk-standards.md), including Go vet and race-enabled checks.

Named external tools use the same declaration, handler, durable journal and
read-only reconciliation façade in every language. See [Relay tools](../../docs/relay-tools.md)
for configuration, grants, examples and recovery semantics.

## Optional saved user login

```go
store, err := plowshare.ResolveCredentials(origin, account, connection, plowshare.CredentialOptions{})
if err != nil { return err }
session, err := store.Authenticated(ctx, nil) // nil selects native HTTP auth
if err != nil { return err }
client, err := plowshare.Connect(ctx, session.Origin, session.Tokens.Access, plowshare.Options{})
```

`NewCredentials` also accepts an explicit account/directory. `Login`, `Session`,
`Renew`, `Authenticated` and `Logout` use typed results and the optional
`Authentication` interface. `NewHttpAuthentication` permits explicit timeout and
cookie names. `Renew(ctx, auth, rejectedAccess)` reuses a newer pair if another
client already replaced that rejected token; an empty rejected access means
explicit rotation. `Session` reads local state only. `Authenticated` renews only
after an explicit session rejection, without reconnecting application sockets.

State, registry selection, migration and logout fences are shared with Node,
Python, .NET and Java. Cancellation, failed refresh or persistence never blindly
replay refresh. All failed refreshes conservatively require fresh login; a failed
final write retains its lock. Never steal stale locks. Service credentials stay
caller-managed and cannot fall back to this store. Retain original mutation UUIDs
and payloads for explicit application recovery. See [the native auth contract](../../docs/decisions/0036-native-sdk-shared-login.md).
