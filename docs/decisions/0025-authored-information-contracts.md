# ADR 0025: Authored information contracts and metadata-only processing

Status: accepted and implemented for the first milestone. Verification is recorded
in the [implementation plan](../authored-knowledge-implementation.md); deployment
is separate. Authored writes can be disabled through the documented configuration
switch without removing retained bindings or processing fences.

## Platform need and baseline

The [authored knowledge proposal](../authored-knowledge-design.md) adds optional
validated metadata to the existing retained information lifecycle. It is general
platform work under [ADR 0001](0001-integration-boundary.md), independently of any
adapter. Applications cannot enforce it across server admission paths using only
an SDK validator. No adapter-specific runtime or alternate information store is
introduced.

This decision was checked against main
`a6d642a9c8a12d419e6bb3c680617c7e826cdc3e` on 15 October 2026. The
[implementation plan](../authored-knowledge-implementation.md) identifies current
code owners, delivery gates and the source-grounded reuse investigation.

## Registry identities and authority

Document metadata and schema property/definition names reserve `__proto__`,
`prototype` and `constructor`, matching the public SDK object codecs. This
document-profile restriction does not change the existing tool schema vocabulary.

The server derives namespaces from authenticated personal/project selection using
the existing information namespace rules. Clients cannot select another account
through payload fields. Slugs are namespace-local lowercase ASCII identifiers of
1–64 characters: start with a letter, end with a letter or digit, and contain only
letters, digits and interior hyphens. Stable type and immutable version identities
are UUIDs. Labels have 1–120 characters and descriptions at most 4,096 characters.
Positive version ordinals are server-allocated under the type lock. A separate
positive control revision fences lifecycle changes.

Personal types are managed by their owner. Project management requires the
existing effective `MANAGER` role, including its current account and Application
ceilings. Creating documents requires the existing information write authority;
type management never grants resource ownership. Model-facing tools expose
published discovery and metadata validation/admission only. No schema-management
tools are granted to models. No new service-token authority is introduced.

Definitions, presentation and profile identity are immutable from creation.
Transitions are `draft -> active -> deprecated`. Deprecated versions cannot be
reactivated; publish a new numbered draft even when its definition is identical.
Activation atomically deprecates the previous active version and increments the
type's control revision. Explicit deprecation applies to the active version.
Unpublished drafts remain manager-only and cannot be assigned to documents.
Existing bindings can inherit their exact deprecated version; new assignments and
explicit upgrades require an active version in the resource's own namespace.

Activation makes the exact contract readable through authorised attached
revisions. Attachment access requires an exact revision/version match; a guessed
version UUID is insufficient. Registry reads require namespace access. These two
read paths do not disclose unrelated versions or documents. Definition records
remain available to attached readers after registry membership changes.

## Metadata and validation contract

Pin `plowshare-document-metadata/1` to the executable semantics of
[ADR 0018](0018-shared-tool-schemas.md) and the corrected token identity in
[ADR 0023](0023-schema-fingerprint-key-ordering.md). Registration rejects features
outside that profile. Preserve 64 KiB schema, 16 KiB instance metadata and 8 KiB
presentation limits in compact UTF-8 JSON, as well as shared structural budgets.
Root metadata is an immutable object with explicit null distinct from omission.
Neither defaults nor format assertions are introduced.

The shared protocol owns a pure schema/value validation contract. `ToolSchemas`
remains a compatibility facade with unchanged acceptance and canonical/legacy
tool fingerprint behaviour. Document callers use neutral values and the document
facade, without constructing Relay declarations or tool invocations. Structural
decoding stays at codecs. Document hashes accept corrected canonical tokens only;
definition identity includes profile, schema and immutable presentation.

Document validation reports at most 32 issues and at most 8 KiB of compact UTF-8
error data. Each issue has instance pointer, schema pointer, keyword and a
code-owned message without instance values. Stop diagnostic collection at the
issue/byte limit with an explicit truncation flag. Diagnostic traversal consumes
the same bounded work as validation; exhausted work is a separate refusal, not a
successful or truncated validation. Invalid metadata and budget exhaustion leave
no admitted revision, processing work or model charge. Successful validation
means metadata conformance, without verifying the prose or factual support.

