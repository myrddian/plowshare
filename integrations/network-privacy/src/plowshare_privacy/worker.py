"""Relay handoff, deterministic collection, evidence retention and completion publication."""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, replace
from typing import Protocol
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from plowshare import Delivery, Refusal, TransportError
from plowshare.contracts import RelayPublishRequest
from plowshare.tools import ToolAttention

from .collection import Collector
from .contracts import (
    Configuration,
    ScanRequest,
    parse_json,
    same_instant,
    utc_now,
    uuid,
)
from .diagnostics import refusal_detail
from .journal import Publication, Receipt, Receipts, ScanOrigin
from .ports import PrivacyPort, evidence_name


class ReconciliationRequired(RuntimeError):
    """The last mutation may have committed. Recovery must inspect retained records."""


class ExternalRequests(Protocol):
    async def poll(self) -> int | None: ...


class Worker:
    def __init__(
        self,
        config: Configuration,
        port: PrivacyPort,
        receipts: Receipts,
        collector: Collector,
    ):
        self.config = config
        self.port = port
        self.receipts = receipts
        self.collector = collector
        self.consumer = str(uuid4())
        self.lock = asyncio.Lock()
        self.request_lock = asyncio.Lock()
        self.operation = "collector startup"
        self.state = "ready" if config.collection.enabled else "configuration_required"
        self.detail = (
            "Waiting for scheduled or requested collection."
            if config.collection.enabled
            else "Choose devices in the dashboard to begin monitoring."
        )

    async def poll(self) -> None:
        """Copy intake durably and acknowledge promptly; scans run outside the batch lease."""
        async with self.lock:
            if not self.config.collection.enabled:
                # Scope changes hold the same lock; disabled intake cannot race an edit.
                return
            await self._drain()
            self.operation = "relay.topics"
            available = await self.port.available_topics()
            for topic in ("schedule.due", self.config.request_topic):
                if topic not in available:
                    # Topics appear on first publication. A paused schedule and an
                    # unused request channel must not prevent other registered intake.
                    # No missing topic is consumed, acknowledged or considered complete.
                    continue
                self.operation = "relay.consume (" + topic + ")"
                batch = await self.port.consume(topic, self.consumer)
                if batch.status == "GAP":
                    raise ReconciliationRequired(
                        "Relay retention gap; inspect and acknowledge its exact boundary explicitly"
                    )
                if batch.status in {"EMPTY", "BUSY"}:
                    continue
                if (batch.project, batch.topic, batch.group, batch.consumer_id) != (
                    self.config.project,
                    topic,
                    self.config.group,
                    self.consumer,
                ):
                    raise ValueError("Foreign batch")
                for event in batch.events:
                    if topic == "schedule.due":
                        if (
                            event.payload.kind != "SCHEDULE_DUE"
                            or event.payload.schedule != self.config.schedule
                        ):
                            continue
                    else:
                        if event.payload.kind != "TEXT" or event.payload.text is None:
                            raise ValueError("Scan requests require TEXT")
                        request = ScanRequest.decode(parse_json(event.payload.text))
                        if request.collector != self.config.collector:
                            continue
                        if request.request_id != event.event_id:
                            raise ValueError(
                                "Request identity does not match the publication"
                            )
                    scan_id = str(
                        uuid5(
                            NAMESPACE_URL,
                            f"plowshare-privacy/{self.config.project}/{self.config.collector}/{event.event_id}",
                        )
                    )
                    existing = next(
                        (
                            item
                            for item in self.receipts.all()
                            if item.scan_id == scan_id
                        ),
                        None,
                    )
                    if existing is None:
                        self.receipts.save(
                            Receipt(scan_id, event.event_id, topic, event.occurred_at)
                        )
                    elif (existing.source_topic, existing.occurred_at) != (
                        topic,
                        event.occurred_at,
                    ):
                        raise ValueError("Conflicting scan redelivery")
                # This acknowledges copied availability, not scan or agent completion.
                self.operation = "relay.ack (" + topic + ")"
                await self.port.acknowledge(batch)
            await self._drain()
            self.detail = (
                "Ready for dashboard scans. Scheduled intake is waiting for its first event."
                if "schedule.due" not in available
                else "Waiting for scheduled or requested collection."
            )

    async def _drain(self) -> None:
        for receipt in self.receipts.all():
            if receipt.phase in {"uploading", "publishing"}:
                raise ReconciliationRequired(
                    f"Scan {receipt.scan_id} needs read-only reconciliation"
                )
            if receipt.phase == "done":
                continue
            if receipt.phase == "queued":
                self.state = "collecting"
                self.operation = "network collection"
                previous = next(
                    (
                        item
                        for item in reversed(self.receipts.all())
                        if item.phase == "done"
                    ),
                    None,
                )
                evidence = await self.collector.collect(
                    receipt.scan_id,
                    receipt.source_event,
                    previous.evidence if previous else None,
                    previous.revision if previous else None,
                )
                if len(evidence.encode().encode("utf-16-le")) // 2 > 60000:
                    raise ValueError("Evidence exceeds the direct-read bound")
                receipt = replace(receipt, phase="collected", evidence=evidence)
                self.receipts.save(receipt)
            if receipt.evidence is None:
                raise ValueError("Receipt has no evidence")
            if receipt.phase == "collected":
                self.state = "retaining"
                self.receipts.save(replace(receipt, phase="uploading"))
                self.operation = "information.upload"
                try:
                    retained = await self.port.upload(
                        receipt.evidence, str(uuid5(UUID(receipt.scan_id), "evidence"))
                    )
                except Refusal:
                    self.receipts.save(receipt)
                    raise
                except TransportError as error:
                    if error.delivery == Delivery.NOT_SUBMITTED:
                        self.receipts.save(receipt)
                    raise
                receipt = replace(receipt, phase="uploaded", revision=retained.revision)
                self.receipts.save(receipt)
            if receipt.phase == "uploaded":
                self.state = "publishing"
                self.receipts.save(replace(receipt, phase="publishing"))
                self.operation = "relay.publish (" + self.config.result_topic + ")"
                try:
                    await self.port.publish(self.completion_request(receipt))
                except Refusal:
                    self.receipts.save(receipt)
                    raise
                except TransportError as error:
                    if error.delivery == Delivery.NOT_SUBMITTED:
                        self.receipts.save(receipt)
                    raise
                self.receipts.save(replace(receipt, phase="done"))
        self.state = "ready"

    def completion_request(self, receipt: Receipt) -> RelayPublishRequest:
        if receipt.evidence is None or receipt.revision is None:
            raise ValueError("Completion requires retained evidence")
        return RelayPublishRequest(
            project=self.config.project,
            topic=self.config.result_topic,
            request_id=str(uuid5(UUID(receipt.scan_id), "completion")),
            occurred_at=receipt.evidence.finished_at,
            correlation_id=receipt.scan_id,
            parent_topic=receipt.source_topic,
            parent_event_id=receipt.source_event,
            text=json.dumps(
                {
                    "version": 1,
                    "collector": self.config.collector,
                    "scan_id": receipt.scan_id,
                    "revision": receipt.revision,
                    "mode": receipt.evidence.mode,
                    "changes": receipt.evidence.changes,
                    "issues": receipt.evidence.issues,
                },
                allow_nan=False,
            ),
        )

    def manual_request(self, publication: Publication) -> RelayPublishRequest:
        return RelayPublishRequest(
            project=self.config.project,
            topic=self.config.request_topic,
            request_id=publication.request_id,
            occurred_at=publication.occurred_at,
            correlation_id=publication.request_id,
            parent_topic=publication.origin.topic if publication.origin else None,
            parent_event_id=publication.origin.event if publication.origin else None,
            text=json.dumps(
                {
                    "version": 1,
                    **asdict(
                        ScanRequest(publication.request_id, self.config.collector)
                    ),
                }
            ),
        )

    async def request_scan(
        self, request_id: str, origin: ScanOrigin | None = None
    ) -> Publication:
        request_id = uuid(request_id)
        # SDK submissions may overlap a scan; journal writes themselves are synchronous.
        # Holding the collection lock here would make the UI wait for every TCP timeout.
        async with self.request_lock:
            if not self.config.collection.enabled:
                raise ValueError(
                    "Collection is disabled; choose devices in the dashboard first"
                )
            existing = next(
                (
                    item
                    for item in self.receipts.publications()
                    if item.request_id == request_id
                ),
                None,
            )
            if existing:
                if existing.origin != origin:
                    raise ValueError("Scan identity has a different retained origin")
                return existing  # A duplicate UI request never resubmits an uncertain publication.
            publication = Publication(request_id, utc_now(), "pending", origin)
            self.receipts.save_publication(publication)
            await self.port.publish(self.manual_request(publication))
            publication = replace(publication, state="published")
            self.receipts.save_publication(publication)
            return publication

    async def reconcile(self) -> None:
        """Positive retained evidence settles unknowns. Absence never authorizes a retry."""
        async with self.lock:
            for publication in self.receipts.publications():
                if publication.state != "pending":
                    continue
                expected = self.manual_request(publication)
                event = await self.port.find_event(expected.topic, expected.request_id)
                if (
                    event is None
                    or event.payload.text != expected.text
                    or not same_instant(event.occurred_at, expected.occurred_at)
                    or publication.origin is not None
                    and (
                        event.causation_id != publication.origin.event
                        or event.correlation_id != publication.request_id
                    )
                ):
                    raise ReconciliationRequired(
                        "Manual request outcome remains unknown"
                    )
                self.receipts.save_publication(replace(publication, state="published"))
            for receipt in self.receipts.all():
                if receipt.phase == "uploading":
                    if receipt.evidence is None:
                        raise ValueError("No evidence to reconcile")
                    sources = await self.port.sources(evidence_name(receipt.evidence))
                    matches = [
                        source
                        for source in sources
                        if source.source_name == evidence_name(receipt.evidence)
                    ]
                    if (
                        len(matches) != 1
                        or await self.port.read_source(matches[0].id)
                        != receipt.evidence.encode()
                    ):
                        raise ReconciliationRequired(
                            "Evidence upload remains unknown; no mutation was replayed"
                        )
                    self.receipts.save(
                        replace(receipt, phase="uploaded", revision=matches[0].id)
                    )
                elif receipt.phase == "publishing":
                    expected = self.completion_request(receipt)
                    event = await self.port.find_event(
                        expected.topic, expected.request_id
                    )
                    if (
                        event is None
                        or event.payload.text != expected.text
                        or not same_instant(event.occurred_at, expected.occurred_at)
                    ):
                        raise ReconciliationRequired(
                            "Completion publication remains unknown"
                        )
                    self.receipts.save(replace(receipt, phase="done"))

    async def run(
        self, stop: asyncio.Event, external: ExternalRequests | None = None
    ) -> None:
        while not stop.is_set():
            try:
                if external is not None:
                    self.operation = "external tool provider"
                    await external.poll()
                await self.poll()
            except (
                ReconciliationRequired,
                ToolAttention,
                TransportError,
                Refusal,
                ValueError,
                OSError,
            ) as error:
                # Keep the dashboard available; reconnection and mutation replay are explicit.
                self.state = "attention_required"
                self.detail = (
                    refusal_detail(error, self.operation)
                    if isinstance(error, Refusal)
                    else (
                        "Collection stopped. Inspect receipts and restart after resolving the cause ("
                        + type(error).__name__
                        + ")."
                    )
                )
                await stop.wait()
                return
            try:
                await asyncio.wait_for(stop.wait(), 2)
            except TimeoutError:
                continue
