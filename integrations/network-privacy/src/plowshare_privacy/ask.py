"""The Ask conversation: the household user's one conversation with the assistant.

Plowshare owns the conversation, its entries, its runs and its caps. This
collector-owned journal (``ask.json``, private, beside the other journals)
keeps only what the collector needs to fence its own mutations: the
conversation ID, the caps it set, the last ``MAX_TURNS`` questions with the
state of each send, how far the conversation has been read and whether the
user has unread answers.

Lifecycle of one question (``AskTurn.state``):

* ``sending``: the question is journalled before ``agent.run``. A restart in
  this state cannot tell whether it reached Plowshare, so it is read back as
  ``unconfirmed``.
* ``running``: Plowshare admitted the run; ``poll`` reads its job.
* ``answered``, ``capped`` or ``failed``: how the run ended. ``capped`` means
  the conversation's model-call or turn cap stopped it; the view is then
  ``paused`` until the user tops up or asks again.
* ``unconfirmed``: the send's outcome is unknown. It is never resent. It
  becomes ``running`` if the conversation later shows the question with its
  job; otherwise the user may ask again.

Delivery rules (AGENTS.md: never replay a mutation after uncertain delivery):
``agent.run`` and ``conversation.resume`` are each sent once. A ``Refusal``
means the server decided and a ``NOT_SUBMITTED`` transport error means
nothing was sent; any other failure after sending leaves the outcome unknown.

Turns the collector did not start. A specialist's reply or an orchestration's
report wakes the conversation on its own job. Such a job is found from the
entries (a harness utterance on a job that is no question's) and held in
memory only: while it runs, sending is refused, and if it is capped the view
is paused and a top-up resumes it. After a restart the reloaded entries find
it again.

Concurrency: one asyncio lock serialises every operation that awaits the
server. ``view``, ``set_settings`` and ``mark_read`` do not await.
"""

from __future__ import annotations

import asyncio
import json
import unicodedata
from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Callable, Literal, Protocol
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, TransportError
from plowshare.contracts import (
    AgentRunRequest,
    AskedViewDto,
    ConversationChatRequest,
    ConversationLatestRequest,
    ConversationOpenRequest,
    ConversationResumeRequest,
    ConversationViewDto,
    EntryPageViewDto,
    EntryViewDto,
    JobStatusRequest,
    ObservedJobDto,
    ObservedOutcomeDto,
    ObservedStartedJobDto,
)

from .contracts import (
    Configuration,
    identifier,
    items,
    load_json,
    object_fields,
    parse_json,
    timestamp,
    utc_now,
    uuid,
)
from .diagnostics import refusal_detail
from .private_files import replace_private

MAX_TURNS = 50
MAX_QUESTION = 4000
MAX_LEDGER_BYTES = 2 * 1024 * 1024
# Each setting is a whole number in this range (Ask settings, Global Constraints).
MIN_SETTING, MAX_SETTING = 1, 200
# Top-ups raise the model-call total; this bounds what a journal may claim.
MAX_TOTAL_CALLS = 100_000
MAX_SEEN = 1_000_000_000
# Entries the view keeps, and reloads after a restart.
MAX_VIEW_ENTRIES = 200
MAX_WORK = 20
MAX_ABOUT = 500
PAGE = 50
MAX_PAGES = 10
_POLL_SECONDS = 5

TurnState = Literal["sending", "running", "answered", "capped", "failed", "unconfirmed"]
ViewState = Literal[
    "idle", "sending", "running", "answered", "capped", "failed", "unconfirmed"
]
JobState = Literal["running", "answered", "capped", "failed"]
Author = Literal["user", "agent", "tool", "system"]
_TURN_STATES = frozenset(
    {"sending", "running", "answered", "capped", "failed", "unconfirmed"}
)
_ACTIVE = frozenset({"sending", "running"})
_JOB_STATES = frozenset({"running", "answered", "capped", "failed"})
# Outcome.Ending names (plowshare-server agents/Outcome.java). AWAITING ends a
# turn that is waiting for an answer from outside, which is not a failure.
_ANSWERED_ENDINGS = frozenset({"ANSWERED", "AWAITING"})
_CAPPED_ENDINGS = frozenset({"TURN_CAP", "CALL_BUDGET"})
# The tool that sends a message, and the prefix of each orchestration start
# tool (CallerOrchestrationTools: "orchestrate_" + definition name).
SEND_MESSAGE = "send_message"
ORCHESTRATE = "orchestrate_"
# Entry kinds that begin a turn somebody other than the user started.
_WAKING = frozenset({"message", "orchestration", "harness"})