## Admission, concurrency and operation shapes

Every mutation has a caller-owned UUID `requestId`. Typed extensions use
`typing: {mode, version?, metadata?}` with closed `inherit`, `set` and `clear`
alternatives. `set` requires exact active version UUID and complete metadata;
`inherit` preserves the exact starting binding, optionally replacing the complete
metadata object; `clear` accepts neither version nor metadata. Unsupported fields
and combinations are refused. An untyped starting revision can inherit its
untyped state only with absent metadata.

Typed updates and metadata-only updates require `expectedRevision`, the starting
revision UUID and highest committed revision of the resource. Resource locks
fence both checks and ordinal allocation. Binding, body copying and provenance
inherit from that exact UUID. No automatic merge or head substitution occurs.
Legacy by-name writes to typed resources refuse with `INFORMATION_TYPING_REQUIRED`.
Changed request payloads conflict; committed duplicates return retained receipts
after current access checks without revalidation against later schema state.

The planned operation family is:

| Operation | Required request fields beyond scope and request identity |
| --- | --- |
| `information.types.list` | Explicit personal/project scope, bounded page; draft inclusion requires management |
| `information.types.get` | Exact version UUID and either registry scope or attached revision UUID |
| `information.types.version.create` | Existing type UUID or new slug/label/description, schema, presentation; creating a version of an existing type requires expected control revision |
| `information.types.activate` / `information.types.deprecate` | Exact version UUID and expected control revision |
| `information.types.validate` | Exact visible version UUID and complete metadata object |
| `information.content.get` | Revision UUID; returns bound contract, metadata, reviser and processing policy/provenance |
| `information.content.revise` | Expected revision UUID and complete typing alternative; accepts no body, report details, dependencies, citations or caller-supplied producer |

Use explicit DTOs for requests, results, persisted gate candidates and events.
Registry management receipts use the existing durable control/gate conventions;
they do not create information processing jobs. Reads and validation do not
require request receipts. Existing upload, revise, replace, report and refresh
contracts gain the typed extension together. The initial slice keeps unimplemented
public paths disabled rather than silently weakening their semantics.

Refusals distinguish metadata invalidity, validation budget exhaustion, stale
revision, stale type control revision, typing-required, stage-selection-required
and unsupported typed view. Unknown and inaccessible IDs share the existing
absent/inaccessible refusal; errors never echo unreadable heads or private values.
Map these categories to the existing outcome envelope in the operation contracts.

## Metadata-only reuse and processing authority

Retain `automatic` or `explicit_only` processing policy per revision. Ordinary
admission stays automatic. `information.content.revise` always creates an
explicit-only revision with zero document model allowance and spending, unchanged
source bytes/media/provenance, and its own immutable metadata binding. It never
fetches a source or schedules document-processing inference merely because metadata
was saved. The revision transition still obeys access, dependency and hook gates.

Existing configured transition hooks may themselves call models. Their authority
and accounting remain governed by the hook contract and must be shown separately
from document processing. The no-automatic-processing guarantee excludes neither
required hooks nor their accounting. Do not advertise that a save can never incur
any model usage when an operator has explicitly installed such a hook. Missing
document allowance must not force a positive allowance just to save metadata.

Reusable outputs require source identity, exact originating generation, current
stage configuration, exact stage input identity and complete committed output
manifests. A ready flag alone is insufficient. Begin with the five content stages;
generated tags/attribution/groups need their own input checks and can remain
unprepared until explicit enrichment. The initial compatibility planner uses the
existing content-stage queue's conservative dependency order: extract, derive,
embed, summarise, summary embed. It refuses downstream reuse when any earlier
required stage cannot be reused, including skipped or gated outputs.

Projection snapshots are physically owned by the new revision. Remap corpus
chapter/section/paragraph/chunk identities while retaining logical content and
extraction offsets. Copy only committed compatible summaries and vectors. Both
embedding slots require exact source-revision and space compatibility, including
committed staging state when it is part of the configured target. Unactivated,
incomplete or stale vectors cannot make a copied stage ready. Reuse must cover the
chunk and document embedding stores; global maintenance must also respect
explicit-only authority before issuing inference against those new rows.

