"""Collector-local presence sampling. This path has no publication or investigation port."""

from __future__ import annotations

import asyncio
import json
import os
import random
import sqlite3
import time
from contextlib import closing
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Protocol, runtime_checkable

from .contracts import CollectionPlan, DeviceIdentity, timestamp, utc_now

WINDOW_SECONDS = 24 * 60 * 60
# Keep disk growth bounded even if the wall clock moves backwards.
MAX_SAMPLES = WINDOW_SECONDS // 120 + 1


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


@dataclass(frozen=True)
class PresenceStatus:
    enabled: bool
    last_checked: str | None
    issues: tuple[str, ...]


@runtime_checkable
class PresenceCollector(Protocol):
    async def collect_presence(self, since: str) -> PresenceSample: ...


def _instant(value: float) -> str:
    return (
        datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")
    )


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
                    device TEXT NOT NULL, PRIMARY KEY (sweep_id, device)
                );
                CREATE TABLE IF NOT EXISTS latest (
                    scope TEXT NOT NULL, address TEXT NOT NULL, identity TEXT,
                    checked REAL NOT NULL,
                    PRIMARY KEY (scope, address)
                );
            """)
        except sqlite3.Error:
            connection.close()
            raise
        return connection

    def record(self, scope: str, sample: PresenceSample) -> None:
        checked = epoch(timestamp(sample.checked_at))
        with closing(self._connect()) as connection, connection:
            result = connection.execute(
                "INSERT INTO sweep (scope, checked) VALUES (?, ?)", (scope, checked)
            )
            sweep = result.lastrowid
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
            connection.execute(
                "DELETE FROM sweep WHERE scope <> ? OR checked < ? OR id <= ?",
                (scope, checked - WINDOW_SECONDS, (sweep or 0) - MAX_SAMPLES),
            )
            # Only the active scope retains discovery metadata. Keep its last
            # sighting per address after a sample ages out of the rolling count.
            connection.execute("DELETE FROM latest WHERE scope <> ?", (scope,))

    def view(self, scope: str, now: float) -> PresenceView:
        self._require_unlinked()
        if not self.path.exists():
            return PresenceView()
        self._require_private()
        cutoff = now - WINDOW_SECONDS
        with closing(
            sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)
        ) as connection:
            checks, last = connection.execute(
                "SELECT count(*), max(checked) FROM sweep WHERE scope=? AND checked BETWEEN ? AND ?",
                (scope, cutoff, now),
            ).fetchone()
            checked_at = _instant(last) if last is not None else None
            counts = {
                key: PresenceCount(
                    count, checks, _instant(seen), key.startswith("ip:"), checked_at
                )
                for key, count, seen in connection.execute(
                    """
                    SELECT seen.device, count(*), max(sweep.checked)
                    FROM sweep JOIN seen ON seen.sweep_id=sweep.id
                    WHERE sweep.scope=? AND sweep.checked BETWEEN ? AND ?
                    GROUP BY seen.device
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
            return PresenceView(checked_at, checks, sightings, counts)


class PresenceWatch:
    """A non-overlapping randomized timer driven by the existing collector loop."""

    def __init__(
        self,
        history: PresenceHistory,
        *,
        monotonic: Callable[[], float] = time.monotonic,
        clock: Callable[[], str] = utc_now,
        delay: Callable[[], float] = lambda: random.uniform(120, 300),
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

    def reset(self) -> None:
        self.due = None
        self.since = None
        self.issues = ()

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
            async with asyncio.timeout(120):
                sample = await collect.collect_presence(since)
            self.history.record(scope, sample)
            self._cached_until = 0.0
            self.issues = sample.issues
            self.since = sample.checked_at
        except (OSError, ValueError, TimeoutError, sqlite3.Error):
            self.issues = (
                "Quick presence check failed; it was excluded from the history.",
            )
        finally:
            self.due = self.monotonic() + self.delay()

    def view(self, scope: str) -> PresenceView:
        now = self.monotonic()
        if self._cached_scope != scope or now >= self._cached_until:
            try:
                self._cached = self.history.view(scope, epoch(self.clock()))
            except (OSError, ValueError, sqlite3.Error):
                self._cached = PresenceView()
                self.issues = ("Quick presence history is unavailable.",)
            self._cached_scope = scope
            self._cached_until = now + 20
        return self._cached
