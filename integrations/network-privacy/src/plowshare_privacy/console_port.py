"""Operator read/admission capability through typed public SDK operations only."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Protocol

from plowshare import Client
from plowshare.contracts import (
    AgentListRequest,
    OrchestrationListRequest,
    OrchestrationReceiptRequest,
    OrchestrationStartRequest,
    ScheduleListRequest,
)

from .contracts import Configuration, uuid
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
        # Global schedule.list is filtered at this owning boundary. No other
        # account/project names or definitions enter the private dashboard DTO.
        matches = [
            value
            for value in (
                await self.client.request(ScheduleListRequest())
            ).require_payload()
            if value.name == self.config.schedule
        ]
        if len(matches) > 1:
            raise ValueError("Ambiguous registered schedule")
        if not matches:
            return None
        item = matches[0]
        return ScheduleView(
            item.name,
            item.cron,
            item.zone,
            item.paused,
            None if item.paused else item.next_fire_at,
        )

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
        visible = set(coordinators[0].tools) if coordinators else set()
        names = {item.name for item in DECLARATIONS}
        return AgentHealth(
            tuple(sorted(served)),
            tuple(sorted(expected - served)),
            tuple(sorted(visible & names)),
            tuple(sorted(names - visible)),
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
        return Admission(uuid(admitted.request_id), uuid(admitted.id), admitted.state)

    async def investigation_receipt(self, request_id: str) -> Admission:
        result = (
            await self.client.request(
                OrchestrationReceiptRequest(request_id=request_id)
            )
        ).require_payload()
        if result.request_id != request_id:
            raise ValueError("Foreign investigation receipt")
        return Admission(uuid(result.request_id), uuid(result.id), result.state)