_NOT_CONFIRMED = (
    "Your question is not confirmed: Plowshare did not acknowledge it. It was"
    " not resent; it shows here once the conversation does, or you can ask again."
)


class AskConflict(Exception):
    """The request is valid but the conversation's current state does not allow it."""


def _setting(value: object) -> int:
    if type(value) is not int or not MIN_SETTING <= value <= MAX_SETTING:
        raise ValueError(
            f"Ask settings must be whole numbers from {MIN_SETTING} to {MAX_SETTING}"
        )
    return value


def question_text(value: object) -> str:
    """A question: 1–4000 characters, not blank, no control characters but newline."""
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > MAX_QUESTION
        or any(c != "\n" and unicodedata.category(c) == "Cc" for c in value)
    ):
        raise ValueError(
            f"A question is 1 to {MAX_QUESTION} characters, with no control characters other than line breaks"
        )
    return value


@dataclass(frozen=True)
class AskSettings:
    """Caps for a new conversation and the size of one top-up; each 1–200."""

    max_model_calls: int = 40
    max_turns: int = 30
    top_up_calls: int = 20

    def __post_init__(self) -> None:
        _setting(self.max_model_calls)
        _setting(self.max_turns)
        _setting(self.top_up_calls)

    @classmethod
    def decode(cls, value: object) -> AskSettings:
        row = object_fields(value, {"max_model_calls", "max_turns", "top_up_calls"})
        return cls(
            _setting(row["max_model_calls"]),
            _setting(row["max_turns"]),
            _setting(row["top_up_calls"]),
        )


@dataclass(frozen=True)
class AskTurn:
    request_id: str
    question: str
    asked_at: str
    # The run's job; None until admitted. A top-up replaces it with the resumed job.
    job: str | None
    state: TurnState

    @classmethod
    def decode(cls, value: object) -> AskTurn:
        row = object_fields(
            value, {"request_id", "question", "asked_at", "job", "state"}
        )
        state = row["state"]
        if state not in _TURN_STATES:
            raise ValueError("Invalid Ask turn state")
        turn = cls(
            uuid(row["request_id"]),
            question_text(row["question"]),
            timestamp(row["asked_at"]),
            identifier(row["job"]) if row["job"] is not None else None,
            state,  # type: ignore[arg-type]
        )
        if state == "running" and turn.job is None:
            raise ValueError("A running Ask turn needs its job")
        return turn


@dataclass(frozen=True)
class AskLedger:
    conversation: str | None = None
    settings: AskSettings = AskSettings()
    turns: tuple[AskTurn, ...] = ()
    # Conversation entries read so far; later ones are new to the user.
    seen_entries: int = 0
    unread: bool = False
    # The conversation's current (max_model_calls, max_turns). Before the
    # conversation opens they follow the settings; top-ups raise the first.
    totals: tuple[int, int] = (40, 30)
    # The application user this conversation belongs to; one per collector today.
    user: str = "operator"
    version: int = 1

    @classmethod
    def decode(cls, value: object) -> AskLedger:
        row = object_fields(
            value,
            {
                "version",
                "user",
                "conversation",
                "settings",
                "turns",
                "seen_entries",
                "unread",
                "totals",
            },
        )
        if type(row["version"]) is not int or row["version"] != 1:
            raise ValueError("Unsupported Ask journal")
        turns = tuple(AskTurn.decode(v) for v in items(row["turns"], MAX_TURNS))
        seen, unread = row["seen_entries"], row["unread"]
        if type(seen) is not int or not 0 <= seen <= MAX_SEEN:
            raise ValueError("Invalid Ask entry count")
        if type(unread) is not bool:
            raise ValueError("Invalid Ask unread flag")
        totals = items(row["totals"], 2)
        if (
            len(totals) != 2
            or type(totals[0]) is not int
            or not MIN_SETTING <= totals[0] <= MAX_TOTAL_CALLS
        ):
            raise ValueError("Invalid Ask totals")
        conversation = (
            identifier(row["conversation"]) if row["conversation"] is not None else None
        )
        active = [i for i, t in enumerate(turns) if t.state in _ACTIVE]
        if (
            len({t.request_id for t in turns}) != len(turns)
            or len(active) > 1
            or (active and active[0] != len(turns) - 1)
            or (turns and conversation is None)
        ):
            raise ValueError("Conflicting Ask turns")
        return cls(
            conversation,
            AskSettings.decode(row["settings"]),
            turns,
            seen,
            unread,
            (totals[0], _setting(totals[1])),
            identifier(row["user"]),
        )


