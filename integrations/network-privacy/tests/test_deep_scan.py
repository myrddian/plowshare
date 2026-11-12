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

from test_device_dossier import card

from plowshare_privacy.contracts import DeviceIdentity
from plowshare_privacy.deep_scan import (
    DeepScanJournal,
    DeepScanLedger,
    DeepScanner,
    DeepScanRequest,
    DeepScanResult,
    HandledReport,
    UnknownDevice,
    probe_port,
)
from plowshare_privacy.device_inventory import DeviceCard, DeviceInventory
from plowshare_privacy.identity_source import IdentitySample

LAMP = "mac:aa:bb:cc:dd:ee:01"
TV = "mac:aa:bb:cc:dd:ee:02"
FAN = "mac:aa:bb:cc:dd:ee:03"
OTHER = "aa:bb:cc:dd:ee:99"
NOW = "2026-11-12T10:00:00Z"
NOW_REPORT = "0b6f1f5e-2d55-4a8c-9c1e-6a3c2f0f7d11"
ADDRESSES = {LAMP: "10.1.2.11", TV: "10.1.2.12", FAN: "10.1.2.13"}


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
            running=request(device_id=TV, trigger="automatic", report=str(uuid4())),
            running_address="10.1.2.12",
            results=(result(), result(state="failed", open_ports=(), detail="boom")),
            admissions=(NOW,),
            handled=(HandledReport(LAMP, str(uuid4())),),
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
            lambda: DeepScanLedger(running=request()),
            lambda: DeepScanLedger(running_address="10.1.2.11"),
            lambda: DeepScanLedger(running=request(), running_address="8.8.8.8"),
            lambda: DeepScanLedger(handled=(HandledReport(LAMP, "nope"),)),
            lambda: DeepScanLedger(handled=(HandledReport(LAMP, NOW_REPORT),) * 2),
            lambda: DeepScanLedger(
                handled=tuple(HandledReport(LAMP, str(uuid4())) for _ in range(4))
            ),
        ]
        for build in bad:
            with self.subTest(build=build), self.assertRaises(ValueError):
                roundtrip(build())

    def test_the_ledger_keeps_one_queued_or_running_scan_per_device(self) -> None:
        bad: list[Callable[[], DeepScanLedger]] = [
            lambda: DeepScanLedger(queue=(request(), request())),
            lambda: DeepScanLedger(
                queue=(request(),), running=request(), running_address="10.1.2.11"
            ),
        ]
        # Each is checked at decode as well as at construction.
        for build in bad:
            with self.subTest(build=build), self.assertRaises(ValueError):
                build()
        valid = DeepScanLedger(
            queue=(request(),),
            running=request(device_id=TV),
            running_address="10.1.2.12",
        )
        for queue, running, address in (
            ([asdict(request()), asdict(request())], None, None),
            ([asdict(request())], asdict(request()), "10.1.2.11"),
        ):
            row = asdict(valid) | {
                "queue": queue,
                "running": running,
                "running_address": address,
            }
            with self.subTest(row=row), self.assertRaises(ValueError):
                DeepScanLedger.decode(json.loads(json.dumps(row)))

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


def monitored(device_id: str, address: str) -> DeviceCard:
    who = DeviceIdentity(
        address,
        device_id,
        None,
        None,
        device_id.removeprefix("mac:"),
        None,
        "unifi",
        NOW,
        NOW,
    )
    return replace(card(who=who), address=address)


def sighting(address: str, mac: str) -> DeviceIdentity:
    return DeviceIdentity(
        address, "mac:" + mac, None, None, mac, None, "unifi", NOW, NOW
    )


