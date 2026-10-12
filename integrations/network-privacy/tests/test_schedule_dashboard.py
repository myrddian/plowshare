"""Dashboard controls retain authenticated, same-origin and strict typed boundaries."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from aiohttp.test_utils import TestClient, TestServer
from support import CountingCollector, FixturePort, MemoryReceipts, configuration

from plowshare_privacy.console import OperatorConsole
from plowshare_privacy.console_port import ScheduleView
from plowshare_privacy.schedule_control import ScheduleControlStatus
from plowshare_privacy.schedule_editor import ScheduleManagement
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "fixture-schedule-dashboard-token"


class ScheduleDashboardTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        config = configuration(Path(temporary.name))
        worker = Worker(
            config, FixturePort(config), MemoryReceipts(), CountingCollector(config)
        )
        self.editor = AsyncMock(spec=ScheduleManagement)
        self.editor.control_status.return_value = ScheduleControlStatus(
            "installer", False, True
        )
        self.editor.control.return_value = ScheduleView(
            config.schedule, "0 */15 * * * *", "UTC", True, None, "installer"
        )
        self.editor.check_control.return_value = self.editor.control.return_value
        self.client = TestClient(
            TestServer(
                application(
                    worker,
                    TOKEN,
                    console=AsyncMock(spec=OperatorConsole),
                    schedule_editor=self.editor,
                )
            )
        )
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.headers = {"Authorization": "Bearer " + TOKEN}

    async def test_pause_route_uses_fixed_controller_and_does_not_deploy(self) -> None:
        response = await self.client.post(
            "/api/schedule/control",
            headers=self.headers,
            json={"paused": True, "password": "fixture"},
        )
        self.assertEqual(response.status, 200)
        self.assertTrue((await response.json())["schedule"]["paused"])
        self.editor.control.assert_awaited_once_with(True, "fixture")
        self.editor.deploy.assert_not_awaited()

    async def test_resume_and_read_only_check_use_separate_capabilities(self) -> None:
        response = await self.client.post(
            "/api/schedule/control",
            headers=self.headers,
            json={"paused": False, "password": "fixture"},
        )
        self.assertEqual(response.status, 200)
        self.editor.control.assert_awaited_once_with(False, "fixture")
        response = await self.client.post(
            "/api/schedule/check", headers=self.headers, json={"password": "fixture"}
        )
        self.assertEqual(response.status, 200)
        self.editor.check_control.assert_awaited_once_with("fixture")
        self.assertEqual(self.editor.control.await_count, 1)

    async def test_invalid_boolean_and_injected_target_are_refused(self) -> None:
        for body in (
            {"paused": "true", "password": "fixture"},
            {"paused": True, "password": "fixture", "schedule": "other"},
            {"paused": True, "password": "fixture", "account": "other"},
        ):
            response = await self.client.post(
                "/api/schedule/control", headers=self.headers, json=body
            )
            self.assertEqual(response.status, 400)
        self.editor.control.assert_not_awaited()

    async def test_no_authentication_or_cross_origin_cookie_cannot_control(
        self,
    ) -> None:
        response = await self.client.post(
            "/api/schedule/control", json={"paused": True, "password": "fixture"}
        )
        self.assertEqual(response.status, 401)
        response = await self.client.post("/api/session", headers=self.headers, json={})
        self.assertEqual(response.status, 200)
        response = await self.client.post(
            "/api/schedule/control",
            headers={"Origin": "https://foreign.invalid"},
            json={"paused": True, "password": "fixture"},
        )
        self.assertEqual(response.status, 403)
        self.editor.control.assert_not_awaited()
