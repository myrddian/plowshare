"""Offline browser preview with synthetic observations and a labelled fixture report.

This fixture does not connect to Plowshare or run an LLM. Production uses cli.py
and SdkPrivacyPort. Bind address and port must be explicitly supplied.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import tempfile
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Awaitable, Callable, cast
from uuid import uuid4

from aiohttp import web
from aiohttp.typedefs import Handler, Middleware
from plowshare.contracts import InformationRevisionDto
from support import (
    EXAMPLES,
    CountingCollector,
    FixturePort,
    MemoryReceipts,
    configuration,
    event,
)
from test_device_profiles import Documents
from test_operator_console import ConsoleFixture, Settings
from test_privacy_issues import IssueDocuments
from test_privacy_issues import fields as issue_fields

from plowshare_privacy.assessment_request import ConcernOutcome
from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.contracts import Configuration, utc_now
from plowshare_privacy.deep_scan import (
    DeepScanner,
    DeepScanResult,
    DeepScanUsage,
    DeepScanView,
    ScanState,
)
from plowshare_privacy.deep_scan import UnknownDevice as UnknownScanDevice
from plowshare_privacy.device_assessments import (
    AssessmentConflict,
    AssessmentLedger,
    AssessmentReport,
    AssessmentSummary,
    AssessmentUsage,
    AssessmentView,
    UnknownDevice,
    summary,
)
from plowshare_privacy.device_dossier import parse_device_id
from plowshare_privacy.device_profiles import (
    DeviceProfiles,
    FileProfileIntents,
    ProfileFields,
)
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.issue_records import FileIssueIntents, PrivacyIssues
from plowshare_privacy.monitor import MonitorView
from plowshare_privacy.operator_state import FileOperatorStore, Preferences
from plowshare_privacy.privacy_issues import IssueLink
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker


def sample_overview(total: int | None = None) -> Middleware:
    """Replace the overview's device list and device facts with the synthetic network."""
    from preview_sample import (
        sample_checked_at,
        sample_devices,
        sample_supplemental,
        sample_system,
        scaled_network,
    )

    @web.middleware
    async def middleware(request: web.Request, handler: Handler) -> web.StreamResponse:
        parts = request.path.split("/")
        if (
            request.method == "GET"
            and len(parts) == 5
            and parts[1:3] == ["api", "devices"]
            and parts[4] == "supplemental"
        ):
            try:
                return web.json_response(sample_supplemental(parts[3]))
            except KeyError:
                raise web.HTTPNotFound(text="Choose a monitored sample device")
        response = await handler(request)
        if request.path != "/api/overview" or not isinstance(response, web.Response):
            return response
        body = json.loads(response.text or "{}")
        # Health agrees with the sample connections it sits beside.
        zone = body["system"]["deployment"]["time_zone"]
        body["system"] = sample_system()
        body["system"]["deployment"]["time_zone"] = zone
        body["health"]["pihole"] = (
            "Latest collection retained; inspect its observation time and DNS window"
        )
        body["health"]["unifi"] = (
            "14 selected addresses associated in the latest collection"
        )
        # Every sample device has quick sightings, so quick checks are on.
        body["quick_presence"] = {
            "enabled": True,
            "last_checked": sample_checked_at(),
            "issues": [],
        }
        if total is None:
            body["devices"] = sample_devices()
        else:
            body["devices"], body["candidates"] = scaled_network(total)
        return web.json_response(body, status=response.status)

    return middleware


