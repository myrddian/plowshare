"""Device dossiers are bounded, typed projections with deterministic signals."""

from __future__ import annotations

import json
import unittest
from dataclasses import replace
from uuid import uuid4

from plowshare_privacy.advisories import Advisory, AdvisoryPage
from plowshare_privacy.contracts import DeviceIdentity, TcpObservation
from plowshare_privacy.deep_scan import DeepScanResult
from plowshare_privacy.device_detection import _SERVICE_RULES, detect_device
from plowshare_privacy.device_dossier import (
    ADMIN_SERVICES,
    MAX_ADVISORY_TEXT,
    MAX_SUPPLEMENTAL_ADVISORIES,
    MIN_ADVERTISING_QUERIES,
    MIN_MEASUREMENT_QUERIES,
    MIN_TELEMETRY_QUERIES,
    UPLOAD_HEAVY_BYTES,
    LinkedIssue,
    OperatorProfile,
    build_dossier,
    confirmed_device_id,
    device_signals,
    encode_dossier,
    parse_device_id,
)
from plowshare_privacy.device_facts import (
    AdvisoryFacts,
    DeviceDescription,
    DeviceFacts,
)
from plowshare_privacy.device_inventory import DeviceCard
from plowshare_privacy.dns_activity import (
    MIN_SILENT_SECONDS,
    MIN_SILENT_WINDOWS,
    DnsActivity,
    group_destinations,
    pihole_silent,
)
from plowshare_privacy.presence import PresenceCount
from plowshare_privacy.traffic import TrafficVolume

ADDRESS = "10.1.2.10"
MAC = "02:00:00:00:00:01"
FROM_AT = "2026-01-01T00:00:00Z"
UNTIL_AT = "2026-01-01T08:00:00Z"
REVISION = str(uuid4())


def identity(*, mac: str | None = MAC) -> DeviceIdentity:
    return DeviceIdentity(
        ADDRESS,
        "mac:" + mac if mac else "ip:" + ADDRESS,
        None,
        "living-room-smarttv" if mac else None,
        mac,
        "Sony" if mac else None,
        "pihole-v6" if mac else "configured",
        FROM_AT,
        FROM_AT if mac else None,
    )


def activity(
    domains: list[tuple[str, int, int]],
    *,
    windows: int = 96,
    partial: int = 0,
    read: int | None = None,
    available: int | None = None,
    silent: bool = False,
) -> DnsActivity:
    total = sum(count for _, count, _ in domains)
    return DnsActivity(
        FROM_AT,
        UNTIL_AT,
        windows,
        partial,
        total if read is None else read,
        total if available is None else available,
        group_destinations(domains),
        silent,
    )


def card(
    *,
    who: DeviceIdentity | None = None,
    ports: tuple[tuple[int, str], ...] = ((443, "open"), (22, "closed")),
    dns: DnsActivity | None = None,
    traffic: TrafficVolume | None = None,
    presence: PresenceCount | None = PresenceCount(90, 96, UNTIL_AT, False, UNTIL_AT),
    revision: str | None = REVISION,
) -> DeviceCard:
    current = who if who is not None else identity()
    services = tuple(TcpObservation(ADDRESS, port, status) for port, status in ports)  # type: ignore[arg-type]
    return DeviceCard(
        address=ADDRESS,
        name="Lounge TV",
        identity=current,
        last_seen=UNTIL_AT,
        services=services,
        dns=(),
        history=(),
        revision=revision,
        first_response_at=FROM_AT,
        last_response_at=UNTIL_AT,
        last_known_identity=current,
        detection=detect_device(current, services),
        presence=presence,
        dns_activity=dns,
        traffic=traffic,
    )


def volume(to_device: int, from_device: int, resets: int = 0) -> TrafficVolume:
    return TrafficVolume(FROM_AT, UNTIL_AT, 97, to_device, from_device, resets)


DESCRIBED_AT = "2026-01-01T07:10:00Z"
MODEL = "BRAVIA KD-55X85K"


def advisory(
    number: int, severity: str | None, exploited: bool = False, day: int = 1
) -> Advisory:
    return Advisory(
        f"CVE-2026-{number:04d}",
        f"Advisory {number} for the fixture model. " + "Detail. " * 60,
        f"2026-01-{day:02d}",
        severity,
        exploited,
    )


