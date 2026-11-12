"""The Ask routes, the overview's Ask badge state and the tool-visibility anchor."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from plowshare import Client, Reply
from plowshare.contracts import (
    AgentListRequest,
    AgentViewDto,
    ContextSamplingDto,
    ContextSnapshotDto,
    ContextSnapshotDtoToolsItemDto,
    ConversationContextSnapshotRequest,
)
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_ask import FakePort
from test_deep_scan_web import _recover
from test_operator_console import ConsoleFixture, Settings

from plowshare_privacy.ask import (
    AskJournal,
    AskLedger,
    AskService,
    AskSettings,
    AskTurn,
    compose_ask,
)
from plowshare_privacy.console import FileManualInvestigations, OperatorConsole
from plowshare_privacy.console_port import SdkConsolePort
from plowshare_privacy.discovery import LocalDeviceDiscovery
from plowshare_privacy.operator_state import FileOperatorStore
from plowshare_privacy.web import application
from plowshare_privacy.worker import Worker

TOKEN = "dashboard-fixture-bearer-123456"
HEADERS = {"Authorization": "Bearer " + TOKEN}
REQUEST = "11111111-1111-4111-8111-111111111111"
ASKED = "2026-11-13T01:00:00Z"


class AskWebTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = configuration(self.root)
        self.store = FileOperatorStore(self.root)
        self.worker = Worker(
            self.config,
            FixturePort(self.config),
            MemoryReceipts(),
            CountingCollector(self.config),
            self.store,
        )
        self.console = OperatorConsole(
            self.root,
            self.worker,
            ConsoleFixture(),
            Settings(),
            LocalDeviceDiscovery(self.root),
            self.store,
            _recover,
            FileManualInvestigations(self.root),
        )
        self.port = FakePort()

    def service(self, ledger: AskLedger | None = None) -> AskService:
        if ledger is not None:
            AskJournal(self.root).save(ledger)
        return AskService(AskJournal(self.root), self.port)

    async def client(
        self, ask: AskService | None
    ) -> TestClient[web.Request, web.Application]:
        client = TestClient(
            TestServer(application(self.worker, TOKEN, console=self.console, ask=ask))
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    @staticmethod
    def capped() -> AskLedger:
        turn = AskTurn(REQUEST, "why?", ASKED, "job-0", "capped", "call_budget")
        return AskLedger(conversation="conv-1", turns=(turn,), unread=True)

    async def test_ask_routes_send_read_top_up_and_settings(self) -> None:
        client = await self.client(self.service(self.capped()))
        self.assertEqual(401, (await client.get("/api/ask")).status)
        # Opening the tab reads the chat and clears its unread mark.
        first = await (await client.get("/api/ask", headers=HEADERS)).json()
        self.assertEqual(
            ("capped", True, False),
            (first["turn_state"], first["paused"], first["unread"]),
        )
        topped = await client.post("/api/ask/top-up", headers=HEADERS, json={})
        self.assertEqual(200, topped.status)
        self.assertEqual("running", (await topped.json())["turn_state"])
        self.assertEqual([("conv-1", "privacy_assistant", 60, 30)], self.port.resumes)
        settings = await client.post(
            "/api/ask/settings",
            headers=HEADERS,
            json={"max_model_calls": 10, "max_turns": 5, "top_up_calls": 7},
        )
        self.assertEqual(200, settings.status)
        self.assertEqual(
            {"max_model_calls": 10, "max_turns": 5, "top_up_calls": 7},
            await settings.json(),
        )
        read = await client.get("/api/ask/settings", headers=HEADERS)
        self.assertEqual(7, (await read.json())["top_up_calls"])

    async def test_ask_sends_a_question_and_returns_the_view(self) -> None:
        client = await self.client(self.service())
        sent = await client.post("/api/ask", headers=HEADERS, json={"question": "hi"})
        self.assertEqual(200, sent.status)
        body = await sent.json()
        self.assertEqual(
            ("running", "conv-1"), (body["turn_state"], body["conversation"])
        )
        self.assertEqual([("privacy_assistant", "conv-1", "hi")], self.port.runs)

    async def test_ask_refuses_while_running_and_explains_bad_input(self) -> None:
        client = await self.client(self.service())
        await client.post("/api/ask", headers=HEADERS, json={"question": "one"})
        busy = await client.post("/api/ask", headers=HEADERS, json={"question": "two"})
        self.assertEqual(409, busy.status)
        self.assertIn("still working", (await busy.json())["error"])
        self.assertEqual(1, len(self.port.runs))
        for body in ({"question": "   "}, {"question": 5}, {"question": "x" * 4001}):
            bad = await client.post("/api/ask", headers=HEADERS, json=body)
            self.assertEqual(400, bad.status)
            self.assertIn("1 to 4000 characters", (await bad.json())["error"])
        self.assertEqual(
            400, (await client.post("/api/ask", headers=HEADERS, json={})).status
        )
        # Top-up is offered only after the allowance ran out.
        refused = await client.post("/api/ask/top-up", headers=HEADERS, json={})
        self.assertEqual(409, refused.status)
        self.assertIn("allowance", (await refused.json())["error"])
        bodies: tuple[dict[str, object], ...] = (
            {"max_model_calls": 0, "max_turns": 5, "top_up_calls": 7},
            {"max_model_calls": 5, "max_turns": 5},
            {"max_model_calls": "5", "max_turns": 5, "top_up_calls": 7},
        )
        for caps in bodies:
            reply = await client.post("/api/ask/settings", headers=HEADERS, json=caps)
            self.assertEqual(400, reply.status)
        self.assertEqual(AskSettings(), self.service().settings)

    async def test_overview_carries_unread_and_paused(self) -> None:
        client = await self.client(self.service(self.capped()))
        first = (await (await client.get("/api/overview", headers=HEADERS)).json())[
            "ask"
        ]
        self.assertEqual(
            {
                "unread": True,
                "paused": True,
                "turn_state": "capped",
                "detail": first["detail"],
            },
            first,
        )
        self.assertIsInstance(first["detail"], str)
        # Polling the overview does not read the chat.
        again = (await (await client.get("/api/overview", headers=HEADERS)).json())[
            "ask"
        ]
        self.assertTrue(again["unread"])
        await client.get("/api/ask", headers=HEADERS)
        after = (await (await client.get("/api/overview", headers=HEADERS)).json())[
            "ask"
        ]
        self.assertFalse(after["unread"])
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertTrue(status["ask_available"])

    async def test_routes_are_absent_without_ask(self) -> None:
        client = await self.client(None)
        for method, path, body in (
            ("get", "/api/ask", None),
            ("post", "/api/ask", {"question": "hi"}),
            ("post", "/api/ask/top-up", {}),
            ("get", "/api/ask/settings", None),
            ("post", "/api/ask/settings", {}),
        ):
            reply = await getattr(client, method)(
                path, headers=HEADERS, **({} if body is None else {"json": body})
            )
            self.assertEqual(404, reply.status, path)
            self.assertEqual("Ask is not enabled", await reply.text())
        self.assertIsNone(
            (await (await client.get("/api/overview", headers=HEADERS)).json())["ask"]
        )
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertFalse(status["ask_available"])

    async def test_status_explains_a_disabled_journal(self) -> None:
        (self.root / "ask.json").write_text("{", encoding="utf-8")
        (self.root / "ask.json").chmod(0o600)
        service, detail = compose_ask(
            self.root, lambda: AsyncMock(spec=Client), self.config, True
        )
        self.assertIsNone(service)
        assert detail is not None
        self.assertIsNone(
            compose_ask(self.root, lambda: AsyncMock(spec=Client), self.config, False)[
                0
            ]
        )
        client = TestClient(
            TestServer(
                application(self.worker, TOKEN, console=self.console, ask_detail=detail)
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        status = await (await client.get("/api/status", headers=HEADERS)).json()
        self.assertEqual(
            (False, detail), (status["ask_available"], status["ask_detail"])
        )


class AssistantToolVisibilityTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.config = configuration(Path(temporary.name))
        self.client = AsyncMock(spec=Client)
        self.conversation: str | None = None
        self.port = SdkConsolePort(self.client, self.config, lambda: self.conversation)
        self.assistant = AgentViewDto(
            bot=True,
            calls=(),
            description="fixture",
            model="configured",
            name="privacy_assistant",
            orchestrations=(),
            preferred=False,
            scopes=(),
            served=True,
            tools=(),
            withheld=(),
        )
        self.snapshot = ContextSnapshotDto(
            conversation="conv-1",
            agent="privacy_assistant",
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

    async def test_tool_visibility_measures_the_assistant_conversation(self) -> None:
        # Before the first Ask conversation nothing is opened or measured.
        self.client.request.side_effect = [Reply("OK", None, (self.assistant,))]
        waiting = await self.port.agents()
        self.assertEqual("awaiting_context", waiting.tool_visibility)
        self.assertEqual(1, self.client.request.await_count)
        self.client.request.reset_mock()
        self.conversation = "conv-1"
        self.client.request.side_effect = [
            Reply("OK", None, (self.assistant,)),
            Reply("OK", None, self.snapshot),
        ]
        result = await self.port.agents()
        requests = [call.args[0] for call in self.client.request.call_args_list]
        self.assertIsInstance(requests[0], AgentListRequest)
        self.assertIsInstance(requests[1], ConversationContextSnapshotRequest)
        self.assertEqual(
            ("conv-1", "privacy_assistant", False),
            (requests[1].conversation, requests[1].agent, requests[1].measure),
        )
        self.assertEqual("projected", result.tool_visibility)
        self.assertEqual(("network_evidence",), result.tools)
        self.assertIn("network_scan", result.missing_tools)

    async def test_a_foreign_projection_is_refused(self) -> None:
        self.conversation = "conv-1"
        self.client.request.side_effect = [
            Reply("OK", None, (self.assistant,)),
            Reply("OK", None, replace(self.snapshot, agent="privacy_coordinator")),
        ]
        with self.assertRaises(ValueError):
            await self.port.agents()

    async def test_an_unserved_assistant_is_not_measured(self) -> None:
        self.conversation = "conv-1"
        self.client.request.side_effect = [
            Reply("OK", None, (replace(self.assistant, served=False),))
        ]
        result = await self.port.agents()
        self.assertEqual("awaiting_context", result.tool_visibility)
        self.assertEqual(1, self.client.request.await_count)
