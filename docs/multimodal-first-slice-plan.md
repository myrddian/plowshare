# Multimodal core first slice implementation plan

Current admission policy: [Decision 0053](decisions/0053-bounded-image-context-admission.md)
replaces the historical mandatory exact-count gate with bounded local reservations;
the counter/sidecar is optional. Other lifecycle and format contracts remain intact.

Status: storage foundation, Relay BLOB delivery, Node typed PNG results and bounded active-loop PNG observations implemented,
23 October 2026. The user authorized starting
an implementation slice. The disk-loaded model-profile configuration slice is
implemented and validated under [Decision 0043](decisions/0043-disk-model-media-profiles.md).
The subsequent routing slice is implemented and validated under
[Decision 0044](decisions/0044-profile-aware-media-routing.md). Bounded PNG inspection
is implemented as an internal capability under
[Decision 0045](decisions/0045-bounded-media-inspection.md) and consumed by invocation admission;
legacy uploads do not invoke it. Invocation-bound PNG admission and producer status
are implemented under [Decision 0046](decisions/0046-invocation-image-admission.md),
with explicit deployment opt-in. The shared blob foundation is implemented under
[Decision 0047](decisions/0047-binary-blob-storage.md), with cleanup disabled by
default. [Relay BLOB delivery](relay-blobs.md) is implemented under
[Decision 0048](decisions/0048-relay-blob-delivery.md), with publication disabled by
default. The Node typed PNG result slice is implemented under
[Decision 0049](decisions/0049-typed-png-tool-results.md), reusing invocation admission
claims. The [active-loop PNG subset](trajectory-image-observations.md) now retains typed
entry metadata and independent claims and enforces native context admission.
Broader DTO exposure, typed redemption and output manifests remain proposed.
Nothing in this worktree has been deployed by this task.

