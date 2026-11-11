"""Named tool facade preserves harness identity and configured collection scope."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from types import MappingProxyType
from unittest.mock import patch
from uuid import uuid4

from plowshare.tool_schemas import ToolValue
from plowshare.tools import ToolAttention, ToolCall
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_device_dossier import activity, card, identity, volume

from plowshare_privacy.device_dossier import build_dossier, encode_dossier
from plowshare_privacy.device_facts import DeviceFacts
from plowshare_privacy.device_inventory import DeviceCard, DeviceInventory
from plowshare_privacy.journal import FileReceipts, Publication, ScanOrigin
from plowshare_privacy.limits import TOOL_REPLY_BYTES
from plowshare_privacy.native_tools import DECLARATIONS, CataloguedProvider, registered
from plowshare_privacy.tools import (
    DeviceCall,
    DevicesCall,
    DevicesResult,
    WorkerTools,
    decode_call,
)
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
            self.assertEqual(8, len(DECLARATIONS))
            self.assertEqual(
                {
                    "network_scope",
                    "network_scan",
                    "network_scan_status",
                    "network_scan_list",
                    "network_evidence",
                    "network_destinations",
                    "network_devices",
                    "network_device",
                },
                {tool.name for tool in DECLARATIONS},
            )


def mac_card(index: int, *, name: str = "Device", dns_rows: int = 0) -> DeviceCard:
    mac = f"02:00:00:00:01:{index:02x}"
    address = f"10.1.3.{index + 1}"
    who = replace(identity(mac=mac), address=address)
    value = card(
        who=who,
        dns=activity(
            [("oztam.com.au", 2, 0)]
            + [(f"h{row:03d}-{'y' * 60}.example.net", 5, 0) for row in range(dns_rows)]
        ),
        traffic=volume(10, 20),
    )
    return replace(value, address=address, name=f"{name} {index}")


def address_only(index: int) -> DeviceCard:
    value = card(who=identity(mac=None))
    return replace(value, address=f"10.1.4.{index + 1}")


class DeviceToolsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.config = configuration(Path(temporary.name))
        self.worker = Worker(
            self.config,
            FixturePort(self.config),
            MemoryReceipts(),
            CountingCollector(self.config),
        )
        self.inventory = DeviceInventory()
        self.reads = 0

    def current(self) -> DeviceInventory:
        self.reads += 1
        return self.inventory

    def tools(self) -> WorkerTools:
        return WorkerTools(self.worker, inventory=self.current)

    async def invoke(
        self, tool: str, arguments: dict[str, ToolValue]
    ) -> tuple[str, str]:
        handler = next(
            item for item in registered(self.tools()) if item.declaration.name == tool
        )
        result = await handler.handler(
            ToolCall(
                str(uuid4()),
                self.config.project,
                "scanner",
                tool,
                "caller",
                "run",
                "call",
                "2099-01-01T00:00:00Z",
                MappingProxyType(arguments),
            )
        )
        return result.state, result.text

    async def test_device_list_and_dossier_through_the_native_facade(self) -> None:
        listed, hidden = mac_card(1, name="Lounge TV"), address_only(2)
        self.inventory = DeviceInventory((listed, hidden))
        state, text = await self.invoke("network_devices", {})
        self.assertEqual(state, "COMPLETED")
        page = json.loads(text)
        self.assertEqual(
            page["devices"],
            [
                {
                    "device_id": "mac:02:00:00:00:01:01",
                    "name": "Lounge TV 1",
                    "address": listed.address,
                    "signals": ["measurement_dns"],
                }
            ],
        )
        self.assertEqual((page["total"], page["unavailable"]), (1, 1))
        self.assertIsNone(page["next_offset"])
        state, text = await self.invoke(
            "network_device", {"device_id": "mac:02:00:00:00:01:01"}
        )
        self.assertEqual(state, "COMPLETED")
        self.assertEqual(text, encode_dossier(build_dossier(listed)))
        # One inventory projection per call; the dossier reuses it.
        self.assertEqual(self.reads, 2)

    async def test_device_dossier_carries_the_workers_retained_facts(self) -> None:
        from test_device_dossier import facts

        listed = mac_card(1)
        self.inventory = DeviceInventory((listed,))
        retained = replace(facts(), device_id="mac:02:00:00:00:01:01")
        asked: list[str] = []

        def lookup(device_id: str) -> DeviceFacts | None:
            asked.append(device_id)
            return retained

        self.worker.device_facts = lookup  # type: ignore[method-assign]
        state, text = await self.invoke(
            "network_device", {"device_id": "mac:02:00:00:00:01:01"}
        )
        self.assertEqual(state, "COMPLETED")
        self.assertEqual(asked, ["mac:02:00:00:00:01:01"])
        self.assertEqual(text, encode_dossier(build_dossier(listed, facts=retained)))
        self.assertEqual(
            json.loads(text)["supplemental"]["descriptions"][0]["model_name"],
            "BRAVIA KD-55X85K",
        )

    async def test_unknown_or_unmonitored_device_ids_are_rejected(self) -> None:
        self.inventory = DeviceInventory((mac_card(1), address_only(2)))
        state, text = await self.invoke(
            "network_device", {"device_id": "mac:02:00:00:00:09:09"}
        )
        self.assertEqual(state, "REJECTED")
        self.assertNotIn("02:00:00:00:09:09", text)
        # The same MAC at two addresses is ambiguous, so neither is selected.
        twin = replace(mac_card(1), address="10.1.3.99")
        self.inventory = DeviceInventory((mac_card(1), twin))
        state, _ = await self.invoke(
            "network_device", {"device_id": "mac:02:00:00:00:01:01"}
        )
        self.assertEqual(state, "REJECTED")
        page = await self.tools().execute(DevicesCall())
        assert isinstance(page, DevicesResult)
        self.assertEqual((page.total, page.unavailable), (0, 2))
        for value in ("ip:10.1.4.3", "10.1.4.3", "mac:02:00:00:00:01:0A", 7):
            with self.assertRaises(ValueError):
                decode_call("network.device", {"device_id": value})

    def test_extra_and_missing_parameters_are_rejected(self) -> None:
        for name, arguments in (
            ("network.device", {}),
            ("network.device", {"device_id": "mac:02:00:00:00:01:01", "address": "x"}),
            ("network.devices", {"targets": ["192.0.2.1"]}),
            ("network.devices", {"device_id": "mac:02:00:00:00:01:01"}),
            ("network.devices", {"limit": 33}),
            ("network.devices", {"limit": 0}),
            ("network.devices", {"offset": -1}),
        ):
            with self.assertRaises(ValueError, msg=f"{name} {arguments}"):
                decode_call(name, arguments)
        declarations = {item.name: item for item in DECLARATIONS}
        with self.assertRaises(ValueError):
            declarations["network_device"].validate(
                {"device_id": "mac:02:00:00:00:01:01", "address": "10.0.0.1"}
            )
        with self.assertRaises(ValueError):
            declarations["network_devices"].validate({"targets": "10.0.0.1"})
        self.assertEqual(
            decode_call("network.devices", {"offset": 2, "limit": 3}),
            DevicesCall(2, 3),
        )
        self.assertEqual(
            decode_call("network.device", {"device_id": "mac:02:00:00:00:01:01"}),
            DeviceCall("mac:02:00:00:00:01:01"),
        )

    async def test_device_pages_follow_next_offset_and_shrink_to_the_budget(
        self,
    ) -> None:
        self.inventory = DeviceInventory(tuple(mac_card(i) for i in range(40)))
        tools = self.tools()
        first = await tools.execute(DevicesCall())
        assert isinstance(first, DevicesResult)
        self.assertEqual((len(first.devices), first.next_offset), (32, 32))
        second = await tools.execute(DevicesCall(32))
        assert isinstance(second, DevicesResult)
        self.assertEqual((len(second.devices), second.next_offset), (8, None))
        with self.assertRaises(ValueError):
            await tools.execute(DevicesCall(41))
        with patch("plowshare_privacy.tools.TOOL_REPLY_BYTES", 1500):
            seen: list[str] = []
            offset: int | None = 0
            while offset is not None:
                page = await tools.execute(DevicesCall(offset))
                assert isinstance(page, DevicesResult)
                self.assertLess(len(page.devices), 32)
                self.assertLessEqual(
                    len(json.dumps(asdict(page), separators=(",", ":")).encode()),
                    1500,
                )
                seen.extend(item.device_id for item in page.devices)
                offset = page.next_offset
        self.assertEqual(seen, [f"mac:02:00:00:00:01:{i:02x}" for i in range(40)])

    async def test_large_dossier_fits_the_reply_budget_with_omissions(self) -> None:
        self.inventory = DeviceInventory((mac_card(1, dns_rows=400),))
        state, text = await self.invoke(
            "network_device", {"device_id": "mac:02:00:00:00:01:01"}
        )
        self.assertEqual(state, "COMPLETED")
        self.assertLessEqual(len(text.encode()), TOOL_REPLY_BYTES)
        dossier = json.loads(text)
        unclassified = next(
            group
            for group in dossier["dns"]["groups"]
            if group["kind"] == "unclassified"
        )
        self.assertEqual(unclassified["distinct"], 400)
        self.assertEqual(
            unclassified["distinct"],
            len(unclassified["destinations"]) + unclassified["omitted"],
        )
        with patch("plowshare_privacy.tools.TOOL_REPLY_BYTES", 3000):
            state, text = await self.invoke(
                "network_device", {"device_id": "mac:02:00:00:00:01:01"}
            )
        self.assertEqual(state, "COMPLETED")
        self.assertLessEqual(len(text.encode()), 3000)
        trimmed = json.loads(text)
        self.assertGreater(trimmed["trimmed_destinations"], 0)
        self.assertEqual(
            [group["queries"] for group in trimmed["dns"]["groups"]],
            [group["queries"] for group in dossier["dns"]["groups"]],
        )

    def test_worker_inventory_reuses_one_projection_until_inputs_change(self) -> None:
        first = self.worker.device_inventory()
        self.assertIs(first, self.worker.device_inventory())
        self.worker.config = replace(
            self.config,
            collection=replace(self.config.collection, targets=("10.1.2.10",)),
        )
        self.assertIsNot(first, self.worker.device_inventory())
