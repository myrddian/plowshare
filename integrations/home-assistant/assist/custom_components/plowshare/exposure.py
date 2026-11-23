"""Versioned effective Assist selection for the optional outbound read provider.

HA's canonical predicate also materializes default exposure preferences in its
own registry/store. This command preserves that behavior; it never changes a
device, invokes a service, submits model work or touches correlation journals.
"""

from dataclasses import dataclass
from time import monotonic
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.homeassistant import async_should_expose
from homeassistant.components.homeassistant.const import DATA_EXPOSED_ENTITIES
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.util.hass_dict import HassKey

COMMAND = "plowshare/discovery/selection"
ASSISTANT = "conversation"
MAX_SCAN = 16384
MAX_ENTITIES = 4096
MAX_ENTITY_CHARACTERS = 240 * 1024
SCAN_SECONDS = 2
REGISTERED: HassKey[bool] = HassKey("plowshare.discovery_registered")
REQUEST_SCHEMA = vol.Schema(
    {vol.Required("id"): cv.positive_int, vol.Required("type"): COMMAND},
    extra=vol.PREVENT_EXTRA,
)


@dataclass(frozen=True)
class EffectiveSelection:
    """Complete bounded policy observation, never an authorization lease."""

    entities: tuple[str, ...]

    def __post_init__(self) -> None:
        if len(self.entities) > MAX_ENTITIES or len(set(self.entities)) != len(
            self.entities
        ):
            raise ValueError("Effective selection exceeds its bound")
        for entity_id in self.entities:
            if cv.entity_id(entity_id) != entity_id or not entity_id.isascii():
                raise ValueError("Invalid effective entity identity")
        # Entity IDs are ASCII. Reserve room for JSON quotes, separators and
        # the result envelope within the native adapter's 256 KiB input bound.
        if sum(len(entity) + 4 for entity in self.entities) > MAX_ENTITY_CHARACTERS:
            raise ValueError("Effective selection exceeds its byte bound")


@callback
@websocket_api.require_admin
def selection_command(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Observe HA-owned effective exposure; no caller can widen assistant scope."""
    request = REQUEST_SCHEMA(msg)
    deadline = monotonic() + SCAN_SECONDS
    if DATA_EXPOSED_ENTITIES not in hass.data:
        connection.send_error(
            request["id"], "not_supported", "HA exposure rules are not initialized"
        )
        return
    registry = er.async_get(hass)
    if (
        len(registry.entities) > MAX_SCAN
        or hass.states.async_entity_ids_count() > MAX_SCAN
    ):
        connection.send_error(
            request["id"],
            "selection_too_large",
            "HA entity inventory exceeds its bound",
        )
        return
    entity_ids = set(registry.entities) | set(hass.states.async_entity_ids())
    if len(entity_ids) > MAX_SCAN:
        connection.send_error(
            request["id"],
            "selection_too_large",
            "HA entity inventory exceeds its bound",
        )
        return
    selected: list[str] = []
    try:
        for entity_id in sorted(entity_ids):
            if monotonic() >= deadline:
                connection.send_error(
                    request["id"],
                    "timeout",
                    "HA exposure observation exceeded its budget",
                )
                return
            if async_should_expose(hass, ASSISTANT, entity_id):
                selected.append(entity_id)
                if len(selected) > MAX_ENTITIES:
                    raise ValueError("Effective selection exceeds its bound")
        snapshot = EffectiveSelection(tuple(selected))
    except ValueError, vol.Invalid:
        connection.send_error(
            request["id"], "selection_unavailable", "HA selection cannot be represented"
        )
        return
    if monotonic() >= deadline:
        connection.send_error(
            request["id"], "timeout", "HA exposure observation exceeded its budget"
        )
        return
    connection.send_result(
        request["id"],
        {"version": 1, "assistant": ASSISTANT, "entities": list(snapshot.entities)},
    )


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register once independently of gateway/config-entry lifecycle."""
    if hass.data.get(REGISTERED):
        return
    # Explicit schema registration matters: HA optimizes a type-only decorator
    # into no argument validation, which would accept unwanted scope arguments.
    websocket_api.async_register_command(
        hass, COMMAND, selection_command, REQUEST_SCHEMA
    )
    hass.data[REGISTERED] = True
