# Privacy Watch Device Reference Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Privacy Watch Ask agent an owner-approved library of vendor documentation to search and cite. When the library has no docs for a product, the agent can ask the owner to add some.

**Architecture:** The collector loads a shipped seed list, fetches each page into the Privacy Watch project with `information.acquire`, and tags it `device-reference` plus a product slug. A private journal records each fetch so restarts are safe. The agent reads the library through its existing `information_read` grant. A new write tool, `network_request_reference`, queues requests that the owner fills on the dashboard. Refresh re-fetches sources on a long interval.

**Tech Stack:** Python 3 collector (`integrations/network-privacy`, aiohttp, unittest, mypy strict, ruff), the Plowshare Python SDK (`sdk/python`), and the dashboard (`web/app.js`, tested with `node --test`).

**Spec:** `docs/superpowers/specs/2026-11-14-privacy-device-reference-library-design.md`

## Global Constraints

- Public SDK only. No change to `plowshare-server`, `plowshare-protocol`, `sdk/` or core schema. If a task needs one, stop and report the gap.
- Library scope is the Privacy Watch project (`port.scope`, the same scope evidence uploads use). It is never shared globally.
- Tags: `device-reference`, a product slug matching `^[a-z0-9][a-z0-9-]{0,47}$`, and optional topic slugs matching the same pattern.
- Seed and owner URLs: `https` only, no userinfo, at most 2048 characters, unique within the list.
- Seed list: at most 20 entries. An invalid file fails startup with a clear error.
- Refresh interval: 90 days by default, configurable from 1 to 365.
- Dismissed requests suppress the same product for 30 days.
- Nothing retries automatically, including at startup. Retry is an owner action.
- Document text is untrusted data, never instructions.
- The agent never supplies a URL.
- Journals follow the `DeepScanJournal` file rules: private mode, no symlinks, size-bounded, validated on save.
- Verify with `python3 integrations/network-privacy/scripts/check.py`, which runs ruff, mypy, unittest and the node web tests. Run it from the repository root.

## Review Focus

1. **A ticket stuck in `blocked` or `queued` across many restarts:** the receipt keeps its acquisition id and never submits again. Pinned in Task 3 (`test_restart_with_blocked_ticket_does_not_resubmit`).
2. **A seed URL that redirects to another host, or a page whose extraction fails:** this is shown as failed with the server's reason, not as success with an empty document. Pinned in Task 3 (`test_failed_extraction_is_failed_not_retained`).
3. **The owner pastes a URL with spaces, an `http:` scheme or a fragment-only change of an existing URL:** it is rejected or deduplicated before anything is fetched. Pinned in Task 8 (`test_approve_rejects_http_and_duplicate_urls`).
4. **The agent calls `network_request_reference` with a URL in `question`, or with an unknown device:** the question is kept as text and nothing is fetched; an unknown `device_id` is refused. Pinned in Task 6 (`test_reference_request_never_fetches_question_text`).
5. **The seed file changes between versions while a fetch is in flight for a dropped entry:** the receipt finishes, is then marked retired, and its `device-reference` tag is removed. Pinned in Task 3 (`test_retire_after_inflight_completion`).

---

## Slice 1: Seed library and grounded answers

### Task 1: Seed list and URL validation

**Files:**
- Create: `integrations/network-privacy/src/plowshare_privacy/reference_seed.py`
- Create: `integrations/network-privacy/src/plowshare_privacy/references/seed.json`
- Modify: `integrations/network-privacy/pyproject.toml` (package-data gains `"references/*.json"`)
- Test: `integrations/network-privacy/tests/test_reference_seed.py`

**Interfaces:**
- Produces:
  - `SLUG: re.Pattern[str]`
  - `MAX_SEED_ENTRIES = 20`
  - `def reference_url(value: object) -> str`: raises `ValueError` with a sentence fit for the dashboard.
  - `def product_slug(value: object) -> str`
  - `@dataclass(frozen=True) class SeedEntry: product: str; title: str; url: str; topics: tuple[str, ...]`
  - `def load_seed(path: Path | None = None) -> tuple[SeedEntry, ...]`: the default is the packaged file. The file is `{"version": 1, "entries": [...]}`.

