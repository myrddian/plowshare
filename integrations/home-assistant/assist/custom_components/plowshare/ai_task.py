"""Independent HA AI Task generation through the existing public SDK."""

from __future__ import annotations

from uuid import uuid4

from homeassistant.components.ai_task import (
    AITaskEntity,
    AITaskEntityFeature,
    GenDataTask,
    GenDataTaskResult,
)
from homeassistant.components.conversation.chat_log import (
    AssistantContent,
    ChatLog,
    SystemContent,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .agent import ProfileRuntime
from .requests import HaRequestContext, TaskRequest, digest
from .structured import (
    StructureError,
    compile_structure,
    parse_completed,
    render_instructions,
)

type PlowshareTaskEntry = ConfigEntry[ProfileRuntime]


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PlowshareTaskEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    if entry.runtime_data.task is not None:
        async_add_entities([PlowshareAITaskEntity(entry)])


class PlowshareAITaskEntity(AITaskEntity):
    """A task invocation never shares a conversation with Assist or another task."""

    _attr_has_entity_name = True
    _attr_supported_features = AITaskEntityFeature.GENERATE_DATA

    def __init__(self, entry: PlowshareTaskEntry) -> None:
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}:task"
        self._attr_name = f"{entry.title} tasks"

    async def _async_generate_data(
        self, task: GenDataTask, chat_log: ChatLog
    ) -> GenDataTaskResult:
        gateway = self._entry.runtime_data.task
        if gateway is None:
            raise HomeAssistantError("The Plowshare AI Task profile is disabled")
        if task.llm_api is not None:
            raise HomeAssistantError(
                "HA LLM tool APIs are not supported by this profile"
            )
        if task.attachments is not None and (
            type(task.attachments) is not list or task.attachments
        ):
            raise HomeAssistantError("Attachments are not supported by this profile")
        try:
            structure = (
                compile_structure(task.structure)
                if task.structure is not None
                else None
            )
        except StructureError as error:
            raise HomeAssistantError(
                "Structured task schema is not supported"
            ) from error
        if not chat_log.content or not isinstance(chat_log.content[0], SystemContent):
            raise HomeAssistantError("The HA task context is not supported")
        try:
            # The public helper creates a fresh chat session. Also mint an
            # internal invocation key: HA has no durable task retry identifier.
            # Entity._context is mutable across concurrent calls and must never
            # be used as the caller identity of this invocation.
            request = TaskRequest(
                digest(("ha-task", chat_log.conversation_id, uuid4().hex)),
                render_instructions(task.instructions, structure)
                if structure is not None
                else task.instructions,
                HaRequestContext(system_text=chat_log.content[0].content),
            )
        except (TypeError, ValueError) as error:
            raise HomeAssistantError("The HA task input is not supported") from error
        result = await gateway.generate(request)
        if result.status != "completed":
            raise HomeAssistantError(
                result.message or "Plowshare did not complete this AI Task"
            )
        if not isinstance(result.text, str):
            raise HomeAssistantError("Plowshare returned unsupported task data")
        # Validation does not start replacement model/tool work. The public
        # gateway has already retained the completed Plowshare receipt/result.
        # Unsupported data is an HA error, never an accepted partial response.
        try:
            data = (
                parse_completed(result.text, structure).as_ha_data()
                if structure is not None
                else result.text
            )
        except StructureError as error:
            raise HomeAssistantError(
                "Plowshare completed this AI Task, but its structured result is invalid; "
                "the completed work remains in Plowshare for review"
            ) from error
        chat_log.async_add_assistant_content_without_tools(
            AssistantContent(agent_id=self.entity_id, content=result.text)
        )
        return GenDataTaskResult(
            conversation_id=chat_log.conversation_id,
            data=data,
        )
