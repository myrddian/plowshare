# Home Assistant baseline and capability audit

Node: `AUDIT-01`. Implementer: `/root/ha_audit_implementer`.
Date: 17 November 2026. Candidate base: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e` plus dirty files below.
Status: ready for independent review; this audit does not accept any implementation node.

## Starting tree and evidence scope

The tracked starting changes are `.agents/guides/integrations.md`,
`integrations/home-assistant/README.md` and
`integrations/home-assistant/examples/application/agents/house_coordinator.md`
(23 added lines across those three files at audit time). The Assist directory,
design, delivery graph/ledger and PLAN-00 review artifacts are untracked starting
work. They must be preserved; HEAD alone cannot identify this baseline.
The live ledger can change during coordination; it is excluded from the source
hash map below. Root owns a frozen candidate/ledger manifest for review.

This audit inspected local owning source and the installed HA distribution.
No live HA, Plowshare, provider, model, device, DNS or database request was made.
No credentials were read. No runtime code was changed. Existing deployment claims
and old test counts are historical evidence, not current acceptance.

## What exists now

| Capability | Actual owner and current behavior | Remaining boundary |
| --- | --- | --- |
| HA reads/actions | `HomeAssistantAdapter.java:305` validates only `states.read` and `actions.execute`; reads one `get_states` batch and projects permitted aliases; actions send one fixed `call_service`. | No search/describe/list-actions interfaces or SDK named tool provider. |
| Policy and values | `HomeAssistantSettings.java:17` defines explicit origins, selected aliases/attributes, fixed service/targets and primitive parameter policy. | No automatic conversion of arbitrary HA service selectors; metadata discovery is absent. |
| Reading freshness | `HomeAssistantAdapter.java:101` subscribes before snapshot; buffered deltas reconcile by time, epochs fence reconnect reads; `lost` marks cached state stale. `HomeAssistantWire.java` sanitizes selected primitives/context. | No device/area/service catalogue, bounded search pagination or metadata revision policy. |
| Action uncertainty | `HomeAssistantAdapter.java:320` distinguishes refusal, acknowledgment and unconfirmed delivery; no automatic resend. Successful receipt is not physical verification. | Broader provider/schema-revision admission and per-target action profiles remain planned. |
| Adapter contract | `IntegrationAdapter.java` owns start/validate/execute/snapshot/maintain/close with typed IntegrationPayload arguments/results. | Keep HA discovery contracts local; no new shared payload variant is required merely to add discovery. |
| Runtime | `IntegrationRuntime.java` and runtime README own journaled Relay requests, threshold routing, return publication and feedback suppression. | A named dynamic provider is distinct from this Relay envelope transport. |
| Assist companion | `assist/custom_components/plowshare/conversation.py:46` hashes HA conversation/caller/device/satellite, sends only `user_input.text`, appends returned assistant content and returns HA's conversation ID. | Does not call `chat_log.async_provide_llm_data`, forward rendered system content/language/context, or supply AI Task. CONTROL is currently unconditional. |
| SDK gateway | `assist/.../agent.py` opens a public SDK conversation once, submits normal AgentRunRequest, polls durable job/trajectory and follows correlated Relay reply jobs. | Local mapping is not the proposed generic core alias map. No OpenEx/RunEx operations exist in current SDK DTOs. |
| Recovery | `assist/.../models.py` stores bounded correlation/job/phase/cursor only; sending becomes uncertain on restart; unknown work is not replayed; pending entries are not evicted. `agent.py` bounds request/response/cleanup and foreground admission. | Migrating correlation to core must preserve retained pending/unknown jobs. Next utterance while pending currently reconciles old work rather than submitting it. |
| Packaging | `assist/scripts/package.py` copies unchanged public Python SDK source/schema resources plus license into a private vendor namespace; manifest dependencies must match SDK. | Prototype package is not final install/release/live acceptance. No AI Task platform entry. |

The adapter uses explicit HA and Plowshare credentials independently. A configured
HA alias or exposed HA entity cannot grant Plowshare tools, membership or authority.
See the adapter README's Mapping capabilities/Application sections and
`docs/tool-scopes.md`, `docs/relay-tools.md` for existing dynamic provider ownership.
The runtime README/adapter README document that fresh native exports currently
support TURN/MESSAGE, not an in-progress ORCHESTRATION source awaiting fresh export.
This audit did not reverify that server-side restriction end to end.

## HA version and semantic compatibility

The isolated check environment reports Python 3.14, `homeassistant==2026.9.4`,
`websockets==17.4`, `ruff==0.16.10`, matching the current check project's HA pin.
This is installed-package evidence, not a new read of the running house instance.
No upgrade is needed merely to implement the baseline Conversation entry point.

Installed HA `components/conversation/entity.py` defines the configured reusable
ConversationEntity and `_async_handle_message(user_input, chat_log)`;
`helpers/chat_session.py` owns session IDs/inactivity cleanup. The existing actual
HA test checks returned ID and speech. Agent definition lifetime and per-chat
conversation lifetime are separate; HA expiry does not cancel a durable Plowshare job.

`components/ai_task/entity.py:63` prepares a ChatLog through
`async_provide_llm_data`, supplies the default task system prompt and adds task
instructions as UserContent. `ai_task/task.py:113` uses a new ChatSession for each
`async_generate_data` call. `ai_task/const.py:38` declares GENERATE_DATA,
SUPPORT_ATTACHMENTS and GENERATE_IMAGE; this baseline has no evaluation flag.
The initial supported delivery should declare data generation only; attachments,
images and evaluation are outside scope. Structured output still needs an explicit
supported schema/selector subset and real result validation.

`conversation/chat_log.py:673` renders user/default template, appends selected
LLM API prompt, date/time unless a GetDateTime tool exists, then extra prompt,
and replaces SystemContent at index zero. Thus HA prompt variations are real and
must be preserved, not normalized to improve caching. This source already has
some cache-aware seams (live context/date-time tools); a blanket claim that all
HA context is necessarily volatile is unsupported.

Current `sdk/python/src/plowshare/contracts.py:6409` AgentRunRequest has task,
agent, conversation/project/session, images and limit fields; it has no arbitrary
system-role/messages field. ConversationOpenRequest at line 6757 has home/budget/
turn fields and no external selector. `Runs.java:418` remains normal run admission;
`ConversationOpenHandler.java:84` checks session/work and opens before log.open.
`LogStages.java:21` and `plowshare-hooks/index.ts:179` confirm log.open happens
after commit and can only add opening text; no pre-selection mapping hook exists.
`JobRuntime.java:6582` hoists system messages before provider dispatch.
Its prompt.pre addition path around line 3150 appends ChatMessage.user, so that
hook also does not preserve an incoming HA system role.

**Public capability gate remains unresolved:** placing HA prompt text inside task
would preserve characters but not native system-role semantics. Neither Ex ID
facades nor a hook supplies that missing role API. FIDELITY-01 must independently
verify an adequate public path or identify a separately scoped platform successor.
This audit does not satisfy `prompt_public_path_verified`, nor authorize arbitrary
role API implementation. Prefix impact must be measured on original HA rendering;
no actual model cache hit rate or performance result has been established here.

## Supported initial discovery/action subset and transport decision

Use native HA WebSocket as the primary package transport for selected state reads,
registry/service metadata capability probes and state_changed continuity. Retain
existing explicit alias policies and configured primitive action profiles while
adding local typed search/describe/read/action interfaces. Start with exact entity
references and bounded text/domain filters over the permitted catalogue; area/device
filters become available only when explicitly supported registry metadata has been
probed and policy-filtered. A state listing alone does not prove relationships.
Missing metadata must remain an explicit unsupported capability, not an empty
claim that broad area execution is safe.

The reviewed executable parameter subset is the existing string/number/integer/
boolean contract, configured enum/numeric/string bounds, fixed targets and named
required fields (`HomeAssistantSettings.java:82`). Initially accept only those
explicit profiles. HA UI selector objects, nested dictionaries/arrays, arbitrary
target selectors, dynamically broadened areas and service response payloads are
unsupported for execution until a typed translator/profile is reviewed. The current
`returnResponse: true` rejection is deliberate. Describe unsupported capabilities
without turning them into unrestricted JSON schemas. Scenes/notifications can use
explicit service profiles; neither is automatically granted by discovery.

MCP remains an opt-in reuse option when the operator has configured an HA MCP
server, not a silent fallback. Installed HA `mcp_server/server.py:69` exposes
selected LLM API tools; its prompt endpoint emits the API prompt as assistant-role
text and its snapshot resource is conditional on GetLiveContext. It does not
provide this package's native registry catalogue/state_changed continuity contract.
The existing `integrations/mcp/README.md` connector supplies hosted HTTP MCP tools
with per-binding credentials, bounded catalogues and receipt recovery; it is a good
choice for that narrower tool-only surface. It is not a replacement for the
planned HA-native discovery/events or an inbound Conversation/AI Task gateway.
No MCP server availability or protocol interoperability on the live house was tested.

## Verification performed on this baseline

1. `/private/tmp/plowshare-ha-venv/bin/python integrations/home-assistant/assist/scripts/check.py`:
   Ruff check passed, Ruff format check passed (14 files), 21 non-socket tests
   passed; two socket tests hit sandbox loopback bind PermissionError before their
   transport scenarios ran. This combined invocation exited nonzero.
2. With approved isolated listener access, ran only
   `python -m unittest discover -s tests -p test_socket.py -v` from Assist:
   both socket tests passed. They exercise unchanged actual public SDK segmented
   round trip and disconnect-after-submission non-replay using ephemeral fixtures.
   Across these invocations all 23 current test cases passed; do not describe the
   initial combined command as a pass.
3. Actual HA integration tests cover config-flow validation without model work,
   identity/speech handling, anonymous denial and staged unchanged SDK packaging.
   Agent tests cover follow-up, Relay reply chains, timeout/restart/uncertain send,
   refusal/budget failure, queue budget and physical cleanup admission fencing.
4. `import homeassistant.components.ai_task.entity` fails because optional stream
   dependency `numpy` is absent. AI Task source was inspected, but its runtime
   pipeline was not tested. Install its actual required optional dependencies
   before claiming AI Task API compatibility tests pass.

No Gradle, PostgreSQL, Docker, model or live suite was run for this audit.
`HomeAssistantAdapterTest.java` contains fixture cases for policy denial, freshness,
malformed batches, reconnect and uncertain actions; `PipelineTest` and
`ProjectExamplesTest` cover related integration/examples. Their current results
remain unverified here. `HarnessPipelineTest.java:89` is `full-db` and was not run.
Prior broader check results are not imported as acceptance evidence.

## Remaining delivery dependencies

- CORE-01 through CORE-04: general platform decision, atomic authorized alias
  persistence, Ex facades, canonical contracts and every SDK; ordinary admission,
  budget, ownership and uncertain mutation behavior remain authoritative.
- FIDELITY-01: actual public role/content semantics and context transfer decision;
  downstream prompt-sensitive nodes remain gated until verified.
- HA-01 through HA-04: typed catalogue/read/action contracts and implementations,
  scoped dynamic ToolProvider, schema/policy revision admission and receipts.
- HA-05 through HA-08: shared public gateway, richer Conversation context and
  feature flags, AI Task text/validated structured data and bounded recovery.
- HA-09 through HA-11: integrate events/feedback, Application profiles and package
  migration/docs without losing prototype pending/uncertain state.
- VERIFY/PERF/RELEASE: independently check integrated exact source, original prompt
  cache impact and reviewable release/rollback; authorized deployment/live evidence
  remains separate. Adapter per-command timers and SDK transmission timers do not
  establish a universal shared end-to-end deadline. Inspect/implement owning budgets
  in affected work rather than claiming this audit proves enforcement.

## Exact source hash map

SHA-256 values below bind this audit's baseline claims. Paths under `ha-2026.9.4/`
refer to distribution-relative files in the isolated installed package, not source
copied into the repository. Generated build/bytecode/cache files are excluded.
The report's own digest belongs in root's external candidate manifest.

```json
{
  "repository": {
    ".agents/guides/integrations.md": "0cbe5a32ff3ef8575cd6f08a105a91284ba3e652884194652b124af3175e4b80",
    "integrations/home-assistant/README.md": "bd7260f0a892065c24b5561733960251b3c9211e13a95cd3424157bc2fd48699",
    "integrations/home-assistant/examples/application/agents/house_coordinator.md": "5ac32c02fa50526a73b378dd0f106b3061fc689ae3f0f872e0ec46bebd19a816",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java": "50cf6682c90402031cc7e0328a217a0879b1948bd6138a23422c9b058919b358",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantSettings.java": "ce80e740018e1ae17f743c8c1e9054b0b71b6c3ee1da569c51688b076cbc8652",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantWire.java": "66da92a20d8e59a689b5b64020a1faea816957f013acdc3076ee7319a0ab1e40",
    "integrations/runtime/src/main/java/io/aeyer/plowshare/integrations/IntegrationAdapter.java": "263ee04ca3e129edfd624d7f26af09da88e18d437dd55a0824b14c657ef07de4",
    "integrations/runtime/src/main/java/io/aeyer/plowshare/integrations/IntegrationRuntime.java": "920911607b61a81a3db543569865fb024a7888bee0475ca75b03c9e45cbd16f2",
    "integrations/mcp/README.md": "af3b39e01108f52a89063eb6fb85e800957857a200cf651f4def4a8676eb7d14",
    "sdk/python/src/plowshare/contracts.py": "eb6b67a2dbb916ed876edf8eb94fd12cde1c9568e4212a8b4cc5b19d193c3765",
    "sdk/python/src/plowshare/tools.py": "e6fe1e412195246eb17db0133858579c6217f052a371c03287eee741a951ef26",
    "sdk/typescript/src/operations/direct.ts": "ecd0b74d7b22e11a51a148cc4787e6b96e6a483fbdc0190cd10d4cecd00ab9b2",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Runs.java": "c8e71b908162cec5cdd9a179143becfc0b62058c6276f91167592157df28e24f",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java": "f0aa469fcb192c821ceac1f23553016f370633bb91f71f8392d0a372a868fd2f",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/LogStages.java": "eeab78b97565fea1591cc1c221815ff241712cf9d84dc06249b77a1330b5254f",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationOpenHandler.java": "8b3b2024a1f68e29457f0809c6a2c7a5d3f19d4788ae98f897e793738a7914eb",
    "plowshare-hooks/index.ts": "dbf1fcfeb7c8d6ead7b2f776c260204c0a7c5966f2d31ca7ea683805899b462d",
    "integrations/home-assistant/assist/README.md": "766f44be14af994f4969a8a7a925e7794c788ad032979cf61db727dd3967cdb7",
    "integrations/home-assistant/assist/custom_components/plowshare/__init__.py": "bc4ece377b1cfd833d3a358e279f4b6263a2ec432801d650baaf56aad640d94b",
    "integrations/home-assistant/assist/custom_components/plowshare/agent.py": "3f60bee85cdb6a774ba0f798b58d9b51a6a55575b49993785854591594425bac",
    "integrations/home-assistant/assist/custom_components/plowshare/config.py": "cdf542481f6b8cfebb924d1776140edc139825148ab33c1b95ad8f734e03f5fd",
    "integrations/home-assistant/assist/custom_components/plowshare/config_flow.py": "04c86d7cc69221bfdae752ca09c2c5d7d215beb02dc1869509ba4ad9dd46f2e0",
    "integrations/home-assistant/assist/custom_components/plowshare/const.py": "b80f377fd741a8ed473ff6072009fc86fe3514636153a19b054f43e9e13aa1ac",
    "integrations/home-assistant/assist/custom_components/plowshare/conversation.py": "1fd952e4d72a2cb2c18008a8f60cf64ac29e2fa3b3c28c05ca68cee0dcf8b6f5",
    "integrations/home-assistant/assist/custom_components/plowshare/manifest.json": "c6bd0c1ec39a21eba2dc41931f64e2bedd735c457260a58c1ecd107ad86a4d7e",
    "integrations/home-assistant/assist/custom_components/plowshare/models.py": "ef059bfb0a3fe6e025385249be0f9cb261d66651c2f1bcfcdb726674a7642dcf",
    "integrations/home-assistant/assist/custom_components/plowshare/strings.json": "99b22ac505436fcd61b2899c30963597003dc07562650f37133676377cb7b38d",
    "integrations/home-assistant/assist/custom_components/plowshare/translations/en.json": "99b22ac505436fcd61b2899c30963597003dc07562650f37133676377cb7b38d",
    "integrations/home-assistant/assist/pyproject.toml": "f458e86b3ba5a005585d868ae194e10d4f1705f06d5c2f0b10963291055cfe82",
    "integrations/home-assistant/assist/scripts/check.py": "49c23ec87a8f11c569ddec5253114b76933bc4a6c7caadd594ca51dde8c2ecee",
    "integrations/home-assistant/assist/scripts/package.py": "6ed3bff89a7ddde1a0617b04c6f9fa609339656b76764ccedf397c62f62e228b",
    "integrations/home-assistant/assist/tests/support.py": "ce039708a4afafc2b37220f061de5b182deabacdbe2343d7c64805099b412780",
    "integrations/home-assistant/assist/tests/test_agent.py": "74e28316957584f18e26e4c15657e8f24e75b9501e425b17222d38870bc1ebcc",
    "integrations/home-assistant/assist/tests/test_home_assistant.py": "7d8ce96c358daa6b05e5313d50e394f5dd892698900b664917a58902311bed94",
    "integrations/home-assistant/assist/tests/test_socket.py": "5960e806316cf7d709941a8dd25dde2f1bba73a4eaffe46e63ff005a47e39c46"
  },
  "ha-2026.9.4": {
    "components/conversation/chat_log.py": "3b4ddba72593847227eb9ee726d6fab8f103ba51844a0145ef3ff07717dece17",
    "components/conversation/entity.py": "44a284797beea59e2aea7640a1698298eb649a7b04c238b98644ee0084de9eff",
    "helpers/chat_session.py": "3eecd17b00afe81273dab939328a7b6d90963dd5bfc9cdecf1330534bfcdc348",
    "helpers/llm.py": "0220ca2cac1c26abad667fdb25a38e4480a56a90ce9fec9a8be60cc3671beb1d",
    "components/ai_task/entity.py": "8a4f3e8bf2a84f86e69a444f5bc0f088e0bf03b343c527e2781ef9c07cb5ae67",
    "components/ai_task/const.py": "61e09538cccdd0962dc121020597c66f497c4608032c9d9c0c1880882daa790c",
    "components/ai_task/task.py": "1c08cd0ddc4bdb649321809486f50d29215cd1e93b7f71654518538211502988",
    "components/mcp_server/server.py": "a67de161410818c396e16ab518296c43a1cde6b36e2168bcc712bb21ebc89be5"
  }
}
```
