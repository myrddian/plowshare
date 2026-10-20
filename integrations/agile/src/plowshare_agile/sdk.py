"""Typed public SDK boundary. No HTTP fallback, internal imports or database access."""

from __future__ import annotations

import asyncio
import hashlib
from collections import OrderedDict
from dataclasses import dataclass, replace
from typing import Mapping, Protocol, TypeVar

from plowshare import Reply
from plowshare.contracts import (
    CurrentDocumentDto,
    DocumentConnectionDtoSelectionVariant1Dto,
    DocumentTargetDto,
    InformationAcquirePayloadDtoTypingVariant1Dto,
    InformationAcquirePayloadDtoTypingVariant2Dto,
    InformationContentBindingDto,
    InformationContentGetRequest,
    InformationContentReviseRequest,
    InformationDocumentsGetRequest,
    InformationDocumentsListRequest,
    InformationFilterDto,
    InformationInterchangeReadableRequest,
    InformationReadRequest,
    InformationReviseRequest,
    InformationTagsRequest,
    InformationTypesCapabilitiesRequest,
    InformationTypesGetRequest,
    InformationUploadRequest,
    Request,
    SchemaObjectEntry,
    SelectionDtoScopeVariant3Dto,
)

from .journal import Intent
from .model import (
    MAX_BODY,
    Configuration,
    Document,
    EpicFields,
    TicketFields,
    body_text,
    identity,
)

T = TypeVar("T")


class SdkConnection(Protocol):
    async def request(self, request: Request[T]) -> Reply[T]: ...


class BoardPort(Protocol):
    async def check(self, *, writing: bool = False) -> None: ...
    async def list(self, kind: str, tags: tuple[str, ...]) -> tuple[Document, ...]: ...
    async def validate_epics(self, resources: tuple[str, ...]) -> None: ...
    async def get(
        self,
        kind: str,
        resource: str,
        revision: str | None = None,
        *,
        body: bool = False,
    ) -> Document: ...
    async def submit(self, intent: Intent) -> MutationResult: ...
    async def editable(self, kind: str, resource: str) -> Document: ...


@dataclass(frozen=True)
class MutationResult:
    request_id: str
    code: str
    resource: str
    revision: str
    operation: str


def metadata(fields: TicketFields | EpicFields) -> Mapping[str, SchemaObjectEntry]:
    if isinstance(fields, EpicFields):
        return {"title": fields.title}
    return {
        "title": fields.title,
        "kind": fields.kind,
        "status": fields.status,
        "priority": fields.priority,
        "assignee": fields.assignee,
        "iteration": fields.iteration,
    }


