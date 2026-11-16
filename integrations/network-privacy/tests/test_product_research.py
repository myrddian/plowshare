"""Research admissions survive uncertain delivery and never bypass source review."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError
from plowshare.contracts import InformationRevisionDto
from test_reference_library import FakePort

from plowshare_privacy.product_research import (
    ProductAnswer,
    ProductContext,
    ProductResearch,
    ProductResearchJournal,
    ProductResearchPort,
    ProductRun,
    ProductSource,
)
from plowshare_privacy.reference_library import ReferenceJournal, ReferenceLibrary
from plowshare_privacy.reference_requests import ReferenceRequest, ReferenceRequests

URL = "https://privacy.example.org/tv-tracking"
REVISION = "11111111-1111-4111-8111-111111111111"
REPORT = "22222222-2222-4222-8222-222222222222"
ANSWER = ProductAnswer(
    REPORT,
    "A reviewed answer\n\nFirmware applicability remains unknown.",
    (ProductSource(URL, "TV tracking research", "privacy", REVISION),),
)


class ProductResearchTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.library = ReferenceLibrary(ReferenceJournal(self.root), FakePort())
        self.requests = ReferenceRequests(self.root, self.library)
        self.question = self.requests.request(
            "lg-tv", "What viewing data does it collect?", None, str(uuid4())
        )
        self.port = AsyncMock(spec=ProductResearchPort)
        self.port.start.return_value = "orc_product_fixture"
        self.port.receipt.return_value = "orc_product_fixture"
        self.port.outcome.return_value = ProductRun(
            "finished", json.dumps(ANSWER.encoded())
        )
        self.preparations = 0
        self.adopt = AsyncMock()
        patcher = patch.object(self.library, "adopt_research", self.adopt)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.service = self.restart()

    async def prepare(self, request: ReferenceRequest, probe: bool) -> ProductContext:
        self.assertEqual(request, self.question)
        self.assertFalse(probe)
        self.preparations += 1
        return ProductContext()

    def restart(self) -> ProductResearch:
        return ProductResearch(
            ProductResearchJournal(self.root),
            self.port,
            self.requests,
            self.library,
            self.prepare,
        )

    async def test_double_click_and_restart_return_the_same_admission(self) -> None:
        first = self.service.queue(self.question.id, False)
        self.assertEqual(first, self.service.queue(self.question.id, False))
        self.service = self.restart()
        await asyncio.gather(self.service.poll(), self.service.poll())
        self.port.start.assert_awaited_once()
        retaining = self.service.entry(self.question.id)
        assert retaining is not None
        self.assertEqual("retaining", retaining.phase)
        await self.service.poll()
        entry = self.service.entry(self.question.id)
        self.assertIsNotNone(entry)
        assert entry is not None
        self.assertEqual(("finished", ANSWER), (entry.phase, entry.result))
        self.assertEqual(1, self.preparations)
        self.assertEqual(1, self.adopt.await_count)
        self.assertEqual(entry, self.restart().entry(self.question.id))

    async def test_unknown_admission_recovers_only_by_receipt(self) -> None:
        self.service.queue(self.question.id, False)
        self.port.start.side_effect = TransportError(Delivery.UNKNOWN, "lost reply")
        await self.service.poll()
        submitted = self.service.entry(self.question.id)
        assert submitted is not None
        self.assertEqual("submitted", submitted.phase)
        self.service = self.restart()
        self.port.receipt.side_effect = Refusal("NOT_FOUND", "not visible")
        await self.service.poll()
        self.assertEqual(
            submitted.request_id, self.service.queue(self.question.id, False).request_id
        )
        self.port.receipt.side_effect = None
        await self.service.poll()
        self.port.start.assert_awaited_once()
        self.port.receipt.assert_awaited_with(submitted.request_id)
        self.assertEqual(1, self.preparations)

    async def test_proven_non_delivery_allows_explicit_new_attempt_only(self) -> None:
        first = self.service.queue(self.question.id, False)
        self.port.start.side_effect = TransportError(Delivery.NOT_SUBMITTED, "offline")
        await self.service.poll()
        await self.service.poll()
        self.port.start.assert_awaited_once()
        retried = self.service.queue(self.question.id, False)
        self.assertNotEqual(first.request_id, retried.request_id)

    async def test_interrupted_preparation_is_not_repeated(self) -> None:
        queued = self.service.queue(self.question.id, False)
        self.service.journal.save((replace(queued, phase="preparing"),))
        self.service = self.restart()
        await self.service.poll()
        self.port.start.assert_not_awaited()
        self.assertEqual(0, self.preparations)
        entry = self.service.entry(self.question.id)
        assert entry is not None
        self.assertEqual("failed", entry.phase)

    async def test_failed_or_malformed_answer_cannot_register_sources(self) -> None:
        self.service.queue(self.question.id, False)
        await self.service.poll()
        self.port.outcome.return_value = ProductRun("failed", None)
        await self.service.poll()
        self.adopt.assert_not_awaited()
        self.service.queue(self.question.id, False)
        await self.service.poll()
        malformed = {**ANSWER.encoded(), "sources": [{"url": URL}]}
        self.port.outcome.return_value = ProductRun("finished", json.dumps(malformed))
        await self.service.poll()
        self.adopt.assert_not_awaited()

    def test_probe_requires_linked_device_and_validates_before_queueing(self) -> None:
        with self.assertRaises(ValueError):
            self.service.queue(self.question.id, True)
        self.assertEqual((), self.service.journal.read())
        with self.assertRaises(ValueError):
            self.service.queue(str(uuid4()), False)
        self.requests.dismiss(self.question.id)
        with self.assertRaises(ValueError):
            self.service.queue(self.question.id, False)

    def test_unknown_fields_and_world_readable_journal_are_refused(self) -> None:
        entry = self.service.queue(self.question.id, False)
        with self.assertRaises(ValueError):
            ProductResearchJournal.decode(
                {"version": 1, "entries": [{**entry.encoded(), "command": "ignored"}]}
            )
        self.service.journal.path.chmod(0o644)
        with self.assertRaises(ValueError):
            self.restart()


class ResearchReferenceAdoptionTest(unittest.IsolatedAsyncioTestCase):
    async def test_research_title_survives_result_adoption_and_journal_reload_in_words(
        self,
    ) -> None:
        title = "\u00a0\u0085\ufeff".join(["research"] * 250)
        source = ProductSource(URL, title, "privacy", REVISION)
        answer = ProductAnswer(REPORT, "Reviewed research.", (source,))
        self.assertEqual(
            answer, ProductAnswer.decode(json.loads(json.dumps(answer.encoded())))
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            port = AsyncMock()
            port.revision.return_value = InformationRevisionDto(
                id=REVISION,
                kind="source",
                source_uri=URL,
                source_name="Product research / retained-identity",
                tags_version=1,
            )
            library = ReferenceLibrary(ReferenceJournal(root), port)
            receipt = await library.adopt_research(
                "lg-tv", title, URL, "privacy", REVISION
            )
            self.assertEqual(title, receipt.title)
            reloaded = ReferenceJournal(root).read().receipts[0]
            self.assertEqual(receipt, reloaded)
            port.acquire.assert_not_awaited()
        with self.assertRaisesRegex(ValueError, "word"):
            ProductSource(URL, "research " * 251, "privacy", REVISION)

    async def test_adoption_checks_source_identity_and_reuses_without_acquiring(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            port = AsyncMock()
            port.revision.return_value = InformationRevisionDto(
                id=REVISION,
                kind="source",
                source_uri=URL,
                source_name="Retained research",
                tags_version=1,
            )
            library = ReferenceLibrary(ReferenceJournal(root), port)
            first = await library.adopt_research(
                "lg-tv", "Tracking", URL, "privacy", REVISION
            )
            self.assertEqual(
                ("research", "retained", REVISION, ("privacy",)),
                (first.origin, first.phase, first.revision_id, first.topics),
            )
            self.assertEqual(
                first,
                await library.adopt_research(
                    "lg-tv", "Tracking", URL, "privacy", REVISION
                ),
            )
            port.revision.assert_awaited_once()
            port.acquire.assert_not_awaited()
            alias = await library.adopt_research(
                "sony-tv", "Same shared source", URL, "privacy", REVISION
            )
            self.assertIn("sony-tv", alias.topics)
            self.assertEqual(1, len(library.view()))
            port.revision.assert_awaited_once()
            await library.advance()
            self.assertEqual("tagged", library.view()[0].phase)
            self.assertEqual(
                {"device-reference", "lg-tv", "sony-tv", "privacy"},
                set(port.replace_tags.await_args.args[1]),
            )
            port.revision.return_value = InformationRevisionDto(
                id=REVISION,
                kind="report",
                source_uri=URL,
                source_name="Wrong kind",
                tags_version=1,
            )
            with self.assertRaises(ValueError):
                await library.adopt_research(
                    "sony-tv", "Wrong", URL + "/other", "advisory", REVISION
                )
