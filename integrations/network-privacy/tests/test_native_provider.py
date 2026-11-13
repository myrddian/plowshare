"""Privacy capabilities through the public SDK: durable intent and no uncertain replay."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from plowshare import PROTOCOL_VERSION, Client, TransportError
from plowshare.tool_journal import SqliteToolJournal
from plowshare.tools import ToolAttention, ToolProvider, ToolReceipt, deployment_config
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from websockets.asyncio.server import ServerConnection, serve

from plowshare_privacy.contracts import object_fields, parse_json, text
from plowshare_privacy.native_tools import DECLARATIONS, registered
from plowshare_privacy.tools import WorkerTools
from plowshare_privacy.worker import Worker


class NativeWire:
    """Independent broker fixture; framing evidence does not prove server authorization."""

    def __init__(self, project: str):
        self.project = project
        self.identity = str(uuid4())
        self.deadline = "2099-01-01T00:00:00Z"
        self.publisher = "tool-runtime"
        self.request_project = project
        self.arguments: dict[str, object] = {}
        self.operations: list[str] = []
        self.publications: list[dict[str, object]] = []
        self.drop = False
        self.retained = True

    def request(self) -> str:
        return json.dumps(
            {
                "schema": "plowshare-tool/1",
                "invocationId": self.identity,
                "project": self.request_project,
                "provider": "scanner",
                "tool": "network_scan",
                "account": "caller",
                "run": "run",
                "call": "call",
                "deadline": self.deadline,
                "arguments": self.arguments,
            }
        )

    async def handle(self, socket: ServerConnection) -> None:
        async for source in socket:
            frame = object_fields(
                parse_json(source), {"id", "type", "protocol_version", "payload"}
            )
            operation = text(frame["type"])
            payload = object_fields(
                frame["payload"],
                set(),
                {
                    "project",
                    "topic",
                    "group",
                    "consumerId",
                    "start",
                    "limit",
                    "waitMs",
                    "batchId",
                    "fence",
                    "requestId",
                    "text",
                    "occurredAt",
                    "correlationId",
                    "parentTopic",
                    "parentEventId",
                    "after",
                    "system",
                },
            )
            self.operations.append(operation)
            topic = payload["topic"]
            result: dict[str, object]
            if operation == "relay.consume":
                selected = topic == "tool.scanner.network_scan.request"
                event = {
                    "position": "1",
                    "eventId": self.identity,
                    "publisher": self.publisher,
                    "occurredAt": "2026-01-01T00:00:00Z",
                    "publishedAt": "2026-01-01T00:00:00Z",
                    "correlationId": self.identity,
                    "causationId": "run",
                    "payload": {
                        "kind": "TEXT",
                        "text": self.request(),
                        "schedule": None,
                        "emits": None,
                        "fireAt": None,
                    },
                }
                result = {
                    "project": self.project,
                    "topic": topic,
                    "group": payload["group"],
                    "consumerId": payload["consumerId"],
                    "status": "DATA" if selected else "EMPTY",
                    "batchId": str(uuid4()) if selected else None,
                    "fence": "1" if selected else None,
                    "through": "1" if selected else "0",
                    "expiresAt": "2099-01-01T00:00:00Z" if selected else None,
                    "expiredThrough": None,
                    "events": [event] if selected else [],
                }
            elif operation == "relay.ack":
                result = {
                    "project": self.project,
                    "topic": topic,
                    "group": payload["group"],
                    "batchId": payload["batchId"],
                    "through": "1",
                    "gap": False,
                }
            elif operation == "relay.publish":
                self.publications.append(payload)
                if self.drop:
                    await socket.close()
                    return
                result = {
                    "project": self.project,
                    "topic": topic,
                    "requestId": payload["requestId"],
                    "position": "1",
                    "publishedAt": payload["occurredAt"],
                }
            elif operation == "relay.log":
                events: list[dict[str, object]] = []
                if self.publications and self.retained:
                    published = self.publications[-1]
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
                                "schedule": None,
                                "emits": None,
                                "fireAt": None,
                            },
                        }
                    )
                result = {
                    "scope": {"project": self.project, "system": False},
                    "topic": {
                        "name": topic,
                        "kind": "TEXT",
                        "retentionSeconds": "3600",
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
                        "id": frame["id"],
                        "type": operation,
                        "protocol_version": PROTOCOL_VERSION,
                        "payload": {"code": "OK", "said": None, "payload": result},
                    }
                )
            )


class NativeProviderTest(unittest.IsolatedAsyncioTestCase):
    async def exercise(
        self,
        *,
        lost: bool = False,
        absent: bool = False,
        interrupted: bool = False,
        expired: bool = False,
        foreign: bool = False,
        invalid_arguments: bool = False,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            relay = FixturePort(config)
            worker = Worker(config, relay, MemoryReceipts(), CountingCollector(config))
            wire = NativeWire(config.project)
            wire.drop = lost
            wire.retained = not absent
            if expired:
                wire.deadline = "2020-01-01T00:00:00Z"
            if foreign:
                wire.request_project = "foreign"
            if invalid_arguments:
                wire.arguments = {"targets": ["192.0.2.1"]}
            binding = deployment_config(
                config.project, "scanner", "provider", DECLARATIONS
            )
            with SqliteToolJournal(
                config.state_directory / "tools", configuration=binding
            ) as journal:
                if interrupted:
                    journal.save(
                        ToolReceipt(wire.identity, wire.request(), "executing", None)
                    )
                async with serve(wire.handle, "127.0.0.1", 0) as server:
                    origin = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
                    async with await Client.connect(
                        origin,
                        "fixture",
                        timeout=1,
                        legacy_transport=True,
                        dispatch_mode="ordered",
                    ) as client:
                        provider = ToolProvider(
                            client,
                            project=config.project,
                            provider="scanner",
                            account="provider",
                            tools=registered(WorkerTools(worker)),
                            journal=journal,
                        )
                        if foreign or invalid_arguments:
                            with self.assertRaises(ValueError):
                                await provider.poll()
                            self.assertEqual(journal.all(), ())
                            self.assertNotIn("relay.ack", wire.operations)
                        elif lost:
                            with self.assertRaises(TransportError):
                                await provider.poll()
                            self.assertEqual(journal.all()[0].phase, "publishing")
                        else:
                            await provider.poll()
                            await provider.poll()
                            result = journal.all()[0].result
                            self.assertIsNotNone(result)
                            assert result is not None
                            self.assertEqual(
                                result.state,
                                "UNKNOWN"
                                if interrupted
                                else "REJECTED"
                                if expired
                                else "COMPLETED",
                            )
                    if lost:
                        wire.drop = False
                        async with await Client.connect(
                            origin,
                            "fixture",
                            timeout=1,
                            legacy_transport=True,
                            dispatch_mode="ordered",
                        ) as client:
                            provider = ToolProvider(
                                client,
                                project=config.project,
                                provider="scanner",
                                account="provider",
                                tools=registered(WorkerTools(worker)),
                                journal=journal,
                            )
                            with self.assertRaises(ToolAttention):
                                await provider.poll()
                            if absent:
                                self.assertEqual(0, await provider.reconcile())
                                self.assertEqual("publishing", journal.all()[0].phase)
                                with self.assertRaises(ToolAttention):
                                    await provider.poll()
                            else:
                                self.assertEqual(1, await provider.reconcile())
                                await provider.poll()
                self.assertEqual(
                    len(relay.published),
                    0 if interrupted or expired or foreign or invalid_arguments else 1,
                )
                self.assertEqual(
                    len(wire.publications), 0 if foreign or invalid_arguments else 1
                )
                self.assertFalse(
                    any(
                        operation.startswith("outgoing.")
                        for operation in wire.operations
                    )
                )
                if relay.published:
                    self.assertEqual(wire.identity, relay.published[0].request_id)
                    self.assertEqual(
                        "tool.scanner.network_scan.request",
                        relay.published[0].parent_topic,
                    )
                    self.assertEqual(wire.identity, relay.published[0].parent_event_id)

    async def test_native_scan_identity_and_redelivery_do_not_duplicate_effect(
        self,
    ) -> None:
        await self.exercise()

    async def test_expired_admission_never_requests_scan(self) -> None:
        await self.exercise(expired=True)

    async def test_foreign_scope_and_arbitrary_targets_have_no_ack_or_effect(
        self,
    ) -> None:
        await self.exercise(foreign=True)
        await self.exercise(invalid_arguments=True)

    async def test_committed_lost_result_is_read_reconciled_without_replay(
        self,
    ) -> None:
        await self.exercise(lost=True)

    async def test_absent_result_never_authorizes_retry(self) -> None:
        await self.exercise(lost=True, absent=True)

    async def test_interrupted_execution_remains_unknown_without_handler(self) -> None:
        await self.exercise(interrupted=True)
