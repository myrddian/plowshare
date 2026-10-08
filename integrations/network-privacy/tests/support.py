"""Typed external-system fixtures. No database, inference provider or live network scan."""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from plowshare.contracts import (
    InformationAdmissionDto,
    InformationRevisionDto,
    RelayBatchDto,
    RelayCausationDto,
    RelayEventDto,
    RelayEventDtoPayloadDto,
    RelayPublishRequest,
    RelayPublishResultDto,
)

from plowshare_privacy.collection import NetworkCollector
from plowshare_privacy.contracts import CollectionPlan, Configuration, Evidence
from plowshare_privacy.journal import Publication, Receipt
from plowshare_privacy.ports import evidence_name

STAMP = "2026-01-01T00:00:00Z"
EXAMPLES = Path(__file__).resolve().parents[1] / "examples"


def configuration(directory: Path) -> Configuration:
    snapshot = directory / "observations.json"
    snapshot.write_bytes((EXAMPLES / "observations-before.json").read_bytes())
    return Configuration(
        "https://plowshare.example.invalid",
        "network-privacy-watch",
        "home-network",
        "PLOWSHARE_TOKEN",
        "PRIVACY_WEB_TOKEN",
        directory / "state",
        "privacy.scan.requests",
        "privacy.scan.completed",
        "privacy-home-network",
        "scheduled-example",
        CollectionPlan("fixture", (), (), 1, 1, snapshot, 0),
    )


def event(
    identity: str = "schedule:fixture",
    *,
    manual: bool = False,
    collector: str = "home-network",
    schedule: str = "scheduled-example",
) -> RelayEventDto:
    return RelayEventDto(
        event_id=identity,
        occurred_at=STAMP,
        published_at=STAMP,
        position="1",
        publisher="sdk:fixture",
        causation_id=None,
        correlation_id=None,
        causation=RelayCausationDto(root_id=identity, parent_id=None, depth=0),
        payload=RelayEventDtoPayloadDto(
            kind="TEXT" if manual else "SCHEDULE_DUE",
            text=json.dumps(
                {"version": 1, "request_id": identity, "collector": collector}
            )
            if manual
            else None,
            schedule=None if manual else schedule,
            emits=None if manual else "privacy-tick",
            fire_at=None if manual else STAMP,
        ),
    )


class MemoryReceipts:
    def __init__(self) -> None:
        self.rows: dict[str, Receipt] = {}
        self.requests: dict[str, Publication] = {}

    def all(self) -> tuple[Receipt, ...]:
        return tuple(self.rows.values())

    def save(self, receipt: Receipt) -> None:
        self.rows[receipt.scan_id] = receipt

    def publications(self) -> tuple[Publication, ...]:
        return tuple(self.requests.values())

    def save_publication(self, publication: Publication) -> None:
        self.requests[publication.request_id] = publication


class CountingCollector(NetworkCollector):
    def __init__(self, config: Configuration):
        super().__init__(config.collector, config.collection)
        self.count = 0

    async def collect(
        self,
        scan_id: str,
        source_event: str,
        previous: Evidence | None,
        previous_revision: str | None,
    ) -> Evidence:
        self.count += 1
        return await super().collect(scan_id, source_event, previous, previous_revision)


class FixturePort:
    def __init__(self, config: Configuration):
        self.config = config
        self.events: dict[str, list[RelayEventDto]] = {
            "schedule.due": [],
            config.request_topic: [],
            config.result_topic: [],
        }
        self.documents: dict[str, tuple[InformationRevisionDto, str]] = {}
        self.published: list[RelayPublishRequest] = []
        self.acknowledgements: list[RelayBatchDto] = []
        self.uploads = 0

    async def consume(self, topic: str, consumer: str) -> RelayBatchDto:
        events = tuple(self.events[topic])
        return RelayBatchDto(
            project=self.config.project,
            topic=topic,
            group=self.config.group,
            consumer_id=consumer,
            events=events,
            status="DATA" if events else "EMPTY",
            batch_id=str(uuid4()) if events else None,
            fence="1" if events else None,
            expires_at=STAMP if events else None,
            expired_through=None,
            through="1" if events else "0",
        )

    async def acknowledge(self, batch: RelayBatchDto) -> None:
        self.acknowledgements.append(batch)
        self.events[batch.topic] = []

    async def upload(
        self, evidence: Evidence, request_id: str
    ) -> InformationAdmissionDto:
        self.uploads += 1
        revision = str(uuid4())
        self.documents[revision] = (
            InformationRevisionDto(
                id=revision, source_name=evidence_name(evidence), kind="source"
            ),
            evidence.encode(),
        )
        return InformationAdmissionDto(
            created=True, resource=str(uuid4()), revision=revision
        )

    async def publish(self, request: RelayPublishRequest) -> RelayPublishResultDto:
        self.published.append(request)
        value = replace(
            event(request.request_id, manual=True),
            occurred_at=request.occurred_at,
            payload=RelayEventDtoPayloadDto(
                kind="TEXT", text=request.text, emits=None, schedule=None, fire_at=None
            ),
        )
        self.events[request.topic].append(value)
        return RelayPublishResultDto(
            project=request.project,
            topic=request.topic,
            request_id=request.request_id,
            position="1",
            published_at=STAMP,
        )

    async def sources(self, name: str) -> tuple[InformationRevisionDto, ...]:
        return tuple(
            value[0]
            for value in self.documents.values()
            if value[0].source_name == name
        )

    async def read_source(self, revision: str) -> str:
        return self.documents[revision][1]

    async def find_event(self, topic: str, event_id: str) -> RelayEventDto | None:
        return next(
            (item for item in self.events[topic] if item.event_id == event_id), None
        )

    async def reports(self) -> tuple[InformationRevisionDto, ...]:
        return tuple(
            item[0] for item in self.documents.values() if item[0].kind == "report"
        )
