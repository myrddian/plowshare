"""Adapter-owned receipts. The project information store owns retained evidence."""

from __future__ import annotations

import hashlib
import json
import os
import sys
from dataclasses import asdict, dataclass, replace
from types import TracebackType
from typing import Literal, Protocol
from uuid import uuid4

from .collection import scope_fingerprint
from .contracts import (
    Configuration,
    Evidence,
    identifier,
    items,
    load_json,
    object_fields,
    text,
    timestamp,
    uuid,
)

Phase = Literal["queued", "collected", "uploading", "uploaded", "publishing", "done"]


@dataclass(frozen=True)
class Receipt:
    scan_id: str
    source_event: str
    source_topic: str
    occurred_at: str
    phase: Phase = "queued"
    evidence: Evidence | None = None
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> Receipt:
        row = object_fields(
            value,
            {
                "scan_id",
                "source_event",
                "source_topic",
                "occurred_at",
                "phase",
                "evidence",
                "revision",
            },
        )
        phases: tuple[Phase, ...] = (
            "queued",
            "collected",
            "uploading",
            "uploaded",
            "publishing",
            "done",
        )
        phase = next((item for item in phases if item == row["phase"]), None)
        if phase is None:
            raise ValueError("Invalid receipt phase")
        evidence = (
            Evidence.decode(row["evidence"]) if row["evidence"] is not None else None
        )
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        scan_id = uuid(row["scan_id"])
        source_event = text(row["source_event"])
        if (
            phase != "queued"
            and evidence is None
            or phase in {"uploaded", "publishing", "done"}
            and revision is None
        ):
            raise ValueError("Receipt is missing committed evidence")
        if evidence and (
            evidence.scan_id != scan_id or evidence.source_event != source_event
        ):
            raise ValueError("Foreign receipt evidence")
        return cls(
            scan_id,
            source_event,
            identifier(row["source_topic"]),
            timestamp(row["occurred_at"]),
            phase,
            evidence,
            revision,
        )


@dataclass(frozen=True)
class ScanOrigin:
    topic: str
    event: str

    def __post_init__(self) -> None:
        identifier(self.topic)
        uuid(self.event)

    @classmethod
    def decode(cls, value: object) -> ScanOrigin:
        row = object_fields(value, {"topic", "event"})
        return cls(identifier(row["topic"]), uuid(row["event"]))


@dataclass(frozen=True)
class Publication:
    request_id: str
    occurred_at: str
    state: Literal["pending", "published"]
    origin: ScanOrigin | None = None

    @classmethod
    def decode(cls, value: object) -> Publication:
        row = object_fields(value, {"request_id", "occurred_at", "state"}, {"origin"})
        if row["state"] not in ("pending", "published"):
            raise ValueError("Invalid publication state")
        return cls(
            uuid(row["request_id"]),
            timestamp(row["occurred_at"]),
            "pending" if row["state"] == "pending" else "published",
            ScanOrigin.decode(row["origin"]) if row.get("origin") is not None else None,
        )


class Receipts(Protocol):
    """Writes finish durably before SDK mutations. Unknown outcomes remain pending."""

    def all(self) -> tuple[Receipt, ...]: ...
    def save(self, receipt: Receipt) -> None: ...
    def publications(self) -> tuple[Publication, ...]: ...
    def save_publication(self, publication: Publication) -> None: ...


