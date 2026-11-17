# HA prompt fidelity: public capability decision

Node: `FIDELITY-01`. Implementer: `/root/ha_fidelity_implementer`.
Date: 17 November 2026. Base: `2d95d9ce4fb75e906b61e8f2afa940d04cf9bf2e` plus the dirty delivery tree.
Status: investigation ready for independent review; proposed platform work below is not implemented or authorized by this report.

## Decision and gate

**No existing public SDK route verified here preserves HA's changing, rendered system prompt in a native system role for both Conversation and AI Task.** Preserve that role as the preferred interpretation of the user's requirement; do not silently downgrade it to task text. Keep `prompt_public_path_verified` unsatisfied. Approval of this investigation can accept an accurate capability-gap finding, but cannot unblock HA-05–HA-08 or claim an implemented prompt handoff.

The proposed Ex facades solve external conversation identity only. Their accepted scope does not authorize a prompt-role extension. Continue independent facade and outbound HA discovery/action work. A separately authorized, independently reviewed generic platform successor is required for native role fidelity. Alternatively, the user could explicitly accept a content-only task envelope and its lower role/compaction semantics; that would be a changed requirement, not verification of native fidelity.

## Integrator and SDK responsibility

The user's subsequent direction is to **leave prompt-dependent features gated**. Prompt quality and cache efficiency belong primarily to the integrator/application that constructs the request. Reusable common patterns may justify SDK support, including understanding variation and offering an explicit correction, but the SDK must not assume that changing an integrator's prompt is transparent or safe. For HA, preserve its original rendering and observe/report the resulting cache impact. Any future transformation needs an explicit semantic contract, compatibility evidence and opt-in; no prompt optimization, transformation or prompt-role API implementation is authorized by this decision.

## Exact preservation contract

For Conversation, call HA's `ChatLog.async_provide_llm_data` with the admitted ConversationInput's LLM context, configured prompt/API selection and `extra_system_prompt`. Preserve the resulting SystemContent string exactly, including whitespace, rendered time/state and appended API/extra prompt order. Keep the newest per-utterance snapshot rather than freezing the first session's prompt. Preserve language and relevant caller/device/source provenance through typed fields; metadata cannot create Plowshare authority.

For AI Task, consume the ChatLog prepared by `AITaskEntity._async_get_ai_task_chat_log`: HA renders its task default system prompt with task LLM API/context and appends task instructions as UserContent. Each `async_generate_data` invocation starts an isolated HA session. Do not replace that prepared system prompt with the Conversation default, omit API context, combine callers, or expose a pending job as a completed result.

The effective prompt can contain user-authored templates and externally supplied context. HA calling it “system” does not turn its data into trusted Plowshare platform instructions, an authenticated identity or a tool grant. Plowshare's selected agent, project/workspace fences, live authorization, tool scopes and approvals remain authoritative. System-role placement is a model-input property; it is not a security grant or an enforceable hierarchy within a merged system message.

The package must explicitly reject unsupported HA `llm_api`/tool schemas or attachments before submission. Forwarding an API's instructions without implementing its advertised tool capability would leave an inconsistent request. The existing selected-action provider is a separate public tool contract; discovery of a HA tool or resource does not grant it. A later adapter may support a reviewed subset, but this investigation does not supply that translator.

Plowshare owns durable tool/history state. The HA chat mirror must not be replayed into it as fabricated assistant/tool history or duplicate utterances. Preserve admitted current user/task text and model-relevant source context while mapping one HA session to one Plowshare conversation. Any requirement for importing pre-existing arbitrary HA assistant/tool history is an additional public capability gap; the successor below deliberately does not provide that import API.

## Owning-source evidence

