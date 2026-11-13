"""Closed application DTOs and strict JSON boundaries, independent of SDK envelopes."""

from __future__ import annotations

import ipaddress
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import AbstractSet, Literal, Mapping, get_args
from urllib.parse import urlsplit
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .limits import (
    MAX_CHANGES,
    MAX_DNS_QUERIES,
    MAX_DNS_ROWS,
    MAX_PORTS,
    MAX_PROBES,
    MAX_TARGETS,
)

MAX_FILE_BYTES = 1_048_576


def object_fields(
    value: object, required: set[str], optional: AbstractSet[str] = frozenset()
) -> dict[str, object]:
    """Validate an object at a JSON boundary; dictionaries never enter domain contracts."""
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise ValueError("Expected a JSON object")
    row: dict[str, object] = {key: item for key, item in value.items()}
    if set(row) - required - optional or required - set(row):
        raise ValueError("Missing or unsupported fields")
    return row


def text(value: object, limit: int = 256) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > limit
        or any(ord(c) < 32 for c in value)
    ):
        raise ValueError("Expected bounded nonempty text without control characters")
    return value


def identifier(value: object) -> str:
    result = text(value, 128)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", result):
        raise ValueError("Invalid identifier")
    return result


def integer(value: object, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Integer outside supported bounds")
    return value


def items(value: object, maximum: int) -> list[object]:
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError("Expected bounded array")
    return list(value)


def timestamp(value: object) -> str:
    result = text(value, 64)
    parsed = datetime.fromisoformat(result.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Timestamp requires an offset")
    return result


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def same_instant(first: str, second: str) -> bool:
    """Retained servers may normalize fractional seconds or UTC offset spelling."""
    return datetime.fromisoformat(
        timestamp(first).replace("Z", "+00:00")
    ) == datetime.fromisoformat(timestamp(second).replace("Z", "+00:00"))


def uuid(value: object) -> str:
    result = text(value, 36)
    if str(UUID(result)) != result:
        raise ValueError("Expected canonical UUID")
    return result


def load_json(path: Path, *, maximum_bytes: int = MAX_FILE_BYTES) -> object:
    with path.open("rb") as source:
        content = source.read(maximum_bytes + 1)
    if len(content) > maximum_bytes:
        raise ValueError("JSON file exceeds the size limit")

    return parse_json(content, maximum_bytes=maximum_bytes)


def parse_json(content: str | bytes, *, maximum_bytes: int = MAX_FILE_BYTES) -> object:
    if len(content.encode() if isinstance(content, str) else content) > maximum_bytes:
        raise ValueError("JSON exceeds the size limit")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        row: dict[str, object] = {}
        for key, value in pairs:
            if key in row:
                raise ValueError("Duplicate JSON field")
            row[key] = value
        return row

    def invalid_constant(value: str) -> object:
        raise ValueError("Nonfinite JSON number")

    result: object = json.loads(
        content, object_pairs_hook=unique, parse_constant=invalid_constant
    )
    return result


@dataclass(frozen=True)
class TcpObservation:
    address: str
    port: int
    status: Literal["open", "closed", "timeout", "unreachable"]

    @classmethod
    def decode(cls, value: object) -> TcpObservation:
        row = object_fields(value, {"address", "port", "status"})
        address = str(ipaddress.ip_address(text(row["address"])))
        status = row["status"]
        if status not in ("open", "closed", "timeout", "unreachable"):
            raise ValueError("Unsupported TCP status")
        if status == "open":
            return cls(address, integer(row["port"], 1, 65535), "open")
        if status == "closed":
            return cls(address, integer(row["port"], 1, 65535), "closed")
        if status == "timeout":
            return cls(address, integer(row["port"], 1, 65535), "timeout")
        return cls(address, integer(row["port"], 1, 65535), "unreachable")


@dataclass(frozen=True)
class DnsObservation:
    device: str
    domain: str
    queries: int

    @classmethod
    def decode(cls, value: object) -> DnsObservation:
        row = object_fields(value, {"device", "domain", "queries"})
        domain = text(row["domain"], 253)
        if any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in domain.split(".")
        ):
            raise ValueError("Expected a lowercase DNS name")
        return cls(
            identifier(row["device"]), domain, integer(row["queries"], 1, 1_000_000)
        )


def mac_address(value: object) -> str:
    mac = text(value, 17).lower()
    if not re.fullmatch(r"[0-9a-f]{2}(?::[0-9a-f]{2}){5}", mac) or mac in {
        "00:00:00:00:00:00",
        "ff:ff:ff:ff:ff:ff",
    }:
        raise ValueError("Invalid device MAC address")
    return mac


EvidenceKind = Literal[
    "configured",
    "neighbour_table",
    "dhcp_lease",
    "controller_client",
    "managed_device",
    "local_dns",
]
_EVIDENCE_KINDS: dict[str, EvidenceKind] = {
    kind: kind for kind in get_args(EvidenceKind)
}
IDENTITY_SOURCES: frozenset[str] = frozenset({"configured", "pihole-v6", "unifi"})
# Evidence kind implied by a source when a record states none.
_LEGACY_KIND: dict[str, EvidenceKind] = {
    "configured": "configured",
    "pihole-v6": "neighbour_table",
    "unifi": "controller_client",
}


@dataclass(frozen=True)
class DeviceIdentity:
    """A time-specific address association, not proof of a physical device's identity."""

    address: str
    device_id: str
    label: str | None
    hostname: str | None
    mac: str | None
    vendor: str | None
    source: Literal["configured", "pihole-v6", "unifi"]
    observed_at: str
    last_seen: str | None
    # Optional provenance appended last: older receipts and positional callers
    # omit it, and encode() drops absent values so retained text is unchanged.
    evidence: EvidenceKind | None = None
    service: str | None = None

    @classmethod
    def decode(cls, value: object) -> DeviceIdentity:
        row = object_fields(
            value,
            {
                "address",
                "device_id",
                "label",
                "hostname",
                "mac",
                "vendor",
                "source",
                "observed_at",
                "last_seen",
            },
            {"evidence", "service"},
        )
        address = str(ipaddress.ip_address(text(row["address"])))
        mac = mac_address(row["mac"]) if row["mac"] is not None else None
        expected = "mac:" + mac if mac else "ip:" + address
        source = row["source"]
        if (
            row["device_id"] != expected
            or not isinstance(source, str)
            or source not in IDENTITY_SOURCES
        ):
            raise ValueError("Invalid device identity provenance")
        kind = row.get("evidence")
        if kind is not None and (
            not isinstance(kind, str)
            or kind not in _EVIDENCE_KINDS
            or (kind == "configured") != (source == "configured")
        ):
            raise ValueError("Invalid device evidence kind")
        service = row.get("service")
        if service is not None:
            service = identifier(service)
            if len(service) > 64:
                raise ValueError("Invalid device service name")
        if source == "unifi" and mac is None:
            raise ValueError("UniFi association requires a client MAC address")
        if row["source"] == "configured" and (
            mac is not None
            or row["hostname"] is not None
            or row["vendor"] is not None
            or row["last_seen"] is not None
        ):
            raise ValueError("Configured labels cannot assert discovered identity")
        return cls(
            address,
            expected,
            text(row["label"], 128) if row["label"] is not None else None,
            text(row["hostname"], 128) if row["hostname"] is not None else None,
            mac,
            text(row["vendor"], 96) if row["vendor"] is not None else None,
            "unifi"
            if source == "unifi"
            else "pihole-v6"
            if source == "pihole-v6"
            else "configured",
            timestamp(row["observed_at"]),
            timestamp(row["last_seen"]) if row["last_seen"] is not None else None,
            _EVIDENCE_KINDS[kind] if isinstance(kind, str) else None,
            service,
        )


def evidence_kind(identity: DeviceIdentity) -> EvidenceKind:
    """The kind of evidence behind an identity, inferred from its source when unstated."""
    return identity.evidence or _LEGACY_KIND[identity.source]


@dataclass(frozen=True)
class DnsWindow:
    """Counts describe a bounded query-log sample; completeness is never inferred from silence."""

    source: Literal["pihole-v6"]
    from_at: str
    until_at: str
    queries_read: int
    queries_available: int
    complete: bool

    @classmethod
    def decode(cls, value: object) -> DnsWindow:
        row = object_fields(
            value,
            {
                "source",
                "from_at",
                "until_at",
                "queries_read",
                "queries_available",
                "complete",
            },
        )
        start, end = timestamp(row["from_at"]), timestamp(row["until_at"])
        if (
            row["source"] != "pihole-v6"
            or type(row["complete"]) is not bool
            or datetime.fromisoformat(start.replace("Z", "+00:00"))
            > datetime.fromisoformat(end.replace("Z", "+00:00"))
        ):
            raise ValueError("Invalid DNS window")
        read = integer(row["queries_read"], 0, MAX_DNS_QUERIES)
        available = integer(row["queries_available"], read, 2**53 - 1)
        if row["complete"] and read != available:
            raise ValueError("Incomplete query counts cannot claim complete coverage")
        return cls("pihole-v6", start, end, read, available, row["complete"])


@dataclass(frozen=True)
class Snapshot:
    """A bounded export/fixture. DNS queries are observations, not proof of payload exfiltration."""

    observed_at: str
    tcp: tuple[TcpObservation, ...]
    dns: tuple[DnsObservation, ...]
    devices: tuple[DeviceIdentity, ...] = ()
    dns_window: DnsWindow | None = None

    @classmethod
    def decode(cls, value: object) -> Snapshot:
        row = object_fields(
            value, {"version", "observed_at", "tcp", "dns"}, {"devices", "dns_window"}
        )
        integer(row["version"], 1, 1)
        tcp = tuple(
            TcpObservation.decode(item) for item in items(row["tcp"], MAX_PROBES)
        )
        dns = tuple(
            DnsObservation.decode(item) for item in items(row["dns"], MAX_DNS_ROWS)
        )
        if len({(item.address, item.port) for item in tcp}) != len(tcp) or len(
            {(item.device, item.domain) for item in dns}
        ) != len(dns):
            raise ValueError("Duplicate observation")
        devices = tuple(
            DeviceIdentity.decode(item)
            for item in items(row.get("devices", []), MAX_TARGETS)
        )
        if len({device.address for device in devices}) != len(devices):
            raise ValueError("Duplicate device address association")
        window = (
            DnsWindow.decode(row["dns_window"])
            if row.get("dns_window") is not None
            else None
        )
        return cls(timestamp(row["observed_at"]), tcp, dns, devices, window)


_PRIVATE_IPV4 = tuple(
    ipaddress.IPv4Network(block)
    for block in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


def private_ipv4(address: str) -> bool:
    return any(ipaddress.ip_address(address) in block for block in _PRIVATE_IPV4)


def expand_targets(value: object) -> tuple[str, ...]:
    """Expand an explicit private IPv4 CIDR, preserving a bounded, frozen scope.

    Saving a subnet does not depend on discovery responses: filtered or currently
    silent hosts remain in scope. Reject overlap rather than silently changing
    caller intent. Explicit historical address configurations remain supported.
    """
    result: list[str] = []
    for entry in items(value, MAX_TARGETS):
        address = text(entry, 64)
        if "/" in address:
            network = ipaddress.IPv4Network(address, strict=True)
            if network.num_addresses > MAX_TARGETS or not any(
                network.subnet_of(block) for block in _PRIVATE_IPV4
            ):
                raise ValueError(
                    "Choose a private IPv4 subnet of at most 256 addresses (/24 or smaller)"
                )
            result.extend(str(host) for host in network.hosts())
        else:
            result.append(str(ipaddress.ip_address(address)))
        if len(result) > MAX_TARGETS:
            raise ValueError("Monitoring scope exceeds 256 device addresses")
    if len(set(result)) != len(result):
        raise ValueError("Monitoring scope contains duplicate or overlapping addresses")
    return tuple(result)


@dataclass(frozen=True)
class DeviceLabel:
    address: str
    label: str


def device_labels(value: object, targets: tuple[str, ...]) -> tuple[DeviceLabel, ...]:
    if not isinstance(value, dict) or len(value) > MAX_TARGETS:
        raise ValueError("Device labels must map selected addresses to names")
    labels = tuple(
        DeviceLabel(str(ipaddress.ip_address(text(address))), text(label, 128))
        for address, label in value.items()
    )
    if len({label.address for label in labels}) != len(labels) or not {
        label.address for label in labels
    } <= set(targets):
        raise ValueError("Labels must refer to selected device addresses")
    # These names also appear in network_scope replies, before a scan exists.
    # Bound their escaped JSON separately so that metadata fits native tool limits.
    if (
        len(json.dumps([asdict(label) for label in labels], separators=(",", ":")))
        > 65536
    ):
        raise ValueError(
            "Device names exceed the retained scope budget; use shorter names"
        )
    return tuple(sorted(labels, key=lambda label: label.address))


@dataclass(frozen=True)
class PiHolePlan:
    origin: str
    password_environment: str | None
    password_file: Path | None
    lookback_seconds: int
    query_limit: int
    max_identity_age: int
    timeout: float

    @classmethod
    def decode(cls, value: object) -> PiHolePlan:
        row = object_fields(
            value,
            {"origin"},
            {
                "passwordEnvironment",
                "passwordFile",
                "lookbackSeconds",
                "queryLimit",
                "maxIdentityAgeSeconds",
                "timeoutSeconds",
            },
        )
        origin = text(row["origin"], 2048).rstrip("/")
        url = urlsplit(origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "Configure a Pi-hole HTTP(S) origin without credentials or a path"
            )
        url.port
        if ("passwordEnvironment" in row) == ("passwordFile" in row):
            raise ValueError(
                "Pi-hole requires exactly one passwordEnvironment or passwordFile"
            )
        environment = (
            text(row["passwordEnvironment"], 128)
            if "passwordEnvironment" in row
            else None
        )
        if environment is not None and not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_]*", environment
        ):
            raise ValueError("Invalid Pi-hole password environment name")
        file = Path(text(row["passwordFile"], 4096)) if "passwordFile" in row else None
        if file is not None and (
            not file.is_absolute() or file.resolve() != file or file.is_symlink()
        ):
            raise ValueError(
                "Pi-hole password file must be an absolute unlinked private path"
            )
        timeout = row.get("timeoutSeconds", 5)
        if (
            isinstance(timeout, bool)
            or not isinstance(timeout, (int, float))
            or not math.isfinite(timeout)
            or not 0.1 <= timeout <= 10
        ):
            raise ValueError("Pi-hole timeout must be between 0.1 and 10 seconds")
        return cls(
            origin,
            environment,
            file,
            integer(row.get("lookbackSeconds", 3600), 60, 86400),
            integer(row.get("queryLimit", 64), 1, 256),
            integer(row.get("maxIdentityAgeSeconds", 86400), 60, 604800),
            float(timeout),
        )


