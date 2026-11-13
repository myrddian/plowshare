# Network Privacy Watch: the Ask tab and its network-security agent

Status: agreed with the project owner on 13 Nov 2026 (conversation); awaiting
the owner's approval of this document before planning.

## Why

Network Privacy Watch has capable agents (the coordinator, analyst, reviewer,
device assessor and device profiler) and workflows (investigation, device
assessment, device profiling, deep scans), but its users cannot talk to them.
System health even reports "Dynamic tool visibility: Awaiting first project
conversation" because no conversation exists.

The owner wants a conversation front door for the application's user, built
on the principle that **Plowshare is the engine and Network Privacy Watch is
the application**. Plowshare supplies the agents, delegation, workflows,
budgets and approvals underneath. The product is not a chatbot wrapper.

## What it is

- **A new agent:** a network-security agent the user asks about anything in
  that domain:
  - analysis of a device or traffic;
  - updates on what changed;
  - the documents (assessment reports, profiles, privacy issues);
  - outstanding questions;
  - mitigation suggestions.
- **It answers directly** from what it can read.
- **Bigger work is handed off asynchronously,** so the conversation stays
  interactive:
  - asking a specialist with `send_message`;
  - starting a workflow (an orchestration, a deep scan).

  Results return to the same conversation later.

## Decided by the owner

- **A separate agent** from `privacy_coordinator`. The coordinator's
  instructions are written for background operation (receipts, scans,
  investigations) and stay as they are.
- **Asynchronous by design.** Do not use `agent_run` delegation: it blocks the
  caller until the specialist finishes (`docs/manual/13-messaging-and-routing.md:18`,
  `:288`). Use `send_message` (durable receipt, replies later) and
  orchestration starts (their own durable run).
- **One persistent conversation per application user** for now.
- **Replies land in that conversation, made human-friendly.** A specialist may
  answer in JSON, so the agent turns delegated results into a readable reply
  before the user sees them.
- **Placement: an "Ask" tab** in the dashboard's main navigation (option B in
  the mockups), not a floating chat bubble.
  - Most of the screen renders answers as formatted documents (headings,
    lists, the exposure wording), not cramped chat bubbles and never raw JSON.
  - A side column shows what the agent is waiting on (who was asked, about
    what, how long ago), and what has come back.
- **A mild alert when an answer arrives:**
  - a "Privacy Watch has an answer" item under "Needs your attention";
  - a small badge on the Ask tab;
  - no pop-ups and no sound.
  - Clicking either opens the Ask tab.
- **Scope of authority: network security, not administration.**
  - The agent may act freely in the network-security domain: answer, read,
    ask specialists, start workflows, request scans and deep scans, run
    assessments and research, and update network-security documents
    (profiles, privacy issues, mitigation notes).
  - It may not touch administration of the application: monitoring scope,
    Pi-hole and UniFi connections and credentials, the schedule, the time
    zone, background limits, recovery, or deployment.
  - It never changes the network itself (no firewall or Pi-hole blocking);
    mitigation means suggesting and tracking.
- **Limits and top-ups reach the user through the application.** When the
  agent's allowance is exhausted:
  - it says so in plain words in the conversation;
  - the side column marks the item paused, with a Top up action;
  - a "Privacy Watch is paused" attention item appears.
  - Top-up uses Plowshare's existing paths: project `autoIncrease`
    (`agents/ProjectCaps.java:23`, `docs/projects.md:640-654`) renews
    automatically where allowed and is only noted in the conversation;
    otherwise the user's click is the approval.
  - Nothing is retried behind the user's back. Paused work resumes once,
    with no duplicate starts.
