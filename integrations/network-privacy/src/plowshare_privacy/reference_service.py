"""The collector's device reference library: its seed, its loop and its status.

``ReferenceService`` is what the collector and the dashboard share. It wraps a
``ReferenceLibrary`` with the periodic ``advance`` pass and a visible loop
error, and summarises receipts for the dashboard's attention band.

Each pass first requeues the references due for their periodic refresh
(``schedule_refresh``), then advances. The pass runs on the deep-scan cadence.
``advance`` re-raises only ``TransportError``; the loop records that once and
keeps going, so connection loss neither crashes the collector nor retries a
receipt by itself. A receipt that failed or was blocked stays that way until
the owner presses Retry.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

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
    def __init__(
        self,
        library: ReferenceLibrary,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self.library = library
        self.now = now
        self.error: str | None = None

    @classmethod
    def open(cls, root: Path, port: ReferencePort) -> ReferenceService:
        """Build the service and queue the shipped seed once.

        The packaged seed is validated first and an invalid seed raises
        ``ValueError``, which stops start-up. A journal that cannot be read or
        written raises ``ValueError`` or ``OSError``; ``open_references`` turns
        that into a disabled feature instead.
        """
        seed = load_seed()
        library = ReferenceLibrary(ReferenceJournal(root), port)
        library.plan_seed(seed)
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

    def refresh_days(self) -> int:
        return self.library.refresh_days()

    def set_refresh_days(self, days: int) -> None:
        self.library.set_refresh_days(days)

    async def poll(self) -> None:
        """Requeue due refreshes, then one ``advance`` pass.

        A clean pass clears the recorded error. The refresh is a journal edit
        only; its acquire is sent by this same ``advance``.
        """
        self.library.schedule_refresh(self.now())
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


JOURNAL_UNREADABLE = (
    "Reference documents need attention: their journal could not be read."
)


def open_references(
    root: Path, port: ReferencePort
) -> tuple[ReferenceService | None, str | None]:
    """Open the service, or report why its journal cannot be used.

    An unreadable, linked, non-private or invalid journal disables the reference
    library only; collection starts and ``/api/status`` shows the detail. Nothing
    is deleted or rewritten. An invalid packaged seed is a build defect and still
    raises, so it stops start-up.
    """
    seed = load_seed()
    try:
        library = ReferenceLibrary(ReferenceJournal(root), port)
        library.plan_seed(seed)
    except (OSError, ValueError):
        return None, JOURNAL_UNREADABLE
    return ReferenceService(library), None
