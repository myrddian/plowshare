"""Per-device privacy assessments and the operator's Accept or Dismiss decision.

Plowshare owns the runs, the report text and its draft/final status. This
collector-owned ledger (``device-assessments.json``, private, beside the
profile research journal) maps each device to its latest request so that the
dashboard never lists every report, and it fences every paid or authoritative
mutation against delivery uncertainty.

Lifecycle of one device's entry (``phase``; the dashboard ``state`` in brackets):

* no entry [idle]
* ``pending`` [running]: the start intent, with its request UUID, is flushed
  before ``orchestration.start``. A lost reply stays here and is settled only by
  the account-owned receipt for that UUID; it is never resubmitted.
* ``running`` [running]: the run ID is known; ``poll`` reads its state.
* ``review`` [review]: the run finished, its result was validated against the
  request, and the draft report revision is recorded. Plowshare then processes
  the revision and refuses to finalise it until that is done, so Accept is
  offered only once a status read (``poll`` or Check status) shows it
  processed. That knowledge is held in memory: after a restart Accept waits
  for the next read.
* ``accepting`` [review]: the finalise intent, with its own request UUID, is
  flushed before ``information.finalise``. A lost reply stays here until a
  status read shows the report final; it is never resent.
* ``accepted`` [accepted]: Plowshare confirmed the report final.
* ``dismissed`` [dismissed]: the operator hid the draft. Local only; the draft
  remains in Plowshare as history.
* ``failed`` [failed]: the start was refused or unsent, the run ended without a
  report, or its result was invalid. Retry creates a new request.

Admission rules: one ``pending``/``running`` entry across all devices; no new
run for a device in ``review``/``accepting``; at most ``DAILY_CEILING``
admissions per household day in total. The optional background queue (limit 0, off,
by default) admits only while today's admissions are below its own limit:
first signalled devices never assessed, then never-assessed devices with
supplemental facts (something the device said about itself, or an advisory
search), then devices whose signal set changed, or whose facts changed after
their last admission, since their last settled assessment.

Concurrency: one asyncio lock serialises every operation that awaits the
server, so an admission decision cannot interleave with another. ``dismiss``
and ``view`` do not await and read the ledger as it stands.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import TYPE_CHECKING, Awaitable, Callable, Literal, Protocol
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError

from .assessment_port import AssessmentPort
from .assessment_request import (
    AssessmentRequest,
    AssessmentResult,
    ConcernOutcome,
    ReviewOutcome,
    concern_outcomes,
)
from .contracts import (
    household_day,
    identifier,
    items,
    load_json,
    object_fields,
    parse_json,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .device_dossier import (
    SIGNAL_ORDER,
    ProfileMatch,
    build_dossier,
    device_signals,
    parse_device_id,
)
from .device_facts import DeviceFacts
from .device_inventory import DeviceCard, DeviceInventory
from .diagnostics import refusal_detail
from .dossier_port import DossierRetention
from .presence import epoch
from .private_files import replace_private
from .tools import monitored_devices
from .worker import ReconciliationRequired

if TYPE_CHECKING:
    # deep_scan imports tools, which imports device_dossier; type-only avoids a cycle.
    from .deep_scan import DeepScanResult

MAX_ENTRIES = 256
MAX_LEDGER_BYTES = 2 * 1024 * 1024
# Each admission spends model calls and report-processing allowance. This bounds
# on-demand and background admissions together, per household day.
DAILY_CEILING = 12
MAX_DETAIL = 1600

Phase = Literal[
    "pending", "running", "review", "accepting", "accepted", "dismissed", "failed"
]
State = Literal["idle", "running", "review", "accepted", "dismissed", "failed"]
_PHASES: frozenset[str] = frozenset(
    {"pending", "running", "review", "accepting", "accepted", "dismissed", "failed"}
)
_ACTIVE = frozenset({"pending", "running"})
_DECIDING = frozenset({"review", "accepting"})
_REPORTED = frozenset({"review", "accepting", "accepted", "dismissed"})
_SETTLED = frozenset({"accepted", "dismissed", "failed"})
_STATE: dict[str, State] = {
    "pending": "running",
    "running": "running",
    "review": "review",
    "accepting": "review",
    "accepted": "accepted",
    "dismissed": "dismissed",
    "failed": "failed",
}
_SIGNALS = frozenset(SIGNAL_ORDER)

_FINALISE_GUIDANCE = (
    " The assessment is still awaiting your review and nothing was resent."
    " If the collector's service account is not allowed to finalise reports"
    " its runs produced, finalise the report in Plowshare's Reports collection"
    " instead, then choose Check status here."
)
# Plowshare's refusal to finalise a report it has not finished processing.
_STILL_PROCESSING = "must complete processing first"
_PROCESSING_DETAIL = (
    "Plowshare is still processing this report, so it was not made final."
    " Accept returns when processing is done; try again shortly."
)


class UnknownDevice(LookupError):
    """The device ID is valid but not a monitored device with a confirmed MAC."""


class AssessmentConflict(Exception):
    """The request is valid but the device's current state does not allow it."""


