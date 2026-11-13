"""Typed comparison signals and byte-preserving historical receipt compatibility."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from support import CountingCollector, configuration

from plowshare_privacy.contracts import (
    IDENTITY_SOURCES,
    DeviceIdentity,
    Evidence,
    evidence_kind,
)
from plowshare_privacy.journal import FileReceipts, Receipt
from plowshare_privacy.operator_state import FileOperatorStore, Preferences, findings


class EvidenceContractTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = configuration(self.root)
        self.collector = CountingCollector(self.config)
        self.evidence = await self.collector.collect(
            str(uuid4()), str(uuid4()), None, None
        )

    async def test_baseline_is_explicit_and_a_changed_scope_still_starts_a_baseline(
        self,
    ) -> None:
        self.assertIs(self.evidence.baseline, True)
        comparable = await self.collector.collect(
            str(uuid4()), str(uuid4()), self.evidence, str(uuid4())
        )
        self.assertIs(comparable.baseline, False)
        changed_scope = await self.collector.collect(
            str(uuid4()),
            str(uuid4()),
            replace(self.evidence, scope="different"),
            str(uuid4()),
        )
        self.assertIs(changed_scope.baseline, True)
        self.assertIsNotNone(changed_scope.previous_revision)

    async def test_display_wording_cannot_change_policy_or_create_baseline_findings(
        self,
    ) -> None:
        evidence = replace(
            self.evidence,
            changes=("Initial observations; wording changed.",),
            baseline=True,
        )
        receipt = Receipt(
            evidence.scan_id,
            evidence.source_event,
            "schedule.due",
            evidence.started_at,
            evidence=evidence,
        )
        self.assertEqual(findings(receipt, frozenset()), ())
        store = FileOperatorStore(self.root)
        store.set_preferences(Preferences(True, 10, 0, False))
        self.assertTrue(store.decide(replace(evidence, changes=()), ()).requested)
        self.assertFalse(
            store.decide(replace(evidence, changes=(), baseline=False), ()).requested
        )

    async def test_legacy_encoding_is_unchanged_and_new_signal_round_trips(
        self,
    ) -> None:
        legacy = replace(self.evidence, baseline=None)
        encoded = legacy.encode()
        self.assertNotIn("baseline", json.loads(encoded))
        self.assertEqual(Evidence.decode(json.loads(encoded)).encode(), encoded)
        self.assertIsNone(Evidence.decode(json.loads(encoded)).baseline)
        for baseline in (False, True):
            evidence = replace(self.evidence, baseline=baseline)
            self.assertEqual(Evidence.decode(json.loads(evidence.encode())), evidence)

    async def test_legacy_journal_rewrite_and_reload_keeps_the_original_evidence_text(
        self,
    ) -> None:
        evidence = replace(self.evidence, baseline=None)
        receipt = Receipt(
            evidence.scan_id,
            evidence.source_event,
            "schedule.due",
            evidence.started_at,
            evidence=evidence,
        )
        with FileReceipts(self.config) as receipts:
            receipts.save(receipt)
        with FileReceipts(self.config) as receipts:
            restored = receipts.all()[0]
            assert restored.evidence is not None
            self.assertEqual(restored.evidence.encode(), evidence.encode())
            self.assertIsNone(restored.evidence.baseline)

    async def test_invalid_signal_is_refused_instead_of_coerced(self) -> None:
        invalid_values: tuple[object, ...] = (None, "true", 1, [], {})
        for invalid in invalid_values:
            row = json.loads(self.evidence.encode())
            row["baseline"] = invalid
            with self.assertRaises(ValueError):
                Evidence.decode(row)


STAMP = "2026-01-01T00:00:00Z"


def raw_evidence(**device: object) -> dict[str, object]:
    row: dict[str, object] = {
        "address": "192.0.2.10",
        "device_id": "mac:aa:bb:cc:dd:ee:ff",
        "label": None,
        "hostname": "host",
        "mac": "aa:bb:cc:dd:ee:ff",
        "vendor": None,
        "source": "pihole-v6",
        "observed_at": STAMP,
        "last_seen": STAMP,
    }
    row.update(device)
    return {
        "scan_id": str(uuid4()),
        "source_event": "fixture:event",
        "collector": "fixture",
        "mode": "fixture",
        "scope": "fixture",
        "started_at": STAMP,
        "finished_at": STAMP,
        "snapshot": {"observed_at": STAMP, "tcp": [], "dns": [], "devices": [row]},
        "issues": [],
        "changes": [],
        "previous_revision": None,
    }


class IdentityProvenanceTest(unittest.TestCase):
    def test_identity_without_provenance_encodes_exactly_as_before(self) -> None:
        raw = raw_evidence()
        encoded = Evidence.decode(raw).encode()
        self.assertEqual(
            encoded, json.dumps(raw, separators=(",", ":"), allow_nan=False)
        )
        self.assertNotIn('"evidence"', encoded)
        self.assertNotIn('"service"', encoded)

    def test_identity_provenance_round_trips(self) -> None:
        raw = raw_evidence(evidence="dhcp_lease", service="home-dns")
        encoded = Evidence.decode(raw).encode()
        self.assertEqual(
            json.loads(encoded)["snapshot"]["devices"][0]["evidence"], "dhcp_lease"
        )
        self.assertEqual(
            json.loads(encoded)["snapshot"]["devices"][0]["service"], "home-dns"
        )
        self.assertEqual(Evidence.decode(json.loads(encoded)).encode(), encoded)

    def test_identity_provenance_is_validated(self) -> None:
        invalid: tuple[dict[str, object], ...] = (
            {"evidence": "router"},
            {"service": "a b"},
            {"service": "a" * 65},
            {"evidence": "configured"},
            {"evidence": []},
            {"evidence": {}},
            {"source": []},
        )
        for device in invalid:
            with self.subTest(device=device), self.assertRaises(ValueError):
                Evidence.decode(raw_evidence(**device))

    def test_evidence_kind_maps_legacy_sources(self) -> None:
        self.assertEqual(
            IDENTITY_SOURCES, frozenset({"configured", "pihole-v6", "unifi"})
        )
        decoded = {
            source: DeviceIdentity.decode(
                raw_evidence(source=source)["snapshot"]["devices"][0]  # type: ignore[index]
                if source != "configured"
                else {
                    "address": "192.0.2.10",
                    "device_id": "ip:192.0.2.10",
                    "label": "x",
                    "hostname": None,
                    "mac": None,
                    "vendor": None,
                    "source": "configured",
                    "observed_at": STAMP,
                    "last_seen": None,
                }
            )
            for source in ("unifi", "pihole-v6", "configured")
        }
        self.assertEqual(evidence_kind(decoded["unifi"]), "controller_client")
        self.assertEqual(evidence_kind(decoded["pihole-v6"]), "neighbour_table")
        self.assertEqual(evidence_kind(decoded["configured"]), "configured")
        self.assertEqual(
            evidence_kind(replace(decoded["unifi"], evidence="managed_device")),
            "managed_device",
        )
