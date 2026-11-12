"""Operator decisions, admission receipts and authenticated recovery; no paid work."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from typing import Awaitable, Callable
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from plowshare import Delivery, Refusal, TransportError
from support import CountingCollector, FixturePort, MemoryReceipts, configuration, event
from test_device_assessments import Dossiers, Runs

from plowshare_privacy.advisories import AdvisoryLookup, AdvisoryPage
from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.console_port import (
    Admission,
    AgentHealth,
    RunSummary,
    ScheduleView,
)
from plowshare_privacy.contracts import (
    Configuration,
    DeviceIdentity,
    DeviceLabel,
    PiHolePlan,
    TcpObservation,
)
from plowshare_privacy.device_assessments import AssessmentJournal, DeviceAssessments
from plowshare_privacy.device_inventory import DeviceInventory, device_inventory
from plowshare_privacy.discovery import (
    DiscoveredDevice,
    DiscoveryReport,
    LocalDeviceDiscovery,
)
from plowshare_privacy.identity_source import ManagedProduct, ManagedProductLookup
from plowshare_privacy.journal import FileReceipts, Receipt
from plowshare_privacy.monitor import MonitorChoice, MonitorView, SettingsBusy
from plowshare_privacy.operator_state import (
    FileOperatorStore,
    InvestigationDecision,
    Preferences,
    findings,
)
from plowshare_privacy.pihole import DeviceData
from plowshare_privacy.presence import PresenceView, Sighting
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "private-console-fixture-token-12345"


class Settings:
    def view(self) -> MonitorView:
        return MonitorView(True, (), (), True, "fixture")

    async def save(self, choice: MonitorChoice) -> MonitorView:
        return self.view()

    async def save_pihole(self, origin: str, password_file: Path) -> MonitorView:
        return self.view()

    async def save_time_zone(self, zone: str | None) -> Configuration:
        raise SettingsBusy("Fixture settings keep no time zone")


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
        self.receipt = Admission(request_id, "orc_fixture_123", "RUNNING")
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
        self.assertEqual(restored.manual[0].run_id, "orc_fixture_123")
        persisted = FileManualInvestigations(self.root).all()[0]
        self.assertEqual(persisted, restored.manual[0])
        self.recover.assert_awaited_once()
        self.assertEqual(self.remote.starts, 1)

    async def test_opaque_run_admission_survives_journal_reload(self) -> None:
        entry = await self.console.investigate(self.scan.scan_id, str(uuid4()))
        self.assertEqual(entry.phase, "confirmed")
        self.assertEqual(entry.run_id, "orc_fixture_123")
        self.assertEqual(FileManualInvestigations(self.root).all(), (entry,))
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
        self.assertNotIn("administrator", first.detail)
        self.assertNotIn("unavailable", first.detail.lower())
        self.assertEqual(await self.console.health(), first)
        self.assertEqual(self.remote.reads, 1)
        self.assertTrue(first.checklist)

    async def test_missing_schedule_does_not_claim_a_permission_failure(self) -> None:
        with patch.object(self.remote, "schedule", new=AsyncMock(return_value=None)):
            health = await self.console.health()
        self.assertTrue(health.connected)
        self.assertIsNone(health.schedule)
        self.assertIn("no active scan schedule", health.detail)
        self.assertNotIn("administrator", health.detail)

    async def test_failed_schedule_read_reports_a_real_connection_problem(self) -> None:
        with patch.object(
            self.remote,
            "schedule",
            new=AsyncMock(
                side_effect=TransportError(Delivery.UNKNOWN, "fixture disconnected")
            ),
        ):
            health = await self.console.health()
        self.assertFalse(health.connected)
        self.assertIn("Plowshare checks are unavailable", health.detail)
        self.assertNotIn("Project read checks passed", health.detail)

    async def test_expected_findings_keep_original_evidence(self) -> None:
        evidence = self.scan.evidence
        assert evidence is not None
        changed = replace(
            evidence, changes=("TCP 192.0.2.12:443: closed -> open",), baseline=False
        )
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
        response = await client.get("/api/overview", headers=headers)
        self.assertEqual(response.status, 200)
        overview = await response.json()
        # Configuration is described, never disclosed; no assessments here.
        self.assertEqual(
            overview["system"]["deployment"]["project"], self.worker.config.project
        )
        self.assertNotIn(TOKEN, json.dumps(overview))
        self.assertIsNone(overview["assessments"])
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

    def test_device_inventory_remembers_responses_without_reasserting_old_identity(
        self,
    ) -> None:
        address, silent, unscanned = "10.1.2.10", "10.1.2.22", "10.1.2.33"
        first, latest = "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        identity = DeviceIdentity(
            address,
            "mac:02:00:00:00:00:01",
            None,
            "TV",
            "02:00:00:00:00:01",
            None,
            "pihole-v6",
            first,
            first,
        )
        self.worker.config = replace(
            self.config,
            collection=replace(
                self.config.collection, targets=(address, silent, unscanned)
            ),
        )
        evidence = self.scan.evidence
        assert evidence is not None
        baseline = replace(
            evidence,
            finished_at=first,
            snapshot=replace(
                evidence.snapshot,
                tcp=(TcpObservation(address, 443, "closed"),),
                devices=(identity,),
            ),
        )
        self.receipts.save(replace(self.scan, evidence=baseline))
        self.assertEqual(self.console.inventory().devices[0].identity, identity)

        # An initial observation stays in inventory after its card link ages out.
        for _ in range(51):
            scan_id = str(uuid4())
            unanswered = replace(
                baseline,
                scan_id=scan_id,
                finished_at=latest,
                snapshot=replace(
                    baseline.snapshot,
                    tcp=(
                        TcpObservation(address, 443, "timeout"),
                        TcpObservation(silent, 443, "unreachable"),
                    ),
                    devices=(),
                ),
            )
            self.receipts.save(replace(self.scan, scan_id=scan_id, evidence=unanswered))
        inventory = self.console.inventory()
        (observed,) = inventory.devices
        never_responded, never_checked = inventory.candidates
        self.assertEqual(observed.last_seen, latest)
        self.assertEqual(
            (observed.first_response_at, observed.last_response_at), (first, first)
        )
        self.assertIsNone(observed.identity)
        self.assertEqual(observed.last_known_identity, identity)
        self.assertEqual(observed.name, "Unnamed device")
        self.assertEqual(len(observed.history), 50)
        self.assertNotIn(self.scan.scan_id, observed.history)
        self.assertEqual(never_responded.address, silent)
        self.assertEqual(never_responded.last_checked_at, latest)
        self.assertEqual(never_checked.address, unscanned)
        self.assertIsNone(never_checked.last_checked_at)

    async def test_device_research_checks_identity_and_searches_only_reviewed_model(
        self,
    ) -> None:
        address = "192.168.90.2"
        candidate = "192.168.90.3"
        stamp = "2026-01-01T00:00:00Z"
        mac = "02:00:00:00:00:01"
        identity = DeviceIdentity(
            address,
            "mac:" + mac,
            None,
            "private-name",
            mac,
            None,
            "unifi",
            stamp,
            stamp,
        )
        sightings = PresenceView(sightings={address: Sighting(address, identity)})
        inventory: DeviceInventory = device_inventory(
            (address, candidate), (), (), sightings
        )
        products = AsyncMock(spec=ManagedProductLookup)
        products.managed_product.return_value = ManagedProduct(
            "Example", "Switch 10G", "1.2.3", True
        )
        advisories = AsyncMock(spec=AdvisoryLookup)
        model = "Example Switch 10G"
        advisories.search.return_value = AdvisoryPage(model, stamp, 0, ())
        headers = {"Authorization": "Bearer " + TOKEN}
        product_path = f"/api/devices/{address}/product"
        search_path = f"/api/devices/{address}/advisories"
        with patch.object(self.console, "inventory", return_value=inventory):
            async with TestClient(
                TestServer(
                    application(
                        self.worker,
                        TOKEN,
                        console=self.console,
                        advisories=advisories,
                        managed_products=lambda: products,
                    )
                )
            ) as client:
                self.assertEqual(
                    (
                        await client.post(
                            product_path, json={"device_id": identity.device_id}
                        )
                    ).status,
                    401,
                )
                self.assertEqual(
                    (
                        await client.post(
                            product_path,
                            headers=headers,
                            json={"device_id": "mac:02:00:00:00:00:02"},
                        )
                    ).status,
                    409,
                )
                products.managed_product.assert_not_awaited()
                product = await client.post(
                    product_path,
                    headers=headers,
                    json={"device_id": identity.device_id},
                )
                self.assertEqual((await product.json())["product"]["firmware"], "1.2.3")
                products.managed_product.assert_awaited_once_with(identity)
                for body in (
                    {"model": model, "mac": mac},
                    {"model": address},
                    {"model": "private-name"},
                    {"model": "private-name.lan"},
                ):
                    self.assertEqual(
                        (
                            await client.post(search_path, headers=headers, json=body)
                        ).status,
                        400,
                    )
                self.assertEqual(
                    (
                        await client.post(
                            f"/api/devices/{candidate}/advisories",
                            headers=headers,
                            json={"model": model},
                        )
                    ).status,
                    404,
                )
                advisories.search.assert_not_awaited()
                response = await client.post(
                    search_path, headers=headers, json={"model": model}
                )
                self.assertEqual((await response.json())["model"], model)
                advisories.search.assert_awaited_once_with(model)
        self.assertEqual(self.remote.starts, 0)
        self.assertEqual(self.receipts.all(), (self.scan,))

    async def test_subnet_addresses_only_become_devices_with_observed_evidence(
        self,
    ) -> None:
        open_address, identified, refused, silent = (
            "192.168.90.1",
            "192.168.90.2",
            "192.168.90.3",
            "192.168.90.4",
        )
        targets = (open_address, identified, refused, silent)
        self.worker.config = replace(
            self.config,
            collection=replace(
                self.config.collection,
                targets=targets,
                labels=(DeviceLabel(refused, "Configured name"),),
            ),
        )
        async with TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console))
        ) as client:
            headers = {"Authorization": "Bearer " + TOKEN}
            response = await client.get("/api/overview", headers=headers)
            self.assertEqual(response.status, 200)
            overview = await response.json()
            self.assertEqual(overview["devices"], [])
            self.assertEqual(
                [row["address"] for row in overview["candidates"]], list(targets)
            )

            evidence = self.scan.evidence
            assert evidence is not None
            stamp = evidence.finished_at
            pihole = DeviceIdentity(
                identified,
                "mac:02:00:00:00:00:01",
                None,
                "TV",
                "02:00:00:00:00:01",
                None,
                "pihole-v6",
                stamp,
                stamp,
            )
            configured = DeviceIdentity(
                refused,
                "ip:" + refused,
                "Configured name",
                None,
                None,
                None,
                "configured",
                stamp,
                None,
            )
            snapshot = replace(
                evidence.snapshot,
                tcp=(
                    TcpObservation(open_address, 443, "open"),
                    TcpObservation(identified, 443, "timeout"),
                    TcpObservation(refused, 443, "closed"),
                    TcpObservation(silent, 443, "timeout"),
                ),
                devices=(pihole, configured),
            )
            self.receipts.save(
                replace(self.scan, evidence=replace(evidence, snapshot=snapshot))
            )
            response = await client.get("/api/overview", headers=headers)
            self.assertEqual(response.status, 200)
            overview = await response.json()
            self.assertEqual(
                [row["address"] for row in overview["devices"]],
                [open_address, identified],
            )
            self.assertEqual(
                [row["address"] for row in overview["candidates"]], [refused, silent]
            )
            self.assertEqual(overview["candidates"][0]["label"], "Configured name")
            self.assertEqual(overview["candidates"][0]["last_checked_at"], stamp)

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

    async def test_pihole_save_never_tests_connection_and_can_wait_for_activation(
        self,
    ) -> None:
        settings = AsyncMock(spec=Settings)
        settings.save_pihole.return_value = MonitorView(
            True, (), (), True, "Settings saved", pending=True
        )
        self.console.settings = settings
        with patch("plowshare_privacy.console.PiHoleV6") as source:
            source.side_effect = AssertionError("Saving must not contact Pi-hole")
            async with self.console.lock:
                saved = await asyncio.wait_for(
                    self.console.connect_pihole(
                        "http://pihole.invalid/", "fixture-secret", True
                    ),
                    1,
                )
        self.assertTrue(saved.saved)
        self.assertTrue(saved.pending)
        self.assertIsNone(saved.connected)
        credential = next(self.root.glob("pihole-*.password"))
        self.assertEqual(credential.read_text(), "fixture-secret\n")
        self.assertEqual(credential.stat().st_mode & 0o777, 0o600)
        self.assertNotIn("fixture-secret", json.dumps(asdict(saved)))
        # Only the connection is handed over; settings keep the saved tuning.
        settings.save_pihole.assert_awaited_once_with(
            "http://pihole.invalid", credential
        )

    async def test_failed_pihole_test_does_not_remove_saved_password(self) -> None:
        await self.console.connect_pihole(
            "http://pihole.invalid", "fixture-secret", True
        )
        credential = next(self.root.glob("pihole-*.password"))
        before = credential.read_bytes()
        with patch("plowshare_privacy.console.PiHoleV6") as source:
            source.return_value.identify = AsyncMock(
                return_value=DeviceData(
                    (), (), None, ("Pi-hole authentication or connection failed",)
                )
            )
            tested = await self.console.connect_pihole(
                "http://pihole.invalid", "bad-secret", False
            )
        self.assertFalse(tested.connected)
        self.assertFalse(tested.saved)
        self.assertEqual(credential.read_bytes(), before)
        self.assertEqual(tuple(self.root.glob("pihole-*.password")), (credential,))

    async def test_pihole_save_api_succeeds_while_worker_disconnected(self) -> None:
        self.worker.state = "attention_required"
        settings = AsyncMock(spec=Settings)
        settings.save_pihole.return_value = MonitorView(
            True, (), (), True, "Settings saved", pending=True
        )
        self.console.settings = settings
        client = TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console))
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        with patch("plowshare_privacy.console.PiHoleV6") as source:
            response = await client.post(
                "/api/pihole",
                headers={"Authorization": "Bearer " + TOKEN},
                json={
                    "origin": "http://pihole.invalid",
                    "password": "fixture-secret",
                    "save": True,
                },
            )
            self.assertEqual(response.status, 200)
            result = await response.json()
            self.assertTrue(result["saved"])
            self.assertTrue(result["pending"])
            self.assertIsNone(result["connected"])
            source.assert_not_called()

    async def test_pihole_test_never_writes_password(self) -> None:
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

    async def test_device_assessment_routes_check_access_inventory_and_state(
        self,
    ) -> None:
        address, mac = "192.168.90.2", "02:00:00:00:00:01"
        stamp = "2026-01-01T00:00:00Z"
        device = "mac:" + mac
        identity = DeviceIdentity(
            address, device, None, "lounge-tv", mac, None, "unifi", stamp, stamp
        )
        inventory = device_inventory(
            (address,),
            (),
            (),
            PresenceView(sightings={address: Sighting(address, identity)}),
        )
        runs = Runs()
        assessments = DeviceAssessments(
            AssessmentJournal(self.root),
            runs,
            Dossiers(),
            lambda: inventory,
            clock=lambda: stamp,
        )
        headers = {"Authorization": "Bearer " + TOKEN}
        path = f"/api/devices/{device}/assessment"
        async with TestClient(
            TestServer(
                application(
                    self.worker, TOKEN, console=self.console, assessments=assessments
                )
            )
        ) as client:
            self.assertEqual((await client.get(path)).status, 401)
            self.assertEqual((await client.post(path + "/assess", json={})).status, 401)
            status = await (await client.get("/api/status", headers=headers)).json()
            self.assertTrue(status["device_assessments_available"])
            idle = await client.get(path, headers=headers)
            self.assertEqual(idle.status, 200)
            body = await idle.json()
            self.assertEqual((body["state"], body["can_assess"]), ("idle", True))
            self.assertEqual(
                (
                    await client.get(
                        "/api/devices/mac:02:00:00:00:00:09/assessment",
                        headers=headers,
                    )
                ).status,
                404,
            )
            self.assertEqual(
                (
                    await client.get(
                        f"/api/devices/{address}/assessment", headers=headers
                    )
                ).status,
                400,
            )
            self.assertEqual(
                (
                    await client.post(
                        path + "/assess", headers=headers, json={"device": device}
                    )
                ).status,
                400,
            )
            await client.post("/api/session", headers=headers, json={})
            self.assertEqual(
                (
                    await client.post(
                        path + "/assess",
                        json={},
                        headers={"Origin": "https://foreign.invalid"},
                    )
                ).status,
                403,
            )
            self.assertEqual(runs.starts, [])
            started = await client.post(path + "/assess", headers=headers, json={})
            self.assertEqual(started.status, 202)
            self.assertEqual((await started.json())["state"], "running")
            again = await client.post(path + "/assess", headers=headers, json={})
            self.assertEqual(again.status, 409)
            self.assertIn("already running", (await again.json())["error"])
            self.assertEqual(
                (await client.get(path + "/report", headers=headers)).status, 409
            )
            revision = runs.finish(runs.starts[0][1])
            await assessments.poll()
            review = await (await client.get(path, headers=headers)).json()
            self.assertEqual((review["state"], review["report"]), ("review", revision))
            report = await (await client.get(path + "/report", headers=headers)).json()
            self.assertEqual(report["revision"], revision)
            self.assertIn("privacy assessment", report["text"])
            runs.finalise_error = Refusal("FORBIDDEN", "Write access is required")
            refused = await client.post(path + "/accept", headers=headers, json={})
            self.assertEqual(refused.status, 409)
            failure = await refused.json()
            self.assertIn("Write access is required", failure["error"])
            self.assertIn("Reports collection", failure["error"])
            self.assertEqual(failure["assessment"]["state"], "review")
            runs.finalise_error = None
            accepted = await client.post(path + "/accept", headers=headers, json={})
            self.assertEqual(accepted.status, 200)
            self.assertEqual((await accepted.json())["state"], "accepted")
            self.assertEqual(
                (await client.post(path + "/dismiss", headers=headers, json={})).status,
                409,
            )
            settings = "/api/device-assessments"
            self.assertEqual(
                (await (await client.get(settings, headers=headers)).json())[
                    "background_limit"
                ],
                0,
            )
            self.assertEqual(
                (
                    await client.post(
                        settings, headers=headers, json={"background_limit": 99}
                    )
                ).status,
                400,
            )
            saved = await client.post(
                settings, headers=headers, json={"background_limit": 2}
            )
            self.assertEqual((await saved.json())["background_limit"], 2)
        self.assertEqual(len(runs.finalised), 2)
        async with TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console))
        ) as client:
            self.assertEqual((await client.get(path, headers=headers)).status, 404)
            status = await (await client.get("/api/status", headers=headers)).json()
            self.assertFalse(status["device_assessments_available"])

    async def test_device_supplemental_route_reads_retained_facts_only(
        self,
    ) -> None:
        from test_device_dossier import facts

        address, mac = "192.168.90.2", "02:00:00:00:00:01"
        stamp = "2026-01-01T00:00:00Z"
        device = "mac:" + mac
        identity = DeviceIdentity(
            address, device, None, "lounge-smarttv", mac, None, "unifi", stamp, stamp
        )
        inventory = device_inventory(
            (address,),
            (),
            (),
            PresenceView(sightings={address: Sighting(address, identity)}),
        )
        retained: dict[str, object] = {}
        self.worker.device_inventory = lambda: inventory  # type: ignore[method-assign]
        self.worker.device_facts = retained.get  # type: ignore[assignment]
        headers = {"Authorization": "Bearer " + TOKEN}
        path = f"/api/devices/{device}/supplemental"
        async with TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console))
        ) as client:
            self.assertEqual((await client.get(path)).status, 401)
            empty = await (await client.get(path, headers=headers)).json()
            self.assertFalse(empty["enabled"])
            self.assertIsNone(empty["supplemental"]["described_at"])
            self.assertEqual(empty["supplemental"]["descriptions"], [])
            retained[device] = replace(facts(), device_id=device)
            body = await (await client.get(path, headers=headers)).json()
            section = body["supplemental"]
            self.assertEqual(
                section["descriptions"][0]["model_name"], "BRAVIA KD-55X85K"
            )
            self.assertEqual(section["advisories"]["status"], "searched")
            self.assertEqual(
                [note["feature"] for note in section["vendor_notes"]],
                ["Samba Interactive TV"],
            )
            missing = await client.get(
                "/api/devices/mac:02:00:00:00:00:09/supplemental", headers=headers
            )
            self.assertEqual(missing.status, 404)
            invalid = await client.get(
                f"/api/devices/{address}/supplemental", headers=headers
            )
            self.assertEqual(invalid.status, 400)

    async def test_device_assessment_dismiss_is_local(self) -> None:
        address, mac = "192.168.90.2", "02:00:00:00:00:01"
        stamp = "2026-01-01T00:00:00Z"
        device = "mac:" + mac
        identity = DeviceIdentity(
            address, device, None, "lounge-tv", mac, None, "unifi", stamp, stamp
        )
        inventory = device_inventory(
            (address,),
            (),
            (),
            PresenceView(sightings={address: Sighting(address, identity)}),
        )
        runs = Runs()
        assessments = DeviceAssessments(
            AssessmentJournal(self.root), runs, Dossiers(), lambda: inventory
        )
        headers = {"Authorization": "Bearer " + TOKEN}
        path = f"/api/devices/{device}/assessment"
        await assessments.assess(device)
        runs.finish(runs.starts[0][1])
        await assessments.poll()
        calls = list(runs.calls)
        async with TestClient(
            TestServer(
                application(
                    self.worker, TOKEN, console=self.console, assessments=assessments
                )
            )
        ) as client:
            dismissed = await client.post(path + "/dismiss", headers=headers, json={})
            self.assertEqual(dismissed.status, 200)
            self.assertEqual((await dismissed.json())["state"], "dismissed")
            self.assertEqual(
                (await client.get(path + "/report", headers=headers)).status, 409
            )
        self.assertEqual(runs.calls, calls)
