"""The Addressing bucket decides each address's assignment mode from decoded facts."""

from __future__ import annotations

import ipaddress
import unittest

from plowshare_privacy.addressing import (
    AddressingSample,
    DhcpScope,
    Lease,
    Reservation,
    assign_addresses,
)
from plowshare_privacy.contracts import AddressAssignment, DeviceIdentity

NOW = "2026-01-01T12:00:00Z"
LATER = "2026-01-02T00:00:00Z"
EARLIER = "2026-01-01T06:00:00Z"
FIRST = "192.0.2.10"
SECOND = "192.0.2.11"
THIRD = "192.0.2.12"
MAC = "00:11:22:33:44:55"
OTHER_MAC = "00:11:22:33:44:66"
THIRD_MAC = "00:11:22:33:44:77"
NETWORK = ipaddress.IPv4Network("192.0.2.0/24")
HOLDER_CONFLICT = (
    "addressing_conflict: a DHCP reservation names a different device than"
    " the one holding the address"
)
CONFIGURED_CONFLICT = (
    "addressing_conflict: configured address assignments disagree on a selected address"
)


def identity(address: str, mac: str | None) -> DeviceIdentity:
    if mac is None:
        return DeviceIdentity(
            address, "ip:" + address, None, None, None, None, "configured", NOW, None
        )
    return DeviceIdentity(
        address, "mac:" + mac, None, "host", mac, None, "pihole-v6", NOW, NOW
    )


def scope(
    service: str = "home-dns",
    *,
    start: str = "192.0.2.100",
    end: str = "192.0.2.200",
    complete: bool = True,
    lease_table: str = "complete",
) -> DhcpScope:
    assert lease_table in ("complete", "incomplete", "none")
    return DhcpScope(
        service,
        NETWORK,
        start,
        end,
        complete,
        "complete"
        if lease_table == "complete"
        else "incomplete"
        if lease_table == "incomplete"
        else "none",
    )


def assign(
    *samples: AddressingSample,
    devices: dict[str, str | None] | None = None,
    targets: tuple[str, ...] = (FIRST,),
) -> tuple[tuple[AddressAssignment, ...], tuple[str, ...]]:
    holders = {FIRST: MAC} if devices is None else devices
    return assign_addresses(
        samples,
        {address: identity(address, mac) for address, mac in holders.items()},
        targets,
        NOW,
    )


