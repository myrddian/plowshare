"""Exercises the installed public Python SDK across an actual WebSocket boundary."""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from plowshare import PROTOCOL_VERSION, Client
from plowshare.contracts import (
    RelayBatchDto,
    RelayCausationDto,
    RelayEventDto,
    RelayPublishRequest,
)
from support import CountingCollector, FixturePort, MemoryReceipts, configuration, event
from websockets.asyncio.server import ServerConnection, serve

from plowshare_privacy.contracts import (
    Evidence,
    identifier,
    object_fields,
    parse_json,
    text,
    timestamp,
    uuid,
)
from plowshare_privacy.peer import SdkOutgoingPort, result_message
from plowshare_privacy.peer_journal import PeerReceipt
from plowshare_privacy.ports import SdkPrivacyPort
from plowshare_privacy.worker import Worker


def event_wire(value: RelayEventDto) -> dict[str, object]:
    ancestry = value.causation
    if not isinstance(ancestry, RelayCausationDto):
        raise ValueError("Fixture event requires causation")
    return {
        "eventId": value.event_id,
        "position": value.position,
        "publisher": value.publisher,
        "occurredAt": value.occurred_at,
        "publishedAt": value.published_at,
        "correlationId": value.correlation_id,
        "causationId": value.causation_id,
        "causation": {
            "rootId": ancestry.root_id,
            "parentId": ancestry.parent_id,
            "depth": ancestry.depth,
        },
        "payload": {
            "kind": value.payload.kind,
            "text": value.payload.text,
            "schedule": value.payload.schedule,
            "emits": value.payload.emits,
            "fireAt": value.payload.fire_at,
        },
    }


def batch_wire(value: RelayBatchDto) -> dict[str, object]:
    return {
        "project": value.project,
        "topic": value.topic,
        "group": value.group,
        "consumerId": value.consumer_id,
        "batchId": value.batch_id,
        "fence": value.fence,
        "expiresAt": value.expires_at,
        "expiredThrough": value.expired_through,
        "through": value.through,
        "status": value.status,
        "events": [event_wire(item) for item in value.events],
    }