@dataclass(frozen=True)
class AssessmentEntry:
    device_id: str
    request_id: str
    dossier: str
    previous: str | None
    requested_at: str
    trigger: Literal["operator", "background"]
    # Signal codes of the dossier this run read; a change requeues in background.
    signals: tuple[str, ...]
    phase: Phase
    run_id: str | None = None
    report: str | None = None
    resource: str | None = None
    review: ReviewOutcome | None = None
    concerns: tuple[ConcernOutcome, ...] = ()
    finalise_id: str | None = None
    decided_at: str | None = None
    # Why the run failed, or why the server refused to finalise; operator text.
    detail: str | None = None
    # The newest report revision known for this device, kept across failed runs.
    latest_report: str | None = None
    # The household zone fixed when the run was admitted, so a rebuilt request
    # matches the first; None for runs admitted before zones (UTC reports).
    time_zone: str | None = None

    def request(self) -> AssessmentRequest:
        return AssessmentRequest(
            self.device_id,
            self.dossier,
            self.previous,
            self.requested_at,
            self.time_zone,
        )

    @classmethod
    def decode(cls, value: object) -> AssessmentEntry:
        row = object_fields(
            value,
            {
                "device_id",
                "request_id",
                "dossier",
                "previous",
                "requested_at",
                "trigger",
                "signals",
                "phase",
                "run_id",
                "report",
                "resource",
                "review",
                "concerns",
                "finalise_id",
                "decided_at",
                "detail",
                "latest_report",
            },
            {"time_zone"},
        )
        phase, trigger = row["phase"], row["trigger"]
        if phase not in _PHASES or trigger not in {"operator", "background"}:
            raise ValueError("Invalid device assessment phase")
        signals = tuple(text(v, 32) for v in items(row["signals"], len(SIGNAL_ORDER)))
        if not set(signals) <= _SIGNALS or len(set(signals)) != len(signals):
            raise ValueError("Invalid device assessment signals")
        review = row["review"]
        if review not in {None, "accepted", "needs_corrections"}:
            raise ValueError("Invalid device assessment review outcome")
        optional_uuid = {
            key: uuid(row[key]) if row[key] is not None else None
            for key in (
                "previous",
                "report",
                "resource",
                "finalise_id",
                "latest_report",
            )
        }
        entry = cls(
            parse_device_id(row["device_id"]),
            uuid(row["request_id"]),
            uuid(row["dossier"]),
            optional_uuid["previous"],
            text(row["requested_at"], 64),
            trigger,
            signals,
            phase,  # type: ignore[arg-type]
            identifier(row["run_id"]) if row["run_id"] is not None else None,
            optional_uuid["report"],
            optional_uuid["resource"],
            review,
            concern_outcomes(row["concerns"]),
            optional_uuid["finalise_id"],
            timestamp(row["decided_at"]) if row["decided_at"] is not None else None,
            text(row["detail"], MAX_DETAIL) if row["detail"] is not None else None,
            optional_uuid["latest_report"],
            text(row["time_zone"], 64) if row.get("time_zone") is not None else None,
        )
        entry.request()  # Validates the device, dossier, timestamp and zone.
        reported = phase in _REPORTED
        # A failed start may have no run; every other phase after pending has one.
        has_run = entry.run_id is not None
        run_ok = not has_run if phase == "pending" else has_run or phase == "failed"
        # The finalise UUID is the Accept receipt; accepted may keep it, or not
        # when the report was finalised elsewhere and read back by Check status.
        finalise_ok = (
            entry.finalise_id is not None
            if phase == "accepting"
            else entry.finalise_id is None or phase == "accepted"
        )
        if (
            not run_ok
            or not finalise_ok
            or reported != (entry.report is not None)
            or reported != (entry.resource is not None)
            or reported != (entry.review is not None)
            or (not reported and entry.concerns)
            or (phase in {"accepted", "dismissed"}) != (entry.decided_at is not None)
            or (entry.report is not None and entry.latest_report != entry.report)
        ):
            raise ValueError("Inconsistent device assessment receipt")
        return entry


