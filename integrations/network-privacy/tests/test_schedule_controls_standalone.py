"""Pause/Resume work on installs without the bootstrap ``deployment/`` layout.

An install that was not made by the bootstrap installer has only its collector
configuration (named by ``--config``, not necessarily ``collector.json``). The
runtime controls must work there; the reviewed schedule editor must refuse.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer
from support import (
    EXAMPLES,
    CountingCollector,
    FixturePort,
    MemoryReceipts,
    configuration,
)
from test_operator_console import ConsoleFixture, Settings

from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.console_port import ScheduleView
from plowshare_privacy.contracts import Configuration
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.monitor import SettingsBusy
from plowshare_privacy.operator_state import FileOperatorStore
from plowshare_privacy.schedule_editor import ScheduleEditor
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "fixture-standalone-controls-token"
REFUSAL = (
    "The schedule editor needs the bootstrap deployment layout; "
    "Pause and Resume remain available."
)


class FakeRuntime:
    """Records the configuration it was built for and every pause it receives."""

    def __init__(self) -> None:
        self.paused = False
        self.sent: list[bool] = []
        self.configs: list[Configuration] = []

    def __call__(self, config: Configuration) -> FakeRuntime:
        self.configs.append(config)
        return self

    async def schedule(self) -> ScheduleView:
        return ScheduleView("fixture", "0 */15 * * * *", "UTC", self.paused, None)

    async def set_paused(self, paused: bool) -> None:
        self.sent.append(paused)
        self.paused = paused


async def _recover(*_: object) -> None:
    return None


class StandaloneControlsTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = self.root / "collector.json"
        row = json.loads((EXAMPLES / "config.json").read_text())
        row["stateDirectory"] = str(self.root / "state")
        self.config.write_text(json.dumps(row))
        self.runtime = FakeRuntime()

    def editor(self, config: Path | None = None) -> ScheduleEditor:
        return ScheduleEditor(self.root, self.runtime, config=config or self.config)

    async def test_constructs_without_deployment_and_controls_are_available(
        self,
    ) -> None:
        editor = self.editor()
        self.assertFalse((self.root / "deployment").exists())
        self.assertTrue(editor.control_status().available)
        self.assertFalse(editor.control_status().pending)
        self.assertIsNone(editor.pending_request())

    async def test_pause_creates_private_deployment_directory(self) -> None:
        editor = self.editor()
        self.assertTrue((await editor.control(True)).paused)
        deployment = self.root / "deployment"
        self.assertTrue(deployment.is_dir())
        self.assertEqual(deployment.stat().st_mode & 0o777, 0o700)
        self.assertEqual(deployment.stat().st_uid, os.getuid())
        self.assertEqual(self.runtime.sent, [True])
        state = json.loads((deployment / "schedule-control.json").read_text())
        self.assertEqual(state["state"], "confirmed")
        self.assertTrue(state["paused"])
        self.assertTrue((await editor.control(False)).paused is False)
        self.assertEqual(self.runtime.sent, [True, False])

    async def test_linked_deployment_directory_is_refused(self) -> None:
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        (self.root / "deployment").symlink_to(elsewhere)
        with self.assertRaises(ValueError):
            await self.editor().control(True)
        self.assertEqual(self.runtime.sent, [])
        self.assertEqual(list(elsewhere.iterdir()), [])

    async def test_deployment_that_is_a_file_is_refused(self) -> None:
        (self.root / "deployment").write_text("not a directory")
        with self.assertRaises(ValueError):
            await self.editor().control(True)
        self.assertEqual(self.runtime.sent, [])

    async def test_editor_actions_refuse_without_contacting_anything(self) -> None:
        editor = self.editor()
        with (
            patch("plowshare_privacy.schedule_editor.administrator_login") as login,
            patch("plowshare_privacy.schedule_editor.Client") as client,
        ):
            for action in (
                editor.review("hourly", "UTC", True, "fixture-password"),
                editor.deploy(
                    "00000000-0000-0000-0000-000000000001",
                    "store",
                    "fixture-password",
                ),
                editor.reconcile("fixture-password"),
            ):
                with self.assertRaises(SettingsBusy) as refused:
                    await action
                self.assertEqual(str(refused.exception), REFUSAL)
            login.assert_not_called()
            client.connect.assert_not_called()
        self.assertEqual(self.runtime.sent, [])
        self.assertFalse((self.root / "deployment").exists())

    async def test_configured_path_is_read_not_collector_json(self) -> None:
        named = self.root / "watch-settings.json"
        row = json.loads(self.config.read_text())
        row["project"] = "configured-project"
        named.write_text(json.dumps(row))
        self.config.unlink()
        editor = self.editor(named)
        self.assertTrue((await editor.control(True)).paused)
        self.assertEqual(
            [config.project for config in self.runtime.configs], ["configured-project"]
        )
        state = json.loads((self.root / "deployment/schedule-control.json").read_text())
        self.assertEqual(state["project"], "configured-project")
        self.assertTrue(editor.control_status().requested_paused)

    async def test_overview_reports_available_control(self) -> None:
        config = configuration(self.root)
        store = FileOperatorStore(self.root)
        worker = Worker(
            config,
            FixturePort(config),
            MemoryReceipts(),
            CountingCollector(config),
            store,
        )
        console = OperatorConsole(
            self.root,
            worker,
            ConsoleFixture(),
            Settings(),
            LocalDeviceDiscovery(self.root),
            store,
            _recover,
            FileManualInvestigations(self.root),
        )
        client = TestClient(
            TestServer(
                application(
                    worker, TOKEN, console=console, schedule_editor=self.editor()
                )
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        headers = {"Authorization": "Bearer " + TOKEN}
        response = await client.get("/api/overview", headers=headers)
        self.assertEqual(response.status, 200)
        overview = await response.json()
        self.assertTrue(overview["schedule_control"]["available"])
        self.assertIsNone(overview["pending_deployment"])
        response = await client.post(
            "/api/schedule/review",
            headers=headers,
            json={
                "preset": "hourly",
                "zone": "UTC",
                "paused": False,
                "password": "fixture-password",
            },
        )
        self.assertEqual(response.status, 409)
        self.assertEqual((await response.json())["error"], REFUSAL)


if __name__ == "__main__":
    unittest.main()
