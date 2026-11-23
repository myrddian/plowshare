# CAP-03 independent integrated review

Date: 24 November 2026. Implementer/coordinator: `/root`.
Reviewer: `/root/ha_capability_reviewer`. Verdict: **APPROVE** for integrated
source verification and packaging. No publication, merge, deployment or live
validation is established by this review.

The reviewer independently inspected the actual source, final evidence, owning
test XML, check logs, archive/distribution contents, prior source approvals and
delivery records. No unresolved actionable finding remains. The implementers
confirmed completion before source-node acceptance; independent reports approve
CAP-PLAN v2, CAP-EXPOSURE, CAP-01 and CAP-02. CAP-03 acceptance can now close the
remaining source-delivery node.

## Exact candidate and verification

Evidence: `docs/reviews/home-assistant-capability-evidence.json`.
Evidence SHA-256:
`145ac9594783ee7df3816bdddf342e927f2fcf4918c87ab7c54cf72483073d3f`.
The reviewer recomputed and matched every one of its **154 source input hashes**,
including all 149 HA inputs and the design/plan/contract/interface/delivery files.
The canonical source-hash aggregate matched:
`e153b71a4725f013310aec12d17c9cbc2df42e76e91cf2cb854913f304f468b9`.

| Check | Independently confirmed result | Log SHA-256 |
| --- | --- | --- |
| Owning HA Java check | 250 tests, 30 suites, zero failures/errors/skips; build successful in 35 seconds | `5b676ccbaacc1a006741fc5822c480cc51131954cb5f4b5a1079d56b2535be77` |
| HA Python check | Ruff check/format and 159 tests on HA 2026.9.4/Python 3.14 | `4d93b86780e83b159e5616c23c4bf1d1a5c1a5835a04f736f2735ae591d2b5bb` |
| Repository format/check/install | Successful in 52 seconds, 144 tasks; full-db disabled | `22254960f57ea916c974b8446116551b235d987585be21efeca2ec42d817bc56` |
| Installed outbound package | Eight offline checks, 57 artifact files | `d5dd27c7e0f5a73751b50c5c417b29fba152c685177dbfb31ee706ec61125dc7` |

All four actual log hashes match the evidence. Initial warning/schema/temporary
path and sanitized-refusal failures were corrected rather than bypassed; the
final successful results belong to the reviewed bytes. Database tests, live
models and device effects were not needed for this adapter-only change.

## Artifact and boundary checks

The reviewer independently matched the Assist archive hash and all **54 entries**
to its complete contents manifest. Its component version is **0.2.0**; the native
exposure command and diagnostics modules match source. All **19** bundled public
Python SDK files match the unchanged owning SDK source, with no extra SDK files.

All **57** installed standalone paths/hashes match the outbound report, with no
extra or missing files. The installed provider guide matches its reviewed source.
The reviewer independently scanned the shipped JAR entries and found no server,
JUnit, Mockito, Testcontainers or MockWebServer production namespaces. Both package
identities are bound in the evidence; source review alone does not prove an
installed or running artifact.

The protected source diff is empty for `sdk/`, `plowshare-server/`,
`plowshare-protocol/` and `integrations/runtime/`. Every operation remains on the
existing public SDK and supported native HA integration boundary. Capability mode
has four policy-aware read tools and rejects legacy data-handler bypass; absent
discovery configuration preserves the original six-tool mode. Exact grants,
disabled control, no automatic live scope expansion and immutable SDK receipt
reconciliation remain intact.

## Delivery and limits

The 41-node graph is acyclic, with valid dependencies and independent implementer/
reviewer identities. Actual accepted report hashes match the ledger. The new
nodes keep merged/deployed/live milestones false. Local design/plan/contract and
delivery links resolve. Earlier diagnostics evidence/review reports remain
immutable historical records; their changed source is revalidated by this new
candidate rather than silently reusing old approval.

Native exposure/identity/unit checks establish a bounded point-in-time read
observation, not an atomic revocation transaction with the Plowshare broker.
Accepted immutable results retain their original reconciliation; later policy
changes block new reads without recollection or retroactive scrubbing. Locator-only
references remain epoch-scoped, unfamiliar field semantics remain unknown, and
complete loss of both stores and all markers cannot be inferred locally.

The evidence is an immutable pre-review capture, so its integrated-review flag is
false at capture. This report and final coordinator ledger record subsequent
approval. Offline fixtures establish neither live command/download discovery,
current house temperature/provider health nor voice/model/device behavior.
