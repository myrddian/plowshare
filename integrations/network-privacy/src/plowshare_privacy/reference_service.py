"""The collector's device reference library: its seed, its loop and its status.

``ReferenceService`` is what the collector and the dashboard share. It wraps a
``ReferenceLibrary`` with the periodic ``advance`` pass and a visible loop
error, and summarises receipts for the dashboard's attention band.

Each pass first requeues the references due for their periodic refresh
(``schedule_refresh``), then advances. The pass runs on the deep-scan cadence.
``advance`` re-raises ``TransportError``; ``poll`` records that as a waiting
connection. Any other failure of a pass (a refused or contract-breaking
acquisition listing, an unreadable or unwritable journal) is caught by ``run``,
which records a fixed owner-facing ``error`` and keeps looping, as the deep scan
loop does. Neither crashes the collector nor retries a receipt by itself: the
next pass re-reads the journal and continues where it stopped. A receipt that
failed or was blocked, or whose uncertain acquire was never found, stays that
way until the owner presses Retry.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

from plowshare import TransportError
from plowshare.contracts import SelectionDtoScopeVariant3Dto

from .reference_library import ReferenceJournal, ReferenceLibrary, ReferenceReceipt
from .reference_port import ReferencePort
from .reference_seed import load_seed

# The same cadence as the deep scan loop (DeepScanner polls every 5 seconds).
POLL_SECONDS = 5

# The server fetches one page at a time and finishes a ticket once the page is
# saved (summaries run later), so a page normally waits seconds. Past this, the
# owner is told; nothing is re-sent, because a re-sent fetch would queue behind
# the same server work.
SLOW_AFTER = timedelta(minutes=10)

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
    """Receipts needing the owner's attention. Retired seed entries are not counted.

    ``unresolved`` is a ``submitted`` receipt with an error: its acquire's delivery
    was uncertain and no matching ticket was found, so only the owner's Retry (an
    idempotent replay with the same request id) moves it on.
    ``slow`` is an ``awaiting`` receipt the server has held for ``SLOW_AFTER`` or
    longer; it has no Retry, only an explanation.
    """

    failed: int
    blocked: int
    unresolved: int
    slow: int


class ReferenceService:
    def __init__(
        self,
        library: ReferenceLibrary,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self.library = library
        self.now = now
        self.error: str | None = None

    def view(self) -> tuple[ReferenceReceipt, ...]:
        return self.library.view()

    def counts(self) -> ReferenceCounts:
        live = [r for r in self.library.view() if not r.retired]
        return ReferenceCounts(
            failed=sum(r.phase == "failed" for r in live),
            blocked=sum(r.phase == "blocked" for r in live),
            unresolved=sum(
                r.phase == "submitted" and r.error is not None for r in live
            ),
            slow=sum(self.slow(r) for r in live),
        )

    def waiting_minutes(self, receipt: ReferenceReceipt) -> int | None:
        """Whole minutes the server has held an ``awaiting`` fetch, else None.

        A clock behind the recorded start reads as zero rather than negative.
        """
        if receipt.phase != "awaiting" or receipt.awaiting_since is None:
            return None
        since = datetime.fromisoformat(receipt.awaiting_since.replace("Z", "+00:00"))
        return max(0, int((self.now() - since).total_seconds() // 60))

    def slow(self, receipt: ReferenceReceipt) -> bool:
        minutes = self.waiting_minutes(receipt)
        return minutes is not None and minutes >= SLOW_AFTER.total_seconds() // 60

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
        """Poll every ``POLL_SECONDS`` until ``stop``; no failure ends the loop.

        A failed pass leaves the journal as its last save wrote it, so the next
        pass resumes from there; nothing is re-sent by the loop beyond what
        ``advance`` would send anyway. The error stays until a clean pass.
        """
        while not stop.is_set():
            try:
                await self.poll()
            except Exception:
                # Shown as fixed text: a refusal's or OS error's own words may
                # carry server text or local paths the dashboard must not show.
                self.error = REFERENCES_FAILED
            try:
                await asyncio.wait_for(stop.wait(), POLL_SECONDS)
            except TimeoutError:
                continue


REFERENCES_FAILED = (
    "Reference documents need attention: the last pass failed. Fetched and queued "
    "references were kept; it tries again on the next pass."
)

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
