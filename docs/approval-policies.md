# Personal approval policies

Personal approval settings control intent review for ordinary agent `run` calls
and direct `file_read`, `file_edit`, `file_delete` and `file_move` calls. The server
owns one revisioned policy per account; Desktop and TUI edit that same policy.

SECURITY compares the requested effects with the authenticated user's task and
personal rules. It can clear the action, reject it back to the agent without a
human prompt, or ask the human. For example, changing Git identity during a task
that only asks to inspect a repository can be rejected. The agent receives the
objection as a tool result and can continue other permitted work.

| Mode | After SECURITY clears |
| --- | --- |
| Manual | Preserve existing consent requirements. No new automatic permission. |
| Smart | Existing consent, an exact personal allow rule, or matching owner human history establishes eligibility. Otherwise ask the human. |
| Auto | Require successful Smart checks, then approval from the explicitly selected personal bot. Bot refusal, failure or uncertainty asks the human. |

A SECURITY rejection or request for human input never goes to the bot for an
override. A human can approve an exact supported request once. A new personal
Reject rule or revoked execution authority still prevents execution after a wait.

The shipped deployment policy includes a `PERSONAL_READ_INTENT` rule that gives
already-permitted reads within your verified Personal workspace standing intent.
Routine reads such as `Resources/personal/preferences.md` can clear review when a scheduled or
delegated run has no authenticated task text. The rule covers the live mount and
the server mirror. SECURITY still evaluates task restrictions, complete input
evidence and applicable rules. Auto still requires the selected bot's approval.
An exact account `REJECT` rule refuses the operation before model review. A
`SECURITY` rejection or request for human input cannot be overridden by Smart
eligibility or the selected bot. An exact account `ASK_HUMAN` rule requires a
human decision after SECURITY clears. Standing Personal read intent supplies
intent only, while account `MAY_APPROVE` rules and history supply eligibility.

## Deployment policy files

Set `security.md` at the root of `plowshare.data.dir` to replace the shipped rule
set. Plowshare loads it at startup. Its YAML frontmatter contains the version,
inline rules and an explicit list of optional JSON or Markdown files under `security/`.
The Markdown body supplies deployment guidance to SECURITY. The shipped
guidance requires an explicit authenticated request for edits to security files,
including when the requested change would come from a command.

```markdown
---
version: 1
rules: []
includes: [review-rules.json, security-edits.md]
---

# Deployment security policy
```

JSON fragments hold arrays of Personal read intent rules. Markdown fragments
add reviewer guidance. Only declared fragments load.
They must be regular files in the `security/` directory. Missing or invalid
declared files refuse startup so an incomplete policy cannot silently take effect.
An absent `security.md` uses the shipped rule set. A valid document with empty
rules and includes supplies no deployment rules.

The server data directory is excluded from workspace file providers. Manage
deployment policy files through the operator's trusted configuration path.
The deployment file supplies standing intent and reviewer guidance. Account
approval rules continue to govern consent.

`PERSONAL_READ_INTENT` accepts only `FILE_READ`. A null selector or empty
`paths` list leaves that selector open within the verified Personal workspace.
To narrow the rule, supply an exact provider, working directory, path or input
digest. An empty path list works across both the live mount and server mirror.
Standing intent requires an unambiguous path spelling. When a remote file
provider cannot attest to canonical file identity, a project/provider-scoped
path or directory restriction also suppresses standing intent for unmatched
names.

## Project, Application and Personal guidance

Put `security.md` at the root of a project, deployed Application, or Personal
workspace. A plain Markdown file is sufficient. To split guidance into smaller
files, add versioned frontmatter and list Markdown filenames under `security/`.

```markdown
---
version: 1
includes: [file-review.md]
---

# Workspace security guidance

Ask before reading archived export files.
```

SECURITY reads the registered workspace's guidance and, for an operation on a
remote provider, the serving checkout's guidance. Personal guidance is available
from the server mirror and the live checkout. Edits take effect on the next
SECURITY consultation. Workspace prose informs only that reviewer. Approval
eligibility, authenticated task evidence, provider access and bot review each
use their existing checks. A workspace or checkout rule can ask SECURITY to
apply stricter review. Deployment restrictions and authenticated intent remain
authoritative when the guidance conflicts.

For edits, deletes and moves that the file router identifies as touching a
configuration root's `security.md` or `security/`, SECURITY must affirm an
explicit request for that change and return a verbatim excerpt from the current
authenticated task. The server checks that excerpt against the task and refuses
the operation when evidence is missing or SECURITY cannot verify it. A human
approval can satisfy a separate consent requirement after SECURITY verifies the
request. It cannot establish a missing reviewer verification.

SECURITY also evaluates changes made through commands and scripts. That review
depends on the reviewer identifying the effects from the operation evidence.
The remote file protocol supplies named paths. Resolving a remote alias that
does not name a policy file also depends on the available review evidence.
Previously parked commands follow the existing retained-approval path. Commands
outside filesystem isolation retain their host permissions.

## Desktop

