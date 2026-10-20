"""Real installed SDK and a controlled socket, not a live Plowshare deployment."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from plowshare import Client, Delivery, TransportError
from test_agile import configuration, uid
from websockets.asyncio.server import ServerConnection, serve

from plowshare_agile.board import Board
from plowshare_agile.journal import Intent, Journal
from plowshare_agile.model import TicketFields
from plowshare_agile.sdk import SdkBoardPort


class SocketTests(unittest.IsolatedAsyncioTestCase):
    async def test_board_query_budget_and_serialized_response_bytes(self) -> None:
        """Measure fixture traffic at the real SDK boundary, excluding socket framing."""
        for tickets, epics, cold_calls, warm_calls in (
            (50, 10, 62, 2),
            (100, 20, 123, 3),
        ):
            with (
                self.subTest(tickets=tickets, epics=epics),
                tempfile.TemporaryDirectory() as folder,
            ):
                config = configuration(Path(folder))
                sent: list[tuple[int, int]] = []  # UTF-8 envelope bytes, document rows.
                documents = {
                    kind: [
                        {
                            "owner": "fixture-owner",
                            "resource": uid(start + number),
                            "revision": uid(start + number + 1000),
                            "scope": {
                                "kind": "project",
                                "project": "fixture",
                                "includeGlobal": False,
                            },
                            "sourceName": f"agile-{kind}/{uid(start + number)}.md",
                            "tags": ["sdk"],
                            "title": "Extraction title",
                        }
                        for number in range(count)
                    ]
                    for kind, count, start in (
                        ("ticket", tickets, 10),
                        ("epic", epics, 200),
                    )
                }

                async def handle(socket: ServerConnection) -> None:
                    async for message in socket:
                        row = json.loads(message)
                        request = row["payload"]
                        rows = 0
                        if row["type"] == "information.documents.list":
                            kind = (
                                "ticket"
                                if request["filter"]["contentType"]
                                == config.ticket_type
                                else "epic"
                            )
                            offset, limit = request["offset"], request["limit"]
                            page = documents[kind][offset : offset + limit]
                            rows = len(page)
                            payload = {
                                "documents": page,
                                "offset": offset,
                                "limit": limit,
                            }
                        else:
                            self.assertEqual(row["type"], "information.content.get")
                            kind = (
                                "ticket"
                                if any(
                                    v["revision"] == request["revision"]
                                    for v in documents["ticket"]
                                )
                                else "epic"
                            )
                            fields: dict[str, str | None] = {"title": "Typed title"}
                            if kind == "ticket":
                                fields.update(
                                    {
                                        "kind": "feature",
                                        "status": "backlog",
                                        "priority": "normal",
                                        "assignee": None,
                                        "iteration": None,
                                    }
                                )
                            payload = {
                                "revision": request["revision"],
                                "authorisedStages": [],
                                "processingPolicy": "explicit_only",
                                "reused": [],
                                "contentType": {
                                    "contract": {
                                        "definitionHash": "a" * 64,
                                        "description": "Fixture",
                                        "label": kind,
                                        "ordinal": 1,
                                        "presentation": {},
                                        "profile": "plowshare-document-metadata/1",
                                        "schema": {"type": "object"},
                                        "slug": "agile-" + kind,
                                        "type": config.type_id(kind),
                                        "version": config.version_id(kind),
                                    },
                                    "metadata": fields,
                                    "metadataHash": "b" * 64,
                                },
                            }
                        encoded = json.dumps(
                            {
                                "id": row["id"],
                                "type": row["type"],
                                "protocol_version": "plowshare-v1",
                                "payload": {
                                    "code": "OK",
                                    "said": None,
                                    "payload": payload,
                                },
                            },
                            separators=(",", ":"),
                        )
                        sent.append((len(encoded.encode("utf-8")), rows))
                        await socket.send(encoded)

                async with serve(handle, "127.0.0.1", 0) as server:
                    port = server.sockets[0].getsockname()[1]
                    async with await Client.connect(
                        f"http://127.0.0.1:{port}",
                        "fixture-bearer",
                        legacy_transport=True,
                        dispatch_mode="ordered",
                    ) as client:
                        application = SdkBoardPort(client, config)
                        for kind in ("ticket", "epic"):
                            await application.list(kind, ())
                        self.assertEqual(len(sent), cold_calls)
                        cold_bytes = sum(size for size, _ in sent)
                        self.assertEqual(sum(rows for _, rows in sent), tickets + epics)
                        sent.clear()
                        for kind in ("ticket", "epic"):
                            await application.list(kind, ())
                        self.assertEqual(len(sent), warm_calls)
                        warm_bytes = sum(size for size, _ in sent)
                        self.assertEqual(sum(rows for _, rows in sent), tickets + epics)
                        self.assertLess(warm_bytes, cold_bytes // 2)
                        print(
                            f"SDK board fixture {tickets}+{epics}: cold={cold_bytes} B/{cold_calls} calls, unchanged={warm_bytes} B/{warm_calls} calls"
                        )

    async def test_original_body_revision_crosses_the_public_socket(self) -> None:
        received: list[object] = []
        source = "## Notes\r\n\r\n**Edited** source 🙂\r\n"

        async def handle(socket: ServerConnection) -> None:
            row = json.loads(await socket.recv())
            received.append(row)
            self.assertEqual(row["type"], "information.revise")
            self.assertEqual(row["payload"]["revision"], uid(1010))
            self.assertEqual(row["payload"]["expectedRevision"], uid(1010))
            self.assertEqual(row["payload"]["text"], source)
            self.assertEqual(row["payload"]["typing"]["mode"], "inherit")
            await socket.send(
                json.dumps(
                    {
                        "id": row["id"],
                        "type": row["type"],
                        "protocol_version": "plowshare-v1",
                        "payload": {
                            "code": "OK",
                            "said": None,
                            "payload": {
                                "resource": uid(10),
                                "revision": uid(2010),
                                "created": False,
                            },
                        },
                    }
                )
            )
            await socket.wait_closed()

        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            async with serve(handle, "127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                async with await Client.connect(
                    f"http://127.0.0.1:{port}",
                    "fixture-bearer",
                    legacy_transport=True,
                    dispatch_mode="ordered",
                ) as client:
                    result = await SdkBoardPort(client, config).submit(
                        Intent(
                            "body",
                            "ticket",
                            uid(62),
                            TicketFields("Ticket"),
                            uid(10),
                            uid(1010),
                            source,
                        )
                    )
                    self.assertEqual(result.operation, "information.revise")
        self.assertEqual(len(received), 1)

    async def test_generated_metadata_request_crosses_the_public_socket_once(
        self,
    ) -> None:
        received: list[object] = []

        async def handle(socket: ServerConnection) -> None:
            row = json.loads(await socket.recv())
            received.append(row)
            self.assertEqual(row["type"], "information.content.revise")
            self.assertEqual(
                row["payload"]["scope"],
                {"kind": "project", "project": "fixture", "includeGlobal": False},
            )
            self.assertEqual(row["payload"]["view"], "content-types-v1")
            self.assertEqual(row["payload"]["expectedRevision"], uid(1010))
            self.assertEqual(row["payload"]["typing"]["metadata"]["status"], "done")
            await socket.send(
                json.dumps(
                    {
                        "id": row["id"],
                        "type": row["type"],
                        "protocol_version": "plowshare-v1",
                        "payload": {
                            "code": "OK",
                            "said": None,
                            "payload": {
                                "resource": uid(10),
                                "revision": uid(2010),
                                "created": False,
                            },
                        },
                    }
                )
            )
            await socket.wait_closed()

        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            async with serve(handle, "127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                # Explicit legacy mode is confined to this fixture. Production
                # uses the SDK's negotiated segmented transport and concurrent
                # dispatch. This fixture models only ordered application frames.
                async with await Client.connect(
                    f"http://127.0.0.1:{port}",
                    "fixture-bearer",
                    legacy_transport=True,
                    dispatch_mode="ordered",
                ) as client:
                    board = Board(
                        SdkBoardPort(client, config), Journal(config, "fixture-bearer")
                    )
                    result = await board.submit(
                        Intent(
                            "revise",
                            "ticket",
                            uid(60),
                            TicketFields("Ticket", status="done"),
                            uid(10),
                            uid(1010),
                        )
                    )
                    self.assertEqual(result.revision, uid(2010))
                    self.assertEqual(board.journal.read(uid(60))[1], "confirmed")
        self.assertEqual(len(received), 1)

    async def test_lost_socket_reply_leaves_exact_intent_pending_without_reconnect(
        self,
    ) -> None:
        received = 0

        async def handle(socket: ServerConnection) -> None:
            nonlocal received
            await socket.recv()
            received += 1
            await socket.close()

        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            journal = Journal(config, "fixture-bearer")
            async with serve(handle, "127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                async with await Client.connect(
                    f"http://127.0.0.1:{port}",
                    "fixture-bearer",
                    legacy_transport=True,
                    dispatch_mode="ordered",
                ) as client:
                    with self.assertRaises(TransportError) as caught:
                        await Board(SdkBoardPort(client, config), journal).submit(
                            Intent(
                                "revise",
                                "ticket",
                                uid(61),
                                TicketFields("Ticket", status="done"),
                                uid(10),
                                uid(1010),
                            )
                        )
                    self.assertEqual(caught.exception.delivery, Delivery.UNKNOWN)
            self.assertEqual(journal.read(uid(61))[1], "pending")
        self.assertEqual(received, 1)