| Owner | Observed behavior and implication |
| --- | --- |
| Installed HA 2026.9.4 `conversation/entity.py:73–87` | Creates/reuses a ChatSession and ChatLog, then calls the integration's handler. The base ConversationEntity does not prepare the LLM prompt on the adapter's behalf. |
| HA `conversation/chat_log.py:673–765` | `async_provide_llm_data` renders configured/default template, API prompt, date/time unless a GetDateTime tool is present, then effective extra prompt; replaces SystemContent at index zero. The string and its order belong to HA. |
| HA `helpers/llm.py:128–147` | LLMContext carries platform, request Context, language, assistant domain and device ID. User name and templates can depend on that context. |
| HA `ai_task/entity.py:63–117`, `ai_task/task.py:113–173` | AI Task prepares the ChatLog itself, adds instructions as user content and invokes the provider; the helper opens a new session per invocation. |
| HA `helpers/chat_session.py:29`, `:113–153` | Five-minute inactivity cleanup; an expired native ULID yields a new ID. This is not cancellation of a server-owned Plowshare job. |
| `sdk/python/src/plowshare/contracts.py:6409–6422`, `:6757–6766` | AgentRunRequest has task/routing/images/limits; ConversationOpenRequest has project/limits. Neither exposes incoming system content or arbitrary messages. |
| `plowshare-server/.../api/RunAgentRequest.java`, `.../ws/AgentRunHandler.java:65–92` | Handler constructs normal Runs.Ask with task and identity/routing fields. No external system block crosses the public boundary. |
| `.../agents/JobRuntime.java:3054–3055`, `:6582–6650` | System text is selected agent prompt plus fixed log opening; all SYSTEM entries are merged into the single first system message. A late SYSTEM entry would be hoisted, not remain after history. |
| `plowshare-hooks/index.ts:149`, `JobRuntime.java:3138–3153` | prompt.pre additions are inserted as USER content. They do not provide native role preservation. |
| `plowshare-hooks/index.ts:178–191`, `.../agents/LogStages.java:21` | log.open occurs after commit and adds a fixed opening once. It cannot carry refreshed per-utterance HA context. Hooks are not a pre-selection ID resolver or public per-request role API. |
| `.../agents/Compaction.java:4974–5004`, `.../agents/Transcript.java:287–296` | Current projection distinguishes historical recorded system block from current definition-derived reconstruction; fixed opening and ordinary history have different semantics. Content buried in task text can be covered/summarized as user history. |
| `integrations/home-assistant/assist/.../conversation.py:46–72` | Current prototype calls `ask(key, user_input.text)` and never prepares/forwards HA's rendered system prompt. Existing passing tests do not establish this missing behavior. |