Format update, 23 October 2026: still PNG, JPEG and WebP now pass through bounded
Tika-based detection/decoding, invocation receipts, Node typed results and retained
Trajectory MIME under [Decision 0051](decisions/0051-typed-still-image-formats.md).
**Model support is distinct from serving-backend and harness support.** The
[capability projection below](#model-backend-and-harness-capability-projection)
records current coverage. Configuration and live backend/context acceptance remain
separate; this work has not deployed or enabled an unverified counter.

The implemented
[BinaryBlobStorage foundation](binary-blob-storage-foundation-plan.md) provides shared
immutable bytes and explicit claims, with existing image IDs, files and sidecars
preserved. Relay and Conversation/Trajectory remain separate lifecycle owners.

Source baseline: `2cf4db4c2d72e9f99e43e343a38ff603278e8329`.

The end-to-end acceptance target lets a browser tool return a screenshot to the
**same active model**, with the exact screenshot retained in the owning tool receipt and
Conversation/Trajectory. Reconnect, compaction and authorized resume preserve its
identity. Relay transports the result through its existing durable delivery path.

Introduce shared binary storage below media services, then one shared content body
and a narrow, authorized image artifact capability. Keep the existing execution
runtime, ledgers, model-call accounting, Relay delivery semantics and immutable
trajectory. Relay BLOB payload support is a separate consumer of that storage,
not the owner of trajectory bytes. The initial native modality is
PNG image input. Media-format capability matching belongs in the existing router
and uses the same mechanism for image, audio and video formats. Extend the existing
disk-loaded sampling profiles to declare model inputs and supported output modes;
operators can add or override profiles through deployment files without rebuilding
server resources. Native audio/video transport and generated model media remain
later extensions.
Provider-produced PNG outputs, including a text-to-image tool result, use the same
disk/reference contract and must be trackable and deliverable without requiring
the conversational model to inspect the image. Native media emitted directly by
the conversational model remains a separate Completion transport extension.

**Storage decision: introduce `BinaryBlobStorage` beneath the existing image
service.** Reuse `ImageStore`'s disk mechanics, `ImageDirectories` and `DataLayout`;
adopt existing held files in place. Bytes and existing metadata sidecars stay on
disk, outside database blobs and Information `source_bytes`. The blob system owns
full-digest identity, private location, integrity and explicit owning-record claims;
the image service owns format/display metadata and its unchanged scoped `img_…`
ID, privately bound to the blob. Database additions track blobs, bindings and
claims as well as admission provenance. Content continues to reference the media
ID, not a filesystem path. Admission/request IDs identify operations, not bytes.

Claims are stable owner/slot records, not increments on retries. Relay publication,
invocation admission, image retention and trajectory entry are independent owners.
Removing one owner releases only its claim; zero claims makes a fully managed blob
eligible for race-safe physical cleanup. Existing image records need explicit
retention pins because their current lifetime has no deletion API. Unregistered
legacy files are never inferred to be garbage. Initial deduplication shares bytes
between owners within the existing storage scope; cross-home deduplication and
bulk relocation are deferred. The foundation plan defines these limits and the
transaction/crash protocol before any Relay or trajectory adoption.

The same storage/reference strategy applies to audio and video: keep each item on
disk with validated metadata and immutable identity; carry its typed reference
through tool receipts, Relay and Conversation/Trajectory; resolve it under the
owning record's current authority. PNG is the first working case of this shared
design, not a separate image-only architecture. Audio/video storage adapters and
native model consumption are later implementations of that strategy.

## Review disposition: the smallest coherent delivery

Reviewed against the source baseline above. The earlier draft included useful
follow-up work in its release gate. The end-to-end target remains the existing tool
execution path carrying one new durable result body. The independently
reviewable storage foundation and subsequent Relay delivery unit do not claim
screenshot-to-model support.

The full delivery has these responsibilities, implemented in separate review units:

1. Shared `BinaryBlobStorage` with full-digest identity, in-place image adoption,
   idempotent owner claims and safe last-claim cleanup. Preserve existing image
   IDs/uploads and protect legacy image lifetime. This foundation is implemented.
2. One ordered text/image-reference contract, including observation versus output
   purpose, plus generated shared read contracts and legacy text compatibility.
3. Invocation-bound PNG admission into the existing disk image store, with narrow
   authenticated retrieval and durable attachment links. Reuse the Relay invocation
   as execution owner; add attachment records, not another work ledger/runtime.
4. Independent Relay BLOB delivery over shared storage, then negotiated content
   results/provider journals retaining identity, authority and uncertain-effect
   behavior. Preserve existing TEXT topics; blob claims do not replace tool receipts.
5. Typed tool observations, immutable trajectory references, and reconstruction
   through the existing projection, fold and result-redemption paths.
6. Disk-loaded model-profile input/output declarations, existing-router filtering,
   one verified PNG-input/text-output transport mapping, and bounded image context
   admission. Keep the current sampling lookup and configuration lifecycle.
7. Authorized inspection/output retrieval, retention/export compatibility, and two
   public-SDK fixtures: same-model screenshot observation and provider PNG output
   from a text-only agent. Output records say produced/available/withheld; they do
   not claim a person viewed an image merely because a result was published.

Defer MCP adoption, native image generation/Completion decoding, generator billing,
live profile reload, endpoint-specific profile overrides, bulk legacy image
collection and a new GUI viewer. Bounded cleanup of registered, fully managed blobs
is part of the foundation. Shared DTOs and meaningful text-client fallbacks
remain required; an authenticated SDK download proves byte delivery without a new
rendering feature. Keep the impact map as a compatibility checklist, not a mandate
to rewrite every listed subsystem or implement media authoring in every SDK.

The source review also found gaps that must be closed in these existing seams:
provider HTTP upload authority cannot borrow the run owner's account; historical
redemption must retain the original producer association; model observation
provenance must not be placed inside the metadata-only billing journal; the
existing all-pool `vision` boot guard must coexist with filtered routing; and an
accepted output combination must distinguish allowed from required formats.
The sections below specify those corrections. No runtime implementation is
authorized by this review.

## First acceptance case

A browser ToolProvider takes a screenshot of a controlled page containing a random
visual marker that does not appear in the prompt or textual tool result. It
admits that PNG through the public SDK and returns text plus its artifact
reference. The active model identifies the marker from the image on its next
inference call. No second agent, OCR service or auxiliary vision model supplies
the answer.

The retained records must let an authorized reader follow:

```text
conversation and turn
  -> model call and tool_call_id
  -> provider invocation and executor fence
  -> immutable screenshot artifact and digest
  -> verified raw tool result
  -> post-hook observation actually submitted to the model
  -> model call that consumed that observation
```

Acceptance includes reconnect, provider restart after result publication, server
restart followed by explicit resume, and a compaction boundary. These are separate
cases: transport reconnect does not restart a completed tool, and server restart
does not promise automatic continuation of an interrupted model call.

The second acceptance case verifies **text-to-image output tracking**: a text-only
agent invokes a PNG-producing tool, its output is retained and linked to the tool
receipt and trajectory, and an authorized client retrieves the same image from
the final result. Reconnect and historical reads return the original output;
neither the agent nor provider generates it again. Optional visual examination
is a separate observation requiring image-input capability. A deterministic PNG
producer exercises this contract; a real text-to-image service is a later adopter.

## Boundary and explicit exclusions

Implement project-scoped, server-retained PNG artifacts produced by authenticated
ToolProvider invocations, including the owner/executor split of hosted tools.
General Personal/Global artifact admission is not enabled in this slice.

Use the shared content contract first for tool results and their trajectory
entries. Existing utterances and answers normalize to text-only bodies. Keep the
current refusal of image-bearing reusable-conversation input until its separate
public admission path is implemented; the screenshot acceptance enters through a
tool result within an existing conversation.

The following stay outside the end-to-end PNG delivery (the foundation is narrower):

- Native audio/video inference, media generation directly from the conversational
  model transport, bundled generation services and live media sessions. A provider
  may generate a PNG through its own service and return it under this slice's
  shared output contract.
- Arbitrary URL fetches, provider-hosted artifacts and public/signed artifact URLs.
- Migration of every Relay topic or new system-specific payload families. A generic
  BLOB payload is a planned follow-up consumer of storage, outside the foundation.
- Media messages in Board, Messaging, Incoming A2A, swarm deliberation or commands.
- AHP/ACP adapters and a general MCP content/resource implementation.
- Automatic OCR, captioning, indexing or embedding of artifacts.
- A universal attachment gallery, media editor or replacement Information store.
- MCP bridge adoption, a new GUI media viewer, bulk legacy image garbage collection,
  external generator billing, endpoint-specific profile selection and live reload.

These exclusions do not permit silent flattening. A text-only boundary must return
a useful descriptor or refuse the unsupported operation explicitly.

## Current contracts and the exact seams to change

| Current source | Constraint or reusable behavior |
| --- | --- |
| [AgentTool](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentTool.java), [RelayAgentTool](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RelayAgentTool.java) and [JobRuntime](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) | Tools return strings; the loop appends a text tool message and records a text result. |
| [Content](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/Content.java), [ChatMessage](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/ChatMessage.java) and [OpenAiTransport](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/openai/OpenAiTransport.java) | Native image input already exists for user-role messages; tool-role images are refused. Bytes are transient data URIs, not durable message content. |
| [LoggedEntry](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/LoggedEntry.java), [EntryRecord](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryRecord.java) and [EntryStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryStore.java) | The trajectory stores text and tool identities; complete-log and bounded-projection reads already have different purposes. |
| [Projection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Projection.java) and [Compaction](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Compaction.java) | Projection reconstructs text messages. Compaction changes the selected model context while preserving the original execution log and result handles. |
| [RelayToolCodec](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/RelayToolCodec.java), [RelayToolRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/RelayToolRepository.java) and [ProjectRelayToolInvocations](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ProjectRelayToolInvocations.java) | A versioned application envelope already crosses Relay TEXT topics. The owning ledger verifies publisher/routing identities and reconciles results without resubmitting intent. Results currently carry text. |
| [SDK ToolProvider](../sdk/typescript/src/operations/relay-tools.ts) and [MCP codec](../integrations/mcp/src/codec.ts) | Provider receipts persist invocation state and text results. The MCP boundary explicitly refuses non-text content blocks. |
| [ImageStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageStore.java) and [ImageController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ImageController.java) | Existing image bytes, scoped identities and upload processing are reusable. Legacy image IDs alone do not provide invocation ownership, durable attachment retention or an authenticated read API. |
| [SamplingProfiles](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/SamplingProfiles.java), [LlmProperties](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmProperties.java) and [LlmConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmConfig.java) | Profiles and model mappings load from an operator-owned directory over shipped resources. The configuration slice adds typed input/output declarations; the routing slice enforces them on explicit format contracts and profiled image requests. Context-bound and durable tool-path work remains proposed. |
| [InformationCatalogue](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) and [PortableInformation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/PortableInformation.java) | Information already owns immutable revisions, original bytes, provenance and access. Its text processing pipeline and bounded bundle export are not an invocation-artifact API. |
| [AccountingEvent](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingEvent.java) and [InferenceCapture](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/InferenceCapture.java) | Model calls already have durable identities, but the billing journal deliberately excludes request content and arbitrary payloads. Link an authorized observation manifest to that identity outside the billing payload; do not add another accounting system. |

## Image storage that already exists

Uploaded images already have both a binary file and a JSON metadata sidecar:

```text
<configured data directory>/projects/<project-id>/images/
  img_<32 hex characters>.png
  img_<32 hex characters>.json

<configured data directory>/global/images/
  img_<32 hex characters>.<format extension>
  img_<32 hex characters>.json
```

These are the layouts implemented by
[DataLayout.imagesFor](../plowshare-server/src/main/java/io/aeyer/plowshare/server/data/DataLayout.java)
and [ImageDirectories](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ImageDirectories.java).
The first slice uses the project form. The ordinary project registry supplies the
project ID used in the directory name; that does not put image bytes in SQL.

| Existing capability | Reuse or narrow addition |
| --- | --- |
| [ImageStore.store](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageStore.java) writes the sidecar and uploaded bytes with filesystem operations. | Current admission work shares locks, bounded staging, atomic rename and forcing through `ImageWrites`. Reuse those mechanics and existing locations behind the blob disk adapter; do not build another writer or copy adopted files. |
| [StoredImage](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/StoredImage.java) records UID, home, detected format, display filename, raw byte size, timestamp and optional workspace path. [ImageSidecars](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageSidecars.java) validates bounded sidecar reads and identity/home consistency. | Preserve this metadata owner and legacy readability. Do not recreate all sidecar fields as a second independently editable database catalogue. Persist missing full-digest/dimension proof in the new admission receipt. |
| IDs use the first 128 bits of SHA-256; repeated identical uploads in one home return the existing held record. | Preserve scoped `img_...` IDs and metadata. Add a private mapping to the full-digest blob identity; trajectory and provider results still reference the media ID. Admission/request IDs track operations separately. |
| [ImageController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ImageController.java) and the [Java SDK](../sdk/java/src/main/java/io/aeyer/plowshare/sdk/WsServerClient.java) already provide multipart image upload. | Extend this public upload path with an explicit invocation admission profile. A new generic binary upload service is unnecessary. |
| [ImagesProperties](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImagesProperties.java) already supplies a configurable raw-byte limit, defaulting to 5 MiB. | Reuse that ceiling, with any stricter invocation bound applied explicitly. Add bounded full PNG validation and pixel limits for new invocation admissions. |
| `ImageStore.note` writes only a sidecar for a server workspace image; the original file stays in place. Resolution rechecks the workspace fence and verifies that the bytes still match the UID. | Preserve this separate, revocable workspace-reference behavior. It is not an immutable retained screenshot. New screenshot receipts bind a held disk image, never merely a mutable workspace path. |
| `ImageStore.dataUri` reads stored bytes and creates the transient image input used by the model transport; standalone input and explicit delegation already consume it. | Reuse byte resolution behind current authorization. Add the durable tool-observation path; do not store the data URI in Relay or trajectory text. |

Invocation admission now relates a held image to its exact producer invocation.
Still absent are generic blob ownership/cleanup and the effective observation,
trajectory-entry and consuming-model associations. Disk persistence does not
by itself cause a resumed conversation to reattach an image. The
[foundation plan](binary-blob-storage-foundation-plan.md) adds storage claims first;
Relay BLOB and trajectory claims follow independently.

## Shared content contract and remaining target design

The PNG tool-result subset is implemented as `ToolContent.Body`; see the
[typed PNG result manual](typed-png-tool-results.md) for exact wire fields, Node
helpers and bounds. The broader cross-owner content/descriptor design below still
includes planned work.

Add immutable Java DTOs under `plowshare-protocol` and derive corresponding SDK
contracts through the existing schema/catalog generators. The names below are
proposed API names, not currently implemented types.

```text
ContentBody(version = 1, parts)
  TextPart(kind = text, text)
  ArtifactPart(kind = artifact, reference = MediaReference, use)

MediaReference, first admitted variant
  ImageReference(imageId)

ArtifactDescriptor
  reference, sha256, mediaType, byteLength, ImageDetails(width, height)
  authenticated owner scope and producer provenance
```

`use` distinguishes `OUTPUT`, `OBSERVATION` and `BOTH`. These are projection and
delivery purposes, not access grants. The producer declares intended use under
its registered result profile; the owning invocation/run validates and retains
the effective use. An output reference is not automatically inserted into the
model's native input. A screenshot observation does not automatically become a
final user output. The same item may serve both purposes when explicitly allowed.

The initial reference union contains `ImageReference` only. Its `imageId` is the
existing `img_...` UID of the disk-backed item, not an artifact UUID assigned by
a new catalogue. Future media stores can add their own typed reference variant.
Audio/video add `AudioReference`/`VideoReference` and typed format details through
the same content, resolution, ownership and retention contracts when implemented.
Do not create another trajectory, Relay or receipt body for each modality, and do
not advertise unimplemented reference variants as supported.
The admitted invocation/entry supplies its authorized home, so a caller cannot
select another project's item by adding an owner or arbitrary disk path.

`ArtifactPart` contains no URL, base64 value or capability token. The server
returns authorized descriptors separately or in a bounded batch beside content
bodies. A descriptor is evidence about the item, not permission to resolve its
bytes. Different image bytes produce a different existing image UID; the admission
receipt's full digest proof rejects collisions or a changed object under a UID.

The durable link is therefore:

```text
trajectory entry (conversation, ordinal, part position)
  -> ImageReference(img_...)
  -> authorized owning home and invocation binding
  -> existing disk image plus JSON sidecar
```

This is the fix to the earlier missing-history-link finding: the trajectory
retains a machine-readable reference to the original item, allowing authorized
inspection, model reconstruction and retention tracking. It neither embeds the
bytes nor replaces the original stored item.

### Shared media behavior

| Concern | Images | Audio | Video |
| --- | --- | --- | --- |
| Physical ownership | Existing disk image and metadata sidecar. | Disk-backed original and validated metadata, when its adapter is added. | Disk-backed original and validated metadata, when its adapter is added. |
| Durable content | Typed reference to the scoped item. | The same reference mechanism. | The same reference mechanism. |
| Native routing | Actual image type and applicable geometry limits. | Actual audio type, codec and applicable duration/sample/channel constraints. | Actual container type, codecs, tracks, timebase and applicable duration/frame constraints. |
| Context selection | Retained full image or an explicit derived image. | Original reference and, when supported, an explicit validated time range. | Original reference and, when supported, explicit ranges/frame timestamps in the original timebase. |
| Replay and inspection | Resolve the retained identity under current authority. | The same ownership, availability and recovery rules. | The same ownership, availability and recovery rules. |

Future time-range/frame selections refer to the original item's immutable identity;
selection metadata must survive replay as typed data rather than prose. A
transcode, crop, extracted frame or generated clip that produces different bytes
is a distinct derived item with a retained source relationship. No implicit
conversion or fresh media processing runs merely because a reference is replayed.
Live capture/stream transport remains a separate lifecycle from these retained
completed files.

Contract rules:

- Preserve part order, including text before/between/after images. Do not turn
  multiple interleaved parts into a trailing attachment list.
- Require 1..16 parts, at most four artifact parts per tool result, and preserve
  the existing 16,384-unit text-result ceiling across text parts. Encoded tool
  envelopes must still fit the existing portable envelope bound.
- Admit only `image/png` through the new producer API initially. Other MIME types
  and unknown part kinds fail before a result becomes an accepted observation.
- Keep producer state (`COMPLETED`, `REJECTED`, `UNKNOWN`) separate from content.
  The first slice permits artifact parts only on `COMPLETED` results.
- Preserve the original result and the effective post-hook body as distinct
  execution facts. Do not rewrite the raw provider receipt when a hook changes
  or withholds the observation.
- Keep output tracking independent of native model observation. An OUTPUT part
  contributes a bounded descriptor to model context and an authorized reference
  to user delivery; only OBSERVATION/BOTH parts request native media consumption.
  Retain the original ordered body for trajectory inspection in either case.
- Keep `text` accessors/legacy constructors for text consumers. Their answer is
  the textual projection only, never bytes. Display fallbacks name omitted media;
  text-only model paths refuse instead of receiving a misleading success.
  A valid image-only typed result remains valid when its textual projection is
  empty; do not pass it through the current string `usable`/blank-result repair
  logic and accidentally replace it with prose.
- Hash versioned canonical typed content, including ordered scoped media references, in
  receipt comparisons. Reusing an identity with changed parts is a conflict.

Treat PNG as the first admitted artifact format, not as the definition of the
eventual content model. Add future modality-specific semantics deliberately;
audio duration and video timing cannot be inferred from a MIME string.

## Generated media outputs

A text-to-image tool is another producer of a disk-backed PNG. Its result must
answer both "what did the tool create?" and "what became available as output?" independently
of whether the conversational model examined the pixels.

```text
text-to-image invocation and retained request
  -> generator operation and its receipt
  -> generated PNG admitted to the existing disk image store
  -> OUTPUT ImageReference in the raw tool result
  -> hooks and authorized delivery selection
  -> typed output reference in the final run result and answer trajectory
  -> authorized client retrieves the original disk item
```

Retain producer provenance with the existing invocation/receipt: provider/tool,
generation operation ID, requested and actually served generator/model/version
when known, validated parameter identity, prompt binding, seed if actually
reported, source media references, and output reference/digest. Request details
remain in their authorized retained record; ordinary logs carry safe IDs and
hashes rather than duplicating complete prompts. An absent seed/model detail is
unknown, not a fabricated value. Prompt revisions returned by a provider remain
distinct from the original request.

Extend [Outcome](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Outcome.java),
its job/SDK views and retained answer content with an additive shared body for
available output references, preserving all existing ending/text semantics.
The closing runtime assembles those references from this run's committed
OUTPUT/BOTH tool-result parts under their registered delivery disposition and
hooks. Retain source invocation, entry ordinal and part position on each delivery
association. A model-written URL/ID or Markdown image is not sufficient to add an
output. Do not copy bytes or create a new media identity when promoting a tool
output into the final answer. Use the explicit minimal policy: after hooks, eligible
OUTPUT/BOTH parts are available run outputs on successful completion; observations
alone are not. This is a runtime-owned output index, not a claim that the model
selected or described every item. Selective model-authored attachment choices,
new attachment tools and client consumption acknowledgements are later work.

Raw generated results, effective tool observations and available final outputs
remain separate facts. A post hook can withhold an output while its raw generation
receipt survives. On cancellation/failure, retain any completed output under its
existing authorized trajectory/receipt access and mark final delivery withheld;
do not report successful delivery merely because generation completed. A final
association means published/available, not fetched or viewed. Reconnect
notifications point to the retained final result and never trigger generation.

The conversational LLM may remain text-only for generation/delivery. Its input
projection receives a safe descriptor, and its output requirement remains text.
The generator service's output capability is separately `image/png`. If the run
explicitly requests native examination of the generated image, that part becomes
an OBSERVATION/BOTH input and must pass the image-capable LLM route. This prevents
output tracking from unnecessarily requiring every text-to-image workflow to use
a vision-capable conversational model.

Keep the generator's stable request/operation ID through upload and delivery
recovery. Neither the same prompt nor the same seed makes repeating a paid
generation safe after uncertain delivery. If generation is still in progress,
use its retained operation handle and existing external-work lifecycle where
appropriate; do not build another durable agent runtime or repeat generation to
recover a missing tool reply. Reuse any existing provider receipt for reported
generation usage/cost; a new billing schema or pricing integration is later work.
Do not count generation as an extra conversational model call or invent
measurements from image dimensions. The output fixture uses a deterministic
PNG-producing provider, so a paid generator is not a first-slice dependency.

Native generated media coming directly from the selected LLM later requires
extending [Completion](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/Completion.java)
and that backend's response decoder. It then enters this same disk admission,
reference, retention and delivery path with native-model provenance. Audio and
video outputs follow the same design with their supported file/transport profiles.

## Artifact ownership and public operations

Define narrow `ArtifactAdmissions`, `ArtifactReads` and `ArtifactRepository`
interfaces. Inject these contracts into consumers. Repository implementations own
SQL for admissions and media bindings; the blob specialist owns generic object and
claim persistence. `ImageStore` remains the media/sidecar owner and initial disk
adapter, consuming `BinaryBlobStorage` for adopted byte resolution and ownership.
Reuse the existing write mechanics and storage layout. Keep media authorization
and descriptor validation outside the format-neutral storage capability.

New invocation-attachment records own admission receipts, full digest/dimension
proof, authenticated producer/consumer bindings and retention links to the
existing home/image UID. Key them by retained invocation and artifact ordinal;
derive/reuse the stable upload request identity from that key. They extend the
existing execution ledger, without another job, queue, polling worker or separate
artifact execution state machine. `RelayToolRepository` and `EntryStore` retain
their own result/entry commits and acquire attachment links in those transactions
using the existing transaction infrastructure. Those links acquire explicit blob
claims; they do not create a second independently counted lifetime. The foundation
specifies admission-cascade release and protected legacy image claims before any
generic physical collection is enabled.
Construct descriptors from that immutable receipt and the stored-image record;
do not duplicate or independently edit existing sidecar metadata in SQL.
The artifact's disclosure owner is the authenticated run owner in its project.
The provider is a producer authorized for one retained invocation. Hosted executor
credentials do not make the executor the disclosure owner, and a supplied
`invocationId` does not itself grant upload rights.

Proposed public boundary:

| Operation | Contract |
| --- | --- |
| HTTP multipart `POST /v1/images` with explicit `invocation-image/1` admission profile | Extend the existing binary-upload boundary. The negotiated multipart profile adds stable request ID, invocation ID and artifact ordinal to the PNG upload. Derive owner/project/executor from the authenticated, retained invocation; verify current provider or hosted executor authority. Return a typed admission receipt for this profile; preserve the ordinary upload request/response unchanged. |
| WebSocket `artifact.admission.status` | Reconcile the original stable invocation/ordinal request identity under the same authorized producer binding. Return a committed receipt or an explicit absent/refused/unknown outcome. There is no new asynchronous admission job; an absent receipt does not prove the byte write never happened. Retry only the identical upload after reconciliation, without repeating the producing tool effect. |
| HTTP `GET /v1/conversations/{conversation}/entries/{ordinal}/images/{imageId}` | Authenticated bounded PNG read reached from the trajectory entry. Verify current conversation/entry access and that the entry actually references that existing image UID, then resolve its owning disk item. No arbitrary project/path override, redirects, public URL or embedded bearer. Return an explicit unavailable/refused outcome as applicable. |

Return descriptors with admission receipts and existing bounded
trajectory/projection/result reads; do not add a standalone `artifact.describe`
operation for this slice. Extend
[NothingEnumeratesImagesTest](../plowshare-server/src/test/java/io/aeyer/plowshare/server/images/NothingEnumeratesImagesTest.java)
to permit only the exact linked read, retaining the prohibition on project image
enumeration. Retrieval uses PNG Content-Type, `nosniff`, sanitized display names
and private non-persistent caching; existing session/bearer authentication applies.

Expose these through purpose-specific SDK methods. Keep binary HTTP helpers
separate from generated WebSocket operation DTOs, following existing image upload
precedent. All SDKs must decode the new shared content/read/status contracts;
Java and TypeScript/Node upload helpers are required for the first acceptance
path. Record any later native-language upload helper as unavailable rather than
claiming complete upload parity.

The current [ImageController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ImageController.java)
resolves ordinary upload home through the authenticated caller's Personal/project
access. That is not the new invocation authority: a hosted executor and run owner
can differ. The opted-in upload branch must resolve the authenticated executor,
then authorize against the retained invocation and
[HostedInvocationAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/HostedInvocationAuthority.java)
before deriving the owner's storage home. Never impersonate the owner, grant the
provider broad project membership, or accept owner/project fields as authority.
Use the existing service-credential subject and hosted-generation checks at both
upload and status boundaries; revocation can refuse a newly arriving upload even
when the original invocation remains retained. Ordinary uploads keep their current
authorization path.

Admission checks must happen before publication of a successful receipt:

1. Validate the complete typed metadata and exact invocation/executor binding.
2. Bound multipart bytes before allocation; reuse `plowshare.images.max-bytes`
   (already 5 MiB by default) and any explicitly stricter invocation ceiling.
3. Parse PNG structure with bounded decoder work; reject corrupt/truncated input,
   unsupported animation and dimensions exceeding the configured pixel budget.
   Proposed initial decoded-image ceiling is 16 megapixels. Signature sniffing
   alone is insufficient. Obtain format/dimensions through the bounded media
   inspection boundary described below. The JDK's Image I/O PNG reader can supply
   bounded decoding regardless of the metadata inspection implementation.
   Header/metadata inspection is an early bound check, not proof that the entire
   file is valid or non-animated. Retain the structural/animation validation and
   full bounded decode before admission. Do not silently resize the observation.
4. Compute full SHA-256 and immutable descriptor server-side. Dedupe bytes only
   inside the authorized storage scope; a digest never proves disclosure rights.
5. Publish durable bytes before marking admission committed. Commit metadata and
   its invocation retention link in one repository transaction. Never hold that
   transaction across a provider/network call or a long image decode.

Use an idempotent temporary-write/atomic-finalize byte-store operation with a
reconcilable database receipt. A crash after byte finalization but before commit
leaves an unclaimed candidate, never an acknowledged artifact with missing bytes.
Bound staging files and clean only staging files this operation exclusively owns.
The implemented admission slice introduces no collector. The proposed blob
foundation adds bounded collection only for registered, fully managed zero-claim
objects, protecting image-retention claims and all unregistered legacy files.
Concurrent identical admissions must reconcile one complete verified held object,
including the sidecar-first legacy repair case and promotion of an existing
workspace-only record. Verify existing bytes/metadata against the full digest
before deduplicating; do not trust `ImageStore.store`'s current early return alone.
Coordinate finalization with legacy uploads and workspace-note writes touching
the same image UID, not just two new admission callers. Strengthen the shared
store write seam sufficiently that an old write cannot overwrite a committed
held snapshot. Specify the acknowledged durability boundary and reconcile both
metadata and bytes; atomic rename alone is not proof that both files survived a
machine/storage failure.
The byte-store adapter verifies the full descriptor digest when resolving data;
it must not treat the legacy image UID's truncated hash as a full integrity check
or let a mutable workspace path stand in for the captured immutable snapshot.

### Authorization at consumption

On result acceptance, require every artifact to belong to the exact producing
invocation and permitted project/owner. Another invocation cannot attach an ID
merely because its executor or digest matches. Public inspection rechecks current
conversation/receipt visibility and project/Application authority. Model use checks
the current owning run and its retained input purpose, without treating provider
credentials or usage attribution as execution authority.

Authorized server-owned result redemption is an explicit exception to the producer
rule: `result_read` resolves the original conversation/handle and retains a source
entry/part link to the original admitted invocation. It does not re-upload, claim
to have produced the image or attach a caller-supplied UID. Apply the same rule to
final output references. Foreign providers still cannot reuse another invocation's
image simply by naming its ID.

Recheck authority before each newly submitted model observation, byte delivery,
delegation or resume. Already sent inference cannot be recalled. In-flight byte
reads use a bounded request authorization interval; later reads must reauthorize.
No cached byte descriptor or previously allowed context is an authorization lease.

## Relay and provider receipt changes

Relay and Trajectory remain independent consumers of `BinaryBlobStorage`. The
foundation changes neither Relay payloads nor trajectory content. The implemented
[Relay delivery unit](relay-blobs.md) adds a generic BLOB payload referencing immutable stored bytes,
with explicit client binary transfer and bounded descriptors. Relay publishes and
prunes its own claim in its existing transactions; it neither adopts trajectory
retention policy nor requires a conversation for generic BLOB delivery.

Current tool requests/results use a versioned application envelope over TEXT
topics. Keep that compatibility path and extend its explicit codec for typed
media references; adding generic BLOB delivery does not itself supply ordered
tool content or authorize an image as an observation. No JSON/prose sniffing,
silent text-to-BLOB conversion or wholesale topic migration is proposed. The BLOB
SDK/wire extension is documented separately; existing text-only DTOs do not carry
arbitrary bytes through an unchanged wire schema.

Introduce `plowshare-tool/3` for negotiated content results, with the shared body
in the result envelope. Its request retains nested-argument schema fingerprints,
invocation/call IDs, deadlines, owner/executor provenance and requested result
profile. Add an explicit result-profile declaration to provider catalogue identity
and validation. Existing v1/v2 request paths and v1 text results remain valid.

- Old providers receive their old envelope and remain text-only. Never send a
  media request to a provider merely because it accepts nested arguments.
- New providers use v3 only when both sides advertise `content/1` support.
- The SDK journal persists immutable artifact admission receipts before marking
  a result ready; the journal contains references, not image bytes.
- A temporary provider-side PNG, when required for upload recovery, belongs in a
  private bounded artifact spool with its own cleanup, outside receipt JSON/logs.
- Result publication retains its existing deterministic event identity. Reconnect
  reconciles the same admission/result receipt; it does not capture again.
- `ProjectRelayToolInvocations` verifies publisher, binding, executor fence,
  invocation identity and referenced artifacts before ledger completion.
- The implemented Node completion validates existing invocation-admission claims under
  the invocation lock; that lifetime needs no duplicate result claim. Future hosted
  content support must preserve its generation/fence checks.

Do not overstate capture/generation recovery. Before durable capture/spooling or
an external operation handle exists, a crash can leave an unknown effect with no
recoverable bytes. Preserve UNKNOWN and require explicit reconciliation; do not
claim that the platform can reconstruct the lost screenshot or safely repeat a
paid generation. After a spool/admission/result receipt exists, test recovery of
those same bytes and identities. Reuse the current provider journal's uncertainty
rules instead of promising exactly-once execution from the content contract.

Relay owns delivery, ordering, expiry, offsets and delivery receipts. A BLOB
publication is one byte-retention claimant; its authorized byte delivery resolves
storage through that claim. Relay does not own image metadata or trajectory
retention. Existing lifecycle/wake events remain references to their owning records.

Relay inspection may show the envelope's bounded descriptors only to callers
already entitled to that publication. This does not authorize reading the
artifact. Keep preview size/cache weight based on descriptor/envelope bytes;
ordinary log inspection never hydrates binary data. Explicit BLOB reads require
current publication authority. Relay expiry releases only its publication claim;
surviving invocation, image or conversation claims retain the same disk object.
Acknowledgement advances delivery state and does not itself delete the publication.

## Conversation and Trajectory changes

Add optional versioned typed content to retained entries while preserving existing
text, kind, role, tool-call, invocation, turn, job and causation fields. Old rows
normalize to one text part. First-slice writers permit artifact parts on
tool-result entries and on answers delivering approved references from those
results. Other entry producers remain text-only. An available answer reference
retains its source result association and does not imply native LLM generation.
For new rows the typed body is canonical and the legacy `content` column is its
derived textual projection, written together by the repository. Reject mismatches;
do not maintain two independently editable versions of what an entry contains.

Use append-only Flyway migrations for artifact admissions/retention links and the
entry body extension. Allocate migration numbers from the implementation branch's
actual head. Do not rewrite historical migrations or backfill old text by parsing
image IDs out of prose.

`EntryStore` must atomically append typed content and acquire the entry's artifact
links and independent blob claims. It does not use a Relay publication as its
durable retention owner. The invocation result remains the recovery authority if the process stops
after result completion but before append. Reconcile using the original run/call
identity under the existing continuation owner; do not append a second completed
tool result or rerun the provider to fill the gap.

Update complete-log, result-handle, current-projection and as-of-turn reads
together. Return the ordered body plus bounded authorized descriptor batches from
trajectory/projection/result DTOs. Preserve entry ordinals and original identities;
do not manufacture an extra trajectory event just to display the image.

Persist a bounded authorized observation manifest linked to the existing
`InferenceCapture.callId`: source entry/call, ordered scoped media
references/digests, resolved model-profile digest and applied wire mapping. Keep
it beside execution/trajectory data, under conversation content authority. Do not
put artifact content or manifests into `AccountingEvent`: its journal explicitly
permits metadata only and has its own replay/version contract.

Prepare the manifest after tool hooks and dispatcher `ChatFiltering.input`, using
the effective ordered prompt that will actually be serialized. If filtering
cannot preserve typed parts and their source associations, refuse media instead
of auditing the pre-filter prompt as submitted. Commit the prepared manifest
before a native send; a refusal or uncommittable manifest sends no media. Link
submission/terminal evidence to the existing inference lifecycle rather than
claiming preparation proves consumption. A crash around network send remains
possibly-submitted/unknown; it cannot prove the remote model saw the bytes.
The manifest contains no data URI, creates no second model-call charge and does
not authorize a retry of an uncertain inference. Correlate to existing attempt
records where available; do not invent a second attempt lifecycle.
The existing [FilteredChat](../plowshare-server/src/main/java/io/aeyer/plowshare/server/security/FilteredChat.java)
already preserves image parts, roles and tool IDs while inspecting text. Reuse
that behavior and carry the new source association through it; this is not a
reason to replace the filtering subsystem or claim its text review inspected pixels.

### LLM routing and capability admission

Reuse the existing LLM route rather than introducing a media router or a separate
vision agent. The existing path, with the proposed eligibility check inserted, is:

```text
active agent definition and its model specifier
  -> ChatRequest
  -> system binding or global class alias resolves to a wire model
  -> pools serving that model
  -> [new] media-format and transport capability match
  -> least-loaded CHAT pool, or the scheduler's explicitly reserved pool
  -> configured provider transport
  -> selected model endpoint
```

[JobRuntime.requestFor](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
uses the agent definition's model and sampling.
[LlmPool.resolve](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java)
accepts a wire model or a class alias mapped to a model the pool actually serves.
[LlmDispatcher.route](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java)
selects the least-loaded eligible pool; `streamOn` uses the scheduler's selected
pool. Pools serve one configured wire model per class target, so an alias is not
an automatic choice among unrelated text and vision models.

[AgentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java)
already invokes `requireSees` for definitions declaring `vision: true`.
At the audit baseline, `requireSees` required every eligible pool to declare its
resolved wire model in `vision`. The routing slice retains that guard for legacy
unprofiled models; a profiled model instead needs one compatible image route,
because each actual image request now excludes incompatible hosts before load
comparison. It does not infer vision from a name, load another model or establish
compatibility through live inference. This is format eligibility, not artifact
access authority or a proven image context bound.

### Model, backend and harness capability projection

Keep these facts separate, then derive the effective capability for the selected
model, pool, operation and content direction. A model profile describes declared
model input/output ability. The serving backend determines accepted encodings and
request shapes. The Plowshare harness determines which of those shapes it can
validate, retain, project, serialize and decode through the chosen execution path.
Neither an image-capable model nor a serializer alone establishes that whole path.

| Projection | Owner / existing seam | Meaning and limit |
| --- | --- | --- |
| Model declaration | Disk-loaded sampling/media profile resolved for the wire model | Native input formats and output combinations. Declaring a format does not implement a harness decoder, tool result, replay path or output transport. |
| Serving-backend support | Selected deployment pool/backend and its verified configuration | Encodings and operations accepted by that runtime/version for this model. A generic OpenAI-compatible request schema does not prove backend acceptance. |
| Harness support | Admission/inspection, Node tool envelope, hooks, Trajectory projection, provider transport and response decoder | Implemented support for this direction and operation. Ordinary uploads, typed observations, HTTP chat, prefill, streaming, native outputs and client rendering can have different coverage. |
| Effective route capability | Existing dispatcher and admission checks | Intersection of model declaration, backend support and every required harness stage, under pool vision policy. Output combinations must satisfy one common mode; do not union unrelated abilities. |
| Invocation admission | Existing authority, claims and selected-route context admission | Current access, available immutable bytes and a verified complete context count are additional gates. Format eligibility is insufficient to send an observation. |

This is a derived projection over existing owners, not another durable capability
registry or a second router. Keep model declarations deployment-owned and
independently disk-loadable. Keep implemented harness support in the owning
components' typed contracts; deployment configuration can restrict it but cannot
enable missing implementation. Reuse `ModelMediaCapabilities`, `MediaRequirements`
and the dispatcher predicate where applicable. Backend restrictions should enter
that same eligibility calculation; do not duplicate model profiles or add a
separate routing lifecycle. Endpoint-specific profile overrides remain deferred.

Current worktree mapping for the screenshot path:

| Stage | Current support / evidence | Gap before broader advertisement |
| --- | --- | --- |
| Gemma 4 E4B | Model documentation describes image input and text output. | A model card does not establish encoded-file acceptance or a loaded instance's context policy. |
| Configured Mac serving path | Installation configuration selects `mlx-community/gemma-4-e4b-it`, provider `lmstudio`, in the vision-enabled `studio` pool. LM Studio documents PNG, JPEG and WebP input. | These are configuration and documentation evidence. The live metadata request required authentication; the exact deployed runtime's format acceptance and image context accounting remain unverified. Keep private installation values out of this public plan. |
| Existing disk image uploads | Existing image IDs/store recognize PNG, JPEG, GIF and WebP signatures. | Signature recognition is not complete decoder validation or typed tool-observation support. Preserve existing upload behavior. |
| Invocation admission and Node typed results | Server-validated PNG/JPEG/WebP receipts, bounded decoders and generic Node helpers reuse existing image IDs/claims. | PNG helpers retain their original restrictions. A caller-supplied MIME string cannot establish the type. |
| Conversation/Trajectory and active-loop projection | Retained references carry validated PNG/JPEG/WebP MIME through replay and routing. V228 preserves historical PNG rows. | Live backend/context acceptance is still separate. |
| Plowshare HTTP transport | OpenAI-compatible serializer declares PNG, JPEG, GIF and WebP input, with text output. LM Studio's HTTP path narrows that declaration to PNG/JPEG/WebP. | Serializer coverage is broader than the documented LM Studio format set; the typed admission path supports PNG/JPEG/WebP. Do not advertise that broader set as effective support. |
| Plowshare LM Studio prefill path | Socket-enabled provider currently declares text-only support because its alternate prefill path refuses images. | A vision model profile cannot override this restriction. Preserve explicit refusal; changing the prefill path is separate work, with no silent protocol fallback. |
| Image context admission | Typed observations require a complete measured count for the selected pool/model, verified revision and generation reserve. | Verify the actual Gemma/backend counter; neither MIME metadata nor the model's maximum context window proves image token accounting. |
| Outputs and clients | Current native model transports decode text output. OUTPUT tool references are retained independently of native observation. | Tool-produced images do not establish native model image generation, final output associations or image rendering in every client. Track those projections independently. |

Sources checked on 23 October 2026:
[Google's Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)
and [LM Studio image input documentation](https://lmstudio.ai/docs/python/llm-prediction/image-input).
The target backend format set is `image/png`, `image/jpeg`, `image/webp`; GIF is
outside this verified documentation set. Audio/video declarations remain separate
from any implemented native path.

The implemented format extension propagates validated JPEG and WebP alongside
PNG, using the existing image IDs, admission receipts,
blob claims, tool envelopes and Trajectory ownership. Preserve original bytes and
derive their actual MIME server-side. Define explicit animation/multi-frame
acceptance or refusal in each validator; do not silently choose a frame or
transcode into PNG. WebP uses a pinned bounded decoder; a Tika
type match alone is insufficient. BinaryBlobStorage and Relay remain format-neutral
and need no format-specific change.

Acceptance for that slice must separately prove each format through admission,
typed tool result, retained replay and exact native request bytes/MIME, plus
negative cases for a model-only declaration, unsupported backend or harness path,
and unknown context accounting. Preserve text routing and PNG compatibility.
Documented backend support and deterministic harness tests remain separate from
live screenshot-to-active-model acceptance. New public discovery/diagnostic APIs
are deferred; this capability map does not require one.

### Deployment-owned model profiles

Reuse the existing sampling profile directory as the model configuration source.
[SamplingProfiles.read](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/SamplingProfiles.java)
already loads bundled profiles, then discovers operator `.yaml`, `.yml` and `.json`
files from disk. A profile file replaces the same-named profile completely;
`models.yaml` merges mappings by model key. Arbitrary new profile names already
work without adding them to the bundled resource list. The current matching rule
strips vendor prefixes and uses the longest matching model-name prefix.
[LlmConfig.samplingProfiles](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmConfig.java)
loads this immutable configuration at startup using
`plowshare.llm.sampling-directory` (currently defaulting to `sampling`, relative to
the process directory). This is operator configuration, separate from artifact
data and project-owned agent definitions.

Extend these files with a versioned model-profile schema containing two typed
sections: the existing sampling modes/intents and native media capabilities.
Keep sampling values separate from capability values internally; the dispatcher
consumes an immutable resolved capability value, not raw YAML or a second copy of
deployment configuration. The existing sampling resolution API can remain as a
compatibility projection of this richer resolved profile.

Complete disk profile `capture-reader.yaml` supported by the configuration slice
(illustrative, not a claim
about any named deployed model; empty intent rows send no sampling overrides):

```yaml
version: 2
modes:
  default:
    precise: {}
    balanced: {}
    exploratory: {}
media:
  inputs: [text/plain, image/png]
  outputs:
    text:
      formats: [text/plain]
```

The corresponding external `models.yaml` can add:

```yaml
models:
  capture-reader-v1:
    profile: capture-reader
    mode: default
```

`inputs` and `outputs` are independent. Output modes name supported combinations
of formats in one successful response; they are not just a union of MIME types:

| Model output ability | Declared output modes |
| --- | --- |
| Text only | `text: {formats: [text/plain]}` |
| Image only | `image: {formats: [image/png]}` |
| Either text or image, in separate responses | Both `text` and `image` modes |
| Text and image together | `mixed: {formats: [text/plain, image/png]}`; add the individual modes only if those responses are also supported |

The request declares required and permitted output formats; routing selects one
mode whose combination includes all required formats and no disallowed formats.
Mode names are local profile labels, not globally interchangeable backend API
values. A mixed mode declares that the formats may occur together; it does not
promise that every response always includes every listed format. A request that
requires both must explicitly require both, and successful output must contain
both. Refusals/errors keep their existing distinct lifecycle. Validate required
parts and decoded format before reporting successful content completion; never
silently drop a required output format. Do not require every
model to output text: an image-only model is a valid profile but cannot satisfy a
text-output conversational call. Tool calling remains a separate existing route
requirement; output-format declarations do not imply tool support.

Profile and loading rules for this slice:

- Keep unversioned sampling files compatible. Missing media declarations mean
  unknown native media support, not inferred image/audio/video capabilities.
  Legacy text calls keep their existing defaults. New media routes require an
  explicit compatible declaration.
- Use exact normalized MIME values and data-defined output mode/profile names,
  with strict schema validation, bounded collections and no duplicate keys.
  Reject malformed versions, unknown fields in the new schema, invalid MIME
  values, empty combinations and dangling mappings before enabling routes.
- Resolve capabilities for the actual wire model using the existing model mapping
  and mode resolution. Keep exact/longest-prefix precedence; a family mapping is
  an operator declaration that the whole matched family shares the profile.
  Recommend exact deployed model mappings for media rather than adding a second
  exact-only resolver. Reject explicit missing modes in version 2 files at boot;
  retain the existing warning/fallback for legacy sampling files. Version 2 media
  declarations apply across that file's modes; use a separate mapped profile when
  capabilities differ, avoiding mode-specific inheritance machinery.
- The effective route is the resolved profile constrained by the concrete
  transport's verified support and existing endpoint restrictions. Neither a
  profile nor an agent override can enable an absent encoder/decoder. Separate
  endpoint profile bindings are later work unless an acceptance deployment proves
  they are required; do not duplicate format lists in pool configuration now.
  Profile capabilities do not change numerical sampling resolution or an agent's
  existing explicit sampling overrides.
- Preserve replacement/merge precedence. Startup diagnostics identify loaded
  files, resolved model/profile/mode and effective input/output modes. Pin the
  resolved profile digest/version, selected output mode and actual model/pool in
  the authorized observation manifest linked to each media call. Historical
  manifests retain the applied snapshot
  identity; resume revalidates against current configuration.
- Mount the operator directory read-only to the server and set
  `plowshare.llm.sampling-directory` through deployment configuration. Adding a
  profile, format declaration or model mapping uses ordinary disk files and a
  server restart, with **no resource generation, JAR rebuild or image rebuild**.
  Built-ins remain a seed/default set; external profiles are independently
  authorable and need not be copied into `src/main/resources`.
- Preserve the optional absent default directory for legacy deployments. For a
  deployment explicitly relying on external media profiles, require the configured
  directory and required profile/mapping to exist and validate; an absent mount
  must not silently enable media from a bundled fallback. Test startup using the
  same packaged server artifact and two different external profile directories.
- Keep startup loading as the first-slice contract. Live reload is separate work:
  it would need atomic snapshot replacement, routing/reservation coordination and
  matching filesystem exclusions. Existing
  [AgentsConfig workspace fencing](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java)
  already protects the sampling directory using this same property; preserve it
  so project tools cannot rewrite their own model capabilities. Correct deployment
  documentation to use the actual property; the current resource header's
  `PLOWSHARE_SAMPLING_DIR` shorthand is not an independently verified binding.

The first implementation slice adds version 2 profile loading and typed media
declarations, documented in [model profiles](model-profiles.md). The routing slice
adds format eligibility to the existing dispatcher, including explicit format
contracts and profiled image requests. It does not yet implement artifact
admission/context policy or the durable screenshot path. Require the profile-aware
server version before relying on these declarations: older loaders can ignore
extra top-level fields. Successful sampling-file parsing on an older server is
not proof that media declarations are enforced.

### Format matching in the existing router

The router now uses typed format requirements and resolved model-profile
capabilities. Ordinary text and unprofiled legacy image calls retain their prior
path; an explicit internal request contract or a profiled image call uses strict
matching. The later shared tool-content path must attach its validated native
requirements here. A boolean `vision` declaration alone cannot distinguish
PNG/JPEG or future audio/video formats.

```text
MediaRequirements
  inputs: validated set of actual content MIME types
  requiredOutputs: formats required in that response
  permittedOutputs: allowed response formats

ModelMediaCapabilities, resolved from the selected disk-loaded model profile
  inputs: declared native input formats
  outputs: named supported combinations of native output formats

effective capability = model declaration intersected with transport support
eligible route = configured model policy
                 AND all requested input formats are supported
                 AND one output mode contains required formats and no disallowed formats
                 AND the applicable media/context limits pass
```

Derive an artifact's media type from validated file content, not its suffix,
uploader-declared Content-Type or an unvalidated provider value. Existing
[ImageFormat](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/ImageFormat.java)
already distinguishes PNG/JPEG/GIF/WebP; the new admission additionally validates
the complete bounded PNG before assigning `image/png`. Mixed requests require the
intersection of **all** included types, not a match for just one attachment.

Resolve the profile for the concrete model/backend route because two endpoints
serving the same model can expose different formats. The transport implementation
supplies what it can actually encode/decode. A model declaration cannot enable an
unimplemented serializer. Match normalized exact MIME types initially, without
an implicit `image/*`, `audio/*` or `video/*` permission. Unknown capability is
unsupported. Output capabilities are independent: a tool returning a PNG as an
observation requests image input to the model, not image generation by the model.
An OUTPUT-only tool artifact imposes no image-input or native image-output
requirement on the conversational model. A generating provider owns its own
model/service profile; its output declaration cannot grant native generation to
the conversational endpoint.

For example, with the same configured model eligible on two pools, a pool that
supports PNG/JPEG remains eligible for a PNG request while a JPEG-only pool is
excluded before comparing load. An audio/wav request uses the same matcher against
audio input declarations. These are illustrative declarations, not claims about
any deployed model. Future audio/video profiles must also validate codecs,
duration/frame constraints and timing requirements; MIME alone is not sufficient
for every encoding inside a container.

Implement the matching in the existing pool selection path:

1. Build typed `MediaRequirements` from the complete validated effective prompt
   and requested output. Recompute after hooks and `ChatFiltering.input` change
   that prompt. Do not trust a
   caller's `supportsImages` flag or only inspect the latest result. Include
   native OBSERVATION/BOTH media, not OUTPUT-only references delivered to a user.
2. Resolve the configured model policy, then remove pools whose effective native
   format capabilities cannot satisfy the request. Choose the least-loaded pool
   from the remaining set. An empty media-compatible set returns an explicit
   unsupported-media outcome, distinct from an unknown model specifier.
3. Use the same eligibility predicate for scheduler admission and for final
   blocking/streaming/`streamOn` checks before counting or inference. A reserved
   slot must not bypass the format/profile check. Required counting/limit support
   is validated alongside the selected route.
4. Preserve the configured wire model across the screenshot observation. A new
   tool result does not trigger a model switch, auxiliary inference or delegation.
   Existing explicit escalation/delegation policies remain distinct; validate a
   later selected model again and do not attribute its observation to the former
   model. The first acceptance fixture disables model-changing escalation.
5. Link requested specifier, actual pool/wire model, resolved profile identity,
   selected output mode, observation mapping and artifact digests through the
   observation manifest and existing inference call ID. Load balancing between
   eligible hosts of the same model need not pin the entire run to one host.

Retain `vision: true` as the existing agent requirement during compatibility
migration. Its broad pool `vision` list does not imply support for every image
format. Legacy routes retain their current checks. The negotiated content profile
uses explicit per-format capabilities and validates that a compatible route
exists, rather than demanding that every otherwise eligible pool support every
format. Reject contradictory declarations; do not invent format capabilities
from a model name. Text-only callers keep their existing routing behavior.

Update the negotiated path through `AgentsConfig.requireModelSees` as well as the
dispatcher: leaving its current unconditional all-pool `requireSees` boot check
in place would reject a valid deployment before format filtering could run. For
the enabled new profile, validate at boot that a compatible image route exists,
then enforce the complete request at call time. Preserve the old guard when the
new capability is disabled. Keep `vision: true` as the agent's opt-in; the new
profile is the format declaration, while an existing endpoint `vision` omission
remains a restriction rather than an inferred grant.

[LlmConfig.provider](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmConfig.java)
currently composes OpenAI-compatible transport and an LM Studio wrapper. The
first supported image profile uses the existing OpenAI-compatible image input
serializer. The LM Studio prefill-progress WebSocket profile remains refused;
enabling it requires its own verified image mapping. Do not silently retry an
uncertain model call on another transport or pool.

No new model lane or separate modality-specific router is required. Implement the
shared format-matching contract now with PNG input/text output as the first
working profile. Audio/video later supply their native declarations and transport
profiles through that same mechanism, together with the required content/output
contracts and accounting. Merely listing `audio/wav` or `video/mp4` cannot advertise
working support while its serializer or validation profile is absent.

### Active model serialization

Extend the internal tool observation boundary so `AgentTool`, `RelayAgentTool`,
`JobRuntime`, hooks and history carry the shared typed result without a string
round-trip. Legacy text tools adapt to one text part.

For the first supported OpenAI-compatible backend, emit a bounded text
acknowledgement answering the exact `tool_call_id`, followed by a harness-owned
observation containing the **complete ordered text/image body**. This preserves
text between images without duplicating all text in two messages. All-text results
keep their existing tool-message wire shape. The wire adapter may use the
backend's user-role image shape, but durable provenance labels it as the tool
observation, not a new human utterance. An internal observation group binds these
adjacent messages to the original tool result. Count the acknowledgement and
mapping text in context admission. Validate the actual backend wire shape in the
acceptance fixture before enabling the profile.

Do not simply remove the current tool-role image guard. Other backends may not
accept media in that role. Keep the LM Studio prefill-progress transport refusal
unless its separate wire profile is verified. Refuse unsupported model/backend
combinations before sending a text-only substitute.

Multiple tool results must first satisfy normal call/result pairing. Only then
emit the associated image observations in deterministic call order. Folding and
history pruning treat the result and image observation group as one unit.
Require the active definition's existing `vision: true` requirement and a capable
selected backend for native OBSERVATION/BOTH parts. OUTPUT-only media results do
not impose that requirement. Validate declared image-result requirements before dispatch
where discovery supplies them. If an unexpected media result is produced anyway,
retain its raw receipt and stop with an explicit unsupported-observation outcome;
do not rerun the tool or switch to an auxiliary model automatically.

### Compaction and resume

Preserve the complete immutable trajectory. Compaction may replace an older
observation group in model context with a text seam and retained reference index;
it must not claim that summary text is a fresh visual observation. Original
artifact references remain redeemable through the existing result-handle path.

Current-turn observations retained by the projection reattach their exact bytes
on authorized resume. An older folded image is not automatically reinserted into
every prompt. Extend result redemption to return typed content when explicitly
requested, under the existing handle/read authority and context budget.

Missing, purged or revoked media fails before submitting a reconstructed native
observation. History can still explain that an observation occurred and its
payload is unavailable. An explicit later text-only continuation may acknowledge
that limitation; automatic silent degradation is prohibited.

Make all current text truncation boundaries explicit. A shortened result preview
must not be returned as a complete `ContentBody`: return the bounded original body
or a typed stored-result reference with its existing handle and cut status.
Image-only bodies still count as payloads when `content` is empty; update
`payloadsHeldBy`, ejection eligibility, result listing and character-based caches
accordingly. The body/attachment count supplies bounds independently of text
length. `result_read` reuses the original admitted media association and current
context checks, rather than treating historical prior fit as proof of current fit.

### Hooks and retained raw effects

[ToolPost](../plowshare-server/src/main/java/io/aeyer/plowshare/server/hooks/ToolPost.java)
and [Hooks](../plowshare-server/src/main/java/io/aeyer/plowshare/server/hooks/Hooks.java)
currently transform string results. Add a typed tool-post path with explicit
keep, replace-text and withhold-body outcomes. Hooks receive safe descriptors and
provenance, not binary data. They cannot introduce or exchange media references
without normal artifact authorization.

Adapt text-only tools without changing their hook behavior. For a media result,
require each applicable transforming/security hook to declare supported typed
semantics; otherwise stop before model consumption with a recoverable explicit
refusal. A legacy rewrite must not accidentally approve or discard media. Preserve
the raw receipt and its artifact hold when a post hook fails, so recovery need not
repeat a completed screenshot operation.

## Retention and deletion

Model retention as explicit idempotent blob claims, not reachability from strings
or an increment/decrement per retry. Owners are image retention, artifact
admission, provider invocation result, Relay publication, trajectory entry and,
where it still uses local blobs, a retained export. Each system retains its own
records and releases only its claims. A verified self-contained export has its own
copied bytes and does not indefinitely pin the live store solely because it exists.

- An admission holds its artifact through the invocation's unresolved/recovery
  lifetime. Bound admission count by the retained result profile and reject new
  ordinals after invocation settlement; repeat identical committed receipts can
  still reconcile. Bound private staging/spool files separately.
- A retained invocation or trajectory payload holds its referenced artifact.
  Compaction alone releases neither hold.
- Relay publication expiry releases that publication's claim in its prune
  transaction. It cannot release an image, invocation or entry claim. Delivery
  acknowledgement remains distinct from retention expiry. A consumer retaining
  content must acquire its own claim while an authorized source claim survives;
  reading an ID into memory is not a retention handoff.
- Revocation affects disclosure immediately at the next authorization boundary;
  it does not require deleting immutable execution metadata.
- Conversation ejection uses the existing staged lifecycle. Export a manifest
  and authorized binary payloads with full digests when the existing export policy
  is enabled. Verify byte counts/digests before marking an enabled export complete.
  Preserve `PayloadExport.NONE`: explicit no-export ejection does not suddenly
  require a new archive destination. Atomically eject the typed body as well as
  text and mark source entry attachment access unavailable. Retained raw provider
  receipts are provenance, not a bypass allowing `result_read`, final outputs or
  linked HTTP reads to resurrect an ejected entry's payload.
- The current image store has no deletion API. The foundation preserves that
  lifetime with explicit non-expiring image-retention claims on adopted held
  files. Unregistered files remain protected. It does not collect old uploads
  merely because an invocation settled or a conversation was ejected.
- `BinaryBlobStorage` alone collects registered, fully managed objects after the
  final claim is released. Use row-locked zero-claim recheck, durable deletion
  state, write/read exclusion and retry after crash/I/O failure, as specified in
  the foundation plan. Last-claim release is not a filesystem unlink inside the
  owner's database transaction. Retained legacy pins mean this foundation alone
  does not reclaim ordinary image disk usage. Legacy image deletion is later scope.
- Shared byte objects cannot be collected solely because an attachment table
  has no links. Claim creation/deletion follows each owner's transaction and
  cascade lifecycle; no caller-supplied hash confers attachment or read rights.
  Ejecting one source entry does not revoke an independent still-authorized entry
  or pre-existing legacy image use in the same home. Preserve those existing
  contracts; do not present logical entry ejection as system-wide media erasure.

Backup/restore must cover artifact metadata, links and referenced image bytes as
one recoverable set. Restore verifies descriptors before permitting model replay.

## Context admission and accounting

### Media inspection is separate from document extraction

The internal PNG inspection capability is implemented; see the
[current contract and limits](media-inspection.md). It uses Tika core detection,
structural/expansion bounds and JDK pixel decoding. The invocation-admission
consumer and persistence of the validated descriptor remain proposed below.

The earlier decision to exclude Tika from document extraction was about avoiding
an expanding document extraction/transformation pipeline. It is not a prohibition
on using Tika for a different capability: identifying admitted media and reading
the properties needed for validation and routing. Do not use that document
decision as an architectural objection to media metadata inspection.

Evaluate Tika's detector and selected media parser modules for this narrow
boundary, following its [individual-parser dependency support](https://tika.apache.org/docs/4.1.x/using-tika/java-api/index.html#_dependencies).
Consumers receive a validated immutable media descriptor; Tika types, raw metadata
bags and parser selection remain inside the implementation. Parse only the
explicitly admitted media formats and normalize an allowlist of required fields
into the existing typed image details, later audio/video details. Do not turn on
general document parsing, recursive embedded extraction, OCR, transcription,
conversion, indexing or corpus admission as a consequence of choosing Tika.
Required metadata unavailable from a parser is an explicit unsupported/unknown
result, not a guessed value. Keep byte, memory, work and concurrency bounds at
this boundary; metadata extraction does not replace complete media validation.

For the PNG acceptance path, [JDK ImageReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/imageio/ImageReader.html)
also exposes detected format and pixel dimensions and can perform bounded image
decoding. It is an implementation option, not a reason to reject a Tika-backed
inspection implementation. The first delivery still admits PNG only; broader
library coverage does not automatically expand advertised platform support.

Populate the artifact descriptor once at admission. Derive byte length and
digest from the admitted bytes, validate dimensions before allocating a full
decoded image, and retain the validated result with its immutable receipt. Do
not repeatedly parse the file during routing or copy arbitrary embedded metadata
into logs/model context.

Keep two inputs distinct: validated file properties describe the media; the
disk-loaded model profile describes supported formats, context limits and the
applicable image-cost rule. Image metadata does not describe the selected model's
resize/patch/tile preprocessing or its context cost. Compute admission from the
descriptor plus that declared model/backend policy; a fixed verified maximum per
admitted image is sufficient for the first slice, without an exact vision
tokenizer. Model metadata exposed by a backend may supply a limit/rule only when
its meaning and applicability to that deployed route are established. No generic
model-metadata discovery or model-file parser is required in this slice.

Image input already makes
[OpenAiTransport counting](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/openai/OpenAiTransport.java)
return `unsupported_images`. Do not turn that unknown into zero or use PNG byte
size/character count as a token bound.

Enable the first image observation profile only with a configured, tested
backend/model admission policy: an exact counter or a verified conservative
image-cost bound based on the actual image preprocessing/profile. Bound the
number of admitted images in the complete selected prompt, not just one result.
If the model/profile has no usable bound, return an explicit unsupported admission
outcome. Pin the policy in the consuming model observation manifest.

For the first supported deployment, use one tested conservative policy for its
actual image preprocessing and declared limits. Carry its typed policy identity,
maximum admitted image count/pixels and proven token bound with the resolved model
profile; do not create an estimator plugin registry. Combine the image bound with
the existing text/tools count, mapping overhead and reserved output budget against
the configured context window. Enforce this even when a pool's optional automatic
counting is off. Feed the same typed estimate/unknown semantics to context
inspection and compaction admission; billing preflight telemetry alone is not an
enforcement mechanism. Selecting that real model/policy and validating its bound
is a release dependency, not something the generic MIME matcher proves.
The schema must name these admission fields explicitly: policy ID/revision,
`maxImages`, `maxPixelsPerImage` and `maxInputTokensPerImage` (or the selected
verified exact-counter policy). The format-only profile example above cannot
enable native image observation until that policy is configured. Do not fill in
a guessed token value just to make a profile runnable.

Reuse [UsageObservation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageObservation.java)
for provider-reported aggregate input/output/cache/reasoning measurements. Preserve
source and unknown semantics. The image estimate is preflight occupancy evidence,
not a fabricated provider bill. Record actual inference once on the existing
model-call ledger; upload/Relay delivery does not create another inference charge.
General audio/video usage dimensions are deferred.

## Implementation sequence and review units

| Step | Concrete changes | Exit evidence |
| --- | --- | --- |
| Already implemented | Disk-loaded profiles, existing-dispatcher media eligibility, bounded PNG inspection and invocation admission/status under Decisions 0043–0046. These are current worktree changes, not a deployed end-to-end feature. | Preserve their existing tests, ordinary upload behavior, feature flag and text routing. No new context-fit or screenshot acceptance claim. |
| F Implemented: BinaryBlobStorage foundation | Internal blob/claim interfaces and specialist repository; adopt existing disk files in place, map scoped image IDs to full digests, retain image pins, commit admission claims transactionally and add bounded safe cleanup. See Decision 0046 and the focused foundation plan. | No duplicate byte write on adoption; retry-safe claims, rollback/cascade and attach/delete/read races; existing IDs/uploads/sidecars/workspace fences unchanged. Relay/Trajectory are untouched. |
| R Implemented: Relay BLOB delivery | Independently add generic BLOB payloads, explicit SDK binary transfer, placement/reuse for image-first and BLOB-first objects, publication claims and prune release. Keep delivery/acknowledgement/gap semantics and old TEXT compatibility. | Authorized binary round-trip; publication expiry releases only Relay's claim; other owners retain the bytes; same-scope duplicate content has one canonical location. No image decoding or trajectory policy in Relay. |
| C Implemented: Node PNG tool results | `ToolContent.Body` has ordered text/PNG references and intended use. Negotiated v3 envelopes, generated owner-read contracts and Node journal support reuse the existing admission claims and invocation result column. Generic BLOB delivery does not replace exact producing-invocation validation. Other producer SDKs and hosted workers remain text-only. | Legacy text remains compatible; ordered parts, changed-result conflicts and restart/uncertain-delivery recovery retain original bytes and provenance without recapture. |
| T PNG subset implemented: active loop and durable history | Typed AgentTool seam, original/effective hook handling, atomic entry claims, derived native observations and context admission. See Decision 0050. Broader DTOs, manifests and typed redemption remain future work. | Exact retained PNG reconstruction after restart/invocation removal; independent ejection and fold retention; native consumption requires a verified complete image count. Deployed backend acceptance remains separate. |
| O Inspection/output retention | Extend export/ejection, authenticated image retrieval and final Outcome/job/SDK output associations. Preserve text-client descriptor fallbacks and release each owning record's claims transactionally. | Export/no-export, retained-image pins, output provenance and revocation tests pass; ejection does not delete another owner's bytes or resurrect inaccessible payloads. |
| A Public SDK media acceptance | External browser and deterministic PNG-producing providers through Java/TS SDKs; pinned real vision model/backend and verified image context policy. Adapter adoption remains separate. | Screenshot reaches the same active model; text-only agent exposes a retrievable PNG output; record exact source/model/digest/invocation/observation associations. No internal/SQL shortcut. |

F is the implemented foundation, R its delivery consumer and C the implemented
Node producer/result subset. T's PNG observation subset is implemented; its broader
read contracts and the O/A delivery and live acceptance work remain later slices.
The implemented JPEG/WebP expansion above extends C/T without changing F/R ownership.
Partial merges keep unsupported media capability advertisement disabled.
Later adapter adoption follows [ADR 0001](decisions/0001-integration-boundary.md).
A fake-transport unit test alone does not satisfy the screenshot acceptance case.

## Wider impact map

“Required” means change before advertising the complete media delivery, not that
every area belongs in the blob foundation. “Guard” means inspect and test
compatibility/refusal without enabling media there. “Later” is a separate feature,
not implied authorization to modify that subsystem. F's exact change set is in
the foundation plan.

| Area and current source | Possible impact | Treatment |
| --- | --- | --- |
| Proposed `BinaryBlobStorage`, existing [ImageWrites](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageWrites.java) and [ImageStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageStore.java) | Canonical byte location, scoped full-digest identity, idempotent claims, legacy pins and deletion/read/write races. | Foundation required: reuse disk mechanics/adopt files in place and preserve image APIs. No global deduplication, bulk file scan or media metadata inside the blob registry. |
| [Provider journals and SDK tools](../sdk/typescript/src/operations/relay-tools.ts), [Python](../sdk/python/src/plowshare/tools.py), [Go](../sdk/go/tools.go), [.NET](../sdk/dotnet/Plowshare.Sdk/Tools.cs) | Result identity, journal recovery, old envelope decoding, content bounds and UNKNOWN handling. | Required generated read contracts/compatible decoders across SDKs, and Java/TS media producer/journal support for acceptance. Other producer SDKs keep their existing text-only advertisement; full media authoring parity is Later. |
| [Native MCP hosting](native-mcp-hosting.md), [native host](../integrations/mcp/src/native-host.ts), [journal](../integrations/mcp/src/journal.ts) | Direct local calls may run without any server, while shared tools use Relay. Media must not force an implicit server upload from an unshared local session. | Guard current non-text refusal and direct local behavior. MCP PNG adoption and local-only artifact ownership are Later; neither gates the direct ToolProvider fixture. |
| [Relay payloads](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayPayload.java), [log repository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayLogRepository.java), [RelayLog DTOs](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/RelayLog.java) | BLOB payload, generic byte transfer, publication claims/prune, cursor/lease compatibility, descriptor bounds and causation. | Implemented R slice after foundation: typed BLOB contract and owner-transaction claim lifecycle. Keep Relay independent of image interpretation and trajectory retention; ordinary inspection remains descriptor-only. |
| [Trajectory API](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationTrajectoryHandler.java), [EntryPage](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryPage.java), [EntryView](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/EntryView.java), [ProjectionView](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ProjectionView.java) | Text length/excerpt/cut fields cannot describe images; pagination must preserve complete reference order without binary hydration. | Required additive body/descriptors and compatibility fields; use bounded set-based descriptor reads. |
| [Context inspection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContextCountFrames.java), [context authority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ConversationContextAccess.java) | A prompt preview must explain image input without exposing bytes or treating human usage-report authority as model context authority. | Required sanitized descriptors and admission estimates under the existing distinct access checks. |
| [LLM dispatcher](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java), [pools](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java), [provider composition](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmConfig.java) | A declared vision model can still be reached through a backend that does not accept the actual image type. Input/output formats and scheduler-pinned routes need the same eligibility rule. | Required typed media-format filtering before load balancing plus final mapping/count admission on chat/stream/streamOn. Preserve model policy and record actual model/pool; no implicit model switch. |
| [Sampling profiles/loader](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/SamplingProfiles.java), [model mappings](../plowshare-server/src/main/resources/sampling/models.yaml), [configuration property](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmProperties.java), [workspace exclusions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java) | Deployment profile overrides, model-mode resolution, output combinations, startup failure and agent filesystem fences must remain consistent. Broad sampling-family matching must not accidentally advertise media for an unverified model. | Required versioned typed profile extension, existing mapping precedence, external-directory startup validation and deployment docs. Recommend exact media model keys. Prove unchanged packaged artifact with independently edited profiles; live reload and endpoint profile overrides are Later. |
| [Retention](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Retention.java), [PayloadExport](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PayloadExport.java), [ResultTools](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ResultTools.java) | Payload ejection/export and result redemption can otherwise orphan bytes or promise media that is gone. | Separate T/O slices: export manifests, typed redemption and entry/result claim release with logical ejection. Preserve staged retention and explicit no-export policy. Storage performs last-claim collection; legacy image-record deletion remains Later. |
| [Log search](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/LogSearch.java), [model-facing trajectory tool](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ConversationTrajectoryTool.java) | Text search snippets and reach counts do not search pixels; a textual trajectory inspection is not a fresh visual observation. | Required coverage labels and descriptor projection; text retrieval cannot claim complete media-content search. No implicit OCR or reference hydration. |
| [JobLog](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JobLog.java), [ReasonLog](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ReasonLog.java), [LogStages](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/LogStages.java), [PROC audit](../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/ProcAudit.java) | Job diagnostics, memory reasons, lifecycle notifications and operational tool metadata serve different purposes from transcript content. | Guard these distinct logs and read permissions. Keep safe IDs/status only; link to owning trajectory/receipt for authorized content. No universal log-body conversion or binary in diagnostic events. |
| [Hooks](../plowshare-server/src/main/java/io/aeyer/plowshare/server/hooks/Hooks.java), [script hooks](../plowshare-server/src/main/java/io/aeyer/plowshare/server/hooks/script/ScriptHooks.java) | Tool-post transformations and hook traces are string-shaped; media can bypass a text-only decision. | Required typed tool-post compatibility and explicit withholding; retain raw effects independently. |
| [Approvals](../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunApproval.java), [continuation recovery](../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/ApprovalContinuationRecovery.java) | Paused runs must retain accepted screenshots; approval preview must not disclose them to a different principal. Approval cannot grant unrelated artifact access. | Required recovery/authorization cases; no new approval type or screenshot-specific approval prompt. |
| [Security review](../plowshare-server/src/main/java/io/aeyer/plowshare/server/security/RelayMessageReview.java) | Text review sees prose, not pixels. A clean text review cannot be reported as media inspection. | Guard: classify coverage explicitly and fail media consumption if configured policy requires an unsupported media review. Add a media reviewer only as Later scope. |
| [Hosted tools](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/HostedInvocation.java), [Application tool scopes](../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationToolScopes.java) | Owner, executor, credential subject and registration generation differ. Root/account/service revocation can change authority during recovery. | Required exact binding checks; retain manifest/tool ceilings and distinguish revoked access from missing content. |
| [Delegation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRunTool.java) | Child model observations need explicit authorized references; child inspection cannot expand parent visibility. | Guard current image behavior; new artifact forwarding is explicit and retained if enabled. Automatic inheritance and media child outputs are Later. |
| [Outcome](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Outcome.java), [Completion](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/Completion.java), generator ToolProviders | A provider-produced image can be a user output without being native LLM output or an image input to the text agent. Text-only job results otherwise lose the deliverable association. | Required typed final output links with source tool provenance and withholding state. Native Completion media decoding and bundled generators are Later; their outputs reuse the same item/reference path. |
| [Orchestration records](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationRecord.java), [script receipts](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptStore.java), [command bindings](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/CommandInvocations.java) | Stage results, cached commands and raw receipts contain strings. A conductor may inspect a child with media while its own stage output remains text. | Guard existing stage/command behavior and no-replay authority. If additive Outcome fields require changes, preserve typed output references or explicitly expose them through the source conversation; do not flatten them into claimed image delivery. New media stage contracts/fixtures are Later. |
| [Messaging](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessaging.java), [BoardMessage](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessage.java), [message wake](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/MessageWake.java) | Routing, body hashes, incoming turns, return addresses and cross-project delivery cannot safely inherit media from a text mention. | Guard text behavior and causation. Media messages require separate source/destination authorization and ownership design. |
| [Board seats](../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/SeatRunner.java), [swarm scheduling](../plowshare-server/src/main/java/io/aeyer/plowshare/server/swarm/SwarmScheduling.java) | Participants may gain access to an observation through shared discussion or copied context. | Guard no implicit media inheritance. Typed shared deliberation input/output is Later. |
| [Learner](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Learner.java), [scribe](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java), [digests](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digester.java) | Text summaries can accidentally claim visual inspection, broaden disclosure or duplicate large descriptors. | Guard text-only learning with explicit media omission/provenance; no automatic artifact inference or new processing authority. |
| [Information processing](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationLifecycle.java), [portable exchange](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/PortableInformation.java) | Artifact storage could be confused with corpus admission, schema metadata, evidence offsets or bounded bundle export. | Guard existing document lifecycle and SYSTEM processing ceiling. Linking an artifact as authored evidence/OCR output is Later. |
| [File channels](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/FileContents.java), [image store](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageStore.java), [Node enforcer](../sdk/node/src/enforcer.ts) | A mutable workspace image differs from an immutable captured snapshot. Snapshot admission must not bypass workspace roots, source hash checks or byte bounds. | Guard existing file operations. First-slice producer submits bytes already captured under its own authorized capability; no arbitrary server filesystem read. |
| [Incoming](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/Incoming.java), [A2A receiver](../integrations/a2a/src/main/java/io/aeyer/plowshare/a2a/Receiver.java) | Text-only provenance and reply contracts cannot retain media by removing one adapter refusal. | Guard current text/plain advertisement/refusal. Native media ingress is Later shared-contract work. |
| [ExternalMessage](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/ExternalMessage.java), [OutgoingTool](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OutgoingTool.java) | External raw base64 is currently serialized into a text tool result; a remote URL is neither a local artifact nor permission to fetch it. | Record as a separate projection/privacy follow-up. Do not reuse whole-object serialization for the new content path or automatically import remote files. |
| [Desktop rendering](../plowshare-desktop/src/renderer/app.ts), [console](../plowshare-console/src/repl/render.ts), [TUI markdown](../plowshare-tui/src/logic/markdown.ts), CLI and MCP facade | Text excerpts, current job updates, trajectory navigation, copy/export and historical reads need a meaningful media fallback. | Required compatible shared DTO reads and meaningful bounded text descriptors. SDK-authenticated byte retrieval proves the first acceptance path. GUI viewers, terminal image protocols and audio players are Later. |
| [Generated contracts](../scripts/generate-sdk-contracts.mjs), [native constraints](../scripts/native-sdk-constraints.mjs), [segmented messages](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/transport/SegmentedMessages.java) | New bodies affect strict decoders, catalogue generation, SDK union handling, frame sizing and old-server/new-client behavior. | Required generator-owned updates and shared fixtures; metadata transport remains bounded and binary uploads remain explicit HTTP. |
| [Projection caches](../plowshare-server/src/main/java/io/aeyer/plowshare/server/cache/VersionedProjectionCache.java), backups and diagnostics | Cached descriptors can outlive authorization; repeated per-reference reads can create N+1 queries; dumps/exports can leak bytes. | Required current authorization, bounded metadata batching, no binary cache on log paths, redaction and restore checks. No speculative new shared cache. |

## Validation plan

Use mocked repositories and deterministic clocks/fake transports for ordinary
behavior. The following tests are proposed implementation checks, not results
claimed by this planning task.

| Boundary | Positive and negative evidence |
| --- | --- |
| Blob foundation | In-place adoption/no duplicate write; unchanged scoped image IDs/sidecars; full-digest conflicts; idempotent claims; image-retention pins; receipt rollback/cascade; zero-claim transition, read/attach/delete races and restart; unregistered/legacy files never swept. Scope and exact foundation checks are in the focused plan. |
| Relay BLOB follow-up | Generic byte round-trip with current publication authority; typed wire/SDK negotiation and bounds; same-scope deduplication in both arrival orders; expiry releases only the publication claim; acknowledgement is not deletion; no trajectory/media-service dependency. |
| Shared content and SDKs | Text byte compatibility; interleaved part order; duplicate/malformed IDs; wrong version; unknown fields; count/text/envelope bounds; canonical identity conflicts; mixed SDK versions. |
| Artifact ownership | Correct project producer; hosted owner/executor split; same digest under foreign owner; wrong invocation/call; sibling service token; stale provider generation; Application revocation; no side effect on invalid admission. |
| Decoder and resolver | Real small PNG; forged MIME; signature-only/truncated PNG; decompression/pixel limits; unsupported animation; changed digest; path/URL input rejected; bounded memory/concurrency and sanitized failures. |
| Receipt lifecycle | Crash before durable capture/spool, after capture/spool, after admission, after result ready, after Relay publication, after ledger completion and before entry append. Pre-receipt uncertainty remains UNKNOWN without automatic recapture; recover committed bytes/identities without duplicate publication or trajectory results. |
| Model profiles and deployment | Extend `SamplingProfilesTest`: unversioned sampling compatibility; disk discovery of new profile names; full-file replacement and mapping merge; existing exact/longest-prefix precedence; explicit missing version-2 mode; absent required mount; invalid/duplicate/unknown fields; text-only, image-only, separate text/image and combined output modes; profile versus concrete transport/endpoint restrictions; agent workspace cannot edit configuration. Start the same packaged artifact with two external directories and prove changed routing/profile receipts after restart without rebuilding resources or the image. |
| Model wire and routing | Capture the actual request and prove image bytes/digest, tool correlation, message ordering and same model identity. Text contains no answer-bearing marker. Test direct/class/system resolution; PNG versus JPEG-only routes; all formats in a mixed prompt; separate input/output capabilities; unknown declarations; lower-load incompatible endpoints; scheduler-pinned pools; unsupported serializers/counters. Exercise audio/video MIME matching with fakes without claiming native audio/video inference. |
| Generated output and delivery | A text-only agent invokes a PNG producer, retains exact output digest and generation provenance, and delivers its authorized reference in the final result. Test OUTPUT versus OBSERVATION/BOTH routing, unknown seed/model details, hook withholding, cancellation after generation, foreign output selection, multiple outputs, lost generation/upload/delivery replies, reconnect and restart. Recover the same committed output without producing it again; use a deterministic fixture, with paid-service recovery as later adopter evidence. |
| Compaction and resume | Preserve immutable full history; treat observation groups atomically; rehydrate current retained image; older folded image stays a reference until redemption; inaccessible reference prevents silent native replay. |
| Hooks and approvals | Withhold image result while retaining raw receipt; legacy unsupported transform refuses; hook cannot add foreign artifact; approval pause/restart preserves reference without executing the screenshot twice. |
| Actual model observation | Manifest links the existing inference call ID to the effective post-hook/post-`ChatFiltering.input` prompt; image-only bodies survive blank-text adapters. Manifest commit failure prevents send; uncertain send remains unknown; content is absent from the billing journal. Multiple tool calls keep all tool results before ordered observations. |
| Inspection and clients | Trajectory, projection, result-read and Relay logs display one coherent observation; foreign readers cannot fetch bytes; cached view after revocation fails retrieval; CLI/TUI fallback is explicit. |
| Retention | Mark/cancel/eject lifecycle; export digest failure aborts ejection; overlapping invocation/entry holds; Relay expiry does not delete live bytes; concurrent append/ejection and admission/settlement; no deletion of shared/legacy bytes; backup with missing object yields explicit unavailable state. |
| Wider text consumers | Existing Board/Messaging/swarm/orchestration/learner flows remain text-compatible and do not infer media authority from descriptors. A2A raw/URL parts are not implicitly resolved. |

Real PostgreSQL coverage is justified only for the new migration, row mapping,
atomic owner-and-claim commits, cascade-release trigger, rollback and concurrent
claim/delete, admission/settlement or append/ejection constraints. Place those tests behind `@Tag("full-db")`; state the exact
database-dependent assertion and use `-PfullDb --tests` on the smallest relevant
set. Do not run a general DB suite or `dockerSmoke` for confidence alone.

Extend existing focused suites such as `ContentPartsTest`, `CompactionTest`,
`RetentionTest`, `ProjectRelayToolInvocationsTest`, `JdbcRelayToolRepositoryTest`,
SDK `relay-tools.test.ts`, `SamplingProfilesTest` and `NothingEnumeratesImagesTest`.
MCP suites remain unchanged compatibility guards until adapter adoption. Add focused
artifact repository and ownership tests. Run Java `format` and `formatCheck`
after implementation, repository `check` before handoff, and the explicit native
`sdkCheck` entry point for native SDK changes. Keep adapter boundary checks.

The live acceptance fixture uses a real browser capture and a pinned configured
vision model/backend from separate processes through public APIs. Record exact
source/model/backend identities, the visual fixture, artifact digest, invocation
and observation receipts, restart/fold actions and actual pass/fail outcomes.
Preserve private origins and credentials outside source and evidence text.

## Alternatives rejected and implementation release gate

The proposed shared core contract is justified by server-owned native tool
observations and durable history, independently of any browser/MCP adapter.
Before implementing it, record this decision as a proposed platform ADR with the
then-current source baseline and implementation authorization. The plan alone is
not authorization to start the platform or adapter changes.

Base64 in text preserves neither native model input nor safe logging. A URL in a
tool result adds no retrieval authority. Delegating to an image reader changes
which model observed the screenshot. An adapter-owned log cannot repair server
resume/compaction. Replacing every body string throughout Plowshare is wider than
the first producer needs. Forcing screenshot admission through Information's
text-processing lifecycle conflates an execution artifact with corpus processing.

Do not advertise this capability until the full screenshot path, typed history,
retention, current authorization, external-profile loading and recovery assertions
pass. Keep first-slice admission behind an explicit deployment capability switch
while review units are
incomplete. Disable new admission to roll back behavior; retain typed readers and
artifact holds for already committed records. Rolling back to a binary that
cannot read new content requires an explicit compatibility barrier/export plan,
not dropping columns or flattening historical observations.

The release claim is deliberately specific: **a project-scoped browser PNG tool
result reaches the same active vision model and remains an authorized, inspectable,
recoverable execution observation; a provider-generated PNG can also be retained
and made available for authenticated retrieval by a text-only agent with its
production provenance intact**. The blob foundation alone makes no such release
claim. Native audio/video generation, bulk legacy image collection and proof that
a client viewed an output are not part of this claim.
Broader modality, messaging and protocol claims require their own acceptance
evidence.
