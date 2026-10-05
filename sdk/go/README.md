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
fallback, credential persistence, implicit reconnect and mutation replay are absent.
Requests use caller contexts plus a 30-second default deadline, with at most 64
outstanding. `TransportError.Delivery` distinguishes not submitted, unknown and
invalid responses. `errors.Is` retains the underlying context cancellation/deadline.
Retain request UUIDs before outgoing submission; recovery is an explicit caller action.

`Pushes()` is a 256-entry channel of validated `ServerPush` DTOs; `DroppedPushes()` counts overflow. Durable reads
reconcile dropped notifications. Filesystem/process presence and binary uploads
are optional platform concerns outside this SDK. See [the SDK contract](../../docs/sdks.md).

Follow [the native SDK standard](../../docs/native-sdk-standards.md), including Go vet and race-enabled checks.
