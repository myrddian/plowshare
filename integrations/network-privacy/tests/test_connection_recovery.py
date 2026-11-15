"""Recovery composes a fresh public SDK connection before inspecting paid-work receipts."""

from __future__ import annotations

import argparse
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from typing import Awaitable, Callable
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp import web
from plowshare import Client, Delivery, Refusal, Reply, TransportError
from plowshare.contracts import (
    InboxReceiptResultDto,
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
    async def run_recovery(
        self,
        *,
        pending: bool,
        automatic: bool = False,
        empty_topics: bool = False,
        reconnections: int = 1,
    ) -> tuple[int, list[str]]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            row = json.loads((EXAMPLES / "config.json").read_text())
            row["stateDirectory"] = str(root / "state")
            config = root / "collector.json"
            config.write_text(json.dumps(row))
            old, fresh = AsyncMock(spec=Client), AsyncMock(spec=Client)
            clients = [old, fresh] + [
                AsyncMock(spec=Client) for _ in range(reconnections - 1)
            ]
            for candidate in clients:
                candidate.__aenter__.return_value = candidate

            old_operations: list[str] = []

            async def disconnected(command: object) -> Reply[object]:
                # Main's stop-alert path may try one new alert on the failed
                # session. Recovery must inspect that identity on the fresh one.
                # Background readers may also observe the same closed session.
                old_operations.append(getattr(command, "operation"))
                raise TransportError(Delivery.NOT_SUBMITTED, "fixture closed socket")

            old.request.side_effect = disconnected
            operations: list[str] = []

            async def request(command: object) -> Reply[object]:
                operation = getattr(command, "operation")
                operations.append(operation)
                if operation == "orchestration.receipt":
                    raise Refusal("NOT_FOUND", "fixture receipt absent")
                if operation == "inbox.receipt":
                    return Reply("OK", None, InboxReceiptResultDto(found=False))
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
                            for field in (
                                () if empty_topics else ("requestTopic", "resultTopic")
                            )
                        ),
                    ),
                )

            for candidate in clients[1:]:
                candidate.request.side_effect = request
            captured: OperatorConsole | None = None
            original_run = Worker.run

            def application(
                worker: Worker, token: str, **options: object
            ) -> web.Application:
                nonlocal captured
                console = options["console"]
                assert isinstance(console, OperatorConsole)
                captured = console
                return web.Application()

            async def run(
                worker: Worker,
                stop: asyncio.Event,
                external: ExternalRequests | None,
                apply_settings: Callable[[], Awaitable[None]] | None,
                recover: Callable[[], Awaitable[None]],
            ) -> None:
                assert captured is not None
                worker.state = "attention_required"
                if pending:
                    captured.journal.save(
                        ManualInvestigation(
                            str(uuid4()), str(uuid4()), utc_now(), "pending"
                        )
                    )
                if automatic:
                    polls = 0

                    async def poll() -> None:
                        nonlocal polls
                        polls += 1
                        if polls == 1:
                            raise TransportError(Delivery.UNKNOWN, "fixture drop")
                        stop.set()

                    async def reconnect() -> None:
                        try:
                            await recover()
                        except Refusal:
                            stop.set()
                            raise

                    with (
                        patch.object(worker, "poll", side_effect=poll),
                        patch(
                            "plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS",
                            0.001,
                        ),
                    ):
                        await original_run(worker, stop, recover=reconnect)
                    self.assertEqual(polls, 1 if pending else 2)
                    if pending:
                        self.assertEqual(captured.manual[0].phase, "pending")
                        self.assertEqual(worker.state, "attention_required")
                    else:
                        self.assertEqual(worker.state, "ready")
                    assert worker.attention is not None
                    self.assertEqual(worker.attention.pending, 1)
                    self.assertEqual(len(worker.attention.rows), 1)
                    self.assertEqual(old_operations.count("inbox.publish"), 1)
                    self.assertFalse(
                        {
                            "relay.publish",
                            "information.upload",
                            "orchestration.start",
                        }.intersection(old_operations)
                    )
                elif pending:
                    with self.assertRaises(Refusal):
                        await captured.check_and_resume()
                    self.assertFalse(worker.recovery_requested.is_set())
                    self.assertEqual(captured.manual[0].phase, "pending")
                else:
                    await captured.check_and_resume()
                    self.assertTrue(worker.recovery_requested.is_set())
                    for _ in range(reconnections - 1):
                        worker.recovery_requested.clear()
                        await captured.check_and_resume()
                    # Retired sessions are closed now, not retained as cleanup
                    # callbacks until this long-running service exits.
                    for candidate in clients[:-1]:
                        candidate.__aexit__.assert_awaited_once()
                    clients[-1].__aexit__.assert_not_awaited()
                old.__aexit__.assert_awaited_once()
                stop.set()

            failures: list[Exception] = []

            async def checked_run(
                worker: Worker,
                stop: asyncio.Event,
                external: ExternalRequests | None,
                apply_settings: Callable[[], Awaitable[None]] | None,
                recover: Callable[[], Awaitable[None]],
            ) -> None:
                # Production shutdown logs background errors. Assertions in this
                # fixture must fail the test after cleanup has finished.
                try:
                    await run(worker, stop, external, apply_settings, recover)
                except Exception as error:
                    failures.append(error)
                finally:
                    stop.set()

            connect = AsyncMock(side_effect=clients)
            with (
                patch("plowshare_privacy.cli.Client.connect", connect),
                patch("plowshare_privacy.cli.application", application),
                patch.object(Worker, "run", checked_run),
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
            if failures:
                raise failures[0]
            for candidate in clients:
                candidate.__aexit__.assert_awaited_once()
            return connect.await_count, operations

    async def test_explicit_recovery_opens_fresh_sdk_session_without_replaying_requests(
        self,
    ) -> None:
        connections, operations = await self.run_recovery(pending=False)
        self.assertEqual(connections, 2)
        self.assertEqual(operations, ["relay.topics"])

    async def test_unknown_manual_receipt_uses_new_session_and_prevents_resume(
        self,
    ) -> None:
        connections, operations = await self.run_recovery(pending=True)
        self.assertEqual(connections, 2)
        self.assertEqual(operations[-1], "orchestration.receipt")
        self.assertNotIn("orchestration.start", operations)

    async def test_drop_automatically_reconnects_before_resuming_intake(self) -> None:
        connections, operations = await self.run_recovery(pending=False, automatic=True)
        self.assertEqual(connections, 2)
        self.assertEqual(operations, ["relay.topics", "inbox.receipt"])

    async def test_automatic_recovery_keeps_unknown_manual_admission_fenced(
        self,
    ) -> None:
        connections, operations = await self.run_recovery(pending=True, automatic=True)
        self.assertEqual(connections, 2)
        self.assertEqual(
            operations, ["relay.topics", "inbox.receipt", "orchestration.receipt"]
        )

    async def test_lazy_topics_do_not_block_an_unused_collectors_recovery(self) -> None:
        connections, operations = await self.run_recovery(
            pending=False, automatic=True, empty_topics=True
        )
        self.assertEqual(connections, 2)
        self.assertEqual(operations, ["relay.topics", "inbox.receipt"])

    async def test_repeated_drops_release_each_retired_session_once(self) -> None:
        connections, operations = await self.run_recovery(
            pending=False, reconnections=3
        )
        self.assertEqual(connections, 4)
        self.assertEqual(operations, ["relay.topics"] * 3)
