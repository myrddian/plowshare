# HA-05 shared SDK gateway implementation — v2

Implementer: `/root/ha_gateway_sdk_implementer`.
Status: v2 implementation and coordinator checks passed; ready for independent v2 review. Not yet accepted.
No build, runtime test, package installation or live action was run by the
implementer. This report does not establish node completion or runtime enablement.

## Public contract and scope

The implementation changes only the HA adapter's Python gateway/models, a new
`requests.py` typed boundary, and its gateway/SDK fixture tests. No Plowshare
core, SDK, shared protocol, schema or Java source is changed. Component setup,
configuration, entity lifecycle, installation packaging and publication guards
remain owned by later deliverables and are unchanged by this implementation.

`GatewayPort` exposes `converse(ConversationRequest)` and `generate(TaskRequest)`.
Both use immutable `HaRequestContext` and return `GatewayResult`. Only
`status == "completed"` carries generated `.text`; all non-completions carry
safe `.message` diagnostics. The existing `ask(key, text) -> Answer` remains a
Conversation presentation wrapper. AI Task consumers must use `generate`, never
interpret the legacy pending sentence as completed data.

Prepared HA system text, language and current input are deterministically
serialized into the existing public SDK `task` string. Source text is preserved
without whitespace/Unicode normalization or cache optimization. The envelope
explicitly says task/user semantics. It does not promise a native system role or
special compaction retention, change the selected agent, or confer tool grants.
Typed user/device/satellite provenance partitions identity but is excluded from
model prose. Unsupported input and resource-bound violations fail before SDK
mutation. Generated answer prose is returned intact.

## Namespace, routing and old state

New work requires an explicit constructor `profile_id` combining stable
installation/config-entry identity and the selected profile. There is no
universal submission namespace fallback. Omitting it permits the read-only
validation method only. Settings retain the explicit server origin, credential,
project and selected agent; request DTOs cannot choose those routing values.

The public source is lower-case `ha.<kind>.<profile digest>`, and the visible
external ID hashes request kind, supplied caller-partitioned key and HA identity.
Conversation and task sources are distinct. Stable local lookup is independent
of mutable connection/agent settings. A retained routing digest binds origin,
credential identity, project, agent, profile and kind. It contains no plaintext
credential. A changed credential is conservatively treated as a changed route:
no public API is invented to assert that two tokens represent the same account.
Old receipts cannot silently resume or submit on the changed route.

A bounded retained-correlation scan prevents changing profile lookup from
bypassing old work. Original four-field state rows decode as unbound legacy
records and remain quarantined, including idle records with old history. Neither
typed intake nor `ask` imports their internal ID into a new alias or creates a
replacement run. Explicit operator migration/inspection or a new HA conversation
is required; no history-continuation claim is made for a newly created alias.

New state stores only internal conversation/job receipts, phase/cursor and routing
hashes. The core owns external-ID mapping. `preparing` denotes pre-mutation
connect/read work and restarts idle; `sending` restarts uncertain. Unknown and
pending rows, and all unbound legacy rows, are never evicted. At the bounded
journal limit, only known external idle or completed records can make room.

Task completion retains the exact final job receipt, including a final job
produced by correlated Relay follow-up. Re-reading a retained completion uses
that job's public status rather than requiring old trajectory to survive archive
or compaction. Terminal receipt retention is bounded, not an indefinite public
idempotency API. HA Task callers must mint a fresh internal invocation key for
each new HA invocation and must not expose arbitrary old-key replay; HA supplies
no durable caller task retry key. Identical instructions in distinct invocations
are distinct work. Pending/uncertain identities remain fenced.

## Mutation, recovery and physical ownership

The durable save callback must commit intent or raise within its owning budget.
The current HA `Store.async_save` is not claimed to provide that guarantee:
setup/storage owners must supply verified persistence before runtime enablement.
A shared writer must serialize snapshot commits across profile gateways. The
gateway saves intent before alias-open and run transmission, retains accepted
receipts before polling, and never reconstructs success from interim prose.

New sessions call `conversation.open.ex` once to establish the alias, then
`agent.run.ex` resolves that existing selector inside the normal operation. No
client alias-resolve RPC or direct core access is added. Existing retained jobs
are checked instead of submitting a new utterance. Only completed `ANSWERED`
output is data; Relay publication acknowledgements, reasoning, tool previews and
failed/capped partial prose are not completed results.

A negative Ex reply alone is not no-effect proof. Archive unavailability and
post-handler authority fences may follow admission/commit. Sending state therefore
becomes uncertain after ambiguous refusals, including `ARCHIVE_UNAVAILABLE` and
`FORBIDDEN`. Only proven pre-handler `CAPACITY_EXHAUSTED` or the SDK's
`Delivery.NOT_SUBMITTED` clears sending. No automatic mutation retry is performed.
Cancellation and caller wait expiry preserve receipt/uncertainty and do not cancel
accepted server work. Diagnostics do not disclose exception payloads.

