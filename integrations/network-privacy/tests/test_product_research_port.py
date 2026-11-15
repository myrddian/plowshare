"""The public SDK port binds results and cancellation to the application's run."""

from __future__ import annotations

import unittest
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from plowshare import Client
from plowshare.contracts import (
    OrchestrationCancelledDto,
    OrchestrationCancelRequest,
    OrchestrationStartRequest,
    OrchestrationStartResultDto,
    OrchestrationStatusDto,
    RunViewDto,
)

from plowshare_privacy.product_research import ProductContext, ProductQuestion
from plowshare_privacy.product_research_port import (
    CALLER,
    DEFINITION,
    SdkProductResearchPort,
)

RUN = "orc_product_fixture"
PROJECT = "privacy-project"


def view(project: str = PROJECT, definition: str = DEFINITION) -> RunViewDto:
    return RunViewDto(
        id=RUN,
        project=project,
        definition=definition,
        caller_agent=CALLER,
        caller_conversation=None,
        conductor_conversation="fixture",
        created_at="2026-11-15T00:00:00Z",
        depth=0,
        ended_at=None,
        failure=None,
        max_returns=1,
        nudges=0,
        parent=None,
        pending_cap=None,
        restarts=0,
        result=None,
        returns_used=0,
        stalled_since=None,
        state="running",
        tier="project",
        waiting_for=None,
    )


class ProductResearchPortTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.client = AsyncMock(spec=Client)
        self.reply = MagicMock()
        self.client.request.return_value = self.reply
        self.port = SdkProductResearchPort(lambda: self.client, PROJECT)

    async def test_start_uses_current_session_and_rejects_foreign_receipt(self) -> None:
        request_id = str(uuid4())
        question = ProductQuestion(
            "lg-tv", "What viewing data is collected?", ProductContext()
        )
        self.reply.require_payload.return_value = OrchestrationStartResultDto(
            id=RUN, request_id=request_id, state="running"
        )
        self.assertEqual(RUN, await self.port.start(request_id, question))
        self.assertEqual(
            OrchestrationStartRequest(
                project=PROJECT,
                agent=CALLER,
                definition=DEFINITION,
                request=question.encode(),
                request_id=request_id,
            ),
            self.client.request.call_args.args[0],
        )
        self.reply.require_payload.return_value = OrchestrationStartResultDto(
            id=RUN, request_id=str(uuid4()), state="running"
        )
        with self.assertRaises(ValueError):
            await self.port.start(request_id, question)

    async def test_foreign_project_or_definition_cannot_be_read_or_cancelled(
        self,
    ) -> None:
        for run in (view(project="other-project"), view(definition="deep_research")):
            self.reply.require_payload.return_value = OrchestrationStatusDto(
                orchestration=run, todos=(), children=(), messages=()
            )
            with self.assertRaises(ValueError):
                await self.port.outcome(RUN)
            with self.assertRaises(ValueError):
                await self.port.cancel(RUN)
        self.assertFalse(
            any(
                isinstance(call.args[0], OrchestrationCancelRequest)
                for call in self.client.request.call_args_list
            )
        )

    async def test_cancellation_uses_the_checked_run_id(self) -> None:
        status = MagicMock()
        status.require_payload.return_value = OrchestrationStatusDto(
            orchestration=view(), todos=(), children=(), messages=()
        )
        cancelled = MagicMock()
        cancelled.require_payload.return_value = OrchestrationCancelledDto(
            id=RUN, state="cancelled"
        )
        self.client.request.side_effect = [status, cancelled]
        await self.port.cancel(RUN)
        self.assertEqual(
            OrchestrationCancelRequest(id=RUN), self.client.request.call_args.args[0]
        )
