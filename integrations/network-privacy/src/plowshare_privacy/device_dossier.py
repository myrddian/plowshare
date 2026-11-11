"""A bounded, typed per-device dossier built from data the collector already holds.

Everything here is a pure function of a ``DeviceCard`` (and, optionally, an
operator profile summary). There is no IO, no model and no clock: the same card
always yields the same dossier and the same JSON bytes, so a reply or retained
revision can be compared and cited.

Three rules shape the record:

* A dossier exists only for a device whose current identity carries a confirmed
  MAC address. Address-only devices may be different machines over time, and
  DNS and traffic are attributed only to a confirmed MAC (see
  ``device_inventory``), so there would be nothing trustworthy to describe.
* Missing measurements are ``None`` plus a ``coverage`` sentence, never zero.
  "No UniFi counters" and "sent 0 bytes" are different facts.
* ``signals`` are deterministic attention flags with thresholds defined below.
  They tell a reader where to look; none of them is a finding.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, replace
from typing import Literal

from .contracts import mac_address, uuid
from .device_detection import ServiceExposure
from .device_inventory import DeviceCard
from .dns_activity import (
    GROUP_ORDER,
    Destination,
    DestinationGroup,
    DestinationKind,
    DnsActivity,
    retention_rank,
)
from .limits import TOOL_REPLY_BYTES
from .presence import PresenceCount, epoch
from .traffic import TrafficVolume

SignalCode = Literal[
    "measurement_dns",
    "advertising_dns",
    "telemetry_dominant",
    "dns_bypass",
    "upload_heavy",
    "open_admin_service",
]
SIGNAL_ORDER: tuple[SignalCode, ...] = (
    "measurement_dns",
    "advertising_dns",
    "telemetry_dominant",
    "dns_bypass",
    "upload_heavy",
    "open_admin_service",
)

# Signal thresholds. They are deliberately simple so that a reader can check a
# flag against the dossier's own numbers.
#
# Measurement and advertising: a single lookup is enough. The classification
# list in dns_activity is short and curated to provider-operated domains with a
# published purpose, so one lookup already says the device (or an app on it) is
# wired to that provider. Blocked lookups count too: the attempt is the signal.
MIN_MEASUREMENT_QUERIES = 1
MIN_ADVERTISING_QUERIES = 1
# Telemetry is ordinary in small amounts (crash reports, diagnostics). It is
# flagged when it is at least half of the device's service lookups, i.e. all
# groups except connectivity probes and local names, which are frequent and say
# nothing about behaviour. A minimum count keeps a handful of lookups from a
# nearly idle device from reading as "dominant".
MIN_TELEMETRY_QUERIES = 20
TELEMETRY_DOMINANT_PERCENT = 50
_PLUMBING: frozenset[DestinationKind] = frozenset({"connectivity", "local"})
# Upload-heavy: bytes from the device are at least the bytes to it, and at least
# 1 GB (decimal) within the presence window (at most 24 hours). Most household
# devices download far more than they upload; sustained upload of this size is
# what a camera streaming to a cloud service or a backup client looks like.
# 1 GB over 24 hours is roughly 93 kbit/s sustained, above phone and TV
# background chatter. UniFi counters are a lower bound and include local
# traffic, so this flag directs attention only; it says nothing about where the
# bytes went.
UPLOAD_HEAVY_BYTES = 1_000_000_000
# Exposures from device_detection whose usual purpose is remote administration:
# shell access (SSH, Telnet), remote desktop, and plain HTTP, where an admin
# page would send its sign-in unencrypted. HTTPS is left out on purpose: TVs,
# speakers and phones open 443 for casting and pairing, so flagging it would
# mark most of the network and direct attention nowhere. The names must match
# device_detection's service rules; a test enforces that.
ADMIN_SERVICES: frozenset[str] = frozenset({"SSH", "Telnet", "HTTP", "Remote desktop"})

_DEVICE_ID = re.compile(r"mac:[0-9a-f]{2}(?::[0-9a-f]{2}){5}")


@dataclass(frozen=True)
class Signal:
    code: SignalCode
    basis: str


@dataclass(frozen=True)
class DossierIdentity:
    device_id: str
    name: str
    address: str
    mac: str
    hostname: str | None
    vendor: str | None
    source: Literal["configured", "pihole-v6", "unifi"]
    observed_at: str


@dataclass(frozen=True)
class DossierDetection:
    device_type: str
    confidence: Literal["tentative", "corroborated", "unknown"]
    clues: tuple[str, ...]
    exposures: tuple[ServiceExposure, ...]


@dataclass(frozen=True)
class DossierServices:
    """Open TCP ports from the latest completed scan of this address."""

    revision: str | None
    checked_at: str | None
    open_ports: tuple[int, ...]


@dataclass(frozen=True)
class DossierGroup:
    """One purpose group. ``queries`` and ``distinct`` always cover the whole group.

    ``omitted`` counts destinations not listed, whether dropped by the display
    limit in dns_activity or by the reply budget here, so
    ``distinct == len(destinations) + omitted``.
    """

    kind: DestinationKind
    queries: int
    distinct: int
    omitted: int
    destinations: tuple[Destination, ...]


@dataclass(frozen=True)
class DossierDns:
    from_at: str
    until_at: str
    windows: int
    partial_windows: int
    queries_read: int
    queries_available: int
    groups: tuple[DossierGroup, ...]


@dataclass(frozen=True)
class DnsSilence:
    """Confirmed presence with no Pi-hole queries; see dns_activity.pihole_silent."""

    hours: float
    checks: int


_APPLICABILITY = frozenset(
    {
        "potentially_affected",
        "confirmed_affected",
        "not_affected",
        "mitigated",
        "unresolved",
    }
)
_MITIGATION = frozenset({"proposed", "accepted", "applied", "verified", "reverted"})


def _bounded(value: str, limit: int) -> str:
    if (
        not isinstance(value, str)
        or len(value) > limit
        or any(ord(c) < 32 and c not in "\n\t" for c in value)
    ):
        raise ValueError("Invalid operator profile text")
    return value


@dataclass(frozen=True)
class LinkedIssue:
    """A privacy issue revision the operator linked to the device profile."""

    issue_id: str
    revision: str
    applicability: str
    mitigation_status: str

    def __post_init__(self) -> None:
        uuid(self.issue_id)
        uuid(self.revision)
        if self.applicability not in _APPLICABILITY:
            raise ValueError("Unsupported issue applicability")
        if self.mitigation_status not in _MITIGATION:
            raise ValueError("Unsupported mitigation status")


@dataclass(frozen=True)
class OperatorProfile:
    """The operator's expectations, with limits matching device_profiles.ProfileFields."""

    revision: str
    purpose: str
    expected_services: str
    issues: tuple[LinkedIssue, ...] = ()

    def __post_init__(self) -> None:
        uuid(self.revision)
        _bounded(self.purpose, 512)
        _bounded(self.expected_services, 1024)
        if len(self.issues) > 4 or len({item.issue_id for item in self.issues}) != len(
            self.issues
        ):
            raise ValueError("Choose at most four distinct privacy issues")


