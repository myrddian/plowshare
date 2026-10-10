"""Public Python SDK composition. Application logic never handles wire envelopes."""

from __future__ import annotations

from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    InformationAcquirePayloadDtoScopeVariant2Dto,
    InformationAdmissionDto,
    InformationFilterDto,
    InformationListRequest,
    InformationReadRequest,
    InformationRevisionDto,
    InformationStatusRequest,
    InformationUploadRequest,
    RelayAckRequest,
    RelayBatchDto,
    RelayConsumeRequest,
    RelayEventDto,
    RelayLogPayloadVariant1Dto,
    RelayLogRequest,
    RelayPublishRequest,
    RelayPublishResultDto,
)

from .contracts import Configuration, Evidence


class PrivacyPort(Protocol):
    async def consume(self, topic: str, consumer: str) -> RelayBatchDto: ...
    async def acknowledge(self, batch: RelayBatchDto) -> None: ...
    async def upload(
        self, evidence: Evidence, request_id: str
    ) -> InformationAdmissionDto: ...
    async def publish(self, request: RelayPublishRequest) -> RelayPublishResultDto: ...
    async def sources(self, name: str) -> tuple[InformationRevisionDto, ...]: ...
    async def read_source(self, revision: str) -> str: ...
    async def find_event(self, topic: str, event_id: str) -> RelayEventDto | None: ...
    async def reports(self) -> tuple[InformationRevisionDto, ...]: ...


def evidence_name(evidence: Evidence) -> str:
    return f"network-privacy/{evidence.collector}/{evidence.scan_id}.json"


class SdkPrivacyPort:
    def __init__(self, client: Client, config: Configuration):
        self.client = client
        self.config = config
        self.scope = InformationAcquirePayloadDtoScopeVariant2Dto(
            kind="project", project=config.project
        )

    async def consume(self, topic: str, consumer: str) -> RelayBatchDto:
        batch = (
            await self.client.request(
                RelayConsumeRequest(
                    project=self.config.project,
                    topic=topic,
                    group=self.config.group,
                    consumer_id=consumer,
                    start="OLDEST_RETAINED",
                    limit=25,
                    wait_ms=0,
                )
            )
        ).require_payload()
        if (batch.project, batch.topic, batch.group, batch.consumer_id) != (
            self.config.project,
            topic,
            self.config.group,
            consumer,
        ):
            raise ValueError("Foreign Relay batch")
        return batch

    async def acknowledge(self, batch: RelayBatchDto) -> None:
        if batch.status != "DATA" or batch.batch_id is None or batch.fence is None:
            raise ValueError("Only inspected DATA batches may be acknowledged")
        receipt = (
            await self.client.request(
                RelayAckRequest(
                    project=batch.project,
                    topic=batch.topic,
                    group=batch.group,
                    consumer_id=batch.consumer_id,
                    batch_id=batch.batch_id,
                    fence=batch.fence,
                )
            )
        ).require_payload()
        if (receipt.project, receipt.topic, receipt.group, receipt.batch_id) != (
            batch.project,
            batch.topic,
            batch.group,
            batch.batch_id,
        ):
            raise ValueError("Foreign Relay acknowledgement")

    async def upload(
        self, evidence: Evidence, request_id: str
    ) -> InformationAdmissionDto:
        return (
            await self.client.request(
                InformationUploadRequest(
                    scope=self.scope,
                    name=evidence_name(evidence),
                    text=evidence.encode(),
                    request_id=request_id,
                )
            )
        ).require_payload()

    async def publish(self, request: RelayPublishRequest) -> RelayPublishResultDto:
        result = (await self.client.request(request)).require_payload()
        if (result.project, result.topic, result.request_id) != (
            request.project,
            request.topic,
            request.request_id,
        ):
            raise ValueError("Foreign Relay publication")
        return result

    async def sources(self, name: str) -> tuple[InformationRevisionDto, ...]:
        return (
            await self.client.request(
                InformationListRequest(
                    scope=self.scope,
                    kind="source",
                    filter=InformationFilterDto(search=name),
                    limit=100,
                )
            )
        ).require_payload()

    async def reports(self) -> tuple[InformationRevisionDto, ...]:
        candidates = (
            await self.client.request(
                InformationListRequest(scope=self.scope, kind="report", limit=100)
            )
        ).require_payload()
        reports: list[InformationRevisionDto] = []
        for candidate in candidates:
            # List entries are summaries and may omit inputs. The dashboard's
            # evidence association must use the authoritative scoped status,
            # not interpret a missing summary field as an unrelated report.
            detail = candidate
            if not isinstance(candidate.inputs, tuple):
                status = (
                    await self.client.request(
                        InformationStatusRequest(
                            scope=self.scope, revision=candidate.id
                        )
                    )
                ).require_payload()
                if (
                    not isinstance(status, InformationRevisionDto)
                    or status.id != candidate.id
                    or status.kind != "report"
                    or not isinstance(status.inputs, tuple)
                ):
                    raise ValueError("Report status is foreign or lacks dependencies")
                detail = status
            reports.append(detail)
        return tuple(reports)

    async def read_source(self, revision: str) -> str:
        # Offsets come from the server (UTF-16 units), never Python string lengths.
        # A deployment may restrict the window size; completeness is still required.
        offset = 0
        total: int | None = None
        parts: list[str] = []
        for _ in range(64):
            result = (
                await self.client.request(
                    InformationReadRequest(
                        scope=self.scope, revision=revision, offset=offset, limit=8192
                    )
                )
            ).require_payload()
            if (
                result.revision != revision
                or result.start != offset
                or not offset <= result.end <= result.total <= 65536
                or any(value % 1 for value in (result.start, result.end, result.total))
                or total is not None
                and total != result.total
            ):
                raise ValueError("Source read is foreign or exceeds supported bounds")
            total = int(result.total)
            parts.append(result.text)
            if result.end == total:
                return "".join(parts)
            if result.end == offset:
                raise ValueError("Source window did not advance")
            offset = int(result.end)
        raise ValueError("Source read is incomplete within the window bound")

    async def find_event(self, topic: str, event_id: str) -> RelayEventDto | None:
        after = "0"
        for _ in range(100):
            result = (
                await self.client.request(
                    RelayLogRequest(
                        selection=RelayLogPayloadVariant1Dto(
                            project=self.config.project,
                            topic=topic,
                            after=after,
                            limit=100,
                        )
                    )
                )
            ).require_payload()
            if (
                result.scope.project != self.config.project
                or result.topic.name != topic
            ):
                raise ValueError("Foreign Relay log")
            for event in result.events:
                if event.event_id == event_id:
                    return event
            if not result.events:
                return None
            if result.next == after:
                raise ValueError("Relay log cursor did not advance")
            after = result.next
        raise ValueError("Reconciliation read bound reached; inspect the retained log")
