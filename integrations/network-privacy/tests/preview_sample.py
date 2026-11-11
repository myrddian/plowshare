"""A synthetic household network for designing the dashboard in the preview.

Every address is from the documentation ranges and every MAC is locally
administered. Device names are generic. Destination domains are real public
provider domains so classification renders as it does in production; the
counts are invented. Nothing here describes a real household.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Any

# Rows mirror the dashboard JSON exactly; they never enter application code.
Row = dict[str, Any]

NOW = datetime(2026, 11, 11, 21, 40, tzinfo=timezone.utc)


def _at(minutes_ago: float) -> str:
    return (NOW - timedelta(minutes=minutes_ago)).isoformat()


def _identity(address: str, name: str, mac: str, source: str = "unifi") -> Row:
    return {
        "address": address,
        "device_id": "mac:" + mac,
        "label": None,
        "hostname": name,
        "mac": mac,
        "vendor": None,
        "source": source,
        "observed_at": _at(2),
        "last_seen": _at(2),
    }


def _group(kind: str, *destinations: tuple[str, str | None, int, int]) -> Row:
    return {
        "kind": kind,
        "queries": sum(item[2] for item in destinations),
        "distinct": len(destinations),
        "destinations": [
            {"domain": d, "provider": p, "queries": q, "blocked": b}
            for d, p, q, b in destinations
        ],
    }


def _dns(*groups: Row, windows: int = 288, silent: bool = False) -> Row:
    total = sum(group["queries"] for group in groups)
    return {
        "from_at": _at(24 * 60),
        "until_at": _at(2),
        "windows": windows,
        "partial_windows": 0 if silent else 3,
        "queries_read": total,
        "queries_available": total,
        "groups": list(groups),
        "pihole_silent": silent,
    }


def _traffic(to_bytes: int, from_bytes: int, resets: int = 0) -> Row:
    return {
        "from_at": _at(24 * 60),
        "until_at": _at(2),
        "samples": 410,
        "to_device_bytes": to_bytes,
        "from_device_bytes": from_bytes,
        "resets": resets,
    }


def _device(
    index: int,
    name: str,
    device_type: str,
    *,
    ports: tuple[tuple[int, str], ...] = (),
    dns: Row | None = None,
    traffic: Row | None = None,
    seen: int = 410,
) -> Row:
    address = f"192.0.2.{index}"
    mac = f"02:00:00:00:00:{index:02x}"
    identity = _identity(address, name, mac)
    return {
        "address": address,
        "name": name,
        "identity": identity,
        "last_seen": _at(9),
        "services": [
            {"address": address, "port": port, "status": status}
            for port, status in ports
        ],
        "dns": [],
        "history": ["00000000-0000-5000-8000-%012d" % index],
        "revision": None,
        "first_response_at": _at(3 * 24 * 60),
        "last_response_at": _at(9),
        "last_known_identity": None,
        "detection": {
            "device_type": device_type,
            "confidence": "corroborated" if device_type != "Unknown" else "unknown",
            "clues": [f"Name contains '{name.split()[0].lower()}'"]
            if device_type != "Unknown"
            else [],
            "exposures": [],
        },
        "presence": {
            "seen": seen,
            "checked": 410,
            "last_seen": _at(2),
            "address_only": False,
            "checked_at": _at(2),
        },
        "dns_activity": dns,
        "traffic": traffic,
    }


def sample_devices() -> list[Row]:
    """Devices chosen to exercise every state the dashboard must render."""
    return [
        _device(
            1,
            "Gateway",
            "Router",
            ports=((53, "open"), (443, "open")),
            traffic=None,
        ),
        _device(
            10,
            "Living room TV",
            "Television",
            ports=((8008, "open"), (8009, "open")),
            dns=_dns(
                _group(
                    "measurement",
                    ("deliver.oztam.com.au", "OzTAM", 412, 0),
                    ("acr-eu-prd.samba.tv", "Samba TV", 96, 40),
                ),
                _group(
                    "advertising",
                    ("pubads.g.doubleclick.net", "Google Ads", 58, 58),
                ),
                _group(
                    "streaming",
                    ("cdn.iview.abc.net.au", "ABC iview", 220, 0),
                    ("nrdp.prod.ftl.netflix.com", "Netflix", 140, 0),
                ),
                _group(
                    "unclassified",
                    ("clients4.google.com", None, 310, 0),
                    ("android.googleapis.com", None, 77, 0),
                ),
                _group(
                    "connectivity",
                    ("connectivitycheck.gstatic.com", "Android", 288, 0),
                ),
            ),
            traffic=_traffic(18_400_000_000, 412_000_000, resets=1),
        ),
        _device(
            11,
            "Kitchen display",
            "Smart display",
            dns=_dns(
                _group("unclassified", ("home-devices.googleapis.com", None, 640, 0)),
                _group("connectivity", ("clients3.google.com", "Google", 288, 0)),
            ),
            traffic=_traffic(2_100_000_000, 246_000_000),
        ),
        _device(
            12,
            "Blu-ray player",
            "Media player",
            dns=_dns(
                _group(
                    "telemetry",
                    ("nrdp.logs.netflix.com", "Netflix device logs", 1790, 0),
                ),
                _group(
                    "streaming", ("nrdp.nm.push.prod.netflix.com", "Netflix", 270, 0)
                ),
            ),
            traffic=_traffic(980_000_000, 61_000_000),
        ),
        _device(
            20,
            "Driveway camera",
            "IP camera",
            ports=((80, "open"), (443, "open"), (554, "open")),
            dns=_dns(windows=402, silent=True),
            traffic=_traffic(310_000_000, 41_700_000_000),
        ),
        _device(
            21,
            "Front door camera",
            "IP camera",
            dns=_dns(
                _group(
                    "unclassified",
                    ("fw-snaps.prod.gws.ring.amazon.dev", None, 1040, 0),
                    ("fw-eventstream.ring.com", None, 40, 0),
                ),
            ),
            traffic=_traffic(41_000_000, 3_300_000_000),
        ),
        _device(
            22,
            "Nursery monitor",
            "IP camera",
            dns=_dns(windows=355, silent=True),
            traffic=_traffic(96_000_000, 2_900_000_000),
        ),
        _device(
            30,
            "Office PC",
            "Computer",
            ports=((445, "open"),),
            dns=_dns(
                _group(
                    "telemetry",
                    (
                        "v10.events.data.microsoft.com",
                        "Microsoft diagnostic data",
                        340,
                        0,
                    ),
                ),
                _group(
                    "unclassified",
                    ("notify.dropboxapi.com", None, 700, 0),
                    ("checkip.c2.synology.com", None, 280, 0),
                ),
                _group("local", ("14.2.0.192.in-addr.arpa", "Reverse lookup", 420, 0)),
            ),
            traffic=_traffic(6_200_000_000, 1_100_000_000),
        ),
        _device(
            31,
            "Phone",
            "Phone",
            dns=_dns(
                _group(
                    "advertising",
                    ("aax.amazon-adsystem.com", "Amazon Ads", 230, 210),
                ),
                _group(
                    "measurement",
                    ("app-measurement.com", "Google Analytics for Firebase", 460, 0),
                    ("appsflyer.com", "AppsFlyer", 120, 0),
                ),
                _group(
                    "telemetry",
                    ("crashlytics.com", "Firebase Crashlytics", 220, 0),
                ),
                _group("streaming", ("spotify.com", "Spotify", 2110, 0)),
                _group("unclassified", ("gateway.icloud.com", None, 1739, 0)),
            ),
            traffic=_traffic(9_800_000_000, 640_000_000, resets=3),
            seen=260,
        ),
        _device(
            40,
            "Home battery",
            "Energy",
            dns=_dns(
                _group(
                    "unclassified",
                    ("am-i-online-prd.sn.tesla.services", None, 2120, 0),
                    ("configupdate-prd.sn.tesla.services", None, 40, 0),
                ),
            ),
            traffic=_traffic(123_000_000, 170_000_000),
        ),
        _device(
            41,
            "Garden irrigation",
            "Controller",
            dns=_dns(
                _group(
                    "unclassified",
                    ("updates3.rainmachine.com", None, 1920, 0),
                    ("api.willyweather.com.au", None, 50, 0),
                ),
            ),
            traffic=_traffic(235_000_000, 213_000_000),
        ),
        _device(
            50,
            "Hallway lamp",
            "Smart light",
            dns=_dns(windows=40, silent=False),
            traffic=_traffic(1_200_000, 2_300_000),
            seen=402,
        ),
        _device(
            51,
            "Bedroom air conditioner",
            "Climate",
            dns=_dns(windows=300, silent=True),
            traffic=_traffic(8_100_000, 4_400_000),
        ),
        _device(
            60,
            "Storage server",
            "NAS",
            ports=((22, "open"), (443, "open"), (445, "open")),
            dns=_dns(
                _group("unclassified", ("checkip.synology.com", None, 288, 0)),
                _group("connectivity", ("pool.ntp.org", "NTP Pool", 96, 0)),
            ),
            traffic=_traffic(52_000_000_000, 44_100_000_000),
        ),
        _device(
            61,
            "Game console",
            "Console",
            dns=None,
            traffic=_traffic(31_000_000_000, 900_000_000),
            seen=90,
        ),
        _device(
            62,
            "Unnamed device",
            "Unknown",
            ports=((80, "timeout"),),
            dns=None,
            traffic=None,
            seen=12,
        ),
    ]


def scaled_network(total: int) -> tuple[list[Row], list[Row]]:
    """Repeat the sample devices up to ``total`` and add 160 candidate addresses.

    Copies get new documentation-range addresses and numbered names, so the
    dashboard can be judged at the size of a real household (about 90 devices
    and 160 unresponsive candidates) rather than 16 hand-made rows.
    """
    base = sample_devices()
    devices = []
    for index in range(total):
        device = json.loads(json.dumps(base[index % len(base)]))
        if index >= len(base):
            octet = index + 1
            prefix = "198.51.100" if octet < 255 else "203.0.113"
            address = f"{prefix}.{octet % 254 + 1}"
            device["address"] = address
            device["name"] = f"{device['name']} {index // len(base) + 1}"
            device["identity"]["address"] = address
            for service in device["services"]:
                service["address"] = address
        devices.append(device)
    candidates = [
        {"address": f"203.0.113.{octet}", "label": None, "last_checked_at": _at(9)}
        for octet in range(1, 161)
    ]
    return devices, candidates