class SdkBoardPort:
    # Only small, validated field DTOs are retained, never bodies, schemas, tags
    # or permission decisions. These limits also bound historical revisions.
    FIELD_ENTRIES = 120
    FIELD_BYTES = 1024 * 1024
    FIELD_LOADS = 128

    def __init__(self, client: SdkConnection, config: Configuration):
        self.client, self.config = client, config
        self.scope = SelectionDtoScopeVariant3Dto(
            kind="project", project=config.project, include_global=False
        )
        self._fields: OrderedDict[
            tuple[str, str, str], tuple[TicketFields | EpicFields, int]
        ] = OrderedDict()
        self._field_bytes = 0
        self._field_keys: dict[tuple[str, str], tuple[str, str, str]] = {}
        self._field_loads: dict[
            tuple[str, str, str], asyncio.Task[TicketFields | EpicFields]
        ] = {}

    async def check(self, *, writing: bool = False) -> None:
        capabilities = (
            await self.client.request(
                InformationTypesCapabilitiesRequest(
                    scope=self.scope, view="content-types-v1"
                )
            )
        ).require_payload()
        if writing and not capabilities.typed_writes:
            raise ValueError("The server has disabled typed document writes")
        # Actual navigation calls enforce navigation availability. Fetching its
        # capabilities here discarded the projection on every write/probe.

    def _entry(self, entry: CurrentDocumentDto) -> None:
        if (
            not isinstance(entry.scope, SelectionDtoScopeVariant3Dto)
            or entry.scope.project != self.config.project
            or entry.scope.include_global is True
        ):
            raise ValueError("Foreign document scope")
        identity(entry.resource)
        identity(entry.revision)

    async def _read_fields(
        self, kind: str, entry: CurrentDocumentDto
    ) -> TicketFields | EpicFields:
        content = (
            await self.client.request(
                InformationContentGetRequest(
                    revision=identity(entry.revision),
                    scope=self.scope,
                    view="content-types-v1",
                )
            )
        ).require_payload()
        binding = content.content_type
        if content.revision != entry.revision or not isinstance(
            binding, InformationContentBindingDto
        ):
            raise ValueError("Document does not have the configured Application type")
        fields = self._decode_fields(kind, binding)
        key = (kind, entry.resource, entry.revision)
        # Conservative accounting includes key/DTO/container overhead and up to
        # four-byte Python string storage; schema definitions are not retained.
        weight = 4096 + 4 * sum(
            len(value.encode("utf-8"))
            for value in (*key, *metadata(fields).values())
            if isinstance(value, str)
        )
        # Retain at most one revision per resource. Otherwise one edit on a
        # full board displaces an unchanged row and the next sequential scan
        # can churn the whole LRU. The secondary index keeps removal O(1).
        resource_key = (kind, entry.resource)
        previous_key = self._field_keys.pop(resource_key, None)
        if previous_key is not None:
            self._field_bytes -= self._fields.pop(previous_key)[1]
        if weight <= self.FIELD_BYTES:
            self._fields[key] = (fields, weight)
            self._field_keys[resource_key] = key
            self._field_bytes += weight
            while (
                len(self._fields) > self.FIELD_ENTRIES
                or self._field_bytes > self.FIELD_BYTES
            ):
                removed_key, (_, removed) = self._fields.popitem(last=False)
                self._field_keys.pop(removed_key[:2])
                self._field_bytes -= removed
        return fields

    def _decode_fields(
        self, kind: str, binding: InformationContentBindingDto
    ) -> TicketFields | EpicFields:
        if (
            binding.contract.type_ != self.config.type_id(kind)
            or binding.contract.slug != "agile-" + kind
        ):
            raise ValueError("Document does not have the configured Application type")
        return (
            TicketFields.decode(binding.metadata)
            if kind == "ticket"
            else EpicFields.decode(binding.metadata)
        )

    async def _listed_fields(
        self, kind: str, entry: CurrentDocumentDto
    ) -> TicketFields | EpicFields:
        # Reuse requires a NEW authorized documents.list page filtered by this
        # content type. That operation also excludes redacted bindings. A cached
        # revision is never an access lease, nor a substitute for get's live
        # binding read. The cache belongs to this connection/configuration only;
        # reconnect constructs another port. Tags/owner always come from the page.
        key = (kind, entry.resource, entry.revision)
        cached = self._fields.get(key)
        if cached is not None:
            self._fields.move_to_end(key)
            return cached[0]
        load = self._field_loads.get(key)
        if load is None:
            if len(self._field_loads) >= self.FIELD_LOADS:
                raise ValueError("Too many metadata reads; retry the read later")
            load = asyncio.create_task(self._read_fields(kind, entry))
            self._field_loads[key] = load

            def finished(task: asyncio.Task[TicketFields | EpicFields]) -> None:
                self._field_loads.pop(key, None)
                # Observe failed loads even if all HTTP waiters disconnected.
                # Active waiters still receive the original exception.
                if not task.cancelled():
                    task.exception()

            load.add_done_callback(finished)
        return await asyncio.shield(load)

    async def _document(
        self, kind: str, entry: CurrentDocumentDto, *, listed: bool = False
    ) -> Document:
        self._entry(entry)
        fields = (
            await self._listed_fields(kind, entry)
            if listed
            else await self._read_fields(kind, entry)
        )
        return Document(
            identity(entry.resource),
            identity(entry.revision),
            entry.owner,
            entry.tags,
            fields,
        )

    async def _entries(
        self, kind: str, tags: tuple[str, ...]
    ) -> tuple[CurrentDocumentDto, ...]:
        maximum = 100 if kind == "ticket" else 20
        entries: list[CurrentDocumentDto] = []
        # Fetch one more than capacity to prove completeness, including the exact
        # full-page boundary. Never count historical revisions as board entries.
        while True:
            limit = min(100, maximum + 1 - len(entries))
            page = (
                await self.client.request(
                    InformationDocumentsListRequest(
                        scope=self.scope,
                        view="content-types-v1",
                        filter=InformationFilterDto(
                            content_type=self.config.type_id(kind), tags=tags
                        ),
                        limit=limit,
                        offset=len(entries),
                    )
                )
            ).require_payload()
            if (
                page.offset != len(entries)
                or page.limit != limit
                or len(page.documents) > limit
            ):
                raise ValueError("Invalid document page boundary")
            entries.extend(page.documents)
            if len(entries) > maximum:
                raise ValueError(
                    f"Board exceeds the {maximum}-{kind} limit; narrow the selection"
                )
            if len(page.documents) < limit:
                break
        if len({v.resource for v in entries}) != len(entries):
            raise ValueError("Document selection changed while paging; refresh")
        for entry in entries:
            self._entry(entry)
        return tuple(entries)

    async def list(self, kind: str, tags: tuple[str, ...]) -> tuple[Document, ...]:
        entries = await self._entries(kind, tags)
        return tuple(
            [await self._document(kind, entry, listed=True) for entry in entries]
        )

    async def validate_epics(self, resources: tuple[str, ...]) -> None:
        """Validate one selected set without per-epic current-head requests."""
        requested = tuple(identity(resource) for resource in resources)
        if len(set(requested)) != len(requested):
            raise ValueError("Duplicate epic selection")
        if not requested:
            return
        entries = {entry.resource: entry for entry in await self._entries("epic", ())}
        if any(resource not in entries for resource in requested):
            raise ValueError("An epic is unavailable")
        for resource in requested:
            await self._document("epic", entries[resource], listed=True)

    async def _select(
        self, resource: str, revision: str | None = None
    ) -> CurrentDocumentDto:
        target = (
            DocumentTargetDto(resource=identity(resource))
            if revision is None
            else DocumentTargetDto(
                resource=identity(resource), revision=identity(revision)
            )
        )
        selected = (
            await self.client.request(
                InformationDocumentsGetRequest(
                    scope=self.scope, view="content-types-v1", target=target
                )
            )
        ).require_payload()
        if not isinstance(selected, DocumentConnectionDtoSelectionVariant1Dto):
            raise ValueError("Document is unavailable")
        entry = selected.document
        if (
            entry.resource != resource
            or revision is not None
            and entry.revision != revision
        ):
            raise ValueError("Document selection returned a different identity")
        self._entry(entry)
        return entry

    async def get(
        self,
        kind: str,
        resource: str,
        revision: str | None = None,
        *,
        body: bool = False,
    ) -> Document:
        entry = await self._select(resource, revision)
        document = await self._document(kind, entry)
        if not body:
            return document
        parts: list[str] = []
        offset = 0
        total: float | None = None
        while True:
            window = (
                await self.client.request(
                    InformationReadRequest(
                        scope=self.scope,
                        view="content-types-v1",
                        revision=document.revision,
                        offset=offset,
                        limit=4096,
                    )
                )
            ).require_payload()
            if (
                window.revision != document.revision
                or window.start != offset
                or window.total > MAX_BODY
                or window.total < 0
                or window.end > window.total
                or window.end < window.start
                or window.end - window.start
                != len(window.text.encode("utf-16-le")) // 2
                or total is not None
                and total != window.total
            ):
                raise ValueError("Invalid or oversized document text window")
            parts.append(window.text)
            total = window.total
            if window.end == total:
                return replace(document, body="".join(parts))
            if window.end <= offset or not window.end.is_integer():
                raise ValueError("Document text read made no progress")
            offset = int(window.end)

    async def editable(self, kind: str, resource: str) -> Document:
        """Return original Markdown for editing; extracted text is never source bytes."""
        entry = await self._select(resource)
        source = (
            await self.client.request(
                InformationInterchangeReadableRequest(
                    scope=self.scope,
                    view="content-types-v1",
                    revision=entry.revision,
                )
            )
        ).require_payload()
        if (
            source.representation != "original_markdown"
            or source.sidecar.resource != entry.resource
            or source.sidecar.revision != entry.revision
            or hashlib.sha256(source.markdown.encode("utf-8")).hexdigest()
            != source.text_hash
        ):
            raise ValueError(
                "Original Markdown is unavailable; extraction cannot be used as editable source"
            )
        binding = source.sidecar.content_type
        if not isinstance(binding, InformationContentBindingDto):
            raise ValueError("Original Markdown has no typed binding")
        # The public readable projection already includes this exact revision's
        # binding; do not fetch content.get merely to discard duplicate fields.
        return Document(
            entry.resource,
            entry.revision,
            entry.owner,
            entry.tags,
            self._decode_fields(kind, binding),
            body_text(source.markdown),
        )

    async def submit(self, intent: Intent) -> MutationResult:
        if intent.action == "create":
            contract = (
                await self.client.request(
                    InformationTypesGetRequest(
                        scope=self.scope,
                        view="content-types-v1",
                        published_only=True,
                        version=self.config.version_id(intent.kind),
                    )
                )
            ).require_payload()
            if (
                contract.type_ != self.config.type_id(intent.kind)
                or contract.version != self.config.version_id(intent.kind)
                or contract.slug != "agile-" + intent.kind
            ):
                raise ValueError(
                    "Configured schema identity differs from the Application"
                )
            if intent.body is None:
                raise ValueError("Missing create body")
            reply = await self.client.request(
                InformationUploadRequest(
                    scope=self.scope,
                    view="content-types-v1",
                    request_id=intent.request_id,
                    name=f"agile-{intent.kind}/{intent.request_id}.md",
                    text=intent.body,
                    typing=InformationAcquirePayloadDtoTypingVariant1Dto(
                        mode="set",
                        version=contract.version,
                        metadata=metadata(intent.fields),
                    ),
                )
            )
            admitted = reply.require_payload()
            return MutationResult(
                intent.request_id,
                reply.code,
                identity(admitted.resource),
                identity(admitted.revision),
                "information.upload",
            )
        if intent.expected_revision is None or intent.resource is None:
            raise ValueError("Missing update identity")
        if intent.action == "body":
            if intent.body is None:
                raise ValueError("Missing update body")
            reply = await self.client.request(
                InformationReviseRequest(
                    scope=self.scope,
                    view="content-types-v1",
                    request_id=intent.request_id,
                    revision=intent.expected_revision,
                    expected_revision=intent.expected_revision,
                    text=intent.body,
                    typing=InformationAcquirePayloadDtoTypingVariant2Dto(
                        mode="inherit", metadata=metadata(intent.fields)
                    ),
                )
            )
            admitted = reply.require_payload()
            if admitted.resource != intent.resource:
                raise ValueError("Write returned a different resource")
            return MutationResult(
                intent.request_id,
                reply.code,
                identity(admitted.resource),
                identity(admitted.revision),
                "information.revise",
            )
        if intent.action == "revise":
            reply = await self.client.request(
                InformationContentReviseRequest(
                    scope=self.scope,
                    view="content-types-v1",
                    request_id=intent.request_id,
                    expected_revision=intent.expected_revision,
                    typing=InformationAcquirePayloadDtoTypingVariant2Dto(
                        mode="inherit", metadata=metadata(intent.fields)
                    ),
                )
            )
            admitted = reply.require_payload()
            if admitted.resource != intent.resource:
                raise ValueError("Write returned a different resource")
            return MutationResult(
                intent.request_id,
                reply.code,
                identity(admitted.resource),
                identity(admitted.revision),
                "information.content.revise",
            )
        changed = await self.client.request(
            InformationTagsRequest(
                scope=self.scope,
                view="content-types-v1",
                request_id=intent.request_id,
                revision=intent.expected_revision,
                tags=intent.tags,
            )
        )
        changed.require_payload()
        return MutationResult(
            intent.request_id,
            changed.code,
            intent.resource,
            intent.expected_revision,
            "information.tags",
        )
