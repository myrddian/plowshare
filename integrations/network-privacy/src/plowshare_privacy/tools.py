"""Named Python capabilities for external callers; not server tool registration.

The scan tool admits work through Relay. Read tools inspect adapter-owned receipts,
so collection completion is never confused with publication or an agent report.
No caller can supply an address, port, executable, filesystem path or server origin.
Device tools select among the current monitored inventory by MAC identity only.

The assistant's request and record tools queue work and write records through the
same services the dashboard uses: ``DeepScanner.request``,
``DeviceAssessments.assess`` and ``ProfileManagement.save``. They never reach
monitor settings, connections, credentials, the schedule, the time zone or limits.
"""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import asdict, dataclass, replace
from typing import TYPE_CHECKING, Callable, Literal, Protocol
from uuid import UUID, uuid5

from plowshare import Refusal, TransportError

from .collection import scope_fingerprint
from .contracts import (
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    DnsWindow,
    Evidence,
    TcpObservation,
    household_day,
    integer,
    object_fields,
    utc_now,
    uuid,
)
from .device_dossier import (
    DeviceDossier,
    SignalCode,
    build_dossier,
    confirmed_device_id,
    device_signals,
    parse_device_id,
)
from .device_inventory import DeviceCard, DeviceInventory
from .device_profiles import (
    NOTES_LIMIT,
    ProfileFields,
    ProfileIntent,
    ProfileManagement,
)
from .diagnostics import refusal_detail
from .issue_records import IssueManagement
from .journal import Publication, Receipt, ScanOrigin
from .limits import BATCH_SIZE, MAX_CHANGES, MAX_TARGETS, TOOL_REPLY_BYTES
from .privacy_issues import (
    APPLICABILITY,
    LINK_NOTES_LIMIT,
    MITIGATION_STATUSES,
    IssueLink,
    IssueValidation,
    needs_explanation,
)
from .worker import ReconciliationRequired, Worker

if TYPE_CHECKING:
    # Both modules import this one for ``monitored_devices``.
    from .deep_scan import DeepScanView
    from .device_assessments import AssessmentView


@dataclass(frozen=True)
class ToolDefinition:
    """One capability. ``request`` queues collector work; ``write`` saves a record."""

    name: str
    description: str
    effect: Literal["read", "relay_submission", "request", "write"]
    parameter: Literal["request_id", "scan_id", "device_id"] | None = None


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
    ToolDefinition(
        "network.devices",
        "List monitored devices with a confirmed MAC (offset/limit): device_id, name, address and attention signals. Follow next_offset.",
        "read",
    ),
    ToolDefinition(
        "network.device",
        "Read one monitored device's dossier: identity, open services, DNS purpose groups, traffic volume, coverage gaps and signals. Signals direct attention; they are not findings.",
        "read",
        "device_id",
    ),
    ToolDefinition(
        "network.request_deep_scan",
        "Queue a deep port scan of one monitored device (a device_id from network_devices). The scan is queued, not done; the reply gives its state.",
        "request",
        "device_id",
    ),
    ToolDefinition(
        "network.request_assessment",
        "Ask the collector to start an assessment of one monitored device. The collector's usual rules apply (one at a time, a daily allowance); a refusal says why. Started is not finished.",
        "request",
        "device_id",
    ),
    ToolDefinition(
        "network.note_profile",
        "Add a note to a monitored device's profile, saved as a dated line attributed to Privacy Watch. A device without a profile gets a minimal one named after it. It only appends notes.",
        "write",
        "device_id",
    ),
    ToolDefinition(
        "network.record_issue",
        "Link an existing privacy issue (an issue_id from the project's issues) to a monitored device and record mitigation progress on its profile. It cannot create issues: new issues are recorded by the user on the dashboard. Recording progress changes nothing on the network.",
        "write",
        "device_id",
    ),
)

# Assistant note bounds. A link's note keeps room for its attribution, whose
# date is always ten characters.
NOTE_LIMIT = 1024
ATTRIBUTION = "Privacy Watch, {day}: "
LINK_NOTE_LIMIT = LINK_NOTES_LIMIT - len(ATTRIBUTION.format(day="YYYY-MM-DD"))
UNKNOWN_DEVICE = "Choose a monitored device with a confirmed MAC address (a device_id from network_devices)."


class ToolRefusal(ValueError):
    """A refusal written for the agent to pass on to the user.

    Its text is fixed wording or a service's own explanation; it never echoes a
    caller-supplied value.
    """


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


