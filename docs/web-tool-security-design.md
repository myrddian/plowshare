# Web tool security design

**Status** · Implemented for the built-in agent runtime and provider services

Plowshare will offer public web search as `web_search` and page retrieval as `web_fetch`.
Both tools will check whether the proposed request serves the authenticated task before
sending it to a provider. An approved request runs in a contained subagent. If
inspection raises a security exception, a monitor decides what evidence can return to
the parent. A denied call becomes a tool result with a reason, so the run can continue
with work it has already completed.

This addresses a research run that fetched a page reporting on AI security. The fetch
succeeded, but the next model call failed because the input filter matched words in a
headline and a description of a test. The proposed monitor can distinguish that
reporting from instructions directed at the agent while the result is still held. The
request gate separately limits what an agent influenced by a page can send out next.
[Current message filtering](message-filtering.md#local-rules)

## Tool names and coverage

| Surface | Contract |
| --- | --- |
| Model-facing agent tools | Expose `web_search` and `web_fetch`. Keep the existing query, URL, and paging semantics. |
| Provider routing | Dispatch the typed `SEARCH` or `FETCH` operation through the corresponding service. Provider selection and fallback follow server policy. |
| Plowshare orchestration | Apply the same gate, containment, and result-release policy to agent-mediated calls, including native scripts and retained calls. |
| Direct clients | Keep the existing `web.search` and `web.fetch` operation names and authorization. Direct SDK and CLI consumers receive the direct service contract. The managed-agent review lifecycle covers calls Plowshare makes on behalf of its own runs. |

Require `web_search` and `web_fetch` as the sole agent tool names for these operations
in model schemas, definitions, grants, scripts, and dispatch. Hook selectors match
those names, and `event.tool` carries the canonical name. Preserve recorded tool calls
and receipts in historical Trajectories. Before cutover, update bundled and configured
definitions, grants, scripts, prompts, and hooks, and settle pending calls that use
retired identifiers. Dispatch refuses an unsupported name with an actionable tool
error.
[Hook selectors and event contracts](manual/12-hooks.md#select-the-events-you-want) ·
[Direct web operations](../plowshare-cli/README.md)

## Request lifecycle

| Step | Owner and outcome |
| --- | --- |
| 1. Admit and run `tool.pre` | The job runtime checks the tool grant and runs the existing pre-call hooks. A hook may deny or rewrite the arguments. |
| 2. Validate and check intent | Plowshare validates the final arguments and applies deterministic request restrictions. The SECURITY reviewer checks the exact proposed request against the authenticated task and effective web policy. |
| 3. Execute in containment | The runtime rechecks current authority and gives a contained subagent a one-use capability for the approved logical operation. The worker calls the existing service and holds the result. |
| 4. Inspect the held result | Ordinary inspection produces either a releasable result or a typed security exception. Only the exception invokes the security monitor. |
| 5. Resolve and return | Plowshare applies the verdict, runs the existing `tool.post` callback on the resulting tool output, checks the final projection, and commits the result and its audit evidence before making the result visible to the parent. |

Early denial follows the existing pre-execution refusal path. A result withheld after
execution reaches the ordinary post-tool path as a safe explanation when current
authority permits the callback. Existing authority checks still control hook invocation
and disclosure. Other calls in the batch retain their ordinary lifecycle.
[Hook stages](manual/12-hooks.md#the-14-callback-events) ·
[Operation authority fences](operation-write-fences.md)

## SECURITY gate and policy files

Reuse the tool-free SECURITY reviewer and the existing approval lifecycle. Add typed web
search and fetch intents. Give the reviewer the authenticated task, the exact post-hook
request, relevant source or link provenance, the requesting agent identity, and
applicable policy. Recent evidence can explain why a request followed from the task.
Retrieved prose, agent-provided explanations, and tool output enter as evidence of
claimed intent. Authenticated task evidence, grants, and deterministic restrictions
establish the authority to act. The reviewer checks task relevance, the necessity of the
egress, and whether the query or URL would disclose material outside the task's
authorization. [Existing intent review](approval-policies.md)

| Gate decision | Runtime behavior |
| --- | --- |
| **Allow** | Proceed with the reviewed arguments after rechecking authority, policy, and destination restrictions. |
| **Ask why** | Return a bounded clarification request to the calling agent. Hold the proposed operation and allow one justification for the same arguments. Missing, expired, or insufficient justification denies that operation. Changed arguments start a fresh review. |
| **Ask human** | Use the existing approval UI with the exact request and a short reason. Park the pending tool call under its current owner. A one-time permit binds to the request shown to the human. |
| **Deny** | Return a safe reason as the result of that tool call. Preserve completed work and allow the run to continue. |

The clarification binds to the run, original call, operation, argument digest, and
effective policy snapshot. Return a challenge ID with the clarification tool result. The
agent submits its justification in a follow-up invocation of the same tool using that ID
and the same operation arguments. The follow-up has its own tool call ID and can claim
the challenge only once. Both calls share one logical request for audit and dispatch. An
unanswered challenge expires or closes when the run ends, with a recorded denial reason.
If a retained or scripted caller cannot supply a justification, resolve the call as
denied or present the human approval the gate explicitly requested.

On human approval, revalidate the exact request, the current grants, and the current
policy before dispatch. Approval denial or expiry settles the pending call without
provider work. Reviewer unavailability settles the request as a recoverable refusal.

Use `security/web-search.md` and `security/web-fetch.md` as operation-specific Markdown
includes in the existing `security.md` policy model. Apply the existing deployment,
project or Application, and Personal guidance selection and precedence. The files can
describe what counts as relevant research, when to ask for justification or consent,
which output signals deserve escalation, and how to treat quoted instructions. Effective
guidance includes its source identities and revision or content digest. Deployment
restrictions and authenticated intent govern conflicts. Capture the effective policy
identity for each decision and revalidate stale decisions at execution and release.
Reuse the security-policy file loader and edit protections as a prerequisite.

## Contained execution and provider routing

The runtime gives the subagent one server-issued capability bound to the exact verb and
arguments, parent call, authority, and deadline. The child receives the approved request
and the context needed to run it. Its only effect capability is invoking that logical
web operation. Filesystem, workspace, shell, private documents, memory, messaging, and
broad network access remain outside the child's capability set. Provider credentials
stay with the service that owns them. Any model work in the child uses the existing
allowance, accounting, and cancellation boundaries.

The child calls `SearchService` or `FetchService`. The services keep ownership of
paging, cached results, provider selection, and failure reporting. Cached results take
the same release path as fresh results. Any query or URL chosen after reading web
evidence passes through the gate again. Approved pagination binds to the original query
or URL and the requested page or offset. Each managed fetch redirect passes destination
review before a connection opens. It uses the original request's remaining budget and
requires an explicit allow. Search fallback uses the configured provider ladder.

Search already has a provider registry and ladder. `FETCH` is reserved in the provider
capability enum, and `FetchService` already dispatches through `PageFetcher` with a
built-in implementation. The first implementation can use those existing backends. If
fetch needs its own process or network boundary, add an opt-in remote `PageFetcher`
through the existing registration and capability probe. That extension needs a versioned
fetch request and reply, explicit deployment routing, and registration for fetch-only
providers. Enforce DNS, address, redirect, transfer, and cancellation policy where the
connection actually opens. A deployment that requires remote containment must refuse the
operation if its selected backend is unavailable. A provider's capability declaration
describes what it can serve. Plowshare still owns permissions and release.
[Provider verbs](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/search/Verb.java) ·
[Search registration](../plowshare-server/src/main/java/io/aeyer/plowshare/server/search/SearchRegistrar.java) ·
[Fetch provider interface](../plowshare-server/src/main/java/io/aeyer/plowshare/server/fetch/PageFetcher.java)

## Exception-only security monitor

Capture the result in containment before any parent preview, post-hook, or model
projection. Screen the returned search titles, snippets, links, and fetched text. A
suspicious content finding raises a typed security exception carrying a reason code,
result identity, and bounded passage references. Move the applicable prompt-injection
check to this boundary for web results. A successful fetch followed by a flagged
headline should reach the monitor here, while the exact result is still available.

Call the monitor only on that security exception. A clean result proceeds through the
ordinary path. Ordinary provider faults, unsupported content, and timeouts use their
existing failure paths. A hard permission, network, or disclosure denial remains
authoritative. If there is no result to release, settle the operation with its refusal.

The monitor is tool-free. Give it the exact request reference, the gate's decision and
short reason, any justification, the requesting model and agent identity, the worker's
observable calls and outcomes, and the minimum flagged evidence needed to judge the
case. Review observable behavior through receipts and summaries. Treat webpage text and
worker output as untrusted evidence.

| Monitor verdict | Effect |
| --- | --- |
| **Approve** | Release the inspected result as untrusted evidence. |
| **Approve and notify** | Release the result, notify the run owner once through the existing attention surface, and tighten the gate for subsequent calls by the same requester in this run. |
| **Approve with redaction** | Release the original result with the identified spans removed. Mark omissions and preserve source references and offsets for the remaining evidence. |
| **Reject** | Withhold the result and return a short, recoverable explanation. |

The monitor returns structured decisions and validated span references. The harness
applies redactions against an immutable snapshot of the extracted result. A missing,
malformed, late, or out-of-budget verdict withholds the result and produces a tool-level
refusal. The requester-specific escalation in **Approve and notify** is retained for the
run, including on resume. Record the requesting model for audit and key the escalation
to the run and requester so a routing change does not erase it. A later clean result
follows the normal path while its request receives stricter intent review.

### Release and projection

For an approved false positive, Plowshare issues a release record bound to the exact
content digest, rule or finding, approved scope, effective policy identity, and call.
Projection can consume that evidence to avoid repeating the same keyword refusal on
those exact spans. Sensitive-data policies, any other applicable input checks, and
current authorization still apply. A provider flag, a user hook, or a model verdict
alone cannot mint a release record. Restore and recheck the release record on resume. If
its evidence is missing or stale, withhold the affected portion or re-review it within
the current work budget.

The existing `tool.post` event receives only the settled output. A hook can add a
`{note}` pointing to the audit decision or apply its existing redaction operation to
that output. If a hook changes the text, recheck the changed output before projection.
The original release applies only where the same approved evidence can still be
verified. A newly flagged or unverifiable addition is withheld with a recoverable
explanation. This final check has no recursive monitor loop.

Released prose can still mislead a model or affect the fidelity of its answer. Web
evidence carries source attribution and grants no authority for actions. Later outbound
requests are checked against the authenticated task, and other effectful tools still use
their own grants, review, and approval fences. For exact quotations, bind source
references to the captured, extracted version and validate cited offsets against that
version.

## Inspecting verdicts and redactions in Trajectory

Write a server-owned, versioned review record as the existing Trajectory `diagnostic`
entry kind. SECURITY approval reviews already use audit diagnostics, and the diagnostic
kind stays outside chat, model projection, and compaction input. Keep diagnostics
bounded so the verdict and key references fit the normal Trajectory excerpt. Write the
applicable gate and monitor decisions, including reviewer failures, and link them to the
resulting tool call. Commit the release decision and the actual returned result
consistently before parent disclosure.
[Existing review audit](approval-policies.md#coverage-boundaries) ·
[Trajectory and projection](manual/03-architecture.md#log-trajectory-and-model-projection)

| Audit field | What an authorized Trajectory reader can verify |
| --- | --- |
| Call and attempt identity | Which parent tool call, job, requesting agent/model, and contained operation the review governed. |
| Request and policy references | The canonical tool and verb, reviewed request digest, policy sources and version, and associated clarification or human approval. |
| Verdict and reason | Whether the gate allowed, challenged, asked the human, or denied. For a monitor decision, show the triggering rule, verdict, bounded reason, and any notification or tighter-review decision. |
| Result and redaction metadata | The source reference and extracted snapshot digest, the withheld spans or passage IDs in that snapshot, the reason for each omission, and the digest of what the parent received after hooks. |
| Evidence availability | Whether the original was retained, withheld by policy, or expired. An authorized reviewer can follow a protected evidence reference when it exists. |

Store redaction coordinates against the captured text, with explicit units and half-open
ranges. For example, `[420, 608)` in UTF-16 offsets identifies the removed text in one
captured page. For search results, bind the range to a specific hit and field. Record a
safe display reason and use sanitized destination or source labels where full URLs,
query terms, arguments, or reviewer evidence could disclose private material. Keep
credentials, raw withheld text, and reviewer reasoning out of the diagnostic body,
notifications, and parent hook events.

The parent tool result is the released text and any omission markers. The diagnostic
tells the reader why the text was withheld and where it was in the captured source. When
retention policy allows exact inspection, retain the flagged snapshot or bounded
passages behind an opaque reference with independent access and expiry checks.
Conversation read access permits reading the diagnostic subject to its normal authority.
Opening protected evidence requires its own authorization and a bounded, read-only
inspection surface. Retention expiry leaves the verdict and coordinates readable.
Re-fetching a URL later cannot reconstruct the original snapshot reliably.

The harness owns security redaction before `tool.post`. A normal script hook's
`{redact}` records a capped excerpt of the text it replaces in its hook record. By
giving the hook only the settled result, the withheld source passage stays out of that
record. Add an optional audit ID to the existing `tool.post` event for reviewed calls so
a `{note}` can reference the verdict. Trajectory remains inspectable if the hook is
absent or its callback fails.
[Hook record format](../plowshare-server/src/main/java/io/aeyer/plowshare/server/hooks/HookRecord.java)

In Trajectory, find the relevant `web_search` or `web_fetch` tool call, then match the call
ID to the `Web security v1` diagnostic and the tool result. In Desktop, use the **All**
filter to include diagnostics. Select the diagnostic to see the verdict, reason, and
redaction ranges. A CLI read can include these rows and the optional hook note.

```sh
bin/plowshare-cli --json conversation trajectory \
  '{"conversation":"<conversation-id>","tail":true,"limit":50,"kinds":["answer","diagnostic","hook","tool_result"]}'
```

For an approved redaction, the reader sees the monitor verdict and reason, the omitted
passage coordinates, and the resulting omissions in the tool output. A reviewer with
evidence access can compare the held source, the redaction, and the output that reached
the parent. A rejected call still has a result row with a safe reason and a diagnostic
explaining the decision. The inspection path reads retained evidence and issues no
provider request.

## Recovery and execution bounds

Use the existing durable tool-call, approval, and inference receipts. Bind any parked
operation to the original call and its exact arguments. A restart or retry first
reconciles the recorded state. If provider dispatch started and its outcome is
uncertain, preserve that evidence and withhold the result pending reconciliation. A
recovered result can complete its pending review if the snapshot, authority, and policy
are still valid. A later authorized call may start fresh under a new call identity.
Replaying earlier tool effects to recover presentation or audit data is outside this
lifecycle.

Give the gate, worker, and monitor finite configured budgets and inherit any earlier
deadline for the active operation. Charge reviewer calls through the existing allowance
and accounting paths. Park human approval without holding inference, provider, or worker
slots. Cancellation and authority loss fence new dispatch and late disclosure. Routine
operator views can use bounded reason codes, durations, and outcome counts without
carrying tool payloads into general logs.
[Runtime bounds and recovery](../.agents/guides/runtime-and-inference.md#bounded-work)

## Current implementation scope

The contained worker is a native server capability with a single typed operation.
The remote fetch provider remains an optional extension. Trajectory retains verdicts,
redaction coordinates within the captured tool rendering, and the released-result
digest. Withheld originals are marked as unavailable for audit. Approve and notify
heightens review for the requester's run, and retained release receipts carry that
state into a continuation of the same run.

Orchestrations pin authenticated task intent when they start. Nested launches carry
that intent into the child for the same execution account. Agent-generated handoffs
stay separate from the task that can authorize a request. For an admitted scheduled
agent run, the runtime supplies the saved instruction under its execution owner.
The event payload remains data. SECURITY receives the already-granted tool name
and evaluates the specific outbound request against that task and policy.
The script journal
records held web output with its review receipt in the same write. Before a resumed
script invokes `tool.post`, the runtime checks the receipt, policy and expiry again.
The hook's final output is inspected before reaching the script. Release notifications
are sent after recording the final tool result.

Configure the active review deadline with `plowshare.security.web-review-budget`
and the human approval lifetime with `plowshare.security.web-approval-lifetime`.
The default active review budget is 90 seconds. Human approval can remain open for
15 minutes by default. The exact request digest is required for a one-time approval.

## Implementation order and acceptance

1. **Tool identity and request gate.** Rename both offered tools and verify that only
   the canonical names can dispatch either operation. Test the updated definitions,
   grant checks, scripts, and hook selectors. Add the typed SECURITY outcomes, policy
   includes, and approval binding. Prove that unrelated or sensitive requests reach zero
   providers after missing justification, denial, expiry, or grant revocation.
2. **Contained dispatch and monitor.** Route both verbs through the single-use child
   capability and hold results before projection. Use deterministic provider fakes to
   verify the clean path calls no monitor, the benign headline triggers review and can
   produce a cited answer, and a malicious search snippet or page cannot induce extra
   outbound calls or privileged actions. Fault injection covers malformed verdicts,
   timeout, cancellation, and changed policy while work is pending.
3. **Trajectory and recovery.** Verify that each verdict correlates with the original
   call and final tool result. Verify exact redaction spans, independent evidence
   access, retention expiry, hook behavior, and resume without projecting withheld bytes
   or repeating provider dispatch. Verify a deny leaves completed results available and
   an escalation notification is delivered once.

Enable the managed-agent policy with the canonical tool names and release path together.
Verify the same policy outcomes through existing search providers and the built-in
fetcher before depending on a remote fetch extension. The first acceptance case should
reproduce a benign article that discusses prompt injection, preserve the relevant
citation, and expose the gate, monitor, and final result in the same Trajectory.
