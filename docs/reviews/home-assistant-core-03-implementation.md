# CORE-03 implementation evidence

Implementer: `/root/ha_core_facade_implementer`.
Status: final source frozen; generation, focused Java/TypeScript and sdkCheck
passed. Repository-wide verification and independent approval remain. **Not
approved or finished**. Root owns the DAG,
ledger, final checks and candidate manifest. CORE-03A v2 was independently accepted
before its SQL capability was connected. No deployment or live model/device action
has been performed.

## Platform contracts and owners

The source adds `conversation.open.ex` and `agent.run.ex` with exact public SDK DTO
names `ConversationOpenRequestEx` and `AgentRunRequestEx`. Ordinary request/result
shapes remain compatible. A separate `ExternalConversationFrames` area registers
the handlers, both under ordinary mutation-barrier scheduling. Strict boundary
conversion rejects unknown routing/owner fields, wrong scalar types, malformed
source/external identifiers, explicit optional nulls except session, conflicting
limits and nonempty images. Missing optional fields remain distinct from explicit
nulls. Prompt/context APIs remain gated; no system-prompt rewrite is included.

`ExternalConversations` is the typed facade. Its service derives the stable
project/account key through the existing authenticated identity and authorization
owners. Its constructor consumes the narrow WorkAccess interface, implemented by
CallerAccess, for session ownership and project work checks; routing and agent
selection are not part of that capability. Namespace work authority precedes alias lookup. The repository's committed
canonical conversation row is reused. A post-transaction actual-target identity
and work-authority fence protects project rename/name reuse and disclosure; that
is a different freshness boundary, not another conversation hydration. Source or
external IDs never appear in refusal text or metric labels.

Open delegates atomic get-or-open to the accepted alias repository, deriving its
immutable creation choices from ordinary budget and cap owners. Configured zero
model-call defaults remain valid; explicit wire zero remains invalid. An existing
mapping does not reset limits or revive a lifecycle. Only the creating request
invokes ordinary log.open after commit. The public ConversationView has no added
lifecycle field. Repository transaction/READ_COMMITTED ownership is unchanged.

Run is resolve-existing-only: a miss returns NOT_FOUND without creating another
conversation or starting work. It translates to the normal internal conversation
selector without carrying the external project as a conflicting selector.
`ConversationRuns.Resolved` carries the hydrated row and stable identity into the
existing Runs owner. Turn still reloads and claims the current row for allowance,
lifecycle and execution exclusion. Normal authorization, approvals, cancellation,
accounting and completion callbacks remain in their owners; no alternate run loop,
self-RPC, utterance deduplication or mutation replay is added.

## Query reuse and authority freshness

The concrete primary membership pipeline is `ApplicationProjectMembers` over
`CachedProjectMembers`. Its read and work paths both resolve stored role,
application manifest and grant lineage, with service-owner/token ceilings when
applicable. CONTRIBUTOR includes readable role presence. The two new adjacent
weaker read checks were removed from actual-target and resolved Runs admission;
ordinary non-Ex behavior is unchanged.

The public router's common CONTRIBUTOR check also duplicated the facade's initial
namespace work pipeline. `ProjectAuthorization.requireExternalConversation` now
runs its unchanged complete common policy once and mints an opaque
`ExternalAdmission` for that immediately following synchronous Ex handler. Its
constructor is private to ProjectAuthorization. Asking and the internal Caller
carry it; no wire field is introduced. Consumption requires exact project,
authenticated account, transport session, Ex operation and original thread. It is
single-use, must still be inside the original live ReadDeadline, and is explicitly
closed when the router handler scope ends. Deadline equality is an additional
lifetime fence, not request identity or continuing permission. Direct typed calls,
wrong bindings and reused/expired evidence still perform the live namespace work
check before any alias disclosure. No global cache or ambient authorization lease
is introduced.

