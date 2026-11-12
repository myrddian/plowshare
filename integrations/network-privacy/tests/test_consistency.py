"""Group 3 of the review: limits, names, zones and profiles that must agree."""

from __future__ import annotations

import json
import re
import unittest
from dataclasses import replace
from ipaddress import IPv4Network
from pathlib import Path
from uuid import uuid4

from plowshare_privacy.assessment_request import AssessmentRequest
from plowshare_privacy.contracts import (
    Configuration,
    DeviceIdentity,
    TcpObservation,
    household_day,
    local_time,
)
from plowshare_privacy.device_detection import detect_device
from plowshare_privacy.device_dossier import ProfileMatch
from plowshare_privacy.device_profiles import (
    DeviceProfile,
    ProfileFields,
    ProfileView,
    assessment_profile,
)
from plowshare_privacy.discovery import DiscoveryPlan
from plowshare_privacy.limits import MAX_PORTS
from plowshare_privacy.monitor import MonitorChoice
from plowshare_privacy.privacy_issues import IssueLink
from plowshare_privacy.system_view import describe

EXAMPLES = Path(__file__).resolve().parents[1] / "examples"
MAC = "02:00:00:00:00:01"
ADDRESS = "192.0.2.10"
STAMP = "2026-11-11T09:00:00Z"
# The script's pattern for a household time (assess_network_device.js LOCAL).
SCRIPT_LOCAL = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2} [A-Za-z0-9+\-]{1,8}$")


def config(**changes: object) -> Configuration:
    row = json.loads((EXAMPLES / "config.json").read_text())
    row.update(changes)
    return Configuration.decode(row, EXAMPLES / "config.json")


def view(*, mac: str | None, addresses: tuple[str, ...] = (ADDRESS,)) -> ProfileView:
    link = IssueLink(
        str(uuid4()), str(uuid4()), "potentially_affected", "proposed", "", ()
    )
    fields = ProfileFields(
        "Living room TV",
        "",
        "",
        "Streaming",
        "HTTPS",
        "",
        addresses,
        (link,),
        mac,
    )
    return ProfileView(
        DeviceProfile(1, str(uuid4()), str(uuid4()), STAMP, fields),
        str(uuid4()),
        (),
        (),
    )


class PortAndAddressLimitTest(unittest.TestCase):
    def test_the_dashboard_accepts_the_configurations_port_bound(self) -> None:
        ports = list(range(1, MAX_PORTS + 1))
        choice = MonitorChoice.decode(
            {
                "enabled": True,
                "targets": [ADDRESS.replace("192.0.2", "192.168.0")],
                "ports": ports,
            }
        )
        self.assertEqual(len(choice.ports), MAX_PORTS)
        with self.assertRaises(ValueError):
            MonitorChoice.decode(
                {"enabled": True, "targets": ["192.168.0.10"], "ports": [*ports, 9999]}
            )
        plan = DiscoveryPlan(IPv4Network("192.168.0.0/24"), tuple(ports), 0.5, 32)
        self.assertEqual(len(plan.ports), MAX_PORTS)

    def test_a_public_address_is_named_with_where_it_can_be_set(self) -> None:
        with self.assertRaisesRegex(
            ValueError, r"^8\.8\.8\.8 is not a private address.*collector\.json"
        ):
            MonitorChoice.decode(
                {
                    "enabled": True,
                    "targets": ["192.168.0.10", "8.8.8.8"],
                    "ports": [443],
                }
            )


class DetectionTest(unittest.TestCase):
    def test_a_name_clue_alone_is_tentative(self) -> None:
        camera = DeviceIdentity(
            ADDRESS,
            "ip:" + ADDRESS,
            None,
            "porch-camera",
            None,
            None,
            "pihole-v6",
            STAMP,
            STAMP,
        )
        named = detect_device(camera, (TcpObservation(ADDRESS, 80, "open"),))
        self.assertEqual(named.confidence, "tentative")
        self.assertEqual(len(named.clues), 1)
        both = detect_device(camera, (TcpObservation(ADDRESS, 554, "open"),))
        self.assertEqual(both.confidence, "corroborated")


