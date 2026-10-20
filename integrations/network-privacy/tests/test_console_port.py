"""Project reads distinguish definition grants from live tool visibility."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

from plowshare import Client, Refusal, Reply
from plowshare.contracts import (
    AgentListRequest,
    AgentViewDto,
    ContextSamplingDto,
    ContextSnapshotDto,
    ContextSnapshotDtoToolsItemDto,
    ConversationContextSnapshotRequest,
    ConversationListRequest,
    ConversationViewDto,
    OrchestrationReceiptRequest,
    OrchestrationReceiptResultDto,
    OrchestrationStartRequest,
    OrchestrationStartResultDto,
    ScheduleFileDto,
    ScheduleFilesRequest,
    ScheduleListRequest,
    ScheduleRecordDto,
)
from support import configuration

from plowshare_privacy.console_port import SdkConsolePort
from plowshare_privacy.journal import Receipt


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
        self.client.request.return_value = Reply("OK", None, ())
        self.assertIsNone(await self.port.schedule())
        command = self.client.request.call_args.args[0]
        self.assertIsInstance(command, ScheduleFilesRequest)
        self.assertEqual(command.project, self.config.project)

    async def test_schedule_target_after_first_hundred_files_and_runtime_rows(
        self,
    ) -> None:
        target = ScheduleFileDto(
            project=self.config.project,
            source="server",
            name="network_scan",
            internal_name=self.config.schedule,
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        first = tuple(
            replace(target, name=f"unrelated-{i}", internal_name=f"file-{i:03}")
            for i in range(100)
        )
        runtime = ScheduleRecordDto(
            name=self.config.schedule,
            cron="0 */15 * * * *",
            zone="UTC",
            paused=True,
            next_fire_at="2026-01-01T00:00:00Z",
            defined_by="@service/fixture",
            emits="scan",
        )
        runtime_first = tuple(
            replace(runtime, name=f"runtime-{i:03}") for i in range(100)
        )
        self.client.request.side_effect = [
            Reply("OK", None, first),
            Reply("OK", None, (target,)),
            Reply("OK", None, runtime_first),
            Reply("OK", None, (runtime,)),
        ]
        result = await self.port.schedule()
        assert result is not None
        self.assertEqual(result.name, self.config.schedule)
        commands = [call.args[0] for call in self.client.request.call_args_list]
        self.assertEqual(len(commands), 4)
        self.assertTrue(
            all(
                isinstance(command, (ScheduleFilesRequest, ScheduleListRequest))
                for command in commands
            )
        )
        self.assertTrue(
            all(
                command.project == self.config.project and command.limit == 100
                for command in commands
            )
        )
        self.assertEqual(commands[1].after, first[-1].internal_name)
        self.assertEqual(commands[3].after, runtime_first[-1].name)

    async def test_schedule_page_revocation_fails_without_retry_or_partial_view(
        self,
    ) -> None:
        target = ScheduleFileDto(
            project=self.config.project,
            source="server",
            name="network_scan",
            internal_name=self.config.schedule,
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        first = tuple(
            replace(target, name=f"unrelated-{i}", internal_name=f"file-{i:03}")
            for i in range(100)
        )
        self.client.request.side_effect = [
            Reply("OK", None, first),
            Reply("FORBIDDEN", "revoked", ()),
        ]
        with self.assertRaises(Refusal):
            await self.port.schedule()
        self.assertEqual(self.client.request.await_count, 2)
        self.assertTrue(
            all(
                isinstance(call.args[0], ScheduleFilesRequest)
                for call in self.client.request.call_args_list
            )
        )

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

    async def test_start_preserves_opaque_run_identity(self) -> None:
        from support import CountingCollector

        identity = str(uuid4())
        scan = str(uuid4())
        evidence = await CountingCollector(self.config).collect(
            scan, "schedule:fixture", None, None
        )
        receipt = Receipt(
            scan,
            "schedule:fixture",
            "schedule.due",
            evidence.finished_at,
            "done",
            evidence=evidence,
            revision=str(uuid4()),
        )
        self.client.request.return_value = Reply(
            "ACCEPTED",
            None,
            OrchestrationStartResultDto(
                id="orc_0123456789ABCDEF", request_id=identity, state="running"
            ),
        )
        result = await self.port.investigate(receipt, identity)
        self.assertEqual(result.run_id, "orc_0123456789ABCDEF")
        self.assertEqual(result.request_id, identity)
        request = self.client.request.call_args.args[0]
        self.assertIsInstance(request, OrchestrationStartRequest)
        self.assertEqual(request.project, self.config.project)
        self.assertEqual(request.request_id, identity)
        self.client.request.assert_awaited_once()

    async def test_receipt_preserves_opaque_run_identity_without_starting(self) -> None:
        identity = str(uuid4())
        self.client.request.return_value = Reply(
            "OK",
            None,
            OrchestrationReceiptResultDto(
                id="orc_0123456789ABCDEF", request_id=identity, state="finished"
            ),
        )
        result = await self.port.investigation_receipt(identity)
        self.assertEqual(result.run_id, "orc_0123456789ABCDEF")
        self.assertEqual(result.request_id, identity)
        self.client.request.assert_awaited_once_with(
            OrchestrationReceiptRequest(request_id=identity)
        )

    async def test_invalid_run_identity_never_confirms_a_receipt(self) -> None:
        identity = str(uuid4())
        for run in ("", "orc bad", "orc_\\bad", "orc_\n", "x" * 129):
            self.client.request.return_value = Reply(
                "OK",
                None,
                OrchestrationReceiptResultDto(
                    id=run, request_id=identity, state="running"
                ),
            )
            with self.subTest(run=run), self.assertRaises(ValueError):
                await self.port.investigation_receipt(identity)

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

    async def test_scoped_schedule_reports_effective_pause_and_refuses_ambiguous_sources(
        self,
    ) -> None:
        file = ScheduleFileDto(
            project=self.config.project,
            source="server",
            name="network_scan",
            internal_name=self.config.schedule,
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        row = ScheduleRecordDto(
            name=self.config.schedule,
            cron="0 */15 * * * *",
            zone="UTC",
            paused=True,
            next_fire_at="2026-01-01T00:00:00Z",
            defined_by="@service/fixture",
            emits="event.network_scan",
        )
        self.client.request.side_effect = [
            Reply("OK", None, (file,)),
            Reply("OK", None, (row,)),
        ]
        result = await self.port.schedule()
        assert result is not None
        self.assertTrue(result.paused)
        command = self.client.request.call_args.args[0]
        self.assertIsInstance(command, ScheduleListRequest)
        self.assertEqual(command.project, self.config.project)
        self.client.request.side_effect = [
            Reply("OK", None, (file, replace(file, internal_name="different-source")))
        ]
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            await self.port.schedule()
