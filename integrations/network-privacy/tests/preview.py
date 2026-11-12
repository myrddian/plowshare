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
from pathlib import Path
from typing import Awaitable, Callable
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
        if total is None:
            body["devices"] = sample_devices()
        else:
            body["devices"], body["candidates"] = scaled_network(total)
        return web.json_response(body, status=response.status)

    return middleware


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
            summary(v.device_id, v.state, v.pending, v.processing, v.concerns)
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


async def preview(
    bind: str,
    port: int,
    sample_network: bool = False,
    sample_devices: int | None = None,
) -> None:
    with tempfile.TemporaryDirectory() as folder:
        config = configuration(Path(folder))
        config = replace(
            config, collection=replace(config.collection, targets=("192.0.2.10",))
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
                return MonitorView(
                    True,
                    config.collection.targets,
                    (443,),
                    True,
                    "Synthetic fixture settings",
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
