"""Lifecycle/authority tests use typed SDK fakes; no database or real devices."""

import asyncio
import json
import unittest
from dataclasses import replace
from unittest.mock import AsyncMock, patch

import support  # noqa: F401
from custom_components.plowshare._vendor.plowshare import (
    Delivery,
    Refusal,
    Reply,
    TransportError,
)
from custom_components.plowshare._vendor.plowshare.contracts import (
    UNSET,
    AgentRunRequest,
    AskedViewDto,
    ConversationOpenRequest,
    ConversationTrajectoryRequest,
    ConversationViewDto,
    EntryPageViewDto,
    EntryViewDto,
    JobStatusRequest,
    ObservedJobDto,
    ObservedOutcomeDto,
    ObservedStartedJobDto,
)

from custom_components.plowshare.agent import PENDING, UNCERTAIN, SdkConversationAgent
from custom_components.plowshare.models import (
    Session,
    Settings,
    decode_sessions,
    encode_sessions,
    session_key,
)

KEY = session_key("chat", "user", None, None, False)
SETTINGS = Settings(
    "https://plowshare.example.invalid", "fixture-token", "house", "house_coordinator"
)


def outcome(ending="ANSWERED", text="The office is 22 degrees."):
    return ObservedOutcomeDto(answered=ending == "ANSWERED", ending=ending, text=text)


def job(identity="job-1", ending="ANSWERED", text="The office is 22 degrees."):
    return ObservedJobDto(
        id=identity, conversation="conv-1", state="DONE", outcome=outcome(ending, text)
    )


def entry(ordinal, kind="answer", identity="job-1", text="", calls=(), call_id=None):
    return EntryViewDto(
        ordinal=ordinal,
        kind=kind,
        job=identity,
        excerpt=text,
        tool_calls=calls,
        tool_call_id=call_id,
        completion=None,
        cut=False,
        dispatch=None,
        ejected_at=None,
        handle=None,
        length=len(text),
        outcome=None,
        recorded_at=None,
        speaker=None,
        speaker_name=None,
        superseded_by=None,
        took_millis=None,
        turn_ordinal=1,
        wire_model=None,
    )


def page(entries=(), through=0):
    return EntryPageViewDto(
        entries=tuple(entries),
        total=len(entries),
        limit=100,
        offset=0,
        through=through,
        oldest=None,
        more=None,
    )


def publication():
    call = AskedViewDto(
        id="publish",
        name="relay_publish",
        arguments="{}",
        cut=False,
        length=2,
        opened=None,
        salient=None,
    )
    receipt = json.dumps(
        {
            "eventId": "11111111-1111-4111-8111-111111111111",
            "topic": "ha.requests",
            "position": "1",
        }
    )
    return (
        entry(1, calls=(call,)),
        entry(2, "tool_result", text=receipt, call_id="publish"),
    )


class FakeClient:
    def __init__(self):
        self.requests = []
        self.status = job()
        self.statuses = {}
        self.entries = ()
        self.error = None
        self.hold = None
        self.submitted = asyncio.Event()
        self.closed = False

    async def request(self, request, **kwargs):
        assert kwargs["wait_for_capacity"] is False
        self.requests.append(request)
        if isinstance(request, ConversationOpenRequest):
            value = ConversationViewDto(
                id="conv-1",
                project="house",
                title=None,
                max_model_calls=40,
                max_turns=40,
                model_calls_spent=0,
                no_budget=False,
                no_turn_cap=False,
            )
        elif isinstance(request, AgentRunRequest):
            self.submitted.set()
            if self.error is not None:
                raise self.error
            if self.hold is not None:
                await self.hold.wait()
            value = ObservedStartedJobDto(id="job-1", conversation="conv-1")
        elif isinstance(request, JobStatusRequest):
            value = self.statuses.get(request.job) or (
                self.status
                if request.job == "job-1"
                else job(request.job, text="The light is on.")
            )
        elif isinstance(request, ConversationTrajectoryRequest):
            listed = self.entries
            if request.tail is True:
                value = page()
            else:
                after = request.after if request.after is not UNSET else 0
                listed = tuple(
                    e for e in listed if e.ordinal > after and e.kind in request.kinds
                )
                if request.drawn is True:
                    listed = tuple(e for e in listed if not e.tool_calls)
                value = page(
                    listed, through=max((e.ordinal for e in self.entries), default=0)
                )
        else:
            value = ()
        return Reply("OK", None, value)

    async def close(self):
        self.closed = True


