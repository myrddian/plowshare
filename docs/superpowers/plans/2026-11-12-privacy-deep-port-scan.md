# Deep Port Scan of a Flagged Device: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an assessment flags a device, or when the operator asks, the collector probes every TCP port (1–65535) of that one device. It keeps the result, shows it in the dashboard and feeds it into the device's next assessment.

**Architecture:**
- A new collector module, `deep_scan.py`, owns:
  - a private journal (`deep-scans.json`);
  - a one-at-a-time queue;
  - the probe loop;
  - the before-and-after identity checks.
- `DeviceAssessments` calls a hook when a report first reaches review with flagged concerns, and passes the device's latest deep scan into the dossier.
- `web.py` exposes routes and an overview block.
- `app.js` renders a pane section, an attention item and a Settings field.
- No Plowshare core, SDK or Application script change.

**Tech Stack:** Python 3.12, asyncio, aiohttp, unittest; vanilla JS dashboard with node:test.

**Spec:** `docs/superpowers/specs/2026-11-12-privacy-deep-port-scan-design.md`

All paths below are relative to `integrations/network-privacy/`. Run Python tests from `tests/` with:

```
PYTHONPATH=../src ../../../build/privacy-dev/bin/python -m unittest <module>
```

## Global Constraints

- **Probing:**
  - Plain TCP connect only: no raw sockets, banners or data sent.
  - Outcomes are `open`, `closed` (refused) or `timeout`. Any other `OSError` fails the scan; it is never treated as closed.
- **Scope:**
  - One device per scan, TCP ports 1–65535, private IPv4 only (`contracts.private_ipv4`).
  - The worst case stays under about 20 minutes: `PROBE_TIMEOUT = 0.5` s and `CONCURRENCY = 64` give ≈ 8.5 min.
- **One scan at a time,** automatic or operator.
- **Automatic limit:**
  - `automatic_limit` is an integer from 0 to 12 per household day (`config.zone`, via `contracts.household_day`). 0 means off and is the default.
  - Operator requests have no daily limit.
- **Triggers:**
  - Only a report that *newly* reaches review while `automatic_limit > 0` triggers a scan. There is no backlog when the limit is raised.
  - Queued triggers: one per device, the newest wins, oldest first, and they expire after 7 days.
  - Setting the limit to 0 discards queued automatic triggers and keeps operator requests.
- **Identity:**
  - Fresh `DeviceIdentitySource.identify((address,), now)` reads before and after the scan; the address must map to the device's confirmed MAC both times.
  - Before the scan: a mismatch or no answer means the scan does not run, and the state is `address_changed` or `unreliable`.
  - After the scan: the result is `unreliable` and is never credited to the device.
  - With no identity source configured, deep scans are unavailable.
- **Restart:** a scan running at a restart is recorded `interrupted` and never resumed. Queued triggers survive a restart.
- **Persistence:**
  - `deep-scans.json` is written with `private_files.replace_private` (0600, no symlinks).
  - Size is bounded: keep the last 3 results per device and at most 256 devices.
- **Dossier:** no envelope version change. The deep scan appears as one coverage line, plus exposures for deep-scan open ports matching `device_detection._SERVICE_RULES`.
- **"New open ports"** are those open in the latest complete scan and not in `config.collection.ports`. The attention item clears when an assessment requested after that scan exists, or when a later scan finds none of them.
- **Copy:** British spelling; counts through `plural()` / `formatCount()`; "collection", never "sweep"; `docs/console-design.md` "Round 3: one pattern" (button variants, cinnabar only for errors).
- **Testing:**
  - Mocked and in-memory tests only (no `-PfullDb`).
  - Run `mypy src tests`, `ruff format` and `ruff check`, all web tests, and from the worktree root `./gradlew -q :plowshare-network-privacy:test formatCheck -x :plowshare-console:pnpmBuild`.

## Review Focus

1. **The device moves to another address mid-scan** (DHCP churn). Expect the result `unreliable`, and that no ports are credited to the device. Test: `test_an_address_that_changes_during_the_scan_is_unreliable` (Task 2).
2. **A probe raises an unexpected `OSError`** (for example `ENOBUFS`, socket exhaustion). Expect the scan to end `failed` with a reason, not a false "all closed". Test: `test_socket_exhaustion_fails_the_scan_instead_of_reporting_closed` (Task 1).
3. **The limit is raised while reports are already in review.** Expect no scans to start. Test: `test_raising_the_limit_starts_no_backlog` (Task 3).
4. **The operator cancels a queued (not yet running) scan.** Expect it to be removed from the queue, with the state back to idle or the last result. Test: `test_cancelling_a_queued_request_removes_it` (Task 2).
5. **The journal is corrupt or oversized at start-up.** Expect deep scans to show "needs attention" and collection to keep working. Test: `test_an_unreadable_journal_disables_deep_scans_only` (Task 4).

