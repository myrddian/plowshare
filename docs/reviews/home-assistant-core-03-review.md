# CORE-03 independent review

Reviewer: `/root/ha_core_facade_reviewer`  
Implementer: `/root/ha_core_facade_implementer`  
Verdict: **CHANGES_REQUIRED**

Candidate: `docs/reviews/home-assistant-core-03-v1-candidate.json`  
Candidate SHA-256: `cdf4e89c194c7c932136474f32891e3aa59b777b3c08675ed1bc207879d2ff69`

## Findings

### CORE03-R1 — P2: current target authority still uses a stale project name

`Runs.start`'s resolved branch at `Runs.java:574–577` checks work authority after
agent-definition loading against the alias snapshot's `Home.project()`. It does
not establish that this name still denotes `Resolved.projectId()`. The facade's
stable-ID check ran before definition loading. If that project is renamed and
its former name is reused during loading, the check can admit the replacement
project's role while the following `Turn.speak` reloads the original conversation
and its current home. This is precisely a freshness boundary where the resolved
routing snapshot must not become a retained allow.

The later JobStore authority check does not protect every earlier mutation:
`Turn.speak` calls `grant` before submission, and a bound `Budget.changeTo` invokes
its durable ceiling callback. `ConversationStore.find` attaches that callback
through the existing allowance repository. A caller who no longer has work
access to the original project can therefore reach its explicit allowance change
before JobStore eventually refuses the run. Turn's claim cleanup is preserved,
but it does not undo an already committed allowance change.

The same stale-name substitution is possible at Ex output disclosure.
`FrameRouter.requireExternalDisclosure` at `FrameRouter.java:163–167` delegates to
the ordinary request-payload check. For Ex, that payload contains the original
project name and no internal target selector. `ProjectListingFilter` does not
adapt a `ConversationView` or `StartedJob` to its actual target scope. A success
waiting in the writer can consequently be authorized against a replacement
project name and disclose the original conversation metadata after its actual
project's access has been withdrawn.

**Required correction:** check current work authority against the actual stable
target at the pre-mutation boundary and current target scope at protected result
disclosure. Reuse the row already reloaded by Turn, or a narrow owning scope
projection, instead of fetching and discarding another full conversation. Keep
all common service/Personal policy and post-commit fences. A stale request name
must not authorize a different durable project. Add deterministic tests that
rename/reuse a name while definition loading is held and while a success is queued;
assert no unauthorized allowance write/job submission or protected disclosure,
and preserve claim/physical-capacity cleanup without replay.

### CORE03-R2 — P2: the reused public failure code still promises no effects

`plowshare-protocol/.../frames/Code.java:170–174` describes `ARCHIVE_UNAVAILABLE` as
meaning that nothing was read or written. The new Ex timeout and exact exception
mapper deliberately use this existing code after an alias may have committed or
a durable job may have been admitted. The accepted scoped SQL mapper has the same
need. The new outcome details are truthful, but the shared enum's documented
contract contradicts them and can lead a consumer to treat this code as a safe
mutation-retry signal.

**Required correction:** narrowly clarify the code's documentation to distinguish
availability from effect certainty and direct callers to the owning operation's
receipt/recovery contract. Preserve the wire enum, status and existing ordinary
exception mapping. No new failure code is needed.

### CORE03-R3 — P3: new Java type uses bypass the mandatory import rule

The new five-argument constructor in `Asking.java:72` spells `RelayReadBudget`
fully qualified, and the new Ex failure branch in `FrameRouter.java:270` spells
`CompletableFuture` fully qualified. Neither has a documented name clash. These
are added uses, not merely unrelated legacy code outside the change.

**Required correction:** import these types and use their simple names consistently
in the affected files. No unrelated repository-wide import cleanup is requested.

## Verified strengths and resolved preflight findings

- Checked the actual 73 authored paths, relevant owning source and tests, ADR
  0077, subsystem guides and review evidence. All 74 bound file hashes (including
  the frozen ledger snapshot), the compressed diff digest and accepted CORE-03A
  dependency manifest matched during review.
- PRE1 is resolved in current outcomes: timeout and exact Ex unavailable mapping
  preserve uncertain effects and do not inherit the archive mapper's no-write
  assurance. R2 concerns the remaining shared documentation contradiction.
- PRE2 is resolved for the documented assembled-request boundary. Both queue
  request types retain the same Ex token before queueing. Its absolute deadline
  covers queue wait, post-assembly session verification and nested Ex SQL work.
  Expired queued requests do not invoke the facade. Token disposal occurs outside
  queue bookkeeping locks; early delivery acknowledgement bridges to the actual
  concurrent dispatcher. The unchanged pre-assembly packet gate is explicitly
  excluded rather than represented as universally bounded.
- PRE3's common-role reuse is narrow and owner-issued. The opaque admission has a
  private constructor, exact project/account/session/operation/thread matching,
  single consumption, live deadline predicate and explicit handler-scope close.
  Wrong, retained or expired evidence falls back to current work checks.
- Alarm callbacks only select an outcome and dispatch one bounded notification
  task. A blocked notification retains its completion capacity. Original physical
  routing, dispatch barriers, IDs and bytes remain owned until execution or queued
  disposal settles; late success is suppressed rather than replayed.
- JobStore's Ex-only deadline guard precedes operation admission and first start
  linkage. No new rejection branch is inserted after durable job metadata.
  Predeadline admitted work may physically start later under ordinary async
  lifecycle; caller timeout is not remote abort.
- Strict external selectors and typed DTOs preserve owner-local aliases and the
  ordinary run/creation owners. The real handler → facade → Runs → Turn fixture
  checks normal exclusion and cancellation settlement. Configured zero defaults
  remain distinct from explicit wire zero. New prompt/context APIs remain gated.
- Canonical TypeScript and generated native Ex result schemas now require the
  resolved non-null conversation identity without tightening ordinary run replies.
  The TypeScript checked boundary enforces Open-Ex OK and Run-Ex ACCEPTED. Native
  helper/status enforcement and Ex-specific shared/package conformance remain
  explicitly assigned to CORE-04.

## Verification evidence inspected

The saved final Java XML totals **213 invocations across 18 classes**, with zero
failures, errors or skips. The final focused TypeScript log records **243 tests
across four files**. Inspected successful generation/build/drift/audit evidence,
formatting and separate formatCheck logs, and `sdkCheck` (**2 minutes 12 seconds**)
with Node, Python, .NET and Go controlled transport conformance. The WorkAccess
server-only seam was followed by the full selected Java rerun and formatting.

The coordinator's current repository-wide `./gradlew check` also passed on this
frozen v1 source (**3 minutes 49 seconds**, 141 tasks, no `-PfullDb`). That result
does not waive the source-backed authority findings above. No new Gradle,
database, live provider, model or device checks were run by this reviewer; no
implementation or ledger files were changed. This report is an exact-candidate
review, not merge, deployment, live validation or CORE-04 approval.