class AgentTests(unittest.IsolatedAsyncioTestCase):
    def make(self, **overrides):
        client = FakeClient()
        sessions = {}
        snapshots = []

        async def save():
            snapshots.append(encode_sessions(sessions))

        agent = SdkConversationAgent(
            replace(SETTINGS, **overrides),
            sessions,
            save,
            AsyncMock(return_value=client),
        )
        return agent, client, snapshots

    async def test_follow_up_reuses_conversation_without_replaying_history(self):
        agent, client, snapshots = self.make()
        first = await agent.ask(KEY, "What is the temperature?")
        second = await agent.ask(KEY, "And outside?")
        self.assertEqual(first.text, "The office is 22 degrees.")
        self.assertFalse(second.error)
        runs = [r for r in client.requests if isinstance(r, AgentRunRequest)]
        self.assertEqual(
            [r.task for r in runs], ["What is the temperature?", "And outside?"]
        )
        self.assertEqual([r.conversation for r in runs], ["conv-1", "conv-1"])
        self.assertEqual(
            sum(isinstance(r, ConversationOpenRequest) for r in client.requests), 1
        )
        self.assertTrue(any(v[KEY]["phase"] == "sending" for v in snapshots))
        self.assertTrue(client.closed)

    async def test_relay_acceptance_is_not_spoken_as_the_device_result(self):
        agent, client, _ = self.make()
        client.status = job(text="Evidence requested.")
        client.entries = (
            *publication(),
            entry(3, identity="reply-job", text="The light is on."),
        )
        answer = await agent.ask(KEY, "Turn the light on")
        self.assertEqual(answer.text, "The light is on.")
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )
        reads = [
            r for r in client.requests if isinstance(r, ConversationTrajectoryRequest)
        ]
        self.assertTrue(
            any(r.drawn is True and r.kinds == ("answer", "refusal") for r in reads)
        )

    async def test_failed_publication_returns_original_answer_without_waiting(self):
        agent, client, _ = self.make()
        call, receipt = publication()
        client.entries = (
            call,
            replace(receipt, excerpt="Relay publication refused: denied"),
        )
        client.status = job(text="The action was refused.")
        self.assertEqual(
            (await agent.ask(KEY, "Turn it on")).text, "The action was refused."
        )

    async def test_native_reply_can_request_more_evidence_without_another_ha_turn(self):
        agent, client, _ = self.make()
        call, receipt = publication()
        client.status = job(text="Evidence requested.")
        client.entries = (
            call,
            receipt,
            replace(call, ordinal=3, job="reply-one"),
            replace(receipt, ordinal=4, job="reply-one"),
            entry(5, identity="reply-one", text="More evidence requested."),
            entry(
                6,
                identity="reply-two",
                text="The office is warm because the heater is on.",
            ),
        )
        client.statuses["reply-one"] = job("reply-one", text="More evidence requested.")
        client.statuses["reply-two"] = job(
            "reply-two", text="The office is warm because the heater is on."
        )
        answer = await agent.ask(KEY, "Why is the office warm?")
        self.assertEqual(answer.text, "The office is warm because the heater is on.")
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )

    async def test_relay_wait_survives_restart_and_reuses_accepted_job(self):
        agent, client, _ = self.make(response_timeout=0.02)
        client.status = job(text="Evidence requested.")
        client.entries = publication()
        self.assertEqual((await agent.ask(KEY, "Turn it on")).text, PENDING)
        saved = decode_sessions(encode_sessions(agent.sessions))
        self.assertEqual(saved[KEY].phase, "waiting")
        restored = SdkConversationAgent(
            SETTINGS, saved, AsyncMock(), AsyncMock(return_value=client)
        )
        client.entries = (
            *client.entries,
            entry(3, identity="reply-job", text="The light is on."),
        )
        self.assertEqual(
            (await restored.ask(KEY, "Did it work?")).text, "The light is on."
        )
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )

    async def test_stalled_cleanup_keeps_admission_until_physical_drain(self):
        agent, client, _ = self.make()
        drain = asyncio.Event()
        client.close = AsyncMock(side_effect=drain.wait)
        with patch("custom_components.plowshare.agent.CLEANUP_TIMEOUT", 0.01):
            self.assertFalse((await agent.ask(KEY, "Hello")).error)
        self.assertTrue((await agent.ask(KEY, "Another request")).error)
        drain.set()
        await agent._drain
        self.assertFalse((await agent.ask(KEY, "Now try")).error)

    async def test_response_timeout_then_restart_checks_original_job(self):
        agent, client, _ = self.make(response_timeout=0.03)
        client.status = ObservedJobDto(
            id="job-1", conversation="conv-1", state="RUNNING"
        )
        self.assertEqual((await agent.ask(KEY, "Turn on the light")).text, PENDING)
        saved = decode_sessions(encode_sessions(agent.sessions))
        restored = SdkConversationAgent(
            SETTINGS, saved, AsyncMock(), AsyncMock(return_value=client)
        )
        client.status = job(text="The light is on.")
        self.assertEqual(
            (await restored.ask(KEY, "Is it done?")).text, "The light is on."
        )
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )

    async def test_lost_acknowledgment_is_retained_and_never_replayed(self):
        agent, client, _ = self.make()
        client.error = TransportError(Delivery.UNKNOWN, "fixture")
        self.assertEqual((await agent.ask(KEY, "Turn it on")).text, UNCERTAIN)
        client.error = None
        self.assertEqual((await agent.ask(KEY, "Try again")).text, UNCERTAIN)
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )
        self.assertEqual(
            decode_sessions(encode_sessions(agent.sessions))[KEY].phase, "uncertain"
        )

    async def test_known_non_submission_and_refusal_do_not_wedge_session(self):
        for error in (
            TransportError(Delivery.NOT_SUBMITTED, "fixture"),
            Refusal("FORBIDDEN", "private detail"),
        ):
            with self.subTest(error=type(error).__name__):
                agent, client, _ = self.make()
                client.error = error
                answer = await agent.ask(KEY, "Hello")
                self.assertTrue(answer.error)
                self.assertNotIn("private detail", answer.text)
                self.assertEqual(agent.sessions[KEY].phase, "idle")

    async def test_cancellation_and_concurrent_intake_do_not_repeat_action(self):
        agent, client, _ = self.make()
        client.hold = asyncio.Event()
        task = asyncio.create_task(agent.ask(KEY, "Turn it on"))
        await asyncio.wait_for(client.submitted.wait(), 1)
        self.assertTrue((await agent.ask(KEY, "Another request")).error)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(agent.sessions[KEY].phase, "uncertain")
        self.assertTrue(client.closed)
        self.assertEqual(
            sum(isinstance(r, AgentRunRequest) for r in client.requests), 1
        )

    async def test_failure_or_budget_stop_never_speaks_partial_model_prose(self):
        for ending in ("CALL_BUDGET", "TURN_CAP", "UNAVAILABLE"):
            agent, client, _ = self.make()
            client.status = job(ending=ending, text="Secret partial reasoning")
            answer = await agent.ask(KEY, "Question")
            self.assertTrue(answer.error)
            self.assertNotIn("Secret", answer.text)

    async def test_invalid_input_has_no_connection_or_mutation(self):
        agent, client, _ = self.make()
        for text in ("", "  ", "bad\x00text", "x" * 16001):
            self.assertTrue((await agent.ask(KEY, text)).error)
        agent.connect.assert_not_awaited()
        self.assertEqual(client.requests, [])

    async def test_slow_admission_obeys_outer_request_budget(self):
        agent, client, _ = self.make(request_timeout=0.02)
        client.hold = asyncio.Event()
        start = asyncio.get_running_loop().time()
        answer = await agent.ask(KEY, "Turn it on")
        self.assertLess(asyncio.get_running_loop().time() - start, 0.5)
        self.assertEqual(answer.text, UNCERTAIN)


