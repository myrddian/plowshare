"""Deep port scan dashboard routes, the overview, and start-up composition."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Awaitable, Callable

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from support import CountingCollector, FixturePort, MemoryReceipts, configuration, event
from test_deep_scan import ADDRESSES, LAMP, Identities, Probe, monitored
from test_operator_console import ConsoleFixture, Settings

from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.deep_scan import (
    JOURNAL_UNREADABLE,
    DeepScanJournal,
    DeepScanner,
    compose_scanner,
    open_scanner,
)
from plowshare_privacy.device_inventory import DeviceInventory
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.operator_state import FileOperatorStore
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "dashboard-fixture-bearer-123456"
HEADERS = {"Authorization": "Bearer " + TOKEN}
DISABLED = "Deep port scans are not enabled"


class DeepScanWebTest(unittest.IsolatedAsyncioTestCase):
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
        self.cards = tuple(monitored(d, a) for d, a in ADDRESSES.items())
        self.identities = Identities()

    def scanner(self, *, identities: bool = True) -> DeepScanner:
        return DeepScanner(
            DeepScanJournal(self.root),
            lambda: DeviceInventory(self.cards),
            lambda: self.identities if identities else None,
            probe=Probe({22}),
            ports=range(1, 11),
        )

    async def client(
        self, scanner: DeepScanner | None, **extra: object
    ) -> TestClient[web.Request, web.Application]:
        client = TestClient(
            TestServer(
                application(
                    self.worker,
                    TOKEN,
                    console=self.console,
                    deep_scans=scanner,
                    **extra,  # type: ignore[arg-type]
                )
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    async def test_deep_scan_routes_request_cancel_and_report_state(self) -> None:
        scanner = self.scanner()
        client = await self.client(scanner)
        path = f"/api/devices/{LAMP}/deep-scan"
        self.assertEqual(401, (await client.get(path)).status)
        self.assertEqual(401, (await client.post(path + "/request", json={})).status)
        idle = await (await client.get(path, headers=HEADERS)).json()
        self.assertEqual(("idle", True), (idle["state"], idle["available"]))
        queued = await client.post(path + "/request", headers=HEADERS, json={})
        self.assertEqual(200, queued.status)
        self.assertEqual("queued", (await queued.json())["state"])
        cancelled = await client.post(path + "/cancel", headers=HEADERS, json={})
        self.assertEqual("idle", (await cancelled.json())["state"])
        # Unknown devices are 404 with the scanner's own text; bad IDs are 400.
        missing = await client.post(
            "/api/devices/mac:aa:bb:cc:dd:ee:77/deep-scan/request",
            headers=HEADERS,
            json={},
        )
        self.assertEqual(404, missing.status)
        self.assertIn("error", await missing.json())
        bad = await client.get("/api/devices/10.1.2.11/deep-scan", headers=HEADERS)
        self.assertEqual(400, bad.status)
        # A body with fields is refused.
        extra = await client.post(path + "/request", headers=HEADERS, json={"a": 1})
        self.assertEqual(400, extra.status)

    async def test_a_scanner_without_identities_explains_itself(self) -> None:
        client = await self.client(self.scanner(identities=False))
        response = await client.post(
            f"/api/devices/{LAMP}/deep-scan/request", headers=HEADERS, json={}
        )
        self.assertEqual(400, response.status)
        self.assertIn("UniFi or Pi-hole", (await response.json())["error"])
        state = await (await client.get("/api/deep-scans", headers=HEADERS)).json()
        self.assertFalse(state["available"])
        self.assertIn("UniFi or Pi-hole", state["detail"])

    async def test_the_automatic_limit_route_validates_its_range(self) -> None:
        client = await self.client(self.scanner())
        state = await (await client.get("/api/deep-scans", headers=HEADERS)).json()
        self.assertEqual(
            {"automatic_limit", "used_today", "available", "detail"}, set(state)
        )
        for value in (13, -1, "4", True):
            response = await client.post(
                "/api/deep-scans", headers=HEADERS, json={"automatic_limit": value}
            )
            self.assertEqual(400, response.status, value)
            self.assertIn(
                "from 0 to 12 automatic deep scans", (await response.json())["error"]
            )
        saved = await client.post(
            "/api/deep-scans", headers=HEADERS, json={"automatic_limit": 4}
        )
        self.assertEqual(200, saved.status)
        self.assertEqual(4, (await saved.json())["automatic_limit"])

    async def test_overview_lists_deep_scans_and_the_configured_ports(self) -> None:
        scanner = self.scanner()
        scanner.request(LAMP)
        client = await self.client(scanner)
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        deep = overview["deep_scans"]
        self.assertEqual(
            {"views", "usage", "available", "detail", "configured_ports"}, set(deep)
        )
        self.assertEqual([LAMP], [v["device_id"] for v in deep["views"]])
        self.assertEqual(
            list(self.worker.config.collection.ports), deep["configured_ports"]
        )
        self.assertEqual({"automatic_limit", "used_today"}, set(deep["usage"]))
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertTrue(status["deep_scans_available"])

    async def test_routes_are_absent_without_a_scanner(self) -> None:
        client = await self.client(None)
        for method, path in (
            ("get", f"/api/devices/{LAMP}/deep-scan"),
            ("post", f"/api/devices/{LAMP}/deep-scan/request"),
            ("post", f"/api/devices/{LAMP}/deep-scan/cancel"),
            ("get", "/api/deep-scans"),
            ("post", "/api/deep-scans"),
        ):
            call = getattr(client, method)
            kwargs = {"json": {"automatic_limit": 1}} if method == "post" else {}
            response = await call(path, headers=HEADERS, **kwargs)
            self.assertEqual(404, response.status, path)
            self.assertEqual(DISABLED, await response.text())
        overview = await (await client.get("/api/overview", headers=HEADERS)).json()
        self.assertIsNone(overview["deep_scans"])
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertFalse(status["deep_scans_available"])
        self.assertIsNone(status["deep_scans_detail"])

    async def test_an_unreadable_journal_disables_deep_scans_only(self) -> None:
        (self.root / "deep-scans.json").write_text("{not json", encoding="utf-8")
        (self.root / "deep-scans.json").chmod(0o600)
        scanner, detail = open_scanner(
            self.root, lambda: DeviceInventory(self.cards), lambda: self.identities
        )
        self.assertIsNone(scanner)
        self.assertEqual(JOURNAL_UNREADABLE, detail)
        client = await self.client(scanner, deep_scans_detail=detail)
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertFalse(status["deep_scans_available"])
        self.assertEqual(JOURNAL_UNREADABLE, status["deep_scans_detail"])
        self.assertEqual("ready", self.worker.state)

    def tcp_worker(self) -> Worker:
        plan = replace(
            self.config.collection, mode="tcp", targets=(), ports=(), enabled=True
        )
        config = replace(self.config, collection=plan)
        return Worker(
            config,
            FixturePort(config),
            MemoryReceipts(),
            NetworkCollector(config.collector, plan),
            self.store,
        )

    async def test_an_unreadable_journal_still_lets_collection_run(self) -> None:
        worker = self.tcp_worker()
        journal = self.root / "deep-scans.json"
        journal.write_text("{not json", encoding="utf-8")
        journal.chmod(0o600)
        scanner, detail = compose_scanner(worker, self.root, True)
        self.assertIsNone(scanner)
        self.assertEqual(JOURNAL_UNREADABLE, detail)
        worker.port.events["schedule.due"] = [event()]  # type: ignore[attr-defined]
        await worker.poll()
        [receipt] = worker.receipts.all()
        self.assertEqual("done", receipt.phase)
        self.assertEqual("ready", worker.state)

    async def test_deep_scans_exist_only_for_tcp_collection_with_the_store(
        self,
    ) -> None:
        tcp = self.tcp_worker()
        self.assertEqual((None, None), compose_scanner(tcp, self.root, False))
        self.assertEqual((None, None), compose_scanner(self.worker, self.root, True))
        scanner, detail = compose_scanner(tcp, self.root, True)
        self.assertIsNone(detail)
        assert scanner is not None
        # Plain TCP collection has no identity source, so scanning is unavailable.
        self.assertFalse(scanner.available)
        self.assertIn("UniFi or Pi-hole", scanner.detail or "")

    async def test_scans_follow_the_collector_the_settings_install(self) -> None:
        worker = self.tcp_worker()
        scanner, _ = compose_scanner(worker, self.root, True)
        assert scanner is not None
        plan = worker.config.collection
        self.assertFalse(scanner.available)
        worker.collector = NetworkCollector(
            worker.config.collector, plan, identity_source=self.identities
        )
        self.assertTrue(scanner.available)
        worker.collector = NetworkCollector(worker.config.collector, plan)
        self.assertFalse(scanner.available)
        self.assertIn("UniFi or Pi-hole", scanner.detail or "")


async def _recover(callback: Callable[[], Awaitable[None]]) -> None:
    await callback()


if __name__ == "__main__":
    unittest.main()