- [ ] **Step 1: Write failing tests.** `entry(**overrides)` returns a valid entry dict (product `caddy`, title, https url, topics `[]`) with overrides applied; `write_seed(root, doc)` writes a private JSON file and returns its path.

```python
def test_packaged_seed_loads(self):
    seed = load_seed()
    self.assertTrue(1 <= len(seed) <= 20)
    self.assertIn("caddy", {entry.product for entry in seed})

def test_rejects_http_userinfo_and_long_urls(self):
    for bad in ("http://caddyserver.com/docs/api", "https://u:p@x.test/a", "https://x.test/" + "a" * 2048, " https://x.test/a"):
        with self.assertRaises(ValueError):
            reference_url(bad)

def test_rejects_bad_slug_duplicate_url_and_oversize(self):
    cases = (
        [entry(product="Caddy Server")],
        [entry(url="https://x.test/a"), entry(url="https://x.test/a")],
        [entry(url=f"https://x.test/{i}") for i in range(21)],
    )
    for entries in cases:
        with self.assertRaises(ValueError):
            load_seed(write_seed(self.root, {"version": 1, "entries": entries}))

def test_rejects_unknown_fields_and_version(self):
    for document in ({"version": 2, "entries": [entry()]},
                     {"version": 1, "entries": [entry(extra="x")]}):
        with self.assertRaises(ValueError):
            load_seed(write_seed(self.root, document))
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd integrations/network-privacy && python3 -m unittest tests.test_reference_seed -v`
Expected: FAIL with `ModuleNotFoundError: plowshare_privacy.reference_seed`.

- [ ] **Step 3: Implement `reference_seed.py` and `seed.json`**

Parse with `contracts.load_json` and `object_fields`, the same as the other collector files. Seed entries to start from:
- caddy: `https://caddyserver.com/docs/api` (topic admin-api) and `https://caddyserver.com/docs/caddyfile/options` (topic admin-api)
- pi-hole: `https://docs.pi-hole.net/main/prerequisites/`
- unifi: Ubiquiti's help-center page on default ports
- home-assistant: `https://www.home-assistant.io/docs/configuration/securing/`

