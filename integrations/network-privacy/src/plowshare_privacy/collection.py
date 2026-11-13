"""Deterministic collection and comparison; no model, shell or Plowshare internals."""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
from collections import Counter
from dataclasses import asdict, replace
from datetime import datetime, timezone
from typing import Protocol

from .connectors import REGISTRY
from .contracts import (
    Capability,
    CollectionPlan,
    ConnectorKind,
    DeviceIdentity,
    DnsObservation,
    DnsWindow,
    Evidence,
    Snapshot,
    TcpObservation,
    evidence_kind,
    legacy_capabilities,
    load_json,
    private_ipv4,
    utc_now,
)
from .dns_activity import DnsActivityWindow
from .identity_merge import PRECEDENCE, merge_identities
from .identity_source import IdentitySample, ManagedProductLookup
from .limits import BATCH_SIZE, MAX_DNS_ROWS, MAX_EVIDENCE_BYTES
from .presence import MAX_ATTRIBUTION_GAP, PresenceSample, Sighting, epoch
from .source_set import SourceSet
from .traffic import TrafficCounter


class Collector(Protocol):
    """Read the deployed scope. Failures remain evidence, never an empty successful scan."""

    async def collect(
        self,
        scan_id: str,
        source_event: str,
        previous: Evidence | None,
        previous_revision: str | None,
    ) -> Evidence: ...


def scope_fingerprint(plan: CollectionPlan) -> str:
    values = asdict(plan)
    # Local presence sampling does not change full collection evidence or receipts.
    values.pop("quick_scan")
    values.pop("dns_profiles")
    values.pop("device_descriptions")
    # Default-enabled plans retain their historical fingerprints and journal identity.
    if plan.enabled:
        values.pop("enabled")
    if not plan.labels:
        values.pop("labels")
    # ``traffic_counters`` reads local presence data, like quick checks, so it
    # never changes full collection evidence or the receipt journal identity.
    values.pop("sources")
    scoped = [(s, s.capabilities - {"traffic_counters"}) for s in plan.sources]
    if all(
        caps == legacy_capabilities(s.kind, s.settings) - {"traffic_counters"}
        for s, caps in scoped
    ):
        # Services that read exactly what the former ``pihole``/``unifi`` keys
        # read keep those keys, so an untouched configuration keeps its
        # fingerprint and journal identity.
        for s, _ in scoped:
            values[s.kind] = REGISTRY[s.kind].scope_settings(s.settings)
    else:
        # Ids are labels; only what is read, and from where, defines scope.
        values["sources"] = sorted(
            (
                {
                    "kind": s.kind,
                    "capabilities": sorted(caps),
                    "settings": REGISTRY[s.kind].scope_settings(s.settings),
                }
                for s, caps in scoped
            ),
            key=lambda row: json.dumps(row, sort_keys=True),
        )
    values["observations_file"] = (
        str(plan.observations_file) if plan.observations_file else None
    )
    return hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()


def changes(
    previous: Evidence | None, current: Snapshot, scope: str, issues: tuple[str, ...]
) -> tuple[str, ...]:
    """Compare only equal scopes and observable values; missing evidence is not removal."""
    if previous is None or previous.scope != scope:
        return ("Baseline collection; no comparable previous evidence.",)
    before = {(item.address, item.port): item.status for item in previous.snapshot.tcp}
    difference = [
        f"TCP {item.address}:{item.port}: {before[(item.address, item.port)]} -> {item.status}"
        for item in current.tcp
        if (item.address, item.port) in before
        and item.status in {"open", "closed"}
        and before[(item.address, item.port)] in {"open", "closed"}
        and before[(item.address, item.port)] != item.status
    ]
    if set(previous.issues) != set(issues):
        difference.insert(
            0,
            "Collection coverage changed; inspect the current gaps against the previous evidence.",
        )
    old_devices = {device.address: device for device in previous.snapshot.devices}
    changed_identity = set()
    for device in current.devices:
        old = old_devices.get(device.address)
        if old is not None and old.mac and device.mac and old.mac != device.mac:
            changed_identity.add(device.address)
            difference.append(
                f"Device association at {device.address} changed: {old.mac} -> {device.mac}; compare address observations, not a presumed physical device."
            )
    # Query counts and absence are not compared: exports may cover different windows.
    if not any(issue.startswith("dns_") for issue in (*previous.issues, *issues)):
        known = {(item.device, item.domain) for item in previous.snapshot.dns}
        difference.extend(
            f"New observed DNS destination for {item.device}: {item.domain}"
            for item in current.dns
            if (item.device, item.domain) not in known
            and item.device not in changed_identity
        )
    return tuple(difference)


