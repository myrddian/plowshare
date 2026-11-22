# PLAT-SQL-01 independent review — v1

Verdict: **APPROVE**. The diagnosed regression-fixture defects are corrected,
the required checks pass, and no production capability or deadline was changed.

Reviewer: `/root/sql_cleanup_reviewer`.
Implementer: `/root/sql_cleanup_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-plat-sql-01-v1-candidate.json`.
Candidate SHA-256:
`cb9ba0d4f6e8956aee0040af507e0d0dadf26fb17e232fc6183e45c51e28ea0c`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact scope

The reviewer authored no implementation, diagnostic instrumentation, delivery
plan or ledger changes and ran no Gradle, database, live endpoint, model or
device operation. Review independently traced the actual fixture and owning
`ScopedSqlWorkBudget`, `ScopedSqlDataSource` and `ReadDeadline` implementations,
then inspected the coordinator's diagnostic and verification evidence.

Independently matched all three bound source/document hashes, the frozen ledger
snapshot and scoped diff to the candidate. The scoped diff contains only the
test, implementation report and coordinator-owned plan/ledger amendment. The
test SHA-256 is
`1804bc5e331d9f5eb05a660120981f9656b4cb09b1cf491bbb332973b38fac00`.
The final implementation report SHA-256 is
`05ca2f1e73f63baff2f235f1502bd6004f3e1d75510c20e8eac2d0dd2f520d22`.

The separate platform node has independent implementation/review identities,
test-only ownership and no production/API/SDK/schema authorization. Its
acceptance requires the unexcluded repository check. The amended dependency
graph has 29 nodes and 56 edges, is acyclic and preserves distinct role
identities. All eleven original gated node objects and all four gate records
equal the main baseline; every capability gate remains `not_satisfied`.
All 21 integration files bound by the accepted packaging/profile candidates
also retain their approved hashes.

## Diagnosis and correction assessment

The first retained diagnostic provides concrete causal evidence: the second
scope started at approximately 1047 ms with a 400 ms allowance, but its scheduled
gate opened at 1473 ms. The pending future failed at `Scope.check`, through the
statement wrapper, before raw execution. Production correctly refused work that
had already expired. This establishes the late scheduled wakeup, without
claiming why the host delayed it. The earlier first-cleanup-latch failure's
precise cause remains unproven and is correctly identified as such in the report.

The second diagnostic independently shows the fake socket's requested 119/120 ms
wait taking 250/269 ms in wall time, despite the applied timeout being 118/119 ms.
It also shows narrowed-scope exit preceding outer resource closure. Source
tracing confirms that restoring the longer setup deadline before closure could
add the configured 100 ms cleanup wait. These observations justify separating
finite worker observation from the SQL deadline; they do not demonstrate a
missing network clamp.

The final fixture observes raw first-lease abort and retains the sole physical
cleanup worker behind an explicit release barrier. It verifies that raw close
has not occurred. Preparation has a separate finite setup allowance; execute
and materialization retain a 120 ms tested SQL deadline, one-second configured
query ceiling and 100 ms configured cleanup budget. A nested scope narrows the
same owning `Scope` object. The fetch variant opens its result before narrowing,
so it exercises refresh on an already-created `Query`.

Assertions require the earlier timeout to exceed 150 ms, the raw execute/fetch
timeout to be positive and no greater than 120 ms, and the refreshed value to be
strictly smaller. The socket fixture returns a deliberately late value; the
future must instead fail with `ReadFailedException` reason
`DEADLINE_EXCEEDED`. Resource closure occurs inside the narrowed scope. Neither
second raw abort nor raw close may execute while the first cleanup operation
holds the worker. The one-second future wait is a finite harness observation
allowance and does not change the asserted network limit or SQL deadline.

This replaces natural same-scope elapsed aging with inherited deadline
narrowing. That distinction is explicit in the test name, comments and report.
Both use the existing `Query.check` selection of the earlier query/scope end
and `networkDeadline` subtraction of monotonic time. Existing expiry-to-cleanup
and materialization ownership tests remain present. The replacement therefore
retains the network-refresh and occupied-cleanup regression without requiring
a scheduler wake inside a narrow wall-clock window. It does not prove strict
physical wall-time termination under host scheduling delays.

## Independently verified evidence

All six diagnostic source/XML/log hashes in the implementation report match
the retained artifacts. The four final command-log hashes and three final XML
hashes also match. Actual command outcomes are:

- Final formatting: passed.
- Fresh corrected parameterized method plus `formatCheck`: two cases passed,
  five seconds and 65 tasks.
- Entire owning mock class: 30 cases passed, nine seconds and 22 tasks.
- Unexcluded `check` and outbound `installDist`: passed in five minutes
  13 seconds, 144 tasks. Full log SHA-256:
  `ec7f3206cc00104e046eb3b5ea852eb0d89d37f4d89b344c440c284ee10fee3b`.

The full log explicitly reports both corrected parameter variants passing.
Independent XML aggregation finds 8,627 server cases across 861 suites, with
zero failures, errors or skips. The retained full owning-class XML has 30 cases
and zero failures/errors/skips, SHA-256
`fd418a4545ecb4ae43a08354f9d486ec4b37d96c77bfdf1aa968784fb45673b9`.
HA XML retains 166 passing cases across 22 suites with zero failures/errors/skips;
its unchanged test task was up-to-date in the full run, as the report states.
The full log includes the integration/database boundaries and formatting checks.
No test exclusion or real PostgreSQL run was used to resolve the blocker.

## Disposition and limits

No open findings. PLAT-SQL-01 may be marked verified and reviewed against this
exact candidate. OUT-VERIFY-01 still requires its own refreshed evidence and
independent review; this approval does not self-close that node. It authorizes
no release, merge, deployment, live device/model acceptance or prompt-dependent
feature. All original prompt/context and operational gates remain unsatisfied.
