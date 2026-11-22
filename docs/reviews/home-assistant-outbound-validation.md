# OUT-VERIFY-01 outbound validation

Implementer: `/root/ha_outbound_validation_implementer`.
Status: **READY FOR INDEPENDENT REVIEW; NOT ACCEPTED.** Scoped validation and
installed-consumer checks passed, but the required full repository check fails in
an existing unchanged SQL concurrency test. That acceptance requirement remains
unmet and is not waived. This report is not release or live acceptance.

## Candidate and ownership

The validation node consumes the independently accepted outbound host/package and
profiles. The packaging candidate is
`ef835127bb7caaf71b574136adad0e0528a70eee967cd25485f26a44a4b33f7f`.
The current profile candidate is
`6698b07861e88453ef17242f62bd56313e322a2911ff1d91ef686d9d961e3c10`.
Their original authors own feature corrections; the validator authored no host,
profile, core, SDK, Assist or delivery-ledger implementation.

Authored validation outputs are:

- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProviderSdkValidationTest.java`;
- `integrations/home-assistant/scripts/check-outbound-package.py`;
- this report and the final text source/artifact evidence attached by the
  coordinator before acceptance.

## Public protocol evidence

Three Java cases instantiate the actual production `HomeAssistantProviderHost`
with the validated control configuration and real native HA/Plowshare sockets.
The SDK uses its ordinary segmented subprotocol and concurrent dispatch; a local
broker fixture uses the shared public packet transport rather than a legacy
fallback or mock SDK service. It scripts Relay outcomes and does not implement
or establish actual server authentication or authorization.

The normal case checks one SDK connection, separate read/control catalogue
topics and exact declared tool sets, bounded empty polling and ordinary
withdrawal with empty tool arrays under both original logical topics. The
negotiation assertion checks the incoming concurrent mode as well as the reply.
The catalogue-loss case deliberately withholds the application
acknowledgement after accepting the publication. Its original intent remains
journaled; a stopped owner cannot poll or publish again. A separately constructed
host represents an explicit operator restart. A missing read-only log result
leaves the original publication uncertain and cannot authorize replacement.

The third case delivers one schema-matched search invocation through the actual
SDK Relay consumer and executes native HA registry collection. Its result keeps
the invocation correlation and parent identity. Losing the result acknowledgement
retains `PUBLISHING`; explicit restart inspects the original result without
repeating that handler collection or publishing a replacement result. Native
startup state snapshots may legitimately occur again on an explicit restart.
No fixture admits a `call_service` effect. These cases prove adapter composition
and wire/recovery behavior, not device success or zero native traffic on restart.

## Fresh installed consumer

The standard-library package checker requires an explicit Java 21-or-newer
`JAVA_HOME`. It copies the installed POSIX distribution into a temporary directory
outside the repository and uses only that installed launcher, jars and examples.
It removes configured credential references and JVM/classpath overrides from the
child environment; only fixture origins change to local connection traps.

It checks both provider configurations and the original legacy `--check` entry
point, first with no journals and then with deliberately unreadable retained
provider journals. File hashes and bounded directory topology are compared around each command,
the fresh copy and original artifact to detect creation, migration, deletion or
rewriting, including empty journal directories. Negative cases exercise
unaccepted prompt input, contradictory control principal and malformed CLI flags.
Every subprocess has a finite timeout, diagnostics are bounded, traps must see no
connection and a supplied private marker must never appear in output.

The payload inspection requires the actual main class and external runtime/SDK
jars, rejects server/test/Assist namespaces and checks every relative Markdown
destination inside the installed guide/examples. Artifact hashes identify the
original unmodified installed distribution; report contents are relative paths
and check names, not credentials, environment dumps or private configurations.
The checker does not claim Windows launcher acceptance.

## Findings returned to feature owners

Installed-profile review found three source-only relative documentation links
which did not resolve in the distribution. The validator and independent
validation reviewer reported them to the coordinator. The original profile
implementer corrected those links; an independent profile reviewer approved the
new v2 candidate above. The packaging implementation stayed unchanged. The
validator did not weaken link checks or edit an accepted feature source.

## Verification and exact evidence

The coordinator ran final `format`, then `formatCheck` with the three strengthened
SDK/native HA cases and the entire existing `ScopedSqlWorkBudgetTest` class.
All three validation cases and all thirty SQL-class cases passed in 8 seconds
(72 tasks). Logs are retained privately as
`/private/tmp/ha-outbound-validation-and-sql-final.log` and
`/private/tmp/ha-outbound-validation-format-final.log`.

The fresh installed consumer passed all eight checks against 44 artifact files:
26 runtime jars and four valid artifact-local documentation links. Its
[package evidence](home-assistant-outbound-validation-package-evidence.json)
binds the exact script, local report/log and every installed file hash. The
[source snapshot](home-assistant-outbound-validation-source.json) binds 23 current
feature/validation files, both accepted dependency candidates and four unchanged
Assist gate sources matching the original frozen gate correction. The original
distribution remained unchanged by the consumer.

The first consumer attempt reached its malformed-CLI case but expected internal
helper status 2 as the OS status. The actual public entry point returns status 1
and a usage diagnostic. The validator expectation was corrected and the entire
consumer passed; no feature behavior was changed to fit the fixture.

The initial file-only consumer snapshots missed a possible empty journal-directory
creation. Independent review identified that P2 validation gap. The original
validator added explicit directory-topology checks without inflating the 44-file
artifact manifest. Both implementer and coordinator independently ran the exact
guard against a new empty directory with identical file hashes; it rejected the
change. The final complete eight-case installed consumer passed with that guard.

The first repository invocation failed one existing non-database SQL barrier
assertion among 8,627 server cases. An isolated-method invocation then failed
both parameter variants at a different barrier. The complete owning SQL class
subsequently passed all thirty cases without any core/test change.

The final full repository invocation with `--max-workers=2` **failed** in 5m10s
(140 tasks). All 166 HA cases, including these three fixtures, passed; the server
suite had two failures among 8,627 cases. Both were
`ScopedSqlWorkBudgetTest.lateQueryAndFetchClampNetworkTimeEvenWhenPhysicalCleanupWorkerIsOccupied(boolean)`
parameter variants at `socketRead.await(1s)`. No source or test exclusions and no
core/SDK/Assist edits were used. All relevant pass/fail log hashes are retained in
the package evidence above. These results establish neither a root cause nor a
harmless flake, and do not establish that this change caused or could not cause
the failure. A green required repository check has not been obtained. Diagnosis
or repair of core behavior is outside this integration's authorized scope.
Independent review must retain this blocker rather than approve the node as
complete or weaken the plan's acceptance requirement.

The implementer ran Python `py_compile`, Ruff lint and Ruff format checks on the
standalone checker. All passed after correcting import ordering. No Gradle,
PostgreSQL, live endpoint, device, model, deployment or Git action was run by this
implementer. The coordinator owns actual Gradle and installed-consumer execution.

## Limits and preserved gates

Template parsers and scripted brokers do not prove live grant issuance,
membership, model-fleet configuration, HA target versions or physical device
effects. Those remain separately authorized operational evidence. Existing Ex
server tests with mocked repositories and canonical SDK-contract drift checks
supplement the transport fixtures; they do not turn this node into original
whole-package VERIFY-01.

All eleven original awaiting-gate deliverables and four capability/operational
gates remain unchanged. Assist setup, configuration and installation packaging
remain disabled; no prompt is rewritten, relocated or downgraded to task text.
The previous isolated HA Python environment is absent and was not recreated to
make an unsupported prompt-dependent feature appear complete. No current full HA
Python runtime-check pass or live voice pipeline is claimed. Historical frozen
reviews retain their exact-source scope. This node prepares outbound-only source
and local artifact evidence; it does not close PERF, RELEASE, DEPLOY or LIVE work.
