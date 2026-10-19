# Slice 04 knowledge mutation specification

This specification precedes the slice 04 enforcement changes. It covers existing
knowledge operations only. Audience/root-policy conversion remains slice 05;
execution and independent SYSTEM processing remain later slices.

## Predicate inventory and classification

| Predicate / owner | Classification and treatment |
| --- | --- |
| Catalogue `requireOwner`, repository `lockOwner`, existing-name admission | Ordinary knowledge mutation: replace creator equality with live authority in the resource's explicit home. Project CONTRIBUTOR/MANAGER can write; READER cannot. Selection must identify that home; shared discovery is never a write destination. |
| Content admission head, metadata projection lock, portable import destination head | Same WRITE rule, retaining expected-head, typing, source-bytes, content-type, projection fingerprint and transaction fences. |
| Explicit processing-stage authorization | Same WRITE rule for requesting supported work. This does not change the worker's execution identity or creator-dependent processing lifecycle. |
| Catalogue share/unshare | SET_AUDIENCE at the home, requiring project MANAGER. Ordinary WRITE is insufficient, even for the original creator. Personal knowledge retains exact-subject control. Input sharing and current revision policy remain unchanged. |
| Collection link/unlink | WRITE at source home and destination project. Collection membership is not a home transfer or audience grant. Preserve the current visibility rules for linked Personal material. |
| Pending source READ | Replace the ordinary pending-project creator shortcut with home READ, retaining the outer mandatory input/reference policy. Per-revision projected visibility and sharing remain unchanged. |
| Catalogue inventory/status and diagnostics | Inventory follows the selected writable home, rather than its creator. Existing read policy still governs content disclosure; mandatory/private/dependency restrictions also govern mutation and hidden status. The existing wire `owner` field continues to identify the legacy custodian, not new authorship. |
| Navigation relationship source `owned` | Ordinary WRITE plus existing readable-head/reference obligations. Policy administration (`canManage`, policy targets) stays on its existing human-management path pending slice 05. Private views/receipts remain exact account. |
| Navigation `ownerOnly`, retained-input/reference closure, unavailable/quarantined content | Mandatory domain restrictions, retained without conversion. A contributor cannot revise, delete, retag, import over or process a resource to bypass these restrictions. |
| Document-policy assignment | Existing human-admin adoption of unclassified/quarantined legacy data. Sets an explicit destination mapping, never creator provenance. No general move API exists. |
| Processing repository owner/authority, model-stage receipts, job/log input ledgers | Retained operation and private-record identities. No SYSTEM execution cutover in slice 04. |
| Conversations, sessions, credentials, acquisition/command/gate/evidence receipts | Exact authenticated subject/idempotency/lifecycle restrictions. Never rewrite these predicates as knowledge permissions. |
| Memory project/home references | Add explicit durable scope mapping while preserving the existing archive/curation admission, state machine and formation/invalidation/source history. Promotion creates a new Global record and retires its source, rather than moving a row. Memory storage has no authenticated-principal parameter; do not invent a new archive control API. |

## Home and provenance

Resources retain namespace, project and legacy owner for compatibility, alongside
an explicit UUID home scope. A consistent existing project maps to its persisted
scope; an account namespace maps to that account's knowledge scope. Legacy rows
with an explicit assigned project/personal policy can map to that home without
asserting who created them. Unclassified legacy rows keep an unknown home and
remain unavailable for ordinary writes. Restricted client/orphan mappings do not
acquire ordinary project authority. Existing account-private knowledge keeps its
exact-subject boundary; this slice does not add account-wide sharing.

Existing memory NULL-project rows are demonstrably the archive's Global tier,
not an inference about arbitrary NULLs: map those rows to the fixed Global
knowledge scope. Keep the legacy Home representation until its scheduled consumer
cutover; no new Global read or execution route is enabled here.

Resource/revision `created_by` is an account UUID, nullable when unknown. Recover
it only from an unambiguous admission request for that exact revision, or an
unambiguous retained intake event. Conflicting evidence stays unknown. A
resource's creator is the evidenced creator of its first revision; assignment,
current home, legacy owner, bibliographic document author and imported foreign
provenance are not evidence of local creation. New admissions record the actual
authenticated writer independently on each revision and on a newly created
resource. Immutable provenance survives later non-creator revisions and retirement.

## Transaction and rollout boundaries

Content assignment takes the shared navigation-policy lock before contract rows, matching import and preventing a contract/policy lock inversion. Ordinary content writes share that policy lock; policy/reference management retains its existing exclusive lock.

Repositories recheck live home authority after locking the resource and before
effects. The scope UUID must agree with the compatibility keys; a stale or forged
mapping cannot redirect a mutation. Request/command receipts stay account-bound;
expected revisions and rollback remain authoritative. Java admission retains the
Application-aware effective-role ceiling, while SQL defends scope and current
stored/token grants. No membership, audience or control change is a side effect of
content WRITE.

Audience updates carry an exact revision/actor transaction-local admission marker, rechecked by a database trigger. Pre-cutover owner-based share/unshare writers fail closed; the marker expires at commit/rollback and cannot authorize another revision.

The migration is additive. Old provenance/home keys remain; inconsistent mappings
and provenance rewrites are refused. Downgrading the application is not a supported
authorization rollback: retain this schema/enforcement and reconcile deployment
versions rather than removing constraints or restoring owner-based writers.

Missing capabilities remain gaps: general home/control transfer, arbitrary
Personal sharing and a Global write selector have no new operation in this slice.

Project renames keep the same home/resource IDs; source lookup uses the persisted
scope rather than the historical namespace text. Authored content-type registry
namespace renaming remains an existing gap: no new registry rehome operation or
immutable-definition rewrite is introduced here. Legacy namespace uniqueness can also refuse reuse of an old locator/name after a rename; it fails closed rather than merging resources or removing old identity constraints.