class FileReceipts:
    """One locked private journal, bounded at 1,000 scans and UI requests each.

    OS locks survive no process restart. Atomic replacement and fsync protect the
    handoff before Relay ack; a crash cannot expose a partially written receipt.
    No automatic pruning erases deduplication while Relay may still redeliver.
    """

    def __init__(self, config: Configuration):
        self.directory = config.state_directory
        self.directory.mkdir(mode=0o700, exist_ok=True)
        if self.directory.is_symlink() or not self.directory.is_dir():
            raise ValueError("Journal requires a private directory")
        if os.name == "posix" and self.directory.stat().st_mode & 0o077:
            raise ValueError("Journal directory permissions must be 0700")
        self.path = self.directory / "receipts.json"
        lock = self.directory / "receipts.lock"
        self._fd = os.open(
            lock, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600
        )
        try:
            if sys.platform == "win32":
                import msvcrt

                os.write(self._fd, b"0")
                os.lseek(self._fd, 0, os.SEEK_SET)
                msvcrt.locking(self._fd, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.identity = hashlib.sha256(
                json.dumps(
                    [
                        config.origin,
                        config.project,
                        config.collector,
                        config.group,
                        config.request_topic,
                        config.result_topic,
                        config.schedule,
                        scope_fingerprint(config.collection),
                    ]
                ).encode()
            ).hexdigest()
            self._receipts: dict[str, Receipt] = {}
            self._publications: dict[str, Publication] = {}
            if self.path.exists():
                if self.path.is_symlink():
                    raise ValueError("Journal cannot be linked")
                row = object_fields(
                    load_json(self.path),
                    {"version", "identity", "receipts", "publications"},
                )
                if row["version"] != 1 or row["identity"] != self.identity:
                    raise ValueError(
                        "Foreign journal; use a new private state directory"
                    )
                receipts = tuple(
                    Receipt.decode(item) for item in items(row["receipts"], 1000)
                )
                publications = tuple(
                    Publication.decode(item)
                    for item in items(row["publications"], 1000)
                )
                self._receipts = {item.scan_id: item for item in receipts}
                self._publications = {item.request_id: item for item in publications}
                if len(self._receipts) != len(receipts) or len(
                    self._publications
                ) != len(publications):
                    raise ValueError("Duplicate journal identity")
        except BaseException:
            os.close(self._fd)
            raise

    def __enter__(self) -> FileReceipts:
        return self

    def __exit__(
        self,
        kind: type[BaseException] | None,
        value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        os.close(self._fd)

    def all(self) -> tuple[Receipt, ...]:
        return tuple(self._receipts.values())

    def publications(self) -> tuple[Publication, ...]:
        return tuple(self._publications.values())

    def save(self, receipt: Receipt) -> None:
        previous = self._receipts.get(receipt.scan_id)
        if (
            previous
            and replace(
                previous,
                phase=receipt.phase,
                evidence=receipt.evidence,
                revision=receipt.revision,
            )
            != receipt
        ):
            raise ValueError("Conflicting receipt identity")
        if previous is None and len(self._receipts) >= 1000:
            raise ValueError(
                "Journal scan limit reached; archive settled state before continuing"
            )
        updated = dict(self._receipts)
        updated[receipt.scan_id] = receipt
        self._write(updated, self._publications)
        self._receipts = updated

    def save_publication(self, publication: Publication) -> None:
        previous = self._publications.get(publication.request_id)
        if previous and (
            previous.occurred_at != publication.occurred_at
            or previous.origin != publication.origin
            or previous.state == "published"
            and publication.state != "published"
        ):
            raise ValueError("Conflicting publication identity")
        if previous is None and len(self._publications) >= 1000:
            raise ValueError("Journal request limit reached")
        updated = dict(self._publications)
        updated[publication.request_id] = publication
        self._write(self._receipts, updated)
        self._publications = updated

    def _write(
        self, receipts: dict[str, Receipt], publications: dict[str, Publication]
    ) -> None:
        encoded = json.dumps(
            {
                "version": 1,
                "identity": self.identity,
                "receipts": [asdict(item) for item in receipts.values()],
                "publications": [asdict(item) for item in publications.values()],
            },
            allow_nan=False,
        ).encode()
        # Match the read bound. Refuse before replacing or acknowledging intake.
        if len(encoded) > 1_048_576:
            raise ValueError("Journal byte limit reached")
        temporary = self.directory / (str(uuid4()) + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
            if os.name == "posix":
                directory = os.open(self.directory, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)
