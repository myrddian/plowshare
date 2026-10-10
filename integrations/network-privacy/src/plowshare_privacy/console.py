"""Authenticated operator console. Read projections do not admit agent work.

Explicit investigation requests have a separate durable intent. A lost reply is
settled only by a positive server receipt; absence never authorizes resubmission.
Health reads are cached to avoid multiplying remote IO with browser polling.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Awaitable, Callable, Protocol
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError

from .collection import scope_fingerprint
from .console_port import Admission, AgentHealth, ConsolePort, RunSummary, ScheduleView
from .contracts import (
    DeviceIdentity,
    DnsObservation,
    PiHolePlan,
    TcpObservation,
    items,
    load_json,
    object_fields,
    text,
    utc_now,
    uuid,
)
from .discovery import DeviceDiscovery
from .monitor import MonitorChoice, MonitorSettings, SettingsBusy
from .operator_state import Finding, OperatorStore, findings
from .pihole import PiHoleV6
from .private_files import replace_private
from .worker import Worker


@dataclass(frozen=True)
class DeviceCard:
    address: str
    name: str
    identity: DeviceIdentity | None
    last_seen: str | None
    services: tuple[TcpObservation, ...]
    dns: tuple[DnsObservation, ...]
    history: tuple[str, ...]
    revision: str | None


@dataclass(frozen=True)
class DeviceMove:
    device_id: str
    old: str
    new: str


@dataclass(frozen=True)
class PiHoleCheck:
    connected: bool
    saved: bool
    issues: tuple[str, ...]


@dataclass(frozen=True)
class Health:
    checked_at: str
    connected: bool
    detail: str
    schedule: ScheduleView | None
    agents: AgentHealth | None
    runs: tuple[RunSummary, ...]
    last_contact: str | None
    last_scan: str | None
    pihole: str
    recovery: tuple[str, ...]
    checklist: tuple[str, ...]
    credential_expires_at: str | None


@dataclass(frozen=True)
class ManualInvestigation:
    request_id: str
    scan_id: str
    requested_at: str
    phase: str
    run_id: str | None = None
    state: str | None = None

    @classmethod
    def decode(cls, value: object) -> ManualInvestigation:
        from .contracts import timestamp

        row = object_fields(
            value, {"request_id", "scan_id", "requested_at", "phase", "run_id", "state"}
        )
        phase = text(row["phase"])
        if phase not in {"pending", "confirmed", "refused"}:
            raise ValueError("Invalid investigation phase")
        if (phase == "confirmed") != (
            row["run_id"] is not None and row["state"] is not None
        ):
            raise ValueError("Investigation phase requires matching admission metadata")
        if phase != "confirmed" and (
            row["run_id"] is not None or row["state"] is not None
        ):
            raise ValueError("Unconfirmed investigation cannot retain an admission")
        return cls(
            uuid(row["request_id"]),
            uuid(row["scan_id"]),
            timestamp(row["requested_at"]),
            phase,
            uuid(row["run_id"]) if row["run_id"] is not None else None,
            text(row["state"], 128) if row["state"] is not None else None,
        )


class ManualInvestigations(Protocol):
    """Own private admission intent/receipt persistence; never submit server work."""

    def all(self) -> tuple[ManualInvestigation, ...]: ...
    def save(self, entry: ManualInvestigation) -> None: ...


class FileManualInvestigations:
    def __init__(self, root: Path):
        self.path = root / "manual-investigations.json"
        self._entries: tuple[ManualInvestigation, ...] = ()
        if self.path.exists():
            if self.path.is_symlink() or self.path.resolve() != self.path:
                raise ValueError("Investigation journal must be unlinked")
            self._entries = tuple(
                ManualInvestigation.decode(item)
                for item in items(load_json(self.path), 128)
            )
            if len({item.request_id for item in self._entries}) != len(self._entries):
                raise ValueError("Duplicate investigation identity")

    def save(self, entry: ManualInvestigation) -> None:
        ManualInvestigation.decode(asdict(entry))
        previous = next(
            (item for item in self._entries if item.request_id == entry.request_id),
            None,
        )
        if previous and (
            previous.scan_id != entry.scan_id
            or previous.requested_at != entry.requested_at
            or previous.phase != "pending"
            and previous != entry
        ):
            raise ValueError("Conflicting investigation receipt identity")
        entries = tuple(
            item for item in self._entries if item.request_id != entry.request_id
        ) + (entry,)
        if len(entries) > 128:
            raise ValueError(
                "Archive settled manual investigation history before continuing"
            )
        replace_private(
            self.path, json.dumps([asdict(item) for item in entries]) + "\n"
        )
        self._entries = entries

    def all(self) -> tuple[ManualInvestigation, ...]:
        return self._entries


class OperatorConsole:
    def __init__(
        self,
        root: Path,
        worker: Worker,
        port: ConsolePort,
        settings: MonitorSettings,
        discovery: DeviceDiscovery,
        store: OperatorStore,
        recover: Callable[[Callable[[], Awaitable[None]]], Awaitable[None]],
        journal: ManualInvestigations,
        credential_expires_at: str | None = None,
    ):
        self.root, self.worker, self.port = root, worker, port
        self.settings, self.discovery, self.store, self.recover = (
            settings,
            discovery,
            store,
            recover,
        )
        self.credential_expires_at = credential_expires_at
        self.lock = asyncio.Lock()
        self.health_lock = asyncio.Lock()
        self.cached: Health | None = None
        self.cache_until = 0.0
        self.journal = journal

    @property
    def manual(self) -> tuple[ManualInvestigation, ...]:
        return self.journal.all()

    def _save_manual(self, entry: ManualInvestigation) -> None:
        self.journal.save(entry)

    def findings(self) -> tuple[Finding, ...]:
        result: dict[str, Finding] = {}
        for receipt in self.worker.receipts.all()[-50:]:
            for item in findings(receipt, self.store.expected):
                result[item.id] = item
        return tuple(reversed(tuple(result.values())))

    def acknowledge(self, identity: str, expected: bool) -> None:
        if type(expected) is not bool or identity not in {
            item.id for item in self.findings()
        }:
            raise ValueError("Choose an observed finding")
        self.store.acknowledge(identity, expected)

    def devices(self) -> tuple[DeviceCard, ...]:
        result: list[DeviceCard] = []
        receipts = tuple(
            item for item in self.worker.receipts.all()[-50:] if item.evidence
        )
        labels = {
            item.address: item.label for item in self.worker.config.collection.labels
        }
        for address in self.worker.config.collection.targets:
            latest = next(
                (
                    item
                    for item in reversed(receipts)
                    if item.evidence
                    and address in {v.address for v in item.evidence.snapshot.tcp}
                ),
                None,
            )
            evidence = latest.evidence if latest else None
            identity = (
                next(
                    (v for v in evidence.snapshot.devices if v.address == address), None
                )
                if evidence
                else None
            )
            history = [
                item.scan_id
                for item in receipts
                if item.evidence
                and any(v.address == address for v in item.evidence.snapshot.tcp)
            ]
            result.append(
                DeviceCard(
                    address,
                    labels.get(address)
                    or (identity.hostname if identity else None)
                    or "Unnamed device",
                    identity,
                    evidence.finished_at if evidence else None,
                    tuple(
                        item
                        for item in evidence.snapshot.tcp
                        if item.address == address
                    )
                    if evidence
                    else (),
                    tuple(
                        item for item in evidence.snapshot.dns if item.device == address
                    )
                    if evidence
                    else (),
                    tuple(history),
                    latest.revision if latest else None,
                )
            )
        return tuple(result)

    async def health(self) -> Health:
        async with self.health_lock:
            if self.cached and time.monotonic() < self.cache_until:
                return self.cached
            runs: tuple[RunSummary, ...]
            schedule, agents, runs, connected, detail = (
                None,
                None,
                (),
                True,
                "Plowshare read checks passed; execution authorization is checked on each call.",
            )
            try:
                schedule = await self.port.schedule()
                agents = await self.port.agents()
                runs = await self.port.runs()
            except (Refusal, TransportError, ValueError, TimeoutError):
                connected, detail = (
                    False,
                    "Plowshare checks are unavailable. Local evidence is preserved; check connection and service-account grants.",
                )
            completed = [
                item
                for item in self.worker.receipts.all()
                if item.phase == "done" and item.evidence
            ]
            latest = completed[-1].evidence if completed else None
            pihole = "Optional: not configured"
            if self.worker.config.collection.pihole:
                pihole = "Configured; awaiting a collection"
                if latest and latest.scope == scope_fingerprint(
                    self.worker.config.collection
                ):
                    pihole = (
                        "Coverage gaps: " + "; ".join(latest.issues)
                        if latest.issues
                        else "Latest collection retained; inspect its observation time and DNS window"
                    )
            recovery = []
            if self.worker.state == "attention_required":
                recovery.append(
                    "Inspect the pending receipts below. Fix connectivity or grants, then choose Check and resume. Unknown effects will not be resent."
                )
            if any(
                item.phase in {"uploading", "publishing"}
                for item in self.worker.receipts.all()
            ) or any(item.phase == "pending" for item in self.manual):
                recovery.append(
                    "An effect has an unknown outcome. Check retained server receipts before admitting new work."
                )
            if self.credential_expires_at:
                from datetime import datetime, timedelta, timezone

                expires = datetime.fromisoformat(
                    self.credential_expires_at.replace("Z", "+00:00")
                )
                if expires <= datetime.now(timezone.utc) + timedelta(days=7):
                    recovery.append(
                        "The service credential has expired or expires within seven days. A deployment administrator must issue a replacement token; keep uncertain work fenced while replacing the private credential."
                    )
            checklist = []
            if not self.worker.config.collection.enabled:
                checklist.append("Find devices, choose ports and save monitoring")
            if not latest:
                checklist.append("Request a first scan and inspect its evidence")
            if not schedule:
                checklist.append("Check the Application schedule registration")
            elif schedule.paused:
                checklist.append("Review and enable the scan schedule below")
            if agents and (agents.unavailable or agents.missing_tools):
                checklist.append("Review project agents and provider-scope grants")
            self.cached = Health(
                utc_now(),
                connected,
                detail,
                schedule,
                agents,
                runs,
                self.worker.last_contact,
                latest.finished_at if latest else None,
                pihole,
                tuple(recovery),
                tuple(checklist),
                self.credential_expires_at,
            )
            self.cache_until = time.monotonic() + 20
            return self.cached

    async def investigate(self, scan_id: str, request_id: str) -> ManualInvestigation:
        async with self.lock:
            previous = next(
                (v for v in self.manual if v.request_id == request_id), None
            )
            if previous:
                if previous.scan_id != scan_id:
                    raise ValueError("Request identity already belongs to another scan")
                return previous
            receipt = next(
                (v for v in self.worker.receipts.all() if v.scan_id == scan_id), None
            )
            if (
                receipt is None
                or receipt.phase != "done"
                or receipt.investigation is None
                or receipt.investigation.requested
            ):
                raise ValueError(
                    "Choose completed evidence whose automatic investigation was withheld"
                )
            if any(v.scan_id == scan_id and v.phase != "refused" for v in self.manual):
                raise SettingsBusy(
                    "This scan already has an investigation intent; inspect its receipt"
                )
            entry = ManualInvestigation(
                uuid(request_id), uuid(scan_id), utc_now(), "pending"
            )
            self._save_manual(entry)
            try:
                admission = await self.port.investigate(receipt, request_id)
            except Refusal:
                self._save_manual(replace(entry, phase="refused"))
                raise
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self._save_manual(replace(entry, phase="refused"))
                raise
            entry = self._confirmed(entry, admission)
            self._save_manual(entry)
            self.cache_until = 0
            return entry

    def _confirmed(
        self, entry: ManualInvestigation, admission: Admission
    ) -> ManualInvestigation:
        if admission.request_id != entry.request_id:
            raise ValueError("Foreign investigation receipt")
        return replace(
            entry, phase="confirmed", run_id=admission.run_id, state=admission.state
        )

    async def check_and_resume(self) -> None:
        async with self.lock:

            async def reconcile_manual() -> None:
                for entry in self.manual:
                    if entry.phase == "pending":
                        self._save_manual(
                            self._confirmed(
                                entry,
                                await self.port.investigation_receipt(entry.request_id),
                            )
                        )

            # Composition first establishes a fresh connection if needed, then
            # invokes this read-only check before authorizing the worker to resume.
            await self.recover(reconcile_manual)
            self.cache_until = 0

    async def connect_pihole(
        self, origin: str, password: str, save: bool
    ) -> PiHoleCheck:
        # Test first without writing a credential or changing the collector. The
        # explicitly supplied application password is never included in a DTO reply.
        async with self.lock:
            path = self.root / ("pihole-" + str(uuid4()) + ".password")
            plan = PiHolePlan.decode({"origin": origin, "passwordFile": str(path)})
            source = PiHoleV6(plan, application_password=password)
            result = await source.identify(
                self.worker.config.collection.targets, utc_now()
            )
            if any(
                "authentication or connection failed" in issue
                or "could not be read or validated" in issue
                or "session" in issue
                for issue in result.issues
            ):
                return PiHoleCheck(False, False, result.issues)
            if save:
                if not self.settings.view().editable:
                    raise SettingsBusy(self.settings.view().detail)
                replace_private(path, password + "\n")
                try:
                    await self.settings.save_pihole(plan)
                except (ValueError, SettingsBusy):
                    if self.worker.config.collection.pihole != plan:
                        path.unlink(missing_ok=True)
                    raise
                self.cache_until = 0
            return PiHoleCheck(True, save, result.issues)

    async def moved_devices(self) -> tuple[DeviceMove, ...]:
        plan = self.worker.config.collection.pihole
        discovered = self.discovery.latest()
        if plan is None or discovered is None:
            raise ValueError("Configure Pi-hole and find devices first")
        candidates = tuple(item.address for item in discovered.devices)
        if len(candidates) > 32:
            raise ValueError(
                "Use discovery with at most 32 responding addresses for identity checks"
            )
        current = await PiHoleV6(plan).identify(candidates, utc_now())
        addresses: dict[str, set[str]] = {}
        for item in current.devices:
            addresses.setdefault(item.device_id, set()).add(item.address)
        associations = {
            device: next(iter(values))
            for device, values in addresses.items()
            if len(values) == 1
        }
        result = []
        for card in self.devices():
            identity = card.identity
            if identity is None:
                continue
            device = identity.device_id
            old = card.address
            new = associations.get(device)
            if (
                device.startswith("mac:")
                and new
                and new != old
                and new not in self.worker.config.collection.targets
            ):
                result.append(DeviceMove(device, old, new))
        return tuple(result)

    async def accept_move(self, old: str, new: str, device: str) -> None:
        async with self.lock:
            if DeviceMove(device, old, new) not in await self.moved_devices():
                raise ValueError("Device association is no longer freshly available")
            current = self.settings.view()
            choice = MonitorChoice.decode(
                {
                    "enabled": current.enabled,
                    "targets": [
                        new if value == old else value for value in current.targets
                    ],
                    "ports": list(current.ports),
                    "labels": {
                        new if value.address == old else value.address: value.label
                        for value in current.labels
                    },
                }
            )
            await self.settings.save(choice)
            self.cache_until = 0
