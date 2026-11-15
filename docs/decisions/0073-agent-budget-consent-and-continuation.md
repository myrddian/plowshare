# ADR 0073: Agent budget consent continues the retained execution

Status: accepted. The owner explicitly requested extension requests and approvals
for delegated and `send_message` agents, including after exhaustion, with the
original work resumed instead of a replacement task.

## Platform need and contract

An agent's step cap and its tree's canonical model-call allowance are separate
limits. Correct definition defaults and allowance notices help planning, but an
agent still needs a public, actionable way to ask for more. A prose message to a
caller does not grant or durably resume an allowance. This is a general runtime
capability shared by foreground, delegated and messaging work, not an adapter
extension. SDK-only clients cannot implement atomic grants or retained execution
recovery; those belong to the server.

The intrinsic `request_budget` tool requests nonnegative `additional_calls` and
`additional_steps`, with at least one positive, and a bounded explanation. The
harness also requests at exhaustion without another paid model call. The request
is a typed budget variant of the existing approval record. `approval.list` exposes
the exact counts; `approval.answer` accepts `once` or `deny`. Standing command
scopes and prefixes are refused, and budget consent can never authorize a command.
Existing owner/application-manager authorization applies before the budget service
is called. Approval revalidates the original executor, session and immutable
operation envelopes; additional budget does not widen resource authority.

## Grant and live suspension

The repository binds the question to the original canonical allowance and records
the complete root-to-leaf conversation stack at a safe boundary. There is one
unacknowledged request per allowance. The checkpoint retains the original task
turn, cumulative steps, step cap and pending tool calls. This adds one durable
checkpoint at suspension, rather than a database write on every step.

Answering locks the retained request and allowance. The decision and additive call
grant commit in one transaction. Repeating the same decision never grants twice;
an opposite decision is refused. Overflow rolls back the entire answer. Spending
and allowance identity remain unchanged. The requesting frame's step extension
is applied once when the original stack observes the decision. Unlimited limits
remain unlimited; a disabled allowance cannot be activated through this flow.

The virtual thread waits without holding a model slot or database transaction.
Delegated parents and unstarted siblings stay on that same stack. Messaging keeps
its original route and task instance open. Approval wakes the existing execution;
denial prevents another extension request for that live stack and leaves any
already available calls usable for finishing. Cancellation withdraws the request.
A thread interruption without cancellation retains the question for recovery.

## Recovery and uncertain effects

After interruption, the existing execution owner resumes the recorded root:
ordinary turns use their existing conversation, orchestration uses its conductor,
and Relay uses its original message route. A budget approval does not also receive
messaging's legacy default command-continuation allowance. Nested `agent_run`
calls reopen the recorded child conversations with their retained capability
fingerprints and authority, instead of deriving a new execution or logging a new
delegation. The child's result fills its original parent tool slot.

The resumed transcript keeps the original task and all working results across
budget segments; its accounting identity refers to the original task turn. History
read failure refuses recovery rather than starting with empty context. Calls known
to be unstarted after the suspended call are returned explicitly as unexecuted;
the model may reconsider them. Completed effects are not repeated.

Recovery is fenced as started before resumed inference or effects. A further
interruption during that restoration can leave outcomes uncertain. It is reported
and the original message is settled unavailable without replaying effects or
creating another task. Expired/stopped messages and revoked execution authority
cannot resume. Background SYSTEM work and board scheduling windows do not receive
agent budget consent; their existing lifecycle owners remain authoritative.

An internal mandatory reviewer has no parent model tool slot. Live budget consent
can resume its waiting stack, but automatic restart recovery of such a checkpoint
is currently refused with the retained child and an inspection diagnostic. It must
not silently discard the reviewer, recreate its work, or certify an unreviewed
answer. Restoring that internal review requires an explicit retained-review
checkpoint contract beyond the ordinary delegated-call slots.

The budget variant is optional in shared approval replies so older command-only
records retain their wire shape. TypeScript, Python, Go and .NET contracts are
regenerated from the owning schemas. Client prompts display exact counts and
offer only one-time approval or denial.

## Verification

Mocked repository tests cover live waits, denial, cancellation, retained recovery,
authority refusal and uncertain-effect settlement. Scripted runtime tests cover
shared delegation exhaustion and restart recovery without repeating a completed
tool effect, plus message agents requesting before exhaustion. PostgreSQL tests
cover migration mapping, concurrent/idempotent answers, transaction rollback,
overflow and source ownership. Client tests cover typed counts, malformed replies
and the available decision controls. These do not establish live model quality or
constitute deployment verification.
