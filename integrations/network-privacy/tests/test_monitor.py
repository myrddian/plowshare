"""Authenticated operator setup, strict scope fences and interruption recovery."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from support import EXAMPLES, CountingCollector, FixturePort, configuration

from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.collector_factory import configured_collector
from plowshare_privacy.contracts import Configuration, DeviceLabel, PiHolePlan, utc_now
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.journal import FileReceipts, Publication, Receipt
from plowshare_privacy.monitor import FileMonitorSettings, MonitorChoice, SettingsBusy
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "dashboard-fixture-bearer-123456"
CHOICE = MonitorChoice.decode(
    {"enabled": True, "targets": ["192.168.0.12"], "ports": [443]}
)


class MonitorTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "collector.json"
        row = json.loads((EXAMPLES / "config.json").read_text())
        row["stateDirectory"] = str(self.root / "state")
        row["collection"] = {
            "mode": "tcp",
            "enabled": False,
            "targets": [],
            "ports": [],
            "timeoutSeconds": 1,
            "concurrency": 4,
        }
        self.path.write_text(json.dumps(row))
        self.config = Configuration.read(self.path)
        self.receipts = FileReceipts(self.config)
        self.closed = False
        self.addCleanup(self.close_receipts)
        self.worker = Worker(
            self.config,
            FixturePort(self.config),
            self.receipts,
            NetworkCollector(self.config.collector, self.config.collection),
        )
        self.settings = FileMonitorSettings(self.path, self.worker, self.receipts)

    def close_receipts(self) -> None:
        if not self.closed:
            self.receipts.__exit__(None, None, None)
            self.closed = True

    async def test_enable_and_change_scope_without_restart_preserves_history(
        self,
    ) -> None:
        saved = await self.settings.save(CHOICE)
        self.assertTrue(saved.pending)
        self.assertFalse(self.worker.config.collection.enabled)
        await self.settings.apply_pending()
        self.assertTrue(self.worker.config.collection.enabled)
        self.assertEqual(Configuration.read(self.path), self.worker.config)
        await self.settings.save(replace(CHOICE, targets=("192.168.0.13",)))
        await self.settings.apply_pending()
        self.assertEqual(self.worker.config.collection.targets, ("192.168.0.13",))
        self.assertEqual(json.loads(self.receipts.path.read_text())["version"], 2)
        # A scope edited behind the operator boundary still fails the restart fence.
        foreign = replace(
            self.worker.config,
            collection=replace(
                self.worker.config.collection, targets=("192.168.0.14",)
            ),
        )
        self.close_receipts()
        with self.assertRaisesRegex(ValueError, "Foreign journal"):
            FileReceipts(foreign)
        with FileReceipts(self.worker.config) as reopened:
            self.assertEqual(reopened.all(), ())

    async def test_completed_evidence_survives_scope_change_and_restart(self) -> None:
        await self.settings.save(CHOICE)
        await self.settings.apply_pending()
        origin = str(uuid4())
        scan = str(uuid4())
        fixture = configuration(self.root)
        evidence = await CountingCollector(fixture).collect(scan, origin, None, None)
        receipt = Receipt(
            scan,
            origin,
            self.config.request_topic,
            utc_now(),
            "done",
            evidence,
            str(uuid4()),
        )
        self.receipts.save(receipt)
        self.receipts.save_publication(Publication(origin, utc_now(), "published"))
        await self.settings.save(replace(CHOICE, targets=("192.168.0.13",)))
        await self.settings.apply_pending()
        self.assertEqual(self.receipts.all(), (receipt,))
        self.close_receipts()
        with FileReceipts(Configuration.read(self.path)) as reopened:
            self.assertEqual(reopened.all(), (receipt,))

    async def test_http_only_session_survives_reload_and_refuses_cross_origin_writes(
        self,
    ) -> None:
        client = TestClient(
            TestServer(application(self.worker, TOKEN, settings=self.settings))
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        connected = await client.post(
            "/api/session", headers={"Authorization": "Bearer " + TOKEN}, json={}
        )
        self.assertEqual(connected.status, 200)
        cookie = next(iter(connected.cookies.values()))
        self.assertTrue(cookie["httponly"])
        self.assertEqual(cookie["samesite"], "Strict")
        cookies = {cookie.key: cookie.value}
        response = await client.get("/api/status", cookies=cookies)
        self.assertEqual(response.status, 200)
        response = await client.post(
            "/api/monitoring",
            cookies=cookies,
            headers={"Origin": "https://foreign.invalid"},
            json={"enabled": True, "targets": ["192.168.0.12"], "ports": [443]},
        )
        self.assertEqual(response.status, 403)
        response = await client.post(
            "/api/session/logout",
            cookies=cookies,
            headers={"Origin": str(client.make_url("/")).rstrip("/")},
            json={},
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(next(iter(response.cookies.values()))["max-age"], "0")
        self.assertFalse(self.worker.config.collection.enabled)

    async def test_unsettled_publications_allow_saving_but_fence_activation(
        self,
    ) -> None:
        identity = str(uuid4())
        self.receipts.save_publication(Publication(identity, utc_now(), "pending"))
        journal = self.receipts.path.read_bytes()
        saved = await self.settings.save(CHOICE)
        self.assertTrue(saved.pending)
        self.assertTrue(saved.editable)
        self.assertIn("Settings saved", saved.detail)
        self.assertIn("scan request", saved.detail)
        await self.settings.apply_pending()
        self.assertEqual(self.receipts.path.read_bytes(), journal)
        self.receipts.save_publication(
            Publication(
                identity, self.receipts.publications()[0].occurred_at, "published"
            )
        )
        await self.settings.apply_pending()
        self.assertEqual(Configuration.read(self.path), self.config)
        self.assertEqual(self.worker.config, self.config)
        self.assertTrue(self.settings.pending_path.exists())

    async def test_disconnected_and_busy_save_survives_restart_and_merges_edits(
        self,
    ) -> None:
        self.worker.state = "attention_required"
        async with self.worker.lock, self.worker.request_lock:
            saved = await asyncio.wait_for(self.settings.save(CHOICE), 1)
        self.assertTrue(saved.editable)
        self.assertTrue(saved.pending)
        self.assertIn("stopped", saved.detail)
        self.assertEqual(self.settings.pending_path.stat().st_mode & 0o777, 0o600)
        await self.settings.save(replace(CHOICE, targets=("192.168.0.13",)))
        await self.settings.apply_pending()
        self.assertEqual(self.worker.config, self.config)
        self.close_receipts()
        with FileReceipts(self.config) as reopened:
            worker = Worker(
                self.config,
                FixturePort(self.config),
                reopened,
                NetworkCollector(self.config.collector, self.config.collection),
            )
            settings = FileMonitorSettings(self.path, worker, reopened)
            self.assertEqual(settings.view().targets, ("192.168.0.13",))
            await settings.apply_pending()
            self.assertFalse(settings.view().pending)
            self.assertFalse(settings.pending_path.exists())
            self.assertEqual(worker.config.collection.targets, ("192.168.0.13",))
            self.assertEqual(Configuration.read(self.path), worker.config)

    async def test_unknown_scan_keeps_its_original_scope_after_save(self) -> None:
        origin, scan = str(uuid4()), str(uuid4())
        evidence = await CountingCollector(configuration(self.root)).collect(
            scan, origin, None, None
        )
        receipt = Receipt(
            scan, origin, self.config.request_topic, utc_now(), "uploading", evidence
        )
        self.receipts.save(receipt)
        journal = self.receipts.path.read_bytes()
        await self.settings.save(CHOICE)
        await self.settings.apply_pending()
        self.assertEqual(self.receipts.path.read_bytes(), journal)
        self.assertEqual(self.worker.config, self.config)
        self.assertIn("unfinished", self.settings.view().detail)

    async def test_invalid_scope_does_not_overwrite_saved_configuration(self) -> None:
        await self.settings.save(CHOICE)
        pending = self.settings.pending_path.read_bytes()
        row = json.loads(self.path.read_text())
        row["project"] = "another-project"
        self.path.write_text(json.dumps(row))
        # The active file changed outside the dashboard, not the desired plan.
        # Activation must refuse rather than silently overwrite that external edit.
        with self.assertRaises(SettingsBusy):
            await self.settings.apply_pending()
        self.assertEqual(self.settings.pending_path.read_bytes(), pending)

    async def test_pending_pihole_and_monitoring_saves_preserve_each_other(
        self,
    ) -> None:
        secret = self.root / "fixture-pihole-password"
        secret.write_text("fixture-secret")
        secret.chmod(0o600)
        pihole = PiHolePlan.decode(
            {"origin": "https://pihole.example", "passwordFile": str(secret)}
        )
        await self.settings.save(CHOICE)
        await self.settings.save_pihole(pihole)
        self.assertEqual(self.settings.view().targets, CHOICE.targets)
        self.assertTrue(self.settings.view().pihole_configured)
        await self.settings.save(replace(CHOICE, targets=("192.168.0.13",)))
        self.assertEqual(
            Configuration.read(self.settings.pending_path).collection.pihole, pihole
        )
        self.assertEqual(self.worker.config, self.config)
        await self.settings.apply_pending()
        self.assertEqual(self.worker.config.collection.targets, ("192.168.0.13",))
        self.assertEqual(self.worker.config.collection.pihole, pihole)

    async def test_worker_applies_saved_settings_before_new_intake(self) -> None:
        await self.settings.save(CHOICE)
        stop = asyncio.Event()

        async def intake() -> None:
            self.assertEqual(self.worker.config.collection.targets, CHOICE.targets)
            self.assertFalse(self.settings.view().pending)
            stop.set()

        with patch.object(self.worker, "poll", side_effect=intake):
            await self.worker.run(stop, apply_settings=self.settings.apply_pending)

    async def test_activation_failure_keeps_durably_saved_settings(self) -> None:
        await self.settings.save(CHOICE)
        pending = self.settings.pending_path.read_bytes()
        with patch(
            "plowshare_privacy.monitor.replace_private",
            side_effect=OSError("fixture disk failure"),
        ):
            with self.assertRaises(OSError):
                await self.settings.apply_pending()
        self.assertEqual(self.settings.pending_path.read_bytes(), pending)
        self.assertTrue(self.settings.view().pending)
        self.assertEqual(Configuration.read(self.path), self.config)
        await self.settings.apply_pending()
        self.assertFalse(self.settings.view().pending)

    async def test_config_failure_keeps_old_plan_and_readable_journal(self) -> None:
        with patch(
            "plowshare_privacy.monitor.replace_private",
            side_effect=OSError("fixture interruption"),
        ):
            with self.assertRaises(OSError):
                await self.settings.save(CHOICE)
        self.assertEqual(Configuration.read(self.path), self.config)
        self.assertEqual(self.worker.config, self.config)
        self.close_receipts()
        with FileReceipts(self.config) as reopened:
            self.assertEqual(reopened.all(), ())

    async def test_crash_after_configuration_can_restart_new_scope(self) -> None:
        await self.settings.save(CHOICE)
        with patch.object(
            self.receipts,
            "complete_scope_change",
            side_effect=OSError("fixture interruption"),
        ):
            with self.assertRaises(OSError):
                await self.settings.apply_pending()
        saved = Configuration.read(self.path)
        self.close_receipts()
        with FileReceipts(saved) as reopened:
            self.assertEqual(reopened.all(), ())

    async def test_api_authentication_and_validation_precede_configuration_and_probes(
        self,
    ) -> None:
        discovery = LocalDeviceDiscovery(self.root)
        client = TestClient(
            TestServer(
                application(
                    self.worker, TOKEN, settings=self.settings, discovery=discovery
                )
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        with patch("plowshare_privacy.discovery.discover") as scan:
            for route in ("/api/monitoring", "/api/discovery"):
                response = await client.post(route, json={})
                self.assertEqual(response.status, 401)
            headers = {"Authorization": "Bearer " + TOKEN}
            response = await client.post(
                "/api/discovery",
                headers=headers,
                json={"network": "8.8.8.0/24", "ports": [443]},
            )
            self.assertEqual(response.status, 400)
            response = await client.post(
                "/api/monitoring",
                headers=headers,
                json={"enabled": True, "targets": ["8.8.8.8"], "ports": [443]},
            )
            self.assertEqual(response.status, 400)
            response = await client.post(
                "/api/monitoring",
                headers=headers,
                json={"enabled": True, "targets": ["192.168.0.12"], "ports": [443]},
            )
            self.assertEqual(response.status, 200)
            scan.assert_not_called()
        self.assertTrue((await self.settings.save(CHOICE)).pending)
        await self.settings.apply_pending()
        self.assertTrue(self.worker.config.collection.enabled)

    def test_scope_validation_refuses_extra_fields_and_unbounded_probes(self) -> None:
        for value in (
            {"enabled": 1, "targets": [], "ports": []},
            {"enabled": True, "targets": [], "ports": [443]},
            {"enabled": True, "targets": ["192.168.0.12"], "ports": [443, 443]},
            {
                "enabled": False,
                "targets": [],
                "ports": [],
                "origin": "https://foreign.invalid",
            },
        ):
            with self.assertRaises(ValueError):
                MonitorChoice.decode(value)

    async def test_device_labels_are_saved_without_exposing_or_removing_pihole_secret(
        self,
    ) -> None:
        row = json.loads(self.path.read_text())
        secret = self.root / "pihole-secret"
        secret.write_text("fixture-app-password")
        secret.chmod(0o600)
        row["collection"]["pihole"] = {
            "origin": "https://pihole.example",
            "passwordFile": str(secret),
        }
        configured = Configuration.decode(row, self.path)
        self.receipts.prepare_scope_change(configured.collection)
        self.path.write_text(json.dumps(row))
        self.receipts.complete_scope_change(configured.collection)
        self.worker.config = configured
        self.worker.collector = configured_collector(
            configured.collector, configured.collection
        )
        labeled = MonitorChoice.decode(
            {
                "enabled": True,
                "targets": ["192.168.0.12"],
                "ports": [443],
                "labels": {"192.168.0.12": "Living room TV"},
            }
        )
        await self.settings.save(labeled)
        view = self.settings.view()
        self.assertTrue(view.pihole_configured)
        self.assertEqual(view.labels, (DeviceLabel("192.168.0.12", "Living room TV"),))
        self.assertNotIn("fixture-app-password", str(view))
        self.assertNotIn(str(secret), str(view))
        await self.settings.save(
            CHOICE
        )  # An older UI omits labels; existing labels are retained.
        self.assertEqual(self.settings.view().labels, view.labels)
        await self.settings.save(replace(CHOICE, targets=("192.168.0.13",)))
        self.assertEqual(self.settings.view().labels, ())
        await self.settings.apply_pending()
        self.assertEqual(
            Configuration.read(self.path).collection.pihole,
            self.worker.config.collection.pihole,
        )

    def test_device_labels_require_selected_addresses_and_bounded_text(self) -> None:
        for labels in (
            {"192.168.0.13": "foreign"},
            {"192.168.0.12": "x" * 129},
            {"192.168.0.12": ""},
        ):
            with self.assertRaises(ValueError):
                MonitorChoice.decode(
                    {
                        "enabled": True,
                        "targets": ["192.168.0.12"],
                        "ports": [443],
                        "labels": labels,
                    }
                )
