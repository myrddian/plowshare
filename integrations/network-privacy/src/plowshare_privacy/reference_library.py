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
  instead of acquiring again. The public listing carries no request id, so the
  source name binds the ticket to its attempt: it is ``Reference: {title} ·
  {first 8 hex of request_id}``, and a requeue takes a new request id and
  therefore a new name. A refresh is the exception: the server keys a Library
  resource by its home and source name (not its URL), so a refresh sends the
  resource's existing name (``resource_name``) to add a revision to that
  resource rather than create a second one. Matching needs that exact URL and
  name, also skips every ticket this receipt already used (``superseded``), and
  the newest ``created_at`` wins among the rest. ``superseded`` is bounded, so a
  requeue also records ``attempt_started_at`` and only tickets created no
  earlier than that, less ``CLOCK_SKEW`` (the collector's clock against the
  server's), can match; a ticket without a readable ``created_at`` then never
  matches. Together these separate a refresh's attempts even after many
  refreshes. A blocked attempt that is requeued also keeps its name: a post-gate
  block has already retained the bytes in a resource, so the next attempt
  revises that resource instead of leaving it orphaned. A ticket observed by its id must still
  show this receipt's URL and name, or the receipt fails with a fixed message. If no ticket is found the
  receipt stays ``submitted``; only the owner's Retry re-sends that acquire, with
  the same request id, which the server treats as an idempotent replay.
  Transport errors are re-raised to the caller, as the scan worker does, so
  connection loss is reported once and stops the pass.
- A ``Refusal`` from a per-receipt call (acquire, revision, tags) is recorded as
  ``failed`` with the redacted server reason, and the remaining receipts continue.
  That includes a ``CONFLICT`` on the tags call (the tags changed after they were
  read): it is not re-read automatically and needs the owner's Retry.
- A reply that breaks its contract for one receipt (a missing ticket, a
  malformed id or tags version) marks only that receipt ``failed`` with a fixed
  message; the pass continues so one bad receipt cannot stall the library.
- ``blocked`` and ``failed`` are terminal until the owner presses Retry. Nothing
  retries automatically: ``plan_seed`` never touches a failed receipt and
  ``advance`` never re-reads a blocked or failed ticket.
- Tagging merges with the revision's existing tags and sends its ``tags_version``
  as the expected version. A revision that already carries the required tags is
  not re-tagged (so a refresh does not pay for a tags call). Each tags request id
  is derived from the receipt, the step, the revision and the version read, so a
  replay after uncertain delivery is idempotent and a fresh read after a version
  conflict is a fresh request.
- Refresh. ``schedule_refresh`` requeues every ``tagged``, non-retired receipt
  whose ``fetched_at`` is older than the ledger's ``refresh_days`` (1 to 365,
  default 90), through the same requeue as a retry: a new request id, the old
  ticket superseded, ``revision_id`` kept until the new revision is retained.
  Tags live on the resource, so the new revision already carries them and the
  retained step makes no tags call. Retired and failed receipts are never
  refreshed.
- A seed entry dropped from a new seed is ``retired``, failed ones included
  (they stay failed): its source is kept so existing citations resolve, only the
  ``device-reference`` tag is removed so it is no longer searched. The removal
  runs for any retired receipt whose revision is known and that has no
  acquisition in flight, and takes priority over the receipt's other step. A
  refresh in flight (``submitted`` or ``awaiting`` with the earlier revision)
  is observed instead, so a refused removal cannot fail it; once retained, its
  tagging leaves ``device-reference`` out. Once a removal attempt fails,
  ``untag_failed`` stops further automatic attempts until the owner's Retry.
  A retired entry that was never submitted is not fetched.

