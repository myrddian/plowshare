"""Plowshare conversation agent for Home Assistant Assist."""

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryError, ConfigEntryNotReady
from homeassistant.helpers.storage import Store

from .agent import ConversationPort, SdkConversationAgent
from .availability import PromptFidelityUnavailableError, require_prompt_fidelity
from .config import settings_from_data
from .const import DOMAIN
from .models import decode_sessions, encode_sessions

type PlowshareConfigEntry = ConfigEntry[ConversationPort]


async def async_setup_entry(hass: HomeAssistant, entry: PlowshareConfigEntry) -> bool:
    """Refuse the gated prototype without touching retained correlation state."""
    try:
        require_prompt_fidelity()
    except PromptFidelityUnavailableError as error:
        # This missing capability is not a transient connection failure. Preserve
        # existing uncertain-submission fences without even opening the store.
        raise ConfigEntryError(str(error)) from error
    store = Store(hass, 1, f"{DOMAIN}.{entry.entry_id}", private=True)
    try:
        sessions = decode_sessions(await store.async_load())
    except (OSError, ValueError) as error:
        # Preserve an unreadable file. Never replace it with empty state, which
        # would forget uncertain submissions and make duplicate effects possible.
        raise ConfigEntryNotReady(
            "Assist correlation store needs inspection"
        ) from error

    async def save() -> None:
        await store.async_save(encode_sessions(sessions))

    entry.runtime_data = SdkConversationAgent(
        settings_from_data(entry.data), sessions, save
    )
    await hass.config_entries.async_forward_entry_setups(entry, [Platform.CONVERSATION])
    return True


async def async_unload_entry(hass: HomeAssistant, entry: PlowshareConfigEntry) -> bool:
    """No background model work is owned by HA; retained server work continues."""
    return await hass.config_entries.async_unload_platforms(
        entry, [Platform.CONVERSATION]
    )
