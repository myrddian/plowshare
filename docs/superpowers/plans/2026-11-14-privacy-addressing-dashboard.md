# Network Privacy Watch: address assignment in the dashboard (stage 3a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show how each device's address was assigned (dynamic, reserved, static, unknown) and when that changed. It appears in the device pane, the device list, an Overview summary, the attention band and the Evidence view, and the assessor and assistant agents get it through the device dossier.

**Architecture:**
- `device_inventory.py` reads `snapshot.addressing` from the done receipts it already folds, and adds a typed `AddressingView` to each `DeviceCard`.
- The dashboard (`web/app.js`) renders it from `/api/overview` with no new endpoint.
- The dossier gains an `addressing` field under a new document version 4. The Application's `assess_network_device.js` and the agent prompts accept and explain it.
- Collection, evidence and fingerprints are untouched.

**Tech Stack:** Python 3.11+ (unittest, mypy strict, ruff), browser JavaScript tested with `node --test` in a `vm` stub DOM, and the JavaScript orchestration exercised by the Java/Jackson test `NetworkDeviceAssessmentTest`.

**Spec:** `docs/superpowers/specs/2026-11-14-privacy-source-connectors-design.md`. It describes the Addressing bucket, and its "Settings, setup and UI" and "Calls made" sections defer the dossier and dashboard to stage 3. This plan is stage 3a. Stage 3b (the Sources panel, settings built from `SettingField`, `connect <kind>`, registry health checks) and stage 3c (several services of one kind, `dns_silent` wording, cleanups) get their own plans.

All paths are relative to `integrations/network-privacy/` unless they start with `docs/`.

## Global Constraints

- Everything stays inside `integrations/network-privacy` and `docs/`. No server, protocol or SDK change.
- Collection is unchanged: `scope_fingerprint`, `Evidence.encode()`, issue strings and `changes` lines stay exactly as they are.
- Display only what the evidence says. Never infer a mode the evidence did not record. When an address has no assignment in the latest collection, show nothing for it, not a stale value.
- A mode change counts only under the same rule as `collection.changes`:
  - the two consecutive collections share the same `scope`;
  - both modes are known (not `unknown`);
  - the MAC is equal and not null;
  - neither collection has an issue starting with `addressing_`.
- Address assignment is configuration context, never evidence of compromise. In the dossier and prompts it can never raise a concern on its own.
- Retained dossier documents of versions 1 to 3 must still read and round-trip byte for byte. New writes are version 4.
- Deploy order on a live install: the Application's `assess_network_device.js` (accepts v4) first, then the collector, which writes v4. Record this in README "Upgrade".
- UI copy, exactly:

  | Mode / basis | Label |
  |---|---|
  | `dhcp_dynamic` / `dhcp_lease` | `Dynamic (DHCP lease)` |
  | `dhcp_dynamic` / `inside_dhcp_range` | `Dynamic (inside the DHCP range)` |
  | `dhcp_reserved` / `dhcp_reservation` | `Reserved (DHCP reservation)` |
  | `dhcp_reserved` / `controller_fixed_ip` | `Reserved (fixed IP in the controller)` |
  | `static` / `outside_dhcp_range` | `Static (outside the DHCP range)` |
  | `static` / `no_lease_or_reservation` | `Static inside the DHCP range (no lease or reservation)` |
  | `unknown`, or anything unrecognised | `Unknown` |

  - Short mode names: `Dynamic`, `Reserved`, `Static`, `Unknown`.
  - Lease line: `Lease ends <formatWhen(expires_at)>`.
  - Change line: `Changed from <Short previous> to <Short current> <formatWhen(changed_at)>`.
- Tests:
  - Python: from `integrations/network-privacy`, `PYTHONPYCACHEPREFIX=build/python-cache ../../build/privacy-python-env/bin/python -m unittest discover -s tests -p '<file>'`.
  - Web: `node --test tests/<file>.test.cjs`.
  - Java: from the repo root, `./gradlew :plowshare-network-privacy:test --tests '*NetworkDeviceAssessmentTest*'`.
  - Before each commit: `ruff format`, `ruff check` and `mypy src tests scripts`.

## Review Focus

