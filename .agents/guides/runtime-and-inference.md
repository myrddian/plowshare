# Runtime and inference

Read this when changing agent admission, the turn loop, model dispatch, streamed
previews, inference budgets or usage accounting. For definition source ownership
and workspace access, also read [applications and files](applications-files.md).
For provider queues, admission or capacity ownership, read
[backpressure](backpressure.md) before changing saturation behavior.

## Mental model

An admitted run executes a server-owned turn loop; jobs expose its live progress
and retain outcomes while inference dispatch owns provider admission and usage.
The observable stream, durable conversation and accounting ledger describe the
same work through separate lifecycle and disclosure contracts.

## Start in code

- [Runs](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Runs.java) owns surface-independent run submission;
  [JobStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobStore.java) owns live job handles and lifecycle events.
- [JobRuntime](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) owns the turn loop and final output;
  [JobWatch](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobWatch.java) publishes droppable progress observations.
- [LlmDispatcher](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmDispatcher.java) owns routed and pinned dispatch;
  [CallAuthorization](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/CallAuthorization.java) separates live authority,
  retained allowance evidence and preview disclosure policy.
- [InferenceAccounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/InferenceAccounting.java) is the provider accounting
  boundary; [UsageReports](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageReports.java) owns authorized reporting.

## Log, Trajectory and model projection

One conversation has recorded history and several purpose-specific readings.
Neither a visible row nor a context-eligible label proves a particular provider request.

| View | Meaning and limits |
| --- | --- |
| Durable log | Typed entries and provenance recorded through `Transcript`/`LoggedEntry`; broader than model input. |
| Chat | Human presentation. `conversation.chat` pages standing projectable entries; Desktop also filters recorded Trajectory rows for its chat display. |
| Trajectory | `conversation.trajectory` reads recorded work, including covered entries, diagnostics and reasoning when retained; pages can be filtered or excerpted. |
| Projection | `conversation.projection` constructs the next opening or reconstructs a selected turn's opening history; its view contains roles/text, not a complete wire request. |
| Context / count | `conversation.context` reports components and turn measurements; `conversation.context.count` explicitly counts the next ordinary projection, not consumed usage. |
| Context snapshot | `conversation.context.snapshot` constructs a timestamped next-projection preview with tools/settings; optional measurement generates no answer or inference spend. Drafts/transient execution additions and image bytes are absent. |
| Actual inference evidence | Retained invocation and accounting call/attempt records describe dispatch/model/usage when recorded; a preview or reconstructed projection is not that evidence or a full provider-payload receipt. |

Compaction writes summaries and coverage markers while keeping original rows;
separate retention can eject tool-result payloads. Projection selects eligible
standing history, adds the agent block and applies tool-result/summary policies.
Diagnostics, hook records and recorded THINKING are not replayed as later model
context; hook effects can change a request, and runtime notes can reach the active run.
Historical `systemBlockAsSent` attests only to the stored block. Current tool policy
can change reconstructed references/seams; it does not certify the whole old request.
Keep current read authority, speaker/provenance and usage attribution distinct.
Live deltas are droppable previews; they neither replace nor guarantee log retention.

Failed streaming calls retain bounded beginning/end samples of reasoning, partial
answer text and tool-call fragments in nonprojected `DIAGNOSTIC` error entries,
with character counts, truncation and timing. They are diagnostic evidence, never
completed assistant messages or executable tool calls. Large captures use numbered
parts within the trajectory entry excerpt limit so every retained sample is readable.
Failed turns record their
terminal summary without adding a projected assistant answer. Explicit retry uses
the existing conversation continuation boundary, preserving completed tool results
and current authority; it never automatically repeats an uncertain effect. A
delegate can publish authorized thinking on its parent's preview channel without
replacing the parent's answer, progress counters or terminal lifecycle.
Ordinary delegated `UNAVAILABLE`/`SUB_AGENT_FAILED` outcomes are failure tool
results, not fatal parent exceptions. `agent_run` can explicitly retry its own
retained child by conversation, checking the original task/images, home, callee
and current authority. Session-loss and mandatory review/stage contracts retain
their owning gates; a failed reviewer never certifies an answer.

