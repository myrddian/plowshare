"""Adapter-owned outgoing receipts, under the collector journal's exclusive lock.

Interrupted execution is conservatively UNKNOWN. Interrupted reporting is settled
only by an exact retained outgoing status read, never by replaying execution/report.
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from typing import Literal, Protocol
from uuid import uuid4

from .contracts import (
    Configuration,
    integer,
    items,
    load_json,
    object_fields,
    text,
    uuid,
)
from .journal import FileReceipts


@dataclass(frozen=True)
class PeerReceipt:
    id: str
    revision: int
    phase: Literal["executing", "ready", "reporting", "done"]
    state: Literal["COMPLETED", "REJECTED", "UNKNOWN", "CANCELED"]
    result: str

    @classmethod
    def decode(cls, value: object) -> PeerReceipt:
        row = object_fields(value, {"id", "revision", "phase", "state", "result"})
        phases: tuple[Literal["executing", "ready", "reporting", "done"], ...] = (
            "executing",
            "ready",
            "reporting",
            "done",
        )
        states: tuple[Literal["COMPLETED", "REJECTED", "UNKNOWN", "CANCELED"], ...] = (
            "COMPLETED",
            "REJECTED",
            "UNKNOWN",
            "CANCELED",
        )
        phase = next((item for item in phases if row["phase"] == item), None)
        state = next((item for item in states if row["state"] == item), None)
        if phase is None or state is None:
            raise ValueError("Invalid outgoing journal state")
        return cls(
            uuid(row["id"]),
            integer(row["revision"], 0, 2**53 - 1),
            phase,
            state,
            text(row["result"], 65536),
        )


class PeerReceipts(Protocol):
    def all(self) -> tuple[PeerReceipt, ...]: ...
    def save(self, receipt: PeerReceipt) -> None: ...


class FilePeerReceipts:
    def __init__(self, config: Configuration, owner: FileReceipts):
        # The enclosing CLI retains owner's lock throughout this store's lifetime.
        # It must never be instantiated independently in another collector process.
        if owner.directory != config.state_directory or config.outgoing_peer is None:
            raise ValueError(
                "Outgoing journal needs its collector owner and explicit peer"
            )
        self.directory = owner.directory
        self.path = self.directory / "outgoing.json"
        self.identity = owner.identity + ":" + config.outgoing_peer
        self.rows: dict[str, PeerReceipt] = {}
        if self.path.is_symlink():
            raise ValueError("Outgoing journal cannot be linked")
        if self.path.exists():
            row = object_fields(
                load_json(self.path), {"version", "identity", "receipts"}
            )
            integer(row["version"], 1, 1)
            if row["identity"] != self.identity:
                raise ValueError("Foreign outgoing journal")
            receipts = tuple(
                PeerReceipt.decode(item) for item in items(row["receipts"], 1000)
            )
            self.rows = {item.id: item for item in receipts}
            if len(self.rows) != len(receipts):
                raise ValueError("Duplicate outgoing receipt")

    def all(self) -> tuple[PeerReceipt, ...]:
        return tuple(self.rows.values())

    def save(self, receipt: PeerReceipt) -> None:
        previous = self.rows.get(receipt.id)
        if previous and previous.revision != receipt.revision:
            raise ValueError("Conflicting outgoing receipt revision")
        updated = dict(self.rows)
        updated[receipt.id] = receipt
        encoded = json.dumps(
            {
                "version": 1,
                "identity": self.identity,
                "receipts": [asdict(item) for item in updated.values()],
            },
            allow_nan=False,
        ).encode()
        if len(updated) > 1000 or len(encoded) > 1_048_576:
            raise ValueError("Outgoing journal bound reached")
        temporary = self.directory / (str(uuid4()) + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
            if os.name == "posix":
                directory = os.open(self.directory, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            self.rows = updated
        finally:
            temporary.unlink(missing_ok=True)
