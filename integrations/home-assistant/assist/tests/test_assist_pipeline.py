"""Actual HA Assist intent pipeline and the adapter's native first-turn fence.

Audio engines, live SDK/model calls and physical devices are not exercised. HA's
session/log helpers, registered entity, pipeline preparation and intent stage are
real; the agent gateway and unrelated sentence-trigger lookup are local fixtures.
"""

import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import support  # noqa: F401 - stage the independently installable component
from homeassistant.components import conversation
from homeassistant.components.assist_pipeline.pipeline import (
    STORAGE_KEY,
    STORAGE_VERSION,
    STORAGE_VERSION_MINOR,
    AudioSettings,
    Pipeline,
    PipelineEventType,
    PipelineInput,
    PipelineRun,
    PipelineStage,
    PipelineStore,
    async_setup_pipeline_store,
)
from homeassistant.components.conversation import ConversationInput
from homeassistant.components.conversation.chat_log import (
    DATA_CHAT_LOGS,
    AssistantContent,
    Attachment,
    SystemContent,
    UserContent,
    async_get_chat_log,
)
from homeassistant.config_entries import ConfigEntries
from homeassistant.core import Context, HomeAssistant
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.chat_session import (
    DATA_CHAT_SESSION,
    ChatSession,
    async_get_chat_session,
)
from homeassistant.helpers.intent import IntentResponseType
from test_home_assistant import GatewayFixture, RuntimeFixture, config_entry

from custom_components.plowshare.conversation import PlowshareConversationEntity


class AssistPipelineTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)
        self.hass.config_entries = ConfigEntries(self.hass, {})
        self.hass.auth = SimpleNamespace(async_get_user=AsyncMock(return_value=None))
        self.hass.http = Mock()
        dr.async_setup(self.hass)
        await dr.async_load(self.hass, load_empty=True)
        await er.async_load(self.hass, load_empty=True)
        self.assertTrue(await conversation.async_setup(self.hass, {}))
        self.component = self.hass.data[conversation.DATA_COMPONENT]
        self.component.register_shutdown()
        self.hass.config.components.add("conversation")
        self.port = GatewayFixture()
        self.entry = config_entry()
        self.entry.runtime_data = RuntimeFixture(self.port)
        self.entity = PlowshareConversationEntity(self.entry)
        await self.component.async_add_entities([self.entity])
        self.pipeline = Pipeline(
            name="Fixture read-only Assist",
            language="en",
            conversation_engine=self.entity.entity_id,
            conversation_language="*",
            prefer_local_intents=False,
            stt_engine=None,
            stt_language=None,
            tts_engine=None,
            tts_language=None,
            tts_voice=None,
            wake_word_entity=None,
            wake_word_id=None,
        )
        # Seed the selected text-only pipeline through HA's real storage API.
        # An empty store would create an unrelated default speech pipeline and
        # require real STT/TTS component setup, despite this fixture using neither.
        await PipelineStore(
            self.hass,
            STORAGE_VERSION,
            STORAGE_KEY,
            minor_version=STORAGE_VERSION_MINOR,
        ).async_save(
            {"items": [self.pipeline.to_json()], "preferred_item": self.pipeline.id}
        )
        await async_setup_pipeline_store(self.hass)

    async def asyncTearDown(self):
        await self.hass.async_stop()
        self.directory.cleanup()

    def request(self, conversation_id, text="Native first turn"):
        return ConversationInput(
            text=text,
            context=Context(user_id="alice"),
            conversation_id=conversation_id,
            device_id="device",
            satellite_id=None,
            language="en",
            agent_id=self.entity.entity_id,
        )

    async def run_pipeline(
        self, text, conversation_id=None, *, user="alice", extra=None, device="device"
    ):
        events = []
        run = PipelineRun(
            hass=self.hass,
            context=Context(user_id=user),
            pipeline=self.pipeline,
            start_stage=PipelineStage.INTENT,
            end_stage=PipelineStage.INTENT,
            event_callback=events.append,
            audio_settings=AudioSettings(is_vad_enabled=False),
        )
        # This is the same public session/Input boundary as HA's WebSocket
        # runner. PipelineInput opens its real ChatLog before calling our entity.
        with async_get_chat_session(self.hass, conversation_id) as session:
            pipeline_input = PipelineInput(
                run=run,
                session=session,
                device_id=device,
                intent_input=text,
                conversation_extra_system_prompt=extra,
            )
            with patch.object(
                conversation,
                "async_handle_sentence_triggers",
                new=AsyncMock(return_value=None),
            ):
                await asyncio.wait_for(pipeline_input.execute(validate=True), 2)
            session_id = session.conversation_id
        self.assertFalse(any(e.type == PipelineEventType.ERROR for e in events), events)
        result = next(
            e.data["intent_output"]
            for e in events
            if e.type == PipelineEventType.INTENT_END
        )
        return session_id, result

    async def test_actual_pipeline_first_turn_and_follow_up_preserve_source_context(
        self,
    ):
        extra = "  Native context — λ.\n"
        first, result = await self.run_pipeline("  Native question\n", extra=extra)
        self.assertEqual(
            result["response"]["speech"]["plain"]["speech"], self.port.result.text
        )
        self.assertEqual(result["conversation_id"], first)
        self.assertEqual(len(self.port.requests), 1)
        self.assertEqual(self.port.requests[0].text, "  Native question\n")
        self.assertEqual(self.port.requests[0].context.system_text.count(extra), 1)
        key = self.port.requests[0].key
        retained = self.hass.data[DATA_CHAT_LOGS][first]
        before = list(retained.content)
        _, foreign = await self.run_pipeline("Foreign utterance", first, user="bob")
        self.assertEqual(foreign["response"]["response_type"], "error")
        self.assertEqual(len(self.port.requests), 1)
        self.assertEqual(retained.content, before)
        _, foreign_device = await self.run_pipeline(
            "Other device", first, device="other"
        )
        self.assertEqual(foreign_device["response"]["response_type"], "error")
        self.assertEqual(len(self.port.requests), 1)
        second, follow = await self.run_pipeline("A follow-up", first)
        self.assertEqual(second, first)
        self.assertEqual(follow["conversation_id"], first)
        self.assertEqual(self.port.requests[-1].key, key)
        self.assertEqual(self.port.requests[-1].context.system_text.count(extra), 1)
        self.assertEqual(len(self.port.requests), 2)

    async def test_native_context_with_retained_foreign_history_is_not_imported(self):
        with async_get_chat_session(self.hass) as session:
            source = self.request(session.conversation_id, "Private original")
            with async_get_chat_log(self.hass, session, source) as log:
                log.extra_system_prompt = "private foreign context"
                log.async_add_assistant_content_without_tools(
                    AssistantContent(
                        agent_id="conversation.other", content="Private answer"
                    )
                )
            retained_id = session.conversation_id
        original = list(self.hass.data[DATA_CHAT_LOGS][retained_id].content)
        _, result = await self.run_pipeline("Must not inherit", retained_id)
        self.assertEqual(result["response"]["response_type"], "error")
        self.assertEqual(self.port.requests, [])
        self.assertEqual(self.hass.data[DATA_CHAT_LOGS][retained_id].content, original)

    async def test_matching_session_without_active_log_does_not_admit_an_unknown_id(
        self,
    ):
        with async_get_chat_session(self.hass) as session:
            result = await self.entity.async_process(
                self.request(session.conversation_id)
            )
        self.assertEqual(result.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(self.port.requests, [])
        self.assertNotIn(DATA_CHAT_LOGS, self.hass.data)

    async def test_unknown_id_needs_exact_fresh_matching_native_contexts(self):
        cases = (
            "system",
            "extra",
            "provided",
            "api",
            "attachments",
            "user",
            "session",
            "hass",
        )
        for case in cases:
            with self.subTest(case=case), async_get_chat_session(self.hass) as session:
                request = self.request(session.conversation_id)
                log_session = (
                    ChatSession("different-session") if case == "session" else session
                )
                with async_get_chat_log(self.hass, log_session, request) as log:
                    if case == "system":
                        log.content[0] = SystemContent(content="Unowned system text")
                    elif case == "extra":
                        log.extra_system_prompt = "Unowned extra text"
                    elif case == "provided":
                        log.llm_input_provided_index = 1
                    elif case == "api":
                        log.llm_api = Mock()
                    elif case == "attachments":
                        log.content[-1] = UserContent(
                            content=request.text,
                            attachments=[
                                Attachment(
                                    "fixture",
                                    "text/plain",
                                    Path(self.directory.name) / "unused",
                                )
                            ],
                        )
                    elif case == "user":
                        log.content[-1] = UserContent(content="Different utterance")
                    elif case == "hass":
                        log.hass = Mock(spec=HomeAssistant)
                    result = await self.entity.async_process(request)
                    self.assertEqual(
                        result.response.response_type, IntentResponseType.ERROR
                    )
        self.assertEqual(self.port.requests, [])

    async def test_first_native_owner_reservation_fences_concurrency_and_capacity(self):
        with async_get_chat_session(self.hass) as session:
            request = self.request(session.conversation_id)
            with async_get_chat_log(self.hass, session, request):
                self.port.release = asyncio.Event()
                first = asyncio.create_task(self.entity.async_process(request))
                await asyncio.wait_for(self.port.entered.wait(), 1)
                concurrent = await self.entity.async_process(request)
                self.assertEqual(
                    concurrent.response.response_type, IntentResponseType.ERROR
                )
                self.assertEqual(len(self.port.requests), 1)
                self.port.release.set()
                await asyncio.wait_for(first, 1)
        with patch("custom_components.plowshare.conversation.MAX_SESSIONS", 1):
            _, refused = await self.run_pipeline("A different fresh session")
        self.assertEqual(refused["response"]["response_type"], "error")
        self.assertEqual(len(self.port.requests), 1)

    async def test_native_expiry_is_a_new_visible_session_not_old_id_continuity(self):
        first, _ = await self.run_pipeline("First session")
        previous_key = self.port.requests[0].key
        old = self.hass.data[DATA_CHAT_SESSION].pop(first)
        old.async_cleanup()
        second, result = await self.run_pipeline("New native session", first)
        self.assertNotEqual(second, first)
        self.assertEqual(result["conversation_id"], second)
        self.assertEqual(len(self.port.requests), 2)
        self.assertNotEqual(previous_key, self.port.requests[1].key)
        # HA replaces an expired ULID before our entity sees the request; there
        # is no public old-ID provenance to import or promise continuity from.
        self.assertEqual(self.port.requests[1].text, "New native session")
