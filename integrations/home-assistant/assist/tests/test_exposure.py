"""Actual HA exposure rules, native registration and bounded read-only protocol."""

import json
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import homeassistant  # noqa: F401 - initialize HA's schema compatibility before imports
import support  # noqa: F401
import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.homeassistant.const import DATA_EXPOSED_ENTITIES
from homeassistant.components.homeassistant.exposed_entities import (
    ExposedEntities,
    async_expose_entity,
)
from homeassistant.config_entries import ConfigEntries
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er

from custom_components.plowshare import async_setup, exposure
from custom_components.plowshare.runtime import OWNERS


class ExposureTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config_entries = ConfigEntries(self.hass, {})
        dr.async_setup(self.hass)
        await dr.async_load(self.hass, load_empty=True)
        await er.async_load(self.hass, load_empty=True)
        self.registry = er.async_get(self.hass)
        self.connection = SimpleNamespace(
            user=SimpleNamespace(is_admin=True),
            send_result=Mock(),
            send_error=Mock(),
        )

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    async def rules(self):
        rules = ExposedEntities(self.hass)
        await rules.async_initialize()
        self.hass.data[DATA_EXPOSED_ENTITIES] = rules
        return rules

    def invoke(self):
        exposure.selection_command(
            self.hass, self.connection, {"id": 1, "type": exposure.COMMAND}
        )

    def result(self):
        self.connection.send_error.assert_not_called()
        return self.connection.send_result.call_args.args[1]

    async def test_canonical_defaults_legacy_and_explicit_denials(self):
        await self.rules()
        office = self.registry.async_get_or_create(
            "climate",
            "fixture",
            "private-office-identity",
            suggested_object_id="office",
        )
        denied = self.registry.async_get_or_create(
            "climate",
            "fixture",
            "private-other-identity",
            suggested_object_id="office_2",
        )
        hidden = self.registry.async_get_or_create(
            "light",
            "fixture",
            "private-hidden-identity",
            hidden_by=er.RegistryEntryHider.USER,
        )
        self.hass.states.async_set(
            office.entity_id, "off", {"current_temperature": 19.4}
        )
        self.hass.states.async_set(denied.entity_id, "unavailable")
        self.hass.states.async_set(hidden.entity_id, "on")
        self.hass.states.async_set(
            "sensor.temperature", "19.4", {"device_class": "temperature"}
        )
        self.hass.states.async_set("sensor.battery", "10", {"device_class": "battery"})
        self.hass.states.async_set("fan.legacy", "on")
        async_expose_entity(self.hass, "conversation", denied.entity_id, False)
        self.assertNotIn("conversation", office.options)

        self.invoke()
        result = self.result()
        self.assertEqual(
            result,
            {
                "version": 1,
                "assistant": "conversation",
                "entities": sorted(
                    [office.entity_id, "sensor.temperature", "fan.legacy"]
                ),
            },
        )
        self.assertNotIn("private-", json.dumps(result))
        self.assertNotIn("19.4", json.dumps(result))
        # The native helper materializes effective defaults. Preserve HA's own
        # documented behavior rather than duplicating it as a second policy.
        current = self.registry.async_get(office.entity_id)
        self.assertTrue(current.options["conversation"]["should_expose"])
        self.assertNotIn(OWNERS, self.hass.data)

    async def test_default_disabled_explicit_allow_and_revocation(self):
        rules = await self.rules()
        rules.async_set_expose_new_entities("conversation", False)
        self.hass.states.async_set("light.room", "on")
        self.invoke()
        self.assertEqual(self.result()["entities"], [])
        async_expose_entity(self.hass, "conversation", "light.room", True)
        self.invoke()
        self.assertEqual(self.result()["entities"], ["light.room"])
        async_expose_entity(self.hass, "conversation", "light.room", False)
        self.invoke()
        self.assertEqual(self.result()["entities"], [])

    async def test_non_admin_never_invokes_exposure_predicate(self):
        for user in (None, SimpleNamespace(is_admin=False)):
            self.connection.user = user
            with patch.object(exposure, "async_should_expose") as predicate:
                with self.assertRaises(Unauthorized):
                    self.invoke()
                predicate.assert_not_called()
        self.connection.send_result.assert_not_called()

    async def test_missing_helper_is_explicit_and_creates_no_owner(self):
        self.invoke()
        self.connection.send_error.assert_called_once_with(
            1, "not_supported", "HA exposure rules are not initialized"
        )
        self.connection.send_result.assert_not_called()
        self.assertNotIn(OWNERS, self.hass.data)

    async def test_schema_rejects_scope_arguments_and_registration_is_once(self):
        with patch.object(
            websocket_api,
            "async_register_command",
            wraps=websocket_api.async_register_command,
        ) as register:
            self.assertTrue(await async_setup(self.hass, {}))
            self.assertTrue(await async_setup(self.hass, {}))
            register.assert_called_once()
        handler, schema = self.hass.data[websocket_api.DOMAIN][exposure.COMMAND]
        self.assertIs(handler, exposure.selection_command)
        for fields in ({"assistant": "other"}, {"entities": []}, {"limit": 1}):
            msg = {"id": 1, "type": exposure.COMMAND, **fields}
            with (
                self.subTest(fields=fields),
                patch.object(exposure, "async_should_expose") as predicate,
            ):
                with self.assertRaises(vol.Invalid):
                    schema(msg)
                with self.assertRaises(vol.Invalid):
                    handler(self.hass, self.connection, msg)
                predicate.assert_not_called()
        self.assertNotIn(OWNERS, self.hass.data)

    async def test_overflow_never_returns_a_partial_selection(self):
        await self.rules()
        self.hass.states.async_set("light.one", "on")
        self.hass.states.async_set("light.two", "on")
        with patch.object(exposure, "MAX_ENTITIES", 1):
            self.invoke()
        self.connection.send_result.assert_not_called()
        self.assertEqual(
            self.connection.send_error.call_args.args[1], "selection_unavailable"
        )

    async def test_scan_inventory_bound_and_deadline_do_not_disclose(self):
        await self.rules()
        self.hass.states.async_set("light.one", "on")
        with patch.object(exposure, "MAX_SCAN", 0):
            self.invoke()
        self.assertEqual(
            self.connection.send_error.call_args.args[1], "selection_too_large"
        )
        with (
            patch.object(exposure, "monotonic", side_effect=[0, 3]),
            patch.object(exposure, "async_should_expose") as predicate,
        ):
            self.invoke()
            predicate.assert_not_called()
        self.assertEqual(self.connection.send_error.call_args.args[1], "timeout")
        self.connection.send_result.assert_not_called()

    async def test_final_validation_deadline_prevents_late_publication(self):
        await self.rules()
        self.hass.states.async_set("light.one", "on")
        with patch.object(exposure, "monotonic", side_effect=[0, 0, 3]):
            self.invoke()
        self.connection.send_result.assert_not_called()
        self.assertEqual(self.connection.send_error.call_args.args[1], "timeout")

    def test_typed_selection_rejects_bad_identity_and_byte_overflow(self):
        with self.assertRaises(vol.Invalid):
            exposure.EffectiveSelection(("bad",))
        with self.assertRaises(ValueError):
            exposure.EffectiveSelection(("light.one", "light.one"))
        with patch.object(exposure, "MAX_ENTITY_CHARACTERS", 1):
            with self.assertRaises(ValueError):
                exposure.EffectiveSelection(("light.one",))
