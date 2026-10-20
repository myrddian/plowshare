"""Bounded complete schedule inspection through typed public SDK reads only."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from typing import Protocol, TypeVar

from plowshare import Reply
from plowshare.contracts import (
    Request,
    ScheduleFileDto,
    ScheduleFilesRequest,
    ScheduleListRequest,
    ScheduleRecordDto,
)

T = TypeVar("T")
PAGE_LIMIT = 100
MAX_PAGES = 100
READ_SECONDS = 30


class ScheduleClient(Protocol):
    async def request(self, request: Request[T]) -> Reply[T]: ...


async def _rows(
    client: ScheduleClient,
    request: Callable[[str | None], Request[tuple[T, ...]]],
    key: Callable[[T], str],
) -> AsyncIterator[T]:
    """Fail closed on refusal, repeated keys or an incomplete bounded traversal.

    Cursors are opaque database keys: Python ordering does not reproduce database
    collation. Duplicate keys detect loops without inventing a lexical comparison.
    Only a seen-key set and the current page are retained; calls are never retried.
    """
    after: str | None = None
    seen: set[str] = set()
    async with asyncio.timeout(READ_SECONDS):
        for _ in range(MAX_PAGES):
            page = (await client.request(request(after))).require_payload()
            if len(page) > PAGE_LIMIT:
                raise ValueError("Schedule page exceeds its requested bound")
            for row in page:
                value = key(row)
                if (
                    not value
                    or len(value) > 512
                    or value != value.strip()
                    or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value)
                    or value in seen
                ):
                    raise ValueError(
                        "Schedule pagination returned a duplicate or invalid cursor"
                    )
                seen.add(value)
                yield row
            if len(page) < PAGE_LIMIT:
                return
            after = key(page[-1])
    raise ValueError(
        "Schedule inspection exceeded its page budget; no complete result is available"
    )


async def schedule_files(
    client: ScheduleClient, project: str
) -> AsyncIterator[ScheduleFileDto]:
    def request(after: str | None) -> ScheduleFilesRequest:
        return (
            ScheduleFilesRequest(project=project, limit=PAGE_LIMIT)
            if after is None
            else ScheduleFilesRequest(project=project, after=after, limit=PAGE_LIMIT)
        )

    def key(value: ScheduleFileDto) -> str:
        return value.internal_name

    async for row in _rows(client, request, key):
        yield row


async def schedules(
    client: ScheduleClient, project: str
) -> AsyncIterator[ScheduleRecordDto]:
    def request(after: str | None) -> ScheduleListRequest:
        return (
            ScheduleListRequest(project=project, limit=PAGE_LIMIT)
            if after is None
            else ScheduleListRequest(project=project, after=after, limit=PAGE_LIMIT)
        )

    def key(value: ScheduleRecordDto) -> str:
        return value.name

    async for row in _rows(client, request, key):
        yield row
