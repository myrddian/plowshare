"""One authenticated socket, bounded correlated requests, and no automatic replay."""

from __future__ import annotations

import asyncio
import json
import random
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum
from types import TracebackType
from typing import Generic, Literal, TypeVar, cast
from urllib.parse import urlencode, urlsplit, urlunsplit
from uuid import uuid4

from websockets.asyncio.client import ClientConnection, connect
from websockets.typing import Subprotocol

from . import contracts
from ._codec import (
    _object,
    decode_push,
    decode_reply,
    deployment_matches,
    encode_request,
)
from ._packets import PROTOCOL as PACKET_PROTOCOL
from ._packets import Packets
from ._protocol import CODES, PROTOCOL_VERSION
from .acting import ActingSelection

T = TypeVar("T")

# The server's dispatch refusal. It is flow control, not a fault: the waiting form
# absorbs it and resubmits under the same request id, and the caller never sees
# it. The dispatch negotiation is exempt; see _exchange.
CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED"

# Resubmission pacing after a capacity refusal, in seconds, matching the reference
# SDK's shipped values: the ceiling doubles with the refusal count up to the cap,
# and the pause is half of it fixed and half random, so a client always pauses and
# clients released together never retry in lockstep. The exponent is a pacing
# input, not a bound: the bound is the deadline armed at the first transmit.
BACKOFF_BASE = 0.1
BACKOFF_CAP = 5.0


class _SingleUpgrade(connect):
    def process_redirect(self, exc: Exception) -> Exception:
        return exc  # Never forward bearer credentials through an upgrade redirect.


class Delivery(str, Enum):
    NOT_SUBMITTED = "NOT_SUBMITTED"
    UNKNOWN = "UNKNOWN"
    INVALID_RESPONSE = "INVALID_RESPONSE"


class TransportError(Exception):
    def __init__(self, delivery: Delivery, message: str):
        super().__init__(message)
        self.delivery = delivery


class CancelledRequest(asyncio.CancelledError):
    def __init__(self, message: str, delivery: Delivery = Delivery.UNKNOWN):
        super().__init__(message)
        self.delivery = delivery


class Refusal(Exception):
    def __init__(self, code: str, said: str | None):
        super().__init__(said or code)
        self.code = code
        self.said = said