---

### Task 1: Journal, records and the port probe

**Files:**
- Create: `src/plowshare_privacy/deep_scan.py`
- Test: `tests/test_deep_scan.py`

**Interfaces:**
- Produces, in `deep_scan.py`:
  - `Trigger = Literal["automatic", "operator"]`
  - `ScanState = Literal["complete", "cancelled", "interrupted", "unreliable", "address_changed", "failed"]`
  - `@dataclass(frozen=True) DeepScanRequest(device_id: str, trigger: Trigger, report: str | None, request_id: str, queued_at: str)`. This is the typed request a later Relay transport would carry. `__post_init__` validates `parse_device_id`, `uuid(request_id)`, `report` is a UUID or None, and `timestamp(queued_at)`.
  - `@dataclass(frozen=True) DeepScanResult(device_id: str, address: str, request_id: str, trigger: Trigger, report: str | None, started_at: str, finished_at: str, state: ScanState, open_ports: tuple[int, ...], unanswered: int, detail: str | None)`. `open_ports` must be sorted, unique and within 1–65535; `unanswered` is between 0 and 65535; `open_ports` is non-empty only when `state == "complete"`.
  - `@dataclass(frozen=True) DeepScanLedger(automatic_limit: int = 0, queue: tuple[DeepScanRequest, ...] = (), running: DeepScanRequest | None = None, results: tuple[DeepScanResult, ...] = (), admissions: tuple[str, ...] = ())`, with `decode(value: object) -> DeepScanLedger` and bounds of 256 devices and 3 results per device.
  - `class DeepScanJournal(root: Path)` with `read() -> DeepScanLedger` and `save(DeepScanLedger) -> None`, at file `root / "deep-scans.json"`. Same rules as `AssessmentJournal`: refuse links and non-private modes, and use `replace_private`.
  - `async def probe_port(address: str, port: int, timeout: float) -> Literal["open", "closed", "timeout"]`. It mirrors `collection._quick_connect`: refused → `"closed"`; `TimeoutError`, `EHOSTUNREACH` or `ETIMEDOUT` → `"timeout"`; any other `OSError` is re-raised.
  - Constants: `PORTS = range(1, 65536)`, `PROBE_TIMEOUT = 0.5`, `CONCURRENCY = 64`, `MAX_AUTOMATIC = 12`, `KEEP_RESULTS = 3`, `QUEUE_TTL_SECONDS = 7 * 86400`.

- [ ] **Step 1: Write failing tests**
  - `test_the_ledger_round_trips_and_rejects_bad_records`:
    - Encode with `json.dumps(asdict(ledger))` and decode it back.
    - Assert `ValueError` for: `automatic_limit` of 13 and of -1; `open_ports=(80, 22)` (unsorted); a port of 70000; a `"failed"` result with open ports; a non-UUID `request_id`.
  - `test_the_journal_is_private_and_refuses_links`:
    - After saving, `stat().st_mode & 0o777 == 0o600`.
    - A symlinked path raises `ValueError`.
  - `test_probe_port_reports_open_closed_and_timeout`, using `asyncio.start_server` on `127.0.0.1` with port 0:
    - An open listener gives `"open"`.
    - A closed port gives `"closed"`.
    - Patching `asyncio.open_connection` to raise `TimeoutError` gives `"timeout"`.
  - `test_socket_exhaustion_fails_the_scan_instead_of_reporting_closed`: patching `open_connection` to raise `OSError(errno.ENOBUFS, ...)` makes `probe_port` raise `OSError`.
- [ ] **Step 2: Run them; expect `ModuleNotFoundError: plowshare_privacy.deep_scan`.**
  Run: `PYTHONPATH=../src ../../../build/privacy-dev/bin/python -m unittest test_deep_scan`
- [ ] **Step 3: Implement the records, journal and `probe_port` in `deep_scan.py`.** Add a module docstring stating the probing contract from Global Constraints.
- [ ] **Step 4: Run the tests; expect OK.** Also run `mypy src tests` and `ruff check`.
- [ ] **Step 5: Commit** with the message "Add the deep port scan journal and probe".

