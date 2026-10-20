# Slice 07 Bounded concurrent MCP calls

Status: source implemented; verification and rollout are tracked in the
[validation record](../async-request-dispatch-validation.md).
This separates MCP execution from the view work in the
[umbrella plan](../async-request-dispatch-plan.md). It depends on slice 01's
correlation/negotiation contract, slice 03's server pipeline and slice 05's SDK
deadline/cancellation semantics. Slice 04 supplies authoritative resource rules.
Use internal Plowshare contracts; Ciotola/Pasta remains a design/source reference,
with no external dependency or compatibility adapter in the bridge.

## Outcome and source assessment

One held tool call must not prevent an unrelated admitted call from returning.
Each JSON-RPC request owns a bounded task, deadline and response reservation.
Physical stdout records remain serialized and valid under backpressure.

| Owner | Verified behavior and implementation target |
| --- | --- |
| [MCP entry point](../../plowshare-mcp/src/main.ts) | The stdin loop awaits `respond` and stdout for each complete line. A single deadline timer calls the process-wide `stop`, aborting the shared session. Split process lifetime from request lifetime. |
| [Protocol](../../plowshare-mcp/src/protocol.ts) | Parses/validates JSON-RPC, handles initialization/ping/tools and returns no reply for notifications. Cancellation notifications currently receive no lifecycle handling. Retain framing and reviewed tool error presentation while adding explicit dispatch state. |
| [Adapter contract](../../plowshare-mcp/src/adapter.ts) | Typed `Backend.invoke` dispatches operations; `root` changes shared presence, and `agent_run` samples `runSession`. Define request-scoped dependencies before concurrent execution. |
| [Node session](../../sdk/node/src/session.ts) | One event connection and local file presence are shared. `root` can replace the served project; `runSession` reflects current presence/loss. Preserve credential, file and scope ownership. |

The existing [socket tests](../../plowshare-mcp/src/socket.test.ts) already cover
transport uncertainty and rooted file access. Extend them; do not replace the
bridge or add another raw WebSocket transport.

## Contracts and ownership

Define narrow internal capabilities for request admission, task settlement,
request cancellation and a complete-record writer. Keep raw JSON in the owning
protocol decoder; application tasks receive validated typed command variants.
Task metadata includes the validated JSON-RPC ID, generation, deadline, delivery
state and response reservation, without logging arguments or credentials.

Keep JSON-RPC IDs distinct from Plowshare envelope IDs, packet transfer IDs and
durable mutation receipt IDs. Validate supported ID forms and limits; reserve
active IDs atomically, reject duplicates before invoking a backend and never
overwrite an existing task. A duplicate active ID terminates the stream through
the owned shutdown path; do not emit a second ambiguous reply under that ID.
Started remote work retains unknown delivery and is not replayed. Specify
reuse/late-cancellation policy so an old
notification cannot cancel a different incarnation of a reused ID.

The process controller owns SIGINT/SIGTERM, terminal stdin/output failure and
shared-session close. A request controller owns only that call's waiting state.
One call timing out or being cancelled must not call process-wide `stop`, close
the shared socket or cancel another admitted task.

## Implementation steps

1. Decode complete stdin records while maintaining the existing encoded input
   buffer ceiling. Admit tasks before allocating retained arguments/workers.
   Configure positive finite task, queued-byte and response-byte limits. Reject
   overload before backend submission with an explicit documented outcome.
2. Keep initialization and lifecycle notifications in a short control path.
   Select an explicit uninitialized/initializing/ready/draining/closed state
   machine; validate legal method ordering against the advertised MCP version.
   Process `notifications/initialized` and cancellation without creating replies
   to notifications. Preserve the reviewed protocol/menu and tool-error mapping.
3. Start independent ready requests without awaiting earlier tool completion.
   Each admitted request gets an absolute deadline spanning queueing, execution
   observation and response handoff. Snapshot the immutable invocation context
   before crossing asynchronous boundaries.
