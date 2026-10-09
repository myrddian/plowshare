"""Repeated launches authenticate the same local process without a second worker."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from support import CountingCollector, FixturePort, MemoryReceipts, configuration

from plowshare_privacy.dashboard import RunningDashboard, reopen
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "dashboard-fixture-bearer-123456"


class DashboardTest(unittest.IsolatedAsyncioTestCase):
    async def test_repeated_launch_opens_verified_running_instance_and_stale_handoff_does_not(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            config = configuration(root)
            worker = Worker(
                config, FixturePort(config), MemoryReceipts(), CountingCollector(config)
            )
            instance = str(uuid4())
            client = TestClient(
                TestServer(application(worker, TOKEN, instance=instance))
            )
            await client.start_server()
            path = root / "dashboard-runtime.json"
            dashboard = RunningDashboard(
                instance, str(client.make_url("/")).rstrip("/"), TOKEN
            )
            dashboard.write(path)
            try:
                with patch(
                    "plowshare_privacy.dashboard.webbrowser.open", return_value=True
                ) as browser:
                    self.assertTrue(await reopen(path))
                    browser.assert_called_once_with(
                        dashboard.origin + "/#token=" + TOKEN
                    )
                RunningDashboard(str(uuid4()), dashboard.origin, TOKEN).write(path)
                with patch("plowshare_privacy.dashboard.webbrowser.open") as browser:
                    self.assertFalse(await reopen(path))
                    browser.assert_not_called()
            finally:
                await client.close()
            with patch("plowshare_privacy.dashboard.webbrowser.open") as browser:
                self.assertFalse(await reopen(path))
                browser.assert_not_called()

    def test_handoff_refuses_remote_origins_credentials_and_linked_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "dashboard-runtime.json"
            for origin in (
                "https://remote.invalid",
                "http://192.168.0.12:8090",
                "http://user:password@127.0.0.1:8080",
                "http://127.0.0.1:8080/?secret=yes",
            ):
                path.write_text(
                    json.dumps(
                        {"instance": str(uuid4()), "origin": origin, "token": TOKEN}
                    )
                )
                path.chmod(0o600)
                with self.assertRaises(ValueError):
                    RunningDashboard.read(path)
