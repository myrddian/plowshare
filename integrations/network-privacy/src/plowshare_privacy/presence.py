"""Collector-local presence sampling. This path has no publication or investigation port."""

from __future__ import annotations

import asyncio
import json
import os
import random
import sqlite3
import time
from collections import defaultdict
from contextlib import closing
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from itertools import groupby
from pathlib import Path
from typing import Callable, Protocol, runtime_checkable

from .contracts import CollectionPlan, DeviceIdentity, timestamp, utc_now
from .dns_activity import (
    DnsActivity,
    DnsActivityWindow,
    group_destinations,
    pihole_silent,
    retention_rank,
)
from .traffic import TrafficCounter, TrafficVolume, accumulate

WINDOW_SECONDS = 24 * 60 * 60
MAX_DELAY_SECONDS = 300
SAMPLE_TIMEOUT_SECONDS = 120
# Keep disk growth bounded even if the wall clock moves backwards.
MAX_SAMPLES = WINDOW_SECONDS // 120 + 1
# One maximum timer delay plus the deadline of the preceding quick check.
MAX_ATTRIBUTION_GAP = MAX_DELAY_SECONDS + SAMPLE_TIMEOUT_SECONDS
DNS_TIMEOUT_SECONDS = 30
MAX_WINDOW_DESTINATIONS = 32


def epoch(when: str) -> float:
    return datetime.fromisoformat(when.replace("Z", "+00:00")).timestamp()


@dataclass(frozen=True)
class Sighting:
    address: str
    identity: DeviceIdentity | None = None

    @property
    def key(self) -> str:
        return self.identity.device_id if self.identity else "ip:" + self.address


@dataclass(frozen=True)
class PresenceSample:
    checked_at: str
    sightings: tuple[Sighting, ...]
    issues: tuple[str, ...] = ()
    ambiguous_addresses: frozenset[str] = frozenset()
    traffic: tuple[TrafficCounter, ...] = ()


@dataclass(frozen=True)
class PresenceCount:
    seen: int
    checked: int
    last_seen: str | None
    address_only: bool
    checked_at: str | None


@dataclass(frozen=True)
class PresenceView:
    checked_at: str | None = None
    checks: int = 0
    sightings: dict[str, Sighting] = field(default_factory=dict)
    counts: dict[str, PresenceCount] = field(default_factory=dict)
    dns: dict[str, DnsActivity] = field(default_factory=dict)
    traffic: dict[str, TrafficVolume] = field(default_factory=dict)


@dataclass(frozen=True)
class PresenceStatus:
    enabled: bool
    last_checked: str | None
    issues: tuple[str, ...]


@runtime_checkable
class PresenceCollector(Protocol):
    async def collect_presence(self, since: str) -> PresenceSample: ...


@runtime_checkable
class DnsActivityCollector(Protocol):
    async def collect_dns_activity(
        self, previous: PresenceSample, current: PresenceSample
    ) -> tuple[tuple[DnsActivityWindow, ...], tuple[str, ...]]: ...


def _instant(value: float) -> str:
    return (
        datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")
    )


@dataclass(frozen=True)
class _RetainedSweep:
    id: int
    checked: float
    devices: frozenset[str]


def _credit_before(
    sweeps: list[_RetainedSweep],
    index: int,
    checked: float,
    address: str,
    device: str,
    *,
    previous: int | None = None,
    legacy: bool = False,
) -> list[tuple[str, int, str]]:
    """Infer only a contiguous run ending at a confirmed association.

    Once an address had an owner, require the same MAC on both sides of the
    run. An address-only legacy record can hide an earlier owner, so legacy
    inference also requires the run to start at the first retained check.
    """
    ip_key = "ip:" + address
    credits: list[tuple[str, int, str]] = []
    for offset in range(index - 1, -1, -1):
        sweep = sweeps[offset]
        if not 0 < checked - sweep.checked <= MAX_ATTRIBUTION_GAP:
            break
        if sweep.id == previous:
            return credits
        if ip_key not in sweep.devices or device in sweep.devices:
            break
        credits.append((device, sweep.id, ip_key))
        checked = sweep.checked
    else:
        return credits if previous is None else []
    return credits if previous is None and not legacy else []


