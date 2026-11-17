"""Convert HA configuration dictionaries into the validated adapter contract."""

from collections.abc import Mapping

from homeassistant.const import CONF_API_KEY, CONF_URL

from .const import (
    CONF_AGENT,
    CONF_ALLOW_ANONYMOUS,
    CONF_PROJECT,
    CONF_REQUEST_TIMEOUT,
    CONF_REQUEST_TOPIC,
    CONF_RESPONSE_TIMEOUT,
    DEFAULT_REQUEST_TIMEOUT,
    DEFAULT_RESPONSE_TIMEOUT,
)
from .models import Settings, required_text, seconds


def settings_from_data(data: Mapping[str, object]) -> Settings:
    # The dataclass validates every field, including values loaded from disk.
    allow_anonymous = data.get(CONF_ALLOW_ANONYMOUS, False)
    if type(allow_anonymous) is not bool:
        raise ValueError("Anonymous voice access must be explicitly enabled")
    return Settings(
        origin=required_text(data.get(CONF_URL), 2048),
        token=required_text(data.get(CONF_API_KEY), 8192),
        project=required_text(data.get(CONF_PROJECT)),
        agent=required_text(data.get(CONF_AGENT)),
        request_timeout=seconds(
            data.get(CONF_REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT), 30
        ),
        response_timeout=seconds(
            data.get(CONF_RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT), 120
        ),
        allow_anonymous=allow_anonymous,
        request_topic=required_text(data.get(CONF_REQUEST_TOPIC, "ha.requests")),
    )