class PreviewDeepScans:
    """In-memory stand-in for ``DeepScanner``; nothing is probed.

    The Living room TV starts with one complete result that includes ports
    outside the preview's usual checks. Requesting a scan of another device
    starts a running scan whose progress advances each time the dashboard reads
    it, finishing as complete (or cancelled when cancelled), so every state can
    be clicked through. The web routes only call the methods below, so the fake
    is passed through ``cast`` rather than inheriting the real scanner's
    journal and probe machinery.
    """

    TOTAL = 65535
    STEP = 9000

    def __init__(self, tv_id: str, addresses: dict[str, str]) -> None:
        self.available = True
        self.detail: str | None = None
        self._limit = 2
        self._addresses = addresses
        self._results: dict[str, DeepScanResult] = {}
        # The newest complete result, kept when a later attempt is cancelled.
        self._complete: dict[str, DeepScanResult] = {}
        self._running: dict[str, int] = {}
        # Finished after the TV's sample assessment was requested, so the
        # attention band asks for a fresh assessment of the extra ports.
        from preview_sample import NOW

        self._results[tv_id] = self._complete[tv_id] = self._result(
            tv_id,
            "complete",
            started=NOW - timedelta(minutes=25),
            finished=NOW - timedelta(minutes=5),
            ports=(443, 8008, 8009, 8443, 9000),
            unanswered=2,
        )

    def _result(
        self,
        device_id: str,
        state: ScanState,
        started: datetime | None = None,
        finished: datetime | None = None,
        ports: tuple[int, ...] = (),
        unanswered: int = 0,
    ) -> DeepScanResult:
        end = finished or datetime.now(timezone.utc)
        begin = started or end - timedelta(minutes=2)
        return DeepScanResult(
            device_id,
            self._addresses[device_id],
            str(uuid4()),
            "operator",
            None,
            begin.strftime("%Y-%m-%dT%H:%M:%SZ"),
            end.strftime("%Y-%m-%dT%H:%M:%SZ"),
            state,
            ports,
            unanswered,
            "You cancelled this scan." if state == "cancelled" else None,
        )

    def _known(self, device_id: str) -> str:
        if parse_device_id(device_id) not in self._addresses:
            raise UnknownScanDevice(device_id)
        return device_id

    def usage(self) -> DeepScanUsage:
        return DeepScanUsage(self._limit, 1)

    def view(self, device_id: str) -> DeepScanView:
        self._known(device_id)
        latest = self._results.get(device_id)
        if device_id in self._running:
            checked = min(self._running[device_id], self.TOTAL)
            self._running[device_id] = checked + self.STEP
            if checked >= self.TOTAL:
                del self._running[device_id]
                self._results[device_id] = latest = self._result(
                    device_id, "complete", ports=(22, 443, 8080), unanswered=5
                )
                self._complete[device_id] = latest
            else:
                left = (self.TOTAL - checked) / self.STEP * 5
                finish = datetime.now(timezone.utc) + timedelta(seconds=left)
                return DeepScanView(
                    device_id, True, "running", "operator", checked, self.TOTAL,
                    (443,) if checked > 20000 else (), finish.strftime("%Y-%m-%dT%H:%M:%SZ"),
                    latest, self._complete.get(device_id), None,
                )  # fmt: skip
        return DeepScanView(
            device_id,
            True,
            "idle",
            None,
            0,
            self.TOTAL,
            (),
            None,
            latest,
            self._complete.get(device_id),
            None,
        )

    def views(self) -> tuple[DeepScanView, ...]:
        return tuple(
            self.view(device_id)
            for device_id in self._addresses
            if device_id in self._results or device_id in self._running
        )

    def request(self, device_id: str) -> DeepScanView:
        self._known(device_id)
        self._running.setdefault(device_id, 0)
        return self.view(device_id)

    def cancel(self, device_id: str) -> DeepScanView:
        self._known(device_id)
        if self._running.pop(device_id, None) is not None:
            self._results[device_id] = self._result(device_id, "cancelled")
        return self.view(device_id)

    def set_automatic_limit(self, limit: int) -> None:
        self._limit = limit