Server text (ticket errors, gate reasons, refusal prose) is untrusted: it is
stripped of control characters including DEL and bounded before it is journaled,
and it is never interpreted. Every recorded error is therefore valid journal
text, so recording a failure cannot itself abort a pass.
"""

from __future__ import annotations

import asyncio
import json
import math
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Iterable, Literal
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
# "Reference: " + a bounded title + " · " + 8 hex, with room to spare.
MAX_SOURCE_NAME = 256
# How far the server's ticket ``created_at`` may trail the collector's clock at
# requeue and still match an uncertainly delivered attempt. Refreshes are at
# least a day apart, so an older attempt's ticket is always outside it.
CLOCK_SKEW = timedelta(minutes=10)
# Ticket ids a receipt has moved past; the oldest is dropped beyond this bound,
# which only matters after that many owner retries or refreshes of one page.
MAX_SUPERSEDED = 32
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
_FOREIGN = (
    "The Library's acquisition ticket does not match this reference's link or "
    "name. Inspect the source in the Library before retrying."
)
_TYPING_REQUIRED = "INFORMATION_TYPING_REQUIRED"
_TYPED_REFRESH = (
    "The Library source now uses typed content, which this collector does not "
    "send, so the refresh was refused and Retry is refused the same way. The "
    "previous revision and its tags stay in the Library and searchable."
)
_BROKEN = (
    "The Library sent a reply for this reference that the collector could not "
    "accept. Inspect the source in the Library before retrying."
)


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


def _resource_name(value: object) -> str:
    return text(value, MAX_SOURCE_NAME)


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
    created and replaced only when a blocked or refused receipt is requeued or
    a tagged one is refreshed.
    ``revision_id`` is the retained revision once known. ``retired`` marks a seed
    entry the current seed no longer lists; ``retired_tagged`` records that its
    ``device-reference`` tag has been removed. ``fetched_at`` is when it was tagged.
    ``superseded`` lists, oldest first, the ticket ids of earlier attempts, so an
    uncertain acquire is never matched to an old ticket for the same page.
    ``untag_failed`` records that removing ``device-reference`` from a retired
    receipt failed, so it is not attempted again until the owner retries.
    ``resource_name`` is the source name of the Library resource that holds
    ``revision_id``, recorded when a refresh is scheduled so every later attempt
    targets that resource; it is absent until the first refresh.
    ``attempt_started_at`` is when the current attempt was requeued; an
    uncertainly delivered acquire only matches tickets created after it (less
    ``CLOCK_SKEW``). It is absent for a first attempt, whose name is unique.
    ``awaiting_since`` is when the receipt last entered ``awaiting``, so the
    dashboard can show how long the server has held the fetch. ``_store`` keeps
    it only in ``awaiting`` (a transition may briefly carry it, so it is not an
    invariant here); a journal written before it existed is filled in from the
    ticket's ``created_at`` on the next pass.
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
    superseded: tuple[str, ...] = ()
    untag_failed: bool = False
    resource_name: str | None = None
    attempt_started_at: str | None = None
    awaiting_since: str | None = None

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
        _optional(self.resource_name, _resource_name)
        _optional(self.attempt_started_at, timestamp)
        _optional(self.awaiting_since, timestamp)
        if any(
            type(flag) is not bool
            for flag in (self.retired, self.retired_tagged, self.untag_failed)
        ):
            raise ValueError("Invalid reference retirement flags")
        if self.untag_failed and (not self.retired or self.retired_tagged):
            raise ValueError("Only a pending untagging can have failed")
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
        if len(self.superseded) > MAX_SUPERSEDED:
            raise ValueError("Too many superseded reference tickets")
        for ticket in self.superseded:
            uuid(ticket)
        if len(set(self.superseded)) != len(self.superseded) or (
            self.acquisition_id in self.superseded
        ):
            raise ValueError("Conflicting superseded reference tickets")

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
                "superseded",
                "untag_failed",
            },
            # Absent from journals written before refresh, or waiting times,
            # existed.
            {"resource_name", "attempt_started_at", "awaiting_since"},
        )
        retired, retired_tagged = row["retired"], row["retired_tagged"]
        untag_failed = row["untag_failed"]
        if (
            type(retired) is not bool
            or type(retired_tagged) is not bool
            or type(untag_failed) is not bool
        ):
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
            tuple(uuid(t) for t in items(row["superseded"], MAX_SUPERSEDED)),
            untag_failed,
            _optional(row.get("resource_name"), _resource_name),
            _optional(row.get("attempt_started_at"), timestamp),
            _optional(row.get("awaiting_since"), timestamp),
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

        A failed receipt keeps its phase, listed or not: nothing retries
        automatically; a dropped one is still retired so its tag is removed. A
        retired URL that returns to the seed is revived; if its tag was already
        removed it goes back to ``retained`` (a failed one stays failed) so the tag
        is restored.
        """
        ledger = self.journal.read()
        wanted = {entry.url: entry for entry in seed}
        receipts: list[ReferenceReceipt] = []
        for receipt in ledger.receipts:
            if receipt.origin != "seed":
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

    def refresh_days(self) -> int:
        return self.journal.read().refresh_days

    def set_refresh_days(self, days: int) -> None:
        """Set the refresh interval; anything but a whole 1 to 365 is refused."""
        days = integer(days, 1, MAX_REFRESH_DAYS)
        self.journal.save(replace(self.journal.read(), refresh_days=days))

    def refresh_due(self, now: datetime) -> tuple[str, ...]:
        """URLs of tagged, non-retired receipts fetched more than the interval ago.

        A tagged receipt always records ``fetched_at``; one that somehow lacks it
        has an unknown age and is treated as due.
        """
        ledger = self.journal.read()
        return tuple(
            receipt.url
            for receipt in ledger.receipts
            if _refresh_due(receipt, now, ledger.refresh_days)
        )

    def schedule_refresh(self, now: datetime) -> None:
        """Requeue every receipt due for refresh; the next ``advance`` acquires it.

        A synchronous journal edit like ``plan_seed``. It only touches ``tagged``,
        non-retired receipts, which neither ``advance`` nor ``retry`` steps, so
        it cannot overwrite a step in flight. The resource's current name is kept
        so the acquire adds a revision to it (see the module doc).
        """
        ledger = self.journal.read()
        started = now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        receipts = tuple(
            replace(
                _requeued(receipt, started),
                resource_name=_source_name(receipt),
            )
            if _refresh_due(receipt, now, ledger.refresh_days)
            else receipt
            for receipt in ledger.receipts
        )
        if receipts != ledger.receipts:
            self.journal.save(replace(ledger, receipts=receipts))

    async def advance(self) -> None:
        async with self._lock:
            receipts = self.journal.read().receipts
            # One listing serves every receipt that needs its ticket state.
            tickets: tuple[InformationAcquisitionDto, ...] = ()
            if any(r.phase in {"submitted", "awaiting"} for r in receipts):
                tickets = await self.port.acquisitions()
            for receipt in receipts:
                try:
                    await self._step(receipt, tickets)
                except ValueError:
                    # A contract-breaking reply for this receipt only; the stored
                    # copy is re-read so a phase saved before the failure is kept.
                    self._fail_broken(receipt.url, _BROKEN)

    async def retry(self, url: str) -> None:
        """Retry a failed, blocked or unresolved receipt at the owner's request.

        - ``submitted`` (an uncertain acquire whose ticket was never found): the
          acquire is re-sent with the same request id. The server deduplicates by
          request id, so this is an owner-initiated idempotent replay, never a
          second fetch.
        - ``failed`` with a ticket: the ticket is inspected. One that in fact
          succeeded (the failure was in tagging) resumes at ``retained``; a failed
          one is re-run by ``information.retry`` and awaited again; a ticket the
          listing no longer shows is superseded and the receipt requeued.
        - ``blocked``, or ``failed`` without a ticket (a refused acquire): requeued
          with a new request id, the old ticket superseded. Re-running a blocked
          acquisition through ``information.retry`` is unconfirmed, so a new
          acquisition is the safe choice, at the cost of the held ticket staying on
          the server.
        """
        async with self._lock:
            receipt = next((r for r in self.view() if r.url == url), None)
            if receipt is None:
                raise LookupError("Unknown device reference")
            # An owner retry also re-arms a failed removal of ``device-reference``.
            untag_retry = receipt.untag_failed
            receipt = replace(receipt, untag_failed=False)
            if receipt.phase == "submitted":
                await self._acquire(receipt)
                return
            if untag_retry and receipt.phase not in {"failed", "blocked"}:
                # A retired receipt whose tag removal failed: clearing the flag
                # lets the next pass remove the tag again.
                self._store(receipt)
                return
            if receipt.phase not in {"failed", "blocked"}:
                raise ValueError(
                    "Only a failed, blocked or unresolved reference can be retried"
                )
            if receipt.phase == "blocked":
                # The same name, so a post-gate block's retained resource is
                # revised rather than orphaned (see the module doc).
                self._store(
                    replace(
                        _requeued(receipt, self.clock()),
                        resource_name=_source_name(receipt),
                    )
                )
                return
            if receipt.acquisition_id is None:
                self._store(_requeued(receipt, self.clock()))
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
                self._store(_requeued(receipt, self.clock()))
                return
            if not _owns(receipt, ticket):
                self._store(_failed(receipt, _FOREIGN))
                return
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
                self._store(_failed(receipt, _refused(error, "information.retry")))
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self._store(receipt)
                raise

    def _fail_broken(self, url: str, message: str) -> None:
        """Fail the receipt as now stored (a phase saved before the fault is kept)."""
        stored = next(r for r in self.journal.read().receipts if r.url == url)
        self._store(_failed(stored, message))

    async def _step(
        self,
        receipt: ReferenceReceipt,
        tickets: tuple[InformationAcquisitionDto, ...],
    ) -> None:
        if _needs_untag(receipt):
            await self._untag(receipt)
        elif receipt.phase == "queued" and not receipt.retired:
            await self._acquire(receipt)
        elif receipt.phase in {"submitted", "awaiting"}:
            self._observe(receipt, tickets)
        elif receipt.phase == "retained":
            await self._tag(receipt)

    async def _acquire(self, receipt: ReferenceReceipt) -> None:
        self._store(replace(receipt, phase="submitted", error=None))
        try:
            ticket = await self.port.acquire(
                receipt.url, _source_name(receipt), receipt.request_id
            )
        except Refusal as error:
            message = (
                _TYPED_REFRESH
                if _typed_refresh(receipt, error.code, error.said)
                else _refused(error, "information.acquire")
            )
            self._store(_failed(receipt, message))
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
        delivered acquire is matched by URL and the source name this receipt sends,
        never to a ticket this receipt already superseded nor to one created
        before the attempt started (less ``CLOCK_SKEW``); among the rest the
        newest ``created_at`` wins (the listing order breaks ties). This collector
        is the only writer of these names in its project.
        """
        if receipt.acquisition_id is None:
            ticket = _newest(
                t
                for t in tickets
                if t.url == receipt.url
                and t.source_name == _source_name(receipt)
                and t.id not in receipt.superseded
                and _created(t) >= _attempt_floor(receipt)
            )
            if ticket is None:
                self._store(replace(receipt, error=_UNRESOLVED))
                return
        else:
            ticket = next((t for t in tickets if t.id == receipt.acquisition_id), None)
            if ticket is None:
                raise ValueError("The reference's acquisition ticket is missing")
            if not _owns(receipt, ticket):
                self._store(_failed(receipt, _FOREIGN))
                return
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
                error=_TYPED_REFRESH
                if _typed_refresh(receipt, ticket.error)
                else _server_text(ticket.error, _UNEXPLAINED),
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
            # Queued, running, or succeeded without a visible revision yet. A
            # receipt already awaiting keeps its start; one journaled before
            # waiting times existed takes the ticket's creation (``_store`` stamps
            # a receipt that has only now entered ``awaiting``).
            current = replace(
                receipt,
                phase="awaiting",
                acquisition_id=acquisition,
                error=None,
                awaiting_since=receipt.awaiting_since
                or (_ticket_created(ticket) if receipt.phase == "awaiting" else None),
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
            self._store(_failed(receipt, _refused(error, "information.status")))
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
            self._store(_failed(receipt, _refused(error, "information.tags")))
            return False
        return True

    def _store(self, receipt: ReferenceReceipt) -> None:
        """Write one advanced receipt into the current ledger, matched by URL.

        Retirement and origin come from the ledger as now stored, because
        ``plan_seed`` or ``add_owner`` may have changed them while an SDK call was
        awaited. A receipt revived while it was being tagged without
        ``device-reference`` goes back to ``retained`` so the tag is restored; one
        retired while being tagged keeps ``tagged`` and is untagged next.

        ``awaiting_since`` is kept only in ``awaiting``, and a receipt entering
        ``awaiting`` without one is stamped with the collector's clock, so every
        path into that phase (an acquire, an owner retry) starts the wait.
        """
        if receipt.phase != "awaiting":
            receipt = replace(receipt, awaiting_since=None)
        elif receipt.awaiting_since is None:
            receipt = replace(receipt, awaiting_since=self.clock())
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
                    untag_failed=receipt.untag_failed and stored.retired,
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
        untag_failed=False,
        phase="retained"
        if receipt.retired_tagged and receipt.phase != "failed"
        else receipt.phase,
    )


def _requeued(receipt: ReferenceReceipt, started: str) -> ReferenceReceipt:
    """Back to ``queued`` for a new acquisition, its old ticket superseded.

    ``started`` (a timestamp from the collector's clock) bounds which tickets an
    uncertain delivery of the new attempt may match.
    """
    superseded = receipt.superseded
    if receipt.acquisition_id is not None:
        superseded = (*superseded, receipt.acquisition_id)[-MAX_SUPERSEDED:]
    return replace(
        receipt,
        phase="queued",
        request_id=str(uuid4()),
        acquisition_id=None,
        error=None,
        superseded=superseded,
        attempt_started_at=started,
    )


def _attempt_floor(receipt: ReferenceReceipt) -> datetime:
    """The earliest ``created_at`` a ticket of the current attempt can have."""
    if receipt.attempt_started_at is None:
        return datetime.min.replace(tzinfo=timezone.utc)
    started = datetime.fromisoformat(receipt.attempt_started_at.replace("Z", "+00:00"))
    return started - CLOCK_SKEW


def _ticket_created(ticket: InformationAcquisitionDto) -> str | None:
    """The ticket's ``created_at`` as journaled, or None when it is malformed."""
    try:
        return timestamp(ticket.created_at)
    except ValueError:
        return None


def _created(ticket: InformationAcquisitionDto) -> datetime:
    """A ticket's creation instant; a missing or malformed one sorts oldest."""
    try:
        return datetime.fromisoformat(
            timestamp(ticket.created_at).replace("Z", "+00:00")
        )
    except ValueError:
        return datetime.min.replace(tzinfo=timezone.utc)


def _newest(
    tickets: Iterable[InformationAcquisitionDto],
) -> InformationAcquisitionDto | None:
    """The newest ticket by ``created_at``; the first listed wins a tie."""
    newest: InformationAcquisitionDto | None = None
    for ticket in tickets:
        if newest is None or _created(ticket) > _created(newest):
            newest = ticket
    return newest


def _source_name(receipt: ReferenceReceipt) -> str:
    """The acquire name: the refreshed resource's own, else bound to this attempt.

    A first acquisition is named by its request id's first 8 hex; a refresh
    reuses the resource's name so the server adds a revision to it.
    """
    if receipt.resource_name is not None:
        return receipt.resource_name
    return f"Reference: {receipt.title} · {receipt.request_id[:8]}"


def _refresh_due(receipt: ReferenceReceipt, now: datetime, days: int) -> bool:
    if receipt.phase != "tagged" or receipt.retired:
        return False
    if receipt.fetched_at is None:
        return True
    fetched = datetime.fromisoformat(receipt.fetched_at.replace("Z", "+00:00"))
    return now - fetched > timedelta(days=days)


def _owns(receipt: ReferenceReceipt, ticket: InformationAcquisitionDto) -> bool:
    """A ticket belongs to the receipt: same URL, and its name when one is shown."""
    name = ticket.source_name
    return ticket.url == receipt.url and (
        not isinstance(name, str) or name == _source_name(receipt)
    )


def _typed_refresh(receipt: ReferenceReceipt, *reasons: object) -> bool:
    """A refresh the server refused because its resource's head is now typed.

    The server raises ``INFORMATION_TYPING_REQUIRED`` when an untyped revision is
    added to a typed resource, at submission or when the fetched bytes are
    admitted. Only a receipt with a previous revision is a refresh.
    """
    return receipt.revision_id is not None and any(
        isinstance(reason, str) and _TYPING_REQUIRED in reason for reason in reasons
    )


def _refused(error: Refusal, operation: str) -> str:
    """Redacted refusal prose, sanitized like any other server text."""
    return _server_text(refusal_detail(error, operation), "Plowshare refused it.")


def _needs_untag(receipt: ReferenceReceipt) -> bool:
    """A retired receipt with a known revision whose tag removal is still due.

    ``retained`` is excluded because its tagging step already leaves
    ``device-reference`` out for a retired receipt. ``submitted`` and
    ``awaiting`` with a revision are a refresh in flight: they are observed
    first, so a refused removal cannot fail the acquisition, and they reach
    ``retained`` (tagged without the library tag) or an end state that is
    untagged here on the next pass.
    """
    return (
        receipt.retired
        and not receipt.retired_tagged
        and not receipt.untag_failed
        and receipt.revision_id is not None
        and receipt.phase not in {"retained", "submitted", "awaiting"}
    )


def _failed(receipt: ReferenceReceipt, message: str) -> ReferenceReceipt:
    """Record a failure; a retired receipt's pending untagging stops retrying."""
    return replace(
        receipt,
        phase="failed",
        error=_server_text(message, _BROKEN),
        untag_failed=receipt.retired
        and not receipt.retired_tagged
        and receipt.revision_id is not None,
    )
