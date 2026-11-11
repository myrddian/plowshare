"""Quick presence is local, scoped, and independent of full scan publications."""

import asyncio
import errno
import json
import sqlite3
import tempfile
import unittest
from dataclasses import asdict, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

from support import FixturePort, MemoryReceipts, configuration

from plowshare_privacy.collection import NetworkCollector, scope_fingerprint
from plowshare_privacy.contracts import CollectionPlan, DeviceIdentity, DnsObservation
from plowshare_privacy.device_inventory import device_inventory
from plowshare_privacy.dns_activity import QuerySample
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
OTHER_MAC = "02:01:02:03:04:06"
START = datetime(2026, 1, 1, tzinfo=timezone.utc)
PLAN = CollectionPlan(
    "tcp", (A, B, C, D), (443, 80, 22), 1, 4, None, 0, quick_scan=True
)


def at(seconds: int) -> str:
    return (START + timedelta(seconds=seconds)).isoformat()


def identity(address: str, source: str, seen: int, mac: str = MAC) -> DeviceIdentity:
    return DeviceIdentity.decode(
        {
            "address": address,
            "device_id": "mac:" + mac,
            "mac": mac,
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
                ambiguous_addresses=frozenset({D}),
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
        self.assertEqual(sample.ambiguous_addresses, frozenset({D}))

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

    async def test_dns_profiles_follow_the_mac_across_watched_addresses_and_never_publish(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            plan = replace(PLAN, dns_profiles=True)
            config = replace(configuration(root), collection=plan)
            port = FixturePort(config)
            receipts = MemoryReceipts()
            read = AsyncMock(
                side_effect=[
                    (
                        (
                            QuerySample(
                                (
                                    DnsObservation(A, "www.youtube.com", 5),
                                    DnsObservation(A, "ads.doubleclick.net", 2),
                                ),
                                7,
                                7,
                                (),
                                {"ads.doubleclick.net": 2},
                            ),
                        ),
                        (),
                    ),
                    (
                        (
                            QuerySample(
                                (
                                    DnsObservation(B, "ads.doubleclick.net", 3),
                                    DnsObservation(B, "doubleclick.net.example", 1),
                                ),
                                4,
                                9,
                                ("dns_truncated: fixture",),
                                {"ads.doubleclick.net": 1},
                            ),
                        ),
                        ("dns_truncated: fixture",),
                    ),
                ]
            )
            collector = NetworkCollector("fixture", plan, pihole_activity=read)
            clock = 0
            history = PresenceHistory(root)
            watch = PresenceWatch(
                history,
                monotonic=lambda: float(clock),
                clock=lambda: at(clock),
                delay=lambda: 120.0,
            )
            worker = Worker(config, port, receipts, collector, presence=watch)
            with patch.object(
                collector, "collect_presence", new_callable=AsyncMock
            ) as sample:
                sample.side_effect = [
                    PresenceSample(
                        at(120), (Sighting(A, identity(A, "unifi", 120)), Sighting(C))
                    ),
                    PresenceSample(
                        at(240), (Sighting(A, identity(A, "unifi", 240)), Sighting(C))
                    ),
                    PresenceSample(
                        at(360),
                        (
                            Sighting(A, identity(A, "unifi", 360, OTHER_MAC)),
                            Sighting(B, identity(B, "unifi", 360)),
                        ),
                    ),
                    PresenceSample(
                        at(480), (Sighting(B, identity(B, "pihole-v6", 480)),)
                    ),
                ]
                await worker.poll_presence()
                for clock in (120, 240, 360, 480):
                    await worker.poll_presence()
            self.assertEqual(
                [call.args for call in read.await_args_list],
                [((A,), at(120), at(240)), ((B,), at(360), at(480))],
            )
            self.assertEqual(port.published, [])
            self.assertEqual(port.uploads, 0)
            self.assertEqual(receipts.all(), ())
            scope = scope_fingerprint(plan)
            self.assertEqual(scope, scope_fingerprint(PLAN))
            self.assertEqual(history.view(scope, epoch(at(480))).dns, {})
            view = PresenceHistory(root).view(scope, epoch(at(480)), include_dns=True)
            cards = {
                card.address: card
                for card in device_inventory(plan.targets, (), (), view).devices
            }
            self.assertIsNone(cards[A].dns_activity)
            profile = cards[B].dns_activity
            assert profile is not None
            self.assertEqual((profile.windows, profile.partial_windows), (2, 1))
            self.assertEqual(
                (profile.queries_read, profile.queries_available), (11, 16)
            )
            groups = {group.kind: group for group in profile.groups}
            self.assertEqual(groups["streaming"].queries, 5)
            self.assertEqual(groups["advertising"].queries, 5)
            self.assertEqual(groups["advertising"].destinations[0].blocked, 3)
            self.assertEqual(groups["unclassified"].queries, 1)
            self.assertEqual(
                history.view(scope, epoch(at(86400 + 480)), include_dns=True).dns, {}
            )

    async def test_dns_activity_refuses_conflicts_and_reused_addresses(self) -> None:
        plan = replace(PLAN, dns_profiles=True)
        read = AsyncMock()
        collector = NetworkCollector("fixture", plan, pihole_activity=read)
        previous = PresenceSample(
            at(120),
            (
                Sighting(A, identity(A, "unifi", 120)),
                Sighting(B, identity(B, "unifi", 120, OTHER_MAC)),
                Sighting(C),
            ),
        )
        current = PresenceSample(
            at(240),
            (
                Sighting(A, identity(A, "unifi", 240)),
                Sighting(B, identity(B, "unifi", 240)),
                Sighting(C, identity(C, "unifi", 240, OTHER_MAC)),
            ),
            ambiguous_addresses=frozenset({A}),
        )
        self.assertEqual(
            await collector.collect_dns_activity(previous, current), ((), ())
        )
        read.assert_not_awaited()

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

    async def test_first_mac_credits_only_the_recent_uninterrupted_ip_run(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.record("scope", PresenceSample(at(0), (Sighting(A),)))
            history.record("scope", PresenceSample(at(120), ()))
            history.record("scope", PresenceSample(at(240), (Sighting(A), Sighting(B))))
            history.record("scope", PresenceSample(at(360), (Sighting(A), Sighting(B))))
            history.record(
                "scope",
                PresenceSample(
                    at(480),
                    (
                        Sighting(A, identity(A, "pihole-v6", 475)),
                        Sighting(B, identity(B, "unifi", 480)),
                    ),
                ),
            )

            view = PresenceHistory(root).view("scope", epoch(at(481)))
            count = view.counts["mac:" + MAC]
            self.assertEqual(
                (count.seen, count.checked, count.address_only), (3, 5, False)
            )
            self.assertEqual(view.counts["ip:" + A].seen, 1)
            self.assertNotIn("ip:" + B, view.counts)
            inventory = device_inventory(PLAN.targets, (), (), view)
            self.assertEqual([card.presence for card in inventory.devices], [count])

    async def test_dhcp_reuse_does_not_credit_the_ambiguous_run_to_either_mac(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.record(
                "scope", PresenceSample(at(0), (Sighting(A, identity(A, "unifi", 0)),))
            )
            history.record("scope", PresenceSample(at(120), (Sighting(A),)))
            history.record(
                "scope",
                PresenceSample(
                    at(240), (Sighting(A, identity(A, "unifi", 240, OTHER_MAC)),)
                ),
            )
            history.record("scope", PresenceSample(at(360), (Sighting(A),)))
            history.record(
                "scope",
                PresenceSample(
                    at(480), (Sighting(A, identity(A, "pihole-v6", 475, OTHER_MAC)),)
                ),
            )
            view = PresenceHistory(root).view("scope", epoch(at(481)))
            self.assertEqual(view.counts["mac:" + MAC].seen, 1)
            self.assertEqual(view.counts["mac:" + OTHER_MAC].seen, 3)
            self.assertEqual(view.counts["ip:" + A].seen, 1)

    async def test_old_owner_and_reported_conflict_fence_backfill(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.record(
                "scope", PresenceSample(at(0), (Sighting(A, identity(A, "unifi", 0)),))
            )
            later = 24 * 60 * 60
            history.record(
                "scope",
                PresenceSample(
                    at(later + 120),
                    (Sighting(A), Sighting(B)),
                    ambiguous_addresses=frozenset({B}),
                ),
            )
            history.record("scope", PresenceSample(at(later + 240), (Sighting(B),)))
            history.record(
                "scope",
                PresenceSample(
                    at(later + 360),
                    (
                        Sighting(A, identity(A, "unifi", later + 360, OTHER_MAC)),
                        Sighting(B, identity(B, "unifi", later + 360)),
                    ),
                ),
            )
            view = PresenceHistory(root).view("scope", epoch(at(later + 361)))
            self.assertEqual(view.counts["mac:" + OTHER_MAC].seen, 1)
            self.assertEqual(view.counts["mac:" + MAC].seen, 1)
            self.assertEqual(view.counts["ip:" + A].seen, 1)
            self.assertEqual(view.counts["ip:" + B].seen, 2)

    async def test_restart_gap_and_mac_seen_elsewhere_do_not_link_earlier_ip(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.record("scope", PresenceSample(at(0), (Sighting(A), Sighting(C))))
            history.record(
                "scope",
                PresenceSample(
                    at(600),
                    (
                        Sighting(A),
                        Sighting(B, identity(B, "unifi", 600)),
                        Sighting(C, identity(C, "unifi", 600, OTHER_MAC)),
                    ),
                ),
            )
            history.record(
                "scope",
                PresenceSample(at(720), (Sighting(A, identity(A, "unifi", 720)),)),
            )
            view = PresenceHistory(root).view("scope", epoch(at(721)))
            self.assertEqual(view.counts["mac:" + MAC].seen, 2)
            self.assertEqual(view.counts["mac:" + OTHER_MAC].seen, 1)
            self.assertEqual(view.counts["ip:" + A].seen, 2)
            self.assertEqual(view.counts["ip:" + C].seen, 1)

    async def test_old_journal_upgrades_without_guessing_ambiguous_ownership(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            history = PresenceHistory(root)
            history.path.touch(mode=0o600)
            with sqlite3.connect(history.path) as db:
                db.executescript("""
                    CREATE TABLE sweep (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, checked REAL NOT NULL);
                    CREATE TABLE seen (
                        sweep_id INTEGER REFERENCES sweep(id) ON DELETE CASCADE,
                        device TEXT NOT NULL, PRIMARY KEY (sweep_id, device)
                    );
                    CREATE TABLE latest (
                        scope TEXT NOT NULL, address TEXT NOT NULL, identity TEXT,
                        checked REAL NOT NULL, PRIMARY KEY (scope, address)
                    );
                """)
                db.executemany(
                    "INSERT INTO sweep VALUES (?, 'scope', ?)",
                    [(index, epoch(at(index * 120))) for index in range(1, 4)],
                )
                db.executemany(
                    "INSERT INTO seen VALUES (?, ?)",
                    [
                        (1, "ip:" + A),
                        (1, "mac:" + OTHER_MAC),
                        (2, "ip:" + A),
                        (2, "ip:" + B),
                        (3, "mac:" + MAC),
                    ],
                )
                db.executemany(
                    "INSERT INTO latest VALUES ('scope', ?, ?, ?)",
                    [
                        (
                            A,
                            json.dumps(asdict(identity(A, "pihole-v6", 355))),
                            epoch(at(360)),
                        ),
                        (B, None, epoch(at(240))),
                    ],
                )
            self.assertEqual(
                history.view("scope", epoch(at(361))).counts["mac:" + MAC].seen, 1
            )
            history.record(
                "scope",
                PresenceSample(
                    at(480), (Sighting(B, identity(B, "unifi", 480, OTHER_MAC)),)
                ),
            )
            view = PresenceHistory(root).view("scope", epoch(at(481)))
            self.assertEqual(view.counts["mac:" + MAC].seen, 3)
            self.assertEqual(view.counts["mac:" + OTHER_MAC].seen, 2)
            self.assertEqual(view.counts["ip:" + B].seen, 1)
            self.assertNotIn("ip:" + A, view.counts)

    async def test_failed_transport_cannot_turn_a_missing_route_into_an_absence(
        self,
    ) -> None:
        collector = NetworkCollector("fixture", PLAN)
        with patch("asyncio.open_connection", new_callable=AsyncMock) as connect:
            connect.side_effect = OSError(errno.ENETUNREACH, "fixture")
            with self.assertRaises(OSError):
                await asyncio.wait_for(collector.collect_presence(at(0)), 1)
