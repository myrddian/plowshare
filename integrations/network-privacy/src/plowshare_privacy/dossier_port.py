"""Retain device dossiers as revisioned project Information sources.

Each monitored device has one source, ``network-privacy-dossier/<device_id>.json``.
The first retention uploads it; later retentions revise the same resource, so
an assessment can cite the exact revision it was built from. Plowshare owns the
text and history. The private intent journal only fences delivery uncertainty,
following ``device_profiles`` and ``issue_records``:

* The exact document, including a fresh write UUID, is flushed to the journal
  before transport. A lost or ambiguous reply leaves the intent ``pending``.
* A pending intent is settled only by reading: exactly one retained revision of
  that device's source must carry the identical text (the write UUID makes it
  unique). Otherwise the device stays fenced with ``ReconciliationRequired``
  and nothing is resubmitted, under the old or a new UUID.
* A fence applies to one device. Other devices' sources are separate resources,
  so their retention does not depend on it.

A retention whose dossier is byte-identical to the latest retained revision
creates nothing and returns that revision. The comparison ignores the write
UUID, which identifies a delivery rather than content.
"""

from __future__ import annotations

import asyncio
import json
import math
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Literal, Protocol
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, TransportError
from plowshare.contracts import (
    InformationFilterDto,
    InformationListRequest,
    InformationReviseRequest,
    InformationUploadRequest,
    SelectionDtoScopeVariant3Dto,
)

from .contracts import (
    MAX_FILE_BYTES,
    Configuration,
    integer,
    items,
    load_json,
    object_fields,
    parse_json,
    timestamp,
    uuid,
)
from .device_dossier import DeviceDossier, encode_dossier, parse_device_id
from .private_files import replace_private
from .profile_port import RetainedSourceReader
from .worker import ReconciliationRequired

PREFIX = "network-privacy-dossier/"
DOCUMENT_VERSION = 1
# A dossier is built to the 16,000-byte tool reply budget; the envelope adds
# well under 200 bytes. The bound leaves room without admitting unbounded text.
MAX_DOCUMENT_BYTES = 32768
# Pending intents each hold a full document. Sixteen keeps the journal far below
# MAX_FILE_BYTES; past that, unresolved writes must be inspected first.
MAX_PENDING = 16
# Settled receipts carry no fencing authority (the server catalogue is the
# record), so only a few recent ones are kept for diagnosis.
KEEP_SETTLED = 8
# Listing pages per device. Revisions accrue only when the dossier changes and a
# retention is requested, so 1,000 is far beyond normal history.
MAX_PAGES = 10
PAGE = 100


def source_name(device_id: str) -> str:
    """The stable source name for one device; only canonical MAC IDs are accepted."""
    return PREFIX + parse_device_id(device_id) + ".json"


@dataclass(frozen=True)
class DossierDocument:
    """The retained text: a versioned envelope around the compact dossier JSON.

    ``payload`` is exactly ``encode_dossier`` output. ``read`` accepts only text
    that ``text()`` would produce, so a retained revision either round-trips
    byte for byte or is rejected.
    """

    write_id: str
    device_id: str
    payload: str

    def __post_init__(self) -> None:
        uuid(self.write_id)
        parse_device_id(self.device_id)
        dossier = object_fields(
            parse_json(self.payload, maximum_bytes=MAX_DOCUMENT_BYTES),
            {
                "identity",
                "detection",
                "presence",
                "services",
                "dns",
                "pihole_silent",
                "traffic",
                "profile",
                "coverage",
                "signals",
                "trimmed_destinations",
            },
        )
        identity = object_fields(
            dossier["identity"],
            {
                "device_id",
                "name",
                "address",
                "mac",
                "hostname",
                "vendor",
                "source",
                "observed_at",
            },
        )
        if identity["device_id"] != self.device_id:
            raise ValueError("Dossier payload belongs to another device")
        if _compact(json.loads(self.payload)) != self.payload:
            raise ValueError("Dossier payload is not in its compact encoding")
        if len(self.text().encode("utf-8")) > MAX_DOCUMENT_BYTES:
            raise ValueError("Dossier document exceeds its size bound")

    @classmethod
    def of(cls, write_id: str, dossier: DeviceDossier) -> DossierDocument:
        return cls(write_id, dossier.identity.device_id, encode_dossier(dossier))

    def text(self) -> str:
        return (
            '{"version":'
            + str(DOCUMENT_VERSION)
            + ',"write_id":'
            + json.dumps(self.write_id)
            + ',"device_id":'
            + json.dumps(self.device_id)
            + ',"dossier":'
            + self.payload
            + "}"
        )

    @classmethod
    def read(cls, text: str) -> DossierDocument:
        row = object_fields(
            parse_json(text, maximum_bytes=MAX_DOCUMENT_BYTES),
            {"version", "write_id", "device_id", "dossier"},
        )
        if type(row["version"]) is not int or row["version"] != DOCUMENT_VERSION:
            raise ValueError("Unsupported dossier document version")
        if not isinstance(row["write_id"], str) or not isinstance(
            row["device_id"], str
        ):
            raise ValueError("Invalid dossier document identity")
        document = cls(row["write_id"], row["device_id"], _compact(row["dossier"]))
        if document.text() != text:
            raise ValueError("Dossier text differs from its validated record")
        return document