def facts(
    *,
    described: bool = True,
    advisories: tuple[Advisory, ...] = (),
    status: str = "searched",
    device_id: str = "mac:" + MAC,
) -> DeviceFacts:
    description = DeviceDescription(
        "upnp",
        DESCRIBED_AT,
        "Sony",
        MODEL,
        None,
        "6.6.0",
        ("screen casting (DIAL)", "remote control (Sony IRCC)"),
    )
    if status == "searched":
        lookup = AdvisoryFacts(
            "searched",
            DESCRIBED_AT,
            MODEL,
            "device",
            AdvisoryPage(MODEL, DESCRIBED_AT, len(advisories) + 4, advisories),
        )
    else:
        lookup = AdvisoryFacts("model_unknown", DESCRIBED_AT)
    return DeviceFacts(
        device_id,
        ADDRESS,
        DESCRIBED_AT,
        DESCRIBED_AT,
        (description,) if described else (),
        lookup,
    )


def codes(value: DeviceCard) -> set[str]:
    return {signal.code for signal in device_signals(value)}


class DeviceDossierTest(unittest.TestCase):
    def test_dossier_carries_identity_detection_presence_and_open_services_only(
        self,
    ) -> None:
        dossier = build_dossier(card(dns=activity([("youtube.com", 5, 0)])))
        self.assertEqual(dossier.identity.device_id, "mac:" + MAC)
        self.assertEqual(dossier.identity.mac, MAC)
        self.assertEqual(dossier.identity.address, ADDRESS)
        self.assertEqual(dossier.identity.vendor, "Sony")
        self.assertEqual(dossier.identity.source, "pihole-v6")
        self.assertEqual(dossier.identity.observed_at, FROM_AT)
        self.assertEqual(dossier.detection.device_type, "TV or streaming player")
        self.assertEqual(dossier.services.open_ports, (443,))
        self.assertEqual(dossier.services.revision, REVISION)
        assert dossier.presence is not None
        self.assertEqual((dossier.presence.seen, dossier.presence.checked), (90, 96))

    def test_no_dossier_without_a_confirmed_mac_identity(self) -> None:
        for value in (
            card(who=identity(mac=None)),
            replace(card(), identity=None),
        ):
            self.assertIsNone(confirmed_device_id(value))
            with self.assertRaisesRegex(ValueError, "confirmed MAC"):
                build_dossier(value)

    def test_device_id_boundary_accepts_only_canonical_mac_identities(self) -> None:
        self.assertEqual(parse_device_id("mac:" + MAC), "mac:" + MAC)
        for value in (
            "mac:02:00:00:00:00:0A",
            "ip:" + ADDRESS,
            ADDRESS,
            "mac:ff:ff:ff:ff:ff:ff",
            "mac:02-00-00-00-00-01",
            None,
            7,
            "",
        ):
            with self.assertRaises(ValueError):
                parse_device_id(value)

    def test_group_totals_are_preserved_when_destinations_are_trimmed(self) -> None:
        domains = [
            (f"host{index:02d}-{'x' * 50}.example.net", 100 + index, 0)
            for index in range(40)
        ] + [("oztam.com.au", 12, 2), ("connectivitycheck.gstatic.com", 400, 0)]
        value = card(dns=activity(domains), traffic=volume(10, 5))
        full = build_dossier(value)
        trimmed = build_dossier(value, budget=3600)
        self.assertGreater(trimmed.trimmed_destinations, 0)
        self.assertLessEqual(len(encode_dossier(trimmed).encode()), 3600)
        assert full.dns is not None and trimmed.dns is not None
        totals = {
            group.kind: (group.queries, group.distinct) for group in full.dns.groups
        }
        self.assertEqual(
            totals,
            {
                group.kind: (group.queries, group.distinct)
                for group in trimmed.dns.groups
            },
        )
        self.assertEqual(totals["unclassified"], (sum(100 + i for i in range(40)), 40))
        for group in trimmed.dns.groups:
            self.assertEqual(group.distinct, len(group.destinations) + group.omitted)
        shown = {
            item.domain for group in trimmed.dns.groups for item in group.destinations
        }
        # Behavioural destinations outrank plumbing and unclassified names.
        self.assertIn("oztam.com.au", shown)
        self.assertNotIn("connectivitycheck.gstatic.com", shown)
        self.assertEqual(
            sum(group.omitted for group in trimmed.dns.groups)
            - sum(group.omitted for group in full.dns.groups),
            trimmed.trimmed_destinations,
        )
        self.assertTrue(
            any("omitted to fit" in line for line in trimmed.coverage),
            trimmed.coverage,
        )
        self.assertEqual(full.signals, trimmed.signals)

    def test_budget_that_cannot_hold_the_core_record_is_refused(self) -> None:
        with self.assertRaisesRegex(ValueError, "budget"):
            build_dossier(card(), budget=200)

    def test_missing_dns_traffic_presence_and_scan_are_coverage_lines_not_zero(
        self,
    ) -> None:
        dossier = build_dossier(card(presence=None, revision=None))
        self.assertIsNone(dossier.dns)
        self.assertIsNone(dossier.traffic)
        self.assertIsNone(dossier.presence)
        self.assertIsNone(dossier.pihole_silent)
        text = "\n".join(dossier.coverage)
        self.assertIn("No Pi-hole DNS profile", text)
        self.assertIn("No UniFi byte counters for this device", text)
        self.assertIn("No quick presence checks", text)
        self.assertIn("No retained port scan", text)
        self.assertIn("No operator profile", text)
        encoded = json.loads(encode_dossier(dossier))
        self.assertIsNone(encoded["dns"])
        self.assertIsNone(encoded["traffic"])

    def test_partial_dns_sampling_resets_and_local_traffic_are_stated(self) -> None:
        dossier = build_dossier(
            card(
                dns=activity(
                    [("youtube.com", 5, 0)], windows=40, partial=3, available=50
                ),
                traffic=volume(10, 5, resets=2),
            )
        )
        text = "\n".join(dossier.coverage)
        self.assertIn("DNS profile partial in 3 of 40 windows", text)
        self.assertIn("Read 5 of 50 DNS queries", text)
        self.assertIn(
            "Byte totals include local traffic and say nothing about destinations", text
        )
        self.assertIn("2 counter resets", text)
        self.assertIn("DNS lookups show names resolved, not data sent", text)

    def test_measurement_and_advertising_signals_at_and_below_threshold(self) -> None:
        self.assertEqual(MIN_MEASUREMENT_QUERIES, 1)
        self.assertEqual(MIN_ADVERTISING_QUERIES, 1)
        at = card(dns=activity([("oztam.com.au", 1, 0), ("doubleclick.net", 1, 1)]))
        self.assertTrue({"measurement_dns", "advertising_dns"} <= codes(at))
        below = card(dns=activity([("youtube.com", 9, 0)]))
        self.assertFalse({"measurement_dns", "advertising_dns"} & codes(below))
        basis = {signal.code: signal.basis for signal in device_signals(at)}
        self.assertIn("OzTAM", basis["measurement_dns"])
        self.assertIn("1 blocked", basis["advertising_dns"])

    def test_telemetry_dominant_needs_half_of_service_lookups_and_a_minimum(
        self,
    ) -> None:
        self.assertEqual(MIN_TELEMETRY_QUERIES, 20)
        noise = [("time.google.com", 500, 0), ("10.in-addr.arpa", 500, 0)]
        at = card(dns=activity([("sentry.io", 20, 0), ("youtube.com", 20, 0), *noise]))
        self.assertIn("telemetry_dominant", codes(at))
        share_below = card(
            dns=activity([("sentry.io", 20, 0), ("youtube.com", 21, 0), *noise])
        )
        self.assertNotIn("telemetry_dominant", codes(share_below))
        count_below = card(dns=activity([("sentry.io", 19, 0), *noise]))
        self.assertNotIn("telemetry_dominant", codes(count_below))

    def test_dns_bypass_follows_pihole_silence_with_hours_and_checks(self) -> None:
        span = float(MIN_SILENT_SECONDS)
        for windows, expected in (
            (MIN_SILENT_WINDOWS, True),
            (MIN_SILENT_WINDOWS - 1, False),
        ):
            silent = pihole_silent(
                windows=windows, partial=0, available=0, span_seconds=span
            )
            value = card(
                dns=replace(
                    activity([], windows=windows),
                    until_at="2026-01-01T06:00:00Z",
                    pihole_silent=silent,
                )
            )
            dossier = build_dossier(value)
            self.assertEqual("dns_bypass" in codes(value), expected)
            if expected:
                assert dossier.pihole_silent is not None
                self.assertEqual(dossier.pihole_silent.hours, 6.0)
                self.assertEqual(dossier.pihole_silent.checks, windows)
                self.assertTrue(
                    any(
                        "No DNS via Pi-hole for 6.0 hours" in line
                        for line in dossier.coverage
                    )
                )
            else:
                self.assertIsNone(dossier.pihole_silent)

    def test_upload_heavy_at_and_below_threshold(self) -> None:
        self.assertEqual(UPLOAD_HEAVY_BYTES, 1_000_000_000)
        self.assertIn(
            "upload_heavy",
            codes(card(traffic=volume(UPLOAD_HEAVY_BYTES, UPLOAD_HEAVY_BYTES))),
        )
        self.assertNotIn(
            "upload_heavy",
            codes(card(traffic=volume(10, UPLOAD_HEAVY_BYTES - 1))),
        )
        self.assertNotIn(
            "upload_heavy",
            codes(card(traffic=volume(UPLOAD_HEAVY_BYTES + 1, UPLOAD_HEAVY_BYTES))),
        )
        self.assertNotIn("upload_heavy", codes(card()))

    def test_open_admin_service_reuses_detection_exposures(self) -> None:
        names = {service for _, service, _ in _SERVICE_RULES}
        self.assertLessEqual(ADMIN_SERVICES, names)
        self.assertIn("open_admin_service", codes(card(ports=((22, "open"),))))
        self.assertNotIn("open_admin_service", codes(card(ports=((22, "closed"),))))
        self.assertNotIn("open_admin_service", codes(card(ports=((9100, "open"),))))
        self.assertNotIn("open_admin_service", codes(card(ports=((443, "open"),))))
        self.assertIn("open_admin_service", codes(card(ports=((8080, "open"),))))

    def test_operator_profile_is_typed_bounded_and_coverage_omits_it(self) -> None:
        issue = LinkedIssue(
            str(uuid4()), str(uuid4()), "potentially_affected", "proposed"
        )
        profile = OperatorProfile(str(uuid4()), "Television", "HTTPS", (issue,))
        dossier = build_dossier(card(), profile=profile)
        self.assertEqual(dossier.profile, profile)
        self.assertFalse(any("operator profile" in line for line in dossier.coverage))
        with self.assertRaises(ValueError):
            OperatorProfile("not-a-uuid", "", "", ())
        with self.assertRaises(ValueError):
            OperatorProfile(str(uuid4()), "x" * 513, "", ())
        with self.assertRaises(ValueError):
            LinkedIssue(str(uuid4()), str(uuid4()), "maybe", "proposed")

    def test_encoding_is_deterministic_compact_json(self) -> None:
        value = card(
            dns=activity([("oztam.com.au", 3, 0), ("youtube.com", 3, 0)]),
            traffic=volume(5, 7),
        )
        first, second = (
            encode_dossier(build_dossier(value)),
            encode_dossier(build_dossier(value)),
        )
        self.assertEqual(first, second)
        decoded = json.loads(first)
        self.assertEqual(
            first, json.dumps(decoded, allow_nan=False, separators=(",", ":"))
        )
        self.assertEqual(
            [signal["code"] for signal in decoded["signals"]], ["measurement_dns"]
        )


