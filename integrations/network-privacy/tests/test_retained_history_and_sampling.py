"""Retained device history survives full scans; backlog never starves quick checks."""

from __future__ import annotations

import asyncio
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from support import FixturePort, MemoryReceipts, configuration

from plowshare_privacy.contracts import (
    DeviceIdentity,
    Evidence,
    Snapshot,
    TcpObservation,
)
from plowshare_privacy.device_inventory import device_inventory
from plowshare_privacy.dns_activity import DnsActivity
from plowshare_privacy.journal import Receipt
from plowshare_privacy.presence import (
    PresenceHistory,
    PresenceSample,
    PresenceView,
    PresenceWatch,
    Sighting,
)
from plowshare_privacy.traffic import TrafficCounter, TrafficVolume
from plowshare_privacy.worker import ReconciliationRequired, Worker

ADDRESS = "192.168.10.10"
MAC = "02:00:00:00:00:01"
OTHER_MAC = "02:00:00:00:00:02"
EARLY = "2026-01-01T00:00:00Z"
LATER = "2026-01-01T00:10:00Z"


def identity(mac: str = MAC) -> DeviceIdentity:
    return DeviceIdentity(
        ADDRESS, "mac:" + mac, None, "fixture", mac, None, "unifi", EARLY, EARLY
    )


def evidence(scan_id: str, source_event: str, device: DeviceIdentity) -> Evidence:
    return Evidence(
        scan_id,
        source_event,
        "fixture",
        "tcp",
        "fixture-scope",
        EARLY,
        LATER,
        Snapshot(LATER, (TcpObservation(ADDRESS, 443, "open"),), (), (device,)),
        (),
        (),
        None,
    )


class RetainedHistoryTest(unittest.TestCase):
    def test_same_confirmed_mac_keeps_both_profiles_when_observation_metadata_changes(
        self,
    ) -> None:
        quick = identity()
        dns = DnsActivity(EARLY, EARLY, 2, 0, 4, 4, ())
        traffic = TrafficVolume(EARLY, EARLY, 2, 1000, 500, 0)
        presence = PresenceView(
            checked_at=EARLY,
            sightings={ADDRESS: Sighting(ADDRESS, quick)},
            dns={quick.device_id: dns},
            traffic={quick.device_id: traffic},
        )
        for current in (
            replace(quick, observed_at=LATER, last_seen=LATER),
            replace(
                quick,
                observed_at=LATER,
                hostname="renamed",
                label="new label",
                source="pihole-v6",
            ),
        ):
            with self.subTest(source=current.source):
                scan = evidence(str(uuid4()), "scheduled:fixture", current)
                receipt = Receipt(
                    scan.scan_id,
                    scan.source_event,
                    "schedule.due",
                    EARLY,
                    "done",
                    scan,
                    str(uuid4()),
                )
                card = device_inventory((ADDRESS,), (), (receipt,), presence).devices[0]
                self.assertEqual(card.identity, current)
                self.assertEqual(card.dns_activity, dns)
                self.assertEqual(card.traffic, traffic)
                assert card.dns_activity is not None
                assert card.traffic is not None
                self.assertEqual(card.dns_activity.until_at, EARLY)
                self.assertEqual(card.traffic.until_at, EARLY)

    def test_reused_or_unconfirmed_address_cannot_inherit_retained_mac_history(
        self,
    ) -> None:
        quick = identity()
        dns = DnsActivity(EARLY, EARLY, 2, 0, 4, 4, ())
        traffic = TrafficVolume(EARLY, EARLY, 2, 1000, 500, 0)
        presence = PresenceView(
            checked_at=EARLY,
            sightings={ADDRESS: Sighting(ADDRESS, quick)},
            dns={quick.device_id: dns},
            traffic={quick.device_id: traffic},
        )
        for current in (
            replace(identity(OTHER_MAC), observed_at=LATER),
            DeviceIdentity(
                ADDRESS,
                "ip:" + ADDRESS,
                None,
                None,
                None,
                None,
                "configured",
                LATER,
                None,
            ),
        ):
            with self.subTest(mac=current.mac):
                scan = evidence(str(uuid4()), "scheduled:fixture", current)
                receipt = Receipt(
                    scan.scan_id,
                    scan.source_event,
                    "schedule.due",
                    EARLY,
                    "done",
                    scan,
                    str(uuid4()),
                )
                card = device_inventory((ADDRESS,), (), (receipt,), presence).devices[0]
                self.assertIsNone(card.dns_activity)
                self.assertIsNone(card.traffic)


