"""Only authenticated owner actions queue research or optional device checks."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_device_dossier import MAC, card
from test_reference_library import FakePort

from plowshare_privacy.device_inventory import DeviceInventory
from plowshare_privacy.product_research import (
    ProductContext,
    ProductResearch,
    ProductResearchJournal,
    ProductResearchPort,
    ProductRun,
)
from plowshare_privacy.reference_library import ReferenceJournal, ReferenceLibrary
from plowshare_privacy.reference_requests import ReferenceRequests
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "product-research-dashboard-fixture-123456"
HEADERS = {"Authorization": "Bearer " + TOKEN}


class ProductResearchWebTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        config = configuration(self.root)
        worker = Worker(
            config, FixturePort(config), MemoryReceipts(), CountingCollector(config)
        )
        library = ReferenceLibrary(ReferenceJournal(self.root), FakePort())
        self.requests = ReferenceRequests(self.root, library)
        self.question = self.requests.request(
            "lg-tv", "What viewing data does it collect?", None, str(uuid4())
        )
        self.port = AsyncMock(spec=ProductResearchPort)
        self.port.start.return_value = "orc_product_fixture"
        self.prepare = AsyncMock(return_value=ProductContext())
        self.research = ProductResearch(
            ProductResearchJournal(self.root),
            self.port,
            self.requests,
            library,
            self.prepare,
        )
        self.client: TestClient[web.Request, web.Application] = TestClient(
            TestServer(
                application(
                    worker,
                    TOKEN,
                    reference_requests=self.requests,
                    product_research=self.research,
                )
            )
        )
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.path = f"/api/reference-requests/{self.question.id}"
        self.worker = worker

    async def test_authenticated_click_queues_once_without_sdk_work_in_the_route(
        self,
    ) -> None:
        self.assertEqual(
            401,
            (
                await self.client.post(self.path + "/research", json={"probe": False})
            ).status,
        )
        first = await self.client.post(
            self.path + "/research", json={"probe": False}, headers=HEADERS
        )
        self.assertEqual(200, first.status)
        row = (await first.json())["requests"][0]
        self.assertEqual("queued", row["research"]["phase"])
        await self.client.post(
            self.path + "/research", json={"probe": False}, headers=HEADERS
        )
        self.assertEqual(1, len(self.research.view()))
        self.port.start.assert_not_awaited()
        self.prepare.assert_not_awaited()
        self.assertEqual(
            400,
            (
                await self.client.post(self.path + "/dismiss", json={}, headers=HEADERS)
            ).status,
        )
        self.assertEqual(
            400,
            (
                await self.client.post(
                    self.path + "/approve",
                    json={"urls": ["https://example.org"]},
                    headers=HEADERS,
                )
            ).status,
        )

    async def test_external_fields_and_unlinked_probes_are_rejected_before_queueing(
        self,
    ) -> None:
        for body in (
            {"probe": True},
            {"probe": "yes"},
            {"probe": False, "address": "192.168.1.10"},
            {"probe": False, "url": "https://example.org"},
        ):
            response = await self.client.post(
                self.path + "/research", json=body, headers=HEADERS
            )
            self.assertEqual(400, response.status, body)
        self.assertEqual((), self.research.view())
        self.prepare.assert_not_awaited()

    async def test_stop_queued_and_running_research_uses_existing_lifecycle(
        self,
    ) -> None:
        self.research.queue(self.question.id, False)
        response = await self.client.post(
            self.path + "/research/cancel", json={}, headers=HEADERS
        )
        self.assertEqual(200, response.status)
        self.port.cancel.assert_not_awaited()
        self.research.queue(self.question.id, False)
        await self.research.poll()
        self.port.outcome.return_value = ProductRun("running", None)
        response = await self.client.post(
            self.path + "/research/cancel", json={}, headers=HEADERS
        )
        self.assertEqual(200, response.status)
        self.port.cancel.assert_awaited_once_with("orc_product_fixture")
        self.port.outcome.return_value = ProductRun("cancelled", None)
        await self.research.poll()
        self.assertFalse(self.research.active(self.question.id))
        self.assertEqual(1, self.port.start.await_count)

    async def test_old_unlinked_question_can_select_only_a_current_monitored_device(
        self,
    ) -> None:
        response = await self.client.post(
            self.path + "/device", json={"device_id": "mac:" + MAC}, headers=HEADERS
        )
        self.assertEqual(400, response.status)
        self.assertIsNone(self.requests.get(self.question.id).device_id)
        with patch.object(
            self.worker, "device_inventory", return_value=DeviceInventory((card(),))
        ):
            response = await self.client.post(
                self.path + "/device", json={"device_id": "mac:" + MAC}, headers=HEADERS
            )
            self.assertEqual(200, response.status)
            body = await response.json()
            self.assertTrue(body["requests"][0]["probe_available"])
            self.assertEqual("mac:" + MAC, body["devices"][0]["id"])
        self.research.queue(self.question.id, True)
        response = await self.client.post(
            self.path + "/device", json={"device_id": "mac:" + MAC}, headers=HEADERS
        )
        self.assertEqual(400, response.status)