Before result disclosure, Open therefore has two effective-role pipelines:
namespace/common admission and actual-target work. Run has those two plus fresh
Runs work after agent-definition loading. Negotiated routing retains an additional
post-handler current-authority fence, and output retains its separate current
scope check. Legacy synchronous routing preserves its existing post-handler
policy and gains the explicitly bounded Ex output-disclosure phase. The account
identity is resolved once per facade call; the actual Home re-resolution after
alias commit remains the stable-project fence.

On a cold ordinary project path, one effective-role pipeline can execute one
stored-role statement and one lineage statement; a service principal can add a
service-owner lookup. Valid authority snapshots can avoid those SQL calls but do
not justify repeating the manifest/policy resolution. Manifest/registration work
and private-project paths vary, so these are source-backed identified reads, not
fixed total statement counts for every operation. The tests exercise the actual
CallerAccess-to-ProjectMembers seam as well as the opaque common-admission reuse.

## Bounded assembled-request admission and caller completion

The Ex deadline starts when the full request frame has been assembled, before it
enters negotiated `ConcurrentRequests` or segmented legacy `EventRequests`.
`QueuedExternalConversationRequest` is retained directly in each queue's request
record; no ID side table or refreshed deadline is used. Queueing, post-assembly
session verification, common authorization, alias access and synchronous run
admission consume the original remaining budget. An expired queued frame never
invokes the facade when the earlier barrier eventually releases.

The accepted `SqlWorkBudget` starts before the post-assembly session check and
common project preauthorization. The FrameRouter public Ex path and direct typed
service also opt in, nesting the same earliest deadline without an outer Spring
transaction. The Ex handler stays synchronous on its existing physical caller
thread. SQL infrastructure remains the accepted CORE-03A implementation; this
node does not change the pool, cancellation or cleanup lifecycle. A separately
queued output disclosure runs on its existing writer with its own explicit finite
current-authority SQL phase, rather than assuming ThreadLocal state propagates.

`BoundedExternalConversationCompletions` selects one normal result or a payload-free
ARCHIVE_UNAVAILABLE timeout using the existing wire code. The default deadline is
the configured alias timeout (2 seconds); completion capacity is separately
bounded by `plowshare.conversation-aliases.completion-capacity`, default 256. Each
retained request owns at most one timer and one notification task. A blocked route
or notifier retains its slot until both physically settle. The alarm only selects
and dispatches; it never executes serialization, callbacks, SQL or network IO.
Late results are suppressed, and new Ex unavailable exceptions have an exact
fault mapping so they cannot inherit the archive mapper's false assurance that
nothing was written. Timeout text explicitly preserves uncertainty and forbids
replay.

Early output does not complete the physical route or unblock an ordered callback.
Negotiated delivery acknowledgement is bridged to the original dispatcher when
that request starts; its ID, bytes, barrier and capacity stay owned until actual
completion or queued disposal. Early Ex notifications keep the full advertised
request window charged and do not borrow the normal reply-handoff exemption.
Queued close/discard cancels its completion token outside bookkeeping locks.
Ordinary operation scheduling and handlers remain unchanged.

`ExternalConversationAdmission` installs an Ex-only same-thread deadline.
JobStore's guard linearizes new job admission before its first causal reservation,
operation admission or durable start linkage. There is no timeout check inserted
after metadata creation that could create a new stranded-job branch. A job admitted
before expiry may physically start later under ordinary asynchronous acceptance;
caller timeout does not cancel it, manufacture a terminal result or authorize
replay. The earlier inferred requirement to prevent every physical worker start
after expiry was explicitly clarified by the coordinator and is not claimed.

These are assembled-request and active SQL bounds. The existing pre-assembly
per-packet SocketAuthorization.current gate remains unchanged; fragmented packets
and credits cannot yet be identified as Ex operations. This change does not claim
to bound every packet/session/connection SQL path. Hook stages retain their own
physical budgets, and filesystem/hook loading is not universally aborted by SQL
expiry. The timer bounds server outcome selection/notification ownership; delivery
to a stalled peer remains subject to existing transport write/connection bounds.
Physical work remains owned until it settles; no hook is silently skipped and no
arbitrary callback is claimed to have been terminated.

