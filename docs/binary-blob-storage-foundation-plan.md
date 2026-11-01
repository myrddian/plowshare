# BinaryBlobStorage foundation plan

Status: implemented in the current worktree following explicit user authorization,
22 October 2026; not deployed. This records the storage boundary in the
[multimodal plan](multimodal-first-slice-plan.md). Profile loading, routing,
PNG inspection and invocation admission remain implemented as described in the
main plan and [Decision 0046](decisions/0046-invocation-image-admission.md).
[Decision 0047](decisions/0047-binary-blob-storage.md) and the
[storage manual](binary-blob-storage.md) describe the implemented foundation;
cleanup defaults to disabled. [Relay BLOB delivery](relay-blobs.md) is now implemented
as a separate consumer under Decision 0047; trajectory media remains unimplemented. The design/acceptance sections below retain the
foundation's scope; earlier admission tests alone do not verify this addition.

Source reviewed: `2cf4db4c2d72e9f99e43e343a38ff603278e8329` plus the current
uncommitted multimodal/admission changes. Earlier verification of those changes
does not verify this proposal.

## Decision and responsibilities

Introduce an internal `BinaryBlobStorage` capability below media and delivery
services. It owns immutable byte identity, private storage locations, integrity,
explicit claims and physical cleanup. It does not own media semantics, execution
provenance, publication expiry, conversation retention or user authorization.

| Owner | Retained record and responsibility |
| --- | --- |
| BinaryBlobStorage | Full digest, byte length, location, availability/deletion state and claims. Location is private to the disk adapter. |
| Image service | Existing scoped image ID and sidecar, detected image format, display metadata and validated image details; an internal binding refers to the blob. |
| Future audio/video services | Their own typed media identities and validation/metadata, referring to blobs through the same storage contract. |
| Invocation admission | Exact producer/invocation/ordinal receipt and its own claim. It remains part of the retained invocation lifecycle. |
| Relay | Publication identity, subscriptions, leases, acknowledgements, delivery and expiry; its publication owns a separate claim when BLOB support is added. |
| Conversation/Trajectory | Immutable entries, projections and payload retention; an entry owns a separate claim when media content is added. |

Relay and Trajectory have no dependency on each other's repositories or retention
schedule. They depend on storage interfaces. Media metadata is not copied into
the blob registry, and arbitrary BLOBs need not be valid images. Media admission
continues to validate actual bytes; a declared MIME type is not validation.

## Identity and scope

- Use the full server-verified SHA-256 as `BlobId`, with byte length checked on
  registration and resolution. A truncated `img_…` ID is not sufficient integrity
  evidence. A conflicting existing object is an error, never an overwrite.
- Preserve the existing public `(home, img_<first 32 digest hex>)` identity.
  Image admissions, SDK receipts and future content references retain that ID.
  A private image-to-blob binding supplies the full digest; no second public image
  ID or mandatory new blob field is added to existing upload responses.
- Initially key storage registration/deduplication by stable storage scope plus
  full digest. Project scope uses the existing surrogate project identity, so
  rename does not orphan a blob. Preserve the existing non-project layout without
  broadening Personal/Global invocation admission.
- Sharing across Relay, invocation and trajectory claims in the same scope uses
  one registered location and performs no byte copy. Deployment-wide physical
  deduplication across homes is deferred: existing files are scoped, and merging
  those copies would require a separate migration and isolation review.
- A hash, claim ID or deduplication hit grants no read or attachment authority.
  No public hash-existence lookup or raw blob download endpoint is introduced.

The scope restriction is an explicit limit of the smallest slice. It reduces
duplicate storage between owners within a project without moving existing files
or promising deployment-wide single-copy storage.

## Smallest foundation slice

Build the internal storage/claim primitive and connect the existing image store
and invocation admission. Do not add a Relay payload kind, trajectory schema,
provider result envelope or new SDK operation in this slice.

1. Define `BinaryBlobStorage` and immutable blob/reference/claim values; add a
   specialist repository for the registry and claims. Constructor-inject those
   interfaces into the affected image/admission composition.
