"""Dispatch backpressure through a real local WebSocket.

These are the cases the shared conformance harness cannot make deterministic: a
cancellation racing the grant or the socket write, a resubmission that must not
re-arm the deadline, and parked callers released on close. The harness drives the
seven contract scenarios; this file drives the races around them.
"""

from __future__ import annotations

import asyncio
import json
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from websockets.asyncio.server import ServerConnection, serve

from plowshare import CancelledRequest, Client, TransportError
from plowshare._codec import _object
from plowshare.contracts import MemoryIndexRequest


class CapacityPeer:
    """A server that can advertise a small window, refuse and hold.

    Refusals apply to the connection's first application request id only, as the
    shared fixture's `capacityContract.refusalScope` defines them: that is what
    lets a case refuse one request and watch what the work behind it does.
    """

    def __init__(
        self,
        *,
        max_pending: int = 64,
        refusals: int = 0,
        refuse_always: bool = False,
        hold: bool = False,
    ) -> None:
        self.max_pending = max_pending
        self.refusals = refusals
        self.refuse_always = refuse_always
        self.hold = hold
        self.frames: list[str] = []
        self.refused = asyncio.Event()
        self.holding = asyncio.Event()
        self._first: str | None = None
        self._attempts: dict[str, int] = {}
        self._held: dict[str, Any] | None = None
        self._socket: ServerConnection | None = None

    async def _answer(
        self, row: dict[str, Any], code: str, payload: object = ()
    ) -> None:
        assert self._socket is not None
        await self._socket.send(
            json.dumps(
                {
                    "id": row["id"],
                    "type": row["type"],
                    "protocol_version": "plowshare-v1",
                    "payload": {"code": code, "payload": payload},
                }
            )
        )

    async def release(self) -> None:
        """Answer the request being held, freeing the window."""
        assert self._held is not None, "nothing is being held"
        held, self._held = self._held, None
        self.hold = False
        await self._answer(held, "OK", [])

    async def handle(self, socket: ServerConnection) -> None:
        self._socket = socket
        async for wire in socket:
            row = _object(json.loads(wire))
            operation = row.get("type")
            identity = row.get("id")
            if not isinstance(operation, str) or not isinstance(identity, str):
                raise AssertionError("Missing operation or correlation id")
            if operation == "transport.dispatch":
                await self._answer(
                    row,
                    "OK",
                    {
                        "mode": "concurrent-v1",
                        "maxPending": self.max_pending,
                        "maxIdBytes": 128,
                    },
                )
                continue
            self.frames.append(identity)
            attempt = self._attempts.get(identity, 0) + 1
            self._attempts[identity] = attempt
            if self._first is None:
                self._first = identity
            if identity == self._first and (
                self.refuse_always or attempt <= self.refusals
            ):
                await self._answer(row, "CAPACITY_EXHAUSTED")
                self.refused.set()
                continue
            if self.hold and self._held is None:
                self._held = row
                self.holding.set()
                continue
            await self._answer(row, "OK", [])

    @asynccontextmanager
    async def open(self, *, timeout: float = 2) -> AsyncIterator[Client]:
        async with serve(self.handle, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            client = await Client.connect(
                f"http://127.0.0.1:{port}",
                "fixture-token",
                timeout=timeout,
                legacy_transport=True,
            )
            # Deterministic resubmission pacing: the shortest pause the contract
            # allows for each refusal count, which is the fixed half of it.
            client._random = lambda: 0.0
            try:
                yield client
            finally:
                await client.close()


class CapacityTests(unittest.IsolatedAsyncioTestCase):
    # A full window is flow control, so the default form waits for a slot and is
    # granted one when a settling request frees it.
    async def test_waiting_form_parks_for_a_slot_and_is_granted_one(self) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            self.assertEqual(client._maximum, 1)
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            parked = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.sleep(0.05)
            self.assertFalse(parked.done(), "the parked caller refused locally")
            self.assertEqual((client._admitted, len(client._slot_waiters)), (1, 1))
            await peer.release()
            self.assertEqual((await held).require_payload(), ())
            self.assertEqual((await parked).require_payload(), ())
            self.assertEqual(len(peer.frames), 2)

    # The non-blocking form is still reachable, explicitly, and reports through the
    # outcome channel a caller already handles. It performs no backoff and no
    # resubmission, and it never consumes an admission slot.
    async def test_non_blocking_form_refuses_a_full_window_in_the_same_call(
        self,
    ) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            with self.assertRaises(TransportError) as refused:
                await client.request(MemoryIndexRequest(), wait_for_capacity=False)
            self.assertEqual(refused.exception.delivery, "NOT_SUBMITTED")
            self.assertEqual((client._admitted, len(client._slot_waiters)), (1, 0))
            await peer.release()
            self.assertEqual((await held).require_payload(), ())
            self.assertEqual(len(peer.frames), 1)

    # The other half of the non-blocking form, and the half that distinguishes it from
    # the default: a CAPACITY_EXHAUSTED reply is handed back through the outcome
    # channel the caller already handles rather than absorbed as flow control. The
    # waiting form would pace and resubmit this, so the single frame is what tells the
    # two forms apart. Inverting the absorb condition in _exchange fails this case on
    # the frame count and on the returned code.
    async def test_non_blocking_form_surfaces_a_refusal_as_the_requests_own_outcome(
        self,
    ) -> None:
        peer = CapacityPeer(refusals=1)
        async with peer.open() as client:
            reply = await client.request(MemoryIndexRequest(), wait_for_capacity=False)
            self.assertEqual(reply.code, "CAPACITY_EXHAUSTED")
            self.assertEqual(
                len(peer.frames), 1, "the non-blocking form resubmitted a refusal"
            )
            # No backoff, no resubmission, and nothing retained: the refusal settled the
            # call, so the window is clear the moment it returns.
            self.assertEqual((client._admitted, client._dispatched), (0, 0))

    # Review Focus 2, first half. Cancelling the wait for local capacity returns
    # promptly, reports NOT_SUBMITTED in Python's own cancellation currency, and
    # leaves the accounting exactly as it was: the slot the cancelled caller never
    # held is still available to the next one.
    async def test_cancelled_capacity_wait_reports_cancellation_and_leaks_no_slot(
        self,
    ) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            parked = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.sleep(0.05)
            self.assertFalse(parked.done())
            parked.cancel()
            with self.assertRaises(CancelledRequest) as cancelled:
                await parked
            self.assertEqual(cancelled.exception.delivery, "NOT_SUBMITTED")
            self.assertEqual((client._admitted, len(client._slot_waiters)), (1, 0))
            await peer.release()
            self.assertEqual((await held).require_payload(), ())
            # The proof that nothing leaked: the window of one is usable again.
            self.assertEqual((await client.request(MemoryIndexRequest())).code, "OK")
            self.assertEqual((client._admitted, client._dispatched), (0, 0))

    # Review Focus 2, second half. The grant and the cancellation can land in the
    # same loop turn: _pump charges the slot to the waiter before waking it, and
    # awaiting an already-resolved future still raises when the task is cancelled.
    # A slot charged to a caller that is already leaving must be handed back rather
    # than leaked. Driven directly rather than by timing, so it is deterministic.
    async def test_capacity_wait_cancelled_as_it_is_granted_leaks_nothing(
        self,
    ) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            parked = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.sleep(0.05)
            self.assertEqual(len(client._slot_waiters), 1)
            # Grant the slot and cancel within the same turn, so the waiter is
            # charged a slot it will never use: cancelling a task whose awaited
            # future is already resolved still raises CancelledError at its resume.
            # The order matters -- cancelling first cancels the slot future, which
            # _pump then skips, and that is the other, already-covered path.
            client._maximum = 2
            client._pump()
            self.assertEqual((client._admitted, len(client._slot_waiters)), (2, 0))
            parked.cancel()
            with self.assertRaises(CancelledRequest) as cancelled:
                await parked
            self.assertEqual(cancelled.exception.delivery, "NOT_SUBMITTED")
            self.assertEqual(client._admitted, 1, "a granted slot was leaked")
            await peer.release()
            self.assertEqual((await held).require_payload(), ())
            self.assertEqual((client._admitted, client._dispatched), (0, 0))
            self.assertEqual(len(peer.frames), 1)

    # Review Focus 2, third half, and the one the item actually names: cancellation
    # racing the socket write. A frame that may have gone out is never reported
    # NOT_SUBMITTED, and no cancellation point leaves the accounting skewed. The
    # cancel is swept across every scheduler turn of the submission path rather
    # than aimed at one, so no single timing assumption carries the test.
    async def test_cancellation_racing_the_socket_write_keeps_accounting_honest(
        self,
    ) -> None:
        peer = CapacityPeer()
        async with peer.open() as client:
            unknown = 0
            for turns in range(6):
                for _ in range(8):
                    call = asyncio.create_task(client.request(MemoryIndexRequest()))
                    for _ in range(turns):
                        await asyncio.sleep(0)
                    call.cancel()
                    try:
                        await call
                    except asyncio.CancelledError as cancelled:
                        if not isinstance(cancelled, CancelledRequest):
                            # Only legal when the task never ran: cancelling before
                            # its first step throws into the coroutine's prologue,
                            # outside any of the SDK's own handling, and nothing was
                            # admitted or written.
                            self.assertEqual(
                                turns, 0, "a started request lost its delivery claim"
                            )
                            continue
                        self.assertIn(cancelled.delivery, ("NOT_SUBMITTED", "UNKNOWN"))
                        unknown += cancelled.delivery == "UNKNOWN"
                    except TransportError as error:
                        self.fail(
                            "cancellation became a transport fault: " + str(error)
                        )
                    # Falling through means the reply won the race, which is legal.
            # Every frame the peer saw was claimed as UNKNOWN by its caller. The
            # client marks a request submitted before the write returns, so UNKNOWN
            # may exceed the frame count -- that direction is conservative. The
            # reverse would be a request reported NOT_SUBMITTED after being sent.
            self.assertLessEqual(len(peer.frames), unknown)
            self.assertEqual(
                (client._admitted, client._dispatched, client._held), (0, 0, 0)
            )
            self.assertTrue(client._idle.is_set())

    # A refusal is flow control the caller never sees: the same request id is
    # resubmitted and the caller gets the answer to the resubmission. While it
    # paces that resubmission the request has released its dispatch count --
    # capacity the server said it cannot take -- and kept its admission slot,
    # which is what stops work that arrived later from overtaking it.
    async def test_refusal_is_absorbed_keeping_admission_and_releasing_dispatch(
        self,
    ) -> None:
        peer = CapacityPeer(refusals=1)
        async with peer.open() as client:
            call = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.refused.wait(), 1)
            # Inside the backoff pause: the fixed half of the first pause is 50ms,
            # so this observation lands well before the resubmission.
            await asyncio.sleep(0.01)
            self.assertEqual((client._admitted, client._dispatched), (1, 0))
            reply = await call
            self.assertEqual(reply.require_payload(), ())
            self.assertEqual(len(peer.frames), 2, "the refusal was not resubmitted")
            self.assertEqual(
                len(set(peer.frames)), 1, "the refusal was replayed as new work"
            )

    # Review Focus 3. Refusals that outlast the deadline armed at the first
    # transmit end in that deadline. Nothing re-arms it per attempt -- `end` is
    # assigned only while it is None -- so the caller is held for its own timeout
    # and no longer, and no retry ceiling is doing the bounding.
    async def test_repeated_refusals_end_in_the_deadline_armed_at_first_transmit(
        self,
    ) -> None:
        peer = CapacityPeer(refuse_always=True)
        async with peer.open(timeout=0.5) as client:
            loop = asyncio.get_running_loop()
            started = loop.time()
            with self.assertRaises(TransportError) as expired:
                await client.request(MemoryIndexRequest())
            elapsed = loop.time() - started
            # Pinned, not a tolerant set. The deadline expires during a backoff
            # pause, where the refused attempt has left no work behind, so the
            # stronger NOT_SUBMITTED is owed. The contract tolerates the UNKNOWN
            # alternate only for the sub-millisecond transmit/refusal race, which
            # this staged pause is not; accepting either here is what let a client
            # that always reported the alternate pass.
            self.assertEqual(expired.exception.delivery, "NOT_SUBMITTED")
            self.assertIn("deadline", str(expired.exception))
            # A deadline re-armed per attempt never expires against a server that
            # refuses forever, so an upper bound is the assertion that matters. It
            # is three times the caller's own timeout: far above any scheduler
            # delay, and unreachable for a client that re-arms even once.
            self.assertLess(elapsed, 1.5, "the deadline was re-armed by a retry")
            self.assertGreaterEqual(elapsed, 0.5, "the caller lost its own timeout")
            self.assertGreaterEqual(len(peer.frames), 2, "nothing was resubmitted")
            self.assertEqual(len(set(peer.frames)), 1, "a refusal became new work")
            self.assertEqual((client._admitted, client._dispatched), (0, 0))

    # The other exit from a backoff pause, and the same claim: a cancellation while
    # the resubmission is being paced is NOT_SUBMITTED, because the refused attempt
    # released its dispatch count and the server did none of the work. Staged inside
    # the fixed 50ms half of the first pause, so no race decides the answer.
    async def test_cancellation_during_a_backoff_pause_is_not_submitted(self) -> None:
        peer = CapacityPeer(refuse_always=True)
        async with peer.open(timeout=5) as client:
            call = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.refused.wait(), 1)
            await asyncio.sleep(0.01)
            self.assertEqual((client._admitted, client._dispatched), (1, 0))
            call.cancel()
            with self.assertRaises(CancelledRequest) as cancelled:
                await call
            self.assertEqual(cancelled.exception.delivery, "NOT_SUBMITTED")
            self.assertEqual((client._admitted, client._dispatched), (0, 0))

    # Review Focus 5. A caller parked for a slot has no deadline of its own -- it
    # arms at transmit -- so a close must release it rather than leave it hanging.
    # Nothing was written for it, which makes NOT_SUBMITTED unambiguous.
    async def test_parked_callers_are_released_when_the_connection_closes(
        self,
    ) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            parked = [
                asyncio.create_task(client.request(MemoryIndexRequest()))
                for _ in range(3)
            ]
            await asyncio.sleep(0.05)
            self.assertEqual(len(client._slot_waiters), 3)
            await client.close()
            for call in parked:
                # One second, against callers whose own 2s timeout never started:
                # the release is what settles them, not any deadline.
                with self.assertRaises(TransportError) as released:
                    await asyncio.wait_for(call, 1)
                self.assertEqual(released.exception.delivery, "NOT_SUBMITTED")
            with self.assertRaises(TransportError) as lost:
                await held
            self.assertEqual(lost.exception.delivery, "UNKNOWN")
            self.assertEqual(len(peer.frames), 1)
            self.assertEqual((client._admitted, client._dispatched), (0, 0))

    # Review Focus 7, as a unit test: the originally reported bug. The deadline
    # arms at the write and carries the caller's own duration, so a request may
    # wait far longer than its timeout for a slot and still get the whole of it
    # once transmitted. Armed at submit entry, this request expires while parked.
    async def test_request_timeout_is_armed_at_transmit_and_not_before_the_wait(
        self,
    ) -> None:
        peer = CapacityPeer(max_pending=1, hold=True)
        async with peer.open() as client:
            held = asyncio.create_task(client.request(MemoryIndexRequest()))
            await asyncio.wait_for(peer.holding.wait(), 1)
            waiting = asyncio.create_task(
                client.request(MemoryIndexRequest(), timeout=0.1)
            )
            # Three times its own timeout spent waiting for a slot. A deadline
            # armed before the wait has expired by now.
            await asyncio.sleep(0.3)
            self.assertFalse(waiting.done(), "the wait was charged to the deadline")
            await peer.release()
            self.assertEqual((await waiting).require_payload(), ())
            self.assertEqual((await held).require_payload(), ())