@dataclass(frozen=True)
class DevicesCall:
    offset: int = 0
    limit: int = BATCH_SIZE

    def __post_init__(self) -> None:
        integer(self.offset, 0, MAX_TARGETS)
        integer(self.limit, 1, BATCH_SIZE)


@dataclass(frozen=True)
class DeviceCall:
    device_id: str

    def __post_init__(self) -> None:
        parse_device_id(self.device_id)


@dataclass(frozen=True)
class DeepScanRequestCall:
    device_id: str


@dataclass(frozen=True)
class AssessmentRequestCall:
    device_id: str


@dataclass(frozen=True)
class NoteProfileCall:
    """``request_id`` is the harness invocation, never a model-supplied value."""

    device_id: str
    note: str
    request_id: str


@dataclass(frozen=True)
class RecordIssueCall:
    """Link ``issue_id`` to the device's profile; ``request_id`` as for notes."""

    device_id: str
    issue_id: str
    applicability: str
    mitigation_status: str | None
    note: str | None
    request_id: str


AssistantCall = (
    DeepScanRequestCall | AssessmentRequestCall | NoteProfileCall | RecordIssueCall
)

ToolCall = (
    ScopeCall
    | ScanCall
    | StatusCall
    | ListCall
    | EvidenceCall
    | DestinationsCall
    | DevicesCall
    | DeviceCall
    | AssistantCall
)


def _note(value: object, limit: int) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > limit
        or any((ord(c) < 32 and c != "\n") or ord(c) == 127 for c in value)
    ):
        raise ToolRefusal(
            f"The note must be 1-{limit} characters of text, with no control characters except new lines."
        )
    return value


def _assistant_call(
    name: str, arguments: object, request_id: str | None
) -> AssistantCall:
    """Decode an assistant tool call; every failure is a ``ToolRefusal``."""
    optional = {
        "network.note_profile": set(),
        "network.record_issue": {"mitigation_status", "note"},
    }.get(name, set())
    required = {
        "network.note_profile": {"device_id", "note"},
        "network.record_issue": {"device_id", "issue_id", "applicability"},
    }.get(name, {"device_id"})
    try:
        row = object_fields(arguments, required, optional)
    except ValueError:
        raise ToolRefusal(
            "The arguments do not match this tool's parameters; nothing ran."
        ) from None
    try:
        device = parse_device_id(row["device_id"])
    except ValueError:
        raise ToolRefusal(UNKNOWN_DEVICE) from None
    if name == "network.request_deep_scan":
        return DeepScanRequestCall(device)
    if name == "network.request_assessment":
        return AssessmentRequestCall(device)
    if request_id is None:
        raise ValueError("A write needs the harness invocation identity")
    request = uuid(request_id)
    if name == "network.note_profile":
        return NoteProfileCall(device, _note(row["note"], NOTE_LIMIT), request)
    try:
        issue = uuid(row["issue_id"])
    except ValueError:
        raise ToolRefusal(
            "The issue_id must be the canonical UUID of an existing privacy issue."
        ) from None
    applicability = row["applicability"]
    if applicability not in APPLICABILITY:
        raise ToolRefusal(
            "The applicability must be one of: "
            + ", ".join(sorted(APPLICABILITY))
            + "."
        )
    status = row.get("mitigation_status")
    if status is not None and status not in MITIGATION_STATUSES:
        raise ToolRefusal(
            "The mitigation_status must be one of: "
            + ", ".join(sorted(MITIGATION_STATUSES))
            + "."
        )
    note = row.get("note")
    return RecordIssueCall(
        device,
        issue,
        applicability,
        status,
        _note(note, LINK_NOTE_LIMIT) if note is not None else None,
        request,
    )


def decode_call(
    name: str, arguments: object, *, request_id: str | None = None
) -> ToolCall:
    """Reject unknown capabilities and all parameters outside the fixed contract.

    ``request_id`` is the harness invocation identity; only the assistant's write
    tools use it, as the write's idempotency key.
    """
    if name in (
        "network.request_deep_scan",
        "network.request_assessment",
        "network.note_profile",
        "network.record_issue",
    ):
        return _assistant_call(name, arguments, request_id)
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
    if name == "network.devices":
        row = object_fields(arguments, set(), {"offset", "limit"})
        return DevicesCall(
            integer(row.get("offset", 0), 0, MAX_TARGETS),
            integer(row.get("limit", BATCH_SIZE), 1, BATCH_SIZE),
        )
    if name == "network.device":
        row = object_fields(arguments, {"device_id"})
        return DeviceCall(parse_device_id(row["device_id"]))
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


@dataclass(frozen=True)
class DeviceSummary:
    device_id: str
    name: str
    address: str
    signals: tuple[SignalCode, ...]