class SdkTest(unittest.IsolatedAsyncioTestCase):
    # These fixtures own legacy JSON envelopes. Select that transport explicitly;
    # SDK packet tests separately own segmented negotiation and reassembly.

    async def test_report_list_hydrates_omitted_dependencies_through_scoped_status(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            report, source = str(uuid4()), str(uuid4())
            operations: list[str] = []
            foreign = False

            async def handle(socket: ServerConnection) -> None:
                async for source_frame in socket:
                    frame = json.loads(source_frame)
                    operation = frame["type"]
                    operations.append(operation)
                    payload = frame["payload"]
                    self.assertEqual(payload["scope"]["project"], config.project)
                    self.assertEqual(payload["scope"]["kind"], "project")
                    summary = {"id": report, "kind": "report", "report_status": "draft"}
                    if operation == "information.list":
                        self.assertEqual(payload["kind"], "report")
                        result: object = [summary]
                    elif operation == "information.status":
                        self.assertEqual(payload["revision"], report)
                        result = {
                            **summary,
                            "id": str(uuid4()) if foreign else report,
                            "inputs": [source],
                        }
                    else:
                        raise AssertionError("Unexpected operation " + operation)
                    await socket.send(
                        json.dumps(
                            {
                                "id": frame["id"],
                                "type": operation,
                                "protocol_version": PROTOCOL_VERSION,
                                "payload": {
                                    "code": "OK",
                                    "said": None,
                                    "payload": result,
                                },
                            }
                        )
                    )

            async with serve(handle, "127.0.0.1", 0) as server:
                origin = "http://127.0.0.1:" + str(server.sockets[0].getsockname()[1])
                async with await Client.connect(
                    origin, "fixture-token", timeout=2, legacy_transport=True
                ) as client:
                    reports = await SdkPrivacyPort(client, config).reports()
                    self.assertEqual(len(reports), 1)
                    self.assertEqual(reports[0].id, report)
                    self.assertEqual(reports[0].inputs, (source,))
                    self.assertEqual(reports[0].report_status, "draft")
                    foreign = True
                    with self.assertRaisesRegex(ValueError, "foreign"):
                        await SdkPrivacyPort(client, config).reports()
            self.assertEqual(operations, ["information.list", "information.status"] * 2)

    async def test_outgoing_peer_advertises_claims_and_reports_over_public_sdk(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = replace(
                configuration(Path(folder)), outgoing_peer="privacy-scanner"
            )
            identity = str(uuid4())
            receipt = PeerReceipt(identity, 1, "ready", "COMPLETED", '{"results":[]}')
            value: dict[str, object] = {
                "id": identity,
                "requestId": str(uuid4()),
                "project": config.project,
                "peer": config.outgoing_peer,
                "conversation": None,
                "createdAt": "2026-01-01T00:00:00Z",
                "error": None,
                "remoteContext": None,
                "remoteTask": None,
                "result": None,
                "revision": 1,
                "state": "DISPATCHED",
                "cancelRequested": False,
                "message": {
                    "parts": [
                        {
                            "data": {
                                "schema": "plowshare-integration/1",
                                "binding": config.collector,
                                "operation": "actions.execute",
                                "arguments": {
                                    "action": "network.scan",
                                    "parameters": {},
                                },
                            }
                        }
                    ]
                },
            }
            requests: list[str] = []

            async def handle(socket: ServerConnection) -> None:
                async for source in socket:
                    frame = object_fields(
                        parse_json(source),
                        {"id", "type", "protocol_version", "payload"},
                    )
                    operation = text(frame["type"])
                    payload = frame["payload"]
                    if not isinstance(payload, dict):
                        raise AssertionError("Expected request payload")
                    requests.append(operation)
                    result: object
                    if operation == "outgoing.advertise":
                        self.assertEqual(payload["project"], config.project)
                        self.assertEqual(payload["peers"], [config.outgoing_peer])
                        self.assertIn("agentCards", payload)
                        result = None
                    elif operation == "outgoing.claim":
                        result = {"action": "send", "work": value}
                    elif operation == "outgoing.report":
                        self.assertEqual(payload["id"], identity)
                        self.assertEqual(payload["revision"], 1)
                        value.update(
                            result=payload["result"], state=payload["state"], revision=2
                        )
                        result = value
                    elif operation == "outgoing.status":
                        result = value
                    else:
                        raise AssertionError("Unexpected operation")
                    await socket.send(
                        json.dumps(
                            {
                                "id": frame["id"],
                                "type": operation,
                                "protocol_version": PROTOCOL_VERSION,
                                "payload": {
                                    "code": "OK",
                                    "said": None,
                                    "payload": result,
                                },
                            }
                        )
                    )

            async with serve(handle, "127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                config = replace(config, origin=f"http://127.0.0.1:{port}")
                # This local fixture implements the legacy JSON envelope explicitly.
                async with await Client.connect(
                    config.origin, "fixture-token", timeout=2, legacy_transport=True
                ) as client:
                    outgoing = SdkOutgoingPort(client, config)
                    await outgoing.advertise()
                    claimed = await outgoing.claim()
                    self.assertEqual(claimed.action, "send")
                    self.assertIsNotNone(claimed.work)
                    reported = await outgoing.report(receipt)
                    self.assertEqual(reported.result, result_message(receipt))
                    self.assertEqual(await outgoing.status(identity), reported)
                    self.assertEqual(
                        requests,
                        [
                            "outgoing.advertise",
                            "outgoing.claim",
                            "outgoing.report",
                            "outgoing.status",
                        ],
                    )

    async def test_schedule_to_retained_evidence_and_completion_over_public_sdk(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            fixture = FixturePort(config)
            fixture.events["schedule.due"] = [event()]
            requests: list[str] = []
            batches: dict[str, RelayBatchDto] = {}

            async def answer(operation: str, row: dict[str, object]) -> object:
                if operation == "relay.topics":
                    self.assertEqual(row["project"], config.project)
                    return {
                        "scope": {"project": config.project, "system": False},
                        "topics": [
                            {
                                "name": topic,
                                "kind": "TEXT",
                                "generation": str(uuid4()),
                                "through": "0",
                                "expiredThrough": "0",
                                "retentionSeconds": "345600",
                                "maxRecords": None,
                            }
                            for topic in sorted(fixture.registered_topics)
                        ],
                    }
                if operation == "relay.consume":
                    self.assertEqual(row["project"], config.project)
                    self.assertEqual(row["group"], config.group)
                    result = await fixture.consume(
                        identifier(row["topic"]), uuid(row["consumerId"])
                    )
                    if result.batch_id:
                        batches[result.batch_id] = result
                    return batch_wire(result)
                if operation == "relay.ack":
                    batch = batches[uuid(row["batchId"])]
                    self.assertEqual(row["fence"], batch.fence)
                    await fixture.acknowledge(batch)
                    return {
                        "project": batch.project,
                        "topic": batch.topic,
                        "group": batch.group,
                        "batchId": batch.batch_id,
                        "through": batch.through,
                        "gap": False,
                    }
                if operation == "information.upload":
                    self.assertEqual(
                        row["scope"],
                        {
                            "kind": "project",
                            "project": config.project,
                            "includeShared": False,
                        },
                    )
                    content = row["text"]
                    if not isinstance(content, str) or len(content) > 65536:
                        raise ValueError("Invalid fixture upload")
                    evidence = Evidence.decode(parse_json(content))
                    admission = await fixture.upload(evidence, uuid(row["requestId"]))
                    return {
                        "created": admission.created,
                        "resource": admission.resource,
                        "revision": admission.revision,
                    }
                if operation == "relay.publish":
                    publication = await fixture.publish(
                        RelayPublishRequest(
                            project=identifier(row["project"]),
                            topic=identifier(row["topic"]),
                            request_id=uuid(row["requestId"]),
                            text=text(row["text"], 65536),
                            occurred_at=timestamp(row["occurredAt"]),
                            correlation_id=text(row["correlationId"]),
                            parent_topic=identifier(row["parentTopic"]),
                            parent_event_id=text(row["parentEventId"]),
                        )
                    )
                    return {
                        "project": publication.project,
                        "topic": publication.topic,
                        "requestId": publication.request_id,
                        "position": publication.position,
                        "publishedAt": publication.published_at,
                    }
                if operation == "information.read":
                    self.assertEqual(
                        row["scope"],
                        {
                            "kind": "project",
                            "project": config.project,
                            "includeShared": False,
                        },
                    )
                    # The second character occupies two UTF-16 units. Follow
                    # server end offsets, rather than Python string lengths.
                    offset = row["offset"]
                    windows = {0: ("A", 1), 1: ("🐍", 3), 3: ("B", 4)}
                    if type(offset) is not int or offset not in windows:
                        raise AssertionError("Invalid server read offset")
                    content, end = windows[offset]
                    return {
                        "revision": row["revision"],
                        "start": offset,
                        "end": end,
                        "total": 4,
                        "text": content,
                    }
                raise AssertionError("Unexpected operation " + operation)

            async def handle(socket: ServerConnection) -> None:
                self.assertIsNotNone(socket.request)
                if socket.request is None:
                    raise AssertionError("No upgrade request")
                self.assertEqual(
                    socket.request.headers["Authorization"],
                    "Bearer fixture-plowshare-token",
                )
                async for source in socket:
                    frame = object_fields(
                        parse_json(source),
                        {"id", "type", "protocol_version", "payload"},
                    )
                    self.assertEqual(frame["protocol_version"], PROTOCOL_VERSION)
                    operation = text(frame["type"])
                    payload = frame["payload"]
                    if not isinstance(payload, dict):
                        raise AssertionError("Expected request payload")
                    row: dict[str, object] = {
                        key: value for key, value in payload.items()
                    }
                    requests.append(operation)
                    result = await answer(operation, row)
                    await socket.send(
                        json.dumps(
                            {
                                "id": frame["id"],
                                "type": operation,
                                "protocol_version": PROTOCOL_VERSION,
                                "payload": {
                                    "code": "OK",
                                    "said": None,
                                    "payload": result,
                                },
                            }
                        )
                    )

            async with serve(handle, "127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                connected_config = replace(config, origin=f"http://127.0.0.1:{port}")
                # This local fixture implements the legacy JSON envelope explicitly.
                async with await Client.connect(
                    connected_config.origin,
                    "fixture-plowshare-token",
                    timeout=2,
                    legacy_transport=True,
                ) as client:
                    receipts = MemoryReceipts()
                    worker = Worker(
                        connected_config,
                        SdkPrivacyPort(client, connected_config),
                        receipts,
                        CountingCollector(connected_config),
                    )
                    await asyncio.wait_for(worker.poll(), 5)
                    self.assertEqual(receipts.all()[0].phase, "done")
                    self.assertEqual(
                        requests,
                        [
                            "relay.topics",
                            "relay.consume",
                            "relay.ack",
                            "relay.consume",
                            "information.upload",
                            "relay.publish",
                        ],
                    )
                    self.assertEqual(fixture.uploads, 1)
                    revision = receipts.all()[0].revision
                    if revision is None:
                        self.fail("Missing revision")
                    self.assertEqual(
                        await SdkPrivacyPort(client, connected_config).read_source(
                            revision
                        ),
                        "A🐍B",
                    )
