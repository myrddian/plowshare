"""Pi-hole v6 DHCP settings: the connection check, scope and reservations."""

from __future__ import annotations

import asyncio
import ipaddress
import os
import unittest
from dataclasses import replace
from typing import Any, cast
from unittest.mock import patch
from uuid import uuid4

from aiohttp import web
from test_connectors import PIHOLE
from test_pihole import ADDRESS, MAC, SECRET, PiHoleServerCase

from plowshare_privacy.addressing import (
    AddressingSample,
    DhcpScope,
    Lease,
    Reservation,
    assign_addresses,
)
from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.connectors import bind
from plowshare_privacy.contracts import (
    AddressAssignment,
    Capability,
    CollectionPlan,
    DeviceIdentity,
    Evidence,
    ServicePlan,
    utc_now,
)
from plowshare_privacy.identity_source import DeviceIdentitySource, IdentitySample
from plowshare_privacy.pihole import PiHoleDhcp, PiHoleV6, instant
from plowshare_privacy.source_set import Bound, SourceSet

OTHER = "192.0.2.150"
OUTSIDE = "198.51.100.7"
UNAVAILABLE = "addressing_unavailable: Pi-hole DHCP settings could not be read"
INVALID_RESERVATION = "addressing_partial: Pi-hole returned an invalid DHCP reservation"
INVALID_RANGE = "addressing_partial: Pi-hole returned an invalid DHCP range"
RESERVATION_LIMIT = "addressing_partial: Pi-hole DHCP reservation limit reached"
DHCP_ONLY: frozenset[Capability] = frozenset({"dhcp_scope", "dhcp_reservations"})
OTHER_MAC = "aa:bb:cc:dd:ee:01"
LEASES: frozenset[Capability] = frozenset({"dhcp_leases"})
TABLE_AND_LEASES: frozenset[Capability] = frozenset({"network_table", "dhcp_leases"})
INVALID_LEASE = "addressing_partial: Pi-hole returned an invalid DHCP lease"
LEASE_LIMIT = "addressing_partial: Pi-hole DHCP lease limit reached"
LEASES_UNREADABLE = "addressing_partial: Pi-hole DHCP leases could not be read"
IDENTITY_LEASE_LIMIT = "identity_partial: Pi-hole DHCP lease limit reached"
IDENTITY_INVALID_LEASE = "identity_partial: Pi-hole returned an invalid DHCP lease"
IDENTITY_LEASES_UNREADABLE = (
    "identity_unavailable: Pi-hole DHCP leases could not be read or validated"
)
DISAGREE = (
    "identity_ambiguous: Pi-hole DHCP lease and network table name different "
    "devices for a selected address"
)
TWO_LEASES = (
    "identity_ambiguous: Pi-hole DHCP lists multiple leases for a selected address"
)
LEASE_CALL = ("GET", "/api/dhcp/leases")
NAMES_UNREADABLE_TEXT = "identity_partial: Pi-hole local DNS records could not be read"


class _Identities:
    """A second identity service holding fixed records."""

    def __init__(self, *devices: DeviceIdentity) -> None:
        self.devices = devices

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        return IdentitySample(tuple(d for d in self.devices if d.address in targets))


def _identity(mac: str = MAC) -> DeviceIdentity:
    return DeviceIdentity(
        ADDRESS, "mac:" + mac, None, None, mac, None, "pihole-v6", utc_now(), None
    )


