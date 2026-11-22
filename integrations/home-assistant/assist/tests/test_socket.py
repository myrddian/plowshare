"""Actual public SDK over segmented WebSockets, without a server/database/model."""

import asyncio
import json
import unittest
from dataclasses import replace
from unittest.mock import AsyncMock

import support  # noqa: F401
from custom_components.plowshare._vendor.plowshare._packets import PROTOCOL, Packets
from websockets.asyncio.server import serve

from custom_components.plowshare.agent import UNCERTAIN, SdkConversationAgent
from custom_components.plowshare.models import Settings, session_key

CONVERSATION = "cnv_fixture_home_conversation"
JOB = "22222222-2222-4222-8222-222222222222"


class Peer:
    def __init__(self):
        self.frames = []
        self.lost_ack = False
        self.refusal_code = None
        self.authorization = None

    async def handle(self, socket):
        self.authorization = socket.request.headers.get("Authorization")
        packets = Packets(lambda: None)
        sending = []
        try:
            async for wire in socket:
                text, credit = packets.accept(wire)
                if credit is not None:
                    await socket.send(credit)
                if text is None:
                    continue
                row = json.loads(text)
                self.frames.append(row)
                operation = row["type"]
                code = "OK"
                if operation == "transport.dispatch":
                    payload = {
                        "mode": "concurrent-v1",
                        "maxPending": 64,
                        "maxIdBytes": 128,
                    }
                elif operation == "conversation.open.ex":
                    payload = {
                        "id": CONVERSATION,
                        "project": "house",
                        "title": None,
                        "maxModelCalls": 40,
                        "maxTurns": 40,
                        "modelCallsSpent": 0,
                        "noBudget": False,
                        "noTurnCap": False,
                    }
                elif operation == "conversation.trajectory":
                    payload = {
                        "entries": [],
                        "total": 0,
                        "offset": 0,
                        "limit": 100,
                        "through": 0,
                        "oldest": None,
                        "more": None,
                    }
                elif operation == "agent.run.ex":
                    code = self.refusal_code or "ACCEPTED"
                    if self.lost_ack:
                        await socket.close()
                        continue
                    payload = {
                        "id": JOB,
                        "conversation": CONVERSATION,
                        "agent": "house_coordinator",
                    }
                    if self.refusal_code:
                        payload = None
                elif operation == "job.status":
                    payload = {
                        "id": JOB,
                        "conversation": CONVERSATION,
                        "state": "DONE",
                        "outcome": {
                            "answered": True,
                            "ending": "ANSWERED",
                            "text": "The office is 22 degrees.",
                        },
                    }
                else:
                    raise AssertionError(operation)
                response = json.dumps(
                    {
                        "id": row["id"],
                        "type": operation,
                        "protocol_version": "plowshare-v1",
                        "payload": {"code": code, "payload": payload},
                    }
                ).encode()
                sending.append(asyncio.create_task(packets.send(response, socket.send)))
        finally:
            packets.close()
            await asyncio.gather(*sending, return_exceptions=True)


class SocketTests(unittest.IsolatedAsyncioTestCase):
    async def run_case(self, lost_ack, refusal_code=None):
        peer = Peer()
        peer.lost_ack = lost_ack
        peer.refusal_code = refusal_code
        async with serve(
            peer.handle, "127.0.0.1", 0, subprotocols=[PROTOCOL]
        ) as server:
            port = server.sockets[0].getsockname()[1]
            settings = Settings(
                f"http://127.0.0.1:{port}",
                "fixture-token",
                "house",
                "house_coordinator",
            )
            settings = replace(settings, response_timeout=2)
            agent = SdkConversationAgent(
                settings, {}, AsyncMock(), profile_id="fixture-entry:conversation"
            )
            key = session_key("chat", "alice", None, None, False)
            first = await agent.ask(key, "How warm is the office?")
            if lost_ack or refusal_code:
                self.assertEqual(first.text, UNCERTAIN)
                self.assertEqual((await agent.ask(key, "Try again")).text, UNCERTAIN)
            else:
                self.assertFalse(first.error, first.text)
                self.assertEqual(first.text, "The office is 22 degrees.")
            self.assertEqual(peer.authorization, "Bearer fixture-token")
            runs = [row for row in peer.frames if row["type"] == "agent.run.ex"]
            self.assertEqual(len(runs), 1)
            self.assertEqual(runs[0]["payload"]["project"], "house")
            opens = [
                row for row in peer.frames if row["type"] == "conversation.open.ex"
            ]
            self.assertEqual(len(opens), 1)
            self.assertEqual(
                runs[0]["payload"]["externalConversation"],
                opens[0]["payload"]["externalConversation"],
            )
            self.assertEqual(
                runs[0]["payload"]["source"], opens[0]["payload"]["source"]
            )
            self.assertNotIn("conversation", runs[0]["payload"])
            envelope = json.loads(runs[0]["payload"]["task"].split("\n", 1)[1])
            self.assertEqual(envelope["input"], "How warm is the office?")

    async def test_actual_sdk_segmented_round_trip(self):
        await self.run_case(False)

    async def test_actual_sdk_disconnect_after_submission_never_replays(self):
        await self.run_case(True)

    async def test_actual_sdk_ambiguous_ex_refusal_never_replays(self):
        await self.run_case(False, "ARCHIVE_UNAVAILABLE")
