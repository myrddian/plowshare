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
from .tools import (
    DEFINITIONS,
    LINK_NOTE_LIMIT,
    NOTE_LIMIT,
    QUESTION_LIMIT,
    PrivacyTools,
    ScanCall,
    ToolRefusal,
    decode_call,
)
from .tools import ToolCall as PrivacyCall


def parameters(name: str, parameter: str | None) -> tuple[Parameter, ...]:
    values = []
    if parameter == "device_id":
        values.append(
            Parameter(
                parameter,
                "STRING",
                "A device_id from network_devices: mac: and a canonical MAC address.",
            )
        )
    elif parameter is not None and name != "network.scan":
        values.append(
            Parameter(
                parameter, "STRING", "Canonical UUID of the retained request or scan."
            )
        )
    if name in {
        "network.scope",
        "network.scan_list",
        "network.evidence",
        "network.destinations",
        "network.devices",
    }:
        values.extend(
            (
                Parameter(
                    "offset",
                    "INTEGER",
                    "Page offset; follow the returned next_offset.",
                    required=False,
                ),
                Parameter(
                    "limit",
                    "INTEGER",
                    "Maximum page records: 1-32."
                    if name == "network.devices"
                    else "Maximum page records: 1-32 (scan list: 1-50).",
                    required=False,
                ),
            )
        )
    if name == "network.note_profile":
        values.append(
            Parameter(
                "note",
                "STRING",
                f"The note, up to {NOTE_LIMIT} characters; new lines allowed, no other control characters. Saved as a dated line attributed to Privacy Watch.",
            )
        )
    if name == "network.record_issue":
        values.extend(
            (
                Parameter(
                    "issue_id",
                    "STRING",
                    "The UUID of an existing privacy issue in this project.",
                ),
                Parameter(
                    "applicability",
                    "STRING",
                    "potentially_affected, confirmed_affected, not_affected, mitigated (needs verified) or unresolved.",
                ),
                Parameter(
                    "mitigation_status",
                    "STRING",
                    "proposed, accepted, applied, verified or reverted. Omit to keep the current status (proposed for a new link).",
                    required=False,
                ),
                Parameter(
                    "note",
                    "STRING",
                    f"What was found or done, up to {LINK_NOTE_LIMIT} characters; dated and attributed to Privacy Watch, replacing the link's earlier note. Required for confirmed_affected, applied, verified and reverted.",
                    required=False,
                ),
            )
        )
    if name == "network.request_reference":
        values.extend(
            (
                Parameter(
                    "product",
                    "STRING",
                    "The product slug, as in the device-reference tags: lowercase letters, digits and hyphens, at most 48 characters.",
                ),
                Parameter(
                    "question",
                    "STRING",
                    f"What the library could not answer, up to {QUESTION_LIMIT} characters, for the owner to read. Nothing in it is fetched.",
                ),
                Parameter(
                    "device_id",
                    "STRING",
                    "Optional: the device_id from network_devices the question is about.",
                    required=False,
                ),
            )
        )
    if name == "network.evidence":
        values.append(
            Parameter(
                "section",
                "STRING",
                "summary, tcp, dns, devices, changes or issues. Omit for small legacy evidence.",
                required=False,
            )
        )
    return tuple(values)


DECLARATIONS = tuple(
    ToolDeclaration(
        definition.name.replace(".", "_"),
        definition.description,
        parameters(definition.name, definition.parameter),
        timeout_seconds=30,
    )
    for definition in DEFINITIONS
)

# The tools the ``network_assistant`` scope grants ``privacy_assistant``: every
# declaration except the scans, which only the coordinator may start. A
# snapshot is measured against this set (a test pins it to plowshare.json).
ASSISTANT_TOOLS = frozenset(
    declaration.name
    for declaration in DECLARATIONS
    if declaration.name not in ("network_scan", "network_scan_status")
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
            # Writes take the harness invocation as their idempotency key, so a
            # replayed call returns the earlier receipt rather than writing twice.
            try:
                decoded = decode_call(
                    name, dict(call.arguments), request_id=call.invocation_id
                )
            except ToolRefusal as error:
                return ToolResult("REJECTED", str(error))
        try:
            result = await tools.execute(decoded)
        except ToolRefusal as error:
            # Written for the agent to tell the user; it echoes no input.
            return ToolResult("REJECTED", str(error))
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