@dataclass(frozen=True)
class UniFiGateway:
    device_id: str
    lan_mac: str

    @classmethod
    def decode(cls, value: object) -> UniFiGateway:
        row = object_fields(value, {"deviceId", "lanMac"})
        return cls(uuid(row["deviceId"]), mac_address(row["lanMac"]))


@dataclass(frozen=True)
class UniFiPlan:
    api_base: str
    site_id: str
    api_key_environment: str
    ca_file: Path | None
    certificate_sha256: str | None = None
    gateway: UniFiGateway | None = None
    # Short legacy site name (often "default") for per-client byte counters.
    # The Integration API used for identity has no traffic fields, so traffic
    # reads the controller's stat/sta endpoint with the same API key.
    traffic_site: str | None = None

    @classmethod
    def decode(cls, value: object) -> UniFiPlan:
        row = object_fields(
            value,
            {"apiBase", "siteId", "apiKeyEnvironment"},
            {"caFile", "certificateSha256", "gateway", "trafficSite"},
        )
        base = text(row["apiBase"], 2048).rstrip("/")
        url = urlsplit(base)
        if (
            url.scheme != "https"
            or not url.hostname
            or url.username is not None
            or url.password is not None
            or url.query
            or url.fragment
            or url.path not in ("/proxy/network/integration/v1", "/integration/v1")
            or any(c.isspace() for c in base)
        ):
            raise ValueError(
                "Configure the local UniFi HTTPS Network Integration API base"
            )
        url.port
        environment = text(row["apiKeyEnvironment"], 128)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", environment):
            raise ValueError("Invalid UniFi API key environment name")
        ca_file = Path(text(row["caFile"], 4096)) if "caFile" in row else None
        if ca_file is not None and (
            not ca_file.is_absolute() or ca_file.resolve() != ca_file
        ):
            raise ValueError("UniFi CA file must be an absolute, unlinked path")
        fingerprint = (
            text(row["certificateSha256"], 64).lower()
            if "certificateSha256" in row
            else None
        )
        if fingerprint is not None and not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
            raise ValueError("UniFi certificate pin must be a SHA-256 hex digest")
        if ca_file is not None and fingerprint is not None:
            raise ValueError("Choose either a UniFi CA file or a certificate pin")
        gateway = UniFiGateway.decode(row["gateway"]) if "gateway" in row else None
        if gateway is not None and not private_ipv4(url.hostname):
            raise ValueError("A UniFi gateway binding requires a private IPv4 API host")
        traffic_site = text(row["trafficSite"], 64) if "trafficSite" in row else None
        if traffic_site is not None and not re.fullmatch(
            r"[A-Za-z0-9_-]{1,64}", traffic_site
        ):
            raise ValueError("UniFi traffic site must be a short site name")
        return cls(
            base,
            uuid(row["siteId"]),
            environment,
            ca_file,
            fingerprint,
            gateway,
            traffic_site,
        )


