"""Application actions shared by the CLI and web interface."""

from dataclasses import replace

from plowshare import Refusal

from .journal import Intent, Journal
from .model import STATUSES, Document, EpicFields, TicketFields, choice, identity, tags
from .sdk import BoardPort, MutationResult

# Infrastructure/model failures do not establish whether a mutation committed.
# Only explicit input/access/conflict outcomes can settle a retained refusal.
DEFINITIVE_REFUSALS = frozenset(
    {
        "BAD_REQUEST",
        "NOT_FOUND",
        "CONFLICT",
        "UNSUPPORTED_DOCUMENT",
        "VALIDATION_FAILED",
    }
)


class RevisionConflict(ValueError):
    """The displayed document is already behind its current public head."""


class Board:
    def __init__(self, port: BoardPort, journal: Journal):
        self.port, self.journal = port, journal

    async def submit(self, intent: Intent) -> MutationResult:
        self.journal.prepare(intent)
        return await self._send(intent)

    async def _send(self, intent: Intent) -> MutationResult:
        # A lost reply or interrupted local settlement leaves the exact request
        # pending. No exception handler guesses non-delivery or submits again.
        try:
            result = await self.port.submit(intent)
        except Refusal as error:
            if error.code in DEFINITIVE_REFUSALS:
                self.journal.settle(intent, "refused")
            raise
        self.journal.settle(intent, "confirmed")
        return result

    async def recover(self, request: str) -> MutationResult:
        """Explicitly recover the server receipt with the exact retained request."""
        intent, _ = self.journal.read(identity(request))
        return await self._send(intent)

    async def update(
        self,
        kind: str,
        resource: str,
        expected: str,
        request: str,
        fields: TicketFields | EpicFields,
        body: str | None = None,
    ) -> MutationResult:
        """Revise the displayed document; metadata-only saves do not replace bytes."""
        current = await self.port.get(kind, identity(resource))
        if current.revision != identity(expected):
            raise RevisionConflict("Document changed; reload and review before saving")
        return await self.submit(
            Intent(
                "revise" if body is None else "body",
                kind,
                identity(request),
                fields,
                resource,
                expected,
                body,
            )
        )

    async def expected_ticket(self, resource: str, expected: str) -> Document:
        document = await self.port.get("ticket", identity(resource))
        if document.revision != identity(expected):
            raise RevisionConflict("Ticket changed; reload and review before saving")
        if not isinstance(document.fields, TicketFields):
            raise ValueError("Expected ticket fields")
        return document

    async def move(
        self, resource: str, expected: str, request: str, status: str
    ) -> MutationResult:
        choice(status, STATUSES, "status")
        document = await self.expected_ticket(resource, expected)
        if not isinstance(document.fields, TicketFields):
            raise ValueError("Expected ticket fields")
        return await self.submit(
            Intent(
                "revise",
                "ticket",
                identity(request),
                replace(document.fields, status=status),
                resource,
                expected,
            )
        )

    async def set_epics(
        self, resource: str, expected: str, request: str, epics: tuple[str, ...]
    ) -> MutationResult:
        if len(set(epics)) != len(epics):
            raise ValueError("Duplicate epic selection")
        for epic in epics:
            selected = await self.port.get("epic", identity(epic))
            if not isinstance(selected.fields, EpicFields):
                raise ValueError("Expected epic fields")
        document = await self.expected_ticket(resource, expected)
        # Tags have their own server control, not the metadata expected-head CAS.
        # Refresh and refuse an already stale selection before taking a snapshot;
        # another writer can still race. This first board is single-maintainer.
        current = await self.port.get("ticket", resource)
        if current.revision != expected:
            raise RevisionConflict(
                "Ticket changed; refresh before replacing epic membership"
            )
        updated = tags(
            tuple(v for v in current.tags if not v.startswith("epic:"))
            + tuple("epic:" + identity(epic) for epic in epics)
        )
        return await self.submit(
            Intent(
                "tags",
                "ticket",
                identity(request),
                document.fields,
                resource,
                expected,
                tags=updated,
            )
        )

    async def set_topics(
        self, resource: str, expected: str, request: str, topics: tuple[str, ...]
    ) -> MutationResult:
        checked = tags(topics)
        if any(v.startswith("epic:") for v in checked):
            raise ValueError("Use set-epics to edit epic membership")
        document = await self.expected_ticket(resource, expected)
        current = await self.port.get("ticket", resource)
        if current.revision != expected:
            raise RevisionConflict(
                "Ticket changed; refresh before replacing topic tags"
            )
        updated = tags(
            tuple(v for v in current.tags if v.startswith("epic:")) + checked
        )
        return await self.submit(
            Intent(
                "tags",
                "ticket",
                identity(request),
                document.fields,
                resource,
                expected,
                tags=updated,
            )
        )
