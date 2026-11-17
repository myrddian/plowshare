# HA-02 implementation evidence

Node: `HA-02`. Implementer: `/root/ha_reads_implementer`.
Date: 17 November 2026. Base: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e` plus the existing dirty delivery tree.
Status: **READY FOR INDEPENDENT REVIEW**, not accepted, merged, deployed or live validated.

## Delivered boundary

`HomeAssistantResourceSource` is the typed native-connection capability;
`HomeAssistantResourceCatalog` implements the accepted `ResourceDiscovery` and
`ResourceReader` interfaces. `HomeAssistantAdapter.resourceSource` shares its
actual configured HA WebSocket rather than creating a second connection/runtime.
Vendor JSON stays inside Adapter/Wire; the catalogue consumes typed immutable
metadata/state snapshots. No core/shared payload, SDK operation or provider was
added. Named tool publication remains HA-03.

The only HA-01 contract-file change is `DiscoveryPolicy.references()`: it returns
an immutable configured reference set, avoiding a second alias inventory or
re-resolving settings in consumers. Root coordinates exact-current HA-01 approval
before HA-02 closure. Other existing changes are confined to Adapter/Wire; the
remaining authored files and hashes appear below.

## Policy, freshness and bounds

- The host supplies an explicit trusted resource policy, never a model/tool-request
  policy. `fromSettings` preserves existing aliases, selected attributes and
  alias-only metadata. A separate explicit resource policy may select additional
  bounded attributes for the same configured entity targets; this does not silently
  change the manual alias policy. Selected nested values become unsupported field
  names without payloads. The legacy manual scalar codec still refuses them.
- One catalogue caches at most one already projected metadata snapshot (256
  configured resources). Filtering precedes search/paging/counts, and denied and
  missing references have equivalent absence behavior. Binding plus opaque
  credential generation, policy revision and connection epoch fence reuse. A new
  adapter/credential generation never adopts old cached evidence.
- Native registry events are internal invalidation hints. The public metadata
  revision changes only if the permitted descriptor/capability projection changes;
  private events can trigger bounded revalidation without resetting a page or
  publishing event counters. Selected-resource state revisions are separate and
  do not cause metadata refresh. This does not eliminate all latency/availability
  side channels of revalidation.
- Positive configured metadata age is capped at five minutes. Refresh after age
  or invalidation may prove a public revision still current; changed metadata,
  policy or epoch refuses an old cursor rather than restarting it. Concurrent
  fills share one load, but each waiter retains its original finite budget.
- Metadata makes collection-level entity/device/area/service probes; names come
  from the entity registry or remain aliases when unavailable. Metadata never
  fetches/discards state values. Every read issues one fresh get_states batch,
  preserving requested order and explicit missing/unavailable/invalid states;
  no per-entity network reads or metadata-cache state values. Native frames retain
  the existing 1 MiB ceiling; larger full-HA collection responses fail rather than
  publish partial selected evidence.
- Unsupported/denied registry capabilities are explicit. Area filtering requires
  explicit relationship disclosure plus available entity/device/area registries,
  otherwise it fails instead of claiming an empty area. Discovery grants no actions.

## Deadline and recovery decisions

The accepted interfaces require the caller's `OperationBudget`. New native
commands inherit its remaining monotonic time through command-slot admission,
transmission, every probe and reply/processing. HA-03 composition owns the
configurable two-second default entry budget; HA-02 does not add unused wrappers
that would reset nested budgets. Existing manual operations retain their owning
configured timeout (the current factory supplies 20 seconds); this is not a claim
of universal two-second enforcement. Connect now shares one configured budget
across authentication, subscription and startup snapshot.

There are at most 64 pending native commands, 128 binding source views and five
metadata event types. Metadata subscriptions are explicit, not wildcard/admin;
known unsupported/denied subscriptions use bounded-age metadata. Partial setup
tracks settled event types and fences unexpected/unconfirmed setup by closing its
captured attempt, so retries cannot accumulate subscriptions on that socket.

HA offers no per-command cancellation. Expiry/interruption cancels the captured
WebSocket attempt and fences possibly submitted commands; concurrent actions
become UNKNOWN and are never replayed. Registration and capture of socket/epoch
are atomic. A stale attempt cannot select a replacement socket during abort.
Cancellation is local connection cleanup, not proof that remote physical effects
stopped; existing journal/receipt owners retain action uncertainty. Reconnect
changes epoch and requires a fresh request/revision.

## Verification on this source

Root serialized global formatting, the focused HA/server checks and global
formatCheck; the combined invocation completed successfully in 12 seconds.
I independently inspected current JUnit XML for all four HA classes:

| Class | Tests | Failures/errors/skips |
| --- | ---: | --- |
| HomeAssistantResourceContractsTest | 10 | 0/0/0 |
| HomeAssistantResourceCatalogTest | 8 | 0/0/0 |
| HomeAssistantNativeResourcesTest | 9 | 0/0/0 |
| HomeAssistantAdapterTest | 10 | 0/0/0 |

The 17 new cases cover policy-first paging, batch/order and no N+1, state/metadata
revision separation, fake-clock maximum age, credential/policy replacement,
reconnect/late-generation fences, bounded concurrent metadata waits, actual native
registry probes, explicit additional attribute configuration, unsupported nested
values, legacy scalar refusal, private metadata/state event isolation, denied area
relationships, finite multi-command deadlines, partial subscription fencing,
concurrent action/read uncertainty without replay, and stale-attempt cancellation.
The stale-attempt race uses a captured old socket mock against an actual current
native connection for deterministic epoch isolation; other native cases use real
local WebSocket fixtures. This is not a probabilistic race-stress claim.

Root also reported 26 passing selected server tests in the same invocation; those
belong to CORE-02 evidence, not HA feature acceptance. No HA-02 PostgreSQL/model/live
suite, installation, device mutation, HA restart or credential read occurred.
`git diff --check` passes. Independent reviewer approval and the final integrated
checks remain required; these fixtures do not establish deployed tools or devices.

## Candidate source hashes

The report itself is hashed externally by the coordinator, avoiding self-reference.

```json
{
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java": "4254a76094ea29374a0832fe29960e6e9717f16b873d6f1f5158797327101fb9",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantWire.java": "2bf3d3d5c19c4e9da7ca2cd911b8b30538c0845c89d246e85b6ab1084ebcd34e",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantDiscoveryPolicy.java": "05828ef6f9dd4e4168fa03ff4852117a5d0111d7057f32ef6809a529f7f769be",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceSource.java": "17940d584e64aaa0fd2940bcf8a6be5c0e22f1bfec28edf3163faa9ce0f36623",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalog.java": "8349e14cdfa47752ab438fe8d1c7ece491365faa1aa1bc204e48386223f8b452",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalogTest.java": "465be766d24dbb571054c4eb1da859cc79daac238383fd44443823d37352c23c",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantNativeResourcesTest.java": "15ed225e175e9b64c9afce076d26c4bcd7db1b7654ab43a8c61f92f07f18491e",
  "integrations/home-assistant/RESOURCE-CONTRACTS.md": "9a71ac880e353d1856cbdc5a770372ae311c853ed8360a4a688b2aeae0b5af91"
}
```
