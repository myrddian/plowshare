"""Typed private setup fixture shared by the lifecycle tests."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    AdminStatusDto,
    ApplicationDeploymentReceiptDto,
    ApplicationDeploymentStatusDto,
    ApplicationDeployRequest,
    ApplicationReleaseDto,
    FileStoreCatalogDto,
    FileStoreOptionDto,
    FileStoreReferenceDto,
    ScheduleFileDto,
    ServerAccountDto,
)
from support import EXAMPLES, STAMP

from plowshare_privacy.setup import Setup

FIRST = "00000000-0000-0000-0000-000000000001"
SECOND = "00000000-0000-0000-0000-000000000002"
PRINCIPAL = "@service/" + SECOND


def release(revision: str) -> ApplicationReleaseDto:
    return ApplicationReleaseDto(revision=revision, digest="a" * 64, file_count=12)


class LifecycleFixture(unittest.IsolatedAsyncioTestCase):
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
        self.destination = FileStoreReferenceDto(
            store="chosen-store", path="network-privacy-watch"
        )

    def client(self, *, lost: bool = False, writable: bool = True) -> AsyncMock:
        client = AsyncMock(spec=Client)
        active: str | None = None

        async def request(command: object) -> Reply[object]:
            nonlocal active
            operation = getattr(command, "operation")
            if operation == "admin.status":
                return Reply(
                    "OK", None, AdminStatusDto(handle="installer", server_admin=True)
                )
            if operation == "admin.accounts":
                return Reply(
                    "OK",
                    None,
                    (
                        ServerAccountDto(
                            created_at=STAMP,
                            enabled=True,
                            handle="manager",
                            must_change_password=False,
                            server_admin=False,
                        ),
                    ),
                )
            if operation == "application.deployment.status":
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentStatusDto(
                        project="network-privacy-watch",
                        active_revision=active,
                        releases=() if active is None else (release(active),),
                    ),
                )
            if operation == "filestore.list":
                return Reply(
                    "OK",
                    None,
                    FileStoreCatalogDto(
                        stores=(
                            FileStoreOptionDto(alias="read-only-store", role="VIEWER"),
                            FileStoreOptionDto(
                                alias="chosen-store",
                                role="MANAGER" if writable else "CONTRIBUTOR",
                            ),
                        )
                    ),
                )
            if operation == "application.deploy":
                self.assertIsInstance(command, ApplicationDeployRequest)
                assert isinstance(command, ApplicationDeployRequest)
                phase = "bootstrap" if active is None else "identity"
                intent = json.loads(
                    (self.setup.output / f"{phase}-intent.json").read_text()
                )
                self.assertEqual(intent["requestId"], command.request_id)
                self.assertEqual(command.expected_revision, active)
                self.assertEqual(command.destination, self.destination)
                if lost:
                    raise TransportError(
                        Delivery.UNKNOWN, "fixture lost deployment reply"
                    )
                active = FIRST if active is None else SECOND
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentReceiptDto(
                        project=command.project,
                        request_id=command.request_id,
                        release=release(active),
                    ),
                )
            if operation == "application.deployment.receipt":
                phase = (
                    "bootstrap"
                    if getattr(command, "request_id")
                    == json.loads(
                        (self.setup.output / "bootstrap-intent.json").read_text()
                    )["requestId"]
                    else "identity"
                )
                return Reply(
                    "OK",
                    None,
                    ApplicationDeploymentReceiptDto(
                        project="network-privacy-watch",
                        request_id=getattr(command, "request_id"),
                        release=release(FIRST if phase == "bootstrap" else SECOND),
                    ),
                )
            if operation == "schedule.files":
                return Reply(
                    "OK",
                    None,
                    (
                        ScheduleFileDto(
                            project="network-privacy-watch",
                            source="server",
                            name="network_scan",
                            internal_name="actual-schedule",
                            status="active",
                            error=None,
                            definition=None,
                            path="schedules/network_scan.json",
                        ),
                    ),
                )
            raise AssertionError("Unexpected operation: " + operation)

        client.request.side_effect = request
        return client
