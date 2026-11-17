# CORE-03 v2 independent re-review

Reviewer: `/root/ha_core_facade_reviewer`  
Implementer: `/root/ha_core_facade_implementer`  
Verdict: **APPROVE**

Candidate: `docs/reviews/home-assistant-core-03-v2-candidate.json`  
Candidate SHA-256: `510f9a8b6a050c39684df937bd3edbc754ac2ff78cf88be243cc94894dd34724`

No blocking findings remain for this candidate. This approval covers CORE-03's
platform facade, bounded assembled-request routing and canonical contracts. It
does not approve CORE-04, merge, deployment, prompt-dependent features or live
house/provider behavior.

## Findings resolved

**CORE03-R1 — actual target authority:** the resolved run branch now uses the
package-private `Turn.speakResolved` door. It requires its additional work guard,
composes normal `requireSpeakable` first, and invokes the guard on Turn's existing
freshly loaded and claimed row before allowance access, grant, automatic increase,
transcript creation or job submission. No extra full-row fetch was added. Ordinary
run/speak doors retain their existing behavior, and refusal releases the ordinary
execution claim. The accepted V274 alias-root trigger fixes the target's durable
project/root identity; the current Home name is resolved by the existing row read,
rather than borrowed from pre-definition routing metadata.

The held-definition regression tests both directions of the original/replacement
project's opposite work roles. Withdrawn original access produces no durable
ceiling callback, job or transcript submission and leaves no claim; legitimate
rename raises once and retains the same internal conversation. The resolved door
cannot replace the normal origin/lifecycle guard or omit its current-work guard.

Successful Ex output checks derive their target from the typed ConversationView
or StartedJob. `ProjectAuthorization.requireExternalConversationTarget` resolves
one current CONVERSATION scope projection, refuses missing/global scope and runs
unchanged common policy against that actual target. FrameRouter reuses the
returned Home for Personal/client-session checks. There is no discarded
ConversationStore hydration or allowance attachment. Post-handler and queued
checks are independent current-authority phases. Controlled Open-Ex and Run-Ex
fixtures cover rename/reused-name denial and authorized rename, including a held
writer; denied or unavailable disclosure withholds metadata and never reexecutes
the handler. Failed/bodyless outcomes do not probe a result resource.

**CORE03-R2 and coordinator availability follow-up:** ARCHIVE_UNAVAILABLE's shared
documentation now describes availability without promising that earlier effects
did not occur. Its wire code/status and the ordinary exception mapper are
unchanged. The Ex-only typed availability policy converts archive-unavailable
failures to constant safe uncertainty/no-replay detail while retaining their
cause. Exact Ex and SqlWorkUnavailableException instances and their owning
messages pass through unchanged. Caller, integrity, grammar and programming
failures retain their original type and are not retried.

The direct facade boundary covers alias open/find and later scoped work. The
Ex-aware router maps typed availability before its local handler/post-handler
failure conversion, including queryValidity failures. FailureReporter observes
the original failure once at that boundary. The whole queued Ex disclosure owner
uses the same policy, including session and target projection failures, then
follows existing writer suppression/close; it creates neither a second reply nor
replacement work. The new fixtures verify successful mutation followed by scope
or session outage, private diagnostic suppression, original reporter observation,
queued withholding and exact/unrelated exception preservation. This is typed
classification, not blanket RuntimeException normalization or string replacement.

**CORE03-R3 — imports:** Asking uses imported RelayReadBudget and Objects.
FrameRouter consistently uses the imported CompletableFuture, CompletionStage and
CompletionException family. No new qualified uses without a clash remain in the
corrected paths; unrelated legacy type spelling was not used to justify the
previous violations.

## Original invariants retained

The v1 preflight corrections remain intact: the opaque common-admission evidence
is privately minted, exactly bound, single-use and explicitly scope-closed; direct
or mismatched callers perform live namespace work checks. Both dispatch queues
retain the original assembled-request deadline token, including queue wait and
post-assembly session/SQL work. An expired queued request cannot invoke the
facade. Queue disposal and early-delivery bridging preserve original ID, byte,
barrier and physical-work ownership.

The shared alarm only selects and launches a bounded notification; it does not
run serialization, callbacks, SQL or socket work. Late replies remain suppressed.
JobStore's Ex guard remains before first operation/start linkage, with no new
post-metadata rejection branch. Predeadline admitted durable work can physically
start later; a caller timeout is not remote abort or mutation-replay permission.
The documented exclusions for pre-assembly packet authentication, hook/file
termination and stalled-peer delivery remain explicit.

Alias/account/project/source isolation, strict external DTOs, configured-zero
versus wire-zero behavior, atomic repository ownership and the ordinary
handler → facade → Runs → Turn lifecycle composition remain as reviewed.
No adapter access to internals, additional schema change, prompt API, prompt
rewriting or alternate agent runtime was added by this correction.

## Exact source and verification

Verified the candidate digest above, all **78 bound file hashes** (77 authored
paths plus frozen ledger snapshot), compressed tracked-diff digest and the
accepted CORE-03A dependency manifest. Rechecked the actual changed source,
correction tests and updated auth/transport guides. The v1 author report and review
remain unchanged historical evidence.

The saved current JUnit XML totals **264 invocations in 23 classes**, with zero
failures, errors or skips; the focused log records a successful **38-second** run.
Inspected global format, separate formatCheck, canonical drift/manifest and
whitespace evidence. All **28 SDK, generator and contract-support paths** carried
from v1 are byte-identical, so its passing 243 focused TypeScript tests and
2-minute-12-second sdkCheck remain applicable to that unchanged source. These
results are not presented as newly rerun SDK checks.

The current v2 repository-wide `./gradlew check` completed successfully in
**3 minutes 49 seconds**, 141 tasks (49 executed, 92 up-to-date), without
`-PfullDb`. Inspected `/private/tmp/core03-v2-repository-check.log`; the coordinator
also confirmed process exit 0 and saved supplemental evidence. The immutable v2
author report correctly retains its earlier full-check-pending statement; that
historical freeze point is supplemented here and in the coordinator's ledger,
not silently rewritten.

No Gradle, database, generator, model, provider or live device checks were run by
this reviewer. No implementation or ledger files were changed. CORE-04 still owns
the Java public helper, native expected-success-code enforcement and Ex-specific
shared WebSocket/installed-consumer/package conformance. This exact candidate is
approved for the coordinator's CORE-03 acceptance only.
