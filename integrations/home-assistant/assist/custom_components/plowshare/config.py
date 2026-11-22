"""Validate HA configuration and select explicit SDK-only profile routes.

A profile's advertised control mode is not a grant. Operators assign the selected
agents their actual Application/server permissions; the adapter never removes or
adds those permissions based on a frontend capability flag.
"""

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Literal

from homeassistant.const import CONF_API_KEY, CONF_URL

from .const import (
    CONF_AGENT,
    CONF_AI_TASK_ENABLED,
    CONF_ALLOW_ANONYMOUS,
    CONF_CONTROL_ENABLED,
    CONF_CONVERSATION_ENABLED,
    CONF_LLM_API,
    CONF_PROJECT,
    CONF_PROMPT,
    CONF_READ_ONLY_AGENT,
    CONF_REQUEST_TIMEOUT,
    CONF_REQUEST_TOPIC,
    CONF_RESPONSE_TIMEOUT,
    CONF_TASK_AGENT,
    DEFAULT_REQUEST_TIMEOUT,
    DEFAULT_RESPONSE_TIMEOUT,
    MAX_PROMPT_TEMPLATE_BYTES,
)
from .models import Settings, required_text, seconds

_ALLOWED_FIELDS = frozenset(
    {
        CONF_URL,
        CONF_API_KEY,
        CONF_PROJECT,
        CONF_AGENT,
        CONF_READ_ONLY_AGENT,
        CONF_TASK_AGENT,
        CONF_CONVERSATION_ENABLED,
        CONF_AI_TASK_ENABLED,
        CONF_CONTROL_ENABLED,
        CONF_PROMPT,
        CONF_LLM_API,
        CONF_ALLOW_ANONYMOUS,
        CONF_REQUEST_TIMEOUT,
        CONF_RESPONSE_TIMEOUT,
        CONF_REQUEST_TOPIC,
    }
)


@dataclass(frozen=True)
class ConversationOptions:
    """Declared HA behavior; tool authorization remains on Plowshare."""

    enabled: bool
    control_enabled: bool
    prompt: str | None = field(repr=False)

    def __post_init__(self) -> None:
        if type(self.enabled) is not bool or type(self.control_enabled) is not bool:
            raise ValueError("Conversation options must be booleans")
        _prompt(self.prompt)


@dataclass(frozen=True)
class EnabledProfiles:
    """Only enabled profiles carry a route; none silently inherit another agent."""

    conversation: Settings | None
    task: Settings | None
    conversation_options: ConversationOptions

    def __post_init__(self) -> None:
        for profile in (self.conversation, self.task):
            if profile is not None and type(profile) is not Settings:
                raise ValueError("A typed profile route is required")
        if type(self.conversation_options) is not ConversationOptions:
            raise ValueError("Typed Conversation options are required")
        if self.conversation_options.enabled != (self.conversation is not None):
            raise ValueError("Conversation option and route must agree")
        if self.conversation is None and self.task is None:
            raise ValueError("Enable at least one Plowshare platform")

    @property
    def primary(self) -> Settings:
        if self.conversation is not None:
            return self.conversation
        if self.task is not None:
            return self.task
        raise ValueError("Enable at least one Plowshare platform")

    @property
    def required_agents(self) -> tuple[str, ...]:
        return tuple(
            dict.fromkeys(
                profile.agent
                for profile in (self.conversation, self.task)
                if profile is not None
            )
        )


def _flag(data: Mapping[str, object], key: str, default: bool) -> bool:
    value = data.get(key, default)
    if type(value) is not bool:
        raise ValueError("Platform and control options must be explicit booleans")
    return value


def _prompt(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise ValueError("The configured prompt must be non-empty text")
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError as error:
        raise ValueError("The configured prompt must be valid Unicode") from error
    if size > MAX_PROMPT_TEMPLATE_BYTES or any(
        ord(char) < 32 and char not in "\n\r\t" for char in value
    ):
        raise ValueError("The configured prompt exceeds its resource boundary")
    # This is template source, not an identifier. Preserve its whitespace exactly.
    return value


def configured_profiles(data: Mapping[str, object]) -> EnabledProfiles:
    """Convert the configuration boundary completely before any SDK operation."""
    if any(key not in _ALLOWED_FIELDS for key in data):
        raise ValueError("Unsupported Plowshare configuration option")
    if data.get(CONF_LLM_API) is not None:
        raise ValueError("Home Assistant LLM tool APIs are not supported")

    conversation_enabled = _flag(data, CONF_CONVERSATION_ENABLED, True)
    task_enabled = _flag(data, CONF_AI_TASK_ENABLED, False)
    control_enabled = _flag(data, CONF_CONTROL_ENABLED, False)
    allow_anonymous = _flag(data, CONF_ALLOW_ANONYMOUS, False)
    if not (conversation_enabled or task_enabled):
        raise ValueError("Enable at least one Plowshare platform")
    prompt = _prompt(data.get(CONF_PROMPT))

    # Validate present fields even for disabled profiles. Such fields never become
    # routes unless their corresponding profile is explicitly enabled.
    for key in (CONF_AGENT, CONF_READ_ONLY_AGENT, CONF_TASK_AGENT):
        if key in data:
            required_text(data[key])
    origin = required_text(data.get(CONF_URL), 2048)
    token = required_text(data.get(CONF_API_KEY), 8192)
    project = required_text(data.get(CONF_PROJECT))
    request_timeout = seconds(
        data.get(CONF_REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT), 30
    )
    response_timeout = seconds(
        data.get(CONF_RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT), 120
    )
    request_topic = required_text(data.get(CONF_REQUEST_TOPIC, "ha.requests"))

    def selected(key: str) -> Settings:
        return Settings(
            origin=origin,
            token=token,
            project=project,
            agent=required_text(data.get(key)),
            request_timeout=request_timeout,
            response_timeout=response_timeout,
            allow_anonymous=allow_anonymous,
            request_topic=request_topic,
        )

    return EnabledProfiles(
        conversation=(
            selected(CONF_AGENT if control_enabled else CONF_READ_ONLY_AGENT)
            if conversation_enabled
            else None
        ),
        task=selected(CONF_TASK_AGENT) if task_enabled else None,
        conversation_options=ConversationOptions(
            conversation_enabled, control_enabled, prompt
        ),
    )


def settings_from_data(
    data: Mapping[str, object],
    profile: Literal["conversation", "task"] = "conversation",
) -> Settings:
    """Select an enabled route; a disabled profile never falls back to another."""
    profiles = configured_profiles(data)
    if profile == "conversation":
        result = profiles.conversation
    elif profile == "task":
        result = profiles.task
    else:
        raise ValueError("Unsupported Plowshare profile")
    if result is None:
        raise ValueError("The selected Plowshare platform is disabled")
    return result
