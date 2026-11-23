"""Relay handoff, deterministic collection, evidence retention and completion publication."""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, replace
from typing import Awaitable, Callable, Protocol
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from plowshare import CancelledRequest, Delivery, Refusal, TransportError
from plowshare.contracts import RelayPublishRequest
from plowshare.tools import ToolAttention

from .attention import AttentionOutbox
from .collection import Collector, scope_fingerprint
from .contracts import (
    Configuration,
    Evidence,
    ScanRequest,
    earlier,
    parse_json,
    same_instant,
    utc_now,
    uuid,
)
from .device_facts import DeviceFacts, DeviceFactsStep
from .device_inventory import DeviceInventory, InventoryProjection
from .diagnostics import refusal_detail
from .journal import Publication, Receipt, Receipts, ScanOrigin
from .limits import MAX_EVIDENCE_BYTES
from .operator_state import InvestigationDecision, OperatorStore
from .ports import PrivacyPort, evidence_name
from .presence import PresenceCollector, PresenceStatus, PresenceView, PresenceWatch
from .recovery import wait_for_recovery
from .rollups import CollectionRollups


class ReconciliationRequired(RuntimeError):
    """The last mutation may have committed. Recovery must inspect retained records."""


class ExternalRequests(Protocol):
    async def poll(self) -> int | None: ...


class ActiveRequests:
    """Composition-owned dispatch target, replaced only while the worker is stopped.

    A fresh connection uses the same durable provider journal. Replacing the
    transport does not retry an invocation or create another execution owner.
    """

    def __init__(self, target: ExternalRequests):
        self.target = target

    async def poll(self) -> int | None:
        return await self.target.poll()


_NO_PRESENCE = PresenceView()


