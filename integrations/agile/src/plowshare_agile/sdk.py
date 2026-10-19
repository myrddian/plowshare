"""Typed public SDK boundary. No HTTP fallback, internal imports or database access."""

from __future__ import annotations

import hashlib
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
    InformationNavigationCapabilitiesRequest,
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
    def __init__(self, client: SdkConnection, config: Configuration):
        self.client, self.config = client, config
        self.scope = SelectionDtoScopeVariant3Dto(
            kind="project", project=config.project, include_global=False
        )

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
        (
            await self.client.request(
                InformationNavigationCapabilitiesRequest(
                    scope=self.scope, view="content-types-v1"
                )
            )
        ).require_payload()

    async def _document(self, kind: str, entry: CurrentDocumentDto) -> Document:
        if (
            not isinstance(entry.scope, SelectionDtoScopeVariant3Dto)
            or entry.scope.project != self.config.project
            or entry.scope.include_global is True
        ):
            raise ValueError("Foreign document scope")
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
        if (
            content.revision != entry.revision
            or not isinstance(binding, InformationContentBindingDto)
            or binding.contract.type_ != self.config.type_id(kind)
            or binding.contract.slug != "agile-" + kind
        ):
            raise ValueError("Document does not have the configured Application type")
        fields = (
            TicketFields.decode(binding.metadata)
            if kind == "ticket"
            else EpicFields.decode(binding.metadata)
        )
        return Document(
            identity(entry.resource),
            identity(entry.revision),
            entry.owner,
            entry.tags,
            fields,
        )

    async def list(self, kind: str, tags: tuple[str, ...]) -> tuple[Document, ...]:
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
        return tuple([await self._document(kind, entry) for entry in entries])

    async def get(
        self,
        kind: str,
        resource: str,
        revision: str | None = None,
        *,
        body: bool = False,
    ) -> Document:
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
        document = await self.get(kind, resource)
        source = (
            await self.client.request(
                InformationInterchangeReadableRequest(
                    scope=self.scope,
                    view="content-types-v1",
                    revision=document.revision,
                )
            )
        ).require_payload()
        if (
            source.representation != "original_markdown"
            or source.sidecar.resource != document.resource
            or source.sidecar.revision != document.revision
            or hashlib.sha256(source.markdown.encode("utf-8")).hexdigest()
            != source.text_hash
        ):
            raise ValueError(
                "Original Markdown is unavailable; extraction cannot be used as editable source"
            )
        return replace(document, body=body_text(source.markdown))

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