class PresenceHistory:
    """Atomically retain completed samples and their newest address associations.

    One sample contributes at most one sighting per identity. Scope changes keep
    earlier samples separate, so addresses outside a scope never count as misses.
    """

    def __init__(self, directory: Path):
        self.path = directory / "presence.sqlite3"

    def _require_unlinked(self) -> None:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Presence journal must be private and unlinked")

    def _require_private(self) -> None:
        if os.name == "posix" and self.path.stat().st_mode & 0o077:
            raise ValueError("Presence journal must have private permissions")

    def _connect(self) -> sqlite3.Connection:
        self._require_unlinked()
        fd = os.open(
            self.path, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600
        )
        os.close(fd)
        self._require_private()
        connection = sqlite3.connect(self.path)
        try:
            connection.execute("PRAGMA foreign_keys = ON")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS sweep (
                    id INTEGER PRIMARY KEY, scope TEXT NOT NULL, checked REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS sweep_scope_checked ON sweep (scope, checked);
                CREATE TABLE IF NOT EXISTS seen (
                    sweep_id INTEGER REFERENCES sweep(id) ON DELETE CASCADE,
                    device TEXT NOT NULL, credited_device TEXT,
                    PRIMARY KEY (sweep_id, device)
                );
                CREATE TABLE IF NOT EXISTS latest (
                    scope TEXT NOT NULL, address TEXT NOT NULL, identity TEXT,
                    checked REAL NOT NULL,
                    PRIMARY KEY (scope, address)
                );
                CREATE TABLE IF NOT EXISTS dns_activity (
                    sweep_id INTEGER REFERENCES sweep(id) ON DELETE CASCADE,
                    device TEXT NOT NULL, start REAL NOT NULL, read INTEGER NOT NULL,
                    available INTEGER NOT NULL, partial INTEGER NOT NULL,
                    destinations TEXT NOT NULL,
                    PRIMARY KEY (sweep_id, device)
                );
                CREATE TABLE IF NOT EXISTS traffic (
                    sweep_id INTEGER REFERENCES sweep(id) ON DELETE CASCADE,
                    device TEXT NOT NULL, to_device INTEGER NOT NULL,
                    from_device INTEGER NOT NULL,
                    PRIMARY KEY (sweep_id, device)
                );
                CREATE TABLE IF NOT EXISTS address_anchor (
                    scope TEXT NOT NULL, address TEXT NOT NULL, device TEXT,
                    sweep_id INTEGER, PRIMARY KEY (scope, address)
                );
            """)
        except sqlite3.Error:
            connection.close()
            raise
        return connection

    @staticmethod
    def _has_credits(connection: sqlite3.Connection) -> bool:
        return any(
            row[1] == "credited_device"
            for row in connection.execute("PRAGMA table_info(seen)")
        )

    @staticmethod
    def _retained_sweeps(
        connection: sqlite3.Connection, scope: str
    ) -> list[_RetainedSweep]:
        rows = connection.execute(
            """
            SELECT sweep.id, sweep.checked, seen.device
            FROM sweep LEFT JOIN seen ON seen.sweep_id=sweep.id AND seen.credited_device IS NULL
            WHERE sweep.scope=? ORDER BY sweep.id
            """,
            (scope,),
        )
        return [
            _RetainedSweep(
                sweep_id, checked, frozenset(row[2] for row in group if row[2])
            )
            for (sweep_id, checked), group in groupby(
                rows, key=lambda row: (row[0], row[1])
            )
        ]

    @staticmethod
    def _save_credits(
        connection: sqlite3.Connection, credits: list[tuple[str, int, str]]
    ) -> None:
        connection.executemany(
            """
            UPDATE seen SET credited_device=?
            WHERE sweep_id=? AND device=? AND credited_device IS NULL
            """,
            credits,
        )

    def _upgrade_history(self, connection: sqlite3.Connection, scope: str) -> None:
        # The old journal kept a MAC's address only in its latest positive
        # association. Use that exact anchor where available, preserving raw
        # sighting keys and leaving histories with unknown ownership separate.
        connection.execute("ALTER TABLE seen ADD COLUMN credited_device TEXT")
        sweeps = self._retained_sweeps(connection, scope)
        by_time: dict[float, list[int]] = {}
        for index, sweep in enumerate(sweeps):
            by_time.setdefault(sweep.checked, []).append(index)
        anchors: list[tuple[str, str, str | None, int | None]] = []
        credits: list[tuple[str, int, str]] = []
        for address, encoded, checked in connection.execute(
            "SELECT address, identity, checked FROM latest WHERE scope=?", (scope,)
        ):
            identity = DeviceIdentity.decode(json.loads(encoded)) if encoded else None
            matches = by_time.get(checked, [])
            sweep_id = None
            if identity and identity.mac and len(matches) == 1:
                index = matches[0]
                if identity.device_id in sweeps[index].devices:
                    sweep_id = sweeps[index].id
                    # A pruned legacy prefix could have held an earlier owner.
                    # IDs begin at one and stay increasing across scope changes.
                    if sweep_id == index + 1:
                        credits.extend(
                            _credit_before(
                                sweeps,
                                index,
                                checked,
                                address,
                                identity.device_id,
                                legacy=True,
                            )
                        )
            anchors.append(
                (
                    scope,
                    address,
                    identity.device_id if sweep_id and identity else None,
                    sweep_id,
                )
            )
        self._save_credits(connection, credits)
        connection.executemany(
            "INSERT INTO address_anchor VALUES (?, ?, ?, ?)", anchors
        )

    def _record_associations(
        self,
        connection: sqlite3.Connection,
        scope: str,
        checked: float,
        sample: PresenceSample,
        sweep_id: int,
        previous_id: int | None,
    ) -> None:
        identified = {
            item.address: item.key
            for item in sample.sightings
            if item.identity
            and item.identity.mac
            and item.address not in sample.ambiguous_addresses
        }
        anchors = {
            address: (device, sweep)
            for address, device, sweep in connection.execute(
                "SELECT address, device, sweep_id FROM address_anchor WHERE scope=?",
                (scope,),
            )
        }
        candidates = {
            address: device
            for address, device in identified.items()
            if address not in anchors
            or (anchors[address][0] == device and anchors[address][1] != previous_id)
        }
        if candidates and previous_id is not None:
            # One bounded history read for all addresses changing from anonymous
            # to identified, and no history read for steady identified devices.
            sweeps = self._retained_sweeps(connection, scope)
            credits = [
                credit
                for address, device in candidates.items()
                for credit in _credit_before(
                    sweeps,
                    len(sweeps) - 1,
                    checked,
                    address,
                    device,
                    previous=anchors[address][1] if address in anchors else None,
                )
            ]
            self._save_credits(connection, credits)
        observations: dict[str, str | None] = dict(identified)
        observations.update(dict.fromkeys(sample.ambiguous_addresses))
        connection.executemany(
            """
            INSERT INTO address_anchor (scope, address, device, sweep_id) VALUES (?, ?, ?, ?)
            ON CONFLICT (scope, address) DO UPDATE SET
                device=excluded.device, sweep_id=excluded.sweep_id
            """,
            (
                (scope, address, device, sweep_id)
                for address, device in observations.items()
            ),
        )

    def record(
        self,
        scope: str,
        sample: PresenceSample,
        dns: tuple[DnsActivityWindow, ...] = (),
    ) -> None:
        checked = epoch(timestamp(sample.checked_at))
        with closing(self._connect()) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            if not self._has_credits(connection):
                self._upgrade_history(connection, scope)
            previous_id = connection.execute(
                "SELECT max(id) FROM sweep WHERE scope=?", (scope,)
            ).fetchone()[0]
            result = connection.execute(
                "INSERT INTO sweep (scope, checked) VALUES (?, ?)", (scope, checked)
            )
            sweep = result.lastrowid
            if sweep is None:
                raise sqlite3.DatabaseError("No sweep identity was assigned")
            connection.executemany(
                "INSERT INTO seen (sweep_id, device) VALUES (?, ?)",
                ((sweep, key) for key in {item.key for item in sample.sightings}),
            )
            connection.executemany(
                """
                INSERT INTO latest (scope, address, identity, checked) VALUES (?, ?, ?, ?)
                ON CONFLICT (scope, address) DO UPDATE
                SET identity=excluded.identity, checked=excluded.checked
                WHERE excluded.checked >= latest.checked
                """,
                (
                    (
                        scope,
                        item.address,
                        json.dumps(asdict(item.identity)) if item.identity else None,
                        checked,
                    )
                    for item in sample.sightings
                ),
            )
            connection.executemany(
                """
                INSERT INTO dns_activity
                    (sweep_id, device, start, read, available, partial, destinations)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (self._dns_row(sweep, item) for item in dns),
            )
            connection.executemany(
                """
                INSERT OR REPLACE INTO traffic (sweep_id, device, to_device, from_device)
                VALUES (?, ?, ?, ?)
                """,
                (
                    # SQLite integers are signed 64-bit; clamp the rare counter
                    # above that so the row stays valid. accumulate() then sees
                    # a flat interval, never a fabricated one.
                    (
                        sweep,
                        item.device,
                        min(item.to_device, 2**63 - 1),
                        min(item.from_device, 2**63 - 1),
                    )
                    for item in sample.traffic
                ),
            )
            self._record_associations(
                connection, scope, checked, sample, sweep, previous_id
            )
            connection.execute(
                "DELETE FROM sweep WHERE scope <> ? OR checked < ? OR id <= ?",
                (scope, checked - WINDOW_SECONDS, sweep - MAX_SAMPLES),
            )
            # Only the active scope retains discovery metadata. Keep its last
            # sighting per address after a sample ages out of the rolling count.
            connection.execute("DELETE FROM latest WHERE scope <> ?", (scope,))
            connection.execute("DELETE FROM address_anchor WHERE scope <> ?", (scope,))

    @staticmethod
    def _dns_row(
        sweep: int, item: DnsActivityWindow
    ) -> tuple[int, str, float, int, int, bool, str]:
        sample = item.sample
        ordered = sorted(
            sample.dns,
            key=lambda query: (
                retention_rank(query.domain),
                -query.queries,
                query.domain,
            ),
        )
        return (
            sweep,
            item.device,
            epoch(item.from_at),
            sample.read,
            sample.available,
            bool(sample.issues) or len(ordered) > MAX_WINDOW_DESTINATIONS,
            json.dumps(
                [
                    (query.domain, query.queries, sample.blocked.get(query.domain, 0))
                    for query in ordered[:MAX_WINDOW_DESTINATIONS]
                ]
            ),
        )

    @staticmethod
    def _dns_view(
        connection: sqlite3.Connection, scope: str, cutoff: float, now: float
    ) -> dict[str, DnsActivity]:
        if (
            connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='dns_activity'"
            ).fetchone()
            is None
        ):
            return {}  # Read-only startup can encounter a pre-profile journal.
        predicate = (
            "sweep.scope=? AND sweep.checked BETWEEN ? AND ? AND dns_activity.start>=?"
        )
        parameters = (scope, cutoff, now, cutoff)
        destinations: dict[str, list[tuple[str, int, int]]] = defaultdict(list)
        for device, domain, count, blocked in connection.execute(
            f"""
            SELECT dns_activity.device, json_extract(destination.value, '$[0]'),
                   sum(json_extract(destination.value, '$[1]')),
                   sum(json_extract(destination.value, '$[2]'))
            FROM sweep JOIN dns_activity ON dns_activity.sweep_id=sweep.id
            JOIN json_each(dns_activity.destinations) AS destination
            WHERE {predicate}
            GROUP BY 1, 2
            """,
            parameters,
        ):
            destinations[device].append((domain, count, blocked))
        return {
            device: DnsActivity(
                _instant(start),
                _instant(end),
                windows,
                partial,
                read,
                available,
                group_destinations(destinations[device]),
                pihole_silent(
                    windows=windows,
                    partial=partial,
                    available=available,
                    span_seconds=end - start,
                ),
            )
            for device, start, end, windows, partial, read, available in connection.execute(
                f"""
                SELECT dns_activity.device, min(dns_activity.start), max(sweep.checked),
                       count(*), sum(dns_activity.partial), sum(dns_activity.read),
                       sum(dns_activity.available)
                FROM sweep JOIN dns_activity ON dns_activity.sweep_id=sweep.id
                WHERE {predicate}
                GROUP BY 1
                """,
                parameters,
            )
        }

    def view(
        self, scope: str, now: float, *, include_dns: bool = False
    ) -> PresenceView:
        self._require_unlinked()
        if not self.path.exists():
            return PresenceView()
        self._require_private()
        cutoff = now - WINDOW_SECONDS
        with closing(
            sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)
        ) as connection:
            connection.execute("BEGIN")
            checks, last = connection.execute(
                "SELECT count(*), max(checked) FROM sweep WHERE scope=? AND checked BETWEEN ? AND ?",
                (scope, cutoff, now),
            ).fetchone()
            checked_at = _instant(last) if last is not None else None
            device_key = (
                "COALESCE(seen.credited_device, seen.device)"
                if self._has_credits(connection)
                else "seen.device"
            )
            counts = {
                key: PresenceCount(
                    count, checks, _instant(seen), key.startswith("ip:"), checked_at
                )
                for key, count, seen in connection.execute(
                    f"""
                    SELECT {device_key}, count(DISTINCT sweep.id), max(sweep.checked)
                    FROM sweep JOIN seen ON seen.sweep_id=sweep.id
                    WHERE sweep.scope=? AND sweep.checked BETWEEN ? AND ?
                    GROUP BY 1
                    """,
                    (scope, cutoff, now),
                )
            }
            sightings = {}
            current_keys = set()
            for address, encoded, checked in connection.execute(
                """
                SELECT address, identity, checked FROM latest
                WHERE scope=? AND checked <= ?
                ORDER BY checked DESC, address
                """,
                (scope, now),
            ):
                identity = (
                    DeviceIdentity.decode(json.loads(encoded)) if encoded else None
                )
                sighting = Sighting(address, identity)
                # A MAC seen at a new address stays one device. Full-scan history
                # still shows what was previously observed at the old address.
                if sighting.key not in current_keys:
                    sightings[address] = sighting
                    current_keys.add(sighting.key)
                    counts.setdefault(
                        sighting.key,
                        PresenceCount(
                            0,
                            checks,
                            _instant(checked),
                            sighting.key.startswith("ip:"),
                            checked_at,
                        ),
                    )
            dns = self._dns_view(connection, scope, cutoff, now) if include_dns else {}
            traffic = (
                self._traffic_view(connection, scope, cutoff, now)
                if include_dns
                else {}
            )
            return PresenceView(checked_at, checks, sightings, counts, dns, traffic)

    @staticmethod
    def _traffic_view(
        connection: sqlite3.Connection, scope: str, cutoff: float, now: float
    ) -> dict[str, TrafficVolume]:
        if (
            connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='traffic'"
            ).fetchone()
            is None
        ):
            return {}  # Read-only startup can encounter a pre-traffic journal.
        rows = connection.execute(
            """
            SELECT traffic.device, sweep.checked, traffic.to_device, traffic.from_device
            FROM sweep JOIN traffic ON traffic.sweep_id=sweep.id
            WHERE sweep.scope=? AND sweep.checked BETWEEN ? AND ?
            ORDER BY traffic.device, sweep.checked, sweep.id
            """,
            (scope, cutoff, now),
        )
        result = {}
        for device, group in groupby(rows, key=lambda row: row[0]):
            series = [(row[1], row[2], row[3]) for row in group]
            samples, to_bytes, from_bytes, resets = accumulate(series)
            if samples < 2:
                continue  # One sample has no interval and therefore no volume.
            result[device] = TrafficVolume(
                _instant(series[0][0]),
                _instant(series[-1][0]),
                samples,
                to_bytes,
                from_bytes,
                resets,
            )
        return result


