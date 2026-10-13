"""Operator-owned monitoring scope, separate from the agent's granted tools."""

from __future__ import annotations

import asyncio
import ipaddress
import json
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Protocol

from .collector_factory import configured_collector
from .contracts import (
    Configuration,
    DeviceLabel,
    PiHolePlan,
    device_labels,
    integer,
    items,
    load_json,
    object_fields,
    text,
)
from .journal import FileReceipts
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

    @classmethod
    def decode(cls, value: object) -> MonitorChoice:
        row = object_fields(value, {"enabled", "targets", "ports"}, {"labels"})
        if type(row["enabled"]) is not bool:
            raise ValueError("Monitoring enabled must be a boolean")
        targets = tuple(
            str(ipaddress.IPv4Address(text(item))) for item in items(row["targets"], 32)
        )
        ports = tuple(integer(item, 1, 65535) for item in items(row["ports"], 8))
        private = tuple(
            ipaddress.IPv4Network(block)
            for block in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        )
        if (
            len(set(targets)) != len(targets)
            or len(set(ports)) != len(ports)
            or any(
                not any(ipaddress.IPv4Address(target) in block for block in private)
                for target in targets
            )
            or row["enabled"]
            and (not targets or not ports)
        ):
            raise ValueError("Choose unique private device addresses and TCP ports")
        return cls(
            row["enabled"],
            targets,
            ports,
            device_labels(row["labels"], targets) if "labels" in row else None,
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


class MonitorSettings(Protocol):
    """Save operator settings independently of connections and active scan work.

    Views expose the saved desired configuration; ``pending`` distinguishes it
    from the configuration currently used by the worker. Saving performs no
    network checks and never rewrites unsettled scan receipts.
    """

    def view(self) -> MonitorView: ...
    async def save(self, choice: MonitorChoice) -> MonitorView: ...
    async def save_pihole(self, plan: PiHolePlan) -> MonitorView: ...


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

    async def save_pihole(self, plan: PiHolePlan) -> MonitorView:
        return await self._save(None, plan)

    async def _save(
        self, choice: MonitorChoice | None, pihole: PiHolePlan | None
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
                {"outgoingPeer"},
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
                },
            )
            plan.update(
                enabled=choice.enabled,
                targets=list(choice.targets),
                ports=list(choice.ports),
            )
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
                plan["pihole"] = {
                    "origin": pihole.origin,
                    "passwordFile": str(pihole.password_file),
                    "queryLimit": pihole.query_limit,
                    "lookbackSeconds": pihole.lookback_seconds,
                    "maxIdentityAgeSeconds": pihole.max_identity_age,
                    "timeoutSeconds": pihole.timeout,
                }
            row["collection"] = plan
            config = Configuration.decode(row, self.path)
            # Decode fully before the durable save. Activation and connectivity are
            # independent; a successful save never implies a successful connection.
            self._validate_scope(config)
            replace_private(self.pending_path, json.dumps(row, indent=2) + "\n")
        return self.view()

    def _validate_scope(self, config: Configuration) -> None:
        if (
            replace(config, collection=self.worker.config.collection)
            != self.worker.config
        ):
            raise SettingsBusy(
                "Configuration changed outside this dashboard; restart to review it."
            )

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