class HouseholdZoneTest(unittest.TestCase):
    def test_a_configured_zone_is_validated_and_unset_means_utc(self) -> None:
        self.assertIsNone(config().time_zone)
        self.assertEqual(config().zone, "UTC")
        self.assertEqual(config(timeZone="Australia/Sydney").zone, "Australia/Sydney")
        for invalid in ("Mars/Olympus", "../etc/passwd", " Australia/Sydney", 10):
            with self.assertRaisesRegex(ValueError, "IANA time zone"):
                config(timeZone=invalid)

    def test_days_and_times_follow_the_zone_and_match_the_script(self) -> None:
        late = "2026-11-11T13:30:00Z"
        self.assertEqual(str(household_day(late, "UTC")), "2026-11-11")
        self.assertEqual(str(household_day(late, "Australia/Sydney")), "2026-11-12")
        for zone in ("UTC", "Australia/Sydney", "Asia/Kolkata", "America/St_Johns"):
            self.assertRegex(local_time(late, zone), SCRIPT_LOCAL)
        self.assertEqual(local_time(late, "Australia/Sydney"), "2026-11-12 00:30 AEDT")

    def test_a_request_without_a_zone_keeps_its_version_1_bytes(self) -> None:
        dossier = str(uuid4())
        legacy = AssessmentRequest("mac:" + MAC, dossier, None, STAMP)
        self.assertEqual(json.loads(legacy.encode())["version"], 1)
        self.assertNotIn("time_zone", legacy.encode())
        zoned = replace(legacy, time_zone="Australia/Sydney")
        self.assertEqual(
            json.loads(zoned.encode()),
            {
                "version": 2,
                "device_id": "mac:" + MAC,
                "dossier": dossier,
                "previous": None,
                "requested_at": STAMP,
                "time_zone": "Australia/Sydney",
                "requested_local": "2026-11-11 20:00 AEDT",
            },
        )
        with self.assertRaises(ValueError):
            replace(legacy, time_zone="Not/AZone")

    def test_system_health_shows_the_zone_or_that_none_is_set(self) -> None:
        unset = describe(config(), None, {}, lambda path: False)
        self.assertIsNone(unset.deployment.time_zone)
        zoned = describe(
            config(timeZone="Australia/Sydney"), None, {}, lambda path: False
        )
        self.assertEqual(zoned.deployment.time_zone, "Australia/Sydney")


class AssessmentProfileTest(unittest.TestCase):
    def test_only_a_profile_confirmed_for_the_mac_is_used(self) -> None:
        confirmed = view(mac=MAC)
        match = assessment_profile((confirmed, view(mac=None)), MAC, ADDRESS)
        assert match.profile is not None
        self.assertEqual(match.profile.revision, confirmed.revision)
        self.assertEqual(match.profile.purpose, "Streaming")
        self.assertEqual(
            match.profile.issues[0].issue_id,
            confirmed.profile.fields.issues[0].issue_id,
        )

    def test_an_address_match_or_several_claims_explain_why_none_is_used(self) -> None:
        self.assertEqual(
            assessment_profile((view(mac=None),), MAC, ADDRESS),
            ProfileMatch(None, "address_only"),
        )
        self.assertEqual(
            assessment_profile((view(mac=MAC), view(mac=MAC)), MAC, ADDRESS),
            ProfileMatch(None, "ambiguous"),
        )
        self.assertEqual(
            assessment_profile(
                (view(mac=None, addresses=("192.0.2.99",)),), MAC, ADDRESS
            ),
            ProfileMatch(None),
        )
        # A profile for another device's MAC is never borrowed by address.
        self.assertEqual(
            assessment_profile((view(mac="02:00:00:00:00:02"),), MAC, ADDRESS),
            ProfileMatch(None),
        )

    def test_a_used_profile_cannot_also_carry_a_note(self) -> None:
        profile = assessment_profile((view(mac=MAC),), MAC, ADDRESS).profile
        with self.assertRaises(ValueError):
            ProfileMatch(profile, "address_only")


if __name__ == "__main__":
    unittest.main()