@dataclass(frozen=True)
class AssessmentLedger:
    version: int = 1
    # Background admissions per household day; 0 means the background queue is off.
    background_limit: int = 0
    entries: tuple[AssessmentEntry, ...] = ()
    # Admission times; only the current household day's are kept.
    admissions: tuple[str, ...] = ()

    @classmethod
    def decode(cls, value: object) -> AssessmentLedger:
        row = object_fields(
            value, {"version", "background_limit", "entries", "admissions"}
        )
        if type(row["version"]) is not int or row["version"] != 1:
            raise ValueError("Unsupported device assessment journal")
        limit = row["background_limit"]
        if type(limit) is not int or not 0 <= limit <= DAILY_CEILING:
            raise ValueError("Invalid background assessment limit")
        entries = tuple(
            AssessmentEntry.decode(v) for v in items(row["entries"], MAX_ENTRIES)
        )
        admissions = tuple(
            timestamp(v) for v in items(row["admissions"], DAILY_CEILING)
        )
        if (
            len({e.device_id for e in entries}) != len(entries)
            or len({e.request_id for e in entries}) != len(entries)
            or sum(e.phase in _ACTIVE for e in entries) > 1
        ):
            raise ValueError("Conflicting device assessment receipts")
        return cls(1, limit, entries, admissions)


class AssessmentJournal:
    """The private ledger file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "device-assessments.json"

    def read(self) -> AssessmentLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Device assessment journal cannot be linked")
        if not self.path.exists():
            return AssessmentLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Device assessment journal must be private")
        return AssessmentLedger.decode(
            load_json(self.path, maximum_bytes=MAX_LEDGER_BYTES)
        )

    def save(self, value: AssessmentLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        AssessmentLedger.decode(parse_json(content, maximum_bytes=MAX_LEDGER_BYTES))
        replace_private(self.path, content)


@dataclass(frozen=True)
class AssessmentView:
    """One device's assessment for the dashboard; no report text."""

    device_id: str
    state: State
    # "admission" while a start reply is unknown, "accept" while a finalise is.
    pending: Literal["admission", "accept"] | None
    request_id: str | None
    run_id: str | None
    report: str | None
    requested_at: str | None
    decided_at: str | None
    review: ReviewOutcome | None
    concerns: tuple[ConcernOutcome, ...]
    detail: str | None
    can_assess: bool
    can_accept: bool
    can_dismiss: bool
    # Why Assess is unavailable when the device's own state would allow it.
    assess_note: str | None
    # Awaiting review, but Plowshare has not been seen to finish processing the
    # report; Accept waits for it. Dismiss is still allowed.
    processing: bool = False


@dataclass(frozen=True)
class AssessmentReport:
    revision: str
    text: str


