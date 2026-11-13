"""Read-only fence for retired outgoing work, under the collector's exclusive lock.

No old receipt is adopted as a native tool invocation, changed or deleted. A
nonterminal record requires settlement with the archived version before cutover.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .contracts import identifier, integer, items, load_json, object_fields, text, uuid
from .journal import FileReceipts
from .worker import ReconciliationRequired


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


def require_settled_legacy_peer(owner: FileReceipts) -> tuple[PeerReceipt, ...]:
    """Validate retained evidence without writes; caller holds FileReceipts for its lifetime.

    Ready results are also unresolved: the old report may not have reached its
    requester. New harness UUIDs cannot safely resume or replace that operation.
    Terminal evidence remains at its original path for private operator inspection.
    """
    path = owner.directory / "outgoing.json"
    if path.is_symlink():
        raise ValueError("Legacy peer journal cannot be linked")
    if not path.exists():
        return ()
    row = object_fields(load_json(path), {"version", "identity", "receipts"})
    integer(row["version"], 1, 1)
    identity = text(row["identity"], 193)
    prefix = owner.identity + ":"
    if not identity.startswith(prefix):
        raise ValueError("Foreign legacy peer journal")
    identifier(identity[len(prefix) :])
    receipts = tuple(PeerReceipt.decode(item) for item in items(row["receipts"], 1000))
    if len({item.id for item in receipts}) != len(receipts):
        raise ValueError("Duplicate legacy peer receipt")
    if any(item.phase != "done" for item in receipts):
        raise ReconciliationRequired(
            "Legacy peer work remains unresolved; settle with the archived version before native intake"
        )
    return receipts
