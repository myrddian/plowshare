"""Named tool facade preserves harness identity and configured collection scope."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from types import MappingProxyType
from uuid import uuid4

from plowshare.tools import ToolAttention, ToolCall
from support import CountingCollector, FixturePort, MemoryReceipts, configuration

from plowshare_privacy.journal import FileReceipts, Publication, ScanOrigin
from plowshare_privacy.native_tools import DECLARATIONS, CataloguedProvider, registered
from plowshare_privacy.tools import WorkerTools
from plowshare_privacy.worker import Worker


class NativeToolsTest(unittest.IsolatedAsyncioTestCase):
    async def test_catalogue_intent_precedes_publication_and_restart_never_replays_uuid(
        self,
    ) -> None:
        class Provider:
            def __init__(self) -> None:
                self.publications: list[str] = []
                self.calls = 0
                self.fail = False

            async def publish_catalog(self, request_id: str) -> None:
                self.publications.append(request_id)
                rows = receipts.read_text().splitlines()
                self_record = json.loads(rows[-1])
                if self_record != {"request_id": request_id, "state": "publishing"}:
                    raise AssertionError("Publication intent was not durable first")
                if self.fail:
                    raise ToolAttention("Catalogue outcome is uncertain")

            async def poll(self) -> int:
                self.calls += 1
                return 0

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            receipts = directory / "tool-catalogue.jsonl"
            provider = Provider()
            facade = CataloguedProvider(provider, directory)
            await facade.poll()
            await facade.poll()
            self.assertEqual(len(provider.publications), 1)
            self.assertEqual(provider.calls, 2)
            provider.fail = True
            facade.next_renewal = 0
            with self.assertRaises(ToolAttention):
                await facade.poll()
            self.assertEqual(provider.calls, 2)
            lost_id = provider.publications[-1]
            provider.fail = False
            await CataloguedProvider(provider, directory).poll()
            self.assertNotEqual(provider.publications[-1], lost_id)
            self.assertEqual(len(provider.publications), 3)
            self.assertEqual(
                json.loads(receipts.read_text().splitlines()[-1])["state"], "published"
            )

    def test_scan_parent_is_durable_immutable_and_legacy_requests_remain_readable(
        self,
    ) -> None:
        identity = str(uuid4())
        value = Publication(
            identity,
            "2026-10-08T00:00:00Z",
            "pending",
            ScanOrigin("tool.scanner.network_scan.request", identity),
        )
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            with FileReceipts(config) as store:
                store.save_publication(value)
                with self.assertRaises(ValueError):
                    store.save_publication(replace(value, origin=None))
            with FileReceipts(config) as reopened:
                self.assertEqual(value, reopened.publications()[0])
                reopened.save_publication(replace(value, state="published"))
                with self.assertRaises(ValueError):
                    reopened.save_publication(value)
        legacy = Publication.decode(
            {
                "request_id": identity,
                "occurred_at": value.occurred_at,
                "state": "pending",
            }
        )
        self.assertIsNone(legacy.origin)

    async def test_provider_attention_keeps_the_dashboard_state_explicit(self) -> None:
        stop = asyncio.Event()

        class UncertainProvider:
            async def poll(self) -> int:
                stop.set()
                raise ToolAttention("Retained publication requires reconciliation")

        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            worker = Worker(
                config, FixturePort(config), MemoryReceipts(), CountingCollector(config)
            )
            await worker.run(stop, UncertainProvider())
            self.assertEqual("attention_required", worker.state)
            self.assertIn("ToolAttention", worker.detail)

    async def test_scan_uses_invocation_uuid_and_does_not_accept_model_scope(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            port = FixturePort(config)
            collector = CountingCollector(config)
            worker = Worker(config, port, MemoryReceipts(), collector)
            identity = str(uuid4())
            call = ToolCall(
                identity,
                config.project,
                "scanner",
                "network_scan",
                "caller",
                "run",
                "call",
                "2099-01-01T00:00:00Z",
                MappingProxyType({}),
            )
            tool = next(
                tool
                for tool in registered(WorkerTools(worker))
                if tool.declaration.name == "network_scan"
            )
            result = await tool.handler(call)
            self.assertEqual("COMPLETED", result.state)
            self.assertIn(identity, result.text)
            self.assertEqual(0, collector.count)
            saved = worker.receipts.publications()[0]
            self.assertIsNotNone(saved.origin)
            request = worker.manual_request(saved)
            self.assertEqual("tool.scanner.network_scan.request", request.parent_topic)
            self.assertEqual(identity, request.parent_event_id)
            with self.assertRaises(ValueError):
                await worker.request_scan(identity)
            with self.assertRaises(ValueError):
                tool.declaration.validate({"targets": "192.0.2.1"})
            with self.assertRaises(ValueError):
                tool.declaration.validate({"request_id": str(uuid4())})
            self.assertEqual(6, len(DECLARATIONS))
            self.assertEqual(
                {
                    "network_scope",
                    "network_scan",
                    "network_scan_status",
                    "network_scan_list",
                    "network_evidence",
                    "network_destinations",
                },
                {tool.name for tool in DECLARATIONS},
            )
