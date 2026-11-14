"""Device cards carry the latest recorded address assignment and its last change."""

import json
import unittest
from dataclasses import asdict

from plowshare_privacy.contracts import (
    AddressAssignment,
    AssignmentBasis,
    AssignmentMode,
    DeviceIdentity,
    Evidence,
    Snapshot,
    TcpObservation,
)
from plowshare_privacy.device_dossier import build_dossier
from plowshare_privacy.device_inventory import DeviceCard, device_inventory
from plowshare_privacy.journal import Receipt
from plowshare_privacy.presence import PresenceView, Sighting

ADDRESS = "192.0.2.12"
MAC = "aa:bb:cc:dd:ee:ff"
OTHER_MAC = "aa:bb:cc:dd:ee:00"
SCOPE = "a" * 64
BASES: dict[AssignmentMode, AssignmentBasis | None] = {
    "dhcp_dynamic": "dhcp_lease",
    "dhcp_reserved": "dhcp_reservation",
    "static": "outside_dhcp_range",
    "unknown": None,
}


def assignment(mode: AssignmentMode, mac: str | None = MAC) -> AddressAssignment:
    return AddressAssignment(
        ADDRESS,
        mac,
        mode,
        BASES[mode],
        ("home-dns",) if mode != "unknown" else (),
        "2026-01-09T00:00:00Z" if mode == "dhcp_dynamic" else None,
    )


def receipt(
    day: int,
    *addressing: AddressAssignment,
    scope: str = SCOPE,
    issues: tuple[str, ...] = (),
    probed: bool = True,
) -> Receipt:
    finished = f"2026-01-0{day}T00:00:00Z"
    evidence = Evidence(
        scan_id=f"scan-{day}",
        source_event=f"event-{day}",
        collector="network-privacy",
        mode="tcp",
        scope=scope,
        started_at=finished,
        finished_at=finished,
        snapshot=Snapshot(
            finished,
            (TcpObservation(ADDRESS, 443, "open"),) if probed else (),
            (),
            (),
            None,
            addressing,
        ),
        issues=issues,
        changes=(),
        previous_revision=None,
    )
    return Receipt(
        f"scan-{day}", f"event-{day}", "topic", finished, "done", evidence, f"rev-{day}"
    )


def card(*receipts: Receipt, presence: PresenceView | None = None) -> DeviceCard:
    (result,) = device_inventory((ADDRESS,), (), receipts, presence).devices
    return result


def sighted(mac: str) -> PresenceView:
    """A quick sighting of ``mac`` at the address, newer than every receipt."""
    identity = DeviceIdentity(
        ADDRESS,
        "mac:" + mac,
        None,
        None,
        mac,
        None,
        "pihole-v6",
        "2026-01-09T12:00:00Z",
        "2026-01-09T12:00:00Z",
    )
    return PresenceView(
        "2026-01-09T12:00:00Z", 1, {ADDRESS: Sighting(ADDRESS, identity)}
    )


