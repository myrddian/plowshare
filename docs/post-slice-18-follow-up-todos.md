# TODO: remaining post–slice 18 runtime and Desktop work

Status: deferred handoff, 19 October 2026. Documentation only; implementation is
for separate tasks. Verified source baseline: `b333632d7eeb5d98dfaf82223e221f611a7f9b97`.

The current branch closes the Global knowledge read and allowance-storage
follow-ups. Orchestrations have their own selected allowances, `agent_run` shares
its caller's Budget, and existing automatic/manual increase and question-routing
behavior is preserved. These are not unfinished items to redesign in the work below.

## TODO 1: complete account-delegated runtime capabilities

- [ ] Implement and verify the existing
  [delegated runtime authority handoff](delegated-runtime-authority-todo.md).

**Problem and impact:** an Application acting for a user can still reject that
user's agent merely because it declares skills, orchestrations or other capabilities
outside the retained runtime's supported subset. Ordinary execution working does
not prove that the account-delegated path supports those same capabilities. This
is a functional limitation, not an optional accounting enhancement.

**Why deferred:** safely removing the blanket guard requires carrying the acting
proof and retained authority through the real capability lifecycles. Copying more
fields into a second configured runtime, or simply removing the guard, is not a
complete repair. This was explicitly handed over separately to keep the current
branch bounded.

**Scope and acceptance:** use the existing runtime and public contracts. Support
ordinary DIRECT and worker skills under the original actor, subject, scope and
capability restrictions. Verify expiry, revocation, concurrent users, child work,
approvals, cancellation and receipt-safe recovery. Publish an explicit support
matrix for orchestration, reviewer, fallback, native/script, board and background
paths; any remaining refusal stays an unfinished item. Preserve independent
orchestration funding and synchronous delegate sharing. The linked handoff owns
the detailed source map and acceptance cases.

## TODO 2: expose Global account administration in Desktop

- [ ] Add a consistent Global account destination to Desktop's existing supported
  document management workflows, using existing server operations.

**Problem and impact:** the collection UI offers Personal, Project and
“Available to everyone” (`global_read`). The latter selects a readable audience;
it does not select Global account custody for administration. Desktop therefore
does not consistently let a human ADMIN browse Global-owned documents and choose
Global as the destination when adding content. Administrators must use other
existing supported clients/operations or the Global manual installer. Ordinary
Global-readable discovery already works and must remain distinct.

**Why deferred:** server authorization and read-selector adoption did not provide
the full Desktop management workflow. This needs deliberate destination controls,
request/receipt handling and UI verification; labelling the whole client-adoption
item complete would overstate what shipped. It is being handed off rather than
extending this branch further.

**Existing implementation to inspect:**

- [Collection selection and authoring controls](../plowshare-desktop/src/renderer/information.ts)
  currently construct Personal/Project/Global-read selections, without an explicit
  Global account management option.
- [Import recovery contract](../plowshare-desktop/src/import-recovery-contract.ts)
  explicitly refuses account destinations. Trace this with the portable import
  lifecycle before changing it; account selection must not imply import support
  that the server does not actually provide.
- [Global knowledge read contract](global-knowledge-read-contract.md) documents
  the existing explicit Global account selector and its separation from GLOBAL_READ.
- [Information access](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java)
  owns the existing server-side authorization and writable-destination boundary.

**Scope and acceptance:**

- Expose Global account custody as an administrative destination using the
  existing selector. Keep it separate from “Available to everyone”; changing a
  document's audience must not silently move its custody.
- Let authorized human ADMINs browse and use existing supported admission,
  editing and lifecycle operations against Global-owned documents. Keep server
  authorization authoritative; UI visibility alone is not a permission check.
- Carry the selected destination through existing request validation, IPC,
  SDK calls, refresh and durable request/receipt recovery. Reconnect and uncertain
  delivery must reconcile rather than replay a mutation.
- Test Global ADMIN success, non-admin refusal, Personal/Project isolation,
  destination switching with stale replies, and unchanged Global-readable discovery.
  Add focused renderer/client tests and a native Desktop smoke using isolated
  fixtures. Database tests are needed only if an actual persistence invariant changes.
- Do not add public APIs, Global execution, a Global schema/type registry,
  universal SDK parity or unrelated client features. Report any missing server
  capability explicitly before expanding scope. Keep unsupported actions unavailable.

## Separate deployment work

Local checks do not establish deployment adoption. Migration with stopped old
workers, backup/restore and live restart/recovery acceptance remain deployment
tasks under the [allowance cutover notes](standalone-allowance-and-recovery-plan.md).
Monetary accounting/pools remain deferred and are not prerequisites for these TODOs.
