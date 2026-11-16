# Approval policy: SECURITY review, personal rules and user bots

Status: core implementation in this checkout, 16 November 2026; verification and
independent review are tracked separately below. Server policy controls, ordinary
`run` review, direct file-operation review, Desktop settings and TUI controls are
implemented. The full plan remains open for native acceptance/check integration,
provider-enforced file version conditions, and any supported access extension.
Nothing here implies a merge, installed-app update or deployment. See the
[approval policy manual](approval-policies.md) for the current user contract.

## Purpose and agreed behavior

Reduce unnecessary human prompts while catching actions that exceed the user's
intent. SECURITY reviews what an operation would access or change, why the task
needs it, and whose machine and files it affects. General command safety alone is
insufficient: an agent with write permission can still violate a read-only task.

Examples:

- A task asks to inspect configuration, but the agent requests a file edit.
  SECURITY rejects the edit or asks the human if the change needs a new decision.
- A coding task requests `git config --local user.email <address>`. Without an
  explicit request to change identity, SECURITY can reject it back to the agent,
  with no approval popup. `--local` changes repository configuration; it is not
  a global Git configuration change.
- A relevant file is outside a project on the user's machine. A provider that
  supports narrowly scoped additional access can ask the human before access.
- An operation asks for a location the provider cannot expose. Explain the
  unsupported capability and refuse; an approval button cannot create support.

## SECURITY outcomes

| Outcome | Operation | Requesting LLM | Human attention |
| --- | --- | --- | --- |
| Clear | Continue through the selected mode's consent checks | Eventually receives the operation result | Only if another consent check requires it |
| Reject | Do not execute | Receives the objection and allowed next steps as a tool result | No approval prompt |
| Ask human | Retain and park before execution | Receives the eventual decision/result through the original call | Show the reason, exact action and requested permission |

A Reject is a decision, not a provider outage. It does not kill the conversation
or require a human to deny the same operation again. The agent may continue other
work, use an allowed alternative, or report a limitation. It must not make the
same prohibited change through another tool. Record attempts at equivalent
circumvention; model instructions alone cannot enforce host isolation.

An Ask human is not a final denial. The human can approve the exact displayed
operation once when it is supported and within their authority. Preserve the
objection and record the override. A Reject has no pending Approve button; a
later direct user instruction can change intent and trigger a fresh review.

## Manual, Smart and Auto

The effective setting belongs to the account on the server. Desktop and TUI
edit and display the same policy; neither implements a separate approval engine.
The default is Manual, with no new automatic consent introduced for existing
accounts. Existing grants remain grants; they do not exempt an operation from
the new intent review when that operation is in the feature's coverage.

| Mode | After SECURITY clears the action |
| --- | --- |
| Manual | Preserve existing consent requirements; requests needing consent go to the human. No new history-based or bot-based approval. |
| Smart | Automatically approve only when personal rules or narrowly matched previous human approvals establish eligibility. Otherwise ask the human. |
| Auto | Apply the Smart checks and then require the selected user's bot to approve. A successful Smart decision alone does not execute. |

SECURITY may Reject or Ask human in all three modes. These outcomes never go to
the bot for an override. Auto does not mean unconditional execution. Bot refusal,
uncertainty, timeout or unavailability routes to the human with the reason.

Deterministic provider/authority denials and explicit human-only gates take
precedence. A hook's deny remains a denial; its request for human consent cannot
be cleared by a model. No reviewer, history match or bot grants a new role, raises
an agent's declared ceiling, or bypasses a workspace fence.

## Personal rules and approval history

Personal approval settings belong to the authenticated account on the server.
The implemented canonical store is private server data under
`personal-approval-policies/<account handle digest>`, exposed only through
`approval.policy.get` and revision-checked `approval.policy.save`. It is outside
workspace and Personal file-editing trees, covered by the existing mandatory
server-data exclusion. This replaces the proposed writable
`Resources/personal/approval-policy.json`: giving ordinary agents that file would
let them change the rules governing their own actions. The mode, selected bot and
rules share one atomically replaced policy revision. A missing policy means Manual;
an invalid or unreadable existing policy never means a permissive default.

Rules have stable IDs, bounded exact conditions and one effect:

