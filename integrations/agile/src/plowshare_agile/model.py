"""Small immutable Application contracts, independent of SDK transport DTOs."""

from __future__ import annotations

import json
import re
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit
from uuid import UUID

STATUSES = ("backlog", "ready", "in-progress", "done")
KINDS = ("feature", "bug", "chore")
PRIORITIES = ("low", "normal", "high")
RECORD_KINDS = ("ticket", "epic")
MAX_BODY = 16384


def text(value: object, field: str, maximum: int) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > maximum
        or any(ord(c) < 32 for c in value)
    ):
        raise ValueError(f"Invalid {field}")
    return value


def identity(value: object) -> str:
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("Expected a canonical lowercase UUID")
    return value


def choice(value: object, allowed: tuple[str, ...], field: str) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ValueError(f"Invalid {field}")
    return value


def object_fields(value: object, required: set[str]) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != required:
        raise ValueError("Missing or unsupported fields")
    return {k: v for k, v in value.items() if isinstance(k, str)}


def read_json(path: Path) -> object:
    if path.stat().st_size > 65536:
        raise ValueError("JSON file exceeds 64 KiB")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def body_text(value: str) -> str:
    if not value.strip() or len(value.encode("utf-16-le")) // 2 > MAX_BODY:
        raise ValueError("Body must contain 1–16384 UTF-16 units")
    if any(ord(c) < 32 and c not in "\n\t\r" for c in value):
        raise ValueError("Invalid body control character")
    return value


def tag_version(value: object) -> int:
    if type(value) is not int or value < 0 or value > 9007199254740991:
        raise ValueError("Expected a nonnegative safe tag version")
    return value


def tags(values: tuple[str, ...]) -> tuple[str, ...]:
    if len(values) > 32 or len(set(values)) != len(values):
        raise ValueError("Choose at most 32 distinct tags")
    for value in values:
        text(value, "tag", 64)
        if value != value.strip().lower():
            raise ValueError("Tags must be trimmed lowercase text")
        if value.startswith("epic:"):
            identity(value[5:])
    return tuple(sorted(values))


@dataclass(frozen=True)
class TicketFields:
    title: str
    kind: str = "feature"
    status: str = "backlog"
    priority: str = "normal"
    assignee: str | None = None
    iteration: str | None = None

    def __post_init__(self) -> None:
        text(self.title, "title", 160)
        choice(self.kind, KINDS, "kind")
        choice(self.status, STATUSES, "status")
        choice(self.priority, PRIORITIES, "priority")
        if self.assignee is not None:
            text(self.assignee, "assignee", 120)
        if self.iteration is not None:
            text(self.iteration, "iteration", 80)

    @classmethod
    def decode(cls, value: Mapping[str, object]) -> TicketFields:
        if set(value) != {
            "title",
            "kind",
            "status",
            "priority",
            "assignee",
            "iteration",
        }:
            raise ValueError("Ticket metadata differs from the Application contract")
        return cls(
            text(value["title"], "title", 160),
            choice(value["kind"], KINDS, "kind"),
            choice(value["status"], STATUSES, "status"),
            choice(value["priority"], PRIORITIES, "priority"),
            None
            if value["assignee"] is None
            else text(value["assignee"], "assignee", 120),
            None
            if value["iteration"] is None
            else text(value["iteration"], "iteration", 80),
        )


@dataclass(frozen=True)
class EpicFields:
    title: str

    def __post_init__(self) -> None:
        text(self.title, "title", 160)

    @classmethod
    def decode(cls, value: Mapping[str, object]) -> EpicFields:
        if set(value) != {"title"}:
            raise ValueError("Epic metadata differs from the Application contract")
        return cls(text(value["title"], "title", 160))


@dataclass(frozen=True)
class Document:
    resource: str
    revision: str
    owner: str
    tags: tuple[str, ...]
    fields: TicketFields | EpicFields
    body: str | None = None
    tags_version: int | None = None


@dataclass(frozen=True)
class Configuration:
    origin: str
    project: str
    token_environment: str
    state_directory: Path
    ticket_type: str
    ticket_version: str
    epic_type: str
    epic_version: str

    def __post_init__(self) -> None:
        url = urlsplit(self.origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError("Configure an explicit HTTP(S) server origin")
        url.port
        text(self.project, "project", 1024)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", self.token_environment):
            raise ValueError("Invalid token environment variable name")
        for value in (
            self.ticket_type,
            self.ticket_version,
            self.epic_type,
            self.epic_version,
        ):
            identity(value)
        if self.ticket_type == self.epic_type:
            raise ValueError("Ticket and epic types must be distinct")

    @classmethod
    def read(cls, path: Path) -> Configuration:
        if path.stat().st_size > 16384:
            raise ValueError("Configuration exceeds 16 KiB")
        fields = {
            "origin",
            "project",
            "token_environment",
            "state_directory",
            "ticket_type",
            "ticket_version",
            "epic_type",
            "epic_version",
        }
        row = object_fields(tomllib.loads(path.read_text(encoding="utf-8")), fields)
        values = {key: text(value, key, 4096) for key, value in row.items()}
        return cls(
            origin=values["origin"],
            project=values["project"],
            token_environment=values["token_environment"],
            state_directory=(
                path.resolve().parent / values["state_directory"]
            ).resolve(),
            ticket_type=identity(values["ticket_type"]),
            ticket_version=identity(values["ticket_version"]),
            epic_type=identity(values["epic_type"]),
            epic_version=identity(values["epic_version"]),
        )

    def type_id(self, kind: str) -> str:
        choice(kind, RECORD_KINDS, "document kind")
        return self.ticket_type if kind == "ticket" else self.epic_type

    def version_id(self, kind: str) -> str:
        choice(kind, RECORD_KINDS, "document kind")
        return self.ticket_version if kind == "ticket" else self.epic_version
