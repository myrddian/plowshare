"""The assistant's request and record tools reuse the dashboard's services.

Every write goes through ``DeviceProfiles.save`` with the harness invocation
as its request identity, so a replayed call never writes twice, and no tool
reaches monitor settings, connections, the schedule, the zone or limits.
"""

from __future__ import annotations

import inspect
import json
import tempfile
import unittest
from collections.abc import Mapping
from dataclasses import replace
from pathlib import Path
from types import MappingProxyType
from uuid import uuid4

from plowshare.tool_schemas import ToolValue
from plowshare.tools import ToolCall
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_deep_scan import Identities
from test_device_profiles import Documents
from test_native_tools import address_only, mac_card
from test_privacy_issues import IssueDocuments
from test_privacy_issues import fields as issue_fields

from plowshare_privacy.contracts import utc_now
from plowshare_privacy.deep_scan import DeepScanJournal, DeepScanner
from plowshare_privacy.device_assessments import AssessmentConflict, AssessmentView
from plowshare_privacy.device_inventory import DeviceInventory
from plowshare_privacy.device_profiles import (
    DeviceProfiles,
    FileProfileIntents,
    ProfileFields,
)
from plowshare_privacy.issue_records import FileIssueIntents, PrivacyIssues
from plowshare_privacy.native_tools import DECLARATIONS, registered
from plowshare_privacy.presence import PresenceView, Sighting
from plowshare_privacy.tools import WorkerTools
from plowshare_privacy.worker import Worker

DEVICE = "mac:02:00:00:00:01:01"
NOW = "2026-11-13T10:00:00Z"


def assessment_view(device_id: str, state: str = "running") -> AssessmentView:
    return AssessmentView(
        device_id=device_id,
        state=state,  # type: ignore[arg-type]
        pending=None,
        request_id=str(uuid4()),
        run_id=None,
        report=None,
        requested_at=NOW,
        decided_at=None,
        review=None,
        concerns=(),
        detail=None,
        can_assess=False,
        can_accept=False,
        can_dismiss=False,
        assess_note=None,
    )


class Assessments:
    """A fake ``assess``: the real service's limits are its own tests' concern."""

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.refusal: str | None = None

    async def assess(self, device_id: str) -> AssessmentView:
        self.calls.append(device_id)
        if self.refusal is not None:
            raise AssessmentConflict(self.refusal)
        return assessment_view(device_id)


class AssistantToolsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = configuration(self.root)
        self.worker = Worker(
            self.config,
            FixturePort(self.config),
            MemoryReceipts(),
            CountingCollector(self.config),
        )
        self.card = mac_card(1)
        self.inventory = DeviceInventory((self.card, address_only(2)))
        self.issue_remote = IssueDocuments()
        self.issues = PrivacyIssues(
            self.issue_remote,
            FileIssueIntents(self.root, self.config.project, self.config.collector),
        )
        self.remote = Documents()
        assert self.card.identity is not None
        fresh = replace(self.card.identity, observed_at=utc_now())
        self.profiles = DeviceProfiles(
            self.remote,
            FileProfileIntents(self.root, self.config.project, self.config.collector),
            MemoryReceipts(),
            lambda: (self.card.address,),
            self.issues,
            lambda: PresenceView(
                sightings={self.card.address: Sighting(self.card.address, fresh)}
            ),
        )
        self.scanner = DeepScanner(
            DeepScanJournal(self.root),
            lambda: self.inventory,
            lambda: Identities(),
        )
        self.assessments = Assessments()

    def tools(self, **overrides: object) -> WorkerTools:
        services: dict[str, object] = {
            "deep_scans": self.scanner,
            "assessments": self.assessments,
            "profiles": self.profiles,
            "issues": self.issues,
        }
        services.update(overrides)
        return WorkerTools(
            self.worker,
            inventory=lambda: self.inventory,
            clock=lambda: NOW,
            **services,  # type: ignore[arg-type]
        )

    async def invoke(
        self,
        tool: str,
        arguments: Mapping[str, ToolValue],
        *,
        invocation: str | None = None,
        tools: WorkerTools | None = None,
    ) -> tuple[str, str]:
        handler = next(
            item
            for item in registered(tools or self.tools())
            if item.declaration.name == tool
        )
        result = await handler.handler(
            ToolCall(
                invocation or str(uuid4()),
                self.config.project,
                "privacy-scanner",
                tool,
                "caller",
                "run",
                "call",
                "2099-01-01T00:00:00Z",
                MappingProxyType(arguments),
            )
        )
        return result.state, result.text

    async def issue(self) -> tuple[str, str]:
        identity = str(uuid4())
        saved = await self.issues.save(identity, str(uuid4()), None, issue_fields())
        assert saved.revision is not None
        return identity, saved.revision

    async def test_request_tools_queue_work_through_the_existing_services(
        self,
    ) -> None:
        state, text = await self.invoke(
            "network_request_deep_scan", {"device_id": DEVICE}
        )
        self.assertEqual(state, "COMPLETED")
        reply = json.loads(text)
        self.assertEqual(
            (reply["device_id"], reply["work"], reply["state"]),
            (DEVICE, "deep_scan", "queued"),
        )
        # A repeat finds the queued operator request; nothing queues twice.
        state, _ = await self.invoke("network_request_deep_scan", {"device_id": DEVICE})
        self.assertEqual(state, "COMPLETED")
        self.assertEqual(
            [(q.device_id, q.trigger) for q in self.scanner.ledger.queue],
            [(DEVICE, "operator")],
        )

        state, text = await self.invoke(
            "network_request_assessment", {"device_id": DEVICE}
        )
        self.assertEqual(state, "COMPLETED")
        reply = json.loads(text)
        self.assertEqual((reply["work"], reply["state"]), ("assessment", "running"))
        self.assertEqual(self.assessments.calls, [DEVICE])
        # The service's own refusal, such as its daily limit, reaches the agent.
        self.assessments.refusal = "The daily limit of 3 assessments is reached."
        state, text = await self.invoke(
            "network_request_assessment", {"device_id": DEVICE}
        )
        self.assertEqual(
            (state, text), ("REJECTED", "The daily limit of 3 assessments is reached.")
        )

        bare = self.tools(deep_scans=None, assessments=None, profiles=None, issues=None)
        for tool, arguments, reason in (
            (
                "network_request_deep_scan",
                {"device_id": DEVICE},
                "Deep scans are not enabled on this collector",
            ),
            (
                "network_request_assessment",
                {"device_id": DEVICE},
                "Device assessments are not enabled on this collector",
            ),
            (
                "network_note_profile",
                {"device_id": DEVICE, "note": "A note"},
                "Device profiles are not enabled on this collector",
            ),
            (
                "network_record_issue",
                {
                    "device_id": DEVICE,
                    "issue_id": str(uuid4()),
                    "applicability": "potentially_affected",
                },
                "Privacy issues are not enabled on this collector",
            ),
        ):
            state, text = await self.invoke(tool, arguments, tools=bare)
            self.assertEqual(state, "REJECTED", tool)
            self.assertTrue(text.startswith(reason), text)

    async def test_note_profile_appends_an_attributed_dated_line(self) -> None:
        invocation = str(uuid4())
        state, text = await self.invoke(
            "network_note_profile",
            {"device_id": DEVICE, "note": "The owner says this is the kitchen TV."},
            invocation=invocation,
        )
        self.assertEqual(state, "COMPLETED", text)
        reply = json.loads(text)
        self.assertEqual(
            (reply["phase"], reply["request_id"]), ("confirmed", invocation)
        )
        [view] = await self.profiles.list()
        saved = view.profile.fields
        # A minimal profile named after the device and bound to its MAC.
        self.assertEqual(
            (saved.name, saved.addresses, saved.device_mac),
            (self.card.name, (self.card.address,), "02:00:00:00:01:01"),
        )
        self.assertEqual(
            saved.notes,
            "Noted by Privacy Watch, 2026-11-13: The owner says this is the kitchen TV.",
        )
        # A replay of the same invocation returns the earlier write.
        self.assertEqual(
            await self.invoke(
                "network_note_profile",
                {"device_id": DEVICE, "note": "The owner says this is the kitchen TV."},
                invocation=invocation,
            ),
            (state, text),
        )
        self.assertEqual(self.remote.writes, 1)

        state, _ = await self.invoke(
            "network_note_profile",
            {"device_id": DEVICE, "note": "It streams at night.\nOnly then."},
        )
        self.assertEqual(state, "COMPLETED")
        [view] = await self.profiles.list()
        self.assertEqual(
            view.profile.fields.notes,
            "Noted by Privacy Watch, 2026-11-13: The owner says this is the kitchen TV.\n"
            "Noted by Privacy Watch, 2026-11-13: It streams at night.\nOnly then.",
        )
        self.assertEqual(view.profile.fields.name, self.card.name)
        self.assertEqual(self.remote.writes, 2)

        # Notes that would pass the profile's limit are refused, not cut.
        full = replace(view.profile.fields, notes="x" * 2040)
        await self.profiles.save(
            view.profile.profile_id, str(uuid4()), view.revision, full
        )
        state, text = await self.invoke(
            "network_note_profile", {"device_id": DEVICE, "note": "One more."}
        )
        self.assertEqual(state, "REJECTED")
        self.assertIn("2048", text)
        self.assertEqual(self.remote.writes, 3)

    async def test_note_profile_refuses_an_address_only_profile(self) -> None:
        legacy = ProfileFields(
            "Old TV", "", "", "", "", "", (self.card.address,), (), None
        )
        await self.profiles.save(str(uuid4()), str(uuid4()), None, legacy)
        state, text = await self.invoke(
            "network_note_profile", {"device_id": DEVICE, "note": "A note."}
        )
        self.assertEqual(state, "REJECTED")
        self.assertIn("dashboard", text)
        self.assertEqual(self.remote.writes, 1)

    async def test_record_issue_links_an_existing_issue_and_records_progress(
        self,
    ) -> None:
        identity, revision = await self.issue()
        await self.invoke(
            "network_note_profile", {"device_id": DEVICE, "note": "Kitchen TV."}
        )
        invocation = str(uuid4())
        state, text = await self.invoke(
            "network_record_issue",
            {
                "device_id": DEVICE,
                "issue_id": identity,
                "applicability": "potentially_affected",
                "mitigation_status": "accepted",
                "note": "The owner agreed to try the outbound block.",
            },
            invocation=invocation,
        )
        self.assertEqual(state, "COMPLETED", text)
        self.assertEqual(json.loads(text)["request_id"], invocation)
        [view] = await self.profiles.list()
        [link] = view.profile.fields.issues
        self.assertEqual(
            (link.issue_id, link.revision, link.applicability, link.mitigation_status),
            (identity, revision, "potentially_affected", "accepted"),
        )
        self.assertEqual(
            link.notes,
            "Privacy Watch, 2026-11-13: The owner agreed to try the outbound block.",
        )
        # Profile notes are untouched by a link.
        self.assertEqual(
            view.profile.fields.notes, "Noted by Privacy Watch, 2026-11-13: Kitchen TV."
        )
        writes = self.remote.writes

        # Progress updates the same link; it is never duplicated.
        state, text = await self.invoke(
            "network_record_issue",
            {
                "device_id": DEVICE,
                "issue_id": identity,
                "applicability": "confirmed_affected",
                "mitigation_status": "applied",
                "note": "The owner added the firewall rule.",
            },
        )
        self.assertEqual(state, "COMPLETED", text)
        [view] = await self.profiles.list()
        [link] = view.profile.fields.issues
        self.assertEqual(
            (link.applicability, link.mitigation_status, link.notes),
            (
                "confirmed_affected",
                "applied",
                "Privacy Watch, 2026-11-13: The owner added the firewall rule.",
            ),
        )
        self.assertEqual(self.remote.writes, writes + 1)

        # Without a status, the existing one and its notes are kept.
        state, _ = await self.invoke(
            "network_record_issue",
            {
                "device_id": DEVICE,
                "issue_id": identity,
                "applicability": "confirmed_affected",
            },
        )
        self.assertEqual(state, "COMPLETED")
        [view] = await self.profiles.list()
        [link] = view.profile.fields.issues
        self.assertEqual(link.mitigation_status, "applied")
        self.assertIn("firewall rule", link.notes)

    async def test_record_issue_creates_the_minimal_profile_when_none_exists(
        self,
    ) -> None:
        identity, revision = await self.issue()
        state, text = await self.invoke(
            "network_record_issue",
            {
                "device_id": DEVICE,
                "issue_id": identity,
                "applicability": "potentially_affected",
            },
        )
        self.assertEqual(state, "COMPLETED", text)
        [view] = await self.profiles.list()
        self.assertEqual(
            (view.profile.fields.name, view.profile.fields.device_mac),
            (self.card.name, "02:00:00:00:01:01"),
        )
        [link] = view.profile.fields.issues
        self.assertEqual(
            (link.revision, link.mitigation_status, link.notes),
            (revision, "proposed", ""),
        )

    async def test_record_issue_refuses_unknown_issues_and_missing_required_notes(
        self,
    ) -> None:
        identity, _ = await self.issue()
        for arguments, reason in (
            (
                {
                    "device_id": DEVICE,
                    "issue_id": str(uuid4()),
                    "applicability": "potentially_affected",
                },
                "dashboard",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": identity,
                    "applicability": "potentially_affected",
                    "mitigation_status": "applied",
                },
                "note",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": identity,
                    "applicability": "confirmed_affected",
                },
                "note",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": identity,
                    "applicability": "mitigated",
                    "mitigation_status": "applied",
                    "note": "Done.",
                },
                "verified",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": identity,
                    "applicability": "fixed",
                },
                "applicability",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": identity,
                    "applicability": "potentially_affected",
                    "mitigation_status": "done",
                },
                "mitigation_status",
            ),
            (
                {
                    "device_id": DEVICE,
                    "issue_id": "not-a-uuid",
                    "applicability": "potentially_affected",
                },
                "issue_id",
            ),
        ):
            state, text = await self.invoke("network_record_issue", arguments)
            self.assertEqual(state, "REJECTED", arguments)
            self.assertIn(reason, text, arguments)
        self.assertEqual(self.remote.writes, 0)

    async def test_assistant_tools_refuse_unknown_devices_and_bad_text(self) -> None:
        identity, _ = await self.issue()
        for device in ("mac:02:00:00:00:09:09", "ip:10.1.4.3", "10.1.3.2"):
            for tool, arguments in (
                ("network_request_deep_scan", {"device_id": device}),
                ("network_request_assessment", {"device_id": device}),
                ("network_note_profile", {"device_id": device, "note": "A note."}),
                (
                    "network_record_issue",
                    {
                        "device_id": device,
                        "issue_id": identity,
                        "applicability": "potentially_affected",
                    },
                ),
            ):
                state, text = await self.invoke(tool, arguments)
                self.assertEqual(state, "REJECTED", (tool, device))
                self.assertIn("monitored device", text)
                self.assertNotIn("09:09", text)
        # An address-only device has no confirmed MAC, so it has no device_id.
        address_device = "mac:" + "02:00:00:00:00:01"
        state, _ = await self.invoke(
            "network_note_profile", {"device_id": address_device, "note": "A note."}
        )
        self.assertEqual(state, "REJECTED")
        for note in ("", "   ", "x" * 1025, "bell\x07", "tab\there", "nul\x00"):
            state, text = await self.invoke(
                "network_note_profile", {"device_id": DEVICE, "note": note}
            )
            self.assertEqual(state, "REJECTED", repr(note))
            self.assertIn("note", text)
        state, text = await self.invoke(
            "network_record_issue",
            {
                "device_id": DEVICE,
                "issue_id": identity,
                "applicability": "potentially_affected",
                "note": "x" * 486,
            },
        )
        self.assertEqual(state, "REJECTED")
        self.assertIn("note", text)
        self.assertEqual(self.remote.writes, 0)
        self.assertEqual(self.assessments.calls, [])
        self.assertEqual(self.scanner.ledger.queue, ())

    def test_no_assistant_tool_reaches_settings_or_credentials(self) -> None:
        assistant = {
            "network_request_deep_scan",
            "network_request_assessment",
            "network_note_profile",
            "network_record_issue",
        }
        names = {item.name for item in DECLARATIONS}
        self.assertLessEqual(assistant, names)
        # The whole provider: reads, the scan request and the four above.
        self.assertEqual(
            names - assistant,
            {
                "network_scope",
                "network_scan",
                "network_scan_status",
                "network_scan_list",
                "network_evidence",
                "network_destinations",
                "network_devices",
                "network_device",
            },
        )
        forbidden = (
            "monitor",
            "setting",
            "pihole",
            "unifi",
            "schedule",
            "zone",
            "limit",
            "credential",
            "token",
            "password",
            "target",
            "recover",
        )
        for item in DECLARATIONS:
            if item.name not in assistant:
                continue
            words = [item.name] + [p.name for p in item.parameters]
            for word in words:
                for term in forbidden:
                    self.assertNotIn(term, word.lower(), item.name)
            self.assertLessEqual(
                {p.name for p in item.parameters},
                {"device_id", "note", "issue_id", "applicability", "mitigation_status"},
            )
        # The tools are composed from request and record services only.
        self.assertEqual(
            set(inspect.signature(WorkerTools).parameters),
            {
                "worker",
                "inventory",
                "deep_scans",
                "assessments",
                "profiles",
                "issues",
                "clock",
            },
        )
        self.assertIn(
            "cannot create",
            next(
                item.description
                for item in DECLARATIONS
                if item.name == "network_record_issue"
            ),
        )


if __name__ == "__main__":
    unittest.main()