class BoundaryTests(unittest.TestCase):
    def test_invalid_settings_and_secret_repr(self):
        for origin in ("", "http://u:p@host", "https://host/path", "https://host?q=1"):
            with self.assertRaises(ValueError):
                replace(SETTINGS, origin=origin)
        for limit in (True, 0, -1, float("inf"), float("nan")):
            with self.assertRaises(ValueError):
                replace(SETTINGS, request_timeout=limit)
        self.assertNotIn("fixture-token", repr(SETTINGS))

    def test_principals_and_voice_devices_cannot_share_a_conversation(self):
        keys = {
            session_key("chat", u, d, s, True)
            for u, d, s in (
                ("alice", "device", None),
                ("bob", "device", None),
                (None, "device", "satellite"),
                (None, "other", "satellite"),
            )
        }
        self.assertEqual(len(keys), 4)
        for anonymous, device in ((False, "device"), (True, None)):
            with self.assertRaises(PermissionError):
                session_key("chat", None, device, None, anonymous)

    def test_restart_sending_is_uncertain_and_bad_store_fails_closed(self):
        loaded = decode_sessions(
            encode_sessions({KEY: Session("conv", phase="sending")})
        )
        self.assertEqual(loaded[KEY].phase, "uncertain")
        for value in (
            [],
            {KEY: {}},
            {KEY: {"conversation": None, "job": None, "phase": "running", "cursor": 0}},
        ):
            with self.assertRaises(ValueError):
                decode_sessions(value)