@dataclass(frozen=True)
class DeviceDossier:
    identity: DossierIdentity
    detection: DossierDetection
    presence: PresenceCount | None
    services: DossierServices
    dns: DossierDns | None
    pihole_silent: DnsSilence | None
    traffic: TrafficVolume | None
    profile: OperatorProfile | None
    coverage: tuple[str, ...]
    signals: tuple[Signal, ...]
    # Destinations dropped from this record to fit the byte budget.
    trimmed_destinations: int = 0


def parse_device_id(value: object) -> str:
    """Validate an external device ID: exactly ``mac:`` and a canonical MAC."""
    if not isinstance(value, str) or not _DEVICE_ID.fullmatch(value):
        raise ValueError("Device ID must be mac: and a canonical MAC address")
    mac_address(value[4:])  # Rejects the all-zero and broadcast addresses.
    return value


def confirmed_device_id(card: DeviceCard) -> str | None:
    """The card's MAC identity, or None when the current identity has no MAC."""
    current = card.identity
    if current is None or current.mac is None:
        return None
    if current.device_id != "mac:" + current.mac:
        return None
    return current.device_id


def _group(groups: tuple[DestinationGroup, ...], kind: DestinationKind) -> int:
    return sum(group.queries for group in groups if group.kind == kind)


def _providers(groups: tuple[DestinationGroup, ...], kind: DestinationKind) -> str:
    names = sorted(
        {
            item.provider or item.domain
            for group in groups
            if group.kind == kind
            for item in group.destinations
        }
    )
    return ", ".join(names[:4]) + (" and others" if len(names) > 4 else "")