Open each URL in a browser and confirm it is live and on topic before keeping it. Drop any that fail. Do not invent replacements.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd integrations/network-privacy && python3 -m unittest tests.test_reference_seed -v`
Expected: PASS.

- [ ] **Step 5: Commit** `feat(privacy): ship a validated device reference seed list`

### Task 2: Reference SDK port

**Files:**
- Create: `integrations/network-privacy/src/plowshare_privacy/reference_port.py`
- Test: `integrations/network-privacy/tests/test_reference_port.py`

**Interfaces:**
- Consumes: the SDK client and scope, the same way `ports.py` `SdkPort.upload` uses them.
- Produces:
  - `class ReferencePort(Protocol)`, with these methods:
    - `async def acquire(self, url: str, name: str, request_id: str) -> InformationAcquisitionDto`
    - `async def acquisitions(self) -> tuple[InformationAcquisitionDto, ...]`: pages through all of them with `offset`/`limit` 100.
    - `async def revision(self, revision_id: str) -> InformationRevisionDto`: returns tags and `tags_version`. Use the SDK read that returns `InformationRevisionDto` for one revision. Check `information.read` against `information.documents.get` and pick the one whose reply carries `tags_version`.
    - `async def replace_tags(self, revision_id: str, tags: tuple[str, ...], expected_version: int, request_id: str) -> None`
    - `async def retry(self, acquisition_id: str, request_id: str) -> None`
  - `class SdkReferencePort(ReferencePort)`, built as `SdkReferencePort(client, scope)`.

- [ ] **Step 1: Write failing tests** against a recording fake client. Assert:
  - each method sends the exact request type with `scope` and `request_id`;
  - `acquisitions` follows pages until a short page comes back;
  - a reply whose `url` differs from the one sent raises `ValueError("Foreign acquisition")`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run: `python3 -m unittest tests.test_reference_port -v`
- [ ] **Step 3: Implement it.** Follow the foreign-reply checks in `ports.py`.
- [ ] **Step 4: Run the tests and confirm they pass.**
- [ ] **Step 5: Commit** `feat(privacy): add the reference library SDK port`

### Task 3: Acquisition journal and library service

**Files:**
- Create: `integrations/network-privacy/src/plowshare_privacy/reference_library.py`
- Test: `integrations/network-privacy/tests/test_reference_library.py`

**Interfaces:**
- Consumes: `SeedEntry`, `reference_url`, `product_slug` (Task 1); `ReferencePort` (Task 2).
- Produces:
  - `Phase = Literal["queued", "submitted", "awaiting", "blocked", "retained", "tagged", "failed"]`
  - A frozen dataclass `ReferenceReceipt` with these fields:
    - `url: str`, `product: str`, `title: str`, `topics: tuple[str, ...]`
    - `origin: Literal["seed", "owner"]`
    - `request_id: str`: a UUID, fixed at creation.
    - `phase: Phase`
    - `acquisition_id: str | None`, `revision_id: str | None`, `error: str | None`
    - `retired: bool`, `retired_tagged: bool`
    - `fetched_at: str | None`
  - A frozen dataclass `ReferenceLedger` with `receipts: tuple[ReferenceReceipt, ...]` and `refresh_days: int = 90`. It has `decode`, the same as `DeepScanLedger`.
  - `class ReferenceJournal`: `ReferenceJournal(root)`, stored at `root / "references.json"`. Its `read()` and `save()` follow `DeepScanJournal`.
  - `class ReferenceLibrary`, built as `ReferenceLibrary(journal, port, clock=utc_now)`, with these methods:
    - `def plan_seed(self, seed: tuple[SeedEntry, ...]) -> None`: adds `queued` receipts for new URLs and marks receipts for dropped seed URLs `retired`. It never touches `failed` receipts.
    - `def add_owner(self, product: str, title: str, url: str) -> ReferenceReceipt`
    - `async def advance(self) -> None`: moves each receipt forward one phase.
    - `async def retry(self, url: str) -> None`: allowed only from `failed` or `blocked`.
    - `def view(self) -> tuple[ReferenceReceipt, ...]`

Phase rules for `advance`. Save the journal before each SDK call.
- `queued` to `submitted`: call `acquire(url, name=f"Reference: {title}", request_id)`. On uncertain delivery (`TransportError` with delivery other than `NOT_SUBMITTED`), stay `submitted` with no acquisition id. A later `advance` then finds the ticket in `acquisitions()` by `url` and `request_id`, and never calls `acquire` again. On `Delivery.NOT_SUBMITTED`, go back to `queued`.
- `submitted` or `awaiting`: read the ticket state.
  - `queued` or `running` stays `awaiting`.
  - `blocked` becomes `blocked`, with the gate reason in `error`.
  - `failed` becomes `failed`, with the server error.
  - `succeeded` with a `revision_id` becomes `retained`.
- `retained`: `revision()`, then `replace_tags(existing ∪ {device-reference, product, *topics}, tags_version)`. Then `tagged`.
- Retired and tagged receipts: `replace_tags(existing − {device-reference})`, then `retired_tagged=True`. The source is kept.
- A `Refusal` is recorded as `failed`, with its message.

- [ ] **Step 1: Write failing tests** with an in-memory fake `ReferencePort`:
  - `test_seed_entry_reaches_tagged`
  - `test_restart_in_every_phase_resumes_without_duplicate_acquire`
  - `test_uncertain_delivery_finds_ticket_instead_of_resubmitting`
  - `test_restart_with_blocked_ticket_does_not_resubmit`
  - `test_failed_extraction_is_failed_not_retained`
  - `test_failed_receipt_not_requeued_by_plan_seed`
  - `test_retry_only_from_failed_or_blocked`
  - `test_retire_after_inflight_completion`: the tag is removed, the source is not withdrawn.
  - `test_journal_rejects_symlink_and_public_mode`
  - `test_tags_merge_keeps_existing_tags_and_uses_tags_version`
- [ ] **Step 2: Run the tests and confirm they fail.** Run: `python3 -m unittest tests.test_reference_library -v`
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the tests and confirm they pass.**
- [ ] **Step 5: Commit** `feat(privacy): journal device reference acquisitions`

### Task 4: Collector wiring and dashboard status

**Files:**
- Modify: `integrations/network-privacy/src/plowshare_privacy/cli.py`. Build `ReferenceLibrary` next to `DeepScanner` and call `plan_seed(load_seed())` once at startup. Call `advance()` on the collector's existing periodic loop. Find where deep scans are driven and reuse that cadence.
- Modify: `integrations/network-privacy/src/plowshare_privacy/web.py`. Add `GET /api/references`, which returns receipts, a phase label and the error. Add `POST /api/references/retry`, with body `{"url": "..."}`. Both behind the existing dashboard key, like the other `/api/` routes.
- Modify: `integrations/network-privacy/src/plowshare_privacy/web/app.js`. Add a "Reference library" list to Settings, with a Retry button on failed or blocked rows. In `renderAttention`, add an item when any receipt is `failed` or `blocked` (title "Reference docs need a check", action "Open Settings").
- Test: `integrations/network-privacy/tests/test_reference_web.py` and `integrations/network-privacy/tests/web_references.test.cjs`

**Interfaces:**
- Consumes: `ReferenceLibrary.view`, `ReferenceLibrary.retry` (Task 3).
- Produces: the overview JSON gains `references: {failed: int, blocked: int}`, which `renderAttention` reads.

- [ ] **Step 1: Write failing tests:**
  - `test_references_list_requires_dashboard_key`
  - `test_retry_rejects_unknown_url`
  - `test_retry_failed_receipt`
  - Node: `attention shows reference failures`, and `retry button posts url`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the full check and confirm it passes:** `python3 integrations/network-privacy/scripts/check.py`
- [ ] **Step 5: Commit** `feat(privacy): fetch seed references at startup and show their status`

### Task 5: Grounded agent instructions

**Files:**
- Modify: `integrations/network-privacy/network-privacy-watch/agents/privacy_assistant.md`
- Test: the existing Application contract tests (`./gradlew :plowshare-network-privacy:test`)

- [ ] **Step 1: Edit the prompt.** Add these four rules, in plain language, after "Answer directly from reads first":
  1. Before advising on a device, read its dossier and evidence and answer from them first. A refused port means the service is not exposed from the scanned vantage point.
  2. To find the product, `list` sources tagged `device-reference`, then match the device against their product tags. Do not guess slugs.
  3. For any claim about how a product works (defaults, ports, authentication, configuration, fixes), `search` with filter `tags: ["device-reference", "<product>"]`, read the passage, and name the source in the answer.
  4. If nothing relevant is found, say so and label the claim as general knowledge that has not been checked. Do not mention `network_request_reference` until Task 6 lands.
- [ ] **Step 2: Run** `./gradlew :plowshare-network-privacy:test` from the repository root. Expected: PASS.
- [ ] **Step 3: Manual check.** With a running collector whose library is tagged, ask "Is there an admin port on Caddy?". Expected: a cited answer saying the default is `localhost:2019` and that remote admin uses mutual TLS, with no password or token claims. Record the result in the PR description. This is not automated.
- [ ] **Step 4: Commit** `feat(privacy): ground Ask answers in the device reference library`

## Slice 2: Agent requests and owner review

### Task 6: `network_request_reference` tool and binding migration

**Files:**
- Modify: `integrations/network-privacy/src/plowshare_privacy/tools.py`. Add a `ToolDefinition("network.request_reference", ..., "request", None)`. Add a `ReferenceRequestCall(product: str, question: str, device_id: str | None, request_id: str)` and add it to `AssistantCall`. Decode it in `_assistant_call` and `decode_call`:
  - `product` uses `product_slug`; a bad slug is refused with the slug rule.
  - `question` uses `_note(..., 512)`.
  - `device_id` is optional and parsed with `parse_device_id`.
  - `request_id` is the harness invocation, required.
  - Extra keys (for example `url`) are refused by `object_fields`.
- Modify: `integrations/network-privacy/src/plowshare_privacy/native_tools.py`. In `parameters()`, give this tool `product` (STRING, required), `question` (STRING, required) and `device_id` (STRING, optional).
- Modify: `integrations/network-privacy/src/plowshare_privacy/tool_journal_upgrade.py`:
  - `REFERENCE_TOOLS = frozenset({"network_request_reference"})`
  - Pin `ASSISTANT_DECLARATIONS = tuple(t for t in DECLARATIONS if t.name not in REFERENCE_TOOLS)`. Exclude `REFERENCE_TOOLS` from `PAGED_DECLARATIONS` and `DEVICE_DECLARATIONS` too.
  - Add the step `_compatible(ASSISTANT_DECLARATIONS, DECLARATIONS, {}, REFERENCE_TOOLS, REFERENCE_TOOLS)`.
  - Extend `open_tool_journal`: the `-assistant` journal uses `ASSISTANT_DECLARATIONS` when it exists, and the newest journal becomes `<directory>-references` with `DECLARATIONS`. Update the docstring chain.
- Modify: `integrations/network-privacy/network-privacy-watch/plowshare.json`, adding `network_request_reference` to `network_assistant` grants.
- Modify: `privacy_assistant.md`. Rule 4 now ends: "…and call network_request_reference with the product and what you could not answer. Say the request is queued for the owner, not answered."
- Test: `tests/test_assistant_tools.py`, `tests/test_tool_journal_upgrade.py`, `tests/test_native_tools.py`

**Interfaces:**
- Produces: `ReferenceRequestCall`; `WorkerTools(..., references: ReferenceRequests | None = None)`. `None` refuses with "Reference requests are not enabled on this collector."

- [ ] **Step 1: Write failing tests:**
  - `test_reference_request_decodes_and_requires_invocation`
  - `test_reference_request_refuses_url_argument`
  - `test_reference_request_never_fetches_question_text`: a question containing `https://…` is queued as text, and the fake library gets no `add_owner` call.
  - `test_reference_request_refuses_unknown_device`
  - `test_existing_binding_digests_unchanged`: the paged, device and assistant digests equal their current pinned values.
  - `test_assistant_journal_upgrades_to_references_without_losing_receipts`
  - `test_assistant_tools_match_plowshare_json` (extend the existing pin).
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the full check and confirm it passes.**
- [ ] **Step 5: Commit** `feat(privacy): let the Ask agent request reference docs`