The [Conversation documentation](https://contributors.home-assistant.io/core/entities/conversation/) and [AI Task documentation](https://contributors.home-assistant.io/core/entities/ai-task/) were checked as public entry-point references. The installed 2026.9.4 source governs this compatibility finding; newer documentation is not proof that newer flags/schema libraries exist on that version.

## Alternatives evaluated

| Route | Preserved | Why it does not satisfy native fidelity now |
| --- | --- | --- |
| Literal typed task envelope over `AgentRunRequest.task` | Complete string and labeled metadata can be copied. | Becomes USER content, competes at a different model role, may be summarized/covered with conversation history and accumulates obsolete clock/context snapshots. No existing role-aware SDK contract. |
| prompt.pre hook | Can add text during an admitted run. | Added as USER; mode changes retention, not role. It is not an authenticated per-request prompt input channel. |
| log.open fixed opening | Opening joins native SYSTEM text after agent prompt. | Captures once, cannot refresh a session's HA-rendered system prompt, and has no run request field carrying that content. Freezing it changes HA freshness. |
| Per-request `agent.define` or agent file rewrite | Could change future definition prompt. | Mutates shared selected-agent configuration, needs write authority, races concurrent callers and changes unrelated runs/resume. It is not a request context contract. |
| New conversation each utterance with frozen opening | Could approximate one initial snapshot through custom hook/state machinery. | Discards the requested session lifecycle/history; does not expose a safe public snapshot channel and still cannot faithfully refresh context within durable follow-up work. |
| Full arbitrary message/history injection | Could emulate another system's request. | No public API exists; grants replay/forgery risks for assistant/tool records and duplicates Plowshare's durable history. Larger than the necessary system-context capability. |

## Deferred successor proposal requiring separate authorization

Propose a generic **per-run external system context** capability for configured SDK consumers, independently useful to any external conversation/task frontend. This section is a proposed authorization target, not a current API promise. Its exact canonical names/resource bounds must be accepted in a separate platform ADR/contract node before code.

1. Add one optional typed `externalContext` to ordinary AgentRunRequest and its Ex facade, carried to the owning run admission API. It contains a single `systemText` string and bounded provenance fields for source kind, external request/session reference and language. This is not a list of arbitrary roles, assistant messages, tool results, capability grants or caller-chosen Plowshare identities. Reject unknown fields and unsupported input before acceptance; omission preserves old behavior. A proposed initial hard input budget is 64 KiB UTF-8 for systemText, within the enclosing transport limit; over-limit input fails explicitly, never truncates/rephrases the HA prompt. These are protocol/resource bounds, not model prose targets.
2. Derive authenticated submitter, project, selected agent, accounting owner and effective authority on the server. Treat client provenance labels as untrusted claims bound to that authenticated admission; never accept an external user ID as an impersonation instruction. Require explicit selected-agent/profile admission for external system context, default off for existing agents, and current normal run authorization. If a new authority action is needed, define its exact grant behavior in the ADR rather than treating WRITE or a service account as ambient SYSTEM. Adapter configuration cannot grant this capability by itself.
3. Freeze the admitted system text/provenance atomically with the run/turn's durable input before returning acceptance. The same admitted run uses that snapshot across all model/tool iterations, approval continuation, workspace pause/recovery and supported resume. Reconnecting HA must not re-render/replace the snapshot of already accepted work. A later admitted user utterance supplies a fresh snapshot; it replaces the active external context for that new run instead of accumulating old system snapshots as conversation user messages. Respect normal conversation exclusion and never rewrite a pending run from a later caller.
4. Preserve the exact HA text as one contiguous block in the single first native system message. Proposed deterministic order: selected agent system text, fixed log opening, admitted external system context, then any system material the current history projection already requires. Document fixed separators and block provenance. This preserves HA block content/role while adapting message packaging to Plowshare's one-system-message requirement; it does not promise byte-identical whole HA request serialization or an enforceable priority between paragraphs. Normal platform authorization remains outside model text. Do not relocate HA components into later user messages to improve caching.
5. Retain the original snapshot for audit/recovery under normal access/retention policy. Keep it out of ordinary history compaction inputs so a fold cannot summarize it away or revive an earlier HA snapshot. Record the actual system block alongside the turn's existing system-block evidence, and update next-projection/reconstruction/resume paths consistently. Public diagnostics must retain ordinary disclosure controls; logs/metrics must not publish raw prompts or caller/device identifiers. No provider/model call is required merely to accept/resolve the context.
6. Preserve ordinary run accounting, tool authorization, cancellation and uncertain-delivery behavior. An external conversation alias is not an utterance idempotency token. Submission timeout after possible transmission must not trigger a new generation/action. No implicit tool attachment, HA tool invocation or history import comes with this field. Shared SDK DTOs/generators, all clients and compatibility tests change together.

Suggested successor graph: `PROMPT-01` platform ADR/admission contract → `PROMPT-02` canonical DTO/runtime storage/projection/resume implementation → `PROMPT-03` all-SDK conformance and isolated HA handoff proof. Each needs a separate implementer and independent reviewer. Attach its accepted outputs to FIDELITY-01's gate evidence and reopen affected dependencies when contracts change. Gate satisfaction requires actual implementation/verification, not merely user authorization or ADR approval.

Acceptance must include exact whitespace/Unicode content through both HA entry points; default/custom/API/extra prompts; refreshed next turn versus frozen pending run; cross-caller/profile/project isolation; refused authority and malformed/oversize input; compaction, approval/workspace recovery and resume retaining the proper snapshot; no duplicate submission or forged history; old API compatibility; actual provider-request layout in a controlled no-model fixture; and SDK/package checks. Any capability intentionally left unsupported must be rejected before submission.

## Prefix-cache consequences

A refreshed HA system block near the front can move the first changed token before conversation history. Under the proposed layout, the earlier fixed Plowshare agent/opening prefix can remain reusable, while backend reuse of the HA block and following history depends on the first divergence, tokenizer/chat template, cache block granularity, eviction and backend implementation. Do not infer total cache loss or a hit percentage from changed characters. Per-run freezing preserves consistency during one task; freezing the first session snapshot across new utterances would change HA behavior and is rejected as an optimization.

I reran the existing isolated actual-HA ChatLog source probe on 17 November 2026. No live HA, model or device calls occurred. The six cases reproduced: default Conversation and AI Task clock changes differ with common character prefixes 161/191 and 88/118; a fixture GetDateTime tool suppresses inline clock differences; a custom sensor-template change diverges earlier (27/56); an extra-prompt-only change diverges late (173/174). These are source/helper observations, not tokens, cache hits or latency. The fixture clock tool was never executed. Keep PERF-01's backend-measurement authorization gate unchanged.

## Verification and limits

Inspected the owning sources above and current public SDK DTOs; reran `/private/tmp/plowshare-ha-venv/bin/python /private/tmp/ha_prompt_impact_probe.py` successfully against installed HA 2026.9.4. The report below hashes its source inputs/results so the isolated evidence is reproducible in this environment. Independently repeated `import homeassistant.components.ai_task.entity`: it fails with missing optional `numpy`, confirming AUDIT-01; this investigation makes no end-to-end AI Task/provider claim. No Gradle, database, model, live install, DNS change, device action or core runtime edit occurred. The shared design document now records the same integrator/SDK responsibility and explicit decision to leave prompt-dependent features gated. This follow-up changed documentation only; checked local links, Markdown fences, whitespace and consistency with the existing source finding. No runtime tests were needed for that prose-only change.

## Evidence hashes

```json
{
  "repository": {
    "sdk/python/src/plowshare/contracts.py": "eb6b67a2dbb916ed876edf8eb94fd12cde1c9568e4212a8b4cc5b19d193c3765",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/api/RunAgentRequest.java": "992305273364a856df507405d6cc1accd865edc71dd24fbcb33cf70008538f20",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/AgentRunHandler.java": "cb9627912e6734b12c66a00e1e60c0a3e58d337c1bb24ff87c0c14dd5fa6f09d",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java": "f0aa469fcb192c821ceac1f23553016f370633bb91f71f8392d0a372a868fd2f",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/LogStages.java": "eeab78b97565fea1591cc1c221815ff241712cf9d84dc06249b77a1330b5254f",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Transcript.java": "1b9de64510dc751b7d60fc3d82ef9462875760dd880a01efc4f0774b6724ee7e",
    "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Compaction.java": "4c5392a37f086db1c39a2d7fda646159875125a17127845fac2adee5409b12dd",
    "plowshare-hooks/index.ts": "dbf1fcfeb7c8d6ead7b2f776c260204c0a7c5966f2d31ca7ea683805899b462d",
    "integrations/home-assistant/assist/custom_components/plowshare/conversation.py": "1fd952e4d72a2cb2c18008a8f60cf64ac29e2fa3b3c28c05ca68cee0dcf8b6f5"
  },
  "installed_ha_2026.9.4": {
    "components/conversation/entity.py": "44a284797beea59e2aea7640a1698298eb649a7b04c238b98644ee0084de9eff",
    "components/conversation/chat_log.py": "3b4ddba72593847227eb9ee726d6fab8f103ba51844a0145ef3ff07717dece17",
    "components/ai_task/entity.py": "8a4f3e8bf2a84f86e69a444f5bc0f088e0bf03b343c527e2781ef9c07cb5ae67",
    "components/ai_task/task.py": "1c08cd0ddc4bdb649321809486f50d29215cd1e93b7f71654518538211502988",
    "helpers/llm.py": "0220ca2cac1c26abad667fdb25a38e4480a56a90ce9fec9a8be60cc3671beb1d",
    "helpers/chat_session.py": "3eecd17b00afe81273dab939328a7b6d90963dd5bfc9cdecf1330534bfcdc348"
  },
  "isolated_probe": {
    "/private/tmp/ha_prompt_impact_probe.py": "6983853269cd9f02c56c2d12a8d3d90cd1e91b248f0319633ef0232025300eff",
    "/private/tmp/ha-prompt-impact-results.json": "dfbf54d43aa8c8763d518e990ebd77381d32ff2337980d698181e1d5a2291930"
  }
}
```
