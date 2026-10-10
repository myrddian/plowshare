"""Discovery boundaries and deferred configuration without scanning a live network."""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from ipaddress import IPv4Network
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from support import (
    EXAMPLES,
    CountingCollector,
    FixturePort,
    MemoryReceipts,
    configuration,
)

from plowshare_privacy.bootstrap import configure
from plowshare_privacy.collection import NetworkCollector, scope_fingerprint
from plowshare_privacy.contracts import Configuration
from plowshare_privacy.discovery import DiscoveryPlan, discover
from plowshare_privacy.ports import PrivacyPort
from plowshare_privacy.tools import ScopeCall, ScopeResult, WorkerTools
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker


class DiscoveryTest(unittest.IsolatedAsyncioTestCase):
    def test_rejects_public_or_large_scope_and_bad_probe_limits(self) -> None:
        for network, ports, timeout, concurrency in (
            ("8.8.8.0/24", (80,), 0.5, 4),
            ("192.168.0.0/16", (80,), 0.5, 4),
            ("127.0.0.0/24", (80,), 0.5, 4),
            ("192.168.1.0/24", (), 0.5, 4),
            ("192.168.1.0/24", (80, 80), 0.5, 4),
            ("192.168.1.0/24", (0,), 0.5, 4),
            ("192.168.1.0/24", (80,), float("nan"), 4),
            ("192.168.1.0/24", (80,), 0.5, 0),
        ):
            with (
                self.subTest(network=network, ports=ports),
                self.assertRaises(ValueError),
            ):
                DiscoveryPlan(IPv4Network(network), ports, timeout, concurrency)

    async def test_responses_and_unknowns_are_distinguished_without_payloads(
        self,
    ) -> None:
        writer = MagicMock()
        writer.wait_closed = AsyncMock()
        calls: list[tuple[str, int]] = []

        async def connection(
            address: str, port: int
        ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
            calls.append((address, port))
            if address.endswith(".1") and port == 80:
                return MagicMock(), writer
            if address.endswith(".1"):
                raise OSError(errno.ECONNREFUSED, "fixture refusal")
            raise TimeoutError("fixture timeout")

        with patch(
            "plowshare_privacy.discovery.asyncio.open_connection",
            side_effect=connection,
        ):
            report = await discover(
                DiscoveryPlan(IPv4Network("192.168.40.0/30"), (80, 443), 0.1, 2)
            )
        self.assertEqual(
            set(calls),
            {
                ("192.168.40.1", 80),
                ("192.168.40.1", 443),
                ("192.168.40.2", 80),
                ("192.168.40.2", 443),
            },
        )
        self.assertEqual(report.addresses_checked, 2)
        self.assertEqual(report.unanswered_probes, 2)
        self.assertEqual(len(report.devices), 1)
        self.assertEqual(report.devices[0].open_ports, (80,))
        self.assertEqual(report.devices[0].refused_ports, (443,))
        writer.write.assert_not_called()
        writer.close.assert_called_once()

    async def test_permission_failure_is_not_reported_as_no_devices(self) -> None:
        with patch(
            "plowshare_privacy.discovery.asyncio.open_connection",
            side_effect=PermissionError(errno.EPERM, "fixture permission failure"),
        ):
            with self.assertRaises(PermissionError):
                await discover(
                    DiscoveryPlan(IPv4Network("192.168.40.0/30"), (80,), 0.1, 1)
                )

    async def test_concurrency_is_bounded_and_cancellation_cleans_up(self) -> None:
        entered = asyncio.Event()
        active = 0
        maximum = 0

        async def blocked(
            address: str, port: int
        ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
            nonlocal active, maximum
            active += 1
            maximum = max(maximum, active)
            if active == 2:
                entered.set()
            try:
                await asyncio.Event().wait()
                raise AssertionError("Never completes")
            finally:
                active -= 1

        with patch(
            "plowshare_privacy.discovery.asyncio.open_connection", side_effect=blocked
        ):
            task = asyncio.create_task(
                discover(DiscoveryPlan(IPv4Network("192.168.40.0/29"), (80, 443), 2, 2))
            )
            await asyncio.wait_for(entered.wait(), 1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        self.assertEqual(active, 0)
        self.assertEqual(maximum, 2)


class DeferredCollectionTest(unittest.IsolatedAsyncioTestCase):
    def test_configuration_requires_explicit_false_before_empty_scope(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            row = json.loads((EXAMPLES / "config.json").read_text())
            row["collection"]["targets"] = []
            row["collection"]["ports"] = []
            for enabled in (None, True, "false", False):
                if enabled is None:
                    row["collection"].pop("enabled", None)
                else:
                    row["collection"]["enabled"] = enabled
                path.write_text(json.dumps(row))
                if enabled is False:
                    self.assertFalse(Configuration.read(path).collection.enabled)
                else:
                    with self.assertRaises(ValueError):
                        Configuration.read(path)

    async def test_disabled_collector_and_worker_do_not_scan_consume_or_publish(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = configuration(Path(directory))
            plan = replace(config.collection, enabled=False)
            config = replace(config, collection=plan)
            port = AsyncMock(spec=PrivacyPort)
            receipts = MemoryReceipts()
            worker = Worker(config, port, receipts, CountingCollector(config))
            await worker.poll()
            self.assertEqual(worker.state, "configuration_required")
            with self.assertRaises(ValueError):
                await worker.request_scan(str(uuid4()))
            with patch(
                "plowshare_privacy.collection.asyncio.open_connection"
            ) as connection:
                with self.assertRaises(ValueError):
                    await NetworkCollector(config.collector, plan).collect(
                        str(uuid4()), "fixture", None, None
                    )
                connection.assert_not_called()
            port.consume.assert_not_called()
            port.publish.assert_not_called()
            self.assertEqual(receipts.publications(), ())
            scope = await WorkerTools(worker).execute(ScopeCall())
            self.assertIsInstance(scope, ScopeResult)
            assert isinstance(scope, ScopeResult)
            self.assertFalse(scope.enabled)

    async def test_disabled_dashboard_exposes_configuration_state_and_refuses_scan(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = configuration(Path(directory))
            config = replace(
                config, collection=replace(config.collection, enabled=False)
            )
            port = FixturePort(config)
            worker = Worker(config, port, MemoryReceipts(), CountingCollector(config))
            async with TestClient(
                TestServer(application(worker, "fixture-web-token-1234567890"))
            ) as client:
                headers = {"Authorization": "Bearer fixture-web-token-1234567890"}
                response = await client.get("/api/status", headers=headers)
                self.assertIn('"collection_enabled": false', await response.text())
                response = await client.post(
                    "/api/scans", headers=headers, json={"request_id": str(uuid4())}
                )
                self.assertEqual(response.status, 400)
                self.assertEqual(port.published, [])

    def test_enabled_fingerprint_preserves_existing_journal_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            plan = configuration(Path(directory)).collection
            legacy = asdict(plan)
            legacy.pop("enabled")
            legacy.pop("labels")
            legacy.pop("pihole")
            legacy["observations_file"] = str(plan.observations_file)
            expected = hashlib.sha256(
                json.dumps(legacy, sort_keys=True).encode()
            ).hexdigest()
            self.assertEqual(scope_fingerprint(plan), expected)
            self.assertNotEqual(
                scope_fingerprint(replace(plan, enabled=False)), expected
            )

    def test_configure_can_defer_targets_without_any_scan(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "private"
            inputs = [
                "https://fixture.invalid",
                "home-network",
                "",
                "1",
                "4",
                "worker",
                "operator",
                "installer",
                "reasoning",
                "30",
                "network-privacy-watch",
            ]
            with patch("builtins.input", side_effect=inputs):
                configure(root, EXAMPLES.parent / "network-privacy-watch")
            config = Configuration.read(root / "collector.json")
            self.assertFalse(config.collection.enabled)
            self.assertEqual(config.collection.targets, ())
            self.assertTrue((root / "deployment/application/plowshare.json").is_file())
