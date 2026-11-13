"""The device reference library: a journal of vendor documentation acquisitions.

Each reference is one https documentation page, fetched into the Plowshare Library
with ``information.acquire`` and then tagged ``device-reference``, its product slug
and its topics so the assistant can find it. The collector owns every fetch; the
journal (``references.json``, private) keeps one receipt per URL so a restart
neither loses an acquisition in flight nor submits it twice.

Phase machine. ``advance`` moves every receipt at most one step, in journal order:

    queued ──acquire──▶ submitted ──ticket id──▶ awaiting ──succeeded──▶ retained
                            │                       │  ├──blocked──▶ blocked
                            │ (uncertain delivery:  │  └──failed───▶ failed
                            │  find the ticket)     │
                            ▼                       ▼
    retained ──tags (∪ required)──▶ tagged ──retired: tags (− device-reference)──▶
                                              tagged, retired_tagged

- The journal is saved *before* every mutating SDK call, so the intent is durable
  when the outcome is uncertain. ``queued`` is saved as ``submitted`` before
  ``acquire``. A ``TransportError`` with ``Delivery.NOT_SUBMITTED`` restores the
  previous phase; any other delivery keeps ``submitted`` without an acquisition id,
  and a later ``advance`` looks the ticket up in ``information.acquisitions``
  instead of acquiring again. Transport errors are then re-raised to the caller,
  as the scan worker does, so connection loss is reported once rather than per
  receipt.
- A ``Refusal`` from a per-receipt call (acquire, revision, tags) is recorded as
  ``failed`` with the redacted server reason, and the remaining receipts continue.
- ``blocked`` and ``failed`` are terminal until the owner presses Retry. Nothing
  retries automatically: ``plan_seed`` never touches a failed receipt and
  ``advance`` never re-reads a blocked or failed ticket.
- Tagging merges with the revision's existing tags and sends its ``tags_version``
  as the expected version. A revision that already carries the required tags is
  not re-tagged (so a refresh does not pay for a tags call). Each tags request id
  is derived from the receipt, the step, the revision and the version read, so a
  replay after uncertain delivery is idempotent and a fresh read after a version
  conflict is a fresh request.
- A seed entry dropped from a new seed is ``retired``: its source is kept so
  existing citations resolve, only the ``device-reference`` tag is removed so it
  is no longer searched. A retired entry that was never submitted is not fetched.

Server text (ticket errors, gate reasons) is untrusted: it is stripped of control
characters and bounded before it is journaled, and it is never interpreted.
"""

from __future__ import annotations

import asyncio
import json
import math
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Literal
from uuid import UUID, uuid4, uuid5

from plowshare import Delivery, Refusal, TransportError
from plowshare.contracts import (
    InformationAcquisitionDto,
    InformationGateDto,
    InformationRevisionDto,
)

