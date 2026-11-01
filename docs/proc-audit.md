# Processes and conversation audit

Open **Processes** in Desktop or the console, or `/proc` in the TUI. The default
inventory includes every retained, authorized, non-archived conversation origin:
human chats, submissions, delegation, document workers, events, orchestration and
other internal logs. It has no implicit date cutoff. Finishing a job does not
archive its conversation. Archive state is resolved from the root for the whole
delegation tree; existing retention still controls what evidence remains.

**My work** preserves conversation/log custody and current project membership.
**Server** is an explicit administrator metadata view, including SYSTEM calls.
Fleet metadata visibility does not grant access to somebody else's trajectory,
document content or private logs. Service tokens cannot use this human operator
surface. Every page checks access again; a cursor is a position, not a grant.

## Trace what is happening

Admission/log custody is shown as **Admission owner**; it is not proof of who
triggered a particular operation. Retained acting-work bindings separately identify
the Application, authenticating actor and acting subject. Missing initiation
evidence remains unrecorded. Execution principal comes from captured accounting.

Select a conversation to inspect its descendants, source revision when bound,
model calls, upstream attempts and tool receipt metadata. Follow **Trace parent**,
**Trace root** or **Trace causal chain** to navigate retained relationships.
Recorded causes and Relay causation connect separate conversation roots when
that evidence exists. Missing legacy provenance remains unknown; timestamps do
not establish causation. Desktop can open an owned trajectory through its existing
authorization path.

Conversation state and evidence appear together:

- `RUNNING`: a job in this server's live runtime snapshot.
- `PROCESSING`: a retained, unexpired document-processing lease.
- `QUEUED` / `BLOCKED`: persisted processing state; expired leases awaiting recovery
  are queued, rather than claimed to be executing.
- `RECORDED_ACTIVE`: the accounting projection retains an unfinished call. This
  is not an independent runtime heartbeat.
- `UNKNOWN`: a job has no terminal receipt and no matching local runtime evidence.
- `COMPLETED`: a completed turn or job was recorded; the ending remains separate.
- `IDLE`: no execution evidence establishes one of those states.

The GUI waits five seconds after a refresh or selected inspection completes before
starting its next periodic read. Slow reads do not accumulate timer catch-up work.
Overlapping inspections share only an in-flight request for the exact same
selection; later requests reauthorize independently. Conversation counts and pages
reuse one statement-local authorized selection, including when a cursor page is
empty. The conversation page shares one narrow revision selection for its distinct
roots and conversation IDs; source metadata and processing state never rescan the
revision corpus per row. Inventory recursion shares a narrow child-link snapshot;
explicit root/subtree reads retain their indexed walk.

Call inventory pages do not need a full conversation count. They use ordered call
candidates with current owner/membership and cycle-safe parent checks, then merge
bounded attributed and orphan branches. Explicit root/subtree selectors use the
indexed subtree instead. Cursor-bearing call pages select authorized conversations
once and seek their direct call index, preserving arbitrary text cursor semantics
without walking the entire UUID call index past off-cursor rows. Recorded accounting roots never authorize a call; the
current conversation root controls archive visibility. Attempts are hydrated only
for the authorized page and keep their independent per-call cap.

Every server request still checks account and fleet authority, and shared
read admission bounds work across clients and sockets. It labels a
failed read stale/unavailable and says current activity is unknown. Changing
selection or account discards old responses. Closing any audit view stops only
observation; reads never start work, contact a model, retry an effect or resume a job.

## Queues and iterations

**Queues / progress** includes full-selection totals as well as paged resource
details. Totals do not shrink to the page size. Units remain separate:

- Current-generation processing stages: ready, pending, leased, blocked,
  failed/cancelled and skipped. Older generations do not inflate the queue.
- Committed paragraph summaries: total paragraphs, completed and unfinished.
  Later section/chapter/document summaries are additional work.
- Chunk-vector checkpoints: current configured code/prose space and source
  revision, including staged vectors. With no configured slot, legacy vector
  checkpoints apply. Counts for different slots must not be added as distinct
  source items.
- Automatic tagging/grouping revisions eligible for a sweep but not yet enqueued.
  Admission and audit share their owning eligibility predicates. A sweep's
  bounded batch does not cap the reported eligible backlog.
- In Server visibility, each local model-pool lane's queued requests, approximate
  running occupancy and capacity. Those are local executor observations, not the
  upstream provider's internal queue.

An unfinished paragraph/chunk can already be in flight; per-item running counts
are unknown where no durable item claim exists. Queue admission times are not
retained for every stage, so waiting age and ETA remain unknown. Source creation
time is not substituted for enqueue time. Arbitrary script/agent loops and
open-ended input streams have no universal item total; their call and conversation
history remains inspectable without inventing a denominator.

## Investigate repetition

Inspection exposes repeated agent/operation/pool/model calls and causal chain
depth as investigation signals. They do not prove a loop: ordinary iterations,
long conversations and retries can be legitimate. Compare the causal links,
attempt outcomes and committed queue progress across refreshes. Calls, attempts,
work items and inclusive execution trees are different units. PROC does not
automatically cancel, replay or change budgets in response to a signal.

## CLI and SDK

Use the existing configured connection or supply the CLI's explicit connection
options. These are read-only WebSocket operations:

```sh
bin/plowshare-cli --json proc list '{}'
bin/plowshare-cli --json proc list '{"visibility":"server","active":true}'
bin/plowshare-cli --json proc inspect '{"conversation":"CONVERSATION_ID"}'
bin/plowshare-cli --json proc list '{"root":"ROOT_CONVERSATION_ID"}'
bin/plowshare-cli --json proc list '{"causation":"CAUSATION_ROOT_ID"}'
bin/plowshare-cli --json proc queues '{"visibility":"server"}'
bin/plowshare-cli --json proc tools '{"conversation":"CONVERSATION_ID"}'
bin/plowshare-cli --json proc calls '{"conversation":"CONVERSATION_ID"}'
```

Selectors are `visibility` (`mine` or `server`), `project`, `conversation` (its
subtree), `root`, `causation`, `active`, `after` and `limit` (1–100; default 50).
`active` includes unresolved evidence, rather than claiming that every retained
unfinished request is running. Each page returns its resolved selection, snapshot
time, coverage and continuation cursor. Conversation, call and queue pages also
report accounting capture/projection health. Supply `cursor` as `after` with the
same selection. Ordering is by stable identifier; it is not execution/causal order.

`proc.inspect` returns independently bounded conversation, queue, call and tool
sections. Follow each section's continuation through its matching list operation.
Tool receipt continuation uses `proc.tools`.
At most 100 attempt observations are included per call; compare `attempts` with
`observations.length` for explicit truncation. Tool receipts include names and
whether a result was recorded, not arguments, result content or proof of success.

TypeScript exposes `ProcClient` and typed DTOs. The same operations and constraints
are generated into the native SDKs. No prompts, model output, provider error
bodies, credentials or credential-bearing endpoint URLs are returned.
