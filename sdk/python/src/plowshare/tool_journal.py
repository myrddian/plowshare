"""Exclusive, bounded local receipts for external tool execution.

SQLite owns atomic durable updates and the process fence. This is an external
provider's effect journal, not a Plowshare job database. Keep this file across
provider upgrades, including when Relay history has expired.
"""

from __future__ import annotations

import hashlib
import os
import sqlite3
from pathlib import Path
from types import TracebackType
from typing import Literal

from .tools import ToolReceipt, ToolResult, _decode, _time, _uuid


class SqliteToolJournal:
    def __init__(self, directory: Path, *, configuration: str):
        if not directory.is_absolute() or directory.is_symlink():
            raise ValueError(
                "Tool journal needs an explicit absolute, unlinked directory"
            )
        directory.mkdir(parents=True, mode=0o700, exist_ok=True)
        path = directory / "relay-tools.sqlite"
        if path.is_symlink():
            raise ValueError("Tool journal cannot be a symbolic link")
        if not path.exists():
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
        self._identity = hashlib.sha256(configuration.encode()).hexdigest()
        self._db = sqlite3.connect(path, timeout=0)
        try:
            # EXCLUSIVE locking retains the file fence between transactions.
            # A second owner fails before it can accept or execute a request.
            self._db.execute("PRAGMA locking_mode=EXCLUSIVE")
            self._db.execute("PRAGMA synchronous=FULL")
            self._db.execute(
                "CREATE TABLE IF NOT EXISTS binding(identity TEXT NOT NULL)"
            )
            self._db.execute("""CREATE TABLE IF NOT EXISTS receipts(
                id TEXT PRIMARY KEY, request TEXT NOT NULL, phase TEXT NOT NULL,
                state TEXT, result TEXT, occurred_at TEXT)""")
            row = self._db.execute("SELECT identity FROM binding").fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO binding(identity) VALUES(?)", (self._identity,)
                )
            elif row[0] != self._identity:
                raise ValueError("Tool journal belongs to another deployment")
            self._db.commit()
        except BaseException:
            self._db.close()
            raise

    @property
    def identity(self) -> str:
        return self._identity

    def __enter__(self) -> SqliteToolJournal:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        self._db.close()

    def all(self) -> tuple[ToolReceipt, ...]:
        rows = self._db.execute(
            "SELECT id,request,phase,state,result,occurred_at FROM receipts ORDER BY id"
        ).fetchmany(1001)
        if len(rows) > 1000:
            raise ValueError("Tool journal exceeds retained receipt bound")
        receipts: list[ToolReceipt] = []
        for row in rows:
            if any(not isinstance(row[i], str) for i in (0, 1, 2)):
                raise ValueError("Invalid tool journal record")
            phases: tuple[Literal["executing", "ready", "publishing", "done"], ...] = (
                "executing",
                "ready",
                "publishing",
                "done",
            )
            phase = next((p for p in phases if p == row[2]), None)
            states: tuple[Literal["COMPLETED", "REJECTED", "UNKNOWN"], ...] = (
                "COMPLETED",
                "REJECTED",
                "UNKNOWN",
            )
            state = next((s for s in states if s == row[3]), None)
            if (
                phase is None
                or phase != "executing"
                and (state is None or not isinstance(row[4], str))
            ):
                raise ValueError("Invalid tool journal phase or result")
            if row[5] is not None and not isinstance(row[5], str):
                raise ValueError("Invalid tool journal publication time")
            receipt = ToolReceipt(
                _uuid(row[0]),
                row[1],
                phase,
                ToolResult(state, row[4]) if state is not None else None,
                row[5],
            )
            self._validate(receipt)
            receipts.append(receipt)
        return tuple(receipts)

    @staticmethod
    def _validate(receipt: ToolReceipt) -> None:
        call = _decode(receipt.request)
        if call.invocation_id != _uuid(receipt.invocation_id):
            raise ValueError("Tool receipt request identity mismatch")
        if receipt.phase not in {"executing", "ready", "publishing", "done"}:
            raise ValueError("Invalid tool receipt phase")
        if (receipt.phase == "executing") != (receipt.result is None):
            raise ValueError("Tool receipt result does not match its phase")
        if (receipt.phase in {"publishing", "done"}) != (
            receipt.occurred_at is not None
        ):
            raise ValueError("Tool receipt timestamp does not match its phase")
        if receipt.occurred_at is not None:
            _time(receipt.occurred_at)

    def save(self, receipt: ToolReceipt) -> None:
        self._validate(receipt)
        old = next(
            (r for r in self.all() if r.invocation_id == receipt.invocation_id), None
        )
        allowed = {
            "executing": {"executing", "ready"},
            "ready": {"ready", "publishing"},
            "publishing": {"publishing", "ready", "done"},
            "done": {"done"},
        }
        if old is None:
            if receipt.phase != "executing" or len(self.all()) >= 1000:
                raise ValueError("Tool journal is full or intake intent is missing")
        elif old.request != receipt.request or receipt.phase not in allowed[old.phase]:
            raise ValueError("Conflicting tool receipt identity or phase")
        elif old.result is not None and old.result != receipt.result:
            raise ValueError("A retained tool result is immutable")
        elif (
            old.occurred_at is not None
            and receipt.phase != "ready"
            and old.occurred_at != receipt.occurred_at
        ):
            # Only proven NOT_SUBMITTED may clear publication intent back to ready.
            # An unresolved or completed publication must keep its exact identity.
            raise ValueError("A retained tool publication time is immutable")
        with self._db:
            self._db.execute(
                """INSERT INTO receipts(id,request,phase,state,result,occurred_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                phase=excluded.phase,state=excluded.state,result=excluded.result,occurred_at=excluded.occurred_at""",
                (
                    receipt.invocation_id,
                    receipt.request,
                    receipt.phase,
                    receipt.result.state if receipt.result else None,
                    receipt.result.text if receipt.result else None,
                    receipt.occurred_at,
                ),
            )
