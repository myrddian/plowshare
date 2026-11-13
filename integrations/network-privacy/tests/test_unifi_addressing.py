"""UniFi fixed IPs (stat/sta) and DHCP ranges (rest/networkconf) as addressing facts."""

from __future__ import annotations

import asyncio
import ipaddress
import os
import shutil
import ssl
import subprocess
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path
from typing import ClassVar
from unittest.mock import patch
from uuid import UUID, uuid4

from aiohttp import web
from aiohttp.test_utils import TestServer
from test_connectors import PIHOLE, UNIFI_TRAFFIC
from test_pihole import PiHoleServerCase

from plowshare_privacy.addressing import DhcpScope, Reservation
from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.connectors import bind
from plowshare_privacy.contracts import (
    Capability,
    CollectionPlan,
    ServicePlan,
    UniFiPlan,
    utc_now,
)
from plowshare_privacy.unifi import UniFiNetwork

SITE = "00000000-0000-4000-8000-000000000001"
KEY = "PRIVACY_FIXTURE_UNIFI_KEY"
SECRET_KEY = "fixture-unifi-key"
FIXED = "192.0.2.12"
DYNAMIC = "192.0.2.150"
OUTSIDE = "192.0.2.20"
FIXED_MAC = "00:11:22:33:44:55"
DYNAMIC_MAC = "00:11:22:33:44:66"
OUTSIDE_MAC = "00:11:22:33:44:77"
NETWORK = ipaddress.IPv4Network("192.0.2.0/24")
BOTH: frozenset[Capability] = frozenset({"dhcp_reservations", "dhcp_scope"})
STA = "/api/s/default/stat/sta"
NETWORKCONF = "/api/s/default/rest/networkconf"
SCOPE_UNAVAILABLE = "addressing_unavailable: UniFi DHCP settings could not be read"
FIXED_UNAVAILABLE = "addressing_unavailable: UniFi fixed IP settings could not be read"
INVALID_RANGE = "addressing_partial: UniFi returned an invalid DHCP range"
INVALID_FIXED = "addressing_partial: UniFi returned an invalid fixed IP"
NETWORK_LIMIT = "addressing_partial: UniFi DHCP network limit reached"
CLIENT_LIMIT = "addressing_partial: UniFi client limit reached"


def legacy(*rows: object) -> dict[str, object]:
    return {"meta": {"rc": "ok"}, "data": list(rows)}


def lan(**fields: object) -> dict[str, object]:
    return {
        "_id": "fixture-network",
        "name": "LAN",
        "purpose": "corporate",
        "enabled": True,
        "ip_subnet": "192.0.2.1/24",
        "dhcpd_enabled": True,
        "dhcpd_start": "192.0.2.100",
        "dhcpd_stop": "192.0.2.200",
    } | fields


def station(address: str, mac: str, **fields: object) -> dict[str, object]:
    return {"mac": mac, "ip": address, "tx_bytes": 1, "rx_bytes": 1} | fields