- **Application users, not Plowshare users.**
  - The conversation, delegation and approvals run under the collector's own
    Plowshare service account through the public SDK, exactly as assessments
    and scans do today.
  - The application authenticates its users (today the dashboard web key);
    a user's click is the application's decision, which the collector
    carries out through Plowshare's public operations (for example
    `approval.list` and `approval.answer`; `approval.requested` and
    `approval.resolved` are Relay lifecycle events in the owning project,
    `docs/relay.md:41`, `:53-75`).
  - "Sign in with Plowshare", personal accounts and the integrations they
    open are deliberately out of scope for now. The application must work
    for users with no Plowshare account.

## Goals

- The user can ask in plain language and get a readable answer.
- Long work never freezes the conversation, and its progress is visible.
- The agent's authority is limited by what it is granted in Plowshare (tool,
  agent and orchestration grants), not only by its instructions.
- Answers that arrive while the user is elsewhere are noticed without
  nagging.
- System health's "Dynamic tool visibility" check gains a real conversation
  to measure. (The builder may point the check at the new agent's
  conversation instead of the coordinator's if that is the more useful
  measure; see "Left to the builder".)

## Non-goals

- Several conversations per user, conversation history management, or
  sharing conversations between users.
- Selectable characters or personalities for the agent. The owner likes the
  idea (original characters, not existing ones) and it is parked for a later
  change.
- A floating chat bubble. It is a possible later shortcut into the Ask tab,
  not the main interface.
- Plowshare sign-in for users, or any per-user Plowshare identity.
- Voice, attachments, or file uploads in the conversation.

## Anti-goals (a failure even if it works)

- **The agent changes application settings or credentials,** or grants
  itself more allowance. Its grants must make this impossible, not merely
  discouraged.
- **The conversation blocks** while a specialist works.
- **The user sees raw JSON** or internal identifiers in place of an answer.
  Identifiers may appear in an evidence or details fold, as reports do today.
- **A top-up or approval happens without a user's click,** where policy
  requires one. Equally, a retry starts duplicate work.
- **The collector bypasses the public SDK,** or Plowshare core is changed to
  make this work (`AGENTS.md`, `docs/decisions/0001-integration-boundary.md`).
  A missing public capability is reported as a gap, not patched in core.
- **The alert becomes noisy:** repeated, popping up, or demanding acknowledgement.

## Constraints

- **Integration boundary:** collector code and Application files only. The
  operations used are public SDK operations, for example
  `conversation.open`, `conversation.chat`, `conversation.follow`,
  `conversation.latest`, `orchestration.start`, `approval.list` and
  `approval.answer`.
- **The new agent's grants** follow the Application's existing patterns
  (`network-privacy-watch/agents/*.md` front matter, `plowshare.json`
  agent grants and tool scopes):
  - read tools for the collector's evidence and documents;
  - `send_message` to the specialists;
  - the network-security orchestrations;
  - none of the collector's settings or admin operations, which are not
    exposed as agent tools today and must stay that way.
- **One application user today.** The dashboard has a single web key, so
  "one conversation per user" is one conversation per collector for now. The
  design must not hard-wire that assumption into stored state. A later
  multi-user application keys conversations by its own user identity.
- **Untrusted content:** delegated replies, device-reported strings and
  report text are untrusted. They are rendered with the dashboard's existing
  Markdown renderer and `textContent` rules, never as HTML.
- **Dashboard conventions:** `docs/console-design.md` "Round 3: one pattern",
  the copy rules (British spelling, `plural()`/`formatCount()`, "collection",
  never "sweep"), the 5-second refresh, and the rule that refreshes rebuild
  only what changed.
- **Tests:** mocked and in-memory, no real database. Python unittest, the
  web tests, mypy and ruff, plus the Gradle checks if Application files
  change.

## Settled after review (checked against the code; calls marked)

- **Asking.**
  - The collector puts each user question into the conversation with
    `agent.run`, passing `conversation=<id>`; `AgentRunRequest` carries
    `agent`, `task` and `conversation` (`sdk/python/src/plowshare/contracts.py:6666-6677`).
    `conversation.chat` only reads.
  - The new agent is `exported: true` so a service-token `agent.run` may
    address it.
  - *Call:* the Ask tab disables sending while a turn for that conversation is
    running, so questions never race.
