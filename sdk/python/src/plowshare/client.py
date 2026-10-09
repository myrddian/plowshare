"""One authenticated socket, bounded correlated requests, and no automatic replay."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from enum import Enum
from types import TracebackType
from typing import Generic, TypeVar, cast
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
    delivery = Delivery.UNKNOWN


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


class Client:
    def __init__(self, socket: ClientConnection, session: str, timeout: float):
        self.session = session
        self.timeout = timeout
        self._socket = socket
        self._closed = False
        self._pending: dict[str, tuple[str, asyncio.Future[_Outcome]]] = {}
        self._send_lock = asyncio.Lock()
        self._pushes: asyncio.Queue[contracts.ServerPush] = asyncio.Queue(256)
        self.dropped_pushes = 0
        self._reader_closed = asyncio.Event()
        self._abort_task: asyncio.Task[None] | None = None
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
    ) -> Client:
        import math

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
        return cls(socket, session, timeout)

    async def request(self, request: contracts.Request[T]) -> Reply[T]:
        """Validate before submission. Cancellation after a write never replays the request."""
        operation, payload = encode_request(request)
        identity = str(uuid4())
        wire = json.dumps(
            {
                "id": identity,
                "type": operation,
                "protocol_version": PROTOCOL_VERSION,
                "payload": payload,
            },
            allow_nan=False,
        )
        try:
            encoded = (
                self._packets.prepare(wire)
                if self._packets is not None
                else wire.encode("utf-8")
            )
        except ValueError:
            raise TransportError(
                Delivery.NOT_SUBMITTED, "encoded request exceeds transport allowance"
            ) from None
        if self._packets is None and len(encoded) > 1048576:
            raise TransportError(
                Delivery.NOT_SUBMITTED, "encoded request exceeds legacy allowance"
            )
        if self._packets is not None:
            try:
                self._packets.reserve_outgoing(len(encoded))
            except ValueError:
                raise TransportError(
                    Delivery.NOT_SUBMITTED, "packet capacity exceeded before submission"
                ) from None
        future: asyncio.Future[_Outcome] = asyncio.get_running_loop().create_future()
        submitted = False
        try:
            async with asyncio.timeout(self.timeout):
                async with self._send_lock:
                    if self._closed or len(self._pending) >= 64:
                        raise TransportError(
                            Delivery.NOT_SUBMITTED,
                            "connection closed or request capacity exceeded; request was not submitted",
                        )
                    self._pending[identity] = (operation, future)
                    submitted = True
                    if self._packets is not None:
                        await self._packets.send(encoded, self._write_packet)
                    else:
                        await self._socket.send(wire)
                outcome = await asyncio.shield(future)
                # The reader validates structure; the submitting caller checks receipt ownership.
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
        except TransportError:
            raise
        except asyncio.CancelledError:
            if submitted:
                raise CancelledRequest(
                    "request cancelled after submission; outcome is unknown; request was not replayed"
                ) from None
            raise
        except Exception:
            raise TransportError(
                Delivery.UNKNOWN if submitted else Delivery.NOT_SUBMITTED,
                "request did not obtain a readable reply; request was not replayed",
            ) from None
        finally:
            self._pending.pop(identity, None)
            if self._packets is not None:
                self._packets.release_outgoing(len(encoded))
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                future.exception()  # Retrieve a late failure when send/timeout won the race.

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
                operation, answer = self._pending.pop(identity)
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
                            decode_reply(operation, row.get("payload"))
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
            for _, answer in self._pending.values():
                if not answer.done():
                    answer.set_exception(
                        TransportError(
                            Delivery.UNKNOWN,
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
        self._closed = True
        await self._socket.close()
        await self._reader
        if self._abort_task is not None:
            await self._abort_task

    async def __aenter__(self) -> Client:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()
