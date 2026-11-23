"""Actual HA diagnostics hook with payload-free observations and no probe effects."""

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from types import MappingProxyType
from unittest.mock import AsyncMock

import support
from custom_components.plowshare._vendor.plowshare import (
    Delivery,
    Refusal,
    TransportError,
)
from homeassistant.config_entries import ConfigEntry, ConfigEntryState
from homeassistant.core import HomeAssistant
from test_agent import KEY, SETTINGS, FakeClient
from test_entry_setup import DATA

from custom_components.plowshare import diagnostics
from custom_components.plowshare.const import INTEGRATION_VERSION
from custom_components.plowshare.models import Session
from custom_components.plowshare.requests import ConversationRequest
from custom_components.plowshare.runtime import OWNERS, EntryRuntime, owners

SECRET = "private-sentinel-do-not-export"


def config_entry(data=None, state=ConfigEntryState.NOT_LOADED):
    return ConfigEntry(
        domain="plowshare",
        title=SECRET,
        data=DATA if data is None else data,
        version=1,
        minor_version=1,
        source="user",
        unique_id=SECRET,
        options={"private_option": SECRET},
        discovery_keys=MappingProxyType({}),
        subentries_data=None,
        state=state,
    )


class DiagnosticsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    def gateway(self, sessions=None, **settings):
        from custom_components.plowshare.agent import SdkConversationAgent

        client = FakeClient()
        save = AsyncMock()
        connect = AsyncMock(return_value=client)
        gateway = SdkConversationAgent(
            replace(SETTINGS, **settings),
            {} if sessions is None else sessions,
            save,
            connect,
            profile_id="entry:conversation",
        )
        return gateway, client, save, connect

    async def test_never_started_invalid_config_is_safe_and_does_not_create_owner(self):
        entry = config_entry({**DATA, "control_enabled": SECRET, "prompt": SECRET})
        report = await diagnostics.async_get_config_entry_diagnostics(self.hass, entry)
        self.assertNotIn(OWNERS, self.hass.data)
        self.assertFalse(report["configuration"]["valid"])
        self.assertEqual(report["configuration"]["control_enabled"], "invalid")
        self.assertEqual(report["runtime"], {"owner": "not_retained"})
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertFalse(Path(self.directory.name, ".storage").exists())

    async def test_failed_setup_and_unloaded_entry_need_no_network_or_journal(self):
        for state in (ConfigEntryState.SETUP_ERROR, ConfigEntryState.NOT_LOADED):
            entry = config_entry(state=state)
            report = await diagnostics.async_get_config_entry_diagnostics(
                self.hass, entry
            )
            self.assertEqual(report["entry_state"], state.value)
            self.assertEqual(report["runtime"]["owner"], "not_retained")
            self.assertEqual(
                report["outbound_read_provider"], "not_observed_separate_process"
            )

    async def test_retained_cleanup_and_shared_counts_without_sensitive_data(self):
        sessions = {
            SECRET + str(i): Session(
                conversation=SECRET,
                job=SECRET,
                phase=phase,
                correlation=SECRET,
                binding=SECRET,
            )
            for i, phase in enumerate(
                ("running", "waiting", "sending", "uncertain", "preparing", "idle")
            )
        }
        gateway, client, save, connect = self.gateway(sessions)
        journal = AsyncMock()
        runtime = EntryRuntime(journal)
        runtime.attach(gateway, gateway)
        runtime.finish_setup()
        # The registry is the retained owner even if HA cleared runtime_data.
        entry = config_entry(
            {
                **DATA,
                "url": "https://" + SECRET + ".invalid",
                "api_key": SECRET,
                "project": SECRET,
                "read_only_agent": SECRET,
                "task_agent": SECRET,
                "prompt": SECRET,
            }
        )
        owners(self.hass).entries[entry.entry_id] = runtime
        self.assertTrue(await runtime.async_close())
        before = [(key, vars(session).copy()) for key, session in sessions.items()]
        report = await diagnostics.async_get_config_entry_diagnostics(self.hass, entry)
        local = report["runtime"]
        self.assertTrue(local["closing"])
        self.assertEqual(
            local["shared_correlation_store"],
            {"retained": 6, "pending": 2, "uncertain": 2, "preparing": 1},
        )
        self.assertIsNone(local["conversation"]["communication"]["last_connection_at"])
        self.assertEqual(before, [(key, vars(s).copy()) for key, s in sessions.items()])
        self.assertNotIn(SECRET, json.dumps(report))
        connect.assert_not_awaited()
        save.assert_not_awaited()
        self.assertEqual(client.requests, [])
        # Runtime cleanup itself ran before the snapshot; downloading never adds I/O.
        journal.async_close.assert_awaited_once()

    async def test_successful_communication_is_historical_not_current_health(self):
        gateway, client, save, connect = self.gateway()
        result = await gateway.converse(ConversationRequest(KEY, SECRET))
        self.assertEqual(result.status, "completed")
        snapshot = gateway.diagnostics_snapshot()
        self.assertIsNotNone(snapshot.communication.last_connection_at)
        self.assertIsNotNone(snapshot.communication.last_successful_request_at)
        self.assertEqual(snapshot.communication.last_result_status, "completed")
        self.assertIsNone(snapshot.communication.last_failure_category)
        report = diagnostics._gateway(snapshot)
        self.assertEqual(
            report["current_connection"], "not_monitored_per_request_connections"
        )
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertTrue(client.closed)
        self.assertEqual(snapshot.active_owners, 0)

    async def test_unknown_submission_keeps_uncertainty_without_replaying_for_report(
        self,
    ):
        gateway, client, save, connect = self.gateway()
        client.error = TransportError(Delivery.UNKNOWN, SECRET)
        result = await gateway.converse(ConversationRequest(KEY, SECRET))
        self.assertEqual(result.status, "uncertain")
        calls = len(client.requests)
        first = gateway.diagnostics_snapshot()
        second = gateway.diagnostics_snapshot()
        self.assertEqual(first, second)
        self.assertEqual(first.shared_correlation_store.uncertain, 1)
        self.assertEqual(first.communication.last_failure_category, "transport")
        self.assertEqual(first.communication.last_result_status, "uncertain")
        self.assertEqual(calls, len(client.requests))
        self.assertNotIn(SECRET, json.dumps(diagnostics._gateway(first)))

    async def test_pending_is_retained_job_not_device_or_provider_health(self):
        gateway, client, save, connect = self.gateway(response_timeout=0.02)
        client.status = replace(client.status, state="RUNNING", outcome=None)
        result = await gateway.converse(ConversationRequest(KEY, SECRET))
        self.assertEqual(result.status, "pending")
        snapshot = gateway.diagnostics_snapshot()
        self.assertEqual(snapshot.shared_correlation_store.pending, 1)
        self.assertEqual(snapshot.communication.last_result_status, "pending")
        self.assertIsNotNone(snapshot.communication.last_successful_request_at)

    async def test_connection_failure_category_does_not_disclose_exception(self):
        cases = (
            (TransportError(Delivery.NOT_SUBMITTED, SECRET), "transport"),
            (TimeoutError(SECRET), "timeout"),
            (OSError(SECRET), "io"),
            (Refusal("FORBIDDEN", SECRET), "refusal"),
        )
        for error, category in cases:
            with self.subTest(category=category):
                gateway, _, _, connect = self.gateway()
                connect.side_effect = error
                await gateway.converse(ConversationRequest(KEY, SECRET))
                snapshot = gateway.diagnostics_snapshot()
                self.assertEqual(snapshot.communication.last_failure_category, category)
                self.assertIsNone(snapshot.communication.last_connection_at)
                self.assertIsNone(snapshot.communication.last_successful_request_at)
                self.assertNotIn(SECRET, json.dumps(diagnostics._gateway(snapshot)))

    async def test_storage_failure_preserves_fence_and_reports_category_only(self):
        gateway, _, save, _ = self.gateway()
        save.side_effect = OSError(SECRET)
        result = await gateway.converse(ConversationRequest(KEY, SECRET))
        self.assertEqual(result.status, "uncertain")
        self.assertEqual(
            gateway.diagnostics_snapshot().communication.last_failure_category,
            "storage",
        )
        self.assertEqual(
            gateway.diagnostics_snapshot().shared_correlation_store.uncertain, 1
        )

    def test_packaged_version_matches_diagnostics_version(self):
        manifest = json.loads((support.COMPONENT / "manifest.json").read_text())
        self.assertEqual(manifest["version"], INTEGRATION_VERSION)
        self.assertTrue((support.COMPONENT / "diagnostics.py").is_file())
