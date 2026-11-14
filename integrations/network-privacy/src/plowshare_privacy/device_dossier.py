"""A bounded, typed per-device dossier built from data the collector already holds.

Everything here is a pure function of a ``DeviceCard`` (and, optionally, an
operator profile summary and the device's retained ``DeviceFacts``). There is
no IO, no model and no clock: the same inputs always yield the same dossier and
the same JSON bytes, so a reply or retained revision can be compared and cited.

Four rules shape the record:

* A dossier exists only for a device whose current identity carries a confirmed
  MAC address. Address-only devices may be different machines over time, and
  DNS and traffic are attributed only to a confirmed MAC (see
  ``device_inventory``), so there would be nothing trustworthy to describe.
* Missing measurements are ``None`` plus a ``coverage`` sentence, never zero.
  "No UniFi counters" and "sent 0 bytes" are different facts.
* ``signals`` are deterministic attention flags with thresholds defined below.
  They tell a reader where to look; none of them is a finding.
* ``supplemental`` is context, not observation: what the device says about
  itself, public advisories for a model name and vendor features worth
  checking. A device that did not describe itself is a coverage line. Serials,
  UUIDs and friendly names never reach it (``device_facts`` drops them).
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, replace
from typing import TYPE_CHECKING, Literal

from .advisories import Advisory
from .contracts import mac_address, uuid
from .device_detection import ServiceExposure, exposures_for
from .device_facts import AdvisoryStatus, DeviceDescription, DeviceFacts, ModelSource
from .device_inventory import AddressingView, DeviceCard
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
from .vendor_notes import VendorNote, vendor_notes

if TYPE_CHECKING:
    # deep_scan imports tools, which imports this module; type-only to avoid a cycle.
    from .deep_scan import DeepScanResult

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

# Advisories listed per dossier, most severe first. NVD pages hold at most ten;
# the rest are counted in ``omitted``. Descriptions are shortened because the
# CVE ID is the reference and the report needs only a hint of the subject.
MAX_SUPPLEMENTAL_ADVISORIES = 5
MAX_ADVISORY_TEXT = 240
_SEVERITY_RANK = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "NONE": 4}

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


# Why no operator profile reached a dossier although one exists: it was matched
# by address only, or several profiles claim the device. Neither is used, since
# an address alone does not establish identity.
ProfileNote = Literal["address_only", "ambiguous"]


@dataclass(frozen=True)
class ProfileMatch:
    """The operator profile chosen for a dossier, or why none was."""

    profile: OperatorProfile | None
    note: ProfileNote | None = None

    def __post_init__(self) -> None:
        if self.profile is not None and self.note is not None:
            raise ValueError("A used profile needs no note")
        if self.note not in (None, "address_only", "ambiguous"):
            raise ValueError("Unsupported profile note")


@dataclass(frozen=True)
class SupplementalAdvisory:
    cve: str
    severity: str | None
    published_on: str
    known_exploited: bool
    description: str


@dataclass(frozen=True)
class SupplementalAdvisories:
    """The advisory lookup for this device's model, as ``device_facts`` retained it.

    ``total`` is NVD's match count for a search (it can exceed the page);
    ``omitted`` counts page entries not listed here, whether by the listing
    limit or the reply budget. Other statuses carry no advisories.
    """

    status: AdvisoryStatus
    checked_at: str
    model: str | None
    model_source: ModelSource | None
    total: int | None
    advisories: tuple[SupplementalAdvisory, ...]
    omitted: int
    unavailable: str | None


@dataclass(frozen=True)
class DossierSupplemental:
    """Context about the device that the network traffic does not show.

    ``described_at`` is the last time the device was asked to describe itself
    (None when it never was); ``updated_at`` the last time these facts changed.
    Descriptions are the device's own claims. Vendor notes come from the
    curated table in ``vendor_notes`` and are things to check, not findings.
    """

    described_at: str | None
    updated_at: str | None
    descriptions: tuple[DeviceDescription, ...]
    advisories: SupplementalAdvisories | None
    vendor_notes: tuple[VendorNote, ...]


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
    supplemental: DossierSupplemental
    coverage: tuple[str, ...]
    signals: tuple[Signal, ...]
    # Destinations dropped from this record to fit the byte budget.
    trimmed_destinations: int = 0
    # The card's address assignment, copied unchanged (None when the latest
    # collection recorded none). Network-configuration context only: it never
    # produces a signal, so it cannot raise a concern on its own. Encoded last,
    # under ``addressing``, from dossier document version 4.
    addressing: AddressingView | None = None


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


def _ranked(advisories: tuple[Advisory, ...]) -> list[Advisory]:
    """Known exploited first, then by severity, then newest, then CVE ID.

    Stable sorts from the last key to the first; unrated advisories sort after
    every rated one.
    """
    ranked = sorted(advisories, key=lambda item: item.cve)
    ranked.sort(key=lambda item: item.published_on, reverse=True)
    ranked.sort(
        key=lambda item: (
            not item.known_exploited,
            _SEVERITY_RANK.get(item.severity or "", len(_SEVERITY_RANK)),
        )
    )
    return ranked


def _brief(value: str, limit: int) -> str:
    text = " ".join(value.split())
    if len(text) <= limit:
        return text
    head = text[: limit - 1]
    space = head.rfind(" ")
    return (head[:space] if space > limit // 2 else head).rstrip(" ,;:") + "…"


def supplemental_section(
    facts: DeviceFacts | None,
    *,
    vendors: tuple[str | None, ...],
    device_type: str,
    limit: int = MAX_SUPPLEMENTAL_ADVISORIES,
) -> DossierSupplemental:
    """Project retained facts and matching vendor notes into the dossier section.

    ``vendors`` are the device's network-identity vendor names (MAC vendor or
    UniFi); with the device-reported manufacturers they select vendor notes.
    """
    descriptions = facts.descriptions if facts is not None else ()
    services = tuple(s for item in descriptions for s in item.services)
    names = (*vendors, *(item.manufacturer for item in descriptions))
    lookup = facts.advisories if facts is not None else None
    advisories = None
    if lookup is not None:
        page = lookup.page
        ranked = _ranked(page.advisories) if page else []
        advisories = SupplementalAdvisories(
            lookup.status,
            lookup.checked_at,
            lookup.model,
            lookup.model_source,
            page.total if page else None,
            tuple(
                SupplementalAdvisory(
                    item.cve,
                    item.severity,
                    item.published_on,
                    item.known_exploited,
                    _brief(item.description, MAX_ADVISORY_TEXT),
                )
                for item in ranked[:limit]
            ),
            max(0, len(ranked) - limit),
            lookup.unavailable,
        )
    return DossierSupplemental(
        facts.described_at if facts is not None else None,
        facts.updated_at if facts is not None else None,
        descriptions,
        advisories,
        vendor_notes(names, device_type, services),
    )


def card_supplemental(
    card: DeviceCard, facts: DeviceFacts | None
) -> DossierSupplemental:
    """``supplemental_section`` for a monitored device's card."""
    current = card.identity
    return supplemental_section(
        facts,
        vendors=(current.vendor if current is not None else None,),
        device_type=card.detection.device_type,
    )