class DeviceAddressingTest(unittest.TestCase):
    def test_card_shows_latest_assignment(self) -> None:
        latest = card(
            receipt(1, assignment("dhcp_reserved")), receipt(2, assignment("static"))
        ).addressing
        assert latest is not None
        self.assertEqual("static", latest.mode)
        self.assertEqual("outside_dhcp_range", latest.basis)
        self.assertEqual(("home-dns",), latest.services)
        self.assertIsNone(latest.expires_at)
        self.assertEqual("2026-01-02T00:00:00Z", latest.observed_at)

        lease = card(receipt(1, assignment("dhcp_dynamic"))).addressing
        assert lease is not None
        self.assertEqual("2026-01-09T00:00:00Z", lease.expires_at)
        self.assertEqual("2026-01-01T00:00:00Z", lease.observed_at)

    def test_no_assignment_in_latest_collection_shows_none(self) -> None:
        self.assertIsNone(card(receipt(1, assignment("static")), receipt(2)).addressing)
        self.assertIsNone(card(receipt(1)).addressing)

    def test_mode_change_is_recorded(self) -> None:
        first = receipt(1, assignment("dhcp_reserved"))
        second = receipt(2, assignment("static"))
        changed = card(first, second).addressing
        assert changed is not None
        self.assertEqual("dhcp_reserved", changed.previous_mode)
        self.assertEqual("2026-01-02T00:00:00Z", changed.changed_at)
        self.assertTrue(changed.changed_in_latest)

        kept = card(first, second, receipt(3, assignment("static"))).addressing
        assert kept is not None
        self.assertEqual("static", kept.mode)
        self.assertEqual("dhcp_reserved", kept.previous_mode)
        self.assertEqual("2026-01-02T00:00:00Z", kept.changed_at)
        self.assertFalse(kept.changed_in_latest)
        self.assertEqual("2026-01-03T00:00:00Z", kept.observed_at)

    def test_unchanged_mode_records_no_change(self) -> None:
        same = card(
            receipt(1, assignment("static")), receipt(2, assignment("static"))
        ).addressing
        assert same is not None
        self.assertIsNone(same.previous_mode)
        self.assertIsNone(same.changed_at)
        self.assertFalse(same.changed_in_latest)

    def test_change_not_recorded_across_scope_baseline(self) -> None:
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved")),
            receipt(2, assignment("static"), scope="b" * 64),
        )

    def test_change_not_recorded_when_mac_differs(self) -> None:
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved")),
            receipt(2, assignment("static", OTHER_MAC)),
        )
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved", None)),
            receipt(2, assignment("static", None)),
        )

    def test_change_is_dropped_when_another_device_holds_the_address(self) -> None:
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved")),
            receipt(2, assignment("static")),
            receipt(3, assignment("static", OTHER_MAC)),
        )

    def test_change_not_recorded_with_addressing_gap(self) -> None:
        gap = ("addressing_partial:home-dns",)
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved"), issues=gap),
            receipt(2, assignment("static")),
        )
        self.assertNoChange(
            receipt(1, assignment("dhcp_reserved")),
            receipt(2, assignment("static"), issues=gap),
        )

    def test_unknown_mode_never_counts_as_change(self) -> None:
        self.assertNoChange(
            receipt(1, assignment("static")),
            receipt(2, assignment("unknown")),
            receipt(3, assignment("static")),
        )

    def test_newest_collection_decides_even_without_probes(self) -> None:
        self.assertIsNone(
            card(receipt(1, assignment("static")), receipt(2, probed=False)).addressing
        )

    def test_change_followed_by_unknown_is_not_shown(self) -> None:
        self.assertNoChange(*self.changed(), receipt(3, assignment("unknown")))

    def test_change_followed_by_incomparable_other_mode_is_not_shown(self) -> None:
        gap = ("addressing_partial:home-dns",)
        self.assertNoChange(
            *self.changed(), receipt(3, assignment("dhcp_dynamic"), issues=gap)
        )
        self.assertNoChange(
            *self.changed(), receipt(3, assignment("dhcp_dynamic"), scope="b" * 64)
        )
        # Returning to the changed-to mode does not revive the dropped change.
        self.assertNoChange(
            *self.changed(),
            receipt(3, assignment("dhcp_dynamic"), issues=gap),
            receipt(4, assignment("static"), issues=gap),
        )

    def test_change_followed_by_incomparable_same_mode_is_kept(self) -> None:
        gap = ("addressing_partial:home-dns",)
        kept = card(
            *self.changed(), receipt(3, assignment("static"), issues=gap)
        ).addressing
        assert kept is not None
        self.assertEqual("dhcp_reserved", kept.previous_mode)
        self.assertEqual("2026-01-02T00:00:00Z", kept.changed_at)
        self.assertFalse(kept.changed_in_latest)

    def test_change_is_not_shown_again_after_mac_handover(self) -> None:
        self.assertNoChange(
            *self.changed(),
            receipt(3, assignment("static", OTHER_MAC)),
            receipt(4, assignment("static")),
        )

    def test_assignment_is_not_attributed_to_a_newer_device(self) -> None:
        moved = card(*self.changed(), presence=sighted(OTHER_MAC))
        assert moved.identity is not None
        self.assertEqual(OTHER_MAC, moved.identity.mac)
        self.assertIsNone(moved.addressing)
        self.assertIsNone(build_dossier(moved).addressing)

    def test_assignment_is_kept_when_the_same_device_is_sighted(self) -> None:
        same = card(*self.changed(), presence=sighted(MAC))
        assert same.addressing is not None
        self.assertEqual("static", same.addressing.mode)
        self.assertEqual("dhcp_reserved", same.addressing.previous_mode)
        dossier = build_dossier(same)
        self.assertEqual(same.addressing, dossier.addressing)

    def test_collection_without_the_address_clears_the_change(self) -> None:
        self.assertNoChange(
            *self.changed(), receipt(3), receipt(4, assignment("static"))
        )

    def test_overview_json_carries_addressing(self) -> None:
        encoded = json.dumps(asdict(card(receipt(1, assignment("static")))))
        self.assertIn('"addressing": {"mode": "static"', encoded)
        self.assertIn('"addressing": null', json.dumps(asdict(card(receipt(1)))))

    def changed(self) -> tuple[Receipt, Receipt]:
        """A recorded reserved -> static change on day 2."""
        return receipt(1, assignment("dhcp_reserved")), receipt(2, assignment("static"))

    def assertNoChange(self, *receipts: Receipt) -> None:
        latest = card(*receipts).addressing
        assert latest is not None
        self.assertIsNone(latest.previous_mode)
        self.assertIsNone(latest.changed_at)
        self.assertFalse(latest.changed_in_latest)


if __name__ == "__main__":
    unittest.main()
