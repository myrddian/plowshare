"""Enabled HA reconfiguration keeps routes and receipts explicit."""

import tempfile
import unittest
from pathlib import Path
from types import MappingProxyType
from unittest.mock import patch

import support  # noqa: F401
from homeassistant.config_entries import ConfigEntries, ConfigEntry
from homeassistant.core import HomeAssistant

from custom_components.plowshare.config_flow import PlowshareConfigFlow

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "read_only_agent": "house_reader",
}


class Probe:
    def __init__(self, settings, sessions, save):
        self.settings = settings

    async def validate(self, required_agents=()):
        if required_agents != ("house_reader",):
            raise AssertionError("Unexpected configured route")

    async def async_close(self):
        return True


class InstallLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config_entries = ConfigEntries(self.hass, {})
        self.entry = ConfigEntry(
            domain="plowshare",
            title="House",
            data=DATA,
            version=1,
            minor_version=1,
            source="user",
            unique_id="installed-house",
            options={},
            discovery_keys=MappingProxyType({}),
            subentries_data=None,
        )
        # Register without triggering a loader/reload or unrelated integration.
        self.hass.config_entries._entries[self.entry.entry_id] = self.entry
        self.receipt = (
            Path(self.directory.name) / ".storage" / f"plowshare.{self.entry.entry_id}"
        )
        self.receipt.parent.mkdir()
        self.receipt.write_bytes(b"retained pending and uncertain work")

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    def flow(self):
        flow = PlowshareConfigFlow()
        flow.hass = self.hass
        flow.context = {"source": "reconfigure", "entry_id": self.entry.entry_id}
        return flow

    async def test_credential_rotation_updates_actual_ha_entry_without_receipt_changes(
        self,
    ):
        rotated = {**DATA, "api_key": "rotated-fixture-token", "response_timeout": 45}
        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent", Probe
            ),
            patch.object(self.hass.config_entries, "_async_schedule_save"),
            patch.object(self.hass.config_entries, "async_schedule_reload") as reload,
        ):
            result = await self.flow().async_step_reconfigure(rotated)
        self.assertEqual(result["type"], "abort")
        self.assertEqual(result["reason"], "reconfigure_successful")
        self.assertEqual(dict(self.entry.data), rotated)
        reload.assert_called_once_with(self.entry.entry_id)
        self.assertEqual(
            self.receipt.read_bytes(), b"retained pending and uncertain work"
        )

    async def test_route_change_is_rejected_before_probe_reload_or_receipt_access(self):
        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent"
            ) as sdk,
            patch.object(self.hass.config_entries, "async_schedule_reload") as reload,
        ):
            result = await self.flow().async_step_reconfigure(
                {**DATA, "project": "other-house"}
            )
        self.assertEqual(result["type"], "form")
        self.assertEqual(result["errors"], {"base": "invalid_config"})
        self.assertEqual(dict(self.entry.data), DATA)
        sdk.assert_not_called()
        reload.assert_not_called()
        self.assertEqual(
            self.receipt.read_bytes(), b"retained pending and uncertain work"
        )