### Task 7: Request journal

**Files:**
- Create: `integrations/network-privacy/src/plowshare_privacy/reference_requests.py`
- Modify: `tools.py`. `WorkerTools.assist` handles `ReferenceRequestCall` and returns `WorkRequestResult(device_id or "", "reference", state, detail)`.
- Test: `integrations/network-privacy/tests/test_reference_requests.py`

**Interfaces:**
- Produces:
  - A frozen dataclass `ReferenceRequest` with these fields:
    - `id: str`: the invocation `request_id`.
    - `product: str`, `question: str`, `device_id: str | None`
    - `state: Literal["pending", "filled", "dismissed"]`
    - `created_at: str`, `decided_at: str | None`
    - `urls: tuple[str, ...]`
  - `class ReferenceRequests`, built as `ReferenceRequests(root, library: ReferenceLibrary, clock=utc_now)` and stored at `root / "reference-requests.json"`. Methods:
    - `def request(self, product: str, question: str, device_id: str | None, request_id: str) -> ReferenceRequest`. Idempotent on `request_id`. Returns the existing pending request for `product`. Returns the dismissed one while it is under 30 days old.
    - `def pending(self) -> tuple[ReferenceRequest, ...]`
    - `def approve(self, request_id: str, urls: tuple[str, ...]) -> ReferenceRequest`. Each URL goes through `reference_url`. Requires 1 to 5 URLs that are unique, also against existing library receipts. Calls `library.add_owner(product, title=f"{product} documentation", url)`.
    - `def dismiss(self, request_id: str) -> ReferenceRequest`

