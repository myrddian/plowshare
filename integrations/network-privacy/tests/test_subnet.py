"""Whole-network collection keeps bounded batches and one durable scan identity."""

from __future__ import annotations

import errno
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from support import FixturePort, MemoryReceipts, configuration, event

from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.contracts import (
    CollectionPlan,
    DeviceIdentity,
    DnsObservation,
    DnsWindow,
    Evidence,
    device_labels,
    expand_targets,
)
from plowshare_privacy.journal import FileReceipts, Receipt
from plowshare_privacy.limits import MAX_EVIDENCE_BYTES, TOOL_REPLY_BYTES
from plowshare_privacy.monitor import MonitorChoice
from plowshare_privacy.pihole import DeviceData
from plowshare_privacy.tools import (
    EvidenceCall,
    EvidencePageResult,
    ScopeCall,
    ScopeResult,
    WorkerTools,
    decode_call,
)
from plowshare_privacy.worker import Worker


class BatchIdentitySource:
    def __init__(self) -> None:
        self.batches: list[tuple[str, ...]] = []

    async def read(self, targets: tuple[str, ...], observed_at: str) -> DeviceData:
        self.batches.append(targets)
        devices = []
        for address in targets:
            index = int(address.rsplit(".", 1)[1])
            mac = f"02:00:00:00:00:{index:02x}"
            devices.append(
                DeviceIdentity(
                    address,
                    "mac:" + mac,
                    None,
                    f"device-{index}",
                    mac,
                    "Example vendor",
                    "pihole-v6",
                    observed_at,
                    observed_at,
                )
            )
        return DeviceData(
            tuple(devices),
            tuple(DnsObservation(address, "example.invalid", 1) for address in targets),
            DnsWindow(
                "pihole-v6",
                observed_at,
                observed_at,
                len(targets) * 64,
                len(targets) * 64,
                True,
            ),
            (),
        )