Trace [Transcript](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Transcript.java), [EntryKind](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/EntryKind.java),
[Projection](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Projection.java)/[Compaction](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Compaction.java) and [ContextCountFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ContextCountFrames.java).
See the [conversation manual](../../docs/manual/03-architecture.md#log-trajectory-and-model-projection) for the user-facing comparison.

## Rules that are easy to miss

A durable job row records that work happened; it does not recreate a lost thread
or grant a restarted worker permission to continue. Keep live handles, durable
outcomes and persisted conversation entries distinct.

Provider calls run outside database transactions. Dispatcher admission and final
output checks remain necessary after queueing: a prior permission check or usage
attribution is not a reusable execution permit. Read [AUTH](auth.md) when changing
identity, grants, budget authority, retained execution or result disclosure.

Ordinary explicitly LIVE calls may emit irretractable previews. Unknown/internal
callers default to BUFFERED. `CallAuthorization.and()` adds a check-only fence and
preserves the original owner's policy. Contained, information-dependent,
regeneration, filtering and response-guard paths retain their separate withholding
checks. Progress delivery never substitutes for final authority or durable output.

Keep provider token observations nullable when absent. A reported zero differs
from unknown; token completion and price completion are independent. Preserve
call/attempt identity, routing evidence and unsettled accounting through failures.

Price lookup uses the exact billing route and wire model. ZERO_RATE explicitly
prices at zero; a verified provider charge replaces arithmetic. Missing prices
otherwise remain unknown. Admission freezes the price snapshot; overrides affect
future admissions without rewriting retries or booked history. Cache/reasoning
tokens are subsets, never additional totals. Cache hit rate is token-weighted
cache-read/input, only with full valid paired coverage and no active or incomplete
attempts in the selected cohort; zero input has no rate. Unknown cost does not
hide valid token metrics.

## Agent budget consent

Shipped agent defaults provide at least one model call per declared step; shared
parent/delegate spending and retries can still exhaust the allowance first.
The runtime tells eligible agents their remaining steps/calls and warns at
50/20/10/5 percent. `request_budget` asks for a one-time extension through existing
approval controls; reaching a limit can ask without another model call.
Approval resumes the retained task, preserving spending, original conversations,
working history and delegated call slots. Recovery rechecks immutable authority;
it does not create replacement work or replay uncertain effects. SYSTEM work and
board wake windows retain their existing owners. See [ADR 0073](../../docs/decisions/0073-agent-budget-consent-and-continuation.md).

## Bounded work

Required contract: each active admission/turn/provider unit needs a configured,
positive finite completion deadline covering queueing, waits, IO and result delivery.
Short interactive reads, controls and acceptance use a 2-second default ceiling,
with normal completion much faster. Provider calls and active job steps need their own
appropriate configured deadlines and budgets; do not cap an entire job at 2 seconds.
Retries and nested calls consume the earlier remaining deadline, never reset it.
Park human/approval waits without holding active resources; bound service renewals.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop new effects, request owned cancellation and withhold late output;
retain current authority/input fences and physical capacity until work actually ends.
An uncertain write keeps its original intent and receipt; never automatically replay.
Current: [LlmPool](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/dispatch/LlmPool.java) permits `NO_DEADLINE` queueing;
queue/transport timeouts and call budgets do not establish an end-to-end deadline.

## Legacy traps

Do not derive permission from the usage actor, treat every allowance binding as a
reason to suppress ordinary previews, or use DIRECT as the production authority
path. THINKING previews are not answer text; protected log retention is a separate
path. Socket loss must not cancel durable work or fabricate a terminal outcome.

## Checks

Choose the tests matching the changed boundary; these default tests use mocks or
in-memory collaborators:

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.agents.LiveJobPreviewTest' --tests 'io.aeyer.plowshare.server.agents.RetainedExecutionTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.llm.dispatch.FilteredDispatcherTest' --tests 'io.aeyer.plowshare.server.llm.openai.OpenAiUsageTest'
```

For lifecycle changes, inspect `agents/DurableJobsTest`; for accounting changes,
inspect `llm/accounting/UsageObservationTest` and `ServiceUsageOwnershipTest`.
Do not enable database tests unless the assertion requires PostgreSQL behavior.
See [model usage accounting](../../docs/model-usage-accounting.md) and
[model profiles](../../docs/model-profiles.md) for configuration and provider details.
