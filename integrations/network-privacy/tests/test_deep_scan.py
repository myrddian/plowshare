from __future__ import annotations

import asyncio
import errno
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from typing import Callable
from unittest.mock import patch
from uuid import uuid4

from plowshare_privacy.deep_scan import (
    DeepScanJournal,
    DeepScanLedger,
    DeepScanRequest,
    DeepScanResult,
    probe_port,
)

LAMP = "mac:aa:bb:cc:dd:ee:01"
NOW = "2026-11-12T10:00:00Z"


def request(**changes: object) -> DeepScanRequest:
    base = DeepScanRequest(LAMP, "operator", None, str(uuid4()), NOW)
    return replace(base, **changes)  # type: ignore[arg-type]


def result(**changes: object) -> DeepScanResult:
    base = DeepScanResult(
        LAMP,
        "10.1.2.11",
        str(uuid4()),
        "automatic",
        str(uuid4()),
        NOW,
        NOW,
        "complete",
        (22, 80),
        3,
        None,
    )
    return replace(base, **changes)  # type: ignore[arg-type]


def roundtrip(ledger: DeepScanLedger) -> DeepScanLedger:
    return DeepScanLedger.decode(json.loads(json.dumps(asdict(ledger))))


class DeepScanRecordTests(unittest.TestCase):
    def test_the_ledger_round_trips_and_rejects_bad_records(self) -> None:
        ledger = DeepScanLedger(
            automatic_limit=4,
            queue=(request(),),
            running=request(trigger="automatic", report=str(uuid4())),
            results=(result(), result(state="failed", open_ports=(), detail="boom")),
            admissions=(NOW,),
        )
        self.assertEqual(ledger, roundtrip(ledger))
        bad: list[Callable[[], DeepScanLedger]] = [
            lambda: DeepScanLedger(automatic_limit=13),
            lambda: DeepScanLedger(automatic_limit=-1),
            lambda: DeepScanLedger(results=(result(open_ports=(80, 22)),)),
            lambda: DeepScanLedger(results=(result(open_ports=(22, 22)),)),
            lambda: DeepScanLedger(results=(result(open_ports=(70000,)),)),
            lambda: DeepScanLedger(results=(result(state="failed"),)),
            lambda: DeepScanLedger(results=(result(unanswered=65536),)),
            lambda: DeepScanLedger(results=(result(address="not-an-address"),)),
            lambda: DeepScanLedger(results=(result(request_id="nope"),)),
            lambda: DeepScanLedger(queue=(request(request_id="nope"),)),
            lambda: DeepScanLedger(queue=(request(device_id="mac:zz"),)),
            lambda: DeepScanLedger(queue=(request(report="nope"),)),
            lambda: DeepScanLedger(queue=(request(queued_at="yesterday"),)),
            lambda: DeepScanLedger(results=tuple(result() for _ in range(4))),
        ]
        for build in bad:
            with self.subTest(build=build), self.assertRaises(ValueError):
                roundtrip(build())

    def test_the_journal_is_private_and_refuses_links(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            journal = DeepScanJournal(root)
            self.assertEqual(DeepScanLedger(), journal.read())
            ledger = DeepScanLedger(automatic_limit=2, results=(result(),))
            journal.save(ledger)
            self.assertEqual(0o600, journal.path.stat().st_mode & 0o777)
            self.assertEqual(ledger, journal.read())
            journal.path.chmod(0o644)
            with self.assertRaises(ValueError):
                journal.read()
            journal.path.unlink()
            (root / "elsewhere.json").write_text("{}")
            journal.path.symlink_to(root / "elsewhere.json")
            with self.assertRaises(ValueError):
                journal.read()
            with self.assertRaises(ValueError):
                journal.save(ledger)


class ProbePortTests(unittest.IsolatedAsyncioTestCase):
    async def test_probe_port_reports_open_closed_and_timeout(self) -> None:
        server = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        async with server:
            self.assertEqual("open", await probe_port("127.0.0.1", port, 1.0))
        self.assertEqual("closed", await probe_port("127.0.0.1", port, 1.0))
        with patch("asyncio.open_connection", side_effect=TimeoutError):
            self.assertEqual("timeout", await probe_port("127.0.0.1", 9, 1.0))
        with patch(
            "asyncio.open_connection", side_effect=OSError(errno.EHOSTUNREACH, "x")
        ):
            self.assertEqual("timeout", await probe_port("127.0.0.1", 9, 1.0))

    async def test_socket_exhaustion_fails_the_scan_instead_of_reporting_closed(
        self,
    ) -> None:
        with patch(
            "asyncio.open_connection", side_effect=OSError(errno.ENOBUFS, "full")
        ):
            with self.assertRaises(OSError):
                await probe_port("127.0.0.1", 9, 1.0)


if __name__ == "__main__":
    unittest.main()
