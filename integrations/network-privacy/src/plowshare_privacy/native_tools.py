"""Privacy capabilities adapted to the native SDK's Relay tool façade."""

from __future__ import annotations

import json
from dataclasses import asdict

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