Capability = Literal[
    "network_table",
    "dns_queries",
    "dhcp_leases",
    "dhcp_reservations",
    "dhcp_scope",
    "local_dns_names",
    "controller_clients",
    "managed_inventory",
    "traffic_counters",
]
_CAPABILITIES: dict[str, Capability] = {name: name for name in get_args(Capability)}
# These add new evidence, so they stay off until the operator names them: on an
# upgraded configuration and whenever a service omits ``capabilities``.
OPT_IN_CAPABILITIES: frozenset[Capability] = frozenset(
    {"dhcp_leases", "dhcp_reservations", "dhcp_scope", "local_dns_names"}
)
ConnectorKind = Literal["pihole", "unifi"]
_KINDS: dict[str, ConnectorKind] = {name: name for name in get_args(ConnectorKind)}
# What each kind of service can offer. Enabling anything else is a decode error.
KIND_CAPABILITIES: Mapping[ConnectorKind, frozenset[Capability]] = MappingProxyType(
    {
        "pihole": frozenset(
            {
                "network_table",
                "dns_queries",
                "dhcp_leases",
                "dhcp_reservations",
                "dhcp_scope",
                "local_dns_names",
            }
        ),
        "unifi": frozenset(
            {
                "controller_clients",
                "managed_inventory",
                "traffic_counters",
                "dhcp_reservations",
                "dhcp_scope",
            }
        ),
    }
)
# UniFi DHCP reads address the legacy site by name, so they require trafficSite.
_UNIFI_SITE_CAPABILITIES: frozenset[Capability] = frozenset(
    {"dhcp_reservations", "dhcp_scope"}
)
MAX_SERVICES = 16


