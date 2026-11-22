# HA-FIX-01 — Native Assist first-turn session admission

Implementer: `/root/ha_assist_first_turn_implementer`. Independent reviewer:
`/root/ha_assist_first_turn_reviewer`. This report is ready for coordinator checks;
it does not approve itself or establish an installed/live-validated replacement.

A live HA Assist connectivity check reached the source-ownership refusal before
SDK work. The previous entity contract expected the first caller to omit a chat
ID. Actual HA 2026.9.4 `PipelineInput`/`PipelineRun.recognize_intent` allocates the
session and enters `async_get_chat_log` before dispatching `async_converse`, so
even the first entity input already has an ID and current user message.

The adapter now admits that native first turn only when both HA public module
context variables (`helpers.chat_session.current_session` and
`conversation.chat_log.current_chat_log`) match the supplied ID. The current log
must belong to this exact HA instance, contain exactly an empty `SystemContent`
and the current `UserContent` text without attachments, and have no retained extra
prompt, LLM API or supplied-LLM index. This reads active typed contexts, never
HA's global session/log caches. An arbitrary supplied ID or session without its
matching fresh log is still insufficient. Retained history/context is not imported.

The adapter claims the new source synchronously before the base entity can await.
Existing foreign-source, active-owner, expired-transition and bounded-owner guards
remain. The same source continues through the existing gateway and durable Ex SDK
mapping; no gateway, SDK, server, shared runtime, protocol or schema change occurs.

HA's pipeline opens its outer log before dispatch. The adapter can fence its own
log/context preparation and SDK submission; it cannot promise to prevent or undo
HA's earlier upstream mutation. Similarly, the native pipeline remaps an expired
ULID before calling the entity. The new visible ID is a new session/external
alias; the original supplied ID is not available to reconstruct or fence. The
README now states that boundary and retains direct-owned-expiry behavior, private
receipt retention, no automatic cancellation and no replay guarantees. This fix
does not introduce broad per-source locks, old-ID import or a new recovery API.

The component manifest version is 0.1.1; runtime requirements are unchanged.
Reproducible checks add the exact requirements needed to import HA's actual
Assist pipeline and TTS modules: `pymicro-vad==1.0.1`, `pyspeex-noise==1.0.2`,
`mutagen==1.48.1` and `ha-ffmpeg==3.2.2`. The intent-only fixtures
turn off audio enhancement and never run speech recognition, models or devices.

New actual-HA regression cases exercise real registered entities, public storage/
session/log helpers and `PipelineInput.execute(validate=True)` through intent
preparation and processing. The gateway is a typed local fixture; only unrelated
sentence-trigger lookup is isolated. Cases cover a genuine native first turn and
same-ID follow-up; exact context text; foreign user/device rejection; retained
foreign history refusal; matching session without a log; wrong session/HA/log
shape/context/API/attachments; synchronous first-owner concurrency and capacity;
and actual native expired-ULID remapping. The existing direct API, reload, expiry,
config, journal, installed-artifact and SDK suites remain enabled.

Verification: the implementer ran only owned-file Ruff lint/format, both passing.
The coordinator ran the actual full Assist suite. The first run reached 136
outer cases and failed one import because the new real pipeline path imports
HA TTS/ffmpeg modules too; their exact pinned dependencies were added to check
extras. A focused pipeline import then passed. The second full run reached 141
cases with the 135 baseline cases passing and all six new cases failing at
fixture setup: an empty pipeline store attempted unrelated default speech-engine
discovery. The fixture now seeds its selected text-only pipeline through the
actual public `PipelineStore.async_save` API before normal storage setup/load.
No production source changed in response to these fixture/environment failures.

The third full coordinator run passed **141 outer tests in 3.401 seconds**, with
Ruff lint/format passing and checked source/test hashes unchanged. This retains
the 135 baseline cases, including the existing wrapper requiring all three
isolated installed-consumer cases, and adds six actual native pipeline cases.
Evidence:

- `/private/tmp/ha-fix-01-v3-check.log`, SHA-256 `1de94b4d9668b2b5d5ac5b3c1534ae1c996052647c131bbcdacd1eb53f5efa20`.
- `/private/tmp/ha-fix-01-v3-source.json`, SHA-256 `c35090438db3ebf953afd4c2f3422ecd070a37b12a26f99d8018eac7e60d50b3`.
- Earlier unsuccessful root runs remain at `/private/tmp/ha-fix-01-v1-check.log`
  and `/private/tmp/ha-fix-01-v2-check.log`; they are not claimed as passing.

The coordinator built a separate `assist/build/plowshare-assist-0.1.1.zip`,
SHA-256 `1ad223a4859e415fd9e2b63344a3736b162a175e753e2fc3f7b8371e049110aa`.
It contains 51 ZIP entries, a 50-file contents manifest and 37 component files.
The prior reviewed 0.1.0 archive is retained unchanged. All 4,042 protected
core/protocol/SDK/shared-runtime source hashes remain unchanged. The coordinator's
repository-wide `./gradlew check` passed in 58 seconds across 141 tasks (25 executed,
116 up-to-date), without database tests. Its log is
`/private/tmp/ha-fix-01-gradle-check-v1.log`, SHA-256 `6c5f2d64bb8a6220b76732a8a300b3e9204e7e6291a464684f21ff097f1a8eb6`.
The separately retained old/new archive comparison confirms only four archive
paths changed: install instructions, Conversation source, component manifest and
contents manifest; it does not overwrite historical source/package evidence.

The coordinator separately owns current-scope clarifications in the delivery plan,
design introduction and release runbook; they must be included explicitly in the
same frozen candidate's independent review. The implementer changed only the
six declared adapter/test/documentation paths and performed no live model/device
calls, target access or deployment. Independent approval and actual installed
replacement/live checks remain separate milestones.
