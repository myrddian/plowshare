"""The connector registry binds each service's enabled capabilities to typed readers."""

from __future__ import annotations

import ast
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Literal
from unittest.mock import AsyncMock, patch
from uuid import UUID

from support import legacy_sources
from test_pihole import ADDRESS, MAC, PiHoleServerCase

from plowshare_privacy import collection, connectors, contracts
from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.connectors import REGISTRY, bind
from plowshare_privacy.contracts import (
    IDENTITY_SOURCES,
    KIND_CAPABILITIES,
    Capability,
    CollectionPlan,
    DeviceIdentity,
    EvidenceKind,
    PiHolePlan,
    ServicePlan,
    UniFiPlan,
    utc_now,
)
from plowshare_privacy.identity_source import DeviceIdentitySource, IdentitySample
from plowshare_privacy.pihole import DeviceData, DeviceDataSource, PiHoleV6
from plowshare_privacy.source_set import Bound, SourceSet
from plowshare_privacy.unifi import UniFiNetwork

SITE = "00000000-0000-4000-8000-000000000001"
FIRST = "192.0.2.10"
SECOND = "192.0.2.11"
OTHER_MAC = "00:11:22:33:44:66"
ENVIRONMENT = {"PIHOLE_PASSWORD": "fixture-password", "UNIFI_KEY": "fixture-key"}
PIHOLE = PiHolePlan.decode(
    {
        "origin": "https://pihole.example.invalid",
        "passwordEnvironment": "PIHOLE_PASSWORD",
    }
)
UNIFI_ROW = {
    "apiBase": "https://unifi.example.invalid/proxy/network/integration/v1",
    "siteId": SITE,
    "apiKeyEnvironment": "UNIFI_KEY",
}
UNIFI = UniFiPlan.decode(UNIFI_ROW)
UNIFI_TRAFFIC = UniFiPlan.decode({**UNIFI_ROW, "trafficSite": "default"})


def plan(*sources: ServicePlan) -> CollectionPlan:
    return CollectionPlan(
        "tcp", (FIRST, SECOND), (443,), 0.1, 1, None, 3600, sources=sources
    )


def pihole(*capabilities: Capability) -> ServicePlan:
    return ServicePlan("pihole", "home-dns", frozenset(capabilities), PIHOLE)


def unifi(settings: UniFiPlan, *capabilities: Capability) -> ServicePlan:
    return ServicePlan("unifi", "controller", frozenset(capabilities), settings)


def bound(sources: CollectionPlan) -> SourceSet:
    with patch.dict(os.environ, ENVIRONMENT):
        return bind(sources)


def accepted_keys(kind: str) -> set[str]:
    """The top-level keys the kind's settings decoder accepts (required | optional)."""
    rows = {
        "pihole": {
            "origin": "https://pihole.example.invalid",
            "passwordEnvironment": "PIHOLE_PASSWORD",
        },
        "unifi": UNIFI_ROW,
    }
    decode = PiHolePlan.decode if kind == "pihole" else UniFiPlan.decode
    with patch.object(
        contracts, "object_fields", wraps=contracts.object_fields
    ) as fields:
        decode(rows[kind])
    _, required, optional = fields.call_args_list[0].args
    return {*required, *optional}


