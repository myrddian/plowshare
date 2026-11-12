"""The Ask conversation: one conversation, journalled sends, polling and caps."""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, Reply, TransportError
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
    SourceViewDto,
)
from support import configuration

from plowshare_privacy.ask import (
    MAX_TURNS,
    AskCall,
    AskConflict,
    AskEntry,
    AskJournal,
    AskLedger,
    AskService,
    AskSettings,
    AskTurn,
    AskWork,
    JobState,
    SdkAskPort,
)

AT = "2026-11-13T01:00:00Z"
LATER = "2026-11-13T01:00:05Z"


class FakePort:
    """An in-memory ``AskPort``: records every call, answers from fields."""

    def __init__(self) -> None:
        self.latest_id: str | None = None
        self.opened: list[tuple[str, int, int]] = []
        self.runs: list[tuple[str, str, str]] = []
        self.resumes: list[tuple[str, str, int, int]] = []
        self.states: dict[str, JobState] = {}
        self.log: list[AskEntry] = []
        self.run_error: Exception | None = None
        self.jobs = 0

    async def latest(self, agent: str) -> str | None:
        return self.latest_id

    async def open(self, agent: str, max_model_calls: int, max_turns: int) -> str:
        self.opened.append((agent, max_model_calls, max_turns))
        return "conv-1"

    async def run(self, agent: str, conversation: str, task: str) -> str:
        self.runs.append((agent, conversation, task))
        if self.run_error is not None:
            raise self.run_error
        self.jobs += 1
        job = f"job-{self.jobs}"
        self.states[job] = "running"
        return job

    async def job_state(self, job: str) -> JobState:
        return self.states[job]

    async def entries(
        self, conversation: str, offset: int, limit: int
    ) -> tuple[AskEntry, ...]:
        return tuple(self.log[offset : offset + limit])

    async def resume(
        self, conversation: str, agent: str, max_model_calls: int, max_turns: int
    ) -> str:
        self.resumes.append((conversation, agent, max_model_calls, max_turns))
        self.jobs += 1
        job = f"job-{self.jobs}"
        self.states[job] = "running"
        return job


def said(index: int, text: str, job: str | None) -> AskEntry:
    return AskEntry(index, "user", text, LATER, "question", job=job)


def answer(
    index: int, text: str, job: str | None, calls: tuple[AskCall, ...] = ()
) -> AskEntry:
    return AskEntry(index, "agent", text, LATER, "answer", calls=calls, job=job)


def result(index: int, call: str, reference: str | None, job: str) -> AskEntry:
    return AskEntry(
        index,
        "tool",
        "{}",
        LATER,
        "tool_result",
        answers=call,
        reference=reference,
        job=job,
    )


class AskServiceTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.port = FakePort()
        self.now = AT
        self.service = self.make()

    def make(self) -> AskService:
        return AskService(
            AskJournal(self.root),
            self.port,
            clock=lambda: self.now,
            new_id=lambda: str(uuid4()),
        )

    async def test_the_first_question_opens_one_conversation_with_the_configured_caps(
        self,
    ) -> None:
        self.service.set_settings(AskSettings(max_model_calls=50, max_turns=12))
        view = await self.service.ask("Which devices talk to the internet?")
        self.assertEqual(self.port.opened, [("privacy_assistant", 50, 12)])
        self.assertEqual(
            self.port.runs,
            [("privacy_assistant", "conv-1", "Which devices talk to the internet?")],
        )
        self.assertEqual((view.conversation, view.turn_state), ("conv-1", "running"))
        self.port.states["job-1"] = "answered"
        await self.service.poll()
        await self.service.ask("And the television?")
        self.assertEqual(len(self.port.opened), 1)
        ledger = AskJournal(self.root).read()
        self.assertEqual(ledger.totals, (50, 12))
        self.assertEqual([t.state for t in ledger.turns], ["answered", "running"])

    async def test_a_lost_journal_recovers_the_existing_conversation(self) -> None:
        self.port.latest_id = "conv-existing"
        view = await self.service.ask("Hello")
        self.assertEqual(self.port.opened, [])
        self.assertEqual(view.conversation, "conv-existing")
        self.assertEqual(self.port.runs[0][1], "conv-existing")

    async def test_sending_is_refused_while_a_turn_runs(self) -> None:
        await self.service.ask("First")
        with self.assertRaises(AskConflict):
            await self.service.ask("Second")
        self.assertEqual(len(self.port.runs), 1)
        # A turn woken by a specialist's reply also holds the conversation.
        self.port.states["job-1"] = "answered"
        self.port.log = [
            said(1, "First", "job-1"),
            answer(2, "Asked the analyst.", "job-1"),
            AskEntry(3, "system", "reply", LATER, "message", job="job-woken"),
        ]
        self.port.states["job-woken"] = "running"
        await self.service.poll()
        self.assertEqual(self.service.view().turn_state, "running")
        with self.assertRaises(AskConflict):
            await self.service.ask("Second")
        self.port.states["job-woken"] = "answered"
        await self.service.poll()
        await self.service.ask("Second")
        self.assertEqual(len(self.port.runs), 2)

    async def test_an_uncertain_send_is_unconfirmed_and_never_resent(self) -> None:
        self.port.run_error = TransportError(Delivery.UNKNOWN, "lost reply")
        view = await self.service.ask("Is the camera safe?")
        self.assertEqual(view.turn_state, "unconfirmed")
        self.assertIn("not confirmed", view.detail or "")
        for _ in range(3):
            await self.service.poll()
        self.assertEqual(len(self.port.runs), 1)
        # The conversation shows the question, so the turn is running after all.
        self.port.states["job-9"] = "running"
        self.port.log = [said(1, "Is the camera safe?", "job-9")]
        await self.service.poll()
        self.assertEqual(self.service.view().turn_state, "running")
        self.assertEqual(AskJournal(self.root).read().turns[-1].job, "job-9")
        self.assertEqual(len(self.port.runs), 1)

    async def test_an_unsent_or_refused_question_fails_and_may_be_asked_again(
        self,
    ) -> None:
        self.port.run_error = TransportError(Delivery.NOT_SUBMITTED, "offline")
        view = await self.service.ask("One")
        self.assertEqual(view.turn_state, "failed")
        self.port.run_error = Refusal("FORBIDDEN", "Not exported")
        view = await self.service.ask("One")
        self.assertEqual(view.turn_state, "failed")
        self.assertIn("Not exported", view.detail or "")
        self.port.run_error = None
        self.assertEqual((await self.service.ask("One")).turn_state, "running")

    async def test_a_restart_reads_the_running_turn_back_without_resending(
        self,
    ) -> None:
        await self.service.ask("Scan the printer")
        self.port.log = [said(1, "Scan the printer", "job-1")]
        await self.service.poll()
        restarted = self.make()
        self.assertEqual(restarted.view().turn_state, "running")
        self.port.states["job-1"] = "answered"
        self.port.log.append(answer(2, "Queued a deep scan.", "job-1"))
        await restarted.poll()
        view = restarted.view()
        self.assertEqual(view.turn_state, "answered")
        self.assertEqual([e.index for e in view.entries], [1, 2])
        self.assertEqual(len(self.port.runs), 1)

    async def test_a_send_interrupted_by_a_restart_is_unconfirmed(self) -> None:
        turn = AskTurn(str(uuid4()), "Hello", AT, None, "sending")
        AskJournal(self.root).save(AskLedger(conversation="conv-1", turns=(turn,)))
        restarted = self.make()
        self.assertEqual(restarted.view().turn_state, "unconfirmed")
        await restarted.poll()
        self.assertEqual(self.port.runs, [])

    async def test_new_agent_entries_set_unread_and_mark_read_clears_it(self) -> None:
        await self.service.ask("Hello")
        self.port.log = [said(1, "Hello", "job-1")]
        await self.service.poll()
        self.assertFalse(self.service.view().unread)
        self.port.log.append(answer(2, "Hi there.", "job-1"))
        await self.service.poll()
        self.assertTrue(self.service.view().unread)
        self.service.mark_read()
        self.assertFalse(self.service.view().unread)
        self.assertFalse(AskJournal(self.root).read().unread)
        # Already-seen entries reloaded after a restart are not new.
        restarted = self.make()
        await restarted.poll()
        self.assertFalse(restarted.view().unread)
        self.assertEqual(AskJournal(self.root).read().seen_entries, 2)

    async def test_waiting_and_done_come_from_send_message_and_orchestration_entries(
        self,
    ) -> None:
        await self.service.ask("Look into the doorbell")
        calls = (
            AskCall("c1", "send_message", "privacy_analyst", "Interpret the doorbell"),
            AskCall("c2", "send_message", "privacy_reviewer", "Check the claim"),
            AskCall(
                "c3",
                "orchestrate_investigate_network",
                "investigate_network",
                "Doorbell",
            ),
            AskCall("c4", "send_message", None, None),
            AskCall("c5", "network_devices", None, None),
        )
        self.port.log = [
            said(1, "Look into the doorbell", "job-1"),
            answer(2, "", "job-1", calls),
            result(3, "c1", "msg-1", "job-1"),
            result(4, "c2", "msg-2", "job-1"),
            result(5, "c3", "orc_1", "job-1"),
            result(6, "c4", None, "job-1"),
            answer(7, "I have asked the analyst and the reviewer.", "job-1"),
            AskEntry(
                8,
                "system",
                "The doorbell talks to its vendor.",
                LATER,
                "message",
                reference="msg-1",
                sender="privacy_analyst",
                job="job-2",
            ),
        ]
        self.port.states.update({"job-1": "answered", "job-2": "answered"})
        await self.service.poll()
        view = self.service.view()
        self.assertEqual(
            view.waiting,
            (
                AskWork("message", "privacy_reviewer", "Check the claim", LATER),
                AskWork("orchestration", "investigate_network", "Doorbell", LATER),
            ),
        )
        self.assertEqual(
            view.done,
            (AskWork("message", "privacy_analyst", "Interpret the doorbell", LATER),),
        )
        tools = {e.answers: e.tool for e in view.entries if e.kind == "tool_result"}
        self.assertEqual(tools["c1"], "send_message")
        self.port.log.append(
            AskEntry(9, "system", "Report", LATER, "orchestration", reference="orc_1")
        )
        await self.service.poll()
        self.assertEqual(
            [w.target for w in self.service.view().done],
            ["privacy_analyst", "investigate_network"],
        )

    async def test_a_capped_turn_pauses_and_top_up_raises_the_totals_once(
        self,
    ) -> None:
        await self.service.ask("Investigate everything")
        with self.assertRaises(AskConflict):
            await self.service.top_up()
        self.port.states["job-1"] = "capped"
        await self.service.poll()
        view = self.service.view()
        self.assertEqual((view.turn_state, view.paused), ("capped", True))
        view = await self.service.top_up()
        self.assertEqual(self.port.resumes, [("conv-1", "privacy_assistant", 60, 30)])
        self.assertEqual((view.paused, view.turn_state), (False, "running"))
        ledger = AskJournal(self.root).read()
        self.assertEqual(ledger.totals, (60, 30))
        self.assertEqual(ledger.turns[-1].job, "job-2")
        with self.assertRaises(AskConflict):
            await self.service.top_up()
        for _ in range(3):
            await self.service.poll()
        self.assertEqual(len(self.port.resumes), 1)

    async def test_an_uncertain_top_up_is_not_resent(self) -> None:
        await self.service.ask("Investigate")
        self.port.states["job-1"] = "capped"
        await self.service.poll()
        self.port.resume = AsyncMock(  # type: ignore[method-assign]
            side_effect=TransportError(Delivery.UNKNOWN, "lost")
        )
        view = await self.service.top_up()
        self.assertEqual((view.turn_state, view.paused), ("unconfirmed", False))
        self.assertEqual(AskJournal(self.root).read().totals, (60, 30))
        self.port.resume = AsyncMock(  # type: ignore[method-assign]
            side_effect=TransportError(Delivery.NOT_SUBMITTED, "offline")
        )
        with self.assertRaises(AskConflict):
            await self.service.top_up()

    async def test_bad_questions_and_settings_are_refused(self) -> None:
        for bad in ("", " \n ", "x" * 4001, "a\x00b", "tab\there"):
            with self.subTest(bad=bad[:8]):
                with self.assertRaises(ValueError):
                    await self.service.ask(bad)
        self.assertEqual(self.port.runs, [])
        await self.service.ask("Two lines\nare fine")
        self.assertEqual(len(self.port.runs), 1)
        for field in ("max_model_calls", "max_turns", "top_up_calls"):
            for value in (0, 201, True, 1.5):
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ValueError):
                        AskSettings(**{field: value})  # type: ignore[arg-type]
        AskSettings(max_model_calls=1, max_turns=200, top_up_calls=200)

    async def test_the_journal_is_private_bounded_and_strict(self) -> None:
        journal = AskJournal(self.root)
        self.assertEqual(journal.read(), AskLedger())
        turns = tuple(
            AskTurn(str(uuid4()), f"q{i}", AT, f"job-{i}", "answered")
            for i in range(MAX_TURNS + 5)
        )
        await self.service.ask("Hello")
        # The service keeps only the last MAX_TURNS turns.
        ledger = replace(
            journal.read(), turns=turns[: MAX_TURNS - 1] + journal.read().turns
        )
        journal.save(ledger)
        self.port.states["job-1"] = "answered"
        restarted = self.make()
        await restarted.poll()
        await restarted.ask("Again")
        self.assertEqual(len(journal.read().turns), MAX_TURNS)
        self.assertEqual(journal.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError):
            journal.save(replace(ledger, turns=turns))
        raw = json.loads(journal.path.read_text())
        bad_values = [
            {**raw, "extra": 1},
            {**raw, "version": 2},
            {**raw, "user": "someone else"},
            {**raw, "seen_entries": -1},
            {**raw, "totals": [0, 30]},
            {**raw, "conversation": None},
            {**raw, "turns": raw["turns"] + [raw["turns"][-1]]},
            {**raw, "turns": [{**raw["turns"][-1], "state": "running", "job": None}]},
            {**raw, "turns": [{**raw["turns"][-1], "question": "a\x00"}]},
            {
                **raw,
                "turns": [
                    {**raw["turns"][0], "state": "running"},
                    {**raw["turns"][1], "state": "running"},
                ],
            },
        ]
        for value in bad_values:
            with self.subTest(value=str(value)[:60]):
                with self.assertRaises(ValueError):
                    AskLedger.decode(value)
        journal.path.chmod(0o644)
        with self.assertRaises(ValueError):
            journal.read()
        broken = self.make()
        self.assertFalse(broken.view().available)
        with self.assertRaises(AskConflict):
            await broken.ask("Hello")
        journal.path.chmod(0o600)
        link = self.root / "elsewhere.json"
        os.replace(journal.path, link)
        journal.path.symlink_to(link)
        with self.assertRaises(ValueError):
            journal.read()

    async def test_run_keeps_the_error_until_a_poll_succeeds(self) -> None:
        await self.service.ask("Hello")
        self.port.job_state = AsyncMock(  # type: ignore[method-assign]
            side_effect=TransportError(Delivery.UNKNOWN, "down")
        )
        stop = asyncio.Event()
        with unittest.mock.patch("plowshare_privacy.ask._POLL_SECONDS", 0.01):
            task = asyncio.create_task(self.service.run(stop))
            await asyncio.sleep(0.05)
            self.assertIn("TransportError", self.service.view().detail or "")
            self.port.job_state = AsyncMock(return_value="answered")  # type: ignore[method-assign]
            await asyncio.sleep(0.05)
            stop.set()
            await task
        self.assertIsNone(self.service.view().detail)