| Effect | Meaning |
| --- | --- |
| May approve automatically | Supplies eligibility; SECURITY and Auto's bot still apply |
| Always ask me | Requires a human, even if history would otherwise allow it |
| Do not perform | Reject to the LLM without an approval prompt |

Conditions cover operation kind, project, execution provider/machine, path scope,
read/write/delete/execute intent, command arguments and destination where relevant.
Use argument-aware matching, canonical containment and explicit scope. Do not use
raw path-prefix matching or turn a match on `git` into permission for all Git use.
Rules may include human-readable guidance for SECURITY and the bot, but text is
not an executable grant. Unknown condition types, conflicting effects and invalid
documents must not silently permit automatic approval.

For overlapping matched rules, Do not perform wins over Always ask me, which
wins over May approve automatically. Exceptions must be explicit and validated;
an LLM's claim that a rule has an exception is insufficient. A rule such as
"Do not change Git identity unless I explicitly request it" must bind its
exception to a real authenticated user instruction. A model-generated reason,
repository file or command output cannot supply that instruction.

Policy edits require authenticated owner control and an expected revision.
Protect the canonical policy from ordinary agent and reviewer writes, including
file-tool and command routes. A bot can propose a rule change for the user to
review but cannot authorize itself by changing the policy. Document the trust
boundary for files edited directly by the machine owner. Invalid or unavailable
policy disables new automatic approvals, surfaces the problem and retains
provider/authority restrictions; do not reuse stale permissive rules.

History is bounded evidence from actual human decisions, scoped to the same
account, project, provider/machine, operation, arguments, working directory and
input/content identity. Do not infer permission from automated decisions, another
user's approval, a denial, a withdrawn question or a broad command-name match.
Prior consent and heuristic eligibility are separate facts. A read approval never
authorizes a write; a once-only decision does not become a standing grant.

## User's bot

Auto requires an explicitly selected, currently available personal bot belonging
to the policy owner. Pin its identity and definition revision to the review.
Disabling or removing it invalidates outstanding bot decisions before execution.

The bot receives only authorized task context, the exact requested action,
applicable personal rules, relevant human history and SECURITY's concise result.
It returns a typed Approve or Ask human result with a reason. A refusal is
represented as Ask human with an explicit bot-refused reason. It does not execute
the operation or answer an approval through a general-purpose tool.

Use the existing personal bot definition and inference/accounting capabilities
through a constrained, tool-free review call. Keep this separate from launching
the bot's ordinary autonomous conversation: no recursive approval requests,
arbitrary delegated tools or access to the requesting agent's private context
beyond the authorized review input. No fallback to another account, SYSTEM
identity or unrelated bot. Definition text and retrieved data cannot change the
review protocol or its authority.

## File access and supported expansion

Represent access as an operation-specific capability, not a generic sandbox
bypass. A successful review is not itself permission to open a forbidden path.

For a supported additional local read, the human sees the machine/provider,
target file, read intent, task justification, SECURITY objection and duration
(initially once). The owning provider must register and enforce that exact grant
before the retained call resumes. Revalidate canonical targets, symlinks, current
session, provider generation and current authority at use time. A read extension
must not allow writes, directory traversal, command execution or access from a
different job/session. Show when a file's contents would be sent to the server or
model as part of the read.

Local and remote are capabilities of the owning provider, not labels that decide
trust. `RemoteProvider` is the server-side proxy for a client's disk; its name
does not imply that this disk is an operator-owned remote project. A shared server
project must never expand host access merely because a client asks for approval.
Providers that lack an explicit supported expansion capability return a typed
unsupported/refused outcome. No local fallback for a refused remote path.

An actual access beyond an enforced fence is a defect, not a request that can be
approved after the fact. SECURITY is an intent check before execution; providers
and process isolation remain responsible for enforcing actual effects.

## Server contracts and lifecycle

The following contracts define the implementation and remaining work:

- `ApprovalPolicy`: immutable revision, mode, selected bot and typed rules; ownership
  comes from the authenticated session, never a field supplied by a client.
- `ApprovalPolicyRepository`: authorized-source revision projection and bounded
  owner-scoped reads; persistence implementations alone use JDBC.
- `ApprovalIntent`: original call identity, execution owner, authenticated user
  intent references, operation variant, exact arguments/input digest, target
  provider, project and authority/policy generations. An agent's justification is
  separately labeled untrusted evidence.