class PiHoleDhcpTest(PiHoleServerCase):
    def settings(self) -> dict[str, object]:
        config = cast(dict[str, object], self.dhcp_settings)["config"]
        return cast(dict[str, object], cast(dict[str, object], config)["dhcp"])

    def source(
        self, capabilities: frozenset[Capability] = DHCP_ONLY, service: str = "dns"
    ) -> PiHoleV6:
        return PiHoleV6(self.plan, capabilities=capabilities, service=service)

    def dhcp_calls(self) -> int:
        return self.calls.count(("GET", "/api/config/dhcp"))

    async def test_dhcp_scope_and_reservations_decoded(self) -> None:
        self.settings()["hosts"] = [
            "00:11:22:33:44:55,192.0.2.12",
            "AA:BB:CC:DD:EE:01,192.0.2.150,printer",
            "aa:bb:cc:dd:ee:02,198.51.100.7,laptop,24h",
        ]
        source = self.source()
        offered = await source.offered()
        self.assertIs(offered.active, True)
        self.assertEqual(offered.issues, ())
        self.assertEqual(
            offered.scope,
            DhcpScope(
                "dns",
                ipaddress.IPv4Network("192.0.2.0/24"),
                "192.0.2.100",
                "192.0.2.200",
                True,
                "incomplete",
            ),
        )
        data = await source.read((ADDRESS, OTHER), utc_now())
        self.assertEqual(
            data.addressing,
            AddressingSample(
                (),
                (
                    Reservation(ADDRESS, MAC, "dhcp_reservation", "dns"),
                    Reservation(OTHER, "aa:bb:cc:dd:ee:01", "dhcp_reservation", "dns"),
                ),
                (offered.scope,) if offered.scope else (),
                (),
            ),
        )
        # Only the addressing read: no device table, no queries, one session each.
        session = [
            ("POST", "/api/auth"),
            ("GET", "/api/config/dhcp"),
            ("DELETE", "/api/auth"),
        ]
        self.assertEqual(self.calls, session + session)

    async def test_reservations_outside_targets_are_not_kept(self) -> None:
        self.settings()["hosts"] = ["aa:bb:cc:dd:ee:02," + OUTSIDE]
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(data.addressing.reservations, ())
        self.assertEqual(data.addressing.issues, ())

    async def test_dhcp_off_is_not_an_issue(self) -> None:
        self.settings()["active"] = False
        self.settings()["hosts"] = ["00:11:22:33:44:55,192.0.2.12"]
        offered = await self.source().offered()
        self.assertEqual(offered, PiHoleDhcp(False, None, (), ()))
        data = await self.source().read((ADDRESS,), utc_now())
        self.assertEqual(data.addressing, AddressingSample())
        self.assertEqual(data.issues, ())

    async def test_unreadable_dhcp_settings_are_a_gap(self) -> None:
        cases: dict[str, tuple[int, object]] = {
            "401": (401, None),
            "403": (403, None),
            "404": (404, None),
            "no dhcp settings": (200, {"config": {}}),
            "non-boolean active": (
                200,
                {"config": {"dhcp": {"active": "yes"}}, "took": 0.01},
            ),
        }
        original = self.dhcp_settings
        for name, (status, body) in cases.items():
            with self.subTest(name):
                self.dhcp_status = status
                self.dhcp_settings = original if body is None else body
                offered = await self.source().offered()
                self.assertIsNone(offered.active)
                self.assertIsNone(offered.scope)
                self.assertEqual(offered.reservations, ())
                self.assertEqual(offered.issues, (UNAVAILABLE,))
                data = await self.source().read((ADDRESS,), utc_now())
                self.assertEqual(
                    data.addressing, AddressingSample(issues=(UNAVAILABLE,))
                )
                for issue in (*offered.issues, *data.issues):
                    self.assertNotIn(SECRET, issue)
                    self.assertNotIn(self.origin, issue)
                    self.assertNotIn("/api", issue)

    async def test_failed_session_is_an_addressing_gap(self) -> None:
        self.mode = "authentication_failure"
        data = await self.source().read((ADDRESS,), utc_now())
        self.assertEqual(data.addressing, AddressingSample(issues=(UNAVAILABLE,)))
        self.assertEqual(data.issues, ())
        self.assertEqual((await self.source().offered()).issues, (UNAVAILABLE,))

    async def test_malformed_reservation_is_partial(self) -> None:
        malformed = [
            "00:11:22:33:44:55",
            "00:11:22:33:44:55,laptop",
            "00:11:22:33:44:55,192.0.2.300",
            "00:11:22:33:44:5,192.0.2.12",
            "00:00:00:00:00:00,192.0.2.12",
            "id:client,192.0.2.12",
            "00:11:22:33:44:55,set:red,192.0.2.12",
            "00:11:22:33:44:55,192.0.2.12,laptop,24h,extra",
            "00:11:22:33:44:55,192.0.2.12,ignore",
            "00:11:22:33:44:55,192.0.2.12,tag:red",
            "00:11:22:33:44:55,[2001:db8::1]",
            "00:11:22:33:44:55,192.0.2.12,",
            17,
            "",
        ]
        for entry in malformed:
            with self.subTest(entry=entry):
                self.settings()["hosts"] = [entry, "aa:bb:cc:dd:ee:01," + OTHER]
                data = await self.source().read((ADDRESS, OTHER), utc_now())
                assert data.addressing is not None
                self.assertEqual(data.addressing.issues, (INVALID_RESERVATION,))
                self.assertEqual(
                    data.addressing.reservations,
                    (
                        Reservation(
                            OTHER, "aa:bb:cc:dd:ee:01", "dhcp_reservation", "dns"
                        ),
                    ),
                )
                # A rejected reservation could hold any address: no static inference.
                self.assertFalse(data.addressing.scopes[0].complete)
        self.settings()["hosts"] = None
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(data.addressing.issues, (INVALID_RESERVATION,))
        self.assertFalse(data.addressing.scopes[0].complete)

    async def test_reservation_limit_is_partial(self) -> None:
        hosts = [
            f"aa:bb:cc:dd:{n // 256:02x}:{n % 256:02x},10.0.{n // 256}.{n % 256}"
            for n in range(1, 513)
        ]
        self.settings()["hosts"] = [*hosts, "00:11:22:33:44:55," + ADDRESS]
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(data.addressing.reservations, ())
        self.assertEqual(data.addressing.issues, (RESERVATION_LIMIT,))
        self.settings()["hosts"] = [*hosts[:511], "00:11:22:33:44:55," + ADDRESS]
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(len(data.addressing.reservations), 1)
        self.assertEqual(data.addressing.issues, ())

    async def test_partial_reservation_read_blocks_static_inference(self) -> None:
        # Without reservations the scope is complete and ADDRESS is static.
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertTrue(data.addressing.scopes[0].complete)
        limit = [
            f"aa:bb:cc:dd:{n // 256:02x}:{n % 256:02x},10.0.{n // 256}.{n % 256}"
            for n in range(1, 514)
        ]
        cases = {"malformed entry": ["id:client,192.0.2.12"], "limit": limit}
        for name, hosts in cases.items():
            with self.subTest(name):
                self.settings()["hosts"] = hosts
                data = await self.source().read((ADDRESS,), utc_now())
                assert data.addressing is not None
                self.assertFalse(data.addressing.scopes[0].complete)
                # The scope reaches the collector, which then cannot infer static.
                assignments, _ = assign_addresses(
                    (data.addressing,), {ADDRESS: _identity()}, (ADDRESS,), utc_now()
                )
                self.assertEqual(assignments[0].mode, "unknown")
        # Reservations off: the hosts list is not read, so an unseen reservation
        # could hold any address and the scope is incomplete, without an issue.
        data = await self.source(frozenset({"dhcp_scope"})).read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertFalse(data.addressing.scopes[0].complete)
        self.assertEqual(data.addressing.issues, ())

    async def test_invalid_range_leaves_the_scope_incomplete(self) -> None:
        cases = {
            "start after end": {"start": "192.0.2.200", "end": "192.0.2.100"},
            "start outside network": {"start": "192.0.3.10"},
            "end outside network": {"end": "198.51.100.200"},
        }
        original = dict(self.settings())
        for name, change in cases.items():
            with self.subTest(name):
                self.settings().update(original, **change)
                offered = await self.source().offered()
                self.assertIs(offered.active, True)
                assert offered.scope is not None
                self.assertFalse(offered.scope.complete)
                self.assertEqual(offered.scope.lease_table, "incomplete")
                self.assertEqual(offered.issues, (INVALID_RANGE,))

    async def test_undecodable_scope_settings_have_no_scope(self) -> None:
        cases: dict[str, dict[str, object]] = {
            "empty start": {"start": ""},
            "invalid end": {"end": "192.0.2"},
            "empty netmask": {"netmask": ""},
            "zero netmask": {"netmask": "0.0.0.0"},
            "non-contiguous netmask": {"netmask": "255.0.255.0"},
            "hostmask": {"netmask": "0.0.0.255"},
            "missing router": {"router": None},
            "zero router": {"router": "0.0.0.0"},
        }
        original = dict(self.settings())
        for name, change in cases.items():
            with self.subTest(name):
                self.settings().update(original, **change)
                offered = await self.source().offered()
                self.assertIs(offered.active, True)
                self.assertIsNone(offered.scope)
                self.assertEqual(offered.issues, (INVALID_RANGE,))

    async def test_dhcp_network_must_be_a_private_lan(self) -> None:
        # Router/netmask pairs whose network is not a private unicast IPv4 LAN
        # from /8 to /30, and a range inside each so only the network decides.
        cases = {
            "public": ("8.8.8.1", "255.255.255.0", "8.8.8.10"),
            "loopback": ("127.0.0.1", "255.0.0.0", "127.0.0.10"),
            "link-local": ("169.254.1.1", "255.255.0.0", "169.254.1.10"),
            "multicast": ("224.0.0.1", "255.255.255.0", "224.0.0.10"),
            "reserved": ("240.0.0.1", "255.255.255.0", "240.0.0.10"),
            "this network": ("0.0.0.1", "255.255.255.0", "0.0.0.10"),
            "/7": ("10.0.0.1", "254.0.0.0", "10.0.0.10"),
            "/31": ("10.0.0.0", "255.255.255.254", "10.0.0.1"),
            "/32": ("10.0.0.1", "255.255.255.255", "10.0.0.1"),
        }
        original = dict(self.settings())
        for name, (router, netmask, address) in cases.items():
            with self.subTest(name):
                self.settings().update(
                    original,
                    router=router,
                    netmask=netmask,
                    start=address,
                    end=address,
                )
                offered = await self.source().offered()
                self.assertIs(offered.active, True)
                self.assertIsNone(offered.scope)
                self.assertEqual(offered.issues, (INVALID_RANGE,))
                data = await self.source().read((ADDRESS,), utc_now())
                assert data.addressing is not None
                self.assertEqual(data.addressing.scopes, ())
                self.assertEqual(data.addressing.issues, (INVALID_RANGE,))
        accepted = {
            "10/8": ("10.0.0.1", "255.0.0.0", "10.0.0.10"),
            "172.16/12": ("172.16.0.1", "255.240.0.0", "172.16.0.10"),
            "192.168/30": ("192.168.1.1", "255.255.255.252", "192.168.1.2"),
        }
        for name, (router, netmask, address) in accepted.items():
            with self.subTest(name):
                self.settings().update(
                    original,
                    router=router,
                    netmask=netmask,
                    start=address,
                    end=address,
                )
                offered = await self.source().offered()
                assert offered.scope is not None
                self.assertTrue(offered.scope.complete)
                self.assertEqual(offered.issues, ())

    async def test_reservations_skipped_when_capability_off(self) -> None:
        self.settings()["hosts"] = ["00:11:22:33:44:55,192.0.2.12", "invalid"]
        data = await self.source(frozenset({"dhcp_scope"})).read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(data.addressing.reservations, ())
        self.assertEqual(data.addressing.issues, ())
        self.assertEqual(len(data.addressing.scopes), 1)

    async def test_scope_skipped_when_capability_off(self) -> None:
        self.settings()["netmask"] = ""
        self.settings()["hosts"] = ["00:11:22:33:44:55,192.0.2.12"]
        data = await self.source(frozenset({"dhcp_reservations"})).read(
            (ADDRESS,), utc_now()
        )
        assert data.addressing is not None
        self.assertEqual(data.addressing.scopes, ())
        self.assertEqual(data.addressing.issues, ())
        self.assertEqual(len(data.addressing.reservations), 1)

    async def test_no_dhcp_capability_makes_no_request(self) -> None:
        for capabilities in (
            frozenset[Capability]({"network_table"}),
            frozenset[Capability]({"network_table", "dns_queries"}),
        ):
            with self.subTest(capabilities=sorted(capabilities)):
                data = await self.source(capabilities).read((ADDRESS,), utc_now())
                self.assertIsNone(data.addressing)
        self.assertEqual(self.dhcp_calls(), 0)
        # A read without any capability it can serve opens no session.
        self.calls.clear()
        data = await self.source(frozenset({"controller_clients"})).read(
            (ADDRESS,), utc_now()
        )
        self.assertIsNone(data.addressing)
        self.assertEqual(self.calls, [])

    async def test_one_dhcp_read_per_session_with_other_capabilities(self) -> None:
        source = self.source(
            frozenset(
                {"network_table", "dns_queries", "dhcp_scope", "dhcp_reservations"}
            )
        )
        data = await source.read((ADDRESS,), utc_now())
        self.assertEqual([device.mac for device in data.devices], [MAC])
        self.assertIsNotNone(data.window)
        self.assertIsNotNone(data.addressing)
        self.assertEqual(self.dhcp_calls(), 1)
        self.assertEqual(self.calls[0], ("POST", "/api/auth"))
        self.assertEqual(self.calls[-1], ("DELETE", "/api/auth"))
        self.assertEqual(self.calls.count(("POST", "/api/auth")), 1)

    async def test_identity_and_presence_reads_do_not_read_dhcp(self) -> None:
        source = self.source(frozenset({"network_table", *DHCP_ONLY}))
        identified = await source.identify((ADDRESS,), utc_now())
        self.assertIsNone(identified.addressing)
        await source.presence((ADDRESS,), utc_now())
        self.assertEqual(self.dhcp_calls(), 0)

    async def test_collection_decides_addressing_from_the_scope(self) -> None:
        async def accept(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            writer.close()
            await writer.wait_closed()

        listener = await asyncio.start_server(accept, "127.0.0.1", 0)
        self.addAsyncCleanup(listener.wait_closed)
        self.addCleanup(listener.close)
        original = asyncio.open_connection

        async def connection(
            address: str, port: int
        ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
            return await original("127.0.0.1", port)

        async def collect(capabilities: frozenset[Capability]) -> Evidence:
            plan = CollectionPlan(
                "tcp",
                (ADDRESS,),
                (listener.sockets[0].getsockname()[1],),
                1,
                1,
                None,
                0,
                sources=(ServicePlan("pihole", "dns", capabilities, self.plan),),
            )
            with patch(
                "plowshare_privacy.collection.asyncio.open_connection",
                side_effect=connection,
            ):
                return await NetworkCollector("fixture", plan, bind(plan)).collect(
                    str(uuid4()), "fixture:event", None, None
                )

        evidence = await collect(frozenset({"network_table", *DHCP_ONLY}))
        [assignment] = evidence.snapshot.addressing
        self.assertEqual(
            (assignment.address, assignment.mac, assignment.mode, assignment.basis),
            (ADDRESS, MAC, "static", "outside_dhcp_range"),
        )
        self.assertEqual(assignment.services, ("dns",))
        self.assertEqual(self.dhcp_calls(), 1)
        # The scope alone cannot rule out a reservation: never static.
        evidence = await collect(frozenset({"network_table", "dhcp_scope"}))
        [assignment] = evidence.snapshot.addressing
        self.assertEqual((assignment.mode, assignment.basis), ("unknown", None))


class PiHoleLeaseTest(PiHoleServerCase):
    async def asyncSetUp(self) -> None:
        await super().asyncSetUp()
        # Whole seconds, so expiries and the collection instant compare exactly.
        self.observed = instant(int(self.now))
        self.expiry = int(self.now) + 3600

    def source(
        self, capabilities: frozenset[Capability] = LEASES, service: str = "dns"
    ) -> PiHoleV6:
        return PiHoleV6(self.plan, capabilities=capabilities, service=service)

    def lease(
        self,
        address: str = ADDRESS,
        mac: str = MAC,
        name: object = "lease-name",
        expires: object = None,
    ) -> dict[str, object]:
        return {
            "expires": self.expiry if expires is None else expires,
            "name": name,
            "hwaddr": mac,
            "ip": address,
            "clientid": "01:" + mac,
        }

    def serve(self, *leases: object) -> None:
        self.leases = {"leases": list(leases), "took": 0.01}

    def settings(self) -> dict[str, object]:
        config = cast(dict[str, object], self.dhcp_settings)["config"]
        return cast(dict[str, object], cast(dict[str, object], config)["dhcp"])

    def lease_identity(
        self,
        address: str = ADDRESS,
        mac: str = MAC,
        hostname: str | None = "lease-name",
        vendor: str | None = None,
    ) -> DeviceIdentity:
        return DeviceIdentity(
            address,
            "mac:" + mac,
            None,
            hostname,
            mac,
            vendor,
            "pihole-v6",
            self.observed,
            None,
            "dhcp_lease",
            "dns",
        )

    async def test_active_lease_identifies_and_addresses_selected_address(
        self,
    ) -> None:
        self.serve(self.lease(), self.lease(OTHER, OTHER_MAC, "*"))
        data = await self.source(frozenset({"dhcp_leases", "dhcp_scope"})).read(
            (ADDRESS, OTHER), self.observed
        )
        self.assertEqual(
            data.devices,
            (self.lease_identity(), self.lease_identity(OTHER, OTHER_MAC, None)),
        )
        self.assertEqual(data.issues, ())
        assert data.addressing is not None
        self.assertEqual(
            data.addressing.leases,
            (
                Lease(ADDRESS, MAC, "lease-name", instant(self.expiry), "dns"),
                Lease(OTHER, OTHER_MAC, None, instant(self.expiry), "dns"),
            ),
        )
        self.assertEqual(data.addressing.issues, ())
        [scope] = data.addressing.scopes
        self.assertEqual(scope.lease_table, "complete")
        # Reservations are not read, so the scope cannot decide static.
        self.assertFalse(scope.complete)
        self.assertEqual(
            self.calls,
            [
                ("POST", "/api/auth"),
                ("GET", "/api/config/dhcp"),
                LEASE_CALL,
                ("DELETE", "/api/auth"),
            ],
        )
        # The collector decides dhcp_dynamic from the lease.
        devices = {device.address: device for device in data.devices}
        assignments, _ = assign_addresses(
            (data.addressing,), devices, (ADDRESS, OTHER), self.observed
        )
        self.assertEqual(
            [(item.mode, item.basis) for item in assignments],
            [("dhcp_dynamic", "dhcp_lease")] * 2,
        )

    async def test_leases_only_reads_settings_then_leases(self) -> None:
        self.serve(self.lease())
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.lease_identity(),))
        self.assertEqual(
            data.addressing,
            AddressingSample(
                (Lease(ADDRESS, MAC, "lease-name", instant(self.expiry), "dns"),)
            ),
        )
        self.assertEqual(self.calls[1:3], [("GET", "/api/config/dhcp"), LEASE_CALL])

    async def test_leases_skipped_when_capability_off_or_dhcp_inactive(self) -> None:
        self.serve(self.lease())
        data = await self.source(frozenset({"network_table", "dhcp_scope"})).read(
            (ADDRESS,), self.observed
        )
        self.assertEqual([device.evidence for device in data.devices], [None])
        assert data.addressing is not None
        self.assertEqual(data.addressing.scopes[0].lease_table, "incomplete")
        await self.source(frozenset({"network_table"})).identify(
            (ADDRESS,), self.observed
        )
        self.assertNotIn(LEASE_CALL, self.calls)
        self.settings()["active"] = False
        for capabilities in (
            LEASES,
            frozenset[Capability]({"dhcp_leases", "dhcp_scope"}),
        ):
            with self.subTest(capabilities=sorted(capabilities)):
                self.calls.clear()
                data = await self.source(capabilities).read((ADDRESS,), self.observed)
                self.assertEqual(data.devices, ())
                self.assertEqual(data.issues, ())
                self.assertEqual(data.addressing, AddressingSample())
                self.assertEqual(self.dhcp_calls(), 1)
                self.assertNotIn(LEASE_CALL, self.calls)

    def dhcp_calls(self) -> int:
        return self.calls.count(("GET", "/api/config/dhcp"))

    async def test_expired_lease_ignored_zero_expiry_kept(self) -> None:
        self.serve(
            self.lease(expires=int(self.now) - 10),
            self.lease(OTHER, OTHER_MAC, expires=0),
        )
        data = await self.source(frozenset({"dhcp_leases", "dhcp_scope"})).read(
            (ADDRESS, OTHER), self.observed
        )
        self.assertEqual(data.devices, (self.lease_identity(OTHER, OTHER_MAC),))
        assert data.addressing is not None
        self.assertEqual(
            data.addressing.leases,
            (Lease(OTHER, OTHER_MAC, "lease-name", None, "dns"),),
        )
        self.assertEqual(data.addressing.issues, ())
        self.assertEqual(data.addressing.scopes[0].lease_table, "complete")

    async def test_lease_and_table_disagree_is_ambiguous(self) -> None:
        self.serve(self.lease(mac=OTHER_MAC))
        data = await self.source(TABLE_AND_LEASES).read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, ())
        self.assertIn(DISAGREE, data.issues)
        identified = await self.source(TABLE_AND_LEASES).identify(
            (ADDRESS,), self.observed
        )
        self.assertEqual(identified.devices, ())
        self.assertIn(DISAGREE, identified.issues)

    async def test_two_leases_for_one_address_is_ambiguous(self) -> None:
        self.serve(self.lease(), self.lease(mac=OTHER_MAC))
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, ())
        self.assertEqual(data.issues, (TWO_LEASES,))
        # The same device listed twice is one identity, not an ambiguity.
        self.serve(self.lease(), self.lease(expires=0))
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.lease_identity(),))
        self.assertEqual(data.issues, ())

    async def test_out_of_scope_leases_never_enter_evidence(self) -> None:
        self.serve(
            self.lease(OUTSIDE, OTHER_MAC, "outside-host"),
            self.lease(OTHER, "aa:bb:cc:dd:ee:02", "other-host"),
        )
        data = await self.source(frozenset({"dhcp_leases", "dhcp_scope"})).read(
            (ADDRESS,), self.observed
        )
        self.assertEqual(data.devices, ())
        assert data.addressing is not None
        self.assertEqual(data.addressing.leases, ())
        self.assertEqual(data.addressing.scopes[0].lease_table, "complete")
        for value in (OUTSIDE, OTHER, "outside-host", "other-host", OTHER_MAC):
            self.assertNotIn(value, repr(data))

    async def test_lease_limit_marks_partial_and_incomplete(self) -> None:
        many = [
            self.lease(
                f"10.0.{n // 256}.{n % 256}",
                f"aa:bb:cc:dd:{n // 256:02x}:{n % 256:02x}",
            )
            for n in range(1, 1025)
        ]
        capabilities = frozenset[Capability]({"dhcp_leases", "dhcp_scope"})
        # 1024 entries: the table may have been cut off.
        self.serve(*many[:1023], self.lease())
        data = await self.source(capabilities).read((ADDRESS,), self.observed)
        assert data.addressing is not None
        self.assertEqual(data.addressing.issues, (LEASE_LIMIT,))
        self.assertEqual(data.addressing.scopes[0].lease_table, "incomplete")
        self.assertEqual(data.issues, (IDENTITY_LEASE_LIMIT,))
        self.assertEqual(len(data.addressing.leases), 1)
        # Entries past the limit are not decoded.
        self.serve(*many, self.lease())
        data = await self.source(capabilities).read((ADDRESS,), self.observed)
        assert data.addressing is not None
        self.assertEqual(data.addressing.leases, ())
        self.assertEqual(data.addressing.issues, (LEASE_LIMIT,))
        # One below the limit is a complete read.
        self.serve(*many[:1022], self.lease())
        data = await self.source(capabilities).read((ADDRESS,), self.observed)
        assert data.addressing is not None
        self.assertEqual(data.addressing.issues, ())
        self.assertEqual(data.addressing.scopes[0].lease_table, "complete")

    async def test_failed_lease_read_marks_incomplete(self) -> None:
        capabilities = frozenset[Capability]({"dhcp_leases", "dhcp_scope"})
        cases: dict[str, tuple[int, object]] = {
            "503": (503, None),
            "403": (403, None),
            "not an object": (200, ["x"]),
            "no lease list": (200, {"took": 0.01}),
            "lease list not a list": (200, {"leases": {"ip": ADDRESS}}),
        }
        for name, (status, body) in cases.items():
            with self.subTest(name):
                self.leases_status = status
                self.leases = {"leases": [self.lease()]} if body is None else body
                data = await self.source(capabilities).read((ADDRESS,), self.observed)
                self.assertEqual(data.devices, ())
                self.assertEqual(data.issues, (IDENTITY_LEASES_UNREADABLE,))
                assert data.addressing is not None
                self.assertEqual(data.addressing.leases, ())
                self.assertEqual(data.addressing.issues, (LEASES_UNREADABLE,))
                [scope] = data.addressing.scopes
                self.assertEqual(scope.lease_table, "incomplete")
                for issue in (*data.issues, *data.addressing.issues):
                    self.assertNotIn(SECRET, issue)
                    self.assertNotIn(self.origin, issue)
        self.leases_status = 200
        malformed: list[object] = [
            self.lease(expires=-1),
            self.lease(expires=1.5),
            self.lease(expires=True),
            self.lease(expires="0"),
            self.lease(expires=2**40),
            self.lease(mac="00:00:00:00:00:00"),
            self.lease(mac="not-a-mac"),
            self.lease(address="192.0.2.300"),
            self.lease(name=17),
            self.lease(name="x" * 200),
            {"ip": ADDRESS},
            "lease",
        ]
        for entry in malformed:
            with self.subTest(entry=entry):
                self.serve(entry, self.lease(OTHER, OTHER_MAC))
                data = await self.source(capabilities).read(
                    (ADDRESS, OTHER), self.observed
                )
                self.assertEqual(data.devices, (self.lease_identity(OTHER, OTHER_MAC),))
                self.assertEqual(data.issues, (IDENTITY_INVALID_LEASE,))
                assert data.addressing is not None
                self.assertEqual(data.addressing.issues, (INVALID_LEASE,))
                self.assertEqual(data.addressing.scopes[0].lease_table, "incomplete")

    async def test_unreadable_settings_or_session_is_an_identity_gap(self) -> None:
        self.dhcp_status = 503
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.issues, (IDENTITY_LEASES_UNREADABLE,))
        self.assertNotIn(LEASE_CALL, self.calls)
        self.mode = "authentication_failure"
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(
            data.issues,
            ("identity_unavailable: Pi-hole authentication or connection failed",),
        )

    async def test_leases_do_not_create_presence(self) -> None:
        self.serve(self.lease(), self.lease(OTHER, OTHER_MAC))
        source = self.source(frozenset({"network_table", "dns_queries", "dhcp_leases"}))
        sample = await source.presence((ADDRESS, OTHER), self.observed)
        self.assertEqual([device.address for device in sample.devices], [ADDRESS])
        self.assertEqual([device.evidence for device in sample.devices], [None])
        await source.activity((ADDRESS,), instant(int(self.now) - 60), self.observed)
        self.assertNotIn(LEASE_CALL, self.calls)
        self.assertNotIn(("GET", "/api/config/dhcp"), self.calls)
        # A leases-only Pi-hole has no presence read at all.
        self.calls.clear()
        sample = await self.source().presence((ADDRESS,), self.observed)
        self.assertEqual(sample.devices, ())
        self.assertEqual(self.calls, [])

    async def test_lease_identity_wins_over_table_with_same_mac(self) -> None:
        self.serve(self.lease())
        data = await self.source(TABLE_AND_LEASES).read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.lease_identity(vendor="Fixture vendor"),))
        self.assertEqual(data.issues, ())

    async def test_lease_fills_an_address_the_table_lacks(self) -> None:
        self.serve(self.lease(OTHER, OTHER_MAC))
        data = await self.source(TABLE_AND_LEASES).read((ADDRESS, OTHER), self.observed)
        self.assertEqual(
            [(device.address, device.evidence) for device in data.devices],
            [(ADDRESS, None), (OTHER, "dhcp_lease")],
        )
        # Every selected address now has an association: no unavailable gap.
        self.assertEqual(data.issues, ())

    async def collect(
        self, capabilities: frozenset[Capability], *others: DeviceIdentity
    ) -> Evidence:
        """A full collection of ADDRESS by this Pi-hole plus a fake identity service."""

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
            (ADDRESS,),
            (listener.sockets[0].getsockname()[1],),
            1,
            1,
            None,
            0,
            sources=(ServicePlan("pihole", "dns", capabilities, self.plan),),
        )
        sources = replace(
            bind(plan),
            identity=(
                Bound[DeviceIdentitySource](
                    "controller", "unifi", _Identities(*others)
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
            return await NetworkCollector("fixture", plan, sources).collect(
                str(uuid4()), "fixture:event", None, None
            )

    def other_record(
        self, mac: str, hostname: str, evidence: str | None = None
    ) -> DeviceIdentity:
        source = "pihole-v6" if evidence == "neighbour_table" else "unifi"
        return DeviceIdentity.decode(
            {
                "address": ADDRESS,
                "device_id": "mac:" + mac,
                "label": None,
                "hostname": hostname,
                "mac": mac,
                "vendor": None,
                "source": source,
                "observed_at": self.observed,
                "last_seen": self.observed,
                "evidence": evidence,
                "service": "controller",
            }
        )

    async def test_contradicted_lease_never_decides_assignment(self) -> None:
        # ADDRESS inside the range: only a lease could make it dhcp_dynamic.
        self.settings()["start"] = "192.0.2.10"
        self.serve(self.lease(mac=OTHER_MAC))
        capabilities = frozenset[Capability](
            {"network_table", "dhcp_leases", "dhcp_scope"}
        )
        # The other service agrees with the lease, the Pi-hole table does not.
        evidence = await self.collect(
            capabilities, self.other_record(OTHER_MAC, "client")
        )
        [device] = evidence.snapshot.devices
        self.assertEqual((device.mac, device.hostname), (OTHER_MAC, "client"))
        self.assertIn(DISAGREE, evidence.issues)
        [assignment] = evidence.snapshot.addressing
        self.assertEqual(
            assignment, AddressAssignment(ADDRESS, OTHER_MAC, "unknown", None, (), None)
        )
        # Pi-hole's own read carries no contradicted lease and an incomplete table.
        data = await self.source(capabilities).read((ADDRESS,), self.observed)
        assert data.addressing is not None
        self.assertEqual(data.addressing.leases, ())
        self.assertEqual(data.addressing.scopes[0].lease_table, "incomplete")
        # Several leases for one address are contradicted the same way.
        self.serve(self.lease(), self.lease(mac=OTHER_MAC))
        data = await self.source(capabilities).read((ADDRESS,), self.observed)
        assert data.addressing is not None
        self.assertEqual(data.addressing.leases, ())
        self.assertEqual(data.addressing.scopes[0].lease_table, "incomplete")

    async def test_lease_precedence_across_services(self) -> None:
        self.serve(self.lease())
        capabilities = frozenset[Capability]({"dhcp_leases", "dhcp_scope"})
        # A lease outranks another service's neighbour-table record.
        evidence = await self.collect(
            capabilities, self.other_record(MAC, "neighbour", "neighbour_table")
        )
        [device] = evidence.snapshot.devices
        self.assertEqual(
            (device.hostname, device.evidence, device.service),
            ("lease-name", "dhcp_lease", "dns"),
        )
        [assignment] = evidence.snapshot.addressing
        self.assertEqual(
            (assignment.mode, assignment.basis), ("dhcp_dynamic", "dhcp_lease")
        )
        # A controller client outranks the lease; the lease still addresses it.
        evidence = await self.collect(capabilities, self.other_record(MAC, "client"))
        [device] = evidence.snapshot.devices
        self.assertEqual((device.hostname, device.source), ("client", "unifi"))
        [assignment] = evidence.snapshot.addressing
        self.assertEqual(
            (assignment.mode, assignment.basis), ("dhcp_dynamic", "dhcp_lease")
        )

    async def test_identify_reads_leases(self) -> None:
        self.serve(self.lease())
        data = await self.source().identify((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.lease_identity(),))
        self.assertIsNone(data.addressing)
        self.assertEqual(self.calls[1:3], [("GET", "/api/config/dhcp"), LEASE_CALL])


# 40 selected addresses inside the default DHCP range: two collector batches.
MANY = tuple(f"192.0.2.{101 + index}" for index in range(40))
EVERYTHING: frozenset[Capability] = frozenset(
    {
        "network_table",
        "dhcp_scope",
        "dhcp_reservations",
        "dhcp_leases",
        "local_dns_names",
    }
)


def _mac(index: int) -> str:
    return f"02:00:00:00:00:{index:02x}"


class PiHoleCollectionScopeTest(PiHoleServerCase):
    """DHCP settings, leases and local names are read once per full collection."""

    async def asyncSetUp(self) -> None:
        await super().asyncSetUp()
        # Endpoints answering 503 on their first request only.
        self.fail_first: set[str] = set()
        # Selected addresses the network table does not list.
        self.unlisted: set[str] = set()

    def failing(self, path: str) -> bool:
        first = self.calls.count(("GET", path)) == 1
        return first and path in self.fail_first

    async def devices(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        listed = [
            self.device(address, _mac(index))
            for index, address in enumerate(MANY)
            if address not in self.unlisted
        ]
        return web.json_response({"devices": listed, "took": 0.01})

    async def dhcp(self, request: web.Request) -> web.Response:
        response = await super().dhcp(request)
        return web.Response(status=503) if self.failing(request.path) else response

    async def dhcp_leases(self, request: web.Request) -> web.Response:
        response = await super().dhcp_leases(request)
        return web.Response(status=503) if self.failing(request.path) else response

    def settings(self) -> dict[str, object]:
        config = cast(dict[str, object], self.dhcp_settings)["config"]
        return cast(dict[str, object], cast(dict[str, object], config)["dhcp"])

    async def collect(
        self, capabilities: frozenset[Capability] = EVERYTHING
    ) -> Evidence:
        plan = CollectionPlan(
            "tcp",
            MANY,
            (443,),
            0.1,
            1,
            None,
            0,
            sources=(ServicePlan("pihole", "dns", capabilities, self.plan),),
        )
        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=TimeoutError,
        ):
            return await NetworkCollector("fixture", plan, bind(plan)).collect(
                str(uuid4()), "fixture:event", None, None
            )

    def lease(self, index: int, mac: str | None = None) -> dict[str, object]:
        return {
            "expires": 0,
            "name": "lease-name",
            "hwaddr": mac or _mac(index),
            "ip": MANY[index],
            "clientid": "*",
        }

    async def test_one_read_of_settings_leases_and_names_per_collection(
        self,
    ) -> None:
        self.leases = {"leases": [self.lease(35)], "took": 0.01}
        self.dns_hosts = {
            "config": {"dns": {"hosts": [MANY[36] + " named.lan"]}},
            "took": 0.01,
        }
        self.unlisted = {MANY[36]}
        evidence = await self.collect()
        for path in ("config/dhcp", "dhcp/leases", "config/dns/hosts"):
            self.assertEqual(self.calls.count(("GET", "/api/" + path)), 1, path)
        # One session per batch; the table is read in each.
        self.assertEqual(self.calls.count(("POST", "/api/auth")), 2)
        self.assertEqual(self.calls.count(("GET", "/api/network/devices")), 2)
        devices = {device.address: device for device in evidence.snapshot.devices}
        # The second batch's lease and name were merged with its own table.
        leased = devices[MANY[35]]
        self.assertEqual(
            (leased.evidence, leased.vendor, leased.service),
            ("dhcp_lease", "Fixture vendor", "dns"),
        )
        named = devices[MANY[36]]
        self.assertEqual((named.hostname, named.evidence), ("named.lan", "local_dns"))
        modes = {
            item.address: (item.mode, item.basis)
            for item in evidence.snapshot.addressing
        }
        self.assertEqual(modes[MANY[35]], ("dhcp_dynamic", "dhcp_lease"))
        self.assertEqual(modes[MANY[0]], ("static", "no_lease_or_reservation"))
        self.assertEqual(modes[MANY[39]], ("static", "no_lease_or_reservation"))
        # The name-only record has no MAC.
        self.assertEqual(modes[MANY[36]], ("unknown", None))
        self.assertFalse(
            any(issue.startswith("addressing_") for issue in evidence.issues)
        )

    def assert_never_static(self, evidence: Evidence) -> None:
        self.assertEqual(len(evidence.snapshot.addressing), len(MANY))
        for item in evidence.snapshot.addressing:
            self.assertNotEqual(item.mode, "static", item.address)
            self.assertEqual(item.mode, "unknown", item.address)

    async def test_failed_lease_read_makes_no_address_static(self) -> None:
        self.fail_first = {"/api/dhcp/leases"}
        evidence = await self.collect()
        self.assert_never_static(evidence)
        self.assertEqual(self.calls.count(LEASE_CALL), 1)
        self.assertIn(LEASES_UNREADABLE, evidence.issues)
        self.assertIn(IDENTITY_LEASES_UNREADABLE, evidence.issues)

    async def test_failed_settings_read_makes_no_address_static(self) -> None:
        self.fail_first = {"/api/config/dhcp"}
        evidence = await self.collect()
        self.assert_never_static(evidence)
        self.assertEqual(self.calls.count(("GET", "/api/config/dhcp")), 1)
        self.assertNotIn(LEASE_CALL, self.calls)
        self.assertIn(UNAVAILABLE, evidence.issues)

    async def test_lease_contradicted_in_one_batch_leaves_the_table_incomplete(
        self,
    ) -> None:
        # The second batch's table names another MAC for the leased address.
        self.leases = {"leases": [self.lease(35, OTHER_MAC)], "took": 0.01}
        evidence = await self.collect()
        self.assertIn(DISAGREE, evidence.issues)
        self.assert_never_static(evidence)

    async def test_failed_first_session_leaves_later_batches_without_leases(
        self,
    ) -> None:
        self.leases = {"leases": [self.lease(35)], "took": 0.01}
        source = PiHoleV6(self.plan, capabilities=EVERYTHING, service="dns")
        sessions = 0
        authenticate = source._authenticate

        async def flaky(session: object) -> str:
            nonlocal sessions
            sessions += 1
            if sessions == 1:
                raise ValueError("first session refused")
            return await authenticate(cast(Any, session))

        with patch.object(source, "_authenticate", flaky):
            read = await source.read_collection((MANY[:32], MANY[32:]), utc_now())
        self.assertIsNotNone(read.addressing)
        assert read.addressing is not None
        self.assertEqual(read.addressing.scopes, ())
        self.assertEqual(read.addressing.issues, (UNAVAILABLE,))
        second = read.batches[1]
        self.assertNotIn("dhcp_lease", [device.evidence for device in second.devices])
        self.assertIn(IDENTITY_LEASES_UNREADABLE, second.issues)
        self.assertIn(NAMES_UNREADABLE_TEXT, second.issues)
        self.assertNotIn(LEASE_CALL, self.calls)


class DhcpOnlyServiceTest(unittest.TestCase):
    def plan(self, capabilities: frozenset[Capability]) -> CollectionPlan:
        return CollectionPlan(
            "tcp",
            (ADDRESS,),
            (443,),
            0.1,
            1,
            None,
            3600,
            sources=(ServicePlan("pihole", "dns", capabilities, PIHOLE),),
        )

    def test_dhcp_only_pihole_binds_a_device_read(self) -> None:
        with patch.dict(os.environ, {"PIHOLE_PASSWORD": "fixture-password"}):
            for capability in ("dhcp_scope", "dhcp_reservations", "dhcp_leases"):
                with self.subTest(capability):
                    plan = self.plan(frozenset({capability}))
                    sources = bind(plan)
                    self.assertEqual(
                        [bound.service for bound in sources.device_reads], ["dns"]
                    )
                    self.assertEqual(sources.presence, ())
                    self.assertIsNone(sources.dns_activity)
                    # Accepted: the DHCP capability has a bound reader.
                    NetworkCollector("fixture", plan, sources)

    def test_dhcp_capability_without_a_reader_is_refused(self) -> None:
        for capability in ("dhcp_scope", "local_dns_names"):
            with (
                self.subTest(capability),
                self.assertRaisesRegex(ValueError, "has no bound reader"),
            ):
                NetworkCollector(
                    "fixture", self.plan(frozenset({capability})), SourceSet()
                )


NAMES: frozenset[Capability] = frozenset({"local_dns_names"})
TABLE_AND_NAMES: frozenset[Capability] = frozenset({"network_table", "local_dns_names"})
INVALID_NAME = "identity_partial: Pi-hole returned an invalid local DNS record"
NAME_LIMIT = "identity_partial: Pi-hole local DNS record limit reached"
NAMES_UNREADABLE = "identity_partial: Pi-hole local DNS records could not be read"
NAMES_CALL = ("GET", "/api/config/dns/hosts")


class PiHoleLocalDnsTest(PiHoleServerCase):
    async def asyncSetUp(self) -> None:
        await super().asyncSetUp()
        self.observed = instant(int(self.now))

    def source(
        self, capabilities: frozenset[Capability] = NAMES, service: str = "dns"
    ) -> PiHoleV6:
        return PiHoleV6(self.plan, capabilities=capabilities, service=service)

    def serve(self, *hosts: object) -> None:
        self.dns_hosts = {"config": {"dns": {"hosts": list(hosts)}}, "took": 0.01}

    def named(self, address: str = ADDRESS, name: str = "tv.lan") -> DeviceIdentity:
        return DeviceIdentity(
            address,
            "ip:" + address,
            None,
            name,
            None,
            None,
            "pihole-v6",
            self.observed,
            None,
            "local_dns",
            "dns",
        )

    async def test_local_name_fills_missing_hostname_only(self) -> None:
        self.serve(f"{ADDRESS} first.lan second.lan", f"{OTHER} other.lan")
        # No table record: an IP-only local_dns record, first name wins.
        data = await self.source().read((ADDRESS, OTHER), self.observed)
        self.assertEqual(
            data.devices,
            (self.named(ADDRESS, "first.lan"), self.named(OTHER, "other.lan")),
        )
        self.assertEqual(data.issues, ())
        # A table record that has a hostname keeps it; one without gets the name.
        self.serve(f"{ADDRESS} first.lan")
        data = await self.source(TABLE_AND_NAMES).read((ADDRESS,), self.observed)
        [device] = data.devices
        self.assertEqual(device.hostname, "living-room-tv")
        self.assertEqual(device.mac, MAC)
        self.assertIsNone(device.evidence)

        original = self.device

        def unnamed(address: str, mac: str = MAC) -> dict[str, object]:
            record = original(address, mac)
            cast(list[dict[str, object]], record["ips"])[0]["name"] = ""
            return record

        with patch.object(self, "device", unnamed):
            data = await self.source(TABLE_AND_NAMES).read((ADDRESS,), self.observed)
            identified = await self.source(TABLE_AND_NAMES).identify(
                (ADDRESS,), self.observed
            )
        for sample in (data, identified):
            [device] = sample.devices
            self.assertEqual(
                (device.hostname, device.mac, device.evidence, device.device_id),
                ("first.lan", MAC, None, "mac:" + MAC),
            )

    async def test_local_name_never_asserts_mac_or_conflicts(self) -> None:
        self.serve(f"{ADDRESS} first.lan")
        data = await self.source().read((ADDRESS,), self.observed)
        [device] = data.devices
        self.assertIsNone(device.mac)
        self.assertEqual(device.device_id, "ip:" + ADDRESS)
        # Next to a table record with a MAC, no conflict or ambiguity arises.
        data = await self.source(TABLE_AND_NAMES).read((ADDRESS,), self.observed)
        self.assertEqual([d.mac for d in data.devices], [MAC])
        self.assertEqual(data.issues, ())

    async def test_ambiguous_address_gets_no_local_name(self) -> None:
        self.mode = "ambiguous"
        self.serve(f"{ADDRESS} first.lan")
        data = await self.source(TABLE_AND_NAMES).read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, ())
        self.assertIn(
            "identity_ambiguous: Pi-hole lists multiple devices for a selected address",
            data.issues,
        )

    async def test_malformed_host_lines_are_partial(self) -> None:
        malformed: list[object] = [
            "not-an-ip name.lan",
            f"{ADDRESS}",
            f"{ADDRESS} bad\x00name",
            f"{ADDRESS} " + "x" * 129,
            "192.0.2.300 name.lan",
            17,
            "",
            "   ",
        ]
        for entry in malformed:
            with self.subTest(entry=entry):
                self.serve(entry, f"{OTHER} other.lan")
                data = await self.source().read((ADDRESS, OTHER), self.observed)
                self.assertEqual(data.devices, (self.named(OTHER, "other.lan"),))
                self.assertEqual(data.issues, (INVALID_NAME,))

    async def test_out_of_scope_records_are_ignored_silently(self) -> None:
        self.serve(f"{OUTSIDE} outside.lan", f"{ADDRESS} first.lan")
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.named(ADDRESS, "first.lan"),))
        self.assertEqual(data.issues, ())
        self.assertNotIn("outside.lan", repr(data))

    async def test_record_limit(self) -> None:
        filler = [f"10.1.{n // 256}.{n % 256} h{n}.lan" for n in range(1, 513)]
        self.serve(*filler, f"{ADDRESS} late.lan")
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, ())
        self.assertEqual(data.issues, (NAME_LIMIT,))
        self.serve(*filler[:511], f"{ADDRESS} last.lan")
        data = await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(data.devices, (self.named(ADDRESS, "last.lan"),))
        self.assertEqual(data.issues, ())

    async def test_unreadable_records_do_not_affect_other_results(self) -> None:
        cases: dict[str, tuple[int, object]] = {
            "503": (503, None),
            "403": (403, None),
            "not an object": (200, ["x"]),
            "no config": (200, {"took": 0.01}),
            "hosts not a list": (200, {"config": {"dns": {"hosts": "x"}}}),
        }
        for name, (status, body) in cases.items():
            with self.subTest(name):
                self.hosts_status = status
                self.dns_hosts = self.dns_hosts if body is None else body
                data = await self.source(TABLE_AND_NAMES).read(
                    (ADDRESS,), self.observed
                )
                self.assertEqual([d.mac for d in data.devices], [MAC])
                self.assertEqual([d.hostname for d in data.devices], ["living-room-tv"])
                self.assertEqual(data.issues, (NAMES_UNREADABLE,))
                self.assertNotIn(SECRET, repr(data))
                self.assertNotIn(self.origin, repr(data))

    async def test_names_skipped_when_capability_off(self) -> None:
        self.serve(f"{ADDRESS} first.lan")
        source = self.source(frozenset({"network_table"}))
        await source.read((ADDRESS,), self.observed)
        await source.identify((ADDRESS,), self.observed)
        self.assertNotIn(NAMES_CALL, self.calls)
        # Names alone make no table request.
        self.calls.clear()
        await self.source().read((ADDRESS,), self.observed)
        self.assertEqual(
            self.calls,
            [("POST", "/api/auth"), NAMES_CALL, ("DELETE", "/api/auth")],
        )

    async def test_names_not_read_by_presence_or_activity(self) -> None:
        self.serve(f"{ADDRESS} first.lan")
        source = self.source(frozenset({"network_table", "local_dns_names"}))
        await source.presence((ADDRESS,), self.observed)
        await source.activity((ADDRESS,), instant(int(self.now) - 60), self.observed)
        self.assertNotIn(NAMES_CALL, self.calls)
        await source.identify((ADDRESS,), self.observed)
        self.assertIn(NAMES_CALL, self.calls)


if __name__ == "__main__":
    unittest.main()