class Identities:
    """Answers each address with its expected MAC unless a scripted answer is queued."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []
        self.on_call: Callable[[int], None] = lambda count: None
        # Consumed one per call: a MAC, None for no answer, or "ambiguous".
        self.script: list[str | None] = []

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        self.calls.append(targets)
        self.on_call(len(self.calls))
        [address] = targets
        expected = next(d for d, a in ADDRESSES.items() if a == address)
        answer = self.script.pop(0) if self.script else expected.removeprefix("mac:")
        if answer is None:
            return IdentitySample()
        if answer == "ambiguous":
            return IdentitySample(
                (sighting(address, expected.removeprefix("mac:")),),
                frozenset({address}),
            )
        return IdentitySample((sighting(address, answer),))


class Probe:
    def __init__(self, open_ports: set[int]) -> None:
        self.open_ports = open_ports
        self.calls: list[tuple[str, int]] = []
        self.after: Callable[[int], None] = lambda count: None

    async def __call__(self, address: str, port: int, timeout: float) -> str:
        self.calls.append((address, port))
        self.after(len(self.calls))
        return "open" if port in self.open_ports else "closed"


class DeepScannerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.journal = DeepScanJournal(Path(self.directory.name).resolve())
        self.now = NOW
        self.zone = "UTC"
        self.cards: tuple[DeviceCard, ...] = tuple(
            monitored(d, a) for d, a in ADDRESSES.items()
        )
        self.identities = Identities()
        self.probe = Probe({22, 80})
        self.scanner = self.build()

    def build(self, *, identities: bool = True, ports: int = 100) -> DeepScanner:
        return DeepScanner(
            self.journal,
            lambda: DeviceInventory(self.cards),
            self.identities if identities else None,
            probe=self.probe,
            ports=range(1, ports + 1),
            clock=lambda: self.now,
            zone=lambda: self.zone,
        )

    def results(self, device_id: str = LAMP) -> list[DeepScanResult]:
        return [r for r in self.scanner.ledger.results if r.device_id == device_id]

    async def test_an_operator_request_scans_and_keeps_the_open_ports(self) -> None:
        view = self.scanner.request(LAMP)
        self.assertEqual(
            ("queued", "operator", 100), (view.state, view.trigger, view.total)
        )
        await self.scanner.poll()
        [done] = self.results()
        self.assertEqual("complete", done.state)
        self.assertEqual((22, 80), done.open_ports)
        self.assertEqual(0, done.unanswered)
        self.assertEqual("10.1.2.11", done.address)
        self.assertEqual(100, len(self.probe.calls))
        self.assertEqual(done, self.scanner.latest(LAMP))
        self.assertEqual("idle", self.scanner.view(LAMP).state)
        self.assertEqual(done, self.scanner.view(LAMP).latest)
        # Identity was confirmed before and after the sweep.
        self.assertEqual(2, len(self.identities.calls))
        self.assertEqual(self.scanner.ledger, self.journal.read())
        with self.assertRaises(UnknownDevice):
            self.scanner.request("mac:aa:bb:cc:dd:ee:77")

    async def test_automatic_triggers_need_a_limit_and_respect_it(self) -> None:
        self.scanner.flagged(LAMP, str(uuid4()))
        self.assertEqual((), self.scanner.ledger.queue)
        self.zone = "Australia/Sydney"
        self.scanner.set_automatic_limit(1)
        self.scanner.flagged(LAMP, str(uuid4()))
        self.now = "2026-11-12T10:00:01Z"
        self.scanner.flagged(TV, str(uuid4()))
        await self.scanner.poll()
        await self.scanner.poll()
        self.assertEqual(1, len(self.results(LAMP)))
        self.assertEqual([], self.results(TV))
        self.assertEqual(1, self.scanner.usage().used_today)
        self.assertEqual("queued", self.scanner.view(TV).state)
        self.assertIsNotNone(self.scanner.view(TV).detail)
        # 13:00Z is midnight in Sydney (UTC+11 in November): a new household day.
        self.now = "2026-11-12T12:59:59Z"
        await self.scanner.poll()
        self.assertEqual([], self.results(TV))
        self.now = "2026-11-12T13:00:01Z"
        self.assertEqual(0, self.scanner.usage().used_today)
        await self.scanner.poll()
        self.assertEqual("complete", self.results(TV)[0].state)
        self.assertEqual(1, self.scanner.usage().used_today)

    async def test_operator_requests_are_not_limited_per_day(self) -> None:
        self.scanner.set_automatic_limit(1)
        self.scanner.flagged(FAN, str(uuid4()))
        await self.scanner.poll()
        self.assertEqual(1, self.scanner.usage().used_today)
        for device in (LAMP, TV, FAN):
            self.scanner.request(device)
        for _ in range(3):
            await self.scanner.poll()
        for device in (LAMP, TV):
            self.assertEqual(["operator"], [r.trigger for r in self.results(device)])
        self.assertEqual(
            ["automatic", "operator"], [r.trigger for r in self.results(FAN)]
        )
        self.assertEqual(1, self.scanner.usage().used_today)

    async def test_the_same_report_triggers_once_and_a_newer_report_replaces_a_queued_one(
        self,
    ) -> None:
        self.scanner.set_automatic_limit(4)
        first, second = str(uuid4()), str(uuid4())
        self.scanner.flagged(LAMP, first)
        self.scanner.flagged(LAMP, first)
        [queued] = self.scanner.ledger.queue
        self.assertEqual(first, queued.report)
        self.scanner.flagged(LAMP, second)
        [replaced] = self.scanner.ledger.queue
        self.assertEqual(second, replaced.report)
        await self.scanner.poll()
        # A scanned report does not trigger again.
        self.scanner.flagged(LAMP, second)
        self.assertEqual((), self.scanner.ledger.queue)
        self.assertEqual([second], [r.report for r in self.results()])
        # An operator request is not downgraded by a later automatic trigger.
        self.scanner.request(TV)
        self.scanner.flagged(TV, str(uuid4()))
        [operator] = self.scanner.ledger.queue
        self.assertEqual(("operator", None), (operator.trigger, operator.report))

    async def test_an_address_that_changes_during_the_scan_is_unreliable(self) -> None:
        self.identities.script = [LAMP.removeprefix("mac:"), OTHER]
        self.scanner.request(LAMP)
        await self.scanner.poll()
        [done] = self.results()
        self.assertEqual("unreliable", done.state)
        self.assertEqual((), done.open_ports)
        self.assertIsNone(self.scanner.latest(LAMP))

    async def test_a_wrong_mac_before_the_scan_does_not_probe(self) -> None:
        self.identities.script = [OTHER]
        self.scanner.request(LAMP)
        await self.scanner.poll()
        self.assertEqual(0, len(self.probe.calls))
        self.assertEqual(["address_changed"], [r.state for r in self.results()])
        self.assertEqual((), self.scanner.ledger.queue)
        for answer in (None, "ambiguous"):
            with self.subTest(answer=answer):
                self.identities.script = [answer]
                self.scanner.request(TV)
                await self.scanner.poll()
                self.assertEqual("unreliable", self.results(TV)[-1].state)
        self.assertEqual(0, len(self.probe.calls))

    async def test_cancelling_a_queued_request_removes_it(self) -> None:
        self.scanner.request(LAMP)
        view = self.scanner.cancel(LAMP)
        self.assertEqual("idle", view.state)
        self.assertEqual((), self.journal.read().queue)
        await self.scanner.poll()
        self.assertEqual(0, len(self.probe.calls))
        self.assertEqual([], self.results())

    async def test_cancelling_a_running_scan_records_cancelled(self) -> None:
        seen: list[str] = []

        def cancel_at_ten(count: int) -> None:
            if count == 10:
                running = self.scanner.view(LAMP)
                seen.append(running.state)
                self.assertEqual(9, running.checked)
                self.assertEqual((), self.journal.read().queue)
                self.assertIsNotNone(self.journal.read().running)
                self.scanner.cancel(LAMP)

        self.probe.after = cancel_at_ten
        self.scanner.request(LAMP)
        await self.scanner.poll()
        self.assertEqual(["running"], seen)
        [done] = self.results()
        self.assertEqual("cancelled", done.state)
        self.assertEqual((), done.open_ports)
        self.assertLess(len(self.probe.calls), 100)
        self.assertIsNone(self.journal.read().running)

    async def test_progress_estimates_the_finish_from_the_pace_so_far(self) -> None:
        views = []

        def tick(count: int) -> None:
            self.now = f"2026-11-12T10:{(count - 1) // 60:02d}:{(count - 1) % 60:02d}Z"
            if count == 51:
                views.append(self.scanner.view(LAMP))

        self.probe.after = tick
        self.scanner.request(LAMP)
        await self.scanner.poll()
        [view] = views
        # Started at 10:00:00; 50 ports took 50 s, so the other 50 need 50 s more.
        self.assertEqual((50, 100, (22,)), (view.checked, view.total, view.open_so_far))
        self.assertEqual("2026-11-12T10:01:40Z", view.estimated_finish)

    async def test_a_scan_running_at_restart_is_interrupted_not_resumed(self) -> None:
        running = request(device_id=LAMP)
        self.journal.save(DeepScanLedger(running=running, running_address="10.1.2.11"))
        # The address comes from the ledger, not from a card that may be gone.
        self.cards = ()
        self.scanner = self.build()
        [done] = self.results()
        self.assertEqual("interrupted", done.state)
        self.assertEqual(running.request_id, done.request_id)
        self.assertEqual("10.1.2.11", done.address)
        self.assertEqual(
            "The collector restarted during this scan; it was not resumed.", done.detail
        )
        self.assertIsNone(self.journal.read().running)
        await self.scanner.poll()
        self.assertEqual(0, len(self.probe.calls))

    async def test_a_cancel_during_the_closing_identity_read_is_honoured(self) -> None:
        def cancel_on_second(count: int) -> None:
            if count == 2:
                self.scanner.cancel(LAMP)

        self.identities.on_call = cancel_on_second
        self.scanner.request(LAMP)
        await self.scanner.poll()
        [done] = self.results()
        self.assertEqual(("cancelled", ()), (done.state, done.open_ports))

    async def test_a_report_triggers_once_whatever_happens_to_its_entry(self) -> None:
        self.scanner.set_automatic_limit(4)
        report = str(uuid4())
        # Cancelled by the operator.
        self.scanner.flagged(LAMP, report)
        self.scanner.cancel(LAMP)
        self.scanner.flagged(LAMP, report)
        self.assertEqual((), self.scanner.ledger.queue)
        # Expired after seven days.
        report = str(uuid4())
        self.scanner.flagged(LAMP, report)
        self.now = "2026-11-19T10:00:01Z"
        await self.scanner.poll()
        self.assertEqual([], self.results())
        self.scanner.flagged(LAMP, report)
        self.assertEqual((), self.scanner.ledger.queue)
        # Discarded by a limit of 0, then the limit is raised again.
        report = str(uuid4())
        self.scanner.flagged(LAMP, report)
        self.scanner.set_automatic_limit(0)
        self.scanner.set_automatic_limit(4)
        self.scanner.flagged(LAMP, report)
        self.assertEqual((), self.scanner.ledger.queue)
        # Upgraded to an operator request: the report is kept and not rescanned.
        report = str(uuid4())
        self.scanner.flagged(TV, report)
        self.scanner.request(TV)
        [upgraded] = self.scanner.ledger.queue
        self.assertEqual(("operator", report), (upgraded.trigger, upgraded.report))
        await self.scanner.poll()
        self.scanner.flagged(TV, report)
        self.assertEqual((), self.scanner.ledger.queue)
        self.assertEqual([report], [r.report for r in self.results(TV)])
        # The memory survives a restart and stays bounded per device.
        self.scanner = self.build()
        self.scanner.flagged(TV, report)
        self.assertEqual((), self.scanner.ledger.queue)
        lamp = [h for h in self.journal.read().handled if h.device_id == LAMP]
        self.assertEqual(3, len(lamp))

    async def test_an_unexpected_probe_error_stops_every_worker(self) -> None:
        calls: list[int] = []

        async def explodes(address: str, port: int, timeout: float) -> str:
            calls.append(port)
            number = len(calls)
            await asyncio.sleep(0)
            if number == 5:
                raise RuntimeError("bug")
            await asyncio.sleep(0)
            return "closed"

        self.probe = explodes  # type: ignore[assignment]
        # Enough ports that orphaned workers would still have work after poll.
        self.scanner = self.build(ports=2000)
        self.scanner.request(LAMP)
        with self.assertRaises(RuntimeError):
            await self.scanner.poll()
        stopped_at = len(calls)
        for _ in range(10):
            await asyncio.sleep(0)
        # No orphaned worker keeps probing after the scan is recorded failed.
        self.assertEqual(stopped_at, len(calls))
        [done] = self.results()
        self.assertEqual("failed", done.state)
        self.assertIn("RuntimeError", done.detail or "")
        self.assertIsNone(self.journal.read().running)

    async def test_run_shows_an_unexpected_error_and_keeps_serving(self) -> None:
        async def explodes(address: str, port: int, timeout: float) -> str:
            raise RuntimeError("bug")

        self.probe = explodes  # type: ignore[assignment]
        self.scanner = self.build()
        self.scanner.request(LAMP)
        stop = asyncio.Event()
        task = asyncio.create_task(self.scanner.run(stop))
        for _ in range(50):
            await asyncio.sleep(0)
            if self.scanner.error is not None:
                break
        self.assertIn("RuntimeError", self.scanner.error or "")
        self.assertFalse(task.done())
        stop.set()
        await task

    async def test_limit_zero_discards_automatic_but_keeps_operator_requests(
        self,
    ) -> None:
        self.scanner.set_automatic_limit(2)
        self.scanner.flagged(LAMP, str(uuid4()))
        self.scanner.request(TV)
        self.scanner.set_automatic_limit(0)
        self.assertEqual([TV], [r.device_id for r in self.journal.read().queue])
        self.assertEqual(0, self.scanner.usage().automatic_limit)
        with self.assertRaises(ValueError):
            self.scanner.set_automatic_limit(13)

    async def test_queued_triggers_expire_after_seven_days(self) -> None:
        self.scanner.set_automatic_limit(1)
        self.scanner.request(LAMP)
        self.now = "2026-11-12T11:00:00Z"
        self.scanner.flagged(TV, str(uuid4()))
        self.now = "2026-11-19T10:00:00Z"
        self.scanner = self.build()  # The queue survives a restart.
        self.assertEqual(2, len(self.scanner.ledger.queue))
        self.now = "2026-11-19T10:00:01Z"
        await self.scanner.poll()
        self.assertEqual([], self.results(LAMP))
        self.assertEqual("complete", self.results(TV)[0].state)
        self.assertEqual((), self.journal.read().queue)

    async def test_a_public_address_is_never_probed(self) -> None:
        self.cards = (monitored(LAMP, "8.8.8.8"),)
        self.scanner.request(LAMP)
        await self.scanner.poll()
        self.assertEqual(0, len(self.probe.calls))
        self.assertEqual(0, len(self.identities.calls))
        [done] = self.results()
        self.assertEqual("failed", done.state)
        self.assertIn("private IPv4", done.detail or "")

    async def test_without_an_identity_source_deep_scans_are_unavailable(
        self,
    ) -> None:
        self.scanner = self.build(identities=False)
        self.assertFalse(self.scanner.available)
        self.assertFalse(self.scanner.view(LAMP).available)
        with self.assertRaisesRegex(ValueError, "needs UniFi or Pi-hole"):
            self.scanner.request(LAMP)

    async def test_a_failing_probe_records_a_failed_scan(self) -> None:
        async def broken(address: str, port: int, timeout: float) -> str:
            raise OSError(errno.ENOBUFS, "full")

        self.probe = broken  # type: ignore[assignment]
        self.scanner = self.build()
        self.scanner.request(LAMP)
        await self.scanner.poll()
        [done] = self.results()
        self.assertEqual("failed", done.state)
        self.assertIsNone(self.journal.read().running)

    async def test_views_cover_queued_running_and_scanned_devices(self) -> None:
        self.scanner.request(LAMP)
        await self.scanner.poll()
        self.scanner.request(TV)
        self.assertEqual(
            [(TV, "queued"), (LAMP, "idle")],
            [(v.device_id, v.state) for v in self.scanner.views()],
        )


if __name__ == "__main__":
    unittest.main()