@unittest.skipUnless(shutil.which("openssl"), "requires OpenSSL for a TLS fixture")
class UniFiAddressingCase(unittest.IsolatedAsyncioTestCase):
    """A local fake UniFi controller over TLS, pinned by certificate."""

    context: ClassVar[ssl.SSLContext]
    pin: ClassVar[str]

    @classmethod
    def setUpClass(cls) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert = Path(directory) / "leaf.pem"
            key = Path(directory) / "key.pem"
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-days",
                    "1",
                    "-subj",
                    "/CN=fixture.invalid",
                    "-out",
                    str(cert),
                    "-keyout",
                    str(key),
                ],
                check=True,
                capture_output=True,
            )
            cls.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            cls.context.load_cert_chain(cert, key)
            cls.pin = sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()

    async def asyncSetUp(self) -> None:
        self.calls: list[tuple[str, str]] = []
        self.keys: list[str | None] = []
        # Each legacy endpoint answers ``body`` with ``status``; bytes are sent raw.
        self.responses: dict[str, tuple[int, object]] = {
            STA: (
                200,
                legacy(
                    station(FIXED, FIXED_MAC, use_fixedip=True, fixed_ip=FIXED),
                    station(DYNAMIC, DYNAMIC_MAC),
                    station(OUTSIDE, OUTSIDE_MAC, use_fixedip=False),
                ),
            ),
            NETWORKCONF: (200, legacy(lan(), {"_id": "wan", "purpose": "wan"})),
        }
        app = web.Application()
        app.router.add_route("*", STA, self.legacy)
        app.router.add_route("*", NETWORKCONF, self.legacy)
        app.router.add_get(f"/integration/v1/sites/{SITE}/clients", self.clients)
        self.server = TestServer(app)
        await self.server.start_server(ssl=self.context)
        self.addAsyncCleanup(self.server.close)
        self.settings = UniFiPlan.decode(
            {
                "apiBase": str(self.server.make_url("/integration/v1")),
                "siteId": SITE,
                "apiKeyEnvironment": KEY,
                "certificateSha256": self.pin,
                "trafficSite": "default",
            }
        )
        self.environment = patch.dict(os.environ, {KEY: SECRET_KEY})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    async def legacy(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        self.keys.append(request.headers.get("X-API-Key"))
        status, body = self.responses[request.path]
        if isinstance(body, bytes):
            return web.Response(status=status, body=body)
        return web.json_response(body, status=status)

    async def clients(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        rows = [
            {
                "id": str(UUID(int=index)),
                "type": "WIRED",
                "name": "fixture-device",
                "ipAddress": address,
                "macAddress": mac,
            }
            for index, (address, mac) in enumerate(
                ((FIXED, FIXED_MAC), (DYNAMIC, DYNAMIC_MAC), (OUTSIDE, OUTSIDE_MAC)),
                1,
            )
        ]
        return web.json_response(
            {
                "offset": 0,
                "limit": 100,
                "totalCount": len(rows),
                "count": len(rows),
                "data": rows,
            }
        )

    def network(self, capabilities: frozenset[Capability] = BOTH) -> UniFiNetwork:
        return UniFiNetwork(self.settings, capabilities=capabilities, service="gw")

    def scope(self, complete: bool = True) -> DhcpScope:
        return DhcpScope("gw", NETWORK, "192.0.2.100", "192.0.2.200", complete, "none")


class UniFiAddressingTest(UniFiAddressingCase):
    async def test_fixed_ip_client_becomes_reservation(self) -> None:
        sample = await self.network().addressing((FIXED, DYNAMIC, OUTSIDE), utc_now())
        self.assertEqual(
            sample.reservations,
            (Reservation(FIXED, FIXED_MAC, "controller_fixed_ip", "gw"),),
        )
        self.assertEqual(sample.leases, ())
        self.assertEqual(sample.issues, ())
        self.assertEqual(self.calls, [("GET", STA), ("GET", NETWORKCONF)])
        self.assertEqual(self.keys, [SECRET_KEY, SECRET_KEY])

    async def test_fixed_ip_outside_targets_and_mac_case_are_normalised(
        self,
    ) -> None:
        self.responses[STA] = (
            200,
            legacy(
                station(FIXED, "00:11:22:AA:BB:CC", use_fixedip=True, fixed_ip=FIXED),
                station(
                    "192.0.2.99", OUTSIDE_MAC, use_fixedip=True, fixed_ip="192.0.2.99"
                ),
            ),
        )
        sample = await self.network(frozenset({"dhcp_reservations"})).addressing(
            (FIXED,), utc_now()
        )
        self.assertEqual(
            sample.reservations,
            (Reservation(FIXED, "00:11:22:aa:bb:cc", "controller_fixed_ip", "gw"),),
        )
        self.assertEqual(sample.scopes, ())
        self.assertEqual(self.calls, [("GET", STA)])

    async def test_invalid_fixed_ip_is_partial_and_blocks_completeness(self) -> None:
        for invalid in (
            {"use_fixedip": True},
            {"use_fixedip": True, "fixed_ip": "not-an-address"},
            {"use_fixedip": True, "fixed_ip": "2001:db8::1"},
            {"use_fixedip": True, "fixed_ip": FIXED, "mac": "invalid"},
            {"use_fixedip": "yes", "fixed_ip": FIXED},
        ):
            with self.subTest(invalid=invalid):
                self.responses[STA] = (
                    200,
                    legacy(
                        station(DYNAMIC, DYNAMIC_MAC) | invalid,
                        station(
                            OUTSIDE, OUTSIDE_MAC, use_fixedip=True, fixed_ip=OUTSIDE
                        ),
                    ),
                )
                sample = await self.network().addressing(
                    (FIXED, DYNAMIC, OUTSIDE), utc_now()
                )
                self.assertEqual(sample.issues, (INVALID_FIXED,))
                self.assertEqual(
                    sample.reservations,
                    (Reservation(OUTSIDE, OUTSIDE_MAC, "controller_fixed_ip", "gw"),),
                )
                self.assertEqual(sample.scopes, (self.scope(complete=False),))

    async def test_dhcp_network_becomes_scope_without_lease_table(self) -> None:
        self.responses[NETWORKCONF] = (
            200,
            legacy(
                lan(),
                # WAN, VLAN-only and IPv6 entries are ignored silently.
                {"_id": "wan", "purpose": "wan", "wan_type": "dhcp"},
                {"_id": "vlan", "purpose": "vlan-only", "dhcpd_enabled": False},
                lan(ip_subnet="2001:db8::1/64", dhcpd_start="", dhcpd_stop=""),
            ),
        )
        sample = await self.network(frozenset({"dhcp_scope"})).addressing(
            (FIXED,), utc_now()
        )
        self.assertEqual(sample.scopes, (self.scope(),))
        self.assertEqual(sample.reservations, ())
        self.assertEqual(sample.issues, ())
        self.assertEqual(self.calls, [("GET", NETWORKCONF)])

    async def test_dhcp_disabled_network_is_no_scope_and_no_issue(self) -> None:
        for disabled in (
            lan(dhcpd_enabled=False),
            lan(dhcpd_enabled=False, dhcpd_start="", dhcpd_stop=""),
            lan(enabled=False),
        ):
            with self.subTest(disabled=disabled):
                self.responses[NETWORKCONF] = (200, legacy(disabled))
                sample = await self.network().addressing((FIXED,), utc_now())
                self.assertEqual(sample.scopes, ())
                self.assertEqual(sample.issues, ())

    async def test_invalid_range_skips_the_network_and_marks_others_incomplete(
        self,
    ) -> None:
        other = lan(
            ip_subnet="198.51.100.1/24",
            dhcpd_start="198.51.100.10",
            dhcpd_stop="198.51.100.20",
        )
        for invalid in (
            lan(dhcpd_start="198.51.100.5"),
            lan(dhcpd_start="192.0.2.201"),
            lan(dhcpd_stop="not-an-address"),
            lan(ip_subnet="nonsense"),
            lan(dhcpd_enabled="true"),
            lan(enabled="yes"),
            "not-a-network",
        ):
            with self.subTest(invalid=invalid):
                self.responses[NETWORKCONF] = (200, legacy(other, invalid))
                sample = await self.network().addressing((FIXED,), utc_now())
                self.assertEqual(sample.issues, (INVALID_RANGE,))
                self.assertEqual(
                    sample.scopes,
                    (
                        DhcpScope(
                            "gw",
                            ipaddress.IPv4Network("198.51.100.0/24"),
                            "198.51.100.10",
                            "198.51.100.20",
                            False,
                            "none",
                        ),
                    ),
                )

    async def test_unreadable_endpoints_are_addressing_unavailable(self) -> None:
        failures: tuple[tuple[int, object], ...] = (
            (401, {"meta": {"rc": "error", "msg": "api.err.LoginRequired"}}),
            (404, b"Not Found at " + STA.encode()),
            (200, b"{not json"),
            (200, {"meta": {"rc": "error"}, "data": []}),
            (200, {"meta": {"rc": "ok"}, "data": {}}),
        )
        for status, body in failures:
            with self.subTest(endpoint="stat/sta", status=status):
                await self.asyncSetUp()
                self.responses[STA] = (status, body)
                sample = await self.network().addressing((FIXED,), utc_now())
                self.assertEqual(sample.issues, (FIXED_UNAVAILABLE,))
                self.assertEqual(sample.reservations, ())
                # A missing fixed IP could otherwise make an address look static.
                self.assertEqual(sample.scopes, (self.scope(complete=False),))
            with self.subTest(endpoint="networkconf", status=status):
                await self.asyncSetUp()
                self.responses[NETWORKCONF] = (status, body)
                sample = await self.network().addressing((FIXED,), utc_now())
                self.assertEqual(sample.issues, (SCOPE_UNAVAILABLE,))
                self.assertEqual(sample.scopes, ())
                self.assertEqual(
                    sample.reservations,
                    (Reservation(FIXED, FIXED_MAC, "controller_fixed_ip", "gw"),),
                )
            for issue in sample.issues:
                self.assertNotIn("/api/", issue)
                self.assertNotIn("127.0.0.1", issue)
                self.assertNotIn(SECRET_KEY, issue)

    async def test_certificate_mismatch_is_unavailable_without_sending_the_key(
        self,
    ) -> None:
        settings = UniFiPlan.decode(
            {
                "apiBase": str(self.server.make_url("/integration/v1")),
                "siteId": SITE,
                "apiKeyEnvironment": KEY,
                "certificateSha256": "00" * 32,
                "trafficSite": "default",
            }
        )
        network = UniFiNetwork(settings, capabilities=BOTH, service="gw")
        sample = await network.addressing((FIXED,), utc_now())
        self.assertEqual(sample.issues, (FIXED_UNAVAILABLE, SCOPE_UNAVAILABLE))
        self.assertEqual((sample.reservations, sample.scopes), ((), ()))
        self.assertEqual(self.keys, [])

    async def test_limits_mark_partial(self) -> None:
        clients = [
            station(f"10.0.{index // 256}.{index % 256}", "00:11:22:33:55:66")
            for index in range(1024)
        ]
        self.responses[STA] = (
            200,
            legacy(
                *clients,
                # Beyond the limit: never decoded.
                station(FIXED, FIXED_MAC, use_fixedip=True, fixed_ip=FIXED),
            ),
        )
        sample = await self.network().addressing((FIXED,), utc_now())
        self.assertEqual(sample.issues, (CLIENT_LIMIT,))
        self.assertEqual(sample.reservations, ())
        self.assertEqual(sample.scopes, (self.scope(complete=False),))

        await self.asyncSetUp()
        self.responses[NETWORKCONF] = (
            200,
            legacy(*[lan(dhcpd_enabled=False)] * 64, lan()),
        )
        sample = await self.network().addressing((FIXED,), utc_now())
        self.assertEqual(sample.issues, (NETWORK_LIMIT,))
        self.assertEqual(sample.scopes, ())

        await self.asyncSetUp()
        self.responses[NETWORKCONF] = (200, legacy(*[lan()] * 65))
        sample = await self.network(frozenset({"dhcp_scope"})).addressing(
            (FIXED,), utc_now()
        )
        self.assertEqual(sample.issues, (NETWORK_LIMIT,))
        self.assertEqual(sample.scopes, (self.scope(complete=False),) * 64)

    async def test_skipped_when_capabilities_off(self) -> None:
        cases: tuple[tuple[frozenset[Capability], list[tuple[str, str]]], ...] = (
            (frozenset({"controller_clients", "traffic_counters"}), []),
            (frozenset({"dhcp_scope"}), [("GET", NETWORKCONF)]),
            (frozenset({"dhcp_reservations"}), [("GET", STA)]),
        )
        for capabilities, calls in cases:
            with self.subTest(capabilities=sorted(capabilities)):
                self.calls.clear()
                sample = await self.network(capabilities).addressing(
                    (FIXED,), utc_now()
                )
                self.assertEqual(self.calls, calls)
                if not calls:
                    self.assertEqual(
                        (sample.reservations, sample.scopes, sample.issues),
                        ((), (), ()),
                    )

    async def test_traffic_read_is_unchanged_by_dhcp_capabilities(self) -> None:
        network = self.network(frozenset({"traffic_counters", *BOTH}))
        counters, issues = await network.traffic((DYNAMIC,))
        self.assertEqual(issues, ())
        self.assertEqual([c.address for c in counters], [DYNAMIC])
        self.assertEqual(self.calls, [("GET", STA)])


class UniFiDhcpCollectionTest(UniFiAddressingCase, PiHoleServerCase):
    async def asyncSetUp(self) -> None:
        await PiHoleServerCase.asyncSetUp(self)
        await UniFiAddressingCase.asyncSetUp(self)

    async def test_unifi_dhcp_with_pihole_dns_gives_inside_range_dynamic(
        self,
    ) -> None:
        # Pi-hole answers DNS only: its DHCP server is off, so it has no scope.
        self.dhcp_settings = {"config": {"dhcp": {"active": False}}, "took": 0.01}

        async def accept(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            writer.close()
            await writer.wait_closed()

        listener = await asyncio.start_server(accept, "127.0.0.1", 0)
        self.addAsyncCleanup(listener.wait_closed)
        self.addCleanup(listener.close)
        plan = CollectionPlan(
            "tcp",
            (FIXED, DYNAMIC, OUTSIDE),
            (listener.sockets[0].getsockname()[1],),
            1,
            1,
            None,
            0,
            sources=(
                ServicePlan(
                    "pihole",
                    "dns",
                    frozenset({"network_table", "dhcp_scope"}),
                    self.plan,
                ),
                ServicePlan(
                    "unifi",
                    "gw",
                    frozenset({"controller_clients", *BOTH}),
                    self.settings,
                ),
            ),
        )
        original = asyncio.open_connection

        async def connection(
            address: str, port: int
        ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
            return await original("127.0.0.1", port)

        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=connection,
        ):
            evidence = await NetworkCollector("fixture", plan, bind(plan)).collect(
                str(uuid4()), "fixture:event", None, None
            )
        self.assertEqual(
            [
                (item.address, item.mac, item.mode, item.basis, item.services)
                for item in evidence.snapshot.addressing
            ],
            [
                (FIXED, FIXED_MAC, "dhcp_reserved", "controller_fixed_ip", ("gw",)),
                (DYNAMIC, DYNAMIC_MAC, "dhcp_dynamic", "inside_dhcp_range", ("gw",)),
                (OUTSIDE, OUTSIDE_MAC, "static", "outside_dhcp_range", ("gw",)),
            ],
        )
        self.assertFalse(
            [issue for issue in evidence.issues if issue.startswith("addressing_")]
        )
        self.assertIn(("GET", "/api/config/dhcp"), self.calls)
        self.assertEqual(self.calls.count(("GET", STA)), 1)
        self.assertEqual(self.calls.count(("GET", NETWORKCONF)), 1)


class UniFiDhcpOnlyServiceTest(unittest.TestCase):
    def test_dhcp_only_unifi_service_binds_addressing_and_constructs(self) -> None:
        with patch.dict(os.environ, {"UNIFI_KEY": SECRET_KEY}):
            dhcp_only: tuple[frozenset[Capability], ...] = (
                frozenset({"dhcp_scope"}),
                frozenset({"dhcp_reservations"}),
                BOTH,
            )
            for capabilities in dhcp_only:
                with self.subTest(capabilities=sorted(capabilities)):
                    plan = CollectionPlan(
                        "tcp",
                        (FIXED,),
                        (443,),
                        0.1,
                        1,
                        None,
                        3600,
                        sources=(
                            ServicePlan("unifi", "gw", capabilities, UNIFI_TRAFFIC),
                        ),
                    )
                    sources = bind(plan)
                    self.assertEqual(
                        [(b.service, b.kind) for b in sources.addressing],
                        [("gw", "unifi")],
                    )
                    self.assertEqual(sources.identity, ())
                    self.assertIsNone(sources.traffic)
                    NetworkCollector("fixture", plan, sources)

    def test_existing_unifi_services_bind_no_addressing(self) -> None:
        with patch.dict(os.environ, {"UNIFI_KEY": SECRET_KEY}):
            plan = CollectionPlan(
                "tcp",
                (FIXED,),
                (443,),
                0.1,
                1,
                None,
                3600,
                sources=(
                    ServicePlan.legacy_service("unifi", UNIFI_TRAFFIC),
                    ServicePlan.legacy_service("pihole", PIHOLE),
                ),
            )
            with patch.dict(os.environ, {"PIHOLE_PASSWORD": "fixture"}):
                self.assertEqual(bind(plan).addressing, ())
