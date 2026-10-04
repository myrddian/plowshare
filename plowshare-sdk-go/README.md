# Plowshare Go SDK

Go 1.23+ library. The module path is `io.aeyer/plowshare/sdk`; this checkout is
not hosted at a Go module discovery endpoint or published to a module registry.
Until release hosting is configured, consumers use a local `replace` directive:

```go
replace io.aeyer/plowshare/sdk => /path/to/plowshare/plowshare-sdk-go
```

```go
client, err := plowshare.Connect(ctx, origin, token, plowshare.Options{})
if err != nil { return err }
defer client.Close()
reply, err := client.Request(ctx, "project.list", nil)
```

`Operations()` returns the generated WS catalog. `Request` covers every registered
operation; helpers cover conversations, runs, jobs and outgoing messages. `Reply.Raw`
preserves the full envelope and unknown fields; `Outcome` retains the code, nullable
sentence and raw payload. `RequirePayload` explicitly returns a refusal error.
`ACCEPTED` indicates pending work and is never converted to completed work.

Tokens are caller-managed and sent in upgrade headers. Redirects, HTTP application
fallback, credential persistence, implicit reconnect and mutation replay are absent.
Requests use caller contexts plus a 30-second default deadline, with at most 64
outstanding. `TransportError.Delivery` distinguishes not submitted, unknown and
invalid responses. `errors.Is` retains the underlying context cancellation/deadline.
Retain request UUIDs before outgoing submission; recovery is an explicit caller action.

`Pushes()` is a 256-entry channel; `DroppedPushes()` counts overflow. Durable reads
reconcile dropped notifications. Filesystem/process presence and binary uploads
are optional platform concerns outside this SDK. See [the SDK contract](../docs/sdks.md).