def _blocked(groups: tuple[DestinationGroup, ...], kind: DestinationKind) -> int:
    return sum(
        item.blocked
        for group in groups
        if group.kind == kind
        for item in group.destinations
    )


def device_signals(card: DeviceCard) -> tuple[Signal, ...]:
    """Compute attention flags in a fixed order. See the threshold constants."""
    signals: list[Signal] = []
    dns = card.dns_activity
    groups = dns.groups if dns is not None else ()
    behavioural: tuple[tuple[SignalCode, DestinationKind, int], ...] = (
        ("measurement_dns", "measurement", MIN_MEASUREMENT_QUERIES),
        ("advertising_dns", "advertising", MIN_ADVERTISING_QUERIES),
    )
    for code, kind, minimum in behavioural:
        queries = _group(groups, kind)
        if queries >= minimum:
            signals.append(
                Signal(
                    code,
                    f"{queries} lookups to {kind} destinations "
                    f"({_providers(groups, kind)}); "
                    f"{_blocked(groups, kind)} blocked",
                )
            )
    telemetry = _group(groups, "telemetry")
    service = sum(group.queries for group in groups if group.kind not in _PLUMBING)
    if (
        telemetry >= MIN_TELEMETRY_QUERIES
        and telemetry * 100 >= service * TELEMETRY_DOMINANT_PERCENT
    ):
        signals.append(
            Signal(
                "telemetry_dominant",
                f"{telemetry} of {service} service lookups are telemetry "
                f"({_providers(groups, 'telemetry')})",
            )
        )
    if dns is not None and dns.pihole_silent:
        silence = _silence(dns)
        signals.append(
            Signal(
                "dns_bypass",
                f"No DNS via Pi-hole for {silence.hours} hours across "
                f"{silence.checks} checks",
            )
        )
    traffic = card.traffic
    if (
        traffic is not None
        and traffic.from_device_bytes >= UPLOAD_HEAVY_BYTES
        and traffic.from_device_bytes >= traffic.to_device_bytes
    ):
        signals.append(
            Signal(
                "upload_heavy",
                f"{traffic.from_device_bytes} bytes from the device and "
                f"{traffic.to_device_bytes} to it between {traffic.from_at} "
                f"and {traffic.until_at}",
            )
        )
    admin = [
        exposure
        for exposure in card.detection.exposures
        if exposure.possible_service in ADMIN_SERVICES
    ]
    if admin:
        signals.append(
            Signal(
                "open_admin_service",
                "Open TCP "
                + ", ".join(
                    f"{port} ({exposure.possible_service})"
                    for exposure in admin
                    for port in exposure.ports
                ),
            )
        )
    return tuple(sorted(signals, key=lambda item: SIGNAL_ORDER.index(item.code)))


def _silence(dns: DnsActivity) -> DnsSilence:
    hours = round((epoch(dns.until_at) - epoch(dns.from_at)) / 3600, 1)
    return DnsSilence(hours, dns.windows)


def _coverage(
    card: DeviceCard, profile: OperatorProfile | None, trimmed: int
) -> tuple[str, ...]:
    lines: list[str] = []
    if card.revision is None:
        lines.append(
            "No retained port scan covers this address; open services are unknown."
        )
    else:
        lines.append(
            "The port scan covers only the configured TCP ports; other services are unknown."
        )
    if card.presence is None:
        lines.append("No quick presence checks in the last 24 hours.")
    dns = card.dns_activity
    if dns is None:
        lines.append(
            "No Pi-hole DNS profile for this device in the last 24 hours; "
            "its destinations are unknown, not zero."
        )
    else:
        if dns.partial_windows:
            lines.append(
                f"DNS profile partial in {dns.partial_windows} of {dns.windows} "
                "windows; lookups in those windows may be missing."
            )
        if dns.queries_read < dns.queries_available:
            lines.append(
                f"Read {dns.queries_read} of {dns.queries_available} DNS queries "
                "Pi-hole reported; group totals cover the queries read."
            )
        if dns.pihole_silent:
            silence = _silence(dns)
            lines.append(
                f"No DNS via Pi-hole for {silence.hours} hours across "
                f"{silence.checks} checks: the device may use its own resolver or "
                "DNS over HTTPS, or be idle; its destinations cannot be seen."
            )
        lines.append("DNS lookups show names resolved, not data sent.")
    traffic = card.traffic
    if traffic is None:
        lines.append(
            "No UniFi byte counters for this device; traffic volume is unknown, not zero."
        )
    else:
        lines.append(
            "Byte totals include local traffic and say nothing about destinations."
        )
        if traffic.resets:
            lines.append(
                f"{traffic.resets} counter resets dropped intervals; "
                "byte totals are a lower bound."
            )
    if profile is None:
        lines.append("No operator profile supplied; expected behaviour is unknown.")
    if trimmed:
        lines.append(
            f"{trimmed} lower-ranked DNS destinations omitted to fit the reply "
            "budget; group totals are complete."
        )
    return tuple(lines)


