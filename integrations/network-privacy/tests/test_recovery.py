"""Backoff, cancellation and receipt fences for automatic session recovery."""

from __future__ import annotations

import asyncio
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4, uuid5

from plowshare import Delivery, Refusal, TransportError
from plowshare.contracts import RelayPublishRequest, RelayPublishResultDto
from support import CountingCollector, FixturePort, MemoryReceipts, configuration, event

from plowshare_privacy.ports import AvailableTopics
from plowshare_privacy.recovery import wait_for_recovery
from plowshare_privacy.worker import ReconciliationRequired, Worker


class RecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def test_offline_attempts_back_off_to_cap_then_settle(self) -> None:
        stop, resumed = asyncio.Event(), asyncio.Event()
        recover = AsyncMock(
            side_effect=[TransportError(Delivery.NOT_SUBMITTED, "offline")] * 4 + [None]
        )
        original_wait = asyncio.wait
        delays: list[float] = []

        async def wait(
            tasks: tuple[asyncio.Task[object], ...],
            *,
            timeout: float | None = None,
            return_when: str,
        ) -> tuple[set[asyncio.Task[object]], set[asyncio.Task[object]]]:
            if timeout is not None:
                delays.append(timeout)
                timeout = 0  # Keep the real event/cancellation behavior without wall-clock waits.
            return await original_wait(tasks, timeout=timeout, return_when=return_when)

        with (
            patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 2),
            patch("plowshare_privacy.recovery.MAX_RECONNECT_SECONDS", 8),
            patch("plowshare_privacy.recovery.asyncio.wait", side_effect=wait),
        ):
            await wait_for_recovery(stop, resumed, recover)
        self.assertEqual(delays, [2, 4, 8, 8, 8])
        self.assertEqual(recover.await_count, 5)
        self.assertTrue(resumed.is_set())

    async def test_manual_check_interrupts_backoff_without_opening_another_session(
        self,
    ) -> None:
        stop, resumed = asyncio.Event(), asyncio.Event()
        recover = AsyncMock()
        task = asyncio.create_task(wait_for_recovery(stop, resumed, recover))
        await asyncio.sleep(0)
        resumed.set()
        await asyncio.wait_for(task, 1)
        recover.assert_not_awaited()

    async def test_shutdown_cancels_an_inflight_recovery_and_releases_its_owner(
        self,
    ) -> None:
        stop, resumed, started, closed = (asyncio.Event() for _ in range(4))

        async def recover() -> None:
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                closed.set()

        with patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 0):
            task = asyncio.create_task(wait_for_recovery(stop, resumed, recover))
            await asyncio.wait_for(started.wait(), 1)
            stop.set()
            await asyncio.wait_for(task, 1)
        self.assertTrue(closed.is_set())
        self.assertFalse(resumed.is_set())

    async def test_unsafe_outcomes_are_not_retried(self) -> None:
        for error in (
            Refusal("REFUSED", "permission revoked"),
            ReconciliationRequired("receipt absent"),
            ValueError("conflicting receipt"),
            TransportError(Delivery.INVALID_RESPONSE, "invalid response"),
        ):
            with self.subTest(error=type(error).__name__):
                resumed = asyncio.Event()
                recover = AsyncMock(side_effect=error)
                with patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 0):
                    with self.assertRaises(type(error)):
                        await wait_for_recovery(asyncio.Event(), resumed, recover)
                recover.assert_awaited_once()
                self.assertFalse(resumed.is_set())


class WorkerRecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = configuration(Path(self.temporary.name))
        self.port = FixturePort(self.config)
        self.receipts = MemoryReceipts()
        self.worker = Worker(
            self.config, self.port, self.receipts, CountingCollector(self.config)
        )

    async def test_refusal_never_triggers_automatic_reconnect(self) -> None:
        stop = asyncio.Event()
        recover = AsyncMock()

        async def denied() -> None:
            stop.set()
            raise Refusal("REFUSED", "permission revoked")

        with patch.object(self.worker, "poll", side_effect=denied):
            await self.worker.run(stop, recover=recover)
        recover.assert_not_awaited()
        self.assertEqual(self.worker.failure_kind, "Refusal")

    async def test_absent_publication_remains_pending_and_is_never_resent(self) -> None:
        identity = str(uuid4())
        with patch.object(
            self.port,
            "publish",
            side_effect=TransportError(Delivery.UNKNOWN, "reply lost"),
        ) as publish:
            with self.assertRaises(TransportError):
                await self.worker.request_scan(identity)
            stop = asyncio.Event()

            async def reconnect() -> None:
                try:
                    await self.worker.reconcile()
                finally:
                    stop.set()

            with (
                patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 0),
                patch.object(
                    self.worker,
                    "poll",
                    side_effect=TransportError(Delivery.NOT_SUBMITTED, "closed"),
                ),
            ):
                await self.worker.run(stop, recover=reconnect)
            publish.assert_awaited_once()
        self.assertEqual(self.receipts.publications()[0].request_id, identity)
        self.assertEqual(self.receipts.publications()[0].state, "pending")
        self.assertEqual(self.worker.state, "attention_required")
        self.assertEqual(self.worker.failure_kind, "ReconciliationRequired")

    async def test_lost_completion_reply_settles_without_republication_or_rescanning(
        self,
    ) -> None:
        self.port.events["schedule.due"] = [event()]
        publish = self.port.publish
        stop = asyncio.Event()
        reconciled = asyncio.Event()

        async def lost(request: RelayPublishRequest) -> RelayPublishResultDto:
            await publish(request)
            raise TransportError(Delivery.UNKNOWN, "completion reply lost")

        async def reconnect() -> None:
            await self.worker.reconcile()
            reconciled.set()

        poll = self.worker.poll

        async def resumed_poll() -> None:
            await poll()
            if reconciled.is_set():
                stop.set()

        with (
            patch.object(self.port, "publish", side_effect=lost) as send,
            patch.object(self.worker, "poll", side_effect=resumed_poll),
            patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 0),
        ):
            await asyncio.wait_for(self.worker.run(stop, recover=reconnect), 1)
            send.assert_awaited_once()
        self.assertTrue(reconciled.is_set())
        self.assertEqual(self.receipts.all()[0].phase, "done")
        self.assertEqual(self.port.uploads, 1)
        self.assertEqual(len(self.port.published), 1)
        self.assertEqual(self.worker.state, "ready")

    async def _lose_completion(self, *, retained: bool) -> str:
        """Leave one scan in ``publishing`` after an unknown completion outcome."""
        self.port.events["schedule.due"] = [event()]
        publish = self.port.publish

        async def lost(request: RelayPublishRequest) -> RelayPublishResultDto:
            if retained:
                await publish(request)
            raise TransportError(Delivery.UNKNOWN, "completion reply lost")

        with patch.object(self.port, "publish", side_effect=lost):
            # One pass copies the occurrence, collects, uploads and publishes.
            with self.assertRaises(TransportError) as stopped:
                await self.worker.poll()
        self.worker._attention(stopped.exception)
        receipt = self.receipts.all()[0]
        self.assertEqual(receipt.phase, "publishing")
        return receipt.scan_id

    def _retain_earlier_completion(self) -> None:
        # The log still reaches back before this scan finished, so a retained
        # completion could not have aged out of it.
        self.port.events[self.config.result_topic].insert(
            0,
            replace(
                event(str(uuid4()), manual=True), occurred_at="2000-01-01T00:00:00Z"
            ),
        )

    async def test_operator_release_republishes_an_absent_completion_once(
        self,
    ) -> None:
        scan = await self._lose_completion(retained=False)
        self._retain_earlier_completion()
        with self.assertRaises(ReconciliationRequired):
            await self.worker.reconcile()  # The ordinary check stays read-only.
        self.assertEqual(self.port.published, [])

        self.assertTrue(await self.worker.release_absent_completion(scan))
        self.assertEqual(self.receipts.all()[0].phase, "uploaded")
        self.assertEqual(self.port.published, [])  # Release itself sends nothing.

        await self.worker.reconcile()
        await self.worker.poll()
        self.assertEqual(self.receipts.all()[0].phase, "done")
        self.assertEqual(
            [item.request_id for item in self.port.published],
            [str(uuid5(UUID(scan), "completion"))],
        )
        self.assertEqual(self.port.uploads, 1)

    async def test_operator_release_settles_a_retained_completion_without_resending(
        self,
    ) -> None:
        scan = await self._lose_completion(retained=True)
        self.assertFalse(await self.worker.release_absent_completion(scan))
        self.assertEqual(self.receipts.all()[0].phase, "done")
        self.assertEqual(len(self.port.published), 1)

    async def test_operator_release_refuses_when_the_log_cannot_prove_absence(
        self,
    ) -> None:
        scan = await self._lose_completion(retained=False)
        # Nothing older is retained: the completion may have been published and
        # then removed by retention, so absence proves nothing.
        with self.assertRaises(ReconciliationRequired):
            await self.worker.release_absent_completion(scan)
        self.port.events[self.config.result_topic].append(
            replace(
                event(str(uuid4()), manual=True), occurred_at="2099-01-01T00:00:00Z"
            )
        )
        with self.assertRaises(ReconciliationRequired):
            await self.worker.release_absent_completion(scan)
        self.assertEqual(self.receipts.all()[0].phase, "publishing")
        self.assertEqual(self.port.published, [])

    async def test_operator_release_refuses_a_conflicting_retained_completion(
        self,
    ) -> None:
        scan = await self._lose_completion(retained=True)
        topic = self.port.events[self.config.result_topic]
        topic[0] = replace(
            topic[0], payload=replace(topic[0].payload, text='{"version": 1}')
        )
        with self.assertRaises(ReconciliationRequired):
            await self.worker.release_absent_completion(scan)
        self.assertEqual(self.receipts.all()[0].phase, "publishing")
        self.assertEqual(len(self.port.published), 1)

    async def test_operator_release_needs_a_stopped_collector_and_an_unknown_completion(
        self,
    ) -> None:
        scan = await self._lose_completion(retained=False)
        self._retain_earlier_completion()
        with self.assertRaises(ValueError):
            await self.worker.release_absent_completion(str(uuid4()))
        with self.assertRaises(ValueError):
            await self.worker.release_absent_completion("not-a-scan")
        self.worker.state = "ready"
        with self.assertRaises(ValueError):
            await self.worker.release_absent_completion(scan)
        self.assertEqual(self.receipts.all()[0].phase, "publishing")
        self.assertEqual(self.port.published, [])

    async def test_disabled_collection_reconnects_without_scanning(self) -> None:
        self.worker.config = replace(
            self.config, collection=replace(self.config.collection, enabled=False)
        )
        stop = asyncio.Event()
        checks = 0

        async def topics() -> AvailableTopics:
            nonlocal checks
            checks += 1
            if checks == 1:
                raise TransportError(Delivery.NOT_SUBMITTED, "closed")
            stop.set()
            return AvailableTopics(frozenset(), frozenset())

        recover = AsyncMock()
        with (
            patch.object(self.port, "available_topics", side_effect=topics),
            patch("plowshare_privacy.recovery.INITIAL_RECONNECT_SECONDS", 0),
        ):
            await asyncio.wait_for(self.worker.run(stop, recover=recover), 1)
        recover.assert_awaited_once()
        self.assertEqual(checks, 2)
        self.assertEqual(self.worker.state, "configuration_required")
        self.assertEqual(self.port.uploads, 0)
        self.assertEqual(self.port.published, [])
