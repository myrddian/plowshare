"""Project reads distinguish definition grants from live tool visibility."""

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
    ContextSamplingDto,
    ContextSnapshotDto,
    ContextSnapshotDtoToolsItemDto,
    ConversationContextSnapshotRequest,
    ConversationListRequest,
    ConversationViewDto,
    OrchestrationReceiptResultDto,
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
        self.coordinator = AgentViewDto(
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
        self.conversation = ConversationViewDto(
            id="cnv_fixture",
            project=self.config.project,
            title=None,
            max_model_calls=None,
            max_turns=None,
            model_calls_spent=None,
            no_budget=False,
            no_turn_cap=False,
        )
        self.snapshot = ContextSnapshotDto(
            conversation=self.conversation.id,
            agent="privacy_coordinator",
            captured_at="2026-01-01T00:00:00Z",
            count=None,
            messages=(),
            model="configured",
            projection="next",
            sampling=ContextSamplingDto(),
            tools=(
                ContextSnapshotDtoToolsItemDto(
                    name="network_evidence", description="fixture", parameters={}
                ),
            ),
        )

    async def test_schedule_runtime_does_not_borrow_global_account_authority(
        self,
    ) -> None:
        self.assertIsNone(await self.port.schedule())
        self.client.request.assert_not_awaited()

    async def test_agent_visibility_uses_projected_registry_not_declared_grants(
        self,
    ) -> None:
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                (self.coordinator, replace(self.coordinator, name="another-agent")),
            ),
            Reply(
                "OK",
                None,
                (replace(self.conversation, project="foreign"), self.conversation),
            ),
            Reply("OK", None, self.snapshot),
        ]
        result = await self.port.agents()
        requests = [call.args[0] for call in self.client.request.call_args_list]
        self.assertIsInstance(requests[0], AgentListRequest)
        self.assertEqual(requests[0].project, self.config.project)
        self.assertIsInstance(requests[1], ConversationListRequest)
        self.assertEqual(requests[1].project, self.config.project)
        self.assertIsInstance(requests[2], ConversationContextSnapshotRequest)
        self.assertEqual(requests[2].conversation, self.conversation.id)
        self.assertIs(requests[2].measure, False)
        self.assertEqual(result.tools, ("network_evidence",))
        self.assertIn("network_scan", result.missing_tools)
        self.assertIn("privacy_reviewer", result.unavailable)
        self.assertNotIn("another-agent", result.served)
        self.assertEqual(result.tool_visibility, "projected")

    async def test_no_context_does_not_report_dynamic_tools_missing(self) -> None:
        self.client.request.side_effect = [
            Reply("OK", None, (self.coordinator,)),
            Reply("OK", None, ()),
        ]
        result = await self.port.agents()
        self.assertEqual(result.tool_visibility, "awaiting_context")
        self.assertEqual(result.tools, ())
        self.assertEqual(result.missing_tools, ())
        self.assertEqual(self.client.request.await_count, 2)

    async def test_foreign_conversation_does_not_become_projection_anchor(self) -> None:
        self.client.request.side_effect = [
            Reply("OK", None, (self.coordinator,)),
            Reply("OK", None, (replace(self.conversation, project="foreign"),)),
        ]
        result = await self.port.agents()
        self.assertEqual(result.tool_visibility, "awaiting_context")
        self.assertEqual(self.client.request.await_count, 2)

    async def test_foreign_projection_is_refused(self) -> None:
        self.client.request.side_effect = [
            Reply("OK", None, (self.coordinator,)),
            Reply("OK", None, (self.conversation,)),
            Reply("OK", None, replace(self.snapshot, conversation="cnv_foreign")),
        ]
        with self.assertRaises(ValueError):
            await self.port.agents()

    async def test_foreign_receipt_never_confirms_an_intent(self) -> None:
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