@dataclass(frozen=True)
class Reply(Generic[T]):
    """An explicit outcome with an operation-specific, completely validated payload."""

    code: str
    said: str | None
    _payload: T

    @property
    def successful(self) -> bool:
        return self.code in {"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}

    def require_payload(self) -> T:
        if not self.successful:
            raise Refusal(self.code, self.said)
        return self._payload


@dataclass(frozen=True)
class _Outcome:
    code: str
    said: str | None
    payload: object


@dataclass
class _Pending:
    operation: str
    answer: asyncio.Future[_Outcome]
    submitted: bool = False


@dataclass(frozen=True)
class _Dispatch:
    maximum: int

    @staticmethod
    def read(value: object) -> _Dispatch:
        row = _object(value)
        maximum = row.get("maxPending")
        if (
            row.get("mode") != "concurrent-v1"
            or type(maximum) is not int
            or not 1 <= maximum <= 64
            or type(row.get("maxIdBytes")) is not int
            or row.get("maxIdBytes") != 128
        ):
            raise ValueError("Invalid dispatch acknowledgement")
        return _Dispatch(maximum)


class Client:
    def __init__(self, socket: ClientConnection, session: str, timeout: float):
        self.session = session
        self.timeout = timeout
        self._socket = socket
        self._closed = False
        self._pending: dict[str, _Pending] = {}
        self._admitted = 0
        # Two counters, as the reference SDK keeps them: _admitted is the
        # advertised window, _dispatched is work actually on the wire. A capacity
        # refusal releases only the dispatch count, so other already-admitted work
        # can transmit while the refused request backs off, and the refused request
        # keeps its place in the window instead of being overtaken by work that
        # arrived after it.
        self._dispatched = 0
        # The FIFO queue of callers parked for an admission slot. Dispatch counts
        # have no queue: a caller holding an admission is already guaranteed one,
        # so parking for one is unreachable; see _acquire_dispatch.
        self._slot_waiters: deque[asyncio.Future[None]] = deque()
        # The resubmission jitter source, injectable so tests are deterministic.
        self._random: Callable[[], float] = random.random
        self._held = 0
        self._idle = asyncio.Event()
        self._idle.set()
        self._maximum = 64
        self._max_pending_bytes = 640 * 1024 * 1024
        self._send_lock = asyncio.Lock()
        self._pushes: asyncio.Queue[contracts.ServerPush] = asyncio.Queue(256)
        self.dropped_pushes = 0
        self._reader_closed = asyncio.Event()
        self._abort_task: asyncio.Task[None] | None = None
        self._close_task: asyncio.Task[None] | None = None
        self._packets = (
            Packets(self._abort_packets)
            if socket.subprotocol == PACKET_PROTOCOL
            else None
        )
        self._reader = asyncio.create_task(self._read())

    @classmethod
    async def connect(
        cls,
        origin: str,
        token: str,
        *,
        session: str | None = None,
        timeout: float = 30,
        legacy_transport: bool = False,
        dispatch_mode: Literal["concurrent-v1", "ordered"] = "concurrent-v1",
        max_pending_bytes: int = 640 * 1024 * 1024,
    ) -> Client:
        import math

        opening_end = asyncio.get_running_loop().time() + timeout
        if (
            dispatch_mode not in {"concurrent-v1", "ordered"}
            or type(max_pending_bytes) is not int
            or max_pending_bytes < 1
        ):
            raise ValueError("Choose a dispatch mode and positive pending byte bound")
        url = urlsplit(origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "an HTTP(S) origin without credentials, path, query or fragment is required"
            )
        session = str(uuid4()) if session is None else session
        if (
            not token.strip()
            or not session.strip()
            or not math.isfinite(timeout)
            or timeout <= 0
        ):
            raise ValueError("token, session and a positive timeout are required")
        address = urlunsplit(
            (
                "wss" if url.scheme == "https" else "ws",
                url.netloc,
                "/v1/events",
                urlencode({"session": session}),
                "",
            )
        )
        try:
            # One await, never the library's reconnecting async iterator. Credentials stay in headers.
            socket = await _SingleUpgrade(
                address,
                additional_headers={"Authorization": "Bearer " + token},
                open_timeout=timeout,
                max_size=1048576,
                max_queue=16,
                subprotocols=None
                if legacy_transport
                else [Subprotocol(PACKET_PROTOCOL)],
            )
        except Exception:
            raise TransportError(
                Delivery.NOT_SUBMITTED,
                "Plowshare WebSocket upgrade failed; no application request was submitted",
            ) from None
        if not legacy_transport and socket.subprotocol != PACKET_PROTOCOL:
            await socket.close()
            raise TransportError(
                Delivery.NOT_SUBMITTED,
                "server does not support segmented transport; explicitly select legacy mode",
            )
        client = cls(socket, session, timeout)
        client._max_pending_bytes = max_pending_bytes
        if dispatch_mode == "concurrent-v1":
            try:
                reply = await client._exchange(
                    "transport.dispatch",
                    {"mode": "concurrent-v1"},
                    timeout=opening_end - asyncio.get_running_loop().time(),
                )
                if reply.code != "OK" or not isinstance(reply.payload, _Dispatch):
                    raise TransportError(
                        Delivery.NOT_SUBMITTED,
                        "Server refused concurrent dispatch; select ordered mode explicitly",
                    )
                client._maximum = reply.payload.maximum
            except asyncio.CancelledError:
                await client.close()
                raise CancelledRequest(
                    "Connection opening cancelled before application submission",
                    Delivery.NOT_SUBMITTED,
                ) from None
            except Exception:
                await client.close()
                raise TransportError(
                    Delivery.NOT_SUBMITTED,
                    "Concurrent dispatch negotiation failed; no application request was submitted; select ordered mode explicitly",
                ) from None
        return client

    async def request(
        self,
        request: contracts.Request[T],
        *,
        acting_for: ActingSelection | None = None,
        timeout: float | None = None,
        wait_for_capacity: bool = True,
    ) -> Reply[T]:
        """Return this request's typed reply; cancellation never replays remote work.

        ``wait_for_capacity`` selects between the contract's two submission forms,
        per call rather than per client, because the choice belongs to the caller's
        role and not to the language.

        The waiting form is the default: a full dispatch window is flow control, so
        this call does not complete until the request has been transmitted, and a
        ``CAPACITY_EXHAUSTED`` reply is absorbed and resubmitted rather than
        surfaced. Waiting here is a pending task, never a held thread; cancel the
        task to get out of it, which reports ``Delivery.NOT_SUBMITTED`` because
        nothing was written.

        ``wait_for_capacity=False`` is the non-blocking form, for a caller that
        would rather shed load than be held -- a batch job under its own deadline.
        It raises ``TransportError`` with ``Delivery.NOT_SUBMITTED`` immediately
        when the window is full, performs no backoff and no resubmission, and
        returns a ``CAPACITY_EXHAUSTED`` reply to the caller as an outcome.
        """
        if acting_for is not None and not isinstance(acting_for, ActingSelection):
            raise ValueError("Invalid acting selection")
        operation, payload = encode_request(request)
        outcome = await self._exchange(
            operation,
            payload,
            acting_for=acting_for,
            timeout=timeout,
            wait_for_capacity=wait_for_capacity,
        )
        if outcome.code in {
            "OK",
            "CREATED",
            "ACCEPTED",
            "NO_CONTENT",
        } and not deployment_matches(operation, payload, outcome.payload):
            raise TransportError(
                Delivery.INVALID_RESPONSE,
                "Foreign Application deployment receipt; outcome is unknown",
            )
        return Reply(outcome.code, outcome.said, cast(T, outcome.payload))

    async def _admit(self, wait: bool) -> None:
        """Reserve one slot of the advertised window.

        Nothing here is charged against the request deadline, which is armed later,
        when the frame is written. The wait is bounded by task cancellation and by
        the connection closing; a cancelled wait reports ``NOT_SUBMITTED`` and
        leaves the accounting exactly as it was, because cancellation must never be
        a way to leak capacity.

        The fast path runs with no await at all, so it is atomic on the event loop:
        a slot cannot be taken from under a caller mid-check. The strict ``<``, and
        the empty-queue condition, are what stop a slot being taken ahead of a
        caller already parked for one.
        """
        if self._closed:
            raise TransportError(
                Delivery.NOT_SUBMITTED, "Connection closed or request capacity exceeded"
            )
        if self._admitted < self._maximum and not self._slot_waiters:
            self._admitted += 1
            self._idle.clear()
            return
        if not wait:
            # Its own sentence, distinct from the closed-connection refusal above: a caller
            # that chose to shed load must be able to tell a full window from a gone
            # connection, and both arrive through the same exception type.
            raise TransportError(
                Delivery.NOT_SUBMITTED,
                "The dispatch window is full and this request asked not to wait for"
                " capacity; nothing was written",
            )
        parked: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        self._slot_waiters.append(parked)
        try:
            await parked
        except asyncio.CancelledError:
            # The slot may have been charged to us by _pump in the same instant we
            # were cancelled: awaiting an already-resolved future still raises when
            # the task is cancelled. Hand it straight back so the next parked
            # caller receives it; otherwise just leave the queue.
            if parked.done() and not parked.cancelled() and parked.exception() is None:
                self._release_admission()
            else:
                try:
                    self._slot_waiters.remove(parked)
                except ValueError:  # Already dequeued by _pump or by stranding.
                    pass
            raise

    def _pump(self) -> None:
        """Hand freed admission slots to parked callers in FIFO order.

        It runs from every release, so a failure frees capacity exactly like a
        reply does. The slot is charged to the waiter before it is woken, so a
        caller arriving later cannot steal what was granted, and FIFO order means
        work that arrived later never overtakes a caller already parked. Nothing is
        granted once the connection is closed; callers parked then are released by
        _strand_slot_waiters.
        """
        while (
            self._slot_waiters and self._admitted < self._maximum and not self._closed
        ):
            parked = self._slot_waiters.popleft()
            if parked.done():
                continue
            self._admitted += 1
            self._idle.clear()
            parked.set_result(None)

    def _release_admission(self) -> None:
        self._admitted -= 1
        if self._admitted == 0:
            self._idle.set()
        self._pump()

    def _strand_slot_waiters(self) -> None:
        """Release every caller parked for a slot once the connection is gone.

        Their deadline never started -- it arms at transmit -- so without this a
        parked caller would hang rather than be released. Nothing was written for
        them, which makes ``NOT_SUBMITTED`` the one delivery claim that is
        unambiguously safe here.
        """
        while self._slot_waiters:
            parked = self._slot_waiters.popleft()
            if not parked.done():
                parked.set_exception(
                    TransportError(
                        Delivery.NOT_SUBMITTED,
                        "Connection closed while waiting for an admission slot",
                    )
                )

    def _acquire_dispatch(self) -> None:
        """Take one of the connection's dispatch counts for one transmit attempt.

        Holding an admission is always enough to be sure of a count, so this never
        waits. A request holds at most one count and only while it is admitted, so
        a caller that has not transmitted yet sees at most ``_admitted - 1``
        dispatched, which is below the window. The window itself only ever changes
        while nothing is admitted: ``_maximum`` is written at construction and once
        after the handshake, whose own admission is released before that write.

        The remaining branch is therefore an invariant violation, not flow control.
        It is asserted rather than queued on, so the advertised window stays
        authoritative: the request is refused without transmitting instead of
        pushing the connection past the window the server advertised. A caller
        seeing it has found a bug in this client's accounting, not a busy server.
        """
        if self._closed:
            raise TransportError(
                Delivery.NOT_SUBMITTED, "Connection closed before writer admission"
            )
        if self._dispatched >= self._maximum:
            raise TransportError(
                Delivery.NOT_SUBMITTED,
                "Dispatch accounting exceeded the advertised window; no frame was written",
            )
        self._dispatched += 1

    async def _pace(self, refusals: int) -> None:
        """Wait out the pause owed for the refusals so far.

        The exponent is a pacing input, not a bound. The bound is the deadline
        armed at the first transmit, which is already in force around this call:
        backoff time and the server's own processing time both count against the
        caller's original timeout, so a request refused over and over ends in a
        timeout rather than retrying forever. There is no retry ceiling and none is
        wanted.

        A pause ended by a close is ``NOT_SUBMITTED``: the refused attempt released
        its dispatch count and the server did none of the work.
        """
        ceiling = min(BACKOFF_CAP, BACKOFF_BASE * 2 ** min(refusals, 16))
        pause = ceiling / 2 + ceiling * self._random() / 2
        stopped = asyncio.ensure_future(self._reader_closed.wait())
        try:
            await asyncio.wait({stopped}, timeout=pause)
        finally:
            stopped.cancel()
            # Awaiting in a finally while a cancellation of this coroutine may be in
            # flight is safe here: the awaited task was just cancelled, so gather
            # returns at the next loop step, and return_exceptions keeps its
            # CancelledError from displacing whatever is already propagating. A
            # second cancellation delivered during that one loop step would surface
            # from the gather itself rather than from the first one's unwinding;
            # it is still a CancelledError from the same submission path, so
            # _exchange's handler reports the same delivery either way.
            await asyncio.gather(stopped, return_exceptions=True)
        if self._reader_closed.is_set():
            raise TransportError(
                Delivery.NOT_SUBMITTED,
                "Connection closed while pacing a resubmission",
            )

    async def _exchange(
        self,
        operation: str,
        payload: object,
        *,
        acting_for: ActingSelection | None = None,
        timeout: float | None = None,
        wait_for_capacity: bool = True,
    ) -> _Outcome:
        """Transmit one request, absorbing dispatch refusals, and return its reply.

        The request deadline is armed when the frame is written, exactly once.
        Waiting for an admission slot happens before that and is charged to
        nothing; so does acquiring ``_send_lock``, which is why the deadline is
        armed *after* the lock is held rather than around it. That wait therefore
        has no bound of the waiter's own: it is bounded only transitively, by every
        holder of the lock arming its deadline before its write and so releasing
        the lock within its own timeout. A holder that writes before arming would
        leave waiters behind it unbounded. Taking a dispatch count never waits at
        all. A resubmission re-enters the transmit below and
        finds the deadline already armed, so it runs against what is left of it.

        A ``CAPACITY_EXHAUSTED`` reply is absorbed when the caller chose the waiting
        form: the dispatch count is released so other admitted work can transmit,
        the admission slot is kept so later arrivals cannot overtake this request,
        and the same request id is resubmitted after a jittered pause against the
        deadline already armed. The server refused the work outright, having done
        none of it, which is the only reason resubmitting is not the replay this
        transport otherwise forbids. The dispatch negotiation is never resubmitted:
        the server treats a second selection on one connection as a protocol
        violation, so a retry would turn a refusal into a teardown.
        """
        import math

        allowance = self.timeout if timeout is None else timeout
        if not math.isfinite(allowance) or allowance <= 0:
            raise ValueError("Choose a positive finite request timeout")
        loop = asyncio.get_running_loop()
        try:
            await self._admit(wait_for_capacity)
        except asyncio.CancelledError:
            raise CancelledRequest(
                "Request cancelled while waiting for dispatch capacity",
                Delivery.NOT_SUBMITTED,
            ) from None
        identity = str(uuid4())
        waiting = _Pending(operation, loop.create_future())
        size = 0
        packet_reserved = False
        dispatched = False
        # None until the first frame is written. It is the single record of whether
        # the deadline has been armed, so "exactly once" is a property of one
        # variable rather than of the control flow.
        end: float | None = None
        try:
            wire = json.dumps(
                {
                    "id": identity,
                    "type": operation,
                    "protocol_version": PROTOCOL_VERSION,
                    "payload": payload,
                    **(
                        {"actingFor": {"context": acting_for.context}}
                        if acting_for
                        else {}
                    ),
                },
                allow_nan=False,
            )
            encoded = (
                self._packets.prepare(wire)
                if self._packets is not None
                else wire.encode("utf-8")
            )
            if (
                self._packets is None
                and len(encoded) > 1048576
                or len(encoded) > self._max_pending_bytes - self._held
            ):
                raise TransportError(
                    Delivery.NOT_SUBMITTED,
                    "Encoded request exceeds pending byte capacity",
                )
            if self._packets is not None:
                self._packets.reserve_outgoing(len(encoded))
                packet_reserved = True
            size = len(encoded)
            self._held += size
            absorbs = wait_for_capacity and operation != "transport.dispatch"
            # One scope for the whole life of the request, entered without a
            # deadline and rescheduled to the armed one at the first transmit. A
            # scope opened per attempt would let a resubmission re-arm; this one
            # cannot, because reschedule is reached only while end is None.
            async with asyncio.timeout_at(None) as deadline:
                refusals = 0
                while True:
                    self._acquire_dispatch()
                    dispatched = True
                    # A fresh answer per attempt, under the same request id: the
                    # previous attempt's future was settled by the refusal. The
                    # submission flag is already False here: it starts that way and
                    # is cleared where a refusal is absorbed, below.
                    waiting.answer = loop.create_future()
                    self._pending[identity] = waiting
                    async with self._send_lock:
                        if self._closed:
                            raise TransportError(
                                Delivery.NOT_SUBMITTED,
                                "Connection closed before writer admission",
                            )
                        if end is None:
                            # The frame is written below, so this is where the
                            # deadline arms, and the only place it ever does. It is
                            # inside the send lock on purpose: the wait for the
                            # writer is client-side queueing and must not be charged
                            # against the time the server is given to answer.
                            end = loop.time() + allowance
                            deadline.reschedule(end)

                        def submitted() -> None:
                            waiting.submitted = True

                        if self._packets is not None:
                            await self._packets.send(
                                encoded, self._write_packet, submitted
                            )
                        else:
                            waiting.submitted = True
                            await self._socket.send(wire)
                    outcome = await asyncio.shield(waiting.answer)
                    if not (absorbs and outcome.code == CAPACITY_EXHAUSTED):
                        return outcome
                    # Dispatch released, admission kept: capacity the server said it
                    # cannot take is given back, the place in the window is not.
                    dispatched = False
                    self._dispatched -= 1
                    self._pending.pop(identity, None)
                    # Cleared before the pause, not at the top of the next attempt:
                    # the refused attempt left no work behind, so for the whole
                    # backoff -- which is where a deadline or a cancellation almost
                    # always lands -- this request is honestly NOT_SUBMITTED. Clearing
                    # it after the pause would make every such exit report the
                    # tolerated UNKNOWN alternate instead of the stronger claim.
                    waiting.submitted = False
                    await self._pace(refusals)
                    refusals += 1
        except TransportError:
            raise
        except asyncio.CancelledError:
            if waiting.submitted:
                raise CancelledRequest(
                    "Request cancelled after submission; outcome is unknown; no replay"
                ) from None
            raise CancelledRequest(
                "Request cancelled before submission", Delivery.NOT_SUBMITTED
            ) from None
        except TimeoutError:
            # Named as a deadline only when one really expired, so a transport
            # TimeoutError from elsewhere cannot claim to be an expiry.
            # The scope's own signal, not a clock comparison: asyncio may fire a
            # timer up to the loop's clock resolution early, so loop.time() >= end
            # can still be false on a genuine expiry. The end is not None guard
            # keeps an inner TimeoutError from claiming an expiry that never armed.
            expired = end is not None and deadline.expired()
            raise TransportError(
                Delivery.UNKNOWN if waiting.submitted else Delivery.NOT_SUBMITTED,
                "Request deadline expired; no request was replayed"
                if expired
                else "Request did not obtain a readable reply; no request was replayed",
            ) from None
        except Exception:
            raise TransportError(
                Delivery.UNKNOWN if waiting.submitted else Delivery.NOT_SUBMITTED,
                "Request did not obtain a readable reply; no request was replayed",
            ) from None
        finally:
            self._pending.pop(identity, None)
            if dispatched:
                self._dispatched -= 1
            self._held -= size
            if packet_reserved and self._packets is not None:
                self._packets.release_outgoing(size)
            if not waiting.answer.done():
                waiting.answer.cancel()
            elif not waiting.answer.cancelled():
                waiting.answer.exception()
            # Last, and after the byte charge is returned, so a caller woken by the
            # pump inside this call sees the capacity it is about to use.
            self._release_admission()

    async def _write_packet(self, packet: str) -> None:
        await self._socket.send(packet)

    def _abort_packets(self) -> None:
        if self._abort_task is None:
            self._abort_task = asyncio.create_task(
                self._socket.close(code=1002, reason="packet transfer failed")
            )

    async def _read(self) -> None:
        try:
            async for wire in self._socket:
                if not isinstance(wire, str):
                    continue
                if self._packets is not None:
                    message, credit = self._packets.accept(wire)
                    if credit is not None:
                        await self._socket.send(credit)
                    if message is None:
                        continue
                    wire = message
                try:
                    frame = json.loads(wire)
                except (ValueError, TypeError):
                    continue
                if not isinstance(frame, dict):
                    continue
                frame = _object(frame)
                if (
                    "protocol_version" not in frame
                    or frame.get("id") is None
                    and frame.get("protocol_version") == PROTOCOL_VERSION
                ):
                    try:
                        self._pushes.put_nowait(decode_push(frame))
                    except ValueError:
                        continue
                    except asyncio.QueueFull:
                        self.dropped_pushes += 1
                    continue
                identity = frame.get("id")
                if not isinstance(identity, str) or identity not in self._pending:
                    continue
                waiting = self._pending.pop(identity)
                operation, answer = waiting.operation, waiting.answer
                outcome = frame.get("payload")
                valid = (
                    frame.get("protocol_version") == PROTOCOL_VERSION
                    and frame.get("type") == operation
                    and isinstance(outcome, dict)
                )
                if isinstance(outcome, dict):
                    outcome = _object(outcome)
                valid = (
                    valid
                    and isinstance(outcome, dict)
                    and isinstance(outcome.get("code"), str)
                    and outcome.get("code") in CODES
                    and (
                        outcome.get("said") is None
                        or isinstance(outcome.get("said"), str)
                    )
                )
                if not answer.done():
                    try:
                        if not valid:
                            raise ValueError("Invalid reply envelope")
                        row = _object(outcome)
                        code = cast(str, row["code"])
                        said = cast(str | None, row.get("said"))
                        payload = (
                            (
                                _Dispatch.read(row.get("payload"))
                                if operation == "transport.dispatch"
                                else decode_reply(operation, row.get("payload"))
                            )
                            if code in {"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}
                            else None
                        )
                        answer.set_result(_Outcome(code, said, payload))
                    except ValueError:
                        answer.set_exception(
                            TransportError(
                                Delivery.INVALID_RESPONSE,
                                "unreadable response; outcome is unknown",
                            )
                        )
        except Exception:
            pass
        finally:
            self._closed = True
            if self._packets is not None:
                self._packets.close()
                self._abort_packets()
            self._reader_closed.set()
            self._strand_slot_waiters()
            for waiting in self._pending.values():
                answer = waiting.answer
                if not answer.done():
                    answer.set_exception(
                        TransportError(
                            Delivery.UNKNOWN
                            if waiting.submitted
                            else Delivery.NOT_SUBMITTED,
                            "socket closed before reply; request was not replayed",
                        )
                    )
            self._pending.clear()

    async def next_push(self) -> contracts.ServerPush:
        """Read a validated hint; closure wakes waiters after buffered hints are drained."""
        if not self._pushes.empty():
            return self._pushes.get_nowait()
        if self._reader_closed.is_set():
            raise TransportError(
                Delivery.UNKNOWN, "notification stream closed; reconcile durable state"
            )
        hint = asyncio.create_task(self._pushes.get())
        stopped = asyncio.create_task(self._reader_closed.wait())
        try:
            await asyncio.wait((hint, stopped), return_when=asyncio.FIRST_COMPLETED)
            if hint.done():
                return hint.result()
            raise TransportError(
                Delivery.UNKNOWN, "notification stream closed; reconcile durable state"
            )
        finally:
            for task in (hint, stopped):
                task.cancel()
            await asyncio.gather(hint, stopped, return_exceptions=True)

    async def close(self) -> None:
        if self._close_task is None:
            self._closed = True
            # Promptly, rather than waiting for the reader to notice: a parked
            # caller has no deadline of its own to fall back on.
            self._strand_slot_waiters()
            self._close_task = asyncio.create_task(self._finish_close())
        # A caller detaching from close cannot cancel the connection's shared drain.
        await asyncio.shield(self._close_task)

    async def _finish_close(self) -> None:
        await self._socket.close()
        await self._reader
        if self._abort_task is not None:
            await self._abort_task
        # Reader completion fans out failures; each exchange still owns its encoded
        # bytes and writer admission until its own finally block has run. This shared
        # task is separate from the reader, so close never asks the reader to join itself.
        await self._idle.wait()

    async def __aenter__(self) -> Client:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()
