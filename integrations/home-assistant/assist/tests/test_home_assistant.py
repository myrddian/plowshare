"""Real HA Conversation/session/prompt APIs with a typed local gateway fixture.

These tests do not prove server grants or device behavior. Public SDK transport
and durable no-replay behavior are exercised by the gateway/socket suites.
"""

import asyncio
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType, SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import support
from homeassistant.components import conversation
from homeassistant.components.conversation import ConversationInput
from homeassistant.components.conversation.chat_log import DATA_CHAT_LOGS
from homeassistant.config_entries import ConfigEntries, ConfigEntry
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import ConfigEntryError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.chat_session import DATA_CHAT_SESSION
from homeassistant.helpers.intent import IntentResponseType

from custom_components.plowshare import async_setup_entry, async_unload_entry
from custom_components.plowshare.agent import GatewayPort
from custom_components.plowshare.config import configured_profiles
from custom_components.plowshare.config_flow import PlowshareConfigFlow
from custom_components.plowshare.conversation import PlowshareConversationEntity
from custom_components.plowshare.requests import ConversationRequest, GatewayResult

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "read_only_agent": "house_reader",
    "agent": "house_controller",
}


def config_entry(data=None):
    return ConfigEntry(
        domain="plowshare",
        title="House",
        data=DATA if data is None else data,
        version=1,
        minor_version=1,
        source="user",
        unique_id="house",
        options={},
        discovery_keys=MappingProxyType({}),
        subentries_data=None,
    )


class GatewayFixture:
    def __init__(self):
        self.requests: list[ConversationRequest] = []
        self.result = GatewayResult(
            "completed", text="Would you like the outside temperature?"
        )
        self.entered = asyncio.Event()
        self.release: asyncio.Event | None = None

    async def converse(self, request):
        self.requests.append(request)
        self.entered.set()
        if self.release is not None:
            await self.release.wait()
        return self.result

    async def generate(self, request):
        raise AssertionError("Conversation never uses the task profile")

    async def validate(self, required_agents=()):
        raise AssertionError("Conversation execution never probes configuration")

    async def ask(self, key, text):
        raise AssertionError("Conversation uses typed requests, not legacy ask")

    async def async_close(self):
        return True


@dataclass(frozen=True)
class RuntimeFixture:
    conversation: GatewayPort | None
    task: GatewayPort | None = None

    async def async_close(self):
        return True


class HomeAssistantTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config.components.add("conversation")
        self.hass.config_entries = ConfigEntries(self.hass, {})
        # Keep actual ChatLog/template rendering. Only the unrelated auth lookup
        # boundary is faked; a Context here is source partitioning, not a grant.
        self.hass.auth = SimpleNamespace(async_get_user=AsyncMock(return_value=None))

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    def entity(self, data=None):
        entry = config_entry(data)
        port = GatewayFixture()
        entry.runtime_data = RuntimeFixture(port)
        entity = PlowshareConversationEntity(entry)
        entity.hass = self.hass
        return entry, entity, port

    def request(self, **changes):
        fields = {
            "text": "How warm is it?",
            "context": Context(user_id="alice"),
            "conversation_id": None,
            "device_id": "device",
            "satellite_id": None,
            "language": "en",
            "agent_id": "conversation.house",
        }
        return ConversationInput(**{**fields, **changes})

    async def test_real_chat_session_preserves_id_and_exact_effective_extra_prompt(
        self,
    ):
        _, entity, port = self.entity(
            {**DATA, "prompt": "Read the supplied facts — λ."}
        )
        extra = "  Extra context: café\n second line  "
        first = await entity.async_process(
            self.request(
                text="  Question\nwith whitespace  ", extra_system_prompt=extra
            )
        )
        self.assertTrue(first.continue_conversation)
        self.assertEqual(first.response.speech["plain"]["speech"], port.result.text)
        first_request = port.requests[0]
        self.assertEqual(first_request.text, "  Question\nwith whitespace  ")
        self.assertTrue(
            first_request.context.system_text.startswith("Read the supplied facts — λ.")
        )
        self.assertTrue(first_request.context.system_text.endswith(extra))
        self.assertEqual(first_request.context.system_text.count(extra), 1)
        log = self.hass.data[DATA_CHAT_LOGS][first.conversation_id]
        self.assertEqual(first_request.context.system_text, log.content[0].content)
        self.assertEqual(log.content[-1].content, port.result.text)
        self.assertEqual(first_request.context.language, "en")
        self.assertEqual(first_request.context.user_id, "alice")
        self.assertEqual(first_request.context.device_id, "device")
        follow = await entity.async_process(
            self.request(text="Yes please", conversation_id=first.conversation_id)
        )
        self.assertEqual(follow.conversation_id, first.conversation_id)
        self.assertEqual(first_request.key, port.requests[1].key)
        self.assertEqual(port.requests[1].text, "Yes please")
        self.assertEqual(port.requests[1].context.system_text.count(extra), 1)

    async def test_foreign_source_cannot_inherit_retained_extra_or_touch_the_log(self):
        _, entity, port = self.entity()
        first = await entity.async_process(
            self.request(extra_system_prompt="private-alice-extra")
        )
        log = self.hass.data[DATA_CHAT_LOGS][first.conversation_id]
        before = list(log.content)
        second = await entity.async_process(
            self.request(
                context=Context(user_id="bob"), conversation_id=first.conversation_id
            )
        )
        self.assertEqual(second.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(port.requests), 1)
        self.assertEqual(log.content, before)
        own = await entity.async_process(self.request(context=Context(user_id="bob")))
        self.assertNotEqual(own.conversation_id, first.conversation_id)
        self.assertNotIn("private-alice-extra", port.requests[-1].context.system_text)
        self.assertNotEqual(port.requests[0].key, port.requests[-1].key)

    async def test_user_device_satellite_and_entry_changes_cannot_reuse_a_log(self):
        entry, entity, port = self.entity()
        first = await entity.async_process(self.request())
        for changes in (
            {"context": Context(user_id="bob")},
            {"device_id": "other-device"},
            {"satellite_id": "other-satellite"},
        ):
            with self.subTest(changes=changes):
                result = await entity.async_process(
                    self.request(conversation_id=first.conversation_id, **changes)
                )
                self.assertEqual(
                    result.response.response_type, IntentResponseType.ERROR
                )
        other_entry, other_entity, other_port = self.entity()
        self.assertNotEqual(entry.entry_id, other_entry.entry_id)
        result = await other_entity.async_process(
            self.request(conversation_id=first.conversation_id)
        )
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(port.requests), 1)
        self.assertEqual(other_port.requests, [])

    async def test_entity_reload_preserves_owned_source_and_effective_extra(self):
        entry, entity, port = self.entity()
        first = await entity.async_process(
            self.request(extra_system_prompt="retained-extra")
        )
        reloaded = PlowshareConversationEntity(entry)
        reloaded.hass = self.hass
        follow = await reloaded.async_process(
            self.request(conversation_id=first.conversation_id)
        )
        self.assertEqual(follow.conversation_id, first.conversation_id)
        self.assertEqual(
            port.requests[-1].context.system_text.count("retained-extra"), 1
        )

    async def test_unknown_supplied_id_is_rejected_before_ha_session_or_sdk_work(self):
        _, entity, port = self.entity()
        result = await entity.async_process(
            self.request(conversation_id="caller-chosen-unknown-id")
        )
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertNotIn(DATA_CHAT_SESSION, self.hass.data)
        self.assertEqual(port.requests, [])

    async def test_expired_ha_id_returns_new_id_without_replacement_work(self):
        _, entity, port = self.entity()
        first = await entity.async_process(self.request())
        old = self.hass.data[DATA_CHAT_SESSION].pop(first.conversation_id)
        old.async_cleanup()
        result = await entity.async_process(
            self.request(conversation_id=first.conversation_id)
        )
        self.assertNotEqual(result.conversation_id, first.conversation_id)
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(port.requests), 1)
        still_blocked = await entity.async_process(
            self.request(conversation_id=result.conversation_id)
        )
        self.assertEqual(still_blocked.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(port.requests), 1)

    async def test_same_session_concurrency_is_refused_before_shared_log_mutation(self):
        _, entity, port = self.entity()
        first = await entity.async_process(self.request())
        port.entered.clear()
        port.release = asyncio.Event()
        pending = asyncio.create_task(
            entity.async_process(
                self.request(
                    conversation_id=first.conversation_id, text="first follow-up"
                )
            )
        )
        await asyncio.wait_for(port.entered.wait(), 1)
        second = await entity.async_process(
            self.request(
                conversation_id=first.conversation_id, text="must not enter log"
            )
        )
        self.assertEqual(second.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(port.requests), 2)
        port.release.set()
        await asyncio.wait_for(pending, 1)
        log = self.hass.data[DATA_CHAT_LOGS][first.conversation_id]
        self.assertFalse(
            any(item.content == "must not enter log" for item in log.content)
        )

    async def test_bounded_ownership_does_not_evict_a_live_source(self):
        _, entity, port = self.entity()
        with patch("custom_components.plowshare.conversation.MAX_SESSIONS", 1):
            first = await entity.async_process(self.request())
            refused = await entity.async_process(self.request())
            self.assertEqual(refused.response.response_type, IntentResponseType.ERROR)
            follow = await entity.async_process(
                self.request(conversation_id=first.conversation_id)
            )
        self.assertEqual(follow.conversation_id, first.conversation_id)
        self.assertEqual(len(port.requests), 2)

    async def test_anonymous_sources_need_explicit_opt_in_and_device_identity(self):
        _, entity, port = self.entity()
        result = await entity.async_process(self.request(context=Context()))
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(port.requests, [])
        _, enabled, enabled_port = self.entity({**DATA, "allow_anonymous": True})
        result = await enabled.async_process(
            self.request(context=Context(), satellite_id="satellite")
        )
        self.assertEqual(result.response.response_type, IntentResponseType.ACTION_DONE)
        self.assertIsNone(enabled_port.requests[0].context.user_id)
        denied = await enabled.async_process(
            self.request(context=Context(), device_id=None)
        )
        self.assertEqual(denied.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(enabled_port.requests), 1)

    async def test_invalid_inputs_and_template_failure_do_not_call_gateway(self):
        _, entity, port = self.entity()
        for changes in (
            {"text": "\x00bad"},
            {"extra_system_prompt": "\x00bad"},
            {"language": ""},
        ):
            with self.subTest(changes=changes):
                result = await entity.async_process(self.request(**changes))
                self.assertEqual(
                    result.response.response_type, IntentResponseType.ERROR
                )
        self.assertEqual(port.requests, [])
        _, broken, broken_port = self.entity(
            {**DATA, "prompt": "{{ nonexistent_function() }}"}
        )
        result = await broken.async_process(self.request())
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(broken_port.requests, [])

    async def test_pending_and_error_outcomes_are_not_completed_answers(self):
        _, entity, port = self.entity()
        port.result = GatewayResult(
            "pending",
            message="The accepted work is still running; please wait?",
        )
        pending = await entity.async_process(self.request())
        self.assertFalse(pending.continue_conversation)
        self.assertIn("still running", pending.response.speech["plain"]["speech"])
        for status in ("uncertain", "refused", "unavailable", "busy", "failed"):
            port.result = GatewayResult(status, message=f"fixture {status}")
            failed = await entity.async_process(self.request())
            self.assertEqual(failed.response.response_type, IntentResponseType.ERROR)
            self.assertFalse(failed.continue_conversation)

    async def test_completed_greek_question_uses_ha_continuation_semantics(self):
        _, entity, port = self.entity()
        port.result = GatewayResult("completed", text="Θέλετε τη θερμοκρασία;")
        result = await entity.async_process(self.request(language="el"))
        self.assertTrue(result.continue_conversation)

    async def test_control_declaration_and_routing_are_explicit(self):
        _, readonly, _ = self.entity()
        self.assertFalse(
            readonly.supported_features & conversation.ConversationEntityFeature.CONTROL
        )
        self.assertEqual(configured_profiles(DATA).conversation.agent, "house_reader")
        _, control, _ = self.entity({**DATA, "control_enabled": True})
        self.assertTrue(
            control.supported_features & conversation.ConversationEntityFeature.CONTROL
        )
        self.assertEqual(
            configured_profiles({**DATA, "control_enabled": True}).conversation.agent,
            "house_controller",
        )

    async def test_actual_registered_conversation_process_service_and_public_api(self):
        # HA 2026.9 separates device-registry creation from its load phase;
        # entity-registry loading then consumes that already-loaded registry.
        dr.async_setup(self.hass)
        await dr.async_load(self.hass, load_empty=True)
        await er.async_load(self.hass, load_empty=True)
        self.hass.http = Mock()
        self.assertTrue(await conversation.async_setup(self.hass, {}))
        component = self.hass.data[conversation.DATA_COMPONENT]
        component.register_shutdown()
        _, entity, port = self.entity()
        await component.async_add_entities([entity])
        self.assertIs(component.get_entity(entity.entity_id), entity)
        first = await self.hass.services.async_call(
            "conversation",
            "process",
            {
                "text": "Service question",
                "language": "fr",
                "agent_id": entity.entity_id,
            },
            blocking=True,
            return_response=True,
            context=Context(user_id="alice"),
        )
        self.assertIsNotNone(first)
        result = await conversation.async_converse(
            self.hass,
            "API follow-up",
            first["conversation_id"],
            Context(user_id="alice"),
            language="fr",
            agent_id=entity.entity_id,
        )
        self.assertEqual(result.conversation_id, first["conversation_id"])
        self.assertEqual(port.requests[0].context.language, "fr")
        self.assertEqual(port.requests[0].key, port.requests[1].key)
        self.assertEqual(port.requests[1].text, "API follow-up")

    async def test_invalid_profiles_refuse_before_journal_sdk_or_platform_work(self):
        entry = config_entry({**DATA, "llm_api": "unsupported"})
        retained = (
            Path(self.directory.name) / ".storage" / f"plowshare.{entry.entry_id}"
        )
        retained.parent.mkdir()
        retained.write_bytes(b'{"pending": "retain even unreadable correlation"}')
        original = retained.read_bytes()
        with (
            patch("custom_components.plowshare.CorrelationJournal") as journal,
            patch("custom_components.plowshare.SdkConversationAgent") as sdk,
            patch.object(
                ConfigEntries, "async_forward_entry_setups", new_callable=AsyncMock
            ) as forward,
        ):
            with self.assertRaises(ConfigEntryError):
                await async_setup_entry(self.hass, entry)
        journal.assert_not_called()
        sdk.assert_not_called()
        forward.assert_not_awaited()
        self.assertEqual(retained.read_bytes(), original)

    async def test_configuration_form_is_available_without_sdk_or_storage_work(self):
        flow = PlowshareConfigFlow()
        flow.hass = self.hass
        flow.context = {"source": "user"}
        with patch(
            "custom_components.plowshare.config_flow.SdkConversationAgent"
        ) as sdk:
            result = await flow.async_step_user()
            self.assertEqual(result["type"], "form")
            self.assertEqual(result["step_id"], "user")
        sdk.assert_not_called()
        self.assertFalse((Path(self.directory.name) / ".storage").exists())

    async def test_unload_keeps_existing_platform_cleanup(self):
        entry = config_entry()
        entry.runtime_data = RuntimeFixture(None)
        unload = AsyncMock(return_value=True)
        with patch.object(
            self.hass, "config_entries", new=Mock(async_unload_platforms=unload)
        ):
            self.assertTrue(await async_unload_entry(self.hass, entry))
        unload.assert_awaited_once()

    def test_fixture_sdk_is_unchanged(self):
        source = support.ROOT.parents[2] / "sdk/python/src/plowshare"
        bundled = support.COMPONENT / "_vendor/plowshare"
        for original in source.rglob("*"):
            if original.is_file() and "__pycache__" not in original.parts:
                self.assertEqual(
                    original.read_bytes(),
                    (bundled / original.relative_to(source)).read_bytes(),
                )