class PreviewAssessments:
    """An in-memory ``AssessmentControl`` for the sample network; nothing reaches Plowshare.

    The Living room TV starts awaiting review with a hand-written report, the
    Driveway camera starts failed, and other sample devices start unassessed.
    Assess finishes immediately into review with the same sample report, so
    every state can be clicked through in the browser.
    """

    def __init__(self) -> None:
        from preview_sample import TV_REPORT, sample_assessments, sample_devices

        self._ledger = AssessmentLedger()
        self.error: str | None = None
        self.views: dict[str, AssessmentView] = {}
        self.texts: dict[str, str] = {}
        self.sample_report = TV_REPORT
        for device in sample_devices():
            device_id = device["identity"]["device_id"]
            self.views[device_id] = AssessmentView(
                device_id, "idle", None, None, None, None, None, None, None,
                (), None, True, False, False, None,
            )  # fmt: skip
        for device_id, (row, report) in sample_assessments().items():
            concerns = tuple(ConcernOutcome(**item) for item in row["concerns"])
            self.views[device_id] = AssessmentView(**{**row, "concerns": concerns})
            if report is not None:
                self.texts[device_id] = report

    @property
    def ledger(self) -> AssessmentLedger:
        return self._ledger

    def view(self, device_id: str) -> AssessmentView:
        if parse_device_id(device_id) not in self.views:
            raise UnknownDevice(
                "Choose a monitored device with a confirmed MAC address"
            )
        return self.views[device_id]

    def summaries(self) -> tuple[AssessmentSummary, ...]:
        return tuple(
            summary(
                v.device_id,
                v.state,
                v.pending,
                v.processing,
                v.concerns,
                v.requested_at,
            )
            for v in self.views.values()
            if v.state != "idle"
        )

    def usage(self) -> AssessmentUsage:
        return AssessmentUsage(self._ledger.background_limit, 1, 12)

    def _set(self, view: AssessmentView) -> AssessmentView:
        self.views[view.device_id] = view
        return view

    async def assess(self, device_id: str) -> AssessmentView:
        current = self.view(device_id)
        if not current.can_assess:
            raise AssessmentConflict("This device cannot be assessed now")
        self.texts[device_id] = self.sample_report
        return self._set(
            replace(
                current,
                state="review",
                request_id=str(uuid4()),
                run_id="orc_preview_" + device_id[-2:],
                report=str(uuid4()),
                requested_at=utc_now(),
                decided_at=None,
                review="accepted",
                detail=None,
                can_assess=False,
                can_accept=True,
                can_dismiss=True,
            )
        )

    async def accept(self, device_id: str) -> AssessmentView:
        current = self.view(device_id)
        if current.state != "review":
            raise AssessmentConflict(
                "Only an assessment awaiting review can be accepted"
            )
        return self._set(
            replace(
                current,
                state="accepted",
                decided_at=utc_now(),
                can_assess=True,
                can_accept=False,
                can_dismiss=False,
            )
        )

    async def check(self, device_id: str) -> AssessmentView:
        return self.view(device_id)

    def dismiss(self, device_id: str) -> AssessmentView:
        current = self.view(device_id)
        if current.state != "review":
            raise AssessmentConflict(
                "Only an assessment awaiting review can be dismissed"
            )
        return self._set(
            replace(
                current,
                state="dismissed",
                decided_at=utc_now(),
                can_assess=True,
                can_accept=False,
                can_dismiss=False,
            )
        )

    async def report(self, device_id: str) -> AssessmentReport:
        current = self.view(device_id)
        if current.report is None or current.state not in {"review", "accepted"}:
            raise AssessmentConflict("This device has no assessment report to show")
        return AssessmentReport(current.report, self.texts[device_id])

    def set_background_limit(self, limit: int) -> None:
        if not 0 <= limit <= 12:
            raise ValueError("Choose between 0 and 12 assessments a day")
        self._ledger = replace(self._ledger, background_limit=limit)


def preview_deep_scans() -> DeepScanner:
    from preview_sample import TV_DEVICE, sample_devices

    addresses = {
        row["identity"]["device_id"]: row["address"]
        for row in sample_devices()
        if row["identity"] is not None
    }
    return cast(DeepScanner, PreviewDeepScans(TV_DEVICE, addresses))


