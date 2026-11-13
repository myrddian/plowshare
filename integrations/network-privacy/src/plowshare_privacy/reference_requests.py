"""The reference request journal: the Ask agent's requests for missing product docs.

The agent can only say *which product* it lacks documentation for and why. It never
supplies a link: the owner reads the request on the dashboard and either approves
one to five https pages or dismisses it. Approved pages go to the reference
library as owner receipts, which the library's loop fetches. Question text is
stored for the owner to read and is never parsed, fetched or queued.

Rules, all enforced here rather than by callers:

- ``request_id`` (the harness invocation) is the idempotency key: repeating it
  returns the stored request unchanged, whatever its state has become since.
- One request per product waits at a time; a further ask returns it.
- A dismissed request suppresses the same product for 30 days; its ``decided_at``
  starts the window. After that a new request is allowed.
- A request is decided once: ``approve`` and ``dismiss`` require ``pending``.
- Approved URLs pass ``reference_url``, are unique among themselves and against
  every library receipt, and are queued with ``ReferenceLibrary.add_owner``.

The journal (``reference-requests.json``) is private, unlinked and size-bounded,
and is validated again as it is serialized. The journal is bounded: when it is full,
decided requests older than the suppression window are dropped first; if none
can be dropped, a new request is refused rather than evicting something that is
still suppressing or waiting. Approval queues library receipts before it records
the decision; a crash between the two leaves the request pending with its URLs
already known to the library. Approving them again is refused as duplicates, so
the owner would dismiss that request.
"""

from __future__ import annotations

import json
import unicodedata
from dataclasses import asdict, dataclass, replace
from datetime import datetime, timedelta
from pathlib import Path
from typing import Callable, Literal
from urllib.parse import urlsplit

from .contracts import (
    items,
    load_json,
    object_fields,
    parse_json,
    timestamp,
    utc_now,
    uuid,
)
from .private_files import replace_private
from .reference_library import ReferenceLibrary
from .reference_seed import product_slug, reference_url
from .tools import QUESTION_LIMIT, ReferenceRequestOutcome, ToolRefusal

State = Literal["pending", "filled", "dismissed"]
MAX_URLS = 5
SUPPRESSION = timedelta(days=30)
MAX_LEDGER_BYTES = 256 * 1024
UNAVAILABLE = "Documentation requests are unavailable on this collector right now."


# The longest host-and-path shown in an owner page's title; the whole title
# stays well inside the library's 200-character limit with a 48-character slug.
MAX_TITLE_LOCATION = 96


def owner_title(product: str, url: str) -> str:
    """Title an owner-approved page by its product and where it lives.

    Citations then tell an owner's pages apart, as in
    ``caddy documentation \u2014 caddyserver.com/docs/api``. Only the host
    (lower-cased) and path are used; query and fragment are dropped, as are
    control and format characters (bidirectional overrides included), and the
    location is cut to ``MAX_TITLE_LOCATION`` characters with an ellipsis.
    ``url`` must already have passed ``reference_url``.
    """
    parts = urlsplit(url)
    location = (parts.hostname or "") + parts.path.rstrip("/")
    location = "".join(
        c for c in location if not unicodedata.category(c).startswith("C")
    )
    if len(location) > MAX_TITLE_LOCATION:
        location = location[: MAX_TITLE_LOCATION - 1] + "\u2026"
    return f"{product} documentation \u2014 {location}"


def _parsed(stamp: str) -> datetime:
    return datetime.fromisoformat(timestamp(stamp).replace("Z", "+00:00"))


def _question(value: object) -> str:
    # The tool's own rule: new lines are fine, other control characters are not.
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > QUESTION_LIMIT
        or any((ord(c) < 32 and c != "\n") or ord(c) == 127 for c in value)
    ):
        raise ValueError("Invalid reference request question")
    return value


def _state(value: object) -> State:
    if value == "pending":
        return "pending"
    if value == "filled":
        return "filled"
    if value == "dismissed":
        return "dismissed"
    raise ValueError("Invalid reference request state")


def _device(value: object) -> str | None:
    # Opaque to this journal: the tool already checked it names a monitored device.
    if value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > 128:
        raise ValueError("Invalid reference request device")
    return value


