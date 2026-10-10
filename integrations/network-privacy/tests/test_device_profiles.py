"""Profile context uses project Information; uncertainty never permits resubmission."""

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

from plowshare_privacy.device_profiles import (
    DeviceProfile,
    DeviceProfiles,
    FileProfileIntents,
    ProfileFields,
    ProfileVersion,
    source_name,
)
from plowshare_privacy.profile_port import SdkProfilePort
from plowshare_privacy.web import application
from plowshare_privacy.worker import ReconciliationRequired, Worker

STAMP = "2026-01-01T00:00:00Z"
TOKEN = "fixture-private-dashboard-token-12345"


class Documents:
    def __init__(self) -> None:
        self.rows: list[ProfileVersion] = []
        self.sources: dict[str, str] = {}
        self.writes = 0
        self.lost = False

    async def versions(self) -> tuple[ProfileVersion, ...]:
        return tuple(self.rows)

    async def read(self, revision: str) -> str:
        return self.sources[revision]

    async def save(
        self, profile: DeviceProfile, previous: str | None
    ) -> tuple[str, str]:
        self.writes += 1
        revision = str(uuid4())
        old = [v for v in self.rows if v.profile_id == profile.profile_id]
        resource = old[-1].resource if old else str(uuid4())
        self.rows.append(
            ProfileVersion(
                profile.profile_id,
                revision,
                resource,
                len(old) + 1,
                "fixture-service",
                STAMP,
            )
        )
        self.sources[revision] = profile.markdown()
        if self.lost:
            raise TransportError(Delivery.UNKNOWN, "Lost response")
        return revision, resource


class DeviceProfilesTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.config = configuration(self.root)
        self.receipts = MemoryReceipts()
        self.remote = Documents()
        self.intents = FileProfileIntents(
            self.root, self.config.project, self.config.collector
        )
        self.profiles = DeviceProfiles(
            self.remote, self.intents, self.receipts, lambda: ("10.1.2.10", "10.1.2.11")
        )
        self.identity, self.request = str(uuid4()), str(uuid4())
        self.fields = ProfileFields(
            "Living room TV",
            "Household",
            "Operator supplied",
            "Streaming",
            "TCP 443; updates may add DNS destinations",
            "Observed MAC can change",
            ("10.1.2.10",),
        )

    async def test_create_edit_retains_stable_id_and_old_document(self) -> None:
        first = await self.profiles.save(self.identity, self.request, None, self.fields)
        self.assertEqual(first.phase, "confirmed")
        assert first.revision is not None
        source = await self.profiles.read(self.identity, first.revision)
        self.assertIn("## Expected services and behaviour", source)
        self.assertEqual(DeviceProfile.read(source).fields, self.fields)
        updated = replace(self.fields, notes="Operator checked the firmware update")
        second = await self.profiles.save(
            self.identity, str(uuid4()), first.revision, updated
        )
        views = await self.profiles.list()
        self.assertEqual(views[0].profile.profile_id, self.identity)
        self.assertEqual(len(views[0].versions), 2)
        self.assertEqual(views[0].revision, second.revision)
        self.assertEqual(
            await self.profiles.read(self.identity, first.revision), source
        )
        self.assertEqual(self.intents.path.stat().st_mode & 0o777, 0o600)

    async def test_lost_write_is_not_resent_after_restart_and_positive_text_settles(
        self,
    ) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.profiles.save(self.identity, self.request, None, self.fields)
        restored = DeviceProfiles(
            self.remote,
            FileProfileIntents(self.root, self.config.project, self.config.collector),
            self.receipts,
            lambda: ("10.1.2.10",),
        )
        self.assertEqual(
            (await restored.save(self.identity, self.request, None, self.fields)).phase,
            "pending",
        )
        with self.assertRaises(ReconciliationRequired):
            await restored.save(self.identity, str(uuid4()), None, self.fields)
        self.assertEqual(self.remote.writes, 1)
        await restored.reconcile()
        self.assertEqual(restored.intents.all()[0].phase, "confirmed")
        self.assertEqual(self.remote.writes, 1)

    async def test_absent_or_different_write_remains_fenced(self) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.profiles.save(self.identity, self.request, None, self.fields)
        revision = self.remote.rows[0].revision
        self.remote.sources[revision] = DeviceProfile(
            1, self.identity, str(uuid4()), STAMP, self.fields
        ).markdown()
        with self.assertRaises(ReconciliationRequired):
            await self.profiles.reconcile()
        self.assertEqual(self.intents.all()[0].phase, "pending")
        self.remote.rows.clear()
        with self.assertRaises(ReconciliationRequired):
            await self.profiles.reconcile()
        self.assertEqual(self.remote.writes, 1)

    async def test_stale_edit_unmonitored_address_and_duplicate_association_are_refused(
        self,
    ) -> None:
        first = await self.profiles.save(self.identity, self.request, None, self.fields)
        with self.assertRaises(ReconciliationRequired):
            await self.profiles.save(self.identity, str(uuid4()), None, self.fields)
        with self.assertRaises(ValueError):
            await self.profiles.save(
                self.identity,
                str(uuid4()),
                first.revision,
                replace(self.fields, addresses=("10.1.2.99",)),
            )
        with self.assertRaises(ValueError):
            await self.profiles.save(str(uuid4()), str(uuid4()), None, self.fields)
        self.assertEqual(self.remote.writes, 1)

    async def test_mutated_intent_and_foreign_journal_are_refused(self) -> None:
        await self.profiles.save(self.identity, self.request, None, self.fields)
        with self.assertRaises(ValueError):
            await self.profiles.save(
                self.identity, self.request, None, replace(self.fields, name="Other")
            )
        prior = self.intents.all()[0]
        with self.assertRaises(ValueError):
            self.intents.save(replace(prior, phase="pending", revision=None))
        with self.assertRaises(ValueError):
            FileProfileIntents(
                self.root, "another-project", self.config.collector
            ).all()

    async def test_only_catalogued_profile_revisions_are_readable(self) -> None:
        await self.profiles.save(self.identity, self.request, None, self.fields)
        with self.assertRaises(ValueError):
            await self.profiles.read(self.identity, str(uuid4()))
        row = self.remote.rows[0]
        self.remote.sources[row.revision] = DeviceProfile(
            1, str(uuid4()), self.request, STAMP, self.fields
        ).markdown()
        with self.assertRaises(ValueError):
            await self.profiles.list()

    async def test_unknown_readiness_cannot_hide_local_intents_and_csrf_is_enforced(
        self,
    ) -> None:
        config = replace(
            self.config,
            collection=replace(self.config.collection, targets=("10.1.2.10",)),
        )
        worker = Worker(
            config, FixturePort(config), self.receipts, CountingCollector(config)
        )
        client = TestClient(
            TestServer(application(worker, TOKEN, profiles=self.profiles))
        )
        await client.start_server()
        self.addAsyncCleanup(client.close)
        payload = {
            "profile_id": self.identity,
            "request_id": self.request,
            "expected_revision": None,
            "fields": json.loads(json.dumps(asdict(self.fields))),
        }
        self.assertEqual((await client.post("/api/profiles", json=payload)).status, 401)
        headers = {"Authorization": "Bearer " + TOKEN}
        self.assertEqual(
            (await client.post("/api/session", headers=headers, json={})).status, 200
        )
        self.assertEqual(
            (
                await client.post(
                    "/api/profiles",
                    headers={"Origin": "https://foreign.invalid"},
                    json=payload,
                )
            ).status,
            403,
        )
        self.remote.lost = True
        self.assertEqual(
            (await client.post("/api/profiles", headers=headers, json=payload)).status,
            503,
        )
        response = await client.get("/api/profile-intents", headers=headers)
        self.assertEqual((await response.json())[0]["phase"], "pending")
        self.assertEqual(
            (await client.post("/api/profiles", headers=headers, json=payload)).status,
            202,
        )
        self.assertEqual(self.remote.writes, 1)

    def test_profile_schema_is_strict_and_markdown_roundtrip_is_exact(self) -> None:
        profile = DeviceProfile(1, self.identity, self.request, STAMP, self.fields)
        self.assertEqual(DeviceProfile.read(profile.markdown()), profile)
        for source in (
            profile.markdown().replace("## Purpose", "## Injected heading"),
            profile.markdown() + "Extra text",
            "{}",
        ):
            with self.assertRaises(ValueError):
                DeviceProfile.read(source)
        for invalid in (
            {**asdict(self.fields), "extra": True},
            {**asdict(self.fields), "name": "\x00"},
            {**asdict(self.fields), "addresses": ["not-an-address"]},
        ):
            with self.assertRaises(ValueError):
                ProfileFields.decode(invalid)
        with self.assertRaises(ValueError):
            replace(profile, fields=replace(self.fields, notes="😀" * 2048)).markdown()

    async def test_sdk_uses_project_scope_and_revision_operations(self) -> None:
        client = AsyncMock(spec=Client)
        reader = AsyncMock(spec=FixturePort)
        port = SdkProfilePort(client, self.config, reader)
        revision, resource = str(uuid4()), str(uuid4())
        client.request.return_value = Reply(
            "ACCEPTED",
            None,
            InformationAdmissionDto(created=True, resource=resource, revision=revision),
        )
        profile = DeviceProfile(1, self.identity, self.request, STAMP, self.fields)
        await port.save(profile, None)
        upload = client.request.call_args.args[0]
        self.assertIsInstance(upload, InformationUploadRequest)
        self.assertEqual(upload.scope.kind, "project")
        self.assertEqual(upload.scope.project, self.config.project)
        self.assertFalse(upload.scope.include_shared)
        self.assertEqual(upload.name, source_name(self.identity))
        await port.save(profile, revision)
        self.assertIsInstance(
            client.request.call_args.args[0], InformationReviseRequest
        )
        self.assertEqual(client.request.call_args.args[0].revision, revision)
        row = InformationRevisionDto(
            id=revision,
            resource_id=resource,
            source_name=source_name(self.identity),
            kind="source",
            ordinal=1,
            author="fixture-service",
            created_at=STAMP,
        )
        client.request.return_value = Reply(
            "OK", None, (row, replace(row, source_name="unrelated-source"))
        )
        self.assertEqual((await port.versions())[0].profile_id, self.identity)
        self.assertIsInstance(client.request.call_args.args[0], InformationListRequest)
        self.assertFalse(client.request.call_args.args[0].scope.include_shared)

    async def test_not_submitted_write_is_retained_as_refused(self) -> None:
        with patch.object(
            self.remote,
            "save",
            AsyncMock(
                side_effect=TransportError(Delivery.NOT_SUBMITTED, "Not delivered")
            ),
        ):
            with self.assertRaises(TransportError):
                await self.profiles.save(self.identity, self.request, None, self.fields)
        self.assertEqual(self.intents.all()[0].phase, "refused")

    async def test_foreign_revision_receipt_keeps_write_pending(self) -> None:
        first = await self.profiles.save(self.identity, self.request, None, self.fields)
        original = self.remote.save

        async def foreign(
            profile: DeviceProfile, previous: str | None
        ) -> tuple[str, str]:
            revision, _ = await original(profile, previous)
            return revision, str(uuid4())

        with patch.object(self.remote, "save", side_effect=foreign):
            with self.assertRaises(ValueError):
                await self.profiles.save(
                    self.identity,
                    str(uuid4()),
                    first.revision,
                    replace(self.fields, notes="Edited"),
                )
        self.assertEqual(self.intents.all()[-1].phase, "pending")
        await self.profiles.reconcile()
        self.assertEqual(self.intents.all()[-1].phase, "confirmed")
        self.assertEqual(self.remote.writes, 2)

    async def test_sdk_rejects_fractional_ordinals_and_incomplete_history(self) -> None:
        client = AsyncMock(spec=Client)
        port = SdkProfilePort(client, self.config, AsyncMock(spec=FixturePort))
        row = InformationRevisionDto(
            id=str(uuid4()),
            resource_id=str(uuid4()),
            source_name=source_name(self.identity),
            kind="source",
            ordinal=1.5,
            author="fixture-service",
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
