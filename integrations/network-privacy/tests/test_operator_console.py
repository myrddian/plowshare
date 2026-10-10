"""Operator decisions, admission receipts and authenticated recovery; no paid work."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from typing import Awaitable, Callable
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from plowshare import Delivery, TransportError
from support import CountingCollector, FixturePort, MemoryReceipts, configuration, event

from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.console_port import (
    Admission,
    AgentHealth,
    RunSummary,
    ScheduleView,
)
from plowshare_privacy.contracts import DeviceIdentity, PiHolePlan
from plowshare_privacy.discovery import (
    DiscoveredDevice,
    DiscoveryReport,
    LocalDeviceDiscovery,
)
from plowshare_privacy.journal import FileReceipts, Receipt
from plowshare_privacy.monitor import MonitorChoice, MonitorView, SettingsBusy
from plowshare_privacy.operator_state import (
    FileOperatorStore,
    InvestigationDecision,
    Preferences,
    findings,
)
from plowshare_privacy.pihole import DeviceData
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "private-console-fixture-token-12345"


class Settings:
    def view(self) -> MonitorView:
        return MonitorView(True, (), (), True, "fixture")

    async def save(self, choice: MonitorChoice) -> MonitorView:
        return self.view()

    async def save_pihole(self, plan: PiHolePlan) -> MonitorView:
        return self.view()


class ConsoleFixture:
    def __init__(self) -> None:
        self.reads = 0
        self.starts = 0
        self.lost = False
        self.receipt: Admission | None = None

    async def schedule(self) -> ScheduleView:
        self.reads += 1
        return ScheduleView("private-schedule", "0 0 * * * *", "UTC", True, None)

    async def agents(self) -> AgentHealth:
        return AgentHealth(
            ("privacy_coordinator",),
            ("privacy_analyst", "privacy_reviewer"),
            (),
            ("network_scan",),
        )

    async def runs(self) -> tuple[RunSummary, ...]:
        return ()

    async def investigate(self, receipt: Receipt, request_id: str) -> Admission:
        self.starts += 1
        self.receipt = Admission(request_id, str(uuid4()), "RUNNING")
        if self.lost:
            raise TransportError(Delivery.UNKNOWN, "fixture lost reply")
        return self.receipt

    async def investigation_receipt(self, request_id: str) -> Admission:
        if not self.receipt or self.receipt.request_id != request_id:
            raise ValueError("No positive receipt")
        return self.receipt


class OperatorTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = configuration(self.root)
        self.store = FileOperatorStore(self.root)
        self.store.set_preferences(Preferences(False, 8, 60, False))
        self.receipts = MemoryReceipts()
        self.port = FixturePort(self.config)
        self.worker = Worker(
            self.config,
            self.port,
            self.receipts,
            CountingCollector(self.config),
            self.store,
        )
        self.remote = ConsoleFixture()

        async def recover(callback: Callable[[], Awaitable[None]]) -> None:
            await callback()

        self.recover = AsyncMock(side_effect=recover)
        self.console = OperatorConsole(
            self.root,
            self.worker,
            self.remote,
            Settings(),
            LocalDeviceDiscovery(self.root),
            self.store,
            self.recover,
            FileManualInvestigations(self.root),
        )
        self.port.events["schedule.due"] = [event()]
        await self.worker.poll()
        self.scan = self.receipts.all()[0]

    async def test_withheld_completion_preserves_evidence_and_legacy_route_skips(
        self,
    ) -> None:
        completion = json.loads(self.port.published[-1].text or "{}")
        self.assertEqual(completion["changes"], [])
        self.assertTrue(completion["observed_changes"])
        self.assertFalse(completion["investigation"]["requested"])
        self.assertTrue(self.scan.evidence and self.scan.evidence.changes)
        self.assertEqual(self.scan.phase, "done")

    async def test_unknown_manual_admission_is_never_resubmitted_and_positive_receipt_settles(
        self,
    ) -> None:
        request = str(uuid4())
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.console.investigate(self.scan.scan_id, request)
        self.assertEqual(
            (await self.console.investigate(self.scan.scan_id, request)).phase,
            "pending",
        )
        restored = OperatorConsole(
            self.root,
            self.worker,
            self.remote,
            Settings(),
            LocalDeviceDiscovery(self.root),
            self.store,
            self.recover,
            FileManualInvestigations(self.root),
        )
        self.assertEqual(
            (await restored.investigate(self.scan.scan_id, request)).phase, "pending"
        )
        self.assertEqual(self.remote.starts, 1)
        await restored.check_and_resume()
        self.assertEqual(restored.manual[0].phase, "confirmed")
        self.recover.assert_awaited_once()
        self.assertEqual(self.remote.starts, 1)

    async def test_absence_does_not_resume_or_resubmit(self) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.console.investigate(self.scan.scan_id, str(uuid4()))
        self.remote.receipt = None
        with self.assertRaises(ValueError):
            await self.console.check_and_resume()
        self.recover.assert_awaited_once()
        self.assertFalse(self.worker.recovery_requested.is_set())
        self.assertEqual(self.remote.starts, 1)

    async def test_automatic_or_legacy_admission_cannot_be_duplicated_manually(
        self,
    ) -> None:
        for decision in (
            None,
            InvestigationDecision(True, "admitted", self.scan.occurred_at),
        ):
            self.receipts.save(replace(self.scan, investigation=decision))
            with self.assertRaises(ValueError):
                await self.console.investigate(self.scan.scan_id, str(uuid4()))
        self.assertEqual(self.remote.starts, 0)

    async def test_health_reads_are_cached_and_keep_partial_unavailability_visible(
        self,
    ) -> None:
        first = await self.console.health()
        self.assertTrue(first.connected)
        self.assertTrue(first.agents and first.agents.missing_tools)
        self.assertTrue(first.schedule and first.schedule.paused)
        self.assertEqual(await self.console.health(), first)
        self.assertEqual(self.remote.reads, 1)
        self.assertTrue(first.checklist)

    async def test_expected_findings_keep_original_evidence(self) -> None:
        evidence = self.scan.evidence
        assert evidence is not None
        changed = replace(evidence, changes=("TCP 192.0.2.12:443: closed -> open",))
        receipt = replace(self.scan, evidence=changed)
        self.receipts.save(receipt)
        finding = self.console.findings()[0]
        self.console.acknowledge(finding.id, True)
        self.assertTrue(self.console.findings()[0].expected)
        self.assertEqual(self.receipts.all()[0].evidence, changed)
        self.assertIn(finding.id, FileOperatorStore(self.root).expected)
        with self.assertRaises(ValueError):
            self.console.acknowledge("0" * 64, True)
        self.console.acknowledge(finding.id, False)
        self.assertFalse(self.console.findings()[0].expected)

    async def test_budget_counts_retained_and_legacy_admissions(self) -> None:
        evidence = self.scan.evidence
        assert evidence is not None
        now = "2026-01-01T00:00:01Z"
        self.store.set_preferences(Preferences(True, 1, 0, False))
        prior = replace(
            self.scan, investigation=InvestigationDecision(True, "baseline", now)
        )
        with (
            patch("plowshare_privacy.operator_state.utc_now", return_value=now),
            patch("plowshare_privacy.operator_state.datetime") as clock,
        ):
            from datetime import datetime, timezone

            clock.now.return_value = datetime(2026, 1, 1, tzinfo=timezone.utc)
            clock.fromisoformat.side_effect = datetime.fromisoformat
            self.assertIn("Daily", self.store.decide(evidence, (prior,)).reason)
            self.assertIn(
                "Daily",
                self.store.decide(
                    evidence,
                    (
                        replace(
                            prior,
                            investigation=None,
                            evidence=replace(evidence, finished_at=now),
                        ),
                    ),
                ).reason,
            )

    async def test_decision_cannot_change_after_retention(self) -> None:
        with FileReceipts(self.config) as receipts:
            receipts.save(self.scan)
            with self.assertRaises(ValueError):
                receipts.save(
                    replace(
                        self.scan,
                        investigation=InvestigationDecision(
                            True, "different", self.scan.occurred_at
                        ),
                    )
                )
        with FileReceipts(self.config) as receipts:
            self.assertEqual(receipts.all()[0], self.scan)

    async def test_preference_validation_and_file_permissions(self) -> None:
        for field, value in (
            ("daily_limit", 0),
            ("daily_limit", True),
            ("cooldown_minutes", 1441),
            ("automatic_investigations", "yes"),
        ):
            row = asdict(Preferences())
            row[field] = value
            with self.assertRaises(ValueError):
                Preferences.decode(row)
        self.assertEqual(
            (self.root / "operator-preferences.json").stat().st_mode & 0o777, 0o600
        )

    async def test_new_web_mutations_require_authentication_and_same_origin(
        self,
    ) -> None:
        client = TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console))
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        payload = asdict(Preferences(True, 2, 10, False))
        self.assertEqual(
            (await client.post("/api/preferences", json=payload)).status, 401
        )
        headers = {"Authorization": "Bearer " + TOKEN}
        self.assertEqual(
            (await client.get("/api/overview", headers=headers)).status, 200
        )
        self.assertEqual(
            (await client.post("/api/session", headers=headers, json={})).status, 200
        )
        self.assertEqual(
            (
                await client.post(
                    "/api/preferences",
                    json=payload,
                    headers={"Origin": "https://foreign.invalid"},
                )
            ).status,
            403,
        )
        self.assertFalse(self.store.preferences.automatic_investigations)
        self.assertEqual(
            (
                await client.post("/api/preferences", headers=headers, json=payload)
            ).status,
            200,
        )
        self.assertTrue(self.store.preferences.automatic_investigations)

    async def test_preference_change_does_not_rewrite_unknown_completion(self) -> None:
        receipt = replace(self.scan, phase="publishing")
        expected = self.worker.completion_request(receipt).text
        self.store.set_preferences(Preferences(True, 50, 0, False))
        self.assertEqual(self.worker.completion_request(receipt).text, expected)
        self.assertEqual(findings(receipt, frozenset()), ())

    async def test_move_requires_one_fresh_address_and_rechecks_before_accept(
        self,
    ) -> None:
        evidence = self.scan.evidence
        assert evidence is not None
        old = "10.1.2.10"
        new = "10.1.2.22"
        stamp = self.scan.occurred_at
        identity = DeviceIdentity(
            old,
            "mac:02:00:00:00:00:01",
            None,
            "TV",
            "02:00:00:00:00:01",
            None,
            "pihole-v6",
            stamp,
            stamp,
        )
        evidence = self.scan.evidence
        assert evidence is not None
        self.receipts.save(
            replace(
                self.scan,
                evidence=replace(
                    evidence,
                    snapshot=replace(
                        evidence.snapshot,
                        devices=(identity,),
                        tcp=(replace(evidence.snapshot.tcp[0], address=old),),
                    ),
                ),
            )
        )
        plan = PiHolePlan.decode(
            {
                "origin": "http://pihole.invalid",
                "passwordEnvironment": "PRIVACY_FIXTURE_PASSWORD",
            }
        )
        self.worker.config = replace(
            self.config,
            collection=replace(
                self.config.collection, targets=(old,), ports=(443,), pihole=plan
            ),
        )
        settings = AsyncMock(spec=Settings)
        settings.view = lambda: MonitorView(True, (old,), (443,), True, "fixture")
        self.console.settings = settings
        discovery = AsyncMock(spec=LocalDeviceDiscovery)
        discovery.latest = lambda: DiscoveryReport(
            "10.1.2.0/24", (443,), 254, (DiscoveredDevice(new, (443,), ()),), 0
        )
        self.console.discovery = discovery
        moved = replace(identity, address=new)
        with patch("plowshare_privacy.console.PiHoleV6") as source:
            identify = source.return_value.identify = AsyncMock(
                return_value=DeviceData((moved,), (), None, ())
            )
            suggestion = (await self.console.moved_devices())[0]
            self.assertEqual((suggestion.old, suggestion.new), (old, new))
            settings.save.assert_not_awaited()
            identify.return_value = DeviceData(
                (moved, replace(moved, address="10.1.2.23")), (), None, ()
            )
            self.assertEqual(await self.console.moved_devices(), ())
            with self.assertRaises(ValueError):
                await self.console.accept_move(old, new, identity.device_id)
            settings.save.assert_not_awaited()
            identify.return_value = DeviceData((moved,), (), None, ())
            await self.console.accept_move(old, new, identity.device_id)
            self.assertEqual(settings.save.await_args.args[0].targets, (new,))
            self.assertEqual(identify.await_count, 4)

    async def test_pihole_test_never_writes_and_busy_save_keeps_secret_out(
        self,
    ) -> None:
        with patch("plowshare_privacy.console.PiHoleV6") as source:
            source.return_value.identify = AsyncMock(
                return_value=DeviceData(
                    (), (), None, ("No fresh identity association",)
                )
            )
            tested = await self.console.connect_pihole(
                "http://pihole.invalid", "fixture-secret", False
            )
            self.assertTrue(tested.connected)
            self.assertFalse(tested.saved)
            self.assertEqual(tuple(self.root.glob("pihole-*.password")), ())
            settings = AsyncMock(spec=Settings)
            settings.view = lambda: MonitorView(True, (), (), False, "Pending receipt")
            self.console.settings = settings
            with self.assertRaises(SettingsBusy):
                await self.console.connect_pihole(
                    "http://pihole.invalid", "fixture-secret", True
                )
            self.assertEqual(tuple(self.root.glob("pihole-*.password")), ())
            settings.save_pihole.assert_not_awaited()
            settings.view = lambda: MonitorView(True, (), (), True, "fixture")
            saved = await self.console.connect_pihole(
                "http://pihole.invalid", "fixture-secret", True
            )
            self.assertTrue(saved.saved)
            credential = next(self.root.glob("pihole-*.password"))
            self.assertEqual(credential.read_text(), "fixture-secret\n")
            self.assertEqual(credential.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("fixture-secret", json.dumps(asdict(saved)))
