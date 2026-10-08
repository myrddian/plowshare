# Skills and agent rules

The server reads skill packages and agent instructions through the existing
filesystem sources. Connected clients provide opaque filesystem operations over
the file channel. They do not interpret the Markdown or select instructions.

The implementation provides scoped discovery, explicit grants, pinned execution,
package resource reads, all four context modes, agent rule prompts, bound
skill/orchestration commands, desktop command discovery and question popups,
and TUI command completion/help. The server performs resolution and dispatch;
clients use the existing conversation and filesystem transports.

## Packages and authority

A connected project uses `.plowshare/skills/<name>/SKILL.md`. The server's data
tree uses `projects/<project-id>/skills/<name>/SKILL.md` and
`global/skills/<name>/SKILL.md`. Shipped packages use `skills/<name>/SKILL.md` in
the resource bundle. Each account can also put reusable packages in
`~/.plowshare/connections/<server-account-key>/personal/Resources/skills/<name>/SKILL.md`; the Personal union
synchronizes them to the server. Authority is server project, rooted session,
account Personal, server global, then shipped. See [Personal space](personal-space.md). A malformed or unreadable override does not select a lower-tier
package with the same name. An unreadable authority tier stops fallback and
reports its refusal.

Use the [Agent Skills format](https://agentskills.io/specification):

```markdown
---
name: code-review
description: Review the supplied change against the project's conventions.
metadata:
  author: my-team
---
Read references/review-guide.md when checking a change.
```

The parser supports `name`, `description`, `license`, `compatibility`, `metadata`,
and `allowed-tools`. Metadata values must be strings. `allowed-tools` is a
space-separated string; it declares a constraint, never a new permission.
Unsupported fields and duplicate YAML keys are refused visibly.

The harness uses its existing `tool.pre` and `tool.post` stages to check
`file_edit` operations on `SKILL.md` packages and legacy `skills.yml` policies in
Plowshare skill directories. `tool.pre` checks complete `content` using the same
discovery parsers and denies invalid source before writing. `tool.post` reads
acknowledged edits through the run's authorized filesystem provider and checks
the saved snapshot, including partial edits and any arguments rewritten by later
hooks. Results include correction guidance for missing frontmatter, duplicate
keys and other invalid fields. The `file_edit` tool is unchanged.
Each refusal identifies the file, preserves the precise parser reason (including
YAML line and column diagnostics), and gives a correction for that violation.
Independent field failures are collected in deterministic order rather than
stopping at the first one. Broken YAML or frontmatter fences stop field checks;
the response explains that further failures may remain after repairing the syntax.
Every format rejection also includes a deterministic valid example for the file
type. A package example uses its valid directory name, required name and
description fields, and an instruction body. A policy example uses one entry
with `agentVisible: false`. These are reference scaffolds, not replacement
instructions or decisions about the author's desired visibility or execution mode.
For example, a duplicate key reports which key was repeated and asks for its
entries to be merged; it does not suggest replacing valid frontmatter.

Post validation happens after saving: an invalid file remains saved and discovery
continues to refuse it until repaired. The harness never invents a description,
chooses a context mode, changes visibility or rewrites the file automatically.
If the provider cannot supply a complete bounded snapshot (including older
clients without raw snapshot support), the result says validation is unverified
and retains the acknowledged edit. Inspect the saved file before making a
correction; do not replay the acknowledged mutation. Validation observes a
snapshot, so a later external edit still requires fresh discovery validation.

Plowshare's extensions are `agent`, `mode`, `agentVisible` and `argument-hint`. An
optional `argument-hint` is nonempty text of at most 1024 characters, describing
useful inputs, for example `argument-hint: '[location or date, optional]'`. It is
shown in the command catalog and client completion; it is help text, not an
argument schema or a requirement for extra text. When omitted, discovery says
"Optional arguments; may be omitted". Skills needing specific inputs should
describe them in their instructions and ask for missing information when invoked.

`agent` defaults to `interlocutor`; `mode` may be `INHERITED`, `SUMMARISED`,
`NEW`, or `DIRECT`.
`DIRECT` cannot select another agent. Explicit user commands default an absent
mode to `DIRECT`; choose a delegated mode explicitly when the package selects
an `agent`. Model-selected `skill_run` still requires a configured package mode.
An invocation cannot override a declared mode or use DIRECT on a package that
selects an agent. Unsupported context modes fail explicitly before any child or
model work; execution never switches modes.

Resources resolve within the selected package, including the exact client root
that supplied it. The server reads them on demand. Traversal, unsafe path
segments, hidden resources, and symlinks leaving the permitted boundary are
refused. Existing file access restrictions and command approvals still apply.
`agentVisible` is a boolean and defaults to `false`. A skill is hidden from the
model's automatic-use catalog unless this flag, or its current project's override,
enables it. When visible and granted, the model receives its name, description,
executor and mode and may select it while completing the user's task. The full
instructions load only on invocation. The harness does not match descriptions or
start work merely because a skill was discovered.

## Project discovery overrides

A shared skill's definition can remain unchanged while each project controls
whether its bots and agents can discover and select it:

```json
{
  "version": 1,
  "name": "my-project",
  "skills": {
    "personal-capture": {"agentVisible": true},
    "personal-maintain-knowledge": {"agentVisible": false}
  }
}
```

Put this in the project's selected JSON manifest; see
[Project runtime configuration](projects.md#project-runtime-configuration).
Legacy `.plowshare/skills.yml`, server-tier `skills.yml` and Personal's
`Resources/skills.yml` remain readable. JSON values take precedence within the same
location. Server project policy overrides rooted-session policy, which overrides
account Personal defaults and the package's `agentVisible` value. An explicit false
hides a package even if its own flag is true. Rules remain scoped by the current
account/project and rooted session. Invalid, unreadable or escaping override files
refuse the catalog instead of selecting a lower policy silently.

Overrides affect model discovery and selection, not grants, package identity,
instruction text, context mode or tool permissions. The effective `agentVisible`
value is included in command metadata. Hidden, granted skills remain available
through an explicit `/skill:<name>` command; the model cannot bypass hiding by
guessing a name and calling `skill_run`. Automatic selection also needs a
configured context mode. A skill without one needs an explicit command with
`--mode` until its author configures the mode.

## Execution

An agent or Bot with a declared skill grant receives `skill_run` and `skill_read`
from the harness. Model-selected `skill_run` requires effective `agentVisible: true`;
hidden skills use the bound-command path. `skill_read` reads supporting files of
an active, pinned invocation. `skill_run` takes `name`,
`arguments`, a stable UUID `invocation`, and optionally `mode`:

```json
{"name":"code-review","arguments":"Review the current change","invocation":"223f98f3-a44b-4a9f-91c6-2e8c045dcd5a","mode":"NEW"}
```

NEW resolves the package's Agent in the caller's project/session tier, checks
both skill grants and the existing filesystem non-escalation rule, and delegates
through `AgentRunTool`. The child has its own durable log and shares the parent's
model budget, cancellation flag and approval propagation. The shipped
`interlocutor` is a delegable ordinary Agent and explicitly declares
`skills: ["*"]` for this default route.

INHERITED captures the parent's complete entry log at a fixed boundary, excluding
its role/system prefix. It stores an immutable copy as historical context data in
the child and receipt, with original entry metadata and result handles. Historical
tool calls are data and cannot execute. The child's `result_read` can redeem only
copied handles belonging to that child and account; it reads copied bytes rather
than a live parent view. Later parent turns do not change the copy. Ejected result
payloads or a context larger than 8 MiB refuse rather than silently truncate or
switch context modes.

SUMMARISED captures the same boundary and uses the configured conversation folder
to summarise it. The folder call spends the shared model budget, observes job
cancellation and is attributed as a FOLD inference. It leaves the parent's entries
and compaction state untouched. The child loads its own role, rules and skill.

For an explicit DIRECT command, the harness loads and activates the pinned skill
before the first model call. Its command notice tells the current Agent or Bot to
read and execute the skill body, with frontmatter removed, and includes the
original arguments as user data. No `command_dispatch` or `skill_run` call is
needed. Skill constraints apply to the first inference's offered tools as well
as execution. Model-selected DIRECT activation still returns the instructions
through `skill_run`, with constraints applied before later calls in the same
batch. Both paths retain the current executor's role and grants.

Migration V90 retains accepted instructions, source, origin, executor and mode.
The source is pinned before child model work begins and is reapplied on resume.
An invocation UUID belongs to one account and request. Reusing it returns its
existing receipt; changed arguments refuse, and ambiguous accepted work is never
replayed. A claimed invocation interrupted before a child starts currently
requires inspection; this implementation does not automatically retry it.

`allowed-tools` filters declared tools and fences extra tools as well. Names must
be tools the server binds; provider-specific expressions are refused rather than
converted into new grants. `skill_read` remains available for package resources.
Resource reads verify that the currently reachable package still matches the
pinned source and origin; a changed or missing package is not silently replaced.
DIRECT constraints finish with the current run; an approval pause retains them.
Activation and receipt messages tell the current executor to perform the supplied
instructions with its permitted tools, rather than wait for a background worker
or invoke the skill again. An ordinary clarification question ends that run;
the user's answer adds conversation context, not replacement invocation arguments.
The agent continues the task from the recorded instructions and result with its
current permissions. A changed-request refusal reports the original invocation's
state and warns against bypassing it with a fresh UUID. These messages do not
extend skill constraints into a later run or automatically resume work.

## Skill grants

An agent or Bot has no skill access unless its definition declares it:

```yaml
skills: [code-review]
```

Use `skills: ["*"]` to grant all packages in the resolved scope. A named list
grants only those packages. Both Bots and ordinary agents honor the list; `bot: true`
does not grant skills implicitly. The shipped bots declare the wildcard explicitly.
Neither a grant nor `agentVisible` bypasses project/account visibility or creates
filesystem/tool grants. Role copies preserve the declared skill grants. Existing
custom bots that relied on implicit access must declare the desired skills.

Personal's `Resources/skills.yml` supplies the account's model-discovery defaults
across projects. A rooted session's `.plowshare/skills.yml` overrides those defaults,
and the server project's `skills.yml` wins over both. These overrides never grant
execution. An `agentVisible: false` skill stays in the user's command catalog when
the selected agent has its grant; it requires explicit user invocation.

`agent.list` includes the declared `skills` and a `commands` catalog containing
only granted skill and orchestration metadata. Each entry carries its kind,
name, help, argument hint, executor, mode, tier, and definition hash. Commands use
`/skill:<name>` and `/orchestration:<name>` identities. This discovery slice emits
only qualified names; bare aliases need a shared resolver that also accounts for
native client commands. Refused skill definitions appear in the applicable
row's `withheld` diagnostics. Older clients can ignore these extra fields;
shared TypeScript clients validate them when present.

## Bound commands

Submit commands as ordinary conversation text:

```text
/skill:code-review --mode=SUMMARISED Review the current change
/orchestration:deep_research Investigate the supplied question
```

A package with a declared `mode` does not need the flag. A portable package with
no mode defaults to `DIRECT`. Use `--mode=INHERITED`, `SUMMARISED`, or `NEW` to
select delegated context, or `--mode=DIRECT` to state the default explicitly.
An orchestration does not take this flag. Skill arguments may be empty: both
`/skill:weather-capture` and `/skill:weather-capture --mode=DIRECT` load the
skill's instructions. Orchestration commands require a description of the work;
an empty invocation reports the command and its argument hint. Only qualified
commands are exposed, avoiding collisions with native client commands.

The harness validates the selected Agent's catalog and binds the original
arguments, definition hash, mode, owning account, conversation, caller and source
run in migration V91. An explicit DIRECT skill is activated by the harness once
before inference; its notice begins "The user has issued the following command"
and contains the skill instructions and original arguments. Delegated skills and
orchestrations receive a dispatch notice and structured JSON; the Agent invokes
`command_dispatch` with only that UUID. Model-supplied replacement arguments are
refused. Skill/agent/orchestration start alternatives are fenced and withheld
from the model's tool catalog for the bound command. A model that ends before a
required dispatch does not produce a successful command outcome.
Existing orchestration phrase notices are suppressed for bound slash commands.

Dispatch claims the receipt once before invoking the operation. Orchestration
starts also use the existing atomic start receipt. Skill execution uses the same
UUID in its pinned receipt. Changed or unavailable sources refuse. A retry reads
the existing state/result rather than repeating work. A continuation can recover
bound, interrupted and failed bindings; ambiguous dispatch remains inspect-only.
An ordinary message does not create a bound command. The model may choose a
granted, model-visible skill for that task; the harness never automatically
dispatches by description matching.

The desktop Commands tab and composer slash picker display the selected Bot's
runnable skills and orchestrations with help. Typing `/` offers keyboard completion;
selecting one prepares an editable draft. The tab refreshes the server catalog for
that project's owning session and displays refused discovery reasons. Multiple
project roots remain connected independently, including roots loaded on demand.
A skill without a declared mode starts its draft with DIRECT selected by default;
the Commands tab offers delegated context overrides. Argument hints appear in
both the tab and slash picker. TUI completion and
`/help` expose the same server-provided metadata, and qualified commands pass
through to the server unchanged. TUI `/commands` refreshes the selected agent's
catalog and completion; `/skills` shows its runnable skills, including skills
hidden from the model. Listing commands does not start work or choose a context
mode. Each TUI process serves one active local root; switching projects replaces
the selected catalog. Personal policy applies through the shared server resolver.
Draft commands use the existing conversation WS
operation. The desktop's separate **Start workflow** action calls the idempotent
`orchestration.start` WS operation directly, with server authorization against
the selected Bot's grant. It retains the request ID across uncertain acknowledgments
and reloads so explicit retries recover the same run. There is no HTTP fallback.

## Agent instructions

[AGENTS.md](https://agents.md/) is canonical; `AGENT.md` is a compatibility alias.
Identical aliases load once. Conflicting aliases refuse execution before any
model call.

Server rules live beside a tier's `agents/` directory or in
`agents/<agent-name>/AGENTS.md`. A rooted client supplies project-root rules,
`.plowshare/AGENTS.md`, and `.plowshare/agents/<agent-name>/AGENTS.md`. Rules are
applied from global to account Personal to session to server-project authority, with the agent's
specific rules after that tier's project rules. Rules modify the selected
executor's instructions and cannot change its grants.

Root and agent-specific rules are included in model prompts and next-context
inspection. Filesystem-root rules also load from the selected providers. Script
drivers remain programs, so Markdown is not appended to their source.

Before `file_read`, `file_stat`, `file_edit`, `file_delete` and `file_move`, the
server resolves parent-to-child instructions within the selected filesystem root.
Moves check both source and destination scopes. New or changed rules are returned
to the model before any operation runs; every affected call in the current batch
is refused until the next model step has received those instructions. Conflicting
aliases and unreadable rules refuse. The client continues to transport filesystem
operations and enforce its existing path boundaries; it does not parse rules.
Searches and shell commands retain the normal root instructions and tool policies;
they do not imply exhaustive discovery of every descendant directory's rules.

The complete target behavior is in the
design contract.

Bot definitions may set an optional `display-name` in frontmatter. The `name` still
matches the filename and remains the routing identity. Agent discovery exposes the
authored display name and the origin of the resolved definition. Desktop's bot details
show this identity alongside resolved permissions, withheld grants, and current local
folder status; offered context tools remain distinct from declared file scopes.
