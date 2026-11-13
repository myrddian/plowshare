import unittest
from dataclasses import replace

from plowshare_privacy.contracts import DeviceIdentity
from plowshare_privacy.identity_merge import PRECEDENCE, merge_identities
from plowshare_privacy.identity_source import IdentitySample

SCOPE = frozenset({"192.0.2.10", "192.0.2.11"})


def identity(
    address: str = "192.0.2.10",
    source: str = "unifi",
    mac: str | None = "aa:bb:cc:00:00:01",
    vendor: str | None = None,
    evidence: str | None = None,
) -> DeviceIdentity:
    return DeviceIdentity(
        address=address,
        device_id=f"{source}:{address}",
        label=None,
        hostname=None,
        mac=mac,
        vendor=vendor,
        source=source,  # type: ignore[arg-type]
        observed_at="2026-01-01T00:00:00Z",
        last_seen=None,
        evidence=evidence,  # type: ignore[arg-type]
    )


class MergeIdentitiesTest(unittest.TestCase):
    def test_higher_precedence_wins_whole_record(self) -> None:
        unifi = identity(source="unifi")
        pihole = identity(source="pihole-v6", vendor="Acme")
        result = merge_identities(
            [IdentitySample((pihole,)), IdentitySample((unifi,))], SCOPE
        )
        self.assertEqual(result.devices, (unifi,))
        self.assertIsNone(result.devices[0].vendor)

    def test_managed_device_beats_controller_client(self) -> None:
        client = identity(source="unifi")
        managed = replace(client, evidence="managed_device")
        result = merge_identities(
            [IdentitySample((client,)), IdentitySample((managed,))], SCOPE
        )
        self.assertEqual(result.devices, (managed,))
        self.assertEqual(PRECEDENCE[0], "managed_device")

    def test_mac_disagreement_conflicts_regardless_of_rank(self) -> None:
        a = identity(source="unifi", mac="aa:bb:cc:00:00:01")
        b = identity(source="pihole-v6", mac="aa:bb:cc:00:00:02")
        result = merge_identities([IdentitySample((a,)), IdentitySample((b,))], SCOPE)
        self.assertEqual(result.devices, ())
        self.assertEqual(result.conflicts, frozenset({"192.0.2.10"}))

    def test_same_mac_in_different_case_is_not_a_conflict(self) -> None:
        a = identity(source="unifi", mac="AA:BB:CC:00:00:01")
        b = identity(source="pihole-v6", mac="aa:bb:cc:00:00:01")
        result = merge_identities([IdentitySample((a,)), IdentitySample((b,))], SCOPE)
        self.assertEqual(result.devices, (a,))
        self.assertEqual(result.conflicts, frozenset())

    def test_ip_only_record_never_conflicts(self) -> None:
        mac = identity(source="pihole-v6")
        dns = identity(source="pihole-v6", mac=None, evidence="local_dns")
        result = merge_identities(
            [IdentitySample((dns,)), IdentitySample((mac,))], SCOPE
        )
        self.assertEqual(result.devices, (mac,))
        self.assertEqual(result.conflicts, frozenset())
        alone = merge_identities([IdentitySample((dns,))], SCOPE)
        self.assertEqual(alone.devices, (dns,))

    def test_ambiguous_addresses_get_no_device(self) -> None:
        record = identity()
        other = identity(address="192.0.2.11")
        result = merge_identities(
            [
                IdentitySample((record, other)),
                IdentitySample(ambiguous_addresses=frozenset({"192.0.2.10"})),
            ],
            SCOPE,
        )
        self.assertEqual(result.devices, (other,))
        self.assertEqual(result.ambiguous, frozenset({"192.0.2.10"}))

    def test_out_of_scope_or_duplicate_rejected(self) -> None:
        with self.assertRaises(ValueError):
            merge_identities(
                [IdentitySample((identity(address="203.0.113.9"),))], SCOPE
            )
        with self.assertRaises(ValueError):
            merge_identities([IdentitySample((identity(), identity()))], SCOPE)


if __name__ == "__main__":
    unittest.main()
