# Network Privacy Watch source connectors (stages 1–2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard-wired Pi-hole and UniFi plumbing with a registry of connectors that declare capabilities feeding vendor-neutral evidence buckets. Then add an Addressing bucket (how each address was assigned), fed by Pi-hole DHCP leases, reservations and scope, and by UniFi fixed IPs and DHCP scope, plus Pi-hole local DNS names.

**Architecture:** `contracts.py` gains `ServicePlan` (kind, id, enabled capabilities, typed settings) and `CollectionPlan.sources`. A new `connectors.py` registry turns each `ServicePlan` into typed bindings collected in a `SourceSet` (`source_set.py`). `NetworkCollector` consumes only the `SourceSet`, and merges identity through one precedence function (`identity_merge.py`). Stage 3 (settings UI built from settings fields, `connect <kind>`, vendor-neutral console, dossier and prompt wording, several services of one kind) gets its own plan once this lands.

**Tech Stack:** Python 3.11+, `aiohttp`, `unittest` (`IsolatedAsyncioTestCase`, `aiohttp.test_utils.TestServer` fakes), mypy strict, ruff.

**Spec:** `docs/superpowers/specs/2026-11-14-privacy-source-connectors-design.md`

All paths below are relative to `integrations/network-privacy/` unless they start with `docs/`.

## Global Constraints