def legacy_capabilities(
    kind: ConnectorKind, settings: PiHolePlan | UniFiPlan
) -> frozenset[Capability]:
    """What a ``collection.pihole`` / ``collection.unifi`` key has always read."""
    if kind == "pihole":
        return frozenset({"network_table", "dns_queries"})
    base: frozenset[Capability] = frozenset({"controller_clients", "managed_inventory"})
    if isinstance(settings, UniFiPlan) and settings.traffic_site is not None:
        return base | {"traffic_counters"}
    return base


@dataclass(frozen=True)
class ServicePlan:
    """One configured external service and the capabilities enabled on it.

    ``legacy`` marks a service decoded from the old ``pihole``/``unifi`` keys,
    whose scope fingerprint keeps its historical encoding.
    """

    kind: ConnectorKind
    id: str
    capabilities: frozenset[Capability]
    settings: PiHolePlan | UniFiPlan
    legacy: bool = False

    def __post_init__(self) -> None:
        expected = PiHolePlan if self.kind == "pihole" else UniFiPlan
        if not isinstance(self.settings, expected):
            raise ValueError("Service settings do not match the service kind")
        if not self.capabilities <= KIND_CAPABILITIES[self.kind]:
            raise ValueError("Service enables a capability its kind does not offer")
        if isinstance(self.settings, UniFiPlan) and self.settings.traffic_site is None:
            # DHCP and byte-counter reads use the legacy endpoints, addressed
            # by the short site name; without it there is nothing to read.
            if self.capabilities & _UNIFI_SITE_CAPABILITIES:
                raise ValueError("UniFi DHCP capabilities require trafficSite")
            if "traffic_counters" in self.capabilities:
                raise ValueError("UniFi traffic counters require a legacy site name")

    @classmethod
    def legacy_service(
        cls, kind: ConnectorKind, settings: PiHolePlan | UniFiPlan
    ) -> ServicePlan:
        return cls(kind, kind, legacy_capabilities(kind, settings), settings, True)

    @classmethod
    def decode(cls, value: object) -> ServicePlan:
        row = object_fields(value, {"kind", "id", "settings"}, {"capabilities"})
        kind = _KINDS.get(row["kind"]) if isinstance(row["kind"], str) else None
        if kind is None:
            raise ValueError("Unsupported service kind")
        settings: PiHolePlan | UniFiPlan = (
            PiHolePlan.decode(row["settings"])
            if kind == "pihole"
            else UniFiPlan.decode(row["settings"])
        )
        if "capabilities" not in row:
            capabilities = KIND_CAPABILITIES[kind] - OPT_IN_CAPABILITIES
            if isinstance(settings, UniFiPlan) and settings.traffic_site is None:
                # As the legacy key: traffic counters only with a site name.
                capabilities -= {"traffic_counters"}
        else:
            names = items(row["capabilities"], len(_CAPABILITIES))
            enabled = [
                _CAPABILITIES.get(name) if isinstance(name, str) else None
                for name in names
            ]
            if None in enabled or len(set(enabled)) != len(enabled):
                raise ValueError("Service capabilities must be unique known names")
            capabilities = frozenset(name for name in enabled if name is not None)
        return cls(kind, identifier(row["id"]), capabilities, settings)


