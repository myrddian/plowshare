# Slice 10: Relay and message-triggered operation authority

Status: implemented in this branch, 18 October 2026, under explicit authorization.
No new public API or deployment. New Relay orchestration starts remain fenced
pending slice 11's retained authority lineage; original receipts remain inspectable.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Integrate common authority at existing message/Relay effect boundaries while leaving the Relay/provider capability model intact.

**Depends on:** [07 — All-inference dispatch checks and operation budgets](07-inference-and-operation-budgets.md), [08 — Sandboxed SYSTEM services and independent processing](08-sandboxed-system-services.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Apply applicable READ/WRITE/control actions to current scoped resources and receiver-triggered operations; admission of a model receiver checks its execution authority and configured operation budget separately from publication.
- Preserve port/topic/account/group bindings, consumer leases, acknowledgements, ancestry, deadlines and generation checks. Cursor/ack mutations are not authorized by READ alone.
- Retain sender, receiver executor and original request/effect correlation across existing durable recovery. Message text cannot select SYSTEM, widen targets or lend sender grants.
- Keep existing manager/configuration controls and provider credential/invocation eligibility distinct from ordinary resource WRITE.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [ProjectRelayPorts.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/ProjectRelayPorts.java) | Existing explicit ingress/egress and principal/group route bindings. |
| [ProjectRelayOperations.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/ProjectRelayOperations.java) | Current operational controls retain their own domain semantics. |
| [WorkRelayReceiver.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/WorkRelayReceiver.java) | Existing receiver dispatch/reconciliation and uncertainty handling. |
| [ProjectMessageRouting.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/ProjectMessageRouting.java) | Current message destinations and source routing. |
| [ProjectRelayToolInvocations.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ProjectRelayToolInvocations.java) | Existing provider invocation fences/receipts; no account-role replacement. |

## Exclusions and capability boundaries

No Relay redesign, new ports/topics wildcard, tool-level RBAC, provider API, message broker, account-based credential bypass or uncertain-effect replay.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Mock source/receiver matrices prove publication cannot confer RUN_MODEL, SYSTEM identity, provider grants or cross-project access.
- READ-only access cannot acknowledge/advance a consumer; supported controls require applicable WRITE/domain authority without inventing new controls.
- Uncertain effects are inspected via original receipts, never republished or resubmitted.
- Selected PostgreSQL tests only where delivery/consumer/authority-reference persistence or lease races change.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [WorkRelayReceiverTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/WorkRelayReceiverTest.java)
- [ProjectRelayPortsTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/ProjectRelayPortsTest.java)
- [ProjectRelayOperationsTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/ProjectRelayOperationsTest.java)
- [ProjectRelayToolInvocationsTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/tools/ProjectRelayToolInvocationsTest.java)

**PostgreSQL justification:** Conditional: existing durable Relay/provider stores require selected tagged tests only when their persistence/transaction behavior changes. Pure authorization/route tests use mocks.

The implementation evidence below distinguishes checks run from the original
planned matrix. Existing suite links do not imply every test is non-database.
Unsupported paths remain explicit and uncertain effects are never replayed.

## Specification required before implementation

Inventory the existing message/Relay effect families, classify read versus mutating consumer operations and retain all independent provider/route fences.

The [effect-family specification](10-relay-authority-specification.md) was recorded
before enforcement and includes the shared person-notification callback.

## Rollout and rollback

Keep original invocation/delivery/consumer identities and causal chains. Incompatible work is fenced and read-only reconciled, not reissued under different authority.

## Implementation and verification

Project port publication rechecks WRITE and exact registered project identity
after parent/source lookup. Consume/acknowledge retain their existing work and
configured account/group/lease fences; READ alone cannot mutate their cursors.
Manager controls and provider credentials, invocation deadlines and UNKNOWN
receipts retain their existing independent contracts.

Work receivers check current route/account eligibility before even disclosing a
known receipt, and again after recording intent before submitting an effect.
Agent/native work uses common JobStore admission and operation budgets. New
orchestration starts refuse explicitly until slice 11; existing owning receipts
can still be read-only reconciled. No receipt or unsupported recovered authority
is replaced to make work execute.

Board message turns use the bound participant account and existing lease, never
the sender or conversation creator. Shared result/approval/board notifications
carry their known recipient into execution. Missing recipients cannot start model
work; lost READ denies disclosure, while READ without work permission can use the
existing inbox fallback without inference. The orchestration notification adapter
only propagates that account; this does not implement orchestration lineage.

Focused mocked tests cover receiver-versus-creator separation, missing recipients,
READ-only consume/ack refusal, revoked authority and project replacement around
intent/source lookup, protected receipt disclosure, result-notification admission,
provider fences and uncertain-effect no-replay. No PostgreSQL run is required for
this slice: Relay/message persistence, leases and receipt transaction predicates
are unchanged.

`format` and the focused tests passed. Repository-wide `formatCheck check` passed
with 5,785 default server tests, zero failures and zero skips. These checks prove
source/test behavior, not a live deployment or the remaining cutover slices.
