"""Bounded operator diagnostics; deployment credentials never enter the dashboard."""

from __future__ import annotations

import re

from plowshare import Refusal


def refusal_detail(error: Refusal, operation: str) -> str:
    """Show the owning operation and server reason, with common credentials redacted.

    This is private dashboard text, never public logs or HTML. A refusal remains
    distinct from an uncertain transport outcome and does not authorize replay.
    """
    code = (
        error.code if re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", error.code) else "REFUSED"
    )
    message = " ".join(
        (error.said or "No further server explanation was provided.").split()
    )
    message = re.sub(r"\bpss_[A-Za-z0-9_.-]+", "[redacted]", message)
    message = re.sub(r"(?i)\bBearer\s+\S+", "Bearer [redacted]", message)
    message = re.sub(
        r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[redacted]", message
    )
    return f"Plowshare refused {operation} ({code}): {message[:600]} Inspect retained state before resuming."
