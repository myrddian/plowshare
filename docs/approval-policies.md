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

## Desktop

Connect, then choose **Approvals** in the toolbar. Select a mode and, for Auto, a
personal bot. The editor loads saved rules and their revision. Rules use JSON;
all fields in the example are intentional, including explicit null values.

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
Reject or Ask rule can cover its entire operation kind. An automatic allow rule
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

## TUI

```text
/approval-mode manual
/approval-mode smart
/approval-mode auto PERSONAL_BOT
/approval policy get
```

Mode commands read the current policy, preserve rules, and save against that
revision. For a rule edit, inspect the get result and submit the complete policy:

```text
/approval policy save {"policy":{"revision":CURRENT_REVISION,"mode":"SMART","bot":null,"rules":[]}}
```

Replace `CURRENT_REVISION` with the returned integer; the example is a template.
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

Migration V271 adds typed file questions, retained tool names and human history.
Deploy it through the normal server migration/backup process and update clients
before relying on file approval controls. Repository checks and fixture tests do
not update an installed Desktop application or validate a live model deployment.