def _validate_sources(sources: tuple[ServicePlan, ...]) -> None:
    """Rules across services that hold until multi-service support (stage 3)."""
    if len({source.id for source in sources}) != len(sources):
        raise ValueError("Service ids must be unique")
    for capability in ("dns_queries", "traffic_counters"):
        if sum(capability in source.capabilities for source in sources) > 1:
            raise ValueError("Enable " + capability + " on at most one service")
    if len({source.kind for source in sources}) != len(sources):
        raise ValueError("Configure one service per kind until multi-service support")


@dataclass(frozen=True)
class CollectionPlan:
    mode: Literal["tcp", "fixture"]
    targets: tuple[str, ...]
    ports: tuple[int, ...]
    timeout: float
    concurrency: int
    observations_file: Path | None
    max_observation_age: int
    enabled: bool = True
    labels: tuple[DeviceLabel, ...] = ()
    # The configured external services, in configuration order. Decode allows
    # at most one service per kind in this stage.
    sources: tuple[ServicePlan, ...] = ()
    quick_scan: bool = False
    dns_profiles: bool = False
    # Ask confirmed devices to describe themselves after quick checks
    # (unicast SSDP/mDNS); see device_facts. Local presence work only.
    device_descriptions: bool = False

    @property
    def pihole(self) -> PiHolePlan | None:
        """Settings of the single Pi-hole service, if configured.

        Stage-1 accessor kept for existing callers; the stage-3 plan removes it
        once the collector reads ``sources`` through the connector registry.
        """
        for source in self.sources:
            if isinstance(source.settings, PiHolePlan):
                return source.settings
        return None

    @property
    def unifi(self) -> UniFiPlan | None:
        """Settings of the single UniFi service, if configured.

        Stage-1 accessor kept for existing callers; the stage-3 plan removes it
        once the collector reads ``sources`` through the connector registry.
        """
        for source in self.sources:
            if isinstance(source.settings, UniFiPlan):
                return source.settings
        return None


