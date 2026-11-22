# HA-FIX-01 v1 independent review

Verdict: **APPROVE**.

Reviewer: `/root/ha_assist_first_turn_reviewer`.
Implementer: `/root/ha_assist_first_turn_implementer`.
Coordinator coauthor: `/root`, native-session documentation only.
Candidate: `docs/reviews/home-assistant-ha-fix-01-v1-candidate.json`.
Candidate SHA-256:
`9eb3354c2682da906c31dd4affbaa36316b573049e6b2b964f6b4ed81f185582`.

I independently read the owning adapter and actual installed HA 2026.9.4
session, chat-log, conversation dispatch and Assist pipeline implementation. I
reviewed the narrow old/new packaged runtime diff, six new real-pipeline cases,
check dependencies, operator documentation, actual check logs and frozen
provenance. All 11 candidate file hashes, frozen ledger/diff hashes and three
accepted dependency candidate/review chains match. I authored no implementation,
changed no target or ledger and ran no builds or tests. This review is my only
new file.

## Findings and contract

No blocking findings remain. The live failure exposed a missing integration
boundary: HA's native pipeline opens a session/log before dispatching the entity,
so a legitimate first request already carries its HA conversation ID. The
previous initial-ID-omission requirement rejected that request before SDK work.

The fix admits an unowned supplied ID only with both matching active HA context
variables. The log must belong to this HA instance, contain exactly an empty
system message and this request's user text without attachments, and carry no
prepared LLM API/index or retained extra prompt. It reads typed active contexts;
it does not inspect or rewrite HA's global session/log caches. An arbitrary ID,
matching session alone, existing history, prepared context, mismatched log or
other HA instance cannot satisfy this boundary.

Source identity still includes the entry, profile, server/project/agent and HA
caller/device/satellite partition. Capacity is checked before the synchronous
owner insertion; the existing active-owner and foreign-source checks then run
before the adapter's base processing can await. The original direct API, owned
expiry-transition, reload and gateway/journal behavior remain in place. No
public SDK, core runtime, shared integration runtime, protocol or schema extension
is introduced.

The lifecycle limits are now explicit. HA may already have appended its outer
input before the adapter runs; this fix fences the adapter's own context and SDK
handoff, not earlier HA behavior. HA may also replace an expired/restarted ULID
before entity dispatch, hiding the original ID. An eligible fresh visible session
then gets a new alias; the adapter cannot claim old-ID continuity or detect an
ID it never received. Earlier SDK receipts remain independent and retained,
without cancellation, import or automatic replay. These limits are recorded in
the Assist README and coordinator-owned plan, design introduction and runbook.
Native HA sentence-trigger automations remain HA behavior even when local-intent
preference is off; this source approval does not claim they are globally disabled.

## Verification inspected

The coordinator's full isolated Assist check passed Ruff lint/format and **141
outer cases in 3.401 seconds**. The existing installation wrapper still requires
all three isolated installed-consumer cases; these are not additional outer
cases. The six new cases execute actual `PipelineInput.execute(validate=True)`,
real pipeline preparation, registered entity dispatch, session/log helpers and
storage. A typed gateway and unrelated sentence-trigger lookup are fixtures;
no audio engine, model or physical device is exercised. They prove first-turn
and same-ID continuation, exact context, foreign user/device/history refusal,
missing/mismatched/prepared context rejection, first-owner concurrency/capacity
and native expired-ID remapping. Existing direct-ID and durable uncertainty
coverage remains enabled.

I verified the actual log and snapshot hashes and all 32 checked source/test
bytes against `/private/tmp/ha-fix-01-v3-source.json`. The source snapshot SHA is
`c35090438db3ebf953afd4c2f3422ecd070a37b12a26f99d8018eac7e60d50b3`;
the check log SHA is
`1de94b4d9668b2b5d5ac5b3c1534ae1c996052647c131bbcdacd1eb53f5efa20`.
The earlier failed environment/fixture runs are retained and accurately described
in the implementation report; they are not represented as passing.

The repository `check --max-workers=2` passed in 58 seconds across 141 tasks,
25 executed and 116 up-to-date, without database tests. I read the actual log and
verified its SHA:
`6c5f2d64bb8a6220b76732a8a300b3e9204e7e6291a464684f21ff097f1a8eb6`.
All 4,042 protected core/protocol/SDK/shared-runtime source hashes still match
the start-of-delivery baseline. The four new check-extra versions exactly match
the installed HA Assist, TTS and ffmpeg manifests; component runtime requirements
are unchanged.

The new 0.1.1 ZIP SHA is
`1ad223a4859e415fd9e2b63344a3736b162a175e753e2fc3f7b8371e049110aa`.
I independently checked all 50 contents hashes in its 51 entries, including the
37 component files. Comparison with the retained approved 0.1.0 ZIP changes only
install instructions, Conversation source, component version and contents
manifest. The SDK/vendor resources, other component code and Application profile
bytes remain identical. Historical source/package acceptance is preserved.

## Acceptance limits

Approval closes this adapter source/package compatibility follow-up only. The
validated baseline is HA 2026.9.4. It establishes neither successful replacement
on the live host nor a completed model, voice or house-read interaction. Those
require their actual deployment and live receipts. Read-provider availability,
server tool grants, control settings and source identity remain their own owning
contracts. No native prompt-role, cache rewriting or special compaction guarantee
is added by this fix.
