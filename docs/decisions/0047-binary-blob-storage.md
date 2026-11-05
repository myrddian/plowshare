# 0047: Shared binary storage with independent ownership claims

Status: accepted and implemented in the current worktree, 22 October 2026.
Not deployed. Relay BLOB delivery was added subsequently in
[Decision 0048](0048-relay-blob-delivery.md). Entry-owned image observations were
subsequently added under [Decision 0050](0050-trajectory-png-observations.md); Relay
and Trajectory remain independent claim owners.

## Context

Relay and Conversation/Trajectory have independent retention policies. Sharing
image bytes must not make either subsystem the other's storage or lifecycle
owner. The existing disk image store already supplies scoped content IDs,
sidecars and durable writes; invocation admission supplies producer authority and
immutable receipts. Replacing those identities or coupling retention would add
unnecessary migration and recovery work.

The user authorized the [foundation plan](../binary-blob-storage-foundation-plan.md)
as a platform change. Its purpose is shared binary storage independently of any
adapter. An SDK-only attachment workaround cannot make server-owned claims,
receipt transactions and filesystem deletion atomic/recoverable.

## Decision

Introduce `BinaryBlobStorage` and a specialist JDBC registry/claim repository.
Adopt existing held files in place, keyed by stable storage scope/full SHA-256;
privately bind existing image IDs as aliases. Media metadata and authorization
stay in the image/admission services. No bytes go into SQL and no public API or
SDK contract changes.

Use explicit idempotent owner/slot claims. Adopted image records get non-expiring
pins preserving current lifetime. Invocation admissions acquire distinct claims
in their receipt transaction; an additive migration installs cascade-safe release
on deletion of the admission. Keep invocation-before-blob lock ordering.

Collection applies only to registered zero-claim objects after a configurable
grace and defaults to disabled. A row-locked deletion marker prevents new claims;
shared disk fencing protects active reads/writes. Unlink/fsync precedes a retained
generation tombstone, allowing restart and stale-sweeper recovery. Unregistered
legacy files and sidecars are not scanned or collected. Pins cannot be released
without a future image-removal policy.

## Consequences and compatibility

- Preserve image IDs, on-disk locations, sidecar shape, ordinary upload formats,
  first-write metadata, workspace revocation and invocation producer checks.
- SQL registration is now part of a successful production held-image write;
  a failure is explicit and may leave protected finalized bytes for retry.
- Adopted reads verify full hashes and fail explicitly on managed corruption.
- Logical owner deletion is independent of physical collection. This foundation
  does not reclaim pinned ordinary images or implement deletion across all homes.
- Scope-local sharing works without migration; cross-home deduplication, generic
  byte ingress/placement, object-store backends and legacy deletion are deferred.
- Relay and trajectory add their own claims later, without cross-calling their
  retention repositories. Storage claims do not replace tool provenance or
  establish model observation/capability/context fit.
- Backups must retain metadata and bytes coherently. Cleanup must be stopped
  before running older claim-unaware writers or downgrading the server.

The [manual](../binary-blob-storage.md) records configuration and recovery behavior.
Focused filesystem/fake-repository tests cover adoption, uploads, corruption and
active-read deletion exclusion. Focused PostgreSQL tests cover the new migration,
claim conflicts/rollback, cascade release, generation transitions and collection
locking.

## Verification

- `./gradlew format formatCheck check` passed on the final implementation; the
  default check includes 25 new blob/configuration/image-adapter cases and existing
  image/upload/admission/workspace compatibility tests. Database tests are excluded.
- Focused `-PfullDb` runs of `JdbcBlobRepositoryTest` and
  `JdbcArtifactRepositoryTest` passed all 16 tests, including an actual held-image
  read after invocation deletion, cascade/rollback and attach/collection locking.
- Local documentation links/tables/fences and `git diff --check` passed. Existing
  migration files and Relay/Trajectory sources were unchanged from the start of
  this slice.

This is local source verification, without a deployment, generic Relay BLOB round
trip or same-active-model screenshot acceptance claim.