- [ ] **Step 1: Write failing tests:**
  - `test_same_invocation_is_idempotent`
  - `test_pending_product_deduplicates`
  - `test_dismissed_product_suppressed_for_30_days_then_allowed`
  - `test_approve_adds_owner_receipts`
  - `test_approve_rejects_http_and_duplicate_urls`
  - `test_decisions_only_from_pending`
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the tests and confirm they pass.**
- [ ] **Step 5: Commit** `feat(privacy): journal reference requests`

### Task 8: Dashboard review of requests

**Files:**
- Modify: `web.py`. Add three routes:
  - `GET /api/reference-requests`
  - `POST /api/reference-requests/{id}/approve`, with body `{"urls": [..]}`
  - `POST /api/reference-requests/{id}/dismiss`

  `ValueError` messages come back as 400, with the text shown to the owner.
- Modify: `cli.py`. Build `ReferenceRequests` and pass it to `assistant_tools()` and to the web app.
- Modify: `app.js`. In `renderAttention`, add the item "Docs requested", with detail "Privacy Watch could not answer about <products>. Add a documentation link or dismiss." and the action "Review". The review panel shows the product, the agent's question, a URL field (one per line, up to 5), and Approve and Dismiss buttons.
- Test: extend `tests/test_reference_web.py` and `tests/web_references.test.cjs`.