2. Reuse existing held image files in place. A private disk adapter adopts a
   verified held file and records its location, scope, full digest and length.
   It does not copy/re-upload the file, rewrite its sidecar, move it to a new
   generic directory or change `img_…` IDs.
3. Record the image-to-blob binding and an explicit image-retention claim. Existing
   ordinary uploads retain their current lifetime. Adoption happens on an
   authorized write/retry; old reads keep the existing legacy path until adoption.
   Commit registry adoption, the binding and the image pin together, so an adopted
   image never becomes visible as an apparently unowned collectible object.
   There is no startup directory scan or bulk filesystem migration.
4. Add the admission's distinct claim in the same database transaction as its
   receipt. Preserve `invocation-image/1`, stable request IDs, current producer
   checks, feature flag, status semantics and byte-repair behavior.
5. Provide bounded, recoverable last-claim cleanup for registered, fully managed
   objects, with claim/delete exclusion. It must never sweep unknown files or
   infer that an existing image is unowned because adoption has not occurred.

This is a registration/adoption foundation, not a second upload service. The
existing `ImageStore` write path is its first disk adapter and remains the only
producer in this slice. Reuse/extract its durable byte-write mechanics rather
than adding another independent implementation. Generic binary ingestion and
placement for objects that have no image record belong to the later Relay BLOB
slice. That slice must handle both image-first and BLOB-first arrival without
writing a second compatibility copy; its placement/alias contract is a review
gate, not an unaccounted promise of this foundation.

### Internal contract

The implemented interfaces preserve these capability contracts:

| Action | Required invariant |
| --- | --- |
| Register/adopt finalized bytes through the trusted disk adapter | Bounded full-digest verification; only configured storage roots; return a typed ready/unavailable/conflict outcome. Domain callers do not supply arbitrary filesystem paths or URLs. |
| Acquire an owning-record claim | Stable claim key; same key and same blob is idempotent; same key with different blob is a conflict. Caller participates in the owning record's transaction. |
| Release an owning-record claim | Delete the exact claim idempotently inside the owning record's deletion/ejection transaction. Do not decrement a blind counter. |
| Resolve/read a blob | Caller first authorizes through the owning record; storage requires the matching retained claim and returns a bounded integrity-checked read. The read is fenced against cleanup for its duration. |
| Reconcile/collect | Bounded indexed candidates, durable deletion state and retry; cleanup changes no Relay or trajectory retention decisions. |

Trusted adoption resolves a held file through the existing directory/sidecar
rules. It rejects symlink escapes and workspace-backed notes; it never opens an
arbitrary caller path. New managed reads use the blob binding and verified
location. A missing/corrupt managed object returns unavailable; it must not fall
back to another home or an equal truncated image ID. Legacy fallback applies only
to records that have not been adopted.

Media callers remain responsible for byte limits and validation appropriate to
their operation. Storage also enforces bounded reads/hashes and a configured
maximum; no media parser, network fetch or model routing runs in blob storage.

### Persistence and claim identity

Migration V225 adds the registry, alias and claim records without rewriting V224.
Records are:

- Blob registry: storage scope, full digest, byte length, private location and
  explicit availability/deletion state. Enforce one canonical location per
  `(scope, digest)`; never derive it from user display names.
- Image binding: `(scope, existing image ID)` to the registered blob. Sidecars
  remain the owner of existing display/format metadata. Do not duplicate them
  as an independently editable image catalogue.
- Claims: unique `(owner kind, stable owner ID, attachment slot)` and blob key,
  indexed by blob for last-claim checks. Initial production owners are image
  retention and invocation admission; Relay/Trajectory owners are later additions.

The authoritative count is the set of claim rows; an indexed `NOT EXISTS`
check avoids introducing a materialized reference counter and counter repair.
Two reads by one owner do not create two claims. Two different owners do.
Deleting a project must not cascade away blob registry/deletion records before
physical cleanup can reconcile them. Retain the stable scope/location in those
records independently of live project-name resolution. Protected image pins
retain their existing lifetime until an explicit image/project removal policy
releases them; this foundation does not silently introduce one.