- `ApprovalReviews`: coordinates deterministic gates, SECURITY, history and bot
  stages over immutable typed snapshots; no raw JSON crosses into this contract.
- `SecurityReviewer`: a bounded, tool-free inference call returning a validated
  Clear, Reject or Ask human result, reason code and concise explanation.
- `PersonalBotReviewer`: the constrained Auto-stage contract described above.
- Implemented review outcomes are durable Trajectory diagnostics; inference uses
  the existing accounting journal. `HumanApprovalHistory` retains hashes of exact
  owner decisions, with indexed eligibility lookup and expiry. `SuspendedRunCalls`
  retains command and file questions/results with atomic execution claims. A
  separately resumable durable reviewer-stage repository remains future work.
- Future `AdditionalFileAccess`: provider capability discovery and explicit grant/use/
  revoke operations. Implement only for providers that can enforce the scope.

The operation variants must distinguish commands, command sets, file reads and
file mutations. A move includes source and destination; an edit binds the base
revision and proposed change. Do not disguise a file request as a shell command
or reuse command prefixes to approve it. Budget-extension approvals retain their
existing typed lifecycle and are human-only for this feature's first release.

Proposed flow:

1. Validate the request and current authority. Determine whether any missing
   access is unsupported or eligible for human-requested expansion, without
   reading the forbidden file to make that decision.
2. Capture authorized task intent, the exact operation and current policy once.
   Apply deterministic prohibitions and human-only gates. Recheck freshness only
   when a later effect or concurrent change requires it.
3. Persist review identity before dispatch. SECURITY evaluates intent and effect;
   opaque commands or insufficient evidence cannot produce an automatic Clear.
4. Reject returns the refusal into the original tool-result slot. Ask human opens
   one durable question. Clear proceeds through Manual/Smart/Auto consent logic.
5. In Auto, retain a bounded bot review stage. Only the service owning this stage
   can apply its typed result; no client-supplied bot or reviewer decision counts.
6. Atomically commit one final decision, then claim the retained operation once.
   Recheck live authority, provider, policy and content/version fences before
   effects. A changed request requires new review; no silent replacement.
7. Append the outcome in the original call's history and retain review provenance.
   Notify the human only when attention is required; Reject remains inspectable
   in activity/history rather than generating an approval popup.

Carry a stage generation through all asynchronous callbacks. Cancellation,
withdrawal, a newer policy revision, manual intervention or job completion fences
late results. Switching from Auto/Smart to Manual prevents unfinished automatic
approvals from executing. Review completion never resurrects cancelled work.
Existing standing approvals are not consumed before required intent review.

Model/provider calls occur outside database transactions. Short reads, controls
and asynchronous acceptance have a configurable two-second default ceiling;
reviews have separate finite configured queue-plus-execution budgets. Persist
and park rather than hold an interactive socket request through inference.
Cancel at the owning dispatcher on expiry, fence late completion, and retain
physical capacity/accounting until work and bounded cleanup settle. A timeout or
invalid review means Ask human with a service-failure reason, never a fabricated
security rejection or automatic approval. Charge review usage to its explicit
existing accounting owner; do not reset the task's allowance or invent unmetered
bot calls. Insufficient review allowance also asks the human.

Restart recovery resumes durable review/decision stages without re-running an
operation with uncertain effects. Preserve original request identity and inspect
receipts/status after uncertain delivery. No automatic mutation replay. Legacy
review/command-judge rows retain their historical provenance and are not promoted
to human approval history.

## Current implementation and gaps

