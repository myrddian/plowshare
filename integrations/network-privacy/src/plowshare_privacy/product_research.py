"""Owner-requested product research; Plowshare owns execution and retained reports.

A durable UUID precedes admission. Unknown delivery is recovered by receipt only,
never by starting a replacement. Only one run advances per pass. Finished answers
stay on the request card; accepted source revisions are registered with the existing
reference library without fetching them again. Interrupting preparation is visible
and never silently repeats local probes. Journals contain projections, not another
agent runtime. All SDK use lives behind ProductResearchPort.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Awaitable, Callable, Protocol
from uuid import uuid4

from plowshare import Delivery, Refusal, TransportError

from .contracts import identifier, items, load_json, object_fields, parse_json, uuid
from .device_profiles import field
from .private_files import replace_private
from .product_probe import ProbeObservation
from .reference_library import ReferenceLibrary
from .reference_requests import ReferenceRequest, ReferenceRequests
from .reference_seed import product_slug, reference_url
from .worker import ReconciliationRequired

KINDS = ("official", "advisory", "privacy", "research", "reporting")
PHASES = (
    "queued",
    "preparing",
    "submitted",
    "running",
    "retaining",
    "finished",
    "failed",
)
MAX_ENTRIES = 64
MAX_BYTES = 24 * 1024 * 1024


@dataclass(frozen=True)
class ProductContext:
    dossier: str | None = None
    probes: tuple[ProbeObservation, ...] = ()

    def __post_init__(self) -> None:
        if self.dossier is not None:
            uuid(self.dossier)
        if len(self.probes) > 2 or len({p.check for p in self.probes}) != len(
            self.probes
        ):
            raise ValueError("Invalid product probe observations")

    @classmethod
    def decode(cls, value: object) -> ProductContext:
        row = object_fields(value, {"dossier", "probes"})
        return cls(
            uuid(row["dossier"]) if row["dossier"] is not None else None,
            tuple(ProbeObservation.decode(p) for p in items(row["probes"], 2)),
        )


@dataclass(frozen=True)
class ProductQuestion:
    product: str
    question: str
    context: ProductContext
    known_sources: tuple[ProductSource, ...] = ()

    def __post_init__(self) -> None:
        product_slug(self.product)
        field(self.question, 2048, required=True)
        if len(self.known_sources) > 5:
            raise ValueError("Too many existing product sources")

    def encode(self) -> str:
        return json.dumps(
            {
                "version": 1,
                "product": self.product,
                "question": self.question,
                **asdict(self.context),
                "known_sources": [asdict(source) for source in self.known_sources],
            },
            allow_nan=False,
        )


@dataclass(frozen=True)
class ProductSource:
    url: str
    title: str
    kind: str
    revision: str

    def __post_init__(self) -> None:
        reference_url(self.url)
        field(self.title, 200, required=True)
        if self.kind not in KINDS:
            raise ValueError("Unsupported product source kind")
        uuid(self.revision)

    @classmethod
    def decode(cls, value: object) -> ProductSource:
        row = object_fields(value, {"url", "title", "kind", "revision"})
        return cls(
            reference_url(row["url"]),
            field(row["title"], 200, required=True),
            field(row["kind"], 32, required=True),
            uuid(row["revision"]),
        )


@dataclass(frozen=True)
class ProductAnswer:
    report: str
    answer: str
    sources: tuple[ProductSource, ...]

    def __post_init__(self) -> None:
        uuid(self.report)
        field(self.answer, 65536, required=True)
        if len(self.sources) > 5 or len({s.url for s in self.sources}) != len(
            self.sources
        ):
            raise ValueError("Invalid product answer sources")

    @classmethod
    def decode(cls, value: object) -> ProductAnswer:
        row = object_fields(value, {"version", "report", "answer", "sources"})
        if type(row["version"]) is not int or row["version"] != 1:
            raise ValueError("Unsupported product answer")
        return cls(
            uuid(row["report"]),
            field(row["answer"], 65536, required=True),
            tuple(ProductSource.decode(s) for s in items(row["sources"], 5)),
        )

    def encoded(self) -> dict[str, object]:
        return {"version": 1, **asdict(self)}


@dataclass(frozen=True)
class ProductRun:
    state: str
    result: str | None


class ProductResearchPort(Protocol):
    async def start(self, request_id: str, question: ProductQuestion) -> str: ...
    async def receipt(self, request_id: str) -> str: ...
    async def outcome(self, run_id: str) -> ProductRun: ...
    async def cancel(self, run_id: str) -> None: ...


@dataclass(frozen=True)
class ProductResearchEntry:
    reference_id: str
    request_id: str
    phase: str
    probe: bool
    context: ProductContext | None = None
    run_id: str | None = None
    result: ProductAnswer | None = None
    detail: str | None = None

    def __post_init__(self) -> None:
        uuid(self.reference_id)
        uuid(self.request_id)
        if self.phase not in PHASES or type(self.probe) is not bool:
            raise ValueError("Invalid product research state")
        if self.run_id is not None:
            identifier(self.run_id)
        if self.phase in ("running", "retaining", "finished") and self.run_id is None:
            raise ValueError("Admitted research needs a run")
        if (
            self.phase in ("submitted", "running", "retaining", "finished")
            and self.context is None
        ):
            raise ValueError("Admitted research needs context")
        if (self.phase in ("retaining", "finished")) != (self.result is not None):
            raise ValueError("Research answer does not match its state")
        if self.detail is not None:
            field(self.detail, 1000, required=True)

    @classmethod
    def decode(cls, value: object) -> ProductResearchEntry:
        row = object_fields(
            value,
            {
                "reference_id",
                "request_id",
                "phase",
                "probe",
                "context",
                "run_id",
                "result",
                "detail",
            },
        )
        if type(row["probe"]) is not bool:
            raise ValueError("Invalid device check choice")
        return cls(
            uuid(row["reference_id"]),
            uuid(row["request_id"]),
            field(row["phase"], 32),
            row["probe"],
            ProductContext.decode(row["context"])
            if row["context"] is not None
            else None,
            identifier(row["run_id"]) if row["run_id"] is not None else None,
            ProductAnswer.decode(row["result"]) if row["result"] is not None else None,
            field(row["detail"], 1000) if row["detail"] is not None else None,
        )

    def encoded(self) -> dict[str, object]:
        return {
            **asdict(self),
            "result": self.result.encoded() if self.result else None,
        }


class ProductResearchJournal:
    def __init__(self, root: Path):
        self.path = root / "product-research.json"

    def read(self) -> tuple[ProductResearchEntry, ...]:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Product research journal cannot be linked")
        if not self.path.exists():
            return ()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Product research journal must be private")
        return self.decode(load_json(self.path, maximum_bytes=MAX_BYTES))

    @staticmethod
    def decode(value: object) -> tuple[ProductResearchEntry, ...]:
        row = object_fields(value, {"version", "entries"})
        if type(row["version"]) is not int or row["version"] != 1:
            raise ValueError("Unsupported product research journal")
        entries = tuple(
            ProductResearchEntry.decode(e) for e in items(row["entries"], MAX_ENTRIES)
        )
        if len({e.reference_id for e in entries}) != len(entries) or len(
            {e.request_id for e in entries}
        ) != len(entries):
            raise ValueError("Conflicting product research entries")
        return entries

    def save(self, entries: tuple[ProductResearchEntry, ...]) -> None:
        content = json.dumps(
            {"version": 1, "entries": [e.encoded() for e in entries]}, allow_nan=False
        )
        self.decode(parse_json(content, maximum_bytes=MAX_BYTES))
        replace_private(self.path, content)


PrepareProduct = Callable[[ReferenceRequest, bool], Awaitable[ProductContext]]


class ProductResearch:
    def __init__(
        self,
        journal: ProductResearchJournal,
        port: ProductResearchPort,
        requests: ReferenceRequests,
        library: ReferenceLibrary,
        prepare: PrepareProduct,
    ):
        self.journal, self.port, self.requests = journal, port, requests
        self.library, self.prepare = library, prepare
        self.lock = asyncio.Lock()
        self.error: str | None = None
        # Do not repeat a local check interrupted before its observations were saved.
        entries = self.journal.read()
        if any(e.phase == "preparing" for e in entries):
            self.journal.save(
                tuple(
                    replace(
                        e,
                        phase="failed",
                        detail="Device evidence preparation was interrupted. Research may be requested again.",
                    )
                    if e.phase == "preparing"
                    else e
                    for e in entries
                )
            )

    def view(self) -> tuple[ProductResearchEntry, ...]:
        return self.journal.read()

    def entry(self, reference_id: str) -> ProductResearchEntry | None:
        return next(
            (e for e in self.journal.read() if e.reference_id == uuid(reference_id)),
            None,
        )

    def queue(self, reference_id: str, probe: bool) -> ProductResearchEntry:
        request = self.requests.get(reference_id)
        if request.state != "pending" or type(probe) is not bool:
            raise ValueError("Choose a pending product question")
        if probe and request.device_id is None:
            raise ValueError(
                "Device checks need a monitored device linked to this question"
            )
        entries = self.journal.read()
        current = next((e for e in entries if e.reference_id == reference_id), None)
        if current is not None and current.phase != "failed":
            if current.probe != probe:
                raise ValueError(
                    "Research is already queued with a different device check choice"
                )
            return current
        if current is None and len(entries) >= MAX_ENTRIES:
            # Only closed requests' finished entries are expendable. Never drop an admission fence.
            pending = {request.id for request in self.requests.pending()}
            entries = tuple(
                e
                for e in entries
                if e.phase not in ("finished", "failed") or e.reference_id in pending
            )
            if len(entries) >= MAX_ENTRIES:
                raise ValueError(
                    "Too many product research results; dismiss completed requests first"
                )
        entry = ProductResearchEntry(reference_id, str(uuid4()), "queued", probe)
        self.journal.save(
            tuple(e for e in entries if e.reference_id != reference_id) + (entry,)
        )
        return entry

    def active(self, reference_id: str) -> bool:
        entry = self.entry(reference_id)
        return entry is not None and entry.phase not in ("finished", "failed")

    async def cancel(self, reference_id: str) -> None:
        """Request an owner-selected stop; polling confirms the terminal state.

        The port verifies run scope before cancellation. Unknown delivery keeps
        the running fence and is reconciled by status, never an automatic replay.
        """
        async with self.lock:
            entry = self.entry(reference_id)
            if entry is None:
                raise ValueError("No research is queued for this question")
            if entry.phase == "queued":
                self._put(
                    replace(
                        entry,
                        phase="failed",
                        detail="Research was stopped before admission.",
                    )
                )
                return
            if entry.phase != "running" or entry.run_id is None:
                raise ValueError(
                    "Wait for the research admission or result before stopping it"
                )
            self._put(
                replace(
                    entry,
                    detail="Stop requested; waiting for the server's final state.",
                )
            )
            await self.port.cancel(entry.run_id)

    def _put(self, entry: ProductResearchEntry) -> None:
        entries = self.journal.read()
        self.journal.save(
            tuple(entry if e.reference_id == entry.reference_id else e for e in entries)
        )

    async def poll(self) -> None:
        async with self.lock:
            entry = next(
                (
                    e
                    for e in self.journal.read()
                    if e.phase not in ("finished", "failed")
                ),
                None,
            )
            if entry is None:
                return
            if entry.phase == "preparing":
                # A previous unexpected failure escaped preparation. Its own
                # dossier fence remains authoritative; never repeat the probe.
                self._put(
                    replace(
                        entry,
                        phase="failed",
                        detail="Device evidence preparation was interrupted. Inspect the dossier before trying again.",
                    )
                )
                return
            try:
                await self._advance(entry)
            except TransportError as error:
                # A submitted intent is fenced before start; only proven non-delivery
                # permits replacing it. Other failures retain their current phase.
                current = self.entry(entry.reference_id)
                if current is not None:
                    if current.phase == "preparing":
                        current = replace(current, phase="failed")
                    if (
                        current.phase == "submitted"
                        and entry.phase == "queued"
                        and error.delivery == Delivery.NOT_SUBMITTED
                    ):
                        current = replace(current, phase="failed")
                    self._put(
                        replace(
                            current,
                            detail="Waiting for Plowshare. Any uncertain admission is recovered by receipt only.",
                        )
                    )
            except Refusal:
                current = self.entry(entry.reference_id)
                if current is not None:
                    # A receipt refusal cannot prove that the original start did not commit.
                    if entry.phase in ("submitted", "running"):
                        self._put(
                            replace(
                                current,
                                detail="Admission receipt is unresolved; inspect the existing request before starting another run.",
                            )
                        )
                    elif current.phase == "retaining":
                        self._put(
                            replace(
                                current,
                                detail="The answer is retained; source registration needs attention.",
                            )
                        )
                    else:
                        self._put(
                            replace(
                                current,
                                phase="failed",
                                result=None,
                                detail="Plowshare refused product research. Check the run and permissions before retrying.",
                            )
                        )
            except (OSError, ValueError, ReconciliationRequired):
                current = self.entry(entry.reference_id)
                if current is not None:
                    # Never remove an uncertain admission or a completed report merely
                    # because a later projection/tagging response is invalid.
                    if current.phase in ("submitted", "running", "retaining"):
                        self._put(
                            replace(
                                current,
                                detail="Research needs attention; the retained admission or answer has been kept.",
                            )
                        )
                    else:
                        self._put(
                            replace(
                                current,
                                phase="failed",
                                result=None,
                                detail="Product research could not be completed. Inspect its evidence and run before retrying.",
                            )
                        )

    async def _advance(self, entry: ProductResearchEntry) -> None:
        if entry.phase == "queued":
            self._put(replace(entry, phase="preparing"))
            request = self.requests.get(entry.reference_id)
            context = await self.prepare(request, entry.probe)
            known = tuple(
                ProductSource(
                    r.url,
                    r.title,
                    next(
                        (kind for kind in r.topics if kind in KINDS),
                        "official" if r.origin == "seed" else "research",
                    ),
                    r.revision_id,
                )
                for r in self.library.view()
                if (r.product == request.product or request.product in r.topics)
                and r.phase == "tagged"
                and not r.retired
                and r.revision_id is not None
            )[:5]
            entry = replace(entry, phase="submitted", context=context, detail=None)
            self._put(entry)
            run_id = await self.port.start(
                entry.request_id,
                ProductQuestion(request.product, request.question, context, known),
            )
            self._put(replace(entry, phase="running", run_id=run_id))
        elif entry.phase == "submitted":
            run_id = await self.port.receipt(entry.request_id)
            self._put(replace(entry, phase="running", run_id=run_id, detail=None))
        elif entry.phase == "running":
            if entry.run_id is None:
                raise ValueError("Missing product run")
            outcome = await self.port.outcome(entry.run_id)
            if outcome.state == "finished":
                if outcome.result is None:
                    raise ValueError("Product research finished without a result")
                result = ProductAnswer.decode(
                    parse_json(outcome.result, maximum_bytes=384 * 1024)
                )
                self._put(replace(entry, phase="retaining", result=result, detail=None))
            elif outcome.state in ("failed", "cancelled"):
                self._put(
                    replace(
                        entry,
                        phase="failed",
                        detail="Research stopped without an accepted answer. Inspect the run before trying again.",
                    )
                )
        elif entry.phase == "retaining":
            if entry.result is None:
                raise ValueError("Missing research answer")
            product = self.requests.get(entry.reference_id).product
            for source in entry.result.sources:
                await self.library.adopt_research(
                    product, source.title, source.url, source.kind, source.revision
                )
            self._put(replace(entry, phase="finished", detail=None))

    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                await self.poll()
                self.error = None
            except Exception:
                # An unreadable journal must not kill collection or erase a fence.
                # Report the failure without leaking exception text or local paths.
                self.error = "Product research needs attention; its saved state could not be advanced."
            try:
                await asyncio.wait_for(stop.wait(), 5)
            except TimeoutError:
                continue
