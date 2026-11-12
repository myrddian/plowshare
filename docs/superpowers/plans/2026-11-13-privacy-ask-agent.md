# Ask Tab and Network-Security Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Network Privacy Watch dashboard an Ask tab, where the
application's user talks to a new network-security agent. The agent answers
directly, hands bigger work off asynchronously, and reports back. It runs on
Plowshare under the collector's own service account.

**Architecture:**
- **Application files:** a new exported agent, `privacy_assistant`, with its
  grants.
- **Collector:**
  - a new module, `ask.py`, owns the Ask conversation: a private journal, an
    SDK port and a polling service;
  - new collector-hosted tools let the agent act in the network-security
    domain;
  - `web.py` routes and an overview block.
- **Dashboard:** `app.js` renders the Ask tab, its side column, alerts and
  Settings fields.
- No Plowshare core or SDK change.

**Tech Stack:** Python 3.12, asyncio, aiohttp, Plowshare Python SDK (typed
`*Request` classes through `client.request`), unittest; vanilla JS with
node:test; the Application's Markdown agents and `plowshare.json`, with the
Gradle Java tests.

**Spec:** `docs/superpowers/specs/2026-11-13-privacy-ask-agent-design.md`

All paths are relative to `integrations/network-privacy/` unless rooted.

Commands:
- **Python tests,** from `tests/`:
  `PYTHONPATH=../src ../../../build/privacy-dev/bin/python -m unittest <module>`
  (full suite: `discover -s .`).
- **Web tests:** `node --test web_*.test.cjs`.
- **Type and lint checks,** from this directory: `../../build/privacy-dev/bin/python -m mypy src tests`,
  `../../build/privacy-dev/bin/ruff format src tests`, `../../build/privacy-dev/bin/ruff check src tests`.
- **Gradle,** from the worktree root:
  `./gradlew -q :plowshare-network-privacy:test formatCheck -x :plowshare-console:pnpmBuild`.

## Global Constraints

- **Integration boundary:**
  - Use public SDK operations only. Today's set:
    - `conversation.open` (`ConversationOpenRequest`: `agent`, `max_model_calls`, `max_turns`, `project`);
    - `conversation.latest` (`agent`, `project`);
    - `conversation.chat` (read, `conversation`, `limit`, `offset`);
    - `conversation.resume` (`conversation`, `agent`, `max_model_calls`, `max_turns`);
    - `agent.run` (`agent`, `task`, `conversation`);
    - `job.status` (`job`).
  - No core change. A missing capability is reported as a gap.
- **No `agent_run` delegation** in the new agent. It uses `send_message` and the
  `investigate_network` orchestration only.
- **One persistent Ask conversation per application user.** Today that is one
  per collector. Journal state is keyed by a user key, the constant `"operator"`
  today, so a later multi-user application can add keys.
- **Authority:**
  - The agent gets no settings or admin tool and no `information_write`.
  - Every write goes through collector-hosted tools that validate the target is
    a network-security record.
  - The agent can never raise its own caps.
  - Approvals (`approval.*`) are out of scope: the Application's commands are off.
- **Caps and top-up:**
  - The conversation opens with `max_model_calls` and `max_turns` from Ask
    settings. Defaults are 40 model calls and 30 turns; each must be a whole
    number from 1 to 200.
  - "Top up" adds the configured top-up size (default 20 model calls, 1–200) by
    `conversation.resume` with the new absolute totals. It runs only on a user
    request.
- **Delivery:**
  - The collector never resends a question whose `agent.run` outcome is
    uncertain. The question is shown as "not confirmed" until the conversation
    shows it, or the user asks again.
  - Sending is refused while a turn for the conversation is running.
- **Untrusted text:** answers, specialists' replies and device strings are
  rendered with `PrivacyMarkdown.render` and `textContent`, never as HTML.
  Raw JSON is never shown as an answer; it goes in a "Details" fold.
- **Copy:** British spelling; counts through `plural()`/`formatCount()`;
  "collection", never "sweep"; `docs/console-design.md` "Round 3: one pattern"
  (primary, secondary, quiet and danger buttons; cinnabar only for errors and
  discard).
- **Persistence:** `ask.json` in the state directory, written with
  `private_files.replace_private` (0600, no symlinks), bounded (at most 200
  outstanding items per user) and validated on decode, like
  `device-assessments.json`.
