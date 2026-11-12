"""Operator-owned monitoring scope, separate from the agent's granted tools."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Literal, Protocol

from .collector_factory import configured_collector
from .contracts import (
    Configuration,
    DeviceLabel,
    PiHolePlan,
    device_labels,
    expand_targets,
    household_zone,
    integer,
    items,
    load_json,
    object_fields,
    private_ipv4,
)
from .journal import FileReceipts
from .limits import MAX_PORTS
from .private_files import replace_private as replace_private
from .worker import Worker


class SettingsBusy(ValueError):
    """A scope change would abandon work admitted under the previous scope."""


@dataclass(frozen=True)
class MonitorChoice:
    enabled: bool
    targets: tuple[str, ...]
    ports: tuple[int, ...]
    labels: tuple[DeviceLabel, ...] | None = None
    quick_scan: bool | None = None
    dns_profiles: bool | None = None
    device_descriptions: bool | None = None

    @classmethod
    def decode(cls, value: object) -> MonitorChoice:
        row = object_fields(
            value,
            {"enabled", "targets", "ports"},
            {"labels", "quickScan", "dnsProfiles", "deviceDescriptions"},
        )
        if type(row["enabled"]) is not bool:
            raise ValueError("Monitoring enabled must be a boolean")
        if "quickScan" in row:
            quick_scan = row["quickScan"]
            if type(quick_scan) is not bool:
                raise ValueError("Quick presence scan must be a boolean")
        else:
            quick_scan = None
        if "dnsProfiles" in row:
            dns_profiles = row["dnsProfiles"]
            if type(dns_profiles) is not bool:
                raise ValueError("DNS activity profiles must be a boolean")
        else:
            dns_profiles = None
        if "deviceDescriptions" in row:
            device_descriptions = row["deviceDescriptions"]
            if type(device_descriptions) is not bool:
                raise ValueError("Device descriptions must be a boolean")
        else:
            device_descriptions = None
        targets = expand_targets(row["targets"])
        # The dashboard shares the collector configuration's port bound, so a
        # configuration it loads can always be saved back unchanged.
        ports = tuple(
            integer(item, 1, 65535) for item in items(row["ports"], MAX_PORTS)
        )
        public = next((target for target in targets if not private_ipv4(target)), None)
        if public is not None:
            # Hand-written configuration may watch other addresses; the
            # dashboard only ever directs probes at the private network.
            raise ValueError(
                f"{public} is not a private address. The dashboard watches private"
                " addresses only; other addresses are set in collector.json."
            )
        if (
            len(set(targets)) != len(targets)
            or len(set(ports)) != len(ports)
            or row["enabled"]
            and (not targets or not ports)
        ):
            raise ValueError("Choose unique device addresses and TCP ports")
        return cls(
            row["enabled"],
            targets,
            ports,
            device_labels(row["labels"], targets) if "labels" in row else None,
            quick_scan,
            dns_profiles,
            device_descriptions,
        )


@dataclass(frozen=True)
class PiHoleView:
    """The saved Pi-hole tuning and where its password comes from.

    It never carries the password, and names the environment variable rather
    than a file path: the operator needs to know which secret is used, not
    where the collector keeps a private file.
    """

    password_source: Literal["file", "environment"]
    password_environment: str | None
    lookback_seconds: int
    query_limit: int
    max_identity_age_seconds: int
    timeout_seconds: float

    @classmethod
    def of(cls, plan: PiHolePlan) -> PiHoleView:
        return cls(
            "environment" if plan.password_environment is not None else "file",
            plan.password_environment,
            plan.lookback_seconds,
            plan.query_limit,
            plan.max_identity_age,
            plan.timeout,
        )


@dataclass(frozen=True)
class MonitorView:
    enabled: bool
    targets: tuple[str, ...]
    ports: tuple[int, ...]
    editable: bool
    detail: str
    labels: tuple[DeviceLabel, ...] = ()
    pihole_configured: bool = False
    pending: bool = False
    unifi_configured: bool = False
    quick_scan: bool = False
    dns_profiles: bool = False
    device_descriptions: bool = False
    pihole: PiHoleView | None = None


class MonitorSettings(Protocol):
    """Save operator settings independently of connections and active scan work.

    Views expose the saved desired configuration; ``pending`` distinguishes it
    from the configuration currently used by the worker. Saving performs no
    network checks and never rewrites unsettled scan receipts.
    """

    def view(self) -> MonitorView: ...
    async def save(self, choice: MonitorChoice) -> MonitorView: ...
    async def save_pihole(self, origin: str, password_file: Path) -> MonitorView:
        """Replace the Pi-hole connection: its origin and its password file.

        The saved tuning (lookback, lookup limit, identity age and timeout) is
        kept; a first connection takes the defaults. A password previously
        read from an environment variable is replaced by ``password_file``.
        """
        ...

    async def save_time_zone(self, zone: str | None) -> Configuration:
        """Set the household zone (None removes it, so UTC is used) and apply it.

        Unlike monitoring scope, the zone changes no collection, so it applies
        at once without waiting for scans to settle. Returns the active
        configuration.
        """
        ...


class FileMonitorSettings:
    """Persist desired settings, then activate them at a settled worker boundary.

    A private pending configuration is atomically saved without acquiring worker
    locks or contacting providers. It survives a disconnected collector and a
    restart. New saves replace the desired plan, preserving other pending fields.
    Activation holds both worker locks and retains the existing journal scope
    transition before replacing active configuration. Uncertain work keeps its
    original scope; it cannot prevent saving the next configuration.
    """

    def __init__(self, path: Path, worker: Worker, receipts: FileReceipts):
        self.path = path.absolute()
        if self.path.is_symlink():
            raise ValueError("Collector configuration cannot be linked")
        self.pending_path = self.path.with_name(
            self.path.stem + ".pending" + self.path.suffix
        )
        self.save_lock = asyncio.Lock()
        self.worker = worker
        self.receipts = receipts

    def view(self) -> MonitorView:
        saved = self._saved_configuration()
        plan = saved.collection
        pending = saved != self.worker.config
        editable = plan.mode == "tcp"
        blocker = self._blocked_detail()
        if pending:
            detail = (
                "Settings saved. Waiting to apply them. " + blocker
                if blocker
                else "Settings saved. They will apply before the next collection pass."
            )
        elif editable:
            detail = "Choose the devices and ports to monitor."
        else:
            detail = "Monitoring settings cannot be changed in fixture mode."
        return MonitorView(
            plan.enabled,
            plan.targets,
            plan.ports,
            editable,
            detail,
            plan.labels,
            plan.pihole is not None,
            pending,
            plan.unifi is not None,
            plan.quick_scan,
            plan.dns_profiles,
            plan.device_descriptions,
            PiHoleView.of(plan.pihole) if plan.pihole is not None else None,
        )

    def _blocked_detail(self, *, include_locks: bool = True) -> str | None:
        """Explain the same scope fence before and after acquiring the worker locks."""
        receipts = self.receipts.all()
        completed = {item.source_event for item in receipts if item.phase == "done"}
        if self.worker.config.collection.mode != "tcp":
            return "Monitoring settings cannot be changed in fixture mode."
        if self.worker.state == "attention_required":
            reason = "The collector is stopped and needs attention."
        elif include_locks and (
            self.worker.lock.locked() or self.worker.request_lock.locked()
        ):
            return (
                "A scan or scan request is in progress. Wait for it to finish before "
                "applying saved settings. Existing work keeps its original configuration."
            )
        elif any(item.phase != "done" for item in receipts):
            reason = "A scan has unfinished collection or delivery work."
        elif any(
            item.state != "published" or item.request_id not in completed
            for item in self.receipts.publications()
        ):
            reason = "A scan request is still waiting for delivery or collection."
        else:
            return None
        return (
            reason
            + " Use Check retained work under System health to check existing work "
            "before applying saved settings. You can still save configuration changes."
        )

    async def save(self, choice: MonitorChoice) -> MonitorView:
        return await self._save(choice, None)

    async def save_pihole(self, origin: str, password_file: Path) -> MonitorView:
        return await self._save(None, (origin, password_file))

    async def _save(
        self, choice: MonitorChoice | None, pihole: tuple[str, Path] | None
    ) -> MonitorView:
        async with self.save_lock:
            if not self.view().editable:
                raise SettingsBusy(self.view().detail)
            saved = self._saved_configuration()
            if choice is None:
                current = saved.collection
                choice = MonitorChoice(
                    current.enabled, current.targets, current.ports, current.labels
                )
            source = self.pending_path if self.pending_path.exists() else self.path
            row = object_fields(
                load_json(source),
                {
                    "origin",
                    "project",
                    "collector",
                    "tokenEnvironment",
                    "webTokenEnvironment",
                    "stateDirectory",
                    "requestTopic",
                    "resultTopic",
                    "group",
                    "schedule",
                    "collection",
                },
                {"outgoingPeer", "timeZone"},
            )
            plan = object_fields(
                row["collection"],
                {"mode", "targets", "ports", "timeoutSeconds", "concurrency"},
                {
                    "enabled",
                    "observationsFile",
                    "maxObservationAgeSeconds",
                    "deviceLabels",
                    "pihole",
                    "unifi",
                    "quickScan",
                    "dnsProfiles",
                    "deviceDescriptions",
                },
            )
            plan.update(
                enabled=choice.enabled,
                targets=list(choice.targets),
                ports=list(choice.ports),
            )
            if choice.quick_scan is not None:
                plan["quickScan"] = choice.quick_scan
                if not choice.quick_scan:
                    plan["dnsProfiles"] = False
                    plan["deviceDescriptions"] = False
            if choice.dns_profiles is not None:
                plan["dnsProfiles"] = choice.dns_profiles
            if choice.device_descriptions is not None:
                plan["deviceDescriptions"] = choice.device_descriptions
            labels = (
                choice.labels
                if choice.labels is not None
                else tuple(
                    label
                    for label in saved.collection.labels
                    if label.address in choice.targets
                )
            )
            if labels or "deviceLabels" in plan:
                plan["deviceLabels"] = {label.address: label.label for label in labels}
            if pihole is not None:
                origin, password_file = pihole
                connection: dict[str, object] = {
                    "origin": origin,
                    "passwordFile": str(password_file),
                }
                # The dashboard edits only the connection. Tuning comes from
                # the saved plan (pending if any), so a re-save cannot reset it;
                # Configuration.decode applies the defaults to a first one.
                tuning = saved.collection.pihole
                if tuning is not None:
                    connection.update(
                        lookbackSeconds=tuning.lookback_seconds,
                        queryLimit=tuning.query_limit,
                        maxIdentityAgeSeconds=tuning.max_identity_age,
                        timeoutSeconds=tuning.timeout,
                    )
                plan["pihole"] = connection
            row["collection"] = plan
            config = Configuration.decode(row, self.path)
            # Decode fully before the durable save. Activation and connectivity are
            # independent; a successful save never implies a successful connection.
            self._validate_scope(config)
            replace_private(self.pending_path, json.dumps(row, indent=2) + "\n")
        return self.view()

    def _validate_scope(self, config: Configuration) -> None:
        # A pending file may carry a newer household zone (save_time_zone
        # writes it first); only collection scope may otherwise differ.
        if (
            replace(
                config,
                collection=self.worker.config.collection,
                time_zone=self.worker.config.time_zone,
            )
            != self.worker.config
        ):
            raise SettingsBusy(
                "Configuration changed outside this dashboard; restart to review it."
            )

    async def save_time_zone(self, zone: str | None) -> Configuration:
        if zone is not None:
            household_zone(zone)
        async with self.save_lock:
            self._saved_configuration()
            # The pending copy first: if the collector stops between the two
            # writes, the pending file holds the newer zone and applying it
            # later keeps the operator's choice rather than reverting it.
            targets = [self.path]
            if self.pending_path.exists():
                targets.insert(0, self.pending_path)
            for target in targets:
                row = load_json(target)
                if not isinstance(row, dict):
                    raise ValueError("Collector configuration must be an object")
                if zone is None:
                    row.pop("timeZone", None)
                else:
                    row["timeZone"] = zone
                Configuration.decode(row, self.path)
                replace_private(target, json.dumps(row, indent=2) + "\n")
            self.worker.config = replace(self.worker.config, time_zone=zone)
            return self.worker.config

    def _saved_configuration(self) -> Configuration:
        if Configuration.read(self.path) != self.worker.config:
            raise SettingsBusy(
                "Configuration changed outside this dashboard; restart to review it."
            )
        source = self.pending_path if self.pending_path.exists() else self.path
        if source.is_symlink():
            raise ValueError("Saved monitoring configuration cannot be linked")
        config = Configuration.read(source)
        self._validate_scope(config)
        return config

    async def apply_pending(self) -> None:
        """Activate saved settings before intake when the old scope is settled.

        This does not reconnect, reconcile, submit work or contact Pi-hole. A
        failed activation leaves the saved file available for explicit recovery.
        Journal transition ordering also supports a crash after active replacement.
        """
        if not self.pending_path.exists() or self._blocked_detail() is not None:
            return
        async with self.save_lock, self.worker.request_lock, self.worker.lock:
            if not self.pending_path.exists() or self._blocked_detail(
                include_locks=False
            ):
                return
            config = self._saved_configuration()
            collector = configured_collector(config.collector, config.collection)
            self.receipts.prepare_scope_change(config.collection)
            replace_private(self.path, self.pending_path.read_text())
            if (
                config.collection.dns_profiles
                != self.worker.config.collection.dns_profiles
                and self.worker.presence is not None
            ):
                self.worker.presence.reset()
            self.worker.config = config
            self.worker.collector = collector
            self.receipts.complete_scope_change(config.collection)
            self.pending_path.unlink()
            self.worker.state = (
                "ready" if config.collection.enabled else "configuration_required"
            )
            self.worker.detail = (
                "Ready. Request a scan to collect evidence."
                if config.collection.enabled
                else "Choose devices below to begin monitoring."
            )
