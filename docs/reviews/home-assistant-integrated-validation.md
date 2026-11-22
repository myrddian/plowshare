# VERIFY-01 — Integrated installed-contract validation

Implementer: `/root/ha_integrated_validation_implementer`. Independent reviewer:
`/root/ha_integrated_validation_reviewer`. This report covers offline source and
artifact validation only; review acceptance is recorded separately in the ledger.

The new installed-contract fixture closes the composition gap between the
previously reviewed HA entity tests, SDK socket tests and installation checks.
It builds a fresh ZIP, extracts it outside the checkout and copies the standalone
consumer there. A bounded isolated Python subprocess admits only that explicit
installation plus its existing environment dependencies. Loaded component and
private SDK modules must resolve under the extracted installation; the consumer
never imports the source-test staging helper.

Three consumer cases exercise the actual component entry setup, shared atomic
correlation journal, gateway, HA Conversation entity, AI Task helper/action,
bundled public SDK codecs/resources and segmented WebSocket transport. The
only isolated HA boundaries are unrelated user lookup and platform-loader
forwarding/unload. The actual AI Task entity is registered with the real HA
component and invoked through its public service. The loopback protocol peer
supplies deterministic alias/job receipts; it executes no model or device work.

- Assist returns HA's conversation ID and reuses the same external SDK alias for
  a second utterance. Exact prepared system text, supplied extra context and
  user-input whitespace survive into ordinary task/user input. A foreign HA
  source cannot reuse the chat or cause another wire operation.
- Text and flat structured AI Tasks select the explicit task agent and fresh
  aliases, separate from Assist and every other invocation. A malformed completed
  structured result raises an HA error without repair/retry or a successful
  assistant-log entry, while the completed job receipt remains retained.
- A peer-accepted run whose acknowledgement is lost leaves an uncertain local
  receipt. Entry unload/reload within the same HA process retains that receipt
  byte-for-byte. Following the same HA chat causes no new connection/open/run.
  This is not a claim of conversation ownership recovery after an HA restart.

Before accepting each Ex mutation, the peer reads the real committed journal and
requires a sending intent for the exact external alias. It checks bearer,
negotiated packet protocol, operation envelope, agent/project route and ordinary
task/user context metadata. Unsupported operations fail the fixture. The peer
exposes only Ex open/run and receipt reads; ordinary conversation/run fallbacks,
subscriptions and hidden server access cannot satisfy the assertions. Peer send
tasks, server closure, HA shutdown and subprocess lifetime have finite waits.

The coordinator ran the full explicit Assist check on the final unchanged source
snapshot: Ruff lint/format passed, and **135 outer tests passed in 3.265 seconds**.
One outer test builds the fresh archive and requires all **three installed
subprocess cases** to pass; they are not three additional outer-suite cases.
The author ran only Ruff on the two owned Python files and did not execute the
runtime checks. Evidence:

- `/private/tmp/ha-final-assist-v1.log`, SHA-256
  `11b7df52df296cbe59836347e8223c12558ff25a315669aa7604c83dbcc6c913`.
- `/private/tmp/ha-final-assist-v1-source.json`, SHA-256
  `ba95b3d6670dd28938e63c9698777f186447ce52422f3802c30ccbb4a4c3f787`.
- [Final validation evidence](home-assistant-final-validation-evidence.json),
  SHA-256 `dbf472faa3aa0a62a832698e097ef77d0790da169b909bf062324dbc601a5e67`.

That evidence binds 116 current source files, the fresh 51-entry Assist ZIP and
its 50 contents hashes, the final 56-file outbound installation, approved
dependency revisions and broad-check logs. Repository `check` plus HA
`installDist` passed in 1 minute 14 seconds across 144 tasks, including 184 HA Java
cases and 8,627 default server cases, with no database tests. The fresh installed
outbound consumer passed all eight offline checks, including retained journal
preservation and no observed network side effects. The explicit SDK package
checks passed in 4 minutes 44 seconds across Java, Node, Python, Go and .NET,
including fresh distribution consumers. Reuse is supported by byte-identical
hashes for all 4,042 protected core/protocol/SDK/shared-runtime source files and
the absence of new untracked files there; the SDK check was not needlessly
repeated after changes restricted to the adapter's test fixtures.

No production adapter, SDK, server, shared contract/runtime or schema change is
part of this validation node. No PostgreSQL/full-db tests, live HA, live server,
provider/model, speech engine or device action was run. The target HA version,
live grants, deployed health and end-to-end voice behavior remain unverified.
The supported test baseline is HA 2026.9.4/Python 3.14. HA text keeps existing
task/user semantics; cache awareness is advisory, with no native-role, cache-key
rewriting or special compaction guarantee.
