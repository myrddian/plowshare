"""Scoped, once-only operational controls; unknown effects are read-only reconciled."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from contextlib import asynccontextmanager
from dataclasses import replace
from pathlib import Path
from typing import AsyncIterator
from unittest.mock import AsyncMock, patch

from plowshare import Client, Delivery, Refusal, Reply, TransportError
from plowshare.contracts import (
    ScheduleFileDto,
    ScheduleFilesRequest,
    ScheduleListRequest,
    SchedulePauseRequest,
    ScheduleRecordDto,
)
from support import EXAMPLES

from plowshare_privacy.contracts import Configuration
from plowshare_privacy.monitor import SettingsBusy
from plowshare_privacy.schedule_control import ScheduleController
from plowshare_privacy.schedule_editor import ScheduleEditor
from plowshare_privacy.setup import Setup, prepare


class ScheduleControlTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        path = self.root / "collector.json"
        row = json.loads((EXAMPLES / "config.json").read_text())
        row["stateDirectory"] = str(self.root / "state")
        path.write_text(json.dumps(row))
        self.setup = Setup(
            path,
            EXAMPLES.parent / "network-privacy-watch",
            self.root / "deployment",
            "worker",
            "manager",
            "installer",
            "collector",
            30,
        )
        prepare(self.setup)
        self.config = Configuration.read(path)
        self.controller = ScheduleController(self.root, self.setup, asyncio.Lock())
        self.source = ScheduleFileDto(
            project=self.config.project,
            source="server",
            name="network_scan",
            internal_name=self.config.schedule,
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        self.schedule = ScheduleRecordDto(
            name=self.config.schedule,
            cron="0 */15 * * * *",
            zone="UTC",
            paused=False,
            next_fire_at="2026-01-01T00:00:00Z",
            defined_by="installer",
            emits="event.network_scan",
        )
        self.client = AsyncMock(spec=Client)
        self.client.__aenter__.return_value = self.client
        self.mode = "ok"
        self.sent: list[SchedulePauseRequest] = []
        self.commands: list[object] = []
        self.logins: list[str] = []
        self.logouts = 0

        @asynccontextmanager
        async def login(origin: str, handle: str, password: str) -> AsyncIterator[str]:
            self.assertEqual(origin, self.config.origin)
            self.assertEqual(password, "fixture-password")
            self.logins.append(handle)
            try:
                yield "fixture-temporary-bearer"
            finally:
                self.logouts += 1

        async def request(command: object) -> Reply[object]:
            self.commands.append(command)
            if isinstance(command, ScheduleFilesRequest):
                self.assertEqual(command.project, self.config.project)
                if self.mode == "read-failed" and self.sent:
                    raise TransportError(Delivery.UNKNOWN, "fixture read lost")
                return Reply("OK", None, (self.source,))
            if isinstance(command, ScheduleListRequest):
                self.assertEqual(command.project, self.config.project)
                return Reply("OK", None, (self.schedule,))
            if isinstance(command, SchedulePauseRequest):
                self.sent.append(command)
                self.assertEqual(command.schedule, self.config.schedule)
                if self.mode == "refused":
                    return Reply("E_NO_ACCESS", "fixture refusal", None)
                if self.mode != "lost-unapplied":
                    self.schedule = replace(self.schedule, paused=command.paused)
                if self.mode.startswith("lost"):
                    raise TransportError(Delivery.UNKNOWN, "fixture reply lost")
                return Reply("NO_CONTENT", None, None)
            raise AssertionError(command)

        self.client.request.side_effect = request
        for target, value in (
            ("plowshare_privacy.schedule_control.administrator_login", login),
            (
                "plowshare_privacy.schedule_control.Client.connect",
                AsyncMock(return_value=self.client),
            ),
        ):
            patcher = patch(target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    async def test_pause_and_resume_use_owner_and_project_reads_without_deployment(
        self,
    ) -> None:
        self.assertTrue((await self.controller.change(True, "fixture-password")).paused)
        self.assertFalse(
            (await self.controller.change(False, "fixture-password")).paused
        )
        self.assertEqual([r.paused for r in self.sent], [True, False])
        self.assertEqual(self.logins, ["installer", "installer"])
        self.assertEqual(self.logouts, 2)
        self.assertFalse(self.controller.status().pending)
        self.assertNotIn("fixture-password", self.controller.path.read_text())
        self.assertEqual(self.controller.path.stat().st_mode & 0o777, 0o600)

    async def test_already_requested_state_is_read_only(self) -> None:
        result = await self.controller.change(False, "fixture-password")
        self.assertFalse(result.paused)
        self.assertEqual(self.sent, [])
        self.assertFalse(self.controller.path.exists())

    async def test_lost_reply_survives_restart_and_check_does_not_resend(self) -> None:
        self.mode = "lost-applied"
        with self.assertRaises(SettingsBusy):
            await self.controller.change(True, "fixture-password")
        restarted = ScheduleController(self.root, self.setup, asyncio.Lock())
        self.assertTrue(restarted.status().pending)
        with self.assertRaises(SettingsBusy):
            await restarted.change(False, "fixture-password")
        observed = await restarted.check("fixture-password")
        self.assertTrue(observed.paused)
        self.assertFalse(restarted.status().pending)
        self.assertEqual(len(self.sent), 1)

    async def test_check_of_differing_state_keeps_unknown_fence(self) -> None:
        self.mode = "lost-unapplied"
        with self.assertRaises(SettingsBusy):
            await self.controller.change(True, "fixture-password")
        self.assertFalse((await self.controller.check("fixture-password")).paused)
        self.assertTrue(self.controller.status().pending)
        self.assertEqual(len(self.sent), 1)

    async def test_definitive_refusal_is_not_claimed_as_paused(self) -> None:
        self.mode = "refused"
        with self.assertRaises(Refusal):
            await self.controller.change(True, "fixture-password")
        self.assertFalse(self.schedule.paused)
        self.assertFalse(self.controller.status().pending)
        self.assertEqual(
            json.loads(self.controller.path.read_text())["state"], "refused"
        )

    async def test_acknowledged_mutation_with_failed_read_stays_pending(self) -> None:
        self.mode = "read-failed"
        with self.assertRaises(TransportError):
            await self.controller.change(True, "fixture-password")
        self.assertTrue(self.controller.status().pending)
        self.mode = "ok"
        self.assertTrue((await self.controller.check("fixture-password")).paused)
        self.assertEqual(len(self.sent), 1)

    async def test_foreign_source_is_refused_before_any_control(self) -> None:
        self.source = replace(self.source, project="foreign-project")
        with self.assertRaises(SettingsBusy):
            await self.controller.change(True, "fixture-password")
        self.assertEqual(self.sent, [])

    async def test_foreign_owner_is_refused_even_when_state_already_matches(
        self,
    ) -> None:
        self.schedule = replace(self.schedule, defined_by="another-owner")
        with self.assertRaisesRegex(SettingsBusy, "another account"):
            await self.controller.change(False, "fixture-password")
        self.assertEqual(self.sent, [])

    async def test_changed_schedule_binding_is_refused(self) -> None:
        self.source = replace(self.source, internal_name="another-schedule")
        with self.assertRaises(ValueError):
            await self.controller.change(True, "fixture-password")
        self.assertEqual(self.sent, [])

    async def test_pending_source_deployment_blocks_control(self) -> None:
        self.controller.deployment.write_text("fixture")
        with self.assertRaises(SettingsBusy):
            await self.controller.change(True, "fixture-password")
        self.assertEqual(self.logins, [])

    async def test_pending_control_blocks_source_review(self) -> None:
        self.mode = "lost-applied"
        with self.assertRaises(SettingsBusy):
            await self.controller.change(True, "fixture-password")
        with self.assertRaises(SettingsBusy):
            await ScheduleEditor(self.root).review(
                "hourly", "UTC", True, "fixture-password"
            )
        self.assertEqual(len(self.logins), 1)

    async def test_invalid_boolean_or_foreign_intent_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            await self.controller.change(1, "fixture-password")  # type: ignore[arg-type]
        self.controller.path.write_text(
            json.dumps(
                {
                    "project": "foreign",
                    "schedule": self.config.schedule,
                    "paused": True,
                    "state": "pending",
                }
            )
        )
        with self.assertRaises(ValueError):
            await self.controller.change(True, "fixture-password")
        self.assertEqual(self.logins, [])