def _collection_sources(plan: Mapping[str, object]) -> tuple[ServicePlan, ...]:
    """Decode ``sources``, or the legacy ``pihole``/``unifi`` keys, never both."""
    if "sources" in plan:
        if "pihole" in plan or "unifi" in plan:
            raise ValueError("Configure services in sources or pihole/unifi, not both")
        sources = tuple(
            ServicePlan.decode(entry) for entry in items(plan["sources"], MAX_SERVICES)
        )
    else:
        legacy: list[ServicePlan] = []
        if "pihole" in plan:
            legacy.append(
                ServicePlan.legacy_service("pihole", PiHolePlan.decode(plan["pihole"]))
            )
        if "unifi" in plan:
            legacy.append(
                ServicePlan.legacy_service("unifi", UniFiPlan.decode(plan["unifi"]))
            )
        sources = tuple(legacy)
    _validate_sources(sources)
    return sources


def household_day(stamp: str, zone: str) -> date:
    """The calendar day ``stamp`` (a UTC timestamp) falls on in ``zone``."""
    return (
        datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        .astimezone(ZoneInfo(zone))
        .date()
    )


def local_time(stamp: str, zone: str) -> str:
    """``stamp`` in ``zone`` as ``YYYY-MM-DD HH:MM`` plus the zone's abbreviation."""
    moment = datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(
        ZoneInfo(zone)
    )
    return moment.strftime("%Y-%m-%d %H:%M ") + (moment.tzname() or "UTC")


def household_zone(value: object) -> str:
    """Accept an available IANA zone name such as ``Australia/Sydney``."""
    if not isinstance(value, str) or not value or len(value) > 64:
        raise ValueError("Choose an available IANA time zone for timeZone")
    name = value
    if name != name.strip() or name.startswith("/") or ".." in name:
        raise ValueError("Choose an available IANA time zone for timeZone")
    try:
        ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        raise ValueError("Choose an available IANA time zone for timeZone") from None
    return name


