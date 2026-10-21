from __future__ import annotations

import tempfile
import unittest
from asyncio import CancelledError
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from aiohttp import CookieJar
from aiohttp.test_utils import TestClient, TestServer
from plowshare import Delivery, Refusal, TransportError
from test_agile import FakePort, configuration, uid

from plowshare_agile.board import Board
from plowshare_agile.journal import Journal
from plowshare_agile.model import TicketFields
from plowshare_agile.web import WebOptions, WebUI, watch_upstream

ORIGIN = "http://agile.fixture.invalid"
KEY = "fixture-web-access-key-at-least-32-characters"


class WebTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.config = configuration(Path(self.folder.name))
        self.journal = Journal(self.config, "fixture-plowshare-bearer")
        self.port = FakePort()
        self.ui = WebUI(
            Board(self.port, self.journal),
            "fixture",
            WebOptions("127.0.0.1", 8765, ORIGIN, KEY),
        )
        self.client = TestClient(
            TestServer(self.ui.application()),
            cookie_jar=CookieJar(unsafe=True),
            headers={"Host": "agile.fixture.invalid", "Origin": ORIGIN},
        )
        await self.client.start_server()
        self.csrf = ""

    async def asyncTearDown(self) -> None:
        await self.client.close()
        self.folder.cleanup()

    async def login(self) -> None:
        response = await self.client.post("/session", json={"accessKey": KEY})
        self.assertEqual(response.status, 200)
        row = await response.json()
        self.csrf = row["csrf"]
        self.assertTrue(response.cookies["agile_session"]["httponly"])
        self.assertEqual(response.cookies["agile_session"]["samesite"], "Strict")

    def headers(self, request: str) -> dict[str, str]:
        return {"X-Agile-CSRF": self.csrf, "X-Agile-Request": request}

    async def test_assets_are_safe_and_workspace_requires_a_session(self) -> None:
        for path in (
            "/",
            "/app.mjs",
            "/model.mjs",
            "/reads.mjs",
            "/styles.css",
            "/markdown.js",
        ):
            response = await self.client.get(path)
            self.assertEqual(response.status, 200)
            self.assertIn(
                "frame-ancestors 'none'", response.headers["Content-Security-Policy"]
            )
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertNotIn("fixture-plowshare-bearer", await response.text())
        self.assertEqual((await self.client.get("/api/board")).status, 401)
        self.assertEqual((await self.client.get("/config.example.toml")).status, 404)

    async def test_wrong_login_origin_and_host_are_refused(self) -> None:
        self.assertEqual(
            (await self.client.post("/session", json={"accessKey": "wrong"})).status,
            401,
        )
        self.assertEqual(
            (
                await self.client.post(
                    "/session",
                    headers={"Origin": "https://foreign.invalid"},
                    json={"accessKey": KEY},
                )
            ).status,
            403,
        )
        self.assertEqual(
            (await self.client.get("/", headers={"Host": "foreign.invalid"})).status,
            403,
        )

    async def test_readiness_uses_the_sdk_and_never_exposes_ticket_data(self) -> None:
        with patch.object(self.port, "check", new_callable=AsyncMock) as check:
            response = await self.client.get("/health")
            self.assertEqual(response.status, 204)
            self.assertEqual(await response.text(), "")
            check.assert_awaited_once_with(writing=True)
            check.side_effect = TransportError(Delivery.NOT_SUBMITTED, "private detail")
            response = await self.client.get("/health")
            self.assertEqual(response.status, 503)
            self.assertNotIn("private detail", await response.text())
        self.assertEqual(self.port.calls, [])

    async def test_watchdog_only_repeats_reads_and_exits_on_connection_loss(
        self,
    ) -> None:
        with (
            patch.object(self.port, "check", new_callable=AsyncMock) as check,
            patch("plowshare_agile.web.asyncio.sleep", new_callable=AsyncMock) as sleep,
        ):
            sleep.side_effect = [None, CancelledError()]
            with self.assertRaises(CancelledError):
                await watch_upstream(self.ui.board)
            self.assertEqual(check.await_count, 2)
            check.reset_mock()
            check.side_effect = TransportError(Delivery.NOT_SUBMITTED, "closed")
            with self.assertRaises(TransportError):
                await watch_upstream(self.ui.board)
            check.assert_awaited_once_with(writing=True)
        self.assertEqual(self.port.calls, [])

    async def test_snapshot_and_document_use_existing_application_port(self) -> None:
        await self.login()
        response = await self.client.get("/api/board")
        self.assertEqual(response.status, 200)
        row = await response.json()
        self.assertEqual(row["tickets"][0]["resource"], uid(10))
        self.assertEqual(row["epics"][0]["resource"], uid(20))
        selected = await self.client.get("/api/documents/ticket/" + uid(10))
        self.assertEqual((await selected.json())["revision"], uid(1010))
        self.assertEqual(self.port.calls, [])

    async def test_csrf_and_request_correlation_are_required_before_writes(
        self,
    ) -> None:
        await self.login()
        payload = {
            "requestId": uid(70),
            "expectedRevision": uid(1010),
            "fields": asdict(TicketFields("New")),
            "body": None,
        }
        path = "/api/documents/ticket/" + uid(10)
        self.assertEqual((await self.client.post(path, json=payload)).status, 403)
        self.assertEqual(
            (
                await self.client.post(
                    path, headers=self.headers(uid(71)), json=payload
                )
            ).status,
            400,
        )
        self.assertEqual(self.port.calls, [])

    async def test_unknown_and_duplicate_fields_produce_no_effect(self) -> None:
        await self.login()
        payload = {
            "requestId": uid(70),
            "kind": "ticket",
            "fields": asdict(TicketFields("New")),
            "body": "# Description",
            "owner": "foreign",
        }
        response = await self.client.post(
            "/api/documents", headers=self.headers(uid(70)), json=payload
        )
        self.assertEqual(response.status, 400)
        response = await self.client.post(
            "/api/documents",
            headers={**self.headers(uid(70)), "Content-Type": "application/json"},
            data='{"kind":"ticket","kind":"epic"}',
        )
        self.assertEqual(response.status, 400)
        self.assertEqual(self.port.calls, [])

    async def test_create_and_body_edit_use_shared_intents_and_journal(self) -> None:
        await self.login()
        response = await self.client.post(
            "/api/documents",
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "kind": "ticket",
                "fields": asdict(TicketFields("New")),
                "body": "## Description\nNew",
            },
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.port.calls[0].action, "create")
        self.assertEqual(self.journal.read(uid(70))[1], "confirmed")
        response = await self.client.post(
            "/api/documents/ticket/" + uid(10),
            headers=self.headers(uid(71)),
            json={
                "requestId": uid(71),
                "expectedRevision": uid(1010),
                "fields": asdict(TicketFields("Updated")),
                "body": "## Notes\nEvidence",
            },
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.port.calls[-1].action, "body")
        self.assertEqual(self.port.calls[-1].expected_revision, uid(1010))

    async def test_conflict_preserves_known_refusal_without_replaying(self) -> None:
        await self.login()
        self.port.error = Refusal("CONFLICT", "Private server diagnostic")
        response = await self.client.post(
            "/api/documents/ticket/" + uid(10),
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "expectedRevision": uid(1010),
                "fields": asdict(TicketFields("Updated")),
                "body": None,
            },
        )
        self.assertEqual(response.status, 409)
        row = await response.json()
        self.assertFalse(row["pending"])
        self.assertNotIn("Private server diagnostic", str(row))
        self.assertEqual(len(self.port.calls), 1)

    async def test_internal_error_requires_recovery_without_exposing_diagnostics(
        self,
    ) -> None:
        await self.login()
        self.port.error = Refusal("INTERNAL_ERROR", "Private server diagnostic")
        response = await self.client.post(
            "/api/documents/ticket/" + uid(10),
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "expectedRevision": uid(1010),
                "fields": asdict(TicketFields("Updated")),
                "body": None,
            },
        )
        self.assertEqual(response.status, 503)
        row = await response.json()
        self.assertTrue(row["pending"])
        self.assertNotIn("Private server diagnostic", str(row))
        self.assertEqual(self.journal.read(uid(70))[1], "pending")
        self.assertEqual(len(self.port.calls), 1)

    async def test_stale_display_is_refused_before_journaling_or_submitting(
        self,
    ) -> None:
        await self.login()
        self.port.ticket = replace(self.port.ticket, revision=uid(99))
        response = await self.client.post(
            "/api/documents/ticket/" + uid(10),
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "expectedRevision": uid(1010),
                "fields": asdict(TicketFields("Draft")),
                "body": None,
            },
        )
        self.assertEqual(response.status, 409)
        self.assertFalse((await response.json())["pending"])
        self.assertEqual(self.port.calls, [])
        with self.assertRaises(FileNotFoundError):
            self.journal.read(uid(70))

    async def test_lost_reply_remains_pending_until_explicit_recovery(self) -> None:
        await self.login()
        self.port.error = TransportError(Delivery.UNKNOWN, "fixture")
        response = await self.client.post(
            "/api/documents/ticket/" + uid(10),
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "expectedRevision": uid(1010),
                "fields": asdict(TicketFields("Updated")),
                "body": None,
            },
        )
        self.assertEqual(response.status, 503)
        self.assertTrue((await response.json())["pending"])
        self.assertEqual(len(self.port.calls), 1)
        original = self.port.calls[0]
        self.port.error = None
        response = await self.client.post(
            "/api/requests/" + uid(70) + "/recover",
            headers=self.headers(uid(70)),
            json={"requestId": uid(70)},
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.port.calls, [original, original])

    async def test_epic_and_topic_updates_keep_the_other_memberships(self) -> None:
        await self.login()
        response = await self.client.post(
            "/api/tickets/" + uid(10) + "/epics",
            headers=self.headers(uid(70)),
            json={
                "requestId": uid(70),
                "expectedRevision": uid(1010),
                "expectedTagsVersion": 0,
                "epics": [],
            },
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.port.calls[-1].tags, ("sdk",))
        response = await self.client.post(
            "/api/tickets/" + uid(10) + "/tags",
            headers=self.headers(uid(71)),
            json={
                "requestId": uid(71),
                "expectedRevision": uid(1010),
                "tags": ["websocket"],
                "expectedTagsVersion": 0,
            },
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(self.port.calls[-1].tags, ("epic:" + uid(20), "websocket"))

    async def test_sign_out_invalidates_the_session(self) -> None:
        await self.login()
        response = await self.client.delete(
            "/session", headers={"X-Agile-CSRF": self.csrf}
        )
        self.assertEqual(response.status, 200)
        self.assertEqual((await self.client.get("/api/board")).status, 401)

    def test_web_options_require_explicit_safe_configuration(self) -> None:
        for origin, key in (
            ("http://user:secret@fixture.invalid", KEY),
            ("http://fixture.invalid/path", KEY),
            (ORIGIN, "short"),
        ):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                WebOptions("127.0.0.1", 8765, origin, key)
