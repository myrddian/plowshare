# PLAT-SQL-01: deterministic SQL cleanup-lane regression fixture

Status: implementation and coordinator-run verification ready for independent review; not yet accepted.

## Scope and finding

This is a separately scoped platform test correction. It changes only
`ScopedSqlWorkBudgetTest.java`; production SQL infrastructure, public contracts,
SDKs, database schema and integration behavior are unchanged. Home Assistant
prompt/context-dependent capabilities remain gated.

The failing mock test waited for a scheduled gate to open during the final 120 ms
of a 400 ms SQL scope. Diagnostic instrumentation exposed the pending worker's
exception instead of reporting only a missing raw-socket latch. In the reproduced
`fetch=false` failure, the gate opened after the owning deadline:

| Diagnostic event | Milliseconds since fixture start |
| --- | ---: |
| Second scope requested | 1047 |
| Second scope action entered | 1048 |
| Second connection acquired | 1049 |
| Raw prepare invoked | 1051 |
| Prepared barrier observed by main | 1057 |
| Approximate requested deadline | 1447 |
| Scheduled gate opened and worker resumed | 1473 |

The requested deadline was recorded immediately before entering `within(400ms)`;
the actual scope began approximately one millisecond later. The gate therefore
opened approximately 25 ms after expiry. The pending future failed with
`ReadFailedException: Read deadline expired before a result was available` through
`Scope.failure` -> `Scope.check` -> `Lease.check` -> statement `State.check`, before
the raw execute mock. This is correct production fencing, rather than evidence
that a live query failed to refresh its network deadline. The `fetch=true`
diagnostic case passed. These observations establish the late scheduler wakeup;
they do not establish why the scheduler woke late or a cold-reflection cause.

A second diagnostic identified a separate harness observation failure after the
first correction. The test's 250 ms future wait expired while the fake socket was
still sleeping: with an applied timeout of 118 ms, raw execute ran from 695 ms to
945 ms (250 ms); with an applied timeout of 119 ms, raw fetch ran from 10 ms to
279 ms (269 ms). The fixture requested timeout plus one millisecond, but its
thread resumed substantially later. This establishes host descheduling/oversleep
in the mock socket, without attributing its cause. Independently, source tracing
showed that restoring the five-second setup deadline before connection closure
could add the allowed 100 ms cleanup wait behind the occupied worker. That source
path is separate from the observed oversleep; neither is a missing network clamp.

A previous full check failed at the first cleanup latch instead. The original
fixture assumed that a first lease would be acquired before its 100 ms setup
scope expired. Its precise failure cause remains unproven; the corrected fixture
removes that incidental setup race as well.

## Correction and retained assertions

The first already-borrowed lease is explicitly aborted. Its scope then exits
with a deliberate typed cancellation, retaining the aborted handle until the
single physical cleanup worker passes the fixture's release barrier. The fixture
observes entry into raw `abort`, verifies the first raw `close` has not occurred,
and keeps the release barrier closed through the second operation's assertions.
Other existing tests retain expiry-to-cleanup and materialization coverage.

Connection and statement setup have a separate finite five-second fixture
allowance. The configured query ceiling remains one second and cleanup budget
remains 100 ms. Once the second statement is prepared, a nested 120 ms SQL unit
narrows the **same Scope object** immediately before the operation under test.
For materialization, the result set was already opened under the longer deadline.
The fixture asserts the previously applied timeout exceeds 150 ms, then checks
that raw execute/fetch sees a positive timeout no greater than 120 ms and strictly
smaller than the earlier timeout. The fake socket crosses its configured timeout
by one millisecond to guarantee that its otherwise successful result is late.
The future must fail with `ReadFailedException` reason `DEADLINE_EXCEEDED`; neither
second raw abort nor close can execute while the first cleanup operation occupies
the sole worker. Connection and statement closure occur inside the narrowed
scope, before its outer setup deadline is restored. The future has a separate
finite one-second **observation allowance**, replacing 250 ms in response to the
measured fake-socket oversleep. This does not increase the 120 ms SQL budget or
the asserted applied network timeout. The fixture proves deadline clamping and
rejection of late results; it does not prove an upper bound on physical wall time
when the host does not schedule the worker promptly.
Finite latch/future observation reports the phase timeline and underlying worker
failure if the raw-I/O barrier is not reached.

