"""Admission, cancellation and opening through a real local WebSocket."""

from __future__ import annotations

import asyncio
import json
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from websockets.asyncio.server import ServerConnection, serve

from plowshare import CancelledRequest, Client, TransportError
from plowshare._codec import _object
from plowshare.contracts import MemoryIndexRequest


class Peer:
    def __init__(self) -> None:
        self.operations: list[str] = []
        self.hold = False
        self.refuse = False
        self.received = asyncio.Event()

    async def handle(self, socket: ServerConnection) -> None:
        async for wire in socket:
            row = _object(json.loads(wire))
            operation = row.get("type")
            if not isinstance(operation, str):
                raise AssertionError("Missing operation")
            self.operations.append(operation)
            if operation == "transport.dispatch":
                code = "BAD_REQUEST" if self.refuse else "OK"
                payload: object = {
                    "mode": "concurrent-v1",
                    "maxPending": 64,
                    "maxIdBytes": 128,
                }
            else:
                self.received.set()
                if self.hold:
                    continue
                code, payload = "OK", []
            await socket.send(
                json.dumps(
                    {
                        "id": row["id"],
                        "type": operation,
                        "protocol_version": "plowshare-v1",
                        "payload": {"code": code, "payload": payload},
                    }
                )
            )

    @asynccontextmanager
    async def open(
        self, *, ordered: bool = False, limit: int = 1024 * 1024
    ) -> AsyncIterator[Client]:
        async with serve(self.handle, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with await Client.connect(
                f"http://127.0.0.1:{port}",
                "fixture-token",
                timeout=2,
                legacy_transport=True,
                dispatch_mode="ordered" if ordered else "concurrent-v1",
                max_pending_bytes=limit,
            ) as client:
                yield client


class AsyncClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_repeated_close_joins_all_admitted_reservation_release(self) -> None:
        peer = Peer()
        peer.hold = True
        async with peer.open() as client:
            calls = [
                asyncio.create_task(client.request(MemoryIndexRequest()))
                for _ in range(64)
            ]
            await asyncio.wait_for(peer.received.wait(), 1)
            self.assertEqual(client._admitted, 64)
            self.assertGreater(client._held, 0)
            await asyncio.gather(client.close(), client.close())
            # Check the close boundary before awaiting caller tasks: their finally
            # blocks, including held bytes, belong to the connection's shared drain.
            self.assertEqual((client._admitted, client._held), (0, 0))
            self.assertTrue(client._idle.is_set())
            results = await asyncio.gather(*calls, return_exceptions=True)
            self.assertTrue(
                all(isinstance(result, TransportError) for result in results)
            )

    async def test_writer_waiters_are_bounded_and_cancel_before_submission(
        self,
    ) -> None:
        peer = Peer()
        async with peer.open() as client:
            await client._send_lock.acquire()
            calls = [
                asyncio.create_task(client.request(MemoryIndexRequest()))
                for _ in range(64)
            ]
            try:
                await asyncio.sleep(0)  # Admit tasks onto the deliberately held writer.
                self.assertEqual(client._admitted, 64)
                with self.assertRaises(TransportError) as refused:
                    await client.request(MemoryIndexRequest())
                self.assertEqual(refused.exception.delivery, "NOT_SUBMITTED")
                self.assertEqual(peer.operations, ["transport.dispatch"])
                calls[0].cancel()
                with self.assertRaises(CancelledRequest) as cancelled:
                    await calls[0]
                self.assertEqual(cancelled.exception.delivery, "NOT_SUBMITTED")
            finally:
                client._send_lock.release()
            replies = await asyncio.gather(*calls[1:])
            self.assertTrue(all(reply.require_payload() == () for reply in replies))
            self.assertEqual(peer.operations.count("memory.index"), 63)
            self.assertEqual((client._admitted, client._held), (0, 0))

    async def test_cancel_after_write_does_not_cancel_an_independent_request(
        self,
    ) -> None:
        peer = Peer()
        peer.hold = True
        async with peer.open() as client:
            first = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.received.wait(), 1)
            first.cancel()
            with self.assertRaises(CancelledRequest) as cancelled:
                await first
            self.assertEqual(cancelled.exception.delivery, "UNKNOWN")
            peer.hold = False
            self.assertEqual(
                (await client.request(MemoryIndexRequest())).require_payload(), ()
            )
            self.assertEqual(peer.operations.count("memory.index"), 2)

    async def test_encoded_capacity_rejects_before_write_and_opening_refusal_has_no_fallback(
        self,
    ) -> None:
        peer = Peer()
        async with peer.open(limit=256) as client:
            with self.assertRaises(TransportError) as refused:
                await client.request(MemoryIndexRequest(project="p" * 220))
            self.assertEqual(refused.exception.delivery, "NOT_SUBMITTED")
            self.assertEqual(peer.operations, ["transport.dispatch"])
            self.assertEqual((client._admitted, client._held), (0, 0))
        peer = Peer()
        peer.refuse = True
        with self.assertRaises(TransportError) as opening:
            async with peer.open():
                self.fail("Refused negotiation produced a client")
        self.assertEqual(opening.exception.delivery, "NOT_SUBMITTED")
        self.assertEqual(peer.operations, ["transport.dispatch"])

    async def test_ordered_compatibility_is_explicit_and_sends_no_control_exchange(
        self,
    ) -> None:
        peer = Peer()
        async with peer.open(ordered=True) as client:
            self.assertEqual(
                (await client.request(MemoryIndexRequest())).require_payload(), ()
            )
        self.assertEqual(peer.operations, ["memory.index"])