4. Thread request cancellation/deadline options through the typed backend and
   shared SDK capability introduced in slice 05. Cancellation before possible
   submission is `NOT_SUBMITTED`; after submission it detaches with `UNKNOWN`.
   Explicit job cancellation remains a separate operation selected by the caller.
5. Resolve reply/deadline/cancellation races through one terminal transition.
   Clear task registrations/timers once, retain only bounded late-reply state
   and use the advertised MCP contract for cancelled responses. Preserve unknown
   mutation outcome text and any caller-owned durable receipt identity.
6. Queue complete encoded response records through one writer that observes
   `process.stdout.write` backpressure. Reserve response capacity at admission;
   include queued records and active write buffers in accounting. A slow consumer
   must not accumulate an unbounded completed-result queue or interleave records.
7. Drain on EOF or process stop: stop admission, settle never-started requests,
   detach started observers, flush owed records within a bounded deadline and
   close the shared session once. Handle a final unterminated input line under
   the same admission rules. Fatal output failure has one owned shutdown path.

## Shared session and workspace hazards

Concurrent reads may share the authenticated connection. Their explicit project
or Personal destination and per-request acting selection remain immutable; never
implement concurrency by changing a shared current account, scope or acting user.
Server authorization remains current when the operation executes/discloses data.

`project_root` changes the one served local presence. It cannot race freely with
another root or with an `agent_run` whose file session is sampled from that
presence. Introduce a narrow local dependency lane for presence changes and
session-dependent admission, preserving receive order for those conflicting
calls. Keep unrelated archive/status reads eligible while that lane is occupied.
Do not hold its lock while waiting for a durable job to finish.

A failed or cancelled root may already have changed presence. Reconcile through
the session owner's current/unknown state; never assume the old root survived or
silently reroot/replay. A subsequent run must wait for the root transition to
settle and validate its selected project/session. This lane establishes local
admission order, not an immutable filesystem lease for a running job. Preserve
current file-provider fences when a later explicit root replaces the workspace.

## Acceptance and tests

Extend [protocol compatibility tests](../../plowshare-mcp/src/compatibility.test.ts),
[socket tests](../../plowshare-mcp/src/socket.test.ts) and their existing
[fixtures](../../plowshare-mcp/src/fixtures.test-support.ts):

- Hold tool A; return B first with B's JSON-RPC ID and correct typed result.
  Release A and verify exactly one complete response record for each.
- Send malformed records, notifications and unknown methods alongside held calls;
  preserve their documented outcomes without losing unrelated admitted work.
  Separately send a duplicate active ID: close through the owned lifecycle,
  execute no duplicate backend mutation and emit no ambiguous second reply.
- Cancel A and expire another call while B continues. Prove cancellation cannot
  abort the shared process/session, replay remote work or fabricate job failure.
- Saturate admission and pause stdout consumption. Verify bounded retained tasks
  and bytes, overload before submission and whole records after draining resumes.
- Delay initialization, send lifecycle/cancellation notifications and close stdin
  with pending work. Prove legal ordering, deadline drain and no stranded timers.
- Race root A, run A and root B; retain their explicit local dependency order.
  Root failure/file loss blocks unsafe session-dependent submission while an
  unrelated status read still progresses. Cover deliberate Personal selection.
- Lose the socket after mutation submission: retain visible uncertainty, count
  exactly one wire mutation and forbid automatic reconnect/replay.

Use controlled deferred tasks, fake writers and socket fixtures; no database is
needed. Run MCP/Node/SDK owning checks plus repository `check` and `sdkCheck` when
their contracts change. Slice 08 owns packaged and live acceptance evidence.

Exit when independent calls complete out of order, task/output capacity stays
bounded and lifecycle/presence/cancellation cannot affect unrelated authority or
work. CLI invocation shape and external integration journals remain unchanged.
