"""Actual HA AI Task action/helper contracts with an isolated typed gateway."""

import asyncio
import tempfile
import unittest
from dataclasses import dataclass
from types import MappingProxyType, SimpleNamespace
from unittest.mock import AsyncMock, patch

import support  # noqa: F401
import voluptuous as vol
from homeassistant.components import ai_task
from homeassistant.components.ai_task.const import DATA_COMPONENT
from homeassistant.components.ai_task.task import GenDataTask
from homeassistant.components.conversation.chat_log import SystemContent
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.selector import BooleanSelector, TextSelector

from custom_components.plowshare.agent import GatewayPort
from custom_components.plowshare.ai_task import PlowshareAITaskEntity
from custom_components.plowshare.requests import GatewayResult

DATA = {
    "url": "https://plowshare.example.invalid",
    "api_key": "fixture-token",
    "project": "house",
    "conversation_enabled": False,
    "ai_task_enabled": True,
    "task_agent": "task_writer",
}


class TaskGateway:
    def __init__(self):
        self.requests = []
        self.result = GatewayResult("completed", text="  Completed α note.\n")
        self.hold = None

    async def validate(self, required_agents=()):
        return None

    async def ask(self, key, text):
        raise AssertionError("AI Task must not use the conversation presentation")

    async def converse(self, request):
        raise AssertionError("AI Task must not use a conversation profile")

    async def generate(self, request):
        self.requests.append(request)
        if self.hold is not None:
            await self.hold.wait()
        return self.result

    async def async_close(self):
        return True


@dataclass(frozen=True)
class RuntimeFixture:
    conversation: GatewayPort | None
    task: GatewayPort | None

    async def async_close(self):
        return True


def config_entry(data=None):
    return ConfigEntry(
        domain="plowshare",
        title="House",
        data=DATA if data is None else data,
        version=1,
        minor_version=1,
        source="user",
        unique_id="task-fixture",
        options={},
        discovery_keys=MappingProxyType({}),
        subentries_data=None,
    )


class AITaskTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config.components.add("conversation")
        self.hass.config.media_dirs = {}
        self.hass.auth = SimpleNamespace(
            async_get_user=AsyncMock(return_value=SimpleNamespace(name="Fixture user"))
        )
        dr.async_setup(self.hass)
        await dr.async_load(self.hass, load_empty=True)
        await er.async_load(self.hass, load_empty=True)
        await ai_task.async_setup(self.hass, {})
        self.component = self.hass.data[DATA_COMPONENT]
        self.component.register_shutdown()
        self.gateway = TaskGateway()
        self.entry = config_entry()
        self.entry.runtime_data = RuntimeFixture(None, self.gateway)
        self.entity = PlowshareAITaskEntity(self.entry)
        await self.component.async_add_entities([self.entity])
        self.assertIsNotNone(self.entity.entity_id)

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    async def generate(self, **kwargs):
        return await ai_task.async_generate_data(
            self.hass,
            task_name="fixture task",
            entity_id=self.entity.entity_id,
            instructions="Write a short household note",
            **kwargs,
        )

    async def test_actual_helper_preserves_prepared_text_and_only_completed_data(self):
        original = self.entity._async_generate_data
        seen = []

        async def observe(task, chat_log):
            seen.append(chat_log.content[0].content)
            result = await original(task, chat_log)
            self.assertEqual(chat_log.content[-1].content, self.gateway.result.text)
            return result

        with patch.object(self.entity, "_async_generate_data", side_effect=observe):
            result = await self.generate(context=Context(user_id="alice"))
        self.assertEqual(result.data, self.gateway.result.text)
        self.assertEqual(self.gateway.requests[0].context.system_text, seen[0])
        self.assertEqual(
            self.gateway.requests[0].instructions, "Write a short household note"
        )
        self.assertIsNone(self.gateway.requests[0].context.user_id)
        self.assertIsNotNone(result.conversation_id)
        self.assertEqual(
            self.entity.supported_features, ai_task.AITaskEntityFeature.GENERATE_DATA
        )

    async def test_actual_generate_data_action_selects_this_task_entity(self):
        result = await self.hass.services.async_call(
            "ai_task",
            "generate_data",
            {
                "task_name": "service task",
                "entity_id": self.entity.entity_id,
                "instructions": "Return a note",
            },
            blocking=True,
            return_response=True,
        )
        self.assertEqual(result["data"], self.gateway.result.text)
        self.assertTrue(result["conversation_id"])
        self.assertEqual(len(self.gateway.requests), 1)
        self.assertEqual(self.gateway.requests[0].instructions, "Return a note")

    async def test_concurrent_identical_tasks_have_distinct_invocations_and_sessions(
        self,
    ):
        self.gateway.hold = asyncio.Event()
        first = asyncio.create_task(self.generate(context=Context(user_id="alice")))
        second = asyncio.create_task(self.generate(context=Context(user_id="bob")))
        for _ in range(100):
            if len(self.gateway.requests) == 2:
                break
            await asyncio.sleep(0)
        self.assertEqual(len(self.gateway.requests), 2)
        self.gateway.hold.set()
        a, b = await asyncio.gather(first, second)
        self.assertNotEqual(a.conversation_id, b.conversation_id)
        self.assertEqual(len({r.key for r in self.gateway.requests}), 2)
        self.assertTrue(all(r.context.user_id is None for r in self.gateway.requests))

    async def test_missing_context_never_reuses_mutable_entity_caller_identity(self):
        await self.generate(context=Context(user_id="alice"))
        await self.generate(context=None)
        self.assertEqual(len(self.gateway.requests), 2)
        self.assertTrue(all(r.context.user_id is None for r in self.gateway.requests))
        self.assertNotEqual(self.gateway.requests[0].key, self.gateway.requests[1].key)

    async def test_pending_uncertain_and_failures_are_errors_not_generated_data(self):
        for status in (
            "pending",
            "uncertain",
            "failed",
            "busy",
            "refused",
            "unavailable",
        ):
            with self.subTest(status=status):
                self.gateway.result = GatewayResult(
                    status, message="Retained work is not completed"
                )
                before = len(self.gateway.requests)
                with self.assertRaisesRegex(HomeAssistantError, "not completed"):
                    await self.generate()
                self.assertEqual(len(self.gateway.requests), before + 1)

    async def test_public_helper_rejects_attachments_before_resolving_or_submitting(
        self,
    ):
        with self.assertRaisesRegex(HomeAssistantError, "does not support attachments"):
            await self.generate(
                attachments=[{"media_content_id": "media-source://camera/fixture"}]
            )
        self.assertEqual(self.gateway.requests, [])

    async def test_unsupported_structure_rejected_before_sdk_submission(self):
        with self.assertRaisesRegex(HomeAssistantError, "Structured"):
            await self.generate(
                structure=vol.Schema({"nested": vol.Schema({"value": str})})
            )
        self.assertEqual(self.gateway.requests, [])

    async def test_actual_structured_action_returns_validated_values_and_context(self):
        self.gateway.result = GatewayResult(
            "completed", text='{"note":"Household α note","enabled":false}'
        )
        prepared = []
        original = self.entity._async_generate_data

        async def observe(task, log):
            prepared.append(log.content[0].content)
            return await original(task, log)

        with patch.object(self.entity, "_async_generate_data", side_effect=observe):
            result = await self.hass.services.async_call(
                "ai_task",
                "generate_data",
                {
                    "task_name": "structured household note",
                    "entity_id": self.entity.entity_id,
                    "instructions": "  Preserve my original instruction.\n",
                    "structure": {
                        "note": {"required": True, "selector": {"text": {}}},
                        "enabled": {"required": True, "selector": {"boolean": {}}},
                        "optional": {"selector": {"text": {}}},
                    },
                },
                blocking=True,
                return_response=True,
            )
        self.assertEqual(result["data"], {"note": "Household α note", "enabled": False})
        self.assertIs(type(result["data"]["enabled"]), bool)
        self.assertEqual(len(self.gateway.requests), 1)
        self.assertEqual(self.gateway.requests[0].context.system_text, prepared[0])
        self.assertTrue(
            self.gateway.requests[0].instructions.startswith(
                "  Preserve my original instruction.\n\n\n[Plowshare"
            )
        )

    async def test_invalid_completed_structure_errors_without_repair_or_retry(self):
        structure = vol.Schema({vol.Required("enabled"): BooleanSelector()})
        for text in (
            '{"enabled":"false"}',
            '{"enabled":false,"enabled":true}',
            "not JSON",
        ):
            with self.subTest(text=text):
                self.gateway.result = GatewayResult("completed", text=text)
                retained = self.gateway.result
                before = len(self.gateway.requests)
                with self.assertRaisesRegex(
                    HomeAssistantError, "completed work remains"
                ):
                    await self.generate(structure=structure)
                self.assertEqual(len(self.gateway.requests), before + 1)
                self.assertIs(self.gateway.result, retained)
                self.assertEqual(self.gateway.result.text, text)

    async def test_structured_request_resource_limit_fails_before_submission(self):
        structure = vol.Schema(
            {
                vol.Required(str(i), description="metadata " * 400): TextSelector()
                for i in range(8)
            }
        )
        with self.assertRaisesRegex(HomeAssistantError, "input is not supported"):
            await self.generate(structure=structure)
        self.assertEqual(self.gateway.requests, [])

    async def test_direct_hook_rejects_tool_api_attachments_and_invalid_input(self):
        log = SimpleNamespace(
            conversation_id="fixture-chat", content=[SystemContent(content="prepared")]
        )
        cases = [
            GenDataTask("fixture", "note", llm_api=object()),
            GenDataTask("fixture", "note", attachments=[object()]),
            GenDataTask("fixture", ""),
        ]
        for task in cases:
            with self.subTest(task=task):
                with self.assertRaises(HomeAssistantError):
                    await self.entity._async_generate_data(task, log)
        self.assertEqual(self.gateway.requests, [])

    async def test_cancelled_helper_is_not_resubmitted(self):
        self.gateway.hold = asyncio.Event()
        pending = asyncio.create_task(self.generate())
        for _ in range(100):
            if self.gateway.requests:
                break
            await asyncio.sleep(0)
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        self.assertEqual(len(self.gateway.requests), 1)

    async def test_disabled_task_does_not_fall_back_to_conversation_gateway(self):
        self.entry.runtime_data = RuntimeFixture(self.gateway, None)
        with self.assertRaisesRegex(HomeAssistantError, "disabled"):
            await self.generate()
        self.assertEqual(self.gateway.requests, [])
