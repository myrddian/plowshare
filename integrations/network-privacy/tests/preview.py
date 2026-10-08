"""Offline browser preview with synthetic observations and a labelled fixture report.

This fixture does not connect to Plowshare or run an LLM. Production uses cli.py
and SdkPrivacyPort. Bind address and port must be explicitly supplied.
"""

from __future__ import annotations

import argparse
import asyncio
import tempfile
from pathlib import Path
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

from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker


async def preview(bind: str, port: int) -> None:
    with tempfile.TemporaryDirectory() as folder:
        config = configuration(Path(folder))
        fixture = FixturePort(config)
        receipts = MemoryReceipts()
        worker = Worker(config, fixture, receipts, CountingCollector(config))
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
        runner = web.AppRunner(
            application(worker, "fixture-web-token-1234567890"), access_log=None
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