class RegistryTest(unittest.TestCase):
    def test_every_kind_is_registered(self) -> None:
        self.assertEqual(set(REGISTRY), set(KIND_CAPABILITIES))
        for kind, connector in REGISTRY.items():
            with self.subTest(kind):
                self.assertEqual(connector.kind, kind)
                self.assertEqual(connector.capabilities, KIND_CAPABILITIES[kind])
                self.assertIn(connector.source, IDENTITY_SOURCES)
        self.assertEqual(REGISTRY["pihole"].display_name, "Pi-hole")
        self.assertEqual(REGISTRY["pihole"].source, "pihole-v6")
        self.assertEqual(REGISTRY["unifi"].display_name, "UniFi")
        self.assertEqual(REGISTRY["unifi"].source, "unifi")

    def test_settings_fields_name_keys_the_decoder_accepts(self) -> None:
        private = Path(tempfile.gettempdir()).resolve()
        minimal: dict[str, dict[str, object]] = {
            "pihole": {
                "origin": "https://pihole.example.invalid",
                "passwordEnvironment": "PIHOLE_PASSWORD",
            },
            "unifi": dict(UNIFI_ROW),
        }
        values: dict[str, object] = {
            "passwordFile": str(private / "pihole-password"),
            "lookbackSeconds": 3600,
            "queryLimit": 64,
            "maxIdentityAgeSeconds": 86400,
            "timeoutSeconds": 5,
            "caFile": str(private / "unifi-ca.pem"),
            "certificateSha256": "a" * 64,
            "trafficSite": "default",
        }
        required = {
            "pihole": {"origin"},
            "unifi": {"apiBase", "siteId", "apiKeyEnvironment"},
        }
        non_form = {
            "pihole": connectors._PIHOLE_NON_FORM_KEYS,
            "unifi": connectors._UNIFI_NON_FORM_KEYS,
        }
        for kind, connector in REGISTRY.items():
            keys = [field.key for field in connector.settings_fields]
            self.assertEqual(len(keys), len(set(keys)))
            # Form fields plus deliberate non-form keys are exactly what the
            # decoder accepts, so a new settings key cannot be dropped silently.
            self.assertEqual(set(keys) | non_form[kind], accepted_keys(kind))
            self.assertFalse(set(keys) & non_form[kind])
            self.assertEqual(
                {field.key for field in connector.settings_fields if field.required},
                required[kind],
            )
            decode = PiHolePlan.decode if kind == "pihole" else UniFiPlan.decode
            for key in keys:
                with self.subTest(kind=kind, key=key):
                    row = dict(minimal[kind])
                    if key == "passwordFile":
                        row.pop("passwordEnvironment")
                    if key in values:
                        row[key] = values[key]
                    decode(row)

    def test_credential_environments_listed(self) -> None:
        self.assertEqual(
            REGISTRY["pihole"].credential_environments(PIHOLE), ("PIHOLE_PASSWORD",)
        )
        file_based = replace(PIHOLE, password_environment=None)
        self.assertEqual(REGISTRY["pihole"].credential_environments(file_based), ())
        self.assertEqual(
            REGISTRY["unifi"].credential_environments(UNIFI), ("UNIFI_KEY",)
        )

    def test_scope_settings_matches_task3_helper(self) -> None:
        # The fingerprint helper now lives on the registry (pinned values in
        # test_scope_fingerprint prove byte-identical fingerprints).
        self.assertFalse(hasattr(collection, "_scope_settings"))
        self.assertEqual(
            REGISTRY["pihole"].scope_settings(PIHOLE),
            {
                "origin": "https://pihole.example.invalid",
                "password_environment": "PIHOLE_PASSWORD",
                "password_file": None,
                "lookback_seconds": 3600,
                "query_limit": 64,
                "max_identity_age": 86400,
                "timeout": 5.0,
            },
        )
        self.assertEqual(
            REGISTRY["unifi"].scope_settings(UNIFI_TRAFFIC),
            {
                "api_base": UNIFI_ROW["apiBase"],
                "site_id": SITE,
                "api_key_environment": "UNIFI_KEY",
                "ca_file": None,
            },
        )


