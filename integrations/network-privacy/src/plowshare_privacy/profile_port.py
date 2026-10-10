"""Device profile documents through the public, explicitly project-scoped SDK."""

from __future__ import annotations

import math
from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    InformationAcquirePayloadDtoScopeVariant2Dto,
    InformationFilterDto,
    InformationListRequest,
    InformationReviseRequest,
    InformationUploadRequest,
)

from .contracts import Configuration, integer, text, timestamp, uuid
from .device_profiles import PREFIX, DeviceProfile, ProfileVersion, source_name


class RetainedSourceReader(Protocol):
    """A bounded source read through its configured Information scope."""

    async def read_source(self, revision: str) -> str: ...


class SdkProfilePort:
    def __init__(
        self, client: Client, config: Configuration, reader: RetainedSourceReader
    ):
        self.client, self.reader = client, reader
        self.scope = InformationAcquirePayloadDtoScopeVariant2Dto(
            kind="project", project=config.project, include_shared=False
        )

    async def versions(self) -> tuple[ProfileVersion, ...]:
        result = []
        for page in range(4):
            rows = (
                await self.client.request(
                    InformationListRequest(
                        scope=self.scope,
                        kind="source",
                        filter=InformationFilterDto(search=PREFIX),
                        limit=100,
                        offset=page * 100,
                    )
                )
            ).require_payload()
            for row in rows:
                if not isinstance(
                    row.source_name, str
                ) or not row.source_name.startswith(PREFIX):
                    continue
                identity = uuid(
                    row.source_name.removeprefix(PREFIX).removesuffix(".md")
                )
                if row.source_name != source_name(identity) or row.kind != "source":
                    raise ValueError("Foreign profile catalogue entry")
                if (
                    isinstance(row.ordinal, bool)
                    or not isinstance(row.ordinal, (int, float))
                    or not math.isfinite(row.ordinal)
                    or row.ordinal % 1
                ):
                    raise ValueError("Profile revision ordinal must be a whole number")
                result.append(
                    ProfileVersion(
                        identity,
                        uuid(row.id),
                        uuid(row.resource_id),
                        integer(int(row.ordinal), 1, 1000000),
                        text(row.author, 512),
                        timestamp(row.created_at),
                    )
                )
            if len(rows) < 100:
                if len({v.revision for v in result}) != len(result):
                    raise ValueError("Profile catalogue changed while paging; refresh")
                return tuple(result)
        raise ValueError(
            "Profile history exceeds the 400-revision read bound; inspect the project catalogue"
        )

    async def read(self, revision: str) -> str:
        return await self.reader.read_source(uuid(revision))

    async def save(
        self, profile: DeviceProfile, previous: str | None
    ) -> tuple[str, str]:
        request = (
            InformationUploadRequest(
                scope=self.scope,
                name=source_name(profile.profile_id),
                request_id=profile.write_id,
                text=profile.markdown(),
            )
            if previous is None
            else InformationReviseRequest(
                scope=self.scope,
                revision=uuid(previous),
                request_id=profile.write_id,
                text=profile.markdown(),
            )
        )
        result = (await self.client.request(request)).require_payload()
        return uuid(result.revision), uuid(result.resource)
