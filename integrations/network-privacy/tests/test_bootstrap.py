"""Guided installation through typed public SDK fakes, without a database or scans."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
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
    FileStoreReferenceDto,
    ScheduleFileDto,
    ServerAccountDto,
)
from support import EXAMPLES, STAMP

from plowshare_privacy.bootstrap import (
    SetupProblem,
    application_files,
    configure,
    deploy,
    inspect,
    install,
    private_root,
    serve,
)
from plowshare_privacy.contracts import Configuration
from plowshare_privacy.setup import Setup, bind_provider, prepare, private_write

FIRST = "00000000-0000-0000-0000-000000000001"
SECOND = "00000000-0000-0000-0000-000000000002"
PRINCIPAL = "@service/" + SECOND


def release(revision: str) -> ApplicationReleaseDto:
    return ApplicationReleaseDto(revision=revision, digest="a" * 64, file_count=12)


class BootstrapTest(unittest.IsolatedAsyncioTestCase):
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

    async def provisioned(self, setup: Setup, client: object) -> None:
        bind_provider(setup, PRINCIPAL)
        private_write(setup.output / "service-token", "pss_fixture_secret\n")
        private_write(
            setup.output / "identity.json",
            json.dumps(
                {
                    "handle": "worker",
                    "principal": PRINCIPAL,
                    "tokenId": SECOND,
                    "expiresAt": STAMP,
                }
            ),
        )

    async def test_install_selects_manager_store_and_uses_two_retained_revisions(
        self,
    ) -> None:
        prepare(self.setup)
        client = self.client()
        with (
            patch("builtins.input", return_value="1"),
            patch(
                "plowshare_privacy.bootstrap.provision", side_effect=self.provisioned
            ) as provision,
            patch("builtins.print") as output,
        ):
            await install(self.setup, client, self.destination.path)
        provision.assert_awaited_once()
        deployments = [
            call.args[0]
            for call in client.request.call_args_list
            if isinstance(call.args[0], ApplicationDeployRequest)
        ]
        self.assertEqual(len(deployments), 2)
        self.assertNotEqual(deployments[0].request_id, deployments[1].request_id)
        self.assertEqual(deployments[1].expected_revision, FIRST)
        manifests = [
            json.loads(
                next(
                    file.text for file in command.files if file.path == "plowshare.json"
                )
            )
            for command in deployments
        ]
        self.assertEqual(manifests[0]["executionAccount"], "worker")
        self.assertEqual(manifests[1]["executionAccount"], PRINCIPAL)
        self.assertEqual(manifests[0]["toolGrants"], manifests[1]["toolGrants"])
        self.assertEqual(manifests[0]["access"], manifests[1]["access"])
        self.assertTrue((self.setup.output / "installation-completed").is_file())
        self.assertEqual(Configuration.read(self.config).schedule, "actual-schedule")
        messages = str(output.call_args_list)
        self.assertNotIn("pss_fixture_secret", messages)
        self.assertNotIn("read-only-store", messages)

    async def test_uncertain_deployment_is_not_replayed_or_provisioned(self) -> None:
        prepare(self.setup)
        client = self.client(lost=True)
        with (
            patch("builtins.input", return_value="1"),
            patch("plowshare_privacy.bootstrap.provision") as provision,
        ):
            with self.assertRaises(TransportError):
                await install(self.setup, client, self.destination.path)
        provision.assert_not_called()
        self.assertTrue((self.setup.output / "bootstrap-intent.json").exists())
        self.assertFalse((self.setup.output / "installation-completed").exists())
        later = self.client()
        with self.assertRaises(SetupProblem):
            await install(self.setup, later, self.destination.path)
        later.request.assert_not_called()

    async def test_provisioning_failure_never_starts_the_second_deployment(
        self,
    ) -> None:
        prepare(self.setup)
        client = self.client()
        with (
            patch("builtins.input", return_value="1"),
            patch(
                "plowshare_privacy.bootstrap.provision",
                side_effect=TransportError(
                    Delivery.UNKNOWN, "fixture lost token reply"
                ),
            ),
        ):
            with self.assertRaises(TransportError):
                await install(self.setup, client, self.destination.path)
        self.assertTrue((self.setup.output / "bootstrap-receipt.json").exists())
        self.assertFalse((self.setup.output / "identity-intent.json").exists())
        self.assertFalse((self.setup.output / "installation-completed").exists())
        self.assertEqual(
            sum(
                isinstance(call.args[0], ApplicationDeployRequest)
                for call in client.request.call_args_list
            ),
            1,
        )

    async def test_existing_application_refuses_before_store_selection_or_mutation(
        self,
    ) -> None:
        prepare(self.setup)
        client = AsyncMock(spec=Client)
        client.request.side_effect = [
            Reply("OK", None, AdminStatusDto(handle="installer", server_admin=True)),
            Reply(
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
            ),
            Reply(
                "OK",
                None,
                ApplicationDeploymentStatusDto(
                    project="network-privacy-watch",
                    active_revision=FIRST,
                    releases=(release(FIRST),),
                ),
            ),
        ]
        with self.assertRaises(SetupProblem):
            await install(self.setup, client, self.destination.path)
        self.assertEqual(client.request.call_count, 3)
        self.assertFalse((self.setup.output / "bootstrap-intent.json").exists())

    async def test_no_writable_store_and_live_schedule_refuse_before_mutations(
        self,
    ) -> None:
        prepare(self.setup)
        with self.assertRaises(SetupProblem):
            await install(
                self.setup, self.client(writable=False), self.destination.path
            )
        self.assertFalse((self.setup.output / "bootstrap-intent.json").exists())
        schedule = self.setup.output / "application/schedules/network_scan.json"
        row = json.loads(schedule.read_text())
        row["paused"] = False
        schedule.write_text(json.dumps(row))
        with patch("builtins.input", return_value="1"):
            with self.assertRaises(SetupProblem):
                await install(self.setup, self.client(), self.destination.path)
        self.assertFalse((self.setup.output / "bootstrap-intent.json").exists())

    async def test_receipt_mismatch_preserves_intent_and_no_completion(self) -> None:
        prepare(self.setup)
        client = AsyncMock(spec=Client)
        client.request.return_value = Reply(
            "OK",
            None,
            ApplicationDeploymentReceiptDto(
                project="another-project", request_id=FIRST, release=release(FIRST)
            ),
        )
        with self.assertRaises(SetupProblem):
            await deploy(self.setup, client, self.destination, "bootstrap", None)
        self.assertTrue((self.setup.output / "bootstrap-intent.json").exists())
        self.assertFalse((self.setup.output / "bootstrap-receipt.json").exists())
        self.assertEqual(client.request.call_count, 1)

    async def test_status_is_read_only_on_server_and_can_fill_schedule_later(
        self,
    ) -> None:
        prepare(self.setup)
        client = self.client()
        await inspect(self.setup, client)
        await inspect(self.setup, client)
        self.assertEqual(Configuration.read(self.config).schedule, "actual-schedule")
        self.assertTrue(
            all(
                call.args[0].operation
                in {"application.deployment.status", "schedule.files"}
                for call in client.request.call_args_list
            )
        )

    async def test_serve_uses_private_service_identity_and_restores_environment(
        self,
    ) -> None:
        prepare(self.setup)
        await self.provisioned(self.setup, object())
        private_write(self.setup.output / "installation-completed", "confirmed\n")
        row = json.loads(self.config.read_text())
        row["schedule"] = "actual-schedule"
        self.config.write_text(json.dumps(row))

        async def collector(args: object) -> None:
            self.assertEqual(os.environ["PLOWSHARE_TOKEN"], "pss_fixture_secret")
            self.assertEqual(
                os.environ["PRIVACY_WEB_TOKEN"], "dashboard-fixture-bearer-123456"
            )
            self.assertEqual(getattr(args, "tool_account"), PRINCIPAL)
            raise ValueError("fixture collector failure")

        with (
            patch.dict(
                os.environ, {"PLOWSHARE_TOKEN": "existing-human-token"}, clear=True
            ),
            patch(
                "plowshare_privacy.bootstrap.getpass",
                return_value="dashboard-fixture-bearer-123456",
            ),
            patch("plowshare_privacy.bootstrap.execute", side_effect=collector),
        ):
            with self.assertRaises(ValueError):
                await serve(self.setup, "fixture-bind", 43210)
            self.assertEqual(os.environ["PLOWSHARE_TOKEN"], "existing-human-token")
            self.assertNotIn("PRIVACY_WEB_TOKEN", os.environ)

    def test_configure_prepares_offline_source_and_all_model_declarations(self) -> None:
        root = self.root / "wizard"
        inputs = [
            "https://fixture.invalid",
            "collector-one",
            "192.0.2.1,192.0.2.2",
            "443,8008",
            "1",
            "4",
            "worker",
            "manager",
            "installer",
            "local-model",
            "30",
            "network-privacy-watch",
        ]
        with patch("builtins.input", side_effect=inputs):
            configure(root, self.setup.source)
        config = Configuration.read(root / "collector.json")
        self.assertEqual(config.collection.targets, ("192.0.2.1", "192.0.2.2"))
        self.assertEqual(config.collection.ports, (443, 8008))
        setup = Setup.read(root / "deployment/setup.json")
        for folder in ("agents", "orchestrations"):
            for path in (setup.output / f"application/{folder}").glob("*.md"):
                self.assertIn("\nmodel: local-model\n", path.read_text())
        scripts = tuple((setup.output / "application/orchestrations").glob("*.js"))
        self.assertEqual(
            {path.stem for path in scripts}, {"privacy_tick", "investigate_network"}
        )
        for path in scripts:
            self.assertIn('model: "local-model"', path.read_text())
        self.assertFalse((setup.output / "provisioning-intent.json").exists())
        if os.name == "posix":
            self.assertEqual((root / "collector.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)
        with self.assertRaises(SetupProblem):
            configure(root, self.setup.source)

    def test_private_paths_and_application_package_links_and_limits(self) -> None:
        with self.assertRaises(SetupProblem):
            private_root(EXAMPLES.parent / "private")
        root = self.root / "source"
        root.mkdir()
        (root / "plowshare.json").write_text("{}")
        (root / "link").symlink_to(self.config)
        with self.assertRaises(SetupProblem):
            application_files(root)
        (root / "link").unlink()
        (root / "large.txt").write_text("x" * 65537)
        with self.assertRaises(SetupProblem):
            application_files(root)
        (root / "large.txt").unlink()
        (root / ".plowshare").mkdir()
        with self.assertRaises(SetupProblem):
            application_files(root)
