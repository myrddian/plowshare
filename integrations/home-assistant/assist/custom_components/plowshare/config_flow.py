"""Configure one fixed Plowshare project, service credential and house agent."""

import hashlib

import voluptuous as vol
from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_API_KEY, CONF_URL
from homeassistant.helpers.selector import (
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from ._vendor.plowshare import Refusal, TransportError
from .agent import SdkConversationAgent
from .availability import PROMPT_FIDELITY_AVAILABLE
from .config import settings_from_data
from .const import (
    CONF_AGENT,
    CONF_ALLOW_ANONYMOUS,
    CONF_PROJECT,
    CONF_REQUEST_TIMEOUT,
    CONF_REQUEST_TOPIC,
    CONF_RESPONSE_TIMEOUT,
    DEFAULT_REQUEST_TIMEOUT,
    DEFAULT_RESPONSE_TIMEOUT,
    DOMAIN,
)

SCHEMA = vol.Schema(
    {
        vol.Required(CONF_URL): TextSelector(
            TextSelectorConfig(type=TextSelectorType.URL)
        ),
        vol.Required(CONF_API_KEY): TextSelector(
            TextSelectorConfig(type=TextSelectorType.PASSWORD)
        ),
        vol.Required(CONF_PROJECT): str,
        vol.Required(CONF_AGENT): str,
        vol.Optional(CONF_REQUEST_TOPIC, default="ha.requests"): str,
        vol.Optional(CONF_ALLOW_ANONYMOUS, default=False): bool,
        vol.Optional(CONF_REQUEST_TIMEOUT, default=DEFAULT_REQUEST_TIMEOUT): vol.All(
            vol.Coerce(float), vol.Range(min=0.1, max=30)
        ),
        vol.Optional(CONF_RESPONSE_TIMEOUT, default=DEFAULT_RESPONSE_TIMEOUT): vol.All(
            vol.Coerce(float), vol.Range(min=1, max=120)
        ),
    }
)


async def _noop_save() -> None:
    """The setup probe has no state to persist."""


class PlowshareConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, object] | None = None
    ) -> ConfigFlowResult:
        if not PROMPT_FIDELITY_AVAILABLE:
            return self.async_abort(reason="prompt_fidelity_unavailable")
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                settings = settings_from_data(user_input)
                await SdkConversationAgent(settings, {}, _noop_save).validate()
            except ValueError:
                errors["base"] = "invalid_config"
            except Refusal:
                errors["base"] = "invalid_auth"
            except TransportError, TimeoutError, OSError:
                errors["base"] = "cannot_connect"
            else:
                # Tokens are deliberately excluded. Rotation updates this binding
                # instead of creating a second assistant with the same authority.
                identity = "\n".join(
                    (settings.origin.rstrip("/"), settings.project, settings.agent)
                )
                await self.async_set_unique_id(
                    hashlib.sha256(identity.encode()).hexdigest()
                )
                self._abort_if_unique_id_configured()
                return self.async_create_entry(title=settings.agent, data=user_input)
        return self.async_show_form(step_id="user", data_schema=SCHEMA, errors=errors)

    async def async_step_reconfigure(
        self, user_input: dict[str, object] | None = None
    ) -> ConfigFlowResult:
        """Rotate credentials; changing the binding requires a separate entry."""
        if not PROMPT_FIDELITY_AVAILABLE:
            return self.async_abort(reason="prompt_fidelity_unavailable")
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                settings = settings_from_data(user_input)
                old = settings_from_data(entry.data)
                if (
                    settings.origin.rstrip("/"),
                    settings.project,
                    settings.agent,
                    settings.request_topic,
                ) != (
                    old.origin.rstrip("/"),
                    old.project,
                    old.agent,
                    old.request_topic,
                ):
                    raise ValueError(
                        "Create another integration for a different binding"
                    )
                await SdkConversationAgent(settings, {}, _noop_save).validate()
            except ValueError:
                errors["base"] = "invalid_config"
            except Refusal:
                errors["base"] = "invalid_auth"
            except TransportError, TimeoutError, OSError:
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
