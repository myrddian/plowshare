"""Agent-callable integration through existing outgoing tools, independently of Relay."""

from __future__ import annotations

import json
from dataclasses import asdict, replace
from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    AgentCardDto,
    AgentCardDtoSkillsItemDto,
    ExternalMessageDto,
    ExternalMessageDtoPartsItemVariant1Dto,
    ExternalMessageDtoPartsItemVariant4Dto,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant1Dto,
    ExternalMessageDtoPartsItemVariant4DtoDataVariant2Dto,
    OutgoingAdvertiseRequest,
    OutgoingClaimRequest,
    OutgoingClaimResultDto,
    OutgoingReportRequest,
    OutgoingStatusRequest,
    OutgoingWorkDto,
    OutgoingWorkDtoResultVariant3Dto,
)

from .contracts import Configuration, integer, uuid
from .peer_journal import PeerReceipt, PeerReceipts
from .tools import (
    DestinationsCall,
    EvidenceCall,
    ListCall,
    PrivacyTools,
    ScanCall,
    ScopeCall,
    StatusCall,
    ToolCall,
)
from .worker import ReconciliationRequired


class OutgoingPort(Protocol):
    async def advertise(self) -> None: ...
    async def claim(self) -> OutgoingClaimResultDto: ...
    async def report(self, receipt: PeerReceipt) -> OutgoingWorkDto: ...
    async def status(self, identity: str) -> OutgoingWorkDto: ...


def result_message(receipt: PeerReceipt) -> OutgoingWorkDtoResultVariant3Dto:
    return OutgoingWorkDtoResultVariant3Dto(
        message=ExternalMessageDto(
            role="ROLE_AGENT",
            message_id=receipt.id,
            parts=(
                ExternalMessageDtoPartsItemVariant1Dto(
                    text=receipt.result, media_type="application/json"
                ),
            ),
        )
    )


class SdkOutgoingPort:
    def __init__(self, client: Client, config: Configuration):
        if config.outgoing_peer is None:
            raise ValueError("An outgoing peer must be configured explicitly")
        self.client, self.config, self.peer = client, config, config.outgoing_peer

    async def advertise(self) -> None:
        description = (
            f"Network privacy collector binding {self.config.collector}. "
            "Use plowshare-integration/1. states.read entities: network.scope, network.scan_list, "
            "network.scan_status:<request UUID>, network.evidence:<scan UUID>, network.destinations:<scan UUID>. "
            "actions.execute action network.scan with empty parameters requests a bounded Relay scan. "
            "Publication is not collection or investigation completion. No arbitrary targets or commands."
        )
        card = AgentCardDto(
            name=self.peer,
            description=description,
            version="0.1.0",
            default_input_modes=("application/json",),
            default_output_modes=("application/json",),
            skills=(
                AgentCardDtoSkillsItemDto(
                    id="network-privacy",
                    name="Network privacy observations",
                    description=description,
                    tags=("defensive", "privacy"),
                ),
            ),
        )
        (
            await self.client.request(
                OutgoingAdvertiseRequest(
                    project=self.config.project,
                    peers=(self.peer,),
                    agent_cards={self.peer: card},
                )
            )
        ).require_payload()

    async def claim(self) -> OutgoingClaimResultDto:
        return (
            await self.client.request(
                OutgoingClaimRequest(project=self.config.project, peers=(self.peer,))
            )
        ).require_payload()

    async def report(self, receipt: PeerReceipt) -> OutgoingWorkDto:
        return (
            await self.client.request(
                OutgoingReportRequest(
                    id=receipt.id,
                    revision=receipt.revision,
                    state=receipt.state,
                    result=result_message(receipt),
                )
            )
        ).require_payload()

    async def status(self, identity: str) -> OutgoingWorkDto:
        return (
            await self.client.request(OutgoingStatusRequest(id=identity))
        ).require_payload()


def calls(work: OutgoingWorkDto, binding: str) -> tuple[ToolCall, ...]:
    """Decode the already validated public integration family to fixed local capabilities."""
    if len(work.message.parts) != 1 or not isinstance(
        work.message.parts[0], ExternalMessageDtoPartsItemVariant4Dto
    ):
        raise ValueError("One structured integration part is required")
    data = work.message.parts[0].data
    if data.binding != binding:
        raise ValueError("Foreign integration binding")
    if isinstance(data, ExternalMessageDtoPartsItemVariant4DtoDataVariant2Dto):
        if data.arguments.action != "network.scan" or data.arguments.parameters:
            raise ValueError(
                "Only configured scan requests with empty parameters are allowed"
            )
        # The agent already retained this outgoing request UUID. Reuse it for the
        # Relay request rather than accept a second model-selected effect identity.
        return (ScanCall(uuid(work.request_id)),)
    if not isinstance(data, ExternalMessageDtoPartsItemVariant4DtoDataVariant1Dto):
        raise ValueError("Unsupported integration family")
    if not 1 <= len(data.arguments.entities) <= 16:
        raise ValueError("Select between 1 and 16 read aliases")
    result: list[ToolCall] = []
    for alias in data.arguments.entities:
        if alias == "network.scope":
            result.append(ScopeCall())
        elif alias == "network.scan_list":
            result.append(ListCall())
        else:
            name, separator, identity = alias.partition(":")
            if not separator:
                raise ValueError("Read alias needs an identity")
            identity = uuid(identity)
            if name == "network.scan_status":
                result.append(StatusCall(identity))
            elif name == "network.evidence":
                result.append(EvidenceCall(identity))
            elif name == "network.destinations":
                result.append(DestinationsCall(identity))
            else:
                raise ValueError("Unsupported read alias")
    return tuple(result)