@dataclass(frozen=True)
class DevicesResult:
    """A page of the monitored inventory in configured target order.

    ``total`` counts devices with exactly one current confirmed-MAC card.
    ``unavailable`` counts inventory devices without one (address-only, or the
    same MAC currently seen at two addresses); they have no dossier.
    """

    devices: tuple[DeviceSummary, ...]
    total: int
    unavailable: int
    offset: int
    next_offset: int | None


@dataclass(frozen=True)
class WorkRequestResult:
    """The device's state after a request: queued or running, not finished."""

    device_id: str
    work: Literal["deep_scan", "assessment"]
    state: str
    detail: str | None


@dataclass(frozen=True)
class ProfileWriteResult:
    """A profile write's receipt. ``pending`` is unconfirmed and never resent."""

    device_id: str
    profile_id: str
    request_id: str
    phase: Literal["pending", "confirmed", "refused"]
    revision: str | None


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
    | DevicesResult
    | DeviceDossier
    | WorkRequestResult
    | ProfileWriteResult
)


def monitored_devices(
    inventory: DeviceInventory,
) -> tuple[tuple[str, DeviceCard], ...]:
    """Cards that can be selected by device_id: one current card per confirmed MAC.

    A MAC currently associated with two addresses is ambiguous; picking either
    would attribute one card's DNS and traffic to the other, so both are left
    out and counted as unavailable.
    """
    ids = [confirmed_device_id(card) for card in inventory.devices]
    seen = Counter(ids)
    return tuple(
        (device_id, card)
        for card, device_id in zip(inventory.devices, ids)
        if device_id is not None and seen[device_id] == 1
    )


class PrivacyTools(Protocol):
    """Transport-neutral provider. Unknown local identities refuse rather than return empty success."""

    async def execute(self, call: ToolCall) -> ToolResult: ...


class DeepScanRequests(Protocol):
    """``DeepScanner.request``: queue an operator scan, idempotent per device."""

    def request(self, device_id: str) -> DeepScanView: ...


class AssessmentRequests(Protocol):
    """``DeviceAssessments.assess``: admit an operator assessment under its rules."""

    async def assess(self, device_id: str) -> AssessmentView: ...