class SupplementalTest(unittest.TestCase):
    def test_device_reported_facts_and_ranked_bounded_advisories(self) -> None:
        listed = (
            advisory(1, "LOW", day=9),
            advisory(2, "HIGH", day=1),
            advisory(3, None),
            advisory(4, "MEDIUM", exploited=True),
            advisory(5, "HIGH", day=5),
            advisory(6, "CRITICAL"),
            advisory(7, "LOW", day=2),
        )
        dossier = build_dossier(card(), facts=facts(advisories=listed))
        section = dossier.supplemental
        self.assertEqual(section.described_at, DESCRIBED_AT)
        [description] = section.descriptions
        self.assertEqual(
            (description.manufacturer, description.model_name, description.firmware),
            ("Sony", MODEL, "6.6.0"),
        )
        self.assertEqual(description.source, "upnp")
        assert section.advisories is not None
        shown = [item.cve[-1] for item in section.advisories.advisories]
        # Known exploited first, then severity, then newest.
        self.assertEqual(shown, ["4", "6", "5", "2", "1"])
        self.assertEqual(len(shown), MAX_SUPPLEMENTAL_ADVISORIES)
        self.assertEqual(section.advisories.omitted, 2)
        self.assertEqual(section.advisories.total, 11)
        self.assertEqual(section.advisories.model_source, "device")
        for item in section.advisories.advisories:
            self.assertLessEqual(len(item.description), MAX_ADVISORY_TEXT)
        self.assertEqual(
            [note.feature for note in section.vendor_notes], ["Samba Interactive TV"]
        )
        text = "\n".join(dossier.coverage)
        self.assertIn("device's own claims", text)
        self.assertIn("Advisories match the model name", text)
        self.assertIn("worth checking", text)
        encoded = json.loads(encode_dossier(dossier))
        self.assertEqual(
            set(encoded["supplemental"]["descriptions"][0]),
            {
                "source",
                "observed_at",
                "manufacturer",
                "model_name",
                "model_number",
                "firmware",
                "services",
            },
        )

    def test_no_facts_and_a_silent_device_are_coverage_lines_not_findings(
        self,
    ) -> None:
        none = build_dossier(card())
        self.assertIsNone(none.supplemental.described_at)
        self.assertEqual(none.supplemental.descriptions, ())
        self.assertIsNone(none.supplemental.advisories)
        self.assertIn("has not been asked to describe itself", "\n".join(none.coverage))
        self.assertIn(
            "Public advisories have not been looked up", "\n".join(none.coverage)
        )
        # The network identity vendor alone still selects the vendor note.
        self.assertEqual(len(none.supplemental.vendor_notes), 1)
        silent = build_dossier(
            card(), facts=facts(described=False, status="model_unknown")
        )
        text = "\n".join(silent.coverage)
        self.assertIn("did not describe itself", text)
        self.assertIn("not a finding", text)
        self.assertIn("No public model name was available", text)
        self.assertEqual(silent.signals, none.signals)

    def test_budget_trims_advisories_before_destinations(self) -> None:
        listed = tuple(advisory(n, "HIGH", day=n) for n in range(1, 6))
        value = card(dns=activity([("oztam.com.au", 3, 0), ("youtube.com", 3, 0)]))
        full = build_dossier(value, facts=facts(advisories=listed))
        size = len(encode_dossier(full).encode())
        trimmed = build_dossier(
            value, facts=facts(advisories=listed), budget=size - 400
        )
        assert trimmed.supplemental.advisories is not None
        self.assertLess(len(trimmed.supplemental.advisories.advisories), 5)
        self.assertEqual(
            len(trimmed.supplemental.advisories.advisories)
            + trimmed.supplemental.advisories.omitted,
            5,
        )
        self.assertEqual(trimmed.trimmed_destinations, 0)
        self.assertTrue(
            any("advisories omitted to fit" in line for line in trimmed.coverage)
        )
        # The most severe, newest advisory is kept.
        self.assertEqual(
            trimmed.supplemental.advisories.advisories[0].cve, "CVE-2026-0005"
        )

    def test_facts_for_another_device_are_refused(self) -> None:
        with self.assertRaisesRegex(ValueError, "another device"):
            build_dossier(card(), facts=facts(device_id="mac:02:00:00:00:00:02"))


