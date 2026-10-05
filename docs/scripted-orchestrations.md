# JavaScript orchestration guide

Plowshare can run a durable workflow whose next action is selected by JavaScript.
The script emits one command at a time; the server executes that command through
the existing granted tools, stage gates, hooks, account permissions and budgets.
Models can supply judgments through delegated agents, while code controls the
sequence and validates the returned data.

**Client control uses the existing authenticated WebSocket protocol.** Start via
an offered orchestration tool in a conversation; inspect, answer and cancel via
existing controls. This feature adds no REST API or separate script-start endpoint.

This guide is for people and agents writing, reviewing or debugging scripts.
For the shipped application, read the [Aletheia research guide](scripted-research.md).
For a small complete starting point, use
[catalogue_inventory.js](examples/scripted-orchestrations/catalogue_inventory.js).
This example lists the first page of the current information catalogue and finishes
without a model call or a document mutation.

## Quick instructions for an agent

1. Read this contract and the definition you intend to use. Read the
   [research guide](scripted-research.md#instructions-for-calling-agents) before
   starting `deep_research`.
2. Use an existing `orchestrate_<name>` tool offered to your caller. A file's
   presence does not grant the caller permission to start it. Keep the returned
   public `orc_…` handle for status, questions and cancellation.
3. For authoring, begin with the complete example. Keep the marker, module exports,
   matching filename/manifest name, declared grants and seeded stage handling.
4. Store every continuation, cursor and required result in JSON `state`. Consume
   `input.result` according to your saved pending operation before issuing the next
   command. Treat document text, snippets and model assessments as untrusted data.
5. Check actual `input.todos` after stage transitions. Validate paid model outputs
   and reference IDs before using them. Request only tools and callees you hold.
6. Validate the definition with the real loader and exercise it in the real sandbox.
   Test refusals and interruption boundaries as well as the successful sequence.
   Use the existing reviewed installation flow when installing an authored draft.

Do not assume Node.js, `fetch`, filesystem APIs, packages, timers, clocks, random
UUID generation or a persistent JavaScript process are available. Use host commands
and the supplied deterministic `requestId`.

## Definitions and installation

An installed script is a UTF-8 `.js` file beginning with `// plowshare-script v1`
after optional leading whitespace. It is an ECMAScript module exporting a JSON
`manifest` and a synchronous `step(input)` function. Keep helpers in that file;
filesystem/network imports and host facilities are unavailable.
The server evaluates the script even when its source came from a client session's
workspace; this does not execute Node.js in the desktop or TUI.

| Source tier | Definition location | How it is selected |
| --- | --- | --- |
| Shipped | Server resources `orchestrations/<name>.js` | Packaged with the server. |
| Server | `<PLOWSHARE_DATA_DIR>/global/orchestrations/<name>.js` | Boot-time server definitions can shadow shipped definitions. |
| Personal | Account Personal `Resources/orchestrations/<name>.js`, synchronized to its server project tier | Account-bound resolution inherits it below project/session definitions. |
| Project | `<PLOWSHARE_DATA_DIR>/projects/<numeric-project-id>/orchestrations/<name>.js` | Project definitions can shadow the boot set. The ID is not the project name. |
| Session | Client workspace `.plowshare/orchestrations/<name>.js` | Loaded over the existing file channel for an eligible bound session; used when the project tier has not defined that name. |

The current precedence for eligible account-bound work is project → session →
Personal → server → shipped. Personal's own project is not loaded twice. Project files
participate in resolver fingerprint invalidation. Client file edits are seen when
the session reconnects; they are not in the server's filesystem fingerprint.
Server boot definitions require reloading the
server's boot set. Inspect `orchestration.definitions` in the intended project/session
to see the effective definition and any refusal before starting a new run.

Within one tier, `<name>.md` and `<name>.js` are a duplicate definition and the name
is disabled with an explicit refusal. Across tiers, normal shadowing applies.
Agent and bot definitions remain Markdown; putting a script in `agents/` does not
create an agent. Ongoing runs retain their pinned source and SHA-256 hash; editing
a definition changes future runs, not the code already pinned to a run.

The existing Orchestration Studio trials source through the real resolver and
installs the exact reviewed bytes after the person's installation answer. Its
current prompts and artifact path convention are Markdown-first: a draft artifact
must still have the path `<name>.md` inside that design run's artifacts directory.
Marker-bearing JavaScript **contents** are recognized by the trial; the writer
then installs `<name>.js`. Do not pass a `.js` artifact path to the current Studio
or assume its authoring agent automatically generates JavaScript. On replacement,
the writer preserves the old format as `.prev` and removes the old active format
after writing the new one. Resolve an existing duplicate before installation.

The writer accepts at most 256 KiB of UTF-8 source bytes. The script evaluator's
separate ceiling is 524,288 UTF-16 code units; upstream source/channel limits can
be smaller. A `.prev` file is not loaded as a definition.

## Running the complete example

1. Copy [catalogue_inventory.js](examples/scripted-orchestrations/catalogue_inventory.js)
   to one supported orchestration directory with that exact basename. For a session
   definition, use `.plowshare/orchestrations/catalogue_inventory.js` in the workspace
   served by the bound file channel.
2. Include `catalogue_inventory` in the intended calling bot/agent's existing
   `orchestrations` list. The Markdown definition field is
   `orchestrations: [catalogue_inventory]` when it is the only grant. Use the normal
   definition loading/installation process and inspect the effective definition in
   that same project/session.
3. Invoke the caller's offered `orchestrate_catalogue_inventory` tool:

```json
{"request":"Inspect the first page of the current catalogue.","wait":true}
```

The example enters `inspect`, reads at most 20 scoped catalogue rows, checks the
returned array, marks the stage done and returns the revision IDs/names. An empty
catalogue is valid. The example does not page the whole library, acquire sources or
write a report. To add paging, persist an offset and accumulated results and emit
another list command until the bounded page plan ends.

## Manifest contract

```javascript
// plowshare-script v1
export const manifest = {
  name: 'catalogue_inventory',
  description: 'Inspect one page of the current scoped catalogue.',
  model: 'fast',
  tools: ['information_read'],
  calls: [],
  scopes: [],
  stages: [{id: 'inspect', 'done-when': 'A catalogue page has been checked.'}],
  'max-turns': 8,
  'max-model-calls': 1
};
// The complete step implementation is in the linked example file.
```

The loader evaluates the manifest and passes it through the ordinary orchestration
parser and grant/graph validation. Arbitrary JavaScript in the module is evaluated
at load time too, under the same sandbox deadline. Make module initialization pure.

| Field | Meaning and authoring rule |
| --- | --- |
| `name` | Match the file stem. Use 1–64 lowercase letters, digits and underscores. The start tool is `orchestrate_<name>`. |
| `description` | Explain the work to the calling agent. |
| `model` | Existing configured model profile. Semantic delegates have their own definition's model; this field does not create a separate conductor model call. |
| `tools` | Ordinary capabilities required by emitted commands. Unknown or unavailable tools are refused. |
| `calls` | Allowed agent names. Nonempty calls make `agent_run` available without explicitly declaring it. The callee must satisfy normal delegation rules. |
| `scopes` | Existing file capability scopes, if needed. Information scope is bound by the root account/home, not this list. |
| `stages` | Ordered, nonempty list with unique lowercase/digit/underscore `id` values. The host seeds actual stage todos. |
| `max-turns` | Command cap for the script, including stage transitions and waits. It does not count just the number of stages. |
| `max-model-calls` | Model-call allowance subject to existing root/project limits and sharing rules. Delegated model work spends that allowance. |
| `max-returns` | Existing stage return limit; it does not introduce an automatic script retry loop. |
| `triggers` | Existing caller trigger routing, such as `/research`; not a new socket start endpoint. |

Existing advanced orchestration fields, including artifacts, child orchestration
grants and checker configuration, still pass through their normal validation.
Read [OrchestrationParser](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationParser.java)
before combining a script with those features. The research example uses sequential
stages without automatic returns or executable acceptance checks.

Never declare the harness-owned `todo_read`, `todo_write`, `orchestration_ask`,
`orchestration_finish`, `orchestration_check`, caller status/answer/cancel tools or
`orchestrate_*` start tools in `tools`. They are supplied by the harness or grants;
the parser refuses declarations that would bypass that binding. Conductor manifests
also reject agent-only fields such as `bot`, `exported`, `delegable` and `fallback`.

Stage `done-when` describes intended completion; it is not an executable proof.
Stage `check: 'required'` and `acceptance: 'written' | 'required'` use the existing
service verification mechanisms and their prerequisites. `may-return-to` can only
name earlier stages. The script must implement any corresponding state rollback;
a manifest permission alone does not rewrite its JSON state.

## Step input and output

Every invocation receives a new plain JSON object:

| Input field | Contract |
| --- | --- |
| `run` | Root conductor conversation ID, typically `cnv…`. It is not the public `orc_…` orchestration handle. |
| `message` | Current turn utterance. Initially a JSON **string** encoding `{request, context}` from the start tool. On later human continuation it can be answer text. Parse the initial envelope once, when `state` is null. |
| `sequence` | Zero-based index of the next command. |
| `requestId` | Stable UUID derived from the conversation and command sequence. Use it for that command's idempotent information mutation. |
| `state` | Previous step's returned JSON state; null on the first invocation. |
| `result` | Previous command's post-hook judged **string**; null initially. Parse JSON only for a tool with that success contract. |
| `todos` | Current typed todo objects, including stage `id`, `stageId` and `status`. They are authoritative for stage transitions. |

Return exactly one command with the next state:

```javascript
return {
  state: {pending: 'catalogue', page: null},
  command: {
    tool: 'information_read',
    arguments: {operation: 'list', offset: 0, limit: 20}
  }
};
```

Or return a bounded host delay:

```javascript
return {state: {pending: 'poll_wait', ticket: savedTicket}, command: {waitMs: 1000}};
```

`waitMs` must be an integer from 1 to 1,000. It consumes a command; its judged
result is the string `{"waited":true}`. Emit either a tool command or a wait,
not both. Return an `arguments` object for tools. Returning `{done:true}`, `null`,
an array or a Promise is not the completion protocol. Completion is the existing
`orchestration_finish` tool with `arguments: {result: '…'}` after all stages pass.

Only JSON survives the call. Avoid `undefined`, `BigInt`, cyclic objects, function
values, class instances, `Map`/`Set` and closures in persisted state. Even if an
object serializes, rely only on the decoded JSON representation. Module globals
and heap identity disappear between invocations; two aliases to the same object
will not remain shared references after serialization.

## Continuation and stage pattern

The complete example uses the same pattern as research:

1. Initialize state once and locate the current seeded stage todo.
2. Save `pending: 'enter'` and emit the todo update to `in_progress`.
3. On the next invocation, verify the actual todo status before doing stage work.
4. Save the operation being requested and emit a single command.
5. Consume its judged result on the next invocation, validate it, save the useful
   data and advance a cursor or subphase.
6. Save `pending: 'exit'` and emit the todo update to `done`. Verify the actual todo
   status before incrementing the script stage. Finish through the host tool.

Example stage command:

```javascript
{
  tool: 'todo_write',
  arguments: {ops: [{op: 'update', id: todo.id, status: 'in_progress'}]}
}
```

Use the todo's actual `id`, not the manifest `stageId`. Successful todo updates
retain their existing prose response. Never infer success from an optimistic state
field or a substring of that prose. A service-owned atomic refusal returns
`{"applied":false,"reason":"…"}` to the script driver; the driver pauses and can
recheck the same transition after resolution.

A script that asks a substantive question must save its continuation first and
interpret the later `input.message` answer explicitly. The shipped research script
saves its objective-review continuation, asks through `orchestration_ask`, then
reads the delivered answer and author from the harness data fences. Explicit
approval starts query planning; corrections revise the objectives and ask again.
The saved question receipt resumes without issuing the question a second time.
Hook pauses instead recheck the already prepared command. Arbitrary delegate
questions and non-answered child outcomes still need explicit design and testing.

## Delegating a semantic task

```javascript
{
  tool: 'agent_run',
  arguments: {
    agent: 'research_analyst',
    task: 'Review the supplied finding. Return exactly the requested JSON.\n' +
      JSON.stringify({finding: savedFinding, evidence: retainedEvidence})
  }
}
```

This requires `calls: ['research_analyst']`. The scripted adapter returns an
answered child's raw answer text, so a JSON-only worker answer can be decoded with
`JSON.parse(input.result)`. Ask for an exact schema and validate it yourself:
normal model output is not guaranteed to be valid JSON or factually correct. Other
child endings propagate their outcome. Delegation remains logged, authenticated,
session-bound and subject to the root model-call budget.

For malformed model JSON, `llmJson.parse(input.result)` invokes the shared Java recovery
utility without a provider call. It returns a plain guest object with `value`, `pass`
and `attempts`; on failure, `pass` is null and `value` is absent. `attempts` records
each candidate and Jackson's error location. Strict parsing runs first; bounded local
passes recover fences/prose, single quotes, trailing/missing commas, raw controls,
literal quotes and backslashes. Validate the recovered shape, references and judgments
exactly as you would strict JSON. Use strict parsing for service/request payloads:
this utility is for model-emitted text, not tool failure messages. See the
[research recovery contract](scripted-research.md#bounded-output-repair-and-context-cost).

The shipped worker has no tools/callees/scopes and at most one turn/model call.
Author, editor, Blue, Red and Yellow are separate calls to that bounded worker
with different task instructions. They are not guaranteed independent models.

## Tool result contracts

The host still executes the existing tool implementations. Scripted adaptations
make `agent_run` answered text and `search` discovery pages directly usable; they
do not turn every tool response into one universal JSON schema.

| Tool | Result handling |
| --- | --- |
| `agent_run` | Answered child's raw text. A JSON-only worker must be validated before use. |
| `search` | JSON discovery page with `hits`; inspect `refusal` when present. URLs/snippets do not constitute retained evidence. |
| `information_read`, `information_write` | Success is JSON, with temporal values serialized as ISO timestamps. Refused/invalid requests can return prose beginning `Information request refused:` or `Information arguments are invalid:`; output serialization errors begin `Information result serialization failed:`. Treat those as failures, not empty successful results. |
| `todo_write` | Success remains prose; authoritative state is in `input.todos`. Atomic refusal is handled by the driver as described above. |
| Other granted tools | Their existing schemas and result contracts. Read the actual tool definition before using them. |

Information calls use `operation` and omit caller-supplied `scope`, `account` or
`project`. The tool binds ownership and namespace from the authenticated run.
Read the [research tool examples](scripted-research.md#information-command-reference)
and [information lifecycle guide](information-system.md) for payloads and limits.
Internal tool payloads are not interchangeable with client WebSocket payloads,
which carry an explicit authorized scope.

## Sandbox and bounds

The server uses GraalJS through the existing hostless hook engine. Each manifest
evaluation and step has a fresh context, with module loading/evaluation,
execution and JSON serialization together bounded to two seconds. The evaluator
rejects source above 524,288 UTF-16 code units and encoded result JSON above
8,388,608 UTF-16 code units. These checks are string lengths, not UTF-8 byte counts.
Information frames, report metadata, reads and installation have additional limits.

There is no host object/class access, file/network/environment IO, process/native
access, host thread creation or polyglot escape. A frozen `llmJson.parse` bridge exposes
one pure Java transformation through primitive JSON strings, not a Java object or class.
It accepts at most 524,288 UTF-16 units for strict parsing, 32,768 for local recovery,
and 128 levels of nesting. The bridge is available in each orchestration context;
hook contexts do not gain it. `Date` is unavailable and
`Math.random()` throws. Do not use timestamps as protocol state or retry tokens.
Do expensive work through granted host tools and use `waitMs` for polling.

Cancellation, command caps and model-call budgets are checked by the driver.
A long-running tool also follows its existing cancellation rules; a JavaScript
step does not override them. Caps can be more restrictive at the project/root.
Raising a cap uses existing orchestration controls, not a field in script state.

## Hooks and durable recovery

Tool pre/post and stage pre/post hooks follow the normal harness → project →
pinned owner chain. Information acquisition, processing, evidence and reports
also reach their service-owned document stage gates. Document jobs use
`context.document` and origin `submission`; a hook filtered only to origin
`orchestration` does not cover that processing. See
[information hooks](information-system.md#hooks).

The V84 `orchestration_script_steps` journal retains:

| Checkpoint | What is durable | Recovery consequence |
| --- | --- | --- |
| Prepared | Pinned source hash, post-step state and command intent | Resume the prepared command without rerunning `step`. |
| Started | Effective pre-hook arguments and start receipt | A missing result now means execution may have occurred. |
| Executed | Raw tool result | Rejudge the post hook without paying for the tool again. Changed effective arguments cannot reuse that raw result. |
| Completed | Post-hook judged result | The next step consumes this result; completed work is not deliberately reissued. |

A pre denial or withheld post result pauses via the normal trusted orchestration
question path. Resolve the hook/gate cause, then answer to recheck it. A completed
paid delegate may be recovered from its answered child log. A started operation
with no durable result or recoverable completed delegate fails with an uncertainty
message; the driver refuses automatic replay. This is not a universal exactly-once
guarantee for external effects. Stable information mutation receipts provide their
own service guarantee; they do not authorize replay of every other tool.

An explicit `orchestration.resume` request can revive a failed root while retaining
this journal and the last turn's input. The desktop Resume control and TUI
`/resume` (`/retry`) expose it. A recorded transient delegate failure continues
that delegate's existing conversation; its eventual answer becomes the pending
command's receipt. Completed commands remain completed, and uncertain native
mutations remain refused. Each intended resume has a stable UUID `requestId`;
repeating it never dispatches another turn, including after a later failure.
Resume requires remaining allowance and current account/project authority.
Observers receive `orchestration.resumed` (run ID and resume request UUID), the
existing `orchestration.changed` account push and a
`run_resumed` milestone through `orchestration.recorded`.

Do not delete/edit journal rows to force progress or silently alter pinned source.
Deleting a consumed document clears associated script journal material under the
existing policy. Retained states and raw outputs carry the root's input restrictions.

## Inspection and troubleshooting

Use the existing authenticated WebSocket client and protocol envelope. These are
operation names and **payloads**, not standalone REST requests or complete frames:

| Operation | Example payload | Purpose |
| --- | --- | --- |
| `orchestration.definitions` | `{"project":"research"}` | Effective definitions and refusal information for the selected project. |
| `orchestration.status` | `{"id":"orc_…"}` | Run state, stages/todos and messages. |
| `orchestration.record` | `{"root":"orc_…","tail":true,"limit":50}` | Recent run-tree record, including questions and failures. |
| `orchestration.answer` | `{"id":"orc_…","answer":"The gate cause has been resolved; recheck."}` | Continue an outstanding question after resolving its cause. |
| `orchestration.resume` | `{"id":"orc_…","requestId":"22222222-2222-4222-8222-222222222222"}` | Explicitly resume a failed root; retain the UUID after uncertainty. |
| `orchestration.cancel` | `{"id":"orc_…"}` | Stop the selected run. |

Caller agents instead use their offered `orchestration_status`,
`orchestration_answer` and `orchestration_cancel` tool schemas. Their `id` is the
public run handle; use structured choices when the outstanding question requires
them. Normal record
inspection is not a dedicated export of every raw JSON journal state.

For operator diagnosis, the journal links to the **conversation** ID, not the
public orchestration handle. A read-only metadata query against the operator's
existing database connection can establish which receipt is missing:

```sql
SELECT sequence, source_hash, started_at,
       effective_arguments IS NOT NULL AS has_effective_arguments,
       raw_result IS NOT NULL AS has_raw_result,
       result IS NOT NULL AS has_judged_result,
       command ->> 'tool' AS tool
FROM orchestration_script_steps
WHERE conversation_id = :conversation_id
ORDER BY sequence;
```

`:conversation_id` is a query-client parameter, not literal SQL accepted by every
client. Access remains subject to the deployment's existing operator permissions.

| Symptom | Interpretation and next action |
| --- | --- |
| Definition absent/refused | Inspect effective tier, duplicate formats, marker, manifest fields and missing tool/callee grants. |
| Caller lacks `orchestrate_<name>` | Add the existing orchestration grant through the appropriate reviewed definition process. Installing a file alone does not offer a tool. |
| `Expected JSON` or invalid analytical fields | Inspect the retained worker response. Research has bounded structural repair, described in the research reference; it cannot repair service failures or invent missing evidence. |
| `Tool information_read (rank) returned non-JSON` or another named tool/operation | The service returned a refusal/error instead of its expected JSON result. The reason is included in the stop message and retained in full in the journal. Fix the service problem; changing objective approval text will not repair it. |
| Hook/stage question | Resolve the actual hook/gate and answer; repeated answers alone cannot approve it. |
| Pending acquisition/extraction | Inspect the durable ticket and current generation's stage status. Retry only through the authorized information lifecycle control. |
| Command cap | Waits/search/stage commands count too. Inspect progress and use normal cap controls when justified. |
| Model allowance exhausted | Inspect root spending and configured caps; adding state flags does not grant more calls. |
| Interrupted before receipt | Check ticket/service receipts or child logs. Do not blindly rerun uncertain effects. |
| Source differs from pinned journal | Verify the actual pinned definition/source. Do not patch an existing journal to match a new file. |
| Document became inaccessible | Recheck account/project membership and input withdrawal/exclusion; restrictions also apply to descendants, logs and reports. |

## Validation and source map

Run these focused checks from the repository root with JDK 21 configured:

```sh
./gradlew :plowshare-server:test \
  --tests '*ScriptDocumentationTest' \
  --tests '*ScriptResearchTest' \
  --tests '*ScriptRuntimeTest' \
  --tests '*ShippedOrchestrationsTest'
```

The documentation test loads the actual example through the orchestration parser
and evaluates it in fresh sandbox contexts with JSON-round-tripped state. It checks
the command sequence and refusal handling. Research fixtures execute the shipped
script with controlled model/tool outputs. Runtime tests use real PostgreSQL for
receipts, hook recovery, caps and uncertain effects. These are not live research
quality or latency benchmarks. Add workflow-specific coverage when adapting the script.

| Implementation | Responsibility |
| --- | --- |
| [ScriptProgram](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptProgram.java) | ESM sandbox, deadlines and JSON boundary. |
| [ScriptStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptStore.java) | Durable command/state journal and completed delegate recovery. |
| [JobRuntime](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) `runScript` | Offered commands, hooks, budgets, cancellation and result checkpoints. |
| [OrchestrationParser](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationParser.java) | Manifest validation and pinned source. |
| [OrchestrationResolver](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationResolver.java) | Tiers, effective definitions and real trials. |
| [OrchestrationWriter](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationWriter.java) | Reviewed source installation and format replacement. |
| [InformationTool](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/InformationTool.java) | Root-bound information commands and inherited input closure. |
| [deep_research.js](../plowshare-server/src/main/resources/orchestrations/deep_research.js) | Application state machine, semantic prompts, validation and report assembly. |
| [research_analyst.md](../plowshare-server/src/main/resources/agents/research_analyst.md) | Bounded JSON analytical worker. |