| Area | Implemented contract |
| --- | --- |
| Server policy | Owner-only WebSocket get/save, strict DTOs, bounded private file storage, atomic expected-revision updates, Manual default, available-bot validation. |
| Intent review | Tool-free SECURITY and optional bot calls, existing execution authority/allowance, shared finite deadline, cancellation and late-result fences. Deterministic Reject rules run before inference. Rules in the same project/provider scope remain reviewer context across command/file forms. |
| Ordinary `run` | Review effective hook-rewritten argv/cwd/stdin before effects. Inspect consent before review, claim it afterwards, and recheck policy/bot/provider before execution. Retained human execution claims its own source approval. |
| Direct files | `file_read`, `file_edit`, `file_delete`, `file_move` use typed once-only questions and retain exact original arguments. Moves bind both paths and provider identity. Hook rewrites and changed providers refuse a retained call. Existing provider fences still apply. |
| Human history | Only the retained execution owner's authenticated USER answer creates evidence. Hash binds project, provider/session, operation, arguments, paths, cwd and input. Automated/legacy decisions are not imported. Default eligibility lifetime is 30 days. |
| Desktop | Personal mode, bot and JSON rule editor; draft preservation and explicit reconciliation after uncertain save; escaped typed file prompts; Approve/Deny on Chat, Inbox and Trajectory. |
| TUI | `/approval-mode manual`, `smart`, `auto PERSONAL_BOT`; complete rules via `/approval policy get` and `/approval policy save`; typed file explanations and once/deny keyboard choices. |
| Shared contracts | TypeScript owns operation and file projection contracts; native SDK/schema/catalog outputs are regenerated. File approval requires the displayed `fileDigest`; older clients cannot approve an unrendered file variant. |

Remaining coverage is explicit:

- Native orchestration acceptance sets, stage checks and `Commands.Port` still
  have their own judge/consent owner. They do not yet use this personal intent
  review pipeline. Migrate them as a separate lifecycle slice with tests for
  existing sets, mixed member decisions and owner-specific continuation.
- File discovery/metadata/code-map operations are outside direct file review.
  Rules on a direct file operation do not establish universal filesystem policy.
- A reviewed file mutation binds its requested change, not a provider-atomic base
  content revision. Existing `old`/`new` uniqueness and provider write/create
  semantics remain. Conditional mutation protocols are required before claiming
  protection from independent concurrent writers or symlink replacement races.
- Additional access is unsupported by the current providers. A refused path stays
  refused; no human decision creates a server-host or remote-root bypass.
- A missing authenticated task (including delegated work without authenticated
  root-intent evidence) is uncertainty for SECURITY. It must ask the human rather
  than accept the requesting model's prose as consent.
- The shared SQL stack retains its documented completion-deadline enforcement gap;
  these indexed repository operations do not establish universal cancellation of
  pool acquisition or database work. Reviewer and policy-file budgets are separate.

Command review cannot infer every runtime effect of arbitrary shell code. Provider
and process isolation remain authoritative. A model classification is not a
filesystem enforcement boundary.

## Shared operations and client experience

Implemented WebSocket operations are `approval.policy.get` and
`approval.policy.save`. Save derives the owner from the session and requires the
current revision. Review decisions are inspected in the authorized Trajectory;
`approval.review.get` remains a possible later structured review projection.
Extend existing `approval.list` and `approval.answer` with typed file/review
projections and once-only file answers. Do not introduce an HTTP fallback.
Additional access grant negotiation belongs to the explicit provider protocol.

Clients display effective mode, policy revision and selected bot; confirmed server
state replaces local pending edits. Both clients expose policy/rule inspection and
editing, bot selection, pending SECURITY/bot review, human decisions and history.
An older server shows an explicit unavailable feature state with existing manual
controls; an older client must not receive an unrenderable new approval variant.
Negotiate supported projections/controls and require an update where necessary,
rather than silently treating a file grant as a command approval.

Human prompts explain why attention is required, whether an objection can be
overridden, the exact action, machine, paths, input/diff, matched rules and bot
reason where present. No raw private chain of thought. File-access grants and
SECURITY overrides are once-only initially; existing command conversation/project
controls remain available only where their established contract permits them.
TUI keys and pasted text must never approve a wider scope than the wording shows.

Reliability requirement from the reported intermittent Desktop Approve/Deny
behavior: immediately acknowledge the click, show Recording/Reconciling while
pending, and display a confirmed result, withdrawal or actionable error. Review
completion, renderer replacement and account/connection changes must not discard
feedback or act on stale request IDs. After uncertain submission reconcile the
original answer; do not resend it automatically. Track diagnosis and focused
regression evidence in the Desktop implementation slice. TUI answers need the same
certainty and reconciliation semantics, including plain-text mode.

## Implementation sequence and acceptance

The table below remains the delivery plan. The current-implementation table above
identifies completed code coverage; the remaining boundaries are not closed. Assign named implementers
and independent reviewers before starting a slice. The reviewer examines the
actual diff and acceptance evidence; implementation, verification/review, merge,
deployment and live validation remain separate states.