class AskJournal:
    """The private ledger file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "ask.json"

    def read(self) -> AskLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Ask journal cannot be linked")
        if not self.path.exists():
            return AskLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Ask journal must be private")
        return AskLedger.decode(load_json(self.path, maximum_bytes=MAX_LEDGER_BYTES))

    def save(self, value: AskLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        AskLedger.decode(parse_json(content, maximum_bytes=MAX_LEDGER_BYTES))
        replace_private(self.path, content)


@dataclass(frozen=True)
class AskCall:
    """One tool call the agent asked for.

    ``target`` is the ``send_message`` recipient (``to`` or ``route``) or the
    orchestration name; ``about`` is the message body or orchestration
    request, bounded. Both are None when the arguments cannot be read, which
    means an unknown recipient. ``reply`` marks a ``send_message`` that answers
    an incoming message, which waits for nothing.
    """

    id: str
    tool: str
    target: str | None
    about: str | None
    reply: bool = False


@dataclass(frozen=True)
class AskEntry:
    """One conversation entry as the Ask tab shows it.

    ``index`` is the entry's ordinal in the conversation. ``at`` is UTC
    ``...Z``, or empty when Plowshare recorded no time. ``kind`` is
    ``question``, ``answer``, ``refusal``, ``tool_result``, ``message`` (a
    specialist's message), ``orchestration`` (a run's report), ``harness``
    (another harness utterance) or Plowshare's own kind for anything else.

    * ``calls``: an answer's tool calls.
    * ``answers`` and ``tool``: the call a ``tool_result`` answers, and that
      call's tool when the call is known.
    * ``reference``: a ``tool_result``'s receipt (message or orchestration
      ID); the message a ``message`` replies to (final replies only); the
      orchestration a report comes from.
    * ``sender``: the agent that sent a ``message``.
    * ``job``: the run that wrote the entry, when recorded.
    """

    index: int
    author: Author
    text: str
    at: str
    kind: str
    calls: tuple[AskCall, ...] = ()
    answers: str | None = None
    tool: str | None = None
    reference: str | None = None
    sender: str | None = None
    job: str | None = None


@dataclass(frozen=True)
class AskWork:
    """Work the agent handed off: a message to a specialist or an orchestration."""

    kind: Literal["message", "orchestration"]
    # The recipient or orchestration; None when the call's arguments were unreadable.
    target: str | None
    about: str | None
    since: str


@dataclass(frozen=True)
class AskView:
    available: bool
    conversation: str | None
    turn_state: ViewState
    entries: tuple[AskEntry, ...]
    waiting: tuple[AskWork, ...]
    done: tuple[AskWork, ...]
    paused: bool
    unread: bool
    detail: str | None


class AskPort(Protocol):
    async def latest(self, agent: str) -> str | None:
        """The agent's newest active conversation in the project, or None."""
        ...

    async def open(self, agent: str, max_model_calls: int, max_turns: int) -> str:
        """Open a conversation with these caps; returns its ID."""
        ...

    async def run(self, agent: str, conversation: str, task: str) -> str:
        """Speak ``task`` into the conversation as a new turn; returns the job ID."""
        ...

    async def job_state(self, job: str) -> JobState: ...

    async def entries(
        self, conversation: str, offset: int, limit: int
    ) -> tuple[AskEntry, ...]:
        """One page of the conversation's chat reading, one ``AskEntry`` per entry."""
        ...

    async def resume(
        self, conversation: str, agent: str, max_model_calls: int, max_turns: int
    ) -> str:
        """Raise the caps to these totals and continue the capped run; returns its job ID."""
        ...