- **Tests:** mocked or in-memory only, no `-PfullDb`.

## Review Focus

1. **The collector restarts while a turn is running.** On restart, the Ask
   tab must show the turn's real outcome (read back through `job.status` and
   `conversation.chat`), never a second identical question. Test:
   `test_a_restart_reads_the_running_turn_back_without_resending` (Task 2).
2. **The journal is lost but the conversation exists in Plowshare.** Expect
   it to be recovered with `conversation.latest`, not a second conversation
   opened. Test: `test_a_lost_journal_recovers_the_existing_conversation`
   (Task 2).
3. **The agent hits its cap mid-answer.** Expect the paused state, the Top up
   action and the attention item, and the half-finished work kept. Test:
   `test_a_capped_turn_pauses_and_top_up_raises_the_totals_once` (Task 2).
4. **A reply contains HTML, script or JSON.** Expect it to render as text or
   Markdown and never execute; JSON goes in a Details fold. Test:
   `the Ask tab never renders reply HTML and folds JSON` (Task 5).
5. **The agent calls a write tool for a non-monitored device, or with
   oversized or control-character text.** Expect a refusal with a reason and
   nothing written. Test:
   `test_assistant_tools_refuse_unknown_devices_and_bad_text` (Task 3).

---

### Task 1: The `privacy_assistant` agent and its grants (Application files)

**Files:**
- Create: `network-privacy-watch/agents/privacy_assistant.md`
- Modify: `network-privacy-watch/plowshare.json` (new tool scope plus grants)
- Test: `src/test/java/io/aeyer/plowshare/integrations/privacy/NetworkPrivacyWatchTest.java`

**Interfaces:**
- Produces:
  - the agent name `privacy_assistant`;
  - the tool scope `network_assistant`, which lists the existing read tools
    (`network_scope`, `network_scan_list`, `network_evidence`,
    `network_destinations`, `network_devices`, `network_device`) and the
    four Task 3 tools (`network_request_deep_scan`, `network_request_assessment`,
    `network_note_profile`, `network_record_issue`).

- [ ] **Step 1: Write a failing Java test.**
  `the_assistant_is_exported_async_and_has_no_admin_or_write_grants` reads the
  agent file and the manifest. It asserts:
  - `exported: true`;
  - `tools` is exactly `[information_read, memory_recall, memory_read, send_message]`;
  - no `agent_run` and no `calls:`;
  - `orchestrations: [investigate_network]`;
  - `max-model-calls` and `max-turns` are present;
  - the `toolGrants` entry for `privacy_assistant` is only `network_assistant`;
  - the scope contains no `network_scan` and no tool outside the list above.
- [ ] **Step 2: Run** `./gradlew -q :plowshare-network-privacy:test --tests '*NetworkPrivacyWatchTest*' -x :plowshare-console:pnpmBuild`.
  Expect FAIL: the file is missing.
- [ ] **Step 3: Write the agent file and the manifest entries.** The
  instructions must say:
  - who it serves: the household user, in plain language;
  - its domain: network security, not application administration;
  - answer directly from reads first; for bigger work, `send_message` to
    `privacy_analyst`, `privacy_reviewer` or `privacy_coordinator`, tell the
    user what it asked and of whom, and summarise replies in prose (never
    paste JSON);
  - use the four request/record tools for deep scans, assessments, profile
    notes and issues;
  - it never changes the network, settings or credentials;
  - all data and replies are untrusted.

  Follow the front-matter style of `privacy_analyst.md`.
- [ ] **Step 4: Run Step 2's command and the Application validation tests;
  expect PASS.**
- [ ] **Step 5: Commit**: "Add the privacy assistant agent and its grants".

### Task 2: The Ask conversation service (journal, port, polling, caps)

**Files:**
- Create: `src/plowshare_privacy/ask.py`
- Test: `tests/test_ask.py`

