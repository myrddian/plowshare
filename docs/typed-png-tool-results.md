# Typed image tool results

The Node provider path supports ordered text and admitted still PNG, JPEG and WebP references through
the existing invocation envelopes and journal lifecycle. Results remain on Relay
TEXT topics; immutable bytes remain in `BinaryBlobStorage`. This slice does not
itself define model or Trajectory ownership. The separate
[active-loop slice](trajectory-image-observations.md) now supplies those boundaries.

The format extension is implemented under [Decision 0051](decisions/0051-typed-still-image-formats.md).
Node provides `admitToolImage`, `imageToolResult` and `toolImagePart`; the existing
PNG helpers remain available and require PNG. The server determines actual MIME
from validated bytes. No caller MIME is added to the tool envelope.

## Contract and negotiation

Set `resultProfile: "content/1"` on the operator's tool binding and the Node
`ToolDeclaration`. It is part of the pinned binding and invocation fingerprint.
Old declarations still use their existing v1/v2 requests and v1 text results.
Content-capable provider catalogues use `plowshare-tool-catalog/3`; v1/v2 catalogues
cannot advertise a content profile. Deployment bindings and non-hosted Application
catalogues are supported. Hosted worker catalogues explicitly refuse this profile
until their worker protocol supports typed reconciliation. Other producer SDKs
continue to author text results.

Negotiated requests use `plowshare-tool/3` with `resultProfile: "content/1"`.
Nested input schemas retain their existing fingerprint; scalar argument validation
retains its existing declaration. The provider checks both contracts before
acknowledging/executing a request. A v3 result carries `content` in place of its
wire `text` field. Its local/server text accessor is the exact textual projection.
There is no automatic JSON/prose detection or conversion of existing topics.

```json
{
  "version": "content/1",
  "parts": [
    {"kind":"TEXT","text":"Captured page.","imageId":null,"ordinal":null,"use":null},
    {"kind":"IMAGE","text":null,"imageId":"img_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ordinal":0,"use":"OBSERVATION"}
  ]
}
```

The example ID is illustrative. An IMAGE identifies the existing scoped image ID
and exact admission ordinal in the enclosing invocation. It contains no bytes,
URL, path or caller-selected scope. Actual format, geometry, full SHA-256 and
length come from the existing server-validated image admission receipt.

`use` is `OBSERVATION`, `OUTPUT` or `BOTH`. It records intended use and grants no
authority. Parts preserve order. A body has 1–16 parts, at most four distinct
image slots, and at most 16,384 UTF-16 units across text parts. The complete encoded
envelope retains the existing 32,768-unit limit. Image-only completed results have
an empty text projection. REJECTED/UNKNOWN results can carry text only. Unknown
kinds, malformed references and conflicting duplicate results are refused.

## Ownership and retained reads

Before settling an invocation, the existing repository locks that invocation and
validates all referenced admission slots in one bounded join. Image ID, full-digest
alias, byte length, READY registry state and the exact invocation-admission claim
must match. Result and admission share the retained invocation lifetime, so no
second result claim or execution ledger is introduced. Rollback leaves the
invocation unsettled and retains its original admission. Disk integrity is checked
when bytes are resolved; SQL is not held across binary I/O.

The result body is stored in the existing invocation result column. Typed equality
includes part order, slot, image ID and use. Identical retries return the retained
result; a changed body conflicts. Relay expiry does not remove that result or its
admission claim. Existing invocation deletion releases its admission claim; image
retention pins remain independent. No new database migration is needed.

`tool.invocation.read` accepts `{project, invocation}` and reuses the existing
owner-scoped non-submitting reconciliation service. It returns `id`, `state`,
`text`, and optional `content`. It authorizes current project/owner access and
retained provider provenance; an invocation ID alone grants no access. Pending or
unverified results return the existing UNKNOWN explanation. All generated SDKs
validate typed reads. Java also exposes `readToolInvocation`; Node/TypeScript use
the existing typed `request` method. This read does not disclose binary bytes.

## Node provider path

Use `admitToolPng` with an explicitly configured server origin/bearer and captured
bytes. It reuses the invocation admission HTTP endpoint and deterministic slot,
verifies the returned digest/length and refuses redirects/replay. Existing
`plowshare.images.invocation-admission-enabled` and producer authority still apply.

The handler can assemble its result as:

```typescript
const image = await admitToolPng(origin, bearer, call, 0, capturedPng, 'OBSERVATION');
return pngToolResult(
  call,
  [toolTextPart('Captured page.'), image.part],
  [image.receipt],
);
```

These helpers are exported by `plowshare-client-node` and its `tools` module.
`ToolProvider` saves the ordered content and complete admission receipts in its
existing READY checkpoint before publishing. Receipt evidence stays in the local
journal; only the compact content body enters the wire envelope. A receipt must
match the exact invocation, project, owner, producer and image slot.

`FileToolJournal.open(absoluteDirectory, identity)` is a bounded private adapter
for the existing `ToolJournal` interface. Derive `identity` using
`nodeToolHost.sha256(toolDeploymentConfig(binding, declarations))`. Hold the
existing `localLock` from `plowshare-client-node/connections` around the journal
and provider's entire lifetime. The journal uses private atomic writes plus
directory forcing, retains at most 1,000 receipts / 2 MB, and stores no image bytes
or bearer. An uncertain save fences the instance until it is reopened, avoiding
an overwrite from stale memory. When the journal reaches its bound, further writes refuse. Any application-owned
archive/rotation strategy must preserve completed-invocation deduplication; this
slice does not add automatic pruning.

Reopen and call `provider.reconcile()` after uncertain publication. It reads the
original deterministic result identity and compares the original content before
settling the journal. It does not repeat capture or publication automatically.
After READY, restart preserves the exact ordered body and receipts. A crash after
execution/capture intent but before a durable READY checkpoint remains UNKNOWN;
this slice introduces no capture spool or automatic handler recovery. Existing
admission status can reconcile an uncertain upload's original slot.

## Model and Trajectory boundary

Content-capable Relay tools now use the separate [active-loop and Trajectory
implementation](trajectory-image-observations.md). It retains original/effective
content and independent entry claims, then resolves authorized pixels for native
model input under verified context admission.

String-only callers, scripts and the invocation-read tool keep their explicit text
fallback, stating that pixels were not supplied to the active model. Typed
redemption, other producer SDKs and hosted worker content remain separate work.
