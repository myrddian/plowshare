"""Project issue documents and private, exact delivery receipts.

Plowshare owns knowledge and history. Local state only fences ambiguous writes;
read-only reconciliation requires one exact retained document including write UUID.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal, Protocol

from plowshare import Delivery, Refusal, TransportError

from .contracts import (
    MAX_FILE_BYTES,
    items,
    load_json,
    object_fields,
    utc_now,
    uuid,
)
from .privacy_issues import MAX_TEXT, PREFIX, IssueFields, PrivacyIssue, narrative
from .private_files import replace_private
from .worker import ReconciliationRequired


def source_name(identity: str) -> str:
    return PREFIX + uuid(identity) + ".md"


@dataclass(frozen=True)
class IssueVersion:
    issue_id: str
    revision: str
    resource: str
    ordinal: int
    author: str
    created_at: str


@dataclass(frozen=True)
class IssueView:
    issue: PrivacyIssue
    revision: str
    versions: tuple[IssueVersion, ...]


class IssuePort(Protocol):
    """Every read/write uses the configured project and authenticated service account."""

    async def versions(self) -> tuple[IssueVersion, ...]: ...
    async def read(self, revision: str) -> str: ...
    async def save(
        self, issue: PrivacyIssue, previous: str | None
    ) -> tuple[str, str]: ...


@dataclass(frozen=True)
class IssueIntent:
    request_id: str
    issue_id: str
    expected_revision: str | None
    document: str
    phase: Literal["pending", "confirmed", "refused"]
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> IssueIntent:
        row = object_fields(
            value,
            {
                "request_id",
                "issue_id",
                "expected_revision",
                "document",
                "phase",
                "revision",
            },
        )
        issue = PrivacyIssue.read(narrative(row["document"], MAX_TEXT))
        request, identity = uuid(row["request_id"]), uuid(row["issue_id"])
        phase = row["phase"]
        if phase not in {"pending", "confirmed", "refused"} or (
            issue.write_id,
            issue.issue_id,
        ) != (request, identity):
            raise ValueError("Foreign issue intent")
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        if (phase == "confirmed") != (revision is not None):
            raise ValueError("Issue receipt must name a confirmed revision")
        return cls(
            request,
            identity,
            uuid(row["expected_revision"])
            if row["expected_revision"] is not None
            else None,
            issue.markdown(),
            phase,
            revision,
        )


class IssueIntents(Protocol):
    def all(self) -> tuple[IssueIntent, ...]: ...
    def save(self, intent: IssueIntent) -> None: ...


class FileIssueIntents:
    """Private bounded delivery receipts, bound to this project and collector."""

    def __init__(self, root: Path, project: str, collector: str):
        self.path = root / "privacy-issue-intents.json"
        self.project, self.collector = project, collector

    def all(self) -> tuple[IssueIntent, ...]:
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
            raise ValueError("Foreign privacy issue journal")
        result = tuple(IssueIntent.decode(v) for v in items(row["intents"], 128))
        if len({v.request_id for v in result}) != len(result):
            raise ValueError("Duplicate issue receipt")
        return result

    def save(self, intent: IssueIntent) -> None:
        IssueIntent.decode(asdict(intent))
        entries = {value.request_id: value for value in self.all()}
        old = entries.get(intent.request_id)
        if old is not None and (
            replace(old, phase=intent.phase, revision=intent.revision) != intent
            or old.phase != "pending"
            and old != intent
        ):
            raise ValueError("Issue intent identity or settled receipt changed")
        if old is None and intent.phase != "pending":
            raise ValueError("A new issue intent must begin pending")
        if old is None and len(entries) >= 128:
            raise ReconciliationRequired(
                "Archive settled issue receipts before saving more issues"
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
        # Reserve the extra UUID/phase bytes before transport so a confirmed
        # receipt can still be persisted when the private journal approaches capacity.
        reserve = 64 * sum(value.phase == "pending" for value in entries.values())
        if len(encoded.encode("utf-8")) + reserve > MAX_FILE_BYTES:
            raise ReconciliationRequired(
                "Archive settled issue receipts before admitting another write"
            )
        replace_private(self.path, encoded)


class IssueManagement(Protocol):
    """Operator document management; callers receive records, never persistence handles."""

    def writes(self) -> tuple[IssueIntent, ...]: ...
    async def list(self) -> tuple[IssueView, ...]: ...
    async def read(self, identity: str, revision: str) -> str: ...
    async def save(
        self, identity: str, request: str, expected: str | None, fields: IssueFields
    ) -> IssueIntent: ...
    async def reconcile(self) -> None: ...


class PrivacyIssues:
    def __init__(
        self,
        port: IssuePort,
        intents: IssueIntents,
    ):
        self.port, self.intents = port, intents
        self.lock = asyncio.Lock()

    def writes(self) -> tuple[IssueIntent, ...]:
        return self.intents.all()

    async def list(self) -> tuple[IssueView, ...]:
        """Select the highest visible ordinal per resource within the project issue catalogue."""
        versions = await self.port.versions()
        latest: dict[str, IssueVersion] = {}
        for version in versions:
            previous = latest.get(version.issue_id)
            if previous and previous.resource != version.resource:
                raise ValueError("Ambiguous privacy issue resource")
            if previous is None or previous.ordinal < version.ordinal:
                latest[version.issue_id] = version
        if len(latest) > 32:
            raise ValueError("Privacy issue listing exceeds 32 issues")
        result = []
        for identity, head in latest.items():
            issue = PrivacyIssue.read(await self.port.read(head.revision))
            if issue.issue_id != identity:
                raise ValueError("Issue document has a foreign identity")
            result.append(
                IssueView(
                    issue,
                    head.revision,
                    tuple(v for v in versions if v.issue_id == identity),
                )
            )
        return tuple(result)

    async def read(self, identity: str, revision: str) -> str:
        # Only revisions in this scoped issue catalogue may enter the dashboard.
        if not any(
            v.issue_id == uuid(identity) and v.revision == uuid(revision)
            for v in await self.port.versions()
        ):
            raise ValueError("Choose a retained privacy issue revision")
        source = await self.port.read(revision)
        if PrivacyIssue.read(source).issue_id != identity:
            raise ValueError("Foreign issue revision")
        return source

    async def save(
        self, identity: str, request: str, expected: str | None, fields: IssueFields
    ) -> IssueIntent:
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
                    prior.issue_id != identity
                    or prior.expected_revision != expected
                    or PrivacyIssue.read(prior.document).fields != fields
                ):
                    raise ValueError(
                        "Request identity already belongs to another issue change"
                    )
                return prior
            if any(v.phase == "pending" for v in self.intents.all()):
                raise ReconciliationRequired(
                    "Check retained issue writes first; an unknown effect will not be resent"
                )
            issues = await self.list()
            current = next((v for v in issues if v.issue.issue_id == identity), None)
            if expected != (current.revision if current else None):
                raise ReconciliationRequired(
                    "Privacy issue changed; reload its retained revision before editing"
                )
            if current is None and len(issues) >= 32:
                raise ValueError("Privacy issue capacity reached")
            issue = PrivacyIssue(1, identity, request, utc_now(), fields)
            intent = IssueIntent(
                request, identity, expected, issue.markdown(), "pending"
            )
            self.intents.save(intent)
            try:
                revision, resource = await self.port.save(issue, expected)
                uuid(resource)
                if current is not None and (
                    resource != current.versions[0].resource or revision == expected
                ):
                    raise ValueError(
                        "Issue admission returned a foreign resource or unchanged revision"
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
                    if version.issue_id == intent.issue_id:
                        try:
                            source = await self.port.read(version.revision)
                        except Refusal:
                            # Extraction may still be pending; absence is not non-delivery.
                            continue
                        if source == intent.document:
                            matches.append(version)
                if len(matches) != 1:
                    raise ReconciliationRequired(
                        "No unique positive issue revision matches the retained write; it remains fenced"
                    )
                self.intents.save(
                    replace(intent, phase="confirmed", revision=matches[0].revision)
                )
