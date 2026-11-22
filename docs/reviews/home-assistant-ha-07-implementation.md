# HA-07: AI Task text provider and entry persistence

Implementer: `/root/ha_ai_task_sdk_implementer`.
Status: coordinator checks passed; ready for exact-candidate independent review;
not yet accepted. No implementer-run build or deployment result is claimed.

## Supported task contract

The component provides an independent `AITaskEntity` advertising only
`GENERATE_DATA`. It consumes HA's already prepared ChatLog system text unchanged
and the current instructions through the accepted typed `TaskRequest` gateway.
Only a completed gateway outcome returns `GenDataTaskResult.data`; pending,
uncertain, refusal and failure are HA errors rather than generated prose.
Completed text is preserved and mirrored into the HA chat log.

Each invocation uses a fresh internal key and HA's returned chat ID. The entity
never reads mutable `entity._context` for identity: HA does not expose per-call
caller context to the subclass hook, so user provenance remains explicitly absent.
Concurrent identical tasks therefore do not share a retained invocation.

Attachments, HA LLM tool APIs and structured output remain explicitly unsupported
before SDK submission in this text-only slice. HA's final base helper can prepare
an explicitly supplied LLM API before calling the subclass; this adapter does not
claim to prevent that HA-side preparation or execute its tools. HA-08 owns the
subsequent typed structured-output handoff. No images/evaluation are advertised.

## Independent runtime profiles

HA-06's typed `configured_profiles` selects explicit conversation/read-only,
control or task agents. `EntryRuntime` implements HA-05's `ProfileRuntime` without
adding gateway/SDK fields. The two optional gateway ports share one correlation
set and one durable writer, with distinct stable entry/profile identities.
Task configuration never inherits a conversation/control agent.

A typed HA owner registry retains setup/unload owners independently of
`ConfigEntry.runtime_data`, which HA can clear after a failed setup. Setup
registers shutdown before filesystem/forwarding awaits. A synchronous closing
fence hides new port lookup, setup's own lifetime remains owned while loading or
forwarding, and setup refuses success if stop began during either await.
Registry removal is identity-conditional so late cleanup cannot delete a
replacement owner. Reload/unload refuses disposal while any gateway or journal
owner remains unsettled. Accepted server jobs are never cancelled by cleanup.

The existing publication/readiness guard is unchanged and remains before storage,
SDK composition or platform registration. HA-11 owns its reviewed final enablement.

## Truthful local journal

HA's normal `Store.async_save` can suppress a write error or defer a write during
shutdown without raising. It is not used as submission acknowledgment.
`CorrelationJournal` is an adapter-owned specialist persistence implementation:

- Derives its absolute path from HA's configured storage location and validated
  config-entry identity; no host/path/credential defaults are introduced.
- Reads bounded UTF-8 JSON through a nonblocking/no-follow descriptor, checks a
  regular file, rejects duplicate keys, invalid constants/depth, unknown envelope
  fields and unsupported versions, then converts to typed session records.
- Accepts the previous HA storage envelope and retains legacy correlation IDs,
  job phases and cursor. Startup does not rewrite it. The accepted gateway keeps
  unbound rows quarantined; no internal-ID alias import or uncertainty reset occurs.
- Serializes an immutable snapshot at admission and uses one bounded FIFO physical
  writer. File data and directory replacement are fsynced; files are private.
  An error is `JournalUnavailable`, an `OSError` accepted by the gateway's existing
  I/O-failure contract, never a successful save or SDK replay permission.
- Caller timeout/cancellation leaves queued/physical writes owned. Later snapshots
  cannot overtake an older write. Close stops new admission and observes settlement
  without cancelling physical work. An unexpectedly cancelled physical future is
  quarantined because its executor completion cannot be inferred.
- Persists only correlation, route fingerprints and phases, not model text or
  credentials. Invalid/corrupt input is preserved for inspection.

The queue holds at most eight admitted writes, matching the two possible profile
ports' bounded active callers. Local I/O uses configured finite waits; physical
filesystem work can outlive a wait and retains ownership until it settles. This
is not a claim that Python can abort a blocked filesystem operation.

Valid legacy rows can acquire explicit `mapping=legacy` fields on a subsequent
legitimate snapshot, preserving their values and quarantine. Earlier prototypes
that only decode four fields cannot be promised transparent rollback into that
new snapshot; installation/rollback documentation must preserve the old backup
and uncertain receipts rather than erase or replay them.

## Authored verification

New tests exercise actual HA AI Task action/helper/entity plumbing, completed-only
results, concurrent tasks, absent context after a prior authenticated call,
unsupported requests, cancellation, independent task-only setup and selected
profile composition. Real temporary-file tests cover atomic commits, permissions,
legacy/corrupt/deep/duplicate input, FIFO/symlink rejection, fsync failure, failed
intent before any Ex mutation, writer timeout/cancellation and FIFO ordering.
Actual HA Store counterexamples demonstrate why its best-effort acknowledgment
was insufficient. Setup tests cover stop during read/forward, stale cleanup,
failed unload/reload and original-platform cleanup after configuration changes.

The coordinator owns formatting/lint, the complete pinned HA runtime checks and
public-SDK fixtures. The check extras now include the pinned numpy/PyTurboJPEG
imports required by HA 2026.9.4's AI Task import chain. Tests were not run by this implementer; the coordinator
results and exact source hashes are recorded below for independent acceptance. No core, SDK, protocol, shared-runtime or Java source is changed.

## Coordinator's first full-run findings and author corrections