@dataclass(frozen=True)
class AssessmentSummary:
    """One device's assessment as the device list and Overview show it.

    Built from the ledger alone, so listing every device costs no inventory or
    server read. ``potential_breaches`` and ``worth_checking`` count reviewed
    concerns the second agent did not refute, in a report that is awaiting
    review or accepted; a dismissed report counts nothing.
    """

    device_id: str
    state: State
    pending: Literal["admission", "accept"] | None
    processing: bool
    potential_breaches: int
    worth_checking: int
    # When the current assessment was requested; None when never assessed.
    requested_at: str | None


@dataclass(frozen=True)
class AssessmentUsage:
    """Today's assessment admissions (household day) against the two limits."""

    background_limit: int
    used_today: int
    daily_ceiling: int


class AssessmentControl(Protocol):
    """The dashboard's view of assessments and the operator's decisions."""

    @property
    def ledger(self) -> AssessmentLedger: ...

    @property
    def error(self) -> str | None: ...

    def view(self, device_id: str) -> AssessmentView: ...
    def summaries(self) -> tuple[AssessmentSummary, ...]: ...
    def usage(self) -> AssessmentUsage: ...
    async def assess(self, device_id: str) -> AssessmentView: ...
    async def accept(self, device_id: str) -> AssessmentView: ...
    async def check(self, device_id: str) -> AssessmentView: ...
    def dismiss(self, device_id: str) -> AssessmentView: ...
    async def report(self, device_id: str) -> AssessmentReport: ...
    def set_background_limit(self, limit: int) -> None: ...


