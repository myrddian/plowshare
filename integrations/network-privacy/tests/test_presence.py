"""Quick presence is local, scoped, and independent of full scan publications."""

import asyncio
import errno
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

from support import FixturePort, MemoryReceipts, configuration

from plowshare_privacy.collection import NetworkCollector, scope_fingerprint
from plowshare_privacy.contracts import CollectionPlan, DeviceIdentity
from plowshare_privacy.device_inventory import device_inventory
from plowshare_privacy.identity_source import IdentitySample
from plowshare_privacy.presence import (
    PresenceHistory,
    PresenceSample,
    PresenceWatch,
    Sighting,
    epoch,
)
from plowshare_privacy.worker import Worker

A, B, C, D = ("192.168.10.1", "192.168.10.2", "192.168.10.3", "192.168.10.4")
MAC = "02:01:02:03:04:05"
START = datetime(2026, 1, 1, tzinfo=timezone.utc)
PLAN = CollectionPlan(
    "tcp", (A, B, C, D), (443, 80, 22), 1, 4, None, 0, quick_scan=True
)


def at(seconds: int) -> str:
    return (START + timedelta(seconds=seconds)).isoformat()


def identity(address: str, source: str, seen: int) -> DeviceIdentity:
    return DeviceIdentity.decode(
        {
            "address": address,
            "device_id": "mac:" + MAC,
            "mac": MAC,
            "label": None,
            "hostname": "fixture-device",
            "vendor": None,
            "source": source,
            "observed_at": at(300),
            "last_seen": at(seen),
        }
    )


class Controller:
    async def identify(self, targets: tuple[str, ...], observed: str) -> IdentitySample:
        return IdentitySample(devices=(identity(A, "unifi", 300),))


class PresenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_sources_use_current_window_and_only_probe_unseen_watched_addresses(
        self,
    ) -> None:
        pihole = AsyncMock(
            return_value=IdentitySample(
                devices=(
                    identity(B, "pihole-v6", 200),
                    identity(C, "pihole-v6", 120),
                    identity("192.168.99.1", "pihole-v6", 200),
                ),
                issues=("identity_partial: sample omitted an invalid row",),
            )
        )
        collector = NetworkCollector(
            "fixture", PLAN, identity_source=Controller(), pihole_presence=pihole
        )
        with (
            patch("plowshare_privacy.collection.utc_now", return_value=at(300)),
            patch(
                "plowshare_privacy.collection._quick_connect", new_callable=AsyncMock
            ) as connect,
        ):
            connect.side_effect = lambda address, port, timeout: address == C
            sample = await collector.collect_presence(at(120))
        pihole.assert_awaited_once_with((B, C, D), at(300))
        self.assertEqual({item.address for item in sample.sightings}, {A, B, C})
        self.assertIsNone(
            next(item for item in sample.sightings if item.address == C).identity
        )
        self.assertEqual(
            {tuple(call.args[:2]) for call in connect.await_args_list},
            {(C, 443), (D, 443), (D, 80)},
        )
        self.assertIn("identity_partial", sample.issues[0])

    async def test_worker_jitters_after_completion_excludes_failures_and_never_publishes(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            config = replace(configuration(root), collection=PLAN)
            port = FixturePort(config)
            receipts = MemoryReceipts()
            collector = NetworkCollector("fixture", PLAN)
            clock = 0
            delays = iter([120.0, 300.0, 180.0])
            watch = PresenceWatch(
                PresenceHistory(root),
                monotonic=lambda: float(clock),
                clock=lambda: at(clock),
                delay=lambda: next(delays),
            )
            worker = Worker(config, port, receipts, collector, presence=watch)
            with patch.object(
                collector, "collect_presence", new_callable=AsyncMock
            ) as sample:
                sample.side_effect = [
                    PresenceSample(at(120), (Sighting(A),)),
                    OSError(errno.ENETUNREACH, "fixture"),
                ]
                await worker.poll_presence()
                clock = 119
                await worker.poll_presence()
                sample.assert_not_awaited()
                clock = 120
                await worker.poll_presence()
                self.assertEqual(watch.due, 420)
                self.assertEqual(worker.presence_view().checks, 1)
                clock = 420
                await worker.poll_presence()
                self.assertEqual(worker.presence_view().checks, 1)
                self.assertIn("excluded", watch.issues[0])
                worker.config = replace(
                    config, collection=replace(PLAN, quick_scan=False)
                )
                clock = 610
                await worker.poll_presence()
                self.assertIsNone(watch.due)
                self.assertEqual(sample.await_count, 2)
            self.assertEqual(port.published, [])
            self.assertEqual(port.uploads, 0)
            self.assertEqual(receipts.all(), ())
            self.assertEqual(
                scope_fingerprint(PLAN), scope_fingerprint(worker.config.collection)
            )

    async def test_history_survives_restart_groups_mac_moves_and_expires_old_checks(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.record(
                "scope",
                PresenceSample(
                    at(300),
                    (
                        Sighting(A, identity(A, "unifi", 300)),
                        Sighting(B, identity(B, "unifi", 300)),
                    ),
                ),
            )
            history.record("scope", PresenceSample(at(450), ()))
            history.record(
                "scope",
                PresenceSample(at(600), (Sighting(C, identity(C, "unifi", 300)),)),
            )
            view = PresenceHistory(root).view("scope", epoch(at(601)))
            self.assertEqual(set(view.sightings), {C})
            counts = view.counts["mac:" + MAC]
            self.assertEqual((counts.seen, counts.checked), (2, 3))
            result = device_inventory(PLAN.targets, (), (), view)
            self.assertEqual([device.address for device in result.devices], [C])
            self.assertEqual(result.devices[0].presence, counts)
            self.assertEqual({item.address for item in result.candidates}, {A, B, D})
            self.assertEqual(history.view("another scope", epoch(at(601))).checks, 0)
            self.assertEqual(history.view("scope", epoch(at(86400 + 500))).checks, 1)
            expired = history.view("scope", epoch(at(86400 + 601)))
            self.assertEqual(expired.checks, 0)
            self.assertEqual(set(expired.sightings), {C})
            self.assertEqual(
                expired.counts["mac:" + MAC].last_seen,
                view.counts["mac:" + MAC].last_seen,
            )

    async def test_failed_transport_cannot_turn_a_missing_route_into_an_absence(
        self,
    ) -> None:
        collector = NetworkCollector("fixture", PLAN)
        with patch("asyncio.open_connection", new_callable=AsyncMock) as connect:
            connect.side_effect = OSError(errno.ENETUNREACH, "fixture")
            with self.assertRaises(OSError):
                await asyncio.wait_for(collector.collect_presence(at(0)), 1)
