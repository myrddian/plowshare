"""Public SDK projections stay in the configured project and exact schedule."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

from plowshare import Client, Reply
from plowshare.contracts import (
    AgentListRequest,
    AgentViewDto,
    ScheduleListRequest,
    ScheduleRecordDto,
)
from support import configuration

from plowshare_privacy.console_port import SdkConsolePort


class ConsolePortTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.config = configuration(Path(temporary.name))
        self.client = AsyncMock(spec=Client)
        self.port = SdkConsolePort(self.client, self.config)

    async def test_other_schedule_names_never_enter_dashboard(self) -> None:
        selected = ScheduleRecordDto(
            cron="0 0 * * * *",
            defined_by="application",
            emits="privacy-tick",
            name=self.config.schedule,
            next_fire_at="2026-01-01T00:00:00Z",
            paused=True,
            zone="UTC",
        )
        foreign = replace(selected, name="another-private-project")
        self.client.request.return_value = Reply("OK", None, (foreign, selected))
        result = await self.port.schedule()
        self.assertEqual(result.name if result else None, self.config.schedule)
        self.assertIsNone(result.next_at if result else None)
        self.assertIsInstance(
            self.client.request.call_args.args[0], ScheduleListRequest
        )
        self.client.request.return_value = Reply("OK", None, (foreign,))
        self.assertIsNone(await self.port.schedule())

    async def test_agent_visibility_uses_project_and_does_not_claim_execution_authority(
        self,
    ) -> None:
        coordinator = AgentViewDto(
            bot=True,
            calls=(),
            description="fixture",
            model="configured",
            name="privacy_coordinator",
            orchestrations=(),
            preferred=False,
            scopes=(),
            served=True,
            tools=("network_scan", "foreign_tool"),
            withheld=(),
        )
        self.client.request.return_value = Reply(
            "OK", None, (coordinator, replace(coordinator, name="another-agent"))
        )
        result = await self.port.agents()
        request = self.client.request.call_args.args[0]
        self.assertIsInstance(request, AgentListRequest)
        self.assertEqual(request.project, self.config.project)
        self.assertEqual(result.tools, ("network_scan",))
        self.assertIn("privacy_reviewer", result.unavailable)
        self.assertNotIn("another-agent", result.served)

    async def test_foreign_receipt_never_confirms_an_intent(self) -> None:
        from plowshare.contracts import OrchestrationReceiptResultDto

        identity = str(uuid4())
        self.client.request.return_value = Reply(
            "OK",
            None,
            OrchestrationReceiptResultDto(
                id=str(uuid4()), request_id=str(uuid4()), state="RUNNING"
            ),
        )
        with self.assertRaises(ValueError):
            await self.port.investigation_receipt(identity)
