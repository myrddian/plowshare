"""Typed comparison signals and byte-preserving historical receipt compatibility."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from support import CountingCollector, configuration

from plowshare_privacy.contracts import Evidence
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
