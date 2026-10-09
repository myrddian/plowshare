"""Provider execution, restart and lost-result behavior through a real SDK WebSocket."""

from __future__ import annotations

import hashlib
import json
import sqlite3
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from websockets.asyncio.server import ServerConnection, serve

from plowshare import PROTOCOL_VERSION, Client, TransportError
from plowshare._codec import _object
from plowshare.tool_journal import SqliteToolJournal
from plowshare.tools import (
    Arguments,
    Parameter,
    RegisteredTool,
    ToolCall,
    ToolDeclaration,
    ToolProvider,
    ToolReceipt,
    ToolResult,
    deployment_config,
    result_request_id,
)

DECLARATION = ToolDeclaration("network_scope", "Read configured scope")
CONFIG = deployment_config("fixture", "scanner", "provider", (DECLARATION,))


class Fixture:
    def __init__(self) -> None:
        self.identity = str(uuid4())
        self.deadline = (
            (datetime.now(timezone.utc) + timedelta(minutes=1))
            .isoformat()
            .replace("+00:00", "Z")
        )
        self.publisher = "tool-runtime"
        self.project = "fixture"
        self.drop = False
        self.operations: list[str] = []
        self.publication: dict[str, object] | None = None

    def request(self) -> str:
        return json.dumps(
            {
                "schema": "plowshare-tool/1",
                "invocationId": self.identity,
                "project": self.project,
                "provider": "scanner",
                "tool": "network_scope",
                "account": "caller",
                "run": "run",
                "call": "model-call",
                "deadline": self.deadline,
                "arguments": {},
            }
        )

    async def handle(self, socket: ServerConnection) -> None:
        async for source in socket:
            row = _object(json.loads(source))
            operation = row["type"]
            if not isinstance(operation, str):
                raise AssertionError("Expected operation")
            payload = _object(row["payload"])
            self.operations.append(operation)
            result: dict[str, object]
            if operation == "relay.consume":
                event = {
                    "position": "1",
                    "eventId": self.identity,
                    "publisher": self.publisher,
                    "occurredAt": "2026-10-08T00:00:00Z",
                    "publishedAt": "2026-10-08T00:00:00Z",
                    "correlationId": self.identity,
                    "causationId": "run",
                    "payload": {
                        "kind": "TEXT",
                        "text": self.request(),
                        "emits": None,
                        "schedule": None,
                        "fireAt": None,
                    },
                }
                result = {
                    "project": "fixture",
                    "topic": payload["topic"],
                    "group": payload["group"],
                    "consumerId": payload["consumerId"],
                    "status": "DATA",
                    "batchId": str(uuid4()),
                    "fence": "1",
                    "through": "1",
                    "expiresAt": "2099-01-01T00:00:00Z",
                    "expiredThrough": None,
                    "events": [event],
                }
            elif operation == "relay.ack":
                result = {
                    "project": "fixture",
                    "topic": payload["topic"],
                    "group": payload["group"],
                    "batchId": payload["batchId"],
                    "through": "1",
                    "gap": False,
                }
            elif operation == "relay.publish":
                if payload["topic"] != "tool.scanner.catalog" and (
                    payload["parentEventId"] != self.identity
                    or payload["requestId"] != result_request_id(self.identity)
                ):
                    raise AssertionError("Invalid provider correlation")
                self.publication = payload
                if self.drop:
                    await socket.close()
                    return
                result = {
                    "project": "fixture",
                    "topic": payload["topic"],
                    "requestId": payload["requestId"],
                    "position": "1",
                    "publishedAt": payload["occurredAt"],
                }
            elif operation == "relay.log":
                published = self.publication
                events: list[dict[str, object]] = []
                if published is not None:
                    events.append(
                        {
                            "position": "1",
                            "eventId": published["requestId"],
                            "publisher": "sdk:"
                            + hashlib.sha256(b"provider").hexdigest(),
                            "occurredAt": published["occurredAt"],
                            "publishedAt": published["occurredAt"],
                            "correlationId": self.identity,
                            "causationId": self.identity,
                            "payload": {
                                "kind": "TEXT",
                                "text": published["text"],
                                "emits": None,
                                "schedule": None,
                                "fireAt": None,
                            },
                        }
                    )
                result = {
                    "scope": {"project": "fixture", "system": False},
                    "topic": {
                        "name": payload["topic"],
                        "kind": "TEXT",
                        "retentionSeconds": "345600",
                        "maxRecords": None,
                        "through": "1",
                        "expiredThrough": "0",
                    },
                    "after": payload["after"],
                    "next": "1",
                    "gapThrough": None,
                    "events": events,
                    "subscribers": [],
                    "branches": [],
                }
            else:
                raise AssertionError("Unexpected operation")
            await socket.send(
                json.dumps(
                    {
                        "id": row["id"],
                        "type": operation,
                        "protocol_version": PROTOCOL_VERSION,
                        "payload": {"code": "OK", "said": None, "payload": result},
                    }
                )
            )