# Capabilities whose binding yields a reader tagged with its service id. A
# configured service enabling one of them must be bound; adopted inventory
# alone binds only an untagged lookup, and opt-in capabilities without a
# reader yet bind nothing.
_TAGGED_CAPABILITIES: frozenset[Capability] = frozenset(
    {"network_table", "dns_queries", "controller_clients", "traffic_counters"}
)


def _conflict_issue(kinds: tuple[ConnectorKind, ...]) -> str:
    """The issue for a MAC disagreement between ``kinds``, highest precedence first.

    Built from the registry's display names so the historical text ("UniFi and
    Pi-hole ...") is unchanged without naming a vendor here.
    """
    names = list(dict.fromkeys(REGISTRY[kind].display_name for kind in kinds))
    joined = (
        names[0] if len(names) == 1 else ", ".join(names[:-1]) + " and " + names[-1]
    )
    return "identity_conflict: " + joined + " disagreed on a selected IP's MAC address"


def _disagreeing_kinds(
    address: str, samples: list[tuple[ConnectorKind, IdentitySample]]
) -> tuple[ConnectorKind, ...]:
    """Kinds whose MAC-bearing records at ``address`` disagree, by precedence.

    Each kind ranks by its best record there, as ``merge_identities`` ranks
    records; ties keep sample order.
    """
    rank: dict[ConnectorKind, int] = {}
    for kind, sample in samples:
        for device in sample.devices:
            if device.address == address and device.mac:
                kind_rank = _rank(device)
                rank[kind] = min(rank.get(kind, kind_rank), kind_rank)
    return tuple(sorted(rank, key=rank.__getitem__))


def _rank(identity: DeviceIdentity) -> int:
    kind = evidence_kind(identity)
    return PRECEDENCE.index(kind) if kind in PRECEDENCE else len(PRECEDENCE)


def _device_sample(
    devices: tuple[DeviceIdentity, ...],
    issues: tuple[str, ...],
    targets: tuple[str, ...],
) -> IdentitySample:
    """A device read's associations, validated against the targets it was asked."""
    addresses = [device.address for device in devices]
    if len(set(addresses)) != len(addresses) or not set(addresses) <= set(targets):
        raise ValueError("Device source returned invalid or out-of-scope addresses")
    return IdentitySample(devices, frozenset(), issues)


