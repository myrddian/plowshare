"""Agent-facing outgoing integration, fixed capabilities and uncertain-effect recovery."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from plowshare import Delivery, TransportError
from plowshare.contracts import (
    ExternalMessageDto,
    ExternalMessageDtoPartsItemVariant4Dto,
    ExternalMessageDtoPartsItemVariant4DtoData,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant1Dto,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant1DtoArgumentsDto,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant2Dto,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant2DtoArgumentsDto,
    OutgoingClaimResultDto,
    OutgoingWorkDto,
)
from support import STAMP, CountingCollector, FixturePort, MemoryReceipts, configuration

from plowshare_privacy.journal import FileReceipts
from plowshare_privacy.peer import IntegrationPeer, result_message
from plowshare_privacy.peer_journal import FilePeerReceipts, PeerReceipt
from plowshare_privacy.tools import WorkerTools
from plowshare_privacy.worker import ReconciliationRequired, Worker


def action(
    binding: str = "home-network", *, arbitrary: bool = False
) -> ExternalMessageDtoPartsItemVariant4DtoDataVariant2Dto:
    return ExternalMessageDtoPartsItemVariant4DtoDataVariant2Dto(
        schema="plowshare-integration/1",
        binding=binding,
        operation="actions.execute",
        arguments=ExternalMessageDtoPartsItemVariant4DtoDataVariant2DtoArgumentsDto(
            action="network.scan",
            parameters={"targets": "192.0.2.1"} if arbitrary else {},
        ),
    )


def work(data: ExternalMessageDtoPartsItemVariant4DtoData) -> OutgoingWorkDto:
    return OutgoingWorkDto(
        id=str(uuid4()),
        request_id=str(uuid4()),
        project="network-privacy",
        peer="privacy-scanner",
        conversation=None,
        created_at=STAMP,
        error=None,
        remote_context=None,
        remote_task=None,
        result=None,
        revision=1,
        state="DISPATCHED",
        cancel_requested=False,
        message=ExternalMessageDto(
            parts=(ExternalMessageDtoPartsItemVariant4Dto(data=data),)
        ),
    )


class MemoryPeerReceipts:
    def __init__(self) -> None:
        self.rows: dict[str, PeerReceipt] = {}

    def all(self) -> tuple[PeerReceipt, ...]:
        return tuple(self.rows.values())

    def save(self, receipt: PeerReceipt) -> None:
        self.rows[receipt.id] = receipt


class FixtureOutgoing:
    def __init__(self, values: list[OutgoingWorkDto]):
        self.queue = list(values)
        self.rows = {item.id: item for item in values}
        self.reports = 0
        self.advertisements = 0

    async def advertise(self) -> None:
        self.advertisements += 1

    async def claim(self) -> OutgoingClaimResultDto:
        return (
            OutgoingClaimResultDto(action="send", work=self.queue.pop(0))
            if self.queue
            else OutgoingClaimResultDto(action=None, work=None)
        )

    async def report(self, receipt: PeerReceipt) -> OutgoingWorkDto:
        self.reports += 1
        value = replace(
            self.rows[receipt.id],
            state=receipt.state,
            revision=receipt.revision + 1,
            result=result_message(receipt),
        )
        self.rows[receipt.id] = value
        return value

    async def status(self, identity: str) -> OutgoingWorkDto:
        return self.rows[identity]


class PeerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = replace(
            configuration(Path(self.temporary.name)), outgoing_peer="privacy-scanner"
        )
        self.relay = FixturePort(self.config)
        self.receipts = MemoryReceipts()
        self.collector = CountingCollector(self.config)
        self.worker = Worker(self.config, self.relay, self.receipts, self.collector)
        self.tools = WorkerTools(self.worker)

    async def test_agent_action_requests_scan_then_agent_reads_scope(self) -> None:
        request = work(action())
        read = work(
            ExternalMessageDtoPartsItemVariant4DtoDataVariant1Dto(
                schema="plowshare-integration/1",
                binding=self.config.collector,
                operation="states.read",
                arguments=ExternalMessageDtoPartsItemVariant4DtoDataVariant1DtoArgumentsDto(
                    entities=("network.scope", "network.scan_list")
                ),
            )
        )
        port = FixtureOutgoing([request, read])
        journal = MemoryPeerReceipts()
        peer = IntegrationPeer(self.config, port, self.tools, journal)
        await peer.poll()
        self.assertEqual(self.collector.count, 0)
        self.assertEqual(self.relay.published[0].request_id, request.request_id)
        self.assertEqual(port.rows[request.id].state, "COMPLETED")
        await self.worker.poll()
        await peer.poll()
        self.assertEqual(self.collector.count, 1)
        self.assertEqual(port.rows[read.id].state, "COMPLETED")
        self.assertIn('"phase": "done"', journal.all()[1].result)
        self.assertEqual(port.advertisements, 1)

    async def test_foreign_binding_and_arbitrary_scope_are_rejected_without_effect(
        self,
    ) -> None:
        requests = [work(action("foreign")), work(action(arbitrary=True))]
        port = FixtureOutgoing(requests)
        peer = IntegrationPeer(self.config, port, self.tools, MemoryPeerReceipts())
        for _ in requests:
            await peer.poll()
        self.assertTrue(
            all(port.rows[item.id].state == "REJECTED" for item in requests)
        )
        self.assertEqual(self.relay.published, [])

    async def test_cancel_before_execution_never_requests_scan(self) -> None:
        request = replace(work(action()), cancel_requested=True)
        port = FixtureOutgoing([request])
        await IntegrationPeer(
            self.config, port, self.tools, MemoryPeerReceipts()
        ).poll()
        self.assertEqual(port.rows[request.id].state, "CANCELED")
        self.assertEqual(self.relay.published, [])

    async def test_lost_report_is_read_reconciled_without_reexecution_or_resubmission(
        self,
    ) -> None:
        request = work(action())
        port, journal = FixtureOutgoing([request]), MemoryPeerReceipts()
        peer = IntegrationPeer(self.config, port, self.tools, journal)
        original = port.report

        async def lost(receipt: PeerReceipt) -> OutgoingWorkDto:
            await original(receipt)
            raise TransportError(Delivery.UNKNOWN, "Fixture report reply lost")

        with patch.object(port, "report", lost):
            with self.assertRaises(TransportError):
                await peer.poll()
        with self.assertRaises(ReconciliationRequired):
            await peer.poll()
        await peer.reconcile()
        await peer.poll()
        self.assertEqual(port.reports, 1)
        self.assertEqual(len(self.relay.published), 1)
        self.assertEqual(journal.all()[0].phase, "done")

    async def test_report_absence_does_not_authorize_retry(self) -> None:
        request = work(action())
        port, journal = FixtureOutgoing([request]), MemoryPeerReceipts()
        peer = IntegrationPeer(self.config, port, self.tools, journal)

        async def lost(receipt: PeerReceipt) -> OutgoingWorkDto:
            raise TransportError(Delivery.UNKNOWN, "Fixture report submission unknown")

        with patch.object(port, "report", lost), self.assertRaises(TransportError):
            await peer.poll()
        with self.assertRaises(ReconciliationRequired):
            await peer.reconcile()
        self.assertEqual(len(self.relay.published), 1)

    async def test_interrupted_execution_survives_restart_as_unknown_without_effect(
        self,
    ) -> None:
        request = work(action())
        port = FixtureOutgoing([request])
        port.queue.clear()
        with FileReceipts(self.config) as owner:
            ledger = FilePeerReceipts(self.config, owner)
            ledger.save(
                PeerReceipt(request.id, 1, "executing", "UNKNOWN", '{"pending":true}')
            )
        with FileReceipts(self.config) as owner:
            ledger = FilePeerReceipts(self.config, owner)
            await IntegrationPeer(self.config, port, self.tools, ledger).poll()
            self.assertEqual(ledger.all()[0].phase, "done")
        self.assertEqual(port.rows[request.id].state, "UNKNOWN")
        self.assertEqual(self.relay.published, [])