1. **Address assignment was on in an earlier collection but is off in the latest one** (capabilities turned off). The device shows no assignment, not a stale one. Test in Task 1.
2. **A mode differs across a scope change** (a new baseline). It is not shown as a change. Test in Task 1.
3. **The same address has a different MAC** (another device took it). This is not a change for the new device, and no `previous_mode` is carried over. Test in Task 1.
4. **The dashboard receives a mode or basis it doesn't recognise** (a newer collector). It renders `Unknown` and does not throw. Test in Task 2.
5. **No device has an assignment** (capabilities never enabled). The Overview summary is hidden and no addressing attention item appears. Test in Task 3.

---

### Task 1: `AddressingView` on device cards

**Files:**
- Modify: `src/plowshare_privacy/device_inventory.py`: `DeviceCard` (~21-38), `_AddressHistory`, and the receipt loop in `device_inventory()` (~67-190)
- Test: `tests/test_dashboard.py`, or a new `tests/test_device_addressing.py` if `test_dashboard.py` has no inventory fixtures to reuse

**Interfaces:**
- Consumes: `AddressAssignment`, `AssignmentMode`, `AssignmentBasis` from `contracts.py`; `Evidence.snapshot.addressing`, `Evidence.scope`, `Evidence.issues`, `Evidence.finished_at`.
- Produces:
  - `@dataclass(frozen=True) class AddressingView` with fields `mode: AssignmentMode; basis: AssignmentBasis | None; services: tuple[str, ...]; expires_at: str | None; observed_at: str; previous_mode: AssignmentMode | None = None; changed_at: str | None = None; changed_in_latest: bool = False`.
  - `observed_at` is the `finished_at` of the collection that recorded it.
  - `changed_at` is the `finished_at` of the collection where the latest qualifying change happened.
  - `DeviceCard.addressing: AddressingView | None = None`, the last defaulted field.

- [ ] **Step 1: Write the failing tests.** Build receipts with `Evidence` whose snapshots carry `addressing` (reuse the evidence fixtures in `tests/support.py` and `tests/test_evidence_contract.py`).
  - `test_card_shows_latest_assignment`: the newest done collection's assignment for the address becomes `card.addressing`, with `observed_at` equal to that collection's `finished_at`.
  - `test_no_assignment_in_latest_collection_shows_none`: the latest collection has no `addressing` (an empty tuple) and an older one did, so `card.addressing is None`.
  - `test_mode_change_is_recorded`: two consecutive same-scope collections give `reserved` then `static` for the same MAC. The result is `previous_mode == "dhcp_reserved"`, `changed_at == second.finished_at` and `changed_in_latest is True`. A third collection that is again `static` keeps `previous_mode` and `changed_at` and sets `changed_in_latest False`.
  - `test_change_not_recorded_across_scope_baseline`: the scopes differ, so `previous_mode is None`.
  - `test_change_not_recorded_when_mac_differs`: `previous_mode is None`.
  - `test_change_not_recorded_with_addressing_gap`: either collection has an issue starting with `addressing_partial`, so `previous_mode is None`.
  - `test_unknown_mode_never_counts_as_change`: `static`, then `unknown`, then `static` gives no change.
  - `test_overview_json_carries_addressing`: `json.dumps(asdict(card))` succeeds and contains `"addressing": {"mode": "static"`. A card without an assignment encodes `"addressing": null`.
- [ ] **Step 2: Run the tests.** Expected: FAIL (no `addressing` attribute).
- [ ] **Step 3: Implement.**
  - In the existing receipt fold, keep per address the latest qualifying assignment and the latest change, using the comparison rule in Global Constraints.
  - Build the comparison from the consecutive done collections that list this address.
  - If the newest done collection's snapshot has no assignment for the address, the result is `None`.
  - `InventoryProjection` caching needs no change.
