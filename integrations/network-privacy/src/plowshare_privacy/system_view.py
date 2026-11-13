"""A read-only description of how this collector is configured.

System health shows where the collector connects and which credentials it
expects, so an operator can see the configuration without reading the
collector's files. Secrets never appear: a credential is described by the
environment variable that holds it, or as a private file, and whether it is
present. Presence is read when the view is built, so a variable removed after
start-up shows as missing even though the running service may hold a copy.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from .connectors import REGISTRY
from .contracts import Configuration, ConnectorKind


@dataclass(frozen=True)
class Credential:
    """Where one secret comes from; never its value."""

    purpose: str
    source: Literal["environment", "file"]
    # The environment variable's name; None for a file, whose path is not shown.
    variable: str | None
    present: bool


@dataclass(frozen=True)
class PiHoleConnection:
    origin: str
    password: Credential


@dataclass(frozen=True)
class UniFiConnection:
    controller: str
    site: str
    # How the controller's certificate is trusted.
    trust: Literal["ca_file", "pinned", "system"]
    api_key: Credential
    # The legacy site name byte counters are read from; None turns them off.
    traffic_site: str | None


@dataclass(frozen=True)
class Deployment:
    server_origin: str
    project: str
    collector: str
    request_topic: str
    result_topic: str
    group: str
    outgoing_peer: str | None
    probe_timeout_seconds: float
    probe_concurrency: int
    credentials: tuple[Credential, ...]
    # The configured household zone; None means not set, so UTC is used.
    time_zone: str | None = None


@dataclass(frozen=True)
class SystemView:
    pihole: PiHoleConnection | None
    unifi: UniFiConnection | None
    # The NVD API origin advisory lookups use; None when lookups are off.
    advisory_origin: str | None
    deployment: Deployment


def _variable(environment: Mapping[str, str], purpose: str, name: str) -> Credential:
    value = environment.get(name)
    return Credential(purpose, "environment", name, bool(value and value.strip()))


# How each kind of service names its secret in the credential list.
_PURPOSES: Mapping[ConnectorKind, str] = {
    "pihole": "Pi-hole password",
    "unifi": "UniFi API key",
}


def describe(
    config: Configuration,
    advisory_origin: str | None,
    environment: Mapping[str, str],
    is_file: Callable[[Path], bool],
) -> SystemView:
    """Describe ``config`` with credential presence from ``environment``."""
    plan = config.collection
    pihole = None
    if plan.pihole is not None:
        password = (
            _variable(environment, "Pi-hole password", plan.pihole.password_environment)
            if plan.pihole.password_environment
            else Credential(
                "Pi-hole password",
                "file",
                None,
                plan.pihole.password_file is not None
                and is_file(plan.pihole.password_file),
            )
        )
        pihole = PiHoleConnection(plan.pihole.origin, password)
    unifi = None
    if plan.unifi is not None:
        unifi = UniFiConnection(
            plan.unifi.api_base,
            plan.unifi.site_id,
            "ca_file"
            if plan.unifi.ca_file is not None
            else "pinned"
            if plan.unifi.certificate_sha256
            else "system",
            _variable(environment, "UniFi API key", plan.unifi.api_key_environment),
            plan.unifi.traffic_site,
        )
    credentials = [
        _variable(
            environment, "Plowshare service credential", config.token_environment
        ),
        _variable(environment, "Dashboard access token", config.web_token_environment),
    ]
    # Environment secrets come from each configured service's connector, so a
    # new service is redacted without editing this module. A Pi-hole password
    # read from a private file has no variable name and is listed separately.
    for service in plan.sources:
        credentials += [
            _variable(environment, _PURPOSES[service.kind], name)
            for name in REGISTRY[service.kind].credential_environments(service.settings)
        ]
    if pihole is not None and pihole.password.source == "file":
        credentials.append(pihole.password)
    return SystemView(
        pihole,
        unifi,
        advisory_origin,
        Deployment(
            config.origin,
            config.project,
            config.collector,
            config.request_topic,
            config.result_topic,
            config.group,
            config.outgoing_peer,
            plan.timeout,
            plan.concurrency,
            tuple(credentials),
            config.time_zone,
        ),
    )