class NetworkCollector:
    """Collect a configured scope through the readers bound from its services.

    The collector knows no vendor: every external read goes through
    ``sources``, and identity evidence from several services is combined by
    ``merge_identities``. A full collection reverts an address the services
    disagree on to its configured record and reports the conflict; a fresh
    identity confirmation instead leaves it ambiguous.
    """

    def __init__(
        self, collector: str, plan: CollectionPlan, sources: SourceSet = SourceSet()
    ):
        tagged = {
            bound.service
            for bound in (
                *sources.device_reads,
                *sources.identity,
                *sources.presence,
                *(b for b in (sources.dns_activity, sources.traffic) if b is not None),
            )
        }
        for service in plan.sources:
            if service.capabilities & _TAGGED_CAPABILITIES and service.id not in tagged:
                raise ValueError(
                    "Configured service " + service.id + " has no bound reader"
                )
        self.collector = collector
        self.plan = plan
        self.sources = sources

    @property
    def confirms_identity(self) -> bool:
        """Whether any bound capability can tie an address to a MAC."""
        return self.sources.confirms_identity

    @property
    def product_lookup(self) -> ManagedProductLookup | None:
        """The bound controller product lookup, if a service offers one."""
        return self.sources.product

    async def confirm_identity(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        """A fresh read of who holds ``targets``, from every source collection uses.

        Network-table reads (the device reads of services that also bind
        presence; their DNS rows are not used) come first, then each identity
        binding, merged by ``merge_identities``. An address on which the
        sources name different MACs is ambiguous and has no identity, so a
        caller can never trust one source over another that disagrees. Nothing
        is probed and no worker lock is needed. Raises ValueError when no source
        is bound or a source answers outside ``targets``.
        """
        if not self.confirms_identity:
            raise ValueError("No configured source can confirm identities")
        identities = await self._identity_samples(observed_at, targets)
        tables = {bound.service for bound in self.sources.presence}
        samples: list[IdentitySample] = []
        for bound in self.sources.device_reads:
            if bound.service in tables:
                data = await bound.reader.read(targets, observed_at)
                samples.append(_device_sample(data.devices, data.issues, targets))
        # Table associations first, so devices keep their first-seen order.
        samples.extend(sample for _, sample in identities)
        merge = merge_identities(samples, frozenset(targets))
        return IdentitySample(
            merge.devices,
            merge.ambiguous | merge.conflicts,
            tuple(
                dict.fromkeys(issue for sample in samples for issue in sample.issues)
            ),
        )

    async def _identity_samples(
        self, observed: str, targets: tuple[str, ...] | None = None
    ) -> list[tuple[ConnectorKind, IdentitySample]]:
        """Each identity binding's sample for ``targets`` (default: the plan's)."""
        scope_targets = self.plan.targets if targets is None else targets
        return [
            (bound.kind, await bound.reader.identify(scope_targets, observed))
            for bound in self.sources.identity
        ]

    async def _identity_sample(
        self, observed: str, targets: tuple[str, ...] | None = None
    ) -> IdentitySample:
        """Every identity binding's evidence for ``targets``, merged.

        Disagreeing services leave the address ambiguous. Raises ValueError
        when a binding answers outside ``targets``.
        """
        scope_targets = self.plan.targets if targets is None else targets
        samples = [s for _, s in await self._identity_samples(observed, scope_targets)]
        merge = merge_identities(samples, frozenset(scope_targets))
        return IdentitySample(
            merge.devices,
            merge.ambiguous | merge.conflicts,
            tuple(
                dict.fromkeys(issue for sample in samples for issue in sample.issues)
            ),
        )

    def _reads_dns(self) -> bool:
        """Whether a bound device read returns DNS rows (``dns_queries`` is enabled).

        Binding sets ``dns_activity`` only for a service with ``dns_queries``,
        and that same service's device read carries its query rows.
        """
        dns = self.sources.dns_activity
        return dns is not None and any(
            bound.service == dns.service for bound in self.sources.device_reads
        )

    def _infrastructure(self, identity: DeviceIdentity) -> bool:
        """Adopted gateways, switches and access points.

        They are network infrastructure, not DNS clients; profiling them would
        only produce a misleading "no DNS" result. A service whose adopted
        inventory is bound without connected clients marks nothing, because
        its inventory is never read.
        """
        return any(
            inventory.recently_managed(identity) for inventory in self.sources.managed
        )

    async def collect_presence(self, since: str) -> PresenceSample:
        """Sample positive evidence only, without building or publishing a full scan."""
        plan = self.plan
        if not (
            plan.mode == "tcp"
            and plan.enabled
            and plan.quick_scan
            and plan.targets
            and plan.ports
            and all(private_ipv4(address) for address in plan.targets)
        ):
            raise ValueError("Quick presence scans require enabled private monitoring")
        observed = utc_now()
        start, end = epoch(since), epoch(observed)
        connected = await self._identity_sample(observed)
        sightings = {
            identity.address: Sighting(identity.address, identity)
            for identity in connected.devices
        }
        issues = list(connected.issues)
        ambiguous = connected.ambiguous_addresses
        remaining = tuple(
            address
            for address in plan.targets
            if address not in sightings and address not in ambiguous
        )
        for bound in self.sources.presence:
            # Each table is asked only about addresses nothing has explained yet.
            remaining = tuple(
                address
                for address in remaining
                if address not in sightings and address not in ambiguous
            )
            if not remaining:
                break
            cached = await bound.reader.presence(remaining, observed)
            # Ordinary absence/staleness is expected when watching every address on a subnet.
            issues.extend(
                issue
                for issue in cached.issues
                if not issue.startswith(
                    ("identity_stale:", "identity_unavailable: some selected")
                )
            )
            eligible = frozenset(remaining)
            ambiguous |= cached.ambiguous_addresses & eligible
            for identity in cached.devices:
                if (
                    identity.address in eligible
                    and identity.address not in ambiguous
                    and identity.last_seen is not None
                    and start < epoch(identity.last_seen) <= end
                ):
                    sightings[identity.address] = Sighting(identity.address, identity)

        pending = tuple(address for address in plan.targets if address not in sightings)
        semaphore = asyncio.Semaphore(plan.concurrency)
        # Low-concurrency subnet plans use one probe to bound the time they hold
        # the ordinary collector's lock even if every address is silent.
        ports = plan.ports[: 1 if len(pending) > 64 * plan.concurrency else 2]

        async def responding(address: str) -> bool:
            async with semaphore:
                for port in ports:
                    if await _quick_connect(address, port, min(plan.timeout, 0.25)):
                        return True
                return False

        for start_at in range(0, len(pending), BATCH_SIZE):
            batch = pending[start_at : start_at + BATCH_SIZE]
            tasks = [asyncio.create_task(responding(address)) for address in batch]
            try:
                for address, seen in zip(
                    batch, await asyncio.gather(*tasks), strict=True
                ):
                    if seen:
                        sightings[address] = Sighting(address)
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        traffic: tuple[TrafficCounter, ...] = ()
        if self.sources.traffic is not None:
            counters, traffic_issues = await self.sources.traffic.reader.traffic(
                plan.targets
            )
            issues.extend(traffic_issues)
            # Attribute bytes only where this check confirmed the same MAC at the
            # same address; IP-only or conflicting sightings get no traffic.
            traffic = tuple(
                counter
                for counter in counters
                if counter.address not in ambiguous
                and (sighting := sightings.get(counter.address)) is not None
                and sighting.key == counter.device
            )
        return PresenceSample(
            observed,
            tuple(sightings.values()),
            tuple(dict.fromkeys(issues)),
            ambiguous,
            traffic,
            frozenset(
                sighting.key
                for sighting in sightings.values()
                if sighting.identity is not None
                and sighting.identity.mac
                and self._infrastructure(sighting.identity)
            ),
        )

    async def collect_dns_activity(
        self, previous: PresenceSample, current: PresenceSample
    ) -> tuple[tuple[DnsActivityWindow, ...], tuple[str, ...]]:
        gap = epoch(current.checked_at) - epoch(previous.checked_at)
        bound = self.sources.dns_activity
        if (
            not self.plan.dns_profiles
            or bound is None
            or not 0 < gap <= MAX_ATTRIBUTION_GAP
        ):
            return (), ()
        watched = frozenset(self.plan.targets)
        old = {
            item.address: item.key
            for item in previous.sightings
            if item.identity
            and item.identity.mac
            and item.address not in previous.ambiguous_addresses
        }
        prior_counts = Counter(old.values())
        current_counts = Counter(
            item.key
            for item in current.sightings
            if item.identity and item.identity.mac
        )
        # Require fresh matching endpoints. IP-only sightings and conflicting
        # associations never backfill DNS, even if they count for presence.
        confirmed = {
            item.address: item.key
            for item in current.sightings
            if item.address in watched
            and item.address not in current.ambiguous_addresses
            and item.identity
            and item.identity.mac
            and old.get(item.address) == item.key
            and prior_counts[item.key] == current_counts[item.key] == 1
            and not self._infrastructure(item.identity)
        }
        if not confirmed:
            return (), ()
        samples, issues = await bound.reader.activity(
            tuple(confirmed), previous.checked_at, current.checked_at
        )
        return (
            tuple(
                DnsActivityWindow(device, previous.checked_at, sample)
                for device, sample in zip(confirmed.values(), samples, strict=True)
            )
            if samples
            else (),
            issues,
        )

    async def collect(
        self,
        scan_id: str,
        source_event: str,
        previous: Evidence | None,
        previous_revision: str | None,
    ) -> Evidence:
        if not self.plan.enabled:
            raise ValueError(
                "Collection is disabled; configure targets and enable it before scanning"
            )
        started = utc_now()
        issues: list[str] = []
        imported: Snapshot | None = None
        if self.plan.observations_file:
            try:
                imported = Snapshot.decode(load_json(self.plan.observations_file))
            except FileNotFoundError:
                if self.plan.mode == "fixture":
                    raise
                issues.append("dns_unavailable: configured observation file is missing")
            # Malformed exports fail before any probes; they are not silently discarded.
            if imported and self.plan.mode == "tcp":
                age = (
                    datetime.now(timezone.utc)
                    - datetime.fromisoformat(
                        imported.observed_at.replace("Z", "+00:00")
                    )
                ).total_seconds()
                if age < -5 or age > self.plan.max_observation_age:
                    issues.append(
                        "dns_stale: exported observations are outside the configured freshness window"
                    )
                    imported = None
        if self.plan.mode == "fixture":
            if imported is None:
                raise ValueError("Fixture observations are required")
            snapshot = imported
        else:
            semaphore = asyncio.Semaphore(self.plan.concurrency)

            async def probe(address: str, port: int) -> TcpObservation:
                async with semaphore:
                    try:
                        _, writer = await asyncio.wait_for(
                            asyncio.open_connection(address, port), self.plan.timeout
                        )
                    except TimeoutError:
                        return TcpObservation(address, port, "timeout")
                    except OSError as error:
                        return TcpObservation(
                            address,
                            port,
                            "closed"
                            if error.errno == errno.ECONNREFUSED
                            else "unreachable",
                        )
                    writer.close()
                    await writer.wait_closed()
                    return TcpObservation(address, port, "open")

            # The configured count, concurrency and per-probe deadlines bound the whole scan.
            observations: list[TcpObservation] = []
            batches = tuple(
                self.plan.targets[start : start + BATCH_SIZE]
                for start in range(0, len(self.plan.targets), BATCH_SIZE)
            )
            for batch in batches:
                # At most 32 devices' tasks exist at once. All batches belong to
                # this single scan identity, snapshot and subsequent publication.
                observations.extend(
                    await asyncio.gather(
                        *(
                            probe(address, port)
                            for address in batch
                            for port in self.plan.ports
                        )
                    )
                )
            observed = utc_now()
            labels = {label.address: label.label for label in self.plan.labels}
            configured = {
                address: DeviceIdentity(
                    address,
                    "ip:" + address,
                    labels.get(address),
                    None,
                    None,
                    None,
                    "configured",
                    observed,
                    None,
                )
                for address in self.plan.targets
            }
            dns = imported.dns if imported else ()
            window = None
            # Per kind, so a conflict can name the services that disagreed.
            samples: list[tuple[ConnectorKind, IdentitySample]] = []
            if self.sources.device_reads:
                dns_rows: list[DnsObservation] = []
                windows: list[DnsWindow] = []
                for bound in self.sources.device_reads:
                    table: list[DeviceIdentity] = []
                    for batch in batches:
                        enriched = await bound.reader.read(batch, observed)
                        if (
                            len({device.address for device in enriched.devices})
                            != len(enriched.devices)
                            or not {device.address for device in enriched.devices}
                            <= set(batch)
                            or not {query.device for query in enriched.dns}
                            <= set(batch)
                        ):
                            raise ValueError(
                                "Device source returned observations outside the configured scope for this batch"
                            )
                        table.extend(enriched.devices)
                        dns_rows.extend(enriched.dns)
                        if enriched.window is not None:
                            windows.append(enriched.window)
                        issues.extend(enriched.issues)
                    samples.append((bound.kind, IdentitySample(tuple(table))))
                if len(dns_rows) > MAX_DNS_ROWS:
                    issues.append("dns_truncated: combined destination limit reached")
                dns = tuple(dns_rows[:MAX_DNS_ROWS])
                if windows:
                    first = windows[0]
                    if any(
                        (item.from_at, item.until_at) != (first.from_at, first.until_at)
                        for item in windows
                    ):
                        raise ValueError(
                            "Device source returned inconsistent batch windows"
                        )
                    # At most one bound service reads DNS, so a complete window
                    # has one part per batch.
                    window = DnsWindow(
                        first.source,
                        first.from_at,
                        first.until_at,
                        sum(item.queries_read for item in windows),
                        sum(item.queries_available for item in windows),
                        len(windows) == len(batches)
                        and all(item.complete for item in windows)
                        and not any(issue.startswith("dns_") for issue in issues),
                    )
                issues = list(dict.fromkeys(issues))
            if self.sources.identity:
                identities = await self._identity_samples(observed)
                samples.extend(identities)
                issues.extend(
                    dict.fromkeys(
                        issue for _, sample in identities for issue in sample.issues
                    )
                )
            merge = merge_identities(
                [sample for _, sample in samples], frozenset(self.plan.targets)
            )
            winners = {device.address: device for device in merge.devices}
            # Ambiguous and conflicting addresses keep their configured record;
            # the snapshot keeps the configured target order.
            devices = {
                address: replace(winners[address], label=labels.get(address))
                if address in winners
                else record
                for address, record in configured.items()
            }
            for kinds in dict.fromkeys(
                _disagreeing_kinds(address, samples)
                for address in self.plan.targets
                if address in merge.conflicts
            ):
                issues.append(_conflict_issue(kinds))
            snapshot = Snapshot(
                imported.observed_at if imported else observed,
                tuple(observations),
                dns,
                tuple(devices.values()),
                window,
            )
            if self.plan.observations_file is None and not self._reads_dns():
                issues.append("dns_unavailable: no DNS observation source configured")
            if any(item.status in {"timeout", "unreachable"} for item in snapshot.tcp):
                issues.append(
                    "tcp_incomplete: some probes timed out or were unreachable"
                )
        scope = scope_fingerprint(self.plan)
        evidence = Evidence(
            scan_id,
            source_event,
            self.collector,
            self.plan.mode,
            scope,
            started,
            utc_now(),
            snapshot,
            tuple(issues),
            changes(previous, snapshot, scope, tuple(issues)),
            previous_revision,
            baseline=previous is None or previous.scope != scope,
        )

        # Retained artifacts and agent replies have different bounds. Preserve every
        # TCP observation and identity; tools page these records, while the workflow
        # projects a bounded assessment input. Disclose any trimmed DNS sample.
        # Halving the sample avoids quadratic serialization of a large subnet.
        while (
            len(evidence.encode().encode("utf-8")) > MAX_EVIDENCE_BYTES
            and evidence.snapshot.dns
        ):
            truncated = tuple(
                dict.fromkeys(
                    (
                        *evidence.issues,
                        "dns_truncated: retained evidence size limit reached",
                    )
                )
            )
            window = (
                replace(evidence.snapshot.dns_window, complete=False)
                if evidence.snapshot.dns_window
                else None
            )
            snapshot = replace(
                evidence.snapshot,
                dns=evidence.snapshot.dns[: len(evidence.snapshot.dns) // 2],
                dns_window=window,
            )
            evidence = replace(
                evidence,
                snapshot=snapshot,
                issues=truncated,
                changes=changes(previous, snapshot, scope, truncated),
            )
        if len(evidence.encode().encode("utf-8")) > MAX_EVIDENCE_BYTES:
            raise ValueError(
                "Evidence exceeds the retained artifact size limit; reduce optional metadata or scope"
            )
        return evidence


async def _quick_connect(address: str, port: int, timeout: float) -> bool:
    try:
        _, writer = await asyncio.wait_for(
            asyncio.open_connection(address, port), timeout
        )
    except TimeoutError:
        return False
    except OSError as error:
        if error.errno in {errno.ECONNREFUSED, errno.EHOSTUNREACH, errno.ETIMEDOUT}:
            return False
        # A missing route, permission failure or socket exhaustion is a failed sweep,
        # not evidence that all of its devices were absent.
        raise
    writer.close()
    try:
        await asyncio.wait_for(writer.wait_closed(), timeout)
    except (TimeoutError, ConnectionError):
        pass
    return True