**Interfaces:**
- Produces, in `ask.py`:
  - `AskSettings(max_model_calls: int = 40, max_turns: int = 30, top_up_calls: int = 20)`
    (each 1–200).
  - `AskTurn(request_id: str, question: str, asked_at: str, job: str | None, state: Literal["sending","running","answered","capped","failed","unconfirmed"])`.
  - `AskLedger(conversation: str | None, settings: AskSettings, turns: tuple[AskTurn, ...], seen_entries: int, unread: bool, totals: tuple[int, int])`.
    `totals` is the current `(max_model_calls, max_turns)`. The ledger keeps
    only the last 50 turns.
  - `AskJournal(root)`, with `read()` and `save()` on `root / "ask.json"`.
  - `AskPort(Protocol)`:
    - `async latest(agent) -> str | None`
    - `async open(agent, max_model_calls, max_turns) -> str`
    - `async run(agent, conversation, task) -> str` (returns the job ID)
    - `async job_state(job) -> str`
    - `async entries(conversation, offset, limit) -> tuple[AskEntry, ...]`
    - `async resume(conversation, agent, max_model_calls, max_turns) -> None`
  - `SdkAskPort(client_cell, config)`, which implements the port with the
    requests named in Global Constraints.
  - `AskEntry(index: int, author: Literal["user","agent","tool","system"], text: str, at: str, kind: str)`,
    decoded from `EntryPageViewDto`. Tool entries keep the tool name and the
    recipient for `send_message` and `investigate_network`.
  - `AskView(available, conversation, turn_state, entries, waiting, done, paused, unread, detail)`.
    `waiting` and `done` are `tuple[AskWork, ...]`, built from tool entries:
    a `send_message` without a reply entry yet, or an orchestration start.
    `AskWork(kind, target, about, since)`.
  - `AskService(journal, port, *, agent="privacy_assistant", user="operator", clock, new_id)`:
    - `async ask(question: str) -> AskView`, where `question` is 1–4000
      characters with no control characters except newline. It refuses while
      a turn is `sending` or `running`.
    - `async top_up() -> AskView`
    - `set_settings(AskSettings) -> None`
    - `view() -> AskView`
    - `mark_read() -> None`
    - `async poll() -> None`
    - `async run(stop) -> None`, a loop every 5 s that keeps `error` until a
      poll that did work succeeds, like `deep_scan.DeepScanner.run`.
- Behaviour:
  - **The conversation:** opened lazily on the first `ask`, recovered with
    `latest` before opening.
  - **Asking:** the request is journalled as `sending` before `run`.
    - Success sets `running` with the job.
    - An uncertain transport error sets `unconfirmed` and never resends.
  - **Polling:**
    - It reads `job_state`; a capped or budget-exhausted ending gives
      `capped` and `paused`.
    - It pages `entries` from `seen_entries`; new agent entries while the user
      is not reading set `unread`.
  - **Top up:** `totals += (top_up_calls, 0)`, then `resume`, then `paused`
    clears. It runs only from an explicit call.

- [ ] **Step 1: Write failing tests** with an in-memory fake `AskPort`:
  - `test_the_first_question_opens_one_conversation_with_the_configured_caps`
  - `test_a_lost_journal_recovers_the_existing_conversation` (`latest` returns
    an ID, so `open` is never called)
  - `test_sending_is_refused_while_a_turn_runs`
  - `test_an_uncertain_send_is_unconfirmed_and_never_resent`
  - `test_a_restart_reads_the_running_turn_back_without_resending`
  - `test_new_agent_entries_set_unread_and_mark_read_clears_it`
  - `test_waiting_and_done_come_from_send_message_and_orchestration_entries`
  - `test_a_capped_turn_pauses_and_top_up_raises_the_totals_once` (one
    `resume` call with `(60, 30)` from the defaults)
  - `test_bad_questions_and_settings_are_refused` (empty, more than 4000
    characters, a NUL character, settings of 0 or 201)
  - `test_the_journal_is_private_bounded_and_strict`
- [ ] **Step 2: Run them; expect `ModuleNotFoundError: plowshare_privacy.ask`.**
- [ ] **Step 3: Implement `ask.py`** as specified.
  - Map `EntryPageViewDto` fields by reading their definitions in
    `sdk/python/src/plowshare/contracts.py`.
  - If a needed field (the tool name, the recipient, a capped job state) is not
    exposed, stop and report NEEDS_CONTEXT naming the gap. Do not guess.
- [ ] **Step 4: Run the tests, mypy and ruff; expect OK.**
- [ ] **Step 5: Commit**: "Add the Ask conversation service".

### Task 3: Collector-hosted tools for the assistant

