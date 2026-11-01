# Invocation-bound image admission

Admission binds a validated still PNG, JPEG or WebP to an existing retained Relay tool invocation.
Bytes and JSON sidecars stay in `ImageStore` under the existing scoped `img_`
identity. The receipt adds missing integrity/dimension proof and an invocation
retention link; it introduces no job, queue, blob database or image gallery.

The [BinaryBlobStorage foundation](binary-blob-storage.md) now privately registers
held files in place and adds a separate blob claim in the receipt transaction.
Image IDs, sidecars, upload fields and status responses remain unchanged. Image
retention pins preserve existing lifetime after an invocation claim is released;
cleanup defaults to disabled. Relay BLOB and Trajectory own independent claims.

Set `plowshare.images.invocation-admission-enabled=true` to enable the new profile.
It is disabled by default. Disabling it stops profile uploads; authorized status
reads remain available. The [typed result](typed-png-tool-results.md) and
[Trajectory observation](trajectory-image-observations.md) paths reuse these receipts.
V228 retains the server-validated MIME in admission and Trajectory records; existing
rows migrate as PNG. Model/backend and harness eligibility remain separate.

## Upload and reconciliation

Ordinary `POST /v1/images` without `profile` keeps its existing fields,
authorization, formats, Personal destination and `StoredImageView` response.
The opted-in multipart request uses that same endpoint:

| Field | Contract |
| --- | --- |
| `profile` | Exactly `invocation-image/1`. |
| `invocation` | Canonical UUID of a retained Relay invocation. |
| `ordinal` | Integer 0–15: at most sixteen committed slots per invocation. |
| `requestId` | Stable identity derived from the invocation and ordinal. |
| `file` | Exactly one file, bounded by `plowshare.images.max-bytes` before copying. |
| `name` | Optional display filename, at most 256 characters; never used as a path. |

Unknown profiles, project/owner overrides, duplicate parameters and extra file
parts are refused. Project, owner and producer come from authenticated identity
and the retained invocation. HTTP 201 returns `ImageAdmissions.Receipt`: request
identity, project, owner, producer, existing image UID, MIME, dimensions, byte
length, full SHA-256 and the original admission timestamp. Format is derived by
[bounded inspection](media-inspection.md), never from filename or MIME claims.

WebSocket `artifact.admission.status` accepts exactly `invocation`, `ordinal` and
`requestId`, rechecks current producer authority, and returns:

| State | Meaning |
| --- | --- |
| `COMMITTED` | Receipt exists and its held bytes/sidecar match the full descriptor. |
| `ABSENT` | No committed receipt; disk finalization may still have occurred. |
| `UNAVAILABLE` | Receipt exists but its held object is missing, unreadable or inconsistent. |

Unauthorized requests receive a refusal, not absence. This is an ordered status
read because hosted authority checks use short transaction locks. It does not
enumerate images, follow workspace paths or publish another tool request.

The request ID is the first 128 bits of SHA-256 over UTF-8
`invocation-image/1\n<canonical invocation UUID>\n<decimal ordinal>`, formatted as a
UUID without changing its version/variant bits, following Relay result identity
conventions. Java supplies `ImageAdmissions.requestId`, TypeScript supplies
`imageAdmissionRequest` with an injected SHA-256 host, and Node supplies
`invocationImageRequest`. This operation ID does not replace the image UID.

Java provides `admitImage`/`admitImageAsync` and
`imageAdmissionStatus`/`imageAdmissionStatusAsync`. Node provides
`admitInvocationImage`; TypeScript/Node use the typed SDK request for status.
These helpers refuse redirects/automatic replay, bound the receipt and correlate
its slot. Generated Python/Go/.NET contracts include typed status reads; their
binary upload helpers are not implemented in this slice.

## Authority, lifecycle and recovery

Only the retained provider account, including exact service identity, may produce
an invocation image. Non-hosted providers require current owner/provider work
membership and the exact live registry contract. Generic membership is insufficient.
Personal/Global artifact admission is refused.

Hosted authority checks the retained executor and durable binding/generation,
connection revision, credential subject/generation and current Application/agent
authority. It releases no upstream secret and gives the executor no project
membership. Sibling accounts, stale generations and revoked grants cannot use a
known invocation UUID. The original worker registration remains invocation
provenance; a disconnected socket or server restart alone does not erase authority
to retain captured output. Admission acquires no execution credential or new effect.

New slots are refused after settlement. A waiting timeout leaves an unsettled
invocation uncertain; it does not impose a separate artifact deadline. Identical
committed retries retain their original receipt and can repair missing held files;
conflicting bytes/provenance are refused. Retry/status still require current
producer authority. Admission authorizes, inspects and finalizes bytes outside
SQL, then rechecks authority and locks the invocation before committing a receipt.
That row lock serializes admission with settlement. Migration V224 adds the
invocation-owned hold; deleting an invocation removes its logical link, never
shared disk bytes. Later result/trajectory transactions acquire their own links.

Legacy uploads, workspace notes and admission share bounded JVM lock stripes and
a directory-sibling OS lock. Same-directory staging/atomic rename preserves
filenames, sidecar shape and first-write metadata. Staging is bounded to one
`<final filename>.pending` per final pathname under that fence; retry replaces
only its own staging pathname. There is no unrelated-file sweep or finalized-image
collector. Workspace promotion holds uploaded bytes without following its source.

Admission verifies full digest and forces both files and their directory ancestry
before SQL commit. The filesystem must support OS locks, atomic move, file force
and directory force, and storage must honor those operations; unsupported
finalization fails without a weaker fallback. Storage loss/inconsistent backups
remain unavailable. Existing held corruption requires restoration, not substitution.

A crash after disk finalization but before SQL commit may leave an unclaimed
finalized candidate. Reconcile status and retry only the identical captured bytes
under the original slot. Never recapture or repeat a generator/browser effect
because a receipt was absent or a reply was lost. No binary read endpoint is
added; future trajectory-linked reads must check their own disclosure authority.
MIME/dimensions also do not establish model support or context cost: native
observation still needs a verified model/backend counter or conservative bound.