from .contracts import (
    integer,
    items,
    load_json,
    object_fields,
    parse_json,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .diagnostics import refusal_detail
from .private_files import replace_private
from .reference_port import ReferencePort
from .reference_seed import MAX_TOPICS, SLUG, SeedEntry, product_slug, reference_url

Phase = Literal[
    "queued", "submitted", "awaiting", "blocked", "retained", "tagged", "failed"
]
Origin = Literal["seed", "owner"]

LIBRARY_TAG = "device-reference"
MAX_RECEIPTS = 256
MAX_TITLE = 200
MAX_ERROR = 1000
MAX_LEDGER_BYTES = 2 * 1024 * 1024
DEFAULT_REFRESH_DAYS = 90
MAX_REFRESH_DAYS = 365

_PHASES: dict[str, Phase] = {
    "queued": "queued",
    "submitted": "submitted",
    "awaiting": "awaiting",
    "blocked": "blocked",
    "retained": "retained",
    "tagged": "tagged",
    "failed": "failed",
}
_ORIGINS: dict[str, Origin] = {"seed": "seed", "owner": "owner"}
# Phases that hold a live ticket id; ``submitted`` never does (see the module doc).
_TICKETED = {"awaiting", "blocked"}
_UNRESOLVED = (
    "The acquisition request's delivery was uncertain and its ticket has not been "
    "found yet."
)
_GATED = "The acquisition is held by a Library gate."
_UNEXPLAINED = "The acquisition failed without a server explanation."


def _phase(value: object) -> Phase:
    if not isinstance(value, str) or value not in _PHASES:
        raise ValueError("Invalid reference phase")
    return _PHASES[value]


def _origin(value: object) -> Origin:
    if not isinstance(value, str) or value not in _ORIGINS:
        raise ValueError("Invalid reference origin")
    return _ORIGINS[value]


def _topic(value: object) -> str:
    if not isinstance(value, str) or not SLUG.fullmatch(value):
        raise ValueError("Invalid reference topic")
    return value


def _optional(value: object, parse: Callable[[object], str]) -> str | None:
    return None if value is None else parse(value)


def _error(value: object) -> str:
    return text(value, MAX_ERROR)


def _server_text(value: object, fallback: str) -> str:
    """Untrusted server prose, made journal-safe: no control characters, bounded."""
    if not isinstance(value, str):
        return fallback
    cleaned = " ".join(
        "".join(" " if ord(c) < 32 or ord(c) == 127 else c for c in value).split()
    )
    return cleaned[:MAX_ERROR] or fallback


@dataclass(frozen=True)
class ReferenceReceipt:
    """One documentation page's acquisition, from queueing to tagging.

    ``request_id`` is the acquire idempotency key; it is fixed when the receipt is
    created and replaced only when a blocked or refused receipt is requeued.
    ``revision_id`` is the retained revision once known. ``retired`` marks a seed
    entry the current seed no longer lists; ``retired_tagged`` records that its
    ``device-reference`` tag has been removed. ``fetched_at`` is when it was tagged.
    """

    url: str
    product: str
    title: str
    topics: tuple[str, ...]
    origin: Origin
    request_id: str
    phase: Phase
    acquisition_id: str | None
    revision_id: str | None
    error: str | None
    retired: bool
    retired_tagged: bool
    fetched_at: str | None

    def __post_init__(self) -> None:
        reference_url(self.url)
        product_slug(self.product)
        text(self.title, MAX_TITLE)
        if len(self.topics) > MAX_TOPICS or len(set(self.topics)) != len(self.topics):
            raise ValueError("Invalid reference topics")
        for topic in self.topics:
            _topic(topic)
        _origin(self.origin)
        uuid(self.request_id)
        _phase(self.phase)
        _optional(self.acquisition_id, uuid)
        _optional(self.revision_id, uuid)
        _optional(self.error, _error)
        _optional(self.fetched_at, timestamp)
        if type(self.retired) is not bool or type(self.retired_tagged) is not bool:
            raise ValueError("Invalid reference retirement flags")
        if self.retired_tagged and not self.retired:
            raise ValueError("Only a retired reference can be untagged")
        if self.retired and self.origin != "seed":
            raise ValueError("Only a seed reference can be retired")
        if (self.phase in _TICKETED and self.acquisition_id is None) or (
            self.phase in {"queued", "submitted"} and self.acquisition_id is not None
        ):
            raise ValueError("A reference ticket id does not match its phase")
        if self.phase in {"retained", "tagged"} and self.revision_id is None:
            raise ValueError("A retained reference needs its revision")

    @classmethod
    def decode(cls, value: object) -> ReferenceReceipt:
        row = object_fields(
            value,
            {
                "url",
                "product",
                "title",
                "topics",
                "origin",
                "request_id",
                "phase",
                "acquisition_id",
                "revision_id",
                "error",
                "retired",
                "retired_tagged",
                "fetched_at",
            },
        )
        retired, retired_tagged = row["retired"], row["retired_tagged"]
        if type(retired) is not bool or type(retired_tagged) is not bool:
            raise ValueError("Invalid reference retirement flags")
        return cls(
            reference_url(row["url"]),
            product_slug(row["product"]),
            text(row["title"], MAX_TITLE),
            tuple(_topic(t) for t in items(row["topics"], MAX_TOPICS)),
            _origin(row["origin"]),
            uuid(row["request_id"]),
            _phase(row["phase"]),
            _optional(row["acquisition_id"], uuid),
            _optional(row["revision_id"], uuid),
            _optional(row["error"], _error),
            retired,
            retired_tagged,
            _optional(row["fetched_at"], timestamp),
        )


@dataclass(frozen=True)
class ReferenceLedger:
    # Journal order is processing order; URLs and request ids are unique.
    receipts: tuple[ReferenceReceipt, ...] = ()
    refresh_days: int = DEFAULT_REFRESH_DAYS

    def __post_init__(self) -> None:
        integer(self.refresh_days, 1, MAX_REFRESH_DAYS)
        if len(self.receipts) > MAX_RECEIPTS:
            raise ValueError("Too many device references")
        if len({r.url for r in self.receipts}) != len(self.receipts) or len(
            {r.request_id for r in self.receipts}
        ) != len(self.receipts):
            raise ValueError("Conflicting device reference receipts")

    @classmethod
    def decode(cls, value: object) -> ReferenceLedger:
        row = object_fields(value, {"receipts", "refresh_days"})
        return cls(
            tuple(
                ReferenceReceipt.decode(v) for v in items(row["receipts"], MAX_RECEIPTS)
            ),
            integer(row["refresh_days"], 1, MAX_REFRESH_DAYS),
        )


class ReferenceJournal:
    """The private ledger file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "references.json"

    def read(self) -> ReferenceLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Reference journal cannot be linked")
        if not self.path.exists():
            return ReferenceLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Reference journal must be private")
        return ReferenceLedger.decode(
            load_json(self.path, maximum_bytes=MAX_LEDGER_BYTES)
        )

    def save(self, value: ReferenceLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        ReferenceLedger.decode(parse_json(content, maximum_bytes=MAX_LEDGER_BYTES))
        replace_private(self.path, content)


def _tags_version(revision: InformationRevisionDto) -> int:
    """The SDK decodes JSON numbers as float; only a whole, non-negative one is valid."""
    version = revision.tags_version
    if (
        isinstance(version, bool)
        or not isinstance(version, (int, float))
        or not math.isfinite(version)
        or version != int(version)
        or version < 0
    ):
        raise ValueError("Revision has an invalid tags version")
    return int(version)


def _gate_reason(ticket: InformationAcquisitionDto) -> str:
    """The denying gate's reason (pre-fetch gate first), as journal-safe text."""
    for gate in (ticket.pre_gate, ticket.post_gate):
        if not isinstance(gate, InformationGateDto):
            continue
        if isinstance(gate.denied, str) and gate.denied.strip():
            return _server_text(gate.denied, _GATED)
        if gate.notes:
            return _server_text("; ".join(gate.notes), _GATED)
    return _GATED


class ReferenceLibrary:
    """Plans, advances and retries device reference acquisitions.

    ``plan_seed``, ``add_owner`` and ``view`` are synchronous journal edits;
    ``advance`` and ``retry`` talk to the Library and are serialized by one lock.
    All of them run on the collector's event loop, so a synchronous edit can land
    while ``advance`` awaits an SDK call; ``_store`` therefore merges each advanced
    receipt into the freshly read ledger and keeps the retirement decided there.
    """

    def __init__(
        self,
        journal: ReferenceJournal,
        port: ReferencePort,
        clock: Callable[[], str] = utc_now,
    ):
        self.journal = journal
        self.port = port
        self.clock = clock
        self._lock = asyncio.Lock()

    def view(self) -> tuple[ReferenceReceipt, ...]:
        return self.journal.read().receipts

    def plan_seed(self, seed: tuple[SeedEntry, ...]) -> None:
        """Queue new seed URLs and retire seed receipts the seed no longer lists.

        A failed receipt is left exactly as it is, listed or not: nothing retries
        automatically. A retired URL that returns to the seed is revived; if its tag
        was already removed it goes back to ``retained`` so the tag is restored.
        """
        ledger = self.journal.read()
        wanted = {entry.url: entry for entry in seed}
        receipts: list[ReferenceReceipt] = []
        for receipt in ledger.receipts:
            if receipt.phase == "failed" or receipt.origin != "seed":
                receipts.append(receipt)
            elif receipt.url not in wanted:
                receipts.append(replace(receipt, retired=True))
            else:
                receipts.append(_revived(receipt))
        known = {receipt.url for receipt in receipts}
        receipts.extend(
            _queued(entry.url, entry.product, entry.title, entry.topics, "seed")
            for entry in seed
            if entry.url not in known
        )
        self.journal.save(replace(ledger, receipts=tuple(receipts)))

    def add_owner(self, product: str, title: str, url: str) -> ReferenceReceipt:
        """Queue a page the owner approved; an already-known URL keeps its receipt.

        A known URL that was retired becomes the owner's, so a later seed no longer
        retires it.
        """
        product, title, url = (
            product_slug(product),
            text(title, MAX_TITLE),
            reference_url(url),
        )
        ledger = self.journal.read()
        for index, receipt in enumerate(ledger.receipts):
            if receipt.url == url:
                if not receipt.retired:
                    return receipt
                owned = replace(_revived(receipt), origin="owner")
                receipts = list(ledger.receipts)
                receipts[index] = owned
                self.journal.save(replace(ledger, receipts=tuple(receipts)))
                return owned
        receipt = _queued(url, product, title, (), "owner")
        self.journal.save(replace(ledger, receipts=(*ledger.receipts, receipt)))
        return receipt

    async def advance(self) -> None:
        async with self._lock:
            receipts = self.journal.read().receipts
            # One listing serves every receipt that needs its ticket state.
            tickets: tuple[InformationAcquisitionDto, ...] = ()
            if any(r.phase in {"submitted", "awaiting"} for r in receipts):
                tickets = await self.port.acquisitions()
            for receipt in receipts:
                await self._step(receipt, tickets)

    async def retry(self, url: str) -> None:
        """Retry a failed or blocked receipt at the owner's request.

        From ``failed`` with a ticket, the ticket is inspected: a ticket that in
        fact succeeded (the failure was in tagging) resumes at ``retained``;
        otherwise ``information.retry`` re-runs it and the receipt awaits it again.
        From ``blocked``, or ``failed`` without a ticket (a refused acquire), the
        receipt is requeued with a new request id: re-running a blocked
        acquisition through ``information.retry`` is unconfirmed, so a new
        acquisition is the safe choice, at the cost of the held ticket staying on
        the server.
        """
        async with self._lock:
            receipt = next((r for r in self.view() if r.url == url), None)
            if receipt is None:
                raise LookupError("Unknown device reference")
            if receipt.phase not in {"failed", "blocked"}:
                raise ValueError("Only a failed or blocked reference can be retried")
            if receipt.phase == "blocked" or receipt.acquisition_id is None:
                self._store(
                    replace(
                        receipt,
                        phase="queued",
                        request_id=str(uuid4()),
                        acquisition_id=None,
                        error=None,
                    )
                )
                return
            ticket = next(
                (
                    t
                    for t in await self.port.acquisitions()
                    if t.id == receipt.acquisition_id
                ),
                None,
            )
            if ticket is None:
                raise ValueError("The reference's acquisition ticket is missing")
            if ticket.state == "succeeded" and isinstance(ticket.revision_id, str):
                self._store(
                    replace(
                        receipt,
                        phase="retained",
                        revision_id=uuid(ticket.revision_id),
                        error=None,
                    )
                )
                return
            # Saved first: if delivery is uncertain the ticket read settles it.
            self._store(replace(receipt, phase="awaiting", error=None))
            try:
                await self.port.retry(receipt.acquisition_id, str(uuid4()))
            except Refusal as error:
                self._store(
                    replace(receipt, error=refusal_detail(error, "information.retry"))
                )
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self._store(receipt)
                raise

    async def _step(
        self,
        receipt: ReferenceReceipt,
        tickets: tuple[InformationAcquisitionDto, ...],
    ) -> None:
        if receipt.phase == "queued" and not receipt.retired:
            await self._acquire(receipt)
        elif receipt.phase in {"submitted", "awaiting"}:
            self._observe(receipt, tickets)
        elif receipt.phase == "retained":
            await self._tag(receipt)
        elif (
            receipt.phase == "tagged" and receipt.retired and not receipt.retired_tagged
        ):
            await self._untag(receipt)

    async def _acquire(self, receipt: ReferenceReceipt) -> None:
        self._store(replace(receipt, phase="submitted", error=None))
        try:
            ticket = await self.port.acquire(
                receipt.url, f"Reference: {receipt.title}", receipt.request_id
            )
        except Refusal as error:
            self._store(
                replace(
                    receipt,
                    phase="failed",
                    error=refusal_detail(error, "information.acquire"),
                )
            )
            return
        except TransportError as error:
            if error.delivery == Delivery.NOT_SUBMITTED:
                self._store(receipt)
            raise
        self._store(
            replace(
                receipt, phase="awaiting", acquisition_id=uuid(ticket.id), error=None
            )
        )

    def _observe(
        self,
        receipt: ReferenceReceipt,
        tickets: tuple[InformationAcquisitionDto, ...],
    ) -> None:
        """Apply the ticket's state; ``submitted`` first finds its ticket.

        The public acquisition DTO carries no request id, so an uncertainly
        delivered acquire is matched by URL and the source name this receipt sends.
        The listing is newest first and this collector is the only writer of these
        names in its project, so the newest match is the one just submitted.
        """
        if receipt.acquisition_id is None:
            name = f"Reference: {receipt.title}"
            ticket = next(
                (t for t in tickets if t.url == receipt.url and t.source_name == name),
                None,
            )
            if ticket is None:
                self._store(replace(receipt, error=_UNRESOLVED))
                return
        else:
            ticket = next((t for t in tickets if t.id == receipt.acquisition_id), None)
            if ticket is None:
                raise ValueError("The reference's acquisition ticket is missing")
        acquisition = uuid(ticket.id)
        if ticket.state == "blocked":
            current = replace(
                receipt,
                phase="blocked",
                acquisition_id=acquisition,
                error=_gate_reason(ticket),
            )
        elif ticket.state == "failed":
            current = replace(
                receipt,
                phase="failed",
                acquisition_id=acquisition,
                error=_server_text(ticket.error, _UNEXPLAINED),
            )
        elif ticket.state == "succeeded" and isinstance(ticket.revision_id, str):
            current = replace(
                receipt,
                phase="retained",
                acquisition_id=acquisition,
                revision_id=uuid(ticket.revision_id),
                error=None,
            )
        else:
            # Queued, running, or succeeded without a visible revision yet.
            current = replace(
                receipt, phase="awaiting", acquisition_id=acquisition, error=None
            )
        if current != receipt:
            self._store(current)

    async def _tag(self, receipt: ReferenceReceipt) -> None:
        """Merge the required tags into the revision's own; then ``tagged``."""
        revision = await self._revision(receipt)
        if revision is None:
            return
        existing = revision.tags if isinstance(revision.tags, tuple) else ()
        required = [LIBRARY_TAG, receipt.product, *receipt.topics]
        if receipt.retired:
            required.remove(LIBRARY_TAG)
        wanted = [t for t in existing if not (receipt.retired and t == LIBRARY_TAG)]
        wanted.extend(t for t in dict.fromkeys(required) if t not in wanted)
        if tuple(wanted) != existing and not await self._replace(
            receipt, revision, tuple(wanted), "tag"
        ):
            return
        self._store(
            replace(
                receipt,
                phase="tagged",
                retired_tagged=receipt.retired,
                fetched_at=self.clock(),
                error=None,
            )
        )

    async def _untag(self, receipt: ReferenceReceipt) -> None:
        """Remove only ``device-reference``; the source and other tags stay."""
        revision = await self._revision(receipt)
        if revision is None:
            return
        existing = revision.tags if isinstance(revision.tags, tuple) else ()
        if LIBRARY_TAG in existing and not await self._replace(
            receipt,
            revision,
            tuple(t for t in existing if t != LIBRARY_TAG),
            "untag",
        ):
            return
        self._store(replace(receipt, retired_tagged=True, error=None))

    async def _revision(
        self, receipt: ReferenceReceipt
    ) -> InformationRevisionDto | None:
        if receipt.revision_id is None:
            raise ValueError("A retained reference needs its revision")
        try:
            return await self.port.revision(receipt.revision_id)
        except Refusal as error:
            self._store(
                replace(
                    receipt,
                    phase="failed",
                    error=refusal_detail(error, "information.status"),
                )
            )
            return None

    async def _replace(
        self,
        receipt: ReferenceReceipt,
        revision: InformationRevisionDto,
        tags: tuple[str, ...],
        step: str,
    ) -> bool:
        """Replace tags; True when applied, False when refused and recorded.

        Nothing new is journaled before this call: the receipt already records the
        step, and an uncertain outcome is settled by the next read of the revision.
        """
        version = _tags_version(revision)
        request_id = str(
            uuid5(UUID(receipt.request_id), f"{step}:{revision.id}:{version}")
        )
        try:
            await self.port.replace_tags(revision.id, tags, version, request_id)
        except Refusal as error:
            self._store(
                replace(
                    receipt,
                    phase="failed",
                    error=refusal_detail(error, "information.tags"),
                )
            )
            return False
        return True

    def _store(self, receipt: ReferenceReceipt) -> None:
        """Write one advanced receipt into the current ledger, matched by URL.

        Retirement and origin come from the ledger as now stored, because
        ``plan_seed`` or ``add_owner`` may have changed them while an SDK call was
        awaited. A receipt revived while it was being tagged without
        ``device-reference`` goes back to ``retained`` so the tag is restored; one
        retired while being tagged keeps ``tagged`` and is untagged next.
        """
        ledger = self.journal.read()
        receipts = list(ledger.receipts)
        for index, stored in enumerate(receipts):
            if stored.url == receipt.url:
                revived = receipt.retired and not stored.retired
                receipts[index] = replace(
                    receipt,
                    origin=stored.origin,
                    retired=stored.retired,
                    retired_tagged=receipt.retired_tagged and stored.retired,
                    phase="retained"
                    if revived and receipt.phase == "tagged"
                    else receipt.phase,
                )
                self.journal.save(replace(ledger, receipts=tuple(receipts)))
                return
        raise LookupError("Unknown device reference")


def _queued(
    url: str, product: str, title: str, topics: tuple[str, ...], origin: Origin
) -> ReferenceReceipt:
    return ReferenceReceipt(
        url=url,
        product=product,
        title=title,
        topics=topics,
        origin=origin,
        request_id=str(uuid4()),
        phase="queued",
        acquisition_id=None,
        revision_id=None,
        error=None,
        retired=False,
        retired_tagged=False,
        fetched_at=None,
    )


def _revived(receipt: ReferenceReceipt) -> ReferenceReceipt:
    """A retired receipt listed again; an untagged one is tagged again next."""
    if not receipt.retired:
        return receipt
    return replace(
        receipt,
        retired=False,
        retired_tagged=False,
        phase="retained" if receipt.retired_tagged else receipt.phase,
    )
