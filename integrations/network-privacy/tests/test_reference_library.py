"""The reference library journals each acquisition so restarts never lose or repeat one."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError
from plowshare.contracts import (
    InformationAcquisitionDto,
    InformationGateDto,
    InformationRevisionDto,
)

from plowshare_privacy.reference_library import (
    ReferenceJournal,
    ReferenceLedger,
    ReferenceLibrary,
    ReferenceReceipt,
)
from plowshare_privacy.reference_seed import SeedEntry

URL = "https://docs.example.test/admin-api"
OTHER = "https://docs.example.test/global-options"
ENTRY = SeedEntry("caddy", "Caddy admin API", URL, ("admin-api",))
SECOND = SeedEntry("caddy", "Caddy global options", OTHER, ())
NOW = "2026-11-14T12:00:00Z"

TicketState = Literal["queued", "running", "failed", "blocked", "succeeded"]


@dataclass
class Ticket:
    id: str
    url: str
    name: str
    state: TicketState = "queued"
    revision_id: str | None = None
    error: str | None = None
    gate: str | None = None
    created_at: str = NOW

    def dto(self) -> InformationAcquisitionDto:
        return InformationAcquisitionDto(
            id=self.id,
            url=self.url,
            source_name=self.name,
            state=self.state,
            revision_id=self.revision_id,
            error=self.error,
            created_at=self.created_at,
            pre_gate=(
                InformationGateDto(denied=self.gate, notes=(), records=())
                if self.gate is not None
                else None
            ),
        )


@dataclass
class Revision:
    tags: tuple[str, ...] = ()
    version: float = 1.0


@dataclass
class FakePort:
    """An in-memory Library: tickets, revisions and a log of every mutation."""

    tickets: list[Ticket] = field(default_factory=list)
    revisions: dict[str, Revision] = field(default_factory=dict)
    acquires: list[tuple[str, str, str]] = field(default_factory=list)
    tag_calls: list[tuple[str, tuple[str, ...], int, str]] = field(default_factory=list)
    retries: list[tuple[str, str]] = field(default_factory=list)
    # The source name of each revision's resource. The server keys a resource by
    # its home and source name, and a resource's tags are shared by its revisions.
    names: dict[str, str] = field(default_factory=dict)
    # One-shot faults raised by the next call of that operation.
    acquire_fault: Exception | None = None
    acquire_lands: bool = True
    tags_fault: Exception | None = None
    # The server's clock, stamped on each new ticket's ``created_at``.
    now: str = NOW

    async def acquire(
        self, url: str, name: str, request_id: str
    ) -> InformationAcquisitionDto:
        self.acquires.append((url, name, request_id))
        fault, self.acquire_fault = self.acquire_fault, None
        if fault is not None and not self.acquire_lands:
            raise fault
        ticket = Ticket(str(uuid4()), url, name, created_at=self.now)
        self.tickets.append(ticket)
        if fault is not None:
            raise fault
        return ticket.dto()

    async def acquisitions(self) -> tuple[InformationAcquisitionDto, ...]:
        # The server lists newest first.
        return tuple(ticket.dto() for ticket in reversed(self.tickets))

    async def revision(self, revision_id: str) -> InformationRevisionDto:
        stored = self.revisions[revision_id]
        return InformationRevisionDto(
            id=revision_id, kind="source", tags=stored.tags, tags_version=stored.version
        )

    async def replace_tags(
        self,
        revision_id: str,
        tags: tuple[str, ...],
        expected_version: int,
        request_id: str,
    ) -> None:
        self.tag_calls.append((revision_id, tags, expected_version, request_id))
        fault, self.tags_fault = self.tags_fault, None
        if fault is not None:
            raise fault
        stored = self.revisions[revision_id]
        if int(stored.version) != expected_version:
            raise Refusal("CONFLICT", "Tags changed")
        self.revisions[revision_id] = Revision(tags, stored.version + 1)

    async def retry(self, acquisition_id: str, request_id: str) -> None:
        self.retries.append((acquisition_id, request_id))
        for ticket in self.tickets:
            if ticket.id == acquisition_id:
                ticket.state, ticket.error = "queued", None

    def finish(
        self,
        index: int = -1,
        tags: tuple[str, ...] = (),
        version: float = 1.0,
        after: str | None = None,
    ) -> str:
        """Complete one ticket (the newest by default) with a fresh revision.

        ``after`` names an earlier revision: when the ticket's source name is that
        revision's, the new one joins the same resource and carries its tags, as
        the server's admission does; any other name is a new resource.
        """
        revision = str(uuid4())
        name = self.tickets[index].name
        if after is not None and self.names.get(after) == name:
            tags, version = self.revisions[after].tags, self.revisions[after].version
        self.revisions[revision] = Revision(tags, version)
        self.names[revision] = name
        self.tickets[index].state = "succeeded"
        self.tickets[index].revision_id = revision
        return revision


class LibraryTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name).resolve()
        self.port = FakePort()
        self.library = self.open()

    def tearDown(self) -> None:
        self.directory.cleanup()

    def open(self) -> ReferenceLibrary:
        """A fresh library over the same journal, as after a collector restart."""
        return ReferenceLibrary(ReferenceJournal(self.root), self.port, lambda: NOW)

    def receipt(self, url: str = URL) -> ReferenceReceipt:
        return next(r for r in self.library.view() if r.url == url)

    async def tagged(self, *entries: SeedEntry) -> str:
        """Plan ``entries`` and drive the first to ``tagged``; its revision."""
        self.library.plan_seed(entries or (ENTRY,))
        await self.library.advance()
        revision = self.port.finish(0)
        await self.library.advance()
        await self.library.advance()
        self.assertEqual("tagged", self.receipt().phase)
        return revision

    async def test_refresh_due_after_interval_only(self) -> None:
        await self.tagged()
        fetched = datetime(2026, 11, 14, 12, tzinfo=timezone.utc)
        self.assertEqual((), self.library.refresh_due(fetched))
        self.assertEqual(
            (),
            self.library.refresh_due(
                fetched + timedelta(days=90) - timedelta(seconds=1)
            ),
        )
        self.assertEqual(
            (URL,), self.library.refresh_due(fetched + timedelta(days=90, seconds=1))
        )
        self.library.set_refresh_days(7)
        self.assertEqual(7, self.library.refresh_days())
        self.assertEqual(
            (URL,), self.library.refresh_due(fetched + timedelta(days=7, seconds=1))
        )
        # Only due receipts move; a pass before the interval changes nothing.
        before = self.library.view()
        self.library.schedule_refresh(fetched + timedelta(days=6))
        self.assertEqual(before, self.library.view())

    async def test_refresh_requeues_with_new_request_id(self) -> None:
        revision = await self.tagged()
        tagged = self.receipt()
        self.library.schedule_refresh(
            datetime(2026, 11, 14, 12, tzinfo=timezone.utc) + timedelta(days=91)
        )
        queued = self.receipt()
        self.assertEqual("queued", queued.phase)
        self.assertNotEqual(tagged.request_id, queued.request_id)
        self.assertEqual(revision, queued.revision_id)
        self.assertIsNone(queued.acquisition_id)
        self.assertEqual((tagged.acquisition_id,), queued.superseded)
        await self.library.advance()
        # The same URL under the resource's own source name, so the server adds a
        # revision to that resource instead of creating a second one.
        self.assertEqual(2, len(self.port.acquires))
        first, second = self.port.acquires
        self.assertEqual((first[0], first[1]), (second[0], second[1]))
        self.assertEqual(queued.request_id, second[2])
        self.assertEqual("awaiting", self.receipt().phase)

    async def test_refresh_skips_retired_and_failed(self) -> None:
        await self.tagged(ENTRY, SECOND)
        await self.library.advance()
        self.port.tags_fault = Refusal("FORBIDDEN", "No tags")
        self.port.finish(1)
        await self.library.advance()
        await self.library.advance()
        self.assertEqual("failed", self.receipt(OTHER).phase)
        self.library.plan_seed((SECOND,))
        await self.library.advance()
        self.assertTrue(self.receipt().retired_tagged)
        later = datetime(2027, 6, 1, tzinfo=timezone.utc)
        self.assertEqual((), self.library.refresh_due(later))
        before = self.library.view()
        self.library.schedule_refresh(later)
        self.assertEqual(before, self.library.view())

    async def test_refresh_days_bounds(self) -> None:
        for days in (0, 366, True, 7.0):
            with self.assertRaises(ValueError):
                self.library.set_refresh_days(days)  # type: ignore[arg-type]
        self.assertEqual(90, self.library.refresh_days())
        for days in (1, 365):
            self.library.set_refresh_days(days)
            self.assertEqual(days, self.open().refresh_days())

    async def test_refreshed_source_keeps_tags_without_retagging_call(self) -> None:
        old = await self.tagged()
        self.assertEqual(1, len(self.port.tag_calls))
        self.library.schedule_refresh(datetime(2027, 3, 1, tzinfo=timezone.utc))
        await self.library.advance()
        new = self.port.finish(after=old)
        self.assertEqual(self.port.revisions[old].tags, self.port.revisions[new].tags)
        await self.library.advance()
        self.assertEqual(("retained", new), self.phase_and_revision())
        await self.library.advance()
        refreshed = self.receipt()
        self.assertEqual(("tagged", new), (refreshed.phase, refreshed.revision_id))
        self.assertEqual(1, len(self.port.tag_calls))

    async def test_refreshing_receipt_is_never_untagged_in_flight(self) -> None:
        old = await self.tagged()
        self.library.schedule_refresh(datetime(2027, 3, 1, tzinfo=timezone.utc))
        await self.library.advance()
        self.assertEqual("awaiting", self.receipt().phase)
        # Not retired: waiting passes never read or change tags.
        await self.library.advance()
        self.assertEqual(1, len(self.port.tag_calls))
        # Retired while the refresh is in flight: a refused removal must not drop
        # the acquisition; the refresh completes and its tagging leaves the
        # library tag out.
        self.library.plan_seed(())
        self.port.tags_fault = Refusal("CONFLICT", "Tags changed")
        await self.library.advance()
        self.assertEqual("awaiting", self.receipt().phase)
        self.assertEqual(1, len(self.port.tag_calls))
        self.port.tags_fault = None
        new = self.port.finish(after=old)
        await self.library.advance()
        await self.library.advance()
        done = self.receipt()
        self.assertEqual(("tagged", True), (done.phase, done.retired_tagged))
        self.assertNotIn("device-reference", self.port.revisions[new].tags)

    async def test_journal_written_before_refresh_still_reads(self) -> None:
        await self.tagged()
        journal = ReferenceJournal(self.root)
        raw = json.loads(journal.path.read_text())
        for row in raw["receipts"]:
            self.assertIsNone(row.pop("resource_name"))
        journal.path.write_text(json.dumps(raw))
        self.assertEqual("tagged", self.receipt().phase)
        self.library.schedule_refresh(datetime(2027, 3, 1, tzinfo=timezone.utc))
        first_name = self.port.acquires[0][1]
        self.assertEqual(first_name, self.open().view()[0].resource_name)

    async def test_uncertain_refresh_never_adopts_a_ticket_older_than_the_attempt(
        self,
    ) -> None:
        old = await self.tagged()
        name = self.port.acquires[0][1]
        # More earlier refreshes than ``superseded`` keeps: the oldest ids have
        # dropped off, but their succeeded tickets still share the URL and name.
        for day in range(40):
            stale = Ticket(
                str(uuid4()),
                URL,
                name,
                "succeeded",
                str(uuid4()),
                created_at=f"2026-12-{1 + day % 28:02d}T00:00:00Z",
            )
            self.port.tickets.append(stale)
        journal = ReferenceJournal(self.root)
        ledger = journal.read()
        kept = tuple(t.id for t in self.port.tickets[-32:])
        journal.save(
            replace(
                ledger,
                receipts=(
                    replace(ledger.receipts[0], superseded=kept, resource_name=name),
                ),
            )
        )
        self.assertEqual(32, len(self.receipt().superseded))
        self.library.schedule_refresh(datetime(2027, 3, 1, tzinfo=timezone.utc))
        self.assertEqual(32, len(self.receipt().superseded))
        self.port.acquire_fault = TransportError(Delivery.UNKNOWN, "lost")
        self.port.acquire_lands = False
        with self.assertRaises(TransportError):
            await self.library.advance()
        await self.library.advance()
        unresolved = self.receipt()
        self.assertEqual(
            ("submitted", None, old),
            (unresolved.phase, unresolved.acquisition_id, unresolved.revision_id),
        )
        self.assertIn("not been found", unresolved.error or "")
        # The owner's Retry lands; that ticket, created after the attempt began
        # (within the clock-skew allowance), is the one adopted.
        self.port.now = "2027-03-01T00:00:00Z"
        await self.library.retry(URL)
        await self.library.advance()
        self.assertEqual(self.port.tickets[-1].id, self.receipt().acquisition_id)

    async def test_blocked_requeue_revises_the_same_resource(self) -> None:
        self.library.plan_seed((ENTRY,))
        await self.library.advance()
        held = self.port.tickets[0]
        held.state, held.gate = "blocked", "Post-gate review"
        await self.library.advance()
        await self.library.retry(URL)
        requeued = self.receipt()
        self.assertEqual(held.name, requeued.resource_name)
        self.assertEqual(NOW, requeued.attempt_started_at)
        await self.library.advance()
        self.assertEqual(held.name, self.port.acquires[-1][1])
        self.assertNotEqual(self.port.acquires[0][2], self.port.acquires[-1][2])

    async def test_typed_head_refusal_says_the_previous_revision_stays(
        self,
    ) -> None:
        old = await self.tagged()
        self.library.schedule_refresh(datetime(2027, 3, 1, tzinfo=timezone.utc))
        self.port.acquire_fault = Refusal("INVALID", "INFORMATION_TYPING_REQUIRED")
        self.port.acquire_lands = False
        await self.library.advance()
        refused = self.receipt()
        self.assertEqual(("failed", old), (refused.phase, refused.revision_id))
        self.assertIn("previous revision", refused.error or "")
        # The same reason on the fetched ticket reads the same way.
        await self.library.retry(URL)
        await self.library.advance()
        ticket = self.port.tickets[-1]
        ticket.state, ticket.error = "failed", "INFORMATION_TYPING_REQUIRED"
        await self.library.advance()
        self.assertIn("stay in the Library", self.receipt().error or "")
        # A first fetch has no previous revision, so it keeps the server reason.
        self.library.plan_seed((ENTRY, SECOND))
        self.port.acquire_fault = Refusal("INVALID", "INFORMATION_TYPING_REQUIRED")
        await self.library.advance()
        self.assertIn("INFORMATION_TYPING_REQUIRED", self.receipt(OTHER).error or "")

    async def test_seed_entry_reaches_tagged(self) -> None:
        self.library.plan_seed((ENTRY,))
        queued = self.receipt()
        self.assertEqual(("queued", "seed"), (queued.phase, queued.origin))
        await self.library.advance()
        self.assertEqual(
            [
                (
                    URL,
                    f"Reference: Caddy admin API · {queued.request_id[:8]}",
                    queued.request_id,
                )
            ],
            self.port.acquires,
        )
        self.assertEqual("awaiting", self.receipt().phase)
        await self.library.advance()
        self.assertEqual("awaiting", self.receipt().phase)
        revision = self.port.finish()
        await self.library.advance()
        self.assertEqual(("retained", revision), self.phase_and_revision())
        await self.library.advance()
        tagged = self.receipt()
        self.assertEqual("tagged", tagged.phase)
        self.assertEqual(NOW, tagged.fetched_at)
        self.assertEqual(
            {"device-reference", "caddy", "admin-api"},
            set(self.port.revisions[revision].tags),
        )
        await self.library.advance()
        self.assertEqual(1, len(self.port.tag_calls))

    def phase_and_revision(self, url: str = URL) -> tuple[str, str | None]:
        receipt = self.receipt(url)
        return receipt.phase, receipt.revision_id

    async def test_restart_in_every_phase_resumes_without_duplicate_acquire(
        self,
    ) -> None:
        self.library.plan_seed((ENTRY,))
        phases = []
        for step in range(6):
            self.library = self.open()
            await self.library.advance()
            phases.append(self.receipt().phase)
            if step == 1:
                self.port.finish()
        self.assertEqual(
            ["awaiting", "awaiting", "retained", "tagged", "tagged", "tagged"], phases
        )
        self.assertEqual(1, len(self.port.acquires))
        self.assertEqual(1, len(self.port.tag_calls))

    async def test_uncertain_delivery_finds_ticket_instead_of_resubmitting(
        self,
    ) -> None:
        self.library.plan_seed((ENTRY,))
        self.port.acquire_fault = TransportError(Delivery.UNKNOWN, "lost")
        with self.assertRaises(TransportError):
            await self.library.advance()
        uncertain = self.receipt()
        self.assertEqual(
            ("submitted", None), (uncertain.phase, uncertain.acquisition_id)
        )
        self.library = self.open()
        await self.library.advance()
        self.assertEqual(1, len(self.port.acquires))
        found = self.receipt()
        self.assertEqual(
            ("awaiting", self.port.tickets[0].id), (found.phase, found.acquisition_id)
        )

        # A request that was never sent goes back to queued and is sent once.
        self.library.plan_seed((ENTRY, SECOND))
        self.port.acquire_fault = TransportError(Delivery.NOT_SUBMITTED, "offline")
        self.port.acquire_lands = False
        with self.assertRaises(TransportError):
            await self.library.advance()
        self.assertEqual("queued", self.receipt(OTHER).phase)
        await self.library.advance()
        self.assertEqual("awaiting", self.receipt(OTHER).phase)
        self.assertEqual(3, len(self.port.acquires))
        self.assertEqual(2, len(self.port.tickets))

    async def test_restart_with_blocked_ticket_does_not_resubmit(self) -> None:
        self.library.plan_seed((ENTRY,))
        await self.library.advance()
        self.port.tickets[0].state = "blocked"
        self.port.tickets[0].gate = "Ingest allowance exhausted"
        await self.library.advance()
        blocked = self.receipt()
        self.assertEqual(
            ("blocked", "Ingest allowance exhausted"), (blocked.phase, blocked.error)
        )
        for _ in range(3):
            self.library = self.open()
            self.library.plan_seed((ENTRY,))
            await self.library.advance()
        self.assertEqual("blocked", self.receipt().phase)
        self.assertEqual(1, len(self.port.acquires))

    async def test_failed_extraction_is_failed_not_retained(self) -> None:
        self.library.plan_seed((ENTRY,))
        await self.library.advance()
        self.port.tickets[0].state = "failed"
        self.port.tickets[0].error = "Unsupported\x00 media\ntype"
        await self.library.advance()
        failed = self.receipt()
        self.assertEqual("failed", failed.phase)
        self.assertIsNone(failed.revision_id)
        self.assertEqual("Unsupported media type", failed.error)
        await self.library.advance()
        self.assertEqual("failed", self.receipt().phase)
        self.assertEqual([], self.port.tag_calls)

    async def test_failed_receipt_not_requeued_by_plan_seed(self) -> None:
        self.library.plan_seed((ENTRY,))
        self.port.acquire_fault = Refusal("FORBIDDEN", "Fetching is disabled")
        self.port.acquire_lands = False
        await self.library.advance()
        failed = self.receipt()
        self.assertEqual("failed", failed.phase)
        self.assertIn("Fetching is disabled", failed.error or "")
        self.library = self.open()
        self.library.plan_seed((ENTRY,))
        self.library.plan_seed(())
        await self.library.advance()
        # Dropped from the seed it is retired, but it stays failed and unsent.
        self.assertEqual(replace(failed, retired=True), self.receipt())
        self.assertEqual(1, len(self.port.acquires))

    async def test_retry_only_from_failed_or_blocked(self) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        with self.assertRaises(ValueError):
            await self.library.retry(URL)
        with self.assertRaises(LookupError):
            await self.library.retry("https://docs.example.test/unknown")
        await self.library.advance()
        first, second = self.port.tickets
        first.state, first.error = "failed", "Timed out"
        second.state, second.gate = "blocked", "Allowance"
        await self.library.advance()

        # Failed: the ticket itself is retried and watched again.
        await self.library.retry(URL)
        retried = self.receipt()
        self.assertEqual([first.id], [a for a, _ in self.port.retries])
        self.assertEqual(
            ("awaiting", first.id, None),
            (retried.phase, retried.acquisition_id, retried.error),
        )

        # Blocked: a new acquisition with a new request id; the old ticket is left.
        before = self.receipt(OTHER)
        await self.library.retry(OTHER)
        requeued = self.receipt(OTHER)
        self.assertEqual(
            ("queued", None, None),
            (requeued.phase, requeued.acquisition_id, requeued.error),
        )
        self.assertNotEqual(before.request_id, requeued.request_id)
        self.assertEqual(1, len(self.port.retries))
        with self.assertRaises(ValueError):
            await self.library.retry(OTHER)

    async def test_retire_after_inflight_completion(self) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        await self.library.advance()
        done_revision = self.port.finish(1, ("vendor",))
        await self.library.advance()
        await self.library.advance()
        self.assertEqual("tagged", self.receipt(OTHER).phase)

        # Both leave the seed: one is still in flight, one is already tagged.
        self.library.plan_seed(())
        self.assertTrue(self.receipt().retired)
        inflight_revision = self.port.finish(0, ("vendor",))
        for _ in range(3):
            await self.library.advance()
        inflight = self.receipt()
        self.assertEqual(("tagged", True), (inflight.phase, inflight.retired_tagged))
        self.assertEqual(
            ("vendor", "caddy", "admin-api"),
            self.port.revisions[inflight_revision].tags,
        )
        self.assertTrue(self.receipt(OTHER).retired_tagged)
        self.assertEqual(("vendor", "caddy"), self.port.revisions[done_revision].tags)
        # The sources stay retained: the fake has no withdrawal, and the
        # receipts keep their revisions; no acquisition was repeated.
        self.assertEqual(inflight_revision, inflight.revision_id)
        self.assertEqual(2, len(self.port.acquires))
        calls = len(self.port.tag_calls)
        await self.library.advance()
        self.assertEqual(calls, len(self.port.tag_calls))

        # A retired entry that was never fetched is not fetched.
        self.library.plan_seed((replace(ENTRY, url=URL + "/new"),))
        self.library.plan_seed(())
        await self.library.advance()
        self.assertEqual(2, len(self.port.acquires))

    async def test_journal_rejects_symlink_and_public_mode(self) -> None:
        journal = ReferenceJournal(self.root)
        self.assertEqual(ReferenceLedger(), journal.read())
        self.library.plan_seed((ENTRY,))
        self.assertEqual(0o600, journal.path.stat().st_mode & 0o777)
        ledger = journal.read()
        self.assertEqual(1, len(ledger.receipts))
        journal.path.chmod(0o644)
        with self.assertRaises(ValueError):
            journal.read()
        journal.path.unlink()
        (self.root / "elsewhere.json").write_text("{}")
        journal.path.symlink_to(self.root / "elsewhere.json")
        with self.assertRaises(ValueError):
            journal.read()
        with self.assertRaises(ValueError):
            journal.save(ledger)
        journal.path.unlink()
        receipt = ledger.receipts[0]
        with self.assertRaises(ValueError):
            ReferenceLedger((receipt, receipt))
        with self.assertRaises(ValueError):
            ReferenceLedger((receipt,), refresh_days=0)
        for broken in (
            {"phase": "awaiting"},
            {"retired_tagged": True},
            {"request_id": "not-a-uuid"},
            {"url": "http://docs.example.test/"},
        ):
            with self.subTest(broken=broken), self.assertRaises(ValueError):
                replace(receipt, **broken)

    async def test_tags_merge_keeps_existing_tags_and_uses_tags_version(
        self,
    ) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        await self.library.advance()
        merged = self.port.finish(0, ("vendor", "caddy"), 7.0)
        await self.library.advance()

        # An uncertain tags call is resolved by re-reading the revision; the
        # replay carries the same request id, so it is idempotent.
        self.port.tags_fault = TransportError(Delivery.UNKNOWN, "lost")
        with self.assertRaises(TransportError):
            await self.library.advance()
        self.assertEqual("retained", self.receipt().phase)
        await self.library.advance()
        self.assertEqual("tagged", self.receipt().phase)
        first, replay = self.port.tag_calls
        self.assertEqual(first, replay)
        revision, tags, version, _ = first
        self.assertEqual(
            (merged, ("vendor", "caddy", "device-reference", "admin-api"), 7),
            (revision, tags, version),
        )

        # A revision already carrying every required tag is not re-tagged.
        complete = self.port.finish(1, ("caddy", "device-reference"), 3.0)
        await self.library.advance()
        await self.library.advance()
        self.assertEqual(("tagged", complete), self.phase_and_revision(OTHER))
        self.assertEqual(2, len(self.port.tag_calls))

        # A non-integral tags version is a broken reply: that receipt fails alone.
        third = OTHER + "/x"
        self.library.plan_seed((ENTRY, SECOND, replace(SECOND, url=third)))
        await self.library.advance()
        odd = self.port.finish(2, (), 2.5)
        await self.library.advance()
        self.assertEqual(("retained", odd), self.phase_and_revision(third))
        await self.library.advance()
        broken = self.receipt(third)
        self.assertEqual(("failed", odd), (broken.phase, broken.revision_id))
        self.assertIn("could not accept", broken.error or "")
        self.assertEqual(2, len(self.port.tag_calls))

    async def test_broken_receipt_fails_alone_and_the_pass_continues(self) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        await self.library.advance()
        # The first receipt's ticket vanishes from the listing; the second's
        # completes. Both are handled in the same pass.
        missing = self.port.tickets.pop(0)
        revision = self.port.finish(0)
        await self.library.advance()
        lost = self.receipt()
        self.assertEqual(("failed", missing.id), (lost.phase, lost.acquisition_id))
        self.assertIn("could not accept", lost.error or "")
        self.assertEqual(("retained", revision), self.phase_and_revision(OTHER))
        await self.library.advance()
        self.assertEqual("tagged", self.receipt(OTHER).phase)
        # Retry of a ticket the listing no longer shows requeues a new acquisition.
        await self.library.retry(URL)
        requeued = self.receipt()
        self.assertEqual(
            ("queued", (missing.id,)), (requeued.phase, requeued.superseded)
        )

    async def test_uncertain_acquire_never_matches_an_older_ticket(self) -> None:
        self.library.plan_seed((ENTRY,))
        await self.library.advance()
        held = self.port.tickets[0]
        held.state, held.gate = "blocked", "Allowance"
        await self.library.advance()
        await self.library.retry(URL)
        self.assertEqual((held.id,), self.receipt().superseded)
        # The new acquire is lost before it reaches the server.
        self.port.acquire_fault = TransportError(Delivery.UNKNOWN, "lost")
        self.port.acquire_lands = False
        with self.assertRaises(TransportError):
            await self.library.advance()
        # The old held ticket has the same URL but the old attempt's name.
        held.state, held.gate = "succeeded", None
        self.port.revisions[str(uuid4())] = Revision()
        for _ in range(2):
            await self.library.advance()
        unresolved = self.receipt()
        self.assertEqual(
            ("submitted", None, None),
            (unresolved.phase, unresolved.acquisition_id, unresolved.revision_id),
        )
        self.assertIn("not been found", unresolved.error or "")

        # The owner's retry re-sends that acquire with the same request id.
        await self.library.retry(URL)
        replayed = self.receipt()
        self.assertEqual(self.port.acquires[-2], self.port.acquires[-1])
        self.assertEqual(replayed.request_id, self.port.acquires[-1][2])
        self.assertEqual(
            ("awaiting", self.port.tickets[-1].id),
            (replayed.phase, replayed.acquisition_id),
        )

    async def test_foreign_ticket_is_not_adopted(self) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        await self.library.advance()
        self.port.tickets[0].url = "https://elsewhere.test/page"
        self.port.finish(0)
        await self.library.advance()
        foreign = self.receipt()
        self.assertEqual(("failed", None), (foreign.phase, foreign.revision_id))
        self.assertIn("does not match", foreign.error or "")

        # Retry checks the ticket's name before trusting its state.
        self.port.tickets[1].state, self.port.tickets[1].error = "failed", "x"
        await self.library.advance()
        self.port.tickets[1].name = "Reference: something else"
        await self.library.retry(OTHER)
        self.assertIn("does not match", self.receipt(OTHER).error or "")
        self.assertEqual([], self.port.retries)

    async def test_refusal_prose_is_sanitized(self) -> None:
        self.library.plan_seed((ENTRY,))
        self.port.acquire_fault = Refusal("FORBIDDEN", "bad\x01 news\x7f here")
        self.port.acquire_lands = False
        await self.library.advance()
        error = self.receipt().error or ""
        self.assertEqual("failed", self.receipt().phase)
        self.assertFalse(any(ord(c) < 32 or ord(c) == 127 for c in error))
        self.assertIn("bad news here", error)

    async def test_retired_failed_receipt_is_untagged_once(self) -> None:
        self.library.plan_seed((ENTRY, SECOND))
        await self.library.advance()
        first = self.port.finish(0, ("vendor",))
        second = self.port.finish(1)
        await self.library.advance()
        await self.library.advance()
        # A refresh of the first later failed; its tagged revision is still known.
        journal = ReferenceJournal(self.root)
        ledger = journal.read()
        journal.save(
            replace(
                ledger,
                receipts=(
                    replace(ledger.receipts[0], phase="failed", error="Refresh failed"),
                    ledger.receipts[1],
                ),
            )
        )
        self.library.plan_seed(())
        self.port.tags_fault = None
        await self.library.advance()
        untagged = self.receipt()
        self.assertEqual(("failed", True), (untagged.phase, untagged.retired_tagged))
        self.assertEqual(
            ("vendor", "caddy", "admin-api"), self.port.revisions[first].tags
        )
        self.assertTrue(self.receipt(OTHER).retired_tagged)

        # A refused removal is not attempted again until the owner retries.
        self.library.plan_seed((SECOND,))
        self.assertEqual("retained", self.receipt(OTHER).phase)
        await self.library.advance()
        self.library.plan_seed(())
        self.port.tags_fault = Refusal("CONFLICT", "Tags changed")
        await self.library.advance()
        refused = self.receipt(OTHER)
        self.assertEqual(("failed", True), (refused.phase, refused.untag_failed))
        calls = len(self.port.tag_calls)
        await self.library.advance()
        self.assertEqual(calls, len(self.port.tag_calls))
        await self.library.retry(OTHER)
        await self.library.advance()
        self.assertNotIn("device-reference", self.port.revisions[second].tags)

    async def test_tags_refusal_is_failed_and_retry_resumes_tagging(self) -> None:
        self.library.plan_seed((ENTRY,))
        await self.library.advance()
        revision = self.port.finish()
        await self.library.advance()
        self.port.tags_fault = Refusal("CONFLICT", "Tags changed")
        await self.library.advance()
        failed = self.receipt()
        self.assertEqual(("failed", revision), (failed.phase, failed.revision_id))
        self.assertIn("information.tags", failed.error or "")
        await self.library.retry(URL)
        self.assertEqual("retained", self.receipt().phase)
        await self.library.advance()
        self.assertEqual("tagged", self.receipt().phase)
        self.assertEqual([], self.port.retries)
        self.assertEqual(1, len(self.port.acquires))


if __name__ == "__main__":
    unittest.main()
