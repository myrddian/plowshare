"""Real file persistence and retained physical ownership; no server/database."""

import asyncio
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

import support  # noqa: F401
from custom_components.plowshare._vendor.plowshare.contracts import (
    AgentRunRequestEx,
    ConversationOpenRequestEx,
)
from homeassistant.core import CoreState, HomeAssistant
from homeassistant.helpers.storage import Store
from homeassistant.util.file import WriteError
from test_agent import SETTINGS, FakeClient

from custom_components.plowshare.agent import SdkConversationAgent
from custom_components.plowshare.models import Session
from custom_components.plowshare.requests import TaskRequest, digest
from custom_components.plowshare.storage import (
    MAX_JOURNAL_BYTES,
    CorrelationJournal,
    JournalUnavailable,
    _write,
)

KEY = "a" * 64


class JournalTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.path = self.root / "journal"
        self.journals = []
        self.releases = []

    async def asyncTearDown(self):
        for release in self.releases:
            release.set()
        for journal in self.journals:
            self.assertTrue(await journal.async_close())
        self.directory.cleanup()

    def journal(self, **kwargs):
        journal = CorrelationJournal(
            self.path, "plowshare.fixture", kwargs.pop("timeout", 1), **kwargs
        )
        self.journals.append(journal)
        return journal

    async def test_atomic_round_trip_private_permissions_and_no_prose(self):
        journal = self.journal()
        sessions = await journal.async_load()
        sessions[KEY] = Session(conversation="cnv_retained", phase="uncertain")
        await journal.async_save(sessions)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual((await self.journal().async_load())[KEY].phase, "uncertain")
        self.assertEqual(
            set(json.loads(self.path.read_text())),
            {"version", "minor_version", "key", "data"},
        )
        self.assertNotIn(SETTINGS.token, self.path.read_text())

    async def test_already_completed_read_establishes_writable_state_before_return(
        self,
    ):
        journal = self.journal()
        completed = asyncio.get_running_loop().create_future()
        completed.set_result({})
        # Its done callback is scheduled but cannot run before shield's fast
        # synchronous return. A successful load must still permit the first save.
        with patch.object(
            asyncio.get_running_loop(), "run_in_executor", return_value=completed
        ):
            sessions = await journal.async_load()
        await journal.async_save(sessions)
        self.assertEqual(json.loads(self.path.read_bytes())["data"], {})

    async def test_legacy_sending_is_quarantined_without_rewriting_file(self):
        old = {
            KEY: {
                "conversation": "cnv_old",
                "job": None,
                "phase": "sending",
                "cursor": 3,
            }
        }
        raw = json.dumps(
            {"version": 1, "key": "plowshare.fixture", "data": old}
        ).encode()
        self.path.write_bytes(raw)
        loaded = await self.journal().async_load()
        self.assertEqual(loaded[KEY].phase, "uncertain")
        self.assertEqual(loaded[KEY].mapping, "legacy")
        self.assertEqual(self.path.read_bytes(), raw)

    async def test_corrupt_duplicate_deep_and_oversize_sources_preserved(self):
        bodies = [
            b"not-json",
            b"\xff",
            b'{"version":1,"version":1,"key":"plowshare.fixture","data":{}}',
            json.dumps({"version": 1, "key": "wrong", "data": {}}).encode(),
            json.dumps(
                {"version": True, "key": "plowshare.fixture", "data": {}}
            ).encode(),
            json.dumps(
                {"version": 1, "key": "plowshare.fixture", "data": {}, "unknown": 1}
            ).encode(),
            ("[" * 2000 + "0" + "]" * 2000).encode(),
            b" " * (MAX_JOURNAL_BYTES + 1),
        ]
        for body in bodies:
            with self.subTest(prefix=body[:16]):
                self.path.write_bytes(body)
                with self.assertRaises(JournalUnavailable):
                    await self.journal().async_load()
                self.assertEqual(self.path.read_bytes(), body)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "POSIX FIFO admission")
    async def test_fifo_is_rejected_before_a_blocking_read(self):
        os.mkfifo(self.path)
        journal = self.journal(timeout=0.5)
        try:
            with self.assertRaises(JournalUnavailable):
                await asyncio.wait_for(journal.async_load(), 1)
            self.assertTrue(journal._read.done())
        finally:
            # Also release a broken blocking-open implementation so this test
            # would fail honestly rather than hang interpreter shutdown.
            try:
                descriptor = os.open(self.path, os.O_WRONLY | os.O_NONBLOCK)
            except OSError:
                pass
            else:
                os.close(descriptor)

    async def test_symlink_is_refused_without_reading_or_replacing_target(self):
        target = self.root / "target"
        target.write_text("private fixture")
        self.path.symlink_to(target)
        with self.assertRaises(JournalUnavailable):
            await self.journal().async_load()
        self.assertTrue(self.path.is_symlink())
        self.assertEqual(target.read_text(), "private fixture")

    async def test_actual_fsync_failure_preserves_old_file_and_refuses_commit(self):
        journal = self.journal()
        sessions = await journal.async_load()
        await journal.async_save(sessions)
        old = self.path.read_bytes()
        sessions[KEY] = Session(phase="uncertain")
        with patch(
            "custom_components.plowshare.storage.os.fsync",
            side_effect=OSError("fixture"),
        ):
            with self.assertRaises(JournalUnavailable):
                await journal.async_save(sessions)
        self.assertEqual(self.path.read_bytes(), old)
        self.assertEqual(list(self.root.glob(".plowshare-*")), [])

    @unittest.skipIf(
        hasattr(os, "geteuid") and os.geteuid() == 0,
        "root bypasses file permission checks",
    )
    async def test_read_only_directory_cannot_acknowledge_new_intent(self):
        journal = self.journal()
        sessions = await journal.async_load()
        self.root.chmod(0o500)
        try:
            with self.assertRaises(JournalUnavailable):
                await journal.async_save(sessions)
        finally:
            self.root.chmod(0o700)
        self.assertFalse(self.path.exists())

    async def test_timeout_retains_writer_and_fifo_prevents_late_stale_overwrite(self):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)
        written = []

        def writer(path, body):
            if not written:
                entered.set()
                if not release.wait(5):
                    raise OSError("fixture barrier expired")
            _write(path, body)
            written.append(json.loads(body)["data"][KEY]["phase"])

        journal = self.journal(timeout=0.03, writer=writer)
        sessions = await journal.async_load()
        sessions[KEY] = Session()
        first = asyncio.create_task(journal.async_save(sessions))
        self.assertTrue(await asyncio.to_thread(entered.wait, 1))
        with self.assertRaises(JournalUnavailable):
            await first
        sessions[KEY].phase = "uncertain"
        with self.assertRaises(JournalUnavailable):
            await journal.async_save(sessions)
        with patch("custom_components.plowshare.storage.CLEANUP_TIMEOUT", 0.02):
            self.assertFalse(await journal.async_close())
        self.assertFalse(self.path.exists())
        release.set()
        await asyncio.wait_for(journal._settled.wait(), 1)
        self.assertTrue(await journal.async_close())
        self.assertEqual(written, ["idle", "uncertain"])
        self.assertEqual(
            json.loads(self.path.read_bytes())["data"][KEY]["phase"], "uncertain"
        )

    async def test_caller_cancellation_does_not_cancel_physical_write(self):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)

        def writer(path, body):
            entered.set()
            if not release.wait(5):
                raise OSError("fixture barrier expired")
            _write(path, body)

        journal = self.journal(writer=writer)
        sessions = await journal.async_load()
        sessions[KEY] = Session(phase="sending")
        pending = asyncio.create_task(journal.async_save(sessions))
        self.assertTrue(await asyncio.to_thread(entered.wait, 1))
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        with patch("custom_components.plowshare.storage.CLEANUP_TIMEOUT", 0.02):
            self.assertFalse(await journal.async_close())
        release.set()
        await asyncio.wait_for(journal._settled.wait(), 1)
        restarted = self.journal()
        self.assertEqual((await restarted.async_load())[KEY].phase, "uncertain")

    async def test_failed_real_commit_prevents_any_ex_mutation(self):
        def failure(_path, _body):
            raise OSError("fixture disk failure")

        journal = self.journal(writer=failure)
        sessions = await journal.async_load()
        client = FakeClient()

        async def save():
            await journal.async_save(sessions)

        gateway = SdkConversationAgent(
            SETTINGS,
            sessions,
            save,
            AsyncMock(return_value=client),
            profile_id="fixture:task",
        )
        result = await gateway.generate(
            TaskRequest(digest(("invocation",)), "Generate a note")
        )
        self.assertNotEqual(result.status, "completed")
        self.assertFalse(
            any(
                isinstance(request, (ConversationOpenRequestEx, AgentRunRequestEx))
                for request in client.requests
            )
        )
        self.assertTrue(await gateway.async_close())


class HAStoreContractTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.hass = HomeAssistant(self.directory.name)

    async def asyncTearDown(self):
        self.hass.state = CoreState.not_running
        await self.hass.async_stop()
        self.directory.cleanup()

    async def test_ha_store_read_only_and_write_error_return_without_commit(self):
        store = Store(self.hass, 1, "fixture.reference", private=True)
        await store.async_save({"phase": "idle"})
        original = Path(store.path).read_bytes()
        with patch.object(
            store, "_async_write_data", AsyncMock(side_effect=WriteError("fixture"))
        ):
            await store.async_save({"phase": "sending"})
        self.assertEqual(Path(store.path).read_bytes(), original)
        store.make_read_only()
        await store.async_save({"phase": "sending"})
        self.assertEqual(Path(store.path).read_bytes(), original)

    async def test_ha_stopping_defers_store_but_owned_journal_commit_is_truthful(self):
        store = Store(self.hass, 1, "fixture.reference", private=True)
        self.hass.state = CoreState.stopping
        await store.async_save({"phase": "sending"})
        self.assertFalse(Path(store.path).exists())
        path = Path(self.directory.name) / ".storage" / "fixture.owned"
        journal = CorrelationJournal(path, "fixture.owned", 1)
        sessions = await journal.async_load()
        sessions[KEY] = Session(phase="uncertain")
        await journal.async_save(sessions)
        self.assertTrue(path.exists())
        self.assertEqual(
            json.loads(path.read_bytes())["data"][KEY]["phase"], "uncertain"
        )
        self.assertTrue(await journal.async_close())