def reply(payload: object) -> Reply[object]:
    return Reply("OK", None, payload)


def entry(**fields: object) -> EntryViewDto:
    base: dict[str, object] = dict(
        completion=None,
        cut=False,
        dispatch=None,
        ejected_at=None,
        excerpt="",
        handle=None,
        kind="answer",
        length=0,
        ordinal=1,
        outcome=None,
        recorded_at="2026-11-13T01:00:05.123Z",
        speaker=None,
        speaker_name=None,
        superseded_by=None,
        took_millis=None,
        tool_call_id=None,
        tool_calls=(),
        turn_ordinal=1,
        wire_model=None,
    )
    base.update(fields)
    return EntryViewDto(**base)  # type: ignore[arg-type]


def asked(call: str, name: str, arguments: str) -> AskedViewDto:
    return AskedViewDto(
        arguments=arguments,
        cut=False,
        id=call,
        length=len(arguments),
        name=name,
        opened=None,
        salient=None,
    )


class SdkAskPortTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.config = configuration(Path(directory.name).resolve())
        self.client = Mock(spec=Client)
        self.client.request = AsyncMock()
        self.port = SdkAskPort(lambda: self.client, self.config)

    def sent(self) -> object:
        return self.client.request.await_args.args[0]

    async def test_requests_are_project_scoped_and_typed(self) -> None:
        view = ConversationViewDto(
            id="conv-1",
            max_model_calls=40,
            max_turns=30,
            model_calls_spent=0,
            no_budget=False,
            no_turn_cap=False,
            project=self.config.project,
            title=None,
        )
        self.client.request.return_value = reply(view)
        self.assertEqual(await self.port.latest("privacy_assistant"), "conv-1")
        self.assertEqual(
            self.sent(),
            ConversationLatestRequest(
                agent="privacy_assistant", project=self.config.project
            ),
        )
        self.client.request.return_value = reply(None)
        self.assertIsNone(await self.port.latest("privacy_assistant"))
        self.client.request.return_value = reply(view)
        self.assertEqual(await self.port.open("privacy_assistant", 40, 30), "conv-1")
        self.assertEqual(
            self.sent(),
            ConversationOpenRequest(
                max_model_calls=40, max_turns=30, project=self.config.project
            ),
        )
        self.client.request.return_value = reply(
            ObservedStartedJobDto(id="job-1", conversation="conv-1")
        )
        self.assertEqual(
            await self.port.run("privacy_assistant", "conv-1", "Hello"), "job-1"
        )
        self.assertEqual(
            self.sent(),
            AgentRunRequest(
                agent="privacy_assistant", conversation="conv-1", task="Hello"
            ),
        )
        self.client.request.return_value = reply(
            ObservedStartedJobDto(id="job-2", conversation="conv-other")
        )
        with self.assertRaises(ValueError):
            await self.port.run("privacy_assistant", "conv-1", "Hello")
        self.client.request.return_value = reply(
            ObservedStartedJobDto(id="job-3", conversation="conv-1")
        )
        self.assertEqual(
            await self.port.resume("conv-1", "privacy_assistant", 60, 30), "job-3"
        )
        self.assertEqual(
            self.sent(),
            ConversationResumeRequest(
                conversation="conv-1",
                agent="privacy_assistant",
                max_model_calls=60,
                max_turns=30,
            ),
        )

    async def test_job_endings_map_to_turn_states(self) -> None:
        def job(state: str, ending: str | None) -> ObservedJobDto:
            outcome = (
                None
                if ending is None
                else ObservedOutcomeDto(answered=ending == "ANSWERED", ending=ending)
            )
            return ObservedJobDto(id="job-1", state=state, outcome=outcome)

        cases = [
            ("RUNNING", None, "running"),
            ("DONE", "ANSWERED", "answered"),
            ("DONE", "AWAITING", "answered"),
            ("DONE", "TURN_CAP", "capped"),
            ("DONE", "CALL_BUDGET", "capped"),
            ("DONE", "STUCK", "failed"),
            ("DONE", "CANCELLED", "failed"),
            ("DONE", None, "failed"),
        ]
        for state, ending, expected in cases:
            with self.subTest(ending=ending):
                self.client.request.return_value = reply(job(state, ending))
                self.assertEqual(await self.port.job_state("job-1"), expected)
        self.assertEqual(self.sent(), JobStatusRequest(job="job-1"))
        self.client.request.return_value = reply(job("PAUSED", None))
        with self.assertRaises(ValueError):
            await self.port.job_state("job-1")
        self.client.request.return_value = reply(
            ObservedJobDto(id="job-other", state="RUNNING")
        )
        with self.assertRaises(ValueError):
            await self.port.job_state("job-1")

    async def test_entries_keep_tool_names_recipients_and_replies(self) -> None:
        incoming = "Incoming message (data).\n" + json.dumps(
            {
                "id": "msg-9",
                "from": "inst-2",
                "sender": "privacy_analyst",
                "body": "The doorbell talks to its vendor.",
                "reply_to": "msg-1",
            }
        )
        page = EntryPageViewDto(
            entries=(
                entry(
                    kind="utterance",
                    ordinal=1,
                    excerpt="Hello",
                    speaker="person",
                    speaker_name="operator",
                    job="job-1",
                    source=SourceViewDto(kind="person", reference="operator"),
                ),
                entry(
                    ordinal=2,
                    excerpt="",
                    job="job-1",
                    tool_calls=(
                        asked(
                            "c1",
                            "send_message",
                            '{"to":"privacy_analyst","body":"Interpret"}',
                        ),
                        asked("c2", "send_message", '{"to": "privacy_rev'),
                        asked(
                            "c3",
                            "orchestrate_investigate_network",
                            '{"request":"Doorbell"}',
                        ),
                        asked("c4", "send_message", '{"reply_to":"m","body":"ok"}'),
                        asked("c5", "send_message", '{"route":"review","body":"x"}'),
                    ),
                ),
                entry(
                    kind="tool_result",
                    ordinal=3,
                    excerpt='{"message":"msg-1","queued":true}',
                    tool_call_id="c1",
                    job="job-1",
                ),
                entry(
                    kind="tool_result",
                    ordinal=4,
                    excerpt='{"id":"orc_1","stages":[]}',
                    tool_call_id="c3",
                ),
                entry(
                    kind="tool_result",
                    ordinal=5,
                    excerpt="send_message needs to, route or reply_to.",
                    tool_call_id="c2",
                ),
                entry(
                    kind="utterance",
                    ordinal=6,
                    excerpt=incoming,
                    speaker="harness",
                    speaker_name="message msg-9",
                    source=SourceViewDto(kind="message", reference="msg-9"),
                    recorded_at=None,
                ),
                entry(
                    kind="utterance",
                    ordinal=7,
                    excerpt="Incoming message, not JSON",
                    speaker="harness",
                    source=SourceViewDto(kind="message", reference="msg-10"),
                ),
                entry(
                    kind="utterance",
                    ordinal=8,
                    excerpt="Finished",
                    speaker="harness",
                    source=SourceViewDto(kind="orchestration", reference="orc_1"),
                ),
                entry(kind="summary", ordinal=9, excerpt="Folded"),
            ),
            limit=50,
            more=None,
            offset=0,
            oldest=1,
            through=9,
            total=9,
        )
        self.client.request.return_value = reply(page)
        entries = await self.port.entries("conv-1", 0, 50)
        self.assertEqual(
            self.sent(),
            ConversationChatRequest(conversation="conv-1", offset=0, limit=50),
        )
        self.assertEqual(
            [(e.index, e.author, e.kind) for e in entries],
            [
                (1, "user", "question"),
                (2, "agent", "answer"),
                (3, "tool", "tool_result"),
                (4, "tool", "tool_result"),
                (5, "tool", "tool_result"),
                (6, "system", "message"),
                (7, "system", "message"),
                (8, "system", "orchestration"),
                (9, "system", "summary"),
            ],
        )
        self.assertEqual(
            (entries[0].at, entries[0].job), ("2026-11-13T01:00:05.123Z", "job-1")
        )
        self.assertEqual(
            entries[1].calls,
            (
                AskCall("c1", "send_message", "privacy_analyst", "Interpret"),
                AskCall("c2", "send_message", None, None),
                AskCall(
                    "c3",
                    "orchestrate_investigate_network",
                    "investigate_network",
                    "Doorbell",
                ),
                AskCall("c4", "send_message", None, "ok", reply=True),
                AskCall("c5", "send_message", "review", "x"),
            ),
        )
        self.assertEqual(
            [(e.answers, e.reference) for e in entries[2:5]],
            [("c1", "msg-1"), ("c3", "orc_1"), ("c2", None)],
        )
        self.assertEqual(
            (entries[5].text, entries[5].sender, entries[5].reference, entries[5].at),
            ("The doorbell talks to its vendor.", "privacy_analyst", "msg-1", ""),
        )
        self.assertEqual(
            (entries[6].text, entries[6].sender, entries[6].reference),
            ("Incoming message, not JSON", None, None),
        )
        self.assertEqual(entries[7].reference, "orc_1")


if __name__ == "__main__":
    unittest.main()
