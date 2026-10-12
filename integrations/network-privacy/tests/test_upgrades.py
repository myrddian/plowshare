"""Stopped upgrade preparation and retained receipt recovery; no scans or inference."""

from __future__ import annotations

import json
import sys
from dataclasses import replace
from unittest.mock import AsyncMock, patch
from zipfile import ZipFile

from lifecycle_support import FIRST, SECOND, LifecycleFixture, release
from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    ApplicationDeploymentReceiptDto,
    ApplicationDeploymentStatusDto,
)

from plowshare_privacy.bootstrap import SetupProblem
from plowshare_privacy.setup import prepare, private_write
from plowshare_privacy.upgrades import (
    Wheel,
    apply_upgrade,
    hashes,
    install_wheels,
    prepare_upgrade,
    selected_plan,
)


class UpgradeTest(LifecycleFixture):
    def installed(self) -> None:
        prepare(self.setup)
        private_write(self.setup.output / "installation-completed", "fixture")
        private_write(
            self.setup.output / "bootstrap-intent.json",
            json.dumps(
                {
                    "project": "network-privacy-watch",
                    "requestId": FIRST,
                    "expectedRevision": None,
                    "destination": {
                        "store": "chosen-store",
                        "path": "network-privacy-watch",
                    },
                }
            ),
        )
        private_write(
            self.setup.output / "package-baseline.json",
            json.dumps({"files": hashes(self.setup.output / "application")}),
        )

    def planning(self) -> AsyncMock:
        client = AsyncMock(spec=Client)
        client.request.side_effect = [
            Reply(
                "OK",
                None,
                ApplicationDeploymentStatusDto(
                    project="network-privacy-watch",
                    active_revision=FIRST,
                    releases=(release(FIRST),),
                ),
            ),
            Reply(
                "OK",
                None,
                ApplicationDeploymentReceiptDto(
                    project="network-privacy-watch",
                    request_id=FIRST,
                    release=release(FIRST),
                ),
            ),
        ]
        return client

    async def test_plan_preserves_identity_local_edits_and_pending_fence(self) -> None:
        self.installed()
        application = self.setup.output / "application"
        original = (application / "plowshare.json").read_text()
        (application / "notes.md").write_text("local evidence")
        agent = application / "agents/privacy_analyst.md"
        agent.write_text(agent.read_text() + "\nLocal instruction.\n")
        stage = await prepare_upgrade(self.setup, self.planning(), self.setup.source)
        _, plan = selected_plan(self.setup)
        self.assertIn("agents/privacy_analyst.md", plan.conflicts)
        self.assertEqual(
            (stage / "prepared/application/notes.md").read_text(), "local evidence"
        )
        self.assertEqual(
            json.loads((stage / "prepared/application/plowshare.json").read_text()),
            json.loads(original),
        )
        with self.assertRaisesRegex(SetupProblem, "already exists"):
            await prepare_upgrade(self.setup, self.planning(), self.setup.source)
        with self.assertRaisesRegex(SetupProblem, "conflicts"):
            await apply_upgrade(self.setup, AsyncMock(spec=Client))

    async def test_unknown_deploy_recovers_original_receipt_and_directory_swap(
        self,
    ) -> None:
        self.installed()
        client = self.planning()
        stage = await prepare_upgrade(self.setup, client, self.setup.source)
        _, plan = selected_plan(self.setup)
        client.request.side_effect = [
            Reply(
                "OK",
                None,
                ApplicationDeploymentStatusDto(
                    project=plan.project,
                    active_revision=FIRST,
                    releases=(release(FIRST),),
                ),
            ),
            TransportError(Delivery.UNKNOWN, "lost reply"),
        ]
        with self.assertRaises(TransportError):
            await apply_upgrade(self.setup, client)
        command = client.request.call_args.args[0]
        self.assertEqual(command.request_id, plan.request_id)
        client.request.reset_mock()
        confirmed = ApplicationDeploymentStatusDto(
            project=plan.project, active_revision=SECOND, releases=(release(SECOND),)
        )
        client.request.side_effect = [
            Reply("OK", None, confirmed),
            Reply(
                "OK",
                None,
                ApplicationDeploymentReceiptDto(
                    project=plan.project,
                    request_id=plan.request_id,
                    release=release(SECOND),
                ),
            ),
            Reply("OK", None, confirmed),
        ]
        # Emulate a crash after retaining the old source but before publishing the replacement.
        (self.setup.output / "application").rename(stage / "previous-application")
        await apply_upgrade(self.setup, client, recover=True)
        self.assertEqual(hashes(self.setup.output / "application"), plan.candidate)
        self.assertTrue((stage / "completed").is_file())
        operations = [call.args[0].operation for call in client.request.call_args_list]
        self.assertEqual(
            operations,
            [
                "application.deployment.status",
                "application.deployment.receipt",
                "application.deployment.status",
            ],
        )

    async def test_plan_validates_tampering_before_mutation(self) -> None:
        self.installed()
        stage = await prepare_upgrade(self.setup, self.planning(), self.setup.source)
        path = stage / "plan.json"
        row = json.loads(path.read_text())
        row["destination"]["path"] = "../outside"
        path.write_text(json.dumps(row))
        client = AsyncMock(spec=Client)
        with self.assertRaisesRegex(SetupProblem, "destination"):
            await apply_upgrade(self.setup, client)
        client.request.assert_not_awaited()

    async def test_reviewed_wheels_reinstall_same_version_and_fence_interrupted_pip(
        self,
    ) -> None:
        self.installed()
        stage = await prepare_upgrade(self.setup, self.planning(), self.setup.source)
        _, plan = selected_plan(self.setup)
        wheels = []
        for name in ("plowshare-sdk", "plowshare-network-privacy"):
            path = self.root / (name + ".whl")
            with ZipFile(path, "w") as archive:
                archive.writestr(
                    name + ".dist-info/METADATA", "Name: " + name + "\nVersion: 0.1.0\n"
                )
                archive.writestr("package.py", "# fixture")
            wheels.append(Wheel.inspect(path, name))
        environment = self.root / "venv"
        environment.mkdir()
        (environment / "installed.txt").write_text("old bytes")
        reviewed = replace(
            plan, wheels=tuple(wheels), python=sys.executable, environment=environment
        )
        commands: list[tuple[str, ...]] = []
        with (
            patch("plowshare_privacy.upgrades.sys.prefix", str(environment)),
            patch(
                "plowshare_privacy.upgrades.installed_wheels", side_effect=[False, True]
            ),
        ):
            with self.assertRaisesRegex(SetupProblem, "do not match"):
                install_wheels(self.setup, stage, reviewed, commands.append)
            self.assertIn("--force-reinstall", commands[0])
            self.assertEqual(
                (stage / "backup/collector-environment/installed.txt").read_text(),
                "old bytes",
            )
            install_wheels(self.setup, stage, reviewed, commands.append)
            self.assertEqual(
                len(commands), 1
            )  # Verified prior completion reconciles without rerunning pip.
        self.assertTrue((stage / "collector-upgrade-completed").is_file())

    def test_wheel_inspection_refuses_traversal_and_foreign_package(self) -> None:
        path = self.root / "unsafe.whl"
        with ZipFile(path, "w") as archive:
            archive.writestr("package.dist-info/METADATA", "Name: plowshare-sdk\n")
            archive.writestr("../outside.py", "bad")
        with self.assertRaisesRegex(SetupProblem, "bounded"):
            Wheel.inspect(path, "plowshare-sdk")
