"""Destination classification is exact-suffix, conservative and order-stable."""

import unittest

from plowshare_privacy.dns_activity import (
    GROUP_ORDER,
    MIN_SILENT_WINDOWS,
    classify,
    group_destinations,
    pihole_silent,
    retention_rank,
)


class ClassifyTest(unittest.TestCase):
    def test_known_destinations_get_their_published_purpose(self) -> None:
        cases = {
            "deliver.oztam.com.au": ("measurement", "OzTAM"),
            "acr0.samba.tv": ("measurement", "Samba TV"),
            "app-measurement.com": ("measurement", "Google Analytics for Firebase"),
            "v10.events.data.microsoft.com": (
                "telemetry",
                "Microsoft diagnostic data",
            ),
            "nrdp.logs.netflix.com": ("telemetry", "Netflix device logs"),
            "aax.amazon-adsystem.com": ("advertising", "Amazon Ads"),
            "nrdp.prod.ftl.netflix.com": ("streaming", "Netflix"),
            "connectivitycheck.gstatic.com": ("connectivity", "Android"),
            "pool.ntp.org": ("connectivity", "NTP Pool"),
        }
        for domain, expected in cases.items():
            with self.subTest(domain=domain):
                self.assertEqual(classify(domain), expected)

    def test_more_specific_suffix_wins_over_its_parent(self) -> None:
        # logs.netflix.com is telemetry even though netflix.com is streaming.
        self.assertEqual(classify("x.logs.netflix.com")[0], "telemetry")
        self.assertEqual(classify("www.netflix.com")[0], "streaming")

    def test_lookalikes_do_not_inherit_a_classification(self) -> None:
        for domain in (
            "doubleclick.net.example",
            "notoztam.com.au",
            "samba.tv.attacker.example",
            "fakelogs.netflix.com.evil",
        ):
            with self.subTest(domain=domain):
                self.assertEqual(classify(domain), ("unclassified", None))

    def test_local_names_and_reverse_lookups_are_local(self) -> None:
        for domain in (
            "14.1.168.192.in-addr.arpa",
            "f.ip6.arpa",
            "wpad.lan",
            "printer.local",
            "nas.home.arpa",
            "wpad",
        ):
            with self.subTest(domain=domain):
                self.assertEqual(classify(domain)[0], "local")

    def test_unknown_vendor_cloud_stays_unclassified(self) -> None:
        self.assertEqual(classify("updates3.rainmachine.com"), ("unclassified", None))


class GroupingTest(unittest.TestCase):
    def test_groups_follow_privacy_first_order(self) -> None:
        groups = group_destinations(
            [
                ("1.1.168.192.in-addr.arpa", 9, 0),
                ("updates.vendor.example", 5, 0),
                ("deliver.oztam.com.au", 2, 0),
                ("aax.amazon-adsystem.com", 1, 1),
                ("v10.events.data.microsoft.com", 3, 0),
            ]
        )
        self.assertEqual(
            [group.kind for group in groups],
            ["advertising", "measurement", "telemetry", "unclassified", "local"],
        )
        self.assertEqual(groups[0].destinations[0].blocked, 1)

    def test_every_kind_has_a_position(self) -> None:
        self.assertEqual(len(GROUP_ORDER), len(set(GROUP_ORDER)))
        self.assertIn("unclassified", GROUP_ORDER)


class RetentionTest(unittest.TestCase):
    def test_privacy_relevant_destinations_are_kept_before_noise(self) -> None:
        ranked = sorted(
            [
                "9.ip6.arpa",
                "updates.vendor.example",
                "deliver.oztam.com.au",
                "connectivitycheck.gstatic.com",
            ],
            key=retention_rank,
        )
        self.assertEqual(ranked[0], "deliver.oztam.com.au")
        self.assertEqual(ranked[1], "updates.vendor.example")
        self.assertEqual(
            set(ranked[2:]), {"9.ip6.arpa", "connectivitycheck.gstatic.com"}
        )


class SilenceTest(unittest.TestCase):
    def test_long_association_without_queries_is_silent(self) -> None:
        self.assertTrue(
            pihole_silent(windows=MIN_SILENT_WINDOWS, partial=0, available=0)
        )

    def test_short_or_partial_or_active_windows_are_not_silent(self) -> None:
        self.assertFalse(
            pihole_silent(windows=MIN_SILENT_WINDOWS - 1, partial=0, available=0)
        )
        # A failed or truncated read could hide queries; never claim silence.
        self.assertFalse(
            pihole_silent(windows=MIN_SILENT_WINDOWS, partial=1, available=0)
        )
        self.assertFalse(
            pihole_silent(windows=MIN_SILENT_WINDOWS, partial=0, available=1)
        )


if __name__ == "__main__":
    unittest.main()