def _supplemental_coverage(section: DossierSupplemental, trimmed: int) -> list[str]:
    lines: list[str] = []
    if section.described_at is None:
        lines.append(
            "The device has not been asked to describe itself; device "
            "descriptions may be off or not yet run."
        )
    elif not section.descriptions:
        lines.append(
            "The device did not describe itself when asked; many devices do not, "
            "and that is not a finding."
        )
    else:
        lines.append(
            "The manufacturer, model and firmware under supplemental are the "
            "device's own claims over the local network, not confirmed identity."
        )
    advisories = section.advisories
    if advisories is None:
        lines.append("Public advisories have not been looked up for this device.")
    elif advisories.status == "model_unknown":
        lines.append(
            "No public model name was available, so advisories were not looked up."
        )
    elif advisories.status == "not_configured":
        lines.append(
            "No advisory source is configured, so advisories were not looked up."
        )
    elif advisories.status == "unavailable":
        lines.append(
            "The advisory lookup did not complete; advisories are unknown, not none."
        )
    else:
        lines.append(
            "Advisories match the model name, not this device's firmware or "
            "settings; a listed advisory may not apply to it."
        )
    if trimmed:
        lines.append(
            f"{trimmed} lower-ranked advisories omitted to fit the reply budget; "
            "the advisory total is complete."
        )
    if section.vendor_notes:
        lines.append(
            "Vendor notes are features worth checking for this manufacturer, "
            "not observations of this device."
        )
    return lines


# Deep-scan open ports named in the coverage line; the rest are counted.
_LISTED_DEEP_PORTS = 64


