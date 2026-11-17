"""Validated configuration and adapter-owned conversation correlation.

HA identities partition conversations; they never impersonate Plowshare users.
Every request uses the explicitly configured service credential and project.
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urlsplit

from .const import MAX_SESSIONS


def required_text(value: object, maximum: int = 256) -> str:
    """Reject malformed external identifiers without silently repairing them."""
    if (
        not isinstance(value, str)
        or not value.strip()
        or value != value.strip()
        or len(value) > maximum
        or any(ord(c) < 32 for c in value)
    ):
        raise ValueError("Invalid configuration or correlation identifier")
    return value


def seconds(value: object, maximum: float) -> float:
    if (
        type(value) not in (int, float)
        or not math.isfinite(value)
        or not 0 < value <= maximum
    ):
        raise ValueError("Timeout must be positive, finite and within its limit")
    return float(value)


@dataclass(frozen=True)
class Settings:
    origin: str
    token: str = field(repr=False)
    project: str
    agent: str
    request_timeout: float = 2
    response_timeout: float = 30
    allow_anonymous: bool = False
    request_topic: str = "ha.requests"

    def __post_init__(self) -> None:
        origin = required_text(self.origin, 2048)
        url = urlsplit(origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username is not None
            or url.password is not None
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "Specify an HTTP(S) server origin without a path or credentials"
            )
        # Accessing port validates malformed and out-of-range port syntax.
        url.port
        required_text(self.token, 8192)
        required_text(self.project)
        required_text(self.agent)
        required_text(self.request_topic)
        seconds(self.request_timeout, 30)
        seconds(self.response_timeout, 120)
        if type(self.allow_anonymous) is not bool:
            raise ValueError("Anonymous voice access must be explicitly enabled")


Phase = Literal["idle", "sending", "running", "waiting", "uncertain"]


@dataclass
class Session:
    """Persist before transmission; an uncertain send is never replayed.

    No transcript or credential is kept here. Plowshare owns both conversation
    history and durable jobs. The cursor is a trajectory ordinal, stable across
    compaction. Pending sessions are never evicted to make room for new work.
    """

    conversation: str | None = None
    job: str | None = None
    phase: Phase = "idle"
    cursor: int = 0


def session_key(
    conversation_id: str,
    user_id: str | None,
    device_id: str | None,
    satellite_id: str | None,
    allow_anonymous: bool,
) -> str:
    required_text(conversation_id)
    for value in (user_id, device_id, satellite_id):
        if value is not None:
            required_text(value)
    if user_id is None and (not allow_anonymous or not (device_id or satellite_id)):
        raise PermissionError(
            "This assistant requires an authenticated Home Assistant user"
        )
    identity = json.dumps([conversation_id, user_id, device_id, satellite_id])
    return hashlib.sha256(identity.encode()).hexdigest()


def encode_sessions(sessions: dict[str, Session]) -> dict[str, object]:
    """Convert typed adapter state only at the HA storage boundary."""
    return {
        key: {
            "conversation": item.conversation,
            "job": item.job,
            "phase": item.phase,
            "cursor": item.cursor,
        }
        for key, item in sessions.items()
    }


def decode_sessions(value: object) -> dict[str, Session]:
    if value is None:
        return {}
    if not isinstance(value, dict) or len(value) > MAX_SESSIONS:
        raise ValueError("Invalid Assist correlation store")
    result: dict[str, Session] = {}
    for key, row in value.items():
        if (
            not isinstance(key, str)
            or len(key) != 64
            or any(c not in "0123456789abcdef" for c in key)
            or not isinstance(row, dict)
            or set(row) != {"conversation", "job", "phase", "cursor"}
        ):
            raise ValueError("Invalid Assist session")
        conversation, job, phase, cursor = (
            row["conversation"],
            row["job"],
            row["phase"],
            row["cursor"],
        )
        for identifier in (conversation, job):
            if identifier is not None:
                required_text(identifier)
        if (
            not isinstance(phase, str)
            or phase not in {"idle", "sending", "running", "waiting", "uncertain"}
            or type(cursor) is not int
            or not 0 <= cursor <= 2_147_483_647
            or (phase in {"running", "waiting"} and (not job or not conversation))
            or (job is not None and conversation is None)
        ):
            raise ValueError("Invalid Assist lifecycle state")
        # Restart during submission cannot establish whether the server accepted it.
        result[key] = Session(
            conversation, job, "uncertain" if phase == "sending" else phase, cursor
        )
    return result
