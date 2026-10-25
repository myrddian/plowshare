# Slice 04 Resource effects observers and service ownership

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
Depends on slices 1 through 3. This widens execution policies only after the
bounded transport/completion proof passes; it is not permission to parallelize
handlers by operation name alone.

## Purpose and operation audit

The [dispatcher read audit](../dispatcher-read-audit.md) records the current
independent-read inventory, conversation inspection keys, retained effectful
barriers and regression coverage. Both event schedulers share that read inventory.

Remove unnecessary barriers while retaining real conflicts, paid-operation
attribution, lease ownership and current authority. For each changed operation,
record typed inputs, complete call path, effects, key identity, transaction
boundaries, disclosure checks and dependencies. Register policy beside its owning
handler through the slice 1 contract; do not grow a second unrelated policy map.

| Existing source | Actual effect and proposed work |
| --- | --- |
| [InformationReadiness](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationReadiness.java) | Polls readiness/status and sleeps for up to 30 seconds; separate short checks from bounded parked observation. |
| [InformationCatalogue](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) | `status` has a hidden-owner `requireWrite` fallback; retain its write-admission locks and current visibility semantics before classifying overlap. |
| [SearchService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/search/SearchService.java) | Page one calls providers and persists a result set; later pages reuse that set. Define same-result-key conflicts and retain expiry/version behavior. |
| [DocumentSearchHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/DocumentSearchHandler.java) and [RetrievalService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/RetrievalService.java) | Search can perform accounted embedding/provider work; keep usage attribution, authorization and query/cache freshness fences. |
| [ProjectRelayPorts](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/ProjectRelayPorts.java) and [JdbcRelayPortRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayPortRepository.java) | Consume can wait and claim a durable leased batch; order delivery/ack by the actual topic/subscriber identity, not as a pure read. |
| [UsageSubscriptions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/UsageSubscriptions.java) | Subscribe creates state and queries initial reports under its monitor; keep acknowledgement-before-push and unsubscribe/teardown ordering while removing blocking work from the monitor. |
| [BoundActingContexts](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/BoundActingContexts.java) and [ActingRequests](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ActingRequests.java) | Open performs repository/authority work under a monitor; preserve bounded contexts, audit, revocation, connection identity and explicit per-request subject selection. |
| [ConversationResumeHandler](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationResumeHandler.java) and [ScheduleFileFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ScheduleFileFrames.java) | Representative conversation/schedule mutations need resource policies matching their durable owners and full affected scope. |

The table is an audit queue, not a claim that these paths already satisfy the new
policy. Keep each unaudited path conservative and migrate cohesive operation
families separately within this slice. Do not change durable lifecycle contracts
merely to make transport scheduling easier.

## Typed resource and conflict rules

Use typed keys for actual resources: conversation identity, information home/name
or revision as required by its mutation, schedule source/definition identity,
Relay topic/subscriber and physical connection subscription/context state. Derive
them from validated input and existing authorized typed snapshots. A path that
can retarget/change identity must prove the selected identity is unchanged before
commit/disclosure; a string supplied by a client is not sufficient authority.

The process-wide registry from slice 2 supplies bounded single-owner state and
idle-key reclamation. PostgreSQL transactions/locks/version fences remain the
cross-process authority. Reads that need post-mutation state either declare that
conflict or are submitted by a client after the mutation's terminal reply.
Independent work must not inherit a project-wide queue for convenience.

Declare shared/exclusive or equivalent conflict semantics explicitly. If an
operation affects several resources, atomically reserve the complete typed key
set or use a documented canonical protocol; never hold half a set while waiting
for the rest. Coarse administrative changes may legitimately retain broader
barriers, but their reason and scope must be documented.

Same-key sequencing cannot substitute for expected revisions, SQL constraints,
durable idempotency or live membership. Distinct connections and processes must
still behave correctly. No generic coalescer may combine paid searches, leased
consumption or mutations; coalesced DTO loads retain their existing cache owner.

