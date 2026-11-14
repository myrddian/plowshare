"""Find and place services in a raw collector configuration row, in either form.

Configuration writers (setup commands and dashboard settings) load the private
JSON, change a few fields and decode the whole row before saving it. Services
live either under the legacy ``collection.pihole`` / ``collection.unifi`` keys
or in ``collection.sources``, and decoding refuses a row that uses both. These
helpers edit a service in whichever form the row already uses, so no writer
mixes the forms. They only reshape JSON: ``Configuration.decode`` remains the
validation, and every caller decodes the edited row before writing it.
"""

from __future__ import annotations

from collections.abc import Iterable

from .contracts import Capability, ConnectorKind, ServicePlan

# Services are written Pi-hole first, then UniFi, matching legacy decoding.
_KIND_ORDER: tuple[ConnectorKind, ...] = ("pihole", "unifi")


def _entries(plan: dict[str, object]) -> list[dict[str, object]]:
    entries = plan["sources"]
    if not isinstance(entries, list) or not all(
        isinstance(entry, dict) for entry in entries
    ):
        raise ValueError("Invalid collector services")
    return entries


def service_entry(
    plan: dict[str, object], kind: ConnectorKind
) -> dict[str, object] | None:
    """The ``sources`` entry of the ``kind`` service, or None in legacy form."""
    if "sources" not in plan:
        return None
    return next((entry for entry in _entries(plan) if entry.get("kind") == kind), None)


def service_settings(
    plan: dict[str, object], kind: ConnectorKind
) -> dict[str, object] | None:
    """The mutable settings object of the ``kind`` service, if configured."""
    if "sources" in plan:
        entry = service_entry(plan, kind)
        settings = entry.get("settings") if entry is not None else None
    else:
        settings = plan.get(kind)
    if settings is not None and not isinstance(settings, dict):
        raise ValueError("Invalid collector service settings")
    return settings


def put_service(
    plan: dict[str, object], kind: ConnectorKind, settings: dict[str, object]
) -> None:
    """Add the ``kind`` service, or replace its settings, keeping the row's form.

    A new ``sources`` entry takes the kind as its id and lists the capabilities
    a legacy key would enable, written explicitly so no opt-in read starts.
    """
    if "sources" not in plan:
        plan[kind] = settings
        return
    entries = _entries(plan)
    entry = service_entry(plan, kind)
    if entry is not None:
        entry["settings"] = settings
        return
    capabilities: list[Capability] = (
        ["dns_queries", "network_table"]
        if kind == "pihole"
        else ["controller_clients", "managed_inventory"]
    )
    if kind == "unifi" and "trafficSite" in settings:
        capabilities.append("traffic_counters")
    added: dict[str, object] = {
        "kind": kind,
        "id": kind,
        "capabilities": sorted(capabilities),
        "settings": settings,
    }
    entries.insert(0 if kind == "pihole" else len(entries), added)


def to_sources_form(plan: dict[str, object], services: Iterable[ServicePlan]) -> None:
    """Move legacy service keys into ``sources``; a no-op in ``sources`` form.

    ``services`` is the decoded configuration of this same row: each legacy key
    becomes one entry with its id, its currently enabled capabilities written
    explicitly, and its settings object verbatim.
    """
    if "sources" in plan:
        return
    by_kind = {service.kind: service for service in services}
    entries: list[dict[str, object]] = []
    for kind in _KIND_ORDER:
        if kind in plan:
            service = by_kind[kind]
            entries.append(
                {
                    "kind": kind,
                    "id": service.id,
                    "capabilities": sorted(service.capabilities),
                    "settings": plan.pop(kind),
                }
            )
    plan["sources"] = entries
