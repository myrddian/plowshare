"""SDK-only HA gateway with durable receipts and resource-owned wait budgets.

Plowshare owns agents, history and jobs. HA keeps only bounded correlation and
submission fences. The save callback must return only after the supplied state
is durably committed; a best-effort storage API is not sufficient for that
contract. No adapter code retries an uncertain mutation or cancels remote work
merely because its caller wait expires.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol, TypeVar, runtime_checkable
from uuid import UUID

from ._vendor.plowshare import Client, Delivery, Refusal, TransportError
from ._vendor.plowshare.contracts import (
    AgentListRequest,
    AgentRunRequestEx,
    ConversationOpenRequestEx,
    ConversationTrajectoryRequest,
    JobStatusRequest,
    ObservedJobDto,
    ObservedOutcomeDto,
    Request,
)
from .const import CLEANUP_TIMEOUT, MAX_SESSIONS
from .diagnostic_models import (
    CommunicationObservation,
    CorrelationCounts,
    FailureCategory,
    GatewayDiagnostics,
)
from .models import Session, Settings, required_text
from .requests import (
    ConversationRequest,
    GatewayResult,
    HaRequest,
    TaskRequest,
    digest,
    external_key,
    kind,
    render_task,
)

_LOGGER = logging.getLogger(__name__)
T = TypeVar("T")
MAX_ACTIVE = 4
PENDING = (
    "Your house agent is still working. Ask again in this conversation to check "
    "the result; your request will not be repeated."
)
UNCERTAIN = (
    "Plowshare did not confirm your request. It may still be running, so I have "
    "not repeated it. Check the house agent conversation in Plowshare."
)
LEGACY = (
    "This retained house-agent conversation needs migration or inspection in "
    "Plowshare before it can continue. Its work has not been repeated."
)
ROUTING_CHANGED = (
    "This conversation belongs to an earlier Plowshare connection or profile. "
    "Inspect its retained work and start a new HA conversation; it was not repeated."
)


@dataclass(frozen=True)
class Answer:
    """Legacy Conversation presentation; task callers use GatewayResult instead."""

    text: str
    error: bool = False


class ConversationPort(Protocol):
    async def validate(self, required_agents: tuple[str, ...] = ()) -> None: ...

    async def ask(self, key: str, text: str) -> Answer: ...


class GatewayPort(ConversationPort, Protocol):
    """Typed entity-facing interface; only completed results contain data."""

    async def converse(self, request: ConversationRequest) -> GatewayResult: ...

    async def generate(self, request: TaskRequest) -> GatewayResult: ...

    async def async_close(self) -> bool: ...


@runtime_checkable
class ProfileRuntime(Protocol):
    """Shared HA entry runtime; concrete lifecycle/journal ownership is separate.

    Disabled profiles return None. Properties are read-only so an entity cannot
    replace another profile's gateway. False close retains the entire runtime
    and its unsettled physical owners for an explicit unload retry.
    """

    @property
    def conversation(self) -> GatewayPort | None: ...

    @property
    def task(self) -> GatewayPort | None: ...

    async def async_close(self) -> bool: ...


class SdkConversationAgent:
    def __init__(
        self,
        settings: Settings,
        sessions: dict[str, Session],
        save: Callable[[], Awaitable[None]],
        connect: Callable[..., Awaitable[Client]] = Client.connect,
        *,
        profile_id: str | None = None,
    ) -> None:
        self.settings = settings
        self.sessions = sessions
        self.save = save
        self.connect = connect
        # A stable installation/config-entry AND selected-profile identity is
        # mandatory for submission. None is useful only for read-only validation.
        self.profile_id = required_text(profile_id) if profile_id is not None else None
        self._active: set[str] = set()
        self._closed = False
        self._settled = asyncio.Event()
        self._settled.set()
        self._drains: dict[str, asyncio.Task[None]] = {}
        self._drain: asyncio.Task[None] | None = None
        self._last_connection_at: datetime | None = None
        self._last_successful_request_at: datetime | None = None
        self._last_failure_at: datetime | None = None
        self._last_failure_category: FailureCategory | None = None
        self._last_result_at: datetime | None = None
        self._last_result_status: str | None = None

    def diagnostics_snapshot(self) -> GatewayDiagnostics:
        """Observe memory only; a past reply never establishes current connectivity.

        Both profiles share the same bounded correlation store. Its aggregate is
        labeled explicitly so callers do not sum two views of the same records.
        No error text, remote content or correlation identities are retained here.
        """
        return GatewayDiagnostics(
            self._closed,
            len(self._active),
            len(self._drains),
            CommunicationObservation(
                self._last_connection_at,
                self._last_successful_request_at,
                self._last_failure_at,
                self._last_failure_category,
                self._last_result_at,
                self._last_result_status,
            ),
            CorrelationCounts(
                len(self.sessions),
                sum(s.phase in {"running", "waiting"} for s in self.sessions.values()),
                sum(
                    s.phase in {"sending", "uncertain"} for s in self.sessions.values()
                ),
                sum(s.phase == "preparing" for s in self.sessions.values()),
            ),
        )

    def _observe_failure(self, category: FailureCategory) -> None:
        self._last_failure_at = datetime.now(UTC)
        self._last_failure_category = category

    async def _connect(self) -> Client:
        try:
            client = await self.connect(
                self.settings.origin,
                self.settings.token,
                timeout=self.settings.request_timeout,
            )
        except Refusal:
            self._observe_failure("refusal")
            raise
        except TransportError:
            self._observe_failure("transport")
            raise
        except TimeoutError:
            self._observe_failure("timeout")
            raise
        except OSError:
            self._observe_failure("io")
            raise
        self._last_connection_at = datetime.now(UTC)
        return client

    async def _save(self) -> None:
        """The injected owner commits durable intent or raises within its budget."""
        try:
            async with asyncio.timeout(self.settings.request_timeout):
                await self.save()
        except TimeoutError, OSError:
            self._observe_failure("storage")
            raise

    async def _request(self, client: Client, request: Request[T]) -> T:
        # Capacity waiting is disabled: no caller timeout supplies replay proof.
        try:
            async with asyncio.timeout(self.settings.request_timeout):
                payload = (
                    await client.request(
                        request,
                        timeout=self.settings.request_timeout,
                        wait_for_capacity=False,
                    )
                ).require_payload()
        except Refusal:
            self._observe_failure("refusal")
            raise
        except TransportError:
            self._observe_failure("transport")
            raise
        except TimeoutError:
            self._observe_failure("timeout")
            raise
        except OSError:
            self._observe_failure("io")
            raise
        except ValueError:
            self._observe_failure("verification")
            raise
        self._last_successful_request_at = datetime.now(UTC)
        return payload

    async def _close(self, client: Client, owner: str) -> None:
        task = asyncio.create_task(client.close())
        self._drain = task  # Legacy fixture visibility; ownership is per key below.
        self._drains[owner] = task
        task.add_done_callback(lambda settled: self._drained(owner, settled))
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await asyncio.shield(task)
        except TimeoutError, TransportError, OSError:
            # Physical close still owns the bounded slot; do not invent abort.
            _LOGGER.warning("Plowshare HA connection cleanup did not settle")

    def _drained(self, owner: str, task: asyncio.Task[None]) -> None:
        if task.cancelled() or task.exception() is not None:
            # A failed cleanup task does not prove physical SDK settlement. Keep
            # this bounded owner quarantined; a fresh caller cannot reuse it.
            _LOGGER.warning("Plowshare HA connection cleanup failed")
            return
        if self._drains.get(owner) is task:
            del self._drains[owner]
            self._release(owner)

    def _release(self, owner: str) -> None:
        self._active.discard(owner)
        if not self._active:
            self._settled.set()

    async def async_close(self) -> bool:
        """Stop intake and wait finitely; false retains the owner for unload retry.

        It never cancels accepted work or claims failed physical cleanup settled.
        The HA lifecycle owner must not discard this gateway while false.
        """
        self._closed = True
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await self._settled.wait()
        except TimeoutError:
            return False
        return True

    def _claim(self, owner: str) -> bool:
        # Event-loop confined; no await between checking and claiming ownership.
        if self._closed or owner in self._active or len(self._active) >= MAX_ACTIVE:
            return False
        self._active.add(owner)
        self._settled.clear()
        return True

    async def validate(self, required_agents: tuple[str, ...] = ()) -> None:
        """Check all enabled profiles from one read-only project snapshot."""
        if type(required_agents) is not tuple or len(required_agents) > 16:
            raise ValueError("A bounded tuple of agent names is required")
        selected = required_agents or (self.settings.agent,)
        names = {required_text(name) for name in selected}
        owner = "validation"
        if not self._claim(owner):
            raise ValueError("Plowshare connection validation is busy")
        client = None
        try:
            async with asyncio.timeout(self.settings.request_timeout):
                client = await self._connect()
                agents = await self._request(
                    client, AgentListRequest(project=self.settings.project)
                )
                if not names.issubset({agent.name for agent in agents}):
                    raise ValueError(
                        "The selected agent is not available in this project"
                    )
        finally:
            if client is not None:
                await self._close(client, owner)
            else:
                self._release(owner)

    async def ask(self, key: str, text: str) -> Answer:
        """Compatibility presentation, not an alternative submission lifecycle."""
        try:
            request = ConversationRequest(key, text)
        except ValueError:
            return Answer(
                "Please provide a shorter, valid, non-empty house request.", True
            )
        result = await self._execute(request, legacy_key=True)
        if result.status == "completed":
            return Answer(result.text)
        if result.status == "pending":
            return Answer(PENDING)
        return Answer(result.message or "The house agent could not finish.", True)

    async def converse(self, request: ConversationRequest) -> GatewayResult:
        if type(request) is not ConversationRequest:
            raise ValueError("A typed conversation request is required")
        return await self._execute(request)

    async def generate(self, request: TaskRequest) -> GatewayResult:
        if type(request) is not TaskRequest:
            raise ValueError("A typed task request is required")
        return await self._execute(request)

    def _routing(self, request: HaRequest) -> tuple[str, str, str, str]:
        if self.profile_id is None:
            raise ValueError("A stable installation/profile identity is required")
        request_kind = kind(request)
        correlation = external_key(request)
        source = "ha." + request_kind + "." + digest((self.profile_id,))
        lookup = digest((self.profile_id, request_kind, correlation))
        # Credential equality is deliberately conservative: the public API does
        # not prove that two tokens denote the same account. Rotation/configuration
        # changes never retarget a retained receipt or transparently continue it.
        binding = digest(
            (
                self.settings.origin,
                self.settings.token,
                self.settings.project,
                self.settings.agent,
                self.profile_id,
                request_kind,
            )
        )
        return source, lookup, binding, correlation

    def _retained(
        self, request: HaRequest, lookup: str, binding: str, correlation: str
    ) -> tuple[str, Session | None, GatewayResult | None]:
        candidates = []
        if lookup in self.sessions:
            candidates.append((lookup, self.sessions[lookup]))
        if request.key in self.sessions and request.key != lookup:
            candidates.append((request.key, self.sessions[request.key]))
        # A changed routing/profile lookup cannot make old pending work invisible.
        # This scan is bounded by MAX_SESSIONS and never discloses another binding.
        for key, session in self.sessions.items():
            if session.correlation == correlation and (key, session) not in candidates:
                candidates.append((key, session))
        for key, session in candidates:
            if session.mapping != "external":
                return key, session, GatewayResult("uncertain", message=LEGACY)
            if session.binding != binding or session.correlation != correlation:
                return key, session, GatewayResult("uncertain", message=ROUTING_CHANGED)
        if len(candidates) > 1:
            return lookup, None, GatewayResult("uncertain", message=ROUTING_CHANGED)
        if candidates:
            key, session = candidates[0]
            return key, session, None
        return lookup, None, None

    async def _record_failure(self) -> None:
        try:
            await self._save()
        except TimeoutError, OSError:
            # Do not conceal the error as success or remove the in-memory fence.
            _LOGGER.warning("Plowshare HA correlation could not be committed")

    async def _execute(
        self, request: HaRequest, *, legacy_key: bool = False
    ) -> GatewayResult:
        result = await self._execute_request(request, legacy_key=legacy_key)
        self._last_result_at = datetime.now(UTC)
        self._last_result_status = result.status
        return result

    async def _execute_request(
        self, request: HaRequest, *, legacy_key: bool = False
    ) -> GatewayResult:
        try:
            body = render_task(request)
            source, lookup, binding, correlation = self._routing(request)
        except ValueError:
            return GatewayResult(
                "refused",
                message="The HA request or profile configuration is unsupported.",
            )
        storage, session, problem = self._retained(
            request, lookup, binding, correlation
        )
        if problem is not None:
            return problem
        if not self._claim(lookup):
            return GatewayResult(
                "busy",
                message="The house agent is handling another request. Try shortly.",
            )
        client = None
        try:
            if session is None:
                if len(self.sessions) >= MAX_SESSIONS:
                    idle = next(
                        (
                            key
                            for key, item in self.sessions.items()
                            if item.mapping == "external"
                            and item.phase in {"idle", "complete"}
                        ),
                        None,
                    )
                    if idle is None:
                        return GatewayResult(
                            "busy",
                            message="Too many retained house-agent requests need inspection.",
                        )
                    del self.sessions[idle]
                storage = request.key if legacy_key else lookup
                session = Session(
                    mapping="external", binding=binding, correlation=correlation
                )
                self.sessions[storage] = session
            if session.phase in {"sending", "uncertain"}:
                return GatewayResult("uncertain", message=UNCERTAIN)
            if session.phase == "idle":
                # This transient pre-mutation phase prevents another profile's
                # bounded state owner from evicting a record while connect/read
                # is suspended. A persisted preparing row safely restarts idle.
                session.phase = "preparing"
            async with asyncio.timeout(self.settings.response_timeout):
                client = await self._connect()
                if session.phase == "preparing":
                    if session.conversation is None:
                        # Persist intent before even an alias-opening mutation.
                        session.phase = "sending"
                        await self._save()
                        opened = await self._request(
                            client,
                            ConversationOpenRequestEx(
                                project=self.settings.project,
                                source=source,
                                external_conversation=correlation,
                                max_model_calls=40,
                                max_turns=40,
                            ),
                        )
                        session.conversation = required_text(opened.id)
                        session.phase = "preparing"
                        await self._save()
                    # Read an answer cursor once before this new turn. The SDK
                    # facade resolves the alias inside the subsequent run operation.
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
                        AgentRunRequestEx(
                            agent=self.settings.agent,
                            project=self.settings.project,
                            source=source,
                            external_conversation=correlation,
                            task=body,
                        ),
                    )
                    session.job = required_text(started.id)
                    if started.conversation != session.conversation:
                        # Possible admission is now uncertain; never read a foreign
                        # receipt or infer that this mutation can be repeated.
                        raise ValueError("Foreign Plowshare conversation reply")
                    session.phase = "running"
                    await self._save()
                while True:
                    answer = await self._poll(client, session)
                    if answer is not None:
                        if kind(request) == "task":
                            # Keep the original terminal receipt while retained;
                            # no model text is stored in the correlation journal.
                            session.phase = "complete"
                        else:
                            session.phase = "idle"
                            session.job = None
                        await self._save()
                        if answer.error:
                            return GatewayResult("failed", message=answer.text)
                        return GatewayResult("completed", text=answer.text)
                    await asyncio.sleep(0.5)
        except Refusal as error:
            if session is not None and session.phase == "preparing":
                session.phase = "idle"
            elif session is not None and session.phase == "sending":
                # Ex failures can follow alias commit or job admission, including
                # result-archive unavailability and post-handler authority fences.
                # A negative reply alone is not proof that no mutation happened.
                session.phase = (
                    "idle" if error.code == "CAPACITY_EXHAUSTED" else "uncertain"
                )
                await self._record_failure()
            if session is not None and session.phase == "uncertain":
                return GatewayResult("uncertain", message=UNCERTAIN)
            return GatewayResult(
                "refused",
                message="Plowshare refused this request. Check the agent's access and allowance.",
            )
        except (TransportError, TimeoutError, OSError, ValueError) as error:
            if session is not None and session.phase == "preparing":
                session.phase = "idle"
            if session is not None and session.phase == "sending":
                session.phase = (
                    "idle"
                    if isinstance(error, TransportError)
                    and error.delivery == Delivery.NOT_SUBMITTED
                    else "uncertain"
                )
                await self._record_failure()
            if session is not None and session.phase == "uncertain":
                return GatewayResult("uncertain", message=UNCERTAIN)
            if isinstance(error, ValueError):
                self._observe_failure("verification")
                return GatewayResult(
                    "unavailable",
                    message="The retained house-agent result could not be verified. Inspect it in Plowshare.",
                )
            if session is not None and session.job is not None:
                return GatewayResult("pending", message=PENDING)
            return GatewayResult(
                "unavailable",
                message="I could not reach the house agent. Check the Plowshare connection.",
            )
        except asyncio.CancelledError:
            if session is not None and session.phase == "preparing":
                session.phase = "idle"
            if session is not None and session.phase == "sending":
                session.phase = "uncertain"
                await self._record_failure()
            raise
        finally:
            if client is not None:
                await self._close(client, lookup)
            else:
                self._release(lookup)

    async def _poll(self, client: Client, session: Session) -> Answer | None:
        if session.job is None or session.conversation is None:
            raise ValueError("Pending turn has no retained job")
        if session.phase in {"running", "complete"}:
            job = await self._request(client, JobStatusRequest(job=session.job))
            self._check_job(job, session)
            if session.phase == "complete":
                if job.state != "DONE" or not isinstance(
                    job.outcome, ObservedOutcomeDto
                ):
                    raise ValueError("Retained terminal result is unavailable")
                # Completion was established and committed before publication.
                # Retain that exact job rather than requiring old trajectory
                # receipts to survive compaction/archive on every repeat read.
                return self._answer(job.outcome)
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
                        session.job = job.id
                        session.cursor = int(entry.ordinal)
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
                if (
                    entry.cut
                    or not isinstance(entry.excerpt, str)
                    or len(entry.excerpt.encode("utf-8")) > 65536
                ):
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
