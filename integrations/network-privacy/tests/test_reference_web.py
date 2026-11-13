"""Reference library dashboard routes and the overview's attention counts."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

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
from plowshare_privacy.reference_service import ReferenceService
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

    async def test_overview_omits_references_without_service(self) -> None:
        client = await self.client(None)
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        self.assertIsNone(overview["references"])


if __name__ == "__main__":
    unittest.main()
