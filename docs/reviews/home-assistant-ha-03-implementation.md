# HA-03 implementation evidence

Node: `HA-03`. Implementer: `/root/ha_provider_implementer`.
Date: 17 November 2026. Base: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e`
plus the existing delivery working tree. Status: **V2 READY FOR INDEPENDENT REVIEW**;
independent reapproval and final integrated checks remain required.

## Delivered public boundary

Six new HA-local production classes supply a public Java composition API:
`HomeAssistantToolHandlers`, `HomeAssistantReadTools`,
`HomeAssistantProviderJournal`, `HomeAssistantToolJournal`,
`HomeAssistantToolTransport` and `HomeAssistantToolProvider`. They consume the
accepted resource interfaces and an already configured HA connection. Production
code imports only integration/runtime, public protocol and SDK capabilities.
No core/server, schema, SDK operation or action execution was added. Existing
manual aliases retain their executable path; a new packaged executable is later
delivery scope. Operator composition/grant instructions are in
[TOOL-PROVIDER.md](../../integrations/home-assistant/TOOL-PROVIDER.md).

Four prefixed named tools search, describe, read and list configured action offers.
Closed SDK schemas decode into typed resource selections before domain work. Exact
binding/opaque credential generation/policy/epoch/version tuples fence evidence.
Counters use decimal strings. Each read performs one resource batch, projects
only requested already-permitted attributes and preserves provenance. Tool JSON
results are bounded at 15,000 UTF-16 units to reserve SDK result-envelope space;
oversized observations reject without truncating, summarizing or refetching.

Installation declares provider account/prefix/lease separately from agent grants.
Discovery grants nothing. The owning server authorizes each invocation; the SDK
independently validates publisher, binding, immutable offered schema and input
before journal intent/handler execution. Fixtures do not claim a live server grant
check. Origins, credentials, binding, prefix, policy and private journal directory
are explicit configuration, with no local-server fallback.

## Deadline and physical ownership

One provider pass shares a configurable two-second default monotonic budget across
SDK admission/reads/ACK/publication, journal work and resource calls. Async public
Relay requests receive the remaining budget, NON_BLOCKING dispatch and one local
cancellation signal. An admitted invocation's earlier deadline caps that same
budget through intent, ACK, resource work and result publication. No retry resets
the pass or invocation deadline. Recovery is bounded inspection of retained work.

Root separately authorized only `OperationBudget.capped(Duration)` and its focused
HA-01 contract regression. Those two files remain frozen for independent HA-01
reapproval. The first canonical HA-03 run compiled production/server sources and
ran 35 focused cases: 34 passed and one fixture-clock assertion failed. The test
had frozen its injected clock before startup but created a deadline from a later
real clock. It now derives both sides from the injected clock; production cap
semantics were unchanged. The canonical rerun passed all 40 focused cases.

A worker and bounded cancellation owner retain one physical pass until both local
work and interruption settle. Thread.interrupt can itself invoke blocking NIO
cleanup, so an expired caller never runs it synchronously. Caller timeout does
not release a journal/worker around an active callback. Cleanup has one separate
finite deadline across both owners; incomplete cleanup retains exclusive journal
ownership. SDK cancellation detaches local interest, not remote execution or an
SDK-owned transmission. The host borrows its underlying SDK/HA connections.

## Durable publication and bounded retirement

The private journal exclusively locks its directory and commits strict typed
snapshots by atomic replacement plus file/directory fsync. Uncertain writes latch
failure until reopen. Exact SDK configuration and private credential/configuration
fingerprints prevent schema, account, lease, policy or credential adoption of old
receipts. Only the credential/configuration hash is persisted; raw credentials
never enter saved snapshots, logs or model output.

Catalogue renewal/withdrawal persists the exact fresh UUID/time/text before send.
Lost replies reconcile the original retained event; absence never permits replay.
Local availability uses the broker publication time and finite configured lease.
Java SDK generic IO refusals lack a definitive non-submission disposition; only a
timely structured NOT_SUBMITTED proof can clear an unsent intent. Other/late
failures remain pending. This public gap needs no core change for safe behavior.

SDK receipts retain immutable EXECUTING/READY/PUBLISHING/DONE transitions. Restarted
EXECUTING becomes UNKNOWN without repeating the handler. PUBLISHING requires exact
read-only result reconciliation. Snapshot version 2 retains up to 32 incomplete,
unknown or unproved receipts plus 128 topic fences within 16 MiB. Successful
long-lived operation does not consume a permanent receipt slot per read:

1. Capture the trusted broker topic, cursor generation and ordered event position.
   The SDK validates the immutable invocation before intent is saved. Delivery
   evidence commits before the exact batch is acknowledged.
2. Validate the ACK's project/topic/group/batch and issued boundary against the
   captured consumer/batch/fence, then commit known cursor advancement.
3. DONE separately confirms exact result publication. Only COMPLETED/REJECTED with
   known ACK and non-null matching generation can retire. UNKNOWN never retires.
4. Remove a settled full receipt and advance its same-generation replay watermark
   in one atomic snapshot. Retired old positions refuse before another intent or
   handler, while full exceptional identities remain available below the watermark.

The proof uses the public ordered, at-least-once Relay contract and the durable
tool invocation ledger documented in [Relay](../relay.md) and
[Relay tools](../relay-tools.md#durable-lifecycle). SDK `Provider.poll` persists
intent before ACK/handler and saves DONE only after validated publication;
`reconcile` validates the original result identity/publisher/parent/time/text.
The owning invocation repository corroborates that request publication occurs
only on the first immutable invocation insert; retained invocation identities
outlive broker retention. No receipt is discarded on age, caller timeout or
missing evidence. Cursor generation change refuses reuse. Older Plowshare peers
without generation cannot retire safely and require update for sustained named
service; this is independent of the HA version. The host does not reset/delete
broker groups. Unreleased snapshot version 1 is refused rather than migrated with
invented proof.

## Verification and limits

The v1 independent review requested one evidence correction, HA03-R1: the shared
fixture reused a constant invocation UUID while advancing broker positions. That
does not represent normal immutable invocation traffic. The revision changes
only `HomeAssistantToolProviderTest`: every new logical request in sustained,
generation and post-reconciliation scenarios gets a distinct UUID and logical
call identity. Deliberate duplicate scenarios retain their exact original
requests. The 80-request/two-lifetime test now asserts 80 distinct invocation and
result identities, COMPLETED results and empty full receipts after every read;
it replays the saved first UUID at position 1 before shutdown, immediately after
restart and after the second stream, refusing before another handler. No
production, shared fixture or HA-01 contract file changed for R1. Private strict
compilation and 38 diagnostic cases pass. The canonical R1 rerun passed all 40
focused JUnit cases; independent approval remains the reviewer's decision.

Private `javac -Xlint:all -Werror` compilation passed for the six new production
classes, authorized HA-01 cap and five focused test classes. A direct diagnostic
harness passed 38 cases, including 80 settled reads across restart, replay and
generation fencing, wrong/lost ACK proof, result reconciliation, unknown-capacity
refusal, blocking journal/interruptor ownership and schema/credential isolation.
This diagnostic is not the Gradle/JUnit acceptance gate. Root subsequently ran the
canonical R1 Gradle selection without skipped production/server compilation:
BUILD SUCCESSFUL in ten seconds, 24 tasks (3 executed, 21 up-to-date), recorded in
`/private/tmp/ha03-v2-focused-check.log`. I independently inspected the current
R1 JUnit XML on this formatted source:

| Class | Tests | Failures/errors/skips |
| --- | ---: | --- |
| HomeAssistantReadToolsTest | 5 | 0/0/0 |
| HomeAssistantToolProviderTest | 17 | 0/0/0 |
| HomeAssistantToolJournalTest | 5 | 0/0/0 |
| HomeAssistantToolTransportTest | 2 | 0/0/0 |
| HomeAssistantResourceContractsTest | 11 | 0/0/0 |

The provider XML timestamp is `2026-11-17T01:07:40`, the transport timestamp is
`01:07:47`, and the remaining three are `01:07:38`. Root serializes formatting
and all Gradle runs. I also inspected its separate `./gradlew formatCheck` log:
PASS in four seconds, 43 tasks (3 executed, 40 up-to-date), recorded in
`/private/tmp/ha03-v2-format-check.log`. Earlier combined-check failures do not
become successful claims: an initial server fixture compilation issue was fixed;
the later repository-wide check failed a stale generated legacy CLI audit after
the separate Ex operation additions. Root owns that regeneration and rerun.
Passing these focused cases and formatting do not substitute for independent
review or the final repository-wide check. `git diff --check` also passes.

No database, model, live HA, physical device, installation or deployment work ran.
Fake public transports exercise the actual SDK provider and temporary real local
journals. Journal startup has bounded bytes and a nonblocking ownership lock; a
kernel filesystem stall cannot be given an absolute wall-clock guarantee and
requires supervised local storage. Independent review and final integrated checks
remain outstanding.

## Candidate source hashes

The report itself is hashed by the coordinator. The two HA-01 cap files are
listed for provenance and require their separate independent contract approval.

```json
{
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResources.java": "b8d8671e10b78a27665b6bb9aa5f683f6fd62f56b8868ea9c587cf0bdfb3ed56",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolHandlers.java": "bea32d08c93c6a77e0e46e333fa454b1ba3f9d72bd7deca97f1772b6db749918",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantReadTools.java": "96955c0d5084570e0b62e58355ae0845959f5049974db3fcde71ce94121e4619",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderJournal.java": "6b6762a050d34f91cf2daa180dd724279b807b57065ab27cb1e2f83cac8ff3a6",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolJournal.java": "843b2e15f2ba84e2939047166a2c134919427dc7dd72da175da6d6b0b4df1378",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolTransport.java": "e05acd7aee44abcea06f63629d48a2bf1411afc19641a28a538213f0acd9aa02",
  "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolProvider.java": "438f44f75e3f9a2419e5dbd835302d0c89efa15263b1d5494d997bab7b2b11e6",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantReadToolsTest.java": "bfca22bf16e06b328df2e48561aa362f54cd8b0940e13937d3b533feb523a70f",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolProviderTest.java": "2cc87ee7153559dbabf9c8f8ddf9feff0b363fa1291a22230477b9a4c3cc9907",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolJournalTest.java": "219deafb9a791287bcbf453bc92abe6c5bbb2f2ab3fe8cb441196740d595d961",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolTransportTest.java": "a4f8cb1855585025360b2530a86304eaa69ce32c3b5a9d2c0577c59e1a32f236",
  "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceContractsTest.java": "beb90d8c2225ca5dc3b2cf02878133a9a854e805c24039e1fd525e90009c99f9",
  "integrations/home-assistant/TOOL-PROVIDER.md": "7df8fc5495c9b1218cab89fe7316cfc5cdde26bfb4c5fb51a71a29f66db957dc"
}
```
