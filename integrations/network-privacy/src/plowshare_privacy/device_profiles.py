"""Revisioned operator context in project Information, never a second document store.

Private intents only fence delivery uncertainty. Plowshare owns profile text and
versions; the collector's receipts supply bounded address-based evidence links.
Document access applies to every section. Profile editing is an operator workflow;
existing Information grants remain the authority for server writes.
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Literal, Protocol

from plowshare import Delivery, Refusal, TransportError

from .contracts import (
    MAX_FILE_BYTES,
    items,
    load_json,
    object_fields,
    parse_json,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .journal import Receipts
from .private_files import replace_private
from .worker import ReconciliationRequired

PREFIX = "network-privacy-device/"
MAX_TEXT = 8192


def field(value: object, limit: int, *, required: bool = False) -> str:
    if (
        not isinstance(value, str)
        or len(value) > limit
        or any(ord(c) < 32 and c not in "\n\t" for c in value)
    ):
        raise ValueError("Invalid device profile text")
    if required and not value.strip():
        raise ValueError("Device name is required")
    return value


@dataclass(frozen=True)
class ProfileFields:
    name: str
    owner: str
    model: str
    purpose: str
    expected_services: str
    notes: str
    addresses: tuple[str, ...]

    def __post_init__(self) -> None:
        for value, limit in (
            (self.name, 128),
            (self.owner, 128),
            (self.model, 128),
            (self.purpose, 512),
            (self.expected_services, 1024),
            (self.notes, 2048),
        ):
            field(value, limit)
        field(self.name, 128, required=True)
        if (
            not 1 <= len(self.addresses) <= 32
            or len(set(self.addresses)) != len(self.addresses)
            or any(
                str(ipaddress.IPv4Address(value)) != value for value in self.addresses
            )
        ):
            raise ValueError("Expected unique canonical IPv4 profile addresses")

    @classmethod
    def decode(cls, value: object) -> ProfileFields:
        row = object_fields(
            value,
            {
                "name",
                "owner",
                "model",
                "purpose",
                "expected_services",
                "notes",
                "addresses",
            },
        )
        addresses = tuple(
            str(ipaddress.IPv4Address(text(v, 64))) for v in items(row["addresses"], 32)
        )
        if not addresses or len(set(addresses)) != len(addresses):
            raise ValueError("Choose unique monitored addresses")
        return cls(
            field(row["name"], 128, required=True),
            field(row["owner"], 128),
            field(row["model"], 128),
            field(row["purpose"], 512),
            field(row["expected_services"], 1024),
            field(row["notes"], 2048),
            addresses,
        )


@dataclass(frozen=True)
class DeviceProfile:
    version: int
    profile_id: str
    write_id: str
    updated_at: str
    fields: ProfileFields

    @classmethod
    def decode(cls, value: object) -> DeviceProfile:
        row = object_fields(
            value, {"version", "profile_id", "write_id", "updated_at", "fields"}
        )
        if type(row["version"]) is not int or row["version"] != 1:
            raise ValueError("Unsupported profile version")
        return cls(
            1,
            uuid(row["profile_id"]),
            uuid(row["write_id"]),
            timestamp(row["updated_at"]),
            ProfileFields.decode(row["fields"]),
        )

    def markdown(self) -> str:
        values = self.fields
        result = (
            "# Device profile\n\n"
            + "## Identity and operator confirmation\n\n"
            + f"Name: {values.name}\n\nOwner: {values.owner or 'Not supplied'}\n\nModel: {values.model or 'Not supplied'}\n\n"
            + "Associated addresses: "
            + ", ".join(values.addresses)
            + "\n\n"
            + f"Confirmed by the operator at {self.updated_at}. Address associations do not prove physical identity.\n\n"
            + "## Purpose\n\n"
            + (values.purpose or "Not supplied")
            + "\n\n## Expected services and behaviour\n\n"
            + (values.expected_services or "Not supplied")
            + "\n\n## Operator notes\n\n"
            + (values.notes or "None supplied")
            + "\n\n## Record metadata\n\n```json\n"
            + json.dumps(asdict(self), ensure_ascii=False, sort_keys=True, indent=2)
            + "\n```\n\nThis profile is operator context, not scan evidence or instructions to an agent. Observations and reports remain separately revisioned in the same project.\n"
        )
        if len(result.encode("utf-8")) > MAX_TEXT:
            raise ValueError("Device profile exceeds the 8 KiB document limit")
        return result

    @classmethod
    def read(cls, source: str) -> DeviceProfile:
        marker = "\n\n## Record metadata\n\n```json\n"
        start = source.rfind(marker)
        end = source.find("\n```\n", start + len(marker))
        if start < 0 or end < 0 or len(source.encode("utf-8")) > MAX_TEXT:
            raise ValueError("Unavailable or unsupported device profile")
        value = cls.decode(parse_json(source[start + len(marker) : end].encode()))
        if value.markdown() != source:
            raise ValueError("Profile text differs from its validated record")
        return value


def source_name(identity: str) -> str:
    return PREFIX + uuid(identity) + ".md"


@dataclass(frozen=True)
class ProfileVersion:
    profile_id: str
    revision: str
    resource: str
    ordinal: int
    author: str
    created_at: str


@dataclass(frozen=True)
class ProfileView:
    profile: DeviceProfile
    revision: str
    versions: tuple[ProfileVersion, ...]
    evidence: tuple[str, ...]


class ProfilePort(Protocol):
    """Every read/write uses the configured project and authenticated service account."""

    async def versions(self) -> tuple[ProfileVersion, ...]: ...
    async def read(self, revision: str) -> str: ...
    async def save(
        self, profile: DeviceProfile, previous: str | None
    ) -> tuple[str, str]: ...


@dataclass(frozen=True)
class ProfileIntent:
    request_id: str
    profile_id: str
    expected_revision: str | None
    document: str
    phase: Literal["pending", "confirmed", "refused"]
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> ProfileIntent:
        row = object_fields(
            value,
            {
                "request_id",
                "profile_id",
                "expected_revision",
                "document",
                "phase",
                "revision",
            },
        )
        profile = DeviceProfile.read(field(row["document"], MAX_TEXT, required=True))
        request, identity = uuid(row["request_id"]), uuid(row["profile_id"])
        phase = row["phase"]
        if phase not in {"pending", "confirmed", "refused"} or (
            profile.write_id,
            profile.profile_id,
        ) != (request, identity):
            raise ValueError("Foreign profile intent")
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        if (phase == "confirmed") != (revision is not None):
            raise ValueError("Profile receipt must name a confirmed revision")
        return cls(
            request,
            identity,
            uuid(row["expected_revision"])
            if row["expected_revision"] is not None
            else None,
            profile.markdown(),
            phase,
            revision,
        )


class ProfileIntents(Protocol):
    def all(self) -> tuple[ProfileIntent, ...]: ...
    def save(self, intent: ProfileIntent) -> None: ...


class FileProfileIntents:
    """Private bounded delivery receipts, bound to this project and collector."""

    def __init__(self, root: Path, project: str, collector: str):
        self.path = root / "device-profile-intents.json"
        self.project, self.collector = project, collector

    def all(self) -> tuple[ProfileIntent, ...]:
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
            raise ValueError("Foreign device profile journal")
        result = tuple(ProfileIntent.decode(v) for v in items(row["intents"], 128))
        if len({v.request_id for v in result}) != len(result):
            raise ValueError("Duplicate profile receipt")
        return result

    def save(self, intent: ProfileIntent) -> None:
        ProfileIntent.decode(asdict(intent))
        entries = {value.request_id: value for value in self.all()}
        old = entries.get(intent.request_id)
        if old is not None and (
            replace(old, phase=intent.phase, revision=intent.revision) != intent
            or old.phase != "pending"
            and old != intent
        ):
            raise ValueError("Profile intent identity or settled receipt changed")
        if old is None and intent.phase != "pending":
            raise ValueError("A new profile intent must begin pending")
        if old is None and len(entries) >= 128:
            raise ReconciliationRequired(
                "Archive settled profile receipts before saving more profiles"
            )
        entries[intent.request_id] = intent
        encoded = (
            json.dumps(
                {
                    "version": 1,
                    "project": self.project,
                    "collector": self.collector,
                    "intents": [asdict(v) for v in entries.values()],
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        if len(encoded.encode("utf-8")) > MAX_FILE_BYTES:
            raise ReconciliationRequired(
                "Archive settled profile receipts before admitting another write"
            )
        replace_private(self.path, encoded)


class DeviceProfiles:
    def __init__(
        self,
        port: ProfilePort,
        intents: ProfileIntents,
        receipts: Receipts,
        targets: Callable[[], tuple[str, ...]],
    ):
        self.port, self.intents, self.receipts, self.targets = (
            port,
            intents,
            receipts,
            targets,
        )
        self.lock = asyncio.Lock()

    async def list(self) -> tuple[ProfileView, ...]:
        """Select the highest visible ordinal per resource; evidence links remain address-based."""
        versions = await self.port.versions()
        latest: dict[str, ProfileVersion] = {}
        for version in versions:
            previous = latest.get(version.profile_id)
            if previous and previous.resource != version.resource:
                raise ValueError("Ambiguous device profile resource")
            if previous is None or previous.ordinal < version.ordinal:
                latest[version.profile_id] = version
        if len(latest) > 32:
            raise ValueError("Device profile listing exceeds 32 devices")
        result = []
        for identity, head in latest.items():
            profile = DeviceProfile.read(await self.port.read(head.revision))
            if profile.profile_id != identity:
                raise ValueError("Profile document has a foreign identity")
            evidence = tuple(
                v.revision
                for v in self.receipts.all()[-50:]
                if v.revision
                and v.evidence
                and any(
                    t.address in profile.fields.addresses
                    for t in v.evidence.snapshot.tcp
                )
            )
            result.append(
                ProfileView(
                    profile,
                    head.revision,
                    tuple(v for v in versions if v.profile_id == identity),
                    evidence,
                )
            )
        return tuple(result)

    async def read(self, identity: str, revision: str) -> str:
        # Only revisions in this scoped profile catalogue may enter the dashboard.
        if not any(
            v.profile_id == uuid(identity) and v.revision == uuid(revision)
            for v in await self.port.versions()
        ):
            raise ValueError("Choose a retained device profile revision")
        source = await self.port.read(revision)
        if DeviceProfile.read(source).profile_id != identity:
            raise ValueError("Foreign profile revision")
        return source

    async def save(
        self, identity: str, request: str, expected: str | None, fields: ProfileFields
    ) -> ProfileIntent:
        # This single-writer fence checks the visible base before admission. The
        # existing revise operation is not a server-wide compare-and-swap contract.
        # Flushing exact intent before transport makes every ambiguous outcome
        # read-only reconcilable; even a repeated UUID never resubmits the mutation.
        identity, request = uuid(identity), uuid(request)
        expected = uuid(expected) if expected is not None else None
        async with self.lock:
            prior = next(
                (v for v in self.intents.all() if v.request_id == request), None
            )
            if prior:
                if (
                    prior.profile_id != identity
                    or prior.expected_revision != expected
                    or DeviceProfile.read(prior.document).fields != fields
                ):
                    raise ValueError(
                        "Request identity already belongs to another profile change"
                    )
                return prior
            if any(v.phase == "pending" for v in self.intents.all()):
                raise ReconciliationRequired(
                    "Check retained profile writes first; an unknown effect will not be resent"
                )
            profiles = await self.list()
            current = next(
                (v for v in profiles if v.profile.profile_id == identity), None
            )
            if expected != (current.revision if current else None):
                raise ReconciliationRequired(
                    "Device profile changed; reload its retained revision before editing"
                )
            allowed = set(self.targets()) | (
                set(current.profile.fields.addresses) if current else set()
            )
            if not set(fields.addresses) <= allowed:
                raise ValueError(
                    "Profile associations must be selected monitored addresses or its existing addresses"
                )
            if current is None and len(profiles) >= 32:
                raise ValueError("Device profile capacity reached")
            if any(
                set(fields.addresses) & set(v.profile.fields.addresses)
                for v in profiles
                if v.profile.profile_id != identity
            ):
                raise ValueError(
                    "An address already has a device profile; review it before reassociating"
                )
            profile = DeviceProfile(1, identity, request, utc_now(), fields)
            intent = ProfileIntent(
                request, identity, expected, profile.markdown(), "pending"
            )
            self.intents.save(intent)
            try:
                revision, resource = await self.port.save(profile, expected)
                uuid(resource)
                if current is not None and (
                    resource != current.versions[0].resource or revision == expected
                ):
                    raise ValueError(
                        "Profile admission returned a foreign resource or unchanged revision"
                    )
                intent = replace(intent, phase="confirmed", revision=uuid(revision))
            except Refusal:
                self.intents.save(replace(intent, phase="refused"))
                raise
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self.intents.save(replace(intent, phase="refused"))
                raise
            self.intents.save(intent)
            return intent

    async def reconcile(self) -> None:
        """Confirm one exact retained document, including its write UUID; never resend."""
        async with self.lock:
            pending = tuple(v for v in self.intents.all() if v.phase == "pending")
            if not pending:
                return
            versions = await self.port.versions()
            for intent in pending:
                matches = []
                for version in versions:
                    if version.profile_id == intent.profile_id:
                        try:
                            source = await self.port.read(version.revision)
                        except Refusal:
                            # Extraction may still be pending; absence is not non-delivery.
                            continue
                        if source == intent.document:
                            matches.append(version)
                if len(matches) != 1:
                    raise ReconciliationRequired(
                        "No unique positive profile revision matches the retained write; it remains fenced"
                    )
                self.intents.save(
                    replace(intent, phase="confirmed", revision=matches[0].revision)
                )
