"""Queue background device drafts and retain the operator's review decisions.

Plowshare owns inference and execution receipts. This collector-owned journal
fences paid admissions and holds draft projections, never confirmed profiles.
"""

from __future__ import annotations

import asyncio
import json
import re
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Protocol
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError

from .advisories import AdvisoryLookup, AdvisoryPage, AdvisoryUnavailable, public_model
from .contracts import (
    DeviceIdentity,
    TcpObservation,
    household_day,
    identifier,
    items,
    load_json,
    object_fields,
    parse_json,
    private_ipv4,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .device_detection import detect_device
from .device_profiles import field
from .identity_source import (
    ManagedInventory,
    ManagedProduct,
    ManagedProductLookup,
    ManagedProductUnavailable,
)
from .journal import Receipts
from .presence import PresenceView, epoch
from .private_files import replace_private

MAX_RECORDS = 256
MAX_JOURNAL_BYTES = 6 * 1024 * 1024
DAILY_LIMIT = 4
MIN_SIGHTINGS = 3
FRESH_SECONDS = 720
_CVE = re.compile(r"\bCVE-[0-9]{4}-[0-9]{4,19}\b", re.I)
_ACTIVE = {"pending", "running"}
_PHASES = _ACTIVE | {"queued", "review", "reviewed", "dismissed", "failed"}


@dataclass(frozen=True)
class ProfileDraft:
    name: str
    model_hypothesis: str
    purpose: str
    expected_services: str
    notes: str
    questions: str

    @classmethod
    def decode(cls, value: object) -> ProfileDraft:
        row = object_fields(
            value,
            {
                "name",
                "model_hypothesis",
                "purpose",
                "expected_services",
                "notes",
                "questions",
            },
        )
        values = cls(
            field(row["name"], 128, required=True),
            field(row["model_hypothesis"], 128),
            field(row["purpose"], 512),
            field(row["expected_services"], 1024),
            field(row["notes"], 1024),
            field(row["questions"], 512),
        )
        if any(
            marker in value
            for value in asdict(values).values()
            for marker in ("<|", "<tool_call", "<function=")
        ):
            raise ValueError("Model response contains raw tool-call markup")
        return values


@dataclass(frozen=True)
class ResearchEntry:
    request_id: str
    identity: DeviceIdentity
    phase: str
    model: str | None = None
    feedback: str = ""
    run_id: str | None = None
    draft: ProfileDraft | None = None
    advisory_ids: tuple[str, ...] = ()
    model_reviewed: bool = False
    nvd_status: str | None = None

    @classmethod
    def decode(cls, value: object) -> ResearchEntry:
        row = object_fields(
            value,
            {
                "request_id",
                "identity",
                "phase",
                "model",
                "feedback",
                "run_id",
                "draft",
                "advisory_ids",
                "model_reviewed",
                "nvd_status",
            },
        )
        identity = DeviceIdentity.decode(row["identity"])
        if (
            not identity.mac
            or not private_ipv4(identity.address)
            or identity.source == "configured"
        ):
            raise ValueError("Research requires an observed MAC at a private address")
        if type(row["model_reviewed"]) is not bool:
            raise ValueError("Invalid confirmed model state")
        phase = text(row["phase"])
        if phase not in _PHASES or (
            phase in {"running", "review", "reviewed"} and row["run_id"] is None
        ):
            raise ValueError("Invalid profile research phase")
        draft = ProfileDraft.decode(row["draft"]) if row["draft"] is not None else None
        if phase in {"review", "reviewed"} and draft is None:
            raise ValueError("Review requires a retained draft")
        advisory_ids = tuple(text(v, 32) for v in items(row["advisory_ids"], 10))
        if any(not _CVE.fullmatch(v) for v in advisory_ids):
            raise ValueError("Invalid advisory reference")
        nvd_status = (
            text(row["nvd_status"], 32) if row["nvd_status"] is not None else None
        )
        if nvd_status not in {
            None,
            "searched",
            "model_unconfirmed",
            "not_configured",
            "unavailable",
        }:
            raise ValueError("Invalid NVD lookup status")
        if advisory_ids and nvd_status != "searched":
            raise ValueError("Advisories require a confirmed lookup")
        return cls(
            uuid(row["request_id"]),
            identity,
            phase,
            public_model(text(row["model"], 128)) if row["model"] is not None else None,
            field(row["feedback"], 512),
            identifier(row["run_id"]) if row["run_id"] is not None else None,
            draft,
            advisory_ids,
            row["model_reviewed"],
            nvd_status,
        )


@dataclass(frozen=True)
class ResearchLedger:
    enabled: bool = False
    entries: tuple[ResearchEntry, ...] = ()
    admissions: tuple[str, ...] = ()
    version: int = 1

    @classmethod
    def decode(cls, value: object) -> ResearchLedger:
        row = object_fields(value, {"version", "enabled", "entries", "admissions"})
        if (
            type(row["version"]) is not int
            or row["version"] != 1
            or type(row["enabled"]) is not bool
        ):
            raise ValueError("Invalid profile research preference")
        entries = tuple(
            ResearchEntry.decode(entry) for entry in items(row["entries"], MAX_RECORDS)
        )
        admissions = tuple(
            timestamp(item) for item in items(row["admissions"], DAILY_LIMIT)
        )
        if (
            len({e.identity.device_id for e in entries}) != len(entries)
            or len({e.request_id for e in entries}) != len(entries)
            or sum(e.phase in _ACTIVE for e in entries) > 1
        ):
            raise ValueError("Conflicting profile research receipts")
        return cls(row["enabled"], entries, admissions)


def reviewed_model(ledger: ResearchLedger, device_id: str) -> str | None:
    """The public model the operator confirmed for a device in review, if any.

    Only a model the operator entered (``model_reviewed``) counts; a model the
    collector inferred from the controller is not an operator confirmation.
    """
    return next(
        (
            entry.model
            for entry in ledger.entries
            if entry.identity.device_id == device_id and entry.model_reviewed
        ),
        None,
    )


class ResearchJournal:
    def __init__(self, root: Path):
        self.path = root / "profile-research.json"

    def read(self) -> ResearchLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Profile research journal cannot be linked")
        if not self.path.exists():
            return ResearchLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Profile research journal must be private")
        return ResearchLedger.decode(
            load_json(self.path, maximum_bytes=MAX_JOURNAL_BYTES)
        )

    def save(self, value: ResearchLedger) -> None:
        # Validate the serialized boundary as well as the in-memory transition.
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        ResearchLedger.decode(parse_json(content, maximum_bytes=MAX_JOURNAL_BYTES))
        replace_private(self.path, content)


@dataclass(frozen=True)
class ResearchEvidence:
    name_hint: str | None
    vendor_hint: str | None
    source: str
    observed_at: str
    device_type: str
    clues: tuple[str, ...]
    open_tcp_ports: tuple[int, ...]
    evidence_revision: str | None
    product: ManagedProduct | None
    public_model: str | None
    nvd: AdvisoryPage | None
    nvd_status: str
    feedback: str


@dataclass(frozen=True)
class ResearchOutcome:
    state: str
    result: str | None = None


class ResearchPort(Protocol):
    async def start(self, request_id: str, evidence: ResearchEvidence) -> str: ...
    async def receipt(self, request_id: str) -> str: ...
    async def outcome(self, run_id: str) -> ResearchOutcome: ...


def admitted_today(ledger: ResearchLedger, now: str, zone: str = "UTC") -> int:
    """Admissions on ``now``'s household day, the window ``DAILY_LIMIT`` applies to."""
    day = household_day(now, zone)
    return sum(household_day(stamp, zone) >= day for stamp in ledger.admissions)


class ResearchReview(Protocol):
    """Dashboard projection and explicit review controls for background drafts."""

    @property
    def ledger(self) -> ResearchLedger: ...

    @property
    def error(self) -> str | None: ...

    def enable(self, enabled: bool) -> None: ...
    def entry(self, request_id: str) -> ResearchEntry: ...
    def draft_for_editor(self, request_id: str) -> ResearchEntry: ...
    def review(
        self, request_id: str, action: str, model: str | None, feedback: str
    ) -> None: ...


class ProfileResearch:
    """Run one bounded draft at a time without holding the collection locks."""

    def __init__(
        self,
        journal: ResearchJournal,
        port: ResearchPort,
        presence: Callable[[], PresenceView],
        receipts: Receipts,
        managed_products: Callable[[], ManagedProductLookup | None],
        advisories: AdvisoryLookup | None,
        *,
        clock: Callable[[], str] = utc_now,
        zone: str = "UTC",
    ):
        # Daily allowances follow the household's day (configuration timeZone).
        self.zone = zone
        self.journal = journal
        self.port = port
        self.presence = presence
        self.receipts = receipts
        self.managed_products = managed_products
        self.advisories = advisories
        self.clock = clock
        self.error: str | None = None
        # Reads/writes have no awaits. Poll never holds this event-loop-owned state
        # across network IO, so operator review can progress during tool calls.
        self._journal_available = True
        try:
            self._ledger = journal.read()
        except (OSError, ValueError):
            self._journal_available = False
            self._ledger = ResearchLedger()
            self.error = "The profile research journal needs inspection. Its retained data was preserved."

    @property
    def ledger(self) -> ResearchLedger:
        return self._ledger

    def _store(self, ledger: ResearchLedger) -> None:
        if not self._journal_available:
            raise ValueError(
                "The retained profile research journal requires inspection"
            )
        self.journal.save(ledger)
        self._ledger = ledger

    def _put(
        self, entry: ResearchEntry, admissions: tuple[str, ...] | None = None
    ) -> None:
        entries = tuple(
            item
            for item in self.ledger.entries
            if item.identity.device_id != entry.identity.device_id
        )
        self._store(
            replace(
                self.ledger,
                entries=(*entries, entry),
                admissions=self.ledger.admissions if admissions is None else admissions,
            )
        )

    def enable(self, enabled: bool) -> None:
        self._store(replace(self.ledger, enabled=enabled))

    def entry(self, request_id: str) -> ResearchEntry:
        entry = next(
            (entry for entry in self.ledger.entries if entry.request_id == request_id),
            None,
        )
        if entry is None:
            raise ValueError("Choose a retained device research draft")
        return entry

    def _confirmed(self, entry: ResearchEntry) -> DeviceIdentity | None:
        return next(
            (
                identity
                for identity in self._candidates()
                if identity.device_id == entry.identity.device_id
            ),
            None,
        )

    def review(
        self, request_id: str, action: str, model: str | None, feedback: str
    ) -> None:
        entry = self.entry(request_id)
        if (
            action == "research"
            and entry.phase in {"review", "failed"}
            and (model or feedback)
        ):
            if not self.ledger.enabled or self._confirmed(entry) is None:
                raise ValueError("Enable research and wait for a fresh MAC sighting")
            public = public_model(model) if model is not None else None
            private_names = {
                value.casefold()
                for value in (entry.identity.hostname, entry.identity.label)
                if value
            }
            if public and public.casefold() in private_names:
                raise ValueError(
                    "Enter a public manufacturer and model rather than a device name"
                )
            self._put(
                replace(
                    entry,
                    phase="queued",
                    model=public,
                    feedback=feedback,
                    model_reviewed=True,
                )
            )
        elif (
            action in {"reviewed", "dismissed"}
            and entry.phase in {"review", "failed", "queued"}
            and model is None
            and not feedback
        ):
            if action == "reviewed" and entry.phase != "review":
                raise ValueError("Review requires a retained draft")
            self._put(replace(entry, phase=action))
        else:
            raise ValueError("Invalid device research review action")

    def draft_for_editor(self, request_id: str) -> ResearchEntry:
        entry = self.entry(request_id)
        current = self._confirmed(entry)
        if entry.phase != "review" or current is None:
            raise ValueError(
                "Wait for a fresh association for this device before using its draft"
            )
        return replace(entry, identity=current)

    def _candidates(self) -> tuple[DeviceIdentity, ...]:
        view = self.presence()
        now = epoch(self.clock())
        result = []
        for sighting in view.sightings.values():
            identity = sighting.identity
            count = view.counts.get(sighting.key)
            if (
                identity
                and identity.mac
                and identity.source != "configured"
                and count
                and count.seen >= MIN_SIGHTINGS
                and 0 <= now - epoch(identity.observed_at) <= FRESH_SECONDS
            ):
                result.append(identity)
        return tuple(result)

    def _retained_tcp(
        self, identity: DeviceIdentity
    ) -> tuple[tuple[TcpObservation, ...], str | None]:
        # Match the MAC and address in the same full sweep before using port clues.
        for receipt in reversed(self.receipts.all()[-50:]):
            if (
                receipt.phase != "done"
                or receipt.evidence is None
                or receipt.revision is None
            ):
                continue
            snapshot = receipt.evidence.snapshot
            if any(
                device.address == identity.address
                and device.device_id == identity.device_id
                for device in snapshot.devices
            ):
                return tuple(
                    probe for probe in snapshot.tcp if probe.address == identity.address
                ), receipt.revision
        return (), None

    async def _evidence(self, entry: ResearchEntry) -> ResearchEvidence:
        identity = entry.identity
        product = None
        lookup = self.managed_products()
        if lookup is not None:
            try:
                product = await lookup.managed_product(identity)
            except ManagedProductUnavailable:
                pass  # Controller metadata is optional; the draft marks the model unknown.
        model = entry.model
        if model is None and product is not None and not entry.model_reviewed:
            try:
                model = public_model(product.manufacturer + " " + product.model)
            except ValueError:
                pass  # Treat a nonpublic controller model string as an unconfirmed clue.
        nvd = None
        nvd_status = "model_unconfirmed" if model is None else "not_configured"
        if model is not None and self.advisories is not None:
            try:
                nvd = await self.advisories.search(model)
                if nvd.model != model:
                    nvd = None
                    raise AdvisoryUnavailable("Foreign advisory result")
                nvd_status = "searched"
            except AdvisoryUnavailable:
                nvd_status = "unavailable"
        tcp, revision = self._retained_tcp(identity)
        detection = detect_device(identity, tcp)
        return ResearchEvidence(
            identity.hostname or identity.label,
            identity.vendor,
            identity.source,
            identity.observed_at,
            detection.device_type,
            detection.clues,
            tuple(sorted({probe.port for probe in tcp if probe.status == "open"})),
            revision,
            product,
            model,
            nvd,
            nvd_status,
            entry.feedback,
        )

    async def poll(self) -> None:
        active = next(
            (entry for entry in self.ledger.entries if entry.phase in _ACTIVE), None
        )
        if active is not None:
            await self._settle(active)
            return
        if not self.ledger.enabled:
            return
        now = self.clock()
        day = household_day(now, self.zone)
        admissions = tuple(
            stamp
            for stamp in self.ledger.admissions
            if household_day(stamp, self.zone) >= day
        )
        if len(admissions) >= DAILY_LIMIT:
            return
        observed = self._candidates()
        inventory = self.managed_products()
        if isinstance(inventory, ManagedInventory):
            observed = tuple(
                sorted(
                    observed,
                    key=lambda identity: not inventory.recently_managed(identity),
                )
            )
        candidates = {identity.device_id: identity for identity in observed}
        existing = {entry.identity.device_id: entry for entry in self.ledger.entries}
        queued = next(
            (
                entry
                for entry in existing.values()
                if entry.phase == "queued" and entry.identity.device_id in candidates
            ),
            None,
        )
        if queued is not None:
            entry = replace(queued, identity=candidates[queued.identity.device_id])
        else:
            identity = next(
                (value for key, value in candidates.items() if key not in existing),
                None,
            )
            if identity is None or len(existing) >= MAX_RECORDS:
                return
            entry = ResearchEntry(str(uuid4()), identity, "queued")

        evidence = await self._evidence(entry)
        # Review controls and sightings may change while external read tools run.
        current = self._confirmed(entry)
        if (
            not self.ledger.enabled
            or current is None
            or current.address != entry.identity.address
            or (queued is not None and queued not in self.ledger.entries)
        ):
            return
        pending = replace(
            entry,
            request_id=str(uuid4()),
            identity=current,
            phase="pending",
            run_id=None,
            draft=None,
            model=evidence.public_model,
            nvd_status=evidence.nvd_status,
            advisory_ids=tuple(item.cve for item in evidence.nvd.advisories)
            if evidence.nvd
            else (),
        )
        self._put(pending, (*admissions, now))
        try:
            run = await self.port.start(pending.request_id, evidence)
        except Refusal:
            self._put(replace(pending, phase="failed"))
            self.error = "Plowshare refused the profile research admission."
        except TransportError as error:
            if error.delivery == Delivery.NOT_SUBMITTED:
                self._put(replace(pending, phase="failed"))
            self.error = "Profile admission unavailable. Any uncertain start is settled by receipt only."
        else:
            self._put(replace(pending, phase="running", run_id=run))

    async def _settle(self, entry: ResearchEntry) -> None:
        if entry.phase == "pending":
            run = await self.port.receipt(entry.request_id)
            self._put(replace(entry, phase="running", run_id=run))
            return
        if entry.run_id is None:
            raise ValueError("Profile research admission is missing its run ID")
        outcome = await self.port.outcome(entry.run_id)
        if outcome.state == "finished":
            try:
                if outcome.result is None:
                    raise ValueError("Missing device draft")
                draft = ProfileDraft.decode(
                    parse_json(outcome.result, maximum_bytes=8192)
                )
                used_ids = {
                    m.upper()
                    for value in asdict(draft).values()
                    for m in _CVE.findall(value)
                }
                if used_ids - set(entry.advisory_ids):
                    raise ValueError(
                        "Draft cites an advisory outside the retained lookup"
                    )
            except ValueError:
                self._put(replace(entry, phase="failed"))
                self.error = "The model output could not be validated. Inspect the retained Plowshare run."
            else:
                self._put(replace(entry, phase="review", draft=draft))
        elif outcome.state in {"failed", "cancelled", "capped"}:
            self._put(replace(entry, phase="failed"))

    async def run(self, stop: asyncio.Event) -> None:
        if not self._journal_available:
            return
        while not stop.is_set():
            try:
                self.error = None
                await self.poll()
            except (Refusal, TransportError, ValueError, OSError):
                self.error = "Profile research needs attention. Existing drafts and admissions were retained."
            try:
                await asyncio.wait_for(stop.wait(), 30)
            except TimeoutError:
                continue
