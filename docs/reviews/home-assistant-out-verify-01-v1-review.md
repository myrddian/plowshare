# OUT-VERIFY-01 independent review — v1

Verdict: **CHANGES_REQUIRED**. The outbound validation implementation and scoped
proof are present, but the deliverable's required full repository check is not
green. This node must remain unaccepted.

Reviewer: `/root/ha_outbound_validation_reviewer`.
Implementer: `/root/ha_outbound_validation_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-out-verify-01-v1-candidate.json`.
Candidate SHA-256:
`50da588dd50c08b074eb34d9919231f62557cdb010b50e44ba38ab414c984392`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact evidence

The reviewer authored no packaging, profiles, validation fixtures, plan, ledger,
core, SDK or Assist code. Review used owning source, the frozen candidate and
actual coordinator-run evidence. No Gradle, installed JVM consumer, database,
live endpoint, model or device operation was run by this reviewer. The reviewer
independently exercised only the corrected directory-topology assertion in a
private temporary fixture, without launching the installed program or editing
source.

Independently verified the candidate digest, all five owned file hashes, the
frozen ledger snapshot and scoped diff. Both dependency manifests and their
current bound files match: OUT-PACK-01 v1
`ef835127bb7caaf71b574136adad0e0528a70eee967cd25485f26a44a4b33f7f`
and OUT-PROFILE-01 v2
`6698b07861e88453ef17242f62bd56313e322a2911ff1d91ef686d9d961e3c10`.
Actual implementation and reviewer identities are distinct; dependencies are
accepted and the validation node is not yet verified-and-reviewed.

The source snapshot binds all 23 current feature/validation files and four
unchanged Assist gate sources; every digest matches. All eleven original gated
node values and all four gate records in the frozen ledger equal the main
baseline. The full repository results are not replaced by those structural
checks.

## Scoped validation findings

The three Java fixtures use actual production host composition, the normal
segmented SDK subprotocol and concurrent dispatch over local sockets. They assert
the incoming concurrent negotiation request, one SDK connection, separate logical
catalogue bindings and exact tool names. Normal shutdown sends empty-tool
withdrawals for both topics with distinct logical publication identities.
Scripted lost acknowledgements retain original catalogue/result identities across
an explicitly constructed operator restart, without replacement publication,
automatic reconnect, effect or repeated search-handler registry collection. The
result correlation and parent identity are checked. Native startup state
snapshots may legitimately repeat on explicit restart; the report states that
limit. The fake broker supplies outcomes and does not prove real authentication,
server grants, model behavior or physical device success. The native fixture
refuses an unexpected service call rather than simulating device actuation.

The standard-library consumer runs the copied installed launcher/jars/examples
outside the repository with explicit Java 21, configured credentials and JVM
classpath overrides removed, local connection traps and finite subprocess waits.
Eight cases cover read-only/control offline checks, legacy invocation, retained
journals, refused prompt input, conflicting principal and malformed CLI. File
hashes and directory topology are compared around each command and the original
artifact. Diagnostics are bounded and a supplied marker must not escape.

The reviewer independently matched the final script, private consumer report/log
and all 44 installed file hashes/sizes. The payload has 26 runtime jars, four
valid artifact-local documentation links, the actual new main and no detected
server/test/Assist namespace; the HA jar contains no test/fixture classes. This
is POSIX/Darwin acceptance only. The previously approved packaging artifact
manifest remains historical after the independently approved profile README v2
amendment; the current joint manifest correctly binds the refreshed README and
all other installed files. No frozen historical evidence was overwritten.

Resolved validation finding OUTVERIFY-R2 (P2): file-only snapshots could miss an
empty journal directory. The original validator added bounded directory-topology
checks. The reviewer independently confirmed that an unchanged tree passes and
a new empty directory is rejected even when file hashes are identical. The full
eight-case installed consumer was rerun successfully with this guard. The earlier
fixture assumption about helper status 2 was corrected to the actual public OS
status 1 plus a Usage diagnostic, without changing feature behavior.

## Blocking finding OUTVERIFY-R1 — required repository check fails (P1)

The approved OUT-VERIFY-01 acceptance contract explicitly requires current
`check` to pass on the integrated candidate. That condition is **not met**.
The final full check with `--max-workers=2` failed in 5m10s / 140 tasks. Independent
XML aggregation confirms 8,627 server cases with two failures, no errors/skips,
and 166 HA cases with zero failures/errors/skips. Both failures are parameter
variants of:

`io.aeyer.plowshare.server.pool.ScopedSqlWorkBudgetTest.lateQueryAndFetchClampNetworkTimeEvenWhenPhysicalCleanupWorkerIsOccupied(boolean)`

The failing assertion is the mocked `socketRead.await(1s)` barrier at line 550.
It is not evidence of a live PostgreSQL result or a proven backend deadline
breach. The final log SHA-256 is
`567b7f2f01926aa0821b77286708e3e7823d33ba7c02ae3a47a7226c55ff97b8`.

The first full check also failed one case among 8,627; an isolated-method attempt
failed both variants at a different barrier. The whole owning class later passed
all 30 cases together with the three strengthened SDK fixtures in 8 seconds.
Those actual outcomes and all referenced log hashes were independently checked.
The focused pass cannot substitute for the required full check. Unchanged core
source establishes scope, not the cause: the evidence proves neither a harmless
flake nor causal independence from this work. No test was excluded and no core,
SDK or Assist source was changed to force a pass.

**Required disposition:** keep OUT-VERIFY-01 `changes_required`; route the failing
SQL test to its owning subsystem for diagnosis within separately authorized
scope, and obtain the required green integrated check with appropriately reviewed
current evidence. Do not waive the check, suppress its test or weaken the plan's
acceptance condition. This review approves neither a core workaround nor node
closure. The frozen validation material remains useful partial evidence.

## Preserved limits

Formatting, SDK-contract/integration-boundary checks, the three public-transport
cases, 30 isolated owning SQL-class cases and eight installed-consumer checks
have their recorded scoped passes. Overall OUT-VERIFY-01 acceptance remains
blocked by OUTVERIFY-R1. The implementation report and package evidence accurately
separate those levels and retain failed attempts.

All original prompt/context, cache-backend, deployment and live-acceptance gates
remain unsatisfied. The gated HA Python environment was not recreated; no current
HA Python runtime, voice, real grant, target-version or device validation is
claimed. No prompt rewriting, native-role substitution, SDK/core extension,
release, deployment or original gated-node completion is approved by this report.
