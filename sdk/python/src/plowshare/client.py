"""One authenticated socket, bounded correlated requests, and no automatic replay."""

from __future__ import annotations

import asyncio
import json
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
    ) -> Reply[T]:
        """Return this request's typed reply; cancellation never replays remote work."""
        if acting_for is not None and not isinstance(acting_for, ActingSelection):
            raise ValueError("Invalid acting selection")
        operation, payload = encode_request(request)
        outcome = await self._exchange(
            operation, payload, acting_for=acting_for, timeout=timeout
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

    async def _exchange(
        self,
        operation: str,
        payload: object,
        *,
        acting_for: ActingSelection | None = None,
        timeout: float | None = None,
    ) -> _Outcome:
        import math

        allowance = self.timeout if timeout is None else timeout
        if not math.isfinite(allowance) or allowance <= 0:
            raise ValueError("Choose a positive finite request timeout")
        end = asyncio.get_running_loop().time() + allowance
        # Event-loop-confined admission is atomic before the first await, including writer waiters.
        if self._closed or self._admitted >= self._maximum:
            raise TransportError(
                Delivery.NOT_SUBMITTED, "Connection closed or request capacity exceeded"
            )
        self._admitted += 1
        self._idle.clear()
        identity = str(uuid4())
        future: asyncio.Future[_Outcome] = asyncio.get_running_loop().create_future()
        waiting = _Pending(operation, future)
        size = 0
        packet_reserved = False
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
            self._pending[identity] = waiting
            async with asyncio.timeout_at(end):
                async with self._send_lock:
                    if self._closed:
                        raise TransportError(
                            Delivery.NOT_SUBMITTED,
                            "Connection closed before writer admission",
                        )

                    def submitted() -> None:
                        waiting.submitted = True

                    if self._packets is not None:
                        await self._packets.send(encoded, self._write_packet, submitted)
                    else:
                        waiting.submitted = True
                        await self._socket.send(wire)
                return await asyncio.shield(future)
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
        except Exception:
            raise TransportError(
                Delivery.UNKNOWN if waiting.submitted else Delivery.NOT_SUBMITTED,
                "Request did not obtain a readable reply; no request was replayed",
            ) from None
        finally:
            self._pending.pop(identity, None)
            self._admitted -= 1
            self._held -= size
            if packet_reserved and self._packets is not None:
                self._packets.release_outgoing(size)
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                future.exception()
            if self._admitted == 0:
                self._idle.set()

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
