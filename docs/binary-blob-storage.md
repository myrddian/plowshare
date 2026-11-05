# Binary blob storage

The internal `BinaryBlobStorage` foundation shares immutable disk bytes through
explicit owning-record claims. It adopts held images in their existing
locations and supports generic preparation for [Relay binary delivery](relay-blobs.md).
Trajectory content remains separate; there is no public raw-hash blob read API. See [Decision 0047](decisions/0047-binary-blob-storage.md)
and the [foundation plan](binary-blob-storage-foundation-plan.md).

## Identity and storage

`ImageStore` preserves existing `img_<32 hex>` IDs, JSON sidecars, legacy placement names,
first-write metadata, accepted ordinary upload formats and workspace fences.
The shared disk implementation reuses bounded staging, scope write fences,
atomic rename and file/directory forcing. Adoption verifies original bytes in
place and does not write a second binary copy or relocate the file. There is no
filesystem scan or database import at startup.

The registry key is `(scope, full SHA-256)`. A scope is `projects/<surrogate ID>`
or the existing `global` storage layout; it is not a disclosure grant. Alias rows
privately bind existing image IDs to blobs. No image display/format metadata is
copied into the registry. The disk adapter uses only configured `DataLayout`
locations and rejects symlink traversal below that root. Same-scope owners share
the registered file. Physical sharing across homes is not implemented.

All production image writes register their held bytes through this adapter.
Generic preparation uses a private full-hash filename under the project `blobs/`
directory. Whichever registered location exists first remains canonical. An image
upload after binary preparation adds only its sidecar, alias and pin, retaining
the existing image ID without creating a second compatibility byte file. The
older direct `ImageStore` constructors remain disk-only for isolated legacy store
fixtures; production configuration always injects claim-aware storage. A deployment
with no data directory still refuses storage, without a memory/directory fallback.

Previously stored images remain readable through the legacy path until adopted
by a successful write/retry. New adopted reads verify the full digest and exact
length through storage. A missing/corrupt/deleting managed object is explicitly
unavailable, never a reason to try legacy bytes, another scope or a workspace file.
Workspace notes remain revocable references with no immutable-blob claim. Upload
promotion snapshots submitted bytes without following the note's workspace path.

## Ownership and transactions

Claims have a stable `(scope, kind, owner, slot)` key and identify one blob.
An identical retry is idempotent. Changed blob/retention under that key conflicts.
The authoritative claim count is the set of rows, with indexed last-claim checks;
reads do not increment counters.

- Each adopted image gets a `PINNED` `image-retention` claim. Registry, alias and
  image claim commit together after disk finalization. The image service has no
  removal policy, so this pin cannot be released through the generic release API.
- Each invocation admission gets a distinct `RELEASABLE` `invocation-admission`
  claim, keyed by invocation UUID and ordinal, in its existing receipt transaction.
  The repository locks invocation before blob, preserving admission/settlement
  ordering. A missing binding or failed claim rolls back the receipt.
- Relay publications own separate `RELEASABLE` claims, committed with append and
  a publication link. Prune/project cascades release only that claim; acknowledgement
  does not. Binary reads require current authority on the retained publication.
- A delete trigger releases exactly the admission claim when its row is removed,
  including invocation/project cascades. Rollback restores both owner and claim.
  Blob locations/tombstones are not cascaded away with a deleted project.

The caller must authorize through its owning image/admission record before using
this internal storage capability. A hash, alias, scope or claim key does not grant
public access. Existing image upload and invocation producer checks remain in
their owning services; the blob service does not resolve user permissions.

Image pins deliberately preserve current upload lifetime. Deleting an invocation
does not reclaim its image file. Introducing image-record removal or bulk legacy
collection requires a separate policy; this foundation does not imply media erasure.

## Cleanup and recovery

Cleanup is disabled by default. Deployment properties are:

| Property | Default | Bounds |
| --- | --- | --- |
| `plowshare.blobs.max-bytes` | `52428800` | Positive verification/read ceiling, raised to the image allowance when necessary. |
| `plowshare.blobs.cleanup-enabled` | `false` | Explicit opt-in. |
| `plowshare.blobs.cleanup-interval` | `1m` | `1s` through `1d`. |
| `plowshare.blobs.cleanup-batch-size` | `32` | 1 through 100 registry objects per pass. |
| `plowshare.blobs.recovery-grace` | `10m` | Zero through 30 days after registration/claim release. |

A bounded scheduled pass selects registered zero-claim candidates after the grace
period. Claim changes and the `READY -> DELETING` transition serialize on the blob
row. Candidate locking skips active owners and uses a set-based fresh claim recheck.
After the marker commits, new claims fail; disk deletion runs outside SQL under
the same fence as image writes/managed reads. An already-authorized active read
finishes before unlink. Before unlink, cleanup rechecks the exact deletion
generation, protecting against another sweeper completing and bytes being
registered again while a stale sweep waits for the disk fence.

Successful unlink and directory forcing precede the `DELETED` tombstone commit.
An interrupted deletion retries its original marker; an already missing file is
idempotent. I/O/integrity failures leave the marker recoverable, are reported by
maintenance and retried on a later pass. Aliases/tombstones remain so managed
unavailability cannot become legacy fallback. Reintroducing identical content
requires verified registration and a new generation/authorized claim.

Unknown/unregistered files, sidecars and pinned images are never swept. A crash
between finalization and registration leaves protected legacy-layout bytes rather
than a falsely acknowledged claimed object. Restoring missing bytes does not
authorize replaying a screenshot or generator effect. SQL metadata and disk files
must be backed up coherently; missing/corrupt objects stay unavailable.

Do not enable cleanup with older writers that do not maintain claims. Stop
cleanup before downgrading; reconcile claims before enabling it again. Existing
pins mean enabling this foundation alone does not reclaim ordinary image storage.

## Ownership boundaries and remaining work

[Trajectory image observations](trajectory-image-observations.md) now own separate
entry claims, committed with append and released on ejection/deletion. Compaction
preserves those claims. [Typed Node results](typed-png-tool-results.md) reuse
invocation admissions and can supply images to the active model after verified
context admission. Relay byte delivery retains its separate publication claims.

Public typed history/redemption, final output associations and export manifests
remain separate work, along with audio/video metadata and native transport.
The configured model/backend still needs a verified image-aware context counter
and live screenshot acceptance before enabling native observations in deployment.
