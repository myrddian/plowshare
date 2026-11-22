# HA-05 independent review — v1

Verdict: **APPROVE**. No open blocking findings on this exact gateway candidate.
This approves HA-05 only; it does not enable or accept component setup,
Conversation/AI Task entities, installation packaging, deployment or live use.

Reviewer: `/root/ha_gateway_sdk_reviewer`.
Implementer: `/root/ha_gateway_sdk_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-ha-05-v1-candidate.json`.
Candidate SHA-256:
`cf53c511c3d7f2615e9f8eb60032dec6521ac75ada1638b2ea02225377f4241e`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and integrity

The reviewer authored no implementation, fixture, coordinator ledger or feature
documentation and ran no builds, package installs, runtime tests or live actions.
Review inspected owning source/public SDK contracts and actual coordinator logs.
The only reviewer-authored file is this report.

Independently matched all seven manifest-bound files (three source modules,
three test modules and the implementation report), frozen ledger snapshot and
scoped diff. Both CORE-04 and SCOPE-01 dependency manifests and their accepted
independent review digests match. Actual implementer/reviewer identities differ;
HA-05 is in review, not already verified-and-reviewed, in its frozen snapshot.

Independently checked the 30-node/56-edge DAG and accepted dependency closure.
The retired prompt/cache gate has no consumers. The three operational
measurement/deployment/live authorization gates remain unsatisfied. Existing
outbound feature/validation sources, four publication guard sources and the
previously accepted SQL test match their prior source manifests. Repository
status shows no production core, protocol, SDK or shared-runtime changes; the
pre-existing SQL fixture edit still matches its accepted candidate.

## Supported contract

`GatewayPort` separates Conversation and Task requests and returns typed states.
Only a completed result carries generated text; pending, uncertain, refusal,
unavailability, failure and busy states carry safe diagnostic messages. The
legacy `ask` method is a presentation wrapper over this lifecycle, not an ordinary
agent-run fallback or a source of successful AI Task data.

The deterministic adapter envelope preserves supplied HA text and language
through the existing SDK task input. Exact whitespace/Unicode preservation and
pre-submission malformed/oversize input validation are covered. The envelope
explicitly states task/user semantics; it neither claims native system-message
priority/special retention nor rewrites prompts for caching. Raw user, device and
satellite identifiers partition requests locally and are absent from model prose.
Configured Settings alone select origin, credential, project and agent.

New submission requires an explicit installation/profile identity. Conversation
and task sources are distinct and valid public Ex selectors. Stable local lookup
and an independently stored routing binding prevent settings/profile changes
from hiding old pending work. Origin, credential, project and agent changes are
fenced before connection/disclosure/submission. Token changes are conservatively
unsupported for transparent continuation; no account-identity API is invented.
Legacy unbound records remain quarantined and are not imported into aliases or
replayed using ordinary operations. Unknown/pending/legacy rows cannot be evicted.

Alias establishment uses Open-Ex once, then Run-Ex resolves the selector inside
that operation. The actual SDK's Open-Ex OK / Run-Ex ACCEPTED contract is preserved;
there is no preliminary alias-resolve call, hidden transport field or direct core
access. Accepted job/conversation IDs are checked before polling and retained for
restart. Only terminal ANSWERED data is returned; reasoning, tool previews,
publication acknowledgements and failed/capped partial prose are excluded.

Physical SDK ownership is per key with four bounded slots. Timeout/cancellation
of a caller does not cancel remote work. Slow cleanup keeps its slot, and failed
or cancelled cleanup remains quarantined. `async_close` stops intake and waits
finitely without cancelling accepted calls; its owner must retain the gateway
when it returns false. A terminal Task retains the actual final reply job,
including Relay continuation, and can recover its data without old trajectory.
Terminal receipt retention remains bounded; callers must generate fresh internal
Task invocation IDs rather than expose arbitrary old-key replay.

## Pre-freeze findings resolved

The reviewer raised four issues during authoring. All are corrected in the
frozen source and have meaningful owning cases:

- Failed/cancelled close no longer releases an unproven physical owner.
- Correlated Task completion retains its final job and reads that receipt after
  trajectory removal, rather than depending on interim jobs or historical pages.
- Malformed stored routing types raise the codec's safe ValueError.
- Ambiguous negative Ex outcomes preserve uncertainty instead of clearing the
  sending fence. Existing SDK `require_payload` can raise Refusal for
  ARCHIVE_UNAVAILABLE even after possible commit/admission. Only the documented
  pre-handler CAPACITY_EXHAUSTED proof or NOT_SUBMITTED clears sending; a repeat
  call/restart never replaces an uncertain model run.

No prompt/cache gate or extra core capability was introduced to resolve them.

## Actual verification evidence

The coordinator's isolated Python 3.14 / HA 2026.9.4 check log contains Ruff lint
and format success and all 48 tests passing in 0.926 seconds, without exclusions
or skips. The reviewer read the full named-case output and matched the log hash:

`/private/tmp/ha-05-check-v2.log`
SHA-256 `e2d37c4fec4fcfd1013c486098e56b32eedfa7ceb83fbbae28acd281492d2475`.

The first attempt stopped at two import-order lint findings. The final log
records mechanical sorting/formatting followed by the full successful check;
that earlier attempt is not presented as a runtime test pass.

Three cases use the actual unchanged public SDK over its normal segmented
WebSocket protocol: Ex success, acknowledgement loss, and a valid negative
ARCHIVE_UNAVAILABLE envelope. The fake/unit path independently proves refusal
classification and restart no-replay. Other cases verify exact prepared text,
caller/device partitioning, separate entrypoint namespaces, concurrent distinct
tasks, per-owner saturation, retained receipts, routing/legacy fences, save
failure before mutation, foreign receipts, failed cleanup and finite unload.
Actual HA guard tests still confirm setup/configuration/packaging are unavailable.
Scripted brokers and typed fakes do not establish real server grants, model
execution, physical devices, deployed compatibility or live voice behavior.

## Composition requirements retained for later deliverables

The injected save callback must provide truthful durable acknowledgment. HA's
ordinary Store.async_save can swallow write failures or defer writes while
stopping; this gateway approval does not claim that the existing setup callback
satisfies its contract. The assigned HA-07 storage/setup owner must supply and
verify that persistence, including concurrent saves and physical writer ownership,
before runtime enablement. The lifecycle owner must also retain unsettled
gateways, including any temporary configuration validator, rather than discarding
them after an unsuccessful close. These responsibilities were explicitly handed
to the coordinator and later owners.

No further runtime/SDK/core change is implied by this review. Existing entity and
publication guards remain unchanged. Integrated whole-component and repository
checks belong to VERIFY-01, and installation/live work retains its own scope.
