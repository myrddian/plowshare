"""HA conversation lifecycle over the typed public-SDK gateway.

HA's ChatLog is shared by HA chat ID, whereas Plowshare correlation is also
partitioned by caller/profile. Claim the HA log's source before the adapter opens
or prepares it so a foreign caller cannot inherit its retained extra prompt or
history. Assist may already have opened a fresh log upstream; admission uses its
active public contexts rather than treating an arbitrary supplied ID as ownership.
Ownership is bounded, lives for this HA process and never replaces the SDK journal.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Literal

from homeassistant.components import conversation
from homeassistant.components.conversation.chat_log import (
    AssistantContent,
    ChatLog,
    ConverseError,
    SystemContent,
    UserContent,
    current_chat_log,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers import intent
from homeassistant.helpers.chat_session import CONVERSATION_TIMEOUT, current_session
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util.hass_dict import HassKey

from . import PlowshareConfigEntry
from .agent import ProfileRuntime
from .config import configured_profiles
from .const import DOMAIN, MAX_SESSIONS
from .models import required_text, session_key
from .requests import ConversationRequest, GatewayResult, HaRequestContext, digest


@dataclass
class _ChatOwner:
    identity: str
    last_used: float
    active: bool = False
    expired_transition: bool = False


_OWNERS: HassKey[dict[str, _ChatOwner]] = HassKey("plowshare_conversation_owners")
# Outlive HA's ordinary chat expiry so its replacement-ID transition can be
# reported explicitly before older idle ownership itself becomes unsupported.
_OWNER_RETENTION = 2 * CONVERSATION_TIMEOUT.total_seconds()


def _error(
    language: str, conversation_id: str | None, message: str
) -> conversation.ConversationResult:
    response = intent.IntentResponse(language=language)
    response.async_set_error(intent.IntentResponseErrorCode.FAILED_TO_HANDLE, message)
    return conversation.ConversationResult(
        response=response, conversation_id=conversation_id
    )


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PlowshareConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    if configured_profiles(entry.data).conversation is not None:
        async_add_entities([PlowshareConversationEntity(entry)])


class PlowshareConversationEntity(conversation.ConversationEntity):
    _attr_has_entity_name = True
    _attr_name = None

    def __init__(self, entry: PlowshareConfigEntry) -> None:
        profiles = configured_profiles(entry.data)
        if profiles.conversation is None:
            raise ValueError("The conversation profile is disabled")
        self._entry = entry
        self._settings = profiles.conversation
        self._options = profiles.conversation_options
        self._attr_unique_id = entry.entry_id
        self._attr_name = entry.title
        self._attr_supported_features = (
            conversation.ConversationEntityFeature.CONTROL
            if self._options.control_enabled
            else conversation.ConversationEntityFeature(0)
        )
        # This declaration never edits the selected agent's actual server grants.
        self._profile = "control" if self._options.control_enabled else "read_only"

    @property
    def supported_languages(self) -> Literal["*"]:
        return "*"

    def _owners(self) -> dict[str, _ChatOwner]:
        owners = self.hass.data.get(_OWNERS)
        if owners is None:
            owners = {}
            self.hass.data[_OWNERS] = owners
        now = self.hass.loop.time()
        for key, owner in tuple(owners.items()):
            if not owner.active and now - owner.last_used > _OWNER_RETENTION:
                del owners[key]
        return owners

    def _identity(self, user_input: conversation.ConversationInput) -> str:
        source = session_key(
            "source",
            user_input.context.user_id,
            user_input.device_id,
            user_input.satellite_id,
            self._settings.allow_anonymous,
        )
        return digest(
            (
                self._entry.entry_id,
                self._profile,
                self._settings.origin,
                self._settings.project,
                self._settings.agent,
                source,
            )
        )

    def _fresh_native_log(self, user_input: conversation.ConversationInput) -> bool:
        """Recognize the exact empty first-turn context prepared by HA Assist.

        HA 2026.9.4's pipeline allocates its session and opens the ChatLog before
        calling the conversation entity. Both public context variables must name
        that request, and there must be no prior history/context to inherit. A
        supplied ID alone is never evidence. Reading the current contexts does
        not open, mutate or inspect HA's global session/log caches.
        """
        session = current_session.get()
        chat_log = current_chat_log.get()
        if (
            session is None
            or chat_log is None
            or session.conversation_id != user_input.conversation_id
            or chat_log.conversation_id != user_input.conversation_id
            or chat_log.hass is not self.hass
            or chat_log.extra_system_prompt is not None
            or chat_log.llm_api is not None
            or chat_log.llm_input_provided_index != 0
            or len(chat_log.content) != 2
        ):
            return False
        system, current_user = chat_log.content
        return (
            isinstance(system, SystemContent)
            and system.content == ""
            and isinstance(current_user, UserContent)
            and current_user.content == user_input.text
            and current_user.attachments is None
        )

    async def async_process(
        self, user_input: conversation.ConversationInput
    ) -> conversation.ConversationResult:
        """Fence ownership before the adapter's log/context and SDK work.

        Direct first calls omit the ID. Assist supplies the ID of an already
        active empty log, admitted only by the narrow native-context check. A
        retained/history-bearing unknown ID is never imported. HA can mutate its
        outer pipeline log before this method runs; this fence cannot undo that
        upstream behavior. SDK receipts remain retained independently.
        """
        try:
            identity = self._identity(user_input)
            extra = user_input.extra_system_prompt
            context = HaRequestContext(
                system_text="" if extra is None else extra,
                language=user_input.language,
                user_id=user_input.context.user_id,
                device_id=user_input.device_id,
                satellite_id=user_input.satellite_id,
            )
            # Validate the current input before HA log mutation or template work.
            ConversationRequest(
                session_key(
                    "input", *context.identity(), self._settings.allow_anonymous
                ),
                user_input.text,
                context,
            )
            owners = self._owners()
            if user_input.conversation_id is not None:
                supplied = required_text(user_input.conversation_id)
                owner = owners.get(supplied)
                if owner is None and self._fresh_native_log(user_input):
                    if len(owners) >= MAX_SESSIONS:
                        return _error(
                            user_input.language,
                            supplied,
                            "Too many HA conversation sources are retained. Try after an idle chat expires.",
                        )
                    # Reserve synchronously before super() can await. Concurrent
                    # first turns and other sources cannot claim the same log.
                    owner = _ChatOwner(identity, self.hass.loop.time())
                    owners[supplied] = owner
                if owner is None or owner.identity != identity:
                    return _error(
                        user_input.language,
                        supplied,
                        "This HA conversation is not owned by this source. Start a new chat without a conversation ID; existing work was not repeated.",
                    )
                if owner.active:
                    return _error(
                        user_input.language,
                        supplied,
                        "This HA conversation is already processing a request.",
                    )
                if owner.expired_transition:
                    return _error(
                        user_input.language,
                        supplied,
                        "The earlier HA chat expired. Inspect its retained work in Plowshare before starting a new chat.",
                    )
                # Reserve before the base method's first await, so two calls
                # cannot race HA's retained extra-prompt mutation.
                owner.active = True
            elif len(owners) >= MAX_SESSIONS:
                return _error(
                    user_input.language,
                    None,
                    "Too many HA conversation sources are retained. Try after an idle chat expires.",
                )
        except ValueError, PermissionError:
            return _error(
                user_input.language,
                user_input.conversation_id,
                "This conversation input or voice source is not supported.",
            )
        try:
            return await super().async_process(user_input)
        finally:
            # _async_handle_message also claims the actual generated ID. An
            # expired HA ID may differ, and neither record is a durable receipt.
            for key in (user_input.conversation_id,):
                owner = owners.get(key) if key is not None else None
                if owner is not None and owner.identity == identity:
                    owner.active = False
                    owner.last_used = self.hass.loop.time()

    async def _async_handle_message(
        self, user_input: conversation.ConversationInput, chat_log: ChatLog
    ) -> conversation.ConversationResult:
        owners = self._owners()
        identity = self._identity(user_input)
        owner = owners.get(chat_log.conversation_id)
        if owner is None:
            if len(owners) >= MAX_SESSIONS:
                return _error(
                    user_input.language,
                    chat_log.conversation_id,
                    "Too many HA conversation sources are retained.",
                )
            owner = _ChatOwner(identity, self.hass.loop.time(), active=True)
            owners[chat_log.conversation_id] = owner
        if owner.identity != identity:
            return _error(
                user_input.language,
                chat_log.conversation_id,
                "This HA conversation is not owned by this source.",
            )
        try:
            if (
                user_input.conversation_id is not None
                and user_input.conversation_id != chat_log.conversation_id
            ):
                # HA expired its chat. There is no public gateway peek/import
                # operation proving old pending work can be abandoned. Return the
                # new HA ID without submitting this utterance as replacement work.
                owner.expired_transition = True
                return _error(
                    user_input.language,
                    chat_log.conversation_id,
                    "The earlier HA chat expired. Its Plowshare work is retained and this request was not submitted. Inspect that work before starting a new chat.",
                )
            runtime = self._entry.runtime_data
            if not isinstance(runtime, ProfileRuntime) or runtime.conversation is None:
                return _error(
                    user_input.language,
                    chat_log.conversation_id,
                    "The Plowshare conversation profile is unavailable.",
                )
            try:
                async with asyncio.timeout(self._settings.request_timeout):
                    await chat_log.async_provide_llm_data(
                        user_input.as_llm_context(DOMAIN),
                        user_llm_hass_api=None,
                        user_llm_prompt=self._options.prompt,
                        user_extra_system_prompt=user_input.extra_system_prompt,
                    )
                first = chat_log.content[0]
                if not isinstance(first, SystemContent):
                    raise ValueError("HA did not provide system context")
                request = ConversationRequest(
                    session_key(
                        chat_log.conversation_id,
                        user_input.context.user_id,
                        user_input.device_id,
                        user_input.satellite_id,
                        self._settings.allow_anonymous,
                    ),
                    user_input.text,
                    HaRequestContext(
                        system_text=first.content,
                        language=user_input.language,
                        user_id=user_input.context.user_id,
                        device_id=user_input.device_id,
                        satellite_id=user_input.satellite_id,
                    ),
                )
            except ConverseError as error:
                return error.as_conversation_result()
            except ValueError, TimeoutError:
                return _error(
                    user_input.language,
                    chat_log.conversation_id,
                    "Home Assistant could not prepare supported request context.",
                )
            result = await runtime.conversation.converse(request)
            return self._present(user_input, chat_log, result)
        finally:
            owner.active = False
            owner.last_used = self.hass.loop.time()

    def _present(
        self,
        user_input: conversation.ConversationInput,
        chat_log: ChatLog,
        result: GatewayResult,
    ) -> conversation.ConversationResult:
        completed = result.status == "completed"
        text = result.text if completed else result.message
        if text is None:
            raise ValueError("The gateway did not return a typed conversation outcome")
        chat_log.async_add_assistant_content_without_tools(
            AssistantContent(agent_id=user_input.agent_id, content=text)
        )
        response = intent.IntentResponse(language=user_input.language)
        if completed or result.status == "pending":
            response.async_set_speech(text)
        else:
            response.async_set_error(
                intent.IntentResponseErrorCode.FAILED_TO_HANDLE, text
            )
        return conversation.ConversationResult(
            response=response,
            conversation_id=chat_log.conversation_id,
            continue_conversation=completed and chat_log.continue_conversation,
        )
