"""Private request intents, never an authoritative ticket store or retry queue."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from uuid import uuid4

from .model import (
    RECORD_KINDS,
    Configuration,
    EpicFields,
    TicketFields,
    body_text,
    choice,
    identity,
    object_fields,
    read_json,
    tag_version,
    tags,
)


@dataclass(frozen=True)
class Intent:
    action: str
    kind: str
    request_id: str
    fields: TicketFields | EpicFields
    resource: str | None = None
    expected_revision: str | None = None
    body: str | None = None
    tags: tuple[str, ...] = ()
    expected_tags_version: int | None = None

    def __post_init__(self) -> None:
        choice(self.action, ("create", "revise", "body", "tags"), "action")
        choice(self.kind, RECORD_KINDS, "document kind")
        identity(self.request_id)
        if (
            self.kind == "ticket"
            and not isinstance(self.fields, TicketFields)
            or self.kind == "epic"
            and not isinstance(self.fields, EpicFields)
        ):
            raise ValueError("Foreign document fields")
        if self.action == "create":
            if (
                self.resource is not None
                or self.expected_revision is not None
                or self.tags
            ):
                raise ValueError("Create must not replace an existing resource")
            if self.body is None:
                raise ValueError("Create requires a body")
            body_text(self.body)
        else:
            identity(self.resource)
            identity(self.expected_revision)
            if self.action == "body":
                if self.body is None:
                    raise ValueError("Body update requires text")
                body_text(self.body)
            elif self.body is not None:
                raise ValueError("This update cannot replace body bytes")
        if self.expected_tags_version is not None:
            tag_version(self.expected_tags_version)
            if self.action != "tags":
                raise ValueError("Tag preconditions belong only to tag mutations")
        if self.action == "tags":
            if self.tags != tags(self.tags):
                raise ValueError("Retained tags must use canonical ordering")
            if self.kind != "ticket":
                raise ValueError("Epic membership belongs to tickets")
        elif self.tags:
            raise ValueError("Tag changes are a separate operation")

    @classmethod
    def decode(cls, value: object) -> Intent:
        if not isinstance(value, dict):
            raise ValueError("Invalid retained intent")
        # Historical intents retain the legacy operation and its original fingerprint.
        version = (
            tag_version(value["expected_tags_version"])
            if "expected_tags_version" in value
            else None
        )
        fields_with_version = {
            k: v for k, v in value.items() if k != "expected_tags_version"
        }
        row = object_fields(
            fields_with_version,
            {
                "action",
                "kind",
                "request_id",
                "fields",
                "resource",
                "expected_revision",
                "body",
                "tags",
            },
        )
        kind = choice(row["kind"], RECORD_KINDS, "document kind")
        names = (
            {"title", "kind", "status", "priority", "assignee", "iteration"}
            if kind == "ticket"
            else {"title"}
        )
        fields = object_fields(row["fields"], names)
        entries = row["tags"]
        if not isinstance(entries, list) or not all(
            isinstance(v, str) for v in entries
        ):
            raise ValueError("Invalid retained tags")
        checked_tags = tuple(v for v in entries if isinstance(v, str))
        body = row["body"]
        if body is not None and not isinstance(body, str):
            raise ValueError("Invalid retained body")
        return cls(
            choice(row["action"], ("create", "revise", "body", "tags"), "action"),
            kind,
            identity(row["request_id"]),
            TicketFields.decode(fields)
            if kind == "ticket"
            else EpicFields.decode(fields),
            None if row["resource"] is None else identity(row["resource"]),
            None
            if row["expected_revision"] is None
            else identity(row["expected_revision"]),
            body,
            tags(checked_tags),
            None if version is None else tag_version(version),
        )


class Journal:
    """Persist exact inputs before submission; recovery is always an explicit command.

    A request cannot be reused for another payload or connection. The SDK/server
    remain responsible for admission and duplicate request receipts. Local results
    are historical observations, not current permission or ticket-state proofs.
    """

    def __init__(self, config: Configuration, credential: str):
        self.root = config.state_directory
        if not credential or credential != credential.strip():
            raise ValueError("A credential is required to bind request recovery")
        binding = {
            "origin": config.origin,
            "project": config.project,
            "ticket_type": config.ticket_type,
            "ticket_version": config.ticket_version,
            "epic_type": config.epic_type,
            "epic_version": config.epic_version,
            "credential": hashlib.sha256(credential.encode()).hexdigest(),
        }
        self.binding = hashlib.sha256(
            json.dumps(binding, sort_keys=True).encode()
        ).hexdigest()

    def _path(self, request: str) -> Path:
        return self.root / (identity(request) + ".json")

    def _record(self, intent: Intent, outcome: str) -> bytes:
        retained = asdict(intent)
        if intent.expected_tags_version is None:
            retained.pop("expected_tags_version")
        return (
            json.dumps(
                {
                    "version": 1,
                    "binding": self.binding,
                    "intent": retained,
                    "outcome": outcome,
                },
                ensure_ascii=False,
                allow_nan=False,
                sort_keys=True,
            )
            + "\n"
        ).encode()

    def prepare(self, intent: Intent) -> None:
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.root.stat().st_mode & 0o077:
            raise ValueError("Request directory must be private (mode 700)")
        path = self._path(intent.request_id)
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            raise ValueError(
                "Request already retained; use request show or request recover"
            ) from None
        with os.fdopen(descriptor, "wb") as output:
            output.write(self._record(intent, "pending"))
            output.flush()
            os.fsync(output.fileno())

    def read(self, request: str) -> tuple[Intent, str]:
        path = self._path(request)
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("Request file must be private and not a symlink")
        row = object_fields(
            read_json(path), {"version", "binding", "intent", "outcome"}
        )
        if (
            type(row["version"]) is not int
            or row["version"] != 1
            or row["binding"] != self.binding
        ):
            raise ValueError("Request belongs to another configuration")
        intent = Intent.decode(row["intent"])
        if intent.request_id != request:
            raise ValueError("Request file identity differs")
        return intent, choice(
            row["outcome"], ("pending", "confirmed", "refused"), "outcome"
        )

    def settle(self, intent: Intent, outcome: str) -> None:
        existing, _ = self.read(intent.request_id)
        if existing != intent:
            raise ValueError("Retained request changed")
        choice(outcome, ("confirmed", "refused"), "outcome")
        temporary = self.root / (str(uuid4()) + ".tmp")
        with os.fdopen(
            os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb"
        ) as output:
            output.write(self._record(intent, outcome))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self._path(intent.request_id))
