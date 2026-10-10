"""Operator-owned monitoring scope, separate from the agent's granted tools."""

from __future__ import annotations

import ipaddress
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from uuid import uuid4

from .collector_factory import configured_collector
from .contracts import (
    Configuration,
    DeviceLabel,
    device_labels,
    integer,
    items,
    load_json,
    object_fields,
    text,
)
from .journal import FileReceipts
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


class MonitorSettings(Protocol):
    """Only the authenticated dashboard operator may change the monitored scope."""

    def view(self) -> MonitorView: ...
    async def save(self, choice: MonitorChoice) -> MonitorView: ...


def replace_private(path: Path, content: str) -> None:
    """Atomically replace private configuration, including a durable directory flush."""
    if path.is_symlink() or path.resolve() != path:
        raise ValueError("Private configuration cannot be linked")
    temporary = path.parent / (str(uuid4()) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


class FileMonitorSettings:
    """Install a validated scope without restarting or discarding collection history.

    Both worker locks fence intake, publication and configuration replacement. All
    prior requests must have completed, including published requests awaiting intake.
    Unknown effects block a change. The journal migrates before the atomic config
    replacement; a crash can therefore restart with either the old or new plan.
    Agents keep the same bounded tool facade and cannot invoke these settings APIs.
    """

    def __init__(self, path: Path, worker: Worker, receipts: FileReceipts):
        self.path = path
        self.worker = worker
        self.receipts = receipts

    def view(self) -> MonitorView:
        plan = self.worker.config.collection
        completed = {
            item.source_event for item in self.receipts.all() if item.phase == "done"
        }
        editable = (
            plan.mode == "tcp"
            and self.worker.state != "attention_required"
            and not self.worker.lock.locked()
            and not self.worker.request_lock.locked()
            and all(item.phase == "done" for item in self.receipts.all())
            and all(
                item.state == "published" and item.request_id in completed
                for item in self.receipts.publications()
            )
        )
        return MonitorView(
            plan.enabled,
            plan.targets,
            plan.ports,
            editable,
            "Choose the devices and ports to monitor."
            if editable
            else "Finish or reconcile the current work before changing monitoring settings.",
            plan.labels,
            plan.pihole is not None,
        )

    async def save(self, choice: MonitorChoice) -> MonitorView:
        if not self.view().editable:
            raise SettingsBusy(self.view().detail)
        async with self.worker.request_lock, self.worker.lock:
            # Lock ownership makes view().editable false; verify retained state again
            # without using transient lock state after waiting for either lock.
            completed = {
                item.source_event
                for item in self.receipts.all()
                if item.phase == "done"
            }
            if (
                self.worker.state == "attention_required"
                or any(item.phase != "done" for item in self.receipts.all())
                or any(
                    item.state != "published" or item.request_id not in completed
                    for item in self.receipts.publications()
                )
            ):
                raise SettingsBusy(
                    "Finish or reconcile current work before changing monitoring settings."
                )
            row = object_fields(
                load_json(self.path),
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
            if Configuration.decode(row, self.path) != self.worker.config:
                raise SettingsBusy(
                    "Configuration changed outside this dashboard; restart to review it."
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
                    for label in self.worker.config.collection.labels
                    if label.address in choice.targets
                )
            )
            if labels or "deviceLabels" in plan:
                plan["deviceLabels"] = {label.address: label.label for label in labels}
            row["collection"] = plan
            config = Configuration.decode(row, self.path)
            collector = configured_collector(config.collector, config.collection)
            self.receipts.prepare_scope_change(config.collection)
            replace_private(self.path, json.dumps(row, indent=2) + "\n")
            self.worker.config = config
            self.worker.collector = collector
            self.receipts.complete_scope_change(config.collection)
            self.worker.state = "ready" if choice.enabled else "configuration_required"
            self.worker.detail = (
                "Ready. Request a scan to collect evidence."
                if choice.enabled
                else "Choose devices below to begin monitoring."
            )
        return self.view()
