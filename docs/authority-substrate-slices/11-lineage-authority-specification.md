# Slice 11 retained lineage specification

Recorded before enforcement, 18 October 2026. Internal contracts only; no new
public operations, delegation modes, grant-management or budget API.

The original enforcement and unsupported-consumer inventory below are historical.
The [19 October orchestration correction](../standalone-allowance-and-recovery-plan.md#orchestration-contract-correction-19-october-2026)
restores each new orchestration's own allowance while retaining caller authority.
It supersedes the parent-budget requirement for nested/model-triggered workflows;
synchronous delegates continue to share their caller's Budget.

## Supported consumers

- Independent orchestration roots retain their executor, exact home/scope and
  supporting grants before the first turn, in the existing start-receipt
  transaction. The conductor conversation identifies its immutable envelope and
  existing configured conversation allowance. Continuations and explicit failed
  root recovery derive from that envelope, never current ambient grants.
- Synchronous `agent_run`, skill/review children and native-script delegation
  retain the invoking operation as parent. Each child has a distinct durable
  conversation, definition/capability identity and at-most-CONTRIBUTOR action ceiling.
  Declared calls, workspace capabilities, cancellation and acting restrictions
  remain additional intersections. Children spend the existing shared Budget;
  their definitions cannot mint an allowance or propagate a forced MANAGER grant.
- Resumed delegated conversations use their recorded envelope and original
  budget source. Missing or incompatible lineage refuses before any new effect;
  old conversations are never adopted under fresh grants.
- Relay may start a supported independent orchestration root through the same
  owning start receipt. Original receipts remain read-only reconciliation evidence.
  SYSTEM model-only services cannot enter ordinary child delegation.

The retained substrate action ceiling is READ, WRITE, CONVERSATION_CREATE and
RUN_MODEL. Actual native/model actions are checked separately. It grants no
MANAGE_ACCESS, MANAGE_SCOPE, SET_AUDIENCE or ADMINISTER, even when the executor is
human ADMIN or holds a human-forced MANAGER binding. No per-child grants are issued.

## Budget boundary and unsupported consumers

Existing synchronous children already share a Budget and its durable conversation
source. Existing nested/model-triggered orchestrations instead allocate fresh
independent conversation allowances. That is not a retained parent budget. This
slice does not invent reservation, subdivision or a second spending ledger. Such
starts remain explicitly refused until an existing supported sharing/allocation
contract can be retained. Direct user/managed event/Relay roots retain their own
configured operation allowance; they do not borrow a publisher or creator budget.
Existing nested receipts may be inspected; missing lineage cannot resume execution.

Caller-conversation launches (including existing bound/slash launch shapes) do not
currently distinguish an independent operation budget from a model's child budget.
They are conservatively refused by the same rule. No client/API flag is invented
to bypass that missing contract.

## Persistence, ordering and recovery

Immutable conversation envelopes retain the admission, agent, optional parent
conversation, capability fingerprint and exact budget owner. Capability changes
under the same name refuse continuation; changing configured runtime limits does
not replace that identity. The versioned fingerprint frames canonical declaration
strings and sorted sets, excluding prompt/display/sampling and runtime limits; it
does not depend on incidental object formatting. Non-conversation allowances retain their live Budget
identity and cannot be replaced by fresh leases during continuation. Immutable operation-parent edges retain
per-turn/child admission ancestry. Repositories own atomic retention, duplicate
binding checks and bounded ancestry reads. Conversation identifiers are historical
evidence without deletion-blocking foreign keys. Admission/grant rows remain
immutable; a replacement grant cannot repair an ancestor.

Admission and child checks run before log/child creation, after durable binding,
on queued execution, around protected effects and before result disclosure.
Every retained conversation input/output and durable budget owner is checked
against the original home, including ancestor envelopes; deleted inputs refuse.
An already submitted remote call may incur cost; revocation prevents later effects
and disclosure, not remote rollback. Existing request and resume receipts suppress
duplicate execution. A repeated start inspects the original envelope instead of
creating replacement authority, children or budgets. Missing historical evidence
is held/refused; no automatic backfill or uncertain-effect replay.
