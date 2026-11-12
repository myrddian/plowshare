"""The preview's sample assessment, produced by the real code paths.

The dossier is built by ``build_dossier`` from the same sample device and
facts the preview shows, and wrapped in the real version 3 envelope. The
assessor and reviewer answers are fixed sample text. A Java test runs them
through the real ``assess_network_device`` script and requires the result to
equal ``preview-tv-assessment.md``, which the preview serves as its report, so
the sample report can never drift from the renderer or the sample data.

Regenerate the run file after changing the sample data:

    PYTHONPATH=../src python preview_report.py
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from preview_sample import TV_DEVICE, _sample_facts, sample_devices

from plowshare_privacy.contracts import (
    DeviceIdentity,
    TcpObservation,
    local_time,
)
from plowshare_privacy.device_detection import detect_device
from plowshare_privacy.device_dossier import build_dossier, encode_dossier
from plowshare_privacy.device_inventory import DeviceCard
from plowshare_privacy.dns_activity import Destination, DestinationGroup, DnsActivity
from plowshare_privacy.dossier_port import DossierDocument
from plowshare_privacy.presence import PresenceCount
from plowshare_privacy.traffic import TrafficVolume

RESOURCES = (
    Path(__file__).resolve().parents[1]
    / "src/test/resources/io/aeyer/plowshare/integrations/privacy"
)
RUN = RESOURCES / "preview-tv-run.json"
REPORT = RESOURCES / "preview-tv-assessment.md"
ZONE = "Australia/Sydney"
WRITE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
# The revision the Java test host serves the dossier under.
DOSSIER_REVISION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
SCAN_REVISION = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
REQUESTED_AT = "2026-11-11T21:00:00Z"


def _z(stamp: str) -> str:
    # The collector writes UTC timestamps with Z; the sample rows use +00:00.
    return stamp.replace("+00:00", "Z")


def sample_card() -> DeviceCard:
    """The Living room TV sample row as the inventory's DeviceCard."""
    row = next(d for d in sample_devices() if d["identity"]["device_id"] == TV_DEVICE)
    identity = DeviceIdentity(
        **{
            **row["identity"],
            "observed_at": _z(row["identity"]["observed_at"]),
            "last_seen": _z(row["identity"]["last_seen"]),
        }
    )
    services = tuple(TcpObservation(**item) for item in row["services"])
    dns = row["dns_activity"]
    traffic = row["traffic"]
    presence = row["presence"]
    return DeviceCard(
        row["address"],
        row["name"],
        identity,
        _z(row["last_seen"]),
        services,
        (),
        tuple(row["history"]),
        SCAN_REVISION,
        _z(row["first_response_at"]),
        _z(row["last_response_at"]),
        None,
        detect_device(identity, services),
        PresenceCount(
            presence["seen"],
            presence["checked"],
            _z(presence["last_seen"]),
            presence["address_only"],
            _z(presence["checked_at"]),
        ),
        DnsActivity(
            _z(dns["from_at"]),
            _z(dns["until_at"]),
            dns["windows"],
            dns["partial_windows"],
            dns["queries_read"],
            dns["queries_available"],
            tuple(
                DestinationGroup(
                    group["kind"],
                    group["queries"],
                    group["distinct"],
                    tuple(Destination(**item) for item in group["destinations"]),
                )
                for group in dns["groups"]
            ),
            dns["pihole_silent"],
        ),
        TrafficVolume(
            _z(traffic["from_at"]),
            _z(traffic["until_at"]),
            traffic["samples"],
            traffic["to_device_bytes"],
            traffic["from_device_bytes"],
            traffic["resets"],
        ),
    )


