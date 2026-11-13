"""The request journal records what the agent asked for; only the owner approves links."""

from __future__ import annotations

import json
import stat
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from test_reference_library import FakePort

from plowshare_privacy.reference_library import ReferenceJournal, ReferenceLibrary
from plowshare_privacy.reference_requests import (
    AssistantReferenceRequests,
    ReferenceRequest,
    ReferenceRequests,
)
from plowshare_privacy.tools import ToolRefusal

START = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)
URL = "https://docs.example.test/cam/manual"
OTHER = "https://docs.example.test/cam/ports"
QUESTION = "Which ports does the camera open?"


class ReferenceRequestsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.now = START
        self.library = ReferenceLibrary(
            ReferenceJournal(self.root), FakePort(), self.stamp
        )
        self.requests = self.open()

    def stamp(self) -> str:
        return self.now.isoformat().replace("+00:00", "Z")

    def open(self) -> ReferenceRequests:
        return ReferenceRequests(self.root, self.library, self.stamp)

    def ask(
        self, product: str = "acme-cam", identity: str | None = None
    ) -> ReferenceRequest:
        return self.requests.request(product, QUESTION, None, identity or str(uuid4()))

    def test_same_invocation_is_idempotent(self) -> None:
        identity = str(uuid4())
        first = self.ask(identity=identity)
        again = self.requests.request("acme-cam", QUESTION, None, identity)
        self.assertEqual(first, again)
        self.assertEqual(first.id, identity)
        self.assertEqual(first.state, "pending")
        self.assertEqual(len(self.open().pending()), 1)
        # An approved request still answers the same invocation unchanged.
        done = self.requests.approve(identity, (URL,))
        self.assertEqual(
            self.requests.request("acme-cam", QUESTION, None, identity), done
        )

    def test_pending_product_deduplicates(self) -> None:
        first = self.ask()
        second = self.ask()
        self.assertEqual(second, first)
        self.assertEqual(len(self.requests.pending()), 1)
        self.assertEqual(self.ask("other-cam").state, "pending")
        self.assertEqual(len(self.requests.pending()), 2)

    def test_dismissed_product_suppressed_for_30_days_then_allowed(self) -> None:
        first = self.ask()
        dismissed = self.requests.dismiss(first.id)
        self.assertEqual(dismissed.state, "dismissed")
        self.now = START + timedelta(days=29, hours=23)
        suppressed = self.ask()
        self.assertEqual(suppressed.id, first.id)
        self.assertEqual(suppressed.state, "dismissed")
        self.assertEqual(self.requests.pending(), ())
        self.now = START + timedelta(days=30, seconds=1)
        allowed = self.ask()
        self.assertNotEqual(allowed.id, first.id)
        self.assertEqual(allowed.state, "pending")

    def test_approve_adds_owner_receipts(self) -> None:
        request = self.ask()
        done = self.requests.approve(request.id, (URL, OTHER))
        self.assertEqual(done.state, "filled")
        self.assertEqual(done.urls, (URL, OTHER))
        self.assertIsNotNone(done.decided_at)
        receipts = self.library.view()
        self.assertEqual([r.url for r in receipts], [URL, OTHER])
        self.assertEqual({r.origin for r in receipts}, {"owner"})
        self.assertEqual({r.product for r in receipts}, {"acme-cam"})
        self.assertEqual(receipts[0].title, "acme-cam documentation")
        self.assertEqual(self.requests.pending(), ())
        # The decision survives a restart.
        self.assertEqual(
            self.open().request("acme-cam", QUESTION, None, request.id), done
        )

    def test_approve_rejects_http_and_duplicate_urls(self) -> None:
        request = self.ask()
        existing = self.ask("other-cam")
        self.requests.approve(existing.id, (OTHER,))
        for urls in (
            (),
            ("http://docs.example.test/a",),
            ("https://user:pw@docs.example.test/a",),
            ("https://docs.example.test/" + "a" * 2048,),
            (URL, URL),
            (OTHER,),
            tuple(f"https://docs.example.test/{n}" for n in range(6)),
        ):
            with self.assertRaises(ValueError, msg=str(urls)):
                self.requests.approve(request.id, urls)
        # Nothing was queued and the request is still waiting.
        self.assertEqual([r.url for r in self.library.view()], [OTHER])
        self.assertEqual(self.requests.pending(), (request,))

    def test_decisions_only_from_pending(self) -> None:
        request = self.ask()
        self.requests.approve(request.id, (URL,))
        with self.assertRaises(ValueError):
            self.requests.approve(request.id, (OTHER,))
        with self.assertRaises(ValueError):
            self.requests.dismiss(request.id)
        with self.assertRaises(ValueError):
            self.requests.dismiss(str(uuid4()))
        with self.assertRaises(ValueError):
            self.requests.approve(str(uuid4()), (OTHER,))
        dismissed = self.ask("other-cam")
        self.requests.dismiss(dismissed.id)
        with self.assertRaises(ValueError):
            self.requests.approve(dismissed.id, (OTHER,))
        self.assertEqual([r.url for r in self.library.view()], [URL])

    def test_question_text_is_never_a_source(self) -> None:
        question = "See https://vendor.example/cam.pdf for the port list"
        request = self.requests.request("acme-cam", question, None, str(uuid4()))
        self.assertEqual(request.question, question)
        self.assertEqual(request.urls, ())
        self.assertEqual(self.library.view(), ())

    def test_journal_is_private_and_validated(self) -> None:
        self.ask()
        path = self.root / "reference-requests.json"
        self.assertEqual(stat.S_IMODE(path.stat().st_mode) & 0o077, 0)
        row = json.loads(path.read_text())
        row["requests"][0]["state"] = "mystery"
        path.write_text(json.dumps(row))
        path.chmod(0o600)
        with self.assertRaises(ValueError):
            self.open().pending()
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            self.open().pending()
        path.unlink()
        (self.root / "elsewhere.json").write_text("{}")
        path.symlink_to(self.root / "elsewhere.json")
        with self.assertRaises(ValueError):
            self.open().pending()

    def test_invalid_inputs_are_refused(self) -> None:
        with self.assertRaises(ValueError):
            self.requests.request("Not A Slug", QUESTION, None, str(uuid4()))
        with self.assertRaises(ValueError):
            self.requests.request("acme-cam", "", None, str(uuid4()))
        with self.assertRaises(ValueError):
            self.requests.request("acme-cam", QUESTION, None, "not-a-uuid")

    def test_decided_requests_are_pruned_not_pending(self) -> None:
        for index in range(ReferenceRequests.MAX_REQUESTS):
            self.ask(f"cam-{index}")
        with self.assertRaises(ValueError):
            self.ask("one-too-many")
        victim = self.requests.pending()[0]
        self.requests.dismiss(victim.id)
        self.now = START + timedelta(days=31)
        self.assertEqual(self.ask("one-too-many").state, "pending")


class AssistantReferenceRequestsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name).resolve()
        self.library = ReferenceLibrary(ReferenceJournal(root), FakePort())
        self.requests = ReferenceRequests(root, self.library)
        self.tool = AssistantReferenceRequests(self.requests)

    def test_wording_never_claims_an_answer(self) -> None:
        first = self.tool.request("acme-cam", QUESTION, None, str(uuid4()))
        self.assertEqual(first.state, "queued")
        self.assertIn("owner", first.detail)
        repeat = self.tool.request("acme-cam", QUESTION, None, str(uuid4()))
        self.assertEqual(repeat.state, "pending")
        self.assertIn("already waiting", repeat.detail)
        pending = self.requests.pending()[0]
        self.requests.dismiss(pending.id)
        dismissed = self.tool.request("acme-cam", QUESTION, None, str(uuid4()))
        self.assertEqual(dismissed.state, "dismissed")
        self.assertIn("declined", dismissed.detail)
        for outcome in (first, repeat, dismissed):
            self.assertNotIn("answered", outcome.detail.lower())

    def test_question_with_link_adds_nothing_to_the_library(self) -> None:
        outcome = self.tool.request(
            "acme-cam",
            "Read https://vendor.example/cam.pdf please",
            None,
            str(uuid4()),
        )
        self.assertEqual(outcome.state, "queued")
        self.assertEqual(self.library.view(), ())

    def test_refuses_when_the_journal_is_unreadable(self) -> None:
        path = self.requests.journal.path
        path.write_text("{}")
        path.chmod(0o600)
        with self.assertRaises(ToolRefusal):
            self.tool.request("acme-cam", QUESTION, None, str(uuid4()))

    def test_refuses_when_the_journal_is_full(self) -> None:
        for index in range(ReferenceRequests.MAX_REQUESTS):
            self.tool.request(f"cam-{index}", QUESTION, None, str(uuid4()))
        with self.assertRaises(ToolRefusal):
            self.tool.request("one-too-many", QUESTION, None, str(uuid4()))


if __name__ == "__main__":
    unittest.main()