| Slice | Depends on | Implementer / independent reviewer roles | Deliverable and acceptance evidence |
| --- | --- | --- | --- |
| A: contracts and policy | None | Server/auth implementer / independent auth reviewer | Typed policy, rules, precedence, owner/revision checks and intent provenance. Fixtures prove Git identity rejection, explicit-user exception, read/write mismatch, invalid policy, cross-account denial and agent policy-write denial. |
| B: durable review foundation | A | Server persistence implementer / independent lifecycle reviewer | New Flyway migration(s), narrow repository interfaces, atomic review stages and authorized bounded history. Prove competing answers, policy invalidation, cancellation and restart recovery. Preserve legacy rows and budget semantics. |
| C: SECURITY and command integration | A, B | Runtime implementer / independent auth/runtime reviewer | Tool-free validated tri-state review; integrate RunTool, acceptance sets and orchestration checks before effects/consent consumption. Deterministic provider fixtures prove Reject returns to the original LLM with zero execution and zero approval notifications; timeout/invalid output asks the human; existing hooks and grants retain their ceilings. |
| D: file review and explicit access | B, C | Files/provider implementer / independent filesystem-authority reviewer | Typed file approval and retained-call recovery; explicit capability for additional local reads in Node-backed Desktop/TUI. Tests cover read-only grants, symlink changes, wrong machine/session, changed content, cancellation, unsupported remote/server paths and no replay after an uncertain effect. Unsupported providers remain explicit refusals. |
| E: user's bot stage | B, C | Runtime/personal implementer / independent authority/accounting reviewer | Constrained personal-bot review after successful Smart checks. Prove Clear alone cannot execute in Auto; bot has no tools, cannot override Reject/Ask, cannot edit policy, and late/removed-bot results cannot authorize execution. Refusal/unavailability/allowance exhaustion asks the human. |
| F: shared transport and SDK | A, B; variants from C–E | Transport/SDK implementer / independent contract reviewer | Owner-authenticated policy controls, typed approval/review replies, provider capability negotiation and compatibility handling. Regenerate owning schemas/catalogs; conformance fixtures cover invalid fields, old peers and uncertain answers. Deliver shared changes with each dependent server/client slice rather than leaving incompatible intermediate commits. |
| G: Desktop | F; server stages exercised by fixtures | Desktop implementer / independent client reviewer | Mode/rules/bot controls, tri-state review display, file access prompts and click reliability. Renderer/client and Electron fixtures show immediate feedback for Approve/Deny, settled/failed/uncertain outcomes, no duplicate sends, escaped review text, reconnection and multi-window state fencing. |
| H: TUI | F; server stages exercised by fixtures | TUI implementer / independent client reviewer | Equivalent mode/rules/bot controls, terminal and plain-text review explanations, once-only file decisions and action feedback. Logic/composition tests cover keyboard scopes, pasted text, stale/withdrawn questions, unknown delivery, reconnect and account switches. |
| I: integration and adoption | C–H | Release integrator / independent end-to-end reviewer | Update manuals and owning subsystem guides; non-DB check passes. Fixture scenario parity across Desktop and TUI. Document opt-in deployment, migration/backup procedure and any unsupported provider. Live validation is separately scoped; never infer it from fixtures or healthy processes. |

Start with A and B, then land C and its shared contracts before enabling Smart.
File consent in D is required for the access-expansion claim; do not ship that
claim with command-only recovery. Auto remains unavailable until E and its shared
contracts are ready. G and H consume the same committed fixtures/contracts.
Feature capability flags keep incomplete modes unavailable; no silent downgrade
from Auto to Smart or from a rejected remote path to a local one.

The Desktop button issue can be diagnosed and fixed independently of the new
review engine. Its regression belongs in G; do not defer a confirmed existing
manual-approval defect until Auto is ready.

### Verification plan

Default to mocked repositories, fake providers/clocks and deterministic model
responses. Extend existing RunToolTest, FileToolsTest, ModelCommandJudgeTest,
AcceptanceGateTest, ApprovalFramesTest, ApprovalProjectionTest and continuation
tests as responsibilities change; add focused policy/review/bot tests. Do not
prescribe live model calls for repeatable review-contract tests.