@dataclass(frozen=True)
class ReferenceRequest:
    """One ask for a product's documentation, from the agent to the owner.

    ``id`` is the invocation that created it. ``urls`` are the pages the owner
    approved and are empty unless ``state`` is ``filled``.
    """

    id: str
    product: str
    question: str
    device_id: str | None
    state: State
    created_at: str
    decided_at: str | None
    urls: tuple[str, ...]

    def __post_init__(self) -> None:
        uuid(self.id)
        product_slug(self.product)
        _question(self.question)
        _device(self.device_id)
        _state(self.state)
        timestamp(self.created_at)
        if self.decided_at is not None:
            timestamp(self.decided_at)
        if (self.state == "pending") != (self.decided_at is None):
            raise ValueError("Only a decided reference request has a decision time")
        if len(self.urls) > MAX_URLS or len(set(self.urls)) != len(self.urls):
            raise ValueError("Invalid reference request links")
        for url in self.urls:
            reference_url(url)
        if (self.state == "filled") != bool(self.urls):
            raise ValueError("Only a filled reference request has links")

    @classmethod
    def decode(cls, value: object) -> ReferenceRequest:
        row = object_fields(
            value,
            {
                "id",
                "product",
                "question",
                "device_id",
                "state",
                "created_at",
                "decided_at",
                "urls",
            },
        )
        decided = row["decided_at"]
        return cls(
            uuid(row["id"]),
            product_slug(row["product"]),
            _question(row["question"]),
            _device(row["device_id"]),
            _state(row["state"]),
            timestamp(row["created_at"]),
            timestamp(decided) if decided is not None else None,
            tuple(reference_url(u) for u in items(row["urls"], MAX_URLS)),
        )


@dataclass(frozen=True)
class RequestLedger:
    """Oldest first. Ids are unique and a product has at most one pending request."""

    requests: tuple[ReferenceRequest, ...] = ()

    def __post_init__(self) -> None:
        if len(self.requests) > ReferenceRequests.MAX_REQUESTS:
            raise ValueError("Too many reference requests")
        pending = [r.product for r in self.requests if r.state == "pending"]
        if len({r.id for r in self.requests}) != len(self.requests) or len(
            set(pending)
        ) != len(pending):
            raise ValueError("Conflicting reference requests")

    @classmethod
    def decode(cls, value: object) -> RequestLedger:
        row = object_fields(value, {"requests"})
        return cls(
            tuple(
                ReferenceRequest.decode(v)
                for v in items(row["requests"], ReferenceRequests.MAX_REQUESTS)
            )
        )


