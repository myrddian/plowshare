"""HA owns audio and its chat UI; Plowshare owns agent history and tools."""

from typing import Literal

from homeassistant.components import conversation
from homeassistant.components.conversation.chat_log import AssistantContent, ChatLog
from homeassistant.core import HomeAssistant
from homeassistant.helpers import intent
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import PlowshareConfigEntry
from .config import settings_from_data
from .models import session_key


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PlowshareConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    async_add_entities([PlowshareConversationEntity(entry)])


class PlowshareConversationEntity(conversation.ConversationEntity):
    _attr_has_entity_name = True
    _attr_name = None
    # Control is provided by the EXISTING selected-action Relay adapter. We do
    # not expose HA's LLM tools or infer permissions from entity exposure lists.
    _attr_supported_features = conversation.ConversationEntityFeature.CONTROL

    def __init__(self, entry: PlowshareConfigEntry) -> None:
        self._entry = entry
        self._settings = settings_from_data(entry.data)
        self._attr_unique_id = entry.entry_id
        self._attr_name = entry.title

    @property
    def supported_languages(self) -> Literal["*"]:
        return "*"

    async def _async_handle_message(
        self, user_input: conversation.ConversationInput, chat_log: ChatLog
    ) -> conversation.ConversationResult:
        try:
            key = session_key(
                chat_log.conversation_id,
                user_input.context.user_id,
                user_input.device_id,
                user_input.satellite_id,
                self._settings.allow_anonymous,
            )
        except ValueError, PermissionError:
            response = intent.IntentResponse(language=user_input.language)
            response.async_set_error(
                intent.IntentResponseErrorCode.FAILED_TO_HANDLE,
                "This voice source is not enabled for the house assistant.",
            )
            return conversation.ConversationResult(
                response=response, conversation_id=chat_log.conversation_id
            )
        answer = await self._entry.runtime_data.ask(key, user_input.text)
        chat_log.async_add_assistant_content_without_tools(
            AssistantContent(agent_id=user_input.agent_id, content=answer.text)
        )
        response = intent.IntentResponse(language=user_input.language)
        if answer.error:
            response.async_set_error(
                intent.IntentResponseErrorCode.FAILED_TO_HANDLE, answer.text
            )
        else:
            response.async_set_speech(answer.text)
        return conversation.ConversationResult(
            response=response,
            conversation_id=chat_log.conversation_id,
            continue_conversation=not answer.error
            and answer.text.rstrip().endswith(("?", "？", ";")),
        )
