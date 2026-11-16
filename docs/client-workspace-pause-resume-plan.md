# Pause work when a required client workspace disconnects

Status: implemented for live workspace-dependent execution and client reopening.
Safely paused ordinary turns, submissions and events also have server-restart
recovery. The implementation limits below distinguish that from workflow-owned
restart recovery.

## User-visible contract

Plowshare owns execution on the server. A client may supply a filesystem or
execution environment that a particular run needs. Losing that provider pauses
the dependent work, preserves its progress and automatically resumes it when the
same authorized workspace is available again.

The dependency is on the machine supplying the workspace, independently of the
machine displaying the conversation. For example, a phone can control a run whose
files are served by a laptop. Closing the phone leaves the run working; closing
the laptop pauses it. Reopening the laptop's client can resume it even though
the new process has a different transport session ID.

| Situation | Required behavior |
| --- | --- |
| A viewing TUI or GUI disconnects; all execution dependencies remain available | Continue on the server |
| A required client filesystem or command provider disconnects | Pause dependent work at a safe boundary |
| The same authorized workspace returns | Automatically continue the retained work |
| A different folder, machine or unauthorized account connects | Keep the work paused; do not substitute that provider |
| Personal or an Application uses only server capabilities | Continue independently of client connections |
| A Personal run explicitly depends on client files or commands | Apply the same dependency pause rule |
| A delegated child loses its workspace | Pause the affected child and its waiting dependency chain; unrelated jobs continue |
| The user cancels paused work | Finish as cancelled; reconnect cannot restart it |

Do not infer a mandatory client dependency merely from the presence of a
viewer, a file-capable socket or an unused optional file tool. Capture required
workspace dependencies through the run's admitted execution context and extend
them when work deliberately acquires another provider. Client-sourced definitions,
rules, hooks and execution environments also count when their availability is
required for the next effect.

## Original implementation and gap

- [`JobRuntime`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java)
  converts file-session loss into a terminal `SESSION_GONE` outcome, including
  propagation from delegated children.
- [`Job`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Job.java)
  exposes only `RUNNING` and `DONE`. A durable job record does not reconstruct a
  worker's stack after it has stopped.
- [`PresenceRegistry`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/session/PresenceRegistry.java)
  tracks live workspace declarations. Withdrawal changes routing for future work;
  it does not migrate outstanding requests to another connection.
- [`FileChannelHandler`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FileChannelHandler.java)
  fails outstanding requests when the file socket closes. Its bound channel
  deliberately refuses replacement connections, preserving context-read fences.
- [`ClientProjects`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ClientProjects.java)
  isolates client-only project keys by authenticated session. A display name is
  insufficient to identify a returning client-only project.
- The [Node session composition](../sdk/node/src/session.ts) and
  [Desktop client](../plowshare-desktop/src/client.ts) mint transport session IDs.
  Waiting for the old ID alone cannot cover closing and reopening the client.
- [`BudgetCheckpoint`](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/BudgetCheckpoint.java)
  and the [budget continuation contract](decisions/0073-agent-budget-consent-and-continuation.md)
  already retain parts of an execution stack. Reuse the owning continuation
  machinery where its semantics fit; a disconnected workspace is not a budget
  approval and must not create an approval or grant extra budget.

Conversation history already recorded on the server remains available. The
original gap was retaining and continuing execution rather than recreating the
user's task from its opening prompt. The following sections describe the target
contract; the implementation notes describe its current coverage.

## Implementation and current limits

`WorkspaceRuns` retains the live stack, dependencies, source fingerprints and
authority validators. `WorkspacePauseRepository` persists safe points in
`workspace_pauses`; `WorkspaceRecovery` binds a private capability to the original
logical session in `workspace_recovery_bindings`. Reopening an authenticated
TUI/GUI and attaching the same workspace recovers client-only project isolation
as well as ordinary rooted projects. Co-located clients use separate private
capability slots and do not repeatedly displace one another.

File connection recovery retries presence establishment, never an operation.
Bound source snapshots retain their connection-generation fences. A failed pure
snapshot may be rebuilt once under its remaining active budget. Unknown writes
and commands retain their original correlation and operation kind and remain
blocked; cancellation withdraws the checkpoint durably. The implementation does
not infer a successful remote effect or physical process termination from socket
loss or cancellation of the conversation.

A live paused owner renews a finite lease, preventing another server process from
claiming its continuation. On server shutdown a safely checkpointed worker drains
without publishing a terminal outcome. After restart, recovery waits for its old
lease to expire and for the required authenticated attachments to return, then
claims the generation before execution. Pending call slots and completed results
are retained. Restart recovery explicitly marks unstarted siblings unexecuted;
the agent can decide whether a fresh invocation is needed. A paid final answer
retained at suspension is returned without another model call.

Live suspension/resumption preserves workflow owners because their existing stack
and callbacks remain in place. **Automatic server-restart recovery currently
supports `TURN`, `SUBMISSION` and `EVENT` roots.** Messaging, orchestration, board
and other workflow-owned roots remain blocked after server restart rather than
being continued through an ordinary turn and bypassing their lifecycle owner.
An internal mandatory review with no durable parent tool slot also remains
blocked, following the existing continuation restriction. These cases need
owner-specific restart continuation support before that target contract is fully
implemented. This restriction does not prevent reopening the serving client while
the server remains running.

Defaults are configurable under `plowshare.jobs.workspace`: `sql-timeout=2s`,
`snapshot-timeout=30s`, `cancellation-check=100ms`, `owner-lease=30s` and
`recovery-delay-ms=1000`. Individual SQL units use the remaining transaction and
statement budget; the platform's existing connection-admission enforcement gap
still applies. Recovery capabilities live in the account/server's private local
configuration, never in the project. Losing that configuration prevents automatic
recovery of the old attachment; a matching folder name is not a replacement proof.

