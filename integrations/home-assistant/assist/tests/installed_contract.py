"""Standalone consumer of one extracted archive; no checkout/staging imports.

Only HA's unrelated authentication lookup and platform forwarding are isolated.
The entry composition, local journal, entities, helper/service, public SDK codec
and segmented transport are production artifact code. A loopback peer supplies
protocol receipts, never a real model, Plowshare server or physical device.
"""

from __future__ import annotations

import asyncio
import json
import sys
import unittest
from pathlib import Path
from types import MappingProxyType, SimpleNamespace
from unittest.mock import AsyncMock, patch
from uuid import uuid4

INSTALLATION = Path(sys.argv[1]).resolve()
CONFIGURATION = Path(sys.argv[2]).resolve()
sys.path.insert(0, str(INSTALLATION))

# Imports deliberately occur only after admitting the extracted installation.
from custom_components.plowshare._vendor.plowshare._packets import (  # noqa: E402
    PROTOCOL,
    Packets,
)
from homeassistant.components import ai_task  # noqa: E402
from homeassistant.components.ai_task.const import DATA_COMPONENT  # noqa: E402
from homeassistant.components.conversation import ConversationInput  # noqa: E402
from homeassistant.components.conversation.chat_log import (  # noqa: E402
    DATA_CHAT_LOGS,
    AssistantContent,
)
from homeassistant.config_entries import ConfigEntries, ConfigEntry  # noqa: E402
from homeassistant.core import Context, HomeAssistant  # noqa: E402
from homeassistant.exceptions import HomeAssistantError  # noqa: E402
from homeassistant.helpers import device_registry as dr  # noqa: E402
from homeassistant.helpers import entity_registry as er  # noqa: E402
from homeassistant.helpers.intent import IntentResponseType  # noqa: E402
from websockets.asyncio.server import serve  # noqa: E402
from websockets.exceptions import ConnectionClosed  # noqa: E402

from custom_components import plowshare  # noqa: E402
from custom_components.plowshare.ai_task import PlowshareAITaskEntity  # noqa: E402
from custom_components.plowshare.conversation import (  # noqa: E402
    PlowshareConversationEntity,
)


