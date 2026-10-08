# Agents, bots and permissions

## Start from a capability, then a definition

A bot is the assistant a person talks to. An agent is a specialist definition the
system or another agent can invoke. Both select model behavior and explicit
capabilities. A bot can discuss a task it lacks permission to execute; a prompt
asking it to edit files does not create a filesystem grant.

Shipped definitions include general conversation, coding/review, document reading,
research workers and internal judgment/summarization roles. Inspect `agent list`
or the client's roster in the intended project. Served, disabled and exported
definitions are different states. A definition can be disabled because its model
is not served or its configuration is invalid.

Definitions are Markdown with YAML frontmatter followed by prompt text. Use an
existing definition as a starting point and preserve its supported field names.
Names of tools, delegates, orchestration grants and model bindings must resolve.
An unknown field is not a harmless way to attach your own configuration.

## Agent aliases and guidance

An agent may declare a logical `alias` shared by several concrete definitions.
The caller can invoke that alias while the server selects one complete definition
using the effective harness profile of its model. It does not combine prompts or
inherit grants between variants.

```yaml
name: coder_minimal
alias: coder
guidance: minimal
model: reasoning
```

`guidance` is optional and accepts `minimal`, `standard`, or `guided`, ordered
from least to most. Selection first takes an exact profile match, then a variant
without `guidance`, then the least-guided variant. An unset or custom harness
profile follows the same fallback rule. Each alias allows at most one variant
per guidance level and one unqualified default; duplicates are refused visibly.
All variants must use the same `model` binding. If that binding can route to models
with different effective harness profiles, the variants are disabled with an
explanation; use a single-profile binding or an explicit model.

A concrete variant name such as `coder_minimal` always selects that definition.
An existing definition may retain the logical name itself by declaring
`name: coder` and `alias: coder` without guidance. That name then addresses the
family, and the same file supplies its default. A logical alias cannot collide
with a concrete definition that has not declared membership in that family.
Bots and orchestration conductors do not accept aliases.

The shipped `coder` is the unqualified default. When its `reasoning` model has
the `minimal` harness profile, the alias selects `coder_minimal`, whose tools are
`file_roots`, `file_read`, `file_edit`, and `run`. Other profiles use the existing
coder unless another matching variant is authored.

Listings expose the alias and concrete variants, with the selected variant's
capabilities and origin. When the alias selects a differently named variant, its
description names that selection. Run receipts, job submissions, delegated logs,
and execution events use the selected concrete identity. A submitted run retains
its immutable definition even if files change afterwards; future resolutions may
select a new definition. Approval continuations use the recorded concrete identity;
they do not select another variant if the model profile changes while approval is pending.

A `calls: [coder]` grant is validated against every possible variant, including
cycle detection, delegation eligibility and workspace grants. The selected
variant must still satisfy the normal execution authorization. An alias never
expands a caller's authority or bypasses `exported`. Orchestration conductor grants
and acceptance checker restrictions are also validated against every variant.

## Definition sources and precedence

Definitions can come from shipped resources, server-global configuration, account
Personal resources, project configuration or an eligible rooted client session.
The exact tier rules depend on the definition kind; the skills and orchestration
manuals spell them out. Inspect the effective definition in the same project and
session that will run it, rather than assuming a global file always wins.

Server project resources live under
`<PLOWSHARE_DATA_DIR>/projects/<numeric-project-id>/`. The numeric ID is not the
display name. Client definitions live under the attached workspace's
`.plowshare/`. Personal resources synchronize through its private union.

An unreadable or malformed authoritative override must not silently fall back to
a less specific definition. Editing a definition affects future resolution; an
ongoing workflow or skill invocation keeps its pinned source and identity where
the lifecycle contract requires it.

## The layers of authority

| Layer | What it controls |
| --- | --- |
| Authentication, project role and token ceiling | Which account/project can read, run or manage an operation |
| Definition tool grants | Which model-facing capabilities are offered |
| Delegate/skill/orchestration grants | Which other definitions may be invoked |
| Workspace scopes and exclusions | Which file operations reach which paths |
| Project writable areas | Which server files may be mutated |
| Command policy | Whether a command can run locally or on the server |
| Hooks and approvals | Stage decisions, explicit refusals and human questions |
| Budgets and caps | How much admitted work may proceed |

These layers intersect. An approval cannot override project membership or a hook
denial. A routing allowlist cannot grant access to another account's Personal
space. Installing a skill cannot grant a command sandbox or a delegate's wider
filesystem access.

## Delegation

`agent_run` invokes an authorized agent. The child has its own durable execution
history, shares the parent model-call allowance and cancellation propagation, and
returns its actual outcome. Its filesystem grants cannot escalate past the
caller. Delegate lists are explicit; cycles or invalid edges are handled during
definition admission rather than trusted to a prompt.

Use delegation for a specialist result the current task needs. Use messaging for
an asynchronous exchange with an addressed agent/bot instance. A bot can receive
messages without being an eligible delegated agent.

## Skills and slash commands

A skill package has `SKILL.md` frontmatter, instructions and optional supporting
resources. The package describes a procedure. `allowed-tools` constrains usage;
it never grants capabilities the executor lacks.

Skills are not automatically triggered by description matching. Model discovery
requires an explicit `agentVisible` policy and the caller's grant. Hidden,
granted skills can remain available through bound human commands such as
`/skill:<name>`. Human skill commands accept empty arguments and default to
`DIRECT` when the package omits its mode. Use `--mode=INHERITED`, `SUMMARISED`,
or `NEW` to select delegated context. A package selecting another agent requires
a delegated mode. Model-selected invocation still requires a configured mode.
Optional `argument-hint` frontmatter explains useful inputs in command discovery.

| Mode | Context behavior |
| --- | --- |
| `NEW` | Run a delegated executor with a fresh context |
| `INHERITED` | Capture the parent's historical log at a fixed boundary |
| `SUMMARISED` | Supply the contract's bounded summary of inherited context |
| `DIRECT` | Execute instructions in the current direct context; cannot select another agent |

Copied historical calls are data, not executable requests. Skill invocation IDs
are durable receipts; changing input behind the same identity is refused. Package
resource reads stay within the selected package and the filesystem authority that
provided it. Read the skill guide before choosing a mode for a long-running task.

## Agent rules

Scoped instruction files provide account/project/workspace guidance through the
server's existing definition and file-resolution paths. They supplement the
definition prompt and must follow the selected authority tier. They do not expand
tools or become a server-side permission override. A repository's `AGENTS.md`
for development and an agent's runtime instruction file serve different roles.

## Limits and useful comments in custom definitions

Configure bounded turns/model calls and appropriate model bindings. Explain what
successful output contains, when to stop, and how to represent missing evidence.
Keep the declared tools consistent with the procedure. A model that cannot meet
an instruction should return a visible gap rather than invent a successful action.

When adding an assistant, test it with its real effective definition and account
scope. Include refused operations and unavailable providers as well as a happy
path. Deterministic fixture tests validate the contracts; they do not prove the
quality or cost of your selected live model.
