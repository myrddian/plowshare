"""Project-scoped profile research over the public Plowshare SDK."""

import json
from dataclasses import asdict
from typing import Callable

from plowshare import Client
from plowshare.contracts import (
    OrchestrationReceiptRequest,
    OrchestrationStartRequest,
    OrchestrationStatusRequest,
)

from .contracts import identifier
from .profile_research import ResearchEvidence, ResearchOutcome

DEFINITION = "profile_network_device"
CALLER = "privacy_coordinator"


class SdkResearchPort:
    def __init__(self, client: Callable[[], Client], project: str):
        self.client, self.project = client, project

    async def start(self, request_id: str, evidence: ResearchEvidence) -> str:
        admission = (
            await self.client().request(
                OrchestrationStartRequest(
                    project=self.project,
                    agent=CALLER,
                    definition=DEFINITION,
                    request=json.dumps(asdict(evidence), allow_nan=False),
                    request_id=request_id,
                ),
                timeout=15,
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign profile research admission")
        return identifier(admission.id)

    async def receipt(self, request_id: str) -> str:
        admission = (
            await self.client().request(
                OrchestrationReceiptRequest(request_id=request_id), timeout=15
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign profile research receipt")
        return identifier(admission.id)

    async def outcome(self, run_id: str) -> ResearchOutcome:
        view = (
            (
                await self.client().request(
                    OrchestrationStatusRequest(id=run_id), timeout=15
                )
            )
            .require_payload()
            .orchestration
        )
        if (view.id, view.project, view.definition, view.caller_agent) != (
            run_id,
            self.project,
            DEFINITION,
            CALLER,
        ):
            raise ValueError("Foreign profile research result")
        return ResearchOutcome(view.state, view.result)
