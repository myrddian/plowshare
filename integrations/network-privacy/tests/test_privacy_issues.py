"""Shared issues remain source-backed, scoped and advisory; uncertain writes never replay."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from aiohttp.test_utils import TestClient, TestServer
from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    InformationAdmissionDto,
    InformationListRequest,
    InformationReviseRequest,
    InformationRevisionDto,
    InformationUploadRequest,
)
from support import CountingCollector, FixturePort, MemoryReceipts, configuration
from test_device_profiles import STAMP, TOKEN, Documents

from plowshare_privacy.device_profiles import (
    DeviceProfile,
    DeviceProfiles,
    FileProfileIntents,
    ProfileFields,
)
from plowshare_privacy.issue_port import SdkIssuePort
from plowshare_privacy.issue_records import (
    FileIssueIntents,
    IssueIntent,
    IssueVersion,
    PrivacyIssues,
    source_name,
)
from plowshare_privacy.privacy_issues import (
    Citation,
    IssueFields,
    IssueLink,
    Mitigation,
    PrivacyIssue,
)
from plowshare_privacy.web import application
from plowshare_privacy.worker import ReconciliationRequired, Worker


def fields() -> IssueFields:
    """Synthetic advice, never a claim about a real model, domain or destination."""
    return IssueFields(
        "Synthetic optional telemetry",
        "Fixture issue with no real vulnerability claim",
        "Fixture model",
        "Unknown; confirm on device",
        "Optional analytics enabled",
        "2026-01-01",
        (Citation("https://vendor.example.test/privacy", None),),
        Mitigation(
            "firewall",
            "outbound",
            "block",
            "linked_device",
            "192.0.2.0/24",
            "tcp",
            (443,),
            "Fixture source describes this exact endpoint range",
            "May disable fixture analytics; test updates and streaming",
            "Compare permitted flow records before/after and test essential functions",
            "Remove the device-specific rule and confirm functionality",
        ),
    )


class IssueDocuments:
    def __init__(self) -> None:
        self.rows: list[IssueVersion] = []
        self.sources: dict[str, str] = {}
        self.writes = 0
        self.lost = False

    async def versions(self) -> tuple[IssueVersion, ...]:
        return tuple(self.rows)

    async def read(self, revision: str) -> str:
        return self.sources[revision]

    async def save(self, issue: PrivacyIssue, previous: str | None) -> tuple[str, str]:
        self.writes += 1
        revision = str(uuid4())
        old = [v for v in self.rows if v.issue_id == issue.issue_id]
        resource = old[-1].resource if old else str(uuid4())
        self.rows.append(
            IssueVersion(
                issue.issue_id,
                revision,
                resource,
                len(old) + 1,
                "fixture-service",
                STAMP,
            )
        )
        self.sources[revision] = issue.markdown()
        if self.lost:
            raise TransportError(Delivery.UNKNOWN, "Lost response")
        return revision, resource


class PrivacyIssuesTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.config = configuration(self.root)
        self.remote = IssueDocuments()
        self.intents = FileIssueIntents(
            self.root, self.config.project, self.config.collector
        )
        self.issues = PrivacyIssues(self.remote, self.intents)
        self.identity, self.request = str(uuid4()), str(uuid4())
        self.fields = fields()
        self.receipts = MemoryReceipts()
        self.profile_remote = Documents()
        self.profiles = DeviceProfiles(
            self.profile_remote,
            FileProfileIntents(self.root, self.config.project, self.config.collector),
            self.receipts,
            lambda: ("10.1.2.10", "10.1.2.11"),
            self.issues,
        )
        self.profile_fields = ProfileFields(
            "Fixture TV",
            "Operator",
            "Fixture",
            "Media",
            "HTTPS",
            "Identity confirmed separately",
            ("10.1.2.10",),
        )

    async def first(self) -> str:
        receipt = await self.issues.save(self.identity, self.request, None, self.fields)
        assert receipt.revision is not None
        return receipt.revision

    async def test_one_issue_can_link_two_devices_and_pins_old_plan_after_revision(
        self,
    ) -> None:
        revision = await self.first()
        link = IssueLink(
            self.identity,
            revision,
            "potentially_affected",
            "proposed",
            "Firmware has not been confirmed",
            (),
        )
        identity = str(uuid4())
        first = await self.profiles.save(
            identity, str(uuid4()), None, replace(self.profile_fields, issues=(link,))
        )
        await self.profiles.save(
            str(uuid4()),
            str(uuid4()),
            None,
            replace(
                self.profile_fields,
                name="Second fixture",
                addresses=("10.1.2.11",),
                issues=(link,),
            ),
        )
        changed = replace(
            self.fields, mitigation=replace(self.fields.mitigation, ports=(8443,))
        )
        second = await self.issues.save(self.identity, str(uuid4()), revision, changed)
        self.assertNotEqual(second.revision, revision)
        self.assertEqual(
            (await self.profiles.list())[0].profile.fields.issues[0].revision, revision
        )
        self.assertIn(
            "Destination ports: 443", await self.issues.read(self.identity, revision)
        )
        self.assertEqual(len((await self.issues.list())[0].versions), 2)
        assert first.revision is not None
        self.assertIn(revision, await self.profiles.read(identity, first.revision))

    async def test_mitigation_progress_retains_history_and_requires_result_for_verified(
        self,
    ) -> None:
        revision = await self.first()
        identity = str(uuid4())
        link = IssueLink(
            self.identity,
            revision,
            "potentially_affected",
            "accepted",
            "Reviewed impact",
            (),
        )
        first = await self.profiles.save(
            identity, str(uuid4()), None, replace(self.profile_fields, issues=(link,))
        )
        applied = replace(
            link,
            mitigation_status="applied",
            notes="Operator installed the exact rule; streaming test passed",
        )
        second = await self.profiles.save(
            identity,
            str(uuid4()),
            first.revision,
            replace(self.profile_fields, issues=(applied,)),
        )
        verified = replace(
            applied,
            applicability="mitigated",
            mitigation_status="verified",
            notes="Operator reviewed firewall counters and tested updates; this is a reported result",
        )
        third = await self.profiles.save(
            identity,
            str(uuid4()),
            second.revision,
            replace(self.profile_fields, issues=(verified,)),
        )
        self.assertEqual(len((await self.profiles.list())[0].versions), 3)
        assert first.revision is not None and third.revision is not None
        self.assertIn("accepted", await self.profiles.read(identity, first.revision))
        self.assertIn("verified", await self.profiles.read(identity, third.revision))
        with self.assertRaises(ValueError):
            replace(link, notes="", mitigation_status="verified")
        with self.assertRaises(ValueError):
            replace(link, applicability="mitigated", mitigation_status="applied")

    async def test_foreign_issue_and_evidence_links_refuse_before_profile_write(
        self,
    ) -> None:
        revision = await self.first()
        for link in (
            IssueLink(str(uuid4()), revision, "unresolved", "proposed", "", ()),
            IssueLink(self.identity, str(uuid4()), "unresolved", "proposed", "", ()),
            IssueLink(
                self.identity, revision, "unresolved", "proposed", "", (str(uuid4()),)
            ),
        ):
            with self.assertRaises(ValueError):
                await self.profiles.save(
                    str(uuid4()),
                    str(uuid4()),
                    None,
                    replace(self.profile_fields, issues=(link,)),
                )
        self.assertEqual(self.profile_remote.writes, 0)

    async def test_legacy_profile_and_pending_intent_remain_readable(self) -> None:
        legacy = DeviceProfile(
            1, str(uuid4()), str(uuid4()), STAMP, self.profile_fields
        )
        source = legacy.markdown()
        self.assertNotIn('"issues"', source)
        self.assertEqual(DeviceProfile.read(source).markdown(), source)
        from plowshare_privacy.device_profiles import ProfileIntent

        intent = ProfileIntent(
            legacy.write_id, legacy.profile_id, None, source, "pending"
        )
        journal = FileProfileIntents(
            self.root, self.config.project, self.config.collector
        )
        journal.save(intent)
        self.assertEqual(journal.all()[0], intent)
        row = asdict(self.profile_fields)
        del row["issues"]
        self.assertEqual(ProfileFields.decode(json.loads(json.dumps(row))).issues, ())

    async def test_lost_issue_write_restart_and_exact_positive_reconciliation(
        self,
    ) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.first()
        restarted = PrivacyIssues(
            self.remote,
            FileIssueIntents(self.root, self.config.project, self.config.collector),
        )
        self.assertEqual(
            (
                await restarted.save(self.identity, self.request, None, self.fields)
            ).phase,
            "pending",
        )
        with self.assertRaises(ReconciliationRequired):
            await restarted.save(str(uuid4()), str(uuid4()), None, self.fields)
        await restarted.reconcile()
        self.assertEqual(restarted.writes()[0].phase, "confirmed")
        self.assertEqual(self.remote.writes, 1)
        self.assertEqual(self.intents.path.stat().st_mode & 0o777, 0o600)

    async def test_missing_different_or_ambiguous_revision_remains_fenced(self) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.first()
        row = self.remote.rows[0]
        original = self.remote.sources[row.revision]
        self.remote.sources[row.revision] = PrivacyIssue(
            1, self.identity, str(uuid4()), STAMP, self.fields
        ).markdown()
        with self.assertRaises(ReconciliationRequired):
            await self.issues.reconcile()
        self.remote.sources[row.revision] = original
        duplicate = str(uuid4())
        self.remote.rows.append(replace(row, revision=duplicate, ordinal=2))
        self.remote.sources[duplicate] = original
        with self.assertRaises(ReconciliationRequired):
            await self.issues.reconcile()
        self.assertEqual(self.issues.writes()[0].phase, "pending")
        self.assertEqual(self.remote.writes, 1)

    async def test_stale_head_changed_request_and_terminal_receipt_are_refused(
        self,
    ) -> None:
        revision = await self.first()
        with self.assertRaises(ReconciliationRequired):
            await self.issues.save(self.identity, str(uuid4()), None, self.fields)
        with self.assertRaises(ValueError):
            await self.issues.save(
                self.identity,
                self.request,
                None,
                replace(self.fields, title="Different change"),
            )
        with self.assertRaises(ValueError):
            self.intents.save(
                replace(self.issues.writes()[0], phase="pending", revision=None)
            )
        with self.assertRaises(ValueError):
            FileIssueIntents(self.root, "foreign-project", self.config.collector).all()
        self.assertEqual(self.remote.writes, 1)
        self.assertIsNotNone(revision)

    async def test_known_not_submitted_and_bad_admission_do_not_report_success(
        self,
    ) -> None:
        with patch.object(
            self.remote,
            "save",
            AsyncMock(side_effect=TransportError(Delivery.NOT_SUBMITTED, "Not sent")),
        ):
            with self.assertRaises(TransportError):
                await self.first()
        self.assertEqual(self.issues.writes()[0].phase, "refused")
        revision = await self.issues.save(
            self.identity, str(uuid4()), None, self.fields
        )
        with patch.object(
            self.remote, "save", AsyncMock(return_value=(str(uuid4()), str(uuid4())))
        ):
            with self.assertRaises(ValueError):
                await self.issues.save(
                    self.identity, str(uuid4()), revision.revision, self.fields
                )
        self.assertEqual(self.issues.writes()[-1].phase, "pending")

    def test_issue_schema_citations_and_exact_canonical_document(self) -> None:
        issue = PrivacyIssue(1, self.identity, self.request, STAMP, self.fields)
        self.assertEqual(PrivacyIssue.read(issue.markdown()), issue)
        for source in (
            issue.markdown() + "Extra",
            issue.markdown().replace("## Sources", "## Altered"),
        ):
            with self.assertRaises(ValueError):
                PrivacyIssue.read(source)
        for row in (
            {**asdict(self.fields), "unknown": True},
            {**asdict(self.fields), "sources": []},
            {**asdict(self.fields), "checked_on": "bad-date"},
        ):
            with self.assertRaises(ValueError):
                IssueFields.decode(json.loads(json.dumps(row)))
        for url in (
            "http://source.example.test",
            "javascript:alert(1)",
            "https://user:secret@source.example.test",
            "https://source.example.test/a b",
            "https://source.example.test:bad",
        ):
            with self.assertRaises(ValueError):
                Citation(url, None)
        with self.assertRaises(ValueError):
            replace(
                issue,
                fields=replace(
                    self.fields,
                    description="😀" * 1024,
                    affected_models="😀" * 1024,
                    firmware="😀" * 1024,
                ),
            ).markdown()

    def test_firewall_directions_protocol_ports_and_pihole_semantics(self) -> None:
        rule = self.fields.mitigation
        inbound = replace(
            rule, direction="inbound", source="internet", destination="linked_device"
        )
        self.assertEqual(inbound.ports, (443,))
        replace(rule, direction="lateral", destination="10.0.0.0/24")
        replace(rule, destination="192.0.2.10")
        replace(rule, protocol="any", ports=())
        replace(
            rule,
            mechanism="pihole",
            destination="telemetry.example.test",
            protocol="not_applicable",
            ports=(),
        )
        for changes in (
            {"direction": "inbound"},
            {"direction": "lateral", "destination": "internet"},
            {"protocol": "any"},
            {"ports": ()},
            {"ports": (True,)},
            {"ports": (443, 443)},
            {"ports": (70000,)},
            {"destination": "10.0.0.1/24"},
            {"source": "internet"},
            {"mechanism": "pihole", "destination": "telemetry.example.test"},
        ):
            with self.assertRaises(ValueError):
                Mitigation.decode(json.loads(json.dumps({**asdict(rule), **changes})))

    def test_journal_reserves_capacity_for_terminal_receipt_before_transport(
        self,
    ) -> None:
        issue = PrivacyIssue(1, self.identity, self.request, STAMP, self.fields)
        intent = IssueIntent(
            self.request, self.identity, None, issue.markdown(), "pending"
        )
        self.intents.save(intent)
        size = self.intents.path.stat().st_size
        self.intents.path.unlink()
        with patch("plowshare_privacy.issue_records.MAX_FILE_BYTES", size + 63):
            with self.assertRaises(ReconciliationRequired):
                self.intents.save(intent)
        self.assertFalse(self.intents.path.exists())
        with patch("plowshare_privacy.issue_records.MAX_FILE_BYTES", size + 64):
            self.intents.save(intent)
            self.intents.save(replace(intent, phase="confirmed", revision=str(uuid4())))
        self.assertEqual(self.intents.all()[0].phase, "confirmed")

    async def test_issue_catalogue_refuses_wrong_ordinals_and_incomplete_history(
        self,
    ) -> None:
        client = AsyncMock(spec=Client)
        port = SdkIssuePort(client, self.config, AsyncMock(spec=FixturePort))
        row = InformationRevisionDto(
            id=str(uuid4()),
            resource_id=str(uuid4()),
            source_name=source_name(self.identity),
            kind="source",
            ordinal=1.5,
            author="fixture",
            created_at=STAMP,
        )
        client.request.return_value = Reply("OK", None, (row,))
        with self.assertRaises(ValueError):
            await port.versions()
        client.request.return_value = Reply(
            "OK",
            None,
            tuple(replace(row, id=str(uuid4()), ordinal=1) for _ in range(100)),
        )
        with self.assertRaises(ValueError):
            await port.versions()
        self.assertEqual(client.request.call_args.args[0].offset, 300)

    async def test_only_device_evidence_can_be_attached_and_survives_history_window(
        self,
    ) -> None:
        from support import event

        port = FixturePort(self.config)
        worker = Worker(
            self.config, port, self.receipts, CountingCollector(self.config)
        )
        port.events["schedule.due"] = [event()]
        await worker.poll()
        receipt = self.receipts.all()[0]
        assert receipt.revision is not None and receipt.evidence is not None
        address = receipt.evidence.snapshot.tcp[0].address
        self.profiles.targets = lambda: (address,)
        revision = await self.first()
        link = IssueLink(
            self.identity,
            revision,
            "potentially_affected",
            "proposed",
            "",
            (receipt.revision,),
        )
        profile_id = str(uuid4())
        first = await self.profiles.save(
            profile_id,
            str(uuid4()),
            None,
            replace(self.profile_fields, addresses=(address,), issues=(link,)),
        )
        self.receipts.rows.clear()
        updated = await self.profiles.save(
            profile_id,
            str(uuid4()),
            first.revision,
            replace(
                self.profile_fields,
                addresses=(address,),
                issues=(
                    replace(link, notes="Earlier retained evidence still referenced"),
                ),
            ),
        )
        self.assertEqual(updated.phase, "confirmed")

    async def test_sdk_uses_only_project_scope_and_existing_upload_revise(self) -> None:
        client = AsyncMock(spec=Client)
        reader = AsyncMock(spec=FixturePort)
        port = SdkIssuePort(client, self.config, reader)
        revision, resource = str(uuid4()), str(uuid4())
        client.request.return_value = Reply(
            "ACCEPTED",
            None,
            InformationAdmissionDto(created=True, resource=resource, revision=revision),
        )
        issue = PrivacyIssue(1, self.identity, self.request, STAMP, self.fields)
        await port.save(issue, None)
        upload = client.request.call_args.args[0]
        self.assertIsInstance(upload, InformationUploadRequest)
        self.assertEqual(upload.scope.project, self.config.project)
        self.assertFalse(upload.scope.include_shared)
        self.assertEqual(upload.name, source_name(self.identity))
        await port.save(issue, revision)
        self.assertIsInstance(
            client.request.call_args.args[0], InformationReviseRequest
        )
        client.request.return_value = Reply(
            "OK",
            None,
            (
                InformationRevisionDto(
                    id=revision,
                    resource_id=resource,
                    source_name=source_name(self.identity),
                    kind="source",
                    ordinal=1,
                    author="fixture",
                    created_at=STAMP,
                ),
            ),
        )
        self.assertEqual((await port.versions())[0].issue_id, self.identity)
        self.assertIsInstance(client.request.call_args.args[0], InformationListRequest)
        self.assertFalse(client.request.call_args.args[0].scope.include_shared)
        await port.read(revision)
        reader.read_source.assert_awaited_once_with(revision)

    async def test_http_authorization_csrf_uncertainty_and_foreign_read(self) -> None:
        worker = Worker(
            self.config,
            FixturePort(self.config),
            self.receipts,
            CountingCollector(self.config),
        )
        client = TestClient(
            TestServer(
                application(worker, TOKEN, profiles=self.profiles, issues=self.issues)
            )
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        payload = {
            "issue_id": self.identity,
            "request_id": self.request,
            "expected_revision": None,
            "fields": json.loads(json.dumps(asdict(self.fields))),
        }
        self.assertEqual((await client.post("/api/issues", json=payload)).status, 401)
        headers = {"Authorization": "Bearer " + TOKEN}
        await client.post("/api/session", headers=headers, json={})
        self.assertEqual(
            (
                await client.post(
                    "/api/issues",
                    headers={"Origin": "https://foreign.invalid"},
                    json=payload,
                )
            ).status,
            403,
        )
        self.assertEqual(
            (
                await client.post(
                    "/api/issues", headers=headers, json={**payload, "extra": True}
                )
            ).status,
            400,
        )
        self.remote.lost = True
        self.assertEqual(
            (await client.post("/api/issues", headers=headers, json=payload)).status,
            503,
        )
        self.assertEqual(
            (await client.post("/api/issues", headers=headers, json=payload)).status,
            202,
        )
        self.assertEqual(self.remote.writes, 1)
        self.assertEqual(
            (await (await client.get("/api/issue-intents", headers=headers)).json())[0][
                "phase"
            ],
            "pending",
        )
        self.assertEqual(
            (
                await client.post("/api/issues/reconcile", headers=headers, json={})
            ).status,
            200,
        )
        self.assertEqual(
            (
                await client.get(
                    f"/api/issues/{self.identity}/{str(uuid4())}", headers=headers
                )
            ).status,
            400,
        )
        self.assertEqual((await client.get("/api/issues", headers=headers)).status, 200)