def _coverage(
    card: DeviceCard,
    profile: OperatorProfile | None,
    trimmed: int,
    section: DossierSupplemental,
    trimmed_advisories: int = 0,
    profile_note: ProfileNote | None = None,
    deep_scan: DeepScanResult | None = None,
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
    if deep_scan is not None:
        count = len(deep_scan.open_ports)
        # A device answering hundreds of ports would fill the reply budget;
        # the exposures still cover every kept port.
        listed = ", ".join(map(str, deep_scan.open_ports[:_LISTED_DEEP_PORTS]))
        if count > _LISTED_DEEP_PORTS:
            listed += f" and {count - _LISTED_DEEP_PORTS} more"
        found = (
            f"{count} open {'port' if count == 1 else 'ports'}: {listed}"
            if count
            else "no open ports"
        )
        lines.append(
            f"A full TCP scan on {deep_scan.finished_at[:10]} found {found}; "
            f"{deep_scan.unanswered} did not answer."
        )
        if deep_scan.detail is not None:
            lines.append(deep_scan.detail)
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
                f"{traffic.resets} counter "
                + ("reset" if traffic.resets == 1 else "resets")
                + " dropped intervals; byte totals are a lower bound."
            )
    if profile is None and profile_note == "address_only":
        lines.append(
            "A profile matches this address only and was not used: a matching "
            "address does not establish identity. Expected behaviour is unknown."
        )
    elif profile is None and profile_note == "ambiguous":
        lines.append(
            "Several profiles claim this device; none was used. Expected "
            "behaviour is unknown."
        )
    elif profile is None:
        lines.append("No operator profile supplied; expected behaviour is unknown.")
    lines.extend(_supplemental_coverage(section, trimmed_advisories))
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


def _exposures(
    seen: tuple[ServiceExposure, ...], deep_scan: DeepScanResult | None
) -> tuple[ServiceExposure, ...]:
    """The detection's exposures plus any a deep scan found that it had not."""
    if deep_scan is None:
        return seen
    known = {e.possible_service for e in seen}
    return seen + tuple(
        e
        for e in exposures_for(set(deep_scan.open_ports))
        if e.possible_service not in known
    )


def build_dossier(
    card: DeviceCard,
    profile: OperatorProfile | None = None,
    *,
    facts: DeviceFacts | None = None,
    budget: int = TOOL_REPLY_BYTES,
    profile_note: ProfileNote | None = None,
    deep_scan: DeepScanResult | None = None,
) -> DeviceDossier:
    """Build the dossier and fit it to ``budget`` bytes.

    Listed advisories are trimmed first, least severe first, then the
    lowest-ranked DNS destinations; counts record what was left out. Signals
    are computed before trimming from complete group totals, so the budget
    never changes them. A ``deep_scan`` adds a coverage line and the service
    exposures its open ports imply. Raises ValueError without a confirmed MAC,
    for facts or a deep scan about another device, for a deep scan that is not
    ``complete``, or when even the record without any advisories or
    destinations exceeds the budget.
    """
    device_id = confirmed_device_id(card)
    current = card.identity
    if device_id is None or current is None or current.mac is None:
        raise ValueError("A device dossier needs a confirmed MAC identity")
    if facts is not None and facts.device_id != device_id:
        raise ValueError("Device facts belong to another device")
    if deep_scan is not None and deep_scan.device_id != device_id:
        raise ValueError("Deep scan belongs to another device")
    if deep_scan is not None and deep_scan.state != "complete":
        raise ValueError("Only a complete deep scan can enter a dossier")
    ProfileMatch(profile, profile_note)
    section = card_supplemental(card, facts)
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
            _exposures(card.detection.exposures, deep_scan),
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
        section,
        _coverage(card, profile, 0, section, 0, profile_note, deep_scan),
        device_signals(card),
        addressing=card.addressing,
    )
    # Advisories go first: each is about the model rather than this device's
    # behaviour, and the total and CVE count stay in the record.
    trimmed_advisories = 0
    while (
        _size(dossier) > budget and section.advisories and section.advisories.advisories
    ):
        listed = section.advisories
        listed = replace(
            listed, advisories=listed.advisories[:-1], omitted=listed.omitted + 1
        )
        section = replace(section, advisories=listed)
        trimmed_advisories += 1
        dossier = replace(
            dossier,
            supplemental=section,
            coverage=_coverage(
                card, profile, 0, section, trimmed_advisories, profile_note, deep_scan
            ),
        )
    dns_section = dossier.dns
    drop = _drop_order(groups)
    trimmed = 0
    # The drop list is empty without DNS, so dns_section is set whenever used.
    while dns_section is not None and _size(dossier) > budget and trimmed < len(drop):
        index, item = drop[trimmed]
        trimmed += 1
        kept = list(dns_section.groups)
        group = kept[index]
        kept[index] = replace(
            group,
            omitted=group.omitted + 1,
            destinations=tuple(d for d in group.destinations if d is not item),
        )
        dns_section = replace(dns_section, groups=tuple(kept))
        dossier = replace(
            dossier,
            dns=dns_section,
            coverage=_coverage(
                card,
                profile,
                trimmed,
                section,
                trimmed_advisories,
                profile_note,
                deep_scan,
            ),
            trimmed_destinations=trimmed,
        )
    if _size(dossier) > budget:
        raise ValueError("Device dossier exceeds the reply budget")
    return dossier
