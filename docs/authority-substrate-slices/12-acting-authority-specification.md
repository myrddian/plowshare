# Slice 12 acting authority specification

Recorded before enforcement changes, 18 October 2026. This specifies the existing
acting consumers; it does not authorize additional APIs or targets.

## Supported operations and targets

| Existing operation | Target | Substrate actions and existing conditions |
| --- | --- | --- |
| `conversation.turns` | Exact conversation in a consented project, or the subject's Personal home | READ; live actor and subject project authority, or subject-only Personal authority; scope checked again before returning history |
| `acting.turn.start` | New subject Personal conversation, or an existing conversation bound to the same proof | READ, WRITE, RUN_MODEL for the supported model agent; CONVERSATION_CREATE before opening a new conversation and when the agent can create synchronous children |
| `acting.turn.receipt` | Original actor/request UUID and proof binding | READ; retained receipt inspection never resubmits work |
| `job.status` | Job correlated with that retained acting receipt | READ; existing subject/job lookup remains required |
| `job.cancel` | Same correlated job | WRITE for cancellation and READ for its returned job view, with existing lifecycle checks; no inference or conversation creation permission is implied |

READ and WORK remain internal semantic classifications. READ maps to READ and
WORK maps to WRITE. Additional internal classifications name CONVERSATION_CREATE
and RUN_MODEL separately; none is a new public operation or role. The policy must
refuse every other classification before consulting resource authority.

## Authority intersection and retention

Every action intersects active Application credentials, enabled human subject,
active manifest grant and eligibility, exact unexpired/unrevoked proof, token
scope and the admitted scope/role ceiling. Each action also checks the live scoped
substrate. Project reads require both subject and Application actor authority,
including the actor's service-token owner and current token/manifest role ceiling.
Personal authority requires the exact subject's Personal scope and current
ownership; the Application gets no membership or grant in that home.

Before creating a conversation or committing a new receipt, check the actual
agent actions. Existing receipt retries keep their read-only reconciliation
semantics and do not resolve/restart an agent. After commit, queued execution
retains its fixed action set and proof check. The existing common operation
admission pins the subject's supporting grants; it supplements, never replaces,
the acting proof. Tools and synchronous children retain the ancestor checks,
exact Personal resources, agent declaration, subject executor and shared budget.
Usage already incurred is settled even when later authority checks withhold an
answer. Closing/expiring a socket context prevents new requests; admitted work
continues only under its durable proof, not that ephemeral context.

No permission, membership, grant, budget or receipt schema changes are needed.
Existing admissions cannot be recovered by stripping their proof, changing the
executor to the Application/SYSTEM, or replaying uncertain work.

## Preserved gaps

Project history reads are supported; project acting execution is not. Global
knowledge is not an implicit additional source. Information processing, Relay,
orchestrations, schedules, approvals, subscriptions, native/scripted agents and
other independent consumers remain closed under acting authority, even where
direct callers have acquired support in earlier slices. Existing tool grants,
provider and filesystem fences remain independent of account actions. SDK/client
parity, new consent workflows and public operation additions remain out of scope.