def _groups(dns: DnsActivity) -> tuple[DossierGroup, ...]:
    return tuple(
        DossierGroup(
            group.kind,
            group.queries,
            group.distinct,
            group.distinct - len(group.destinations),
            group.destinations,
        )
        for group in dns.groups
    )


def _drop_order(groups: tuple[DossierGroup, ...]) -> list[tuple[int, Destination]]:
    """Destinations from least to most worth keeping.

    Plumbing goes first, then ordinary service names, then behavioural
    destinations (dns_activity's retention ranks). Within a rank the rarest
    lookups go first; ties break on group order and then domain, so the result
    never depends on input order.
    """
    entries = [
        (index, item)
        for index, group in enumerate(groups)
        for item in group.destinations
    ]
    return sorted(
        entries,
        key=lambda entry: (
            -retention_rank(entry[1].domain),
            entry[1].queries,
            -GROUP_ORDER.index(groups[entry[0]].kind),
            entry[1].domain,
        ),
    )


def encode_dossier(dossier: DeviceDossier) -> str:
    """Compact JSON in dataclass field order; identical dossiers give identical bytes."""
    return json.dumps(asdict(dossier), allow_nan=False, separators=(",", ":"))


def _size(dossier: DeviceDossier) -> int:
    return len(encode_dossier(dossier).encode())


def build_dossier(
    card: DeviceCard,
    profile: OperatorProfile | None = None,
    *,
    budget: int = TOOL_REPLY_BYTES,
) -> DeviceDossier:
    """Build the dossier, trimming lowest-ranked destinations to fit ``budget`` bytes.

    Signals are computed before trimming from complete group totals, so the
    budget never changes them. Raises ValueError without a confirmed MAC or when
    even the record without any destinations exceeds the budget.
    """
    device_id = confirmed_device_id(card)
    current = card.identity
    if device_id is None or current is None or current.mac is None:
        raise ValueError("A device dossier needs a confirmed MAC identity")
    dns = card.dns_activity
    groups = _groups(dns) if dns is not None else ()
    dossier = DeviceDossier(
        DossierIdentity(
            device_id,
            card.name,
            card.address,
            current.mac,
            current.hostname,
            current.vendor,
            current.source,
            current.observed_at,
        ),
        DossierDetection(
            card.detection.device_type,
            card.detection.confidence,
            card.detection.clues,
            card.detection.exposures,
        ),
        card.presence,
        DossierServices(
            card.revision,
            card.last_seen if card.revision is not None else None,
            tuple(
                sorted(
                    {probe.port for probe in card.services if probe.status == "open"}
                )
            ),
        ),
        DossierDns(
            dns.from_at,
            dns.until_at,
            dns.windows,
            dns.partial_windows,
            dns.queries_read,
            dns.queries_available,
            groups,
        )
        if dns is not None
        else None,
        _silence(dns) if dns is not None and dns.pihole_silent else None,
        card.traffic,
        profile,
        _coverage(card, profile, 0),
        device_signals(card),
    )
    section = dossier.dns
    drop = _drop_order(groups)
    trimmed = 0
    # The drop list is empty without DNS, so section is set whenever it is used.
    while section is not None and _size(dossier) > budget and trimmed < len(drop):
        index, item = drop[trimmed]
        trimmed += 1
        kept = list(section.groups)
        group = kept[index]
        kept[index] = replace(
            group,
            omitted=group.omitted + 1,
            destinations=tuple(d for d in group.destinations if d is not item),
        )
        section = replace(section, groups=tuple(kept))
        dossier = replace(
            dossier,
            dns=section,
            coverage=_coverage(card, profile, trimmed),
            trimmed_destinations=trimmed,
        )
    if _size(dossier) > budget:
        raise ValueError("Device dossier exceeds the reply budget")
    return dossier
