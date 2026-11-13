"""Public Python SDK operations used by the device reference library."""

from __future__ import annotations

from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    InformationAcquireRequest,
    InformationAcquisitionDto,
    InformationAcquisitionsRequest,
    InformationRetryRequest,
    InformationRevisionDto,
    InformationStatusRequest,
    InformationTagsReplaceRequest,
    SelectionDtoScope,
)

PAGE_SIZE = 100


class ReferencePort(Protocol):
    async def acquire(
        self, url: str, name: str, request_id: str
    ) -> InformationAcquisitionDto: ...
    async def acquisitions(self) -> tuple[InformationAcquisitionDto, ...]: ...
    async def revision(self, revision_id: str) -> InformationRevisionDto: ...
    async def replace_tags(
        self,
        revision_id: str,
        tags: tuple[str, ...],
        expected_version: int,
        request_id: str,
    ) -> None: ...
    async def retry(self, acquisition_id: str, request_id: str) -> None: ...


class SdkReferencePort(ReferencePort):
    def __init__(self, client: Client, scope: SelectionDtoScope):
        self.client = client
        self.scope = scope

    async def acquire(
        self, url: str, name: str, request_id: str
    ) -> InformationAcquisitionDto:
        ticket = (
            await self.client.request(
                InformationAcquireRequest(
                    scope=self.scope, url=url, name=name, request_id=request_id
                )
            )
        ).require_payload()
        if ticket.url != url:
            raise ValueError("Foreign acquisition")
        return ticket

    async def acquisitions(self) -> tuple[InformationAcquisitionDto, ...]:
        """Every acquisition in scope; a short page marks the end."""
        found: list[InformationAcquisitionDto] = []
        offset = 0
        while True:
            page = (
                await self.client.request(
                    InformationAcquisitionsRequest(
                        scope=self.scope, offset=offset, limit=PAGE_SIZE
                    )
                )
            ).require_payload()
            found.extend(page)
            if len(page) < PAGE_SIZE:
                return tuple(found)
            offset += PAGE_SIZE

    async def revision(self, revision_id: str) -> InformationRevisionDto:
        """Read one revision's tags and tags version via `information.status`.

        `information.read` returns only a text window and `information.documents.get`
        returns a different document record; status is the call that replies with an
        InformationRevisionDto carrying `tags_version`.
        """
        status = (
            await self.client.request(
                InformationStatusRequest(scope=self.scope, revision=revision_id)
            )
        ).require_payload()
        if not isinstance(status, InformationRevisionDto) or status.id != revision_id:
            raise ValueError("Foreign revision")
        if not isinstance(status.tags_version, (int, float)):
            raise ValueError("Revision lacks a tags version")
        return status

    async def replace_tags(
        self,
        revision_id: str,
        tags: tuple[str, ...],
        expected_version: int,
        request_id: str,
    ) -> None:
        (
            await self.client.request(
                InformationTagsReplaceRequest(
                    scope=self.scope,
                    revision=revision_id,
                    tags=tags,
                    expected_tags_version=expected_version,
                    request_id=request_id,
                )
            )
        ).require_payload()

    async def retry(self, acquisition_id: str, request_id: str) -> None:
        (
            await self.client.request(
                InformationRetryRequest(
                    scope=self.scope,
                    acquisition=acquisition_id,
                    request_id=request_id,
                )
            )
        ).require_payload()