class BindTest(unittest.TestCase):
    def test_bind_follows_enabled_capabilities(self) -> None:
        table_only = bound(plan(pihole("network_table")))
        self.assertEqual(len(table_only.device_reads), 1)
        self.assertEqual(len(table_only.presence), 1)
        self.assertIsNone(table_only.dns_activity)
        self.assertEqual(table_only.device_reads[0].service, "home-dns")
        self.assertEqual(table_only.device_reads[0].kind, "pihole")
        self.assertTrue(table_only.confirms_identity)

        dns_only = bound(plan(pihole("dns_queries")))
        self.assertEqual(len(dns_only.device_reads), 1)
        self.assertEqual(dns_only.presence, ())
        self.assertIsNotNone(dns_only.dns_activity)
        self.assertFalse(dns_only.confirms_identity)

        no_traffic = bound(
            plan(unifi(UNIFI_TRAFFIC, "controller_clients", "managed_inventory"))
        )
        self.assertIsNone(no_traffic.traffic)
        self.assertEqual(len(no_traffic.identity), 1)
        self.assertEqual(len(no_traffic.managed), 1)
        self.assertIsNotNone(no_traffic.product)
        self.assertTrue(no_traffic.confirms_identity)

        traffic = bound(plan(unifi(UNIFI_TRAFFIC, "traffic_counters")))
        self.assertIsNotNone(traffic.traffic)
        self.assertEqual(traffic.identity, ())
        self.assertEqual(traffic.managed, ())
        self.assertIsNone(traffic.product)
        self.assertFalse(traffic.confirms_identity)

    def test_bind_merges_every_service(self) -> None:
        sources = bound(
            plan(
                pihole("network_table", "dns_queries"),
                ServicePlan.legacy_service("unifi", UNIFI_TRAFFIC),
            )
        )
        self.assertEqual([b.kind for b in sources.device_reads], ["pihole"])
        self.assertEqual([b.kind for b in sources.presence], ["pihole"])
        self.assertEqual([b.kind for b in sources.identity], ["unifi"])
        assert sources.dns_activity is not None and sources.traffic is not None
        self.assertEqual(sources.dns_activity.kind, "pihole")
        self.assertEqual(sources.traffic.kind, "unifi")
        self.assertEqual(bound(plan()), SourceSet())

    def test_unifi_without_traffic_site_binds_no_traffic(self) -> None:
        # Decode rejects this; a directly built plan still binds no reader.
        with self.assertRaisesRegex(ValueError, "legacy site name"):
            unifi(UNIFI, "traffic_counters")
        self.assertIsNone(
            bound(plan(ServicePlan.legacy_service("unifi", UNIFI))).traffic
        )


class PiHoleCapabilityTest(PiHoleServerCase):
    async def test_pihole_without_dns_capability_never_requests_queries(
        self,
    ) -> None:
        source = PiHoleV6(self.plan, capabilities=frozenset({"network_table"}))
        result = await source.read((ADDRESS,), utc_now())
        self.assertEqual([d.mac for d in result.devices], [MAC])
        self.assertEqual(result.dns, ())
        self.assertIsNone(result.window)
        self.assertEqual(result.issues, ())
        self.assertEqual(self.query_clients, [])
        self.assertNotIn(("GET", "/api/queries"), self.calls)
        self.assertEqual(
            self.calls,
            [
                ("POST", "/api/auth"),
                ("GET", "/api/network/devices"),
                ("DELETE", "/api/auth"),
            ],
        )

    async def test_pihole_without_network_table_never_reads_devices(self) -> None:
        source = PiHoleV6(self.plan, capabilities=frozenset({"dns_queries"}))
        result = await source.read((ADDRESS,), utc_now())
        self.assertEqual(result.devices, ())
        self.assertEqual(result.issues, ())
        self.assertIsNotNone(result.window)
        self.assertEqual(
            self.calls,
            [("POST", "/api/auth"), ("GET", "/api/queries"), ("DELETE", "/api/auth")],
        )

    async def test_bound_reader_honours_capabilities(self) -> None:
        sources = bind(
            plan(ServicePlan("pihole", "dns", frozenset({"network_table"}), self.plan))
        )
        await sources.device_reads[0].reader.read((ADDRESS,), utc_now())
        self.assertNotIn(("GET", "/api/queries"), self.calls)

    def test_default_capabilities_keep_existing_behaviour(self) -> None:
        source = PiHoleV6(self.plan)
        self.assertEqual(
            source.capabilities, frozenset({"network_table", "dns_queries"})
        )
        self.assertEqual(source.service, "pihole")


