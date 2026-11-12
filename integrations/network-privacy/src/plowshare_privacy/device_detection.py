"""Explain tentative device types and service exposure from a single sweep."""

import re
from dataclasses import dataclass
from typing import Literal

from .contracts import DeviceIdentity, TcpObservation


@dataclass(frozen=True)
class ServiceExposure:
    ports: tuple[int, ...]
    possible_service: str
    risk_check: str


@dataclass(frozen=True)
class DeviceDetection:
    device_type: str
    confidence: Literal["tentative", "corroborated", "unknown"]
    clues: tuple[str, ...]
    exposures: tuple[ServiceExposure, ...]


@dataclass(frozen=True)
class _TypeRule:
    device_type: str
    name_words: frozenset[str]
    ports: frozenset[int] = frozenset()


# Names and conventional ports are clues. A vendor string can identify a network
# interface maker, and an open socket does not identify the protocol or software.
_TYPE_RULES = (
    _TypeRule(
        "Printer",
        frozenset({"printer", "laserjet", "officejet"}),
        frozenset({631, 9100}),
    ),
    _TypeRule(
        "Camera or video recorder",
        frozenset({"camera", "ipcam", "doorbell", "nvr"}),
        frozenset({554, 8554}),
    ),
    _TypeRule(
        "Network storage",
        frozenset({"nas", "diskstation", "truenas"}),
        frozenset({445, 2049}),
    ),
    _TypeRule(
        "Network infrastructure",
        frozenset({"router", "gateway", "switch", "accesspoint", "firewall"}),
    ),
    _TypeRule("Phone or tablet", frozenset({"iphone", "ipad", "android", "pixel"})),
    _TypeRule(
        "TV or streaming player",
        frozenset({"smarttv", "appletv", "roku", "chromecast", "firetv"}),
    ),
)
_SERVICE_RULES = (
    (
        (22,),
        "SSH",
        "If this is SSH administration, check who can reach it and which authentication methods are enabled.",
    ),
    (
        (23,),
        "Telnet",
        "If this is Telnet, credentials and session traffic may be readable on the network. Check whether it can be disabled.",
    ),
    (
        (53,),
        "DNS",
        "If this is a DNS resolver, check that recursive queries are limited to the intended clients.",
    ),
    (
        (80, 8080),
        "HTTP",
        "Check whether this listener serves an admin interface and whether sign-in and administration require encryption.",
    ),
    (
        (443,),
        "HTTPS",
        "If this is a management interface, check that access is limited to the intended clients.",
    ),
    (
        (445, 2049),
        "File sharing",
        "If file sharing is enabled, review guest access, share permissions and which network segments can reach it.",
    ),
    (
        (554, 8554),
        "Media streaming",
        "If this carries audio or video, check authentication and which network segments can reach the stream.",
    ),
    (
        (631, 9100),
        "Printing",
        "If this is a print service, check who can submit jobs and reach its management interface.",
    ),
    (
        (3389,),
        "Remote desktop",
        "If this is remote desktop, check which clients can connect and whether strong authentication is required.",
    ),
)


def detect_device(
    identity: DeviceIdentity | None, services: tuple[TcpObservation, ...]
) -> DeviceDetection:
    """Use only current observations. Names never establish vendor, model or firmware."""
    opened = {probe.port for probe in services if probe.status == "open"}
    hostname = (
        identity.hostname
        if identity is not None and identity.source != "configured"
        else None
    )
    words = set(re.findall(r"[a-z]+", (hostname or "").lower()))
    matches: list[tuple[str, tuple[str, ...], bool, bool]] = []
    for rule in _TYPE_RULES:
        matched_ports = sorted(rule.ports & opened)
        matched_name = bool(rule.name_words & words)
        clues = []
        if matched_name:
            clues.append(f"Observed device name suggests {rule.device_type.lower()}")
        if matched_ports:
            ports = ", ".join(map(str, matched_ports))
            clues.append(
                f"Open TCP {ports} is consistent with {rule.device_type.lower()}"
            )
        # File sharing and streaming alone do not distinguish a NAS or camera
        # from a general-purpose host running the same service.
        if matched_name or (rule.device_type == "Printer" and matched_ports):
            matches.append(
                (rule.device_type, tuple(clues), matched_name, bool(matched_ports))
            )
    exposures = tuple(
        ServiceExposure(tuple(port for port in ports if port in opened), service, check)
        for ports, service, check in _SERVICE_RULES
        if opened.intersection(ports)
    )
    if len(matches) != 1:
        return DeviceDetection(
            "Unknown",
            "unknown",
            tuple(clue for _, clues, _, _ in matches for clue in clues),
            exposures,
        )
    device_type, matched_clues, by_name, by_port = matches[0]
    # Corroboration means two independent observations agree: the device's
    # own name and a conventional open port. One clue alone stays tentative.
    return DeviceDetection(
        device_type,
        "corroborated" if by_name and by_port else "tentative",
        matched_clues,
        exposures,
    )