def _compact(value: object) -> str:
    # Matches encode_dossier: default ASCII escaping, no spaces.
    return json.dumps(value, allow_nan=False, separators=(",", ":"))


@dataclass(frozen=True)
class DossierVersion:
    device_id: str
    revision: str
    resource: str
    ordinal: int
    created_at: str


@dataclass(frozen=True)
class RetainedDossier:
    """The citable revision for a dossier; ``changed`` is False when none was written."""

    device_id: str
    revision: str
    resource: str
    changed: bool


class DossierPort(Protocol):
    """Project-scoped source operations under the collector's service account."""

    async def versions(self, device_id: str) -> tuple[DossierVersion, ...]: ...
    async def read(self, revision: str) -> str: ...
    async def save(
        self, device_id: str, request_id: str, text: str, previous: str | None
    ) -> tuple[str, str]: ...


@dataclass(frozen=True)
class DossierIntent:
    request_id: str
    device_id: str
    expected_revision: str | None
    document: str
    phase: Literal["pending", "confirmed", "refused"]
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> DossierIntent:
        row = object_fields(
            value,
            {
                "request_id",
                "device_id",
                "expected_revision",
                "document",
                "phase",
                "revision",
            },
        )
        if not isinstance(row["document"], str):
            raise ValueError("Invalid dossier intent document")
        document = DossierDocument.read(row["document"])
        request, device = uuid(row["request_id"]), parse_device_id(row["device_id"])
        phase = row["phase"]
        if phase not in {"pending", "confirmed", "refused"} or (
            document.write_id,
            document.device_id,
        ) != (request, device):
            raise ValueError("Foreign dossier intent")
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        if (phase == "confirmed") != (revision is not None):
            raise ValueError("Dossier receipt must name a confirmed revision")
        return cls(
            request,
            device,
            uuid(row["expected_revision"])
            if row["expected_revision"] is not None
            else None,
            document.text(),
            phase,
            revision,
        )


class DossierIntents(Protocol):
    def all(self) -> tuple[DossierIntent, ...]: ...
    def save(self, intent: DossierIntent) -> None: ...


