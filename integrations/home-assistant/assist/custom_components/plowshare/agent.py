"""Bounded Assist conversations through the unchanged public Python SDK.

Only one foreground interaction is admitted per integration instance. There is
no adapter-owned background runtime. Timed-out jobs remain on Plowshare; the next
interaction reconciles their status before it can submit another question.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol, TypeVar
from uuid import UUID

from ._vendor.plowshare import Client, Delivery, Refusal, TransportError
from ._vendor.plowshare.contracts import (
    AgentListRequest,
    AgentRunRequest,
    ConversationOpenRequest,
    ConversationTrajectoryRequest,
    JobStatusRequest,
    ObservedJobDto,
    ObservedOutcomeDto,
    Request,
)
from .const import CLEANUP_TIMEOUT, MAX_SESSIONS, MAX_TEXT
from .models import Session, Settings, required_text

_LOGGER = logging.getLogger(__name__)
T = TypeVar("T")
PENDING = (
    "Your house agent is still working. Ask again in this conversation to check "
    "the result; your request will not be repeated."
)
UNCERTAIN = (
    "Plowshare did not confirm your request. It may still be running, so I have "
    "not repeated it. Check the house agent conversation in Plowshare."
)


class ConversationPort(Protocol):
    """Owns SDK connection lifetimes; never retries a model call or mutation."""

    async def validate(self) -> None: ...

    async def ask(self, key: str, text: str) -> Answer: ...


@dataclass(frozen=True)
class Answer:
    text: str
    error: bool = False


class SdkConversationAgent:
    def __init__(
        self,
        settings: Settings,
        sessions: dict[str, Session],
        save: Callable[[], Awaitable[None]],
        connect: Callable[..., Awaitable[Client]] = Client.connect,
    ) -> None:
        self.settings = settings
        self.sessions = sessions
        self.save = save
        self.connect = connect
        self._busy = False
        self._drain: asyncio.Task[None] | None = None

    async def _save(self) -> None:
        """A stalled local store cannot hold intake or postpone submission forever."""
        async with asyncio.timeout(self.settings.request_timeout):
            await self.save()

    async def _request(self, client: Client, request: Request[T]) -> T:
        # The SDK's timer starts at transmission. This outer timer also bounds
        # admission/queueing and decoding. Non-waiting capacity mode cannot replay.
        async with asyncio.timeout(self.settings.request_timeout):
            return (
                await client.request(
                    request,
                    timeout=self.settings.request_timeout,
                    wait_for_capacity=False,
                )
            ).require_payload()

    async def _close(self, client: Client) -> None:
        self._drain = asyncio.create_task(client.close())
        self._drain.add_done_callback(self._drained)
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await asyncio.shield(self._drain)
        except TimeoutError, TransportError, OSError:
            # SDK close owns its shielded drain. Do not claim physical abort.
            _LOGGER.warning("Plowshare Assist connection cleanup did not settle")

    @staticmethod
    def _drained(task: asyncio.Task[None]) -> None:
        if not task.cancelled() and task.exception() is not None:
            _LOGGER.warning("Plowshare Assist connection cleanup failed")

    async def validate(self) -> None:
        """Read-only setup probe; never creates a conversation or runs a model."""
        client = None
        try:
            async with asyncio.timeout(self.settings.request_timeout):
                client = await self.connect(
                    self.settings.origin,
                    self.settings.token,
                    timeout=self.settings.request_timeout,
                )
                agents = await self._request(
                    client, AgentListRequest(project=self.settings.project)
                )
                if not any(agent.name == self.settings.agent for agent in agents):
                    raise ValueError(
                        "The selected house agent is not available in this project"
                    )
        finally:
            if client is not None:
                await self._close(client)

    async def ask(self, key: str, text: str) -> Answer:
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT:
            return Answer("Please provide a shorter, non-empty house request.", True)
        if any(ord(c) < 32 and c not in "\n\t" for c in text):
            return Answer(
                "The house request contains invalid control characters.", True
            )
        if self._busy or (self._drain is not None and not self._drain.done()):
            return Answer(
                "The house assistant is handling another voice request. Try shortly.",
                True,
            )
        # Event-loop confined admission: no await between testing and claiming it.
        self._busy = True
        client = None
        session = None
        try:
            session = self.sessions.get(key)
            if session is None:
                if len(self.sessions) >= MAX_SESSIONS:
                    idle = next(
                        (
                            key
                            for key, item in self.sessions.items()
                            if item.phase == "idle"
                        ),
                        None,
                    )
                    if idle is None:
                        return Answer(
                            "The house assistant has too many pending conversations. Check Plowshare.",
                            True,
                        )
                    del self.sessions[idle]
                session = Session()
                self.sessions[key] = session
            async with asyncio.timeout(self.settings.response_timeout):
                client = await self.connect(
                    self.settings.origin,
                    self.settings.token,
                    timeout=self.settings.request_timeout,
                )
                if session.phase in {"uncertain", "sending"}:
                    return Answer(UNCERTAIN, True)
                if session.phase == "idle":
                    if session.conversation is None:
                        # Fence even an uncertain conversation.open; never silently
                        # replace an unacknowledged mutation with a new one.
                        session.phase = "sending"
                        await self._save()
                        opened = await self._request(
                            client,
                            ConversationOpenRequest(
                                project=self.settings.project,
                                max_model_calls=40,
                                max_turns=40,
                            ),
                        )
                        session.conversation = required_text(opened.id)
                        session.phase = "idle"
                        await self._save()
                    # Capture a stable ordinal before submitting this turn. History
                    # belongs to Plowshare; HA's transcript is never re-submitted.
                    page = await self._request(
                        client,
                        ConversationTrajectoryRequest(
                            conversation=session.conversation,
                            tail=True,
                            limit=1,
                            kinds=("answer",),
                            drawn=True,
                        ),
                    )
                    session.cursor = int(page.through)
                    session.phase = "sending"
                    await self._save()
                    started = await self._request(
                        client,
                        AgentRunRequest(
                            agent=self.settings.agent,
                            conversation=session.conversation,
                            task=text,
                        ),
                    )
                    session.job = required_text(started.id)
                    if started.conversation != session.conversation:
                        raise ValueError("Foreign Plowshare conversation reply")
                    session.phase = "running"
                    await self._save()
                # A pending question is reconciled instead of submitting the new
                # utterance. This avoids issuing duplicate house actions on retries.
                while True:
                    answer = await self._poll(client, session)
                    if answer is not None:
                        session.phase = "idle"
                        session.job = None
                        await self._save()
                        return answer
                    await asyncio.sleep(0.5)
        except Refusal:
            if session is not None and session.phase == "sending":
                session.phase = "idle"
                await self._save()
            return Answer(
                "Plowshare refused this request. Check the house agent's access and allowance.",
                True,
            )
        except (TransportError, TimeoutError, OSError, ValueError) as error:
            if session is not None and session.phase == "sending":
                session.phase = (
                    "idle"
                    if isinstance(error, TransportError)
                    and error.delivery == Delivery.NOT_SUBMITTED
                    else "uncertain"
                )
                await self._save()
            if session is not None and session.phase == "uncertain":
                return Answer(UNCERTAIN, True)
            if session is not None and session.job is not None:
                return Answer(PENDING)
            return Answer(
                "I could not reach the house agent. Please check the Plowshare connection.",
                True,
            )
        except asyncio.CancelledError:
            if session is not None and session.phase == "sending":
                session.phase = "uncertain"
                await self._save()
            raise
        finally:
            try:
                if client is not None:
                    await self._close(client)
            finally:
                self._busy = False

    async def _poll(self, client: Client, session: Session) -> Answer | None:
        if session.job is None or session.conversation is None:
            raise ValueError("Pending turn has no retained job")
        if session.phase == "running":
            job = await self._request(client, JobStatusRequest(job=session.job))
            self._check_job(job, session)
            if job.state != "DONE":
                return None
            outcome = job.outcome
            if not isinstance(outcome, ObservedOutcomeDto):
                return Answer(
                    "The house agent stopped without an answer. Check its conversation in Plowshare.",
                    True,
                )
            exported = await self._exported(client, session, job.id)
            if outcome.ending != "AWAITING" and not exported:
                return self._answer(outcome)
            session.phase = "waiting"
            await self._save()
        # Relay replies wake the SAME durable conversation on a different job.
        # Read only drawn answer/refusal entries after our original cursor. Never
        # speak reasoning, tool results, or a tool-calling model preview.
        page = await self._request(
            client,
            ConversationTrajectoryRequest(
                conversation=session.conversation,
                after=session.cursor,
                kinds=("answer", "refusal"),
                drawn=True,
                limit=100,
            ),
        )
        for entry in page.entries:
            if isinstance(entry.job, str) and entry.job != session.job:
                job = await self._request(client, JobStatusRequest(job=entry.job))
                self._check_job(job, session, entry.job)
                if job.state != "DONE":
                    # Keep this ordinal unread until its job has settled.
                    return None
                if isinstance(job.outcome, ObservedOutcomeDto):
                    exported = await self._exported(client, session, job.id)
                    if job.outcome.ending != "AWAITING" and not exported:
                        return self._answer(job.outcome)
                    # A correlated reply can itself request more house evidence.
                    # Follow that existing job rather than speaking its interim text.
                    session.job = job.id
                    session.cursor = int(entry.ordinal)
                    await self._save()
                    return None
            session.cursor = int(entry.ordinal)
        if len(page.entries) < 100:
            session.cursor = int(page.through)
        await self._save()
        return None

    async def _exported(self, client: Client, session: Session, job: str) -> bool:
        """Recognize accepted native Relay exports from public trajectory receipts.

        The source turn ends ANSWERED with e.g. 'Evidence requested'; that is not
        the device result. A successful relay_publish receipt on the configured
        house request topic means its later native reply must be followed. Failed
        tools and prose resembling a receipt never establish this lifecycle.
        """
        cursor = session.cursor
        calls: set[str] = set()
        for _ in range(10):
            page = await self._request(
                client,
                ConversationTrajectoryRequest(
                    conversation=session.conversation,
                    after=cursor,
                    kinds=("answer", "tool_result"),
                    limit=100,
                ),
            )
            for entry in page.entries:
                cursor = int(entry.ordinal)
                if entry.job != job:
                    continue
                calls.update(
                    call.id for call in entry.tool_calls if call.name == "relay_publish"
                )
                if entry.kind != "tool_result" or entry.tool_call_id not in calls:
                    continue
                if entry.cut or not isinstance(entry.excerpt, str):
                    raise ValueError("Cannot inspect retained Relay receipt")
                try:
                    receipt = json.loads(entry.excerpt)
                except ValueError, TypeError:
                    continue  # Tool refusals are ordinary explanatory text.
                if (
                    not isinstance(receipt, dict)
                    or receipt.get("topic") != self.settings.request_topic
                ):
                    continue
                event, position = receipt.get("eventId"), receipt.get("position")
                if (
                    not isinstance(event, str)
                    or not isinstance(position, str)
                    or not position.isdecimal()
                    or int(position) < 1
                ):
                    raise ValueError("Malformed Relay acceptance receipt")
                UUID(event)
                return True
            if len(page.entries) < 100:
                return False
        raise ValueError(
            "House turn trajectory exceeds the bounded receipt inspection window"
        )

    @staticmethod
    def _check_job(
        job: ObservedJobDto, session: Session, expected: str | None = None
    ) -> None:
        if (
            job.id != (expected or session.job)
            or job.conversation != session.conversation
        ):
            raise ValueError("Foreign Plowshare job status")
        if job.state not in {"RUNNING", "DONE"}:
            raise ValueError("Unsupported Plowshare job state")

    @staticmethod
    def _answer(outcome: ObservedOutcomeDto) -> Answer:
        if (
            outcome.ending == "ANSWERED"
            and isinstance(outcome.text, str)
            and outcome.text.strip()
        ):
            # Model prose is passed intact, including modest target overruns.
            return Answer(outcome.text)
        return Answer(
            "The house agent needs attention before it can finish. Check its conversation in Plowshare.",
            True,
        )
