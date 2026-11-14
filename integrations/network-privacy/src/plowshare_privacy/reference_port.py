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
# The most pages one listing reads: 5,000 acquisitions. The collector creates
# a handful per product, so a server still returning full pages past this is
# misbehaving; the listing fails instead of looping forever.
MAX_PAGES = 50


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
        """Every acquisition in scope; a short page marks the end.

        At most ``MAX_PAGES`` pages are read. A page longer than requested, or a
        listing still full after the last allowed page, raises ``ValueError``.
        """
        found: list[InformationAcquisitionDto] = []
        for number in range(MAX_PAGES):
            page = (
                await self.client.request(
                    InformationAcquisitionsRequest(
                        scope=self.scope, offset=number * PAGE_SIZE, limit=PAGE_SIZE
                    )
                )
            ).require_payload()
            if len(page) > PAGE_SIZE:
                raise ValueError("Acquisition page longer than requested")
            found.extend(page)
            if len(page) < PAGE_SIZE:
                return tuple(found)
        raise ValueError("Too many acquisitions to list")

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