class ToolsTest(unittest.IsolatedAsyncioTestCase):
    def test_shared_tool_boundaries(self) -> None:
        from plowshare.tools import _decode

        fixture_path = (
            Path(__file__).resolve().parents[3]
            / "test-support/contracts/relay-tools.json"
        )
        fixture = json.loads(fixture_path.read_text())
        declaration = ToolDeclaration(
            "inspect", "Inspect fixture", (Parameter("value", "NUMBER"),)
        )
        self.assertEqual(
            fixture["resultRequestId"], result_request_id(fixture["invocationId"])
        )
        for sample in fixture["cases"]:
            with self.subTest(name=sample["name"]):
                accepted = True
                try:
                    declaration.validate(_decode(sample["source"]).arguments)
                except ValueError:
                    accepted = False
                self.assertEqual(sample["valid"], accepted)

    async def exercise(
        self, *, lost: bool = False, expired: bool = False, recovery: bool = False
    ) -> None:
        fixture = Fixture()
        fixture.drop = lost
        if expired:
            fixture.deadline = "2020-01-01T00:00:00Z"
        calls: list[ToolCall] = []

        async def handler(call: ToolCall) -> ToolResult:
            calls.append(call)
            return ToolResult("COMPLETED", "configured scope")

        with tempfile.TemporaryDirectory() as directory:
            with SqliteToolJournal(Path(directory), configuration=CONFIG) as journal:
                if recovery:
                    journal.save(
                        ToolReceipt(
                            fixture.identity, fixture.request(), "executing", None
                        )
                    )
                async with serve(fixture.handle, "127.0.0.1", 0) as server:
                    port = server.sockets[0].getsockname()[1]
                    async with await Client.connect(
                        f"http://127.0.0.1:{port}",
                        "fixture-token",
                        timeout=1,
                        legacy_transport=True,
                    ) as client:
                        provider = ToolProvider(
                            client,
                            project="fixture",
                            provider="scanner",
                            account="provider",
                            tools=(RegisteredTool(DECLARATION, handler),),
                            journal=journal,
                        )
                        if lost:
                            with self.assertRaises(TransportError):
                                await provider.poll()
                            self.assertEqual(journal.all()[0].phase, "publishing")
                        else:
                            await provider.poll()
                            await (
                                provider.poll()
                            )  # Deliberate redelivery never invokes again.
                            receipt = journal.all()[0]
                            self.assertEqual(receipt.phase, "done")
                            self.assertIsNotNone(receipt.result)
                            if receipt.result is None:
                                raise AssertionError("Missing provider result")
                            self.assertEqual(
                                receipt.result.state,
                                "UNKNOWN"
                                if recovery
                                else "REJECTED"
                                if expired
                                else "COMPLETED",
                            )
                    if lost:
                        fixture.drop = False
                        async with await Client.connect(
                            f"http://127.0.0.1:{port}",
                            "fixture-token",
                            timeout=1,
                            legacy_transport=True,
                        ) as client:
                            provider = ToolProvider(
                                client,
                                project="fixture",
                                provider="scanner",
                                account="provider",
                                tools=(RegisteredTool(DECLARATION, handler),),
                                journal=journal,
                            )
                            with self.assertRaises(RuntimeError):
                                await provider.poll()
                            self.assertEqual(await provider.reconcile(), 1)
                            await provider.poll()
                            self.assertEqual(journal.all()[0].phase, "done")
                self.assertEqual(len(calls), 0 if recovery or expired else 1)
                self.assertEqual(fixture.operations.count("relay.publish"), 1)
                if not recovery:
                    self.assertLess(
                        fixture.operations.index("relay.ack"),
                        fixture.operations.index("relay.publish"),
                    )
            with SqliteToolJournal(Path(directory), configuration=CONFIG) as reopened:
                self.assertEqual(reopened.all()[0].phase, "done")

    async def test_catalogue_publish_withdraw_and_lost_reply_do_not_execute_or_replay(
        self,
    ) -> None:
        fixture = Fixture()
        executions = 0

        async def handler(call: ToolCall) -> ToolResult:
            nonlocal executions
            executions += 1
            return ToolResult("COMPLETED", "unexpected")

        async with serve(fixture.handle, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            with tempfile.TemporaryDirectory() as directory:
                with SqliteToolJournal(
                    Path(directory), configuration=CONFIG
                ) as journal:
                    async with await Client.connect(
                        f"http://127.0.0.1:{port}",
                        "fixture-token",
                        timeout=1,
                        legacy_transport=True,
                    ) as client:
                        provider = ToolProvider(
                            client,
                            project="fixture",
                            provider="scanner",
                            account="provider",
                            tools=(RegisteredTool(DECLARATION, handler),),
                            journal=journal,
                        )
                        await provider.publish_catalog(str(uuid4()))
                        publication = fixture.publication
                        assert publication is not None
                        self.assertEqual(publication["topic"], "tool.scanner.catalog")
                        self.assertEqual(
                            json.loads(str(publication["text"]))["version"],
                            "plowshare-tool-catalog/1",
                        )
                        await provider.withdraw_catalog(str(uuid4()))
                        publication = fixture.publication
                        assert publication is not None
                        self.assertEqual(
                            json.loads(str(publication["text"]))["tools"], []
                        )
                        fixture.drop = True
                        with self.assertRaises(TransportError):
                            await provider.publish_catalog(str(uuid4()))
                        self.assertEqual(fixture.operations.count("relay.publish"), 3)
                        self.assertEqual(executions, 0)

    async def test_named_handler_and_duplicate_delivery(self) -> None:
        await self.exercise()

    async def test_lost_result_is_read_reconciled_without_repeating_execution_or_publication(
        self,
    ) -> None:
        await self.exercise(lost=True)

    async def test_expired_deadline_does_not_execute_handler(self) -> None:
        await self.exercise(expired=True)

    async def test_interrupted_execution_is_unknown_and_never_reexecuted(self) -> None:
        await self.exercise(recovery=True)

    async def test_wrong_publisher_and_scope_have_no_ack_or_effect(self) -> None:
        for change in ("publisher", "project"):
            fixture = Fixture()
            setattr(fixture, change, "foreign")

            async def handler(call: ToolCall) -> ToolResult:
                raise AssertionError("Invalid request executed")

            with tempfile.TemporaryDirectory() as directory:
                with SqliteToolJournal(
                    Path(directory), configuration=CONFIG
                ) as journal:
                    async with serve(fixture.handle, "127.0.0.1", 0) as server:
                        port = server.sockets[0].getsockname()[1]
                        async with await Client.connect(
                            f"http://127.0.0.1:{port}",
                            "fixture-token",
                            timeout=1,
                            legacy_transport=True,
                        ) as client:
                            provider = ToolProvider(
                                client,
                                project="fixture",
                                provider="scanner",
                                account="provider",
                                tools=(RegisteredTool(DECLARATION, handler),),
                                journal=journal,
                            )
                            with self.assertRaises(ValueError):
                                await provider.poll()
                            self.assertEqual(journal.all(), ())
                    self.assertEqual(fixture.operations, ["relay.consume"])

    def test_declarations_refuse_invalid_types_and_unknown_arguments(self) -> None:
        tool = ToolDeclaration(
            "read_value",
            "Read",
            (Parameter("flag", "BOOLEAN"), Parameter("count", "INTEGER")),
        )
        tool.validate({"flag": True, "count": 1})
        cases: tuple[Arguments, ...] = (
            {"flag": "true", "count": 1},
            {"flag": True, "count": 1.5},
            {"flag": True, "count": 1, "extra": 2},
        )
        for args in cases:
            with self.assertRaises(ValueError):
                tool.validate(args)
        with self.assertRaises(ValueError):
            ToolDeclaration("bad__topic", "invalid")
        with self.assertRaises(ValueError):
            ToolResult("COMPLETED", "🐍" * 9000)

    def test_journal_fences_live_owners_and_foreign_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with SqliteToolJournal(Path(directory), configuration=CONFIG):
                with self.assertRaises(sqlite3.OperationalError):
                    SqliteToolJournal(Path(directory), configuration=CONFIG)
            with self.assertRaises(ValueError):
                SqliteToolJournal(Path(directory), configuration=CONFIG + "different")

    def test_publication_identity_survives_reopen_and_refuses_changed_time(
        self,
    ) -> None:
        fixture = Fixture()
        executing = ToolReceipt(fixture.identity, fixture.request(), "executing", None)
        ready = replace(
            executing, phase="ready", result=ToolResult("COMPLETED", "scope")
        )
        publication = replace(
            ready, phase="publishing", occurred_at="2026-10-08T00:00:00Z"
        )
        with tempfile.TemporaryDirectory() as directory:
            with SqliteToolJournal(Path(directory), configuration=CONFIG) as journal:
                for receipt in (executing, ready, publication):
                    journal.save(receipt)
            with SqliteToolJournal(Path(directory), configuration=CONFIG) as journal:
                changed = replace(publication, occurred_at="2026-10-08T00:00:01Z")
                for phase in ("publishing", "done"):
                    with self.assertRaises(ValueError):
                        journal.save(replace(changed, phase=phase))
                    self.assertEqual(journal.all(), (publication,))
                # A transport-proven non-submission clears intent before another attempt.
                journal.save(ready)
                journal.save(changed)
                done = replace(changed, phase="done")
                journal.save(done)
                with self.assertRaises(ValueError):
                    journal.save(replace(done, occurred_at=publication.occurred_at))
                self.assertEqual(journal.all(), (done,))
