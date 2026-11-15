"""Durable collector failure notices through the public Application inbox contract.

Each stopped episode has one request UUID. Unknown replies are reconciled by that
exact receipt, never by repeating a scan or automatically replaying a publication.
An operator can explicitly submit pending notices after restoring connectivity.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Literal, Protocol
from uuid import uuid4

from plowshare import Client, Refusal, TransportError
from plowshare.contracts import InboxPublishRequest, InboxReceiptRequest

from .contracts import items, load_json, object_fields, text, uuid
from .private_files import replace_private


class AttentionPort(Protocol):
    async def publish(self, request: str, message: str) -> None: ...
    async def confirmed(self, request: str) -> bool: ...


class SdkAttentionPort:
    def __init__(self, client: Callable[[], Client], project: str):
        self.client, self.project = client, project

    async def publish(self, request: str, message: str) -> None:
        (
            await self.client().request(
                InboxPublishRequest(
                    project=self.project,
                    request_id=request,
                    kind="failure",
                    text=message,
                )
            )
        ).require_payload()

    async def confirmed(self, request: str) -> bool:
        return (
            (
                await self.client().request(
                    InboxReceiptRequest(project=self.project, request_id=request)
                )
            )
            .require_payload()
            .found
        )


@dataclass(frozen=True)
class Alert:
    request: str
    category: str
    operation: str
    phase: Literal["pending", "published"]
    active: bool

    @classmethod
    def decode(cls, value: object) -> Alert:
        row = object_fields(
            value, {"request", "category", "operation", "phase", "active"}
        )
        if (
            row["phase"] not in ("pending", "published")
            or type(row["active"]) is not bool
        ):
            raise ValueError("Invalid attention delivery state")
        return cls(
            uuid(row["request"]),
            text(row["category"], 128),
            text(row["operation"], 256),
            "pending" if row["phase"] == "pending" else "published",
            row["active"],
        )

    def message(self) -> str:
        # Categories and operation names are adapter-owned. Exception messages,
        # credentials and untrusted provider payloads never enter notifications.
        return (
            "Privacy Watch collection stopped and needs attention.\n\n"
            f"Operation: {self.operation}\nFailure category: {self.category}\n"
            "Inspect System health and retained work in the Privacy Watch dashboard. "
            "No scan or uncertain mutation was replayed."
        )


class AttentionOutbox:
    def __init__(self, directory: Path, port: AttentionPort):
        self.path = directory / "attention.json"
        self.port = port
        self.rows: tuple[Alert, ...] = ()
        if self.path.is_symlink():
            raise ValueError("Attention journal cannot be linked")
        if self.path.exists():
            self.rows = tuple(
                Alert.decode(row) for row in items(load_json(self.path), 1000)
            )
            if len({row.request for row in self.rows}) != len(self.rows):
                raise ValueError("Duplicate attention identity")
            if sum(row.active for row in self.rows) > 1:
                raise ValueError("Conflicting active attention episodes")

    @property
    def pending(self) -> int:
        return sum(row.phase == "pending" for row in self.rows)

    def save(self, rows: tuple[Alert, ...]) -> None:
        if len(rows) > 1000:
            raise ValueError("Attention journal is full; inspect retained alerts")
        replace_private(self.path, json.dumps([asdict(row) for row in rows]) + "\n")
        self.rows = rows

    def update(self, row: Alert) -> None:
        self.save(
            tuple(row if old.request == row.request else old for old in self.rows)
        )

    async def stopped(self, category: str, operation: str) -> str | None:
        if any(row.active for row in self.rows):
            return "An inbox alert remains pending." if self.pending else None
        row = Alert(
            str(uuid4()), text(category, 128), text(operation, 256), "pending", True
        )
        self.save((*self.rows, row))
        try:
            await self.port.publish(row.request, row.message())
        except (Refusal, TransportError, OSError, ValueError) as error:
            return "Inbox alert delivery is unconfirmed (" + type(error).__name__ + ")."
        self.update(replace(row, phase="published"))
        return None

    def recovered(self) -> None:
        if any(row.active for row in self.rows):
            self.save(tuple(replace(row, active=False) for row in self.rows))

    async def reconcile(self) -> None:
        """Positive exact receipts settle delivery; absent receipts remain pending."""
        for row in self.rows:
            if row.phase == "pending" and await self.port.confirmed(row.request):
                self.update(replace(row, phase="published"))
        self.save(tuple(replace(row, active=False) for row in self.rows))

    async def send_pending(self) -> int:
        """Explicit operator retry with the original identity and bytes; never called by polling."""
        sent = 0
        for row in self.rows:
            if row.phase != "pending":
                continue
            if not await self.port.confirmed(row.request):
                await self.port.publish(row.request, row.message())
            self.update(replace(row, phase="published"))
            sent += 1
        return sent