- Everything stays inside `integrations/network-privacy`. No change to the Plowshare server, protocol, SDKs or shared contracts.
- Connectors only read: every call is a `GET` except opening and closing the Pi-hole session (`POST`/`DELETE auth`), which is never replayed.
- Every list read has a fixed limit; only targeted addresses enter evidence; error and issue strings are fixed text with no response bodies, URLs or credentials.
- Raw JSON or `dict` never crosses from a connector into the collector or buckets; decode into frozen dataclasses at the connector boundary.
- No deployment values in code: addresses, credential names and paths come only from configuration.
- An untouched configuration keeps exactly the same `scope_fingerprint`, the same `Evidence.encode()` text and the same issue strings.
- A capability the service does not offer is "not offered", never an issue/gap.
- Precedence, highest first: `managed_device` → `controller_client` → `dhcp_lease` → `neighbour_table` → `local_dns` (name only). Precedence never overrides a MAC disagreement.
- DHCP leases are identity and addressing evidence; they never create a presence `Sighting`. Reservations and fixed IPs are addressing evidence only, never identity.
- Addressing order: reservation/fixed IP for (address, MAC) → `dhcp_reserved`; active lease for (address, MAC) → `dhcp_dynamic`; complete DHCP scope covering the subnet → `static` (`outside_dhcp_range`), `static` (`no_lease_or_reservation`, only when that service's lease table was read completely) or `dhcp_dynamic` (`inside_dhcp_range`, service without a lease table); otherwise `unknown`. `static` is never a default.
- `dhcp_leases`, `dhcp_reservations`, `dhcp_scope` and `local_dns_names` stay off on an upgraded configuration and when `capabilities` is omitted.
- The dossier and agent prompts are unchanged in this plan.
- In this plan: at most one service of each kind, at most one `dns_queries` and one `traffic_counters` capability enabled.
- Tests use fakes; no live Pi-hole or UniFi. Run tests from `integrations/network-privacy` with `PYTHONPYCACHEPREFIX=build/python-cache python3 -m unittest discover -s tests -p '<file>' -v`.
- Before each commit: `python3 -m ruff format . && python3 -m ruff check . && python3 -m mypy src tests scripts`.

## Review Focus

1. **Pi-hole answers `config/dhcp` with 401/403/404 or a malformed body.** Expect an `addressing_unavailable` gap. It must not be reported as "DHCP off" or as an incomplete scope that lets `static` be inferred. Test in Task 9.
2. **The lease read fails or hits its limit while the DHCP scope reads fine.** That scope is then not "lease-complete", so in-range addresses without a lease are `unknown`, not `static`. Test in Task 8 and Task 10.
3. **One selected IP appears in two leases with different MACs, or the lease and the network table disagree.** Identity: the address is ambiguous. Addressing: `unknown`. Both carry an issue. Expiry 0 is kept, a past expiry is dropped. Test in Task 10.
4. **The configuration uses both legacy `pihole`/`unifi` keys and `sources`, names an unknown capability, names a capability that kind does not offer (`unifi` + `dhcp_leases`), or reuses one credential variable across services.** Decode rejects it with a fixed message. Test in Task 2.
5. **Two sources name different MACs for one IP after the port.** The issue strings are byte-identical to today's (`identity_conflict: UniFi and Pi-hole disagreed on a selected IP's MAC address`, `unifi_ambiguous: …`), so upgrading does not produce "coverage changed". Test in Task 4 and Task 6.

---

## File structure

| File | Responsibility |
|---|---|
| `src/plowshare_privacy/contracts.py` (modify) | `Capability`, `EvidenceKind`, `IDENTITY_SOURCES`, `ServicePlan`, `CollectionPlan.sources` + legacy accessors, decoding, `DeviceIdentity.evidence`/`service` |
| `src/plowshare_privacy/identity_merge.py` (create) | Pure precedence merge of identity samples |
| `src/plowshare_privacy/addressing.py` (create) | Addressing facts, `AddressAssignment`, and the one function that decides each address's mode |
| `src/plowshare_privacy/source_set.py` (create) | Typed capability bindings the collector consumes |
| `src/plowshare_privacy/connectors.py` (create) | Registry: one `Connector` per kind; `bind(plan) -> SourceSet`; credential and scope metadata |
| `src/plowshare_privacy/collection.py` (modify) | `NetworkCollector(collector, plan, sources)`; generic `scope_fingerprint` |
| `src/plowshare_privacy/collector_factory.py` (modify) | Delegates to `connectors.bind` |
| `src/plowshare_privacy/pihole.py` (modify) | Capability gating; `offered()`; DHCP leases; local DNS names |
| `src/plowshare_privacy/unifi.py` (modify) | Per-service identity+inventory merge moves here from the collector |
| `src/plowshare_privacy/system_view.py` (modify) | Credential redaction from the registry |
| `src/plowshare_privacy/console.py`, `web/app.js` (modify) | `PiHoleCheck.dhcp` reported in the connection test |
| `src/plowshare_privacy/bootstrap.py`, `cli.py` (modify) | `enable-capability` under the scope-change fence |
| `tests/support.py` (modify) | `with_sources(plan, *, pihole=None, unifi=None)` helper |
| `tests/test_sources_config.py`, `tests/test_identity_merge.py`, `tests/test_connectors.py`, `tests/test_scope_fingerprint.py`, `tests/test_addressing.py`, `tests/test_pihole_dhcp.py`, `tests/test_unifi_addressing.py` (create) | New tests |

---

## Stage 1: registry and configuration, no behaviour change

### Task 1: Provenance fields on `DeviceIdentity`

**Files:**
- Modify: `src/plowshare_privacy/contracts.py:180-243` (`DeviceIdentity`), `:856-872` (`Evidence.encode`)
- Test: `tests/test_evidence_contract.py`

**Interfaces:**
- Produces:
  - `EvidenceKind = Literal["configured", "neighbour_table", "dhcp_lease", "controller_client", "managed_device", "local_dns"]`
  - `IDENTITY_SOURCES: frozenset[str] = frozenset({"configured", "pihole-v6", "unifi"})`
  - `DeviceIdentity.evidence: EvidenceKind | None = None`, `DeviceIdentity.service: str | None = None` (last two fields, defaulted)
  - `def evidence_kind(identity: DeviceIdentity) -> EvidenceKind`. Returns `identity.evidence` when set; otherwise maps `unifi` → `controller_client`, `pihole-v6` → `neighbour_table`, `configured` → `configured`.

- [ ] **Step 1: Write failing tests** in `tests/test_evidence_contract.py`:
  - `test_identity_without_provenance_encodes_exactly_as_before`: decode a v1 snapshot with one `pihole-v6` device, then assert `Evidence.decode(raw).encode()` equals the compact JSON of `raw`, and that neither `"evidence"` nor `"service"` appears in it.
  - `test_identity_provenance_round_trips`: a device with `"evidence": "dhcp_lease", "service": "home-dns"` decodes and re-encodes with both keys.
  - `test_identity_provenance_is_validated`: `"evidence": "router"` raises `ValueError`. A `service` value that is not an identifier (`"a b"`, or 65 characters) raises. `"evidence": "configured"` on a `pihole-v6` source raises.
  - `test_evidence_kind_maps_legacy_sources`: `evidence_kind` returns the three mappings above.
- [ ] **Step 2: Run** `python3 -m unittest discover -s tests -p 'test_evidence_contract.py' -v`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** Add both fields as optional keys in `DeviceIdentity.decode`. Validate `service` with the existing `identifier()` helper (max 64). Replace the inline source tuple with `IDENTITY_SOURCES`. In `Evidence.encode`, pop `evidence` and `service` from each encoded device when they are `None`, so legacy text is byte-identical.
- [ ] **Step 4: Run** the same command plus `-p 'test_pihole.py'` (`test_legacy_receipts_keep_exact_encoding_and_scope_fingerprint`). Expected: PASS.
- [ ] **Step 5: Commit** `feat(privacy): optional evidence kind and service on device identities`.

### Task 2: `ServicePlan` and `CollectionPlan.sources`

**Files:**
- Modify: `src/plowshare_privacy/contracts.py:553-570` (`CollectionPlan`), `:680-800` (`Configuration.decode`)
- Modify: `tests/support.py`; mechanical update of the 23 `pihole=` / `unifi=` / `CollectionPlan(` sites in `tests/` (listed by `grep -rn "pihole=\|unifi=\|CollectionPlan(" tests`)
- Test: `tests/test_sources_config.py`

**Interfaces:**
- Produces:
  - `Capability = Literal["network_table", "dns_queries", "dhcp_leases", "dhcp_reservations", "dhcp_scope", "local_dns_names", "controller_clients", "managed_inventory", "traffic_counters"]`
  - `OPT_IN_CAPABILITIES: frozenset[Capability] = frozenset({"dhcp_leases", "dhcp_reservations", "dhcp_scope", "local_dns_names"})`
  - `ConnectorKind = Literal["pihole", "unifi"]`
  - `KIND_CAPABILITIES: Mapping[ConnectorKind, frozenset[Capability]]`, with `pihole` → {network_table, dns_queries, dhcp_leases, dhcp_reservations, dhcp_scope, local_dns_names} and `unifi` → {controller_clients, managed_inventory, traffic_counters, dhcp_reservations, dhcp_scope}. UniFi's two DHCP capabilities require `trafficSite` (decode error otherwise).
  - `def legacy_capabilities(kind: ConnectorKind, settings: PiHolePlan | UniFiPlan) -> frozenset[Capability]`. Pi-hole → {network_table, dns_queries}. UniFi → {controller_clients, managed_inventory}, plus traffic_counters when `settings.traffic_site` is set.
  - `@dataclass(frozen=True) class ServicePlan: kind: ConnectorKind; id: str; capabilities: frozenset[Capability]; settings: PiHolePlan | UniFiPlan; legacy: bool = False`
  - `CollectionPlan.sources: tuple[ServicePlan, ...] = ()`. This replaces the `pihole` and `unifi` fields. Read-only properties `pihole -> PiHolePlan | None` and `unifi -> UniFiPlan | None` return the settings of that kind's single service. Mark them in a docstring as stage-1 accessors, to be removed by the stage-3 plan.
  - Test helper `with_sources(plan: CollectionPlan, *, pihole: PiHolePlan | None = None, unifi: UniFiPlan | None = None) -> CollectionPlan` in `tests/support.py`. It builds legacy services (`id` = kind, `legacy=True`, `legacy_capabilities`).

- [ ] **Step 1: Write failing tests** in `tests/test_sources_config.py` (decode through `Configuration.decode` with a fixture row built like `support.configuration`):
  - `test_legacy_keys_become_legacy_services`: `collection.pihole` yields one `ServicePlan("pihole", "pihole", {network_table, dns_queries}, …, legacy=True)`, and `plan.pihole` returns its settings. UniFi with `trafficSite` yields three capabilities.
  - `test_sources_list_decodes`: a `sources` entry with `"id": "home-dns"` and `"capabilities": ["network_table", "dns_queries", "dhcp_leases"]` decodes with `legacy=False`. Omitting `capabilities` enables `KIND_CAPABILITIES[kind] - OPT_IN_CAPABILITIES`.
  - `test_rejects_invalid_sources` (`subTest` per case, each `ValueError`): both `pihole` and `sources` present; unknown kind; unknown capability; `unifi` with `dhcp_leases`; `unifi` with `dhcp_scope` but no `trafficSite`; duplicate `id`; two services of one kind ("one service per kind until multi-service support"); two `dns_queries`; `sources` combined with `observationsFile` while `dns_queries` is enabled; the same credential variable used by Pi-hole and UniFi.
  - `test_dns_profiles_require_dns_queries`: `dnsProfiles: true` with a Pi-hole whose capabilities omit `dns_queries` is rejected.
- [ ] **Step 2: Run** `-p 'test_sources_config.py'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ServicePlan.decode(value: object) -> ServicePlan`, which dispatches `settings` to `PiHolePlan.decode` / `UniFiPlan.decode`. Change `Configuration.decode` to build `sources` from either form. Move the existing cross-field rules (DNS profiles, observation file, distinct credentials) onto capabilities. Replace `CollectionPlan`'s two fields with `sources` and the two properties. Update test call sites to use `with_sources`.
- [ ] **Step 4: Run** the full suite: `python3 -m unittest discover -s tests -p 'test_*.py'`. Expected: PASS, with only constructor call sites changed in the existing tests.
- [ ] **Step 5: Commit** `feat(privacy): configure external services as a typed sources list`.

### Task 3: Generic `scope_fingerprint`

**Files:**
- Modify: `src/plowshare_privacy/collection.py:46-79`
- Test: `tests/test_scope_fingerprint.py`

**Interfaces:**
- Consumes: `ServicePlan`, `legacy_capabilities` (Task 2).
- Produces: `scope_fingerprint(plan: CollectionPlan) -> str` (same signature).

- [ ] **Step 1: Pin today's values before changing anything.** On the commit before Task 2, compute `scope_fingerprint` for five plans built from `support.configuration` and add them as literal hex constants to `tests/test_scope_fingerprint.py`. The five plans are: no sources; Pi-hole only; UniFi only; both; UniFi with `gateway` and `trafficSite`. Each test (`test_<case>_fingerprint_is_unchanged`) asserts equality.
- [ ] **Step 2: Add** `test_sources_form_matches_legacy_form`: the same settings written as a `sources` list with ids `home-dns`/`gw` and legacy capabilities give the same fingerprint. Add `test_enabling_dhcp_changes_scope`: adding `dhcp_leases` changes the fingerprint. Add `test_traffic_capability_and_ids_do_not_change_scope`.
- [ ] **Step 3: Run.** Expected: the pinned tests PASS on the old code, then FAIL after Task 2 until Step 4.
- [ ] **Step 4: Implement.** Per-kind scope-field rules move to `connectors.py` in Task 5; until then, a private `_scope_settings(service) -> dict[str, object]` in `collection.py` reproduces today's Pi-hole/UniFi field handling (stringify paths, drop `gateway`, `traffic_site`, and `certificate_sha256` when absent). The merge rule:

```python
values = asdict(plan) minus sources (existing pops unchanged)
scoped = [(s, s.capabilities - {"traffic_counters"}) for s in plan.sources]
if all(caps == legacy_capabilities(s.kind, s.settings) - {"traffic_counters"} for s, caps in scoped):
    for s, _ in scoped:
        values[s.kind] = _scope_settings(s)          # exactly today's keys
else:
    values["sources"] = sorted(
        ({"kind": s.kind, "capabilities": sorted(caps), "settings": _scope_settings(s)} for s, caps in scoped),
        key=lambda row: json.dumps(row, sort_keys=True),
    )                                                 # ids never enter scope
```
- [ ] **Step 5: Run** `-p 'test_scope_fingerprint.py'` and `-p 'test_pihole.py'`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(privacy): keep scope fingerprints stable across the sources form`.

### Task 4: Identity precedence merge

**Files:**
- Create: `src/plowshare_privacy/identity_merge.py`
- Test: `tests/test_identity_merge.py`

**Interfaces:**
- Consumes: `evidence_kind`, `IdentitySample`.
- Produces:
  - `PRECEDENCE: tuple[EvidenceKind, ...] = ("managed_device", "controller_client", "dhcp_lease", "neighbour_table", "local_dns")`
  - `@dataclass(frozen=True) class IdentityMerge: devices: tuple[DeviceIdentity, ...]; conflicts: frozenset[str]; ambiguous: frozenset[str]`
  - `def merge_identities(samples: Sequence[IdentitySample], scope: frozenset[str]) -> IdentityMerge`. For each address: if records name different non-null MACs, the address goes into `conflicts` and gets no device. Otherwise the record ranked highest by `PRECEDENCE` wins whole (no field coalescing). `ambiguous` is the union of the samples' `ambiguous_addresses`, and those addresses get no device. Raises `ValueError("Identity source returned invalid or out-of-scope addresses")` on duplicate addresses within one sample or addresses outside `scope`. `local_dns` records never win against a MAC-bearing record and never conflict.

- [ ] **Step 1: Write failing tests:** `test_higher_precedence_wins_whole_record` (a UniFi controller record without vendor beats a Pi-hole table record with vendor; the result has no vendor, as today); `test_managed_device_beats_controller_client`; `test_mac_disagreement_conflicts_regardless_of_rank`; `test_ip_only_record_never_conflicts`; `test_ambiguous_addresses_get_no_device`; `test_out_of_scope_or_duplicate_rejected`.
- [ ] **Step 2: Run** `-p 'test_identity_merge.py'`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** `merge_identities`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(privacy): one precedence rule for merging identity evidence`.

### Task 5: Connector registry and `SourceSet`

**Files:**
- Create: `src/plowshare_privacy/source_set.py`, `src/plowshare_privacy/connectors.py`
- Modify: `src/plowshare_privacy/pihole.py:121-135` (constructor takes capabilities), `src/plowshare_privacy/unifi.py:84-125` (owns its identity+inventory merge)
- Test: `tests/test_connectors.py`

**Interfaces:**
- Produces in `source_set.py`:
  - `class PresenceReader(Protocol): async def presence(self, targets: tuple[str, ...], observed_at: str) -> IdentitySample`
  - `class DnsActivityReader(Protocol): async def activity(self, targets: tuple[str, ...], since: str, observed_at: str) -> tuple[tuple[QuerySample, ...], tuple[str, ...]]`
  - `class TrafficReader(Protocol): async def traffic(self, targets: tuple[str, ...]) -> tuple[tuple[TrafficCounter, ...], tuple[str, ...]]`
  - `@dataclass(frozen=True) class Bound(Generic[R]): service: str; kind: ConnectorKind; reader: R`
  - `@dataclass(frozen=True) class SourceSet`:
    - `device_reads: tuple[Bound[DeviceDataSource], ...] = ()`: one session per full collection, returning identity and/or DNS
    - `identity: tuple[Bound[DeviceIdentitySource], ...] = ()`: already merged per service
    - `presence: tuple[Bound[PresenceReader], ...] = ()`
    - `dns_activity: Bound[DnsActivityReader] | None = None`
    - `traffic: Bound[TrafficReader] | None = None`    - `product: ManagedProductLookup | None = None`
    - `managed: tuple[ManagedInventory, ...] = ()`: for `recently_managed`
    - property `confirms_identity -> bool`
- Produces in `connectors.py`:
  - `@dataclass(frozen=True) class SettingField: key: str; label: str; kind: Literal["url", "text", "integer", "secret_file", "environment", "certificate_pin"]; required: bool; help: str`
  - `@dataclass(frozen=True) class Connector`, with fields:
    - `kind: ConnectorKind; display_name: str; source: str` (the `IDENTITY_SOURCES` value)
    - `capabilities: frozenset[Capability]; settings_fields: tuple[SettingField, ...]`
    - `scope_settings: Callable[[PiHolePlan | UniFiPlan], dict[str, object]]`
    - `credential_environments: Callable[[PiHolePlan | UniFiPlan], tuple[str, ...]]`
    - `bind: Callable[[ServicePlan], SourceSet]`
  - `REGISTRY: Mapping[ConnectorKind, Connector]` (`pihole` display "Pi-hole", source "pihole-v6"; `unifi` display "UniFi", source "unifi")
  - `def bind(plan: CollectionPlan) -> SourceSet` (merges each service's `SourceSet`)
- `PiHoleV6(plan, *, capabilities: frozenset[Capability] = frozenset({"network_table", "dns_queries"}), service: str = "pihole", application_password: str | None = None)`: `_read` skips `network/devices` without `network_table` and `queries` without `dns_queries`.
- `UniFiNetwork.identify` returns the merged controller+inventory sample when `managed_inventory` is enabled, keeping the issue `unifi_ambiguous: connected and adopted inventory disagree on a watched address` verbatim.

- [ ] **Step 1: Write failing tests:**
  - `test_every_kind_is_registered`: `set(REGISTRY) == set(KIND_CAPABILITIES)`; each `capabilities` equals `KIND_CAPABILITIES[kind]`; each `source in IDENTITY_SOURCES`.
  - `test_bind_follows_enabled_capabilities`: a Pi-hole with only `network_table` gives `device_reads` and `presence` but `dns_activity is None`; UniFi without `traffic_counters` gives `traffic is None`.
  - `test_pihole_without_dns_capability_never_requests_queries`: reuse the `test_pihole.py` `TestServer` fake and assert no `/api/queries` call.
  - `test_credential_environments_listed`: returns Pi-hole `passwordEnvironment` (when set) and UniFi `apiKeyEnvironment`.
  - `test_scope_settings_matches_task3_helper`: then delete the private helper from Task 3 and call `REGISTRY[kind].scope_settings`.
- [ ] **Step 2: Run** `-p 'test_connectors.py'`. Expected: FAIL.
- [ ] **Step 3: Implement** both modules and the constructor and capability changes. Move the controller+inventory loop from `NetworkCollector._identity_sample` into `UniFiNetwork` using `merge_identities`.
- [ ] **Step 4: Run** `-p 'test_connectors.py'`, `-p 'test_unifi.py'`, `-p 'test_pihole.py'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(privacy): connector registry binding capabilities to typed readers`.

### Task 6: Collector consumes `SourceSet`

**Files:**
- Modify: `src/plowshare_privacy/collection.py:140-640`, `src/plowshare_privacy/collector_factory.py`, `src/plowshare_privacy/cli.py:490-500`
- Modify (call sites only): `tests/test_deep_scan_web.py`, `tests/test_collection_web.py`, `tests/test_pihole.py`, `tests/test_monitor.py`, `tests/test_unifi.py`, `tests/test_presence.py`
- Test: `tests/test_connectors.py`

**Interfaces:**
- Consumes: `SourceSet`, `bind`, `merge_identities`, `REGISTRY`.
- Produces:
  - `NetworkCollector(collector: str, plan: CollectionPlan, sources: SourceSet = SourceSet())`, which raises `ValueError` if a configured service has no binding
  - `NetworkCollector.sources: SourceSet`; `NetworkCollector.confirms_identity` delegates to it
  - `NetworkCollector.product_lookup -> ManagedProductLookup | None` (replaces `collector.identity_source` in `cli.py`)
  - `configured_collector(collector, plan) = NetworkCollector(collector, plan, bind(plan))`

- [ ] **Step 1: Write failing tests:**
  - `test_conflict_issue_text_is_unchanged`: fake Pi-hole and UniFi readers disagree on a MAC. Assert the issue equals `"identity_conflict: UniFi and Pi-hole disagreed on a selected IP's MAC address"` and the address keeps its configured record. Build the text from `REGISTRY[...].display_name` ordered by precedence.
  - `test_two_identity_services_merge_by_precedence`: two fake `Bound[DeviceIdentitySource]` entries with different kinds, same MAC. The higher precedence wins.
  - `test_collector_contains_no_vendor_names`: read `collection.py` source text and assert it contains neither `pihole` nor `unifi` (case-insensitive) outside comments that cite issue history.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Replace `device_source`, `identity_source`, `pihole_presence`, `pihole_activity` and `traffic_source` with loops over `self.sources`. Replace the merges in `collect`, `confirm_identity` and `collect_presence` with `merge_identities`. Keep `_infrastructure` using `sources.managed`. Update test call sites to `SourceSet(...)`.
- [ ] **Step 4: Run** the full suite. Expected: PASS, with no assertion changes in existing tests.
- [ ] **Step 5: Commit** `refactor(privacy): collector reads evidence through bound capabilities`.

### Task 7: Credential redaction from the registry

**Files:**
- Modify: `src/plowshare_privacy/system_view.py:80-125`, `src/plowshare_privacy/service.py:112`
- Test: `tests/test_system_view.py`

**Interfaces:**
- Consumes: `REGISTRY[kind].credential_environments`.

- [ ] **Step 1: Write failing test** `test_every_service_credential_is_redacted`: a plan with Pi-hole (environment password) and UniFi. The system view's redaction list contains both values, taken from the registry rather than from fixed fields. Keep the existing `PiHoleConnection`/`UniFiConnection` display records (stage 3 replaces them).
- [ ] **Step 2: Run** `-p 'test_system_view.py'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Build `credentials` by iterating `plan.sources`.
- [ ] **Step 4: Run** the full suite plus `node --test tests/web_*.test.cjs`. Expected: PASS.
- [ ] **Step 5: Commit** `refactor(privacy): redact credentials for every configured service`.

---

## Stage 2: Addressing, Pi-hole DHCP and local DNS names

### Task 8: Addressing bucket

**Files:**
- Create: `src/plowshare_privacy/addressing.py`
- Modify: `src/plowshare_privacy/contracts.py` (`Snapshot.addressing`, decode, `Evidence.encode`), `src/plowshare_privacy/collection.py` (`changes`, `collect`), `src/plowshare_privacy/source_set.py`, `src/plowshare_privacy/pihole.py:41-46` (`DeviceData.addressing`)
- Test: `tests/test_addressing.py`, `tests/test_evidence_contract.py`

**Interfaces:**
- Produces in `addressing.py`. These are the decoded forms the connectors build; `AddressAssignment` lives in `contracts.py` because `Snapshot` holds it.
  - `@dataclass(frozen=True) class Lease: address: str; mac: str; hostname: str | None; expires_at: str | None; service: str` (`expires_at=None` = no expiry)
  - `@dataclass(frozen=True) class Reservation: address: str; mac: str; basis: Literal["dhcp_reservation", "controller_fixed_ip"]; service: str`
  - `@dataclass(frozen=True) class DhcpScope: service: str; network: ipaddress.IPv4Network; start: str; end: str; complete: bool; lease_table: Literal["complete", "incomplete", "none"]`. `complete` means DHCP is active and the settings were read fully; `none` means the service has no lease table (UniFi).
  - `@dataclass(frozen=True) class AddressingSample: leases: tuple[Lease, ...] = (); reservations: tuple[Reservation, ...] = (); scopes: tuple[DhcpScope, ...] = (); issues: tuple[str, ...] = ()`
  - `def assign_addresses(samples: Sequence[AddressingSample], devices: Mapping[str, DeviceIdentity], targets: tuple[str, ...], observed_at: str) -> tuple[tuple[AddressAssignment, ...], tuple[str, ...]]`. Implements the Global Constraints order. Leases with `expires_at` at or before `observed_at` are ignored. It emits `addressing_conflict: a DHCP reservation names a different device than the one holding the address` and `addressing_conflict: configured address assignments disagree on a selected address`. Raises `ValueError` for facts outside `targets`.
- Produces in `contracts.py`:
  - `AssignmentMode = Literal["dhcp_dynamic", "dhcp_reserved", "static", "unknown"]`
  - `AssignmentBasis = Literal["dhcp_lease", "dhcp_reservation", "controller_fixed_ip", "inside_dhcp_range", "outside_dhcp_range", "no_lease_or_reservation"]`
  - `@dataclass(frozen=True) class AddressAssignment: address: str; mac: str | None; mode: AssignmentMode; basis: AssignmentBasis | None; services: tuple[str, ...]; expires_at: str | None`
  - `Snapshot.addressing: tuple[AddressAssignment, ...] = ()`, omitted from `Evidence.encode` when empty. It is an optional decode key, at most `MAX_TARGETS` entries with unique addresses.
- Produces in `source_set.py`: `class AddressingReader(Protocol): async def addressing(self, targets: tuple[str, ...], observed_at: str) -> AddressingSample`; `SourceSet.addressing: tuple[Bound[AddressingReader], ...] = ()`.
- `DeviceData.addressing: AddressingSample | None = None`. The collector gathers this from `device_reads` and calls each `SourceSet.addressing` reader. It then calls `assign_addresses` with the merged identities, but only when at least one addressing capability is enabled; otherwise `Snapshot.addressing` stays `()`.

- [ ] **Step 1: Write failing tests** in `tests/test_addressing.py`. Each builds facts directly, with no HTTP.
  - `test_reservation_for_holder_is_reserved` (`dhcp_reserved`, `dhcp_reservation`)
  - `test_controller_fixed_ip_is_reserved` (`controller_fixed_ip`)
  - `test_active_lease_is_dynamic_and_keeps_expiry`
  - `test_expired_lease_is_ignored`
  - `test_outside_range_with_complete_scope_is_static`
  - `test_inside_range_complete_lease_table_without_lease_is_static` (`no_lease_or_reservation`)
  - `test_inside_range_incomplete_lease_table_is_unknown`
  - `test_inside_range_without_lease_table_is_dynamic` (`inside_dhcp_range`)
  - `test_no_scope_or_incomplete_scope_is_unknown_never_static`
  - `test_address_without_mac_is_unknown`
  - `test_reservation_for_other_mac_is_conflict_and_holder_still_assessed`
  - `test_disagreeing_configured_sources_are_unknown_with_conflict`
  - `test_mixed_network_keeps_each_mode`: three addresses come out reserved, dynamic and static.
  - `test_out_of_scope_facts_rejected`
- [ ] **Step 2: Write failing tests** in `tests/test_evidence_contract.py`:
  - `test_snapshot_without_addressing_encodes_exactly_as_before`
  - `test_addressing_round_trips_and_validates`: rejects a bad mode, a duplicate address, or a basis that doesn't fit the mode (for example `static` with `dhcp_lease`).
  - `test_assignment_change_is_reported`: same address and MAC, `dhcp_reserved` → `static`, gives `"Address assignment at 192.0.2.12 changed: dhcp_reserved -> static"`. There is no line when either side is `unknown`, when the MAC changed, or when either collection has an `addressing_` issue.
- [ ] **Step 3: Run** `-p 'test_addressing.py'` and `-p 'test_evidence_contract.py'`. Expected: FAIL.
- [ ] **Step 4: Implement** `addressing.py`, the contract fields, the `changes` line, and the collector assembly (one fake `AddressingReader` test in `tests/test_connectors.py`: `test_collector_records_addressing_only_when_enabled`).
- [ ] **Step 5: Run** those files plus the full suite. Expected: PASS.
- [ ] **Step 6: Commit** `feat(privacy): record how each address was assigned`.

### Task 9: Pi-hole DHCP check and scope

**Files:**
- Modify: `src/plowshare_privacy/pihole.py`, `src/plowshare_privacy/console.py:50-60,462-491`, `src/plowshare_privacy/web/app.js` (Pi-hole connection-test result text)
- Test: `tests/test_pihole_dhcp.py`, `tests/web_settings.test.cjs`

**Interfaces:**
- Consumes: `DhcpScope`, `Reservation`, `AddressingSample` (Task 8).
- Produces:
  - `@dataclass(frozen=True) class PiHoleDhcp: active: bool | None; scope: DhcpScope | None; reservations: tuple[Reservation, ...]; issues: tuple[str, ...]` (`active=None` = could not be read)
  - `async def PiHoleV6._dhcp(self, session: aiohttp.ClientSession, sid: str, targets: tuple[str, ...]) -> PiHoleDhcp`: one `GET config/dhcp` per session. Decodes `active`, `start`, `end`, `router` and `netmask` into a `DhcpScope`, and the reservation list (`hosts`, limit 512) into `Reservation`s within `targets`.
  - `async def PiHoleV6.offered(self) -> PiHoleDhcp` for the connection test (its own session)
  - `PiHoleCheck.dhcp: bool | None = None`, set by `connect_pihole` when testing

- [ ] **Step 1: Verify response shapes.** Read the Pi-hole v6 API documentation (`/api/docs` on an instance, or the FTL OpenAPI spec) for `GET /api/config/dhcp`, `GET /api/dhcp/leases` and `GET /api/config/dns/hosts`. Confirm the JSON paths:
  - `config.dhcp.{active, start, end, router, netmask, hosts}`, including the format of a `hosts` entry (assumed `"MAC,IP[,NAME]"`)
  - `leases[].{expires, name, hwaddr, ip}`, and what `expires` is for a reserved address (assumed `0`)
  - `config.dns.hosts[]` as `"IP NAME…"` strings

  Record any difference in the spec's "Left to the builder" section and in this plan's fixtures before continuing. Do not query the owner's Pi-hole without asking.
- [ ] **Step 2: Write failing tests** with the `TestServer` fake (add `/api/config/dhcp`):
  - `test_dhcp_scope_and_reservations_decoded`: a /24 network built from `router` and `netmask`; only reservations inside `targets` are kept.
  - `test_dhcp_off_is_not_an_issue`: `active: false` gives `active=False`, `scope=None` and `issues == ()`.
  - `test_unreadable_dhcp_settings_are_a_gap` (`subTest` for 401, 403, 404, `{"config": {}}`): `active is None`, `scope is None`, plus the issue `"addressing_unavailable: Pi-hole DHCP settings could not be read"`. The issue text contains no URL or body.
  - `test_malformed_reservation_is_partial`: `"addressing_partial: Pi-hole returned an invalid DHCP reservation"`, and the scope's `complete` is still true.
  - `test_reservations_skipped_when_capability_off`
  - `web_settings.test.cjs`: a connection-test result with `dhcp: false` renders "DHCP: off on this Pi-hole"; `true` renders "DHCP: on"; `null` renders "DHCP: could not be checked".
- [ ] **Step 3: Run** `-p 'test_pihole_dhcp.py'` and `node --test tests/web_settings.test.cjs`. Expected: FAIL.
- [ ] **Step 4: Implement** `_dhcp`, `offered()`, the console field and the app.js text. During a full collection with `dhcp_scope` or `dhcp_reservations` enabled, put the result into `DeviceData.addressing`.
- [ ] **Step 5: Run** both. Expected: PASS.
- [ ] **Step 6: Commit** `feat(privacy): read Pi-hole DHCP scope and reservations`.

### Task 10: Pi-hole DHCP leases

**Files:**
- Modify: `src/plowshare_privacy/pihole.py:170-255` (add `_leases`), `:374-470` (`_read`)
- Test: `tests/test_pihole_dhcp.py`

**Interfaces:**
- Consumes: `PiHoleDhcp`, `Lease`, `merge_identities`.
- Produces: per active lease in scope, both of these:
  - a `Lease` in `DeviceData.addressing`
  - an identity with `source="pihole-v6"`, `evidence="dhcp_lease"`, `service=<id>`, `last_seen=None` and `observed_at=<collection instant>`, with vendor copied from the network-table record of the same MAC when present

  The scope's `lease_table` is `"complete"` only when the lease read succeeded below its limit; otherwise `"incomplete"`.

- [ ] **Step 1: Write failing tests** (fake `/api/dhcp/leases`):
  - `test_active_lease_identifies_and_addresses_selected_address`
  - `test_leases_skipped_when_capability_off_or_dhcp_inactive`: no `/api/dhcp/leases` request either way
  - `test_expired_lease_ignored_zero_expiry_kept`
  - `test_lease_and_table_disagree_is_ambiguous`: issue `"identity_ambiguous: Pi-hole DHCP lease and network table name different devices for a selected address"`
  - `test_two_leases_for_one_address_is_ambiguous`
  - `test_out_of_scope_leases_never_enter_evidence`
  - `test_lease_limit_marks_partial_and_incomplete`: 1024 leases gives `"addressing_partial: Pi-hole DHCP lease limit reached"` and `lease_table == "incomplete"`
  - `test_failed_lease_read_marks_incomplete`
  - `test_leases_do_not_create_presence`: `presence()` makes no lease request and returns no lease-only sighting
  - `test_lease_identity_wins_over_table_with_same_mac`: evidence is `dhcp_lease`, the hostname comes from the lease, and the vendor comes from the table
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** In `_read`, when `since is None` and `dhcp_leases` is enabled and `_dhcp` reports active, call `GET dhcp/leases` (limit 1024). Decode into `Lease` and `DeviceIdentity` using `_devices`' MAC/IP validation, then `merge_identities` with the table sample. `presence()` and `activity()` never read leases.
- [ ] **Step 4: Run** `-p 'test_pihole_dhcp.py'` and `-p 'test_pihole.py'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(privacy): use Pi-hole DHCP leases for identity and addressing`.

### Task 11: Local DNS names

**Files:**
- Modify: `src/plowshare_privacy/pihole.py`
- Test: `tests/test_pihole_dhcp.py`

**Interfaces:**
- Produces: for a selected address with a local record, the winning identity's `hostname` is set to the local name only when that identity has no hostname. An address without another identity gets `DeviceIdentity(address, "ip:"+address, None, name, None, None, "pihole-v6", observed, None, evidence="local_dns", service=id)`. Never a MAC.

- [ ] **Step 1: Write failing tests:**
  - `test_local_name_fills_missing_hostname_only`
  - `test_local_name_never_asserts_mac_or_conflicts`
  - `test_malformed_host_lines_are_partial`: issue `"identity_partial: Pi-hole returned an invalid local DNS record"`
  - `test_names_skipped_when_capability_off`
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** parsing of `GET config/dns/hosts` (limit 512): split on whitespace, validate the IP, name ≤128 characters, first name wins. Apply it after the lease/table merge.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(privacy): name devices from Pi-hole local DNS records`.

### Task 12: UniFi fixed IPs and DHCP scope

**Files:**
- Modify: `src/plowshare_privacy/unifi.py` (next to `traffic`, `:193-235`, and `_traffic_url`, `:569`), `src/plowshare_privacy/connectors.py` (bind `addressing`)
- Test: `tests/test_unifi_addressing.py`

**Interfaces:**
- Consumes: `AddressingReader`, `Reservation`, `DhcpScope`.
- Produces: `async def UniFiNetwork.addressing(self, targets: tuple[str, ...], observed_at: str) -> AddressingSample`.
  - `GET {legacy}/api/s/{site}/stat/sta`: clients with `use_fixedip: true` and a `fixed_ip` in `targets` become `Reservation(basis="controller_fixed_ip")`.
  - `GET {legacy}/api/s/{site}/rest/networkconf`: networks with `dhcpd_enabled: true` become `DhcpScope(lease_table="none")`, with the network from `ip_subnet` and the range from `dhcpd_start`/`dhcpd_stop`.
  - Limits: 1024 clients, 64 networks.

- [ ] **Step 1: Verify** with the UniFi Network API documentation (or ask the owner to check a controller) that both legacy endpoints accept the API key read-only, and confirm the field names above. Record any difference in the spec. Do not call the owner's controller without asking.
- [ ] **Step 2: Write failing tests** with a `TestServer` fake modelled on `tests/test_unifi.py`:
  - `test_fixed_ip_client_becomes_reservation`
  - `test_dhcp_network_becomes_scope_without_lease_table`
  - `test_dhcp_disabled_network_is_no_scope_and_no_issue`
  - `test_unreadable_endpoints_are_addressing_unavailable` (401, 404, malformed): fixed text, no URL
  - `test_limits_mark_partial`
  - `test_skipped_when_capabilities_off`
  - `test_unifi_dhcp_with_pihole_dns_gives_inside_range_dynamic`: end to end through `NetworkCollector`, with a UniFi DHCP network plus a Pi-hole whose DHCP is off. A non-fixed client inside the range is `dhcp_dynamic` (`inside_dhcp_range`); a fixed client is `dhcp_reserved`; a client outside the range is `static`.
- [ ] **Step 3: Run** `-p 'test_unifi_addressing.py'`. Expected: FAIL.
- [ ] **Step 4: Implement**, reusing the traffic session and URL derivation.
- [ ] **Step 5: Run** that file plus `-p 'test_unifi.py'`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(privacy): read UniFi fixed IPs and DHCP ranges`.

### Task 13: Turning capabilities on

**Files:**
- Modify: `src/plowshare_privacy/bootstrap.py` (next to `connect_pihole`, `:740-790`), `src/plowshare_privacy/cli.py`, `SETUP.md`, `README.md` (Pi-hole section near line 108)
- Test: `tests/test_bootstrap.py`

**Interfaces:**
- Produces: `def enable_capability(setup: Setup, service: str, capability: Capability, enabled: bool) -> None`; CLI `enable-capability --service <id> --capability <name> [--off]`.

- [ ] **Step 1: Write failing tests:**
  - `test_enable_dhcp_rewrites_to_sources_form_under_scope_fence`: a legacy config gets rewritten with `sources`, `id` `pihole` and the added capability; `prepare_scope_change`/`complete_scope_change` are called around the write; the fingerprint changes.
  - `test_rejects_capability_the_kind_does_not_offer`
  - `test_rejects_unifi_dhcp_without_traffic_site`
  - `test_rejects_changed_configuration` (same check as `connect_pihole`)
  - `test_running_collector_refused`
- [ ] **Step 2: Run** `-p 'test_bootstrap.py'`. Expected: FAIL.
- [ ] **Step 3: Implement**, mirroring `connect_pihole`'s fence and private write. Document in `SETUP.md` and `README.md`:
  - the command
  - the four assignment modes and what each basis means
  - that `static` is only claimed from a complete DHCP scope
  - that leases are identity and addressing evidence, not presence, and reservations are intent only
- [ ] **Step 4: Run** the full gate: `python3 scripts/check.py`. Expected: exits 0.
- [ ] **Step 5: Commit** `feat(privacy): enable or disable a service capability`.

---

## Not in this plan (stage 3 plan)

Settings UI and CLI prompts built from `SettingField`; showing each address's assignment mode in the device pane and Addressing in the dossier; `connect <kind>`; a generic Sources panel and per-capability status; health checks run through the registry instead of fixed lines in `console.py`; more than one service of one kind; vendor-neutral dossier fields (`dns_silent`) and agent prompt wording; removal of the `CollectionPlan.pihole` / `.unifi` accessors.