class ControllerIdentityTest(unittest.IsolatedAsyncioTestCase):
    """``controller_identity`` gives the result the collector's merge gives today."""

    def adopted(self, address: str, mac: str) -> dict[str, object]:
        return {
            "offset": 0,
            "limit": 100,
            "count": 1,
            "totalCount": 1,
            "data": [
                {
                    "id": str(UUID(int=1)),
                    "ipAddress": address,
                    "macAddress": mac,
                    "state": "ONLINE",
                    "name": "Managed switch",
                    "model": "USW-Pro",
                }
            ],
        }

    async def compare(
        self, response: dict[str, object], stamp: str
    ) -> tuple[IdentitySample, IdentitySample]:
        client = DeviceIdentity(
            FIRST, "mac:" + MAC, None, "client label", MAC, None, "unifi", stamp, stamp
        )
        with patch.dict(os.environ, ENVIRONMENT):
            network = UniFiNetwork(UNIFI)
        collector = NetworkCollector(
            "fixture", plan(), legacy_sources(controller=network)
        )
        with (
            patch.object(
                network, "identify", AsyncMock(return_value=IdentitySample((client,)))
            ),
            patch.object(network, "_read_page", AsyncMock(return_value=response)),
        ):
            today = await collector._identity_sample(stamp, (FIRST, SECOND))
            merged = await network.controller_identity((FIRST, SECOND), stamp)
        return today, merged

    async def test_adopted_record_replaces_client_when_macs_agree(self) -> None:
        stamp = utc_now()
        today, merged = await self.compare(self.adopted("192.0.2.99", MAC), stamp)
        self.assertEqual(merged, today)
        self.assertEqual(merged.devices[0].hostname, "Managed switch")
        self.assertIsNone(merged.devices[0].evidence)

    async def test_disagreement_is_ambiguous_with_unchanged_issue(self) -> None:
        stamp = utc_now()
        today, merged = await self.compare(self.adopted(FIRST, OTHER_MAC), stamp)
        self.assertEqual(merged, today)
        self.assertEqual(merged.devices, ())
        self.assertEqual(merged.ambiguous_addresses, frozenset({FIRST}))
        self.assertIn(
            "unifi_ambiguous: connected and adopted inventory disagree on a watched address",
            merged.issues,
        )

    async def test_order_matches_the_collector_merge(self) -> None:
        # String sorting would put 192.0.2.12 before 192.0.2.9; first-seen
        # order keeps the clients' order, then the managed-only address.
        stamp = utc_now()
        nine, twelve = "192.0.2.9", "192.0.2.12"
        targets = (FIRST, nine, twelve)
        clients = IdentitySample(
            tuple(
                DeviceIdentity(
                    address, "mac:" + mac, None, None, mac, None, "unifi", stamp, stamp
                )
                for address, mac in ((FIRST, MAC), (nine, OTHER_MAC))
            )
        )
        rows = [
            {
                "id": str(UUID(int=index)),
                "ipAddress": address,
                "macAddress": mac,
                "state": "ONLINE",
                "name": "Managed " + str(index),
                "model": "USW-Pro",
            }
            for index, address, mac in (
                (1, twelve, "00:11:22:33:44:77"),
                (2, "192.0.2.99", MAC),
            )
        ]
        response = {
            "offset": 0,
            "limit": 100,
            "count": 2,
            "totalCount": 2,
            "data": rows,
        }
        with patch.dict(os.environ, ENVIRONMENT):
            network = UniFiNetwork(UNIFI)
        collector = NetworkCollector(
            "fixture", plan(), legacy_sources(controller=network)
        )
        with (
            patch.object(network, "identify", AsyncMock(return_value=clients)),
            patch.object(network, "_read_page", AsyncMock(return_value=response)),
        ):
            today = await collector._identity_sample(stamp, targets)
            merged = await network.controller_identity(targets, stamp)
        self.assertEqual(merged, today)
        self.assertEqual([d.address for d in merged.devices], [FIRST, nine, twelve])
        self.assertEqual(merged.devices[0].hostname, "Managed 2")

    async def test_without_managed_inventory_only_clients_are_read(self) -> None:
        stamp = utc_now()
        client = DeviceIdentity(
            FIRST, "mac:" + MAC, None, None, MAC, None, "unifi", stamp, stamp
        )
        with patch.dict(os.environ, ENVIRONMENT):
            network = UniFiNetwork(
                UNIFI, capabilities=frozenset({"controller_clients"})
            )
        read = AsyncMock()
        with (
            patch.object(
                network, "identify", AsyncMock(return_value=IdentitySample((client,)))
            ),
            patch.object(network, "_read_page", read),
        ):
            sample = await network.controller_identity((FIRST,), stamp)
        self.assertEqual(sample, IdentitySample((client,)))
        read.assert_not_called()


class _Table:
    """A fake network-table read holding fixed records, without DNS."""

    def __init__(self, *devices: DeviceIdentity) -> None:
        self.devices = devices

    async def read(self, targets: tuple[str, ...], observed_at: str) -> DeviceData:
        return DeviceData(
            tuple(d for d in self.devices if d.address in targets), (), None, ()
        )


class _Identities:
    def __init__(self, *devices: DeviceIdentity) -> None:
        self.devices = devices

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        return IdentitySample(tuple(d for d in self.devices if d.address in targets))


