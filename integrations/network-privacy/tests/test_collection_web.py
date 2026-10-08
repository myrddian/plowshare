"""Actual TCP probes and Python HTTP behavior on isolated ephemeral test listeners."""

from __future__ import annotations

import argparse
import asyncio
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from support import (
    EXAMPLES,
    CountingCollector,
    FixturePort,
    MemoryReceipts,
    configuration,
)

from plowshare_privacy.cli import execute
from plowshare_privacy.collection import NetworkCollector, changes
from plowshare_privacy.contracts import (
    CollectionPlan,
    DnsObservation,
    Evidence,
    Snapshot,
    object_fields,
    parse_json,
    utc_now,
)
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

WEB_TOKEN = "fixture-web-token-1234567890"


class WebTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = configuration(Path(self.temporary.name))
        self.port = FixturePort(self.config)
        self.receipts = MemoryReceipts()
        self.collector = CountingCollector(self.config)
        self.worker = Worker(self.config, self.port, self.receipts, self.collector)
        self.client = TestClient(TestServer(application(self.worker, WEB_TOKEN)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.headers = {"Authorization": "Bearer " + WEB_TOKEN}

    async def test_public_ui_contains_no_evidence_and_private_api_requires_bearer(
        self,
    ) -> None:
        response = await self.client.get("/")
        self.assertEqual(response.status, 200)
        self.assertIn("Know what changed", await response.text())
        self.assertNotIn(WEB_TOKEN, await response.text())
        self.assertIn("script-src 'self'", response.headers["Content-Security-Policy"])
        for route in ("/api/status", "/api/scans", "/api/reports", "/api/tools"):
            response = await self.client.get(route)
            self.assertEqual(response.status, 401)
        response = await self.client.post(
            "/api/scans", json={"request_id": str(uuid4())}
        )
        self.assertEqual(response.status, 401)
        self.assertEqual(self.port.published, [])

    async def test_named_tools_request_and_read_same_relay_collection(self) -> None:
        response = await self.client.get("/api/tools", headers=self.headers)
        self.assertEqual(response.status, 200)
        self.assertIn('"additionalProperties": false', await response.text())
        identity = str(uuid4())

        async def call(name: str, arguments: dict[str, str]) -> str:
            response = await self.client.post(
                "/api/tools/" + name, headers=self.headers, json=arguments
            )
            self.assertIn(response.status, (200, 202))
            return await response.text()

        self.assertIn('"mode": "fixture"', await call("network.scope", {}))
        await call("network.scan", {"request_id": identity})
        await call("network.scan", {"request_id": identity})
        self.assertEqual(len(self.port.published), 1)
        self.assertEqual(self.collector.count, 0)
        status = await call("network.scan_status", {"request_id": identity})
        self.assertIn('"collection": null', status)
        await self.worker.poll()
        scan = self.receipts.all()[0]
        self.assertIn(
            '"phase": "done"',
            await call("network.scan_status", {"request_id": identity}),
        )
        self.assertIn(scan.scan_id, await call("network.scan_list", {}))
        self.assertIn(
            scan.revision or "missing",
            await call("network.evidence", {"scan_id": scan.scan_id}),
        )
        destinations = await call("network.destinations", {"scan_id": scan.scan_id})
        self.assertIn("streaming.example", destinations)
        self.assertIn('"mode": "fixture"', destinations)

    async def test_tools_refuse_unknown_capabilities_scope_and_identity(self) -> None:
        for name, arguments in (
            ("network.shell", {}),
            ("network.scope", {"targets": ["192.0.2.10"]}),
            ("network.scan", {"request_id": str(uuid4()), "ports": [443]}),
            ("network.scan_status", {"request_id": str(uuid4())}),
            ("network.evidence", {"scan_id": str(uuid4())}),
        ):
            response = await self.client.post(
                "/api/tools/" + name, headers=self.headers, json=arguments
            )
            self.assertEqual(response.status, 400)
        response = await self.client.post(
            "/api/tools/network.scan", json={"request_id": str(uuid4())}
        )
        self.assertEqual(response.status, 401)
        self.assertEqual(self.collector.count, 0)
        self.assertEqual(self.port.published, [])

    async def test_authenticated_scan_goes_through_relay_then_returns_evidence(
        self,
    ) -> None:
        request_id = str(uuid4())
        response = await self.client.post(
            "/api/scans", headers=self.headers, json={"request_id": request_id}
        )
        self.assertEqual(response.status, 202)
        self.assertEqual(self.collector.count, 0)
        await self.worker.poll()
        response = await self.client.get("/api/scans", headers=self.headers)
        self.assertEqual(response.status, 200)
        receipt = self.receipts.all()[0]
        response = await self.client.get(
            "/api/scans/" + receipt.scan_id, headers=self.headers
        )
        self.assertEqual(response.status, 200)
        content = await response.text()
        self.assertIn("fixture", content)
        self.assertIn(receipt.revision or "missing", content)

    async def test_extra_fields_wrong_media_and_bad_identity_have_no_effect(
        self,
    ) -> None:
        for payload in (
            {"request_id": "invalid"},
            {"request_id": str(uuid4()), "targets": ["192.0.2.1"]},
        ):
            response = await self.client.post(
                "/api/scans", headers=self.headers, json=payload
            )
            self.assertEqual(response.status, 400)
        response = await self.client.post(
            "/api/scans", headers=self.headers, data="not JSON"
        )
        self.assertEqual(response.status, 415)
        self.assertEqual(self.port.published, [])
        response = await self.client.get(
            "/api/scans/" + str(uuid4()), headers=self.headers
        )
        self.assertEqual(response.status, 404)


class CollectionTest(unittest.IsolatedAsyncioTestCase):
    async def test_cli_scan_probes_without_server_credentials_or_journal(self) -> None:
        connected = asyncio.Event()

        async def accept(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            self.assertEqual(await reader.read(), b"")
            writer.close()
            await writer.wait_closed()
            connected.set()

        server = await asyncio.start_server(accept, "127.0.0.1", 0)
        try:
            with tempfile.TemporaryDirectory() as folder:
                directory = Path(folder)
                value = object_fields(
                    parse_json((EXAMPLES / "config.json").read_bytes()),
                    {
                        "origin",
                        "project",
                        "collector",
                        "tokenEnvironment",
                        "webTokenEnvironment",
                        "stateDirectory",
                        "requestTopic",
                        "resultTopic",
                        "group",
                        "schedule",
                        "collection",
                    },
                )
                value["stateDirectory"] = str(directory / "state")
                value["collection"] = {
                    "mode": "tcp",
                    "targets": ["127.0.0.1"],
                    "ports": [server.sockets[0].getsockname()[1]],
                    "timeoutSeconds": 1,
                    "concurrency": 1,
                }
                path = directory / "config.json"
                path.write_text(json.dumps(value))
                output = io.StringIO()
                with (
                    patch("plowshare_privacy.cli.Client.connect") as connect,
                    redirect_stdout(output),
                ):
                    await execute(argparse.Namespace(config=str(path), command="scan"))
                    connect.assert_not_called()
                result = object_fields(
                    parse_json(output.getvalue()), {"retained", "evidence"}
                )
                self.assertIs(result["retained"], False)
                evidence = Evidence.decode(result["evidence"])
                self.assertEqual(evidence.snapshot.tcp[0].status, "open")
                self.assertFalse((directory / "state").exists())
                await asyncio.wait_for(connected.wait(), 1)
        finally:
            server.close()
            await server.wait_closed()

    async def test_actual_tcp_probe_observes_open_listener_without_sending_payload(
        self,
    ) -> None:
        connected = asyncio.Event()

        async def accept(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            self.assertEqual(await reader.read(), b"")
            writer.close()
            await writer.wait_closed()
            connected.set()

        server = await asyncio.start_server(accept, "127.0.0.1", 0)
        try:
            address = server.sockets[0].getsockname()
            collector = NetworkCollector(
                "test-network",
                CollectionPlan("tcp", ("127.0.0.1",), (address[1],), 1, 1, None, 0),
            )
            evidence = await collector.collect(
                str(uuid4()), "fixture-event", None, None
            )
            await asyncio.wait_for(connected.wait(), 1)
            self.assertEqual(evidence.snapshot.tcp[0].status, "open")
            self.assertTrue(
                any(issue.startswith("dns_unavailable") for issue in evidence.issues)
            )
        finally:
            server.close()
            await server.wait_closed()

    async def test_missing_dns_does_not_become_a_disappearance_or_all_clear(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = configuration(Path(directory))
            collector = CountingCollector(config)
            previous = await collector.collect(
                str(uuid4()), "fixture-first", None, None
            )
            current = Snapshot(utc_now(), (), (DnsObservation("tv", "new.example", 1),))
            missing = changes(
                previous, current, previous.scope, ("dns_stale: fixture",)
            )
            self.assertEqual(len(missing), 1)
            self.assertIn("coverage changed", missing[0])
            other = replace(previous, scope="different")
            self.assertIn("Baseline", changes(other, current, previous.scope, ())[0])
