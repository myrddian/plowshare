# HA-09 implementation candidate

Status: coordinator verification passed; Java and documentation are frozen for
independent review and affected HA-04 reapproval. No completion, deployment or
live validation is claimed.

## Deliverable and ownership

The existing shared integration runtime now accepts a bounded exact `whenState`
condition alongside numeric `above` routes. The condition is mutually exclusive
with `above`/`unit`, requires a selected alias, and supports the same cooldown and
held interval as a numeric predicate. Existing constructor overloads, numeric
configuration and durable event/state formats remain valid. `ThresholdPolicy`'s
retained `high` bit simply means the configured predicate matches; no additional
event engine, timer, queue, journal or scheduler was introduced.

Root authorized two shared runtime production paths (`Configuration.java` and
`ThresholdPolicy.java`), their configuration/route fixtures, and the narrow affected
HA-04 action-policy guard. The HA-01 discovery contracts, native adapter/wire,
resource catalogue, SDK, core runtime, APIs and database are unchanged by this node.
The shared design's authorization/feedback paragraph is aligned with this current
named-control scope and documented composition limit; its prompt/cache/fidelity
sections are unchanged.

Named device control policy now refuses `conversation.process`,
`ai_task.generate_data` and `ai_task.generate_image` before admitting a policy
generation. This conservatively prevents direct calls into the package's own
model-backed HA endpoints: an entity target cannot identify an agent selected by
parameters or HA defaults. No override is supplied without a typed destination
and ancestry contract. Read discovery can still describe a configured profile;
description does not confer execution. Manual operator-authored aliases retain
their separate configuration contract. Arbitrary HA scripts and indirect service
chains are outside this guard's claimed scope.

[Observation routes and composition limits](../../integrations/home-assistant/OBSERVATION-ROUTES.md)
document the supported behavior. The [action-provider guide](../../integrations/home-assistant/ACTION-PROVIDER.md)
records the named-control restriction without changing the accepted receipt,
recovery, revocation or authorization contracts.

## Feedback ownership and the deferred bridge

Existing runtime-owned actions continue to commit a known acknowledgement's bounded
HA context with their action result in the existing integration journal. Context
correlation suppresses eligible workflow starts and held intervals while preserving
the event as evidence. Unknown effects never receive invented context correlation
and are never automatically replayed.

Named SDK controls retain their own authoritative SDK invocation journal and typed
native context receipts. Those receipts are not automatically inserted into the
legacy runtime's feedback index. An after-ack callback alone is unsafe: a native
event may arrive before its acknowledgement, a concurrent runtime pass may already
plan the event, and a capacity check alone does not reserve an index slot against
another action. A future bridge needs bounded admission into the same physical
effect/planning owner and durable known-acknowledgement intake into the existing
journal. No new journal or callback framework was added to disguise that gap.

Operators must keep named-control effects independent of workflow-start sources
or use the existing runtime-owned action path when relying on its causal feedback
policy. This candidate preserves and tests the existing scoped feedback contract;
it does not claim automatic cross-host or universal loop suppression. Captured-input
ORCHESTRATION routing also retains its current restriction: a running orchestration
cannot issue a fresh remote export and wait for an adapter result through this slice.

## Source evidence for the service guard

Inspected installed HA 2026.9.4 source offline, without importing optional AI Task
dependencies or calling any service. `components/conversation/__init__.py` registers
`SERVICE_PROCESS` (`process` in `const.py`); `components/ai_task/__init__.py` registers
`SERVICE_GENERATE_DATA` and `SERVICE_GENERATE_IMAGE` (`generate_data` and
`generate_image` in `const.py`). `components/llm/__init__.py` registers Assist APIs,
not an `llm.generate_data` service, so that guessed name was not added to the guard.

| Installed source relative to `homeassistant/` | SHA-256 |
| --- | --- |
| `components/conversation/__init__.py` | `65a0d85b679b3c5b16656182fed12750b4002fe45fe70b4bd43ff52a484fe7f3` |
| `components/conversation/const.py` | `417f337811dffae62158e81b740089fc38c4f08e1f4ac34c3f4894d11e703750` |
| `components/ai_task/__init__.py` | `b9a7edd87566660971bdb428d9e11cf94fbbc3cd5141d3c0bf0427a8a807a877` |
| `components/ai_task/const.py` | `61e09538cccdd0962dc121020597c66f497c4608032c9d9c0c1880882daa790c` |
| `components/llm/__init__.py` | `729d25b23de61b666b8194818c7fc6a8ede531b58cdfad63eb912ed4d33befcd` |
| `components/llm/const.py` | `c33c4d1ce8bffec54d0267542c3bb0a96223b45afdc8884283591e8b9cd4ca6a` |

## Verification and actual results

The coordinator's serialized focused run passed these new fixtures:

- `ConfigurationTest`: strict exact-state decoding, absent/nonnumeric/ambiguous
  conditions, units, bounds and missing aliases; existing numeric cases retained.
- `StateRouteTest`: exact edges/case, bootstrap suppression, cooldown, held completion,
  gap/resync/unavailable/epoch cancellation, restart/downtime, runtime-owned causal
  acknowledgement, unknown-action non-replay and preserved queue transitions at bounds.
- `HomeAssistantControlServicesTest`: all three endpoint refusals, unchanged generation
  after refused replacement, discovery-versus-execution distinction and manual-profile
  compatibility.

Observed current results: **125 invocations in 15 classes**, with zero errors,
failures or skips, in **49 seconds**. The retained JUnit reports independently
confirm the coordinator's counts:

| Module | Selected classes | Cases |
| --- | --- | --- |
| Shared runtime | `ConfigurationTest`, `StateRouteTest`, `HeldThresholdTest`, `FeedbackTest`, `CausalLineageTest`, `RetentionTest`, `RecoveryTest` | 82 |
| Home Assistant | `HomeAssistantControlServicesTest`, `HomeAssistantActionToolsTest`, `HomeAssistantActionProviderTest`, `HomeAssistantActionVerifierTest`, `HomeAssistantActionOfferFenceTest`, `HomeAssistantNativeActionsTest`, `HomeAssistantAdapterTest`, `PipelineTest` | 43 |

The new state-routing and control-safety classes contribute nine and seven
invocations respectively; the configuration table test is the seventeenth new
invocation. `IntegrationRuntimeTest` and `JournalTest` were not selected in this
run and are not claimed as current evidence. All selected tests use fake
adapters/transports; no real database was required.

The coordinator also ran global `format` and a separate `formatCheck`, both passing
in four seconds. Source remained frozen afterward. Evidence is preserved beneath
`/private/tmp/ha09-verification-evidence/` with runtime/HA JUnit XMLs and these logs:

| Log | SHA-256 |
| --- | --- |
| `ha09-focused-check.log` | `5efc87aabe673be22459b4358fd91848d92cdb4ad65bdc122a37cf96c8f7580b` |
| `ha09-format.log` | `50c67403ce9ad4e9e43f93d34ad3dda3fa0144888fd47dc32fc37514ce21c78f` |
| `ha09-format-check.log` | `ed44261c28861cf1c8e7a306e0b9ed263acf3be4e0b32cb7dc450bb1801498b0` |

`git diff --check` passes for the owned changes. Gradle and formatting were run by
the coordinator, not this implementer. No live HA, model or device execution was
performed. Repository-wide checks and independent approvals remain separate
acceptance evidence; these focused checks do not establish deployment readiness.
