"""Shared SDK gateway contract, isolation and uncertain physical ownership."""

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
    AgentListRequest,
    AgentRunRequestEx,
    AgentViewDto,
    ConversationOpenRequestEx,
    ConversationStartedJobDto,
    JobStatusRequest,
    ObservedJobDto,
)
from test_agent import KEY, SETTINGS, FakeClient, entry, job, publication

from custom_components.plowshare.agent import SdkConversationAgent
from custom_components.plowshare.models import decode_sessions, encode_sessions
from custom_components.plowshare.requests import (
    MAX_CONTEXT_BYTES,
    ConversationRequest,
    GatewayResult,
    HaRequestContext,
    TaskRequest,
    digest,
    render_task,
)

PROFILE = "fixture-installation/config-entry:conversation"
OTHER_KEY = digest(("another-invocation",))


def conversation(key=KEY, context=None):
    return ConversationRequest(key, "What is happening?", context or HaRequestContext())


def task(key=KEY):
    return TaskRequest(key, "Summarize the supplied data.")


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    def make(
        self, settings=SETTINGS, sessions=None, profile=PROFILE, client=None, save=None
    ):
        client = client or FakeClient()
        sessions = sessions if sessions is not None else {}
        snapshots = []

        async def committed():
            snapshots.append(encode_sessions(sessions))

        agent = SdkConversationAgent(
            settings,
            sessions,
            save or committed,
            AsyncMock(return_value=client),
            profile_id=profile,
        )
        return agent, client, snapshots

    async def test_all_enabled_agents_validated_from_one_project_snapshot(self):
        agent, client, _ = self.make()

        async def available(request, **kwargs):
            client.requests.append(request)
            return Reply(
                "OK",
                None,
                tuple(
                    AgentViewDto(
                        name=name,
                        bot=False,
                        calls=(),
                        description="",
                        model=None,
                        orchestrations=(),
                        preferred=False,
                        scopes=(),
                        served=True,
                        tools=(),
                        withheld=(),
                    )
                    for name in ("house_coordinator", "reader", "task")
                ),
            )

        client.request = available
        await agent.validate(("house_coordinator", "reader", "task"))
        self.assertEqual(len(client.requests), 1)
        self.assertIsInstance(client.requests[0], AgentListRequest)
        self.assertEqual(client.requests[0].project, SETTINGS.project)
        client.requests.clear()
        with self.assertRaises(ValueError):
            await agent.validate(("reader", "missing"))
        self.assertEqual(len(client.requests), 1)
        self.assertFalse(any(isinstance(r, AgentRunRequestEx) for r in client.requests))
        for names in ((" ",), ["reader"], ("reader",) * 17):
            with self.assertRaises(ValueError):
                await agent.validate(names)

    async def test_prepared_text_preserved_in_existing_task_field(self):
        agent, client, snapshots = self.make()
        system = '  Résumé "home"\r\n\tα 🏠  '
        context = HaRequestContext(
            system, "en", "private-user", "private-device", "private-satellite"
        )
        result = await agent.converse(conversation(context=context))
        self.assertEqual(result.status, "completed")
        runs = [r for r in client.requests if isinstance(r, AgentRunRequestEx)]
        opens = [r for r in client.requests if isinstance(r, ConversationOpenRequestEx)]
        self.assertEqual(len(runs), 1)
        self.assertEqual(len(opens), 1)
        self.assertEqual(
            (runs[0].source, runs[0].external_conversation),
            (opens[0].source, opens[0].external_conversation),
        )
        self.assertRegex(runs[0].source, r"^ha\.conversation\.[0-9a-f]{64}$")
        envelope = json.loads(runs[0].task.split("\n", 1)[1])
        self.assertEqual(envelope["context"]["system_text"], system)
        self.assertEqual(envelope["context"]["effective_role"], "task/user")
        self.assertEqual(envelope["input"], "What is happening?")
        self.assertNotIn("private-user", runs[0].task)
        self.assertNotIn("private-device", runs[0].task)
        self.assertNotIn("private-satellite", runs[0].task)
        self.assertNotIn(system, json.dumps(snapshots))
        self.assertNotIn("fixture-token", json.dumps(snapshots))
        self.assertFalse(
            any(
                r.operation
                in {"conversation.open", "agent.run", "conversation.alias.resolve"}
                for r in client.requests
            )
        )

    async def test_accidentally_reused_key_cannot_join_other_identity(self):
        agent, client, _ = self.make()
        for context in (
            HaRequestContext("alice instructions", user_id="alice", device_id="one"),
            HaRequestContext("bob instructions", user_id="bob", device_id="one"),
            HaRequestContext("other device", user_id="alice", device_id="two"),
        ):
            self.assertEqual(
                (await agent.converse(conversation(context=context))).status,
                "completed",
            )
        opens = [r for r in client.requests if isinstance(r, ConversationOpenRequestEx)]
        self.assertEqual(len({r.external_conversation for r in opens}), 3)
        self.assertEqual(len(agent.sessions), 3)

    async def test_conversation_and_task_have_separate_core_sources(self):
        agent, client, _ = self.make()
        await agent.converse(conversation())
        await agent.generate(task())
        opens = [r for r in client.requests if isinstance(r, ConversationOpenRequestEx)]
        self.assertEqual(len(opens), 2)
        self.assertNotEqual(opens[0].source, opens[1].source)
        self.assertNotEqual(
            opens[0].external_conversation, opens[1].external_conversation
        )

    async def test_task_result_and_retained_terminal_receipt_are_distinct_from_pending_prose(
        self,
    ):
        agent, client, _ = self.make()
        first = await agent.generate(task())
        self.assertEqual(first.status, "completed")
        self.assertIsNone(first.message)
        row = next(iter(agent.sessions.values()))
        self.assertEqual((row.phase, row.job), ("complete", "job-1"))
        saved = decode_sessions(encode_sessions(agent.sessions))
        restored, _, _ = self.make(sessions=saved, client=client)
        self.assertEqual((await restored.generate(task())).text, first.text)
        self.assertEqual(
            sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
        )

    async def test_pending_task_restart_reconciles_exact_original_job(self):
        agent, client, _ = self.make(settings=replace(SETTINGS, response_timeout=0.02))
        client.status = ObservedJobDto(
            id="job-1", conversation="conv-1", state="RUNNING"
        )
        result = await agent.generate(task())
        self.assertEqual(result.status, "pending")
        self.assertIsNone(result.text)
        restored, _, _ = self.make(
            sessions=decode_sessions(encode_sessions(agent.sessions)), client=client
        )
        client.status = job(text="Completed original task")
        result = await restored.generate(task())
        self.assertEqual(
            (result.status, result.text), ("completed", "Completed original task")
        )
        self.assertEqual(
            sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
        )

    async def test_identical_instructions_in_distinct_invocations_can_run_concurrently(
        self,
    ):
        first, second = FakeClient(), FakeClient()
        first.hold = second.hold = asyncio.Event()
        agent, _, _ = self.make()
        agent.connect = AsyncMock(side_effect=[first, second])
        one = asyncio.create_task(agent.generate(task()))
        two = asyncio.create_task(agent.generate(task(OTHER_KEY)))
        await asyncio.wait_for(
            asyncio.gather(first.submitted.wait(), second.submitted.wait()), 1
        )
        first.hold.set()
        results = await asyncio.gather(one, two)
        self.assertEqual([r.status for r in results], ["completed", "completed"])
        run_one = next(r for r in first.requests if isinstance(r, AgentRunRequestEx))
        run_two = next(r for r in second.requests if isinstance(r, AgentRunRequestEx))
        self.assertNotEqual(
            run_one.external_conversation, run_two.external_conversation
        )

    async def test_fifth_physical_request_refused_before_dispatch(self):
        clients = [FakeClient() for _ in range(4)]
        release = asyncio.Event()
        for client in clients:
            client.hold = release
        agent, _, _ = self.make()
        agent.connect = AsyncMock(side_effect=clients)
        calls = [
            asyncio.create_task(agent.generate(task(digest((str(i),)))))
            for i in range(4)
        ]
        await asyncio.wait_for(
            asyncio.gather(*(client.submitted.wait() for client in clients)), 1
        )
        self.assertEqual(
            (await agent.generate(task(digest(("fifth",))))).status, "busy"
        )
        self.assertEqual(agent.connect.await_count, 4)
        release.set()
        self.assertEqual(
            [result.status for result in await asyncio.gather(*calls)],
            ["completed"] * 4,
        )

    async def test_routing_token_and_profile_changes_fence_old_work_before_connect(
        self,
    ):
        agent, client, _ = self.make(settings=replace(SETTINGS, response_timeout=0.02))
        client.status = ObservedJobDto(
            id="job-1", conversation="conv-1", state="RUNNING"
        )
        await agent.converse(conversation())
        saved = encode_sessions(agent.sessions)
        for settings, profile in (
            (replace(SETTINGS, origin="https://other.example.invalid"), PROFILE),
            (replace(SETTINGS, token="rotated-token"), PROFILE),
            (replace(SETTINGS, project="other"), PROFILE),
            (replace(SETTINGS, agent="other"), PROFILE),
            (SETTINGS, "different-installation/profile"),
        ):
            with self.subTest(profile=profile, project=settings.project):
                changed, _, _ = self.make(settings, decode_sessions(saved), profile)
                result = await changed.converse(conversation())
                self.assertEqual(result.status, "uncertain")
                self.assertIsNone(result.text)
                changed.connect.assert_not_awaited()
                self.assertEqual(encode_sessions(changed.sessions), saved)

    async def test_unbound_legacy_journals_are_never_bypassed(self):
        for phase in ("idle", "sending", "uncertain", "running", "waiting"):
            with self.subTest(phase=phase):
                value = {
                    KEY: {
                        "conversation": "old-conversation",
                        "job": "old-job" if phase in {"running", "waiting"} else None,
                        "phase": phase,
                        "cursor": 0,
                    }
                }
                sessions = decode_sessions(value)
                agent, _, _ = self.make(sessions=sessions)
                before = encode_sessions(sessions)
                self.assertEqual(
                    (await agent.converse(conversation())).status, "uncertain"
                )
                self.assertTrue((await agent.ask(KEY, "Retry")).error)
                agent.connect.assert_not_awaited()
                self.assertEqual(encode_sessions(sessions), before)

    async def test_missing_installation_identity_never_uses_universal_namespace(self):
        agent, client, _ = self.make(profile=None)
        self.assertEqual((await agent.generate(task())).status, "refused")
        agent.connect.assert_not_awaited()
        self.assertEqual(client.requests, [])

    async def test_failed_durable_intent_prevents_alias_or_run_mutation(self):
        agent, client, _ = self.make(
            save=AsyncMock(side_effect=OSError("private failure"))
        )
        result = await agent.generate(task())
        self.assertEqual(result.status, "uncertain")
        self.assertFalse(
            any(
                isinstance(r, (ConversationOpenRequestEx, AgentRunRequestEx))
                for r in client.requests
            )
        )
        self.assertNotIn("private failure", result.message)
        self.assertEqual(next(iter(agent.sessions.values())).phase, "uncertain")

    async def test_ambiguous_ex_refusal_never_replays_after_restart(self):
        for code in ("ARCHIVE_UNAVAILABLE", "FORBIDDEN", "INTERNAL", "UNAVAILABLE"):
            with self.subTest(code=code):
                agent, client, _ = self.make()
                client.error = Refusal(code, "private provider details")
                first = await agent.generate(task())
                self.assertEqual(first.status, "uncertain")
                restored, _, _ = self.make(
                    sessions=decode_sessions(encode_sessions(agent.sessions)),
                    client=client,
                )
                client.error = None
                self.assertEqual((await restored.generate(task())).status, "uncertain")
                self.assertNotIn("private", first.message)
                self.assertEqual(
                    sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
                )

    async def test_foreign_run_receipt_is_retained_uncertain_not_polled_or_replayed(
        self,
    ):
        client = FakeClient()
        request = client.request

        async def foreign(value, **kwargs):
            reply = await request(value, **kwargs)
            if isinstance(value, AgentRunRequestEx):
                return Reply(
                    "ACCEPTED",
                    None,
                    ConversationStartedJobDto(
                        id="foreign-job", conversation="foreign-conversation"
                    ),
                )
            return reply

        client.request = foreign
        agent, _, _ = self.make(client=client)
        self.assertEqual((await agent.generate(task())).status, "uncertain")
        self.assertEqual((await agent.generate(task())).status, "uncertain")
        self.assertFalse(any(isinstance(r, JobStatusRequest) for r in client.requests))
        self.assertEqual(
            sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
        )

    async def test_failed_sdk_close_quarantines_its_physical_owner(self):
        agent, client, _ = self.make()
        client.close = AsyncMock(
            side_effect=TransportError(Delivery.UNKNOWN, "private cleanup")
        )
        self.assertEqual((await agent.generate(task())).status, "completed")
        await asyncio.sleep(0)
        self.assertEqual((await agent.generate(task())).status, "busy")
        with patch("custom_components.plowshare.agent.CLEANUP_TIMEOUT", 0.01):
            self.assertFalse(await agent.async_close())
        self.assertEqual(
            sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
        )

    async def test_cancelled_sdk_close_does_not_free_physical_owner(self):
        agent, client, _ = self.make()

        async def cancelled():
            raise asyncio.CancelledError

        client.close = cancelled
        with self.assertRaises(asyncio.CancelledError):
            await agent.generate(task())
        await asyncio.sleep(0)
        self.assertEqual((await agent.generate(task())).status, "busy")
        with patch("custom_components.plowshare.agent.CLEANUP_TIMEOUT", 0.01):
            self.assertFalse(await agent.async_close())

    async def test_unload_wait_is_finite_and_never_cancels_accepted_work(self):
        agent, client, _ = self.make()
        client.hold = asyncio.Event()
        pending = asyncio.create_task(agent.generate(task()))
        await asyncio.wait_for(client.submitted.wait(), 1)
        with patch("custom_components.plowshare.agent.CLEANUP_TIMEOUT", 0.01):
            self.assertFalse(await agent.async_close())
        self.assertFalse(pending.done())
        client.hold.set()
        self.assertEqual((await pending).status, "completed")
        self.assertTrue(await agent.async_close())
        self.assertEqual((await agent.generate(task(OTHER_KEY))).status, "busy")

    async def test_correlated_task_completion_retains_final_job_without_old_trajectory(
        self,
    ):
        agent, client, _ = self.make()
        client.status = job(text="Evidence requested")
        client.entries = (
            *publication(),
            entry(3, identity="reply-job", text="The light is on."),
        )
        first = await agent.generate(task())
        self.assertEqual(first.text, "The light is on.")
        row = next(iter(agent.sessions.values()))
        self.assertEqual((row.phase, row.job), ("complete", "reply-job"))
        client.entries = ()
        second = await agent.generate(task())
        self.assertEqual(second.text, first.text)
        self.assertEqual(
            sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 1
        )

    async def test_saturated_journal_keeps_every_unsettled_record(self):
        settings = replace(SETTINGS, response_timeout=0.02)
        agent, client, _ = self.make(settings=settings)
        client.status = ObservedJobDto(
            id="job-1", conversation="conv-1", state="RUNNING"
        )
        with patch("custom_components.plowshare.agent.MAX_SESSIONS", 2):
            await agent.generate(task())
            await agent.generate(task(OTHER_KEY))
            saved = encode_sessions(agent.sessions)
            result = await agent.generate(task(digest(("third",))))
            self.assertEqual(result.status, "busy")
            self.assertEqual(encode_sessions(agent.sessions), saved)
            self.assertEqual(
                sum(isinstance(r, AgentRunRequestEx) for r in client.requests), 2
            )


