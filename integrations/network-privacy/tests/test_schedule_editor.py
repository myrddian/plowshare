"""Schedule revisions are reviewed once and fenced on unknown deployment delivery."""

from __future__ import annotations

import json
import tempfile
import unittest
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import AsyncIterator
from unittest.mock import AsyncMock, patch

from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    AdminStatusDto,
    ApplicationDeploymentReceiptDto,
    ApplicationDeploymentStatusDto,
    ApplicationDeployRequest,
    ApplicationReleaseDto,
    FileStoreCatalogDto,
    FileStoreOptionDto,
)
from support import EXAMPLES

from plowshare_privacy.monitor import SettingsBusy
from plowshare_privacy.schedule_editor import ScheduleEditor
from plowshare_privacy.setup import Setup, prepare

FIRST = "00000000-0000-0000-0000-000000000001"
SECOND = "00000000-0000-0000-0000-000000000002"


class ScheduleEditorTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.config = self.root / "collector.json"
        row = json.loads((EXAMPLES / "config.json").read_text())
        row["stateDirectory"] = str(self.root / "state")
        self.config.write_text(json.dumps(row))
        self.setup = Setup(
            self.config,
            EXAMPLES.parent / "network-privacy-watch",
            self.root / "deployment",
            "worker",
            "manager",
            "installer",
            "collector",
            30,
        )
        prepare(self.setup)
        self.release = ApplicationReleaseDto(
            revision=FIRST, digest="a" * 64, file_count=12
        )
        (self.root / "deployment/identity-receipt.json").write_text(
            json.dumps(
                asdict(
                    ApplicationDeploymentReceiptDto(
                        project="network-privacy-watch",
                        request_id=FIRST,
                        release=self.release,
                    )
                )
            )
        )
        (self.root / "destination.json").write_text(json.dumps({"path": "private-app"}))
        self.editor = ScheduleEditor(self.root)
        self.client = AsyncMock(spec=Client)
        self.client.__aenter__.return_value = self.client
        self.lost = False
        self.active = FIRST
        self.sent: list[ApplicationDeployRequest] = []
        self.logins: list[tuple[str, str, str]] = []

        @asynccontextmanager
        async def login(origin: str, handle: str, password: str) -> AsyncIterator[str]:
            self.logins.append((origin, handle, password))
            yield "temporary-fixture-admin-bearer"

        async def request(command: object) -> Reply[object]:
            operation = getattr(command, "operation")
            if operation == "admin.status":
                return Reply(
                    "OK", None, AdminStatusDto(handle="installer", server_admin=True)
                )
            if operation == "application.deployment.status":
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentStatusDto(
                        project="network-privacy-watch",
                        active_revision=self.active,
                        releases=(self.release,),
                    ),
                )
            if operation == "filestore.list":
                return Reply(
                    "OK",
                    None,
                    FileStoreCatalogDto(
                        stores=(
                            FileStoreOptionDto(alias="allowed", role="MANAGER"),
                            FileStoreOptionDto(alias="not-writable", role="VIEWER"),
                        )
                    ),
                )
            if operation == "application.deployment.receipt":
                command = self.sent[-1]
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentReceiptDto(
                        project=command.project,
                        request_id=command.request_id,
                        release=ApplicationReleaseDto(
                            revision=SECOND,
                            digest="b" * 64,
                            file_count=len(command.files),
                        ),
                    ),
                )
            if isinstance(command, ApplicationDeployRequest):
                self.assertTrue(
                    (self.root / "deployment/schedule-update-intent.json").exists()
                )
                self.sent.append(command)
                if self.lost:
                    raise TransportError(
                        Delivery.UNKNOWN, "fixture lost deployment reply"
                    )
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentReceiptDto(
                        project=command.project,
                        request_id=command.request_id,
                        release=ApplicationReleaseDto(
                            revision=SECOND,
                            digest="b" * 64,
                            file_count=len(command.files),
                        ),
                    ),
                )
            raise AssertionError(operation)

        self.client.request.side_effect = request
        for target, value in (
            ("plowshare_privacy.schedule_editor.administrator_login", login),
            (
                "plowshare_privacy.schedule_editor.Client.connect",
                AsyncMock(return_value=self.client),
            ),
        ):
            patcher = patch(target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    async def test_review_is_read_only_and_deployment_uses_reviewed_source_and_store(
        self,
    ) -> None:
        before = (
            self.setup.output / "application/schedules/network_scan.json"
        ).read_text()
        review = await self.editor.review("hourly", "UTC", False, "review-password")
        self.assertEqual(review.stores, ("allowed",))
        self.assertEqual(self.sent, [])
        self.assertEqual(
            (self.setup.output / "application/schedules/network_scan.json").read_text(),
            before,
        )
        revision = await self.editor.deploy(review.id, "allowed", "deploy-password")
        self.assertEqual(revision, SECOND)
        command = self.sent[0]
        self.assertEqual(command.expected_revision, FIRST)
        self.assertEqual(command.destination.store, "allowed")
        selected = next(
            item for item in command.files if item.path == "schedules/network_scan.json"
        )
        self.assertFalse(json.loads(selected.text)["paused"])
        self.assertEqual(
            (self.setup.output / "application/schedules/network_scan.json").read_text(),
            selected.text,
        )
        self.assertFalse(self.editor.pending.exists())
        retained = "".join(
            path.read_text()
            for path in (self.root / "deployment").glob("*receipt.json")
        )
        self.assertNotIn("password", retained)
        self.assertEqual(len(self.logins), 2)

    async def test_unknown_delivery_blocks_reentry_and_keeps_prepared_source(
        self,
    ) -> None:
        review = await self.editor.review(
            "15m", "UTC", False, "fixture-administrator-secret-not-in-source-73002"
        )
        before = (
            self.setup.output / "application/schedules/network_scan.json"
        ).read_text()
        self.lost = True
        with self.assertRaises(TransportError):
            await self.editor.deploy(
                review.id, "allowed", "fixture-administrator-secret-not-in-source-73002"
            )
        with self.assertRaises(ValueError):
            await self.editor.deploy(
                review.id, "allowed", "fixture-administrator-secret-not-in-source-73002"
            )
        with self.assertRaises(SettingsBusy):
            await ScheduleEditor(self.root).review(
                "daily",
                "UTC",
                False,
                "fixture-administrator-secret-not-in-source-73002",
            )
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(
            (self.setup.output / "application/schedules/network_scan.json").read_text(),
            before,
        )
        self.assertNotIn(
            "fixture-administrator-secret-not-in-source-73002",
            self.editor.pending.read_text(),
        )

    async def test_foreign_store_stale_revision_or_changed_source_never_deploys(
        self,
    ) -> None:
        review = await self.editor.review(
            "daily", "UTC", True, "fixture-administrator-secret-not-in-source-73002"
        )
        with self.assertRaises(ValueError):
            await self.editor.deploy(
                review.id,
                "not-writable",
                "fixture-administrator-secret-not-in-source-73002",
            )
        (self.setup.output / "application/README.md").write_text("unreviewed change")
        with self.assertRaises(ValueError):
            await self.editor.deploy(
                review.id, "allowed", "fixture-administrator-secret-not-in-source-73002"
            )
        self.assertEqual(self.sent, [])
        self.active = SECOND
        with self.assertRaises(SettingsBusy):
            await self.editor.review(
                "hourly",
                "UTC",
                False,
                "fixture-administrator-secret-not-in-source-73002",
            )

    async def test_unknown_preset_is_refused_before_login(self) -> None:
        with self.assertRaises(ValueError):
            await self.editor.review(
                "arbitrary",
                "UTC",
                False,
                "fixture-administrator-secret-not-in-source-73002",
            )
        self.assertEqual(self.logins, [])

    async def test_positive_receipt_recovers_lost_deployment_without_resubmission(
        self,
    ) -> None:
        review = await self.editor.review("hourly", "UTC", False, "fixture-secret")
        self.lost = True
        with self.assertRaises(TransportError):
            await self.editor.deploy(review.id, "allowed", "fixture-secret")
        self.assertEqual(self.editor.pending_request(), review.id)
        self.active = SECOND
        self.assertEqual(await self.editor.reconcile("fixture-secret"), SECOND)
        self.assertIsNone(self.editor.pending_request())
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(
            json.loads(
                (
                    self.setup.output / "application/schedules/network_scan.json"
                ).read_text()
            )["cron"],
            review.cron,
        )

    async def test_unknown_zone_is_refused_before_login(self) -> None:
        with self.assertRaises(ValueError):
            await self.editor.review(
                "hourly", "Unavailable/FixtureZone", True, "fixture-secret"
            )
        self.assertEqual(self.logins, [])