class WorkerTools:
    def __init__(
        self,
        worker: Worker,
        inventory: Callable[[], DeviceInventory] | None = None,
        *,
        deep_scans: DeepScanRequests | None = None,
        assessments: AssessmentRequests | None = None,
        profiles: ProfileManagement | None = None,
        issues: IssueManagement | None = None,
        clock: Callable[[], str] = utc_now,
    ):
        self.worker = worker
        # Device tools read the worker's shared inventory projection, the same
        # one the dashboard draws, unless a caller supplies a fixed source.
        self.inventory = inventory if inventory is not None else worker.device_inventory
        # The assistant's request and record services; None refuses the tool.
        self.deep_scans, self.assessments = deep_scans, assessments
        self.profiles, self.issues = profiles, issues
        self.clock = clock

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
        if isinstance(
            call,
            (
                DeepScanRequestCall,
                AssessmentRequestCall,
                NoteProfileCall,
                RecordIssueCall,
            ),
        ):
            return await self.assist(call)
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
        if isinstance(call, DevicesCall):
            return self.devices(call)
        if isinstance(call, DeviceCall):
            # One projection per call; the card chosen here is the one built.
            chosen = next(
                (
                    card
                    for device_id, card in monitored_devices(self.inventory())
                    if device_id == call.device_id
                ),
                None,
            )
            if chosen is None:
                raise ValueError("Unknown monitored device")
            return build_dossier(
                chosen,
                facts=self.worker.device_facts(call.device_id),
                budget=TOOL_REPLY_BYTES,
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

    # The assistant's request and record tools. Every refusal is a ToolRefusal.

    def _card(self, device_id: str) -> DeviceCard:
        card = next(
            (c for d, c in monitored_devices(self.inventory()) if d == device_id),
            None,
        )
        if card is None:
            raise ToolRefusal(UNKNOWN_DEVICE)
        return card

    def _day(self) -> str:
        return household_day(self.clock(), self.worker.config.zone).isoformat()

    async def assist(self, call: AssistantCall) -> ToolResult:
        if isinstance(call, DeepScanRequestCall):
            if self.deep_scans is None:
                raise ToolRefusal("Deep scans are not enabled on this collector.")
            self._card(call.device_id)
            try:
                scan = self.deep_scans.request(call.device_id)
            except LookupError:
                raise ToolRefusal(UNKNOWN_DEVICE) from None
            except ValueError as error:
                # The scanner's fixed reason, such as no identity source.
                raise ToolRefusal(str(error)) from None
            return WorkRequestResult(
                call.device_id, "deep_scan", scan.state, scan.detail
            )
        if isinstance(call, AssessmentRequestCall):
            return await self._assess(call)
        if isinstance(call, RecordIssueCall) and self.issues is None:
            raise ToolRefusal("Privacy issues are not enabled on this collector.")
        if self.profiles is None:
            raise ToolRefusal("Device profiles are not enabled on this collector.")
        if isinstance(call, NoteProfileCall):
            card = self._card(call.device_id)
            line = "Noted by " + ATTRIBUTION.format(day=self._day()) + call.note

            def noted(base: ProfileFields) -> ProfileFields:
                notes = base.notes
                if notes and not notes.endswith("\n"):
                    notes += "\n"
                notes += line
                if len(notes) > NOTES_LIMIT:
                    raise ToolRefusal(
                        f"The profile's notes would pass their {NOTES_LIMIT}-character limit. The user can tidy them on the dashboard."
                    )
                return replace(base, notes=notes)

            prior = self._prior(self.profiles, call.device_id, call.request_id)
            if prior is not None:
                return prior
            return await self._write_profile(
                self.profiles, call.device_id, call.request_id, card, noted
            )
        assert self.issues is not None
        return await self._record_issue(self.profiles, self.issues, call)

    async def _assess(self, call: AssessmentRequestCall) -> WorkRequestResult:
        # Imported here: device_assessments imports this module.
        from .device_assessments import AssessmentConflict

        if self.assessments is None:
            raise ToolRefusal("Device assessments are not enabled on this collector.")
        self._card(call.device_id)
        try:
            view = await self.assessments.assess(call.device_id)
        except LookupError:
            raise ToolRefusal(UNKNOWN_DEVICE) from None
        except (AssessmentConflict, ReconciliationRequired, ValueError) as error:
            # The service's own reason: one at a time, the daily allowance, or a
            # journal needing inspection.
            raise ToolRefusal(str(error)) from None
        except Refusal as error:
            raise ToolRefusal(
                refusal_detail(error, "preparing this assessment")
            ) from None
        except TransportError:
            raise ToolRefusal(
                "Plowshare did not answer while preparing the assessment. Nothing was started; ask again later."
            ) from None
        return WorkRequestResult(
            call.device_id, "assessment", view.state, view.detail or view.assess_note
        )

    async def _record_issue(
        self,
        profiles: ProfileManagement,
        issues: IssueManagement,
        call: RecordIssueCall,
    ) -> ProfileWriteResult:
        card = self._card(call.device_id)
        prior = self._prior(profiles, call.device_id, call.request_id)
        if prior is not None:
            return prior
        try:
            listed = await issues.list()
        except (ValueError, Refusal, TransportError):
            raise ToolRefusal(
                "The project's privacy issues could not be read just now; nothing was saved."
            ) from None
        head = next((v for v in listed if v.issue.issue_id == call.issue_id), None)
        if head is None:
            raise ToolRefusal(
                "That privacy issue is not in this project's issue list. This tool cannot create issues; the user records new ones on the dashboard."
            )
        note = (
            ATTRIBUTION.format(day=self._day()) + call.note
            if call.note is not None
            else None
        )

        def linked(base: ProfileFields) -> ProfileFields:
            old = next((v for v in base.issues if v.issue_id == call.issue_id), None)
            status = call.mitigation_status or (
                old.mitigation_status if old else "proposed"
            )
            # A confirmation or performed action this call records must be
            # explained now; an unchanged one keeps the explanation it has.
            unchanged = old is not None and (
                old.applicability,
                old.mitigation_status,
            ) == (
                call.applicability,
                status,
            )
            if (
                note is None
                and needs_explanation(call.applicability, status)
                and not unchanged
            ):
                raise ToolRefusal(
                    "A note is required: confirmed_affected, and the applied, verified and reverted statuses, need a short explanation of what was found or done."
                )
            link = IssueLink(
                call.issue_id,
                head.revision,
                call.applicability,
                status,
                note if note is not None else (old.notes if old else ""),
                old.evidence if old else (),
            )
            links = (
                tuple(link if v.issue_id == call.issue_id else v for v in base.issues)
                if old
                else base.issues + (link,)
            )
            return replace(base, issues=links)

        return await self._write_profile(
            profiles, call.device_id, call.request_id, card, linked
        )

    def _prior(
        self, profiles: ProfileManagement, device_id: str, request_id: str
    ) -> ProfileWriteResult | None:
        """The earlier write of a replayed invocation, returned instead of a second one.

        The change is recomputed from the current profile, so a replay after the
        first write landed would build different fields under the same request
        identity; the receipt is the answer.
        """
        try:
            prior = next(
                (v for v in profiles.writes() if v.request_id == request_id), None
            )
        except ValueError:
            raise ToolRefusal(
                "The profile write journal needs inspection on the dashboard; nothing was saved."
            ) from None
        return self._receipt(device_id, prior) if prior is not None else None

    @staticmethod
    def _receipt(device_id: str, intent: ProfileIntent) -> ProfileWriteResult:
        return ProfileWriteResult(
            device_id,
            intent.profile_id,
            intent.request_id,
            intent.phase,
            intent.revision,
        )

    async def _write_profile(
        self,
        profiles: ProfileManagement,
        device_id: str,
        request_id: str,
        card: DeviceCard,
        change: Callable[[ProfileFields], ProfileFields],
    ) -> ProfileWriteResult:
        """Apply ``change`` to the device's confirmed-MAC profile and save it once.

        The profile is chosen by the device pane's rule: the one profile confirmed
        for the card's MAC. Several, or an address-only profile at the device's
        address, are left to the user on the dashboard. With none, a minimal
        profile named after the device is created and bound to its MAC;
        ``DeviceProfiles.save`` then requires a fresh matching MAC sighting.
        ``request_id`` is the save's idempotency key; callers check ``_prior``
        first.
        """
        assert card.identity is not None and card.identity.mac is not None
        mac = card.identity.mac
        try:
            views = await profiles.list()
            confirmed = [v for v in views if v.profile.fields.device_mac == mac]
            if len(confirmed) > 1:
                raise ToolRefusal(
                    "Several profiles claim this device. The user can review them on the dashboard."
                )
            if confirmed:
                current = confirmed[0]
                identity: str = current.profile.profile_id
                expected: str | None = current.revision
                base = current.profile.fields
            else:
                if any(
                    v.profile.fields.device_mac is None
                    and card.address in v.profile.fields.addresses
                    for v in views
                ):
                    raise ToolRefusal(
                        "This device's address has an older profile without a confirmed MAC. The user can review it on the dashboard first."
                    )
                identity = str(uuid5(UUID(request_id), "network-privacy-profile"))
                expected = None
                base = _minimal_profile(card, mac)
            intent = await profiles.save(identity, request_id, expected, change(base))
        except ToolRefusal:
            raise
        except (ReconciliationRequired, IssueValidation) as error:
            raise ToolRefusal(str(error)) from None
        except Refusal as error:
            raise ToolRefusal(
                refusal_detail(error, "saving the device profile")
            ) from None
        except TransportError:
            raise ToolRefusal(
                "Plowshare did not confirm the profile write. It will not be resent; the user can check it on the dashboard."
            ) from None
        except ValueError as error:
            # DeviceProfiles' own fixed explanations, such as a stale MAC
            # sighting, an unresolvable issue revision or a full document.
            raise ToolRefusal(str(error)) from None
        return self._receipt(device_id, intent)

    def devices(self, call: DevicesCall) -> DevicesResult:
        inventory = self.inventory()
        cards = monitored_devices(inventory)
        if not 0 <= call.offset <= len(cards):
            raise ValueError("Device page offset is unavailable")
        selected = cards[call.offset : call.offset + call.limit]
        listing = DevicesResult(
            tuple(
                DeviceSummary(
                    device_id,
                    card.name,
                    card.address,
                    tuple(signal.code for signal in device_signals(card)),
                )
                for device_id, card in selected
            ),
            len(cards),
            len(inventory.devices) - len(cards),
            call.offset,
            call.offset + len(selected)
            if call.offset + len(selected) < len(cards)
            else None,
        )
        while not tool_fits(listing) and len(listing.devices) > 1:
            listing = replace(
                listing,
                devices=listing.devices[:-1],
                next_offset=call.offset + len(listing.devices) - 1,
            )
        if not tool_fits(listing):
            raise ValueError("Device metadata exceeds the tool page budget")
        return listing

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


def _minimal_profile(card: DeviceCard, mac: str) -> ProfileFields:
    """A profile with only the device's name, address and confirmed MAC."""
    name = card.name.strip()[:128]
    try:
        return ProfileFields(name, "", "", "", "", "", (card.address,), (), mac)
    except ValueError:
        # A device-reported name that is not valid profile text.
        return ProfileFields(mac, "", "", "", "", "", (card.address,), (), mac)
