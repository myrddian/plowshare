from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from plowshare import CancelledRequest, Delivery, Refusal, TransportError
from plowshare.contracts import InformationAdmissionDto, InformationRevisionDto
from support import EXAMPLES, CountingCollector, FixturePort, configuration, event

from plowshare_privacy.journal import FileReceipts, Receipt
from plowshare_privacy.operator_state import FileOperatorStore, Preferences
from plowshare_privacy.rollups import CollectionRollups
from plowshare_privacy.worker import ReconciliationRequired, Worker


class ReportPort(FixturePort):
    def __init__(self, directory: Path):
        super().__init__(
            replace(configuration(directory), time_zone="Australia/Melbourne")
        )
        self.sent: list[tuple[str, str, str]] = []
        self.excluded: list[str] = []
        self.readable = True
        self.lose_upload = False
        self.lose_exclusion = False
        self.drop_before_commit = False
        self.cancel_before_submit = False
        self.refused_suffix: str | None = None

    async def upload_document(
        self, name: str, body: str, request_id: str
    ) -> InformationAdmissionDto:
        if self.cancel_before_submit:
            self.cancel_before_submit = False
            raise CancelledRequest(
                "Fixture cancelled before sending", Delivery.NOT_SUBMITTED
            )
        self.sent.append((name, body, request_id))
        if self.refused_suffix is not None and name.endswith(self.refused_suffix):
            raise Refusal("E_NO_ACCESS", None)
        if self.drop_before_commit:
            self.drop_before_commit = False
            raise TransportError(Delivery.UNKNOWN, "Fixture lost the request")
        revision = str(uuid4())
        self.documents[revision] = (
            InformationRevisionDto(
                id=revision, source_name=name, kind="source", excluded=False
            ),
            body,
        )
        if self.lose_upload:
            self.lose_upload = False
            raise TransportError(Delivery.UNKNOWN, "Fixture lost the upload reply")
        return InformationAdmissionDto(
            created=True, resource=str(uuid4()), revision=revision
        )

    async def read_source(self, revision: str) -> str:
        if not self.readable:
            raise Refusal("E_NOT_READY", None)
        return await super().read_source(revision)

    async def source_status(self, revision: str) -> InformationRevisionDto:
        return self.documents[revision][0]

    async def exclude_source(self, revision: str, request_id: str) -> None:
        self.excluded.append(revision)
        row, body = self.documents[revision]
        self.documents[revision] = replace(row, excluded=True), body
        if self.lose_exclusion:
            self.lose_exclusion = False
            raise TransportError(Delivery.UNKNOWN, "Fixture lost the exclusion reply")


class CollectionRollupTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.port = ReportPort(self.directory)
        self.config = self.port.config
        self.now = "2026-11-30T12:55:00Z"  # 23:55 in Melbourne
        self.collector = CountingCollector(self.config)

    async def test_an_absent_daily_receipt_does_not_starve_later_periods(self) -> None:
        with FileReceipts(self.config):
            rollups = self.rollups()
            rollups.seed(())
            scan = await self.snapshot("2026-11-30T12:45:00Z")
            rollups.record(scan)
            self.now = "2026-11-30T13:00:00Z"
            self.port.drop_before_commit = True
            await rollups.advance(self.port)

            later = await self.snapshot("2026-12-31T12:45:00Z")
            rollups.record(later)
            self.now = "2026-12-31T13:00:00Z"
            rollups.retry_at = 0
            self.port.lose_upload = True
            await rollups.advance(self.port)
            for _ in range(10):
                rollups.retry_at = 0
                await rollups.advance(self.port)
            self.assertEqual(len(self.port.sent), 3)
            self.assertIn("Monthly rollup 2026-12.md", self.port.sent[2][0])
            self.assertEqual(len(self.port.excluded), 1)
            self.assertEqual(
                [day.phase for day in rollups.days.values()], ["submitting"]
            )

    async def test_a_definite_daily_refusal_does_not_starve_later_days(self) -> None:
        with FileReceipts(self.config):
            rollups = self.rollups()
            rollups.seed(())
            old = rollups.record(await self.snapshot("2026-11-29T12:45:00Z"))
            recent = rollups.record(await self.snapshot("2026-11-30T12:45:00Z"))
            self.now = "2026-11-30T13:00:00Z"
            self.port.refused_suffix = "Daily report 2026-11-29.md"
            await rollups.advance(self.port)
            rollups.retry_at = 0
            await rollups.advance(self.port)
            self.assertEqual(rollups.days[old].phase, "open")
            self.assertEqual(rollups.days[recent].phase, "published")

    async def test_dashboard_timezone_change_keeps_the_reporting_calendar(
        self,
    ) -> None:
        with FileReceipts(self.config):
            rollups = self.rollups()
            rollups.seed(())
            previous = await self.snapshot("2026-11-30T12:45:00Z")
            rollups.record(previous)
            self.now = "2026-11-30T13:00:00Z"
            for _ in range(5):
                await rollups.advance(self.port)
            self.assertFalse(rollups.days)

            updated = replace(self.config, time_zone="Pacific/Honolulu")
            rollups = CollectionRollups(updated, clock=lambda: self.now)
            current = await self.snapshot("2026-11-30T13:15:00Z")
            day = rollups.record(current)
            self.assertEqual(day.isoformat(), "2026-12-01")
            self.assertEqual(rollups.days[day].zone, self.config.zone)

    async def test_known_unsent_cancellation_restores_the_daily_intent(self) -> None:
        with FileReceipts(self.config):
            rollups = self.rollups()
            rollups.seed(())
            scan = await self.snapshot("2026-11-30T12:45:00Z")
            day = rollups.record(scan)
            self.now = "2026-11-30T13:00:00Z"
            self.port.cancel_before_submit = True
            with self.assertRaises(CancelledRequest):
                await rollups.advance(self.port)
            rollups = self.rollups()
            self.assertEqual(rollups.days[day].phase, "open")
            await rollups.advance(self.port)
            self.assertEqual(rollups.days[day].phase, "published")
            self.assertEqual(len(self.port.sent), 1)

    def rollups(self) -> CollectionRollups:
        return CollectionRollups(self.config, clock=lambda: self.now)

    async def snapshot(self, stamp: str, *, comparison: str | None = None) -> Receipt:
        scan_id, origin = str(uuid4()), str(uuid4())
        evidence = await self.collector.collect(scan_id, origin, None, None)
        evidence = replace(
            evidence,
            started_at=stamp,
            finished_at=stamp,
            changes=("A service changed",),
            issues=("DNS coverage gap",),
            baseline=False,
        )
        return Receipt(
            scan_id,
            origin,
            "schedule.due",
            stamp,
            "done",
            evidence,
            str(uuid4()),
            comparison_scan_id=comparison,
        )

    async def test_monthly_document_is_readable_before_any_daily_is_hidden(
        self,
    ) -> None:
        with FileReceipts(self.config):
            rollups = self.rollups()
            rollups.seed(())
            first = await self.snapshot("2026-11-29T14:00:00Z")
            second = await self.snapshot("2026-11-30T12:45:00Z")
            for scan in (first, second, second):
                rollups.record(scan)
            await rollups.advance(self.port)
            self.assertEqual(self.port.sent, [])
            self.now = "2026-11-30T13:00:00Z"

            self.port.lose_upload = True
            await rollups.advance(self.port)
            self.assertEqual(len(self.port.sent), 1)
            self.assertIn("Daily report 2026-11-30.md", self.port.sent[0][0])
            self.assertIn("2 settled collections", self.port.sent[0][1])
            self.assertIn("DNS coverage gap (2 collections", self.port.sent[0][1])

            rollups = self.rollups()
            await rollups.advance(
                self.port
            )  # An exact read settles the lost upload reply.
            await rollups.advance(self.port)  # Publish the monthly document.
            self.assertEqual(len(self.port.sent), 2)
            self.assertIn("Monthly rollup 2026-11.md", self.port.sent[1][0])
            self.assertIn("A service changed (2 collections", self.port.sent[1][1])
            daily_revision = next(iter(self.port.documents))
            self.assertIn(
                f"Daily source revision `{daily_revision}`", self.port.sent[1][1]
            )

            self.port.readable = False
            await rollups.advance(self.port)
            self.assertEqual(self.port.excluded, [])

            self.port.readable = True
            rollups = self.rollups()
            await rollups.advance(self.port)  # Confirm full monthly source readable.
            self.assertEqual(self.port.excluded, [])
            self.port.lose_exclusion = True
            await rollups.advance(self.port)
            rollups = self.rollups()
            for _ in range(3):
                await rollups.advance(self.port)
            self.assertEqual(self.port.excluded, [daily_revision])
            self.assertEqual(len(self.port.sent), 2)
            self.assertFalse(rollups.days)

    async def test_routine_scan_stays_local_until_an_investigation_pins_it_and_its_baseline(
        self,
    ) -> None:
        with FileReceipts(self.config) as receipts:
            rollups = self.rollups()
            rollups.seed(())
            operator = FileOperatorStore(self.directory)
            operator.set_preferences(
                replace(Preferences(), automatic_investigations=False)
            )
            worker = Worker(
                self.config,
                self.port,
                receipts,
                self.collector,
                operator,
                rollups=rollups,
            )
            for index in range(2):
                self.port.events["schedule.due"].append(
                    event(f"schedule:fixture-{index}")
                )
                await worker.poll()
            first, second = receipts.all()
            self.assertEqual(self.port.uploads, 0)
            self.assertIsNone(second.revision)
            self.assertEqual(second.comparison_scan_id, first.scan_id)
            original_completion = worker.completion_text(second)
            self.assertEqual(json.loads(original_completion)["version"], 2)
            self.assertNotIn("revision", json.loads(original_completion))

            pinned = await worker.retain_for_investigation(second.scan_id)
            self.assertEqual(self.port.uploads, 2)
            assert (
                pinned.revision is not None and pinned.comparison_revision is not None
            )
            assert second.evidence is not None and first.evidence is not None
            self.assertEqual(
                await self.port.read_source(pinned.revision), second.evidence.encode()
            )
            self.assertEqual(
                await self.port.read_source(pinned.comparison_revision),
                first.evidence.encode(),
            )
            self.assertEqual(worker.completion_text(pinned), original_completion)
            self.assertEqual(
                await worker.retain_for_investigation(second.scan_id), pinned
            )
            self.assertEqual(self.port.uploads, 2)

        with FileReceipts(self.config) as restored:
            self.assertEqual(restored.all()[1], pinned)

    async def test_optional_evidence_pin_does_not_fence_unrelated_collection_recovery(
        self,
    ) -> None:
        with FileReceipts(self.config) as receipts:
            rollups = self.rollups()
            rollups.seed(())
            operator = FileOperatorStore(self.directory)
            operator.set_preferences(
                replace(Preferences(), automatic_investigations=False)
            )
            worker = Worker(
                self.config,
                self.port,
                receipts,
                self.collector,
                operator,
                rollups=rollups,
            )
            self.port.events["schedule.due"].append(event("schedule:before-recovery"))
            await worker.poll()
            first = receipts.all()[0]
            receipts.save(replace(first, pin_pending=True))
            await worker.reconcile()
            with self.assertRaises(ReconciliationRequired):
                await worker.retain_scan_revision(first.scan_id)
            operator.set_preferences(Preferences())
            observations = self.config.collection.observations_file
            assert observations is not None
            observations.write_bytes(
                (EXAMPLES / "observations-after.json").read_bytes()
            )
            self.port.events["schedule.due"].append(event("schedule:after-recovery"))
            await worker.poll()
            self.assertTrue(receipts.all()[0].pin_pending)
            after_recovery = receipts.all()[1]
            self.assertEqual(after_recovery.phase, "done")
            assert after_recovery.investigation is not None
            self.assertFalse(after_recovery.investigation.requested)
            self.assertIn("awaiting confirmation", after_recovery.investigation.reason)
            self.assertEqual(self.port.uploads, 0)

    async def test_retention_carries_the_comparison_needed_by_the_oldest_snapshot(
        self,
    ) -> None:
        from plowshare_privacy.operator_state import InvestigationDecision

        with FileReceipts(
            self.config, clock=lambda: datetime(2026, 12, 16, tzinfo=timezone.utc)
        ) as receipts:
            previous = None
            for index in range(52):
                item = await self.snapshot("2026-11-30T12:45:00Z", comparison=previous)
                receipts.save(
                    replace(
                        item,
                        revision=None,
                        local_completion=True,
                        investigation=InvestigationDecision(
                            False, "Fixture policy", item.occurred_at
                        ),
                    )
                )
                previous = item.scan_id
                if index == 0:
                    oldest = item.scan_id
            self.assertTrue(receipts.archived(oldest))
            retained = receipts.all()
            self.assertEqual(len(retained), 51)
            self.assertEqual(retained[1].comparison_scan_id, retained[0].scan_id)

    async def test_automatic_investigation_pins_the_immediately_previous_scan(
        self,
    ) -> None:
        with FileReceipts(self.config) as receipts:
            rollups = self.rollups()
            rollups.seed(())
            worker = Worker(
                self.config, self.port, receipts, self.collector, rollups=rollups
            )
            for index in range(2):
                self.port.events["schedule.due"].append(
                    event(f"schedule:fixture-{index}")
                )
                await worker.poll()
            baseline, routine = receipts.all()
            self.assertIsNotNone(baseline.revision)
            self.assertIsNone(routine.revision)

            observations = self.config.collection.observations_file
            assert observations is not None
            observations.write_bytes(
                (EXAMPLES / "observations-after.json").read_bytes()
            )
            self.port.events["schedule.due"].append(event("schedule:fixture-2"))
            await worker.poll()

            _, previous, current = receipts.all()
            completion = json.loads(worker.completion_text(current))
            self.assertEqual(completion["version"], 1)
            self.assertEqual(completion["comparison_scan_id"], routine.scan_id)
            self.assertEqual(completion["comparison_revision"], previous.revision)
            self.assertNotEqual(completion["comparison_revision"], baseline.revision)
            self.assertEqual(completion["revision"], current.revision)
            self.assertEqual(self.port.uploads, 3)