- [ ] **Step 4: Run** the new tests, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(privacy): carry address assignment on device cards`.

### Task 2: Device pane, device list and Evidence view

**Files:**
- Modify: `src/plowshare_privacy/web/app.js`:
  - add `renderAddressing(addressing)` next to `renderTraffic` (~659);
  - `renderDeviceView` (~1133);
  - `renderInventory` (~2254) search text and tags;
  - `showEvidence` (~311).
- Modify: `src/plowshare_privacy/web/style.css`, for one tag colour only if the existing `tag` classes don't fit.
- Test: `tests/web_addressing.test.cjs` (new). Copy the `dashboard()` harness from `tests/web_attention.test.cjs`.

**Interfaces:**
- Consumes: `device.addressing` as built by Task 1 (snake_case JSON keys).
- Produces:
  - `addressingLabel(addressing) -> string` (the full label from the copy table);
  - `addressingShort(mode) -> string`;
  - `renderAddressing(addressing) -> Element | null`.

  Task 3 uses all three.

- [ ] **Step 1: Write the failing tests** in `tests/web_addressing.test.cjs`.
  - `addressing labels follow the mode and basis table`: every row of the copy table, plus `{mode:'static', basis:'novel'}` and `{mode:'novel'}`, both giving `Unknown`.
  - `the pane shows assignment, lease end and change`: `renderAddressing({mode:'dhcp_dynamic', basis:'dhcp_lease', services:['pihole'], expires_at:'2026-11-14T04:02:45Z', observed_at:'…', previous_mode:'dhcp_reserved', changed_at:'2026-11-14T03:10:11Z', changed_in_latest:true})`. The text contains `Dynamic (DHCP lease)`, `Lease ends `, `Changed from Reserved to Dynamic` and `Source: pihole`.
  - `no addressing renders nothing`: `renderAddressing(null) === null`.
  - `the device list can be searched by assignment`: after `renderInventory` with one device whose `addressing.mode` is `static`, `card.dataset.search` includes `static`. A device with `basis` `no_lease_or_reservation` gets a tag whose text is `Static in DHCP range`.
  - `the Evidence view lists assignments`: `showEvidence` with `snapshot.addressing` of two rows renders a list titled `Address assignment` with `192.0.2.10 · Reserved (DHCP reservation)`.
- [ ] **Step 2: Run** `node --test tests/web_addressing.test.cjs`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `renderAddressing` builds a `section.readout.addressing` containing: an `h4` `Address assignment`; a `p` with the label; a `p.hint.source` `Source: <services joined by ", ">` when there are services; a `p` with the lease line when `expires_at` is set; and a `p.limit` with the change line when `previous_mode` and `changed_at` are set.
  - In `renderDeviceView`, add a `fact(facts, 'Address', addressingLabel(...))` line under the existing identity facts. Append `renderAddressing` beside the traffic readout in `device-facts`, and skip it when it returns null.
  - In `renderInventory`, append the lowercase short mode to `dataset.search`. Add the `Static in DHCP range` tag only for basis `no_lease_or_reservation`.
  - In `showEvidence`, use the existing `listItems(panel, 'Address assignment', rows)` with `<address> · <label>` rows, but only when `snapshot.addressing` is non-empty.
  - Use only `element()`, `append` and property assignment; the stub DOM has no `style`.
- [ ] **Step 4: Run** the new test file plus all `node --test tests/web_*.test.cjs`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(privacy): show address assignment in the device pane and list`.

### Task 3: Overview summary and attention items

**Files:**
- Modify: `src/plowshare_privacy/web/app.js`: `renderAttention` (~2461) and `renderOverview` (~2574).
- Modify: `src/plowshare_privacy/web/index.html`: add `<section id="addressing-summary" hidden>` directly after the attention band.
- Test: `tests/web_addressing.test.cjs`.

**Interfaces:**
- Consumes: `addressingShort` from Task 2; `deviceNames(devices)`, `reviewAction(devices)` and `attentionItem(...)` (existing).
- Produces: `renderAddressingSummary(devices) -> void` (fills and unhides `#addressing-summary`, or hides it).

- [ ] **Step 1: Write the failing tests.**
  - `the overview summarises assignment across devices`: three devices (`dhcp_dynamic`, `dhcp_reserved`, `static`) plus one with `addressing: null`. The summary text is exactly `Address assignment (latest collection): 1 dynamic · 1 reserved · 1 static · 0 unknown · 1 not reported`, and the section is not hidden.
  - `the summary is hidden when no device reports assignment`: every device has `addressing: null`, so the section is hidden.
  - `a static address inside the DHCP range is an attention item`, with the title `Static address inside the DHCP range`. The detail starts with the device names from `deviceNames` and ends with ` use an address the DHCP server can hand out, with no lease or reservation; that can clash with another device.`
  - `a mode change in the latest collection is an attention item`, with the title `Address assignment changed`. The detail is `<names> changed how their address is assigned in the latest collection.` and appears only for `changed_in_latest: true`.
  - `addressing items follow breach and review items`: they come after the existing breach and review items in the band.
  - `unmonitored devices never raise addressing items`: use the same monitored filter the band already applies.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `renderAddressingSummary` counts `addressing.mode` over the overview devices. A missing `addressing` counts as `not reported`, and an unrecognised mode counts as `unknown`.
  - Call it from `renderOverview`.
  - Push the two attention items at the end of `renderAttention`, each with `reviewAction(devices)`.