## SDK result parity

The canonical graph, strict validation, dispatch and portable native constraints
include both Ex operations. Run-Ex now has an Ex-only `ConversationStartedJob`
result requiring its resolved non-null `cnv_` conversation; ordinary older
started-job results retain optional metadata. Standalone decodeReply validates
that Ex identity too. The native constraint clones the Ex root before tightening
it, so ordinary run/resume graphs cannot be mutated accidentally. Open-Ex requires
OK and Run-Ex requires ACCEPTED at the checked TypeScript result boundary.
CORE-04 owns native public helper/status enforcement and operation-specific
installed-consumer/transport conformance. All generated outputs remain owned by
the coordinator's generators, never hand patches. No new Flyway migration was
added by this node.

## Verification and remaining approval

Earlier semantic-base checks passed 115 focused Java invocations and a corrected
full sdkCheck (1 minute 44 seconds, 22 tasks), including controlled typed native
WebSocket conformance. Those results precede the final routing/deadline changes
and do not certify this current candidate. An earlier broad check failed only the
legacy CLI audit's two source hashes; root regenerated that audit through its
owning script and its check passed. The stale one-shot test count was corrected
from 278 to 280 without weakening exhaustive set/fixture comparisons. Failed
intermediate runs and partial native passes are not represented as full successes.

The final frozen checkpoint adds focused owner, routing, admission, evidence and
channel cases. Channel cases cover active timeout/late-reply suppression on four
transport modes; queued expiry, entry-session SQL scope and close/disposal on
negotiated raw, negotiated segmented and legacy segmented queues. Unit cases cover
blocked notification independence and capacity retention, opaque binding/thread/
use/expiry/scope-exit rules, and refusal before first JobStore admission effect.
The first real Turn submission now crosses the actual Ex frame handler, real
facade and Runs owners; normal exclusion and cancellation settlement remain in
the fixture. These use mocked persistence and controlled transports, not a live
house, provider or database. SQL-driver/transaction evidence belongs to CORE-03A
and alias races/constraints belong to CORE-02.

Root completed owning generation, TypeScript build, generated drift/audit checks,
global formatting and separate formatCheck on this final checkpoint. Production
Java compilation passed. The first test-compilation attempt found three incorrect
protocol Outcome test accessors; those were corrected to `said()`, and the
matching channel JSON assertion was corrected before the semantic run.

The current focused Java run passed in 32 seconds: 213 invocations in 18 classes,
zero failures, errors or skips. The copied JUnit XML at
`/private/tmp/core03-final-verification-evidence/server` was independently summed
by the implementer and matches those counts. Current focused TypeScript checks
passed 243 tests in four files. Logs are
`/private/tmp/core03-final-server-focused-check.log` and
`/private/tmp/core03-final-typescript-focused-check.log`. These runs include the
new queue/admission/deadline fixtures and ordinary routing/boot regressions;
they do not establish live database, provider or house behavior.

The definitive current ownership list is `/private/tmp/core03-owned-paths.txt`
(73 existing repository-relative paths). Root still owns the frozen candidate
manifest. The full current sdkCheck passed in 2 minutes 12 seconds; its log is
`/private/tmp/core03-final-sdk-check.log`. CORE-04's Ex-specific public/transport
coverage remains separate. After that pass, the constructor's concrete CallerAccess
dependency was narrowed to WorkAccess with no behavior or SDK contract change.
Root reran all 213 focused Java invocations in the same 18 classes after this
server-only seam; all passed again in 32 seconds with zero failures, errors or
skips. Global format and separate formatCheck then passed. No SDK contract changed
after its passing gate. Repository-wide check must pass on the final source,
followed by independent reviewer approval against a frozen manifest before the
coordinator can mark CORE-03 finished. CORE-04 remains tracked separately for
public native helpers, expected-success status checks, Ex-specific shared fixtures,
real controlled WebSocket conformance and installed-consumer package checks.