This intentionally replaces natural same-scope elapsed aging with inherited
deadline narrowing. It does not claim to exercise a scheduler waking inside a
deadline window. Both paths use the existing `Query.check` calculation: select
the earlier query end and current `Scope.end`, then have `networkDeadline`
subtract current monotonic time. The fetch variant specifically proves that an
already-created Query consults the narrowed scope; the execute variant proves
refresh after checkout. No injectable production clock or new timer API is added.

## Diagnostic evidence

The following temporary coordinator artifacts preserve the diagnostic source and
failure; they are local evidence locations, not production configuration. Raw
test dumps are not copied into the repository.

| Artifact | SHA-256 |
| --- | --- |
| `/private/tmp/ha-sql-diagnostic-source.java` | `8bd36d0666888973935b88eb089151cf92d4b6d5ea075dc1053f00c08082a706` |
| `/private/tmp/ha-sql-diagnostic.xml` | `b7c71cb259501f87af5f9009e11e496dfcc24bfe6ba4dcdcace585959af332b3` |
| `/private/tmp/ha-sql-diagnostic.log` | `1abc0eaf7ae5d06f342f3a3d8f6b98527bb9ecfe34d0f7936e2ec9616ec13bd1` |
| `/private/tmp/ha-sql-terminal-diagnostic-source.java` | `5abf57e074632d0aa54bdaae07f9a6bc9bb726772dac15f0dba6a87ab0eccebb` |
| `/private/tmp/ha-sql-terminal-diagnostic.xml` | `7208f64a18b16b21864a031664bbb249a00eeb8aac0274136c998e18bffe26e1` |
| `/private/tmp/ha-sql-terminal-diagnostic.log` | `4abc60805009eb2f3977dff62f487ed12493fbd79809320e95dd29d42e255b11` |

## Verification

No implementer-run Gradle or real-database tests. The coordinator serializes
formatting, selected mock-test verification and required repository checks.

| Coordinator check | Actual outcome | Evidence SHA-256 |
| --- | --- | --- |
| Final `format` | PASS | `/private/tmp/ha-sql-fixture-format-v2.log`: `c3fd79afddae7d415197d18f5bbe8b53f0c8cd5eddca0477d0d08c5e822eacec` |
| Corrected parameterized method and `formatCheck` | PASS, two cases; five seconds, 65 tasks | `/private/tmp/ha-sql-fixture-method-final.log`: `b35176af7775ec6277935b790688de0af198ed4dd6003a313edc03d63a6fd124` |
| Entire `ScopedSqlWorkBudgetTest` mock class | PASS, 30 cases; nine seconds, 22 tasks | `/private/tmp/ha-sql-fixture-class-final.log`: `42f8d6b88786aca6e20dafbd1c51b49e60d7dd9d931acee8a4de5bcce89f18c8` |
| Unexcluded repository `check` and outbound `installDist` | PASS; five minutes 13 seconds, 144 tasks (29 executed, one from cache, 114 up-to-date) | `/private/tmp/ha-outbound-integrated-v2-check.log`: `ec7f3206cc00104e046eb3b5ea852eb0d89d37f4d89b344c440c284ee10fee3b` |

The corresponding retained method XML hash is
`cba645ac94296020e1aa8a2eb7cab6f4e5fec5907502f693278285081af86b6a`
(`/private/tmp/ha-sql-fixture-method-final.xml`); the class XML hash is
`d5d6bc0bcaef61eff31499cc03685a8ed83831defcc4be07b99cbbbea5b68e21`
(`/private/tmp/ha-sql-fixture-class-final.xml`). Both XML files report zero
failures and errors. The formatted corrected Java source SHA-256 is
`1804bc5e331d9f5eb05a660120981f9656b4cb09b1cf491bbb332973b38fac00`.

The full check reports 8,627 server cases with zero failures, errors or skips.
Its owning-class XML retains 30 cases with zero failures, errors or skips;
`/private/tmp/ha-sql-fixture-full-final.xml` has SHA-256
`fd418a4545ecb4ae43a08354f9d486ec4b37d96c77bfdf1aa968784fb45673b9`.
Home Assistant XML retains 166 passing cases with zero failures, errors or skips;
that test task was up-to-date for unchanged adapter source in this full run.
The corrected Java source hash above remains unchanged after verification.
These are mock/default checks; no real database, live Home Assistant deployment,
model invocation or physical-device acceptance is claimed. Independent approval
is still required before marking this deliverable accepted.