class GatewayBoundaryTests(unittest.TestCase):
    def test_invalid_context_request_and_result_invariants(self):
        for text in (None, "bad\x00text", "x" * (MAX_CONTEXT_BYTES + 1), "\ud800"):
            with (
                self.subTest(text_type=type(text).__name__),
                self.assertRaises(ValueError),
            ):
                HaRequestContext(text)
        for cls in (ConversationRequest, TaskRequest):
            for key, text, context in (
                ("not-partitioned", "hello", HaRequestContext()),
                (KEY, "", HaRequestContext()),
                (KEY, "hello", {}),
            ):
                with self.assertRaises(ValueError):
                    cls(key, text, context)
        for status in ("pending", "uncertain", "refused", "failed"):
            with self.assertRaises(ValueError):
                GatewayResult(status, text="Pending is not generated data")
        with self.assertRaises(ValueError):
            GatewayResult("completed", text="")

    def test_codec_rejects_malformed_routing_metadata_without_type_errors(self):
        base = {
            "conversation": "conversation",
            "job": None,
            "phase": "idle",
            "cursor": 0,
            "mapping": "external",
            "binding": "a" * 64,
            "correlation": "b" * 64,
        }
        for name, value in (
            ("mapping", []),
            ("mapping", {}),
            ("binding", []),
            ("binding", "bad"),
            ("correlation", None),
        ):
            row = {**base, name: value}
            with (
                self.subTest(name=name, value_type=type(value).__name__),
                self.assertRaises(ValueError),
            ):
                decode_sessions({KEY: row})
        preparing = decode_sessions({KEY: {**base, "phase": "preparing"}})
        self.assertEqual(preparing[KEY].phase, "idle")
        self.assertNotIn("system_text", json.dumps(encode_sessions(preparing)))

    def test_serialization_has_no_native_role_or_credential_field(self):
        payload = render_task(
            conversation(context=HaRequestContext("precise source text"))
        )
        decoded = json.loads(payload.split("\n", 1)[1])
        self.assertEqual(decoded["context"]["effective_role"], "task/user")
        self.assertNotIn("messages", decoded)
        self.assertNotIn("agent", decoded)
        self.assertNotIn("project", decoded)