def _optional(value: object) -> object:
    """None for an omitted SDK field (the SDK's ``UNSET`` enum member), else the value."""
    return None if isinstance(value, Enum) else value


class SdkAskPort:
    """``AskPort`` in the configured project, over the public SDK.

    ``client`` is a cell: operator recovery replaces the SDK connection, and
    each call uses the current one. ``conversation.open`` takes no agent; the
    conversation becomes the agent's when its first turn runs, which is what
    ``conversation.latest`` finds.
    """

    def __init__(self, client: Callable[[], Client], config: Configuration):
        self.client, self.project = client, config.project

    async def latest(self, agent: str) -> str | None:
        view = (
            await self.client().request(
                ConversationLatestRequest(agent=agent, project=self.project),
                timeout=15,
            )
        ).require_payload()
        if view is None:
            return None
        if not isinstance(view, ConversationViewDto):
            raise ValueError("Unexpected latest conversation reply")
        return identifier(view.id)

    async def open(self, agent: str, max_model_calls: int, max_turns: int) -> str:
        view = (
            await self.client().request(
                ConversationOpenRequest(
                    max_model_calls=max_model_calls,
                    max_turns=max_turns,
                    project=self.project,
                ),
                timeout=15,
            )
        ).require_payload()
        return identifier(view.id)

    async def run(self, agent: str, conversation: str, task: str) -> str:
        # No project: the turn's home is the conversation's, and agent.run
        # refuses a project beside a conversation.
        started = (
            await self.client().request(
                AgentRunRequest(agent=agent, conversation=conversation, task=task),
                timeout=15,
            )
        ).require_payload()
        return _started(started, agent, conversation)

    async def job_state(self, job: str) -> JobState:
        view = (
            await self.client().request(JobStatusRequest(job=job), timeout=15)
        ).require_payload()
        if not isinstance(view, ObservedJobDto) or view.id != job:
            raise ValueError("Foreign Ask job status")
        if view.state == "RUNNING":
            return "running"
        if view.state != "DONE":
            raise ValueError("Unsupported Ask job state")
        outcome = _optional(view.outcome)
        if not isinstance(outcome, ObservedOutcomeDto):
            return "failed"
        if outcome.ending in _ANSWERED_ENDINGS:
            return "answered"
        if outcome.ending in _CAPPED_ENDINGS:
            return "capped"
        return "failed"

    async def entries(
        self, conversation: str, offset: int, limit: int
    ) -> tuple[AskEntry, ...]:
        page = (
            await self.client().request(
                ConversationChatRequest(
                    conversation=conversation, offset=offset, limit=limit
                ),
                timeout=15,
            )
        ).require_payload()
        if not isinstance(page, EntryPageViewDto):
            raise ValueError("Unexpected conversation page")
        return tuple(decode_entry(e) for e in page.entries)

    async def resume(
        self, conversation: str, agent: str, max_model_calls: int, max_turns: int
    ) -> str:
        started = (
            await self.client().request(
                ConversationResumeRequest(
                    conversation=conversation,
                    agent=agent,
                    max_model_calls=max_model_calls,
                    max_turns=max_turns,
                ),
                timeout=15,
            )
        ).require_payload()
        return _started(started, agent, conversation)


def _started(started: ObservedStartedJobDto, agent: str, conversation: str) -> str:
    if _optional(started.conversation) not in (None, conversation) or _optional(
        started.agent
    ) not in (None, agent):
        raise ValueError("Foreign Ask job")
    return identifier(started.id)


def _instant(value: object) -> str:
    """UTC ``...Z``; empty for an entry recorded before Plowshare kept times."""
    if value is None:
        return ""
    result = timestamp(value)
    if result.endswith("Z"):
        return result
    parsed = datetime.fromisoformat(result).astimezone(timezone.utc)
    return parsed.isoformat().replace("+00:00", "Z")


def _flat(value: object, limit: int) -> str | None:
    """Model-supplied text on one line, bounded; None when it is not text."""
    if not isinstance(value, str):
        return None
    flat = " ".join(value.split())
    if not flat:
        return None
    return flat if len(flat) <= limit else flat[: limit - 1] + "…"


def _object(content: object) -> dict[str, object] | None:
    """A JSON object from untrusted text, or None when it is not one."""
    if not isinstance(content, str):
        return None
    try:
        value = parse_json(content)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _token(value: object) -> str | None:
    try:
        return identifier(value)
    except ValueError:
        return None


