# OUT-VERIFY-01 outbound validation — v2

Evidence implementer: `/root/ha_outbound_validation_v2_implementer`.
Status: **READY FOR INDEPENDENT REVIEW; NOT ACCEPTED.** Fresh integrated
verification passed and the platform test dependency is independently accepted.
Final v2 validation approval remains pending. This
revision preserves the frozen v1 report and its CHANGES_REQUIRED verdict. No release, deployment or live acceptance is claimed.

## Candidate and independence

This evidence revision consumes independently reviewed outbound packaging and
profiles plus the separately scoped PLAT-SQL-01 test correction. It does not
author or amend their implementation. The coordinator runs Gradle and the fresh
installed consumer; this implementer owns only new v2 evidence files. The
independent reviewer must inspect the frozen v2 candidate before acceptance.

The accepted feature candidates remain OUT-PACK-01 v1
`ef835127bb7caaf71b574136adad0e0528a70eee967cd25485f26a44a4b33f7f`
and OUT-PROFILE-01 v2
`6698b07861e88453ef17242f62bd56313e322a2911ff1d91ef686d9d961e3c10`.
The PLAT-SQL-01 candidate is
`cb9ba0d4f6e8956aee0040af507e0d0dadf26fb17e232fc6183e45c51e28ea0c`;
its [formal independent review](home-assistant-plat-sql-01-v1-review.md) approved
the candidate, and the coordinator accepted PLAT-SQL-01. Review SHA-256:
`81be571f929f49c43e543768aad3c27292ef3565dbc731253a8d1123fce7b5b7`. The new
[source snapshot](home-assistant-outbound-validation-v2-source.json) binds current
feature/validation files, the separately scoped SQL test and unchanged gate sources.
The [package evidence](home-assistant-outbound-validation-v2-package-evidence.json)
binds the fresh installed artifact and actual private check logs.

## Existing validation contract

The unchanged three Java fixtures compose the production outbound host with the
actual SDK's segmented WebSocket subprotocol and concurrent dispatch against
controlled local broker and HA fixtures. They cover one SDK connection, distinct
read/control catalogue topics and exact tool names, normal empty withdrawals,
retained catalogue publication after lost acknowledgement and retained result
publication after lost acknowledgement. Explicit operator restart reconciles the
same journaled identity without replacement publication or repeated search-handler
collection. Native startup snapshots may repeat on restart. The scripted broker
supplies outcomes; these fixtures do not prove server authentication or grants,
model behavior, physical devices or zero native traffic on restart.

The unchanged standard-library consumer copies the installed POSIX distribution
outside the repository and invokes its launcher, jars and examples with explicit
Java 21-or-newer. It removes configured credentials and JVM/classpath overrides,
uses local connection traps and finite subprocess budgets, bounds diagnostics and
rejects marker disclosure. Eight cases cover read-only/control and legacy offline
checks, retained unreadable journals, refused prompt input, conflicting principal
and malformed CLI flags. File hashes and directory topology are compared around
every command and against the original distribution. Payload checks require the
public runtime/SDK main, reject server/test/Assist namespaces and verify local
Markdown links. These fixtures do not establish Windows launcher acceptance.

The v1 independent reviewer found a file-only snapshot could miss an empty
journal directory. The original validator corrected that check; independent probes
verified that a new empty directory is rejected despite identical file hashes.
The original profile author also corrected three installed documentation links,
with separate profile v2 approval. Neither correction weakens the consumer.

## Prior blocker and separate platform scope

The frozen [v1 report](home-assistant-outbound-validation.md) and
[independent review](home-assistant-out-verify-01-v1-review.md) retain their actual
failed attempts. The final v1 full repository check failed two variants of
`ScopedSqlWorkBudgetTest.lateQueryAndFetchClampNetworkTimeEvenWhenPhysicalCleanupWorkerIsOccupied`;
all 166 HA cases passed. A complete owning-class pass did not substitute for
required full verification, and the node remained unaccepted.

Continuation introduced PLAT-SQL-01 as a separate owning-platform, test-only
investigation. Its contract requires phase/Future diagnostics before correction,
observed cleanup-worker occupancy, the short tested SQL deadline, both execute
and fetch coverage, rejection of late results and exact physical lease ownership.
The instrumented isolated method ran both parameter variants; one failed. In
that failure the second SQL scope was requested at 1047 ms with a 400 ms budget
and entered at 1048 ms, but the scheduler opened its gate at 1473 ms, approximately
25–26 ms after the deadline. The retained
Future cause was `ReadFailedException` from `Scope.check` before raw JDBC execute.
This directly identifies the late gate in the reproduced failure; it establishes
neither a production defect nor a cause for every previous failure. No harmless
flake or cold-initialization claim follows from it.

