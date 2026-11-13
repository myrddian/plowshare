"""The reference library journals each acquisition so restarts never lose or repeat one."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import dataclass, field, replace
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

    def dto(self) -> InformationAcquisitionDto:
        return InformationAcquisitionDto(
            id=self.id,
            url=self.url,
            source_name=self.name,
            state=self.state,
            revision_id=self.revision_id,
            error=self.error,
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
    # One-shot faults raised by the next call of that operation.
    acquire_fault: Exception | None = None
    acquire_lands: bool = True
    tags_fault: Exception | None = None

    async def acquire(
        self, url: str, name: str, request_id: str
    ) -> InformationAcquisitionDto:
        self.acquires.append((url, name, request_id))
        fault, self.acquire_fault = self.acquire_fault, None
        if fault is not None and not self.acquire_lands:
            raise fault
        ticket = Ticket(str(uuid4()), url, name)
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
        self, index: int = -1, tags: tuple[str, ...] = (), version: float = 1.0
    ) -> str:
        """Complete one ticket (the newest by default) with a fresh revision."""
        revision = str(uuid4())
        self.revisions[revision] = Revision(tags, version)
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

    async def test_seed_entry_reaches_tagged(self) -> None:
        self.library.plan_seed((ENTRY,))
        queued = self.receipt()
        self.assertEqual(("queued", "seed"), (queued.phase, queued.origin))
        await self.library.advance()
        self.assertEqual(
            [(URL, "Reference: Caddy admin API", queued.request_id)],
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
        self.assertEqual(failed, self.receipt())
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

        # A non-integral tags version is a broken reply, not a version.
        third = OTHER + "/x"
        self.library.plan_seed((ENTRY, SECOND, replace(SECOND, url=third)))
        await self.library.advance()
        odd = self.port.finish(2, (), 2.5)
        await self.library.advance()
        self.assertEqual(("retained", odd), self.phase_and_revision(third))
        with self.assertRaises(ValueError):
            await self.library.advance()
        self.assertEqual(2, len(self.port.tag_calls))

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