### Task 2: The scanner: queue, limits, identity checks, cancel and restart

**Files:**
- Modify: `src/plowshare_privacy/deep_scan.py`
- Test: `tests/test_deep_scan.py`

**Interfaces:**
- Consumes the Task 1 records, `DeviceIdentitySource` and `IdentitySample` from `identity_source.py`, `DeviceInventory` and `monitored_devices` from `device_inventory.py`, `household_day`, `private_ipv4` and `utc_now`.
- Produces:
  - `@dataclass(frozen=True) DeepScanView(device_id: str, available: bool, state: Literal["idle", "queued", "running"], trigger: Trigger | None, checked: int, total: int, open_so_far: tuple[int, ...], estimated_finish: str | None, latest: DeepScanResult | None, detail: str | None)`.
  - `@dataclass(frozen=True) DeepScanUsage(automatic_limit: int, used_today: int)`.
  - `class DeepScanner` with the constructor:
    `__init__(self, journal: DeepScanJournal, inventory: Callable[[], DeviceInventory], identities: DeviceIdentitySource | None, *, probe: Callable[[str, int, float], Awaitable[str]] = probe_port, ports: Sequence[int] = PORTS, clock: Callable[[], str] = utc_now, zone: Callable[[], str] = lambda: "UTC", new_id: Callable[[], str] = lambda: str(uuid4()))`
  - and these members:
    - `ledger: DeepScanLedger`, a read-only property over the persisted state.
    - `available: bool`: True when `identities is not None`.
    - `error: str | None`
    - `flagged(device_id: str, report: str) -> None`: enqueues an automatic trigger only when `automatic_limit > 0`. It is idempotent per `(device_id, report)`.
    - `request(device_id: str) -> DeepScanView`: an operator trigger. It raises `UnknownDevice` (from `device_assessments`) for a device that is not monitored or has no confirmed MAC.
    - `cancel(device_id: str) -> DeepScanView`
    - `set_automatic_limit(limit: int) -> None`
    - `view(device_id: str) -> DeepScanView`
    - `latest(device_id: str) -> DeepScanResult | None`, which returns the latest `complete` result only.
    - `views() -> tuple[DeepScanView, ...]`, one per device with a queue entry, a running scan or a result.
    - `usage() -> DeepScanUsage`
    - `async poll() -> None`, which runs at most one queued scan to completion.
    - `async run(stop: asyncio.Event) -> None`, which loops `poll()` every 5 s and sets `error` on failure, like `DeviceAssessments.run`.
- Order inside `poll()`:
  1. Drop queue entries older than `QUEUE_TTL_SECONDS`.
  2. Pick the oldest entry. An automatic entry is skipped while `usage().used_today >= automatic_limit`; an operator entry is never limited.
  3. Resolve the device's current address from `inventory()`.
  4. Call `identities.identify((address,), now)`. Require exactly one identity with `mac == expected` and the address not in `ambiguous_addresses`. Otherwise record `address_changed` (wrong MAC) or `unreliable` (no answer or ambiguous), and stop.
  5. Persist `running` and append the automatic admission.
  6. Probe `ports` with a semaphore of `CONCURRENCY`, checking the cancel set between completions and updating in-memory progress.
  7. Re-identify. A mismatch makes the result `unreliable` with no ports.
  8. Write the result and clear `running`.
- At construction, a persisted `running` becomes an `interrupted` result with `detail="The collector restarted during this scan; it was not resumed."`.