- [ ] **Step 1: Write failing tests:**
  - `test_approve_requires_dashboard_key`
  - `test_approve_rejects_http_and_duplicate_urls` (HTTP 400 with the message)
  - `test_dismiss_then_list_excludes_it`
  - Node: `attention shows docs requested`, and `approve posts trimmed urls`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the full check and confirm it passes.**
- [ ] **Step 5: Commit** `feat(privacy): review reference requests on the dashboard`

## Slice 3: Refresh

### Task 9: Periodic refresh and its setting

**Files:**
- Modify: `reference_library.py`:
  - `def refresh_due(self, now: datetime) -> tuple[str, ...]`: lists `tagged`, non-retired receipts whose `fetched_at` is older than `refresh_days`.
  - `def schedule_refresh(self, now: datetime) -> None`: moves each due receipt back to `queued` with a new `request_id`. It keeps `revision_id` so the resource stays the same; the new revision comes from a fresh acquire of the same URL. Before relying on this, check with a test against the fake port, and with one live run, that acquiring the same URL in the same scope adds a revision to the existing resource rather than creating a second one. If it creates a second resource, stop and report it as a gap.
  - `def set_refresh_days(self, days: int) -> None`: accepts 1 to 365.
  - `fetched_at` is set on `tagged`.
- Modify: `cli.py`, calling `schedule_refresh` on the same loop as `advance`.
- Modify: `web.py` and `app.js`. Add `GET` and `POST /api/references/settings` with `{"refresh_days": int}`, and a Settings field, following the deep-scan settings pattern (`web.py:650-711`).
- Test: `tests/test_reference_library.py`, `tests/test_reference_web.py`

- [ ] **Step 1: Write failing tests:**
  - `test_refresh_due_after_interval_only`
  - `test_refresh_requeues_with_new_request_id`
  - `test_refresh_skips_retired_and_failed`
  - `test_refresh_days_bounds` (0 and 366 rejected)
  - `test_refreshed_source_keeps_tags_without_retagging_call`
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement it.**
- [ ] **Step 4: Run the full check (`scripts/check.py`) and `./gradlew :plowshare-network-privacy:test`, and confirm both pass.**
- [ ] **Step 5: Commit** `feat(privacy): refresh device references on an interval`
