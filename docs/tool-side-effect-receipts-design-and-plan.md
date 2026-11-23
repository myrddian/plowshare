# Tool side-effect receipts for interrupted mutations

**Status:** Proposed design and implementation plan for bug ledger item 5.

When a tool fails, the harness should settle the original tool call with the
reason and the evidence it has about the effect. A recoverable tool failure
should let the agent continue. If Plowshare sends a request to a connecting
service and never receives the required reply, the harness can report the send
while leaving the remote effect unknown. Keep one result entry per tool call
and attach the receipt as metadata. Opening a tool call can fetch one optional
debug-level trace record. Trace is additive to the existing Trajectory and
approval records. The first implementation covers retained approvals and the
file channel.

## Current behavior and evidence

The trajectory records a declared tool call and, when available, a correlated
tool result with timing and a display outcome. Some outcome labels are derived
from rendered result text after hooks. On certain exceptions the runtime ends
the run before writing a result for the call. A tool result, a job outcome, and
proof of a side effect answer different questions.
[Tool result handling](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L4984)

The harness already writes approval reviews and human approval decisions as
durable `DIAGNOSTIC` entries in Trajectory. They are available to the reader
and stay outside model context. Retained approval state also has its own
store and recovery contract.
[Approval review entries](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L5721) ·
[Approval decision entries](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L5313)