def decode_call(call: AskedViewDto) -> AskCall:
    """A tool call; unreadable arguments mean an unknown recipient, never an error."""
    tool = _token(call.name) or "unknown"
    args = _object(call.arguments)
    if tool.startswith(ORCHESTRATE) and len(tool) > len(ORCHESTRATE):
        about = _flat(args.get("request"), MAX_ABOUT) if args else None
        return AskCall(call.id, tool, tool[len(ORCHESTRATE) :], about)
    if tool != SEND_MESSAGE or args is None:
        return AskCall(call.id, tool, None, None)
    target = _flat(args.get("to"), 128) or _flat(args.get("route"), 128)
    reply = target is None and _flat(args.get("reply_to"), 256) is not None
    return AskCall(call.id, tool, target, _flat(args.get("body"), MAX_ABOUT), reply)


def _incoming(excerpt: str) -> tuple[str, str | None, str | None]:
    """A delivered message's (body, sender, replied-to message) from its utterance.

    The harness writes a sentence, a newline and the message as JSON
    (BoardMessaging.incoming). Unreadable text is kept whole, with no sender.
    Only a final reply names the message it settles.
    """
    start = excerpt.find("\n{")
    message = _object(excerpt[start + 1 :]) if start >= 0 else None
    if message is None or not isinstance(message.get("body"), str):
        return excerpt, None, None
    final = message.get("final") is not False
    return (
        str(message["body"]),
        _token(message.get("sender")),
        _token(message.get("reply_to")) if final else None,
    )


def decode_entry(entry: EntryViewDto) -> AskEntry:
    """Map one ``EntryView`` (plowshare-server api/EntryView.java) to an ``AskEntry``."""
    ordinal = entry.ordinal
    if isinstance(ordinal, bool) or ordinal % 1 or ordinal < 1:
        raise ValueError("Conversation entry ordinal must be a positive whole number")
    index, at = int(ordinal), _instant(entry.recorded_at)
    excerpt = entry.excerpt if isinstance(entry.excerpt, str) else ""
    job = _token(_optional(entry.job))
    if entry.kind == "utterance":
        if entry.speaker == "person":
            return AskEntry(index, "user", excerpt, at, "question", job=job)
        source = _optional(entry.source)
        kind = getattr(source, "kind", None)
        reference = _token(getattr(source, "reference", None))
        if kind == "message":
            body, sender, replied = _incoming(excerpt)
            return AskEntry(
                index,
                "system",
                body,
                at,
                "message",
                reference=replied,
                sender=sender,
                job=job,
            )
        if kind == "orchestration":
            return AskEntry(
                index,
                "system",
                excerpt,
                at,
                "orchestration",
                reference=reference,
                job=job,
            )
        return AskEntry(index, "system", excerpt, at, "harness", job=job)
    if entry.kind in ("answer", "refusal"):
        calls = tuple(decode_call(c) for c in entry.tool_calls)
        return AskEntry(index, "agent", excerpt, at, entry.kind, calls=calls, job=job)
    if entry.kind == "tool_result":
        receipt = _object(excerpt) or {}
        reference = _token(receipt.get("message")) or _token(receipt.get("id"))
        return AskEntry(
            index,
            "tool",
            excerpt,
            at,
            "tool_result",
            answers=_token(entry.tool_call_id),
            reference=reference,
            job=job,
        )
    return AskEntry(
        index, "system", excerpt, at, _token(entry.kind) or "other", job=job
    )


