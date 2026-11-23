"""Adapter-owned receipts and exact local scan evidence.

The journal is one small index plus one immutable evidence file per scan, so a
phase change rewrites a few hundred kilobytes at most, never every retained
scan. Settled scans leave the local window after ``RETAINED_SCAN_DAYS``; see
``FileReceipts`` and ``scan_retention``.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import TracebackType
from typing import Literal, Protocol
from uuid import uuid4

from .collection import scope_fingerprint
from .contracts import (
    CollectionPlan,
    Configuration,
    Evidence,
    identifier,
    items,
    load_json,
    object_fields,
    parse_json,
    text,
    timestamp,
    uuid,
)
from .limits import (
    MAX_ARCHIVED_SCANS,
    MAX_EVIDENCE_FILE_BYTES,
    MAX_INDEX_BYTES,
    MAX_JOURNAL_BYTES,
    MAX_RETAINED_SCANS,
    MIN_RETAINED_SCANS,
    RETAINED_SCAN_DAYS,
)
from .operator_state import InvestigationDecision
from .scan_retention import CarriedAddress, carry_forward

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
    investigation: InvestigationDecision | None = None
    local_completion: bool = False
    comparison_scan_id: str | None = None
    comparison_revision: str | None = None
    pin_pending: bool = False

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
            {
                "investigation",
                "local_completion",
                "comparison_scan_id",
                "comparison_revision",
                "pin_pending",
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
        local_completion = row.get("local_completion", False)
        pin_pending = row.get("pin_pending", False)
        investigation = (
            InvestigationDecision.decode(row["investigation"])
            if row.get("investigation") is not None
            else None
        )
        if (
            not isinstance(local_completion, bool)
            or not isinstance(pin_pending, bool)
            or phase != "queued"
            and evidence is None
            or phase in {"uploaded", "publishing", "done"}
            and revision is None
            and not local_completion
            or local_completion
            and (
                investigation is None
                or investigation.requested
                or phase not in {"collected", "publishing", "done"}
            )
            or pin_pending
            and (phase != "done" or not local_completion or revision is not None)
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
            investigation,
            local_completion,
            uuid(row["comparison_scan_id"])
            if row.get("comparison_scan_id") is not None
            else None,
            uuid(row["comparison_revision"])
            if row.get("comparison_revision") is not None
            else None,
            pin_pending,
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
    def carried(self) -> tuple[CarriedAddress, ...]:
        """Per-address history from scans that left the retained window."""
        ...

    def archived(self, scan_id: str) -> bool:
        """Whether this scan settled and then left the retained window."""
        ...


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _digest(content: str) -> str:
    return hashlib.sha256(content.encode()).hexdigest()


@dataclass(frozen=True)
class _State:
    """One consistent journal value; replaced as a whole after a durable write."""

    receipts: dict[str, Receipt]
    publications: dict[str, Publication]
    carried: tuple[CarriedAddress, ...] = ()
    # Oldest first, so the bound drops the identities least likely to return.
    archived: tuple[str, ...] = ()


class FileReceipts:
    """One locked private journal, bounded at 1,000 scans and UI requests each.

    OS locks survive no process restart. Atomic replacement and fsync protect the
    handoff before Relay ack; a crash cannot expose a partially written receipt.

    Layout. ``receipts.json`` is an index of receipt metadata and a SHA-256 of
    each scan's evidence. ``evidence/<scan_id>.json`` holds that evidence in its
    exact retained encoding and is written once, before the index refers to it.
    A phase change therefore rewrites only the index. An index that names
    missing or altered evidence refuses to open; an evidence file the index does
    not name is the remains of an interrupted write and is removed.

    Retention. A scan may leave once it is ``done`` with confirmed remote or
    local-only completion and no outstanding pin. A local predecessor needed
    by a still-retained scan stays until that comparison can be read remotely
    or its dependent scan leaves. Otherwise a scan leaves once it finished
    more than ``RETAINED_SCAN_DAYS`` ago, or when
    the journal exceeds ``MAX_RETAINED_SCANS``; the newest ``MIN_RETAINED_SCANS``
    settled scans always stay. Unsettled scans never leave, whatever their age.
    Leaving is one index write that removes the receipt, folds its per-address
    history into ``carried``, removes the published request that caused it and
    records its identity in ``archived``. That identity is what keeps a Relay
    redelivery from being collected again, for the newest
    ``MAX_ARCHIVED_SCANS`` departed scans. The local evidence file is deleted
    only after that write is durable.

    A journal written before this layout kept evidence inline. It is converted
    on first open, and the original file is kept beside it as
    ``receipts.before-split.json`` for the operator to remove.
    """

    def __init__(
        self, config: Configuration, *, clock: Callable[[], datetime] = _utc_now
    ):
        self.directory = config.state_directory
        self.directory.mkdir(mode=0o700, exist_ok=True)
        if self.directory.is_symlink() or not self.directory.is_dir():
            raise ValueError("Journal requires a private directory")
        if os.name == "posix" and self.directory.stat().st_mode & 0o077:
            raise ValueError("Journal directory permissions must be 0700")
        self.path = self.directory / "receipts.json"
        self.evidence_directory = self.directory / "evidence"
        self._clock = clock
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
            deployment = [
                config.origin,
                config.project,
                config.collector,
                config.group,
                config.request_topic,
                config.result_topic,
                config.schedule,
            ]
            self.collection_scope = scope_fingerprint(config.collection)
            self.next_collection_scope: str | None = None
            self.identity = hashlib.sha256(json.dumps(deployment).encode()).hexdigest()
            legacy_identity = hashlib.sha256(
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
            self._state = _State({}, {})
            self._archived_ids: frozenset[str] = frozenset()
            # Digest of each stored evidence file, by scan.
            self._digests: dict[str, str] = {}
            self.evidence_directory.mkdir(mode=0o700, exist_ok=True)
            if (
                self.evidence_directory.is_symlink()
                or not self.evidence_directory.is_dir()
            ):
                raise ValueError("Journal requires a private evidence directory")
            if self.path.exists():
                if self.path.is_symlink():
                    raise ValueError("Journal cannot be linked")
                row = object_fields(
                    load_json(self.path, maximum_bytes=MAX_JOURNAL_BYTES),
                    {"version", "identity", "receipts", "publications"},
                    {"collectionScope", "nextCollectionScope", "carried", "archived"},
                )
                version = row["version"]
                split = type(version) is int and version == 3
                if split != ("carried" in row and "archived" in row) or (
                    not split and ("carried" in row or "archived" in row)
                ):
                    raise ValueError("Invalid journal layout")
                if type(version) is int and version in (2, 3):
                    scope = text(row.get("collectionScope"), 64)
                    staged = row.get("nextCollectionScope")
                    if (
                        not re.fullmatch(r"[0-9a-f]{64}", scope)
                        or "nextCollectionScope" not in row
                        or staged is not None
                        and not re.fullmatch(r"[0-9a-f]{64}", text(staged, 64))
                    ):
                        raise ValueError("Invalid journal scope transition")
                if not (
                    type(version) is int
                    and (
                        version == 1
                        and row["identity"] == legacy_identity
                        or version in (2, 3)
                        and row["identity"] == self.identity
                        and self.collection_scope
                        in (row.get("collectionScope"), row.get("nextCollectionScope"))
                    )
                ):
                    raise ValueError(
                        "Foreign journal; use a new private state directory"
                    )
                rows = items(row["receipts"], 1000)
                receipts = tuple(
                    self._decode_split(item) if split else Receipt.decode(item)
                    for item in rows
                )
                publications = tuple(
                    Publication.decode(item)
                    for item in items(row["publications"], 1000)
                )
                carried = tuple(
                    CarriedAddress.decode(item)
                    for item in items(row.get("carried", []), 1 << 16)
                )
                archived = tuple(
                    uuid(item)
                    for item in items(row.get("archived", []), MAX_ARCHIVED_SCANS)
                )
                state = _State(
                    {item.scan_id: item for item in receipts},
                    {item.request_id: item for item in publications},
                    carried,
                    archived,
                )
                if (
                    len(state.receipts) != len(receipts)
                    or len(state.publications) != len(publications)
                    or len({item.address for item in carried}) != len(carried)
                    or len(set(archived)) != len(archived)
                    or state.receipts.keys() & set(archived)
                ):
                    raise ValueError("Duplicate journal identity")
                if row.get("nextCollectionScope") is not None:
                    completed = {
                        item.source_event for item in receipts if item.phase == "done"
                    }
                    if any(item.phase != "done" for item in receipts) or any(
                        item.state != "published" or item.request_id not in completed
                        for item in publications
                    ):
                        raise ValueError("Scope transition contains unsettled work")
                if not split:
                    # Evidence files first, then the original kept under another
                    # name, then the index: an interruption at any point leaves
                    # the inline journal in place and the conversion repeatable.
                    for item in receipts:
                        self._store_evidence(item)
                    backup = self.directory / "receipts.before-split.json"
                    if not backup.exists():
                        shutil.copyfile(self.path, backup)
                        os.chmod(backup, 0o600)
                    self._write(state)
                self._state = state
                self._archived_ids = frozenset(state.archived)
            self._remove_unreferenced_evidence()
            self._commit(self._state)
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
        return tuple(self._state.receipts.values())

    def publications(self) -> tuple[Publication, ...]:
        return tuple(self._state.publications.values())

    def carried(self) -> tuple[CarriedAddress, ...]:
        # The same tuple until it changes, so projections can cache on identity.
        return self._state.carried

    def archived(self, scan_id: str) -> bool:
        return scan_id in self._archived_ids

    def save(self, receipt: Receipt) -> None:
        previous = self._state.receipts.get(receipt.scan_id)
        if (
            previous
            and (
                previous.investigation is not None
                or previous.phase in {"publishing", "done"}
            )
            and previous.investigation != receipt.investigation
        ):
            raise ValueError("An investigation admission decision is immutable")
        if (
            previous
            and replace(
                previous,
                phase=receipt.phase,
                evidence=receipt.evidence,
                revision=receipt.revision,
                investigation=receipt.investigation,
                local_completion=receipt.local_completion,
                comparison_scan_id=receipt.comparison_scan_id,
                comparison_revision=receipt.comparison_revision,
                pin_pending=receipt.pin_pending,
            )
            != receipt
        ):
            raise ValueError("Conflicting receipt identity")
        if previous is None and self.archived(receipt.scan_id):
            raise ValueError("This scan already settled and left the journal")
        if previous is None and len(self._state.receipts) >= 1000:
            raise ValueError(
                "Journal scan limit reached; archive settled state before continuing"
            )
        if previous is None or previous.evidence is not receipt.evidence:
            self._store_evidence(receipt)
        updated = dict(self._state.receipts)
        updated[receipt.scan_id] = receipt
        self._commit(replace(self._state, receipts=updated))

    def save_publication(self, publication: Publication) -> None:
        previous = self._state.publications.get(publication.request_id)
        if previous and (
            previous.occurred_at != publication.occurred_at
            or previous.origin != publication.origin
            or previous.state == "published"
            and publication.state != "published"
        ):
            raise ValueError("Conflicting publication identity")
        if previous is None and len(self._state.publications) >= 1000:
            raise ValueError("Journal request limit reached")
        updated = dict(self._state.publications)
        updated[publication.request_id] = publication
        self._commit(replace(self._state, publications=updated), retain=False)

    def prepare_scope_change(self, plan: CollectionPlan) -> None:
        """Migrate verified v1 state before replacing configuration.

        The index fences the deployment identity and explicitly staged scope;
        each evidence record retains its own scope. The operator must settle all
        work before changing that scope.
        Migrating first means a crash between the two writes remains recoverable.
        """
        completed = {
            item.source_event
            for item in self._state.receipts.values()
            if item.phase == "done"
        }
        if any(item.phase != "done" for item in self._state.receipts.values()) or any(
            item.state != "published" or item.request_id not in completed
            for item in self._state.publications.values()
        ):
            raise ValueError("Settle retained work before changing collection scope")
        self.next_collection_scope = scope_fingerprint(plan)
        self._write(self._state)

    def complete_scope_change(self, plan: CollectionPlan) -> None:
        self.collection_scope = scope_fingerprint(plan)
        self.next_collection_scope = None
        self._write(self._state)

    def _evidence_path(self, scan_id: str) -> Path:
        # ``scan_id`` is a validated UUID, so it cannot name another directory.
        return self.evidence_directory / (uuid(scan_id) + ".json")

    def _decode_split(self, value: object) -> Receipt:
        """Join an index row with its evidence file, refusing any mismatch."""
        if not isinstance(value, dict):
            raise ValueError("Expected a receipt")
        row = dict(value)
        digest = row.get("evidence")
        if digest is None:
            return Receipt.decode(row)
        scan_id = uuid(row.get("scan_id"))
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Invalid evidence digest")
        path = self._evidence_path(scan_id)
        if path.is_symlink() or not path.is_file():
            raise ValueError("Retained evidence is missing; restore the journal")
        with path.open("rb") as source:
            content = source.read(MAX_EVIDENCE_FILE_BYTES + 1)
        if len(content) > MAX_EVIDENCE_FILE_BYTES:
            raise ValueError("Retained evidence exceeds the size limit")
        if hashlib.sha256(content).hexdigest() != digest:
            raise ValueError("Retained evidence does not match the journal")
        row["evidence"] = parse_json(content, maximum_bytes=MAX_EVIDENCE_FILE_BYTES)
        receipt = Receipt.decode(row)
        self._digests[scan_id] = digest
        return receipt

    def _store_evidence(self, receipt: Receipt) -> None:
        """Write a scan's evidence durably before any index refers to it."""
        if receipt.evidence is None:
            return
        content = receipt.evidence.encode()
        digest = _digest(content)
        if self._digests.get(receipt.scan_id) == digest:
            return
        encoded = content.encode()
        if len(encoded) > MAX_EVIDENCE_FILE_BYTES:
            raise ValueError("Evidence exceeds the journal's per-scan limit")
        self._replace(self._evidence_path(receipt.scan_id), encoded)
        self._digests[receipt.scan_id] = digest

    def _remove_unreferenced_evidence(self) -> None:
        for path in self.evidence_directory.iterdir():
            if path.name.endswith(".tmp") or (
                path.name.endswith(".json")
                and path.name.removesuffix(".json") not in self._state.receipts
            ):
                path.unlink(missing_ok=True)

    def _retained(self, state: _State) -> _State:
        """Apply the retention rule described on the class; pure."""
        settled = [
            item
            for item in state.receipts.values()
            if item.phase == "done"
            and (item.revision is not None or item.local_completion)
            and not item.pin_pending
            and item.evidence is not None
        ]
        candidates = settled[: max(0, len(settled) - MIN_RETAINED_SCANS)]
        horizon = self._clock() - timedelta(days=RETAINED_SCAN_DAYS)
        excess = len(state.receipts) - MAX_RETAINED_SCANS
        leaving: list[Receipt] = []
        for item in candidates:
            assert item.evidence is not None
            finished = datetime.fromisoformat(
                item.evidence.finished_at.replace("Z", "+00:00")
            )
            if finished < horizon or len(leaving) < excess:
                leaving.append(item)
        departing = {item.scan_id for item in leaving}
        needed = {
            item.comparison_scan_id
            for item in state.receipts.values()
            if item.scan_id not in departing
            and item.comparison_revision is None
            and item.evidence is not None
            and item.evidence.previous_revision is None
        }
        leaving = [item for item in leaving if item.scan_id not in needed]
        if not leaving:
            return state
        carried = state.carried
        for item in leaving:  # Journal order is oldest first.
            assert item.evidence is not None
            carried = carry_forward(carried, item.evidence)
        gone = {item.scan_id for item in leaving}
        causes = {item.source_event for item in leaving}
        return _State(
            {key: item for key, item in state.receipts.items() if key not in gone},
            {
                key: item
                for key, item in state.publications.items()
                if not (item.state == "published" and key in causes)
            },
            carried,
            (*state.archived, *(item.scan_id for item in leaving))[
                -MAX_ARCHIVED_SCANS:
            ],
        )

    def _commit(self, state: _State, *, retain: bool = True) -> None:
        """Make ``state`` durable and current, then drop evidence that left."""
        retained = self._retained(state) if retain else state
        if retained is not self._state:
            self._write(retained)
        departed = self._state.receipts.keys() - retained.receipts.keys()
        departed |= state.receipts.keys() - retained.receipts.keys()
        if retained.archived is not self._state.archived:
            self._archived_ids = frozenset(retained.archived)
        self._state = retained
        for scan_id in departed:
            self._digests.pop(scan_id, None)
            self._evidence_path(scan_id).unlink(missing_ok=True)

    def _write(self, state: _State) -> None:
        encoded = json.dumps(
            {
                "version": 3,
                "identity": self.identity,
                "collectionScope": self.collection_scope,
                "nextCollectionScope": self.next_collection_scope,
                # Only metadata is encoded here: the evidence itself is already
                # durable in its own file and is named by digest.
                "receipts": [
                    {
                        **asdict(replace(item, evidence=None)),
                        "evidence": self._digests[item.scan_id]
                        if item.evidence is not None
                        else None,
                    }
                    for item in state.receipts.values()
                ],
                "publications": [asdict(item) for item in state.publications.values()],
                "carried": [item.encode() for item in state.carried],
                "archived": list(state.archived),
            },
            allow_nan=False,
        ).encode()
        # Refuse before replacing or acknowledging intake.
        if len(encoded) > MAX_INDEX_BYTES:
            raise ValueError("Journal byte limit reached")
        self._replace(self.path, encoded)

    def _replace(self, path: Path, encoded: bytes) -> None:
        """Atomically install ``encoded`` at ``path`` and make it durable."""
        temporary = path.parent / (str(uuid4()) + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            if os.name == "posix":
                directory = os.open(path.parent, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)
