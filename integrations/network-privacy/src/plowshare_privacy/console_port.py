"""Operator read/admission capability through typed public SDK operations only."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    AgentListRequest,
    ConversationContextSnapshotRequest,
    ConversationListRequest,
    OrchestrationListRequest,
    OrchestrationReceiptRequest,
    OrchestrationStartRequest,
)

from .contracts import Configuration, identifier, text, uuid
from .journal import Receipt


@dataclass(frozen=True)
class ScheduleView:
    name: str
    cron: str
    zone: str
    paused: bool
    next_at: str | None


@dataclass(frozen=True)
class AgentHealth:
    served: tuple[str, ...]
    unavailable: tuple[str, ...]
    tools: tuple[str, ...]
    missing_tools: tuple[str, ...]
    tool_visibility: str = "projected"


@dataclass(frozen=True)
class RunSummary:
    id: str
    state: str
    created_at: str


@dataclass(frozen=True)
class Admission:
    request_id: str
    run_id: str
    state: str


class ConsolePort(Protocol):
    async def schedule(self) -> ScheduleView | None: ...
    async def agents(self) -> AgentHealth: ...
    async def runs(self) -> tuple[RunSummary, ...]: ...
    async def investigate(self, receipt: Receipt, request_id: str) -> Admission: ...
    async def investigation_receipt(self, request_id: str) -> Admission: ...


class SdkConsolePort:
    def __init__(self, client: Client, config: Configuration):
        self.client, self.config = client, config

    async def schedule(self) -> ScheduleView | None:
        # The public schedule listing has no project selection and service tokens
        # cannot call it. Absence of this read is not evidence of no registration.
        # Do not borrow the deployment administrator's credential for polling.
        return None

    async def agents(self) -> AgentHealth:
        from .native_tools import DECLARATIONS

        agents = (
            await self.client.request(AgentListRequest(project=self.config.project))
        ).require_payload()
        expected = {"privacy_coordinator", "privacy_analyst", "privacy_reviewer"}
        relevant = [value for value in agents if value.name in expected]
        served = {value.name for value in relevant if value.served}
        coordinators = [
            value
            for value in relevant
            if value.name == "privacy_coordinator" and value.served
        ]
        if len(coordinators) > 1:
            raise ValueError("Ambiguous project coordinator")
        names = {item.name for item in DECLARATIONS}
        visible: set[str] = set()
        visibility = "awaiting_context"
        if coordinators:
            # Agent listings describe definition grants, not the live dynamic
            # registry. Project an existing project conversation without counting
            # tokens or opening/running a conversation. The server resolves current
            # tools for this account and coordinator; discard all message content.
            conversations = (
                await self.client.request(
                    ConversationListRequest(project=self.config.project)
                )
            ).require_payload()
            selected = next(
                (
                    value
                    for value in conversations
                    if value.project == self.config.project
                ),
                None,
            )
            if selected is not None:
                snapshot = (
                    await self.client.request(
                        ConversationContextSnapshotRequest(
                            conversation=selected.id,
                            agent="privacy_coordinator",
                            measure=False,
                        )
                    )
                ).require_payload()
                if (
                    snapshot.conversation != selected.id
                    or snapshot.agent != "privacy_coordinator"
                ):
                    raise ValueError("Foreign tool visibility projection")
                visible = {item.name for item in snapshot.tools}
                visibility = "projected"
        return AgentHealth(
            tuple(sorted(served)),
            tuple(sorted(expected - served)),
            tuple(sorted(visible & names)),
            tuple(sorted(names - visible)) if visibility == "projected" else (),
            visibility,
        )

    async def runs(self) -> tuple[RunSummary, ...]:
        result = (
            await self.client.request(
                OrchestrationListRequest(project=self.config.project, limit=25)
            )
        ).require_payload()
        return tuple(
            RunSummary(value.id, value.state, value.created_at)
            for value in result.orchestrations
            if value.project == self.config.project
            and value.definition == "investigate_network"
        )

    async def investigate(self, receipt: Receipt, request_id: str) -> Admission:
        if (
            receipt.evidence is None
            or receipt.revision is None
            or receipt.phase != "done"
        ):
            raise ValueError(
                "Investigation needs confirmed retained evidence and completion"
            )
        completion = {
            "version": 1,
            "collector": receipt.evidence.collector,
            "scan_id": receipt.scan_id,
            "revision": receipt.revision,
            "mode": receipt.evidence.mode,
            "changes": receipt.evidence.changes,
            "issues": receipt.evidence.issues,
        }
        # This is an explicit operator start, not a fabricated Relay delivery. The
        # script uses the same typed evidence reference; server authentication owns
        # account/project authority and its normal start receipt owns lifecycle.
        request = json.dumps(
            {"payload": {"kind": "TEXT", "text": json.dumps(completion)}}
        )
        admitted = (
            await self.client.request(
                OrchestrationStartRequest(
                    project=self.config.project,
                    agent="privacy_coordinator",
                    definition="investigate_network",
                    request=request,
                    request_id=request_id,
                )
            )
        ).require_payload()
        if admitted.request_id != request_id:
            raise ValueError("Foreign investigation receipt")
        # Request identities are UUIDs; server run identities are opaque IDs.
        # Preserve the exact retained ID rather than interpreting it as a UUID.
        return Admission(
            uuid(admitted.request_id),
            identifier(admitted.id),
            text(admitted.state, 128),
        )

    async def investigation_receipt(self, request_id: str) -> Admission:
        result = (
            await self.client.request(
                OrchestrationReceiptRequest(request_id=request_id)
            )
        ).require_payload()
        if result.request_id != request_id:
            raise ValueError("Foreign investigation receipt")
        return Admission(
            uuid(result.request_id), identifier(result.id), text(result.state, 128)
        )