No production runtime, public API, SDK, schema or prompt capability change is
authorized by that scope. A fixture correction must not be described as a runtime
fix or as proof of live PostgreSQL cancellation. The correction explicitly aborts
an already-borrowed first lease and observes its blocked physical cleanup. A
separate finite setup allowance precedes a nested 120 ms unit on the same Scope;
both raw execute and an already-open result set must refresh their network
limit to a positive value no greater than 120 ms. Their late results remain
`DEADLINE_EXCEEDED`, and second-lease physical abort/close cannot run while the
first cleanup occupies the sole worker. Resource closure stays inside the
narrowed scope. This replaces natural deadline aging with inherited deadline
narrowing through the existing `Query.check` path; it is not a claim to reproduce
a scheduler waking inside a deadline window.

The first correction's 250 ms worker-observation wait also failed. Temporary
terminal diagnostics measured mock socket reads requested for roughly 120 ms
returning after 250/269 ms; they establish oversleep/descheduling in that fixture,
without establishing its cause or a missing network clamp. The final fixture
keeps the tested 120 ms SQL budget and uses a separate finite one-second worker
observation allowance. The platform report retains this failed attempt and the
source-based reason for closing resources before restoring the setup allowance.
Its exact current results are recorded below and its independent review approved
the frozen platform candidate.

## Fresh verification

The coordinator's corrected-source isolated method run passed both variants and
`formatCheck` in five seconds / 65 tasks. The complete owning SQL class passed
all 30 cases in nine seconds / 22 tasks. Their private logs are
`/private/tmp/ha-sql-fixture-method-final.log` and
`/private/tmp/ha-sql-fixture-class-final.log`; matching XML confirms zero failures,
errors or skips. These passes do not substitute for full repository verification.

The refreshed installed distribution completed before a fresh copied consumer
passed all eight checks against 44 files, including 26 runtime jars and four
valid artifact-local documentation links. The v2 package evidence binds its exact
script, private report/log, file hashes and sizes. File hashes and directory
topology remained unchanged. This is a new consumer run against the refreshed
artifact, not reuse of the v1 consumer result.

The coordinator's complete `check` plus HA `installDist` with `--max-workers=2`
passed in five minutes thirteen seconds: 144 tasks, 29 executed, one from cache
and 114 up to date. No tests were excluded and no real-database option was enabled.
XML aggregation confirms 8,627 server cases with zero failures, errors or skips.
The HA task was `UP-TO-DATE` on unchanged source; its retained results contain
166 cases with zero failures, errors or skips, including the three public-SDK
fixtures. This is not a claim that the final command freshly reran all HA cases.
Its private log is `/private/tmp/ha-outbound-integrated-v2-check.log`, SHA-256
`ec7f3206cc00104e046eb3b5ea852eb0d89d37f4d89b344c440c284ee10fee3b`.

The explicit `sdkContractCheck` entry point also passed in 529 ms, verifying
296 operations, 20 outcome codes and 1,012 canonical DTO cases. It is recorded
separately because it is not included in the root `check`. Its private log is
`/private/tmp/ha-outbound-v2-sdk-contract.log`, SHA-256
`2e5a41a5927a76433ee19b7dc82a6b7f8e996f011e1872b9852eefad94950d0d`.
PLAT-SQL-01 is independently approved and accepted. OUT-VERIFY-01 still requires
independent review of this frozen v2 validation candidate before it can close.

## Preserved gates and limits

All eleven original awaiting-gate nodes and all four capability/operational gate
records must remain equal to the main baseline. Assist setup, configuration and
installation packaging remain disabled. No prompt is rewritten, relocated or
replaced by task text; no external-system-context API is introduced. The former
HA Python environment was not recreated, and no current HA Python runtime, voice
pipeline, live grant issuance, target version, model fleet or device validation
is claimed. Mock JDBC and local scripted sockets do not supply that evidence.

This revision adds no authorization for PostgreSQL tests, endpoint or device
operations, model calls, publication, merge, release or deployment. It does not
close the original VERIFY, PERF, RELEASE, DEPLOY or LIVE nodes.
