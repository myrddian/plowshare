"""Pi-hole v6 DHCP settings: the connection check, scope and reservations."""

from __future__ import annotations

import asyncio
import ipaddress
import os
import unittest
from typing import cast
from unittest.mock import patch
from uuid import uuid4

from test_connectors import PIHOLE
from test_pihole import ADDRESS, MAC, SECRET, PiHoleServerCase

from plowshare_privacy.addressing import AddressingSample, DhcpScope, Reservation
from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.connectors import bind
from plowshare_privacy.contracts import (
    Capability,
    CollectionPlan,
    ServicePlan,
    utc_now,
)
from plowshare_privacy.pihole import PiHoleDhcp, PiHoleV6
from plowshare_privacy.source_set import SourceSet

OTHER = "192.0.2.150"
OUTSIDE = "198.51.100.7"
UNAVAILABLE = "addressing_unavailable: Pi-hole DHCP settings could not be read"
INVALID_RESERVATION = "addressing_partial: Pi-hole returned an invalid DHCP reservation"
INVALID_RANGE = "addressing_partial: Pi-hole returned an invalid DHCP range"
RESERVATION_LIMIT = "addressing_partial: Pi-hole DHCP reservation limit reached"
DHCP_ONLY: frozenset[Capability] = frozenset({"dhcp_scope", "dhcp_reservations"})


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
                self.assertTrue(data.addressing.scopes[0].complete)
        self.settings()["hosts"] = None
        data = await self.source().read((ADDRESS,), utc_now())
        assert data.addressing is not None
        self.assertEqual(data.addressing.issues, (INVALID_RESERVATION,))
        self.assertTrue(data.addressing.scopes[0].complete)

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
        data = await self.source(frozenset({"local_dns_names"})).read(
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
        plan = CollectionPlan(
            "tcp",
            (ADDRESS,),
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
        [assignment] = evidence.snapshot.addressing
        self.assertEqual(
            (assignment.address, assignment.mac, assignment.mode, assignment.basis),
            (ADDRESS, MAC, "static", "outside_dhcp_range"),
        )
        self.assertEqual(assignment.services, ("dns",))
        self.assertEqual(self.dhcp_calls(), 1)


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
        with self.assertRaisesRegex(ValueError, "has no bound reader"):
            NetworkCollector(
                "fixture", self.plan(frozenset({"dhcp_scope"})), SourceSet()
            )


if __name__ == "__main__":
    unittest.main()
