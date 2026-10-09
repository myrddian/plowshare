"""Connection-owned packet assembly and stop-and-wait byte credits, never mutation retries."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import threading
from dataclasses import dataclass
from typing import Awaitable, Callable
from uuid import UUID, uuid4

PROTOCOL = "plowshare-segments-v1"
CHUNK = 65536
MAX_MESSAGE = 320 * 1024 * 1024
MAX_PACKET = 128 * 1024
_PROCESS_LIMIT = 2 * 1024 * 1024 * 1024
_process_held = 0
_budget_lock = threading.Lock()


def _object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate packet field")
        result[key] = value
    return result


def _integer(value: object) -> int:
    if type(value) is not int:
        raise ValueError("invalid packet integer")
    return value


def _identity(value: object) -> str:
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("invalid transfer identity")
    return value


@dataclass(frozen=True)
class _Segment:
    transfer: str
    number: int
    count: int
    offset: int
    total: int
    sha256: str
    data: bytes

    @staticmethod
    def parse(frame: dict[str, object]) -> _Segment:
        if set(frame) != {
            "kind",
            "version",
            "transferId",
            "segmentNumber",
            "segmentCount",
            "byteOffset",
            "totalBytes",
            "sha256",
            "data",
        }:
            raise ValueError("invalid segment fields")
        total = _integer(frame["totalBytes"])
        count = _integer(frame["segmentCount"])
        number = _integer(frame["segmentNumber"])
        offset = _integer(frame["byteOffset"])
        sha256, data = frame["sha256"], frame["data"]
        if (
            total <= 0
            or total > MAX_MESSAGE
            or count != (total + CHUNK - 1) // CHUNK
            or number < 1
            or number > count
            or offset != (number - 1) * CHUNK
            or not isinstance(sha256, str)
            or len(sha256) != 64
            or any(c not in "0123456789abcdef" for c in sha256)
            or not isinstance(data, str)
            or len(data) > 87384
        ):
            raise ValueError("invalid segment metadata")
        decoded = base64.b64decode(data, validate=True)
        if (
            len(decoded) != min(CHUNK, total - offset)
            or base64.b64encode(decoded).decode("ascii") != data
        ):
            raise ValueError("invalid segment bytes")
        return _Segment(
            _identity(frame["transferId"]),
            number,
            count,
            offset,
            total,
            sha256,
            decoded,
        )


@dataclass
class _Assembly:
    first: _Segment
    data: bytearray
    ranges: set[int]
    total_timer: asyncio.TimerHandle
    idle_timer: asyncio.TimerHandle


class Packets:
    def __init__(self, expired: Callable[[], None]):
        self._expired = expired
        self._incoming: dict[str, _Assembly] = {}
        self._completed: set[str] = set()
        self._held = 0
        self._closed = False
        self._waiting: tuple[str, int, asyncio.Future[None]] | None = None

    def _reserve(self, size: int) -> None:
        global _process_held
        with _budget_lock:
            if (
                size > 640 * 1024 * 1024 - self._held
                or size * 3 > _PROCESS_LIMIT - _process_held
            ):
                raise ValueError("packet memory capacity exceeded")
            self._held += size
            _process_held += size * 3

    def _release(self, size: int) -> None:
        global _process_held
        with _budget_lock:
            self._held -= size
            _process_held -= size * 3

    def prepare(self, text: str) -> bytes:
        # JSON encoding is performed by the owning SDK before this boundary; no wire side effect.
        if not text or len(text) > MAX_MESSAGE:
            raise ValueError("encoded message exceeds transport allowance")
        data = text.encode("utf-8", errors="strict")
        if len(data) > MAX_MESSAGE:
            raise ValueError("encoded message exceeds transport allowance")
        return data

    def reserve_outgoing(self, size: int) -> None:
        """Reserve before waiting on the client's writer; queued buffers count too."""
        if self._closed:
            raise ValueError("packet connection closed")
        self._reserve(size)

    def release_outgoing(self, size: int) -> None:
        self._release(size)

    async def send(self, data: bytes, write: Callable[[str], Awaitable[None]]) -> None:
        # The client holds the outgoing reservation until its request stops retaining these bytes.
        try:
            async with asyncio.timeout(60):
                transfer = str(uuid4())
                count = (len(data) + CHUNK - 1) // CHUNK
                fingerprint = hashlib.sha256(data).hexdigest()
                for number in range(1, count + 1):
                    if self._closed:
                        raise ConnectionError("packet connection closed")
                    offset = (number - 1) * CHUNK
                    credit: asyncio.Future[None] = (
                        asyncio.get_running_loop().create_future()
                    )
                    self._waiting = (transfer, number, credit)
                    await write(
                        json.dumps(
                            {
                                "kind": "transport.segment",
                                "version": 1,
                                "transferId": transfer,
                                "segmentNumber": number,
                                "segmentCount": count,
                                "byteOffset": offset,
                                "totalBytes": len(data),
                                "sha256": fingerprint,
                                "data": base64.b64encode(
                                    data[offset : offset + CHUNK]
                                ).decode("ascii"),
                            }
                        )
                    )
                    await asyncio.wait_for(credit, 15)
                    self._waiting = None
        except BaseException:
            self._expire()
            raise
        finally:
            if self._waiting is not None:
                future = self._waiting[2]
                if not future.done():
                    future.cancel()
                elif not future.cancelled():
                    future.exception()
            self._waiting = None

    def accept(self, text: str) -> tuple[str | None, str | None]:
        if (
            self._closed
            or len(text) > MAX_PACKET
            or len(text.encode("utf-8")) > MAX_PACKET
        ):
            raise ValueError("invalid packet message")
        parsed: object = json.loads(text, object_pairs_hook=_object)
        if not isinstance(parsed, dict):
            raise ValueError("invalid packet object")
        # json.loads is an owning boundary; project its validated field names and scalar values.
        frame = _object(list(parsed.items()))
        if _integer(frame.get("version")) != 1:
            raise ValueError("unsupported packet version")
        if frame.get("kind") == "transport.credit":
            if set(frame) != {"kind", "version", "transferId", "segmentNumber"}:
                raise ValueError("invalid credit fields")
            transfer, number = (
                _identity(frame["transferId"]),
                _integer(frame["segmentNumber"]),
            )
            if self._waiting is None or self._waiting[:2] != (transfer, number):
                raise ValueError("unexpected packet credit")
            if not self._waiting[2].done():
                self._waiting[2].set_result(None)
            return None, None
        if frame.get("kind") != "transport.segment":
            raise ValueError("unsupported packet kind")
        part = _Segment.parse(frame)
        if part.transfer in self._completed:
            raise ValueError("completed transfer identity reused")
        assembly = self._incoming.get(part.transfer)
        loop = asyncio.get_running_loop()
        if assembly is None:
            if (
                len(self._incoming) >= 4
                or len(self._incoming) + len(self._completed) >= 4096
            ):
                raise ValueError("transfer capacity exceeded")
            self._reserve(part.total)
            assembly = _Assembly(
                part,
                bytearray(part.total),
                set(),
                loop.call_later(60, self._expire),
                loop.call_later(15, self._expire),
            )
            self._incoming[part.transfer] = assembly
        if (part.total, part.count, part.sha256) != (
            assembly.first.total,
            assembly.first.count,
            assembly.first.sha256,
        ):
            raise ValueError("conflicting transfer metadata")
        if part.number in assembly.ranges:
            if assembly.data[part.offset : part.offset + len(part.data)] != part.data:
                raise ValueError("conflicting duplicate range")
        else:
            assembly.data[part.offset : part.offset + len(part.data)] = part.data
            assembly.ranges.add(part.number)
        assembly.idle_timer.cancel()
        assembly.idle_timer = loop.call_later(15, self._expire)
        message = None
        if len(assembly.ranges) == part.count:
            if hashlib.sha256(assembly.data).hexdigest() != part.sha256:
                raise ValueError("transfer hash mismatch")
            message = assembly.data.decode("utf-8", errors="strict")
            assembly.idle_timer.cancel()
            assembly.total_timer.cancel()
            del self._incoming[part.transfer]
            self._release(part.total)
            self._reserve(256)
            self._completed.add(part.transfer)
        credit = json.dumps(
            {
                "kind": "transport.credit",
                "version": 1,
                "transferId": part.transfer,
                "segmentNumber": part.number,
            }
        )
        return message, credit

    def _expire(self) -> None:
        self.close()
        self._expired()

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        for assembly in self._incoming.values():
            assembly.idle_timer.cancel()
            assembly.total_timer.cancel()
            self._release(assembly.first.total)
        self._incoming.clear()
        self._release(len(self._completed) * 256)
        self._completed.clear()
        if self._waiting is not None and not self._waiting[2].done():
            self._waiting[2].set_exception(ConnectionError("packet connection closed"))
