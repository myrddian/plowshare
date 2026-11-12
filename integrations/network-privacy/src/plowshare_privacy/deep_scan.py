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
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

from .contracts import (
    integer,
    items,
    load_json,
    object_fields,
    parse_json,
    text,
    timestamp,
    uuid,
)
from .device_dossier import parse_device_id
from .private_files import replace_private

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
QUEUE_TTL_SECONDS = 7 * 86400
MAX_DEVICES = 256
MAX_LEDGER_BYTES = 2 * 1024 * 1024
MAX_DETAIL = 1600

_TRIGGERS = frozenset({"automatic", "operator"})
_STATES = frozenset(
    {"complete", "cancelled", "interrupted", "unreliable", "address_changed", "failed"}
)


def _trigger(value: object) -> Trigger:
    if value not in _TRIGGERS:
        raise ValueError("Invalid deep scan trigger")
    return value  # type: ignore[return-value]


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
        if self.state not in _STATES:
            raise ValueError("Invalid deep scan state")
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
            row["state"],  # type: ignore[arg-type]
            tuple(integer(p, 1, 65535) for p in items(row["open_ports"], 65535)),
            integer(row["unanswered"], 0, 65535),
            text(row["detail"], MAX_DETAIL) if row["detail"] is not None else None,
        )


@dataclass(frozen=True)
class DeepScanLedger:
    # Automatic scans admitted per household day; 0 turns automatic scans off.
    automatic_limit: int = 0
    queue: tuple[DeepScanRequest, ...] = ()
    running: DeepScanRequest | None = None
    # Newest last; at most KEEP_RESULTS per device.
    results: tuple[DeepScanResult, ...] = ()
    # Automatic admission times; only the current household day's are kept.
    admissions: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        integer(self.automatic_limit, 0, MAX_AUTOMATIC)
        if (
            len(self.queue) > MAX_DEVICES
            or len(self.results) > MAX_DEVICES * KEEP_RESULTS
        ):
            raise ValueError("Too many deep scan records")
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
            value, {"automatic_limit", "queue", "running", "results", "admissions"}
        )
        return cls(
            integer(row["automatic_limit"], 0, MAX_AUTOMATIC),
            tuple(DeepScanRequest.decode(v) for v in items(row["queue"], MAX_DEVICES)),
            DeepScanRequest.decode(row["running"])
            if row["running"] is not None
            else None,
            tuple(
                DeepScanResult.decode(v)
                for v in items(row["results"], MAX_DEVICES * KEEP_RESULTS)
            ),
            tuple(timestamp(v) for v in items(row["admissions"], MAX_AUTOMATIC)),
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