The coordinator's first combined HA-06/07 runtime invocation is retained in
`/private/tmp/ha-06-07-full-v2.log`: Ruff passed; 108 cases ran, with 93 passing
and 15 errors. It was not an HA-07 acceptance pass. Fourteen errors were in this
slice: ten AI fixtures omitted the public device-registry setup step, one fixture
assigned HA's read-only config-entry data directly, and three first-save cases
exposed an actual fast-completion race in journal loading. The remaining registry
fixture error belonged to the concurrent Conversation slice.

The fixtures now call public device-registry setup before device load/entity load,
and use actual `ConfigEntries.async_update_entry`. `async_load` now establishes
its loaded-state invariant immediately from the awaited result before returning;
the detached-caller callback remains for eventual physical completion. A
completed-future injection deterministically covers that race.

Independent pre-freeze lifecycle review also found that cancellation or failure
of aggregate platform forwarding could hide a live sibling, and successful
platform cleanup could be repeated after partial failure. The runtime now owns a
separate task for each of its at most two singleton public HA forward calls.
Observers use `asyncio.wait`, which does not cancel these tasks on caller expiry
or cancellation, and inspect all physical completions. The existing HA setup
stage limit bounds the caller wait; late work remains owned. An unexpectedly
cancelled physical forward remains uncertain rather than proving settlement.

Failure/stop cleanup and ordinary unload use the same per-platform owner.
Successful unload progress is retained and never repeated. Failed or pending
cleanup blocks replacement and keeps the owner; actual public unload calls are
used rather than private HA state inspection. Singleton calls are intentional:
they isolate HA's nested gather and permit accurate partial-cleanup accounting.
A framework refusal that cannot prove a platform unloaded remains a retained
cleanup failure requiring inspection; it is not converted to successful disposal.

Additional tests cover a failed forward while a sibling remains live, setup
cancellation with a late child, platform cleanup after stop, failed cleanup
blocking replacement, and successful-sibling/failed-sibling unload followed by an
explicit retry. Those corrections were then submitted for the fresh coordinator run recorded
below; this historical failure record is retained rather than replaced.


## Final coordinator verification on frozen source

The initial full invocation v1 stopped at an unused test import before runtime
execution; the coordinator removed that import mechanically. V2 then ran 108
cases with 15 errors as recorded above. These attempts remain part of the
evidence rather than being described as successful passes.

The corrected v3 complete Assist invocation passed Ruff lint, Ruff format
verification and **113 tests in 1.298 seconds**, with no failures or errors.
The coordinator checked that all shared source/test hashes remained unchanged
through the run. This includes the actual isolated HA Conversation service/API
and AI Task service/helper, the real segmented public SDK fixtures, and the
journal/setup lifecycle tests. It is a complete local scripted-fixture check,
not a live HA/model/device or hardware power-loss test.

| Coordinator artifact | SHA-256 |
| --- | --- |
| `/private/tmp/ha-06-07-full-v1.log` | `f53e054d415957e6e3abc870ed251e8fb0ddd63bfb6e5786e4d6d095bff5fce2` |
| `/private/tmp/ha-06-07-full-v2.log` | `0a8717ab3bbd0874b43d67a9df330b6e42997f2ccf3c6bad2059bb962cec17c8` |
| `/private/tmp/ha-06-07-full-v3.log` | `f8fc9f4609779aca8afcd51a9bd2c3172f569c7678f6f8be26ec3fe322e0cceb` |
| `/private/tmp/ha-06-07-full-v3-source.json` | `ca33183416f8f0d967cc93951e703fe16da2fea170cceccbe526996334940656` |

The HA-07-owned implementation/test bytes in that source snapshot are:

| File | SHA-256 |
| --- | --- |
| `integrations/home-assistant/assist/custom_components/plowshare/__init__.py` | `858d43458d45bc19577073ae6f3e499a3dade0a8bb5606fddb3b9692233e318c` |
| `integrations/home-assistant/assist/custom_components/plowshare/ai_task.py` | `5b82cd8f3dae46efce7246d9ea0bdeb6de8848a4a7f6eb5f85d2a37eaa152920` |
| `integrations/home-assistant/assist/custom_components/plowshare/runtime.py` | `0b7b4d298a8bbed69a9e572b736b8eadf1dbb9a6c8bc62badb89a4d40828de92` |
| `integrations/home-assistant/assist/custom_components/plowshare/storage.py` | `4161b97a7f1330ec1a5e13a34e1ad0c861730a4911738899e2089fa3cd16b257` |
| `integrations/home-assistant/assist/custom_components/plowshare/manifest.json` | `6b8193f156919635af764e2d21b0d768eea66f6b0f3b501d3dcce6194a094fbe` |
| `integrations/home-assistant/assist/pyproject.toml` | `9ff8fa45eb5d3544550d4eb6ef0340144410c0029950e012d17a5665fae6720b` |
| `integrations/home-assistant/assist/tests/test_ai_task.py` | `73b1a4784736b65212d062c0f69fd9268ceb401974dcc9e26ab6930e520502a6` |
| `integrations/home-assistant/assist/tests/test_storage.py` | `5fe70f0ead268e08e2cf087639c77bcfc8a5a423c62c418cbae97697417daa50` |
| `integrations/home-assistant/assist/tests/test_entry_setup.py` | `133826f0042528ec326cf332a4cd64970152a9e94489aa85cc2bd5f5565a503b` |

The shared gateway, configuration and Conversation source in the complete
snapshot belong to their original independently reviewed nodes; this author did
not amend them. Setup/configuration/packaging readiness remains disabled until
HA-11. Structured requests remain unsupported until HA-08. No core, SDK, shared
protocol/schema, database, deployment, live endpoint or model/device work was
performed by this slice. Final HA-07 acceptance still requires its independent
reviewer's exact frozen-candidate approval.
