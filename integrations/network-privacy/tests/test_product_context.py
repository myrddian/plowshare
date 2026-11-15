"""Local checks use current monitored identity and cannot take model-selected targets."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

from support import configuration
from test_device_dossier import ADDRESS, MAC, card, facts, identity

from plowshare_privacy.device_inventory import DeviceInventory
from plowshare_privacy.dossier_port import DossierRetention, RetainedDossier
from plowshare_privacy.identity_source import DeviceIdentitySource, IdentitySample
from plowshare_privacy.product_context import CurrentProductContext
from plowshare_privacy.product_probe import ProbeObservation, ProductProbe
from plowshare_privacy.reference_requests import ReferenceRequest
from plowshare_privacy.worker import Worker

REVISION = "11111111-1111-4111-8111-111111111111"
REQUEST = "22222222-2222-4222-8222-222222222222"
STAMP = "2026-11-15T00:00:00Z"


class ProductContextTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.worker = MagicMock(spec=Worker)
        config = configuration(Path(temporary.name).resolve())
        self.worker.config = replace(
            config,
            collection=replace(config.collection, mode="tcp", targets=(ADDRESS,)),
        )
        self.worker.device_inventory.return_value = DeviceInventory((card(),))
        self.worker.device_facts.return_value = facts()
        self.dossiers = AsyncMock(spec=DossierRetention)
        self.dossiers.retain.return_value = RetainedDossier(
            "mac:" + MAC, REVISION, REVISION, False
        )
        self.probe = AsyncMock(spec=ProductProbe)
        self.probe.inspect.return_value = (
            ProbeObservation("http_root", STAMP, "http_response", 200),
        )
        self.identities = AsyncMock(spec=DeviceIdentitySource)
        self.identities.identify.return_value = IdentitySample((identity(),))
        self.context = CurrentProductContext(
            self.worker, self.dossiers, self.probe, lambda: self.identities
        )
        self.request = ReferenceRequest(
            REQUEST,
            "sony-tv",
            "What is port 80?",
            "mac:" + MAC,
            "pending",
            STAMP,
            None,
            (),
        )

    async def test_explicit_check_is_confirmed_before_and_after_and_dossier_is_retained(
        self,
    ) -> None:
        result = await self.context(self.request, True)
        self.assertEqual(REVISION, result.dossier)
        self.assertEqual(1, len(result.probes))
        self.probe.inspect.assert_awaited_once_with(ADDRESS, True)
        self.assertEqual(2, self.identities.identify.await_count)
        self.dossiers.retain.assert_awaited_once()
        self.assertNotIn(ADDRESS, repr(result))

    async def test_research_without_checks_sends_no_device_requests(self) -> None:
        result = await self.context(self.request, False)
        self.assertEqual((), result.probes)
        self.probe.inspect.assert_not_awaited()
        self.identities.identify.assert_not_awaited()

    async def test_unconfirmed_address_causes_no_probe(self) -> None:
        for sample in (
            IdentitySample(),
            IdentitySample((identity(),), frozenset({ADDRESS})),
            IdentitySample((replace(identity(), mac="02:00:00:00:00:02"),)),
        ):
            self.identities.identify.return_value = sample
            with self.assertRaises(ValueError):
                await self.context(self.request, True)
        self.probe.inspect.assert_not_awaited()
        self.dossiers.retain.assert_not_awaited()

    async def test_changed_identity_after_probe_is_not_retained(self) -> None:
        self.identities.identify.side_effect = [
            IdentitySample((identity(),)),
            IdentitySample(),
        ]
        with self.assertRaises(ValueError):
            await self.context(self.request, True)
        self.dossiers.retain.assert_not_awaited()

    async def test_unknown_or_out_of_scope_device_is_rejected(self) -> None:
        self.worker.device_inventory.return_value = DeviceInventory()
        with self.assertRaises(ValueError):
            await self.context(self.request, True)
        self.probe.inspect.assert_not_awaited()
        self.worker.device_inventory.return_value = DeviceInventory((card(),))
        self.worker.config = replace(
            self.worker.config,
            collection=replace(self.worker.config.collection, targets=()),
        )
        with self.assertRaises(ValueError):
            await self.context(self.request, True)
        self.probe.inspect.assert_not_awaited()