- [ ] **Step 1: Write failing tests** with a fake `identify` that returns chosen samples, a fake `probe` that returns `"open"` for chosen ports and `"closed"` otherwise, `ports=range(1, 101)` to keep tests fast, and a fixed clock:
  - `test_an_operator_request_scans_and_keeps_the_open_ports`: ports {22, 80} are open → the result is `complete`, `open_ports == (22, 80)` and `unanswered == 0`.
  - `test_automatic_triggers_need_a_limit_and_respect_it`:
    - With limit 0, `flagged()` queues nothing.
    - With limit 1, two flagged devices produce only one scan today. The second scan runs once the clock passes the household-day boundary for `zone="Australia/Sydney"`.
  - `test_operator_requests_are_not_limited_per_day`: with limit 1 already used, three operator requests all run in turn.
  - `test_the_same_report_triggers_once_and_a_newer_report_replaces_a_queued_one`
  - `test_an_address_that_changes_during_the_scan_is_unreliable`: the second `identify` returns a different MAC → the state is `unreliable` and `open_ports == ()`.
  - `test_a_wrong_mac_before_the_scan_does_not_probe`: probe call count 0 and state `address_changed`.
  - `test_cancelling_a_queued_request_removes_it`, and `test_cancelling_a_running_scan_records_cancelled`, which cancels from inside the fake probe after 10 ports.
  - `test_a_scan_running_at_restart_is_interrupted_not_resumed`: persist `running`, construct a new `DeepScanner` → there is an `interrupted` result, and poll does not probe.
  - `test_limit_zero_discards_automatic_but_keeps_operator_requests`
  - `test_queued_triggers_expire_after_seven_days`
  - `test_a_public_address_is_never_probed`: the inventory gives the device `8.8.8.8` → probe call count 0, state `failed`, and the detail names the private-address rule.
  - `test_without_an_identity_source_deep_scans_are_unavailable`: `available is False`, and `request()` raises `ValueError` with "needs UniFi or Pi-hole".
- [ ] **Step 2: Run them; expect failures** (`AttributeError`/`ImportError` on `DeepScanner`).
- [ ] **Step 3: Implement `DeepScanner`, `DeepScanView` and `DeepScanUsage`** in the poll order above. Persist after every state change. `estimated_finish` comes from the elapsed time per checked port.
- [ ] **Step 4: Run the tests, then `mypy` and `ruff`; expect OK.**
- [ ] **Step 5: Commit** with the message "Run deep port scans one device at a time with identity checks".

### Task 3: Trigger from assessments and feed the dossier

**Files:**
- Modify: `src/plowshare_privacy/device_assessments.py` (constructor, the review transition near the `phase="review"` replace at about line 783, `assess()`, `AssessmentSummary`/`summary()`)
- Modify: `src/plowshare_privacy/device_dossier.py` (`build_dossier`, `_coverage`)
- Modify: `src/plowshare_privacy/device_detection.py`
- Test: `tests/test_device_assessments.py`, `tests/test_device_dossier.py`

**Interfaces:**
- Consumes `DeepScanResult` (Task 1) and `DeepScanner.flagged` and `latest` (Task 2).
- Produces:
  - `DeviceAssessments.__init__(..., flagged: Callable[[str, str], None] = lambda device, report: None, deep_scan: Callable[[str], DeepScanResult | None] = lambda _: None)`.
    - `flagged(device_id, report_revision)` is called only at the transition into `review`, and only when the result has a non-refuted `potential_breach` or `worth_checking` concern. It is called after the `_put`; an exception from it is caught and logged to `self.error`, and never breaks the transition.
  - `AssessmentSummary` gains `requested_at: str | None`, the entry's `requested_at`; `summary()` takes it as a new final parameter.
  - In `device_detection.py`: `def exposures_for(open_ports: set[int]) -> tuple[ServiceExposure, ...]`, which reuses `_SERVICE_RULES`. `detect_device` uses it internally.
  - `build_dossier(..., deep_scan: DeepScanResult | None = None)` with these effects:
    - It adds one coverage line:
      `f"A full TCP scan on {result.finished_at[:10]} found {len(result.open_ports)} open {'port' if n == 1 else 'ports'}: {', '.join(map(str, result.open_ports))}; {result.unanswered} did not answer."`
      With no open ports, the line reads `"found no open ports"`.
    - It appends `exposures_for(set(result.open_ports))` entries not already present to `DossierDetection.exposures`.
    - Only a `complete` result for the same device is used; anything else raises `ValueError`.
  - `DeviceAssessments.assess()` passes `deep_scan=self.deep_scan(device)`.

- [ ] **Step 1: Write failing tests**
  - `test_a_report_reaching_review_with_a_flagged_concern_calls_flagged_once`: one call with `(TV, report_revision)`; polling again makes no second call; a report with only `expected` concerns makes no call.
  - `test_raising_the_limit_starts_no_backlog`: a real `DeepScanner` (in-memory fakes) is wired as `flagged`. A report reaches review while the limit is 0, then the limit is raised to 4 → `scanner.ledger.queue == ()`.
  - `test_the_latest_deep_scan_reaches_the_dossier`: the dossier coverage contains `"A full TCP scan on 2026-11-11 found 2 open ports: 22, 8008; 0 did not answer."`, and the exposures include `"SSH"`.
  - `test_summaries_carry_the_assessment_request_time`
  - In `test_device_dossier.py`: `test_a_deep_scan_for_another_device_is_refused`