async def preview(
    bind: str,
    port: int,
    sample_network: bool = False,
    sample_devices: int | None = None,
) -> None:
    with tempfile.TemporaryDirectory() as folder:
        config = configuration(Path(folder))
        config = replace(
            config,
            collection=replace(
                config.collection, targets=("192.0.2.10",), ports=(443, 8008, 8009)
            ),
        )
        store = FileOperatorStore(Path(folder).resolve())
        store.set_preferences(Preferences(False, 8, 60, False))
        fixture = FixturePort(config)
        receipts = MemoryReceipts()
        worker = Worker(config, fixture, receipts, CountingCollector(config), store)
        fixture.events["schedule.due"] = [event()]
        await worker.poll()
        if config.collection.observations_file is None:
            raise ValueError("Preview requires a fixture")
        config.collection.observations_file.write_bytes(
            (EXAMPLES / "observations-after.json").read_bytes()
        )
        fixture.events["schedule.due"] = [event("schedule:second-fixture")]
        await worker.poll()
        revision = receipts.all()[-1].revision
        if revision is None:
            raise ValueError("Preview evidence missing")
        report_id = str(uuid4())
        fixture.documents[report_id] = (
            InformationRevisionDto(
                id=report_id,
                kind="report",
                title="Synthetic preview report — no LLM was run",
                report_status="draft",
                inputs=(revision,),
            ),
            "SYNTHETIC BROWSER PREVIEW — no Plowshare server or LLM was used.\n\nThe fixture contains a new observed DNS destination and a changed TCP response. Those observations do not establish what was transmitted or whether the destination is harmful. A real investigation would read retained evidence through Plowshare and save its report with the source dependencies.",
        )

        async def recover(callback: Callable[[], Awaitable[None]]) -> None:
            await worker.reconcile()
            await callback()

        console = OperatorConsole(
            Path(folder).resolve(),
            worker,
            ConsoleFixture(),
            Settings(),
            LocalDeviceDiscovery(Path(folder).resolve()),
            store,
            recover,
            FileManualInvestigations(Path(folder).resolve()),
        )

        # The sample household keeps Sydney time until Settings changes it.
        worker.config = replace(worker.config, time_zone="Australia/Sydney")

        class PreviewSettings(Settings):
            def view(self) -> MonitorView:
                # The sample household uses Pi-hole, UniFi, quick checks, DNS
                # profiles and descriptions, so Settings says so too.
                return MonitorView(
                    True,
                    config.collection.targets,
                    (443,),
                    True,
                    "Synthetic fixture settings",
                    pihole_configured=True,
                    unifi_configured=True,
                    quick_scan=True,
                    dns_profiles=True,
                    device_descriptions=True,
                )

            async def save_time_zone(self, zone: str | None) -> Configuration:
                # In memory only, like the rest of the sample household.
                worker.config = replace(worker.config, time_zone=zone)
                return worker.config

        issues = PrivacyIssues(
            IssueDocuments(),
            FileIssueIntents(Path(folder).resolve(), config.project, config.collector),
        )
        issue_id = str(uuid4())
        issue_receipt = await issues.save(issue_id, str(uuid4()), None, issue_fields())
        assert issue_receipt.revision is not None
        profiles = DeviceProfiles(
            Documents(),
            FileProfileIntents(
                Path(folder).resolve(), config.project, config.collector
            ),
            receipts,
            lambda: config.collection.targets,
            issues,
        )
        await profiles.save(
            str(uuid4()),
            str(uuid4()),
            None,
            ProfileFields(
                "Living room TV",
                "Household",
                "Operator supplied model",
                "Streaming and home media",
                "HTTPS for media and firmware updates; investigate unexplained services",
                "Synthetic preview profile; physical identity is not verified",
                config.collection.targets,
                (
                    IssueLink(
                        issue_id,
                        issue_receipt.revision,
                        "potentially_affected",
                        "proposed",
                        "Synthetic issue; confirm firmware and attribution before deciding",
                        (revision,),
                    ),
                ),
            ),
        )
        app = application(
            worker,
            "fixture-web-token-1234567890",
            console=console,
            profiles=profiles,
            issues=issues,
            settings=PreviewSettings(),
            assessments=PreviewAssessments()
            if sample_network or sample_devices is not None
            else None,
            deep_scans=preview_deep_scans() if sample_network else None,
        )
        if sample_network or sample_devices is not None:
            app.middlewares.append(sample_overview(sample_devices))
        runner = web.AppRunner(app, access_log=None)
        await runner.setup()
        stop = asyncio.Event()
        task = asyncio.create_task(worker.run(stop))
        try:
            await web.TCPSite(runner, bind, port).start()
            print(
                "Synthetic preview ready. Web token: fixture-web-token-1234567890",
                flush=True,
            )
            await stop.wait()
        finally:
            stop.set()
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument(
        "--sample-network",
        action="store_true",
        help="Show a synthetic household of devices for design review",
    )
    parser.add_argument(
        "--sample-devices",
        type=int,
        help="Repeat the synthetic devices up to this many, plus 160 candidates",
    )
    arguments = parser.parse_args()
    asyncio.run(
        preview(
            arguments.bind,
            arguments.port,
            arguments.sample_network,
            arguments.sample_devices,
        )
    )