class RequestJournal:
    """The private ledger file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "reference-requests.json"

    def read(self) -> RequestLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Reference request journal cannot be linked")
        if not self.path.exists():
            return RequestLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Reference request journal must be private")
        return RequestLedger.decode(
            load_json(self.path, maximum_bytes=MAX_LEDGER_BYTES)
        )

    def save(self, value: RequestLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        RequestLedger.decode(parse_json(content, maximum_bytes=MAX_LEDGER_BYTES))
        replace_private(self.path, content)


class ReferenceRequests:
    """Journal of requests; see the module notes for the rules it enforces.

    Synchronous and run on the collector's event loop, like the library's other
    journal edits: each method reads, decides and saves with no await between.
    """

    MAX_REQUESTS = 64

    def __init__(
        self,
        root: Path,
        library: ReferenceLibrary,
        clock: Callable[[], str] = utc_now,
    ):
        self.journal = RequestJournal(root)
        self.library = library
        self.clock = clock

    def request(
        self, product: str, question: str, device_id: str | None, request_id: str
    ) -> ReferenceRequest:
        """Record a request, or return the one that already covers it."""
        request_id, product = uuid(request_id), product_slug(product)
        created = ReferenceRequest(
            request_id,
            product,
            _question(question),
            _device(device_id),
            "pending",
            timestamp(self.clock()),
            None,
            (),
        )
        ledger = self.journal.read()
        now = _parsed(created.created_at)
        for existing in ledger.requests:
            if existing.id == request_id:
                return existing
        for existing in ledger.requests:
            if existing.product != product:
                continue
            if existing.state == "pending" or (
                existing.state == "dismissed"
                and existing.decided_at is not None
                and now - _parsed(existing.decided_at) < SUPPRESSION
            ):
                return existing
        kept = list(ledger.requests)
        if len(kept) >= self.MAX_REQUESTS:
            # Only a decided request past its suppression window is expendable.
            kept = [r for r in kept if not self._expired(r, now)]
            if len(kept) >= self.MAX_REQUESTS:
                raise ValueError("Too many reference requests are waiting")
        self.journal.save(RequestLedger((*kept, created)))
        return created

    def pending(self) -> tuple[ReferenceRequest, ...]:
        return tuple(r for r in self.journal.read().requests if r.state == "pending")

    def approve(self, request_id: str, urls: tuple[str, ...]) -> ReferenceRequest:
        """Queue the owner's pages for fetching and mark the request filled."""
        ledger = self.journal.read()
        index, current = self._pending(ledger, request_id)
        if not 1 <= len(urls) <= MAX_URLS:
            raise ValueError("Approve one to five reference links")
        checked = tuple(reference_url(u) for u in urls)
        known = {r.url for r in self.library.view()}
        if len(set(checked)) != len(checked) or known.intersection(checked):
            raise ValueError("Reference links must be new and unique")
        # Validated in full above; add_owner then cannot reject a later link and
        # leave only some of them queued.
        for url in checked:
            self.library.add_owner(
                current.product, owner_title(current.product, url), url
            )
        return self._decide(ledger, index, "filled", checked)

    def dismiss(self, request_id: str) -> ReferenceRequest:
        ledger = self.journal.read()
        index, current = self._pending(ledger, request_id)
        return self._decide(ledger, index, "dismissed", ())

    def _pending(
        self, ledger: RequestLedger, request_id: str
    ) -> tuple[int, ReferenceRequest]:
        request_id = uuid(request_id)
        for index, existing in enumerate(ledger.requests):
            if existing.id == request_id:
                if existing.state != "pending":
                    raise ValueError("That reference request was already decided")
                return index, existing
        raise ValueError("Unknown reference request")

    def _decide(
        self,
        ledger: RequestLedger,
        index: int,
        state: State,
        urls: tuple[str, ...],
    ) -> ReferenceRequest:
        decided = replace(
            ledger.requests[index],
            state=state,
            urls=urls,
            decided_at=timestamp(self.clock()),
        )
        requests = list(ledger.requests)
        requests[index] = decided
        self.journal.save(RequestLedger(tuple(requests)))
        return decided

    @staticmethod
    def _expired(request: ReferenceRequest, now: datetime) -> bool:
        return (
            request.decided_at is not None
            and now - _parsed(request.decided_at) >= SUPPRESSION
        )


class AssistantReferenceRequests:
    """The agent-facing ``ReferenceRequestService`` over the journal.

    It turns a stored request into one sentence for the agent. Every sentence says
    the owner decides; none says the question is answered or documentation exists.
    """

    def __init__(self, requests: ReferenceRequests):
        self.requests = requests

    def request(
        self, product: str, question: str, device_id: str | None, request_id: str
    ) -> ReferenceRequestOutcome:
        try:
            stored = self.requests.request(product, question, device_id, request_id)
        except ValueError as error:
            # The tool validated every argument, so a failure here is the journal:
            # full, unreadable or invalid. Fixed wording; nothing is echoed.
            if "waiting" in str(error):
                raise ToolRefusal(
                    "Too many documentation requests are waiting for the owner."
                ) from None
            raise ToolRefusal(UNAVAILABLE) from None
        except OSError:
            raise ToolRefusal(UNAVAILABLE) from None
        if stored.state == "dismissed":
            return ReferenceRequestOutcome(
                "dismissed",
                "The owner recently declined a request for this product's "
                "documentation, so nothing new was queued.",
            )
        if stored.state == "filled":
            return ReferenceRequestOutcome(
                "filled",
                "The owner approved documentation links for this request; they are "
                "being fetched and may not be searchable yet.",
            )
        if stored.id != request_id:
            return ReferenceRequestOutcome(
                "pending",
                "A request for this product's documentation is already waiting "
                "for the owner to review.",
            )
        return ReferenceRequestOutcome(
            "queued",
            "Queued for the owner to review. No documentation has been fetched, "
            "so tell the user it is not available yet.",
        )
