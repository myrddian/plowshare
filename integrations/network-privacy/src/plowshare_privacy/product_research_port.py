"""Product research through existing, typed public SDK operations only."""

from __future__ import annotations

from typing import Callable

from plowshare import Client
from plowshare.contracts import (
    OrchestrationCancelRequest,
    OrchestrationReceiptRequest,
    OrchestrationStartRequest,
    OrchestrationStatusRequest,
)

from .contracts import identifier, uuid
from .product_research import ProductQuestion, ProductResearchPort, ProductRun

DEFINITION = "research_product_question"
CALLER = "privacy_coordinator"


class SdkProductResearchPort(ProductResearchPort):
    def __init__(self, client: Callable[[], Client], project: str):
        self.client, self.project = client, project

    async def start(self, request_id: str, question: ProductQuestion) -> str:
        admission = (
            await self.client().request(
                OrchestrationStartRequest(
                    project=self.project,
                    agent=CALLER,
                    definition=DEFINITION,
                    request=question.encode(),
                    request_id=uuid(request_id),
                ),
                timeout=15,
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign product research admission")
        return identifier(admission.id)

    async def receipt(self, request_id: str) -> str:
        admission = (
            await self.client().request(
                OrchestrationReceiptRequest(request_id=uuid(request_id)), timeout=15
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign product research receipt")
        return identifier(admission.id)

    async def outcome(self, run_id: str) -> ProductRun:
        view = (
            (
                await self.client().request(
                    OrchestrationStatusRequest(id=identifier(run_id)), timeout=15
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
            raise ValueError("Foreign product research result")
        return ProductRun(view.state, view.result)

    async def cancel(self, run_id: str) -> None:
        # Do not cancel a foreign run even if a local journal has been corrupted.
        outcome = await self.outcome(run_id)
        if outcome.state in ("finished", "failed", "cancelled"):
            return
        result = (
            await self.client().request(
                OrchestrationCancelRequest(id=identifier(run_id)), timeout=15
            )
        ).require_payload()
        if result.id != run_id:
            raise ValueError("Foreign product research cancellation")
