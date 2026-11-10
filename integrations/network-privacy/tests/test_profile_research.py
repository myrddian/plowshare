"""Background drafts use retained evidence and receipt-only recovery."""

import asyncio
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import AsyncMock

from plowshare import Delivery, TransportError
from support import MemoryReceipts

from plowshare_privacy.advisories import Advisory, AdvisoryLookup, AdvisoryPage
from plowshare_privacy.contracts import DeviceIdentity
from plowshare_privacy.identity_source import ManagedProduct, ManagedProductLookup
from plowshare_privacy.presence import PresenceCount, PresenceView, Sighting
from plowshare_privacy.profile_research import (
    DAILY_LIMIT,
    ProfileDraft,
    ProfileResearch,
    ResearchJournal,
    ResearchOutcome,
    ResearchPort,
)

STAMP = "2026-01-01T00:00:00Z"
ADDRESS = "192.168.8.20"
MAC = "02:00:00:00:00:01"
MODEL = "Fixture Switch X100"
CVE = "CVE-2026-1234"
IDENTITY = DeviceIdentity(
    ADDRESS,
    "mac:" + MAC,
    None,
    "private-name.lan",
    MAC,
    "Example NIC",
    "unifi",
    STAMP,
    STAMP,
)
DRAFT = ProfileDraft(
    "Office switch",
    "Fixture Switch X100",
    "Switching",
    "",
    "Verify firmware",
    "Can you confirm the public model?",
)


class ProfileResearchTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.journal = ResearchJournal(Path(directory.name).resolve())
        self.port = AsyncMock(spec=ResearchPort)
        self.port.start.return_value = "orc_profile_fixture"
        self.port.receipt.return_value = "orc_profile_fixture"
        self.port.outcome.return_value = ResearchOutcome(
            "finished", json.dumps(asdict(DRAFT))
        )
        self.advisories = AsyncMock(spec=AdvisoryLookup)
        self.advisories.search.return_value = AdvisoryPage(
            MODEL,
            STAMP,
            1,
            (Advisory(CVE, "Fixture advisory", "2026-01-01", "HIGH", False),),
        )
        self.products = AsyncMock(spec=ManagedProductLookup)
        self.products.managed_product.return_value = None
        self.presence = self.view(IDENTITY, 3)
        self.receipts = MemoryReceipts()
        self.service = self.restart()

    @staticmethod
    def view(identity: DeviceIdentity, seen: int) -> PresenceView:
        sighting = Sighting(identity.address, identity)
        return PresenceView(
            STAMP,
            3,
            {identity.address: sighting},
            {sighting.key: PresenceCount(seen, 3, STAMP, False, STAMP)},
        )

    def restart(self) -> ProfileResearch:
        return ProfileResearch(
            self.journal,
            self.port,
            lambda: self.presence,
            self.receipts,
            lambda: self.products,
            self.advisories,
            clock=lambda: STAMP,
        )

    async def test_opt_in_waits_for_stable_mac_and_researches_confirmed_product(
        self,
    ) -> None:
        await self.service.poll()
        self.port.start.assert_not_awaited()
        self.service.enable(True)
        self.presence = self.view(IDENTITY, 2)
        await self.service.poll()
        self.port.start.assert_not_awaited()

        self.presence = self.view(IDENTITY, 3)
        self.products.managed_product.return_value = ManagedProduct(
            "Fixture", "Switch X100", "1.2.3", True
        )
        await self.service.poll()
        self.advisories.search.assert_awaited_once_with(MODEL)
        first = self.service.ledger.entries[0]
        self.assertEqual(first.phase, "running")
        evidence = self.port.start.await_args_list[0].args[1]
        self.assertEqual(evidence.nvd.advisories[0].cve, CVE)
        self.assertEqual(evidence.open_tcp_ports, ())
        payload = json.dumps(asdict(evidence))
        self.assertNotIn(ADDRESS, payload)
        self.assertNotIn(MAC, payload)
        await self.service.poll()
        draft = self.service.draft_for_editor(first.request_id)
        self.assertEqual(draft.draft, DRAFT)
        self.service.review(first.request_id, "reviewed", None, "")
        await self.service.poll()
        self.port.start.assert_awaited_once()
        self.assertEqual(self.receipts.all(), ())

    async def test_hypothesis_stays_private_until_operator_confirms_model(self) -> None:
        self.service.enable(True)
        await self.service.poll()
        await self.service.poll()
        before = self.service.ledger.entries[0]
        self.assertIsNone(before.model)
        self.advisories.search.assert_not_awaited()
        with self.assertRaises(ValueError):
            self.service.review(before.request_id, "research", "private-name.lan", "")
        self.service.review(
            before.request_id, "research", MODEL, "The switch label confirms it."
        )
        await self.service.poll()
        self.advisories.search.assert_awaited_once_with(MODEL)
        args = self.port.start.await_args_list[-1].args
        self.assertNotEqual(args[0], before.request_id)
        self.assertEqual(args[1].feedback, "The switch label confirms it.")

    async def test_operator_can_withdraw_a_previously_confirmed_model(self) -> None:
        self.service.enable(True)
        self.products.managed_product.return_value = ManagedProduct(
            "Fixture", "Switch X100", "1.2.3", True
        )
        await self.service.poll()
        await self.service.poll()
        entry = self.service.ledger.entries[0]
        self.service.review(
            entry.request_id, "research", None, "The reported model is wrong."
        )
        await self.service.poll()
        self.advisories.search.assert_awaited_once_with(MODEL)
        self.assertIsNone(self.port.start.await_args_list[-1].args[1].public_model)
        self.assertIsNone(self.service.ledger.entries[0].model)

    async def test_unknown_start_reconciles_by_receipt_after_restart(self) -> None:
        self.service.enable(True)
        self.port.start.side_effect = TransportError(Delivery.UNKNOWN, "fixture")
        await self.service.poll()
        pending = self.service.ledger.entries[0]
        self.assertEqual(pending.phase, "pending")
        self.service = self.restart()
        self.service.enable(False)
        await self.service.poll()
        self.port.receipt.assert_awaited_once_with(pending.request_id)
        await self.service.poll()
        self.assertEqual(self.service.entry(pending.request_id).phase, "review")
        self.port.start.assert_awaited_once()

    async def test_reused_address_cannot_apply_or_research_a_stale_draft(self) -> None:
        self.service.enable(True)
        await self.service.poll()
        await self.service.poll()
        entry = self.service.ledger.entries[0]
        new_mac = "02:00:00:00:00:02"
        self.presence = self.view(
            replace(IDENTITY, mac=new_mac, device_id="mac:" + new_mac), 3
        )
        with self.assertRaises(ValueError):
            self.service.draft_for_editor(entry.request_id)
        with self.assertRaises(ValueError):
            self.service.review(entry.request_id, "research", MODEL, "")
        self.service.review(entry.request_id, "dismissed", None, "")
        self.assertEqual(self.service.entry(entry.request_id).phase, "dismissed")

    async def test_invalid_model_output_and_daily_limit(self) -> None:
        self.service.enable(True)
        self.port.outcome.return_value = ResearchOutcome(
            "finished",
            json.dumps(asdict(replace(DRAFT, notes="Unsupported claim " + CVE))),
        )
        for index in range(DAILY_LIMIT + 1):
            mac = f"02:00:00:00:01:{index:02x}"
            self.presence = self.view(
                replace(IDENTITY, mac=mac, device_id="mac:" + mac), 3
            )
            await self.service.poll()
            await self.service.poll()
        self.assertEqual(self.port.start.await_count, DAILY_LIMIT)
        self.assertTrue(
            all(entry.phase == "failed" for entry in self.service.ledger.entries)
        )

    async def test_invalid_private_journal_fails_closed_without_stopping_collector(
        self,
    ) -> None:
        self.journal.path.write_text("invalid retained file")
        self.journal.path.chmod(0o600)
        self.service = self.restart()
        self.assertIsNotNone(self.service.error)
        await self.service.run(asyncio.Event())
        with self.assertRaises(ValueError):
            self.service.enable(True)
        self.assertEqual(self.journal.path.read_text(), "invalid retained file")
        self.port.start.assert_not_awaited()
