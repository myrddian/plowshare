"""Meaningful delivery, recovery, scope and invalid-input behavior through typed ports."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from plowshare import Delivery, TransportError
from plowshare.contracts import (
    InformationAdmissionDto,
    RelayBatchDto,
    RelayPublishRequest,
    RelayPublishResultDto,
)
from support import (
    EXAMPLES,
    CountingCollector,
    FixturePort,
    MemoryReceipts,
    configuration,
    event,
)

from plowshare_privacy.contracts import (
    Configuration,
    Evidence,
    ScanRequest,
    Snapshot,
    parse_json,
)
from plowshare_privacy.journal import FileReceipts
from plowshare_privacy.worker import ReconciliationRequired, Worker


class WorkflowTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = configuration(Path(self.temporary.name))
        self.port = FixturePort(self.config)
        self.receipts = MemoryReceipts()
        self.collector = CountingCollector(self.config)
        self.worker = Worker(self.config, self.port, self.receipts, self.collector)

    async def test_schedule_collects_retains_and_publishes_then_compares_next_scan(
        self,
    ) -> None:
        self.port.events["schedule.due"] = [event()]
        await self.worker.poll()
        first = self.receipts.all()[0]
        self.assertEqual(first.phase, "done")
        self.assertIsNotNone(first.revision)
        self.assertEqual(self.port.published[0].parent_event_id, first.source_event)
        evidence = first.evidence
        self.assertIsNotNone(evidence)
        if evidence is None:
            self.fail("Missing retained evidence")
        self.assertEqual(evidence.mode, "fixture")
        self.assertEqual(Evidence.decode(parse_json(evidence.encode())), evidence)
        self.config.collection.observations_file.write_bytes(
            (EXAMPLES / "observations-after.json").read_bytes()
        ) if self.config.collection.observations_file else self.fail("Missing fixture")
        self.port.events["schedule.due"] = [event("schedule:next")]
        await self.worker.poll()
        second = self.receipts.all()[1]
        if second.evidence is None:
            self.fail("Missing comparison")
        self.assertEqual(second.evidence.previous_revision, first.revision)
        self.assertEqual(len(second.evidence.changes), 2)
        self.assertIn("new-destination.example", second.evidence.changes[1])

    async def test_redelivery_does_not_scan_or_upload_again(self) -> None:
        self.port.events["schedule.due"] = [event()]
        await self.worker.poll()
        self.port.events["schedule.due"] = [event()]
        await self.worker.poll()
        self.assertEqual(self.collector.count, 1)
        self.assertEqual(self.port.uploads, 1)
        self.assertEqual(len(self.port.published), 1)

    async def test_manual_request_uses_relay_and_same_collection_pipeline(self) -> None:
        identity = str(uuid4())
        await self.worker.request_scan(identity)
        await self.worker.request_scan(identity)
        self.assertEqual(len(self.port.published), 1)
        self.assertEqual(self.collector.count, 0)
        await self.worker.poll()
        self.assertEqual(self.collector.count, 1)
        self.assertEqual(self.receipts.all()[0].source_topic, self.config.request_topic)

    async def test_unrelated_schedule_does_not_launch_collection(self) -> None:
        self.port.events["schedule.due"] = [event(schedule="some-other-schedule")]
        await self.worker.poll()
        self.assertEqual(self.collector.count, 0)
        self.assertEqual(len(self.port.acknowledgements), 1)

    async def test_gap_is_not_acknowledged_or_treated_as_empty(self) -> None:
        original = self.port.consume

        async def gap(topic: str, consumer: str) -> RelayBatchDto:
            return replace(
                await original(topic, consumer), status="GAP", expired_through="2"
            )

        with patch.object(self.port, "consume", gap):
            with self.assertRaises(ReconciliationRequired):
                await self.worker.poll()
        self.assertEqual(self.port.acknowledgements, [])

    async def test_unknown_upload_is_read_reconciled_without_resubmission(self) -> None:
        original = self.port.upload

        async def lost(evidence: Evidence, request_id: str) -> InformationAdmissionDto:
            await original(evidence, request_id)
            raise TransportError(Delivery.UNKNOWN, "Fixture reply lost")

        upload_patch = patch.object(self.port, "upload", lost)
        upload_patch.start()
        self.addCleanup(upload_patch.stop)
        self.port.events["schedule.due"] = [event()]
        with self.assertRaises(TransportError):
            await self.worker.poll()
        with self.assertRaises(ReconciliationRequired):
            await self.worker.poll()
        self.assertEqual(self.port.uploads, 1)
        await self.worker.reconcile()
        upload_patch.stop()
        await self.worker.poll()
        self.assertEqual(self.port.uploads, 1)
        self.assertEqual(self.receipts.all()[0].phase, "done")

    async def test_unknown_completion_is_read_reconciled_without_republishing(
        self,
    ) -> None:
        original = self.port.publish

        async def lost(request: RelayPublishRequest) -> RelayPublishResultDto:
            await original(request)
            raise TransportError(Delivery.UNKNOWN, "Fixture reply lost")

        publish_patch = patch.object(self.port, "publish", lost)
        publish_patch.start()
        self.addCleanup(publish_patch.stop)
        self.port.events["schedule.due"] = [event()]
        with self.assertRaises(TransportError):
            await self.worker.poll()
        self.assertEqual(self.receipts.all()[0].phase, "publishing")
        await self.worker.reconcile()
        await self.worker.poll()
        self.assertEqual(len(self.port.published), 1)
        self.assertEqual(self.collector.count, 1)

    async def test_absence_cannot_settle_unknown_publication(self) -> None:
        async def lost(request: RelayPublishRequest) -> RelayPublishResultDto:
            raise TransportError(Delivery.UNKNOWN, "Fixture reply lost")

        publish_patch = patch.object(self.port, "publish", lost)
        publish_patch.start()
        self.addCleanup(publish_patch.stop)
        self.port.events["schedule.due"] = [event()]
        with self.assertRaises(TransportError):
            await self.worker.poll()
        with self.assertRaises(ReconciliationRequired):
            await self.worker.reconcile()
        self.assertEqual(self.receipts.all()[0].phase, "publishing")

    async def test_intake_copy_survives_lost_ack_and_process_restart(self) -> None:
        async def lost_ack(batch: RelayBatchDto) -> None:
            raise TransportError(Delivery.UNKNOWN, "Fixture ack reply lost")

        ack_patch = patch.object(self.port, "acknowledge", lost_ack)
        ack_patch.start()
        self.addCleanup(ack_patch.stop)
        self.port.events["schedule.due"] = [event()]
        with FileReceipts(self.config) as receipts:
            worker = Worker(self.config, self.port, receipts, self.collector)
            with self.assertRaises(TransportError):
                await worker.poll()
            self.assertEqual(receipts.all()[0].phase, "queued")
        with FileReceipts(self.config) as reopened:
            self.assertEqual(reopened.all()[0].phase, "queued")
        self.assertEqual(self.collector.count, 0)

    async def test_foreign_journal_and_second_owner_are_refused(self) -> None:
        with FileReceipts(self.config) as receipts:
            self.port.events["schedule.due"] = [event()]
            worker = Worker(self.config, self.port, receipts, self.collector)
            await worker.poll()
            with self.assertRaises(OSError):
                FileReceipts(self.config)
        with self.assertRaises(ValueError):
            FileReceipts(replace(self.config, project="different-project"))


class BoundaryTest(unittest.TestCase):
    def test_request_cannot_select_commands_or_network_scope(self) -> None:
        for value in (
            {
                "version": 1,
                "request_id": str(uuid4()),
                "collector": "home",
                "targets": ["192.0.2.10"],
            },
            {"version": True, "request_id": str(uuid4()), "collector": "home"},
            {"version": 1, "request_id": "invalid", "collector": "home"},
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ScanRequest.decode(value)

    def test_json_rejects_duplicates_nonfinite_and_excessive_observations(self) -> None:
        for source in ('{"version":1,"version":2}', '{"timeout":NaN}'):
            with self.assertRaises(ValueError):
                parse_json(source)
        with self.assertRaises(ValueError):
            Snapshot.decode(
                {"version": 1, "observed_at": "2026-01-01", "tcp": [], "dns": []}
            )

    def test_configuration_rejects_shell_targets_and_missing_origin(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.json"
            source = (EXAMPLES / "config.json").read_text()
            for content in (
                source.replace('"https://plowshare.example.invalid"', '""'),
                source.replace('"192.0.2.10"', '"example; touch something"'),
                source.replace('"concurrency": 4', '"concurrency": true'),
                source.replace('"mode": "tcp"', '"mode": []'),
            ):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    Configuration.read(path)