@dataclass(frozen=True)
class Configuration:
    origin: str
    project: str
    collector: str
    token_environment: str
    web_token_environment: str
    state_directory: Path
    request_topic: str
    result_topic: str
    group: str
    schedule: str
    collection: CollectionPlan
    outgoing_peer: str | None = None
    # The household's IANA time zone. The dashboard, assessment reports and
    # daily limits all use it, so one day means the same everywhere. None
    # (not configured) uses UTC, and the dashboard says so.
    time_zone: str | None = None

    @property
    def zone(self) -> str:
        """The zone in effect: the configured one, else UTC."""
        return self.time_zone or "UTC"

    @classmethod
    def read(cls, path: Path) -> Configuration:
        return cls.decode(load_json(path), path)

    @classmethod
    def decode(cls, value: object, path: Path) -> Configuration:
        """Validate a complete configuration before installing or saving it."""
        row = object_fields(
            value,
            {
                "origin",
                "project",
                "collector",
                "tokenEnvironment",
                "webTokenEnvironment",
                "stateDirectory",
                "requestTopic",
                "resultTopic",
                "group",
                "schedule",
                "collection",
            },
            {"outgoingPeer", "timeZone"},
        )
        origin = text(row["origin"], 2048)
        url = urlsplit(origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "Configure an HTTP(S) origin without credentials or a path"
            )
        url.port  # Reject a malformed port before any connection.
        directory = Path(text(row["stateDirectory"], 4096))
        if not directory.is_absolute() or directory.is_symlink():
            raise ValueError(
                "State directory must be an absolute, unlinked private path"
            )
        plan = object_fields(
            row["collection"],
            {"mode"},
            {
                "targets",
                "ports",
                "timeoutSeconds",
                "concurrency",
                "observationsFile",
                "maxObservationAgeSeconds",
                "enabled",
                "deviceLabels",
                "pihole",
                "unifi",
                "sources",
                "quickScan",
                "dnsProfiles",
                "deviceDescriptions",
            },
        )
        mode = plan["mode"]
        if mode not in ("tcp", "fixture"):
            raise ValueError("Collection mode must be tcp or fixture")
        observations = (
            Path(text(plan["observationsFile"], 4096))
            if "observationsFile" in plan
            else None
        )
        if observations is not None and not observations.is_absolute():
            observations = path.resolve().parent / observations
        if observations is not None and observations.is_symlink():
            raise ValueError("Observation input cannot be a symbolic link")
        if mode == "fixture":
            if observations is None or set(plan) != {"mode", "observationsFile"}:
                raise ValueError("Fixture mode requires only observationsFile")
            collection = CollectionPlan("fixture", (), (), 1.0, 1, observations, 0)
        else:
            enabled = plan.get("enabled", True)
            quick_scan = plan.get("quickScan", False)
            if type(quick_scan) is not bool:
                raise ValueError("Quick presence scan must be a boolean")
            if type(enabled) is not bool:
                raise ValueError("TCP collection enabled must be a boolean")
            if not {"targets", "ports", "timeoutSeconds", "concurrency"} <= set(plan):
                raise ValueError(
                    "TCP collection requires explicit targets, ports and limits"
                )
            targets = expand_targets(plan["targets"])
            if quick_scan and not all(private_ipv4(address) for address in targets):
                raise ValueError("Quick presence scans require private IPv4 addresses")
            ports = tuple(
                integer(item, 1, 65535) for item in items(plan["ports"], MAX_PORTS)
            )
            timeout = plan["timeoutSeconds"]
            if (
                isinstance(timeout, bool)
                or not isinstance(timeout, (int, float))
                or not math.isfinite(timeout)
                or not 0.1 <= timeout <= 5
            ):
                raise ValueError("Timeout must be between 0.1 and 5 seconds")
            if (
                enabled
                and (not targets or not ports)
                or len(targets) * len(ports) > MAX_PROBES
                or len(set(targets)) != len(targets)
                or len(set(ports)) != len(ports)
            ):
                raise ValueError(
                    "Configure unique nonempty targets/ports, at most 4096 probes"
                )
            if observations is None and "maxObservationAgeSeconds" in plan:
                raise ValueError("Observation age requires an observation file")
            age = (
                integer(plan.get("maxObservationAgeSeconds"), 1, 86400)
                if observations
                else 0
            )
            sources = _collection_sources(plan)
            dns = any("dns_queries" in source.capabilities for source in sources)
            dns_profiles = plan.get("dnsProfiles", False)
            if type(dns_profiles) is not bool:
                raise ValueError("DNS activity profiles must be a boolean")
            if dns_profiles and (
                not quick_scan or not any(source.kind == "pihole" for source in sources)
            ):
                raise ValueError(
                    "DNS activity profiles require quick presence checks and Pi-hole"
                )
            if dns_profiles and not dns:
                raise ValueError("DNS activity profiles require dns_queries on Pi-hole")
            device_descriptions = plan.get("deviceDescriptions", False)
            if type(device_descriptions) is not bool:
                raise ValueError("Device descriptions must be a boolean")
            if device_descriptions and not quick_scan:
                raise ValueError("Device descriptions require quick presence checks")
            if dns and observations is not None:
                raise ValueError("Choose either Pi-hole or a DNS observation file")
            collection = CollectionPlan(
                "tcp",
                targets,
                ports,
                float(timeout),
                integer(plan["concurrency"], 1, 32),
                observations,
                age,
                enabled,
                device_labels(plan.get("deviceLabels", {}), targets),
                sources,
                quick_scan,
                dns_profiles,
                device_descriptions,
            )
        tokens = (
            identifier(row["tokenEnvironment"]),
            identifier(row["webTokenEnvironment"]),
        )
        if any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) for name in tokens):
            raise ValueError("Invalid credential environment name")
        if collection.unifi and collection.unifi.api_key_environment in (
            *tokens,
            collection.pihole.password_environment if collection.pihole else None,
        ):
            raise ValueError("Use a separate credential environment variable for UniFi")
        request_topic, result_topic = (
            identifier(row["requestTopic"]),
            identifier(row["resultTopic"]),
        )
        if request_topic == result_topic or "schedule.due" in {
            request_topic,
            result_topic,
        }:
            raise ValueError("Request, result and schedule topics must be distinct")
        return cls(
            origin,
            identifier(row["project"]),
            identifier(row["collector"]),
            tokens[0],
            tokens[1],
            directory,
            request_topic,
            result_topic,
            identifier(row["group"]),
            identifier(row["schedule"]),
            collection,
            identifier(row["outgoingPeer"]) if "outgoingPeer" in row else None,
            household_zone(row["timeZone"]) if "timeZone" in row else None,
        )


