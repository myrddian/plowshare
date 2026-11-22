"""Configure independent SDK-only Conversation and AI Task profiles."""

from __future__ import annotations

import hashlib
import json

import voluptuous as vol
from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_API_KEY, CONF_URL
from homeassistant.helpers.selector import (
    BooleanSelector,
    NumberSelector,
    NumberSelectorConfig,
    NumberSelectorMode,
    TemplateSelector,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from ._vendor.plowshare import Refusal, TransportError
from .agent import SdkConversationAgent
from .config import EnabledProfiles, configured_profiles
from .const import (
    CONF_AGENT,
    CONF_AI_TASK_ENABLED,
    CONF_ALLOW_ANONYMOUS,
    CONF_CONTROL_ENABLED,
    CONF_CONVERSATION_ENABLED,
    CONF_PROJECT,
    CONF_PROMPT,
    CONF_READ_ONLY_AGENT,
    CONF_REQUEST_TIMEOUT,
    CONF_REQUEST_TOPIC,
    CONF_RESPONSE_TIMEOUT,
    CONF_TASK_AGENT,
    DEFAULT_REQUEST_TIMEOUT,
    DEFAULT_RESPONSE_TIMEOUT,
    DOMAIN,
)
from .validation import ValidationBusyError, ValidationUnavailableError, probe_pool

SCHEMA = vol.Schema(
    {
        vol.Required(CONF_URL): TextSelector(
            TextSelectorConfig(type=TextSelectorType.URL)
        ),
        vol.Required(CONF_API_KEY): TextSelector(
            TextSelectorConfig(type=TextSelectorType.PASSWORD)
        ),
        vol.Required(CONF_PROJECT): str,
        vol.Optional(CONF_CONVERSATION_ENABLED, default=True): BooleanSelector(),
        vol.Optional(CONF_AI_TASK_ENABLED, default=False): BooleanSelector(),
        vol.Optional(CONF_CONTROL_ENABLED, default=False): BooleanSelector(),
        vol.Optional(CONF_READ_ONLY_AGENT): str,
        vol.Optional(CONF_AGENT): str,
        vol.Optional(CONF_TASK_AGENT): str,
        vol.Optional(CONF_PROMPT): TemplateSelector(),
        vol.Optional(CONF_REQUEST_TOPIC, default="ha.requests"): str,
        vol.Optional(CONF_ALLOW_ANONYMOUS, default=False): BooleanSelector(),
        vol.Optional(
            CONF_REQUEST_TIMEOUT, default=DEFAULT_REQUEST_TIMEOUT
        ): NumberSelector(
            NumberSelectorConfig(min=0.1, max=30, step=0.1, mode=NumberSelectorMode.BOX)
        ),
        vol.Optional(
            CONF_RESPONSE_TIMEOUT, default=DEFAULT_RESPONSE_TIMEOUT
        ): NumberSelector(
            NumberSelectorConfig(min=1, max=120, step=1, mode=NumberSelectorMode.BOX)
        ),
    }
)


def _binding(profiles: EnabledProfiles) -> tuple[str | None, ...]:
    """Credentials/timeouts do not select an agent; route changes need a new entry."""
    primary = profiles.primary
    return (
        primary.origin.rstrip("/"),
        primary.project,
        primary.request_topic,
        profiles.conversation.agent if profiles.conversation is not None else None,
        profiles.task.agent if profiles.task is not None else None,
        "control" if profiles.conversation_options.control_enabled else "read_only",
    )


class PlowshareConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def _validate(self, profiles: EnabledProfiles) -> None:
        await probe_pool(self.hass).async_validate(
            profiles.primary, profiles.required_agents, SdkConversationAgent
        )

    async def async_step_user(
        self, user_input: dict[str, object] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                profiles = configured_profiles(user_input)
                await self._validate(profiles)
            except ValueError:
                errors["base"] = "invalid_config"
            except Refusal as error:
                # Current public WS outcomes do not expose a precise auth code.
                # A busy/unavailable service is not evidence of bad credentials.
                errors["base"] = (
                    "validation_busy"
                    if error.code == "CAPACITY_EXHAUSTED"
                    else "cannot_connect"
                )
            except ValidationBusyError:
                errors["base"] = "validation_busy"
            except ValidationUnavailableError, TransportError, TimeoutError, OSError:
                errors["base"] = "cannot_connect"
            else:
                # Two credentials do not prove equivalent server accounts, but
                # neither may create a duplicate entry for an identical route.
                # The runtime separately fences retained receipt token changes.
                identity = json.dumps(_binding(profiles), separators=(",", ":"))
                await self.async_set_unique_id(
                    hashlib.sha256(identity.encode()).hexdigest()
                )
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title=profiles.primary.agent, data=user_input
                )
        return self.async_show_form(step_id="user", data_schema=SCHEMA, errors=errors)

    async def async_step_reconfigure(
        self, user_input: dict[str, object] | None = None
    ) -> ConfigFlowResult:
        """Rotate credentials/timeouts without silently retargeting stored work."""
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                profiles = configured_profiles(user_input)
                old = configured_profiles(entry.data)
                if _binding(profiles) != _binding(old):
                    raise ValueError("Create another integration for different routes")
                await self._validate(profiles)
            except ValueError:
                errors["base"] = "invalid_config"
            except Refusal as error:
                # Current public WS outcomes do not expose a precise auth code.
                # A busy/unavailable service is not evidence of bad credentials.
                errors["base"] = (
                    "validation_busy"
                    if error.code == "CAPACITY_EXHAUSTED"
                    else "cannot_connect"
                )
            except ValidationBusyError:
                errors["base"] = "validation_busy"
            except ValidationUnavailableError, TransportError, TimeoutError, OSError:
                errors["base"] = "cannot_connect"
            else:
                return self.async_update_reload_and_abort(
                    entry, data_updates=user_input
                )
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=self.add_suggested_values_to_schema(SCHEMA, entry.data),
            errors=errors,
        )
