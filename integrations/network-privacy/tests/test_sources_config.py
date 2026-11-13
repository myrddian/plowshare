"""Configured external services decode into typed sources with enabled capabilities."""

from __future__ import annotations

import unittest
from pathlib import Path

from plowshare_privacy.contracts import (
    KIND_CAPABILITIES,
    OPT_IN_CAPABILITIES,
    Capability,
    Configuration,
    PiHolePlan,
    ServicePlan,
    UniFiPlan,
)

PATH = Path("/var/lib/privacy-fixture/collector.json")
PIHOLE = {
    "origin": "https://pihole.example.invalid",
    "passwordEnvironment": "PIHOLE_PASSWORD",
}
UNIFI = {
    "apiBase": "https://192.0.2.1/proxy/network/integration/v1",
    "siteId": "88f7af54-98f8-306a-a1c7-c9349722b1f6",
    "apiKeyEnvironment": "UNIFI_KEY",
}


def row(**collection: object) -> dict[str, object]:
    """A complete configuration row shaped like ``support.configuration``."""
    return {
        "origin": "https://plowshare.example.invalid",
        "project": "network-privacy-watch",
        "collector": "home-network",
        "tokenEnvironment": "PLOWSHARE_TOKEN",
        "webTokenEnvironment": "PRIVACY_WEB_TOKEN",
        "stateDirectory": "/var/lib/privacy-fixture",
        "requestTopic": "privacy.scan.requests",
        "resultTopic": "privacy.scan.completed",
        "group": "privacy-home-network",
        "schedule": "scheduled-example",
        "collection": {
            "mode": "tcp",
            "targets": ["10.1.2.10"],
            "ports": [443],
            "timeoutSeconds": 1,
            "concurrency": 4,
            **collection,
        },
    }


def service(kind: str, settings: object, **fields: object) -> dict[str, object]:
    return {"kind": kind, "id": kind, "settings": settings, **fields}


