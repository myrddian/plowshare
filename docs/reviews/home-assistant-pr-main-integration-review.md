# Main integration independent review

Verdict: **APPROVE**. No blocking findings.

Reviewer: `/root/ha_pr_integration_reviewer`.
Implementer: `/root/ha_pr_integration_implementer`.
The reviewer did not author or modify implementation, tests or the delivery ledger.

This approval binds candidate
`docs/reviews/home-assistant-pr-main-integration-v1-candidate.json`, SHA-256
`241bbe44f0b95f9bd65fc9b07b20d67ffe2d48897d3c778b82e503b72df52f4a`.
All five source/test hashes and the implementation-report hash match the current
files. The reviewed base is merge commit
`f75d870fca6b680b536d704e9a0814f1dcadc863`, incorporating main through `abef4b11`.

## Contract and implementation

Main's PostgreSQL read helper could not unwrap a native statement inside an
active Ex SQL scope. The correction introduces a narrow infrastructure-only
statement capability that tightens an absolute deadline and returns no JDBC
handle. Its implementation checks caller/lease ownership and refuses application
during an active result. It retains the earlier applied deadline across statement
reuse. The proxy uses the application capability's class loader, and standard
`unwrap`/`isWrapperFor` continue to reject native `PgStatement` access.

The new query overload takes the minimum of the read ceiling, current operation,
configured per-query limit and active parent query. That deadline reaches the
existing watchdog, network/server limit and result-materialization checks.
Physical cancellation, cleanup ownership, query-count limits and bounded metric
labels retain their existing owners. The private raw-statement call preserves
shorter pgJDBC millisecond limits; the fallback retains the scoped cancellation
boundary without exposing a native handle. Unscoped helper behavior remains the
owning PostgreSQL path. No facade, service or adapter receives JDBC infrastructure.

The cancellation regression verifies both open and run with ordinary archive
unavailability and main's `ArchiveQueryCancelledException`. Ex outcomes retain
their original cause and possible commit/admission uncertainty, omit raw dependency
details and do not replay work. The change does not broaden ordinary search's
read-only recovery policy to mutations.

## Merge verification

The automatic `ReadDeadline.java` merge retains Ex's `isCurrent(long)` scope-bound
evidence and main's removal of `jdbcTimeoutSeconds`. No production/test callers
of the removed method remain. This review additionally binds the merged
`plowshare-server/src/main/java/io/aeyer/plowshare/server/reads/ReadDeadline.java`
SHA-256 `d0744638e8887b45f1069588c7a3fed9e51721abbed31b9ed787020e5a1d128f`,
beyond the frozen implementer candidate.

Migration versions are unique through V274; main ends at V273 and the alias
migration remains append-only. Main adds no shared protocol or SDK contract
collision: its only SDK changes are Node workspace cleanup implementation/tests,
disjoint from the Ex operations. This review covers merge compatibility and the
five-file correction, while prior independently approved candidates retain
ownership of the broader feature implementation.

## Evidence and limits

The focused log SHA-256
`76fa79f6b71c1be837f45355ddb4e180b972f5d64fcbf2f6ce8a1ccaf3bc93cd`
matches the candidate. It records `format`, `formatCheck` and twelve selected
non-database classes passing in 19 seconds, with 127 passing test cases and no
failures. The reviewer inspected the new tests, including both driver/fallback
branches, native unwrap refusal, statement reuse, materialization cancellation
with retained physical cleanup, and both Ex uncertainty paths. The scoped diff
passes `git diff --check`. Java types use imports and simple names.

No additional Gradle process or PostgreSQL instance was started by the reviewer.
These fake-JDBC tests establish proxy dispatch/deadline ownership and exception
translation, not live PostgreSQL termination or performance. Earlier selected
database evidence is not replaced. The coordinator's full repository and SDK
publication checks were still running at review time and remain required before
publication.

Home Assistant prompt-dependent capabilities remain gated. This review adds no
system-context API, prompt rewrite, installation release, deployment, live HA
validation, model/device action or authorization to merge the PR into main.
