"""Scope fingerprints are journal identity: untouched configurations must not move."""

from __future__ import annotations

import ipaddress
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from support import configuration, with_sources

from plowshare_privacy.collection import scope_fingerprint
from plowshare_privacy.connectors import REGISTRY
from plowshare_privacy.contracts import (
    Capability,
    CollectionPlan,
    ConnectorKind,
    PiHolePlan,
    ServicePlan,
    UniFiPlan,
    legacy_capabilities,
)

# Values computed with the pre-sources implementation (commit 211e2c24).
NONE = "8213f89eb09d5f9f9cd3a21b43fa9077cdc10c3b176e6e4cd6d0d6c68153a3e6"
PIHOLE = "6c327989751bbcbc89ca4b9e1b8c934e46b785ed8da4c1382ebb66e8be552641"
UNIFI = "2a1a09bcd729c3dcba7c826a21d31e0e3dcee44f90fc3d4a9ca68c42efb24eb4"
BOTH = "174170cebeaef9ab17a48031357e434b2f62e5d1931aef233851d15f5af501df"
GATEWAY = "56814288199c808eedefa964b56f74f711be546234d2f8b07163702a82419053"

SITE = "11111111-1111-4111-8111-111111111111"
PIHOLE_PLAN = PiHolePlan.decode(
    {
        "origin": "https://pihole.example.invalid",
        "passwordEnvironment": "PIHOLE_PASSWORD",
    }
)
UNIFI_PLAN = UniFiPlan.decode(
    {
        "apiBase": "https://unifi.example.invalid/proxy/network/integration/v1",
        "siteId": SITE,
        "apiKeyEnvironment": "UNIFI_API_KEY",
    }
)
GATEWAY_PLAN = UniFiPlan.decode(
    {
        "apiBase": "https://192.168.8.1/proxy/network/integration/v1",
        "siteId": SITE,
        "apiKeyEnvironment": "UNIFI_API_KEY",
        "gateway": {
            "deviceId": "22222222-2222-4222-8222-222222222222",
            "lanMac": "aa:bb:cc:dd:ee:01",
        },
        "trafficSite": "default",
    }
)


def service(
    kind: ConnectorKind,
    ident: str,
    settings: PiHolePlan | UniFiPlan,
    extra: frozenset[Capability] = frozenset(),
) -> ServicePlan:
    """A non-legacy service with the legacy capabilities plus ``extra``."""
    return ServicePlan(
        kind, ident, legacy_capabilities(kind, settings) | extra, settings
    )


class ScopeFingerprintTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        # The observations path is part of the scope, so pin it.
        self.plan: CollectionPlan = replace(
            configuration(Path(directory.name)).collection,
            observations_file=Path("/example/observations.json"),
        )

    def test_no_sources_fingerprint_is_unchanged(self) -> None:
        self.assertEqual(NONE, scope_fingerprint(self.plan))

    def test_pihole_fingerprint_is_unchanged(self) -> None:
        plan = with_sources(self.plan, pihole=PIHOLE_PLAN)
        self.assertEqual(PIHOLE, scope_fingerprint(plan))

    def test_unifi_fingerprint_is_unchanged(self) -> None:
        plan = with_sources(self.plan, unifi=UNIFI_PLAN)
        self.assertEqual(UNIFI, scope_fingerprint(plan))

    def test_both_fingerprint_is_unchanged(self) -> None:
        plan = with_sources(self.plan, pihole=PIHOLE_PLAN, unifi=UNIFI_PLAN)
        self.assertEqual(BOTH, scope_fingerprint(plan))

    def test_gateway_and_traffic_site_fingerprint_is_unchanged(self) -> None:
        plan = with_sources(self.plan, unifi=GATEWAY_PLAN)
        self.assertEqual(GATEWAY, scope_fingerprint(plan))

    def test_sources_form_matches_legacy_form(self) -> None:
        plan = replace(
            self.plan,
            sources=(
                service("pihole", "home-dns", PIHOLE_PLAN),
                service("unifi", "gw", GATEWAY_PLAN),
            ),
        )
        legacy = with_sources(self.plan, pihole=PIHOLE_PLAN, unifi=GATEWAY_PLAN)
        self.assertEqual(scope_fingerprint(legacy), scope_fingerprint(plan))

    def test_enabling_dhcp_changes_scope(self) -> None:
        legacy = with_sources(self.plan, unifi=GATEWAY_PLAN)
        plan = replace(
            self.plan,
            sources=(service("unifi", "gw", GATEWAY_PLAN, frozenset({"dhcp_scope"})),),
        )
        self.assertNotEqual(scope_fingerprint(legacy), scope_fingerprint(plan))

    def test_traffic_capability_and_ids_do_not_change_scope(self) -> None:
        with_traffic = replace(
            self.plan,
            sources=(service("unifi", "gw", GATEWAY_PLAN, frozenset({"dhcp_scope"})),),
        )
        renamed = replace(
            self.plan,
            sources=(
                service("unifi", "other-id", GATEWAY_PLAN, frozenset({"dhcp_scope"})),
            ),
        )
        self.assertEqual(scope_fingerprint(with_traffic), scope_fingerprint(renamed))
        # Traffic counters never enter scope, in either form.
        no_traffic = replace(
            self.plan,
            sources=(
                ServicePlan(
                    "unifi",
                    "gw",
                    legacy_capabilities("unifi", GATEWAY_PLAN) - {"traffic_counters"}
                    | frozenset({"dhcp_scope"}),
                    GATEWAY_PLAN,
                ),
            ),
        )
        self.assertEqual(scope_fingerprint(with_traffic), scope_fingerprint(no_traffic))

    def test_source_order_does_not_change_scope(self) -> None:
        first = replace(
            self.plan,
            sources=(
                service("pihole", "a", PIHOLE_PLAN, frozenset({"dhcp_leases"})),
                service("unifi", "b", UNIFI_PLAN),
            ),
        )
        second = replace(first, sources=tuple(reversed(first.sources)))
        self.assertEqual(scope_fingerprint(first), scope_fingerprint(second))

    def test_dhcp_network_is_pihole_scope_only_when_set(self) -> None:
        # Unset, the key is left out, so the pinned fingerprints hold.
        self.assertNotIn("dhcp_network", REGISTRY["pihole"].scope_settings(PIHOLE_PLAN))
        lan = replace(PIHOLE_PLAN, dhcp_network=ipaddress.IPv4Network("192.168.1.0/24"))
        self.assertEqual(
            REGISTRY["pihole"].scope_settings(lan)["dhcp_network"], "192.168.1.0/24"
        )
        # Set, it decides what is inferred static, so it changes the scope in
        # either form, and a different network is a different scope.
        legacy = with_sources(self.plan, pihole=lan)
        self.assertNotEqual(PIHOLE, scope_fingerprint(legacy))
        dhcp = frozenset[Capability]({"dhcp_scope", "dhcp_reservations"})
        plain = replace(
            self.plan, sources=(service("pihole", "dns", PIHOLE_PLAN, dhcp),)
        )
        with_lan = replace(self.plan, sources=(service("pihole", "dns", lan, dhcp),))
        other = replace(
            self.plan,
            sources=(
                service(
                    "pihole",
                    "dns",
                    replace(lan, dhcp_network=ipaddress.IPv4Network("10.0.0.0/24")),
                    dhcp,
                ),
            ),
        )
        fingerprints = {scope_fingerprint(p) for p in (plain, with_lan, other)}
        self.assertEqual(len(fingerprints), 3)


if __name__ == "__main__":
    unittest.main()
