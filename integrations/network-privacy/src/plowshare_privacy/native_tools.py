"""Privacy capabilities adapted to the native SDK's Relay tool façade."""

from __future__ import annotations

import json
import os
import time
from dataclasses import asdict
from pathlib import Path
from typing import Protocol
from uuid import uuid4

from plowshare.tools import (
    Parameter,
    RegisteredTool,
    ToolCall,
    ToolDeclaration,
    ToolResult,
)

from .journal import Publication, ScanOrigin
from .tools import DEFINITIONS, PrivacyTools, ScanCall, decode_call
from .tools import ToolCall as PrivacyCall

DECLARATIONS = tuple(
    ToolDeclaration(
        definition.name.replace(".", "_"),
        definition.description,
        (
            (
                Parameter(
                    definition.parameter,
                    "STRING",
                    "Canonical UUID of the retained request or scan.",
                ),
            )
            if definition.parameter is not None and definition.name != "network.scan"
            else ()
        ),
        timeout_seconds=30,
    )
    for definition in DEFINITIONS
)


def registered(tools: PrivacyTools) -> tuple[RegisteredTool, ...]:
    async def handle(call: ToolCall) -> ToolResult:
        # A scan gets its retained request identity from the harness invocation;
        # the model cannot fabricate a replacement id after an uncertain result.
        decoded: PrivacyCall
        if call.tool == "network_scan":
            decoded = ScanCall(
                call.invocation_id,
                ScanOrigin(
                    f"tool.{call.provider}.{call.tool}.request", call.invocation_id
                ),
            )
        else:
            name = next(
                (d.name for d in DEFINITIONS if d.name.replace(".", "_") == call.tool),
                None,
            )
            if name is None:
                return ToolResult(
                    "REJECTED", "Unknown network privacy capability; nothing ran."
                )
            decoded = decode_call(name, dict(call.arguments))
        try:
            result = await tools.execute(decoded)
        except ValueError:
            return ToolResult(
                "REJECTED", "Requested collector evidence or identity is unavailable."
            )
        value = (
            {
                "request_id": result.request_id,
                "occurred_at": result.occurred_at,
                "state": result.state,
            }
            if isinstance(result, Publication)
            else asdict(result)
        )
        return ToolResult(
            "COMPLETED",
            json.dumps(value, allow_nan=False, separators=(",", ":")),
        )

    return tuple(RegisteredTool(declaration, handle) for declaration in DECLARATIONS)


class CatalogueTools(Protocol):
    async def publish_catalog(self, request_id: str) -> None: ...

    async def poll(self) -> int: ...


class CataloguedProvider:
    """Renew metadata leases; durable intents are recorded before each SDK publication.

    A failed publication stops intake. Restart submits fresh current metadata, never
    replays an old publication UUID or any tool invocation. The log retains the UUID
    for read-only broker inspection if the reply was lost.
    """

    def __init__(
        self, provider: CatalogueTools, directory: Path, renew_seconds: float = 100
    ):
        if not 1 <= renew_seconds <= 100:
            raise ValueError("Catalogue renewal must be between 1 and 100 seconds")
        self.renew_seconds = renew_seconds
        self.provider = provider
        self.receipts = directory / "tool-catalogue.jsonl"
        self.next_renewal = 0.0

    def _record(self, request_id: str, state: str) -> None:
        descriptor = os.open(
            self.receipts, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600
        )
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(json.dumps({"request_id": request_id, "state": state}) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        # The first receipt must survive loss of the directory entry as well as its contents.
        if os.name == "posix":
            directory = os.open(self.receipts.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)

    async def poll(self) -> int:
        if time.monotonic() >= self.next_renewal:
            request_id = str(uuid4())
            self._record(request_id, "publishing")
            await self.provider.publish_catalog(request_id)
            self._record(request_id, "published")
            self.next_renewal = time.monotonic() + self.renew_seconds
        return await self.provider.poll()
