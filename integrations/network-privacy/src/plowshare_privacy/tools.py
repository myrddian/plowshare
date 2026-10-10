"""Named Python capabilities for external callers; not server tool registration.

The scan tool admits work through Relay. Read tools inspect adapter-owned receipts,
so collection completion is never confused with publication or an agent report.
No caller can supply an address, port, executable, filesystem path or server origin.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol

from .contracts import (
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    DnsWindow,
    Evidence,
    object_fields,
    uuid,
)
from .journal import Publication, Receipt, ScanOrigin
from .worker import Worker


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    effect: Literal["read", "relay_submission"]
    parameter: Literal["request_id", "scan_id"] | None = None


DEFINITIONS = (
    ToolDefinition(
        "network.scope", "Read the configured collector scope and probe limits.", "read"
    ),
    ToolDefinition(
        "network.scan",
        "Request a scan through Relay using a caller-retained UUID. Admission is not completion.",
        "relay_submission",
        "request_id",
    ),
    ToolDefinition(
        "network.scan_status",
        "Read a manual request's local publication and collection receipts.",
        "read",
        "request_id",
    ),
    ToolDefinition(
        "network.scan_list", "List the latest 50 local collection receipts.", "read"
    ),
    ToolDefinition(
        "network.evidence",
        "Read a collection's observations, comparison, gaps and retained revision.",
        "read",
        "scan_id",
    ),
    ToolDefinition(
        "network.destinations",
        "Read observed DNS destinations from a collection; queries do not prove transmitted content.",
        "read",
        "scan_id",
    ),
)


@dataclass(frozen=True)
class ScopeCall:
    pass


@dataclass(frozen=True)
class ScanCall:
    request_id: str
    origin: ScanOrigin | None = None


@dataclass(frozen=True)
class StatusCall:
    request_id: str


@dataclass(frozen=True)
class ListCall:
    pass


@dataclass(frozen=True)
class EvidenceCall:
    scan_id: str


@dataclass(frozen=True)
class DestinationsCall:
    scan_id: str


ToolCall = (
    ScopeCall | ScanCall | StatusCall | ListCall | EvidenceCall | DestinationsCall
)


def decode_call(name: str, arguments: object) -> ToolCall:
    """Reject unknown capabilities and all parameters outside the fixed contract."""
    if name in ("network.scope", "network.scan_list"):
        object_fields(arguments, set())
        return ScopeCall() if name == "network.scope" else ListCall()
    if name in ("network.scan", "network.scan_status"):
        row = object_fields(arguments, {"request_id"})
        identity = uuid(row["request_id"])
        return ScanCall(identity) if name == "network.scan" else StatusCall(identity)
    if name in ("network.evidence", "network.destinations"):
        row = object_fields(arguments, {"scan_id"})
        identity = uuid(row["scan_id"])
        return (
            EvidenceCall(identity)
            if name == "network.evidence"
            else DestinationsCall(identity)
        )
    raise ValueError("Unsupported network privacy tool")


@dataclass(frozen=True)
class ScopeResult:
    collector: str
    mode: Literal["tcp", "fixture"]
    targets: tuple[str, ...]
    ports: tuple[int, ...]
    concurrency: int
    timeout_seconds: float
    dns_export_configured: bool
    enabled: bool
    device_labels: tuple[DeviceLabel, ...] = ()
    pihole_configured: bool = False


@dataclass(frozen=True)
class ScanSummary:
    scan_id: str
    phase: str
    occurred_at: str
    revision: str | None
    mode: Literal["tcp", "fixture"]
    changes: tuple[str, ...]
    issues: tuple[str, ...]


@dataclass(frozen=True)
class ScanListResult:
    scans: tuple[ScanSummary, ...]


@dataclass(frozen=True)
class StatusResult:
    request_id: str
    publication: Literal["pending", "published", "received"]
    collection: ScanSummary | None


@dataclass(frozen=True)
class EvidenceResult:
    revision: str | None
    evidence: Evidence


@dataclass(frozen=True)
class DestinationsResult:
    scan_id: str
    revision: str | None
    mode: Literal["tcp", "fixture"]
    observed_at: str
    destinations: tuple[DnsObservation, ...]
    issues: tuple[str, ...]
    devices: tuple[DeviceIdentity, ...] = ()
    dns_window: DnsWindow | None = None


ToolResult = (
    ScopeResult
    | Publication
    | ScanListResult
    | StatusResult
    | EvidenceResult
    | DestinationsResult
)


class PrivacyTools(Protocol):
    """Transport-neutral provider. Unknown local identities refuse rather than return empty success."""

    async def execute(self, call: ToolCall) -> ToolResult: ...


class WorkerTools:
    def __init__(self, worker: Worker):
        self.worker = worker

    def summary(self, receipt: Receipt) -> ScanSummary:
        evidence = receipt.evidence
        return ScanSummary(
            receipt.scan_id,
            receipt.phase,
            receipt.occurred_at,
            receipt.revision,
            evidence.mode if evidence else self.worker.config.collection.mode,
            evidence.changes if evidence else (),
            evidence.issues if evidence else (),
        )

    def evidence(self, identity: str) -> EvidenceResult:
        receipt = next(
            (item for item in self.worker.receipts.all() if item.scan_id == identity),
            None,
        )
        if receipt is None or receipt.evidence is None:
            raise ValueError("Collection evidence is unavailable")
        return EvidenceResult(receipt.revision, receipt.evidence)

    async def execute(self, call: ToolCall) -> ToolResult:
        if isinstance(call, ScopeCall):
            plan = self.worker.config.collection
            return ScopeResult(
                self.worker.config.collector,
                plan.mode,
                plan.targets,
                plan.ports,
                plan.concurrency,
                plan.timeout,
                plan.observations_file is not None,
                plan.enabled,
                plan.labels,
                plan.pihole is not None,
            )
        if isinstance(call, ScanCall):
            return await self.worker.request_scan(call.request_id, call.origin)
        if isinstance(call, ListCall):
            return ScanListResult(
                tuple(
                    self.summary(item) for item in reversed(self.worker.receipts.all())
                )[:50]
            )
        if isinstance(call, StatusCall):
            publication = next(
                (
                    item
                    for item in self.worker.receipts.publications()
                    if item.request_id == call.request_id
                ),
                None,
            )
            receipt = next(
                (
                    item
                    for item in self.worker.receipts.all()
                    if item.source_event == call.request_id
                    and item.source_topic == self.worker.config.request_topic
                ),
                None,
            )
            if publication is None and receipt is None:
                raise ValueError("Unknown local request identity")
            return StatusResult(
                call.request_id,
                publication.state if publication else "received",
                self.summary(receipt) if receipt else None,
            )
        if isinstance(call, EvidenceCall):
            return self.evidence(call.scan_id)
        result = self.evidence(call.scan_id)
        evidence = result.evidence
        return DestinationsResult(
            call.scan_id,
            result.revision,
            evidence.mode,
            evidence.snapshot.observed_at,
            evidence.snapshot.dns,
            evidence.issues,
            evidence.snapshot.devices,
            evidence.snapshot.dns_window,
        )