class Worker:
    def __init__(
        self,
        config: Configuration,
        port: PrivacyPort,
        receipts: Receipts,
        collector: Collector,
        operator: OperatorStore | None = None,
        presence: PresenceWatch | None = None,
        facts: DeviceFactsStep | None = None,
        rollups: CollectionRollups | None = None,
        advance_rollups: Callable[[], Awaitable[None]] | None = None,
    ):
        self.attention: AttentionOutbox | None = None
        self.operator = operator
        self.presence = presence
        self.facts = facts
        self.rollups = rollups
        self._advance_rollups = advance_rollups
        self._facts_task: asyncio.Task[None] | None = None
        self._rollup_task: asyncio.Future[None] | None = None
        self._facts_checked: str | None = None
        self.recovery_requested = asyncio.Event()
        self.last_contact: str | None = None
        self.failure_kind: str | None = None
        self.reconnecting = False
        self.config = config
        self.port = port
        self.receipts = receipts
        self.collector = collector
        self.inventory_projection = InventoryProjection()
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
            if await self._drain():
                # One retained scan per pass: provider dispatch, settings and
                # presence get a turn before another queued scan starts.
                return
            self.operation = "relay.topics"
            available = await self.port.available_topics()
            for topic in (*sorted(available.schedules), self.config.request_topic):
                if topic not in available.names:
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
                    if topic in available.schedules:
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
                    if existing is None and self.receipts.archived(scan_id):
                        # Settled and already outside the retained window: this
                        # redelivery is acknowledged, never collected again.
                        continue
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
                if not available.schedules
                else "Waiting for scheduled or requested collection."
            )

    async def _drain(self) -> bool:
        """Finish at most one retained scan without admitting another identity."""
        for receipt in self.receipts.all():
            if receipt.phase in {"uploading", "publishing"}:
                raise ReconciliationRequired(
                    f"Scan {receipt.scan_id} needs read-only reconciliation"
                )
            if receipt.phase == "done":
                continue
            if receipt.phase == "queued":
                self.state = "collecting"
                self.detail = "Collecting a queued scan."
                self.operation = "network collection"
                previous = next(
                    (
                        item
                        for item in reversed(self.receipts.all())
                        if item.phase == "done"
                    ),
                    None,
                )
                evidence = await self._collect_with_presence(
                    receipt.scan_id,
                    receipt.source_event,
                    previous.evidence if previous else None,
                    previous.revision if previous else None,
                )
                if len(evidence.encode().encode("utf-16-le")) // 2 > MAX_EVIDENCE_BYTES:
                    raise ValueError("Evidence exceeds the direct-read bound")
                receipt = replace(
                    receipt,
                    phase="collected",
                    evidence=evidence,
                    comparison_scan_id=previous.scan_id
                    if previous is not None
                    and not evidence.is_baseline
                    and self.rollups is not None
                    else None,
                )
                self.receipts.save(receipt)
            if receipt.evidence is None:
                raise ValueError("Receipt has no evidence")
            if receipt.phase == "collected":
                if self.rollups is not None and receipt.investigation is None:
                    decision = self._decide(receipt.evidence)
                    previous = (
                        self._scan(receipt.comparison_scan_id)
                        if receipt.comparison_scan_id is not None
                        else None
                    )
                    if (
                        decision.requested
                        and previous is not None
                        and previous.pin_pending
                    ):
                        # An earlier optional upload can remain uncertain while
                        # new scans continue. Hold only this investigation until
                        # its exact comparison can be confirmed.
                        try:
                            await self._confirm_upload(previous)
                        except (ReconciliationRequired, Refusal):
                            decision = InvestigationDecision(
                                False,
                                "Comparison evidence upload is awaiting confirmation",
                                decision.decided_at,
                            )
                    receipt = replace(
                        receipt,
                        investigation=decision,
                        local_completion=not decision.requested,
                    )
                    self.receipts.save(receipt)
                if not receipt.local_completion:
                    comparison = await self._retain_comparison(receipt)
                    receipt = replace(receipt, comparison_revision=comparison)
                    receipt = await self._upload_evidence(receipt)
            if receipt.phase == "uploaded" or (
                receipt.phase == "collected" and receipt.local_completion
            ):
                if self.operator is not None and receipt.investigation is None:
                    if receipt.evidence is None:
                        raise ValueError("Missing retained evidence")
                    receipt = replace(
                        receipt,
                        investigation=self.operator.decide(
                            receipt.evidence, self.receipts.all()
                        ),
                    )
                    self.receipts.save(receipt)
                self.state = "publishing"
                self.receipts.save(replace(receipt, phase="publishing"))
                self.operation = "relay.publish (" + self.config.result_topic + ")"
                try:
                    await self.port.publish(self.completion_request(receipt))
                except Refusal:
                    self.receipts.save(receipt)
                    raise
                except (TransportError, CancelledRequest) as error:
                    if error.delivery == Delivery.NOT_SUBMITTED:
                        self.receipts.save(receipt)
                    raise
                self._settle(receipt)
                self.state = "ready"
                self.detail = "Retained scan completed; waiting for scheduled or queued collection."
                return True
        self.state = "ready"
        return False

    def _decide(self, evidence: Evidence) -> InvestigationDecision:
        if self.operator is not None:
            return self.operator.decide(evidence, self.receipts.all())
        requested = evidence.is_baseline or bool(evidence.changes)
        return InvestigationDecision(
            requested,
            "Baseline or changed observations"
            if requested
            else "No changes to investigate",
            utc_now(),
        )

    def _settle(self, receipt: Receipt) -> None:
        if self.rollups is not None:
            self.rollups.record(receipt)
        self.receipts.save(replace(receipt, phase="done"))

    async def _upload_evidence(self, receipt: Receipt) -> Receipt:
        evidence = receipt.evidence
        if evidence is None:
            raise ValueError("Evidence is unavailable")
        self.state = "retaining" if receipt.phase != "done" else self.state
        self.operation = "information.upload"
        pin = receipt.phase == "done"
        self.receipts.save(
            replace(receipt, pin_pending=True)
            if pin
            else replace(receipt, phase="uploading")
        )
        try:
            retained = await self.port.upload(
                evidence, str(uuid5(UUID(receipt.scan_id), "evidence"))
            )
        except (Refusal, TransportError, CancelledRequest) as error:
            if isinstance(error, Refusal) or error.delivery == Delivery.NOT_SUBMITTED:
                self.receipts.save(receipt)
            raise
        confirmed = replace(
            receipt,
            phase="done" if pin else "uploaded",
            revision=uuid(retained.revision),
            pin_pending=False,
        )
        self.receipts.save(confirmed)
        return confirmed

    def _scan(self, scan_id: str) -> Receipt | None:
        return next(
            (item for item in self.receipts.all() if item.scan_id == scan_id), None
        )

    async def _retain_scan(self, receipt: Receipt) -> Receipt:
        if receipt.phase != "done" or receipt.evidence is None:
            raise ValueError("Choose a completed collection with local evidence")
        if receipt.revision is not None:
            return receipt
        if receipt.pin_pending:
            return await self._confirm_upload(receipt)
        return await self._upload_evidence(receipt)

    async def _retain_comparison(self, receipt: Receipt) -> str | None:
        if receipt.evidence is None:
            raise ValueError("Evidence is unavailable")
        if receipt.comparison_revision is not None:
            return receipt.comparison_revision
        if (
            receipt.evidence.previous_revision is not None
            or receipt.comparison_scan_id is None
        ):
            # The original payload already names this exact revision, or there
            # was no comparable snapshot.
            return None
        previous = self._scan(receipt.comparison_scan_id)
        if previous is None:
            raise ValueError("The comparison snapshot has aged out of local evidence")
        return (await self._retain_scan(previous)).revision

    async def retain_for_investigation(self, scan_id: str) -> Receipt:
        """Pin exact selected evidence before admitting an operator's investigation."""
        async with self.lock:
            receipt = self._scan(scan_id)
            if receipt is None or receipt.phase != "done":
                raise ValueError("The collection has aged out of local evidence")
            comparison = await self._retain_comparison(receipt)
            retained = await self._retain_scan(receipt)
            if retained.comparison_revision != comparison:
                retained = replace(retained, comparison_revision=comparison)
                self.receipts.save(retained)
            return retained

    async def retain_scan_revision(self, scan_id: str) -> str:
        """Pin the exact completed scan consumed by device research or assessment."""
        async with self.lock:
            receipt = self._scan(scan_id)
            if receipt is None:
                raise ValueError("The collection has aged out of local evidence")
            revision = (await self._retain_scan(receipt)).revision
            assert revision is not None
            return revision

    async def _collect_with_presence(
        self,
        scan_id: str,
        source_event: str,
        previous: Evidence | None,
        previous_revision: str | None,
    ) -> Evidence:
        """Sample during slow read-only collection under the same scope fence.

        The caller owns ``lock`` until both reads settle. No second full scan or
        quick sample overlaps its own kind, and settings cannot replace the plan
        mid-sample. Quick checks retain their existing sample/DNS deadlines and
        randomized cadence. The child performs no upload or publication; on any
        failure/cancellation it is cancelled and joined before releasing ownership.
        """
        plan = self.config.collection
        if (
            self.presence is None
            or not plan.quick_scan
            or plan.mode != "tcp"
            or not isinstance(self.collector, PresenceCollector)
        ):
            return await self.collector.collect(
                scan_id, source_event, previous, previous_revision
            )
        collection = asyncio.create_task(
            self.collector.collect(scan_id, source_event, previous, previous_revision)
        )
        try:
            while not collection.done():
                await self._poll_presence_locked()
                if not collection.done():
                    await asyncio.wait(
                        (collection,), timeout=self.presence.wait_seconds()
                    )
            return await collection
        finally:
            if not collection.done():
                collection.cancel()
            # Keep physical ownership through connector cancellation/cleanup.
            await asyncio.gather(collection, return_exceptions=True)

    def completion_request(self, receipt: Receipt) -> RelayPublishRequest:
        if receipt.evidence is None or (
            receipt.revision is None and not receipt.local_completion
        ):
            raise ValueError("Completion requires retained evidence")
        return RelayPublishRequest(
            project=self.config.project,
            topic=self.config.result_topic,
            request_id=str(uuid5(UUID(receipt.scan_id), "completion")),
            occurred_at=receipt.evidence.finished_at,
            correlation_id=receipt.scan_id,
            parent_topic=receipt.source_topic,
            parent_event_id=receipt.source_event,
            text=self.completion_text(receipt),
        )

    def completion_text(self, receipt: Receipt) -> str:
        """Bound event previews independently of full retained evidence.

        Historical small completion bytes remain identical for reconciliation.
        New large sweeps expose total counts when previews must be shortened;
        the owning evidence revision always retains the complete comparison.
        """
        evidence = receipt.evidence
        if evidence is None or (
            receipt.revision is None and not receipt.local_completion
        ):
            raise ValueError("Completion requires retained evidence")
        row = {
            "version": 2 if receipt.local_completion else 1,
            "collector": self.config.collector,
            "scan_id": receipt.scan_id,
            **({"revision": receipt.revision} if not receipt.local_completion else {}),
            **(
                {
                    "comparison_revision": receipt.comparison_revision,
                    "comparison_scan_id": receipt.comparison_scan_id,
                }
                if receipt.comparison_revision is not None
                and not receipt.local_completion
                else {}
            ),
            "mode": evidence.mode,
            "changes": evidence.changes
            if receipt.investigation is None or receipt.investigation.requested
            else (),
            **({"observed_changes": evidence.changes} if receipt.investigation else {}),
            "issues": evidence.issues,
            **(
                {"baseline": evidence.baseline} if evidence.baseline is not None else {}
            ),
            **(
                {"investigation": asdict(receipt.investigation)}
                if receipt.investigation
                else {}
            ),
        }
        encoded = json.dumps(row, allow_nan=False)
        if len(encoded) <= 24000:
            return encoded
        # The serializer remains this transport boundary; domain callers receive
        # no raw mapping and do not infer completion from an event preview.
        row["changes_count"] = len(evidence.changes) if row["changes"] else 0
        if "observed_changes" in row:
            row["observed_changes_count"] = len(evidence.changes)
        for size in (32, 16, 8, 4, 1, 0):
            row["changes"] = (
                evidence.changes[:size]
                if receipt.investigation is None or receipt.investigation.requested
                else ()
            )
            if "observed_changes" in row:
                row["observed_changes"] = evidence.changes[:size]
            encoded = json.dumps(row, allow_nan=False)
            if len(encoded) <= 24000:
                return encoded
        raise ValueError("Completion coverage metadata exceeds the event preview bound")

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
            if self.state == "attention_required":
                raise ValueError(
                    "Check retained work and resolve the stopped collector before requesting another scan"
                )
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
        """Positive retained evidence settles unknowns. Absence never authorizes a retry.

        The only path from an absent completion back to publication is the
        operator's explicit ``release_absent_completion``.
        """
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
                # Optional pins belong to their next evidence consumer. A
                # missing optional receipt cannot fence unrelated collection.
                if receipt.phase == "uploading":
                    await self._confirm_upload(receipt)
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
                    self._settle(receipt)
            if self.attention is not None:
                await self.attention.reconcile()

    async def _confirm_upload(self, receipt: Receipt) -> Receipt:
        if receipt.evidence is None:
            raise ValueError("No evidence to reconcile")
        name = evidence_name(receipt.evidence)
        matches = [
            source
            for source in await self.port.sources(name)
            if source.source_name == name
        ]
        if (
            len(matches) != 1
            or await self.port.read_source(matches[0].id) != receipt.evidence.encode()
        ):
            raise ReconciliationRequired(
                "Evidence upload remains unknown; no mutation was replayed"
            )
        confirmed = replace(
            receipt,
            phase="done" if receipt.pin_pending else "uploaded",
            revision=uuid(matches[0].id),
            pin_pending=False,
        )
        self.receipts.save(confirmed)
        return confirmed

    async def release_absent_completion(self, scan_id: str) -> bool:
        """Explicit operator recovery for a completion the server never retained.

        ``reconcile`` stays read-only: absence alone never lets the collector
        resend by itself. This is the operator's separate, deliberate decision
        for one named scan while collection is stopped. It reads the retained
        log again and then does one of three things:

        * the exact completion is retained: the receipt settles as ``done`` and
          nothing is sent (returns ``False``);
        * a different event holds the identity, or the log no longer reaches
          back before this scan finished: the outcome stays unknown and the
          receipt stays ``publishing``. Retention may already have removed a
          published completion, so absence proves nothing there;
        * the log still holds an event older than this completion and the
          completion is absent: the receipt returns to ``uploaded`` (returns
          ``True``).

        Nothing is published here. The next ordinary pass publishes the
        ``uploaded`` receipt with its original request identity, parent, text
        and timestamp, exactly as after a submission that never left this
        process. Relay deduplicates that identity while it is retained, so a
        completion that lands between this read and the publish is not doubled.
        Collection and upload are never repeated.
        """
        scan_id = uuid(scan_id)
        async with self.lock:
            if self.state != "attention_required":
                raise ValueError(
                    "A completion can be released only while collection is stopped"
                )
            receipt = next(
                (
                    item
                    for item in self.receipts.all()
                    if item.scan_id == scan_id and item.phase == "publishing"
                ),
                None,
            )
            if receipt is None:
                raise ValueError("Choose a scan whose completion outcome is unknown")
            expected = self.completion_request(receipt)
            self.operation = "relay.log (" + expected.topic + ")"
            event = await self.port.find_event(expected.topic, expected.request_id)
            if event is not None:
                if event.payload.text != expected.text or not same_instant(
                    event.occurred_at, expected.occurred_at
                ):
                    raise ReconciliationRequired(
                        "A different retained event holds this completion identity"
                    )
                self._settle(receipt)
                return False
            oldest = await self.port.oldest_event(expected.topic)
            if oldest is None or not earlier(oldest.occurred_at, expected.occurred_at):
                raise ReconciliationRequired(
                    "The retained log no longer reaches back before this completion; "
                    "its absence cannot be established"
                )
            self.receipts.save(
                replace(
                    receipt,
                    phase="collected" if receipt.local_completion else "uploaded",
                )
            )
            return True

    def presence_view(self) -> PresenceView:
        if self.presence is None:
            # One shared empty view keeps the inventory projection's identity
            # check stable when quick presence is not configured.
            return _NO_PRESENCE
        plan = self.config.collection
        return self.presence.view(
            scope_fingerprint(plan), include_dns=plan.enabled and plan.dns_profiles
        )

    def device_inventory(self) -> DeviceInventory:
        """The monitored inventory from done receipts and the current presence view."""
        return self.inventory_projection.current(
            self.config.collection,
            self.receipts.all(),
            self.presence_view(),
            self.receipts.carried(),
        )

    def presence_status(self) -> PresenceStatus:
        plan = self.config.collection
        enabled = self.presence is not None and plan.enabled and plan.quick_scan
        view = self.presence_view()
        issues = self.presence.issues if enabled and self.presence is not None else ()
        return PresenceStatus(enabled, view.checked_at, issues)

    async def poll_presence(self) -> None:
        if self.presence is None:
            return
        # The same fence serializes settings activation and ordinary collections.
        async with self.lock:
            await self._poll_presence_locked()
        self._start_facts()

    async def _poll_presence_locked(self) -> None:
        """The worker lock fences ordinary samples and samples during collection."""
        if self.presence is None:
            return
        plan = self.config.collection
        if not plan.enabled or not plan.quick_scan:
            self.presence.reset()
            return
        if isinstance(self.collector, PresenceCollector):
            await self.presence.poll(plan, scope_fingerprint(plan), self.collector)

    def _start_facts(self) -> None:
        """Start the device facts step for a newly recorded quick check.

        It runs as one background task outside the collector lock, so device
        queries never delay presence recording, intake or scans. A check that
        arrives while the step is still running is skipped; the next one picks
        up whatever is still due. The step handles its own expected failures,
        so a finished task's result only re-raises programming errors.
        """
        presence, facts = self.presence, self.facts
        if presence is None or facts is None:
            return
        sample = presence.latest
        if (
            sample is None
            or sample.checked_at == self._facts_checked
            or not self.config.collection.device_descriptions
        ):
            return
        if self._facts_task is not None:
            if not self._facts_task.done():
                return
            self._facts_task.result()
        self._facts_checked = sample.checked_at
        self._facts_task = asyncio.create_task(facts.refresh(sample))

    async def stop_facts(self) -> None:
        """Cancel a running facts step; it keeps every fact already saved."""
        task, self._facts_task = self._facts_task, None
        if task is not None and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    def device_facts(self, device_id: str) -> DeviceFacts | None:
        """Retained supplemental facts for a confirmed MAC (``mac:…``), if any."""
        return self.facts.facts(device_id) if self.facts is not None else None

    async def run(
        self,
        stop: asyncio.Event,
        external: ExternalRequests | None = None,
        apply_settings: Callable[[], Awaitable[None]] | None = None,
        recover: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        """Run intake; reconnect transport failures through receipt-only recovery.

        ``recover`` must establish a fresh session and settle retained effects
        without replaying operations. Returning permits intake to resume;
        unresolved receipts and non-transport failures require a manual check.
        Without a callback, this worker preserves explicit-only recovery.
        """
        try:
            await self._run(stop, external, apply_settings, recover)
        finally:
            if self._rollup_task is not None:
                self._rollup_task.cancel()
                await asyncio.gather(self._rollup_task, return_exceptions=True)
            await self.stop_facts()

    async def _run(
        self,
        stop: asyncio.Event,
        external: ExternalRequests | None,
        apply_settings: Callable[[], Awaitable[None]] | None,
        recover: Callable[[], Awaitable[None]] | None,
    ) -> None:
        idle_check_due = 0.0
        while not stop.is_set():
            try:
                # Apply desired configuration before new intake, retaining the old
                # scope for unfinished work. Saving itself never uses this loop.
                if apply_settings is not None:
                    self.operation = "saved configuration activation"
                    await apply_settings()
                if external is not None:
                    self.operation = "external tool provider"
                    await external.poll()
                elif not self.config.collection.enabled:
                    # Disabled collection still needs a live dashboard session.
                    # Probe only while no provider/intake reads check it, and at
                    # most twice per minute rather than on every local tick.
                    now = asyncio.get_running_loop().time()
                    if now >= idle_check_due:
                        self.operation = "relay.topics"
                        await self.port.available_topics()
                        idle_check_due = now + 30.0
                await self.poll()
                if self._advance_rollups is not None and (
                    self._rollup_task is None or self._rollup_task.done()
                ):
                    if self._rollup_task is not None:
                        self._rollup_task.result()
                    self._rollup_task = asyncio.ensure_future(self._advance_rollups())
                if self.attention is not None:
                    self.attention.recovered()
                    if self.attention.pending:
                        self.detail += f" {self.attention.pending} inbox alert(s) await confirmation."
                self.last_contact = utc_now()
                self.failure_kind = None
            except (
                ReconciliationRequired,
                ToolAttention,
                TransportError,
                Refusal,
                ValueError,
                OSError,
            ) as error:
                self._attention(error)
                self.reconnecting = (
                    recover is not None
                    and isinstance(error, TransportError)
                    and error.delivery != Delivery.INVALID_RESPONSE
                )
                if self.attention is not None:
                    try:
                        alert = await self.attention.stopped(
                            type(error).__name__, self.operation
                        )
                    except (OSError, ValueError) as journal_error:
                        # The collector's original stop remains primary and the dashboard
                        # explicitly reports that even its alert could not be retained.
                        alert = (
                            "Inbox alert could not be retained ("
                            + type(journal_error).__name__
                            + ")."
                        )
                    if alert:
                        self.detail += " " + alert
                try:
                    try:
                        await wait_for_recovery(
                            stop,
                            self.recovery_requested,
                            recover if self.reconnecting else None,
                        )
                    except (
                        ReconciliationRequired,
                        ToolAttention,
                        TransportError,
                        Refusal,
                        ValueError,
                        OSError,
                    ) as recovery_error:
                        # Connectivity is independent of effect settlement. An
                        # absent/conflicting receipt or revoked authority keeps
                        # intake fenced until explicit operator recovery.
                        self._attention(recovery_error)
                        self.reconnecting = False
                        await wait_for_recovery(stop, self.recovery_requested, None)
                finally:
                    self.reconnecting = False
                self.recovery_requested.clear()
                if stop.is_set():
                    return
                self.state = (
                    "ready"
                    if self.config.collection.enabled
                    else "configuration_required"
                )
                continue
            if not stop.is_set():
                await self.poll_presence()
            try:
                wait = (
                    self.presence.wait_seconds() if self.presence is not None else 2.0
                )
                await asyncio.wait_for(stop.wait(), wait)
            except TimeoutError:
                continue

    def _attention(self, error: Exception) -> None:
        """Keep local evidence/UI available while intake is fenced."""
        if self.presence is not None:
            self.presence.reset()
        self.failure_kind = type(error).__name__
        self.state = "attention_required"
        self.detail = (
            refusal_detail(error, self.operation)
            if isinstance(error, Refusal)
            else (
                "Collection stopped. Inspect retained receipts before resuming ("
                + type(error).__name__
                + ")."
            )
        )
