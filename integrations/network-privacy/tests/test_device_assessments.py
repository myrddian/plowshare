"""Device assessments: one run at a time, receipts settle uncertainty, decisions are fenced."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Literal, cast
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, Reply, TransportError
from plowshare.contracts import (
    FinalisedInformationDto,
    InformationFinaliseRequest,
    InformationListRequest,
    InformationRevisionDto,
    InformationStatusRequest,
    InformationStepDto,
    OrchestrationStartRequest,
)
from support import configuration
from test_device_dossier import activity, card, facts, identity

from plowshare_privacy.assessment_port import (
    AssessmentRun,
    ReportState,
    ReportStatus,
    SdkAssessmentPort,
)
from plowshare_privacy.assessment_request import (
    CALLER,
    DEFINITION,
    AssessmentRequest,
    ConcernOutcome,
    report_name,
)
from plowshare_privacy.device_assessments import (
    DAILY_CEILING,
    AssessmentConflict,
    AssessmentJournal,
    AssessmentLedger,
    AssessmentUsage,
    DeviceAssessments,
    UnknownDevice,
    summary,
)
from plowshare_privacy.device_dossier import DeviceDossier
from plowshare_privacy.device_facts import DeviceFacts
from plowshare_privacy.device_inventory import DeviceCard, DeviceInventory
from plowshare_privacy.dossier_port import RetainedDossier

TV = "mac:02:00:00:00:00:01"
LAMP = "mac:02:00:00:00:00:02"
NOW = "2026-11-11T09:00:00Z"
Stage = Literal[
    "extract", "derive", "embed", "summarise", "summary_embed", "autoTag", "tagGroups"
]
StepState = Literal[
    "running", "failed", "blocked", "pending", "ready", "skipped", "cancelled"
]


def lamp(*, queries: int = 0) -> DeviceCard:
    who = replace(
        identity(), address="10.1.2.11", device_id=LAMP, mac=LAMP.removeprefix("mac:")
    )
    value = card(
        who=who,
        dns=activity([("oztam.com.au", queries, 0)]) if queries else None,
    )
    return replace(value, address="10.1.2.11", name="Hallway lamp")


def tv(*, adverts: bool = False) -> DeviceCard:
    domains = [("oztam.com.au", 14, 0), ("netflix.com", 120, 0)]
    if adverts:
        domains.append(("doubleclick.net", 9, 9))
    return card(dns=activity(domains))


class Dossiers:
    """Records each retention; every dossier gets a fresh revision."""

    def __init__(self) -> None:
        self.retained: list[DeviceDossier] = []
        self.revisions: list[str] = []
        self.zones: list[str] = []

    async def retain(
        self, dossier: DeviceDossier, zone: str = "UTC"
    ) -> RetainedDossier:
        self.zones.append(zone)
        self.retained.append(dossier)
        self.revisions.append(str(uuid4()))
        return RetainedDossier(
            dossier.identity.device_id, self.revisions[-1], str(uuid4()), True
        )

    async def reconcile(self) -> None:
        return None


class Runs:
    """In-memory orchestration and report catalogue; a lost reply still commits."""

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.starts: list[tuple[str, AssessmentRequest]] = []
        self.admitted: dict[str, str] = {}
        self.states: dict[str, AssessmentRun] = {}
        self.newest: dict[str, str] = {}
        self.status: dict[str, ReportStatus] = {}
        # Report revisions Plowshare has not finished processing.
        self.processing: set[str] = set()
        self.texts: dict[str, str] = {}
        self.finalised: list[tuple[str, str]] = []
        self.start_error: Exception | None = None
        self.finalise_error: Exception | None = None
        self.receipt_error: Exception | None = None

    async def newest_report(self, device_id: str) -> str | None:
        self.calls.append("newest_report")
        return self.newest.get(device_id)

    async def start(self, request_id: str, request: AssessmentRequest) -> str:
        self.calls.append("start")
        self.starts.append((request_id, request))
        if isinstance(self.start_error, TransportError) and (
            self.start_error.delivery == Delivery.NOT_SUBMITTED
        ):
            raise self.start_error
        if isinstance(self.start_error, Refusal):
            raise self.start_error
        run = "orc_" + request_id[:8]
        self.admitted[request_id] = run
        self.states[run] = AssessmentRun("running", None)
        if self.start_error is not None:
            raise self.start_error
        return run

    async def receipt(self, request_id: str) -> str:
        self.calls.append("receipt")
        if self.receipt_error is not None:
            raise self.receipt_error
        if request_id not in self.admitted:
            raise Refusal("NOT_FOUND", "No admission receipt")
        return self.admitted[request_id]

    async def outcome(self, run_id: str) -> AssessmentRun:
        self.calls.append("outcome")
        return self.states[run_id]

    async def finalise(self, request_id: str, revision: str) -> None:
        self.calls.append("finalise")
        self.finalised.append((request_id, revision))
        if isinstance(self.finalise_error, Refusal):
            raise self.finalise_error
        if isinstance(self.finalise_error, TransportError) and (
            self.finalise_error.delivery == Delivery.NOT_SUBMITTED
        ):
            raise self.finalise_error
        self.status[revision] = "final"
        if self.finalise_error is not None:
            raise self.finalise_error

    async def report_state(self, device_id: str, revision: str) -> ReportState:
        self.calls.append("report_state")
        return ReportState(self.status[revision], revision not in self.processing)

    async def read_report(self, device_id: str, revision: str) -> str:
        self.calls.append("read_report")
        return self.texts[revision]

    def finish(
        self,
        request: AssessmentRequest,
        *,
        level: str = "worth_checking",
        state: str = "finished",
    ) -> str:
        """Complete the run for ``request`` and write its report revision."""
        request_id = next(rid for rid, value in self.starts if value == request)
        run = self.admitted[request_id]
        if state != "finished":
            self.states[run] = AssessmentRun(state, None)
            return ""
        revision = str(uuid4())
        self.newest[request.device_id] = revision
        self.status[revision] = "draft"
        self.texts[revision] = "# Lounge TV: privacy assessment\n\nAssessed."
        self.states[run] = AssessmentRun(
            "finished",
            json.dumps(
                {
                    "version": 1,
                    "device_id": request.device_id,
                    "dossier": request.dossier,
                    "report": {"revision": revision, "resource": str(uuid4())},
                    "review": "accepted",
                    "corrected": False,
                    "concerns": [{"id": "c1", "level": level, "verdict": "holds"}],
                }
            ),
        )
        return revision


class AssessmentTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.runs = Runs()
        self.dossiers = Dossiers()
        self.cards: tuple[DeviceCard, ...] = (tv(), lamp())
        self.facts: dict[str, DeviceFacts] = {}
        self.now = NOW
        self.service = self.restart()

    def restart(self) -> DeviceAssessments:
        return DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            facts=self.facts.get,
            clock=lambda: self.now,
        )

    def learn(self, device: str, updated_at: str, *, described: bool = True) -> None:
        """Give ``device`` retained facts whose content last changed at ``updated_at``."""
        value = replace(
            facts(described=described, status="searched" if described else "x"),
            device_id=device,
            described_at=updated_at,
            updated_at=updated_at,
        )
        self.facts[device] = value

    async def finished(self, device: str = TV, **kwargs: str) -> str:
        """Start and complete one assessment; returns its report revision."""
        await self.service.assess(device)
        request = self.runs.starts[-1][1]
        revision = self.runs.finish(request, **kwargs)
        await self.service.poll()
        return revision

    async def test_assess_retains_the_dossier_and_starts_one_request(self) -> None:
        view = await self.service.assess(TV)
        self.assertEqual(view.state, "running")
        self.assertEqual(len(self.dossiers.retained), 1)
        self.assertEqual(self.dossiers.retained[0].identity.device_id, TV)
        request_id, request = self.runs.starts[0]
        self.assertEqual(view.request_id, request_id)
        self.assertEqual(view.run_id, "orc_" + request_id[:8])
        self.assertEqual(request.dossier, self.dossiers.revisions[0])
        self.assertIsNone(request.previous)
        self.assertEqual(request.requested_at, NOW)
        self.assertEqual(self.runs.calls, ["newest_report", "start"])
        self.assertEqual(self.service.journal.path.stat().st_mode & 0o777, 0o600)

    async def test_only_one_assessment_runs_at_a_time(self) -> None:
        await self.service.assess(TV)
        with self.assertRaises(AssessmentConflict):
            await self.service.assess(LAMP)
        with self.assertRaises(AssessmentConflict):
            await self.service.assess(TV)
        self.assertEqual(len(self.runs.starts), 1)
        self.assertFalse(self.service.view(LAMP).can_assess)
        self.runs.finish(self.runs.starts[0][1])
        await self.service.poll()
        self.assertEqual(self.service.view(TV).state, "review")
        self.assertEqual((await self.service.assess(LAMP)).state, "running")

    async def test_review_blocks_a_new_run_for_that_device(self) -> None:
        revision = await self.finished()
        view = self.service.view(TV)
        self.assertEqual((view.state, view.report), ("review", revision))
        self.assertFalse(view.can_assess)
        self.assertTrue(view.can_accept and view.can_dismiss)
        with self.assertRaises(AssessmentConflict):
            await self.service.assess(TV)
        self.service.set_background_limit(4)
        self.cards = (tv(adverts=True), lamp())
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 1)

    async def test_failed_and_capped_runs_show_the_run_and_retry_with_a_new_request(
        self,
    ) -> None:
        await self.finished(state="failed")
        failed = self.service.view(TV)
        self.assertEqual(failed.state, "failed")
        self.assertEqual(failed.run_id, "orc_" + self.runs.starts[0][0][:8])
        self.assertIn("No report was written", failed.detail or "")
        self.assertTrue(failed.can_assess)
        self.assertIsNone(failed.report)
        await self.finished(state="capped")
        capped = self.service.view(TV)
        self.assertEqual(capped.state, "failed")
        self.assertIn("cap", capped.detail or "")
        self.assertNotEqual(self.runs.starts[0][0], self.runs.starts[1][0])

    async def test_an_invalid_result_fails_without_recording_a_report(self) -> None:
        await self.service.assess(TV)
        run = self.runs.admitted[self.runs.starts[0][0]]
        foreign = AssessmentRequest(LAMP, str(uuid4()), None, NOW)
        self.runs.starts.append((str(uuid4()), foreign))
        self.runs.admitted[self.runs.starts[-1][0]] = run
        self.runs.finish(foreign)
        await self.service.poll()
        view = self.service.view(TV)
        self.assertEqual(view.state, "failed")
        self.assertIsNone(view.report)
        self.assertIn(run, view.detail or "")

    async def test_lost_start_reply_is_settled_by_receipt_only(self) -> None:
        self.runs.start_error = TransportError(Delivery.UNKNOWN, "lost reply")
        view = await self.service.assess(TV)
        self.assertEqual((view.state, view.pending), ("running", "admission"))
        self.assertIsNone(view.run_id)
        self.runs.start_error = None
        self.runs.receipt_error = Refusal("NOT_FOUND", "not yet")
        await self.service.poll()
        self.assertEqual(self.service.view(TV).pending, "admission")
        self.runs.receipt_error = None
        restarted = self.restart()
        await restarted.poll()
        settled = restarted.view(TV)
        self.assertEqual(settled.state, "running")
        self.assertIsNone(settled.pending)
        self.assertEqual(settled.run_id, "orc_" + self.runs.starts[0][0][:8])
        self.assertEqual(len(self.runs.starts), 1)
        self.assertEqual(self.runs.calls.count("start"), 1)
        self.assertEqual(self.runs.calls.count("receipt"), 2)

    async def test_refused_or_unsent_start_fails_and_can_be_retried(self) -> None:
        self.runs.start_error = Refusal("FORBIDDEN", "No orchestration grant")
        refused = await self.service.assess(TV)
        self.assertEqual(refused.state, "failed")
        self.assertIn("No orchestration grant", refused.detail or "")
        self.runs.start_error = TransportError(Delivery.NOT_SUBMITTED, "offline")
        unsent = await self.service.assess(TV)
        self.assertEqual(unsent.state, "failed")
        self.assertIn("not sent", unsent.detail or "")
        self.runs.start_error = None
        self.assertEqual((await self.service.assess(TV)).state, "running")
        self.assertEqual(len({rid for rid, _ in self.runs.starts}), 3)

    async def test_accept_finalises_the_report_revision_with_a_retained_request(
        self,
    ) -> None:
        revision = await self.finished()
        view = await self.service.accept(TV)
        self.assertEqual((view.state, view.decided_at), ("accepted", NOW))
        [(request_id, finalised)] = self.runs.finalised
        self.assertEqual(finalised, revision)
        self.assertEqual(self.restart().ledger.entries[0].finalise_id, request_id)
        self.assertTrue(view.can_assess)
        with self.assertRaises(AssessmentConflict):
            await self.service.accept(TV)

    async def test_a_refused_finalise_stays_in_review_with_an_actionable_reason(
        self,
    ) -> None:
        await self.finished()
        self.runs.finalise_error = Refusal(
            "FORBIDDEN", "Write access to this revision is required"
        )
        view = await self.service.accept(TV)
        self.assertEqual(view.state, "review")
        self.assertIsNone(view.pending)
        detail = view.detail or ""
        self.assertIn("Write access to this revision is required", detail)
        self.assertIn("service account", detail)
        self.assertIn("Reports", detail)
        self.assertIn("Check status", detail)
        self.assertTrue(view.can_accept)
        self.assertEqual(len(self.runs.finalised), 1)
        # Finalised in Plowshare by a person: Check status records the acceptance.
        self.runs.status[view.report or ""] = "final"
        checked = await self.service.check(TV)
        self.assertEqual(checked.state, "accepted")
        self.assertEqual(len(self.runs.finalised), 1)

    async def test_an_uncertain_finalise_stays_pending_until_a_status_read(
        self,
    ) -> None:
        revision = await self.finished()
        self.runs.finalise_error = TransportError(Delivery.UNKNOWN, "lost reply")
        self.runs.status[revision] = "draft"
        original = self.runs.finalise

        async def lost(request_id: str, revision: str) -> None:
            self.runs.calls.append("finalise")
            self.runs.finalised.append((request_id, revision))
            raise TransportError(Delivery.UNKNOWN, "lost reply")

        self.runs.finalise = lost  # type: ignore[method-assign]
        view = await self.service.accept(TV)
        self.assertEqual((view.state, view.pending), ("review", "accept"))
        self.assertFalse(view.can_accept or view.can_dismiss or view.can_assess)
        with self.assertRaises(AssessmentConflict):
            await self.service.accept(TV)
        with self.assertRaises(AssessmentConflict):
            self.service.dismiss(TV)
        await self.service.poll()
        self.assertEqual(self.restart().view(TV).pending, "accept")
        self.runs.finalise = original  # type: ignore[method-assign]
        self.runs.status[revision] = "final"
        settled = await self.restart().check(TV)
        self.assertEqual(settled.state, "accepted")
        self.assertEqual(self.runs.calls.count("finalise"), 1)

    async def test_summaries_list_each_device_and_count_live_concerns(self) -> None:
        self.assertEqual(self.service.summaries(), ())
        await self.service.assess(TV)
        running = self.service.summaries()
        self.assertEqual(
            [(s.device_id, s.state, s.potential_breaches) for s in running],
            [(TV, "running", 0)],
        )
        revision = self.runs.finish(self.runs.starts[-1][1], level="potential_breach")
        self.runs.processing.add(revision)
        await self.service.poll()
        (waiting,) = self.service.summaries()
        self.assertEqual((waiting.state, waiting.processing), ("review", True))
        self.assertEqual((waiting.potential_breaches, waiting.worth_checking), (1, 0))
        # A dismissed report no longer counts toward breaches.
        self.service.dismiss(TV)
        (dismissed,) = self.service.summaries()
        self.assertEqual(
            (dismissed.state, dismissed.potential_breaches), ("dismissed", 0)
        )
        self.assertEqual(self.runs.calls.count("read_report"), 0)

    def test_summary_ignores_refuted_concerns_and_settled_failures(self) -> None:
        concerns = (
            ConcernOutcome("c1", "potential_breach", "refuted"),
            ConcernOutcome("c2", "potential_breach", "weakened"),
            ConcernOutcome("c3", "worth_checking", "holds"),
            ConcernOutcome("c4", "expected", "holds"),
        )
        accepted = summary(TV, "accepted", None, False, concerns)
        self.assertEqual((accepted.potential_breaches, accepted.worth_checking), (1, 1))
        failed = summary(TV, "failed", None, False, concerns)
        self.assertEqual((failed.potential_breaches, failed.worth_checking), (0, 0))

    async def test_usage_counts_todays_admissions_against_both_limits(self) -> None:
        self.service.set_background_limit(3)
        self.assertEqual(self.service.usage(), AssessmentUsage(3, 0, 12))
        await self.finished()
        self.assertEqual(self.service.usage().used_today, 1)
        self.now = "2026-11-12T00:00:01Z"
        self.assertEqual(self.service.usage().used_today, 0)

    async def test_accept_waits_until_plowshare_has_processed_the_report(
        self,
    ) -> None:
        await self.service.assess(TV)
        revision = self.runs.finish(self.runs.starts[-1][1])
        self.runs.processing.add(revision)
        await self.service.poll()
        view = self.service.view(TV)
        self.assertEqual(view.state, "review")
        self.assertTrue(view.processing)
        self.assertFalse(view.can_accept)
        # Dismiss stays available while the report is processed.
        self.assertTrue(view.can_dismiss)
        with self.assertRaisesRegex(AssessmentConflict, "being processed"):
            await self.service.accept(TV)
        self.assertEqual(self.runs.finalised, [])
        # Each poll reads the state again until processing is done.
        await self.service.poll()
        self.assertEqual(self.runs.calls.count("report_state"), 2)
        self.runs.processing.clear()
        await self.service.poll()
        ready = self.service.view(TV)
        self.assertFalse(ready.processing)
        self.assertTrue(ready.can_accept)
        # Once processed, polling stops reading this report.
        await self.service.poll()
        self.assertEqual(self.runs.calls.count("report_state"), 3)
        self.assertEqual((await self.service.accept(TV)).state, "accepted")
        self.assertEqual([item[1] for item in self.runs.finalised], [revision])

    async def test_a_report_read_after_restart_is_processing_until_read(
        self,
    ) -> None:
        await self.finished()
        self.assertTrue(self.service.view(TV).can_accept)
        restarted = self.restart()
        # Unknown after a restart: Accept waits for one state read.
        self.assertTrue(restarted.view(TV).processing)
        self.assertFalse(restarted.view(TV).can_accept)
        await restarted.check(TV)
        self.assertTrue(restarted.view(TV).can_accept)

    async def test_finalise_refused_while_processing_says_to_try_again_shortly(
        self,
    ) -> None:
        revision = await self.finished()
        self.runs.finalise_error = Refusal(
            "BAD_REQUEST", "a final report must complete processing first"
        )
        view = await self.service.accept(TV)
        self.assertEqual((view.state, view.pending), ("review", None))
        detail = view.detail or ""
        self.assertIn("still processing", detail)
        self.assertIn("try again shortly", detail)
        self.assertNotIn("service account", detail)
        self.assertTrue(view.processing)
        self.assertFalse(view.can_accept)
        self.assertTrue(view.can_dismiss)
        self.runs.finalise_error = None
        await self.service.poll()
        ready = self.service.view(TV)
        self.assertTrue(ready.can_accept)
        self.assertEqual((await self.service.accept(TV)).state, "accepted")
        self.assertEqual(len(self.runs.finalised), 2)
        self.assertEqual(self.runs.status[revision], "final")

    async def test_dismiss_is_local_and_never_calls_the_server(self) -> None:
        await self.finished()
        before = list(self.runs.calls)
        view = self.service.dismiss(TV)
        self.assertEqual((view.state, view.decided_at), ("dismissed", NOW))
        self.assertEqual(self.runs.calls, before)
        self.assertEqual(self.runs.finalised, [])
        with self.assertRaises(AssessmentConflict):
            await self.service.report(TV)
        with self.assertRaises(AssessmentConflict):
            self.service.dismiss(TV)
        self.assertTrue(self.restart().view(TV).can_assess)

    async def test_reassessment_cites_the_newest_report_revision(self) -> None:
        first = await self.finished()
        await self.service.accept(TV)
        await self.service.assess(TV)
        self.assertEqual(self.runs.starts[-1][1].previous, first)
        self.runs.finish(self.runs.starts[-1][1])
        await self.service.poll()
        self.service.dismiss(TV)
        # A catalogue that no longer shows a known report must not start a twin.
        self.runs.newest.clear()
        with self.assertRaises(AssessmentConflict):
            await self.service.assess(TV)
        self.assertEqual(len(self.runs.starts), 2)

    async def test_device_ids_are_checked_against_the_current_inventory(self) -> None:
        with self.assertRaises(ValueError):
            self.service.view("02:00:00:00:00:01")
        with self.assertRaises(UnknownDevice):
            self.service.view("mac:02:00:00:00:00:09")
        with self.assertRaises(UnknownDevice):
            await self.service.assess("mac:02:00:00:00:00:09")
        self.assertEqual(self.service.view(TV).state, "idle")
        self.assertTrue(self.service.view(TV).can_assess)
        self.assertEqual(self.runs.calls, [])

    async def test_report_text_is_read_only_for_the_current_revision(self) -> None:
        with self.assertRaises(AssessmentConflict):
            await self.service.report(TV)
        revision = await self.finished()
        report = await self.service.report(TV)
        self.assertEqual(report.revision, revision)
        self.assertIn("privacy assessment", report.text)

    async def test_on_demand_admissions_stop_at_the_daily_ceiling(self) -> None:
        for _ in range(DAILY_CEILING):
            await self.finished(state="failed")
        self.assertFalse(self.service.view(TV).can_assess)
        with self.assertRaises(AssessmentConflict):
            await self.service.assess(TV)
        self.now = "2026-11-12T00:00:01Z"
        self.assertEqual((await self.service.assess(TV)).state, "running")

    async def test_background_is_off_by_default_then_bounded_and_requeues_on_change(
        self,
    ) -> None:
        self.assertEqual(self.service.ledger.background_limit, 0)
        await self.service.poll()
        self.assertEqual(self.runs.starts, [])
        self.service.set_background_limit(2)
        await self.service.poll()
        [(_, request)] = self.runs.starts
        # The lamp has no signal, so only the TV is a candidate.
        self.assertEqual(request.device_id, TV)
        self.runs.finish(request)
        await self.service.poll()
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 1)
        self.service.dismiss(TV)
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 1)
        self.cards = (tv(adverts=True), lamp(queries=3))
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 2)
        self.assertEqual(self.runs.starts[1][1].device_id, LAMP)
        self.runs.finish(self.runs.starts[1][1], state="failed")
        await self.service.poll()
        await self.service.poll()
        # The daily limit of two is spent; the changed TV waits for tomorrow.
        self.assertEqual(len(self.runs.starts), 2)
        self.now = "2026-11-12T00:00:01Z"
        await self.service.poll()
        self.assertEqual(self.runs.starts[2][1].device_id, TV)
        with self.assertRaises(ValueError):
            self.service.set_background_limit(DAILY_CEILING + 1)

    async def test_the_dossier_carries_the_devices_retained_facts(self) -> None:
        self.learn(TV, "2026-11-11T08:00:00Z")
        await self.service.assess(TV)
        [dossier] = self.dossiers.retained
        self.assertEqual(dossier.supplemental.described_at, "2026-11-11T08:00:00Z")
        self.assertEqual(dossier.supplemental.descriptions[0].manufacturer, "Sony")

    async def test_the_confirmed_profile_and_household_zone_reach_the_run(
        self,
    ) -> None:
        from plowshare_privacy.device_dossier import OperatorProfile, ProfileMatch

        revision = str(uuid4())
        seen: list[str] = []

        async def profile(card: DeviceCard) -> ProfileMatch:
            seen.append(card.address)
            return ProfileMatch(OperatorProfile(revision, "Television", "HTTPS"))

        service = DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            facts=self.facts.get,
            profile=profile,
            clock=lambda: self.now,
            zone="Australia/Sydney",
        )
        await service.assess(TV)
        [dossier] = self.dossiers.retained
        assert dossier.profile is not None
        self.assertEqual(dossier.profile.revision, revision)
        self.assertEqual(self.dossiers.zones, ["Australia/Sydney"])
        [(_, request)] = self.runs.starts
        encoded = json.loads(request.encode())
        self.assertEqual(encoded["version"], 2)
        self.assertEqual(encoded["time_zone"], "Australia/Sydney")
        # 09:00 UTC on the 11th is 20:00 in Sydney (UTC+11 in November).
        self.assertEqual(encoded["requested_local"], "2026-11-11 20:00 AEDT")
        # The zone is retained with the entry, so a restart rebuilds the same request.
        restarted = DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            zone="UTC",
        )
        rebuilt = restarted._entry(TV)
        assert rebuilt is not None
        self.assertEqual(rebuilt.request().encode(), request.encode())

    async def test_an_address_only_profile_is_named_in_coverage_not_used(
        self,
    ) -> None:
        from plowshare_privacy.device_dossier import ProfileMatch

        async def profile(card: DeviceCard) -> ProfileMatch:
            return ProfileMatch(None, "address_only")

        service = DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            profile=profile,
            clock=lambda: self.now,
        )
        await service.assess(TV)
        [dossier] = self.dossiers.retained
        self.assertIsNone(dossier.profile)
        self.assertTrue(
            any("matches this address only" in line for line in dossier.coverage)
        )

    async def test_an_unreadable_profile_list_starts_nothing(self) -> None:
        async def profile(card: DeviceCard) -> object:
            raise ConnectionError("profiles unavailable")

        service = DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            profile=profile,  # type: ignore[arg-type]
            clock=lambda: self.now,
        )
        with self.assertRaises(ConnectionError):
            await service.assess(TV)
        self.assertEqual(self.dossiers.retained, [])
        self.assertEqual(self.runs.starts, [])

    async def test_the_daily_ceiling_counts_the_household_day(self) -> None:
        sydney = DeviceAssessments(
            AssessmentJournal(self.root),
            self.runs,
            self.dossiers,
            lambda: DeviceInventory(self.cards),
            clock=lambda: self.now,
            zone="Australia/Sydney",
        )
        # 09:00 UTC on the 11th is the evening of the 11th in Sydney; 13:30 UTC
        # is already the 12th there, so the earlier admission no longer counts.
        await sydney.assess(TV)
        self.assertEqual(sydney.usage().used_today, 1)
        self.now = "2026-11-11T13:30:00Z"
        self.assertEqual(sydney.usage().used_today, 0)
        self.service = self.restart()
        self.assertEqual(self.service.usage().used_today, 1)

    async def test_a_ledger_entry_without_a_zone_still_decodes(self) -> None:
        await self.service.assess(TV)
        path = self.root / "device-assessments.json"
        row = json.loads(path.read_text())
        for entry in row["entries"]:
            del entry["time_zone"]
        path.write_text(json.dumps(row))
        legacy = self.restart()._entry(TV)
        assert legacy is not None
        self.assertIsNone(legacy.time_zone)
        self.assertEqual(json.loads(legacy.request().encode())["version"], 1)

    async def test_background_assesses_a_never_assessed_device_with_facts(
        self,
    ) -> None:
        self.service.set_background_limit(3)
        # The lamp has no signal but described itself; the signalled TV goes first.
        self.learn(LAMP, "2026-11-11T08:00:00Z")
        await self.service.poll()
        self.assertEqual(self.runs.starts[0][1].device_id, TV)
        self.runs.finish(self.runs.starts[0][1])
        await self.service.poll()
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 2)
        self.assertEqual(self.runs.starts[1][1].device_id, LAMP)

    async def test_facts_without_anything_learned_do_not_queue_a_device(
        self,
    ) -> None:
        self.cards = (lamp(),)
        self.service.set_background_limit(3)
        self.learn(LAMP, "2026-11-11T08:00:00Z", described=False)
        await self.service.poll()
        self.assertEqual(self.runs.starts, [])

    async def test_changed_facts_requeue_a_settled_device_once(self) -> None:
        self.cards = (lamp(),)
        self.learn(LAMP, "2026-11-11T08:00:00Z")
        self.service.set_background_limit(4)
        await self.service.poll()
        [(_, first)] = self.runs.starts
        self.runs.finish(first)
        await self.service.poll()
        # In review: changed facts do not start another run.
        self.learn(LAMP, "2026-11-11T09:30:00Z")
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 1)
        self.service.dismiss(LAMP)
        # Facts older than the last admission are not a change.
        self.learn(LAMP, "2026-11-11T08:59:00Z")
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 1)
        self.learn(LAMP, "2026-11-11T09:30:00Z")
        self.now = "2026-11-11T10:00:00Z"
        await self.service.poll()
        self.assertEqual(len(self.runs.starts), 2)
        self.assertEqual(self.service.ledger.entries[0].trigger, "background")
        self.runs.finish(self.runs.starts[1][1])
        await self.service.poll()
        self.service.dismiss(LAMP)
        await self.service.poll()
        # The same facts after the new admission do not requeue it again.
        self.assertEqual(len(self.runs.starts), 2)

    async def test_an_unreadable_journal_disables_assessments_and_is_preserved(
        self,
    ) -> None:
        self.service.journal.path.write_text("{}")
        self.service.journal.path.chmod(0o600)
        service = self.restart()
        self.assertIsNotNone(service.error)
        with self.assertRaises(ValueError):
            await service.assess(TV)
        self.assertEqual(self.service.journal.path.read_text(), "{}")
        self.assertEqual(self.runs.calls, [])

    def test_ledger_rejects_two_active_runs(self) -> None:
        entry = {
            "device_id": TV,
            "request_id": str(uuid4()),
            "dossier": str(uuid4()),
            "previous": None,
            "requested_at": NOW,
            "trigger": "operator",
            "signals": ["measurement_dns"],
            "phase": "running",
            "run_id": "orc_1",
            "report": None,
            "resource": None,
            "review": None,
            "concerns": [],
            "finalise_id": None,
            "decided_at": None,
            "detail": None,
            "latest_report": None,
        }
        other = {**entry, "device_id": LAMP, "request_id": str(uuid4())}
        value = {
            "version": 1,
            "background_limit": 0,
            "entries": [entry, other],
            "admissions": [],
        }
        with self.assertRaises(ValueError):
            AssessmentLedger.decode(value)
        AssessmentLedger.decode({**value, "entries": [entry]})
        with self.assertRaises(ValueError):
            AssessmentLedger.decode(
                {**value, "entries": [{**entry, "phase": "review"}]}
            )


def reply(payload: object) -> Reply[object]:
    return Reply("OK", None, payload)


class SdkAssessmentPortTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.config = configuration(Path(directory.name).resolve())
        self.client = Mock(spec=Client)
        self.client.request = AsyncMock()
        self.reader = Mock()
        self.reader.read_source = AsyncMock(return_value="# Report")
        self.port = SdkAssessmentPort(
            lambda: self.client, self.config, lambda: self.reader
        )

    async def test_start_uses_the_assessment_definition_and_request_identity(
        self,
    ) -> None:
        request_id = str(uuid4())
        request = AssessmentRequest(TV, str(uuid4()), None, NOW)
        self.client.request.return_value = reply(
            Mock(request_id=request_id, id="orc_123")
        )
        self.assertEqual(await self.port.start(request_id, request), "orc_123")
        sent = self.client.request.await_args.args[0]
        self.assertIsInstance(sent, OrchestrationStartRequest)
        self.assertEqual(
            (sent.agent, sent.definition, sent.request, sent.request_id),
            (CALLER, DEFINITION, request.encode(), request_id),
        )
        self.client.request.return_value = reply(
            Mock(request_id=str(uuid4()), id="orc_123")
        )
        with self.assertRaises(ValueError):
            await self.port.start(request_id, request)

    async def test_newest_report_reads_only_this_devices_report_resource(
        self,
    ) -> None:
        resource = str(uuid4())
        first, second = str(uuid4()), str(uuid4())

        def row(
            revision: str, ordinal: int, name: str, owner: str = resource
        ) -> InformationRevisionDto:
            return InformationRevisionDto(
                id=revision,
                kind="report",
                source_name=name,
                resource_id=owner,
                ordinal=ordinal,
            )

        name = report_name(TV)
        self.client.request.return_value = reply(
            (
                row(first, 1, name),
                row(second, 2, name),
                row(str(uuid4()), 9, name + "x", str(uuid4())),
            )
        )
        self.assertEqual(await self.port.newest_report(TV), second)
        sent = self.client.request.await_args.args[0]
        self.assertIsInstance(sent, InformationListRequest)
        self.assertEqual(sent.kind, "report")
        self.client.request.return_value = reply(())
        self.assertIsNone(await self.port.newest_report(TV))
        self.client.request.return_value = reply(
            (row(first, 1, name), row(second, 2, name, str(uuid4())))
        )
        with self.assertRaises(ValueError):
            await self.port.newest_report(TV)
        self.client.request.return_value = reply(
            (InformationRevisionDto(id=first, kind="report"),)
        )
        with self.assertRaises(ValueError):
            await self.port.newest_report(TV)

    async def test_finalise_sends_the_retained_request_and_status_is_checked(
        self,
    ) -> None:
        request_id, revision = str(uuid4()), str(uuid4())
        self.client.request.return_value = reply(
            FinalisedInformationDto(finalised=True)
        )
        await self.port.finalise(request_id, revision)
        sent = self.client.request.await_args.args[0]
        self.assertIsInstance(sent, InformationFinaliseRequest)
        self.assertEqual((sent.request_id, sent.revision), (request_id, revision))
        self.assertEqual(sent.scope.project, self.config.project)
        self.client.request.return_value = reply(
            InformationRevisionDto(
                id=revision,
                kind="report",
                report_status="final",
                source_name=report_name(TV),
            )
        )
        # Without processing steps the server is left to decide on finalise.
        self.assertEqual(
            await self.port.report_state(TV, revision), ReportState("final", True)
        )
        self.assertIsInstance(
            self.client.request.await_args.args[0], InformationStatusRequest
        )
        for foreign in (
            InformationRevisionDto(
                id=str(uuid4()), kind="report", report_status="final"
            ),
            InformationRevisionDto(id=revision, kind="source", report_status="final"),
            InformationRevisionDto(
                id=revision,
                kind="report",
                report_status="final",
                source_name=report_name(LAMP),
            ),
            InformationRevisionDto(id=revision, kind="report"),
        ):
            self.client.request.return_value = reply(foreign)
            with self.assertRaises(ValueError):
                await self.port.report_state(TV, revision)

    async def test_report_state_is_processed_when_its_generation_is_ready(
        self,
    ) -> None:
        revision = str(uuid4())
        content = ("extract", "derive", "embed", "summarise", "summary_embed")

        def status(
            steps: tuple[tuple[str, str, int], ...], generation: int | None = 2
        ) -> InformationRevisionDto:
            # A status that omits its generation, as an older server may.
            row = InformationRevisionDto(
                id=revision,
                kind="report",
                report_status="draft",
                source_name=report_name(TV),
                steps=tuple(
                    InformationStepDto(
                        attempt=1,
                        generation=g,
                        stage=cast(Stage, stage),
                        state=cast(StepState, state),
                    )
                    for stage, state, g in steps
                ),
            )
            return row if generation is None else replace(row, generation=generation)

        ready = tuple((stage, "ready", 2) for stage in content)
        for steps, generation, processed in (
            (ready, 2, True),
            # Tagging is not part of what finalise requires.
            ((*ready, ("autoTag", "running", 2)), 2, True),
            (ready[:-1] + (("summary_embed", "running", 2),), 2, False),
            (ready[:-1], 2, False),
            # Ready stages of an earlier generation do not count.
            (tuple((stage, "ready", 1) for stage in content), 2, False),
            (ready, None, True),
            (
                (*tuple((s, "ready", 1) for s in content), ("extract", "pending", 2)),
                None,
                False,
            ),
        ):
            self.client.request.return_value = reply(status(steps, generation))
            self.assertEqual(
                await self.port.report_state(TV, revision),
                ReportState("draft", processed),
                (steps, generation),
            )

    async def test_outcome_refuses_a_foreign_run(self) -> None:
        view = Mock(
            id="orc_1",
            project=self.config.project,
            definition=DEFINITION,
            caller_agent=CALLER,
            state="finished",
            result="{}",
        )
        self.client.request.return_value = reply(Mock(orchestration=view))
        self.assertEqual(
            await self.port.outcome("orc_1"), AssessmentRun("finished", "{}")
        )
        view.definition = "profile_network_device"
        with self.assertRaises(ValueError):
            await self.port.outcome("orc_1")


if __name__ == "__main__":
    unittest.main()