Admission is per qualified request identity, with four physical SDK owners per
gateway. Unrelated invocations can proceed concurrently. Close has a separate
finite wait; delayed cleanup retains its slot, and failed/cancelled close is
quarantined rather than treated as physical settlement. `async_close() -> bool`
stops intake and waits finitely without cancelling accepted calls. A false result
requires the HA lifecycle owner to retain the gateway and refuse disposal/reload.
Each control/storage read has its existing explicit finite budget; the separate
response wait is not an invented two-second job lifetime.

`validate(required_agents=())` validates either the selected agent or a bounded
explicit tuple of enabled profile agents against one public project catalogue
snapshot, without opening aliases or model work.

## Pre-freeze independent findings addressed

The independent reviewer identified these issues while implementation was in
progress; author corrections and owning cases were added before candidate freeze:

- Failed/cancelled SDK close must retain physical ownership.
- A completed correlated task must retain its final job receipt.
- Malformed stored mapping types must raise the codec's safe `ValueError`.
- Ambiguous negative Ex replies must not reset sending and permit replacement.

The real segmented SDK fixture now uses Open-Ex `OK`, Run-Ex `ACCEPTED`, a valid
`cnv_` conversation result shape, the ordinary envelope task field, lost-ack
no-replay and an actual ambiguous Ex negative reply. It does not weaken SDK result
validation or claim actual server authorization, model execution or devices.

## Coordinator verification

The coordinator ran the explicit Assist check script using its isolated Python
3.14 / Home Assistant 2026.9.4 environment. Ruff lint, Ruff format check and all
48 isolated Assist tests passed; unittest reported 0.926 seconds. The cases
include the actual segmented public SDK Ex round trip, lost acknowledgement and
valid `ARCHIVE_UNAVAILABLE` reply, existing Relay/restart behavior, typed gateway
lifecycle/identity cases and actual HA prototype guard tests. No model provider,
live HA instance, device, PostgreSQL or deployment operation was involved.

An initial check stopped at two Ruff I001 import-order findings before running
the suite. The coordinator applied only mechanical import sorting/formatting,
then reran the complete check successfully. No test was excluded or suppressed.
The successful log is `/private/tmp/ha-05-check-v2.log`, SHA-256
`e2d37c4fec4fcfd1013c486098e56b32eedfa7ceb83fbbae28acd281492d2475`.

The v1 formatted source passed those checks. Following the narrow interface-only
addition described below, the coordinator ran the complete Assist check again:
Ruff lint, Ruff format check and all 48 cases passed in 0.921 seconds. The current
log is `/private/tmp/ha-05-check-v3.log`, SHA-256
`74f8e999db47e98e4b91d6b8977245b2c523acb45a69bf5dcdf315a91ef7d377`.
No test was excluded. Source remains frozen for independent v2 review. Successful
lower-level tests do not enable component intake or packaging; the unchanged
runtime publication guards remain in place until HA-11's integrated work.
Subsequent entity/setup owners must implement verified durable persistence and
retain unsettled gateways through unload as specified above. Repository-wide
and integrated whole-component checks belong to the later validation node.

### Formatted source hashes

| Owned file | SHA-256 |
| --- | --- |
| `integrations/home-assistant/assist/custom_components/plowshare/agent.py` | `0bea26471ab9511271a26c6a6cb6b585205e73889dc55c02da583b264cf7171c` |
| `integrations/home-assistant/assist/custom_components/plowshare/models.py` | `1289cc93024ba4d05fe0e86db854afefabb561e79e61e202b4eb48dc2389262f` |
| `integrations/home-assistant/assist/custom_components/plowshare/requests.py` | `15a6cd457b02c7236e52d77a56a7cd47357b91409524fa569e2b50ed8ba3ba23` |
| `integrations/home-assistant/assist/tests/test_agent.py` | `0e11cd5d5c9f0d82760d43457f2d24fc33234f44922337c6569597f3e43ce92c` |
| `integrations/home-assistant/assist/tests/test_gateway.py` | `a3484daa56e8d8e02af8acd990c0e16b3fe156b8206cbd224803342f7b6f0e3a` |
| `integrations/home-assistant/assist/tests/test_socket.py` | `93e0cef063178106cf12c3fd7ea08b92d49434c81f9bbbaa7c548852bcde8c38` |

## Shared runtime interface handoff for v2

At the coordinator's request, `agent.py` now declares the adapter-only
`@runtime_checkable ProfileRuntime` Protocol. Its read-only `conversation` and
`task` properties expose `GatewayPort | None`; disabled profiles are absent.
`async_close() -> bool` retains the existing unsettled-owner lifecycle contract.
HA-07 owns the concrete runtime/journal, while HA-06 may consume and test this
structural interface without importing its sibling's unreviewed implementation.
No gateway logic, SDK/core API, state format or authority behavior changed.

No tests or builds were run by the implementer after this source addition.
The coordinator recheck above covers the changed source. The final `agent.py`
SHA-256 is `0bea26471ab9511271a26c6a6cb6b585205e73889dc55c02da583b264cf7171c`.
The original v1 candidate, implementation report and review remain unchanged
historical exact-source evidence. Only this new v2 note is updated for the new
interface and current verification.