class ReceiptPeer:
    """A public wire fixture with authoritative per-alias/job receipts."""

    def __init__(self):
        self.frames = []
        self.aliases = {}
        self.jobs = {}
        self.headers = []
        self.errors = []
        self.journal = None
        self.drop_next_run = False
        self.task_text = "  Task note — α.\n"
        self.intent_checks = 0

    def intent(self, alias):
        # On-wire mutation must already have a physically committed intent.
        document = json.loads(self.journal.read_bytes())
        records = [
            row for row in document["data"].values() if row["correlation"] == alias
        ]
        assert len(records) == 1 and records[0]["phase"] == "sending"
        assert records[0]["mapping"] == "external"
        self.intent_checks += 1

    def reply(self, frame):
        operation = frame["type"]
        payload = frame["payload"]
        assert frame["protocol_version"] == "plowshare-v1"
        if operation == "transport.dispatch":
            return "OK", {
                "mode": "concurrent-v1",
                "maxPending": 64,
                "maxIdBytes": 128,
            }
        if operation == "conversation.open.ex":
            alias = (payload["source"], payload["externalConversation"])
            self.intent(alias[1])
            assert alias not in self.aliases
            conversation = "cnv_installed_" + uuid4().hex
            self.aliases[alias] = conversation
            return "OK", {
                "id": conversation,
                "project": "house",
                "title": None,
                "maxModelCalls": 40,
                "maxTurns": 40,
                "modelCallsSpent": 0,
                "noBudget": False,
                "noTurnCap": False,
            }
        if operation == "conversation.trajectory":
            assert payload["conversation"] in self.aliases.values()
            return "OK", {
                "entries": [],
                "total": 0,
                "offset": 0,
                "limit": payload["limit"],
                "through": 0,
                "oldest": None,
                "more": None,
            }
        if operation == "agent.run.ex":
            assert "conversation" not in payload
            alias = (payload["source"], payload["externalConversation"])
            self.intent(alias[1])
            conversation = self.aliases[alias]
            envelope = json.loads(payload["task"].split("\n", 1)[1])
            expected = (
                "house_reader" if envelope["kind"] == "conversation" else "task_writer"
            )
            assert payload["agent"] == expected and payload["project"] == "house"
            assert envelope["context"]["effective_role"] == "task/user"
            assert envelope["context"]["source_role"] == "system"
            assert (
                not {"user_id", "device_id", "satellite_id"}
                & envelope["context"].keys()
            )
            job = str(uuid4())
            text = (
                "The office is 22 degrees. Anything else?"
                if expected == "house_reader"
                else self.task_text
            )
            self.jobs[job] = (conversation, text)
            return "ACCEPTED", {
                "id": job,
                "conversation": conversation,
                "agent": expected,
            }
        if operation == "job.status":
            conversation, text = self.jobs[payload["job"]]
            return "OK", {
                "id": payload["job"],
                "conversation": conversation,
                "state": "DONE",
                "outcome": {"answered": True, "ending": "ANSWERED", "text": text},
            }
        raise AssertionError("Unexpected wire operation: " + operation)

    async def handle(self, socket):
        self.headers.append(
            (socket.request.headers.get("Authorization"), socket.subprotocol)
        )
        packets = Packets(lambda: None)
        sending = []
        try:
            async for wire in socket:
                text, credit = packets.accept(wire)
                if credit is not None:
                    await socket.send(credit)
                if text is None:
                    continue
                frame = json.loads(text)
                self.frames.append(frame)
                code, payload = self.reply(frame)
                if frame["type"] == "agent.run.ex" and self.drop_next_run:
                    self.drop_next_run = False
                    await socket.close()
                    continue
                response = json.dumps(
                    {
                        "id": frame["id"],
                        "type": frame["type"],
                        "protocol_version": "plowshare-v1",
                        "payload": {"code": code, "payload": payload},
                    }
                ).encode()
                sending.append(asyncio.create_task(packets.send(response, socket.send)))
        except ConnectionClosed:
            pass
        except Exception as error:
            self.errors.append(error)
        finally:
            packets.close()
            results = await asyncio.gather(*sending, return_exceptions=True)
            self.errors.extend(
                r
                for r in results
                if isinstance(r, Exception) and not isinstance(r, ConnectionClosed)
            )

    def operations(self, name):
        return [row["payload"] for row in self.frames if row["type"] == name]