class IntegrationPeer:
    def __init__(
        self,
        config: Configuration,
        port: OutgoingPort,
        tools: PrivacyTools,
        receipts: PeerReceipts,
    ):
        if config.outgoing_peer is None:
            raise ValueError("Outgoing peer is disabled")
        self.config, self.port, self.tools, self.receipts = (
            config,
            port,
            tools,
            receipts,
        )
        self.advertised = False

    def matches(self, work: OutgoingWorkDto, receipt: PeerReceipt) -> bool:
        self.validate_scope(work)
        return (
            work.id == receipt.id
            and work.revision == receipt.revision + 1
            and work.state == receipt.state
            and work.result == result_message(receipt)
        )

    def validate_scope(self, work: OutgoingWorkDto) -> None:
        if (
            work.project != self.config.project
            or work.peer != self.config.outgoing_peer
        ):
            raise ValueError("Foreign outgoing work")

    async def reconcile(self) -> None:
        for receipt in self.receipts.all():
            if receipt.phase == "reporting":
                if not self.matches(await self.port.status(receipt.id), receipt):
                    raise ReconciliationRequired(
                        "Outgoing report remains unknown; execution and report were not replayed"
                    )
                self.receipts.save(replace(receipt, phase="done"))

    async def drain(self) -> None:
        for receipt in self.receipts.all():
            if receipt.phase == "reporting":
                raise ReconciliationRequired(
                    "Outgoing report needs read-only reconciliation"
                )
            if receipt.phase == "executing":
                # The process stopped after durable admission. Never infer that
                # an external effect failed merely because its result was not saved.
                receipt = replace(
                    receipt,
                    phase="ready",
                    state="UNKNOWN",
                    result='{"error":"Interrupted execution; inspect retained scan request and outgoing receipts."}',
                )
                self.receipts.save(receipt)
            if receipt.phase == "ready":
                self.receipts.save(replace(receipt, phase="reporting"))
                if not self.matches(await self.port.report(receipt), receipt):
                    raise ReconciliationRequired(
                        "Outgoing report acknowledgement differs"
                    )
                self.receipts.save(replace(receipt, phase="done"))

    async def poll(self) -> None:
        await self.drain()
        if not self.advertised:
            await self.port.advertise()
            self.advertised = True
        claimed = await self.port.claim()
        work = claimed.work
        if work is None:
            if claimed.action is not None:
                raise ValueError("Outgoing action has no work")
            return
        self.validate_scope(work)
        if any(item.id == work.id for item in self.receipts.all()):
            raise ReconciliationRequired("Outgoing work was already admitted locally")
        revision = work.revision
        if revision % 1:
            raise ValueError("Outgoing revision must be integral")
        receipt = PeerReceipt(
            uuid(work.id),
            integer(int(revision), 0, 2**53 - 1),
            "executing",
            "UNKNOWN",
            '{"error":"Execution outcome pending"}',
        )
        self.receipts.save(receipt)
        if claimed.action == "cancel" or work.cancel_requested:
            receipt = replace(
                receipt,
                phase="ready",
                state="CANCELED",
                result='{"canceled":true,"executed":false}',
            )
        else:
            try:
                if claimed.action != "send":
                    raise ValueError("No remote task exists to observe")
                plan = calls(work, self.config.collector)
                results = [asdict(await self.tools.execute(call)) for call in plan]
                content = json.dumps({"results": results}, allow_nan=False)
                if len(content.encode()) > 65536:
                    raise ValueError(
                        "Integration result bound reached; read a selected evidence revision"
                    )
                receipt = replace(
                    receipt, phase="ready", state="COMPLETED", result=content
                )
            except ValueError:
                receipt = replace(
                    receipt,
                    phase="ready",
                    state="REJECTED",
                    result='{"error":"Unsupported request, unavailable evidence or oversized result"}',
                )
            # Transport/cancellation failures leave executing, preserving uncertainty.
        self.receipts.save(receipt)
        await self.drain()