Use a non-expiring image-retention claim for every adopted held image, including
an ordinary upload and an invocation upload that created the same image record.
The current image store has no delete API: losing an invocation claim must not
silently shorten that image record's existing lifetime. Releasing this image
claim requires a separately designed image-record removal policy. Consequently,
this foundation does not reclaim ordinary held images; cleanup eligibility can
be exercised with isolated managed-storage fixtures. This limitation is explicit,
not a claim that invocation expiry now frees all image bytes.

The existing admission table cascades deletion from the invocation. New admission
claims follow that cascade transactionally. A narrowly scoped database
delete trigger on the admission row releases its exact claim, including parent
invocation/project cascade deletion; do not rely on callers remembering to invoke
a service after SQL has removed the owner. Normal acquisition remains in the
specialist repository transaction. Future Relay and entry adoption must each
specify equivalent owner-deletion handling before they can create claims.

Existing receipts require no rewrite of their public fields or historical
migration. They remain protected by legacy file lifetime until verified adoption;
adoption reconciles the original invocation/ordinal claim if that owner is still
retained. Failure to adopt does not turn an old receipt into proof of new storage
availability. No database migration attempts to read/hash filesystem bytes.

### Publication, races and crash recovery

Reuse `ImageWrites`' bounded staging, OS/JVM exclusion, atomic rename and file/
directory forcing. Preserve first-write metadata and legacy sidecar-first repair.
Filesystem work precedes the short final registry/receipt transaction; do not hold
a database transaction through PNG decoding, streaming or file forcing. Lock the
owning invocation before blob rows in that transaction, consistent with existing
admission/settlement ordering. A rollback can leave finalized bytes, but cannot
leave an acknowledged new receipt without its committed claim.

Claim acquisition, release and cleanup serialize on the blob registry row.
Cleanup rechecks zero claims and atomically transitions `READY -> DELETING`.
After that transition, new attachments fail with a reconcilable unavailable
outcome; a concurrent writer cannot reuse/delete/recreate the same location until
the deletion generation has completed. Physical removal occurs after commit,
under the same disk exclusion used by writes and active reads. An already-started
read completes before unlink; later reads fail explicitly. Retain a durable
deletion record until file removal and directory forcing complete, then finalize
the registry transition to a tombstone. Preserve enough binding/deletion state
that a collected managed record cannot fall back to legacy resolution. Reusing
the content later requires verified byte registration in a new storage generation
and a newly authorized claim. Missing files during a resumed delete are idempotent;
unexpected corruption or I/O failure is surfaced and retried, never hidden.

Use a bounded candidate scan through the existing server maintenance scheduling
pattern, not a new durable job/execution runtime. Persist the eligibility time;
any staging/recovery grace is configured rather than guessed in code. A crash
before registry registration leaves an unregistered legacy-layout file protected
from collection. A registered zero-claim candidate may be collected only after
its persisted recovery eligibility time. It cannot race a successful attach.

The foundation's image-retention pin also protects the interval between legacy
disk finalization and SQL adoption. Cleanup cannot remove a pinned image, and
never traverses the filesystem searching for apparent orphans. This is how we
preserve existing uploads without claiming full legacy ownership enumeration.

## Source seams and compatibility

| Current source | Foundation change |
| --- | --- |
| [ImageStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageStore.java), [ImageSnapshots](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageSnapshots.java) | Add the internal blob mapping/adoption seam; route adopted held reads through storage. Preserve `store`, `find`, `hold`, `available` and `dataUri` behavior, formats, limits and first-write metadata. |
| [ImageWrites](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageWrites.java) | Reuse or extract shared disk mechanics behind the storage adapter. All affected image writers and cleanup share the fence; no independent uncoordinated writer. |
| [StoredImage](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/StoredImage.java), [ImageSidecars](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/ImageSidecars.java) | Preserve existing sidecar shape and public image identity. Workspace notes stay revocable references, with no immutable-blob claim until explicitly snapshotted from authorized bytes. |
| [ImageDirectories](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ImageDirectories.java), [DataLayout](../plowshare-server/src/main/java/io/aeyer/plowshare/server/data/DataLayout.java) | Reuse configured existing roots and stable project IDs. No hard-coded root, bulk move or new data-layout directory is required for adoption. |
| [InvocationArtifactAdmissions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/InvocationArtifactAdmissions.java), [JdbcArtifactRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/images/JdbcArtifactRepository.java) | Commit the admission claim with the existing receipt; preserve fresh authority recheck and settlement lock. Existing receipt retries reconcile one claim. |
| [Admission migration](../plowshare-server/src/main/resources/db/migration/V224__invocation_image_admissions.sql) | Leave unchanged. Add the registry/binding/claim schema and cascade-release trigger in a new migration. |
| [ImageController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/ImageController.java), Java/Node image admission helpers | No request/response, endpoint, SDK or feature-flag changes. No public generic blob service. |

