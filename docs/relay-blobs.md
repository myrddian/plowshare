# Relay binary delivery

Relay supports project-scoped, fixed-family `BLOB` topics alongside `TEXT` topics.
Bytes use authenticated HTTP; existing WebSocket `relay.consume`, `relay.ack` and
Relay log reads carry a bounded descriptor. Text publication and delivery retain
their existing contracts. [Decision 0048](decisions/0048-relay-blob-delivery.md)
records the ownership and compatibility boundaries.

## Configuration and authority

Binary publication is opt-in. These are separate from the existing text allowance:

| Property | Default | Bounds |
| --- | --- | --- |
| `plowshare.relay.blobs.enabled` | `false` | Enables publication; retained reads remain available when disabled. |
| `plowshare.relay.blobs.max-bytes` | `5242880` | 1 through 50 MiB per publication. |
| `plowshare.relay.blobs.max-concurrent` | `4` | 1 through 32 simultaneous service transfers. |
| `plowshare.blobs.max-bytes` | `52428800` | Positive storage verification ceiling; configure at least the Relay allowance. |

The server also enforces its multipart request limits. Configure those to admit
the selected byte allowance plus multipart metadata; raising the Relay allowance
alone does not raise the HTTP request limit. Storage uses the larger of its own
ceiling and the existing image allowance, preserving image uploads.

Use existing `plowshare.relay.ports.bindings` for the exact project, topic,
authenticated account and direction. Egress requires an allowed group. Current
project membership and Personal-home ownership are checked as for text ports.
Dynamic tool-provider grants do not authorize binary transfer; `tool.*` topics
keep their text protocol. A binary topic must be published before consumption.
An established topic's family cannot be changed by uploading different content.

## Publication and retrieval

`POST /v1/relay/blobs` accepts authenticated `multipart/form-data` with exactly
one `file` part, required string fields `requestId` (canonical UUID), `project`,
`topic`, `occurredAt` (ISO instant) and `mediaType`, and optional `correlationId`,
`parentTopic` and `parentEventId`. Both parent fields must be supplied together;
the existing parent authority and effect-depth budget apply. Duplicate/unknown
fields and extra files are rejected. The server derives publisher identity from
authentication, calculates the full SHA-256 and returns:

```json
{
  "publication": {
    "requestId": "11111111-1111-1111-1111-111111111111",
    "project": "atlas",
    "topic": "media.capture",
    "position": "1",
    "publishedAt": "2026-10-22T00:00:00Z"
  },
  "blob": {
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "byteLength": 123,
    "mediaType": "image/png"
  }
}
```

The hash above is illustrative. `mediaType` is a lowercase MIME type/subtype
without parameters or wildcards. It describes submitted delivery metadata;
Relay does not decode images, inspect documents or infer model support.

Existing consume/log events expose `payload.kind: "BLOB"` and `payload.blob`
with the same descriptor. They never contain byte arrays, paths or bearer URLs.
An explicit authenticated `GET /v1/relay/blobs` requires `project`, `topic`,
`group` and the publication's `eventId` (the original request UUID). Current
egress authority and that exact retained publication authorize the read. A digest
alone grants no access. Range requests are refused. The response is a download
attachment with `application/octet-stream`, private no-store caching, `nosniff`
and `X-Plowshare-Blob-Sha256` / `X-Plowshare-Blob-Media-Type` headers.

Java `ServerClient.publishRelayBlob` / `readRelayBlob` and Node
`publishRelayBlob` / `readRelayBlob` provide explicit binary HTTP helpers. Node
requires the deployment origin and bearer; Java uses its configured authenticated
client. They refuse redirects and automatic replay, bound receipt/download size
and verify receipt identity plus downloaded length/digest. All SDKs gain validated
descriptors through generated consume/log DTOs; binary HTTP authoring helpers in
other native SDKs are separate work.

Keep the original request UUID, metadata and bytes until reconciled. An identical
retained retry returns the original receipt without allocating another position
or claim. Changed content or metadata under the same UUID conflicts. After
uncertain delivery, explicitly reconcile that UUID through existing retained
lookup; do not recapture or automatically replay a producer effect. An expired
receipt remains unknown under the existing retention contract.

## Storage and lifecycle

`BinaryBlobStorage` finalizes bytes before the short publication transaction.
Append, publication link and releasable claim commit together. Failure returns no
receipt and advances no position. Prepared bytes without a publication have zero
claims and the configured storage recovery grace; if cleanup wins before claim
attachment, publication fails and the original bytes can be explicitly retried.

Within one project, registered identical content has one canonical file. Existing
image-first placement is reused. BLOB-first placement uses the private project
`blobs/<full SHA-256>.bin` path; a later image upload adds its original image ID,
sidecar and independent image-retention pin without another byte file. Legacy
images become registered on a successful write/retry; there is no startup scan
or bulk deduplication. Data layout 7 reserves `blobs/` without moving old images.
Run only writers that understand the upgraded layout and shared scope fences.

Acknowledgement advances delivery state and keeps the publication claim. Prune,
topic deletion and project cascades release only that publication's claim in the
owning transaction. Storage cleanup, disabled by default, removes registered
zero-claim files after its grace. Other image/invocation claims retain shared
bytes. Once the publication expires its read selector is unavailable even if an
image still owns the bytes. Missing/corrupt storage returns `BLOB_UNAVAILABLE`
(HTTP 503); it never falls back to another file or scope.

Trajectory/Conversation media claims, typed tool result envelopes, internal Relay
filter/receiver processing of BLOBs and model observations remain separate slices.
Binary delivery alone does not establish screenshot-to-active-model support.
