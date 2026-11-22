"""Actual HA entry composition with truthful local persistence and owned cleanup."""

import asyncio
import tempfile
import threading
import unittest
from pathlib import Path
from types import MappingProxyType, SimpleNamespace
from unittest.mock import AsyncMock, call, patch

import support  # noqa: F401
from homeassistant.config_entries import ConfigEntries, ConfigEntry
from homeassistant.const import EVENT_HOMEASSISTANT_STOP, Platform
from homeassistant.core import CoreState, HomeAssistant
from homeassistant.exceptions import ConfigEntryError, ConfigEntryNotReady

from custom_components.plowshare import async_setup_entry, async_unload_entry
from custom_components.plowshare.agent import ProfileRuntime
from custom_components.plowshare.runtime import EntryRuntime, owners
from custom_components.plowshare.storage import JournalUnavailable

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "read_only_agent": "house_reader",
    "task_agent": "task_writer",
    "conversation_enabled": True,
    "ai_task_enabled": True,
}


class GatewayFixture:
    def __init__(self, settings, sessions, save, *, profile_id):
        self.settings = settings
        self.sessions = sessions
        self.save = save
        self.profile_id = profile_id
        self.close_result = True
        self.async_close = AsyncMock(side_effect=lambda: self.close_result)
        self.generate = AsyncMock()
        self.converse = AsyncMock()
        self.ask = AsyncMock()
        self.validate = AsyncMock()


def config_entry(data=None):
    return ConfigEntry(
        domain="plowshare",
        title="House",
        data=DATA if data is None else data,
        version=1,
        minor_version=1,
        source="user",
        unique_id="entry-fixture",
        options={},
        discovery_keys=MappingProxyType({}),
        subentries_data=None,
    )


class EntrySetupTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config_entries = SimpleNamespace(
            async_forward_entry_setups=AsyncMock(),
            async_unload_platforms=AsyncMock(return_value=True),
        )
        self.releases = []
        self.created = []

        def gateway(*args, **kwargs):
            result = GatewayFixture(*args, **kwargs)
            self.created.append(result)
            return result

        self.gateway_factory = self.enterContext(
            patch(
                "custom_components.plowshare.SdkConversationAgent", side_effect=gateway
            )
        )

    async def asyncTearDown(self):
        for release in self.releases:
            release.set()
        self.hass.config_entries.async_unload_platforms.side_effect = None
        self.hass.config_entries.async_unload_platforms.return_value = True
        for runtime in tuple(owners(self.hass).entries.values()):
            runtime.finish_setup()
            for gateway in self.created:
                gateway.close_result = True
            await runtime.async_close()
        self.hass.state = CoreState.not_running
        await self.hass.async_stop()
        self.directory.cleanup()

    async def test_setup_composes_independent_profiles_over_one_shared_journal(self):
        entry = config_entry()
        self.assertTrue(await async_setup_entry(self.hass, entry))
        runtime = entry.runtime_data
        self.assertIsInstance(runtime, ProfileRuntime)
        self.assertEqual(len(self.created), 2)
        conversation, task = self.created
        self.assertEqual(conversation.settings.agent, "house_reader")
        self.assertEqual(task.settings.agent, "task_writer")
        self.assertTrue(conversation.profile_id.endswith(":conversation:read_only"))
        self.assertEqual(task.profile_id, entry.entry_id + ":task")
        self.assertIs(conversation.sessions, task.sessions)
        self.assertIs(conversation.save, task.save)
        self.assertFalse(
            Path(
                self.directory.name, ".storage", "plowshare." + entry.entry_id
            ).exists()
        )
        self.hass.config_entries.async_forward_entry_setups.assert_has_awaits(
            [call(entry, [Platform.CONVERSATION]), call(entry, [Platform.AI_TASK])],
            any_order=True,
        )
        self.assertEqual(
            self.hass.config_entries.async_forward_entry_setups.await_count, 2
        )
        self.assertTrue(await async_unload_entry(self.hass, entry))
        self.assertNotIn(entry.entry_id, owners(self.hass).entries)

    async def test_task_only_configuration_never_composes_conversation_agent(self):
        entry = config_entry({**DATA, "conversation_enabled": False})
        await async_setup_entry(self.hass, entry)
        self.assertIsNone(entry.runtime_data.conversation)
        self.assertEqual(len(self.created), 1)
        self.assertEqual(self.created[0].settings.agent, "task_writer")
        self.hass.config_entries.async_forward_entry_setups.assert_awaited_once_with(
            entry, [Platform.AI_TASK]
        )

    async def test_corrupt_storage_stays_unchanged_and_no_gateway_is_created(self):
        entry = config_entry()
        path = Path(self.directory.name, ".storage", "plowshare." + entry.entry_id)
        path.parent.mkdir()
        source = b'{"retained": "unreadable receipt"}'
        path.write_bytes(source)
        with self.assertRaises(ConfigEntryNotReady):
            await async_setup_entry(self.hass, entry)
        self.assertEqual(path.read_bytes(), source)
        self.gateway_factory.assert_not_called()
        self.hass.config_entries.async_forward_entry_setups.assert_not_awaited()

    async def test_invalid_profile_has_no_storage_or_sdk_work(self):
        entry = config_entry({**DATA, "task_agent": ""})
        with patch("custom_components.plowshare.CorrelationJournal") as journal:
            with self.assertRaises(ConfigEntryError):
                await async_setup_entry(self.hass, entry)
        journal.assert_not_called()
        self.gateway_factory.assert_not_called()

    async def test_all_stopping_states_refuse_before_storage_or_sdk_work(self):
        for state in (CoreState.stopping, CoreState.final_write, CoreState.stopped):
            with self.subTest(state=state):
                self.hass.state = state
                with patch("custom_components.plowshare.CorrelationJournal") as journal:
                    with self.assertRaises(ConfigEntryNotReady):
                        await async_setup_entry(self.hass, config_entry())
                journal.assert_not_called()
        self.gateway_factory.assert_not_called()

    async def test_stop_during_load_fences_setup_and_retains_owner_until_read_settles(
        self,
    ):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)

        def read(_path, _key):
            entered.set()
            if not release.wait(5):
                raise OSError("fixture load barrier expired")
            return {}

        entry = config_entry()
        with patch("custom_components.plowshare.storage._read", side_effect=read):
            pending = asyncio.create_task(async_setup_entry(self.hass, entry))
            self.assertTrue(await asyncio.to_thread(entered.wait, 1))
            retained = owners(self.hass).entries[entry.entry_id]
            self.hass.state = CoreState.stopping
            self.hass.bus.async_fire(EVENT_HOMEASSISTANT_STOP)
            await asyncio.sleep(0)
            self.assertTrue(retained.closing)
            self.assertIs(owners(self.hass).entries[entry.entry_id], retained)
            release.set()
            with self.assertRaises(ConfigEntryNotReady):
                await pending
        self.gateway_factory.assert_not_called()
        self.hass.config_entries.async_forward_entry_setups.assert_not_awaited()

    async def test_stop_during_forwarding_hides_new_profiles_and_refuses_success(self):
        entered, release = asyncio.Event(), asyncio.Event()
        self.releases.append(release)

        async def forward(_entry, _platforms):
            entered.set()
            await release.wait()

        self.hass.config_entries.async_forward_entry_setups.side_effect = forward
        entry = config_entry()
        pending = asyncio.create_task(async_setup_entry(self.hass, entry))
        await asyncio.wait_for(entered.wait(), 1)
        retained = owners(self.hass).entries[entry.entry_id]
        self.hass.state = CoreState.stopping
        self.hass.bus.async_fire(EVENT_HOMEASSISTANT_STOP)
        await asyncio.sleep(0)
        self.assertIsNone(retained.conversation)
        self.assertIsNone(retained.task)
        self.assertIs(owners(self.hass).entries[entry.entry_id], retained)
        release.set()
        with self.assertRaises(ConfigEntryNotReady):
            await pending
        for gateway in self.created:
            gateway.generate.assert_not_awaited()
            gateway.converse.assert_not_awaited()
        self.hass.config_entries.async_unload_platforms.assert_has_awaits(
            [call(entry, [Platform.CONVERSATION]), call(entry, [Platform.AI_TASK])],
            any_order=True,
        )

    async def test_failed_cleanup_blocks_unload_and_replacement_on_setup(self):
        entry = config_entry()
        await async_setup_entry(self.hass, entry)
        runtime = entry.runtime_data
        self.created[0].close_result = False
        self.assertFalse(await async_unload_entry(self.hass, entry))
        self.hass.config_entries.async_unload_platforms.assert_not_awaited()
        self.assertIs(owners(self.hass).entries[entry.entry_id], runtime)
        with patch("custom_components.plowshare.CorrelationJournal") as journal:
            with self.assertRaises(ConfigEntryNotReady):
                await async_setup_entry(self.hass, entry)
        journal.assert_not_called()
        self.assertEqual(len(self.created), 2)
        self.assertIs(entry.runtime_data, runtime)
        self.created[0].close_result = True
        self.assertTrue(await async_unload_entry(self.hass, entry))

    async def test_late_failed_setup_cannot_remove_a_replacement_owner(self):
        entered, release = asyncio.Event(), asyncio.Event()
        journal = SimpleNamespace(async_close=AsyncMock(return_value=True))

        async def load():
            entered.set()
            await release.wait()
            raise JournalUnavailable("fixture load failure")

        journal.async_load = load
        replacement = EntryRuntime(
            SimpleNamespace(async_close=AsyncMock(return_value=True))
        )
        replacement.finish_setup()
        entry = config_entry()
        with patch(
            "custom_components.plowshare.CorrelationJournal", return_value=journal
        ):
            pending = asyncio.create_task(async_setup_entry(self.hass, entry))
            await asyncio.wait_for(entered.wait(), 1)
            owners(self.hass).entries[entry.entry_id] = replacement
            entry.runtime_data = replacement
            release.set()
            with self.assertRaises(ConfigEntryNotReady):
                await pending
        self.assertIs(owners(self.hass).entries[entry.entry_id], replacement)
        self.assertIs(entry.runtime_data, replacement)

    async def test_unload_uses_original_platforms_even_after_configuration_changes(
        self,
    ):
        entry = config_entry()
        await async_setup_entry(self.hass, entry)
        # A loader/administrator can update data before unload. Cleanup must not
        # forget a platform merely because the new configuration disables it.
        manager = ConfigEntries(self.hass, {})
        # Register the fixture without starting another integration. The actual
        # public updater enforces HA's read-only ConfigEntry.data contract.
        manager._entries[entry.entry_id] = entry
        with patch.object(manager, "_async_schedule_save"):
            manager.async_update_entry(
                entry, data={**DATA, "conversation_enabled": False}
            )
        await async_unload_entry(self.hass, entry)
        self.hass.config_entries.async_unload_platforms.assert_has_awaits(
            [call(entry, [Platform.CONVERSATION]), call(entry, [Platform.AI_TASK])],
            any_order=True,
        )

    async def test_partial_platform_unload_retry_never_repeats_successful_sibling(self):
        entry = config_entry()
        await async_setup_entry(self.hass, entry)
        attempts = {Platform.CONVERSATION: 0, Platform.AI_TASK: 0}

        async def unload(_entry, platforms):
            platform = platforms[0]
            attempts[platform] += 1
            return platform is Platform.CONVERSATION or attempts[platform] > 1

        self.hass.config_entries.async_unload_platforms.side_effect = unload
        self.assertFalse(await async_unload_entry(self.hass, entry))
        self.assertIn(entry.entry_id, owners(self.hass).entries)
        self.assertTrue(await async_unload_entry(self.hass, entry))
        self.assertEqual(attempts, {Platform.CONVERSATION: 1, Platform.AI_TASK: 2})
        self.assertNotIn(entry.entry_id, owners(self.hass).entries)

    async def test_failed_forward_waits_for_live_sibling_then_cleans_platforms(self):
        started, release = asyncio.Event(), asyncio.Event()
        self.releases.append(release)

        async def forward(_entry, platforms):
            if platforms == [Platform.AI_TASK]:
                raise RuntimeError("fixture platform failed")
            started.set()
            await release.wait()

        self.hass.config_entries.async_forward_entry_setups.side_effect = forward
        entry = config_entry()
        pending = asyncio.create_task(async_setup_entry(self.hass, entry))
        await asyncio.wait_for(started.wait(), 1)
        await asyncio.sleep(0)
        self.assertFalse(pending.done())
        self.hass.config_entries.async_unload_platforms.assert_not_awaited()
        release.set()
        with self.assertRaises(ConfigEntryNotReady):
            await pending
        self.assertNotIn(entry.entry_id, owners(self.hass).entries)
        self.hass.config_entries.async_unload_platforms.assert_has_awaits(
            [call(entry, [Platform.CONVERSATION]), call(entry, [Platform.AI_TASK])],
            any_order=True,
        )

    async def test_cancelled_setup_retains_forward_owner_until_late_child_and_cleanup(
        self,
    ):
        started, release, cancelled = asyncio.Event(), asyncio.Event(), asyncio.Event()
        self.releases.append(release)

        async def forward(_entry, _platforms):
            started.set()
            try:
                await release.wait()
            except asyncio.CancelledError:
                cancelled.set()
                await release.wait()

        self.hass.config_entries.async_forward_entry_setups.side_effect = forward
        entry = config_entry()
        pending = asyncio.create_task(async_setup_entry(self.hass, entry))
        await asyncio.wait_for(started.wait(), 1)
        retained = owners(self.hass).entries[entry.entry_id]
        with patch("custom_components.plowshare.runtime.CLEANUP_TIMEOUT", 0.02):
            pending.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await pending
            self.assertFalse(await retained.async_close())
        self.assertFalse(cancelled.is_set())
        self.assertIs(owners(self.hass).entries[entry.entry_id], retained)
        self.hass.config_entries.async_unload_platforms.assert_not_awaited()
        release.set()
        await asyncio.gather(*retained._forwards.values())
        self.assertTrue(await async_unload_entry(self.hass, entry))
        self.assertNotIn(entry.entry_id, owners(self.hass).entries)

    async def test_failed_setup_cleanup_blocks_replacement_until_unload_succeeds(self):
        async def failed(_entry, platforms):
            if platforms == [Platform.AI_TASK]:
                raise RuntimeError("fixture forward failure")

        self.hass.config_entries.async_forward_entry_setups.side_effect = failed
        self.hass.config_entries.async_unload_platforms.return_value = False
        entry = config_entry()
        with self.assertRaises(ConfigEntryNotReady):
            await async_setup_entry(self.hass, entry)
        retained = owners(self.hass).entries[entry.entry_id]
        with patch("custom_components.plowshare.CorrelationJournal") as journal:
            with self.assertRaises(ConfigEntryNotReady):
                await async_setup_entry(self.hass, entry)
        journal.assert_not_called()
        self.assertIs(owners(self.hass).entries[entry.entry_id], retained)
        self.hass.config_entries.async_unload_platforms.return_value = True
        self.assertTrue(await async_unload_entry(self.hass, entry))
