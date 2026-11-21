"""Per-scan evidence files, the small index and the settled-scan retention window."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import asdict, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import NAMESPACE_URL, uuid4, uuid5

from support import (
    CountingCollector,
    FixturePort,
    configuration,
    event,
)

from plowshare_privacy.contracts import (
    Configuration,
    DeviceIdentity,
    Evidence,
    Snapshot,
    TcpObservation,
)
from plowshare_privacy.device_inventory import device_inventory
from plowshare_privacy.journal import FileReceipts, Publication, Receipt
from plowshare_privacy.scan_retention import CarriedAddress, carry_forward
from plowshare_privacy.worker import Worker

ADDRESS = "192.0.2.12"
MAC = "aa:bb:cc:dd:ee:ff"
START = datetime(2026, 1, 1, tzinfo=timezone.utc)


def stamp(day: float) -> str:
    return (START + timedelta(days=day)).isoformat().replace("+00:00", "Z")


def identity(day: float) -> DeviceIdentity:
    return DeviceIdentity(
        ADDRESS, "mac:" + MAC, None, "camera", MAC, None, "unifi", stamp(day), None
    )


def receipt(
    day: float,
    status: str = "open",
    *,
    phase: str = "done",
    identified: bool = True,
    source_event: str | None = None,
) -> Receipt:
    scan, source = str(uuid4()), source_event or "schedule:" + str(uuid4())
    statuses = {
        "open": TcpObservation(ADDRESS, 443, "open"),
        "closed": TcpObservation(ADDRESS, 443, "closed"),
        "timeout": TcpObservation(ADDRESS, 443, "timeout"),
    }
    evidence = Evidence(
        scan,
        source,
        "fixture",
        "tcp",
        "a" * 64,
        stamp(day),
        stamp(day),
        Snapshot(
            stamp(day),
            (statuses[status],),
            (),
            (identity(day),) if identified else (),
        ),
        (),
        (),
        None,
    )
    decoded = Receipt.decode(
        {
            "scan_id": scan,
            "source_event": source,
            "source_topic": "schedule.due",
            "occurred_at": stamp(day),
            "phase": phase,
            "evidence": json.loads(evidence.encode()),
            "revision": str(uuid4()),
        }
    )
    return decoded


class Clock:
    def __init__(self, day: float) -> None:
        self.day = day

    def __call__(self) -> datetime:
        return START + timedelta(days=self.day)


class ScanRetentionTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.config: Configuration = configuration(Path(temporary.name))
        self.state = self.config.state_directory
        self.clock = Clock(0)
        # The production floor keeps the newest scans whatever their age; these
        # tests exercise the age rule with a floor of one.
        floor = patch("plowshare_privacy.journal.MIN_RETAINED_SCANS", 1)
        floor.start()
        self.addCleanup(floor.stop)

    def journal(self) -> FileReceipts:
        return FileReceipts(self.config, clock=self.clock)

    def test_evidence_is_written_once_and_later_saves_rewrite_only_the_index(
        self,
    ) -> None:
        first = replace(receipt(0), phase="uploaded")
        with self.journal() as receipts:
            receipts.save(first)
            path = self.state / "evidence" / (first.scan_id + ".json")
            assert first.evidence is not None
            self.assertEqual(path.read_text(), first.evidence.encode())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            written = path.stat().st_ino
            receipts.save(replace(first, phase="publishing"))
            receipts.save(replace(first, phase="done"))
            self.assertEqual(path.stat().st_ino, written)
        index = json.loads((self.state / "receipts.json").read_text())
        self.assertEqual(index["version"], 3)
        self.assertNotIn("snapshot", json.dumps(index))
        self.assertLess((self.state / "receipts.json").stat().st_size, 4096)
        with self.journal() as reopened:
            self.assertEqual(reopened.all(), (replace(first, phase="done"),))

    def test_inline_journal_migrates_and_keeps_the_original_file(self) -> None:
        old, kept = receipt(0), receipt(0.5)
        with self.journal() as receipts:
            identity_hash, scope = receipts.identity, receipts.collection_scope
        legacy = json.dumps(
            {
                "version": 2,
                "identity": identity_hash,
                "collectionScope": scope,
                "nextCollectionScope": None,
                "receipts": [
                    {
                        **asdict(item),
                        "evidence": json.loads(item.evidence.encode())
                        if item.evidence
                        else None,
                    }
                    for item in (old, kept)
                ],
                "publications": [],
            }
        ).encode()
        (self.state / "receipts.json").write_bytes(legacy)
        with self.journal() as receipts:
            self.assertEqual(receipts.all(), (old, kept))
        self.assertEqual(
            (self.state / "receipts.before-split.json").read_bytes(), legacy
        )
        self.assertEqual(
            json.loads((self.state / "receipts.json").read_text())["version"], 3
        )
        with self.journal() as reopened:
            self.assertEqual(reopened.all(), (old, kept))

    def test_settled_scans_past_the_window_leave_with_their_last_seen_carried(
        self,
    ) -> None:
        opened, closed, silent, newest = (
            receipt(0, "open"),
            receipt(1, "closed"),
            receipt(2, "timeout", identified=False),
            receipt(9.5, "timeout", identified=False),
        )
        with self.journal() as receipts:
            for item in (opened, closed, silent):
                receipts.save(item)
            self.assertEqual(len(receipts.all()), 3)  # All inside seven days.
            self.clock.day = 9.5
            receipts.save(newest)
            self.assertEqual(receipts.all(), (newest,))
            for gone in (opened, closed, silent):
                self.assertTrue(receipts.archived(gone.scan_id))
                self.assertFalse(
                    (self.state / "evidence" / (gone.scan_id + ".json")).exists()
                )
            self.assertFalse(receipts.archived(newest.scan_id))
            expected = (CarriedAddress(ADDRESS, stamp(0), stamp(1), identity(1), True),)
            self.assertEqual(receipts.carried(), expected)
        with self.journal() as reopened:
            self.assertEqual(reopened.all(), (newest,))
            self.assertEqual(reopened.carried(), expected)
            self.assertTrue(reopened.archived(opened.scan_id))
            # A retained inventory still knows the device and when it last answered.
            (card,) = device_inventory(
                (ADDRESS,), (), reopened.all(), None, reopened.carried()
            ).devices
            self.assertEqual(card.first_response_at, stamp(0))
            self.assertEqual(card.last_response_at, stamp(1))
            self.assertEqual(card.last_known_identity, identity(1))
            self.assertEqual(card.last_seen, stamp(9.5))

    def test_unsettled_work_and_the_newest_scans_are_never_evicted(self) -> None:
        stuck = receipt(0, phase="publishing")
        old_one, old_two = receipt(1), receipt(2)
        with (
            patch("plowshare_privacy.journal.MIN_RETAINED_SCANS", 2),
            self.journal() as receipts,
        ):
            for item in (stuck, old_one, old_two):
                receipts.save(item)
            self.clock.day = 60
            receipts.save(replace(old_two, phase="done"))
            # Everything is far older than the window, yet the unknown outcome
            # and the two newest settled scans stay.
            self.assertEqual(receipts.all(), (stuck, old_one, old_two))
            self.assertEqual(receipts.carried(), ())

    def test_a_published_request_leaves_with_its_scan(self) -> None:
        request = str(uuid4())
        requested = receipt(0, source_event=request)
        newest = receipt(9)
        with self.journal() as receipts:
            receipts.save_publication(Publication(request, stamp(0), "published"))
            pending = Publication(str(uuid4()), stamp(0), "pending")
            receipts.save_publication(pending)
            receipts.save(requested)
            self.clock.day = 9
            receipts.save(newest)
            self.assertEqual(receipts.all(), (newest,))
            self.assertEqual(receipts.publications(), (pending,))

    def test_damaged_or_missing_evidence_refuses_the_journal(self) -> None:
        item = receipt(0)
        with self.journal() as receipts:
            receipts.save(item)
        path = self.state / "evidence" / (item.scan_id + ".json")
        original = path.read_bytes()
        path.write_bytes(original.replace(b'"open"', b'"closed"'))
        with self.assertRaises(ValueError):
            self.journal()
        path.unlink()
        with self.assertRaises(ValueError):
            self.journal()
        path.write_bytes(original)
        with self.journal() as receipts:
            self.assertEqual(receipts.all(), (item,))

    def test_an_unreferenced_evidence_file_from_an_interrupted_write_is_removed(
        self,
    ) -> None:
        with self.journal() as receipts:
            receipts.save(receipt(0))
        orphan = self.state / "evidence" / (str(uuid4()) + ".json")
        orphan.write_text("{}")
        with self.journal() as receipts:
            self.assertEqual(len(receipts.all()), 1)
        self.assertFalse(orphan.exists())


class CarryForwardTest(unittest.TestCase):
    def test_carry_follows_the_inventory_rules_and_stays_bounded(self) -> None:
        carried: tuple[CarriedAddress, ...] = ()
        for item in (
            receipt(0, "timeout", identified=False),
            receipt(1, "closed", identified=False),
            receipt(2, "open"),
            receipt(3, "timeout", identified=False),
        ):
            assert item.evidence is not None
            carried = carry_forward(carried, item.evidence)
        self.assertEqual(
            carried, (CarriedAddress(ADDRESS, stamp(1), stamp(2), identity(2), True),)
        )
        with patch("plowshare_privacy.scan_retention.MAX_CARRIED_ADDRESSES", 1):
            other = receipt(4)
            assert other.evidence is not None
            moved = replace(
                other.evidence,
                snapshot=replace(
                    other.evidence.snapshot,
                    tcp=(TcpObservation("192.0.2.99", 443, "open"),),
                    devices=(),
                ),
            )
            # The address that answered longest ago makes room.
            self.assertEqual(
                carry_forward(carried, moved),
                (CarriedAddress("192.0.2.99", stamp(4), stamp(4), None, True),),
            )

    def test_carried_summary_round_trips_and_rejects_invalid_rows(self) -> None:
        value = CarriedAddress(ADDRESS, stamp(0), stamp(1), identity(1), True)
        self.assertEqual(CarriedAddress.decode(value.encode()), value)
        for invalid in (
            {**value.encode(), "address": "not-an-address"},
            {**value.encode(), "open_service_observed": "yes"},
            {**value.encode(), "last_response_at": "yesterday"},
            {**value.encode(), "extra": 1},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                CarriedAddress.decode(invalid)

    def test_a_carried_device_is_not_demoted_to_a_candidate(self) -> None:
        quiet = receipt(9, "timeout", identified=False)
        without = device_inventory((ADDRESS,), (), (quiet,))
        self.assertEqual((len(without.devices), len(without.candidates)), (0, 1))
        carried = (CarriedAddress(ADDRESS, stamp(0), stamp(1), None, True),)
        kept = device_inventory((ADDRESS,), (), (quiet,), None, carried)
        self.assertEqual((len(kept.devices), len(kept.candidates)), (1, 0))


class ArchivedRedeliveryTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_redelivered_archived_occurrence_is_acknowledged_not_rescanned(
        self,
    ) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        config = configuration(Path(temporary.name))
        clock = Clock(0)
        port, collector = FixturePort(config), CountingCollector(config)
        with (
            patch("plowshare_privacy.journal.MIN_RETAINED_SCANS", 1),
            FileReceipts(config, clock=clock) as receipts,
        ):
            worker = Worker(config, port, receipts, collector)
            port.events["schedule.due"] = [event("schedule:first")]
            await worker.poll()
            first = receipts.all()[0].scan_id
            self.assertEqual(
                first,
                str(
                    uuid5(
                        NAMESPACE_URL,
                        f"plowshare-privacy/{config.project}/{config.collector}/schedule:first",
                    )
                ),
            )
            clock.day = 36500  # Far beyond the wall-clock time this scan finished.
            port.events["schedule.due"] = [event("schedule:second")]
            await worker.poll()
            self.assertTrue(receipts.archived(first))
            scans, uploads = collector.count, port.uploads

            port.events["schedule.due"] = [event("schedule:first")]
            await worker.poll()
            self.assertEqual(port.events["schedule.due"], [])  # Acknowledged.
            self.assertEqual((collector.count, port.uploads), (scans, uploads))
            self.assertEqual(len(receipts.all()), 1)
