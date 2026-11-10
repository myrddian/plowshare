"""Deterministic collection and comparison; no model, shell or Plowshare internals."""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
from dataclasses import asdict, replace
from datetime import datetime, timezone
from typing import Awaitable, Callable, Protocol

from .contracts import (
    CollectionPlan,
    DeviceIdentity,
    DnsObservation,
    DnsWindow,
    Evidence,
    Snapshot,
    TcpObservation,
    load_json,
    private_ipv4,
    utc_now,
)
from .identity_source import DeviceIdentitySource, IdentitySample
from .limits import BATCH_SIZE, MAX_DNS_ROWS, MAX_EVIDENCE_BYTES
from .pihole import DeviceDataSource
from .presence import PresenceSample, Sighting, epoch


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
    # Default-enabled plans retain their historical fingerprints and journal identity.
    if plan.enabled:
        values.pop("enabled")
    if not plan.labels:
        values.pop("labels")
    if plan.pihole is None:
        values.pop("pihole")
    else:
        values["pihole"]["password_file"] = (
            str(plan.pihole.password_file) if plan.pihole.password_file else None
        )
    if plan.unifi is None:
        values.pop("unifi")
    else:
        values["unifi"]["ca_file"] = (
            str(plan.unifi.ca_file) if plan.unifi.ca_file else None
        )
        if plan.unifi.certificate_sha256 is None:
            values["unifi"].pop("certificate_sha256")
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


class NetworkCollector:
    def __init__(
        self,
        collector: str,
        plan: CollectionPlan,
        device_source: DeviceDataSource | None = None,
        identity_source: DeviceIdentitySource | None = None,
        pihole_presence: Callable[[tuple[str, ...], str], Awaitable[IdentitySample]]
        | None = None,
    ):
        if plan.pihole is not None and device_source is None:
            raise ValueError("Configured Pi-hole requires a device-data source")
        if plan.unifi is not None and identity_source is None:
            raise ValueError("Configured UniFi requires a device identity source")
        self.collector = collector
        self.plan = plan
        self.device_source = device_source
        self.identity_source = identity_source
        self.pihole_presence = pihole_presence

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
        scope = frozenset(plan.targets)
        connected = (
            await self.identity_source.identify(plan.targets, observed)
            if self.identity_source is not None
            else IdentitySample()
        )
        sightings = {
            identity.address: Sighting(identity.address, identity)
            for identity in connected.devices
            if identity.address in scope
            and identity.address not in connected.ambiguous_addresses
        }
        issues = list(connected.issues)
        ambiguous = connected.ambiguous_addresses & scope
        remaining = tuple(
            address
            for address in plan.targets
            if address not in sightings and address not in connected.ambiguous_addresses
        )
        if self.pihole_presence is not None and remaining:
            cached = await self.pihole_presence(remaining, observed)
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
        return PresenceSample(
            observed, tuple(sightings.values()), tuple(dict.fromkeys(issues)), ambiguous
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
            devices = configured.copy()
            dns = imported.dns if imported else ()
            window = None
            if self.device_source is not None:
                dns_rows: list[DnsObservation] = []
                windows: list[DnsWindow] = []
                for batch in batches:
                    enriched = await self.device_source.read(batch, observed)
                    if (
                        len({device.address for device in enriched.devices})
                        != len(enriched.devices)
                        or not {device.address for device in enriched.devices}
                        <= set(batch)
                        or not {query.device for query in enriched.dns} <= set(batch)
                    ):
                        raise ValueError(
                            "Device source returned observations outside the configured scope for this batch"
                        )
                    for device in enriched.devices:
                        devices[device.address] = replace(
                            device, label=labels.get(device.address)
                        )
                    dns_rows.extend(enriched.dns)
                    if enriched.window is not None:
                        windows.append(enriched.window)
                    issues.extend(enriched.issues)
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
            if self.identity_source is not None:
                sample = await self.identity_source.identify(
                    self.plan.targets, observed
                )
                observed_addresses = {item.address for item in sample.devices}
                if (
                    len(observed_addresses) != len(sample.devices)
                    or observed_addresses & sample.ambiguous_addresses
                    or not (observed_addresses | sample.ambiguous_addresses)
                    <= configured.keys()
                ):
                    raise ValueError(
                        "Identity source returned addresses outside its scope or with ambiguous identity"
                    )
                issues.extend(sample.issues)
                for address in sample.ambiguous_addresses:
                    devices[address] = configured[address]
                conflict = False
                for item in sample.devices:
                    previous_identity = devices[item.address]
                    if previous_identity.mac and item.mac != previous_identity.mac:
                        devices[item.address] = configured[item.address]
                        conflict = True
                    else:
                        devices[item.address] = replace(
                            item, label=labels.get(item.address)
                        )
                if conflict:
                    issues.append(
                        "identity_conflict: UniFi and Pi-hole disagreed on a selected IP's MAC address"
                    )
            snapshot = Snapshot(
                imported.observed_at if imported else observed,
                tuple(observations),
                dns,
                tuple(devices.values()),
                window,
            )
            if self.plan.observations_file is None and self.device_source is None:
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