class SourcesConfigurationTest(unittest.TestCase):
    def test_legacy_keys_become_legacy_services(self) -> None:
        plan = Configuration.decode(row(pihole=PIHOLE), PATH).collection
        settings = PiHolePlan.decode(PIHOLE)
        self.assertEqual(
            plan.sources,
            (
                ServicePlan(
                    "pihole",
                    "pihole",
                    frozenset({"network_table", "dns_queries"}),
                    settings,
                    legacy=True,
                ),
            ),
        )
        self.assertEqual(plan.pihole, settings)
        self.assertIsNone(plan.unifi)

        unifi = {**UNIFI, "trafficSite": "default"}
        plan = Configuration.decode(row(unifi=unifi), PATH).collection
        (source,) = plan.sources
        self.assertEqual(
            (source.kind, source.id, source.legacy), ("unifi", "unifi", True)
        )
        self.assertEqual(
            source.capabilities,
            frozenset({"controller_clients", "managed_inventory", "traffic_counters"}),
        )
        self.assertEqual(plan.unifi, UniFiPlan.decode(unifi))
        self.assertIsNone(plan.pihole)
        # Without a traffic site the legacy UniFi service reads no byte counters.
        (source,) = Configuration.decode(row(unifi=UNIFI), PATH).collection.sources
        self.assertEqual(
            source.capabilities, frozenset({"controller_clients", "managed_inventory"})
        )
        self.assertEqual(Configuration.decode(row(), PATH).collection.sources, ())

    def test_sources_list_decodes(self) -> None:
        capabilities: list[Capability] = ["network_table", "dns_queries", "dhcp_leases"]
        plan = Configuration.decode(
            row(
                sources=[
                    {
                        "kind": "pihole",
                        "id": "home-dns",
                        "capabilities": capabilities,
                        "settings": PIHOLE,
                    },
                    service("unifi", UNIFI),
                ]
            ),
            PATH,
        ).collection
        pihole, unifi = plan.sources
        self.assertEqual(
            pihole,
            ServicePlan(
                "pihole",
                "home-dns",
                frozenset(capabilities),
                PiHolePlan.decode(PIHOLE),
            ),
        )
        self.assertFalse(pihole.legacy)
        self.assertEqual(plan.pihole, PiHolePlan.decode(PIHOLE))
        # Omitted capabilities enable what the kind offers, except the opt-ins.
        self.assertEqual(
            unifi.capabilities, KIND_CAPABILITIES["unifi"] - OPT_IN_CAPABILITIES
        )
        self.assertFalse(unifi.legacy)
        self.assertEqual(plan.unifi, UniFiPlan.decode(UNIFI))
        (pihole,) = Configuration.decode(
            row(sources=[service("pihole", PIHOLE)]), PATH
        ).collection.sources
        self.assertEqual(
            pihole.capabilities, KIND_CAPABILITIES["pihole"] - OPT_IN_CAPABILITIES
        )
        self.assertEqual(
            pihole.capabilities, frozenset({"network_table", "dns_queries"})
        )
        # UniFi's DHCP capabilities are available once a traffic site is named.
        (unifi,) = Configuration.decode(
            row(
                sources=[
                    service(
                        "unifi",
                        {**UNIFI, "trafficSite": "default"},
                        capabilities=["dhcp_reservations", "dhcp_scope"],
                    )
                ]
            ),
            PATH,
        ).collection.sources
        self.assertEqual(
            unifi.capabilities, frozenset({"dhcp_reservations", "dhcp_scope"})
        )

    def test_rejects_invalid_sources(self) -> None:
        cases: dict[str, tuple[dict[str, object], str]] = {
            "both forms": (
                row(pihole=PIHOLE, sources=[service("pihole", PIHOLE)]),
                "not both",
            ),
            "unknown kind": (
                row(sources=[service("adguard", PIHOLE)]),
                "Unsupported service kind",
            ),
            "unknown capability": (
                row(sources=[service("pihole", PIHOLE, capabilities=["telepathy"])]),
                "unique known names",
            ),
            "capability not offered": (
                row(sources=[service("unifi", UNIFI, capabilities=["dhcp_leases"])]),
                "does not offer",
            ),
            "unifi dhcp without traffic site": (
                row(sources=[service("unifi", UNIFI, capabilities=["dhcp_scope"])]),
                "require trafficSite",
            ),
            "duplicate id": (
                row(
                    sources=[
                        service("pihole", PIHOLE),
                        {**service("unifi", UNIFI), "id": "pihole"},
                    ]
                ),
                "ids must be unique",
            ),
            "two of one kind": (
                row(
                    sources=[
                        service("pihole", PIHOLE),
                        {
                            **service("pihole", PIHOLE, capabilities=["network_table"]),
                            "id": "second-dns",
                        },
                    ]
                ),
                "one service per kind until multi-service support",
            ),
            "two dns_queries": (
                row(
                    sources=[
                        service("pihole", PIHOLE, capabilities=["dns_queries"]),
                        {
                            **service("pihole", PIHOLE, capabilities=["dns_queries"]),
                            "id": "second-dns",
                        },
                    ]
                ),
                "dns_queries on at most one service",
            ),
            "observation file with dns_queries": (
                row(
                    observationsFile="/var/lib/privacy-fixture/observations.json",
                    maxObservationAgeSeconds=60,
                    sources=[service("pihole", PIHOLE)],
                ),
                "DNS observation file",
            ),
            "shared credential": (
                row(
                    sources=[
                        service(
                            "pihole", {**PIHOLE, "passwordEnvironment": "SHARED_SECRET"}
                        ),
                        service(
                            "unifi", {**UNIFI, "apiKeyEnvironment": "SHARED_SECRET"}
                        ),
                    ]
                ),
                "separate credential",
            ),
        }
        for name, (case, message) in cases.items():
            with self.subTest(name), self.assertRaisesRegex(ValueError, message):
                Configuration.decode(case, PATH)
        # The observation file only conflicts with a DNS query source.
        plan = Configuration.decode(
            row(
                observationsFile="/var/lib/privacy-fixture/observations.json",
                maxObservationAgeSeconds=60,
                sources=[service("pihole", PIHOLE, capabilities=["network_table"])],
            ),
            PATH,
        ).collection
        self.assertEqual(plan.sources[0].capabilities, frozenset({"network_table"}))

    def test_dns_profiles_require_dns_queries(self) -> None:
        enabled = Configuration.decode(
            row(
                quickScan=True,
                dnsProfiles=True,
                sources=[service("pihole", PIHOLE)],
            ),
            PATH,
        ).collection
        self.assertTrue(enabled.dns_profiles)
        with self.assertRaisesRegex(ValueError, "dns_queries"):
            Configuration.decode(
                row(
                    quickScan=True,
                    dnsProfiles=True,
                    sources=[service("pihole", PIHOLE, capabilities=["network_table"])],
                ),
                PATH,
            )


if __name__ == "__main__":
    unittest.main()
