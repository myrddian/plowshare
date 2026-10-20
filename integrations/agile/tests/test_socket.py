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
        """Measure real SDK traffic; fixture bytes exclude framing, compression and bodies."""
        for tickets, epics, calls in ((50, 10, 2), (100, 20, 3)):
            with (
                self.subTest(tickets=tickets, epics=epics),
                tempfile.TemporaryDirectory() as folder,
            ):
                config = configuration(Path(folder))
                sent: list[tuple[int, int]] = []
                documents = {}
                for kind, count, start in (
                    ("ticket", tickets, 10),
                    ("epic", epics, 200),
                ):
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
                    documents[kind] = [
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
                            "contentMetadata": {
                                "type": {
                                    "type": config.type_id(kind),
                                    "version": config.version_id(kind),
                                    "ordinal": 1,
                                    "slug": "agile-" + kind,
                                    "label": kind,
                                    "definitionHash": "a" * 64,
                                },
                                "metadata": fields,
                                "metadataHash": "b" * 64,
                            },
                        }
                        for number in range(count)
                    ]

                async def handle(socket: ServerConnection) -> None:
                    async for message in socket:
                        row = json.loads(message)
                        self.assertEqual(row["type"], "information.documents.list")
                        request = row["payload"]
                        self.assertEqual(request["projection"], "metadata-v1")
                        self.assertEqual(request["view"], "content-types-v1")
                        kind = (
                            "ticket"
                            if request["filter"]["contentType"] == config.ticket_type
                            else "epic"
                        )
                        offset, limit = request["offset"], request["limit"]
                        page = documents[kind][offset : offset + limit]
                        encoded = json.dumps(
                            {
                                "id": row["id"],
                                "type": row["type"],
                                "protocol_version": "plowshare-v1",
                                "payload": {
                                    "code": "OK",
                                    "said": None,
                                    "payload": {
                                        "documents": page,
                                        "offset": offset,
                                        "limit": limit,
                                        "projection": "metadata-v1",
                                    },
                                },
                            },
                            separators=(",", ":"),
                        )
                        sent.append((len(encoded.encode("utf-8")), len(page)))
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
                        sizes = []
                        for _ in range(2):
                            sent.clear()
                            for kind in ("ticket", "epic"):
                                await application.list(kind, ())
                            self.assertEqual(len(sent), calls)
                            self.assertEqual(
                                sum(rows for _, rows in sent), tickets + epics
                            )
                            sizes.append(sum(size for size, _ in sent))
                        self.assertEqual(sizes[0], sizes[1])
                        print(
                            f"SDK projected board fixture {tickets}+{epics}: {sizes[0]} B/{calls} calls, cold or repeated"
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
