"""Truthful, bounded persistence for adapter-owned correlation receipts.

HA's ordinary Store is intentionally best effort: it can defer or suppress a
write without raising. Submission fencing requires a stronger acknowledgment.
This specialist journal retains the same on-disk envelope but owns atomic writes
and their physical completion. It never stores model text or credentials.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import stat
import tempfile
from collections import deque
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .const import CLEANUP_TIMEOUT
from .models import Session, decode_sessions, encode_sessions, required_text, seconds

MAX_JOURNAL_BYTES = 1024 * 1024
MAX_WRITES = 8
_LOGGER = logging.getLogger(__name__)


class JournalUnavailable(OSError):
    """No durable acknowledgment is available; this never permits SDK replay."""


class CorrelationPersistence(Protocol):
    """Owns one typed receipt set and physical I/O until it actually settles."""

    async def async_load(self) -> dict[str, Session]: ...

    async def async_save(self, sessions: Mapping[str, Session]) -> None: ...

    async def async_close(self) -> bool: ...


def _object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """Reject duplicate keys at the JSON boundary, before lifecycle conversion."""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate correlation field")
        result[key] = value
    return result


def _invalid_constant(_value: str) -> object:
    raise ValueError("Invalid correlation number")


def _read(path: Path, key: str) -> dict[str, Session]:
    try:
        if path.is_symlink():
            raise JournalUnavailable("Correlation storage must not be a symlink")
        # A FIFO must not block before the file-type check, and a leaf symlink
        # must not be followed if it races the preliminary inspection.
        flags = os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as source:
            if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
                raise JournalUnavailable("Correlation storage must be a regular file")
            raw = source.read(MAX_JOURNAL_BYTES + 1)
    except FileNotFoundError:
        return {}
    if len(raw) > MAX_JOURNAL_BYTES:
        raise JournalUnavailable("Correlation storage exceeds its resource limit")
    document = json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=_object,
        parse_constant=_invalid_constant,
    )
    if (
        not isinstance(document, dict)
        or set(document)
        not in ({"version", "key", "data"}, {"version", "minor_version", "key", "data"})
        or type(document["version"]) is not int
        or document["version"] != 1
        or type(document.get("minor_version", 1)) is not int
        or document.get("minor_version", 1) != 1
        or document["key"] != key
        or document["data"] is None
    ):
        raise JournalUnavailable("Unsupported correlation storage envelope")
    return decode_sessions(document["data"])


def _write(path: Path, content: bytes) -> None:
    """Commit one private file; fsync/replace failures are never swallowed."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink():
        raise JournalUnavailable("Correlation storage must not be a symlink")
    try:
        if not stat.S_ISREG(path.lstat().st_mode):
            raise JournalUnavailable("Correlation storage must be a regular file")
    except FileNotFoundError:
        pass  # The first committed intent creates the owned file.
    descriptor, temporary = tempfile.mkstemp(prefix=".plowshare-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        # Atomic replacement alone does not acknowledge directory durability.
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


@dataclass(frozen=True)
class _Write:
    content: bytes
    result: asyncio.Future[None]


class CorrelationJournal:
    """One FIFO physical writer shared by both configured profile gateways.

    Callers wait within the configured local-I/O budget. Cancellation/expiry of
    that wait leaves the immutable snapshot queued and its physical owner held.
    Only the executor completion callback advances the FIFO; an older snapshot
    therefore cannot overwrite a newer committed receipt. No caller cancels the
    physical future. An unexpectedly cancelled physical future is quarantined,
    because that is not proof its executor stopped.
    """

    def __init__(
        self,
        path: Path,
        key: str,
        timeout: float,
        *,
        writer: Callable[[Path, bytes], None] = _write,
    ) -> None:
        if not isinstance(path, Path) or not path.is_absolute():
            raise ValueError("An explicit HA storage path is required")
        self.path = path
        self.key = required_text(key)
        self.timeout = seconds(timeout, 30)
        self._writer = writer
        self._loaded: dict[str, Session] | None = None
        self._read: asyncio.Future[dict[str, Session]] | None = None
        self._physical: asyncio.Future[None] | None = None
        self._queue: deque[_Write] = deque()
        self._pending = 0
        self._closing = False
        self._quarantined = False
        self._settled = asyncio.Event()
        self._settled.set()

    def _update_settled(self) -> None:
        if (
            not self._quarantined
            and (self._read is None or self._read.done())
            and self._physical is None
            and self._pending == 0
        ):
            self._settled.set()
        else:
            self._settled.clear()

    def _read_finished(self, future: asyncio.Future[dict[str, Session]]) -> None:
        if future.cancelled():
            self._quarantined = True
        elif future.exception() is None:
            self._loaded = future.result()
        self._update_settled()

    async def async_load(self) -> dict[str, Session]:
        if self._closing or self._quarantined:
            raise JournalUnavailable("Correlation storage is closing")
        if self._loaded is not None:
            return self._loaded
        if self._read is None:
            self._read = asyncio.get_running_loop().run_in_executor(
                None, _read, self.path, self.key
            )
            self._read.add_done_callback(self._read_finished)
            self._update_settled()
        try:
            async with asyncio.timeout(self.timeout):
                loaded = await asyncio.shield(self._read)
            # shield(done_future) can return before the scheduled completion
            # callback runs. Establish the successful-load invariant in this
            # caller too; detached callers still use the callback above.
            self._loaded = loaded
            self._update_settled()
            return loaded
        except (OSError, ValueError, RecursionError, TimeoutError) as error:
            raise JournalUnavailable("Correlation storage needs inspection") from error

    @staticmethod
    def _observe_result(future: asyncio.Future[None]) -> None:
        # A caller may have timed out. Consume its eventual exception without
        # changing what an awaiting caller receives or logging private content.
        if not future.cancelled():
            future.exception()

    async def async_save(self, sessions: Mapping[str, Session]) -> None:
        if self._closing or self._quarantined or self._loaded is None:
            raise JournalUnavailable("Correlation storage is not writable")
        if self._pending >= MAX_WRITES:
            raise JournalUnavailable("Correlation storage has pending writes")
        try:
            data = encode_sessions(dict(sessions))
            decode_sessions(
                data
            )  # Repository invariants also defend alternate callers.
            content = json.dumps(
                {"version": 1, "minor_version": 1, "key": self.key, "data": data},
                ensure_ascii=False,
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        except (TypeError, ValueError, UnicodeError) as error:
            raise JournalUnavailable("Invalid correlation snapshot") from error
        if len(content) > MAX_JOURNAL_BYTES:
            raise JournalUnavailable("Correlation storage exceeds its resource limit")
        result: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        result.add_done_callback(self._observe_result)
        self._queue.append(_Write(content, result))
        self._pending += 1
        self._settled.clear()
        self._start_next()
        try:
            async with asyncio.timeout(self.timeout):
                await asyncio.shield(result)
        except TimeoutError as error:
            raise JournalUnavailable("Correlation write remains pending") from error

    def _start_next(self) -> None:
        # Event-loop confined admission; no await between testing and claiming
        # the physical writer. Already admitted snapshots still drain on close.
        while self._physical is None and self._queue and not self._quarantined:
            request = self._queue.popleft()
            try:
                future = asyncio.get_running_loop().run_in_executor(
                    None, self._writer, self.path, request.content
                )
            except RuntimeError:
                self._pending -= 1
                request.result.set_exception(
                    JournalUnavailable("Correlation writer is unavailable")
                )
                continue
            self._physical = future
            future.add_done_callback(
                lambda completed, selected=request: self._write_finished(
                    selected, completed
                )
            )
        self._update_settled()

    def _write_finished(self, request: _Write, future: asyncio.Future[None]) -> None:
        if future.cancelled():
            self._quarantined = True
            request.result.set_exception(
                JournalUnavailable("Correlation writer completion is uncertain")
            )
            self._update_settled()
            return
        self._physical = None
        self._pending -= 1
        if (failure := future.exception()) is None:
            request.result.set_result(None)
        else:
            _LOGGER.error(
                "HA correlation storage write failed (%s)", type(failure).__name__
            )
            request.result.set_exception(
                JournalUnavailable("Correlation snapshot could not be committed")
            )
        self._start_next()

    async def async_close(self) -> bool:
        """Stop intake and observe existing I/O; never fabricate its completion."""
        self._closing = True
        self._update_settled()
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await self._settled.wait()
        except TimeoutError:
            return False
        return True
