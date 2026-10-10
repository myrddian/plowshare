"""Offline browser preview with synthetic observations and a labelled fixture report.

This fixture does not connect to Plowshare or run an LLM. Production uses cli.py
and SdkPrivacyPort. Bind address and port must be explicitly supplied.
"""

from __future__ import annotations

import argparse
import asyncio
import tempfile
from dataclasses import replace
from pathlib import Path
from typing import Awaitable, Callable
from uuid import uuid4

from aiohttp import web
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

from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.device_profiles import (
    DeviceProfiles,
    FileProfileIntents,
    ProfileFields,
)
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.monitor import MonitorView
from plowshare_privacy.operator_state import FileOperatorStore, Preferences
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker


async def preview(bind: str, port: int) -> None:
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

        class PreviewSettings(Settings):
            def view(self) -> MonitorView:
                return MonitorView(
                    True,
                    config.collection.targets,
                    (443,),
                    True,
                    "Synthetic fixture settings",
                )

        profiles = DeviceProfiles(
            Documents(),
            FileProfileIntents(
                Path(folder).resolve(), config.project, config.collector
            ),
            receipts,
            lambda: config.collection.targets,
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
            ),
        )
        runner = web.AppRunner(
            application(
                worker,
                "fixture-web-token-1234567890",
                console=console,
                profiles=profiles,
                settings=PreviewSettings(),
            ),
            access_log=None,
        )
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
    arguments = parser.parse_args()
    asyncio.run(preview(arguments.bind, arguments.port))