class AssignAddressesTest(unittest.TestCase):
    def test_reservation_for_holder_is_reserved(self) -> None:
        assignments, issues = assign(
            AddressingSample(
                reservations=(Reservation(FIRST, MAC, "dhcp_reservation", "home-dns"),),
                leases=(Lease(FIRST, MAC, "host", LATER, "home-dns"),),
            )
        )
        self.assertEqual(
            assignments,
            (
                AddressAssignment(
                    FIRST, MAC, "dhcp_reserved", "dhcp_reservation", ("home-dns",), None
                ),
            ),
        )
        self.assertEqual(issues, ())

    def test_controller_fixed_ip_is_reserved(self) -> None:
        assignments, _ = assign(
            AddressingSample(
                reservations=(
                    Reservation(FIRST, MAC, "controller_fixed_ip", "controller"),
                )
            )
        )
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST,
                MAC,
                "dhcp_reserved",
                "controller_fixed_ip",
                ("controller",),
                None,
            ),
        )

    def test_active_lease_is_dynamic_and_keeps_expiry(self) -> None:
        assignments, issues = assign(
            AddressingSample(
                leases=(Lease(FIRST, MAC, "host", LATER, "home-dns"),),
                scopes=(scope(),),
            )
        )
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST, MAC, "dhcp_dynamic", "dhcp_lease", ("home-dns",), LATER
            ),
        )
        self.assertEqual(issues, ())
        # A lease without expiry is still active.
        assignments, _ = assign(
            AddressingSample(leases=(Lease(FIRST, MAC, None, None, "home-dns"),))
        )
        self.assertEqual(
            (assignments[0].mode, assignments[0].expires_at), ("dhcp_dynamic", None)
        )

    def test_expired_lease_is_ignored(self) -> None:
        for expiry in (EARLIER, NOW):
            with self.subTest(expiry=expiry):
                assignments, _ = assign(
                    AddressingSample(leases=(Lease(FIRST, MAC, None, expiry, "d"),))
                )
                self.assertEqual(
                    assignments[0],
                    AddressAssignment(FIRST, MAC, "unknown", None, (), None),
                )

    def test_outside_range_with_complete_scope_is_static(self) -> None:
        assignments, _ = assign(AddressingSample(scopes=(scope(),)))
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST, MAC, "static", "outside_dhcp_range", ("home-dns",), None
            ),
        )

    def test_inside_range_complete_lease_table_without_lease_is_static(self) -> None:
        assignments, _ = assign(AddressingSample(scopes=(scope(start=FIRST),)))
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST, MAC, "static", "no_lease_or_reservation", ("home-dns",), None
            ),
        )

    def test_inside_range_incomplete_lease_table_is_unknown(self) -> None:
        assignments, _ = assign(
            AddressingSample(scopes=(scope(start=FIRST, lease_table="incomplete"),))
        )
        self.assertEqual(
            assignments[0], AddressAssignment(FIRST, MAC, "unknown", None, (), None)
        )

    def test_inside_range_without_lease_table_is_dynamic(self) -> None:
        assignments, _ = assign(
            AddressingSample(
                scopes=(
                    scope(
                        "controller", end=FIRST, start="192.0.2.2", lease_table="none"
                    ),
                )
            )
        )
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST, MAC, "dhcp_dynamic", "inside_dhcp_range", ("controller",), None
            ),
        )

    def test_no_scope_or_incomplete_scope_is_unknown_never_static(self) -> None:
        other_network = DhcpScope(
            "home-dns",
            ipaddress.IPv4Network("198.51.100.0/24"),
            "198.51.100.100",
            "198.51.100.200",
            True,
            "complete",
        )
        for sample in (
            AddressingSample(),
            AddressingSample(scopes=(scope(complete=False),)),
            AddressingSample(scopes=(other_network,)),
        ):
            with self.subTest(sample=sample):
                assignments, issues = assign(sample)
                self.assertEqual(
                    assignments[0],
                    AddressAssignment(FIRST, MAC, "unknown", None, (), None),
                )
                self.assertEqual(issues, ())

    def test_address_without_mac_is_unknown(self) -> None:
        assignments, _ = assign(
            AddressingSample(
                leases=(Lease(FIRST, MAC, None, LATER, "home-dns"),),
                scopes=(scope(),),
            ),
            devices={FIRST: None},
        )
        self.assertEqual(
            assignments[0], AddressAssignment(FIRST, None, "unknown", None, (), None)
        )

    def test_reservation_for_other_mac_is_conflict_and_holder_still_assessed(
        self,
    ) -> None:
        assignments, issues = assign(
            AddressingSample(
                reservations=(
                    Reservation(FIRST, OTHER_MAC, "dhcp_reservation", "home-dns"),
                ),
                leases=(Lease(FIRST, MAC, None, LATER, "home-dns"),),
            )
        )
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST, MAC, "dhcp_dynamic", "dhcp_lease", ("home-dns",), LATER
            ),
        )
        self.assertEqual(issues, (HOLDER_CONFLICT,))

    def test_disagreeing_configured_sources_are_unknown_with_conflict(self) -> None:
        assignments, issues = assign(
            AddressingSample(
                reservations=(Reservation(FIRST, MAC, "dhcp_reservation", "home-dns"),),
                scopes=(scope(),),
            ),
            AddressingSample(
                reservations=(
                    Reservation(FIRST, OTHER_MAC, "controller_fixed_ip", "controller"),
                ),
                issues=("addressing_partial: example connector gap",),
            ),
        )
        self.assertEqual(
            assignments[0], AddressAssignment(FIRST, MAC, "unknown", None, (), None)
        )
        # Connector issues come first, in sample order; conflicts are deduped.
        self.assertEqual(
            issues,
            ("addressing_partial: example connector gap", CONFIGURED_CONFLICT),
        )
        # Agreeing configured sources keep both services, first-seen order.
        assignments, issues = assign(
            AddressingSample(
                reservations=(Reservation(FIRST, MAC, "dhcp_reservation", "home-dns"),)
            ),
            AddressingSample(
                reservations=(
                    Reservation(FIRST, MAC, "controller_fixed_ip", "controller"),
                )
            ),
        )
        self.assertEqual(
            assignments[0],
            AddressAssignment(
                FIRST,
                MAC,
                "dhcp_reserved",
                "dhcp_reservation",
                ("home-dns", "controller"),
                None,
            ),
        )
        self.assertEqual(issues, ())

    def test_mixed_network_keeps_each_mode(self) -> None:
        targets = (FIRST, SECOND, THIRD)
        assignments, issues = assign(
            AddressingSample(
                reservations=(Reservation(FIRST, MAC, "dhcp_reservation", "home-dns"),),
                leases=(Lease(SECOND, OTHER_MAC, None, LATER, "home-dns"),),
                scopes=(scope(),),
            ),
            devices={FIRST: MAC, SECOND: OTHER_MAC, THIRD: THIRD_MAC},
            targets=targets,
        )
        self.assertEqual(
            [(item.address, item.mode) for item in assignments],
            [(FIRST, "dhcp_reserved"), (SECOND, "dhcp_dynamic"), (THIRD, "static")],
        )
        self.assertEqual(issues, ())

    def test_only_targets_with_an_identity_record_are_assigned(self) -> None:
        assignments, _ = assign(devices={SECOND: MAC}, targets=(FIRST, SECOND))
        self.assertEqual([item.address for item in assignments], [SECOND])

    def test_out_of_scope_facts_rejected(self) -> None:
        outside = "192.0.2.99"
        for sample in (
            AddressingSample(leases=(Lease(outside, MAC, None, None, "home-dns"),)),
            AddressingSample(
                reservations=(Reservation(outside, MAC, "dhcp_reservation", "d"),)
            ),
        ):
            with self.subTest(sample=sample), self.assertRaises(ValueError):
                assign(sample)


if __name__ == "__main__":
    unittest.main()