@dataclass(frozen=True)
class ScanRequest:
    """Requests select the deployed collector, never addresses, ports, commands or paths."""

    request_id: str
    collector: str

    @classmethod
    def decode(cls, value: object) -> ScanRequest:
        row = object_fields(value, {"version", "request_id", "collector"})
        integer(row["version"], 1, 1)
        return cls(uuid(row["request_id"]), identifier(row["collector"]))


@dataclass(frozen=True)
class Evidence:
    scan_id: str
    source_event: str
    collector: str
    mode: Literal["tcp", "fixture"]
    scope: str
    started_at: str
    finished_at: str
    snapshot: Snapshot
    issues: tuple[str, ...]
    changes: tuple[str, ...]
    previous_revision: str | None
    baseline: bool | None = None

    @property
    def is_baseline(self) -> bool:
        """Prefer the typed signal; preserve the exact historical v1 baseline meaning."""
        if self.baseline is not None:
            return self.baseline
        # Older retained receipts have no typed signal. Keep this compatibility
        # path isolated from policy, and never rewrite their original encoded text.
        return self.previous_revision is None or self.changes == (
            "Baseline collection; no comparable previous evidence.",
        )

    def encode(self) -> str:
        # Historical receipts are reconciled against exact retained text. Preserve
        # their original pretty encoding and omit absent v1 extensions. Enriched
        # evidence uses compact JSON; retained artifacts and agent projections have separate bounds.
        row = asdict(self)
        if self.baseline is None:
            row.pop("baseline")
        enriched = bool(self.snapshot.devices) or self.snapshot.dns_window is not None
        if not self.snapshot.devices:
            row["snapshot"].pop("devices")
        for device in row["snapshot"].get("devices", ()):
            for key in ("evidence", "service"):
                if device[key] is None:
                    del device[key]
        if self.snapshot.dns_window is None:
            row["snapshot"].pop("dns_window")
        return json.dumps(
            row,
            indent=None if enriched else 2,
            separators=(",", ":") if enriched else None,
            allow_nan=False,
        )

    @classmethod
    def decode(cls, value: object) -> Evidence:
        row = object_fields(
            value,
            {
                "scan_id",
                "source_event",
                "collector",
                "mode",
                "scope",
                "started_at",
                "finished_at",
                "snapshot",
                "issues",
                "changes",
                "previous_revision",
            },
            {"baseline"},
        )
        if "baseline" in row and type(row["baseline"]) is not bool:
            raise ValueError("Evidence baseline must be a boolean")
        mode = row["mode"]
        if mode not in ("tcp", "fixture"):
            raise ValueError("Invalid evidence mode")
        snapshot = object_fields(
            row["snapshot"], {"observed_at", "tcp", "dns"}, {"devices", "dns_window"}
        )
        snapshot["version"] = 1
        return cls(
            uuid(row["scan_id"]),
            text(row["source_event"]),
            identifier(row["collector"]),
            "tcp" if mode == "tcp" else "fixture",
            identifier(row["scope"]),
            timestamp(row["started_at"]),
            timestamp(row["finished_at"]),
            Snapshot.decode(snapshot),
            tuple(text(item) for item in items(row["issues"], 32)),
            tuple(text(item, 1024) for item in items(row["changes"], MAX_CHANGES)),
            uuid(row["previous_revision"])
            if row["previous_revision"] is not None
            else None,
            row["baseline"] is True if "baseline" in row else None,
        )
