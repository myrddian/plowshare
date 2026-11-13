"""The collector's device reference library: its seed, its loop and its status.

``ReferenceService`` is what the collector and the dashboard share. It wraps a
``ReferenceLibrary`` with the periodic ``advance`` pass and a visible loop
error, and summarises receipts for the dashboard's attention band.

The pass runs on the deep-scan cadence. ``advance`` re-raises only
``TransportError``; the loop records that once and keeps going, so connection
loss neither crashes the collector nor retries a receipt by itself. A receipt
that failed or was blocked stays that way until the owner presses Retry.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from pathlib import Path

from plowshare import TransportError
from plowshare.contracts import SelectionDtoScopeVariant3Dto

from .reference_library import ReferenceJournal, ReferenceLibrary, ReferenceReceipt
from .reference_port import ReferencePort
from .reference_seed import load_seed

# The same cadence as the deep scan loop (DeepScanner polls every 5 seconds).
POLL_SECONDS = 5

PHASE_LABELS = {
    "queued": "Waiting to be fetched",
    "submitted": "Fetch sent",
    "awaiting": "Being fetched",
    "blocked": "Held by a Library gate",
    "retained": "Fetched, being tagged",
    "tagged": "Ready",
    "failed": "Failed",
}


def project_scope(project: str) -> SelectionDtoScopeVariant3Dto:
    """The collector's project-only scope, as its evidence port uses."""
    return SelectionDtoScopeVariant3Dto(
        kind="project", project=project, include_global=False
    )


@dataclass(frozen=True)
class ReferenceCounts:
    """Receipts needing the owner's attention. Retired seed entries are not counted."""

    failed: int
    blocked: int


class ReferenceService:
    def __init__(self, library: ReferenceLibrary):
        self.library = library
        self.error: str | None = None

    @classmethod
    def open(cls, root: Path, port: ReferencePort) -> ReferenceService:
        """Build the service and queue the shipped seed once.

        A journal or seed that cannot be read raises ``ValueError`` and stops
        start-up, as other private journals do.
        """
        library = ReferenceLibrary(ReferenceJournal(root), port)
        library.plan_seed(load_seed())
        return cls(library)

    def view(self) -> tuple[ReferenceReceipt, ...]:
        return self.library.view()

    def counts(self) -> ReferenceCounts:
        live = [r for r in self.library.view() if not r.retired]
        return ReferenceCounts(
            failed=sum(r.phase == "failed" for r in live),
            blocked=sum(r.phase == "blocked" for r in live),
        )

    async def retry(self, url: str) -> None:
        await self.library.retry(url)

    async def poll(self) -> None:
        """One ``advance`` pass; a clean pass clears the recorded error."""
        try:
            await self.library.advance()
        except TransportError as error:
            self.error = (
                "Reference documents are waiting for the Plowshare connection "
                f"({type(error).__name__}). Nothing was lost; it continues when "
                "the connection returns."
            )
        else:
            self.error = None

    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await self.poll()
            try:
                await asyncio.wait_for(stop.wait(), POLL_SECONDS)
            except TimeoutError:
                continue