| Boundary | Evidence available today |
| --- | --- |
| Retained approval | The approval store durably claims the call before invoking retained preflight and saves the result before appending it to the trajectory. The older `started` state covers both preflight and possible execution. PR #312 catches `WorkspaceRefusedException` specifically from `gateRetained` in the shared `resumeRunCall` path. It preserves the actual refusal as a tool result. [Resumption](../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunCallResumption.java#L65) · [PR #312](https://github.com/myrddian/plowshare/pull/312) |
| Remote files | The file channel checks the captured session before writing, calls the socket sender, and waits for the correlated reply. A write-lock timeout before the socket call and a reply timeout after it both currently surface as workspace unavailability. The waiter's correlation entry is removed on timeout. Return from the socket send establishes local handoff, while an authoritative matching provider reply can establish the file operation's result. [File channel](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FileChannelHandler.java#L937) · [Send boundary](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FileChannelHandler.java#L1063) |
| Commands | The command runner can prove a launch refusal and report an observed exit, timeout, or cancellation. An exit code alone supplies no proof of the command's downstream effects. [Command runner](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/CommandRunner.java#L151) |
| Internal messaging | A durable message and owed wake establish queued acceptance. The recipient's effect and final reply have separate evidence. [Messaging contract](internal-messaging.md#7-delivery-replies-and-permissions) |
| Relay and MCP | The existing invocation and owner receipt can reconcile a result. A valid error reply establishes that a reply arrived, and the tool contract determines what that reply proves about effects. [MCP recovery](mcp-recovery.md#outcomes-and-cancellation) |

In the item 5 incident, a workspace check refused the resumed edit before the
file provider could execute it. The exception escaped after the retained call
was claimed, leaving recovery with a started claim and no tool result. It
reported uncertainty. PR #312 addressed that exception and Personal alias
routing. The remaining gap is durable, typed evidence across tool boundaries.

## Tool payload integrity

### Behavior before the lossless file-read slice

In the OpenAI-compatible adapter, an ordinary textual tool result is a single
`content` string on the provider's JSON tool message beside `tool_call_id`.
JSON wire escaping is decoded by the provider. The runtime adds no common
`{result: ...}` object inside that text. The native LM Studio socket uses a
protocol-required `toolCallResult` part and puts the original string in that
part's `content`. Individual tools can author JSON as their output, as
`conversation_search` does. That is the tool's payload.
[OpenAI adapter](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/openai/OpenAiTransport.java#L1812) ·
[LM Studio adapter](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/lmstudio/LmStudioSocket.java#L632) ·
[Conversation search result](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ConversationSearchTool.java#L95)

`file_read` returns a bounded **text view** to the model. On the remote
line-read path, the client sends an internal `FileReply` frame with a request
ID, outcome, and `span` containing the selected lines and window metadata.
Source-capable peers can instead supply a source snapshot that the server
windows. In both cases the server produces the model-facing string. It joins
the lines with LF, which loses CRLF and the original trailing newline, and can
add a paging preamble, code metadata, or an empty-file message. The line window
provides useful bounds and continuation coordinates. Those can be carried
separately from the selected bytes.
[Internal file reply](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/FileReply.java#L214) ·
[Remote read paths](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/RemoteProvider.java#L292) ·
[Provider text decoding](../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/LocalProvider.java#L588) ·
[File view](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/FileTools.java#L434) ·
[Line rendering](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/FileTools.java#L590)

The generic runtime substitutes a diagnostic for legacy null or blank results
and can project a post-tool hook's modified output. The current `ToolFailure`
renderer uses a code and message in text. The JSON form below is proposed for
Plowshare-detected failures.
[Legacy blank handling](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentTool.java#L70) ·
[Post-tool projection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L4974) ·
[Current error rendering](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ToolFailure.java#L19)

The normal file-channel handler decodes `FileReply` and correlates it with the
waiting request. It does not routinely store the raw frame as a trajectory
entry. The channel logs malformed, late, or unmatched replies. Trajectory's
`DIAGNOSTIC` is available to authorized readers and stays out of model
projection. [File reply handling](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FileChannelHandler.java#L635) ·
[Diagnostic entry policy](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/EntryKind.java#L170)

### Required behavior

- Keep the authorized tool payload in the original tool message. Receipts,
  timing, paging, and failure metadata belong beside it in the runtime and
  trajectory. Use the provider's required message framing only. Preserve
  tool-authored JSON, whitespace, and line endings as received.
- A valid correlated reply completes Plowshare's exchange even when the tool
  reports an application error. Return the tool-authored body unchanged,
  including an error body. Record the tool-reported status separately when its
  protocol exposes one, such as MCP `isError`. Payload text that merely looks
  like an error is still data. Neither the completed exchange nor a tool-reported error
  settles the side effect without evidence from the tool's owning contract.
  [MCP reply semantics](mcp-recovery.md#outcomes-and-cancellation)
- When Plowshare detects a failure and has no valid, releasable tool payload
  for the call, emit a Plowshare-authored JSON error in the tool result. Use
  a stable code, explicit source, and safe message. A preflight refusal, a
  missing or malformed reply, or an output release refusal can take this path.
  If the effect is unknown, explain that in the same error so the agent can
  reconcile before retrying. For example, the proposed model-facing result
  after a confirmed send and reply timeout is:

  ```json
  {"error":{"source":"plowshare","code":"FILE_REPLY_TIMEOUT","message":"The request was sent and no reply arrived before the deadline. The effect is unknown. Reconcile the original call before retrying."}}
  ```

- Link the metadata-only receipt to the original call and show it beside its
  single `TOOL_RESULT` entry. Preserve existing approval and diagnostic
  entries. Opening the call can fetch one optional
  [trace record](#tool-call-trace) with additional debug observations.
- If the model needs instructions that the existing reply cannot carry, such
  as a paging continuation for a raw text window, use the existing after-batch
  note mechanism. A self-contained Plowshare JSON error is enough for the
  ordinary failure case. A successful empty payload is a distinct typed case.
  Current tool messages reject empty and whitespace-only text, so the lossless path
  needs explicit validation and provider compatibility tests before it ships.
  [Existing after-batch notes](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java#L5152) ·
  [Tool message validation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/ChatMessage.java#L191)
- Authorization, security review, and retention still govern release. If raw
  content cannot be released, withhold it and report that decision separately.
  An existing filtered view may continue under its own contract. A filtered
  view cannot be labeled byte-exact, and withholding the output cannot erase
  independently confirmed effect evidence.
- A lossless file read must select the bounded window from the original bytes
  before line splitting discards separators. For valid UTF-8 text, the decoded
  model string must re-encode to the exact selected bytes. Preserve CRLF,
  trailing newlines, and empty windows. Carry offsets, truncation, and
  continuation details as metadata. Binary data requires a supported typed
  payload or an explicit unsupported result. Keep the existing bounded text
  view available until the local provider, remote client, and model adapter can
  all support the lossless path.

## Receipt contract and ownership

Use an additive, typed receipt linked to the original server-owned invocation.
Reuse the owning approval, provider request, or domain receipt identity where
available. A model-supplied call ID alone is insufficient across turns. Bind
the invocation to its authenticated owner and workspace or provider, and apply
existing authorization on reads of both the receipt and referenced evidence.

Keep these fields independent:

| Field | Meaning |
| --- | --- |
| Harness outcome and reason | The state of Plowshare's exchange, such as reply received, preflight refused, timeout, or cancelled, with a bounded reason code and safe detail. |
| Tool-reported status | Optional application status from the tool's protocol. A valid tool error reply can accompany a completed exchange. Keep the reported status separate from the unmodified body and from effect evidence. |
| Dispatch | `NOT_DISPATCHED`, `MAY_HAVE_DISPATCHED`, or `DISPATCHED`. Record a successful socket send as a specific observation. It means local transport handoff. |
| Effect | `NO_EFFECT`, `CONFIRMED`, or `UNKNOWN`, scoped to a named effect or milestone. An authoritative provider rejection can establish no effect even after dispatch. |
| Evidence | A typed observation or authorized reference to the owning receipt. |

### Metadata retention

Persist only facts about the invocation in the new receipt, trace, and related
diagnostics. Keep the stable call ID, tool name, relevant timestamps or
elapsed time, and the observed phases and outcomes. A safe reason code,
tool-reported status, and authorized reference to owning evidence can be
included when known. Record the file request ID and whether the send or
matching reply was observed. A bounded status description should come from
Plowshare's classification, since raw exception and provider error text can
include request or response data.

Keep raw frames, tool arguments and response bodies, file bytes, command
output, and credentials out of receipts, trace rows, and diagnostics. Keep
references in place of copies of owning records. The existing owners continue
to govern payload retention for model history and recovery. A
Plowshare-authored JSON error remains the model-facing result when Plowshare
rejects or fails the exchange and has no valid, releasable tool payload.

Use the declared business effect for each tool. A matching file-provider reply
may confirm a change to a target file. A message receipt may confirm only queue
acceptance. A command exit confirms process completion and can leave changes
to files or external services unknown. For compound calls, preserve independently
confirmed milestones even when the overall result is partial or unknown.

At the owning boundary, retain intent before a mutation can execute. A crash
between that write-ahead marker and the actual send leaves dispatch possible.
Record a send observation only after the socket send returns. Record
`NOT_DISPATCHED + NO_EFFECT` only when the owner can prove the effect boundary
was never crossed. Before crossing it, a failed intent write prevents dispatch.
Keep repository operations bounded and complete them outside waits on external
I/O. Keep existing execution, authority, and no-replay fences authoritative.

On timeout after an observed send, settle the wait as `DISPATCHED + UNKNOWN`.
Surface it as “Sent, reply timed out, effect unknown.” A recovery or receipt read
uses the original identity and never invokes the mutation. Only matching
authoritative evidence may resolve the effect later. Preserve the original
timeout observation. A missing receipt, a cancellation request, a changed
workspace binding, or a later matching file read cannot settle it. Keep legacy
calls without enough evidence unclassified. The first file slice can retain
uncertainty without implementing a durable client-side file journal.
[File recovery boundaries](decisions/0034-mutation-integrity-and-file-recovery.md#aspirational-track-b-durable-file-channel-evidence)

A terminal tool result and a continuing run are compatible. Continue for
bounded tool failures when authority, transcript persistence, execution
ownership, and other run invariants still hold. Unknown effects can be returned
as tool results without allowing work that depends on their completion. Preserve
existing run-wide cancellation and workspace pause behavior where those
conditions apply. If the harness loses a required execution fence or cannot
retain a safe model history, stop or suspend with the failure it can prove.
Keep the original call paired with its result when projecting a resumable batch.

## Tool call trace

Trajectory shows one `TOOL_RESULT` entry for each settled call, including
refusals and unknown outcomes. Recovery and reconciliation are idempotent
against that invocation. A later reconciliation can update the receipt shown
beside the original result without adding a second result or replacing its
payload. Keep the model's existing call declaration and tool-call correlation.
Trace is debug-level detail outside the transcript and model projection. Keep
the current Trajectory writers and stored records, including approval reviews,
decisions, and other diagnostics. A timestamp or transport observation can
appear in the optional trace alongside its existing audit record. Creating,
updating, expiring, or fetching trace does not rewrite, move, or suppress any
existing record.

### Storage and access

Add a specialist repository and a new Flyway migration for a
`tool_call_trace` table. The read contract for an opened tool call is one
optional trace record for its server-owned invocation. Use a single row per
invocation for this first implementation. That row can hold several kinds of
metadata about the call. The invocation retains its existing conversation and
authority binding. Other trace types are outside this plan and can choose
their own storage model.

| Proposed fields | Contract |
| --- | --- |
| `invocation_id` | Primary key. Stable server-owned invocation identity shared with the receipt. |
| Approval and preflight | Optional approval claim time, preflight outcome, and safe refusal reason code. |
| File transport | Optional mutation request ID, send and matching reply timestamps, and transport outcome such as reply timeout. |
| Settlement and timing | Optional settlement time, elapsed duration, and bounded safe reason code. |

The execution owner creates or updates that row as observations become
available. Multiple observations or recovery reads of the same invocation
cannot create another row. Merge updates to their owned fields so a late
observation preserves the facts already recorded. Trace writes have bounded
capture, buffering, and retention. A trace write failure or expiry leaves
the tool result and durable receipt intact. Dispatch intent and effect evidence
required for safe recovery still go through the authoritative execution or
receipt store. The absence of a trace record establishes neither dispatch nor
non-dispatch.

Add a read-only WebSocket operation, proposed as `conversation.tool.trace`,
which takes the conversation and invocation ID. Authorize the exact
invocation under the conversation's current visibility, then return its
single bounded, typed trace record or report that the trace is unavailable.
An invocation from another conversation or owner is unavailable under the
requested scope.

Trajectory renders the receipt summary with the tool result. The client
fetches trace only when the operator expands that call or requests its
trace through the SDK. Normal trajectory pagination and model projection
perform zero trace queries. Fetch the record through one indexed repository
read after authorization. Keep the usual SQL budget and avoid
per-tool background fetches.
[SQL and query reuse policy](coding-standards.md#query-efficiency-and-reuse) ·
[Existing trajectory operation](../sdk/typescript/src/operations/catalog.ts#L62)

## Implementation plan

The first implementation covers retained approvals and remote `file_edit`,
`file_delete`, and `file_move` calls. The retained call stores the latest
verified observation separately from its result text. A retained preflight
refusal records no dispatch and no effect. A successful channel write records
that Plowshare sent the request and leaves the effect unknown. A matching
reply timeout keeps the effect unknown and settles the call with a Plowshare
error. A matching structured reply can confirm the effect. The existing
retained claim prevents execution during recovery, and existing Trajectory
entries and provider result text keep their current format.

Trajectory pages expose `effectReceipt` on the exact recorded call, keyed by
the server-owned retained invocation. Desktop shows the summary and reads its
optional metadata through `conversation.tool.trace` when the Trace disclosure
opens. The shared SDK types both replies. Existing entries and result payloads
keep their existing shapes and content. The retained invocation makes a repeated
result append idempotent across recovery. Calls retained before the answer
link was introduced remain unlinked.

Directly admitted agent file tools use the same invocation identity, receipt,
and optional TRACE, with or without a Personal approval review session. The
harness claims the original call before any mutation.
The claim alone leaves dispatch and effect uncertain. Recovery settles or
retrieves that invocation's result without calling its provider again.

When the provider can supply bounded snapshots on the same fenced binding,
the harness checks the expected bytes after a successful edit or write. It
distinguishes changed content from a byte-for-byte no-op. Delete checks absence;
move checks source absence and the destination's original source bytes. A
matching remote reply is required before these checks can confirm an effect.
Missing, inaccessible, or contradictory verification leaves the effect unknown.
No bytes or hashes are stored in the receipt or TRACE. Provider output follows
its existing path, independently of verification.

A remote send with no reply retains an unknown effect even if another read
could now see the requested content. Broader tool coverage remains open.

Model-facing `file_read` returns byte-exact bounded UTF-8 windows on local and
source-capable remote providers. Original line endings and empty windows are
retained as tool results. Paging facts appear on the result entry as metadata
and reach the model in an after-batch note. Converted documents, named images,
and span-only clients retain their existing views. Non-UTF-8 and NUL-bearing
files cannot be returned by the exact-text path.

| Slice | Changes and dependency | Acceptance evidence |
| --- | --- | --- |
| 1. Retained approvals | Define typed evidence and result metadata at the runtime boundary. Extend the existing retained-call repository and resumption flow so the claim, verified preflight refusal, dispatch intent, and settled output are distinguishable. Save the result and its evidence before acknowledging the call. Preserve the original payload and approval identity. | Reproduce the Personal alias refusal with the existing retained-approval fixture. The provider receives zero calls, the original call records `NOT_DISPATCHED + NO_EFFECT` with the reason, and the run completes with earlier results intact. A crash after result retention and before acknowledgement recovers the original result without executing. |
| 2. File-channel evidence | Carry the invocation through the reviewed file path to the exact provider and request correlation. Instrument pre-send failure, send attempt, returned send, and authoritative reply at the file-channel boundary. Use the existing provider binding and workspace lifecycle. Map evidence to the retained receipt for approved calls and add the same fenced path for directly admitted file mutations. | A fake peer receives one mutation and withholds its response. The agent, trajectory, and recovery read all retain an unknown effect. Reconnect, restart, and continuation produce no second mutation. Verify the pre-send and valid-response cases with the same fixture. |
| 3. Projection and compatibility | Expose the optional typed receipt on the original call beside its single result in Trajectory and the shared SDK contract. Preserve valid tool replies and render Plowshare-authored JSON only for a harness-detected failure with no valid, releasable payload. Regenerate affected operation catalogs and check consumers. | Capture a valid tool error and verify identical payload text, with completed exchange and tool-reported error in metadata. Capture a reply timeout and verify one Plowshare-authored JSON error with the unknown-effect guidance and typed receipt. Repeated recovery yields one tool-result entry. A JSON-looking file body is delivered unchanged and does not set an error status. |
| 4. Separate trace storage and fetch | After the invocation and receipt identities are pinned, add the tool-call trace table and repository. Collect additional approval and file-channel metadata, preserve the existing audit writes, and expose the scoped read through the shared SDK and tool-row expansion. | Expanding an authorized tool call fetches one optional bounded trace. Existing and newly written approval review and decision entries keep their current kinds, payloads, and ordering with or without trace. An ordinary Trajectory page and model request execute zero trace reads. A foreign invocation cannot disclose trace data or its presence. Fixture payload markers are absent from the new trace and receipt metadata. Trace write failure or expiry leaves a sent-without-reply receipt unknown and creates zero additional mutation frames. |
| 5. Lossless file reads | After the payload boundary is pinned in slice 3, implement a bounded lossless text window through the local and negotiated remote provider paths. Move window and continuation facts into separate metadata. Preserve the existing presentation for legacy clients. | Exact decoded model payload matches the requested UTF-8 bytes with CRLF, final newline, JSON-looking text, and whitespace. Verify empty content separately. Exercise both the OpenAI-compatible and native LM Studio adapters. A provider that lacks support cannot claim a lossless result. |
| 6. Additional tools | Map the existing messaging, transactional write, and Relay or MCP receipts onto the same fields. Define the effect milestone each operation can attest. Cover other tool paths after their owners can supply reliable dispatch and effect evidence. | Queue acceptance stays confirmed when a final reply is missing. An upstream error reply without effect evidence leaves the effect unknown. Each owner retains its existing idempotency and recovery contract. |

Start with repository fakes and controllable socket peers. A focused PostgreSQL
test behind `-PfullDb` belongs only where the implementation needs to verify
actual atomic claim and settlement, concurrent ownership, or restart-visible
persistence. No live provider disruption is needed for the first regression
suite.

### Sent request with no reply

1. Set up a reviewed `file_edit` against a controllable peer. Have it receive
   the exact mutation frame, count it, and optionally apply the edit to a
   fixture. Withhold its reply while the socket stays open. Use a latching peer
   and an injected deadline so the test proves a send and a reply timeout
   separately.
2. Assert **one outbound mutation frame**, a retained confirmed send
   observation, an unknown effect with a reply-timeout reason, and a correlated
   tool result. Run the fixture with and without a peer-side edit. Plowshare
   must report the same uncertainty in both cases, since it receives no reply.
   Check that the model can finish independent work on a still-valid run.
3. Reconnect the original logical workspace, read the receipt, and resume the
   original conversation. Reconstruct the runtime from the same retained
   state to exercise restart recovery. Repeat the receipt read and continuation.
   Assert the same invocation identity, the same unknown effect, and **zero
   additional mutation frames** across all sockets. With the applying peer,
   assert that the file changed exactly once.
4. Repeat for a disconnect during the socket write. Allow possible dispatch
   where the send did not return. Verify a failure before the write boundary
   instead records no dispatch. Verify a late or mismatched reply cannot
   settle a different request, and a stop or reconnect never creates a new
   execution of the original invocation.
5. Inject a crash after durable dispatch intent and before the send returns.
   Recovery must preserve possible dispatch and unknown effect without a new
   send. Then inject a crash after authoritative result retention but before
   the trajectory append. Recovery may append that original result once.
   Assert that it sends no new request and preserves the payload exactly.

Extend the existing [approval recovery tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/approvals/RunCallResumptionTest.java#L86),
[file session fence tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/FileSessionFenceTest.java#L222),
and [file reply correlation tests](../plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/FileChannelTest.java#L567).
Close item 5 when the shared contract and the migrated tool paths have verified
coverage. Keep the coverage boundary visible as other tools adopt the receipt.
