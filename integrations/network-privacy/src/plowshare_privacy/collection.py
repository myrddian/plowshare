"""Deterministic collection and comparison; no model, shell or Plowshare internals."""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
from dataclasses import asdict, replace
from datetime import datetime, timezone
from typing import Protocol

from .contracts import (
    CollectionPlan,
    DeviceIdentity,
    Evidence,
    Snapshot,
    TcpObservation,
    load_json,
    utc_now,
)
from .pihole import DeviceDataSource


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
    ):
        if plan.pihole is not None and device_source is None:
            raise ValueError("Configured Pi-hole requires a device-data source")
        self.collector = collector
        self.plan = plan
        self.device_source = device_source

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
            observations = await asyncio.gather(
                *(
                    probe(address, port)
                    for address in self.plan.targets
                    for port in self.plan.ports
                )
            )
            observed = utc_now()
            labels = {label.address: label.label for label in self.plan.labels}
            devices = {
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
            if self.device_source is not None:
                enriched = await self.device_source.read(self.plan.targets, observed)
                if (
                    len({device.address for device in enriched.devices})
                    != len(enriched.devices)
                    or not {device.address for device in enriched.devices}
                    <= set(self.plan.targets)
                    or not {query.device for query in enriched.dns}
                    <= set(self.plan.targets)
                ):
                    raise ValueError(
                        "Device source returned observations outside the configured scope"
                    )
                for device in enriched.devices:
                    devices[device.address] = replace(
                        device, label=labels.get(device.address)
                    )
                dns, window = enriched.dns, enriched.window
                issues.extend(enriched.issues)
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
        )

        # Native SDK replies allow 16,384 characters. Keep 384 bytes for the
        # evidence/revision wrapper; ASCII JSON also fits the 32 KiB investigation
        # source limit. Preserve TCP/identities and disclose any trimmed DNS sample.
        while len(evidence.encode().encode("utf-8")) > 16000 and evidence.snapshot.dns:
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
                evidence.snapshot, dns=evidence.snapshot.dns[:-1], dns_window=window
            )
            evidence = replace(
                evidence,
                snapshot=snapshot,
                issues=truncated,
                changes=changes(previous, snapshot, scope, truncated),
            )
        if len(evidence.encode().encode("utf-8")) > 16000:
            raise ValueError(
                "Evidence exceeds the tool reply size limit; reduce selected devices or ports"
            )
        return evidence