## Suspension and recovery

Introduce a typed runtime dependency contract and a specialist repository for
retained suspension state. Transport adapters report provider lifecycle events;
the execution owner decides which work must pause. Clients display server state
and supply capabilities; they do not own a second durable runtime.

Use explicit lifecycle transitions:

`RUNNING -> PAUSING -> PAUSED -> RESUMING -> RUNNING`

`PAUSING` means no further work is being admitted while an active operation
settles. `PAUSED` means a safe continuation has been retained and active resources
have been released. `RESUMING` covers claiming that continuation and revalidating
its inputs and authority. Cancellation is valid throughout these states. Failure
to revalidate leaves an actionable blocked reason rather than a successful resume.

At a safe boundary retain the original job/conversation identity, turn and
delegation stack, completed tool results, pending call slots, remaining limits,
usage accounting, original admission and required workspace identities. Preserve
existing approval and workflow gates. Recovery must not open a replacement task,
reset allowances, double-charge completed calls or rerun settled tools.

Suspend before the next model call or effect, including between tools in a
single model response. A model call already dispatched can settle under its
existing finite budget and accounting, but its returned tool calls must not start
while a required provider is absent. Keep its completion for continuation and
preserve the existing output and authority fences.

A paused run holds no database transaction, inference slot or active command
permit. Retained state can outlive a client outage without an arbitrary job
timeout. Each detection, checkpoint, recovery claim, authority check and cleanup
step has an explicit finite budget. Recovery must not reset an active operation's
deadline or treat caller timeout as proof of remote cancellation.

Returning presence is a wakeup hint. Atomically claim a persisted continuation
generation before recovery; duplicate declarations, rapid connection flapping,
late disconnect events and concurrent reconnects must not create two workers.
If a server restarts while work is already safely paused, recover from its
retained state. An uncheckpointed server crash still requires the existing
interrupted-work handling; this feature cannot promise recovery of an arbitrary
in-flight stack.

## Workspace identity and authority

Keep durable workspace identity separate from ephemeral socket/session identity.
Bind a returning provider to the admitted account, project, machine and root
through an authenticated attachment/recovery capability. A hostname or path claim
alone is not proof of identity. Persist any client recovery credential in the
owning credential store and keep it out of project files, logs and presentation
payloads. Retain client-only project isolation; do not replace session scoping
with account-wide lookup by project label.

An already accepted, equivalent provider may satisfy a dependency only if the
retained binding explicitly permits it. A different checkout, moved root or
replacement machine requires explicit rebinding and fresh validation. A matching
friendly project name never authorizes a switch. Union workspaces preserve their
existing provider and synchronization contracts.

Before continuing, recheck current executor/subject authority, project membership,
tool grants, application ceilings, workspace fences and retained admission.
Re-establish source/rule/hook snapshots through their owners and invalidate old
file snapshots across the connection generation. If inputs changed, validate the
continuation against them; fail closed with a visible reason when compatibility
cannot be established. Do not weaken `SessionChannel.bound` or reuse old allow
decisions to make reconnect succeed.

## Interrupted file operations and commands

Distinguish operations proved unsubmitted, completed operations with retained
results, and operations whose effects are uncertain. Record request identity and
submission/effect state at the owning boundary; an exception type alone does not
prove that nothing ran.

- Unsubmitted pending work may proceed after recovery under fresh fences.
- Completed work retains its result and is not executed again.
- Writes, moves, deletes, commands and other actions possibly delivered before
  disconnect retain an unknown outcome. Never replay them automatically. Reconcile
  through their owning receipt or explicit inspection; if the outcome cannot be
  established, retain a blocked pause requiring resolution. A generic automatic
  readback is not sufficient proof for every mutation.
- Read-only operations can be repeated only through an explicit bounded policy
  that discards the incomplete generation and builds a fresh authorized snapshot.

The same rule applies to commands that might continue on the disconnected
machine: loss of the reply does not prove the process stopped. Track physical
execution and cancellation separately from the paused conversation.

## Delivery and acceptance

Implement as dependent changes, with review of each contract and its evidence:

1. Define dependency identity, suspension DTOs and atomic repository transitions;
   add new Flyway migrations for persisted state. Specify cancellation/recovery
   races and uncertain-effect handling before implementing transport recovery.
2. Integrate safe suspension with the runtime, delegated work and existing
   continuation owners. Cover model completion and partial tool batches without
   replay, loss of accounting or a replacement job identity.
3. Add authenticated provider recovery and workspace revalidation through the
   shared SDK attachment contract, including process restart and client-only
   projects. Keep the viewer separate from the serving provider.
4. Update shared job observation and affected TUI/GUI/CLI consumers together.
   Show the pause reason and required workspace, support cancellation and recover
   current status after reconnect. Paused jobs remain active for conversation
   exclusion and controls, and are never interpreted as completed or lost.

Default tests use mocked repositories, fake transports and controlled clocks.
Cover viewer-only disconnect, server-only Personal work, serving-provider loss,
reopening a client under a new session, wrong-account/root rejection, changed
rules or revoked grants, cancellation during pause/resume, duplicate reconnects,
flapping, nested delegation, multiple dependencies, in-flight model completion,
partially completed tool batches and uncertain writes/commands. Verify paused
work releases active resources and completed operations are never replayed.

Select real PostgreSQL tests only for new migration, atomic recovery claim,
rollback and persistence assertions that require it, using `@Tag("full-db")`,
`-PfullDb` and the smallest relevant `--tests` selection. Run the affected client
checks and normal repository `check`; mocked tests do not establish a live
disconnect/reconnect integration.