class FileDossierIntents:
    """Private bounded delivery receipts, bound to this project and collector."""

    def __init__(self, root: Path, project: str, collector: str):
        self.path = root / "device-dossier-intents.json"
        self.project, self.collector = project, collector

    def all(self) -> tuple[DossierIntent, ...]:
        if not self.path.exists():
            return ()
        row = object_fields(
            load_json(self.path), {"version", "project", "collector", "intents"}
        )
        if (
            type(row["version"]) is not int
            or row["version"] != 1
            or (row["project"], row["collector"]) != (self.project, self.collector)
        ):
            raise ValueError("Foreign device dossier journal")
        result = tuple(
            DossierIntent.decode(v)
            for v in items(row["intents"], MAX_PENDING + KEEP_SETTLED)
        )
        if len({v.request_id for v in result}) != len(result):
            raise ValueError("Duplicate dossier receipt")
        return result

    def save(self, intent: DossierIntent) -> None:
        DossierIntent.decode(asdict(intent))
        entries = {value.request_id: value for value in self.all()}
        old = entries.get(intent.request_id)
        if old is not None and (
            replace(old, phase=intent.phase, revision=intent.revision) != intent
            or old.phase != "pending"
            and old != intent
        ):
            raise ValueError("Dossier intent identity or settled receipt changed")
        if old is None and intent.phase != "pending":
            raise ValueError("A new dossier intent must begin pending")
        pending = sum(v.phase == "pending" for v in entries.values())
        if old is None and pending >= MAX_PENDING:
            raise ReconciliationRequired(
                "Too many unconfirmed dossier writes; reconcile them before retaining more"
            )
        entries[intent.request_id] = intent
        # Insertion order is chronological. Pending intents are never dropped.
        settled = [v for v in entries.values() if v.phase != "pending"]
        dropped = {v.request_id for v in settled[:-KEEP_SETTLED]}
        kept = [v for v in entries.values() if v.request_id not in dropped]
        encoded = (
            json.dumps(
                {
                    "version": 1,
                    "project": self.project,
                    "collector": self.collector,
                    "intents": [asdict(v) for v in kept],
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        if len(encoded.encode("utf-8")) > MAX_FILE_BYTES:
            raise ReconciliationRequired(
                "The dossier journal is full; reconcile unconfirmed writes first"
            )
        replace_private(self.path, encoded)


class DossierRetention(Protocol):
    """Retain a device's dossier and return the revision a report may cite."""

    async def retain(self, dossier: DeviceDossier) -> RetainedDossier: ...
    async def reconcile(self) -> None: ...


def _latest(versions: tuple[DossierVersion, ...]) -> DossierVersion | None:
    if not versions:
        return None
    if len({v.resource for v in versions}) != 1:
        raise ValueError("Ambiguous device dossier resource")
    if len({v.ordinal for v in versions}) != len(versions):
        raise ValueError("Device dossier history has duplicate ordinals")
    return max(versions, key=lambda v: v.ordinal)


class DeviceDossiers:
    """``DossierRetention`` over a ``DossierPort`` and a private intent journal."""

    def __init__(
        self,
        port: DossierPort,
        intents: DossierIntents,
        new_id: Callable[[], str] = lambda: str(uuid4()),
    ):
        self.port, self.intents, self.new_id = port, intents, new_id
        # One writer per process; the journal is the cross-restart fence.
        self.lock = asyncio.Lock()

    async def retain(self, dossier: DeviceDossier) -> RetainedDossier:
        """Upload, revise or reuse; raises before transport if the device is fenced.

        Reading the latest revision may be refused while its extraction is
        pending. That ``Refusal`` propagates: without the text the dossier
        cannot be compared, and writing blind could duplicate content.
        """
        device = parse_device_id(dossier.identity.device_id)
        payload = encode_dossier(dossier)
        async with self.lock:
            await self._settle(device)
            head = _latest(await self.port.versions(device))
            if head is not None:
                current = DossierDocument.read(await self.port.read(head.revision))
                if current.device_id != device:
                    raise ValueError("Retained dossier belongs to another device")
                if current.payload == payload:
                    return RetainedDossier(device, head.revision, head.resource, False)
            document = DossierDocument(self.new_id(), device, payload)
            expected = head.revision if head is not None else None
            intent = DossierIntent(
                document.write_id, device, expected, document.text(), "pending"
            )
            self.intents.save(intent)
            try:
                revision, resource = await self.port.save(
                    device, document.write_id, document.text(), expected
                )
                revision, resource = uuid(revision), uuid(resource)
                if head is not None and (
                    resource != head.resource or revision == head.revision
                ):
                    # The write may still have committed; the intent stays pending.
                    raise ValueError(
                        "Dossier admission returned a foreign resource or unchanged revision"
                    )
            except Refusal:
                self.intents.save(replace(intent, phase="refused"))
                raise
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self.intents.save(replace(intent, phase="refused"))
                raise
            self.intents.save(replace(intent, phase="confirmed", revision=revision))
            return RetainedDossier(device, revision, resource, True)

    async def reconcile(self) -> None:
        """Settle every pending write by reading; raises if any remains unconfirmed."""
        async with self.lock:
            devices = dict.fromkeys(
                v.device_id for v in self.intents.all() if v.phase == "pending"
            )
            unresolved = []
            for device in devices:
                try:
                    await self._settle(device)
                except ReconciliationRequired:
                    unresolved.append(device)
            if unresolved:
                raise ReconciliationRequired(
                    f"{len(unresolved)} device dossier writes remain unconfirmed; "
                    "they are not resent"
                )

    async def _settle(self, device: str) -> None:
        pending = [
            v
            for v in self.intents.all()
            if v.phase == "pending" and v.device_id == device
        ]
        if not pending:
            return
        versions = await self.port.versions(device)
        for intent in pending:
            # The write can only have produced a revision after its base.
            base = next(
                (v.ordinal for v in versions if v.revision == intent.expected_revision),
                0,
            )
            matches = []
            for version in versions:
                if version.ordinal <= base:
                    continue
                try:
                    source = await self.port.read(version.revision)
                except Refusal:
                    # Extraction may still be pending; absence is not non-delivery.
                    continue
                if source == intent.document:
                    matches.append(version)
            if len(matches) != 1:
                raise ReconciliationRequired(
                    "No unique retained dossier revision matches the unconfirmed "
                    "write; it remains fenced and is not resent"
                )
            self.intents.save(
                replace(intent, phase="confirmed", revision=matches[0].revision)
            )


class SdkDossierPort:
    """``DossierPort`` through the public SDK, in the configured project only."""

    def __init__(
        self, client: Client, config: Configuration, reader: RetainedSourceReader
    ):
        self.client, self.reader = client, reader
        self.scope = SelectionDtoScopeVariant3Dto(
            kind="project", project=config.project, include_global=False
        )

    async def versions(self, device_id: str) -> tuple[DossierVersion, ...]:
        name = source_name(device_id)
        result: list[DossierVersion] = []
        for page in range(MAX_PAGES):
            rows = (
                await self.client.request(
                    InformationListRequest(
                        scope=self.scope,
                        kind="source",
                        filter=InformationFilterDto(search=name),
                        limit=PAGE,
                        offset=page * PAGE,
                    )
                )
            ).require_payload()
            for row in rows:
                # Search is lexical; only the exact name is this device's source.
                if row.source_name != name:
                    continue
                if row.kind != "source":
                    raise ValueError("Foreign dossier catalogue entry")
                if (
                    isinstance(row.ordinal, bool)
                    or not isinstance(row.ordinal, (int, float))
                    or not math.isfinite(row.ordinal)
                    or row.ordinal % 1
                ):
                    raise ValueError("Dossier revision ordinal must be a whole number")
                result.append(
                    DossierVersion(
                        device_id,
                        uuid(row.id),
                        uuid(row.resource_id),
                        integer(int(row.ordinal), 1, 1000000),
                        timestamp(row.created_at),
                    )
                )
            if len(rows) < PAGE:
                if len({v.revision for v in result}) != len(result):
                    raise ValueError("Dossier catalogue changed while paging; refresh")
                return tuple(result)
        raise ValueError(
            "Dossier history exceeds the 1000-revision read bound; inspect the project catalogue"
        )

    async def read(self, revision: str) -> str:
        return await self.reader.read_source(uuid(revision))

    async def save(
        self, device_id: str, request_id: str, text: str, previous: str | None
    ) -> tuple[str, str]:
        request = (
            InformationUploadRequest(
                scope=self.scope,
                name=source_name(device_id),
                request_id=uuid(request_id),
                text=text,
            )
            if previous is None
            else InformationReviseRequest(
                scope=self.scope,
                revision=uuid(previous),
                request_id=uuid(request_id),
                text=text,
            )
        )
        result = (await self.client.request(request)).require_payload()
        return uuid(result.revision), uuid(result.resource)
