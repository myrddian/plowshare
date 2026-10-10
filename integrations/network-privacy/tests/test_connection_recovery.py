"""Recovery composes a fresh public SDK connection before inspecting paid-work receipts."""

from __future__ import annotations

import argparse
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp import web
from plowshare import Client, Refusal, Reply
from plowshare.contracts import (
    RelayTopicDto,
    RelayTopicsResultDto,
    RelayTopicsResultDtoScopeDto,
)
from support import EXAMPLES

from plowshare_privacy.cli import execute
from plowshare_privacy.console import ManualInvestigation, OperatorConsole
from plowshare_privacy.contracts import utc_now
from plowshare_privacy.worker import ExternalRequests, Worker


class ConnectionRecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def run_recovery(self, *, pending: bool) -> tuple[int, list[str]]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            row = json.loads((EXAMPLES / "config.json").read_text())
            row["stateDirectory"] = str(root / "state")
            config = root / "collector.json"
            config.write_text(json.dumps(row))
            old, fresh = AsyncMock(spec=Client), AsyncMock(spec=Client)
            old.__aenter__.return_value = old
            fresh.__aenter__.return_value = fresh
            old.request.side_effect = AssertionError(
                "Recovery must not use the stopped socket"
            )
            operations: list[str] = []

            async def request(command: object) -> Reply[object]:
                operation = getattr(command, "operation")
                operations.append(operation)
                if operation == "orchestration.receipt":
                    raise Refusal("NOT_FOUND", "fixture receipt absent")
                self.assertEqual(operation, "relay.topics")
                return Reply(
                    "OK",
                    None,
                    RelayTopicsResultDto(
                        scope=RelayTopicsResultDtoScopeDto(
                            project=row["project"], system=False
                        ),
                        topics=tuple(
                            RelayTopicDto(
                                name=row[field],
                                kind="EMPTY",
                                through="0",
                                expired_through="0",
                                max_records=None,
                                retention_seconds="3600",
                            )
                            for field in ("requestTopic", "resultTopic")
                        ),
                    ),
                )

            fresh.request.side_effect = request
            captured: OperatorConsole | None = None

            def application(
                worker: Worker, token: str, **options: object
            ) -> web.Application:
                nonlocal captured
                console = options["console"]
                assert isinstance(console, OperatorConsole)
                captured = console
                return web.Application()

            async def run(
                worker: Worker, stop: asyncio.Event, external: ExternalRequests | None
            ) -> None:
                assert captured is not None
                worker.state = "attention_required"
                if pending:
                    captured.journal.save(
                        ManualInvestigation(
                            str(uuid4()), str(uuid4()), utc_now(), "pending"
                        )
                    )
                    with self.assertRaises(Refusal):
                        await captured.check_and_resume()
                    self.assertFalse(worker.recovery_requested.is_set())
                    self.assertEqual(captured.manual[0].phase, "pending")
                else:
                    await captured.check_and_resume()
                    self.assertTrue(worker.recovery_requested.is_set())
                old.close.assert_awaited_once()
                stop.set()

            connect = AsyncMock(side_effect=(old, fresh))
            with (
                patch("plowshare_privacy.cli.Client.connect", connect),
                patch("plowshare_privacy.cli.application", application),
                patch.object(Worker, "run", run),
                patch.dict(
                    "os.environ",
                    {
                        "PLOWSHARE_TOKEN": "fixture-service-token",
                        "PRIVACY_WEB_TOKEN": "fixture-dashboard-token-long-enough",
                    },
                ),
            ):
                await execute(
                    argparse.Namespace(
                        config=str(config),
                        command="serve",
                        tool_provider=None,
                        tool_account=None,
                        dashboard_settings=True,
                        bind="127.0.0.1",
                        port=0,
                    )
                )
            fresh.__aexit__.assert_awaited_once()
            return connect.await_count, operations

    async def test_explicit_recovery_opens_fresh_sdk_session_without_replaying_requests(
        self,
    ) -> None:
        connections, operations = await self.run_recovery(pending=False)
        self.assertEqual(connections, 2)
        self.assertEqual(operations, ["relay.topics", "relay.topics"])

    async def test_unknown_manual_receipt_uses_new_session_and_prevents_resume(
        self,
    ) -> None:
        connections, operations = await self.run_recovery(pending=True)
        self.assertEqual(connections, 2)
        self.assertEqual(operations[-1], "orchestration.receipt")
        self.assertNotIn("orchestration.start", operations)
