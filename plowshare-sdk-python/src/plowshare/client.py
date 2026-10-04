"""One authenticated socket, bounded correlated requests, and no automatic replay."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from enum import Enum
import json
from typing import Any
from urllib.parse import urlsplit, urlunsplit, urlencode
from uuid import uuid4
from websockets.asyncio.client import connect, ClientConnection
from ._protocol import PROTOCOL_VERSION, OPERATIONS, CODES

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
    def __init__(self, reply: Reply):
        super().__init__(reply.outcome.get("said") or reply.code)
        self.reply = reply

@dataclass(frozen=True)
class Reply:
    raw: dict[str, Any]
    @property
    def outcome(self) -> dict[str, Any]:
        return self.raw["payload"]
    @property
    def code(self) -> str:
        return self.outcome["code"]
    @property
    def successful(self) -> bool:
        return self.code in {"OK", "CREATED", "ACCEPTED", "NO_CONTENT"}
    def require_payload(self) -> Any:
        if not self.successful:
            raise Refusal(self)
        if self.outcome.get("payload") is None:
            raise TransportError(Delivery.INVALID_RESPONSE, "successful response omitted a required payload; outcome is unresolved")
        return self.outcome["payload"]

class Client:
    def __init__(self, socket: ClientConnection, session: str, timeout: float):
        self.session = session
        self.timeout = timeout
        self._socket = socket
        self._closed = False
        self._pending: dict[str, tuple[str, asyncio.Future[Reply]]] = {}
        self._send_lock = asyncio.Lock()
        self._pushes: asyncio.Queue[dict[str, Any]] = asyncio.Queue(256)
        self.dropped_pushes = 0
        self._reader = asyncio.create_task(self._read())

    @classmethod
    async def connect(cls, origin: str, token: str, *, session: str | None = None, timeout: float = 30) -> Client:
        import math
        url = urlsplit(origin)
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment:
            raise ValueError("an HTTP(S) origin without credentials, path, query or fragment is required")
        session = str(uuid4()) if session is None else session
        if not token.strip() or not session.strip() or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("token, session and a positive timeout are required")
        address = urlunsplit(("wss" if url.scheme == "https" else "ws", url.netloc, "/v1/events", urlencode({"session": session}), ""))
        try:
            # One await, never the library's reconnecting async iterator. Credentials stay in headers.
            socket = await _SingleUpgrade(address, additional_headers={"Authorization": "Bearer " + token}, open_timeout=timeout, max_size=1048576, max_queue=16)
        except Exception:
            raise TransportError(Delivery.NOT_SUBMITTED, "Plowshare WebSocket upgrade failed; no application request was submitted") from None
        return cls(socket, session, timeout)

    async def request(self, operation: str, payload: dict[str, Any] | None = None) -> Reply:
        if operation not in OPERATIONS:
            raise ValueError("unknown Plowshare operation")
        if payload is not None and not isinstance(payload, dict):
            raise ValueError("request payload must be an object")
        identity = str(uuid4())
        wire = json.dumps({"id": identity, "type": operation, "protocol_version": PROTOCOL_VERSION, "payload": {} if payload is None else payload}, allow_nan=False)
        future: asyncio.Future[Reply] = asyncio.get_running_loop().create_future()
        submitted = False
        try:
            async with asyncio.timeout(self.timeout):
                async with self._send_lock:
                    if self._closed or len(self._pending) >= 64:
                        raise TransportError(Delivery.NOT_SUBMITTED, "connection closed or request capacity exceeded; request was not submitted")
                    self._pending[identity] = (operation, future)
                    submitted = True
                    await self._socket.send(wire)
                return await asyncio.shield(future)
        except TransportError:
            raise
        except asyncio.CancelledError:
            if submitted:
                raise CancelledRequest("request cancelled after submission; outcome is unknown; request was not replayed") from None
            raise
        except Exception:
            raise TransportError(Delivery.UNKNOWN if submitted else Delivery.NOT_SUBMITTED, "request did not obtain a readable reply; request was not replayed") from None
        finally:
            self._pending.pop(identity, None)
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                future.exception()  # Retrieve a late failure when send/timeout won the race.

    async def _read(self) -> None:
        try:
            async for wire in self._socket:
                if not isinstance(wire, str):
                    continue
                try:
                    frame = json.loads(wire)
                except (ValueError, TypeError):
                    continue
                if not isinstance(frame, dict):
                    continue
                if "protocol_version" not in frame or frame.get("id") is None and frame.get("protocol_version") == PROTOCOL_VERSION:
                    try:
                        self._pushes.put_nowait(frame)
                    except asyncio.QueueFull:
                        self.dropped_pushes += 1
                    continue
                identity = frame.get("id")
                if not isinstance(identity, str) or identity not in self._pending:
                    continue
                operation, answer = self._pending.pop(identity)
                outcome = frame.get("payload")
                valid = frame.get("protocol_version") == PROTOCOL_VERSION and frame.get("type") == operation and isinstance(outcome, dict)
                valid = valid and isinstance(outcome.get("code"), str) and outcome.get("code") in CODES and (outcome.get("said") is None or isinstance(outcome.get("said"), str))
                if not answer.done():
                    if valid:
                        answer.set_result(Reply(frame))
                    else:
                        answer.set_exception(TransportError(Delivery.INVALID_RESPONSE, "unreadable response; outcome is unknown"))
        except Exception:
            pass
        finally:
            self._closed = True
            for _, answer in self._pending.values():
                if not answer.done():
                    answer.set_exception(TransportError(Delivery.UNKNOWN, "socket closed before reply; request was not replayed"))
            self._pending.clear()

    async def next_push(self) -> dict[str, Any]:
        return await self._pushes.get()

    async def job_status(self, job: str) -> Reply:
        return await self.request("job.status", {"job": job})
    async def cancel_job(self, job: str) -> Reply:
        return await self.request("job.cancel", {"job": job})
    async def open_conversation(self, **payload: Any) -> Reply:
        return await self.request("conversation.open", payload)
    async def run_agent(self, agent: str, task: str, **payload: Any) -> Reply:
        return await self.request("agent.run", {"session": self.session, **payload, "agent": agent, "task": task})
    async def send_outgoing(self, request_id: str, peer: str, message: dict[str, Any], **scope: Any) -> Reply:
        from uuid import UUID
        UUID(request_id)  # Stable receipt is caller-owned and retained before submission.
        return await self.request("outgoing.send", {**scope, "requestId": request_id, "peer": peer, "message": message})
    async def outgoing_status(self, identity: str) -> Reply:
        return await self.request("outgoing.status", {"id": identity})
    async def cancel_outgoing(self, identity: str) -> Reply:
        return await self.request("outgoing.cancel", {"id": identity})

    async def close(self) -> None:
        self._closed = True
        await self._socket.close()
        await self._reader
    async def __aenter__(self) -> Client:
        return self
    async def __aexit__(self, *args: Any) -> None:
        await self.close()