- **Replies come back to the same conversation.**
  - Messaging binds the sender to the sending conversation and definition
    (`board/BoardMessaging.java:231-266`), so a reply to the agent's
    `send_message` wakes that conversation.
  - The journal stores the conversation id.
- **Who it asks.**
  - *Call:* the agent messages the existing specialists (analyst, reviewer,
    and the coordinator for workflow-shaped requests) with `send_message`.
    Their replies come back through the harness's final-reply path, even
    though they have no `send_message` tool of their own.
  - The agent turns those replies, JSON or not, into prose on its woken turn.
    No new specialist agents.
- **Workflow results.**
  - *Call:* orchestration starts do not wake the conversation. The collector
    tracks outstanding runs by their receipts (`orchestration.status`), as it
    already does for assessments.
  - When a run finishes, the Ask tab shows the result directly. Assessment
    reports and profiles are already readable documents.
  - No extra model turn is spent rewriting a finished report, and no result
    text is fed back to the agent as if the user had said it, which would be
    an injection path.
- **Allowance and top-up.**
  - *Call:* Plowshare approvals (`approval.*`) are command approvals, and this
    Application's commands are off, so approvals are **out of scope**.
  - The limit is the Ask conversation's own model-call and turn caps, set
    when the collector opens it (`conversation.open` `max_model_calls`,
    `max_turns`). This keeps it separate from the background workflows'
    project budget, so neither starves the other.
  - When the caps are reached, the agent stops and the paused state appears.
    "Top up" makes the collector raise that conversation's caps
    (`conversation.resume` with new totals) after the user's click.
  - The cap and the top-up size are collector settings the user controls, in
    the same Settings panel as the background limits.
  - The project's `autoIncrease` stays as the owner set it (off today). If the
    owner turns it on, it applies as Plowshare defines it.
  - The agent itself can never raise its caps.
- **What the agent can change.**
  - *Call:* no broad `information_write`. Writes go through collector-hosted
    tools in the Application's tool provider (`server/tools.json`, granted in
    `plowshare.json`). Each tool validates that it touches a network-security
    record:
    - request a deep scan of a device (wrapping `DeepScanner.request`);
    - update a device profile's notes and expectations;
    - record a privacy issue or a mitigation step.
  - Reads use the existing `network_*` tools plus `information_read`.
  - Orchestration grants: `investigate_network` only. Assessments and
    profiling stay collector-started, so their limits and journals keep
    working. The agent asks for them through a tool that queues them in the
    collector, never by starting them directly.
- **Delivery and restarts.**
  - *Call:* the collector keeps the conversation id and outstanding work in a
    private journal. After a lost journal it recovers with
    `conversation.latest(agent=…)` instead of opening a second conversation.
  - A send whose outcome is unknown is shown as "not confirmed" and is never
    resent automatically. The user can ask again explicitly. This follows
    AGENTS.md: never replay a mutation after uncertain delivery.
- **Dynamic tool visibility.**
  - *Call:* the check measures the new agent's conversation. The current
    check takes the first project conversation, which would otherwise become
    ambiguous.

## Left to the builder

- The new agent's name (for example `privacy_assistant`), its instruction
  text, and its exact tool and orchestration grants within the scope above.
- How the collector tracks outstanding work for the side column:
  `send_message` receipts and replies, orchestration receipts and statuses.
- How a delegated reply is turned into a human-friendly answer: the agent's
  next turn, or the dashboard rendering structured content.
- Storage of the conversation reference and outstanding-work state in a
  private collector journal, bounded and validated like the other journals.
- How approvals and allowance exhaustion are detected (Relay lifecycle
  events through a port, or polling `approval.list` and the conversation's
  status), within the no-duplicate and no-self-grant rules.
- Whether "Dynamic tool visibility" measures the new agent, the coordinator,
  or both.
- Exact copy for the tab, alerts and paused states.