- [ ] **Step 2: Run them; expect failures.**
- [ ] **Step 3: Implement** the hook, the summary field, `exposures_for` and the `build_dossier` kwarg as specified. Update the existing `summary()` call sites and tests for the new parameter.
- [ ] **Step 4: Run the full Python suite, then `mypy` and `ruff`; expect OK.**
- [ ] **Step 5: Commit** with the message "Trigger deep scans from flagged assessments and include them in the dossier".

### Task 4: Dashboard API and composition

**Files:**
- Modify: `src/plowshare_privacy/web.py`, `src/plowshare_privacy/cli.py`
- Test: `tests/test_deep_scan_web.py` (new; follow `test_monitor.py`'s `TestClient` pattern), `tests/test_operator_console.py`

**Interfaces:**
- Consumes `DeepScanner` (Task 2) and the `DeviceAssessments` kwargs (Task 3).
- Produces these HTTP endpoints. All require the dashboard bearer; mutations require JSON and the same origin, as with the existing routes.
  - `GET /api/devices/{device_id}/deep-scan` returns `asdict(DeepScanView)`.
  - `POST /api/devices/{device_id}/deep-scan/request` with body `{}` returns the view.
  - `POST /api/devices/{device_id}/deep-scan/cancel` with body `{}` returns the view.
  - `GET /api/deep-scans` returns `{"automatic_limit", "used_today", "available", "detail"}`.
  - `POST /api/deep-scans` with body `{"automatic_limit": int}` returns the same shape. A value outside 0–12 → 400 with `"Choose a whole number from 0 to 12 automatic deep scans a day"`.
  - When no scanner is configured, every route returns 404 `"Deep port scans are not enabled"`.
- `/api/overview` adds `"deep_scans": {"views": [...], "usage": {...}, "available": bool, "detail": str | None, "configured_ports": [...]}`, or `None` when there is no scanner. `/api/status` adds `"deep_scans_available"`.
- `application(..., deep_scans: DeepScanner | None = None)`.
- In `cli.py`, build the scanner when `operator_store` and the collection mode is `"tcp"`:
  - `identities` is `collector.identity_source`.
  - `zone` is `lambda: worker.config.zone`.
  - Pass `flagged=scanner.flagged` and `deep_scan=scanner.latest` to `DeviceAssessments`.
  - Run `scanner.run(stop)` as a task beside the assessment task, and cancel it the same way at shutdown.
  - If the journal can't be read at start-up, keep `scanner = None` and expose `detail` "Deep port scans need attention: their journal could not be read." in status. Collection must start regardless.

- [ ] **Step 1: Write failing tests**
  - `test_deep_scan_routes_request_cancel_and_report_state`
  - `test_the_automatic_limit_route_validates_its_range`: values 13, -1 and `"4"` → 400.
  - `test_overview_lists_deep_scans_and_the_configured_ports`
  - `test_routes_are_absent_without_a_scanner`: 404.
  - `test_an_unreadable_journal_disables_deep_scans_only`: write garbage to `deep-scans.json`, then compose through the helper `cli` uses → `scanner is None`, the status detail is set and the worker is ready.
- [ ] **Step 2: Run them; expect failures.**
- [ ] **Step 3: Implement** the routes, the overview and status fields, and the `cli.py` composition. Extract the journal-read guard into `deep_scan.open_scanner(...) -> tuple[DeepScanner | None, str | None]` so the test can call it.
- [ ] **Step 4: Run the full Python suite, then `mypy` and `ruff`; expect OK.**
- [ ] **Step 5: Commit** with the message "Expose deep port scans to the dashboard".

### Task 5: Dashboard UI

**Files:**
- Modify: `src/plowshare_privacy/web/app.js`, `src/plowshare_privacy/web/index.html`, `src/plowshare_privacy/web/style.css` (only if a new class is needed)
- Modify: `tests/preview.py` (a `PreviewDeepScans` fake with in-memory views, so the preview shows one complete result for the Living room TV and Settings works)
- Test: `tests/web_deep_scan.test.cjs` (new; copy the `dashboard()` harness from `web_consistency.test.cjs`)

**Interfaces:**
- Consumes the Task 4 endpoints and `overview.deep_scans`.
- Produces these JS functions:
  - `deepScanOf(device)`, which returns the view or null.
  - `newOpenPorts(view, configuredPorts)`, which returns the ports open in `view.latest` that are not in `configuredPorts`.
  - `deepScanNeedsReview(device, overview)`, true when new ports exist and the device's assessment summary `requested_at` is null or earlier than `view.latest.finished_at`.
  - `renderDeepScan(device, block)` for the pane section.
  - `renderDeepScanSettings(value)`.
- What the operator sees:
  - **Pane section "Deep port scan"**, placed after the agent assessment:
    - Idle: "Deep-scan this device" (secondary button).
    - Queued: "Waiting for its turn" and "Cancel" (quiet button).
    - Running: "{formatCount(checked)} of 65,535 ports checked · {plural(n,'open port','open ports')} so far · about {time} left" and "Cancel".
    - Latest result: open ports as a list, each with `exposures` wording where one exists, plus "{plural(unanswered,'port','ports')} did not answer", and "Assess again" if `deepScanNeedsReview`.
    - Non-complete states show their `detail` as plain hint text, not an error colour, except `failed`, which uses the error style.
  - **Attention item:** "New open ports" with figure = the count of devices where `deepScanNeedsReview`, text "{names} answered on ports outside the usual checks." and the "Open {name}" action, placed after assessment items and before credentials.
  - **Settings,** in the existing "Background assessments" panel:
    - A field labelled "Automatic deep scans per day" (`type=number min=0 max=12`).
    - Hint: "0 turns them off. Each scan probes all 65,535 TCP ports of one flagged device. Before raising this on a network with UniFi threat detection, check it will not block the collector. Scans you start yourself are not limited."
    - When unavailable: "Deep port scans need UniFi or Pi-hole to confirm each device's address." and the field is disabled.
  - **Background work summary:** "Automatic deep scans used today" shows "{used} of {limit}" (or "Off" when the limit is 0).
  - Polling: while any view is `running`, the pane re-reads `GET .../deep-scan` every 5 s through the existing refresh. No new timer, and the Task 17 rule still holds: the pane is rebuilt only when this device's data changed.

- [ ] **Step 1: Write failing tests**
  - `newOpenPorts ignores configured ports`
  - `the attention band lists devices with unassessed new ports and clears after an assessment`: `requested_at` is later than `finished_at` → no item.
  - `the pane shows progress, the result and Assess again`
  - `Settings disables the field without an identity source`
  - `background work summarises automatic deep scans`
- [ ] **Step 2: Run them; expect failures.**
  Run: `node --test tests/web_deep_scan.test.cjs`
- [ ] **Step 3: Implement the UI** as specified. Clear all deep-scan state in the disconnect reset beside the other `deviceSupplemental = null` resets.
- [ ] **Step 4: Run all web tests (`node --test tests/web_*.test.cjs`) and the full Python suite; expect OK.**
- [ ] **Step 5: Check the preview** (`privacy-preview` in `.claude/launch.json`) at desktop and at 375 px:
  - The pane section renders in each state (switch states through the preview fake).
  - The attention item, the Settings field and the absence of horizontal scroll at 375 px.
  - Reset the viewport afterwards.
- [ ] **Step 6: Commit** with the message "Show deep port scans in the device pane, attention band and Settings".

### Task 6: Documentation and final verification

**Files:**
- Modify: `README.md` (a "Deep port scans" subsection after the time zone paragraph, under "Run the Python web interface")
- Modify: `docs/console-design.md` (one catalogue row for the "Deep port scan" pane section and one decision line for the attention rule)

- [ ] **Step 1: Write the README subsection.** It covers:
  - what a deep scan probes and does not probe (no UDP or banners);
  - the automatic trigger and its 0–12 daily limit (0 by default);
  - that operator scans are unlimited and one at a time;
  - the identity checks;
  - "interrupted, never resumed";
  - the UniFi IDS/IPS check before raising the limit.
- [ ] **Step 2: Add the console-design row and decision.**
- [ ] **Step 3: Run every check:**
  - From `tests/`: the unittest discover, and `node --test web_*.test.cjs`.
  - From `integrations/network-privacy`: `mypy src tests`, `ruff format src tests` (expect "left unchanged") and `ruff check src tests`.
  - From the worktree root: `./gradlew -q :plowshare-network-privacy:test formatCheck -x :plowshare-console:pnpmBuild` (expect exit 0).
- [ ] **Step 4: Commit** with the message "Document deep port scans". Push the branch, and open a PR against `main` describing the feature, the checks and the deploy notes:
  - The deploy is collector only, with no Application script change.
  - The PROD limit stays 0 until the owner raises it after the UniFi check.