ASSESSOR: dict[str, Any] = {
    "in_short": "The TV looked up Samba TV's content-recognition service 96 times in a day. That is consistent with the TV identifying what is on screen and reporting it. It also looked up OzTAM, an audience-measurement company, 412 times, and an advertising service that Pi-hole blocked every time.",
    "what_it_is": {
        "text": "A smart TV used for streaming and broadcast catch-up.",
        "confidence": "medium",
        "basis": [
            "The device name says it is a TV.",
            "It describes itself as a Sony BRAVIA over UPnP.",
            "Most named lookups are for ABC iview and Netflix.",
        ],
    },
    "who_it_talks_to": [
        {
            "party": "Samba TV",
            "purpose": "measurement",
            "observation": "Looked up 96 times; 40 were blocked.",
            "meaning": "Samba TV matches what is on screen against known programmes and adverts.",
        },
        {
            "party": "OzTAM",
            "purpose": "measurement",
            "observation": "Looked up 412 times; none were blocked.",
            "meaning": "OzTAM measures Australian TV audiences for broadcasters.",
        },
        {
            "party": "Google Ads",
            "purpose": "advertising",
            "observation": "Looked up 58 times; all 58 were blocked.",
            "meaning": "Apps on the TV request adverts.",
        },
        {
            "party": "ABC iview and Netflix",
            "purpose": "streaming",
            "observation": "Looked up 360 times.",
            "meaning": "Ordinary streaming use.",
        },
    ],
    "what_it_sends": {
        "observed": "Most of the volume went to the TV, which is consistent with streaming video.",
        "unknown": "Where the outgoing bytes went, and what they contained, cannot be told from this data.",
    },
    "concerns": [
        {
            "id": "c1",
            "title": "Viewing recognised by Samba TV",
            "level": "worth_checking",
            "fact": "The TV looked up a Samba TV content-recognition name 96 times, and 56 of those lookups were answered.",
            "inference": "The TV's built-in viewing-data feature is probably on and reporting what is watched.",
            "confidence": "high",
            "signals": ["measurement_dns"],
            "mitigation": "Turn off viewing information or content recognition in the TV's privacy settings, then block samba.tv in Pi-hole.",
            "verify": "Check that the Pi-hole query log shows no further answered Samba TV lookups after a day of normal use.",
        },
        {
            "id": "c2",
            "title": "Viewing measurement by OzTAM",
            "level": "worth_checking",
            "fact": "The TV looked up an OzTAM measurement name 412 times; none were blocked.",
            "inference": "A broadcaster app on the TV is probably reporting what is watched to OzTAM.",
            "confidence": "medium",
            "signals": ["measurement_dns"],
            "mitigation": "Block oztam.com.au in Pi-hole. Some catch-up apps may refuse to play.",
            "verify": "Check that the Pi-hole query log shows the OzTAM lookups as blocked.",
        },
        {
            "id": "c3",
            "title": "Advertising lookups",
            "level": "expected",
            "fact": "All 58 lookups for Google's advertising service were blocked by Pi-hole.",
            "inference": "Apps on the TV request adverts; the block is working.",
            "confidence": "high",
            "signals": ["advertising_dns"],
            "mitigation": "None needed; the existing Pi-hole block covers it.",
            "verify": "Keep an eye on the blocked count staying equal to the total.",
        },
    ],
    "questions": [
        "Was viewing information turned on during the TV's first setup?",
        "Which catch-up apps are used on the TV?",
    ],
}

REVIEWER: dict[str, Any] = {
    "verdict": "accepted",
    "concerns": [
        {"id": "c1", "verdict": "holds", "note": "Matches the measurement signal."},
        {"id": "c2", "verdict": "holds", "note": "Matches the measurement signal."},
        {"id": "c3", "verdict": "holds", "note": "Matches the advertising signal."},
    ],
    "notes": "Supported by the dossier; coverage gaps are stated.",
}


def preview_run() -> dict[str, Any]:
    """The request, retained dossier text and agent answers for the sample run."""
    card = sample_card()
    dossier = build_dossier(card, facts=_sample_facts(TV_DEVICE, True))
    document = DossierDocument(WRITE_ID, TV_DEVICE, encode_dossier(dossier), zone=ZONE)
    return {
        "request": {
            "version": 2,
            "device_id": TV_DEVICE,
            "dossier": DOSSIER_REVISION,
            "previous": None,
            "requested_at": REQUESTED_AT,
            "time_zone": ZONE,
            "requested_local": local_time(REQUESTED_AT, ZONE),
        },
        "dossier": document.text(),
        "assessor": ASSESSOR,
        "reviewer": REVIEWER,
    }


def sample_report() -> str:
    """The renderer's report for the sample run, as the Java test pins it."""
    return REPORT.read_text(encoding="utf-8")


if __name__ == "__main__":
    RUN.write_text(json.dumps(preview_run(), indent=2) + "\n", encoding="utf-8")
    print(RUN)
