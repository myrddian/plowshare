# HA-05 independent review — v2

Verdict: **APPROVE**. No open blocking findings for the exact v2 gateway candidate.
This permits HA-05 closure only, not entity/setup/package enablement or live use.

Reviewer: `/root/ha_gateway_sdk_reviewer`.
Implementer: `/root/ha_gateway_sdk_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-ha-05-v2-candidate.json`.
Candidate SHA-256:
`f165cb89ae87625c1dcaacad1eced1ed1e37224efb85dcd420167c1d12197f92`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact-source verification

The reviewer authored no gateway, fixture, implementation note, plan or ledger.
No build, runtime test, package install, live endpoint, model or device operation
was run by the reviewer. Only independent review reports were written.

Independently verified the candidate digest, all seven current bound source/test/
report files, frozen ledger and scoped diff. CORE-04 and SCOPE-01 manifests and
accepted review-report digests match. Actual implementer/reviewer IDs differ;
HA-05 remains in review with verified-and-reviewed false in the frozen snapshot.
The prior review established the 30-node/56-edge DAG and accepted dependencies;
the v2 snapshot preserves that structure and dependency closure. The retired
prompt/cache gate has no consumers; three operational authorizations remain
unsatisfied. No native-system-role capability is claimed or newly required.

The original v1 implementation report remains at its frozen digest
`c443d22a779c09760028c50b0ffd8f786100b7f2468c371c4c319a4b5cc903d6`.
The [v1 independent review](home-assistant-ha-05-v1-review.md) remains unchanged,
SHA-256 `81698e448074132e998622f3c0efc2c291760a4c25cad56b2fd7fb91ab057f65`.
It approved only its exact source; ledger closure was held for the shared runtime
interface handoff, not performed using stale v1 evidence.

## V2 delta and preserved gateway review

The sole source delta from v1 is `ProfileRuntime` and its `runtime_checkable`
import. Independently removed that declaration/import in memory and obtained the
exact v1 agent.py hash, proving the semantic gateway code did not otherwise
change. Models, request types and all three test files retain their v1 hashes.
The new implementation note correctly separates original and current check logs.

`ProfileRuntime` belongs beside `GatewayPort` in the adapter's agent module. Its
read-only optional `conversation` and `task` properties give HA-06/HA-07 a narrow
shared service interface without a reverse models import or circular dependency.
`async_close() -> bool` explicitly retains unsettled physical owners when false.
The concrete setup/journal/lifecycle composition remains HA-07's responsibility.
This structural interface confers no routing, credentials, tool grants or native
HA prompt semantics and adds no core or SDK capability.

The detailed v1 review of the unchanged gateway remains applicable. Typed results
separate completed data from pending/uncertain/failed states; raw caller routing
IDs do not become model prose. Exact supplied HA text is preserved through the
existing task/user envelope. Stable local lookup plus a separate routing binding
fences changed origin/credential/project/agent/profile before effects. Legacy
records stay quarantined, unresolved records cannot be evicted, and task terminal
receipts are bounded rather than a public indefinite idempotency API.

The four pre-freeze corrections remain present: failed/cancelled close retains
physical ownership; correlated Task completion records its final job; malformed
routing metadata raises safe ValueError; ambiguous negative Ex outcomes preserve
uncertainty. In particular, ARCHIVE_UNAVAILABLE can follow Ex admission/commit,
so only documented pre-handler capacity proof or NOT_SUBMITTED clears sending.
No automatic model/action replay or synthetic remote cancellation is added.

## Current verification

Independently read the coordinator's current log and matched SHA-256:

`/private/tmp/ha-05-check-v3.log`
`74f8e999db47e98e4b91d6b8977245b2c523acb45a69bf5dcdf315a91ef7d377`.

Ruff lint and format checks passed; all 48 cases passed in 0.921 seconds with no
exclusions/skips. The actual unchanged public SDK segmented Ex success,
acknowledgement-loss and valid ARCHIVE_UNAVAILABLE cases passed, alongside typed
request/lifecycle/isolation, retained receipts, saturation, persistence-failure,
cleanup/unload and existing real-HA prototype guard tests. This is fresh current
source evidence, not reuse of the earlier 0.926-second v1 result. The v1 log and
report remain accurate historical records.

These fixtures do not prove live grants, provider/model execution, physical
devices, deployment, voice or whole-component release. Existing component setup,
configuration and packaging guards remain unchanged. No production core, protocol,
SDK or shared-runtime file is changed by this candidate.

## Remaining composition obligations

The save callback must truthfully acknowledge durable intent. Ordinary HA
Store.async_save can swallow failures; the assigned HA-07 storage owner must
supply verified atomic persistence before runtime enablement and serialize shared
snapshots. Runtime owners, including temporary configuration validators, must
retain unsettled gateways instead of discarding a false-close result. These are
explicit handoffs to the unfinished composition/entity/package deliverables, not
new core changes or prompt/cache gates.

HA-05 is approved for its typed SDK-only gateway and interface scope. Subsequent
owners must preserve this contract and obtain their own tests and independent
review; integrated/repository validation and operational milestones remain open.
