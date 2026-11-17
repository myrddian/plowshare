# HA-02 v2 implementation evidence

Node: `HA-02`. Implementer: `/root/ha_reads_implementer`.
Date: 17 November 2026. Base: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e` plus the dirty delivery tree.
Status: **READY FOR INDEPENDENT RE-REVIEW**. This report does not close findings or accept the node.
The [v1 evidence](home-assistant-ha-02-implementation.md) and
[v1 independent findings](home-assistant-ha-02-review.md) remain historical records.

## Current capability and boundaries

The adapter-local typed ResourceSource/Catalogue shares the configured native HA
WebSocket. Policy filtering precedes paging/counts; one metadata snapshot is
bounded to 256 resources. Read batches preserve request order and explicit
missing/unavailable/invalid evidence without per-entity network calls. Metadata
uses collection registry/service probes and registry names/aliases, never a
fetch/discard state poll. Vendor JSON stays inside Adapter/Wire; the catalogue
consumes typed immutable snapshots.

Binding/opaque credential generation, policy revision and connection epoch fence
reuse. Private invalidation tokens remain internal; outward metadata revisions
advance only for changed permitted projections. Bounded refresh can prove an old
page still valid; changed public metadata refuses the old cursor. State versions
track the source's selected references independently. A positive configured
metadata age (at most five minutes) also bounds reuse when registry-event
subscriptions are denied. Unsupported/denied capabilities and area-relationship
filters fail explicitly, never claiming an empty house from missing permissions.

The resource policy is trusted host configuration, never model/tool input.
`fromSettings` retains existing aliases/attribute ceilings/alias-only metadata;
a separate explicit resource policy can opt into additional bounded attributes
for those same configured entity targets. Resource reads identify unsupported
nested field names without payloads; the legacy manual scalar codec still refuses
them. The narrow immutable policy `references()` accessor is part of the current
HA-01 v3 dependency and has not changed during this corrective iteration.

These capabilities do not add a named SDK ToolProvider, execution grants,
AI Task/Conversation prompt handoff, core/shared payload or SDK API. HA-03 owns
tool publication; prompt-dependent features remain gated by the user's decision.
The [resource contract](../../integrations/home-assistant/RESOURCE-CONTRACTS.md)
documents composition and exact limits.

## HA02-R1 corrective implementation and ownership

Fast connection loss now fences the captured generation and captures pending
futures under the adapter monitor, then performs socket cancellation and pending
completion independently of arbitrary observer/journal work. The original Gap is
retained until admitted to its separate observation owner. No externally supplied
observer runs under the adapter monitor or on an expired request's abort path.

`HomeAssistantObservations` owns an ephemeral single-worker FIFO: 256 pending
state observations, a reserved Gap and one active delivery. On final close, an
already removed but never-invoked delivery is returned to retained storage rather
than lost if scheduling loses the close race. Overflow fences continuity/intake;
accepted transitions retain order followed by their Gap. Reconnect waits for the
old physical callback/FIFO/Gap to settle. Startup resync acknowledgments are
awaited outside the adapter monitor, preserving initial-delivery behavior.

Callback and cleanup budgets default independently to two seconds and have an
explicit host constructor configuration. Callback expiry fails logical admission
and fences only the observation's captured generation. The physical callback slot
and original identity remain retained until the callback actually returns. An
interrupt cannot target a subsequent delivery. A late normal return can drain the
retained FIFO/Gap once; an exception leaves an uncertain journal position and
requires reconciliation, never replay or running later callbacks around it.

Cleanup drains within one remaining budget, then interrupts and reports incomplete
cleanup with a fixed diagnostic. An arbitrary observer may ignore interruption;
its daemon thread can outlive cleanup, still owning its physical work and original
identity. This does not claim remote/journal abort or safe journal-owner replacement
before settlement. The queue is not a second durable runtime and does not persist
its own independent receipts. An operator must reconcile retained uncertainty
through the owning runtime/journal lifecycle.

## HA02-R2 corrective implementation and generation audit

State and authentication callbacks recheck the captured epoch atomically with
their mutations. Result association, metadata invalidation, socket assignment and
failed-start cleanup retain the corresponding generation fences. A new attempt
starts disconnected with no old socket attached. Loss retires its current attempt
even before a replacement epoch exists; only a new connection resets retirement.
Late same-attempt authentication cannot revive the connected flag. Authentication
refusal immediately fences/cancels its captured attempt.

Command registration and capture of socket/epoch remain atomic; cancellation never
selects whichever socket is current later. New commands reject retired attempts.
Original private result futures remain correlated to their request identities;
ordinary accepted receipts are not invented from a replacement generation.

## Current verification

Root serialized the HA formatter, all five selected HA classes and global
formatCheck. The current invocation passed in 12 seconds (coordinator log
`ha-02-v2-focused-check.log`). I independently inspected current JUnit XML:

| Class | Tests | Failures/errors/skips |
| --- | ---: | --- |
| HomeAssistantResourceContractsTest | 10 | 0/0/0 |
| HomeAssistantResourceCatalogTest | 8 | 0/0/0 |
| HomeAssistantNativeResourcesTest | 9 | 0/0/0 |
| HomeAssistantAdapterTest | 10 | 0/0/0 |
| HomeAssistantObservationLifecycleTest | 9 | 0/0/0 |
| **Total** | **46** | **0/0/0** |

The nine new lifecycle regressions use latches, controlled monitor blocking and
an injected synchronous WebSocket factory: blocked Gap observer with an expired
resource read/concurrent unconfirmed action; blocked state observer with independent
snapshot/timeout and ordered Gap; an old state callback unable to overwrite the
replacement snapshot/version; old-generation and same-retired-attempt authentication
unable to revive connection state; FIFO capacity/reserved-Gap retention; callback
expiry with retained physical ownership and no replay; finite cleanup of an
uncooperative callback; and uncertain observer failure that cannot retry or run
around the retained Gap. Authentication tests inspect the owning connected flag
so a retired-status projection cannot conceal an illegal late write.

The prior 37 cases still cover contract/legacy compatibility, policy-first paging,
batching/order, metadata/state revision separation, fake-clock age, policy/credential
isolation, reconnect and late-result fences, bounded concurrent fills, actual local
WebSocket capability probes, explicit attribute-policy opt-in, private metadata/state
events, unsupported area relationships, shared probe deadlines and subscription
setup fencing. These are controlled protocol fixtures, not live device validation.
Root's server/TypeScript checks are separate branch evidence, not HA closure.

## Remaining limits

Every resource operation inherits the caller-owned monotonic OperationBudget;
HA-03 composition owns its configurable two-second default. No nested timer resets
or mutation replay were added. Existing manual commands retain their configured
timeout (the current factory supplies 20 seconds), so this does not claim universal
two-second enforcement. Native frames retain the existing 1 MiB ceiling and larger
full-HA collection responses fail rather than publish partial evidence. There are
at most 64 pending native commands, 128 binding source views and five metadata
subscription types. HA has no per-command cancellation; closing a shared connection
makes other possibly submitted actions unconfirmed and never proves a physical
effect stopped.

No HA-02 database/model/live suite, installation, device mutation, credential read
or HA restart occurred. No Java source changed after the passing formatted-source
checks. `git diff --check` passes. Independent reviewer disposition of R1/R2 and an
exact new candidate manifest remain required.

## Exact current source hashes

The report is hashed externally by the coordinator to avoid self-reference.

```json
{
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java": "d350421b64be10ae30039657440bbcfbfb5cfea8dbe2df436a93564d929565cf",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantWire.java": "2bf3d3d5c19c4e9da7ca2cd911b8b30538c0845c89d246e85b6ab1084ebcd34e",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantDiscoveryPolicy.java": "05828ef6f9dd4e4168fa03ff4852117a5d0111d7057f32ef6809a529f7f769be",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceSource.java": "17940d584e64aaa0fd2940bcf8a6be5c0e22f1bfec28edf3163faa9ce0f36623",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalog.java": "8349e14cdfa47752ab438fe8d1c7ece491365faa1aa1bc204e48386223f8b452",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantObservations.java": "822d56f25881eef7092d058f251884d1aad10a510f324fe6da1da7d0da97860c",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalogTest.java": "465be766d24dbb571054c4eb1da859cc79daac238383fd44443823d37352c23c",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantNativeResourcesTest.java": "15ed225e175e9b64c9afce076d26c4bcd7db1b7654ab43a8c61f92f07f18491e",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantObservationLifecycleTest.java": "71f0565fd71d6ff28e3e72d9e789c0e40b03a1549ce780980271a7ca9e88d58c",
  "integrations/home-assistant/RESOURCE-CONTRACTS.md": "dd64f459d73621ea697a40c571cb232d77ad6dc1dd76ea22b4ed488764e88882"
}
```
