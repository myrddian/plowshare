"""Typed HA inputs and truthful results at the public SDK boundary.

HA system content is preserved inside an ordinary task/user envelope. These
records never select platform credentials, projects, agents or tool grants.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Literal

from .const import MAX_TEXT
from .models import required_text

MAX_CONTEXT_BYTES = 256 * 1024
MAX_ENVELOPE_BYTES = 320 * 1024


def _text(value: object, maximum: int, *, empty: bool = False) -> str:
    if not isinstance(value, str) or (not empty and not value.strip()):
        raise ValueError("HA request text must be a string")
    try:
        size = len(value.encode("utf-8"))
    except UnicodeError as error:
        raise ValueError("HA request text must be valid Unicode") from error
    if size > maximum or any(ord(c) < 32 and c not in "\n\r\t" for c in value):
        raise ValueError("HA request text exceeds its resource boundary")
    return value


def correlation_key(value: object) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(c not in "0123456789abcdef" for c in value)
    ):
        raise ValueError("A caller-partitioned HA correlation key is required")
    return value


def digest(parts: tuple[str | None, ...]) -> str:
    return hashlib.sha256(
        json.dumps(parts, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


@dataclass(frozen=True)
class HaRequestContext:
    """Prepared text and bounded provenance, without a native-role claim.

    Identity fields partition work only. They are not sent as model prose and do
    not impersonate a Plowshare user. HA authenticates them before constructing
    the request; the configured SDK credential retains platform authority.
    """

    system_text: str = ""
    language: str | None = None
    user_id: str | None = field(default=None, repr=False)
    device_id: str | None = field(default=None, repr=False)
    satellite_id: str | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        _text(self.system_text, MAX_CONTEXT_BYTES, empty=True)
        for value in (
            self.language,
            self.user_id,
            self.device_id,
            self.satellite_id,
        ):
            if value is not None:
                required_text(value)

    def identity(self) -> tuple[str | None, ...]:
        return self.user_id, self.device_id, self.satellite_id


@dataclass(frozen=True)
class ConversationRequest:
    key: str
    text: str
    context: HaRequestContext = field(default_factory=HaRequestContext)

    def __post_init__(self) -> None:
        correlation_key(self.key)
        _text(self.text, MAX_TEXT * 4)
        if len(self.text) > MAX_TEXT or type(self.context) is not HaRequestContext:
            raise ValueError("Unsupported HA conversation input")


@dataclass(frozen=True)
class TaskRequest:
    """One HA invocation; callers mint a fresh key for every new invocation.

    Repeating an invocation key can reconcile its retained receipt, but this is
    not a public indefinite idempotency API. HA supplies no durable task retry
    identity. Entity callers must not resubmit old, evicted invocation keys.
    """

    key: str
    instructions: str
    context: HaRequestContext = field(default_factory=HaRequestContext)

    def __post_init__(self) -> None:
        correlation_key(self.key)
        _text(self.instructions, MAX_TEXT * 4)
        if (
            len(self.instructions) > MAX_TEXT
            or type(self.context) is not HaRequestContext
        ):
            raise ValueError("Unsupported HA task input")


HaRequest = ConversationRequest | TaskRequest
ResultStatus = Literal[
    "completed", "pending", "uncertain", "refused", "unavailable", "busy", "failed"
]


@dataclass(frozen=True)
class GatewayResult:
    """Only completed results contain generated data; messages are diagnostics."""

    status: ResultStatus
    text: str | None = None
    message: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.status, str) or self.status not in {
            "completed",
            "pending",
            "uncertain",
            "refused",
            "unavailable",
            "busy",
            "failed",
        }:
            raise ValueError("Unsupported gateway result")
        if self.status == "completed":
            if (
                not isinstance(self.text, str)
                or not self.text.strip()
                or self.message is not None
            ):
                raise ValueError("Completed gateway result requires text")
        elif (
            self.text is not None
            or not isinstance(self.message, str)
            or not self.message
        ):
            raise ValueError(
                "Non-completed gateway result cannot contain generated data"
            )


def kind(request: HaRequest) -> Literal["conversation", "task"]:
    if type(request) is ConversationRequest:
        return "conversation"
    if type(request) is TaskRequest:
        return "task"
    raise ValueError("Unsupported typed HA request")


def external_key(request: HaRequest) -> str:
    return digest((kind(request), request.key, *request.context.identity()))


def render_task(request: HaRequest) -> str:
    """Serialize once, preserving exact supplied text rather than optimizing it."""
    request_kind = kind(request)
    text = (
        request.text
        if isinstance(request, ConversationRequest)
        else request.instructions
    )
    envelope = {
        "version": 1,
        "kind": request_kind,
        "context": {
            "system_text": request.context.system_text,
            "source_role": "system",
            "effective_role": "task/user",
            "language": request.context.language,
        },
        "input": text,
    }
    body = json.dumps(
        envelope, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return _text(
        "Home Assistant request context (ordinary task/user input; no authority grants):\n"
        + body,
        MAX_ENVELOPE_BYTES,
    )