class DeviceAssessments:
    """``AssessmentControl`` and the background poll, over an ``AssessmentPort``."""

    def __init__(
        self,
        journal: AssessmentJournal,
        port: AssessmentPort,
        dossiers: DossierRetention,
        inventory: Callable[[], DeviceInventory],
        *,
        facts: Callable[[str], DeviceFacts | None] = lambda _: None,
        profile: Callable[[DeviceCard], Awaitable[ProfileMatch]] | None = None,
        clock: Callable[[], str] = utc_now,
        new_id: Callable[[], str] = lambda: str(uuid4()),
        zone: Callable[[], str] = lambda: "UTC",
        flagged: Callable[[str, str, str], None] = lambda device, report, at: None,
        deep_scan: Callable[[str], DeepScanResult | None] = lambda _: None,
    ):
        # The household zone (configuration timeZone), read when used: the
        # dashboard can change it while the collector runs. The daily ceiling
        # counts its day; each run keeps the zone it was admitted with.
        self.zone = zone
        # ``flagged(device_id, report_revision, requested_at)`` runs once when a
        # report with a live potential-breach or worth-checking concern reaches
        # review; ``requested_at`` is when its assessment was requested, so the
        # scanner can tell whether that dossier already carried its latest scan.
        # ``deep_scan(device_id)`` is the device's latest complete deep scan.
        self.flagged, self.deep_scan = flagged, deep_scan
        # ``profile`` reads the operator's saved profiles when an assessment
        # starts; None means this collector keeps no profiles.
        self.profile = profile
        self.journal, self.port, self.dossiers = journal, port, dossiers
        self.inventory, self.clock, self.new_id = inventory, clock, new_id
        # Retained supplemental facts by device ID (Worker.device_facts).
        self.facts = facts
        self.lock = asyncio.Lock()
        self.error: str | None = None
        # Devices whose dossier cannot be built are skipped by the background
        # queue until restart, rather than retried on every poll.
        self._unbuildable: set[str] = set()
        # Report revisions a status read showed processed. Not journalled: it
        # is Plowshare's state, and is read again after a restart.
        self._processed: set[str] = set()
        self._journal_available = True
        try:
            self._ledger = journal.read()
        except (OSError, ValueError):
            self._journal_available = False
            self._ledger = AssessmentLedger()
            self.error = "The device assessment journal needs inspection. Its retained data was preserved."

    @property
    def ledger(self) -> AssessmentLedger:
        return self._ledger

    # Ledger access. Writes happen between awaits only, so each is atomic with
    # respect to the event loop.

    def _store(self, ledger: AssessmentLedger) -> None:
        if not self._journal_available:
            raise ValueError(
                "The retained device assessment journal requires inspection"
            )
        self.journal.save(ledger)
        self._ledger = ledger

    def _entry(self, device_id: str) -> AssessmentEntry | None:
        return next((e for e in self.ledger.entries if e.device_id == device_id), None)

    def _put(self, entry: AssessmentEntry, admitted: str | None = None) -> None:
        others = tuple(e for e in self.ledger.entries if e.device_id != entry.device_id)
        if len(others) >= MAX_ENTRIES:
            raise AssessmentConflict("The assessment ledger is full")
        admissions = self._today()
        if admitted is not None:
            admissions = (*admissions, admitted)
        self._store(
            replace(self.ledger, entries=(*others, entry), admissions=admissions)
        )

    def _today(self) -> tuple[str, ...]:
        zone = self.zone()
        day = household_day(self.clock(), zone)
        return tuple(a for a in self.ledger.admissions if household_day(a, zone) == day)

    def _card(self, device_id: str) -> DeviceCard:
        device = parse_device_id(device_id)
        card = next(
            (c for d, c in monitored_devices(self.inventory()) if d == device), None
        )
        if card is None:
            raise UnknownDevice(
                "Choose a monitored device with a confirmed MAC address"
            )
        return card

    def _active(self) -> AssessmentEntry | None:
        return next((e for e in self.ledger.entries if e.phase in _ACTIVE), None)

    def _assess_note(self, entry: AssessmentEntry | None) -> str | None:
        if not self._journal_available:
            return "The assessment journal needs inspection before new assessments."
        active = self._active()
        if active is not None and (entry is None or active is not entry):
            return "Another device's assessment is running. One runs at a time."
        if len(self._today()) >= DAILY_CEILING:
            return (
                f"The daily limit of {DAILY_CEILING} assessments is reached."
                f" Try again after midnight ({self.zone()})."
            )
        return None

    # Dashboard projection and decisions.

    def view(self, device_id: str) -> AssessmentView:
        self._card(device_id)
        entry = self._entry(device_id)
        note = self._assess_note(entry)
        phase = entry.phase if entry is not None else None
        # Assess is offered from idle or a settled state; note says what else blocks it.
        open_to_assess = phase is None or phase in _SETTLED
        deciding = phase == "review" and self._journal_available
        processing = (
            phase == "review"
            and entry is not None
            and entry.report not in self._processed
        )
        return AssessmentView(
            device_id=device_id,
            state="idle" if phase is None else _STATE[phase],
            pending="admission"
            if phase == "pending"
            else "accept"
            if phase == "accepting"
            else None,
            request_id=entry.request_id if entry else None,
            run_id=entry.run_id if entry else None,
            report=entry.report if entry else None,
            requested_at=entry.requested_at if entry else None,
            decided_at=entry.decided_at if entry else None,
            review=entry.review if entry else None,
            concerns=entry.concerns if entry else (),
            detail=entry.detail if entry else None,
            can_assess=open_to_assess and note is None,
            can_accept=deciding and not processing,
            can_dismiss=deciding,
            assess_note=note if open_to_assess else None,
            processing=processing,
        )

    def summaries(self) -> tuple[AssessmentSummary, ...]:
        return tuple(
            summary(
                entry.device_id,
                _STATE[entry.phase],
                "admission"
                if entry.phase == "pending"
                else "accept"
                if entry.phase == "accepting"
                else None,
                entry.phase == "review" and entry.report not in self._processed,
                entry.concerns,
                entry.requested_at,
            )
            for entry in self.ledger.entries
        )

    def usage(self) -> AssessmentUsage:
        return AssessmentUsage(
            self.ledger.background_limit, len(self._today()), DAILY_CEILING
        )

    async def assess(self, device_id: str) -> AssessmentView:
        card = self._card(device_id)
        async with self.lock:
            await self._admit(card, "operator")
        return self.view(device_id)

    async def accept(self, device_id: str) -> AssessmentView:
        self._card(device_id)
        async with self.lock:
            entry = self._entry(device_id)
            if entry is None or entry.phase != "review" or entry.report is None:
                raise AssessmentConflict(
                    "Only an assessment awaiting review can be accepted"
                )
            if entry.report not in self._processed:
                raise AssessmentConflict(
                    "The report is still being processed by Plowshare. Accept it when processing is done."
                )
            accepting = replace(
                entry, phase="accepting", finalise_id=self.new_id(), detail=None
            )
            self._put(accepting)
            try:
                await self.port.finalise(accepting.finalise_id or "", entry.report)
            except Refusal as error:
                if _STILL_PROCESSING in (error.said or ""):
                    # Processing went back (or our read was early): wait for
                    # the next status read rather than show the long guidance.
                    self._processed.discard(entry.report)
                    self._put(replace(entry, detail=_PROCESSING_DETAIL))
                else:
                    detail = refusal_detail(error, "finalising this assessment report")
                    self._put(replace(entry, detail=detail + _FINALISE_GUIDANCE))
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self._put(
                        replace(
                            entry,
                            detail="Accept was not sent to Plowshare. Choose Accept again when the connection is back.",
                        )
                    )
                else:
                    self._put(
                        replace(
                            accepting,
                            detail="Plowshare did not confirm the Accept. It was not resent; Check status reads whether the report is final.",
                        )
                    )
            else:
                self._put(replace(accepting, phase="accepted", decided_at=self.clock()))
        return self.view(device_id)

    async def check(self, device_id: str) -> AssessmentView:
        """Settle this device by reading only: a receipt, run state or report status."""
        self._card(device_id)
        async with self.lock:
            entry = self._entry(device_id)
            if entry is None:
                raise AssessmentConflict("This device has no assessment to check")
            if entry.phase in _ACTIVE:
                await self._settle(entry)
            elif entry.phase in _DECIDING:
                await self._settle_finalise(entry)
        return self.view(device_id)

    def dismiss(self, device_id: str) -> AssessmentView:
        """Record the operator's dismissal locally. No server request is made."""
        self._card(device_id)
        entry = self._entry(device_id)
        if entry is None or entry.phase != "review":
            raise AssessmentConflict(
                "Only an assessment awaiting review can be dismissed"
            )
        self._put(
            replace(entry, phase="dismissed", decided_at=self.clock(), detail=None)
        )
        return self.view(device_id)

    async def report(self, device_id: str) -> AssessmentReport:
        """The current report text; a dismissed draft is not shown."""
        self._card(device_id)
        entry = self._entry(device_id)
        if (
            entry is None
            or entry.report is None
            or entry.phase not in {"review", "accepting", "accepted"}
        ):
            raise AssessmentConflict("This device has no assessment report to show")
        return AssessmentReport(
            entry.report, await self.port.read_report(device_id, entry.report)
        )

    def set_background_limit(self, limit: int) -> None:
        if type(limit) is not int or not 0 <= limit <= DAILY_CEILING:
            raise ValueError(f"Choose between 0 and {DAILY_CEILING} assessments a day")
        self._store(replace(self.ledger, background_limit=limit))

    # Admission and settlement. Callers hold ``self.lock``.

    async def _admit(self, card: DeviceCard, trigger: str) -> None:
        if not self._journal_available:
            raise ValueError(
                "The retained device assessment journal requires inspection"
            )
        device = card.identity.device_id if card.identity else ""
        entry = self._entry(device)
        if entry is not None and entry.phase not in _SETTLED:
            raise AssessmentConflict(
                "Accept or dismiss the current assessment before assessing again"
                if entry.phase in _DECIDING
                else "This device's assessment is already running"
            )
        note = self._assess_note(entry)
        if note is not None:
            raise AssessmentConflict(note)
        # The operator's expectations are read fresh for each assessment, so a
        # profile saved a moment ago is the one the report cites. A failed read
        # stops here: nothing is retained or started without it.
        match = await self.profile(card) if self.profile is not None else None
        dossier = build_dossier(
            card,
            match.profile if match is not None else None,
            facts=self.facts(device),
            profile_note=match.note if match is not None else None,
            deep_scan=self.deep_scan(device),
        )
        zone = self.zone()
        retained = await self.dossiers.retain(dossier, zone)
        previous = await self.port.newest_report(device)
        known = entry.latest_report if entry is not None else None
        if known is not None and previous is None:
            raise AssessmentConflict(
                "Plowshare no longer lists this device's earlier assessment report."
                " A new run could create a second report with the same name;"
                " inspect the project's reports first."
            )
        now = self.clock()
        pending = AssessmentEntry(
            device,
            self.new_id(),
            retained.revision,
            previous,
            now,
            trigger,  # type: ignore[arg-type]
            tuple(signal.code for signal in dossier.signals),
            "pending",
            latest_report=previous,
            time_zone=zone,
        )
        pending.request()
        # Flushed before transport: the request UUID is the only way to settle it.
        self._put(pending, admitted=now)
        try:
            run = await self.port.start(pending.request_id, pending.request())
        except Refusal as error:
            self._put(
                replace(
                    pending,
                    phase="failed",
                    detail=refusal_detail(error, "starting this assessment"),
                )
            )
        except TransportError as error:
            if error.delivery == Delivery.NOT_SUBMITTED:
                self._put(
                    replace(
                        pending,
                        phase="failed",
                        detail="The start request was not sent to Plowshare. Choose Retry when the connection is back.",
                    )
                )
            else:
                self._put(
                    replace(
                        pending,
                        detail="Plowshare did not confirm the start. It is not resent; its admission receipt settles it.",
                    )
                )
        else:
            self._put(replace(pending, phase="running", run_id=run))

    async def _settle(self, entry: AssessmentEntry) -> None:
        if entry.phase == "pending":
            try:
                run = await self.port.receipt(entry.request_id)
            except Refusal as error:
                # Absence of a readable receipt is not proof of non-admission.
                self._put(
                    replace(
                        entry,
                        detail=refusal_detail(error, "reading the admission receipt"),
                    )
                )
                return
            self._put(replace(entry, phase="running", run_id=run, detail=None))
            return
        if entry.run_id is None:
            raise ValueError("Running assessment is missing its run ID")
        outcome = await self.port.outcome(entry.run_id)
        if outcome.state == "finished":
            try:
                if outcome.result is None:
                    raise ValueError("Missing assessment result")
                result = AssessmentResult.decode(outcome.result)
                result.require(entry.request())
            except ValueError:
                self._put(
                    replace(
                        entry,
                        phase="failed",
                        detail=f"Run {entry.run_id} finished but its result could not be validated, so no report was recorded here. Inspect the run in Plowshare.",
                    )
                )
                return
            self._put(
                replace(
                    entry,
                    phase="review",
                    report=result.report_revision,
                    resource=result.report_resource,
                    review=result.review,
                    concerns=result.concerns,
                    latest_report=result.report_revision,
                    detail=None,
                )
            )
            self._flag(
                entry.device_id,
                result.report_revision,
                entry.requested_at,
                result.concerns,
            )
        elif outcome.state in {"failed", "cancelled", "capped"}:
            reason = {
                "failed": "The run failed.",
                "cancelled": "The run was cancelled.",
                "capped": "The run reached its model-call or budget cap.",
            }[outcome.state]
            self._put(
                replace(
                    entry, phase="failed", detail=reason + " No report was written."
                )
            )

    def _flag(
        self,
        device_id: str,
        report: str,
        requested_at: str,
        concerns: tuple[ConcernOutcome, ...],
    ) -> None:
        """Tell the deep scanner a report needs a closer look, once, on entering review.

        Called only from the transition into review, after the entry is saved, so
        polling a report already in review (or one restored at startup) never
        triggers it. A failing callback must not undo the transition.
        """
        if not any(
            c.verdict != "refuted" and c.level in {"potential_breach", "worth_checking"}
            for c in concerns
        ):
            return
        try:
            self.flagged(device_id, report, requested_at)
        except Exception as error:  # noqa: BLE001 - the transition is already saved
            self.error = (
                "A deep port scan could not be queued for this report: "
                f"{error or type(error).__name__}."
            )

    async def _settle_finalise(self, entry: AssessmentEntry) -> None:
        if entry.report is None:
            raise ValueError("Assessment decision is missing its report")
        state = await self.port.report_state(entry.device_id, entry.report)
        if state.processed:
            self._processed.add(entry.report)
        else:
            self._processed.discard(entry.report)
        if state.status in {"final", "superseded"}:
            self._put(
                replace(entry, phase="accepted", decided_at=self.clock(), detail=None)
            )
        elif entry.phase == "accepting":
            self._put(
                replace(
                    entry,
                    detail="Plowshare still shows this report as a draft. The Accept was not resent; Check status again later.",
                )
            )
        elif entry.detail == _PROCESSING_DETAIL and state.processed:
            self._put(replace(entry, detail=None))

    async def poll(self) -> None:
        async with self.lock:
            active = self._active()
            if active is not None:
                await self._settle(active)
            for entry in self.ledger.entries:
                # A report awaiting review is read until it shows processed,
                # so Accept appears without the operator asking.
                if entry.phase == "accepting" or (
                    entry.phase == "review" and entry.report not in self._processed
                ):
                    await self._settle_finalise(entry)
            if active is not None or self._active() is not None:
                return
            limit = self.ledger.background_limit
            if limit == 0 or len(self._today()) >= min(limit, DAILY_CEILING):
                return
            card = self._background_candidate()
            if card is None:
                return
            try:
                await self._admit(card, "background")
            except ValueError:
                device = card.identity.device_id if card.identity else ""
                self._unbuildable.add(device)
                raise

    def _background_candidate(self) -> DeviceCard | None:
        """The next device the background queue should assess, if any.

        In order: a signalled device never assessed; a never-assessed device
        with learned facts; a settled device whose (non-empty) signal set
        changed or whose facts changed after its last admission. Facts change
        only when their content does (``DeviceFacts.updated_at``), so a daily
        re-description with the same answer requeues nothing.
        """
        learned = changed = None
        for device, card in monitored_devices(self.inventory()):
            if device in self._unbuildable:
                continue
            signals = {signal.code for signal in device_signals(card)}
            facts = self.facts(device)
            known = _learned(facts)
            entry = self._entry(device)
            if entry is None:
                if signals:
                    return card
                if learned is None and known:
                    learned = card
                continue
            if changed is None and entry.phase in _SETTLED:
                if (signals and signals != set(entry.signals)) or (
                    known
                    and facts is not None
                    and epoch(facts.updated_at) > epoch(entry.requested_at)
                ):
                    changed = card
        return learned or changed

    async def run(self, stop: asyncio.Event) -> None:
        if not self._journal_available:
            return
        while not stop.is_set():
            try:
                self.error = None
                await self.poll()
            except (
                Refusal,
                TransportError,
                ValueError,
                OSError,
                AssessmentConflict,
                ReconciliationRequired,
            ):
                self.error = "Device assessments need attention. Retained requests and decisions were kept."
            try:
                await asyncio.wait_for(stop.wait(), 30)
            except TimeoutError:
                continue


def _learned(facts: DeviceFacts | None) -> bool:
    """True when the facts hold something to assess: a self-description or a search."""
    return facts is not None and (
        bool(facts.descriptions)
        or (facts.advisories is not None and facts.advisories.status == "searched")
    )


def summary(
    device_id: str,
    state: State,
    pending: Literal["admission", "accept"] | None,
    processing: bool,
    concerns: tuple[ConcernOutcome, ...],
    requested_at: str | None,
) -> AssessmentSummary:
    """Count the concerns a reader still has to weigh in a live report."""
    live = (
        tuple(c for c in concerns if c.verdict != "refuted")
        if state in {"review", "accepted"}
        else ()
    )
    return AssessmentSummary(
        device_id,
        state,
        pending,
        processing,
        sum(c.level == "potential_breach" for c in live),
        sum(c.level == "worth_checking" for c in live),
        requested_at,
    )