def _record(
    address: str,
    mac: str,
    source: Literal["pihole-v6", "unifi"],
    hostname: str,
    evidence: EvidenceKind | None = None,
) -> DeviceIdentity:
    stamp = utc_now()
    return DeviceIdentity(
        address,
        "mac:" + mac,
        None,
        hostname,
        mac,
        None,
        source,
        stamp,
        stamp,
        evidence=evidence,
    )


class CollectorSourcesTest(unittest.IsolatedAsyncioTestCase):
    """The collector reads only bound capabilities and names no vendor itself."""

    async def collect(self, sources: SourceSet) -> contracts.Evidence:
        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=TimeoutError,
        ):
            return await NetworkCollector("fixture", plan(), sources).collect(
                SITE, "fixture:event", None, None
            )

    async def test_conflict_issue_text_is_unchanged(self) -> None:
        table = _Table(_record(FIRST, MAC, "pihole-v6", "table"))
        controller = _Identities(_record(FIRST, OTHER_MAC, "unifi", "client"))
        evidence = await self.collect(
            SourceSet(
                device_reads=(Bound[DeviceDataSource]("home-dns", "pihole", table),),
                identity=(
                    Bound[DeviceIdentitySource]("controller", "unifi", controller),
                ),
            )
        )
        expected = (
            "identity_conflict: "
            + REGISTRY["unifi"].display_name
            + " and "
            + REGISTRY["pihole"].display_name
            + " disagreed on a selected IP's MAC address"
        )
        self.assertEqual(
            expected,
            "identity_conflict: UniFi and Pi-hole disagreed on a selected IP's MAC address",
        )
        self.assertIn(expected, evidence.issues)
        first = evidence.snapshot.devices[0]
        self.assertEqual((first.address, first.source), (FIRST, "configured"))
        self.assertIsNone(first.mac)

    async def test_two_identity_services_merge_by_precedence(self) -> None:
        # The lower-ranked record comes first, so order cannot decide the winner.
        lease = _Identities(
            _record(FIRST, MAC, "pihole-v6", "lease", evidence="dhcp_lease")
        )
        client = _Identities(_record(FIRST, MAC, "unifi", "client"))
        sources = SourceSet(
            identity=(
                Bound[DeviceIdentitySource]("home-dns", "pihole", lease),
                Bound[DeviceIdentitySource]("controller", "unifi", client),
            )
        )
        evidence = await self.collect(sources)
        self.assertEqual(evidence.snapshot.devices[0].hostname, "client")
        self.assertFalse(
            any(issue.startswith("identity_conflict:") for issue in evidence.issues)
        )
        confirmed = await NetworkCollector("fixture", plan(), sources).confirm_identity(
            (FIRST,), utc_now()
        )
        self.assertEqual([d.hostname for d in confirmed.devices], ["client"])

    def test_configured_service_requires_a_binding(self) -> None:
        with self.assertRaisesRegex(ValueError, "no bound reader"):
            NetworkCollector("fixture", plan(pihole("network_table")))
        # Adopted inventory alone binds no service-tagged reader.
        NetworkCollector("fixture", plan(unifi(UNIFI, "managed_inventory")))

    def test_collector_contains_no_vendor_names(self) -> None:
        tree = ast.parse(Path(collection.__file__).read_text(encoding="utf-8"))
        docstrings = {
            id(node.body[0].value)
            for node in ast.walk(tree)
            if isinstance(
                node,
                (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef),
            )
            and node.body
            and isinstance(node.body[0], ast.Expr)
            and isinstance(node.body[0].value, ast.Constant)
        }
        vendors = {"pihole", "unifi"}
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                module = (node.module or "").rsplit(".", 1)[-1]
                self.assertNotIn(module, vendors)
                self.assertFalse(vendors & {alias.name for alias in node.names})
            elif isinstance(node, ast.Import):
                self.assertFalse(
                    vendors & {a.name.rsplit(".", 1)[-1] for a in node.names}
                )
            elif isinstance(node, ast.Attribute):
                self.assertNotIn(node.attr.lower(), vendors)
            elif isinstance(node, ast.Constant) and id(node) not in docstrings:
                self.assertNotIn(node.value, {"pihole-v6", "unifi", "pihole"})
                if isinstance(node.value, str):
                    self.assertNotIn("unifi", node.value.lower())
                    self.assertNotIn("pi-hole", node.value.lower())


if __name__ == "__main__":
    unittest.main()