Keep absent-storage behavior explicit; do not silently switch to memory or another
root. Blob metadata and files must be backed up/restored coherently. Missing
restored bytes are unavailable, never reconstructed by rerunning a screenshot or
generator. Downgrading to older code that does not maintain claims requires
stopping cleanup and reconciling claims before re-enabling it; do not run mixed
claim-aware and claim-unaware writers with active collection.

## Separate follow-up slices

1. **Relay BLOB delivery:** typed generic binary payload, authenticated SDK byte
   transfer, quota/bounds and protocol compatibility. Relay creates/releases its
   publication claim with its own publish/prune transaction. Keep existing text,
   cursor, lease, acknowledgement and gap semantics. Acknowledgement is not
   publication deletion. Specify generic placement and image/BLOB-first reuse
   before enabling the new payload kind; content types remain delivery metadata.
2. **Typed tool results and provider journals:** ordered content and image
   references, immutable admission receipts, exact producer provenance and
   no-recapture recovery. A generic BLOB alone does not establish those semantics.
3. **Conversation/Trajectory:** atomic entry-plus-claim, separate retention/export/
   ejection policy and authorized reconstruction. Acquire its claim before the
   Relay publication can expire; missing delivery cannot be repaired by attaching
   a hash without a surviving authorized source. Compaction does not release it.
4. **Same active model/output acceptance:** existing profile/dispatcher path,
   verified image context bound and backend mapping, authorized screenshot
   observation and text-only-agent PNG output delivery.

Cross-home deduplication, bulk migration, legacy image deletion, Information
source-byte migration, native audio/video consumers, object-store backends,
public blob endpoints and a universal gallery remain outside this foundation.

## Acceptance and verification for the foundation

The foundation passes without a model, browser, Relay BLOB or trajectory change:

- Existing image/upload/admission fixtures keep the same IDs, sidecars, bytes,
  filenames and responses. PNG inspection stays opt-in for invocation admission;
  ordinary accepted formats and workspace fences remain unchanged.
- Adopting an existing held image verifies its full digest and performs no second
  binary write. Same-scope identical owners share one registered location;
  full-digest conflict, foreign scope and caller-selected paths are refused.
- An image-retention claim and an admission claim can coexist. Identical retries
  do not add claims; deleting the invocation releases only its exact claim, and
  the existing image remains readable through its retained image owner.
- Isolated managed fixtures demonstrate two distinct claim keys, idempotent
  acquire/release, zero-claim cleanup, last-release/attach race, active-read/delete
  exclusion and restart after a deletion marker or unlink. Synthetic owner keys
  in tests do not advertise Relay/Trajectory support.
- Database rollback leaves neither a new receipt without a claim nor a partial
  owner-delete/release. Parent cascades release the admission claim. Filesystem
  failures, post-finalization SQL failure and incomplete legacy sidecars remain
  explicit and recoverable; unknown/unregistered files are never collected.

Use temporary directories, bounded streams and mocked repositories by default.
Focused `full-db` tests are justified for the new migration, uniqueness, trigger,
transaction rollback and claim/delete row locking. State that reason and select
only those tests with `-PfullDb --tests`. After implementation run Java `format`,
`formatCheck`, relevant existing image/admission tests and `check`. Implementation
verification is recorded in Decision 0046 after those gates.