Prepare and gate the exact reuse manifest. At final commit, recheck current
authority, resource head, origin generation, input/output digests and embedding
targets in one transaction. A changed candidate conflicts; do not silently copy a
different plan after approval. Repository-owned copies, policy, lineage, typing,
receipt and stage states commit atomically. Gates and model/network calls stay
outside transactions. Final implementation must audit lock order against worker
claim/completion, deletion and embedding maintenance; pure planner tests do not
prove transaction safety.

Use `pending` with no automatic authority to represent unprepared work, preserving
the existing closed stage-state vocabulary. Status also exposes processing policy
and whether an explicit processing request authorises each pending stage. Workers
must require either automatic policy or an applicable durable stage authorisation.
Tag sweeps, availability restoration, retries and embedding maintenance cannot
implicitly grant such authority. Expired authorised work may resume through its
existing leases. Configuration changes require explicit repair and cannot expand
the selected stages.

Extend retry/rebuild requests with bounded typed stage selection as needed. On
explicit-only revisions, absent selection refuses with stage-selection-required;
legacy automatic revisions keep their current request semantics. A request grants
only selected stages and their prerequisite closure for a fenced generation. The
owning receipt retains that selection and the existing caller/request fingerprint.
Allowance changes permit spending but do not authorise processing. Explicit repair
requires both applicable stage authority and owner-set allowance; new spending is
attributed to the repair work. Reused inference creates no new usage record and
does not transfer previous allowance, spending, paid checkpoints or conversation
identities. Cancellation and generation changes fence grants as well as leases.

Reports remain draft after a metadata-only edit. Finalisation still requires five
ready, compatible content stages and the existing explicit owner action. The new
revision retains exact report inputs/citations/details and original body producer;
authenticated reviser and starting revision are separate lineage. A body-edited
report uses normal report admission and records the actual new actor/producing run,
with the previous producer only as lineage. Human edits never fabricate a run.

## Access, deletion and compatibility

Revision-specific sharing, collection links, finalisation and publication are not
copied implicitly. Existing namespace/resource authority and all retained report
input restrictions remain effective. Origin content/run details require their own
current read checks. A copied snapshot uses new revision authority; provenance
cannot dereference private or deleted origin content.

Revision deletion atomically redacts metadata, bytes and its owned projection
copies under existing tombstone semantics. Deleting an origin does not erase
independently retained copies, and a provenance identifier alone is not a new
evidence input dependency. Existing actual input dependencies remain restrictive.
Keep type/profile/hash identity on tombstones only without instance values.

Advertise `information.contentTypes.v1` only after full enforcement is ready.
Supporting clients explicitly select the typed revision view. The legacy view
excludes typed revisions before discovery counts/ranking/paging and refuses direct
typed reads with unsupported-view; ordinary untyped operations and historical
receipt recovery stay compatible. All SDKs must implement and test the negotiated
view before deployment. Disabling authored writes prevents new typed mutations.
Rollback to an unaware server requires a write stop or compatibility release that
preserves typing and explicit-only processing; additive tables remain retained.

## Consequences and delivery gates

The first milestone includes registry persistence, common validation machinery,
all-path admission enforcement, physically owned projection copies, selective
processing authority, SDK codecs and a minimum Library authoring/read/revise flow.
No broad schema-driven form engine is required. Backlinks, editor grants,
arbitrary-field queries and portable bundles remain subsequent designs.

These choices prevent routine metadata maintenance from automatically charging
document inference, preserve cited history and keep ownership explicit. They also
require changes outside the information queue: corpus projection storage, dual
embedding maintenance and shared validator compatibility are part of the critical
path. No implementation estimate should treat reuse as a byte-copy shortcut.

Acceptance requires mock service/contract coverage first, focused PostgreSQL
coverage only for actual migrations, copy/remap constraints, locks/rollback,
receipts and deletion, and supporting SDK/client fixtures. Run format/check and
affected SDK gates before publication. Full typed-write activation requires the
all-path and human workflow release checklist in the implementation plan.
