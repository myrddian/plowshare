# Slice 10: Relay and message-triggered operation authority

Status: proposed scope, 17 October 2026. Planning only; no implementation,
new public capability or deployment is authorized by this document.

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

These are future checks, not tests run for this planning document. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

Inventory the existing message/Relay effect families, classify read versus mutating consumer operations and retain all independent provider/route fences.

## Rollout and rollback

Keep original invocation/delivery/consumer identities and causal chains. Incompatible work is fenced and read-only reconciled, not reissued under different authority.