**Files:**
- Modify: `src/plowshare_privacy/tools.py` (definitions and call types),
  `src/plowshare_privacy/native_tools.py` (parameters, handler routing)
- Test: `tests/test_assistant_tools.py`

**Interfaces:**
- Consumes `deep_scan.DeepScanner.request(device_id)` and
  `device_assessments.DeviceAssessments.assess(device_id)`. It also uses the
  profile and issue services the dashboard uses: the `ProfileManagement.save`
  and `IssueManagement.save` paths in `web.py`. Reuse them; do not add a
  second write path.
- Produces four tool definitions:
  - `network.request_deep_scan`, taking `device_id`. Effect `request`. It
    queues an operator-trigger deep scan and returns the view's state.
  - `network.request_assessment`, taking `device_id`. Effect `request`. It
    calls `assess` and returns the assessment state, or the refusal text
    (limits apply as usual).
  - `network.note_profile`, taking `device_id` and `note`, with `note` at most
    1024 characters. Effect `write`. It appends a dated, attributed line ("Noted
    by Privacy Watch, <day>: …") to the confirmed-MAC profile's `notes`, or
    creates a minimal profile named after the device if there is none.
  - `network.record_issue`, taking `title`, `description`, `device_id`
    (optional), `mitigation` (optional) and `progress` (optional), with each
    field within `IssueFields` limits. Effect `write`. It creates or updates a
    privacy issue and links it to the device's profile, following the
    dashboard's link rules.
- No profiling tool. Profile research is a background queue that cannot be
  requested for one device (the dashboard says so), so the agent explains that
  instead. This is the one part of the spec's "assessments and profiling"
  that is not offered.
- Validation: `device_id` must be a monitored device with a confirmed MAC
  (`tools.monitored_devices`). Text must be within its bounds, with no control
  characters except newline. A refusal returns a reason the agent can tell the
  user.

- [ ] **Step 1: Write failing tests:**
  - `test_request_tools_queue_work_through_the_existing_services`
  - `test_note_profile_appends_an_attributed_dated_line`
  - `test_record_issue_creates_and_links`
  - `test_assistant_tools_refuse_unknown_devices_and_bad_text`
  - `test_no_assistant_tool_reaches_settings_or_credentials`, which asserts the
    tool set contains no monitor, Pi-hole, UniFi, schedule, time-zone or limit
    operation.
- [ ] **Step 2: Run them; expect failures.**
- [ ] **Step 3: Implement the definitions, call types, parameters and
  handlers.** Extend `ToolDefinition.effect`/`parameter` minimally. Update
  `network-privacy-watch/server/tools.json` only if the provider needs it. The
  provider prefix is already `network_`.
- [ ] **Step 4: Run the tests, the full suite, mypy and ruff; expect OK.**
  Run the Gradle tests if the tool catalogue test covers declarations.
- [ ] **Step 5: Commit**: "Let the assistant request and record through
  validated tools".

### Task 4: Composition, routes and the overview

**Files:**
- Modify: `src/plowshare_privacy/web.py`, `src/plowshare_privacy/cli.py`,
  `src/plowshare_privacy/console_port.py` (tool-visibility agent)
- Test: `tests/test_ask_web.py`

**Interfaces:**
- Consumes `AskService` (Task 2).
- Produces:
  - `GET /api/ask` returns `asdict(AskView)` and marks it read.
  - `POST /api/ask` with `{"question": str}` returns the view. It answers 409
    with the refusal text while a turn runs, and 400 with the reason for a bad
    question.
  - `POST /api/ask/top-up` with `{}` returns the view.
  - `GET` and `POST /api/ask/settings` with
    `{"max_model_calls","max_turns","top_up_calls"}` return the settings, or
    400 with the reason.
  - With no service, every route returns 404 "Ask is not enabled".
- `/api/overview` adds
  `"ask": {"unread": bool, "paused": bool, "turn_state": str | None, "detail": str | None}`,
  or `None`. `/api/status` adds `ask_available`.
- `application(..., ask: AskService | None = None)`.
- `cli.py` composes `AskService` when the operator store is on, with
  `SdkAskPort(lambda: client, config)`, and runs `ask.run(stop)` beside the
  other background tasks, cancelled the same way.
- The console port's tool-visibility check snapshots the `privacy_assistant`
  conversation recorded by `AskService` instead of the first project
  conversation. Before one exists it says "Awaiting the first Ask
  conversation".

- [ ] **Step 1: Write failing tests** using the `TestClient` pattern of
  `tests/test_monitor.py`:
  - `test_ask_routes_send_read_top_up_and_settings`
  - `test_ask_refuses_while_running_and_explains_bad_input`
  - `test_overview_carries_unread_and_paused`
  - `test_routes_are_absent_without_ask`
  - `test_tool_visibility_measures_the_assistant_conversation`
- [ ] **Step 2: Run them; expect failures.**
- [ ] **Step 3: Implement** the routes, overview, status, composition and
  console-port change.
- [ ] **Step 4: Run the full suite, mypy and ruff; expect OK.**
- [ ] **Step 5: Commit**: "Expose the Ask conversation to the dashboard".

### Task 5: The Ask tab, alerts and Settings

**Files:**
- Modify: `src/plowshare_privacy/web/app.js`, `src/plowshare_privacy/web/index.html`,
  `src/plowshare_privacy/web/style.css` (only if a new class is needed),
  `tests/preview.py` (`PreviewAsk` fake with a scripted conversation)
- Test: `tests/web_ask.test.cjs`

**Interfaces:**
- Consumes the Task 4 routes and `overview.ask`.
- **The Ask tab** sits in the main navigation between Evidence and Settings,
  labelled "Ask", with a "•" badge when `overview.ask.unread`.
  - The main area renders the conversation as a reading pane. Each agent
    entry is rendered with `PrivacyMarkdown.render`; JSON-looking entries go
    in a "Details" fold; tool entries never show as answers.
  - The input sits at the bottom. It is disabled with "Privacy Watch is still
    working on your last question" while `turn_state` is `sending` or
    `running`.
  - An `unconfirmed` turn shows "Not confirmed as sent. Ask again if you
    still need it."
  - The side column lists "Waiting on" (target, about, `formatWhen(since)`)
    and "Done". A done `investigate_network` item links to its report through the
    existing report reader.
  - When paused, the column shows "Paused: allowance used" and a Top up
    primary button.
- **Attention band items:**
  - "Privacy Watch has an answer" when `unread`.
  - "Privacy Watch is paused" when `paused`.
  - Each opens the Ask tab.
- **Settings:** a "Privacy Watch conversation" group in the Background
  assessments panel with model calls, turns and top-up size (1–200 each), and
  the hint "These limit how much one conversation can spend. Top up adds the
  top-up size when you choose."
- No new timer: the existing refresh reads `/api/ask` only while the Ask tab
  is open, or while `turn_state` is active. The tab is rebuilt only when its
  view changes.

- [ ] **Step 1: Write failing tests:**
  - `the Ask tab never renders reply HTML and folds JSON`
  - `sending is disabled while a turn runs and unconfirmed is explained`
  - `the side column lists waiting and done work`
  - `paused shows Top up and the attention item`
  - `an unread answer badges the tab and adds the attention item`
  - `disconnect clears Ask state`
- [ ] **Step 2: Run them; expect failures.**
- [ ] **Step 3: Implement the UI and the preview fake.**
- [ ] **Step 4: Run the web tests and the Python suite; expect OK.** Check the
  preview at desktop width and at 375 px: no horizontal scroll, the tab
  works, a scripted reply appears, and Top up clears the paused state.
- [ ] **Step 5: Commit**: "Add the Ask tab".

### Task 6: Documentation and final verification

**Files:**
- Modify: `README.md` (an "Ask Privacy Watch" section),
  `docs/console-design.md` (an Ask tab catalogue row and a decision line)

- [ ] **Step 1: Write the README section.** It covers:
  - what the agent can and cannot do (network security, not administration;
    it never changes the network);
  - that work is asynchronous;
  - the caps and Top up;
  - "not confirmed" sends;
  - that it runs under the collector's service account, with no Plowshare
    sign-in for users.
- [ ] **Step 2: Add the console-design row and decision.**
- [ ] **Step 3: Run every check:**
  - Python and web suites;
  - mypy, ruff format (expect "left unchanged") and ruff check;
  - `./gradlew -q :plowshare-network-privacy:test formatCheck -x :plowshare-console:pnpmBuild`
    (expect exit 0).
- [ ] **Step 4: Commit**: "Document the Ask tab". Do not push.
