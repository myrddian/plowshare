"""Reference library dashboard routes and the overview's attention counts."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from plowshare import Delivery, Refusal, TransportError
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_operator_console import ConsoleFixture, Settings
from test_reference_library import ENTRY, OTHER, SECOND, URL, FakePort

from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.operator_state import FileOperatorStore
from plowshare_privacy.reference_library import ReferenceJournal, ReferenceLibrary
from plowshare_privacy.reference_service import (
    JOURNAL_UNREADABLE,
    ReferenceService,
    open_references,
)
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "dashboard-fixture-bearer-123456"
HEADERS = {"Authorization": "Bearer " + TOKEN}


async def _recover(*_: object) -> None:
    return None


class ReferenceWebTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = configuration(self.root)
        self.store = FileOperatorStore(self.root)
        self.worker = Worker(
            self.config,
            FixturePort(self.config),
            MemoryReceipts(),
            CountingCollector(self.config),
            self.store,
        )
        self.console = OperatorConsole(
            self.root,
            self.worker,
            ConsoleFixture(),
            Settings(),
            LocalDeviceDiscovery(self.root),
            self.store,
            _recover,
            FileManualInvestigations(self.root),
        )
        self.port = FakePort()
        library = ReferenceLibrary(ReferenceJournal(self.root), self.port)
        library.plan_seed((ENTRY, SECOND))
        self.service = ReferenceService(library)

    async def client(
        self, service: ReferenceService | None
    ) -> TestClient[web.Request, web.Application]:
        client = TestClient(
            TestServer(
                application(
                    self.worker, TOKEN, console=self.console, references=service
                )
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    async def fail_first(self) -> None:
        self.port.acquire_fault = Refusal("FORBIDDEN", "Fetching is disabled")
        await self.service.poll()

    async def test_references_list_requires_dashboard_key(self) -> None:
        client = await self.client(self.service)
        self.assertEqual(401, (await client.get("/api/references")).status)
        self.assertEqual(
            401, (await client.post("/api/references/retry", json={"url": URL})).status
        )
        body = await (await client.get("/api/references", headers=HEADERS)).json()
        self.assertEqual([URL, OTHER], [row["url"] for row in body["references"]])
        first = body["references"][0]
        self.assertEqual(
            ("queued", "Waiting to be fetched"), (first["phase"], first["label"])
        )
        self.assertIsNone(body["error"])

    async def test_missing_service_is_not_found(self) -> None:
        client = await self.client(None)
        self.assertEqual(
            404, (await client.get("/api/references", headers=HEADERS)).status
        )

    async def test_retry_rejects_unknown_url(self) -> None:
        client = await self.client(self.service)
        for url in ("https://docs.example.test/missing", 5):
            response = await client.post(
                "/api/references/retry", headers=HEADERS, json={"url": url}
            )
            self.assertEqual(400, response.status)
        extra = await client.post(
            "/api/references/retry", headers=HEADERS, json={"url": URL, "x": 1}
        )
        self.assertEqual(400, extra.status)
        # A reference that is not failed or blocked cannot be retried.
        queued = await client.post(
            "/api/references/retry", headers=HEADERS, json={"url": URL}
        )
        self.assertEqual(400, queued.status)
        self.assertIn("Only a failed", (await queued.json())["error"])

    async def test_retry_failed_receipt(self) -> None:
        await self.fail_first()
        client = await self.client(self.service)
        listed = await (await client.get("/api/references", headers=HEADERS)).json()
        failed = listed["references"][0]
        self.assertEqual("failed", failed["phase"])
        self.assertIn("Fetching is disabled", failed["error"])
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        self.assertEqual({"failed": 1, "blocked": 0}, overview["references"])
        response = await client.post(
            "/api/references/retry", headers=HEADERS, json={"url": URL}
        )
        self.assertEqual(200, response.status)
        again = (await response.json())["references"][0]
        self.assertEqual("queued", again["phase"])
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        self.assertEqual({"failed": 0, "blocked": 0}, overview["references"])

    async def test_transport_error_is_recorded_not_raised(self) -> None:
        self.port.acquire_fault = TransportError(Delivery.NOT_SUBMITTED, "offline")
        await self.service.poll()
        self.assertIn("TransportError", self.service.error or "")
        await self.service.poll()
        self.assertIsNone(self.service.error)

    async def test_retry_reports_a_connection_or_library_failure(self) -> None:
        await self.fail_first()
        client = await self.client(self.service)
        retry = {"url": URL}
        # A retried failure with a ticket reads the Library; the port faults.
        self.service.library.journal.save(
            replace(
                self.service.library.journal.read(),
                receipts=tuple(
                    replace(r, acquisition_id=str(uuid4())) if r.url == URL else r
                    for r in self.service.library.view()
                ),
            )
        )

        async def offline() -> tuple[object, ...]:
            raise TransportError(Delivery.NOT_SUBMITTED, "offline")

        async def refused() -> tuple[object, ...]:
            raise Refusal("FORBIDDEN", "Listing is not allowed")

        self.port.acquisitions = offline  # type: ignore[method-assign,assignment]
        down = await client.post("/api/references/retry", headers=HEADERS, json=retry)
        self.assertEqual(502, down.status)
        self.assertIn("connection", (await down.json())["error"])
        self.port.acquisitions = refused  # type: ignore[method-assign,assignment]
        denied = await client.post("/api/references/retry", headers=HEADERS, json=retry)
        self.assertEqual(409, denied.status)
        self.assertIn("refused", (await denied.json())["error"])

    async def test_retry_clears_a_failed_tag_removal(self) -> None:
        library = self.service.library
        ledger = library.journal.read()
        library.journal.save(
            replace(
                ledger,
                receipts=tuple(
                    replace(
                        r,
                        phase="tagged",
                        retired=True,
                        untag_failed=True,
                        revision_id=str(uuid4()),
                    )
                    if r.url == URL
                    else r
                    for r in ledger.receipts
                ),
            )
        )
        client = await self.client(self.service)
        response = await client.post(
            "/api/references/retry", headers=HEADERS, json={"url": URL}
        )
        self.assertEqual(200, response.status, await response.text())
        first = (await response.json())["references"][0]
        self.assertFalse(first["untag_failed"])
        self.assertEqual("tagged", first["phase"])

    async def test_unreadable_journal_disables_only_references(self) -> None:
        root = self.root / "bad"
        root.mkdir()
        (root / "references.json").write_text("{not json")
        (root / "references.json").chmod(0o600)
        service, detail = open_references(root, self.port)
        self.assertIsNone(service)
        self.assertEqual(JOURNAL_UNREADABLE, detail)
        client = TestClient(
            TestServer(
                application(
                    self.worker,
                    TOKEN,
                    console=self.console,
                    references=service,
                    references_detail=detail,
                )
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertEqual(JOURNAL_UNREADABLE, status["references_detail"])
        self.assertEqual(
            404, (await client.get("/api/references", headers=HEADERS)).status
        )

    async def test_invalid_seed_still_stops_startup(self) -> None:
        with (
            patch(
                "plowshare_privacy.reference_service.load_seed",
                side_effect=ValueError("bad seed"),
            ),
            self.assertRaises(ValueError),
        ):
            open_references(self.root / "seedless", self.port)

    async def test_overview_omits_references_without_service(self) -> None:
        client = await self.client(None)
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        self.assertIsNone(overview["references"])


if __name__ == "__main__":
    unittest.main()
