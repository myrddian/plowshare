"""The fixed registry of connector kinds and their binding to typed readers.

Each `Connector` describes one kind of external service: what it can offer,
which settings fields an operator fills in, which settings define collection
scope, which settings name a credential, and how a configured service becomes
the readers in a `SourceSet`. The registry is a fixed mapping in this package;
no code is loaded from elsewhere.

A service that enables at least one readable capability is constructed at
binding, which reads its credential, so a missing or invalid secret fails at
startup rather than silently dropping a source. A service that binds an empty
`SourceSet` constructs nothing and reads no credential.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import asdict, dataclass
from types import MappingProxyType
from typing import Literal

from .contracts import (
    KIND_CAPABILITIES,
    Capability,
    CollectionPlan,
    ConnectorKind,
    PiHolePlan,
    ServicePlan,
    UniFiPlan,
)
from .identity_source import IdentitySample
from .pihole import DeviceDataSource, PiHoleV6
from .source_set import (
    AddressingReader,
    Bound,
    DnsActivityReader,
    PresenceReader,
    SourceSet,
    TrafficReader,
)
from .unifi import UniFiNetwork


@dataclass(frozen=True)
class SettingField:
    """Description of one settings key, for building forms and prompts (data only)."""

    key: str
    label: str
    kind: Literal[
        "url", "text", "integer", "secret_file", "environment", "certificate_pin"
    ]
    required: bool
    help: str


@dataclass(frozen=True)
class Connector:
    kind: ConnectorKind
    display_name: str
    # The ``DeviceIdentity.source`` value its identities carry.
    source: str
    capabilities: frozenset[Capability]
    settings_fields: tuple[SettingField, ...]
    # Settings that define what a collection covers, for the scope fingerprint.
    scope_settings: Callable[[PiHolePlan | UniFiPlan], dict[str, object]]
    # Environment variable names holding this service's secrets.
    credential_environments: Callable[[PiHolePlan | UniFiPlan], tuple[str, ...]]
    bind: Callable[[ServicePlan], SourceSet]


def _pihole_settings(settings: PiHolePlan | UniFiPlan) -> PiHolePlan:
    if not isinstance(settings, PiHolePlan):
        raise ValueError("Pi-hole connector requires Pi-hole settings")
    return settings


def _unifi_settings(settings: PiHolePlan | UniFiPlan) -> UniFiPlan:
    if not isinstance(settings, UniFiPlan):
        raise ValueError("UniFi connector requires UniFi settings")
    return settings


def _pihole_scope(settings: PiHolePlan | UniFiPlan) -> dict[str, object]:
    plan = _pihole_settings(settings)
    values = asdict(plan)
    values["password_file"] = str(plan.password_file) if plan.password_file else None
    # It decides what is inferred static, so it is scope once set; unset, the
    # key is left out so untouched configurations keep their fingerprint.
    if plan.dhcp_network is None:
        values.pop("dhcp_network")
    else:
        values["dhcp_network"] = str(plan.dhcp_network)
    return values


def _unifi_scope(settings: PiHolePlan | UniFiPlan) -> dict[str, object]:
    plan = _unifi_settings(settings)
    values = asdict(plan)
    values["ca_file"] = str(plan.ca_file) if plan.ca_file else None
    if plan.certificate_sha256 is None:
        values.pop("certificate_sha256")
    # A controller alias enriches identity evidence without changing probe scope.
    values.pop("gateway")
    # Byte counters are local presence data and never change scope.
    values.pop("traffic_site")
    return values


def _pihole_credentials(settings: PiHolePlan | UniFiPlan) -> tuple[str, ...]:
    environment = _pihole_settings(settings).password_environment
    return (environment,) if environment is not None else ()


def _unifi_credentials(settings: PiHolePlan | UniFiPlan) -> tuple[str, ...]:
    return (_unifi_settings(settings).api_key_environment,)


# A Pi-hole device read serves these; DHCP facts ride on its session.
_PIHOLE_DEVICE_READ: frozenset[Capability] = frozenset(
    {
        "network_table",
        "dns_queries",
        "dhcp_scope",
        "dhcp_reservations",
        "dhcp_leases",
        "local_dns_names",
    }
)


def _bind_pihole(service: ServicePlan) -> SourceSet:
    enabled = service.capabilities
    if not enabled & _PIHOLE_DEVICE_READ:
        return SourceSet()
    pihole = PiHoleV6(
        _pihole_settings(service.settings),
        capabilities=enabled,
        service=service.id,
    )
    return SourceSet(
        device_reads=(Bound[DeviceDataSource](service.id, "pihole", pihole),),
        presence=(Bound[PresenceReader](service.id, "pihole", pihole),)
        if "network_table" in enabled
        else (),
        dns_activity=Bound[DnsActivityReader](service.id, "pihole", pihole)
        if "dns_queries" in enabled
        else None,
    )


class _ControllerIdentity:
    """A UniFi identity reader that already merges adopted inventory."""

    def __init__(self, network: UniFiNetwork):
        self._network = network

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        return await self._network.controller_identity(targets, observed_at)


# UniFi's fixed IPs and DHCP ranges are read by its own addressing reader.
_UNIFI_ADDRESSING: frozenset[Capability] = frozenset(
    {"dhcp_reservations", "dhcp_scope"}
)


def _bind_unifi(service: ServicePlan) -> SourceSet:
    enabled = service.capabilities
    if not enabled & {
        "controller_clients",
        "managed_inventory",
        "traffic_counters",
        *_UNIFI_ADDRESSING,
    }:
        return SourceSet()
    network = UniFiNetwork(
        _unifi_settings(service.settings), capabilities=enabled, service=service.id
    )
    managed = "managed_inventory" in enabled
    return SourceSet(
        identity=(Bound(service.id, "unifi", _ControllerIdentity(network)),)
        if "controller_clients" in enabled
        else (),
        # reads_traffic is false without a legacy site name.
        traffic=Bound[TrafficReader](service.id, "unifi", network)
        if network.reads_traffic
        else None,
        product=network if managed else None,
        managed=(network,) if managed else (),
        addressing=(Bound[AddressingReader](service.id, "unifi", network),)
        if enabled & _UNIFI_ADDRESSING
        else (),
    )


_PIHOLE_FIELDS = (
    SettingField(
        "origin",
        "Pi-hole address",
        "url",
        True,
        "HTTP(S) origin of the Pi-hole web interface, without a path.",
    ),
    SettingField(
        "passwordFile",
        "Application password file",
        "secret_file",
        False,
        "Absolute path of a private (0600) file holding the application "
        "password. Set this or the environment variable, not both.",
    ),
    SettingField(
        "passwordEnvironment",
        "Application password variable",
        "environment",
        False,
        "Environment variable holding the application password. Set this or "
        "the password file, not both.",
    ),
    SettingField(
        "lookbackSeconds",
        "DNS lookback (seconds)",
        "integer",
        False,
        "How far back each collection reads queries; 60 to 86400, default 3600.",
    ),
    SettingField(
        "queryLimit",
        "Queries per device",
        "integer",
        False,
        "Most queries read per device per collection; 1 to 256, default 64.",
    ),
    SettingField(
        "maxIdentityAgeSeconds",
        "Identity freshness (seconds)",
        "integer",
        False,
        "Oldest network-table association accepted; 60 to 604800, default 86400.",
    ),
    SettingField(
        "timeoutSeconds",
        "Request timeout (seconds)",
        "integer",
        False,
        "Timeout for each Pi-hole session; 0.1 to 10, default 5.",
    ),
    SettingField(
        "dhcpNetwork",
        "DHCP network (when Pi-hole's netmask is empty)",
        "text",
        False,
        "Private IPv4 CIDR of the DHCP LAN, such as 192.168.1.0/24. Needed only "
        "when Pi-hole's DHCP netmask is empty; without it, addresses are never "
        "inferred static.",
    ),
)

# Accepted by the decoder but not typed into a form: ``gateway`` is a nested
# {deviceId, lanMac} alias written by the gateway-binding command in bootstrap.
_PIHOLE_NON_FORM_KEYS: frozenset[str] = frozenset()
_UNIFI_NON_FORM_KEYS: frozenset[str] = frozenset({"gateway"})

_UNIFI_FIELDS = (
    SettingField(
        "apiBase",
        "Network Integration API",
        "url",
        True,
        "Local HTTPS base ending in /proxy/network/integration/v1 or /integration/v1.",
    ),
    SettingField("siteId", "Site ID", "text", True, "The site's UUID."),
    SettingField(
        "apiKeyEnvironment",
        "API key variable",
        "environment",
        True,
        "Environment variable holding a read-only UniFi API key.",
    ),
    SettingField(
        "caFile",
        "CA certificate file",
        "secret_file",
        False,
        "Absolute path of a CA bundle for the controller. Set this or a "
        "certificate pin, not both.",
    ),
    SettingField(
        "certificateSha256",
        "Certificate pin",
        "certificate_pin",
        False,
        "SHA-256 of the controller certificate, as 64 hex digits.",
    ),
    SettingField(
        "trafficSite",
        "Legacy site name",
        "text",
        False,
        "Short site name (often 'default'); needed for traffic counters.",
    ),
)

REGISTRY: Mapping[ConnectorKind, Connector] = MappingProxyType(
    {
        "pihole": Connector(
            "pihole",
            "Pi-hole",
            "pihole-v6",
            KIND_CAPABILITIES["pihole"],
            _PIHOLE_FIELDS,
            _pihole_scope,
            _pihole_credentials,
            _bind_pihole,
        ),
        "unifi": Connector(
            "unifi",
            "UniFi",
            "unifi",
            KIND_CAPABILITIES["unifi"],
            _UNIFI_FIELDS,
            _unifi_scope,
            _unifi_credentials,
            _bind_unifi,
        ),
    }
)


def bind(plan: CollectionPlan) -> SourceSet:
    """Bind every configured service, in configuration order, into one set.

    Decode already allows at most one ``dns_queries`` and one
    ``traffic_counters`` service; binding defends that invariant.
    """
    sets = [REGISTRY[service.kind].bind(service) for service in plan.sources]
    dns = [s.dns_activity for s in sets if s.dns_activity is not None]
    traffic = [s.traffic for s in sets if s.traffic is not None]
    products = [s.product for s in sets if s.product is not None]
    for name, bound in (
        ("DNS activity", dns),
        ("traffic", traffic),
        ("product lookup", products),
    ):
        if len(bound) > 1:
            raise ValueError("Bind " + name + " from at most one service")
    return SourceSet(
        device_reads=tuple(b for s in sets for b in s.device_reads),
        identity=tuple(b for s in sets for b in s.identity),
        presence=tuple(b for s in sets for b in s.presence),
        dns_activity=dns[0] if dns else None,
        traffic=traffic[0] if traffic else None,
        product=products[0] if products else None,
        managed=tuple(m for s in sets for m in s.managed),
        addressing=tuple(b for s in sets for b in s.addressing),
    )