def deep(device: str = "mac:" + MAC, **changes: object) -> DeepScanResult:
    base = DeepScanResult(
        device,
        ADDRESS,
        str(uuid4()),
        "operator",
        None,
        "2026-11-11T09:00:00Z",
        "2026-11-11T09:30:00Z",
        "complete",
        (22, 8008),
        0,
        None,
    )
    return replace(base, **changes)  # type: ignore[arg-type]


class DeepScanDossierTest(unittest.TestCase):
    def test_a_deep_scan_adds_its_coverage_and_the_exposures_it_found(self) -> None:
        dossier = build_dossier(card(ports=((443, "open"),)), deep_scan=deep())
        line = "A full TCP scan on 2026-11-11 found 2 open ports: 22, 8008; 0 did not answer."
        self.assertIn(line, dossier.coverage)
        names = [e.possible_service for e in dossier.detection.exposures]
        self.assertEqual(names, ["HTTPS", "SSH"])

    def test_one_open_port_and_none_are_worded_exactly(self) -> None:
        one = build_dossier(card(), deep_scan=deep(open_ports=(22,), unanswered=3))
        self.assertIn(
            "A full TCP scan on 2026-11-11 found 1 open port: 22; 3 did not answer.",
            one.coverage,
        )
        none = build_dossier(card(), deep_scan=deep(open_ports=()))
        self.assertIn(
            "A full TCP scan on 2026-11-11 found no open ports; 0 did not answer.",
            none.coverage,
        )

    def test_exposures_already_present_are_not_repeated(self) -> None:
        dossier = build_dossier(
            card(ports=((22, "open"),)), deep_scan=deep(open_ports=(22,))
        )
        names = [e.possible_service for e in dossier.detection.exposures]
        self.assertEqual(names, ["SSH"])

    def test_a_deep_scan_for_another_device_is_refused(self) -> None:
        with self.assertRaisesRegex(ValueError, "another device"):
            build_dossier(card(), deep_scan=deep("mac:02:00:00:00:00:02"))

    def test_only_a_complete_deep_scan_is_used(self) -> None:
        with self.assertRaisesRegex(ValueError, "complete"):
            build_dossier(card(), deep_scan=deep(state="interrupted", open_ports=()))


if __name__ == "__main__":
    unittest.main()
