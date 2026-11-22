# RELEASE-01 — Source release readiness

Implementer: `/root/ha_release_readiness_implementer`. Independent reviewer:
`/root/ha_release_readiness_reviewer`. The coordinator owns the current delivery
plan/status update. This implementation report is ready for independent review;
it does not approve itself or mark release publication, merge, deployment or live
acceptance complete.

The [release runbook](../../integrations/home-assistant/RELEASE-RUNBOOK.md) supplies
the target preflight, artifact checks, safe replacement, separately scoped live
acceptance and receipt-preserving rollback. It links the owning Assist/provider
contracts rather than duplicating their implementation. No runtime, SDK, shared
contract, core, database, Application profile or artifact bytes change in this
documentation node.

## Exact validated source and artifacts

The [final validation evidence](home-assistant-final-validation-evidence.json),
SHA-256 `dbf472faa3aa0a62a832698e097ef77d0790da169b909bf062324dbc601a5e67`,
binds 116 current source hashes including dirty/untracked adapter work. Base
commit `6de685f63e060f3003cb93233d2567e004269755` is provenance only, not the
whole candidate identity. All 4,042 protected core/protocol/SDK/shared-runtime
source files remain byte-identical to the coordinator's starting snapshot, with
no new untracked files there. The only already accepted core capability used is
external-to-internal conversation mapping through the public Ex SDK operations.

The final Assist archive is
`integrations/home-assistant/assist/build/plowshare-assist.zip`, SHA-256
`9f3be08f07b5b72849f2d62b407119292e10663bdb112da4dd2f0cf16d267038`.
Its 51 entries include a contents manifest for 50 files. The unchanged bundled
public Python SDK/resources/license and three Application profile roots are
covered by those hashes. The outbound installation at
`integrations/home-assistant/build/install/plowshare-integration-home-assistant`
contains 56 files/26 runtime JARs; the evidence identifies each relative path and
SHA-256. A directory filename or version string alone is not its identity.

## Verification and independent review chain

VERIFY-01 is accepted against
[candidate b9f3e57f](home-assistant-verify-01-v1-candidate.json), full SHA-256
`b9f3e57fa47090bd8ad2ac876fc6a2211f173ad68ef43bec9cb20138eea9ab0c`,
with independent [APPROVE review](home-assistant-verify-01-v1-review.md), SHA-256
`08b1e3950abe1a6135e9737608fd5b18ffce077300ce9fa258a79cd01c86e2b7`.
That review checked the exact final evidence, source/artifact hashes, dependency
approvals and actual check logs. The evidence retains candidate and review
digests for HA-05/06/07/08/10/11, the outbound branch and SCOPE-01; the VERIFY
candidate also binds its eight graph dependency revisions. Historical candidates
remain immutable, with later authorized handoffs reviewed in their owning nodes.

| Check | Recorded result |
| --- | --- |
| Isolated HA 2026.9.4/Python 3.14 | Ruff lint/format pass; 135 outer cases, including one wrapper requiring all three installed-contract subprocess cases |
| Repository `check` and HA `installDist` | Pass in 1m14s/144 tasks; 184 HA Java and 8,627 default server cases, zero failures; no database tests |
| Fresh installed outbound consumer | Eight offline checks pass against all 56 payload files, retained journals and directory topology |
| Explicit SDK package checks | Pass in 4m44s across Java, Node, Python, Go and .NET, including fresh consumers; retained proof reused because protected SDK source is unchanged |

The installed-contract cases exercise actual HA entry/entities/journal and the
bundled SDK through a segmented loopback peer. They establish continuation and
source isolation, separate text/structured task routing, committed intent before
wire submission, invalid-result retention and lost-ack reload without replay.
The peer executes no model/device work. All log hashes and source snapshots are
in the linked evidence and VERIFY report. This documentation author ran no new
builds/tests and changed no reviewed artifacts; the coordinator checks local links
and evidence identities before freezing this node for its independent reviewer.

## Readiness limits and remaining operations

The former native prompt-fidelity/cache gate is retired following the user's
scope correction. HA supplied text is preserved through existing ordinary
task/user input, with no native system-role, special compaction or prefix-cache
rewriting claim. No additional core capability is requested. Optional backend
cache measurement is not a release dependency.

The tested HA/Python baseline is not proof of the running target's version or
compatibility. That target has not been inspected or updated. Configuration,
principal/membership and exact tool grants must be verified for the actual
deployment. Read-only/control/task profiles are templates, not issued grants.
Attachments/images, evaluation, nested/custom schemas and HA LLM tool translation
remain unsupported. The runbook records the process-restart chat-ID restriction
and the fact that repeating an AI Task creates a new invocation.

No new publication, commit/merge, deployment, HA restart, live server/model/voice
check or device action was performed. DEPLOY-01 and LIVE-01 retain their explicit
target/action authorization gates, and PERF-01 retains its optional backend-access
scope. These are operational follow-ups rather than unfinished SDK-only source
implementation. Root marks RELEASE-01 accepted only after its independent review;
all later milestones require their own actual evidence.
