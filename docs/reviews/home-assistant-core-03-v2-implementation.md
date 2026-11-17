# CORE-03 v2 correction evidence

Implementer: `/root/ha_core_facade_implementer`.
Status: correction source frozen; current focused checks passed. The full
repository check and exact independent re-review remain pending. **Not approved
or finished**. This report supersedes the author handoff
for the rejected v1 candidate. The earlier
`home-assistant-core-03-implementation.md` and its review/candidate remain unchanged
historical evidence. Root owns the ledger, formal candidate and final verification.

## CORE03-R1: actual target authority at mutation and disclosure

The resolved run branch no longer authorizes the Home name retained before
agent-definition loading. It calls a package-private `Turn.speakResolved` door,
which requires a non-null additional current-work guard and always composes it
with normal `requireSpeakable`. Turn's existing row reload and execution claim
remain its lifecycle/allowance/concurrency owner. The current-work guard runs on
that same row before allowance access, explicit grant, automatic increase,
transcript construction or JobStore submission. A refusal uses ordinary claim
cleanup. Ordinary non-Ex speak/run paths are unchanged.

The stable internal conversation ID still selects the aliased root. The accepted
V274 `conversation_alias_root_fixed` trigger forbids changing its durable project
identity or root shape while aliased. A project rename changes the current Home
name returned by Turn's reload; reuse of the old name cannot select Work authority
over the original row. The new guard uses that current Home and refuses global
work. It does not fetch/discard another conversation or attach another allowance
callback. Definition routing still uses the previously resolved stable project ID;
that routing metadata is not retained authority to change its allowance.

Successful Ex result fences derive the internal target from typed
ConversationView.id or StartedJob.conversation. The narrow
`ProjectAuthorization.requireExternalConversationTarget` resolves its current
project once through the existing ResourceScopeRepository CONVERSATION projection,
rejects missing/global targets, and applies the unchanged complete common Ex
policy to that actual scope. The returned typed Home is reused for current
Personal/client-session checks, with no full ConversationStore.find. Post-handler
and queued-output checks each own this fresh projection and policy phase. They
never reuse the initial admission token or let the original request name authorize
a replacement project. Failed/bodyless outcomes do not probe a result resource.
Queued denial withholds the protected result and follows the existing close path.
Legitimate authorized rename keeps the same internal conversation identity.

The original assembled-request deadline, scoped SQL cancellation/cleanup,
notification capacity, queue-token ownership, uncertain delivery and admission
lifecycle from v1 remain unchanged. No new SQL, schema migration, client RPC,
wire field, SDK contract or alternate runtime was introduced. Each disclosure
projection has a distinct current-authority purpose; Turn's existing row is reused
for pre-mutation authorization instead of another scope/hydration read.

## CORE03-R2: availability does not establish effect certainty

ARCHIVE_UNAVAILABLE's enum documentation now distinguishes unavailable results
from effect certainty and directs mutation recovery to the owning receipt/operation
contract. Its wire code/status and ordinary exception mapper are unchanged.

`ExternalConversationUnavailableException.preserveUncertainty` is the shared Ex
boundary policy for typed ArchiveUnavailableException failures. Exact Ex and
SqlWorkUnavailableException instances/messages pass through unchanged. Other
archive-availability failures retain their original cause and receive a constant
safe uncertainty/no-replay detail; raw dependency details cannot appear in that
client outcome. Caller, data-integrity, SQL-grammar and programming failures are
not normalized or retried.

The facade covers both atomic open and resolve-existing failures. Ex-aware router
fault mapping applies before routeAdmitted consumes a handler/post-handler failure,
including its queryValidity fence, while FailureReporter observes the original
failure once at that boundary. The whole queued Ex disclosure owner uses the same
policy, including session and target projection failures; it propagates to existing
writer suppression/close rather than publishing a second reply or replaying work.
The ordinary operation mapper remains unchanged.

## CORE03-R3: imported Java types

Asking now imports RelayReadBudget and Objects. FrameRouter uses imported
CompletableFuture, CompletionStage and CompletionException consistently for the
affected type family. Newly added correction code uses imported simple type names;
there is no name clash or unrelated repository-wide cleanup.

## Focused correction coverage

New deterministic tests hold definition loading, rename the original project and
reuse its former name with the opposite Work role. Denied original access must
produce no durable allowance callback, no job/transcript submission and no retained
claim; authorized renamed access must raise once and retain the same conversation.
The current row is loaded once. Separate coverage proves the resolved door cannot
replace normal speakability or omit its mandatory current-work guard.

Typed Open-Ex and Run-Ex post-handler fixtures exercise both access directions.
Controlled socket fixtures hold the writer with an earlier reply, queue a success,
then rename/reuse before disclosure. They require withholding/close for denied
original access and the same internal ID for authorized rename. Actual Personal
checks use the narrow current Home; mocked ConversationStore must not be touched.
Failed/bodyless output fixtures perform no resource lookup.

Availability fixtures cover base archive outage from alias open and find, exact
Ex/SQL instance preservation, unrelated failure-type preservation, successful
mutation followed by a post-handler scope or session outage, and queued projection
outage with no protected metadata, no replay and ordinary physical cleanup.
The post-handler failure fixture checks one original FailureReporter observation.
These are mocked persistence/controlled transport tests, not PostgreSQL, provider,
model, deployment or house evidence. No new database test is required: the immutable
alias-root constraint is the accepted CORE-02 dependency and remains unchanged.

## Verification status at report freeze

No author Gradle, generator, formatter or live action was run for this correction.
The coordinator's v2 global format and separate formatCheck passed. Canonical
operation generation/manifest drift and diff whitespace checks passed.

The current focused Java run passed in 38 seconds: **264 invocations across 23
classes**, zero failures, errors or skips. The implementer independently summed
the copied JUnit XML under
`/private/tmp/core03-v2-verification-evidence/server` and confirmed those counts.
That evidence directory also retains the focused and formatting logs. The matrix
covers the new correction fixtures and the prior Ex/ordinary dispatch, admission,
claim, availability and boot regressions. These are controlled fixtures with
mocked persistence, not live database/provider/house validation.

All 23 SDK/generator paths checked by the coordinator remain byte-identical to the
v1 candidate. Its passing sdkCheck (2 minutes 12 seconds) and 243 focused
TypeScript tests therefore remain relevant to the unchanged canonical/native
contract; they are not represented as newly rerun checks. CORE-04 still owns public
native helpers, expected-success status enforcement and operation-specific
shared/installed-consumer/transport conformance. No new database test was needed
or run by this node.

The current full non-database repository check is running at this report's freeze:
`/private/tmp/core03-v2-repository-check.log`. It is **pending**, not a claimed
pass. Root will bind its actual completion result as supplemental verification
and ledger evidence before acceptance. This report will not be rewritten after
hash freeze, so that supplemental result cannot silently change the reviewed
candidate. V1's broad check is historical evidence and does not certify these
corrected authorization paths.

The definitive v2 ownership list is `/private/tmp/core03-v2-owned-paths.txt`
(**77 paths**), with byte/hash handoff manifest
`/private/tmp/core03-v2-owned-path-hashes.json`. It replaces the old author report
with this report and adds Turn, protocol Code and the two correction test classes.
The earlier author report is retained unchanged with SHA-256
`b3d2348c437cd2d1460c1de63541732acac2e73a90f46ab6c9765fa4603ffd59`.
Root owns the formal candidate/ledger and must obtain exact independent re-review
approval plus the current full-check result before marking CORE-03 finished.