class InstalledContracts(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.peer = ReceiptPeer()
        self.server = await serve(
            self.peer.handle, "127.0.0.1", 0, subprotocols=[PROTOCOL], close_timeout=1
        )
        port = self.server.sockets[0].getsockname()[1]
        self.hass = HomeAssistant(str(CONFIGURATION / self._testMethodName))
        self.hass.config.components.add("conversation")
        self.hass.config.media_dirs = {}
        self.hass.config_entries = ConfigEntries(self.hass, {})
        self.hass.auth = SimpleNamespace(async_get_user=AsyncMock(return_value=None))
        dr.async_setup(self.hass)
        await dr.async_load(self.hass, load_empty=True)
        await er.async_load(self.hass, load_empty=True)
        await ai_task.async_setup(self.hass, {})
        self.component = self.hass.data[DATA_COMPONENT]
        self.component.register_shutdown()
        self.entry = ConfigEntry(
            domain="plowshare",
            title="Installed house",
            data={
                "url": f"http://127.0.0.1:{port}",
                "api_key": "installed-fixture-token",
                "project": "house",
                "read_only_agent": "house_reader",
                "task_agent": "task_writer",
                "conversation_enabled": True,
                "ai_task_enabled": True,
                "request_timeout": 2,
                "response_timeout": 3,
                "prompt": "  Installed household prompt — λ.\n",
            },
            version=1,
            minor_version=1,
            source="user",
            unique_id="installed-fixture",
            options={},
            discovery_keys=MappingProxyType({}),
            subentries_data=None,
        )
        # Platform-loader orchestration is isolated; composition still creates
        # the unchanged SDK gateways and their real shared filesystem journal.
        self.enterContext(
            patch.object(
                self.hass.config_entries, "async_forward_entry_setups", new=AsyncMock()
            )
        )
        self.enterContext(
            patch.object(
                self.hass.config_entries,
                "async_unload_platforms",
                new=AsyncMock(return_value=True),
            )
        )
        self.peer.journal = Path(
            self.hass.config.path(".storage", "plowshare." + self.entry.entry_id)
        )
        self.assertTrue(await plowshare.async_setup_entry(self.hass, self.entry))
        self.assertFalse(self.peer.journal.exists())
        self.conversation = self.conversation_entity()
        self.task = PlowshareAITaskEntity(self.entry)
        await self.component.async_add_entities([self.task])

    def conversation_entity(self):
        entity = PlowshareConversationEntity(self.entry)
        entity.hass = self.hass
        return entity

    async def asyncTearDown(self):
        try:
            self.assertTrue(
                await asyncio.wait_for(
                    plowshare.async_unload_entry(self.hass, self.entry), 5
                )
            )
            await asyncio.wait_for(self.hass.async_stop(), 5)
        finally:
            self.server.close()
            await asyncio.wait_for(self.server.wait_closed(), 5)
        self.assertEqual(self.peer.errors, [])
        self.assertTrue(
            all(
                row == ("Bearer installed-fixture-token", PROTOCOL)
                for row in self.peer.headers
            )
        )
        for name, module in tuple(sys.modules.items()):
            if name.startswith("custom_components.plowshare") and getattr(
                module, "__file__", None
            ):
                self.assertTrue(
                    Path(module.__file__).resolve().is_relative_to(INSTALLATION), name
                )
        self.assertNotIn("support", sys.modules)

    def request(
        self,
        conversation_id=None,
        *,
        user="alice",
        text="  How warm is it?\n",
        extra=None,
    ):
        return ConversationInput(
            text=text,
            context=Context(user_id=user),
            conversation_id=conversation_id,
            device_id="installed-device",
            satellite_id=None,
            language="en",
            agent_id="conversation.installed_house",
            extra_system_prompt=extra,
        )

    async def test_assist_continuation_exact_context_and_foreign_source_fence(self):
        extra = "  Supplied context: café\n second line  "
        first = await self.conversation.async_process(self.request(extra=extra))
        self.assertEqual(
            first.response.speech["plain"]["speech"],
            "The office is 22 degrees. Anything else?",
        )
        self.assertTrue(first.continue_conversation)
        log = self.hass.data[DATA_CHAT_LOGS][first.conversation_id]
        before = len(self.peer.frames)
        foreign = await self.conversation.async_process(
            self.request(first.conversation_id, user="bob")
        )
        self.assertEqual(foreign.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(len(self.peer.frames), before)
        second = await self.conversation.async_process(
            self.request(first.conversation_id, text="And outside?")
        )
        self.assertEqual(first.conversation_id, second.conversation_id)
        runs = self.peer.operations("agent.run.ex")
        opens = self.peer.operations("conversation.open.ex")
        self.assertEqual(len(opens), 1)
        self.assertEqual(len(runs), 2)
        self.assertEqual(
            {r["externalConversation"] for r in runs},
            {opens[0]["externalConversation"]},
        )
        self.assertEqual({r["source"] for r in runs}, {opens[0]["source"]})
        envelopes = [json.loads(r["task"].split("\n", 1)[1]) for r in runs]
        self.assertEqual(
            [r["input"] for r in envelopes], ["  How warm is it?\n", "And outside?"]
        )
        self.assertTrue(
            all(
                r["context"]["system_text"] == log.content[0].content for r in envelopes
            )
        )
        self.assertEqual(log.content[0].content.count(extra), 1)
        self.assertEqual(self.peer.intent_checks, 3)
        self.assertNotIn("installed-fixture-token", self.peer.journal.read_text())
        self.assertNotIn("How warm", self.peer.journal.read_text())

    async def task_action(self, **changes):
        return await self.hass.services.async_call(
            "ai_task",
            "generate_data",
            {
                "task_name": "installed task",
                "entity_id": self.task.entity_id,
                "instructions": "  Preserve task input — α.\n",
                **changes,
            },
            blocking=True,
            return_response=True,
        )

    async def test_ai_text_structured_routes_and_invalid_completed_result(self):
        chat = await self.conversation.async_process(self.request())
        text = await self.task_action()
        self.assertEqual(text["data"], self.peer.task_text)
        self.peer.task_text = '{"note":"Installed α note","enabled":false}'
        structure = {
            "note": {"required": True, "selector": {"text": {}}},
            "enabled": {"required": True, "selector": {"boolean": {}}},
        }
        structured = await self.task_action(structure=structure)
        self.assertEqual(
            structured["data"], {"note": "Installed α note", "enabled": False}
        )
        self.peer.task_text = '{"note":"Invalid completed note","enabled":"false"}'
        with self.assertRaisesRegex(HomeAssistantError, "completed work remains"):
            await self.task_action(structure=structure)
        runs = self.peer.operations("agent.run.ex")
        self.assertEqual(len(runs), 4)
        self.assertEqual(
            [r["agent"] for r in runs],
            ["house_reader", "task_writer", "task_writer", "task_writer"],
        )
        self.assertEqual(
            len({(r["source"], r["externalConversation"]) for r in runs}), 4
        )
        self.assertNotEqual(runs[0]["source"], runs[1]["source"])
        self.assertEqual(len(self.peer.operations("conversation.open.ex")), 4)
        envelope = json.loads(runs[1]["task"].split("\n", 1)[1])
        self.assertEqual(envelope["input"], "  Preserve task input — α.\n")
        self.assertEqual(
            envelope["context"]["system_text"],
            self.hass.data[DATA_CHAT_LOGS][text["conversation_id"]].content[0].content,
        )
        self.assertNotEqual(chat.conversation_id, text["conversation_id"])
        self.assertEqual(self.peer.intent_checks, 8)
        for log in self.hass.data[DATA_CHAT_LOGS].values():
            self.assertFalse(
                any(
                    isinstance(row, AssistantContent)
                    and row.content == self.peer.task_text
                    for row in log.content
                )
            )
        rows = json.loads(self.peer.journal.read_bytes())["data"].values()
        self.assertEqual(sum(row["phase"] == "complete" for row in rows), 3)

    async def test_lost_run_ack_entry_reload_retains_receipt_without_replay(self):
        self.peer.drop_next_run = True
        first = await self.conversation.async_process(self.request())
        self.assertEqual(first.response.response_type, IntentResponseType.ERROR)
        receipt = self.peer.journal.read_bytes()
        record = next(iter(json.loads(receipt)["data"].values()))
        self.assertEqual(record["phase"], "uncertain")
        self.assertTrue(record["conversation"].startswith("cnv_installed_"))
        self.assertIsNone(record["job"])
        self.assertEqual(len(self.peer.jobs), 1)  # Peer accepted despite lost receipt.
        self.assertTrue(await plowshare.async_unload_entry(self.hass, self.entry))
        self.assertTrue(await plowshare.async_setup_entry(self.hass, self.entry))
        self.conversation = self.conversation_entity()
        before = len(self.peer.frames)
        again = await self.conversation.async_process(
            self.request(first.conversation_id, text="Try again")
        )
        self.assertEqual(again.response.response_type, IntentResponseType.ERROR)
        self.assertEqual(again.conversation_id, first.conversation_id)
        self.assertEqual(len(self.peer.frames), before)
        self.assertEqual(self.peer.journal.read_bytes(), receipt)
        self.assertEqual(len(self.peer.operations("conversation.open.ex")), 1)
        self.assertEqual(len(self.peer.operations("agent.run.ex")), 1)
        self.assertEqual(self.peer.intent_checks, 2)


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]], verbosity=2)