## Observer execution and recovery

Split observation into admit, short current-state check, bounded parking and
checked resumption. Waiting consumes an observer reservation, not a finite-work
or JDBC permit. Release any temporary resource lane before parking when the
mutation that changes the condition needs that lane. Preserve the current public
timeout/result shapes and use one absolute deadline across repeated checks.

Use existing notification/signal mechanisms as wake hints with periodic durable
reconciliation. This slice does not require converting every polling path to a
new notification system. Recheck current authority and exact resource identity
before each claim/disclosure. A missed notification must affect latency rather
than lose durable work or permanently strand an observer.

Relay consumption remains a durable claim operation: waiting and claiming have
different capacity needs, but a returned batch retains its lease/fence/ack owner.
Stopping a wait must not fabricate acknowledgement or replay a publication.
Information readiness cancellation stops the observer rather than undoing its
independently admitted acquisition. Account revocation/disconnect detaches all
connection observers and prevents later completion reaching another generation.

Prevent dependency deadlocks: a parent cannot hold the last worker permit while
awaiting a child that needs that permit; an observer cannot retain an exclusive
key needed to make its condition true. Bound observer population, check frequency
and wake/completion queues, and reserve lifecycle/mutation progress under load.

## Removing service monitor bottlenecks

For Usage subscriptions and acting contexts, move affected blocking queries,
proof/grant lookups and audit calls outside global service monitors. Use a short
reservation turn, immutable external work, then checked finalization. Prefer
concurrent structures for shared indexes; retain a narrow atomic protocol for
capacity and lifecycle rather than replace a map and leave the race unresolved.

Subscribe finalization must verify the connection/reservation is still current.
Queue the initial reply before marking the subscription ready, preserving the
existing reply-before-push activation contract. Packet credits acknowledge bytes
and do not add a new application acknowledgement. Unsubscribe or disconnect
cancels a pending reservation and prevents completion from restoring it.
Query/report failure releases its slot and produces one terminal outcome.

Acting-context finalization rechecks connection generation, proof/grant validity
and expiry after IO. Preserve audit semantics and bound both pending and opened
contexts. Closing/disconnecting during open cannot publish a usable context
later. Future completion, sinks and callbacks run outside coordination locks.
Scope this migration to affected paths; avoid unrelated service refactors.

## Verification, PostgreSQL exceptions and handoff

Default tests use mocked repositories, fake clocks/signals and controlled stages.
Prove same-resource mutation ordering across connections, unrelated-resource
progress, two conflicting key sets without deadlock, source-identity changes,
revocation during parking, missed wakes, deadline expiry and disconnect cleanup.
Saturate observer slots and show an unblocking mutation and lifecycle close still
progress. Verify no extra permission/metadata fetches merely to construct keys.

For subscriptions, test pending open versus unsubscribe/close, acknowledgement
ordering and report failure. For acting contexts, test close/revocation/expiry
during proof lookup plus capacity reservation rollback and isolated subjects.
Verify cache versions and scope on result reuse without canceling another
caller's shared DTO load when one observer detaches.

Only changed repository transaction/lease/lock behavior warrants focused real
PostgreSQL tests behind `@Tag("full-db")`, `-PfullDb` and narrow selectors. State
the database-specific assertion first: actual concurrent claim fences, rollback
or lock/version ordering. Dispatcher/observer mocks alone need no database, and
unchanged SQL is not a reason to run the whole database suite.

Acceptance requires an operation policy inventory plus deterministic evidence:
same-key conflicts obey declared order, unrelated work overtakes a held request,
parked observers consume no DB workers, all reservations return to baseline, and
no mutation replay/duplicate paid work is introduced. Handoff to slices 5 through
8 includes policy fixtures, metrics for queue/execution/observation separately,
and explicitly retained barriers. Out of scope: replacing the bounded DTO cache,
external actor dependencies, domain-wide SQL redesign and deployment claims.
