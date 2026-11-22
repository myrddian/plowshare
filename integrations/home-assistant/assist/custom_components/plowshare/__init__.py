"""Compose independently configured HA profiles over the existing public SDK."""

import re
from pathlib import Path

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EVENT_HOMEASSISTANT_STOP, Platform
from homeassistant.core import CoreState, Event, HomeAssistant, callback
from homeassistant.exceptions import ConfigEntryError, ConfigEntryNotReady
from homeassistant.helpers.storage import STORAGE_DIR

from .agent import ProfileRuntime, SdkConversationAgent
from .config import configured_profiles
from .const import CONF_AI_TASK_ENABLED, CONF_CONVERSATION_ENABLED, DOMAIN
from .runtime import EntryRuntime, PlatformSetupIncomplete, owners
from .storage import CorrelationJournal, JournalUnavailable

type PlowshareConfigEntry = ConfigEntry[ProfileRuntime]
_STOPPING = {CoreState.stopping, CoreState.final_write, CoreState.stopped}


class _HAPlatforms:
    """Compose the runtime's narrow resource contract from supported HA APIs."""

    def __init__(self, hass: HomeAssistant, entry: PlowshareConfigEntry) -> None:
        self.hass = hass
        self.entry = entry

    async def async_forward(self, platform: Platform) -> None:
        await self.hass.config_entries.async_forward_entry_setups(
            self.entry, [platform]
        )

    async def async_unload(self, platform: Platform) -> bool:
        return await self.hass.config_entries.async_unload_platforms(
            self.entry, [platform]
        )


async def async_setup_entry(hass: HomeAssistant, entry: PlowshareConfigEntry) -> bool:
    """Retain every owner before forwarding either entity platform.

    Setup owns the journal and physical gateway cleanup before exposing either
    platform. Invalid profiles fail before storage or SDK work begins.
    """
    if hass.state in _STOPPING:
        raise ConfigEntryNotReady("Home Assistant is stopping")
    try:
        profiles = configured_profiles(entry.data)
    except ValueError as error:
        raise ConfigEntryError(
            "Plowshare profile configuration needs updating"
        ) from error
    if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", entry.entry_id) is None:
        raise ConfigEntryError("Invalid Plowshare integration identity")
    retained = owners(hass)
    previous = retained.entries.get(entry.entry_id)
    if previous is not None and not await previous.async_close():
        raise ConfigEntryNotReady("Previous Plowshare cleanup remains unsettled")

    key = f"{DOMAIN}.{entry.entry_id}"
    journal = CorrelationJournal(
        Path(hass.config.path(STORAGE_DIR, key)),
        key,
        profiles.primary.request_timeout,
    )
    runtime = EntryRuntime(journal, _HAPlatforms(hass, entry))
    # HA may clear runtime_data after a failed setup. The registry still owns
    # an unsettled read/write/SDK drain and prevents a replacement on reload.
    retained.entries[entry.entry_id] = runtime
    entry.runtime_data = runtime

    @callback
    def on_stop(_event: Event) -> None:
        runtime.begin_close(hass)

    # Register before the first filesystem/forwarding await. A stop during setup
    # must fence this owner too, not create newly exposed profiles afterward.
    entry.async_on_unload(hass.bus.async_listen_once(EVENT_HOMEASSISTANT_STOP, on_stop))
    try:
        sessions = await journal.async_load()
        if runtime.closing or hass.state in _STOPPING:
            raise ConfigEntryNotReady("Home Assistant stopped during Plowshare setup")

        async def save() -> None:
            await journal.async_save(sessions)

        conversation = None
        task = None
        if profiles.conversation is not None:
            mode = (
                "control"
                if profiles.conversation_options.control_enabled
                else "read_only"
            )
            conversation = SdkConversationAgent(
                profiles.conversation,
                sessions,
                save,
                profile_id=f"{entry.entry_id}:conversation:{mode}",
            )
        if profiles.task is not None:
            task = SdkConversationAgent(
                profiles.task,
                sessions,
                save,
                profile_id=f"{entry.entry_id}:task",
            )
        runtime.attach(conversation, task)
        await runtime.async_forward()
        if runtime.closing or hass.state in _STOPPING:
            raise ConfigEntryNotReady("Home Assistant stopped during Plowshare setup")
    except (JournalUnavailable, PlatformSetupIncomplete) as error:
        runtime.finish_setup()
        if await runtime.async_close():
            retained.release(entry.entry_id, runtime)
        raise ConfigEntryNotReady(
            "Plowshare setup resources need inspection"
        ) from error
    except BaseException:
        # Cleanup observes ownership; it never cancels accepted server jobs.
        # A failed cleanup remains retained even if setup is being cancelled.
        runtime.finish_setup()
        if await runtime.async_close():
            retained.release(entry.entry_id, runtime)
        raise
    runtime.finish_setup()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: PlowshareConfigEntry) -> bool:
    """Refuse disposal until profile gateways and physical journal I/O settle."""
    runtime = owners(hass).entries.get(entry.entry_id)
    if runtime is None:
        # A never-started entry has no adapter-owned work to dispose.
        platforms = []
        if entry.data.get(CONF_CONVERSATION_ENABLED, True) is not False:
            platforms.append(Platform.CONVERSATION)
        if entry.data.get(CONF_AI_TASK_ENABLED, False) is True:
            platforms.append(Platform.AI_TASK)
        return await hass.config_entries.async_unload_platforms(entry, platforms)
    if not await runtime.async_close():
        return False
    owners(hass).release(entry.entry_id, runtime)
    return True