Real PostgreSQL is justified only for the new migration, row mapping, constraints,
atomic claims and transaction/race assertions that require PostgreSQL. State that
reason, tag those tests `full-db`, and select only the owning tests with
`-PfullDb --tests`. Do not run real DB tests for this documentation change.

For implementation, use the relevant focused server tests, then:

```sh
pnpm --dir sdk/typescript test
pnpm --dir plowshare-desktop typecheck
pnpm --dir plowshare-desktop test
pnpm --dir plowshare-desktop test:approvals
pnpm --dir plowshare-tui typecheck
pnpm --dir plowshare-tui test
./gradlew format
./gradlew formatCheck
./gradlew check
```

Use [SDK generation instructions](sdks.md#shared-contract) and the owning scripts
for generated artifacts; do not hand-edit catalogs. Extend the approval Electron
smoke fixture for the new flows. `check` does not replace the relevant UI fixture
or prove an installed application was updated.

Release acceptance includes: unauthorized Git config change rejected without a
popup; authorized read allowed without acquiring write permission; task-inconsistent
write rejected; extra supported local read approved once by a human; unsupported
host access refused; Auto requires the bot; human-required cases explain why;
both clients visibly acknowledge answers and reconcile uncertain delivery; restart
and concurrent clients cannot duplicate an operation. Report unsupported coverage
and actual validation evidence explicitly.

### Existing approval-button defect: local implementation evidence

During planning, the user reported intermittent Approve/Deny clicks and identified
Trajectory as another affected view. Both the main approval bar and Trajectory
unconditionally replaced their approval markup on state updates. Replacing an
unchanged button between pointer-down and pointer-up detaches the click target.

The local fix preserves each region's DOM when its approval markup is unchanged.
Pending, failed, disconnected and settled states still update the controls. The
Electron approval smoke test now deliberately refreshes state during Approve and
Deny presses in the main window and an Approve press in Trajectory, waits for the
target window to receive the update, and checks that the pressed button remains
attached and exactly one answer is sent. This regression failed against the
original main-window renderer and passed with the fix.

Verification of the preceding button-only slice: Desktop build, typecheck, focused
typed lint and formatting passed;
all 391 Desktop tests passed across the main test run and a selected rerun of the
13 protocol tests whose loopback listeners were blocked by the sandbox. The
Electron approval fixture passed. Documentation links and diff whitespace were
checked. No database tests or repository-wide Gradle check were run. This is a
local source fix, not an updated installed Desktop app or a live deployment.
The policy implementation and its subsequent verification are recorded below.

## Implementation verification

Implementer: Codex. Independent review remains pending. No merge, packaging of an
installed application, deployment or live model validation is recorded.

- `./gradlew check sdkContractCheck` passed, including 8,217 server tests, client
  checks, generated contracts for 294 operations and 761 canonical SDK DTO cases.
- The four selected PostgreSQL tests passed for migration V270, typed file
  questions and original tool retention, single-use execution claims, revocation
  during review, and exact owner human-history eligibility/expiry. The full
  database suite was not enabled.
- The Electron approval fixture passed policy editing, loss of a committed policy
  reply followed by explicit read reconciliation with its draft preserved, typed
  file consent with digest echo, and Approve/Deny across Chat, Inbox and
  Trajectory. Pressing across an unrelated refresh retains the same button and
  sends one answer per click. Five focused renderer tests passed.
- `sdkCheck` was attempted and stopped because the .NET toolchain is unavailable.
  Generated contracts/DTO cases passed; complete native compilation and socket
  conformance remain unverified. No toolchain was installed implicitly.

The SECURITY/bot decisions in these tests are controlled fixtures. They verify
policy ordering, malformed-output refusal, authority/cancellation and lifecycle
behavior; they do not establish a live model's classification accuracy. The
remaining coverage boundaries above are open work in the delivery plan.

## Engineering references

- [AUTH](../.agents/guides/auth.md), [workflows](../.agents/guides/workflows.md),
  [runtime and inference](../.agents/guides/runtime-and-inference.md).
- [Applications and files](../.agents/guides/applications-files.md),
  [transport and SDKs](../.agents/guides/transport-sdks.md),
  [clients](../.agents/guides/clients.md).
- [Persistence and cache](../.agents/guides/persistence-cache.md),
  [delivery and review](../.agents/delivery-and-review.md),
  [coding standards](coding-standards.md).