class SubnetTest(unittest.IsolatedAsyncioTestCase):
    async def test_one_100_device_scan_collects_every_identity_and_probe_in_32_device_batches(
        self,
    ) -> None:
        targets = tuple(f"192.168.77.{index}" for index in range(1, 101))
        plan = CollectionPlan(
            "tcp", targets, (80, 443, 8008, 8009, 8091, 22, 53, 8080), 0.1, 8, None, 0
        )
        source = BatchIdentitySource()
        collector = NetworkCollector("fixture-collector", plan, source)
        scan_id, event_id = str(uuid4()), str(uuid4())
        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=ConnectionRefusedError(errno.ECONNREFUSED, "fixture refused"),
        ) as connect:
            evidence = await collector.collect(scan_id, event_id, None, None)
        self.assertEqual([len(batch) for batch in source.batches], [32, 32, 32, 4])
        self.assertEqual(connect.call_count, 800)
        self.assertEqual(evidence.scan_id, scan_id)
        self.assertEqual(evidence.source_event, event_id)
        self.assertEqual(len(evidence.snapshot.tcp), 800)
        self.assertEqual(len(evidence.snapshot.devices), 100)
        self.assertEqual(len(evidence.snapshot.dns), 100)
        self.assertEqual(
            evidence.snapshot.devices[-1].device_id, "mac:02:00:00:00:00:64"
        )
        self.assertEqual(
            evidence.snapshot.dns_window.queries_read
            if evidence.snapshot.dns_window
            else None,
            6400,
        )
        self.assertLessEqual(len(evidence.encode().encode()), MAX_EVIDENCE_BYTES)
        self.assertEqual(evidence, Evidence.decode(json.loads(evidence.encode())))
        with tempfile.TemporaryDirectory() as folder:
            config = replace(configuration(Path(folder)), collection=plan)
            receipts = MemoryReceipts()
            receipt = Receipt(
                scan_id,
                event_id,
                config.request_topic,
                evidence.finished_at,
                "done",
                evidence,
                str(uuid4()),
            )
            receipts.save(receipt)
            tools = WorkerTools(
                Worker(config, FixturePort(config), receipts, collector)
            )
            summary = await tools.execute(EvidenceCall(scan_id))
            self.assertIsInstance(summary, EvidencePageResult)
            assert isinstance(summary, EvidencePageResult)
            self.assertEqual(summary.counts.devices, 100)
            self.assertEqual(summary.mode, "tcp")
            self.assertEqual(summary.dns_window, evidence.snapshot.dns_window)
            identities: list[DeviceIdentity] = []
            offset = 0
            while True:
                result = await tools.execute(EvidenceCall(scan_id, "devices", offset))
                assert isinstance(result, EvidencePageResult)
                self.assertLessEqual(
                    len(json.dumps(asdict(result), separators=(",", ":")).encode()),
                    TOOL_REPLY_BYTES,
                )
                identities.extend(
                    row for row in result.records if isinstance(row, DeviceIdentity)
                )
                if result.next_offset is None:
                    break
                self.assertGreater(result.next_offset, offset)
                offset = result.next_offset
            self.assertEqual(tuple(identities), evidence.snapshot.devices)
            with FileReceipts(config) as journal:
                for _ in range(20):
                    identity, source_event = str(uuid4()), str(uuid4())
                    item = replace(
                        evidence, scan_id=identity, source_event=source_event
                    )
                    journal.save(
                        Receipt(
                            identity,
                            source_event,
                            config.request_topic,
                            item.finished_at,
                            "done",
                            item,
                            str(uuid4()),
                        )
                    )
                self.assertGreater(journal.path.stat().st_size, 1048576)
            with FileReceipts(config) as journal:
                self.assertEqual(len(journal.all()), 20)
                retained = journal.all()[-1].evidence
                assert retained is not None
                self.assertEqual(len(retained.snapshot.devices), 100)

    async def test_full_subnet_scope_and_unicode_metadata_are_paged_without_losing_targets(
        self,
    ) -> None:
        targets = expand_targets(["192.168.77.0/24"])
        self.assertEqual(len(targets), 254)
        self.assertEqual((targets[0], targets[-1]), ("192.168.77.1", "192.168.77.254"))
        self.assertEqual(
            MonitorChoice.decode(
                {"enabled": True, "targets": ["192.168.77.0/24"], "ports": [443]}
            ).targets,
            targets,
        )
        with tempfile.TemporaryDirectory() as folder:
            labels = device_labels(
                {address: "😀" * 128 for address in targets[:32]}, targets
            )
            plan = CollectionPlan(
                "tcp", targets, (443,), 0.1, 8, None, 0, labels=labels
            )
            config = replace(configuration(Path(folder)), collection=plan)
            collector = NetworkCollector(config.collector, plan)
            tools = WorkerTools(
                Worker(config, FixturePort(config), MemoryReceipts(), collector)
            )
            selected: list[str] = []
            offset = 0
            while True:
                result = await tools.execute(ScopeCall(offset))
                self.assertLessEqual(
                    len(json.dumps(asdict(result), separators=(",", ":")).encode()),
                    TOOL_REPLY_BYTES,
                )
                assert isinstance(result, ScopeResult)
                self.assertEqual(result.total_targets, 254)
                selected.extend(result.targets)
                if result.next_offset is None:
                    break
                self.assertGreater(result.next_offset, offset)
                offset = result.next_offset
            self.assertEqual(tuple(selected), targets)

    async def test_full_24_sweep_uploads_once_and_redelivery_does_not_repeat_batches(
        self,
    ) -> None:
        targets = expand_targets(["192.168.77.0/24"])
        plan = CollectionPlan("tcp", targets, tuple(range(1, 17)), 0.1, 32, None, 0)
        source = BatchIdentitySource()
        with tempfile.TemporaryDirectory() as folder:
            config = replace(configuration(Path(folder)), collection=plan)
            port = FixturePort(config)
            port.events["schedule.due"] = [event()]
            receipts = MemoryReceipts()
            worker = Worker(
                config, port, receipts, NetworkCollector(config.collector, plan, source)
            )
            with patch(
                "plowshare_privacy.collection.asyncio.open_connection",
                side_effect=ConnectionRefusedError(
                    errno.ECONNREFUSED, "fixture refused"
                ),
            ) as connect:
                await worker.poll()
                port.events["schedule.due"] = [event()]
                await worker.poll()
            self.assertEqual(connect.call_count, 254 * 16)
            self.assertEqual([len(batch) for batch in source.batches], [32] * 7 + [30])
            self.assertEqual(port.uploads, 1)
            self.assertEqual(len(port.published), 1)
            self.assertEqual(len(receipts.all()), 1)
            receipt = receipts.all()[0]
            self.assertEqual(receipt.phase, "done")
            assert receipt.evidence is not None
            self.assertEqual(len(receipt.evidence.snapshot.devices), 254)
            self.assertEqual(
                Evidence.decode(json.loads(receipt.evidence.encode())), receipt.evidence
            )
            original = worker.completion_text(receipt)
            self.assertNotIn("changes_count", json.loads(original))
            large = replace(
                receipt,
                evidence=replace(
                    receipt.evidence,
                    changes=tuple(
                        "changed " + str(index) + "x" * 256 for index in range(1000)
                    ),
                ),
            )
            preview = worker.completion_text(large)
            self.assertLessEqual(len(preview), 24000)
            self.assertEqual(json.loads(preview)["changes_count"], 1000)
            self.assertLess(len(json.loads(preview)["changes"]), 1000)
            assert large.evidence is not None
            self.assertEqual(len(large.evidence.changes), 1000)
            self.assertEqual(worker.completion_text(receipt), original)

    def test_scope_and_page_boundaries_refuse_expansion_overlap_and_malformed_cursors(
        self,
    ) -> None:
        for targets in (
            ["192.168.77.0/23"],
            ["8.8.8.0/24"],
            ["192.168.77.0/24", "192.168.77.1"],
            ["192.168.77.1/24"],
        ):
            with self.assertRaises(ValueError):
                expand_targets(targets)
        scan_id = str(uuid4())
        for values in (
            {"offset": True},
            {"limit": 0},
            {"section": "all"},
            {"section": "tcp", "limit": 33},
            {"section": "tcp", "offset": -1},
        ):
            with self.assertRaises(ValueError):
                decode_call("network.evidence", {"scan_id": scan_id, **values})
        with self.assertRaises(ValueError):
            ScopeCall(0, 0)