Connect, then choose **Approvals** in the toolbar. Select a mode and, for Auto, a
personal bot. The editor loads saved rules and their revision. Rules use JSON.
Include every field in the example, including explicit null values.

```json
[
  {
    "id": "inspect-status",
    "effect": "MAY_APPROVE",
    "kind": "COMMAND",
    "project": "<project>",
    "provider": "<actual provider identity>",
    "cwd": "<absolute project directory>",
    "inputDigest": null,
    "argv": ["git", "status"],
    "paths": [],
    "guidance": null
  }
]
```

Use the actual execution provider identity from review evidence. Server and client
providers have distinct identities; client identities include their session.
Arguments match the complete array, including empty arguments. Paths match exact
paths. There is no command-prefix or raw path-prefix rule syntax.

`REJECT` wins over `ASK_HUMAN`, which wins over `MAY_APPROVE`. Null selectors on a
Reject or Ask rule can cover its entire operation kind. A `MAY_APPROVE` rule
requires an exact project, provider, working directory and command or paths.
`inputDigest` binds stdin or complete file-operation arguments; an automatic rule
with a null digest covers no input, rather than arbitrary input. Guidance is
reviewer context and cannot create a permission or override a Reject rule.

Save uses the loaded revision. Conflicting edits require a reload. After a failed
or uncertain save, the draft remains visible and the editor requires explicit
reload to reconcile the saved state. Reconciliation preserves the draft against
the newly loaded revision for explicit review; it never silently retries a mutation.

File prompts show operation kind, provider, paths, input digest, available input
preview and the reason for human attention. Approve permits only that retained
operation once. Changed hook arguments, provider/session, policy or authority can
refuse execution after approval. A client must echo the displayed input digest
when approving a file request, preventing an older command-only client from
approving details it cannot display. Denial remains available without that digest.

Inbox decisions use the connected session retaining the reviewed approval. Its
background or delegated conversation need not appear in Desktop's chat list.
Server refusals remain visible; uncertain answers are retained for inspection and
are never retried automatically on another session.

## TUI

```text
/approval-mode manual
/approval-mode smart
/approval-mode auto PERSONAL_BOT
/approval policy get
```

Mode commands read the current policy, preserve rules, and save against that
revision. For a rule edit, inspect the get result and submit the complete policy.
This example saves Smart mode with no account-specific rules.

```text
/approval policy save {"policy":{"revision":CURRENT_REVISION,"mode":"SMART","bot":null,"rules":[]}}
```

Replace `CURRENT_REVISION` with the returned integer.
Commands are account-scoped and do not inherit the current project's selector.
File questions offer **o** to approve the exact request once and **d** to deny.
Conversation and project-prefix choices are unavailable for file questions.

## Server storage and budgets

Policy lives in private server data, outside agent-readable workspace/Personal
file trees. Authenticated policy-management operations are its only application
writer. Ordinary user preference prose and repository documents cannot modify
approval authority. Missing policy means Manual; corrupt/unavailable policy never
reuses stale automatic permission.

Review calls offer no tools, calls or filesystem grants. They consume the
requesting operation's existing allowance and retain its execution/accounting
owner. The security reviewer is the private `security_reviewer` definition using
`system.judge`. Auto resolves the selected bot in the owner's Personal definition
context and pins its definition until execution.

| Configuration | Default | Purpose |
| --- | --- | --- |
| `plowshare.approvals.policy-io-budget` | `PT2S` | One policy-file read/save budget, including lock wait |
| `plowshare.approvals.review-budget` | `PT60S` | Shared SECURITY plus bot review deadline |
| `plowshare.approvals.review-capacity` | `64` | Physical concurrent review calls; saturation asks the human |
| `plowshare.approvals.review-cleanup-budget` | `PT2S` | Bounded review-worker shutdown drain |
| `plowshare.approvals.history-lifetime` | `P30D` | Exact owner human evidence lifetime; maximum 90 days |

History uses only a matching authenticated USER answer to a retained call owned by
that same account. It matches project, provider/session, operation kind, arguments,
paths, cwd and input hash. Automated decisions, other users and legacy command
judge rows do not create history. A history match supplies eligibility; SECURITY
and Auto's bot still apply.

## Coverage boundaries

Native acceptance sets and stage checks retain their existing approval pipeline.
Discovery, metadata and code-map reads are outside direct file review. This is
not universal filesystem enforcement. File questions bind the requested action;
provider-atomic base-version checks for independent concurrent writers remain
future work. Current providers cannot expand access outside their existing fences.

Review decisions appear as audit diagnostics in Trajectory, with policy revision,
matched rules and SECURITY/bot outcomes. Inference uses existing accounting
journals. Reviewer-stage recovery is not a new durable workflow; uncertain tool
execution retains the existing started/result fence and is never replayed.

Migration V273 adds typed file questions, retained tool names and human history.
Deploy it through the normal server migration/backup process and update clients
before relying on file approval controls. Repository checks and fixture tests do
not update an installed Desktop application or validate a live model deployment.