def _work(entries: tuple[AskEntry, ...]) -> tuple[tuple[AskWork, ...], ...]:
    """(waiting, done): hand-offs from tool calls, settled by replies and reports.

    A hand-off whose tool result carries no receipt was refused and is in
    neither list. One without a result yet is waiting.
    """
    work: dict[str, AskWork] = {}
    receipts: dict[tuple[str, str], str] = {}
    dropped: set[str] = set()
    finished: list[str] = []
    for entry in entries:
        for call in entry.calls:
            if call.tool == SEND_MESSAGE and not call.reply:
                work[call.id] = AskWork("message", call.target, call.about, entry.at)
            elif call.tool.startswith(ORCHESTRATE):
                work[call.id] = AskWork(
                    "orchestration", call.target, call.about, entry.at
                )
        if entry.kind == "tool_result" and entry.answers in work:
            call_id = entry.answers
            if entry.reference is None:
                dropped.add(call_id)
            else:
                receipts[(work[call_id].kind, entry.reference)] = call_id
        if entry.kind in ("message", "orchestration") and entry.reference is not None:
            settled = receipts.pop((entry.kind, entry.reference), None)
            if settled is not None:
                finished.append(settled)
    waiting = tuple(
        w for c, w in work.items() if c not in dropped and c not in finished
    )
    done = tuple(work[c] for c in finished)
    return waiting[-MAX_WORK:], done[-MAX_WORK:]


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _later_or_same(at: str, since: str) -> bool:
    """Whether an entry time is not before ``since``; an unrecorded time may be."""
    return not at or _parse(at) >= _parse(since)


@dataclass
class _Woken:
    """A turn the collector did not start: its job and what is known of it."""

    job: str
    state: ViewState = "running"
    # Whether this turn's entries have been seen since its waking utterance.
    resumed_from: set[str] = field(default_factory=set)


