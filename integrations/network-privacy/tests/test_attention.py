"""Failure alerts retain identity across stops, restarts and uncertain replies."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from plowshare import Delivery, TransportError

from plowshare_privacy.attention import AttentionOutbox


class AttentionTest(unittest.IsolatedAsyncioTestCase):
    async def test_one_notice_per_episode_and_routine_recovery_sends_nothing(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            port = AsyncMock()
            outbox = AttentionOutbox(Path(temporary).resolve(), port)
            outbox.recovered()
            port.publish.assert_not_awaited()
            await outbox.stopped("ValueError", "network collection")
            await outbox.stopped("ValueError", "network collection")
            port.publish.assert_awaited_once()
            reopened = AttentionOutbox(Path(temporary).resolve(), port)
            await reopened.stopped("ValueError", "network collection")
            port.publish.assert_awaited_once()
            reopened.recovered()
            await reopened.stopped("OSError", "network collection")
            self.assertEqual(2, port.publish.await_count)
            self.assertNotEqual(outbox.rows[0].request, reopened.rows[1].request)

    async def test_intent_precedes_submission_and_unknown_delivery_never_replays(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            port = AsyncMock()
            outbox = AttentionOutbox(Path(temporary).resolve(), port)

            async def publish(request: str, message: str) -> None:
                retained = AttentionOutbox(Path(temporary).resolve(), port)
                self.assertEqual(request, retained.rows[0].request)
                self.assertEqual("pending", retained.rows[0].phase)
                self.assertIn("TransportError", message)
                raise TransportError(Delivery.UNKNOWN, "lost response")

            port.publish.side_effect = publish
            detail = await outbox.stopped("TransportError", "information.upload")
            self.assertIn("unconfirmed", detail or "")
            port.confirmed.return_value = False
            await outbox.reconcile()
            self.assertEqual(1, outbox.pending)
            port.publish.assert_awaited_once()
            port.confirmed.return_value = True
            await outbox.reconcile()
            self.assertEqual(0, outbox.pending)
            port.publish.assert_awaited_once()

    async def test_explicit_retry_preserves_the_original_identity_and_text(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            port = AsyncMock()
            port.publish.side_effect = TransportError(Delivery.NOT_SUBMITTED, "offline")
            outbox = AttentionOutbox(Path(temporary).resolve(), port)
            await outbox.stopped("TransportError", "relay.topics")
            original = port.publish.await_args
            port.publish.side_effect = None
            port.confirmed.return_value = False
            reopened = AttentionOutbox(Path(temporary).resolve(), port)
            self.assertEqual(1, await reopened.send_pending())
            self.assertEqual(original, port.publish.await_args)
            self.assertEqual(0, reopened.pending)

    async def test_confirmed_pending_alert_is_not_submitted_again(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            port = AsyncMock()
            port.publish.side_effect = TransportError(Delivery.UNKNOWN, "lost")
            outbox = AttentionOutbox(Path(temporary).resolve(), port)
            await outbox.stopped("TransportError", "relay.publish")
            port.confirmed.return_value = True
            self.assertEqual(1, await outbox.send_pending())
            port.publish.assert_awaited_once()

    def test_malformed_and_linked_journals_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            path = directory / "attention.json"
            path.write_text('[{"phase":"published"}]')
            with self.assertRaises(ValueError):
                AttentionOutbox(directory, AsyncMock())
            path.unlink()
            (directory / "other").write_text("[]")
            path.symlink_to(directory / "other")
            with self.assertRaises(ValueError):
                AttentionOutbox(directory, AsyncMock())
