"""Deep port scans of flagged devices: the durable records and the port probe.

Probing contract: a scan is a full TCP connect sweep of ports 1-65535 against
one device's current address, started only for a MAC-confirmed device, from the
collector's own host. A refused connection is ``closed``; a timeout or an
unreachable host is ``timeout`` (unanswered, never proof of closure); any other
operating-system error (socket exhaustion, a missing route, a permission
failure) is re-raised so the scan fails instead of reporting ports as closed.
Only the open ports are recorded. Nothing is sent after the connection is made.

This module deliberately does not import ``device_assessments``; the dependency
runs the other way.

The journal (``deep-scans.json``, private) holds the queue, the one running
scan, the last few results per device and the household's automatic-scan
admissions, so an interrupted scan is visible after a restart rather than lost.
"""

from __future__ import annotations

import asyncio
import errno
import ipaddress
import json
from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Awaitable, Callable, Iterator, Literal, Sequence
from uuid import uuid4

from .collection import NetworkCollector
from .contracts import (
    household_day,
    integer,
    items,
    load_json,
    object_fields,
    parse_json,
    private_ipv4,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .device_dossier import parse_device_id
from .device_inventory import DeviceInventory
from .identity_source import DeviceIdentitySource, IdentitySample
from .private_files import replace_private
from .tools import monitored_devices

if TYPE_CHECKING:
    from .worker import Worker

Trigger = Literal["automatic", "operator"]
ScanState = Literal[
    "complete", "cancelled", "interrupted", "unreliable", "address_changed", "failed"
]

PORTS = range(1, 65536)
PROBE_TIMEOUT = 0.5
CONCURRENCY = 64
# Automatic scans admitted per household day; the operator's own scans are unlimited.
MAX_AUTOMATIC = 12
KEEP_RESULTS = 3
# Assessment reports remembered per device so a report triggers at most once.
KEEP_REPORTS = 3
QUEUE_TTL_SECONDS = 7 * 86400
MAX_DEVICES = 256
MAX_LEDGER_BYTES = 2 * 1024 * 1024
MAX_DETAIL = 1600

# Name -> typed literal; a lookup narrows an external string without a cast.
_TRIGGERS: dict[str, Trigger] = {"automatic": "automatic", "operator": "operator"}
_STATES: dict[str, ScanState] = {
    "complete": "complete",
    "cancelled": "cancelled",
    "interrupted": "interrupted",
    "unreliable": "unreliable",
    "address_changed": "address_changed",
    "failed": "failed",
}


def _trigger(value: object) -> Trigger:
    if not isinstance(value, str) or value not in _TRIGGERS:
        raise ValueError("Invalid deep scan trigger")
    return _TRIGGERS[value]


def _state(value: object) -> ScanState:
    if not isinstance(value, str) or value not in _STATES:
        raise ValueError("Invalid deep scan state")
    return _STATES[value]


@dataclass(frozen=True)
class DeepScanRequest:
    """A queued or running scan; the typed request a Relay transport would carry."""

    device_id: str
    trigger: Trigger
    # The assessment report revision whose signals prompted an automatic scan.
    report: str | None
    request_id: str
    queued_at: str

    def __post_init__(self) -> None:
        parse_device_id(self.device_id)
        _trigger(self.trigger)
        uuid(self.request_id)
        if self.report is not None:
            uuid(self.report)
        timestamp(self.queued_at)

    @classmethod
    def decode(cls, value: object) -> DeepScanRequest:
        row = object_fields(
            value, {"device_id", "trigger", "report", "request_id", "queued_at"}
        )
        return cls(
            parse_device_id(row["device_id"]),
            _trigger(row["trigger"]),
            uuid(row["report"]) if row["report"] is not None else None,
            uuid(row["request_id"]),
            timestamp(row["queued_at"]),
        )


@dataclass(frozen=True)
class DeepScanResult:
    """One finished scan. ``open_ports`` is meaningful only for ``complete``."""

    device_id: str
    address: str
    request_id: str
    trigger: Trigger
    report: str | None
    started_at: str
    finished_at: str
    state: ScanState
    open_ports: tuple[int, ...]
    # Ports that neither accepted nor refused within the probe timeout.
    unanswered: int
    detail: str | None

    def __post_init__(self) -> None:
        parse_device_id(self.device_id)
        ipaddress.ip_address(text(self.address))
        uuid(self.request_id)
        _trigger(self.trigger)
        if self.report is not None:
            uuid(self.report)
        timestamp(self.started_at)
        timestamp(self.finished_at)
        _state(self.state)
        for port in self.open_ports:
            integer(port, 1, 65535)
        # Strictly increasing means sorted and unique.
        if any(a >= b for a, b in zip(self.open_ports, self.open_ports[1:])):
            raise ValueError("Open ports must be sorted and unique")
        integer(self.unanswered, 0, 65535)
        # A scan that did not finish cannot vouch for any port.
        if self.open_ports and self.state != "complete":
            raise ValueError("Only a complete scan records open ports")
        if self.detail is not None:
            text(self.detail, MAX_DETAIL)

    @classmethod
    def decode(cls, value: object) -> DeepScanResult:
        row = object_fields(
            value,
            {
                "device_id",
                "address",
                "request_id",
                "trigger",
                "report",
                "started_at",
                "finished_at",
                "state",
                "open_ports",
                "unanswered",
                "detail",
            },
        )
        return cls(
            parse_device_id(row["device_id"]),
            text(row["address"]),
            uuid(row["request_id"]),
            _trigger(row["trigger"]),
            uuid(row["report"]) if row["report"] is not None else None,
            timestamp(row["started_at"]),
            timestamp(row["finished_at"]),
            _state(row["state"]),
            tuple(integer(p, 1, 65535) for p in items(row["open_ports"], 65535)),
            integer(row["unanswered"], 0, 65535),
            text(row["detail"], MAX_DETAIL) if row["detail"] is not None else None,
        )


@dataclass(frozen=True)
class HandledReport:
    """An assessment report that has already triggered (or been covered by) a scan."""

    device_id: str
    report: str

    def __post_init__(self) -> None:
        parse_device_id(self.device_id)
        uuid(self.report)

    @classmethod
    def decode(cls, value: object) -> HandledReport:
        row = object_fields(value, {"device_id", "report"})
        return cls(parse_device_id(row["device_id"]), uuid(row["report"]))


@dataclass(frozen=True)
class DeepScanLedger:
    # Automatic scans admitted per household day; 0 turns automatic scans off.
    automatic_limit: int = 0
    # At most one entry per device, and never the device that is running.
    queue: tuple[DeepScanRequest, ...] = ()
    # ``queued_at`` of the running scan is the time it was admitted to run.
    running: DeepScanRequest | None = None
    # The private IPv4 address the running scan probes; set exactly when running is.
    running_address: str | None = None
    # Newest last; at most KEEP_RESULTS per device.
    results: tuple[DeepScanResult, ...] = ()
    # Automatic admission times; only the current household day's are kept.
    admissions: tuple[str, ...] = ()
    # Newest last; unique, at most KEEP_REPORTS per device and MAX_DEVICES devices.
    handled: tuple[HandledReport, ...] = ()

    def __post_init__(self) -> None:
        integer(self.automatic_limit, 0, MAX_AUTOMATIC)
        if (
            len(self.queue) > MAX_DEVICES
            or len(self.results) > MAX_DEVICES * KEEP_RESULTS
        ):
            raise ValueError("Too many deep scan records")
        devices = [r.device_id for r in self.queue]
        if self.running is not None:
            devices.append(self.running.device_id)
        if len(set(devices)) != len(devices):
            raise ValueError("A device has more than one queued or running scan")
        if (self.running is None) != (self.running_address is None):
            raise ValueError("A running scan needs its address, and only it")
        if self.running_address is not None and not private_ipv4(
            text(self.running_address, 15)
        ):
            raise ValueError("A deep scan only probes a private IPv4 address")
        if len(set(self.handled)) != len(self.handled):
            raise ValueError("Duplicate handled deep scan reports")
        reports: dict[str, int] = {}
        for handled in self.handled:
            reports[handled.device_id] = reports.get(handled.device_id, 0) + 1
        if len(reports) > MAX_DEVICES or any(
            n > KEEP_REPORTS for n in reports.values()
        ):
            raise ValueError("Too many handled deep scan reports")
        counts: dict[str, int] = {}
        for result in self.results:
            counts[result.device_id] = counts.get(result.device_id, 0) + 1
        if len(counts) > MAX_DEVICES or any(n > KEEP_RESULTS for n in counts.values()):
            raise ValueError("Too many deep scan results for a device")
        if len(self.admissions) > MAX_AUTOMATIC:
            raise ValueError("Too many automatic scan admissions")
        for admission in self.admissions:
            timestamp(admission)
        ids = [r.request_id for r in self.queue] + [r.request_id for r in self.results]
        if self.running is not None:
            ids.append(self.running.request_id)
        if len(set(ids)) != len(ids):
            raise ValueError("Conflicting deep scan receipts")

    @classmethod
    def decode(cls, value: object) -> DeepScanLedger:
        row = object_fields(
            value,
            {
                "automatic_limit",
                "queue",
                "running",
                "running_address",
                "results",
                "admissions",
                "handled",
            },
        )
        return cls(
            integer(row["automatic_limit"], 0, MAX_AUTOMATIC),
            tuple(DeepScanRequest.decode(v) for v in items(row["queue"], MAX_DEVICES)),
            DeepScanRequest.decode(row["running"])
            if row["running"] is not None
            else None,
            text(row["running_address"], 15)
            if row["running_address"] is not None
            else None,
            tuple(
                DeepScanResult.decode(v)
                for v in items(row["results"], MAX_DEVICES * KEEP_RESULTS)
            ),
            tuple(timestamp(v) for v in items(row["admissions"], MAX_AUTOMATIC)),
            tuple(
                HandledReport.decode(v)
                for v in items(row["handled"], MAX_DEVICES * KEEP_REPORTS)
            ),
        )


class DeepScanJournal:
    """The private ledger file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "deep-scans.json"

    def read(self) -> DeepScanLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Deep scan journal cannot be linked")
        if not self.path.exists():
            return DeepScanLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Deep scan journal must be private")
        return DeepScanLedger.decode(
            load_json(self.path, maximum_bytes=MAX_LEDGER_BYTES)
        )

    def save(self, value: DeepScanLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        DeepScanLedger.decode(parse_json(content, maximum_bytes=MAX_LEDGER_BYTES))
        replace_private(self.path, content)


async def probe_port(
    address: str, port: int, timeout: float
) -> Literal["open", "closed", "timeout"]:
    """Connect once and classify the answer; see the module's probing contract."""
    try:
        _, writer = await asyncio.wait_for(
            asyncio.open_connection(address, port), timeout
        )
    except TimeoutError:
        return "timeout"
    except OSError as error:
        if error.errno == errno.ECONNREFUSED:
            return "closed"
        if error.errno in {errno.EHOSTUNREACH, errno.ETIMEDOUT}:
            return "timeout"
        # Socket exhaustion, a missing route or a permission failure is a failed
        # scan, not evidence that the port is closed.
        raise
    writer.close()
    try:
        await asyncio.wait_for(writer.wait_closed(), timeout)
    except (TimeoutError, ConnectionError):
        pass
    return "open"


_POLL_SECONDS = 5
_INTERRUPTED = "The collector restarted during this scan; it was not resumed."
_UNAVAILABLE = (
    "A deep port scan needs UniFi or Pi-hole to confirm the device's MAC address"
    " before and after the sweep."
)
_WAITING = "Waiting for tomorrow's automatic scan allowance."

ViewState = Literal["idle", "queued", "running"]
_Outcome = tuple[ScanState, str | None]


class UnknownDevice(LookupError):
    """The device ID is valid but not a monitored device with a confirmed MAC."""


@dataclass(frozen=True)
class DeepScanView:
    """One device's scan state for the dashboard; progress is in memory only."""

    device_id: str
    available: bool
    state: ViewState
    trigger: Trigger | None
    checked: int
    total: int
    open_so_far: tuple[int, ...]
    estimated_finish: str | None
    # The newest result in any state, so a failed or cancelled scan stays visible.
    latest: DeepScanResult | None
    # The newest ``complete`` result: the only one that vouches for open ports,
    # so a later cancelled or failed attempt does not hide them.
    latest_complete: DeepScanResult | None
    detail: str | None


@dataclass(frozen=True)
class DeepScanUsage:
    automatic_limit: int
    used_today: int


@dataclass
class _Progress:
    """The running scan's sweep so far; never persisted."""

    request_id: str
    started_at: str
    checked: int = 0
    open_ports: set[int] = field(default_factory=set)
    unanswered: int = 0


def _instant(stamp: str) -> datetime:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00"))


def _remember(
    handled: tuple[HandledReport, ...], entry: HandledReport
) -> tuple[HandledReport, ...]:
    """Append ``entry``; keep KEEP_REPORTS per device for the MAX_DEVICES newest."""
    counts: dict[str, int] = {}
    kept: list[HandledReport] = []
    for item in reversed(handled + (entry,)):
        if item.device_id not in counts:
            if len(counts) >= MAX_DEVICES:
                continue
            counts[item.device_id] = 0
        if counts[item.device_id] < KEEP_REPORTS:
            counts[item.device_id] += 1
            kept.append(item)
    return tuple(reversed(kept))


def _keep(results: tuple[DeepScanResult, ...]) -> tuple[DeepScanResult, ...]:
    """Keep the newest KEEP_RESULTS per device for the MAX_DEVICES newest devices."""
    counts: dict[str, int] = {}
    kept: list[DeepScanResult] = []
    for result in reversed(results):
        if result.device_id not in counts:
            if len(counts) >= MAX_DEVICES:
                continue
            counts[result.device_id] = 0
        if counts[result.device_id] < KEEP_RESULTS:
            counts[result.device_id] += 1
            kept.append(result)
    return tuple(reversed(kept))


class DeepScanner:
    """Runs queued deep port scans one device at a time.

    Concurrency: every state change happens on the event loop. The synchronous
    entry points (``flagged``, ``request``, ``cancel``, ``set_automatic_limit``)
    never await, so each applies atomically to the current ledger. ``poll``
    re-reads the ledger after every await rather than trusting a snapshot, so a
    cancel or replacement made during an identity read wins; its own lock keeps
    a second ``poll`` from starting another scan. No worker or collection lock is
    held: identity reads and probes run unlocked.

    Persistence: each ledger change is saved before memory is updated, so a
    failed save leaves both unchanged. Sweep progress is memory only; a restart
    turns the persisted running scan into an ``interrupted`` result.

    Identity: the device's MAC must be the only identity at its address both
    before the first probe and after the last. A wrong MAC before the sweep is
    ``address_changed`` and nothing is probed; any doubt after the sweep makes
    the whole result ``unreliable`` with no ports, since the answers may belong
    to another device.
    """

    def __init__(
        self,
        journal: DeepScanJournal,
        inventory: Callable[[], DeviceInventory],
        identities: Callable[[], DeviceIdentitySource | None],
        *,
        probe: Callable[[str, int, float], Awaitable[str]] = probe_port,
        ports: Sequence[int] = PORTS,
        clock: Callable[[], str] = utc_now,
        zone: Callable[[], str] = lambda: "UTC",
        new_id: Callable[[], str] = lambda: str(uuid4()),
    ):
        self._journal = journal
        self._inventory = inventory
        self._identities = identities
        self._probe = probe
        self._ports = ports
        self._clock = clock
        # Read when used: the household zone can change at runtime.
        self._zone = zone
        self._new_id = new_id
        self.error: str | None = None
        self._lock = asyncio.Lock()
        # Request IDs of running scans the operator asked to stop.
        self._cancelled: set[str] = set()
        self._progress: _Progress | None = None
        self._ledger = journal.read()
        running = self._ledger.running
        address = self._ledger.running_address
        if running is not None and address is not None:
            self._commit(
                self._finish(
                    running,
                    address,
                    running.queued_at,
                    ("interrupted", _INTERRUPTED),
                )
            )

    @property
    def ledger(self) -> DeepScanLedger:
        return self._ledger

    @property
    def available(self) -> bool:
        return self._identities() is not None

    @property
    def detail(self) -> str | None:
        """Why scanning is not working now: a failed poll, else missing identities."""
        return self.error or (None if self.available else _UNAVAILABLE)

    def usage(self) -> DeepScanUsage:
        zone = self._zone()
        today = household_day(self._clock(), zone)
        used = sum(
            1 for a in self._ledger.admissions if household_day(a, zone) == today
        )
        return DeepScanUsage(self._ledger.automatic_limit, used)

    def flagged(self, device_id: str, report: str) -> None:
        """Queue an automatic scan for an assessment report's concerns, once.

        Idempotence: an accepted ``(device_id, report)`` is remembered in the
        ledger (the last KEEP_REPORTS per device), so it never queues again,
        whatever later happens to its entry: a cancel, expiry, a limit of 0 or an
        upgrade to an operator request. A queued automatic entry for an older
        report is replaced; a queued operator request or a running scan of the
        device covers the report without queueing. Nothing is queued or
        remembered while automatic scans are off (limit 0) or no identity source
        can confirm the device.
        """
        handled = HandledReport(parse_device_id(device_id), uuid(report))
        ledger = self._ledger
        if not self.available or ledger.automatic_limit == 0:
            return
        if handled in ledger.handled:
            return
        remembered = _remember(ledger.handled, handled)
        queued = self._queued(device_id)
        running = ledger.running is not None and ledger.running.device_id == device_id
        if running or (queued is not None and queued.trigger == "operator"):
            self._commit(replace(ledger, handled=remembered))
            return
        entry = DeepScanRequest(
            device_id, "automatic", report, self._new_id(), self._clock()
        )
        self._commit(
            replace(
                ledger,
                queue=self._enqueue(ledger, queued, entry),
                handled=remembered,
            )
        )

    def request(self, device_id: str) -> DeepScanView:
        """Queue an operator scan; an automatic entry is upgraded in place.

        An upgraded entry keeps its report, so its result still names the
        assessment that flagged the device.
        """
        parse_device_id(device_id)
        if not self.available:
            raise ValueError(_UNAVAILABLE)
        if all(d != device_id for d, _ in monitored_devices(self._inventory())):
            raise UnknownDevice(device_id)
        ledger = self._ledger
        queued = self._queued(device_id)
        running = ledger.running is not None and ledger.running.device_id == device_id
        if running or (queued is not None and queued.trigger == "operator"):
            return self.view(device_id)
        if queued is not None:
            upgraded = replace(queued, trigger="operator")
            queue = tuple(upgraded if q is queued else q for q in ledger.queue)
        else:
            entry = DeepScanRequest(
                device_id, "operator", None, self._new_id(), self._clock()
            )
            queue = self._enqueue(ledger, None, entry)
        self._commit(replace(ledger, queue=queue))
        return self.view(device_id)

    def cancel(self, device_id: str) -> DeepScanView:
        """Drop a queued request, or stop the running sweep after current probes."""
        parse_device_id(device_id)
        ledger = self._ledger
        queued = self._queued(device_id)
        if queued is not None:
            self._commit(
                replace(ledger, queue=tuple(q for q in ledger.queue if q is not queued))
            )
        elif ledger.running is not None and ledger.running.device_id == device_id:
            self._cancelled.add(ledger.running.request_id)
        return self.view(device_id)

    def set_automatic_limit(self, limit: int) -> None:
        """Set the automatic scans per household day; 0 discards queued ones."""
        integer(limit, 0, MAX_AUTOMATIC)
        ledger = self._ledger
        queue = ledger.queue
        if limit == 0:
            queue = tuple(q for q in queue if q.trigger == "operator")
        self._commit(replace(ledger, automatic_limit=limit, queue=queue))

    def latest(self, device_id: str) -> DeepScanResult | None:
        """The newest ``complete`` result: the only kind that vouches for ports."""
        return next(
            (
                r
                for r in reversed(self._ledger.results)
                if r.device_id == device_id and r.state == "complete"
            ),
            None,
        )

    def view(self, device_id: str) -> DeepScanView:
        ledger = self._ledger
        latest = next(
            (r for r in reversed(ledger.results) if r.device_id == device_id), None
        )
        latest_complete = self.latest(device_id)
        total = len(self._ports)
        detail = None if self.available else _UNAVAILABLE
        running = ledger.running
        if running is not None and running.device_id == device_id:
            progress = self._progress
            if progress is None or progress.request_id != running.request_id:
                progress = _Progress(running.request_id, running.queued_at)
            return DeepScanView(
                device_id,
                self.available,
                "running",
                running.trigger,
                progress.checked,
                total,
                tuple(sorted(progress.open_ports)),
                self._estimate(progress, total),
                latest,
                latest_complete,
                detail,
            )
        queued = self._queued(device_id)
        if queued is not None:
            if detail is None and queued.trigger == "automatic" and self._spent():
                detail = _WAITING
            return DeepScanView(
                device_id,
                self.available,
                "queued",
                queued.trigger,
                0,
                total,
                (),
                None,
                latest,
                latest_complete,
                detail,
            )
        return DeepScanView(
            device_id,
            self.available,
            "idle",
            None,
            0,
            total,
            (),
            None,
            latest,
            latest_complete,
            detail,
        )

    def views(self) -> tuple[DeepScanView, ...]:
        """Running first, then the queue in order, then scanned devices newest first."""
        ledger = self._ledger
        devices = [ledger.running.device_id] if ledger.running is not None else []
        devices += [q.device_id for q in ledger.queue]
        devices += [r.device_id for r in reversed(ledger.results)]
        return tuple(self.view(d) for d in dict.fromkeys(devices))

    async def poll(self) -> None:
        """Run at most one queued scan to completion."""
        async with self._lock:
            if not self.available:
                return
            self._expire()
            chosen = self._next()
            if chosen is not None:
                await self._scan(*chosen)

    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                self.error = None
                await self.poll()
            except Exception as error:
                # Any failure, expected or not, is shown and the loop keeps
                # serving; the failed scan, if one was running, is recorded.
                self.error = (
                    "Deep port scans need attention "
                    f"({type(error).__name__}). Queued requests and results were kept."
                )
            try:
                await asyncio.wait_for(stop.wait(), _POLL_SECONDS)
            except TimeoutError:
                continue

    def _commit(self, ledger: DeepScanLedger) -> None:
        self._journal.save(ledger)
        self._ledger = ledger

    def _queued(self, device_id: str) -> DeepScanRequest | None:
        return next((q for q in self._ledger.queue if q.device_id == device_id), None)

    def _enqueue(
        self,
        ledger: DeepScanLedger,
        replacing: DeepScanRequest | None,
        entry: DeepScanRequest,
    ) -> tuple[DeepScanRequest, ...]:
        queue = tuple(q for q in ledger.queue if q is not replacing)
        if len(queue) >= MAX_DEVICES:
            raise ValueError("The deep scan queue is full")
        return queue + (entry,)

    def _spent(self) -> bool:
        usage = self.usage()
        return usage.used_today >= usage.automatic_limit

    def _estimate(self, progress: _Progress, total: int) -> str | None:
        """Finish time at the pace so far: elapsed per checked port times those left."""
        if progress.checked == 0:
            return None
        now = _instant(self._clock())
        elapsed = now - _instant(progress.started_at)
        left = elapsed / progress.checked * (total - progress.checked)
        finish = (now + left).astimezone(timezone.utc).replace(microsecond=0)
        return finish.isoformat().replace("+00:00", "Z")

    def _expire(self) -> None:
        ledger = self._ledger
        cutoff = _instant(self._clock()) - timedelta(seconds=QUEUE_TTL_SECONDS)
        queue = tuple(q for q in ledger.queue if _instant(q.queued_at) >= cutoff)
        if queue != ledger.queue:
            self._commit(replace(ledger, queue=queue))

    def _next(self) -> tuple[DeepScanRequest, str] | None:
        """The oldest admissible entry whose device has a confirmed current address.

        An entry whose device is not currently monitored stays queued until it
        returns or expires.
        """
        spent = self._spent()
        addresses = dict(monitored_devices(self._inventory()))
        for entry in sorted(self._ledger.queue, key=lambda q: _instant(q.queued_at)):
            if entry.trigger == "automatic" and spent:
                continue
            card = addresses.get(entry.device_id)
            if card is not None:
                return entry, card.address
        return None

    async def _scan(self, entry: DeepScanRequest, address: str) -> None:
        if not private_ipv4(address):
            detail = f"Deep port scans only probe private IPv4 addresses; {address} is not one."
            self._commit(
                self._finish(entry, address, self._clock(), ("failed", detail))
            )
            return
        checked_at = self._clock()
        problem = await self._confirm(entry.device_id, address)
        # A cancel, an upgrade or a newer report may have landed during the read.
        current = self._queued(entry.device_id)
        if current is None or current.request_id != entry.request_id:
            return
        if problem is not None:
            self._commit(self._finish(current, address, checked_at, problem))
            return
        started = self._clock()
        running = replace(current, queued_at=started)
        ledger = self._ledger
        admissions = ledger.admissions
        if running.trigger == "automatic":
            zone = self._zone()
            today = household_day(started, zone)
            admissions = tuple(
                a for a in admissions if household_day(a, zone) == today
            ) + (started,)
        self._commit(
            replace(
                ledger,
                queue=tuple(q for q in ledger.queue if q is not current),
                running=running,
                running_address=address,
                admissions=admissions,
            )
        )
        progress = _Progress(running.request_id, started)
        self._progress = progress
        try:
            outcome = await self._sweep(running, address, progress)
        except Exception as error:
            # Every worker has stopped (the task group cancelled and awaited
            # them). Record the failure so the scan does not stay running, then
            # let run() show it in ``error``. Cancellation is not caught: a
            # shutdown leaves the scan running on disk, and the next start
            # records it interrupted.
            failed: _Outcome = ("failed", f"The scan stopped: {type(error).__name__}.")
            self._commit(self._finish(running, address, started, failed))
            raise
        finally:
            self._progress = None
            self._cancelled.discard(running.request_id)
        state, _ = outcome
        self._commit(
            self._finish(
                running,
                address,
                started,
                outcome,
                tuple(sorted(progress.open_ports)) if state == "complete" else (),
                progress.unanswered if state == "complete" else 0,
            )
        )

    async def _sweep(
        self,
        running: DeepScanRequest,
        address: str,
        progress: _Progress,
    ) -> _Outcome:
        """Probe every port with CONCURRENCY workers, then confirm the identity.

        The workers share one port iterator, so each port is probed once and at
        most CONCURRENCY probes are outstanding. A cancel or an OSError probe
        failure is seen between completions: workers stop taking ports and the
        sweep ends once the probes in flight return. Any other exception in a
        worker makes the task group cancel and await the rest before it raises,
        so no probe outlives the sweep. A cancel that arrives during the closing
        identity read is honoured.
        """
        ports: Iterator[int] = iter(self._ports)
        failures: list[str] = []

        async def worker() -> None:
            for port in ports:
                if failures or running.request_id in self._cancelled:
                    return
                try:
                    answer = await self._probe(address, port, PROBE_TIMEOUT)
                except OSError as error:
                    failures.append(f"probing port {port} failed: {error}")
                    return
                if answer == "open":
                    progress.open_ports.add(port)
                elif answer == "timeout":
                    progress.unanswered += 1
                elif answer != "closed":
                    failures.append(f"port {port} gave an unknown answer")
                    return
                progress.checked += 1

        try:
            async with asyncio.TaskGroup() as group:
                for _ in range(CONCURRENCY):
                    group.create_task(worker())
        except ExceptionGroup as group_error:
            # Surface the worker's own error; the rest were cancellations.
            raise group_error.exceptions[0] from group_error
        total = len(self._ports)
        if failures:
            detail = f"The scan stopped after {progress.checked} of {total} ports; {failures[0]}."
            return "failed", detail[:MAX_DETAIL]
        cancelled: _Outcome = (
            "cancelled",
            f"Cancelled after {progress.checked} of {total} ports.",
        )
        if running.request_id in self._cancelled:
            return cancelled
        problem = await self._confirm(running.device_id, address)
        if running.request_id in self._cancelled:
            return cancelled
        if problem is not None:
            _, why = problem
            return "unreliable", f"After the sweep: {why} No ports are reported."
        return "complete", None

    async def _confirm(self, device_id: str, address: str) -> _Outcome | None:
        """None when the device's MAC is the only identity at ``address``.

        The source is resolved on every call, so a collector replaced by new
        settings is used at once; one removed mid-scan leaves the identity
        unconfirmed rather than trusting a source the operator turned off.
        """
        identities = self._identities()
        if identities is None:
            return "unreliable", _UNAVAILABLE
        sample = await identities.identify((address,), self._clock())
        if address in sample.ambiguous_addresses:
            return "unreliable", f"More than one device claimed {address}."
        found = [d for d in sample.devices if d.address == address]
        if not found:
            return "unreliable", f"Nothing confirmed which device holds {address}."
        if len(found) > 1:
            return "unreliable", f"More than one device claimed {address}."
        expected = device_id.removeprefix("mac:").lower()
        if (found[0].mac or "").lower() != expected:
            return "address_changed", f"{address} now belongs to a different device."
        return None

    def _finish(
        self,
        entry: DeepScanRequest,
        address: str,
        started_at: str,
        outcome: _Outcome,
        open_ports: tuple[int, ...] = (),
        unanswered: int = 0,
    ) -> DeepScanLedger:
        """The ledger with ``entry`` removed from the queue or running, and its result."""
        ledger = self._ledger
        state, detail = outcome
        result = DeepScanResult(
            entry.device_id,
            address,
            entry.request_id,
            entry.trigger,
            entry.report,
            started_at,
            self._clock(),
            state,
            open_ports,
            unanswered,
            detail,
        )
        running, address_now = ledger.running, ledger.running_address
        if running is not None and running.request_id == entry.request_id:
            running, address_now = None, None
        return replace(
            ledger,
            queue=tuple(q for q in ledger.queue if q.request_id != entry.request_id),
            running=running,
            running_address=address_now,
            results=_keep(ledger.results + (result,)),
        )


JOURNAL_UNREADABLE = "Deep port scans need attention: their journal could not be read."


def open_scanner(
    root: Path,
    inventory: Callable[[], DeviceInventory],
    identities: Callable[[], DeviceIdentitySource | None],
    *,
    zone: Callable[[], str] = lambda: "UTC",
) -> tuple[DeepScanner | None, str | None]:
    """Open the scanner over its journal, or report why it cannot be opened.

    An unreadable, linked, non-private or invalid journal disables deep scans
    only: the caller keeps collecting and shows the returned detail. Nothing is
    deleted or rewritten, so the operator can inspect the file.
    """
    try:
        return DeepScanner(
            DeepScanJournal(root), inventory, identities, zone=zone
        ), None
    except (OSError, ValueError):
        return None, JOURNAL_UNREADABLE


def compose_scanner(
    worker: Worker, root: Path, operator_store: bool
) -> tuple[DeepScanner | None, str | None]:
    """Build the scanner the collector serves, or None with the reason.

    Deep scans exist only beside the operator store and TCP collection. The
    identity source (UniFi, its adopted devices and Pi-hole, merged as
    collection merges them) is read from ``worker.collector`` on each use, so
    settings that replace the collector change what confirms a device. A
    journal that cannot be read disables deep scans only; collection is
    unaffected.
    """
    if not operator_store or worker.config.collection.mode != "tcp":
        return None, None
    return open_scanner(
        root,
        worker.device_inventory,
        collector_identities(worker),
        zone=lambda: worker.config.zone,
    )


@dataclass(frozen=True)
class _CollectorIdentities:
    """The collector's merged UniFi and Pi-hole identity read, as a scan source."""

    collector: NetworkCollector

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        return await self.collector.confirm_identity(targets, observed_at)


def collector_identities(
    worker: Worker,
) -> Callable[[], DeviceIdentitySource | None]:
    """Resolve the scan's identity source from ``worker.collector`` on each call.

    None when the current collector has neither UniFi nor Pi-hole, so settings
    that replace the collector change availability at once.
    """

    def identities() -> DeviceIdentitySource | None:
        collector = worker.collector
        if isinstance(collector, NetworkCollector) and collector.confirms_identity:
            return _CollectorIdentities(collector)
        return None

    return identities