class AskService:
    """The Ask conversation for one application user, over an ``AskPort``."""

    def __init__(
        self,
        journal: AskJournal,
        port: AskPort,
        *,
        agent: str = "privacy_assistant",
        user: str = "operator",
        clock: Callable[[], str] = utc_now,
        new_id: Callable[[], str] = lambda: str(uuid4()),
    ):
        self.journal, self.port = journal, port
        self.agent, self.user = identifier(agent), identifier(user)
        self.clock, self.new_id = clock, new_id
        self.lock = asyncio.Lock()
        # The run loop's last failure; kept until a poll that did work succeeds.
        self.error: str | None = None
        # The last request's outcome for the user, replaced by the next one.
        self._detail: str | None = None
        self._available = True
        try:
            ledger = journal.read()
            if ledger == AskLedger():
                ledger = replace(ledger, user=self.user)
            if ledger.user != self.user:
                raise ValueError("The Ask journal belongs to another user")
        except (OSError, ValueError):
            self._available = False
            ledger = AskLedger(user=self.user)
            self.error = (
                "The Ask journal needs inspection. Its retained data was preserved."
            )
        if ledger.turns and ledger.turns[-1].state == "sending":
            # Cut off mid-send: it may or may not have reached Plowshare.
            last = replace(ledger.turns[-1], state="unconfirmed")
            ledger = replace(ledger, turns=(*ledger.turns[:-1], last))
        self._ledger = ledger
        self._entries: tuple[AskEntry, ...] = ()
        # Reload the tail after a restart; those entries are not new.
        self._cursor = max(0, ledger.seen_entries - MAX_VIEW_ENTRIES)
        self._woken: _Woken | None = None

    # Ledger access. Writes happen between awaits only, so each is atomic with
    # respect to the event loop; a write after an await starts from the
    # current ledger, never from one read before it.

    def _store(self, ledger: AskLedger) -> None:
        if not self._available:
            raise AskConflict("The Ask journal needs inspection")
        self.journal.save(ledger)
        self._ledger = ledger

    def _set_turn(self, request_id: str, **changes: object) -> None:
        turns = tuple(
            replace(t, **changes) if t.request_id == request_id else t  # type: ignore[arg-type]
            for t in self._ledger.turns
        )
        self._store(replace(self._ledger, turns=turns))

    def _state(self) -> ViewState:
        woken = self._woken
        if woken is not None and woken.state in ("running", "capped", "unconfirmed"):
            return woken.state
        return self._ledger.turns[-1].state if self._ledger.turns else "idle"

    def view(self) -> AskView:
        tools = {c.id: c.tool for e in self._entries for c in e.calls}
        entries = tuple(
            replace(e, tool=tools.get(e.answers)) if e.answers in tools else e
            for e in self._entries
        )
        waiting, done = _work(entries)
        state = self._state()
        return AskView(
            self._available,
            self._ledger.conversation,
            state,
            entries,
            waiting,
            done,
            state == "capped",
            self._ledger.unread,
            self.error or self._detail,
        )

    def set_settings(self, settings: AskSettings) -> None:
        """Save the caps and top-up size.

        The caps apply when a conversation opens; an open conversation keeps
        its totals and later top-ups add the new top-up size.
        """
        if not isinstance(settings, AskSettings):
            raise ValueError("Invalid Ask settings")
        ledger = replace(self._ledger, settings=settings)
        if ledger.conversation is None:
            ledger = replace(
                ledger, totals=(settings.max_model_calls, settings.max_turns)
            )
        self._store(ledger)

    def mark_read(self) -> None:
        if self._ledger.unread:
            self._store(replace(self._ledger, unread=False))

    async def ask(self, question: str) -> AskView:
        question = question_text(question)
        async with self.lock:
            if not self._available:
                raise AskConflict("The Ask journal needs inspection")
            if self._state() in _ACTIVE:
                raise AskConflict(
                    "The assistant is still working. Ask again when it has finished."
                )
            conversation = await self._conversation()
            if conversation is None:
                return self.view()
            turn = AskTurn(
                self.new_id(), question, timestamp(self.clock()), None, "sending"
            )
            self._detail, self._woken = None, None
            self._store(
                replace(self._ledger, turns=(*self._ledger.turns, turn)[-MAX_TURNS:])
            )
            try:
                job = identifier(
                    await self.port.run(self.agent, conversation, question)
                )
            except Refusal as error:
                self._detail = refusal_detail(error, "the question")
                self._set_turn(turn.request_id, state="failed")
            except TransportError as error:
                if error.delivery == Delivery.NOT_SUBMITTED:
                    self._detail = "The question was not sent to Plowshare. Ask again when the connection is back."
                    self._set_turn(turn.request_id, state="failed")
                else:
                    self._detail = _NOT_CONFIRMED
                    self._set_turn(turn.request_id, state="unconfirmed")
            except Exception:
                # Sent, but the reply could not be read: the outcome is unknown.
                self._detail = _NOT_CONFIRMED
                self._set_turn(turn.request_id, state="unconfirmed")
            else:
                self._set_turn(turn.request_id, state="running", job=job)
        return self.view()

    async def _conversation(self) -> str | None:
        """The journalled conversation, else the agent's latest, else a new one.

        Recovery reads ``latest`` first so a lost journal never opens a second
        conversation. A failure is shown and nothing else is sent.
        """
        if self._ledger.conversation is not None:
            return self._ledger.conversation
        try:
            found = await self.port.latest(self.agent)
            if found is None:
                calls, turns = self._ledger.totals
                found = await self.port.open(self.agent, calls, turns)
            conversation = identifier(found)
        except Refusal as error:
            self._detail = refusal_detail(error, "opening the Ask conversation")
            return None
        except TransportError:
            self._detail = "The Ask conversation could not be opened. Nothing was asked; try again."
            return None
        self._store(replace(self._ledger, conversation=conversation))
        return conversation

    async def top_up(self) -> AskView:
        """Raise the model-call total by the top-up size and resume the capped run.

        Only from an explicit request. The raised totals and a ``sending``
        turn are journalled before ``conversation.resume``; it is sent once.
        """
        async with self.lock:
            if not self._available:
                raise AskConflict("The Ask journal needs inspection")
            if self._state() != "capped":
                raise AskConflict(
                    "Top up is available only when the assistant has used its allowance."
                )
            conversation = self._ledger.conversation
            if conversation is None:
                raise AskConflict("There is no Ask conversation to top up")
            before = self._ledger.totals
            calls, turns = before
            raised = calls + self._ledger.settings.top_up_calls
            if raised > MAX_TOTAL_CALLS:
                raise AskConflict(
                    "The Ask conversation has reached its largest allowance"
                )
            woken = (
                self._woken if self._woken and self._woken.state == "capped" else None
            )
            last = self._ledger.turns[-1]
            self._detail = None
            self._store(replace(self._ledger, totals=(raised, turns)))
            if woken is None:
                self._set_turn(last.request_id, state="sending")
            try:
                job = identifier(
                    await self.port.resume(conversation, self.agent, raised, turns)
                )
            except (Refusal, TransportError) as error:
                if isinstance(error, Refusal) or (
                    error.delivery == Delivery.NOT_SUBMITTED
                ):
                    self._detail = (
                        refusal_detail(error, "the top-up")
                        if isinstance(error, Refusal)
                        else "The top-up was not sent to Plowshare. Try again when the connection is back."
                    )
                    self._store(replace(self._ledger, totals=before))
                    if woken is None:
                        self._set_turn(last.request_id, state="capped")
                else:
                    self._unconfirmed_top_up(woken, last)
            except Exception:
                self._unconfirmed_top_up(woken, last)
            else:
                if woken is None:
                    self._set_turn(last.request_id, state="running", job=job)
                else:
                    self._woken = _Woken(job, "running", {woken.job})
        return self.view()

    def _unconfirmed_top_up(self, woken: _Woken | None, last: AskTurn) -> None:
        # The raised totals stay: Plowshare may have applied them.
        self._detail = "Plowshare did not confirm the top-up. It was not resent; ask again to continue."
        if woken is None:
            self._set_turn(last.request_id, state="unconfirmed")
        else:
            woken.state = "unconfirmed"

    async def poll(self) -> None:
        await self._poll()

    async def _poll(self) -> bool:
        """Read new entries and the state of any running job; True when it read."""
        async with self.lock:
            conversation = self._ledger.conversation
            if conversation is None or not self._available:
                return False
            await self._read(conversation)
            last = self._ledger.turns[-1] if self._ledger.turns else None
            if last is not None and last.state == "running" and last.job is not None:
                state = await self.port.job_state(last.job)
                if state not in _JOB_STATES:
                    raise ValueError("Unsupported Ask job state")
                if state != "running":
                    self._set_turn(last.request_id, state=state)
            woken = self._woken
            if woken is not None and woken.state == "running":
                state = await self.port.job_state(woken.job)
                if state not in _JOB_STATES:
                    raise ValueError("Unsupported Ask job state")
                woken.state = state
            return True

    async def _read(self, conversation: str) -> None:
        """Page the chat reading forward from the cursor, bounded per poll.

        Each page is journalled as soon as it is taken, so a failure on a
        later page cannot lose an earlier page's unread answer.
        """
        for _ in range(MAX_PAGES):
            page = await self.port.entries(conversation, self._cursor, PAGE)
            seen, unread = self._ledger.seen_entries, False
            for offset, entry in enumerate(page, start=self._cursor):
                self._take(entry)
                if offset >= seen and entry.author == "agent" and entry.text.strip():
                    unread = True
            self._cursor += len(page)
            self._entries = (*self._entries, *page)[-MAX_VIEW_ENTRIES:]
            if self._cursor > seen:
                self._store(
                    replace(
                        self._ledger,
                        seen_entries=self._cursor,
                        unread=self._ledger.unread or unread,
                    )
                )
            if len(page) < PAGE:
                break

    def _take(self, entry: AskEntry) -> None:
        """Follow which run the conversation is on, one entry at a time."""
        turns = self._ledger.turns
        last = turns[-1] if turns else None
        if entry.author == "user":
            self._woken = None
            if (
                last is not None
                and last.state == "unconfirmed"
                and last.job is None
                and entry.job is not None
                and entry.text == last.question
                and _later_or_same(entry.at, last.asked_at)
            ):
                # The unconfirmed question reached Plowshare after all.
                self._set_turn(last.request_id, state="running", job=entry.job)
            return
        job = entry.job
        if job is None or any(t.job == job for t in turns):
            return
        if entry.kind in _WAKING:
            self._woken = _Woken(job)
        elif (
            self._woken is not None
            and job != self._woken.job
            and job not in self._woken.resumed_from
        ):
            # A woken turn continued on a new job, as a top-up resumes it.
            self._woken = _Woken(
                job, "running", {*self._woken.resumed_from, self._woken.job}
            )

    async def run(self, stop: asyncio.Event) -> None:
        """Poll every _POLL_SECONDS until ``stop``.

        ``error`` is kept until a poll that did work succeeds, so a failure
        stays visible between idle polls instead of clearing within seconds.
        """
        while not stop.is_set():
            try:
                if await self._poll():
                    self.error = None
            except Exception as error:
                self.error = (
                    "The Ask conversation could not be read "
                    f"({type(error).__name__}). Your questions were kept."
                )
            try:
                await asyncio.wait_for(stop.wait(), _POLL_SECONDS)
            except TimeoutError:
                continue
