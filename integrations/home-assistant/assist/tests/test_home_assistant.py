"""Exercise the real installed HA conversation/chat-log and config-flow APIs."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import MappingProxyType
from unittest.mock import AsyncMock, Mock, patch

import support
from homeassistant.components.conversation import ConversationInput
from homeassistant.config_entries import ConfigEntries, ConfigEntry
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import ConfigEntryError
from homeassistant.helpers.intent import IntentResponseType

from custom_components.plowshare import async_setup_entry, async_unload_entry
from custom_components.plowshare.agent import Answer
from custom_components.plowshare.config_flow import PlowshareConfigFlow
from custom_components.plowshare.conversation import PlowshareConversationEntity

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "agent": "house_coordinator",
}


def config_entry():
    return ConfigEntry(
        domain="plowshare",
        title="House",
        data=DATA,
        version=1,
        minor_version=1,
        source="user",
        unique_id="house",
        options={},
        discovery_keys=MappingProxyType({}),
        subentries_data=None,
    )


class HomeAssistantTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config.components.add("conversation")

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    async def test_real_chat_session_preserves_id_and_speech(self):
        entry = config_entry()
        port = AsyncMock()
        port.ask.return_value = Answer("Would you like the outside temperature?")
        entry.runtime_data = port
        entity = PlowshareConversationEntity(entry)
        entity.hass = self.hass
        request = ConversationInput(
            text="How warm is it?",
            context=Context(user_id="alice"),
            conversation_id=None,
            device_id="device",
            satellite_id=None,
            language="en",
            agent_id="conversation.house",
        )
        result = await entity.async_process(request)
        self.assertTrue(result.continue_conversation)
        self.assertEqual(
            result.response.speech["plain"]["speech"], port.ask.return_value.text
        )
        self.assertIsNotNone(result.conversation_id)
        first_key = port.ask.call_args.args[0]
        request.conversation_id = result.conversation_id
        request.text = "Yes please"
        follow_up = await entity.async_process(request)
        self.assertEqual(follow_up.conversation_id, result.conversation_id)
        self.assertEqual(first_key, port.ask.call_args.args[0])
        self.assertEqual(port.ask.call_args.args[1], "Yes please")

    async def test_unattributed_voice_is_denied_without_opt_in(self):
        entry = config_entry()
        entry.runtime_data = AsyncMock()
        entity = PlowshareConversationEntity(entry)
        entity.hass = self.hass
        result = await entity.async_process(
            ConversationInput(
                text="Open the door",
                context=Context(),
                conversation_id=None,
                device_id="device",
                satellite_id="satellite",
                language="en",
                agent_id="conversation.house",
            )
        )
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        entry.runtime_data.ask.assert_not_awaited()

    async def test_setup_refuses_before_storage_sdk_or_platform_work(self):
        entry = config_entry()
        retained = (
            Path(self.directory.name) / ".storage" / f"plowshare.{entry.entry_id}"
        )
        retained.parent.mkdir()
        retained.write_bytes(b'{"pending": "retain even unreadable correlation"}')
        original = retained.read_bytes()
        with (
            patch("custom_components.plowshare.Store") as store,
            patch("custom_components.plowshare.SdkConversationAgent") as sdk,
            patch.object(
                ConfigEntries,
                "async_forward_entry_setups",
                new_callable=AsyncMock,
            ) as forward,
        ):
            with self.assertRaisesRegex(ConfigEntryError, "Assist is gated"):
                await async_setup_entry(self.hass, entry)
        store.assert_not_called()
        sdk.assert_not_called()
        forward.assert_not_awaited()
        self.assertEqual(retained.read_bytes(), original)

    async def test_initial_and_reconfigure_flows_abort_without_sdk_work(self):
        flow = PlowshareConfigFlow()
        flow.hass = self.hass
        flow.context = {}
        with (
            patch(
                "custom_components.plowshare.config_flow.SdkConversationAgent"
            ) as sdk,
            patch.object(flow, "_get_reconfigure_entry", new=Mock()) as lookup,
        ):
            for user_input in (None, DATA, {"url": "invalid"}):
                for step in (flow.async_step_user, flow.async_step_reconfigure):
                    result = await step(user_input)
                    self.assertEqual(result["type"], "abort")
                    self.assertEqual(result["reason"], "prompt_fidelity_unavailable")
        sdk.assert_not_called()
        lookup.assert_not_called()

    async def test_unload_keeps_existing_platform_cleanup(self):
        entry = config_entry()
        unload = AsyncMock(return_value=True)
        with patch.object(
            self.hass, "config_entries", new=Mock(async_unload_platforms=unload)
        ):
            self.assertTrue(await async_unload_entry(self.hass, entry))
        unload.assert_awaited_once()

    def test_packaging_refuses_without_creating_or_replacing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "absent" / "assist.zip"
            command = [
                sys.executable,
                str(support.ROOT / "scripts/package.py"),
                "--output",
                str(output),
            ]
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn("Assist is gated", result.stderr)
            self.assertFalse(output.parent.exists())
            output.parent.mkdir()
            output.write_bytes(b"retained previous artifact")
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(output.read_bytes(), b"retained previous artifact")

    def test_fixture_sdk_is_unchanged(self):
        repo = support.ROOT.parents[2]
        source = repo / "sdk/python/src/plowshare"
        bundled = support.COMPONENT / "_vendor/plowshare"
        for original in source.rglob("*"):
            if original.is_file() and "__pycache__" not in original.parts:
                self.assertEqual(
                    original.read_bytes(),
                    (bundled / original.relative_to(source)).read_bytes(),
                )