- [ ] **Step 4: Run** all `node --test tests/web_*.test.cjs`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(privacy): summarise address assignment and flag risky ones`.

### Task 4: Dossier v4 for the agents

**Files:**
- Modify: `src/plowshare_privacy/device_dossier.py`: add a `DeviceDossier.addressing` field (~309) and set it in `build_dossier` (~748).
- Modify: `src/plowshare_privacy/dossier_port.py`: `DOCUMENT_VERSION = 4`, `_DOSSIER_FIELDS[4]` (~66-85).
- Modify: `network-privacy-watch/orchestrations/assess_network_device.js`: `dossierRecord` (~220-257) accepts versions 1-4, with `addressing` allowed and checked only for version 4 or later.
- Modify: `network-privacy-watch/agents/privacy_device_assessor.md`, `privacy_reviewer.md` and `privacy_assistant.md`.
- Modify: `README.md`, under "Upgrade" and the dashboard sections.
- Test:
  - `tests/test_device_dossier.py` and `tests/test_dossier_port.py`;
  - `src/test/java/io/aeyer/plowshare/integrations/privacy/NetworkDeviceAssessmentTest.java`, plus a new fixture `src/test/resources/io/aeyer/plowshare/integrations/privacy/tv-dossier-v4.json`.

**Interfaces:**
- Consumes: `DeviceCard.addressing: AddressingView | None` (Task 1).
- Produces: `DeviceDossier.addressing: AddressingView | None = None`, encoded under the `addressing` key in version-4 documents.

- [ ] **Step 1: Write the failing tests.**
  - Python:
    - `test_dossier_carries_addressing`: `build_dossier` with a card whose `addressing` is set gives `dossier.addressing == card.addressing`, and `encode_dossier` contains `"addressing":{"mode":"static"`.
    - `test_v4_document_round_trips`.
    - `test_v1_to_v3_documents_still_read_byte_for_byte`, kept or extended.
    - `test_v3_document_with_addressing_is_rejected`.
  - Java:
    - `v4_dossier_with_addressing_is_accepted`: the new fixture is `tv-dossier.json` with `"version": 4`, the v3 envelope fields, and an `addressing` object. The orchestration runs as it does for v3.
    - `v4_dossier_with_invalid_addressing_mode_is_rejected`: mode `"novel"` fails with the orchestration's dossier error.
- [ ] **Step 2: Run** the Python tests and `./gradlew :plowshare-network-privacy:test --tests '*NetworkDeviceAssessmentTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The dossier field is copied from the card (dataclass `asdict` encoding, as the other fields are).
  - Raise the version and field table.
  - In `dossierRecord`:
    - accept version 4;
    - for version 4 or later, allow an optional `addressing` that is `null` or an object whose `mode` is one of the four modes and whose `basis` is `null` or one of the six bases;
    - leave other keys lenient, as today.
  - Prompts: add one paragraph to the assessor and reviewer, adapted to each file's voice:

    > `DATA.dossier.addressing` (when present) says how the device's address was assigned and whether that changed. It is network-configuration context: mention a static address inside the DHCP range or a recent change in plain words, but never treat it alone as a concern.

    In the assistant prompt, add one sentence so it can explain the label to the user.
  - README: the dashboard shows address assignment, and the upgrade order is Application first, then collector.
- [ ] **Step 4: Run** the Python suite, the Java test, ruff, mypy and the web tests. Expected: all PASS.
- [ ] **Step 5: Commit:** `feat(privacy): give the agents address assignment in dossier v4`.

---

## Not in this plan
- Stage 3b: a Sources panel with per-capability status; settings built from `SettingField`; `connect <kind>`; registry-driven health checks, including the `_PURPOSES` label.
- Stage 3c: several services of one kind (fix `scope_fingerprint`'s per-kind keys first); carrying Pi-hole ambiguity across services (`DeviceData.ambiguous`); `pihole_silent` to `dns_silent`; removing the `CollectionPlan.pihole`/`.unifi` accessors and `ServicePlan.legacy`; the stage 1–2 deferred minors.
- Changing how addresses are classified. This plan only displays what collections already record.