class SlowCollector:
    """A read-only full scan parked while bounded quick samples can progress."""

    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.settled = asyncio.Event()
        self.sampled_twice = asyncio.Event()
        self.scans: list[str] = []
        self.samples = 0

    async def collect(
        self,
        scan_id: str,
        source_event: str,
        previous: Evidence | None,
        previous_revision: str | None,
    ) -> Evidence:
        self.scans.append(scan_id)
        self.started.set()
        try:
            await self.release.wait()
            return evidence(scan_id, source_event, identity())
        finally:
            self.settled.set()

    async def collect_presence(self, since: str) -> PresenceSample:
        self.samples += 1
        if self.samples == 2:
            self.sampled_twice.set()
        return PresenceSample(
            EARLY,
            (Sighting(ADDRESS, identity()),),
            traffic=(
                TrafficCounter(
                    "mac:" + MAC, ADDRESS, self.samples * 1000, self.samples * 500
                ),
            ),
        )


class BacklogSamplingTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        original = configuration(self.root)
        self.config = replace(
            original,
            collection=replace(
                original.collection, mode="tcp", targets=(ADDRESS,), quick_scan=True
            ),
        )
        self.clock = 0.0
        self.watch = PresenceWatch(
            PresenceHistory(self.root),
            monotonic=lambda: self.clock,
            clock=lambda: EARLY,
            delay=lambda: 120.0,
        )
        self.collector = SlowCollector()
        self.port = FixturePort(self.config)
        self.receipts = MemoryReceipts()
        for index in range(2):
            self.receipts.save(
                Receipt(str(uuid4()), f"scheduled:{index}", "schedule.due", EARLY)
            )
        self.worker = Worker(
            self.config, self.port, self.receipts, self.collector, presence=self.watch
        )
        real_wait = asyncio.wait

        async def advance(
            tasks: tuple[asyncio.Task[Evidence], ...], *, timeout: float
        ) -> tuple[set[asyncio.Task[Evidence]], set[asyncio.Task[Evidence]]]:
            self.clock += timeout
            return await real_wait(tasks, timeout=0)

        self.wait_patch = patch(
            "plowshare_privacy.worker.asyncio.wait", side_effect=advance
        )
        self.wait_patch.start()
        self.addCleanup(self.wait_patch.stop)

    async def test_quick_checks_progress_during_a_slow_scan_and_each_pass_finishes_one_scan(
        self,
    ) -> None:
        pending = asyncio.create_task(self.worker.poll())
        self.addAsyncCleanup(self.cancel, pending)
        await asyncio.wait_for(self.collector.sampled_twice.wait(), 2)
        self.assertFalse(pending.done())
        self.assertTrue(self.worker.lock.locked())
        self.assertGreaterEqual(self.collector.samples, 2)
        self.assertEqual(self.port.uploads, 0)
        self.assertEqual(self.port.published, [])
        self.assertEqual(
            [item.phase for item in self.receipts.all()], ["queued", "queued"]
        )
        self.collector.release.set()
        await pending
        self.assertEqual(len(self.collector.scans), 1)
        self.assertEqual(
            [item.phase for item in self.receipts.all()], ["done", "queued"]
        )
        self.assertEqual(self.port.uploads, 1)
        self.assertEqual(len(self.port.published), 1)
        await self.worker.poll()
        self.assertEqual(len(self.collector.scans), 2)
        self.assertEqual([item.phase for item in self.receipts.all()], ["done", "done"])
        self.assertEqual(self.port.uploads, 2)
        self.assertEqual(len(self.port.published), 2)

    async def test_cancellation_joins_collection_before_releasing_scope_and_never_publishes(
        self,
    ) -> None:
        pending = asyncio.create_task(self.worker.poll())
        await asyncio.wait_for(self.collector.started.wait(), 2)
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        self.assertTrue(self.collector.settled.is_set())
        self.assertFalse(self.worker.lock.locked())
        self.assertEqual(self.port.uploads, 0)
        self.assertEqual(self.port.published, [])
        self.assertTrue(all(item.phase == "queued" for item in self.receipts.all()))

    async def test_unknown_completion_fences_backlog_without_sampling_or_resending(
        self,
    ) -> None:
        first = self.receipts.all()[0]
        self.receipts.save(replace(first, phase="publishing"))
        with self.assertRaises(ReconciliationRequired):
            await self.worker.poll()
        self.assertEqual(self.collector.scans, [])
        self.assertEqual(self.collector.samples, 0)
        self.assertEqual(self.port.uploads, 0)
        self.assertEqual(self.port.published, [])

    @staticmethod
    async def cancel(task: asyncio.Task[None]) -> None:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
