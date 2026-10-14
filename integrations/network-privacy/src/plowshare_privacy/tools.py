"""Named Python capabilities for external callers; not server tool registration.

The scan tool admits work through Relay. Read tools inspect adapter-owned receipts,
so collection completion is never confused with publication or an agent report.
No caller can supply an address, port, executable, filesystem path or server origin.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, replace
from typing import Literal, Protocol

from .collection import scope_fingerprint
from .contracts import (
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    DnsWindow,
    Evidence,
    TcpObservation,
    integer,
    object_fields,
    uuid,
)
from .journal import Publication, Receipt, ScanOrigin
from .limits import BATCH_SIZE, MAX_CHANGES, MAX_TARGETS, TOOL_REPLY_BYTES
from .worker import Worker


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    effect: Literal["read", "relay_submission"]
    parameter: Literal["request_id", "scan_id"] | None = None


DEFINITIONS = (
    ToolDefinition(
        "network.scope",
        "Read configured scope in pages (offset/limit); follow next_offset and check the scope fingerprint.",
        "read",
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
        "network.scan_list",
        "List paged local collection receipts (offset/limit), with bounded previews and full counts.",
        "read",
    ),
    ToolDefinition(
        "network.evidence",
        "Read retained evidence. Large scans return counts; use section tcp/dns/devices/changes/issues with offset/limit and follow next_offset.",
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
    offset: int = 0
    limit: int = BATCH_SIZE

    def __post_init__(self) -> None:
        integer(self.offset, 0, MAX_TARGETS)
        integer(self.limit, 1, BATCH_SIZE)


@dataclass(frozen=True)
class ScanCall:
    request_id: str
    origin: ScanOrigin | None = None


@dataclass(frozen=True)
class StatusCall:
    request_id: str


@dataclass(frozen=True)
class ListCall:
    offset: int = 0
    limit: int = 50

    def __post_init__(self) -> None:
        integer(self.offset, 0, 1000)
        integer(self.limit, 1, 50)


@dataclass(frozen=True)
class EvidenceCall:
    scan_id: str
    section: Literal["summary", "tcp", "dns", "devices", "changes", "issues"] | None = (
        None
    )
    offset: int = 0
    limit: int = BATCH_SIZE

    def __post_init__(self) -> None:
        integer(self.offset, 0, MAX_CHANGES)
        integer(self.limit, 1, BATCH_SIZE)
        if self.section is not None and self.section not in (
            "summary",
            "tcp",
            "dns",
            "devices",
            "changes",
            "issues",
        ):
            raise ValueError("Unknown evidence section")


@dataclass(frozen=True)
class DestinationsCall:
    scan_id: str
    offset: int = 0
    limit: int = BATCH_SIZE
    paged: bool = False

    def __post_init__(self) -> None:
        integer(self.offset, 0, MAX_CHANGES)
        integer(self.limit, 1, BATCH_SIZE)
        if type(self.paged) is not bool:
            raise ValueError("Invalid destination page intent")


ToolCall = (
    ScopeCall | ScanCall | StatusCall | ListCall | EvidenceCall | DestinationsCall
)


def decode_call(name: str, arguments: object) -> ToolCall:
    """Reject unknown capabilities and all parameters outside the fixed contract."""
    if name in ("network.scope", "network.scan_list"):
        row = object_fields(arguments, set(), {"offset", "limit"})
        offset = integer(row.get("offset", 0), 0, 1000)
        limit = integer(
            row.get("limit", 32 if name == "network.scope" else 50),
            1,
            32 if name == "network.scope" else 50,
        )
        return (
            ScopeCall(offset, limit)
            if name == "network.scope"
            else ListCall(offset, limit)
        )
    if name in ("network.scan", "network.scan_status"):
        row = object_fields(arguments, {"request_id"})
        identity = uuid(row["request_id"])
        return ScanCall(identity) if name == "network.scan" else StatusCall(identity)
    if name in ("network.evidence", "network.destinations"):
        row = object_fields(
            arguments,
            {"scan_id"},
            {"offset", "limit", "section"}
            if name == "network.evidence"
            else {"offset", "limit"},
        )
        identity = uuid(row["scan_id"])
        offset, limit = (
            integer(row.get("offset", 0), 0, MAX_CHANGES),
            integer(row.get("limit", 32), 1, 32),
        )
        section = row.get("section")
        if (
            name == "network.evidence"
            and section is None
            and {"offset", "limit"} & set(row)
        ):
            raise ValueError("Choose an evidence section for paged reads")
        if section is not None and section not in (
            "summary",
            "tcp",
            "dns",
            "devices",
            "changes",
            "issues",
        ):
            raise ValueError("Unknown evidence section")
        if name == "network.destinations":
            return DestinationsCall(
                identity, offset, limit, bool({"offset", "limit"} & set(row))
            )
        if section == "tcp":
            return EvidenceCall(identity, "tcp", offset, limit)
        if section == "dns":
            return EvidenceCall(identity, "dns", offset, limit)
        if section == "devices":
            return EvidenceCall(identity, "devices", offset, limit)
        if section == "changes":
            return EvidenceCall(identity, "changes", offset, limit)
        if section == "issues":
            return EvidenceCall(identity, "issues", offset, limit)
        return EvidenceCall(
            identity, "summary" if section == "summary" else None, offset, limit
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
    total_targets: int = 0
    offset: int = 0
    next_offset: int | None = None
    scope: str = ""


@dataclass(frozen=True)
class ScanSummary:
    scan_id: str
    phase: str
    occurred_at: str
    revision: str | None
    mode: Literal["tcp", "fixture"]
    changes: tuple[str, ...]
    issues: tuple[str, ...]
    total_changes: int = 0
    total_issues: int = 0


@dataclass(frozen=True)
class ScanListResult:
    scans: tuple[ScanSummary, ...]
    total: int = 0
    offset: int = 0
    next_offset: int | None = None


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


@dataclass(frozen=True)
class EvidenceCounts:
    tcp: int
    dns: int
    devices: int
    changes: int
    issues: int


EvidenceRecord = TcpObservation | DnsObservation | DeviceIdentity | str


@dataclass(frozen=True)
class EvidencePageResult:
    """A stable window of an immutable retained scan, never a truncated full scan.

    Page size is adaptive to serialized tool bytes. next_offset is authoritative;
    callers must use it rather than assume every request returns limit records.
    """

    scan_id: str
    revision: str | None
    section: str
    observed_at: str
    counts: EvidenceCounts
    offset: int
    total: int
    next_offset: int | None
    records: tuple[EvidenceRecord, ...]
    issues: tuple[str, ...]
    issues_complete: bool
    mode: Literal["tcp", "fixture"]
    scope: str
    previous_revision: str | None
    baseline: bool
    dns_window: DnsWindow | None


def tool_fits(result: ToolResult) -> bool:
    # Callers supply one of the explicit result records, not an arbitrary payload.
    return (
        len(json.dumps(asdict(result), allow_nan=False, separators=(",", ":")).encode())
        <= TOOL_REPLY_BYTES
    )


ToolResult = (
    ScopeResult
    | Publication
    | ScanListResult
    | StatusResult
    | EvidenceResult
    | DestinationsResult
    | EvidencePageResult
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
            evidence.changes[:1] if evidence else (),
            evidence.issues[:4] if evidence else (),
            len(evidence.changes) if evidence else 0,
            len(evidence.issues) if evidence else 0,
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
            if not 0 <= call.offset <= len(plan.targets):
                raise ValueError("Scope page offset is unavailable")
            targets = plan.targets[call.offset : call.offset + call.limit]
            scope_result = ScopeResult(
                self.worker.config.collector,
                plan.mode,
                targets,
                plan.ports,
                plan.concurrency,
                plan.timeout,
                plan.observations_file is not None,
                plan.enabled,
                tuple(label for label in plan.labels if label.address in targets),
                plan.pihole is not None,
                len(plan.targets),
                call.offset,
                call.offset + len(targets)
                if call.offset + len(targets) < len(plan.targets)
                else None,
                scope_fingerprint(plan),
            )
            while not tool_fits(scope_result) and len(scope_result.targets) > 1:
                targets = scope_result.targets[:-1]
                scope_result = replace(
                    scope_result,
                    targets=targets,
                    device_labels=tuple(
                        label for label in plan.labels if label.address in targets
                    ),
                    next_offset=call.offset + len(targets),
                )
            if not tool_fits(scope_result):
                raise ValueError("Scope metadata exceeds the tool page budget")
            return scope_result
        if isinstance(call, ScanCall):
            return await self.worker.request_scan(call.request_id, call.origin)
        if isinstance(call, ListCall):
            scans = tuple(
                self.summary(item) for item in reversed(self.worker.receipts.all())
            )
            if not 0 <= call.offset <= len(scans):
                raise ValueError("Scan page offset is unavailable")
            selected = scans[call.offset : call.offset + call.limit]
            listing = ScanListResult(
                selected,
                len(scans),
                call.offset,
                call.offset + len(selected)
                if call.offset + len(selected) < len(scans)
                else None,
            )
            while not tool_fits(listing) and len(listing.scans) > 1:
                listing = replace(
                    listing,
                    scans=listing.scans[:-1],
                    next_offset=call.offset + len(listing.scans) - 1,
                )
            if not tool_fits(listing):
                raise ValueError("Scan metadata exceeds the tool page budget")
            return listing
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
        result = self.evidence(call.scan_id)
        evidence = result.evidence
        if isinstance(call, EvidenceCall):
            if call.section is None and call.offset == 0 and tool_fits(result):
                return result
            return self.page(result, call.section or "summary", call.offset, call.limit)
        legacy = DestinationsResult(
            call.scan_id,
            result.revision,
            evidence.mode,
            evidence.snapshot.observed_at,
            evidence.snapshot.dns,
            evidence.issues,
            evidence.snapshot.devices,
            evidence.snapshot.dns_window,
        )
        if (
            not call.paged
            and call.offset == 0
            and call.limit == BATCH_SIZE
            and tool_fits(legacy)
        ):
            return legacy
        return self.page(result, "dns", call.offset, call.limit)

    def page(
        self, result: EvidenceResult, section: str, offset: int, limit: int
    ) -> EvidencePageResult:
        evidence = result.evidence
        snapshot = evidence.snapshot
        counts = EvidenceCounts(
            len(snapshot.tcp),
            len(snapshot.dns),
            len(snapshot.devices),
            len(evidence.changes),
            len(evidence.issues),
        )
        records: tuple[EvidenceRecord, ...]
        if section == "tcp":
            records = snapshot.tcp
        elif section == "dns":
            records = snapshot.dns
        elif section == "devices":
            records = snapshot.devices
        elif section == "changes":
            records = evidence.changes
        elif section == "issues":
            records = evidence.issues
        elif section == "summary":
            records = ()
        else:
            raise ValueError("Unknown evidence section")
        if not 0 <= offset <= len(records):
            raise ValueError("Evidence page offset is unavailable")
        selected = records[offset : offset + limit]
        page = EvidencePageResult(
            evidence.scan_id,
            result.revision,
            section,
            snapshot.observed_at,
            counts,
            offset,
            len(records),
            offset + len(selected) if offset + len(selected) < len(records) else None,
            selected,
            evidence.issues[:4],
            len(evidence.issues) <= 4,
            evidence.mode,
            evidence.scope,
            evidence.previous_revision,
            evidence.is_baseline,
            snapshot.dns_window,
        )
        while not tool_fits(page) and len(page.records) > 1:
            page = replace(
                page,
                records=page.records[:-1],
                next_offset=offset + len(page.records) - 1,
            )
        if not tool_fits(page):
            raise ValueError("Evidence record exceeds the tool page budget")
        return page
