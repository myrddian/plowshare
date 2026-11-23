"""HA's local diagnostics download, available independently of the read provider.

Only this explicitly constructed data section is controlled here. HA adds its
standard system/integration envelope to the full administrator-only download.
No SDK calls, journal reads/writes, probes or job resumption occur on download.
"""

from dataclasses import asdict
from datetime import UTC, datetime
from typing import Literal

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import __version__ as HA_VERSION
from homeassistant.core import HomeAssistant

from .config import configured_profiles
from .const import (
    CONF_AI_TASK_ENABLED,
    CONF_ALLOW_ANONYMOUS,
    CONF_CONTROL_ENABLED,
    CONF_CONVERSATION_ENABLED,
    INTEGRATION_VERSION,
)
from .diagnostic_models import GatewayDiagnostics
from .runtime import OWNERS


def _gateway(snapshot: GatewayDiagnostics | None) -> dict[str, object]:
    if snapshot is None:
        return {"observation": "not_observed"}
    communication = snapshot.communication
    return {
        "observation": "local_memory",
        "current_connection": "not_monitored_per_request_connections",
        "closed": snapshot.closed,
        "active_owners": snapshot.active_owners,
        "unsettled_cleanup_owners": snapshot.unsettled_cleanup_owners,
        "communication": {
            "last_connection_at": _time(communication.last_connection_at),
            "last_successful_request_at": _time(
                communication.last_successful_request_at
            ),
            "last_failure_at": _time(communication.last_failure_at),
            "last_failure_category": communication.last_failure_category,
            "last_result_at": _time(communication.last_result_at),
            "last_result_status": communication.last_result_status,
        },
    }


def _time(value: datetime | None) -> str | None:
    return value.isoformat() if value is not None else None


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry
) -> dict[str, object]:
    """Return a bounded allowlist even after failed setup or retained cleanup.

    Past communication remains historical evidence and resets on HA restart.
    The separate outbound provider and native HA device state are deliberately
    unobserved here: their availability cannot be inferred from an SDK reply.
    """
    configuration_valid = True
    try:
        configured_profiles(entry.data)
    except ValueError:
        configuration_valid = False

    def flag(key: str, default: bool) -> bool | Literal["invalid"]:
        value = entry.data.get(key, default)
        return value if type(value) is bool else "invalid"

    # Read the existing registry directly: owners(hass) would create state just
    # to produce a report. HA may clear runtime_data while cleanup stays owned.
    registry = hass.data.get(OWNERS)
    runtime = registry.entries.get(entry.entry_id) if registry is not None else None
    local: dict[str, object] = {"owner": "not_retained"}
    if runtime is not None:
        snapshot = runtime.diagnostics_snapshot()
        shared = snapshot.conversation or snapshot.task
        local = {
            "owner": "retained",
            "attached": snapshot.attached,
            "setup_finished": snapshot.setup_finished,
            "closing": snapshot.closing,
            "owned_platforms": snapshot.owned_platforms,
            "conversation": _gateway(snapshot.conversation),
            "ai_task": _gateway(snapshot.task),
            "shared_correlation_store": (
                asdict(shared.shared_correlation_store) if shared is not None else None
            ),
        }
    return {
        "schema_version": 1,
        "generated_at": datetime.now(UTC).isoformat(),
        "integration_version": INTEGRATION_VERSION,
        "home_assistant_version": HA_VERSION,
        "entry_state": entry.state.value,
        "configuration": {
            "valid": configuration_valid,
            "conversation_enabled": flag(CONF_CONVERSATION_ENABLED, True),
            "ai_task_enabled": flag(CONF_AI_TASK_ENABLED, False),
            "control_enabled": flag(CONF_CONTROL_ENABLED, False),
            "anonymous_access_enabled": flag(CONF_ALLOW_ANONYMOUS, False),
        },
        "runtime": local,
        "outbound_read_provider": "not_observed_separate_process",
        "ha_device_health": "not_observed",
    }