class PresenceWatch:
    """A non-overlapping randomized timer driven by the existing collector loop."""

    def __init__(
        self,
        history: PresenceHistory,
        *,
        monotonic: Callable[[], float] = time.monotonic,
        clock: Callable[[], str] = utc_now,
        delay: Callable[[], float] = lambda: random.uniform(120, MAX_DELAY_SECONDS),
    ):
        self.history = history
        self.monotonic = monotonic
        self.clock = clock
        self.delay = delay
        self.due: float | None = None
        self.since: str | None = None
        self.scope: str | None = None
        self.issues: tuple[str, ...] = ()
        self._cached_scope: str | None = None
        self._cached_until = 0.0
        self._cached = PresenceView()
        self._cached_dns = False
        self._previous: PresenceSample | None = None

    def reset(self) -> None:
        self.due = None
        self.since = None
        self.issues = ()
        self._previous = None

    def wait_seconds(self) -> float:
        return (
            max(0.0, min(2.0, self.due - self.monotonic()))
            if self.due is not None
            else 2.0
        )

    async def poll(
        self, plan: CollectionPlan, scope: str, collect: PresenceCollector
    ) -> None:
        if not plan.enabled or not plan.quick_scan or plan.mode != "tcp":
            self.reset()
            return
        if self.scope != scope or self.due is None or self.since is None:
            self.scope = scope
            self._previous = None
            self.since = self.clock()
            self.due = self.monotonic() + self.delay()
            self.issues = ()
            return
        if self.monotonic() < self.due:
            return
        since = self.since
        self.since = self.clock()
        try:
            # One failed, cancelled or incomplete sweep never enters the denominator.
            async with asyncio.timeout(SAMPLE_TIMEOUT_SECONDS):
                sample = await collect.collect_presence(since)
            dns: tuple[DnsActivityWindow, ...] = ()
            dns_issues: tuple[str, ...] = ()
            if (
                plan.dns_profiles
                and self._previous is not None
                and isinstance(collect, DnsActivityCollector)
            ):
                try:
                    async with asyncio.timeout(DNS_TIMEOUT_SECONDS):
                        dns, dns_issues = await collect.collect_dns_activity(
                            self._previous, sample
                        )
                except (OSError, ValueError, TimeoutError):
                    dns_issues = (
                        "DNS activity could not be sampled in this quick check.",
                    )
            self.history.record(scope, sample, dns)
            self._previous = sample if plan.dns_profiles else None
            self._cached_until = 0.0
            self.issues = (*sample.issues, *dns_issues)
            self.since = sample.checked_at
        except (OSError, ValueError, TimeoutError, sqlite3.Error):
            self._previous = None
            self.issues = (
                "Quick presence check failed; it was excluded from the history.",
            )
        finally:
            self.due = self.monotonic() + self.delay()

    def view(self, scope: str, *, include_dns: bool = False) -> PresenceView:
        now = self.monotonic()
        if (
            self._cached_scope != scope
            or self._cached_dns != include_dns
            or now >= self._cached_until
        ):
            try:
                self._cached = self.history.view(
                    scope, epoch(self.clock()), include_dns=include_dns
                )
            except (OSError, ValueError, sqlite3.Error):
                self._cached = PresenceView()
                self.issues = ("Quick presence history is unavailable.",)
            self._cached_scope = scope
            self._cached_dns = include_dns
            self._cached_until = now + 20
        return self._cached
