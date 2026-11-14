"""Guided installation through typed public SDK fakes, without a database or scans."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Any
from unittest.mock import ANY, AsyncMock, patch
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, Reply, TransportError
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
    ScheduleFilesRequest,
    ServerAccountDto,
)
from support import EXAMPLES, STAMP, CountingCollector, configuration

from plowshare_privacy.bootstrap import (
    SetupProblem,
    application_files,
    bind_unifi_gateway,
    configure,
    connect_pihole,
    connect_unifi,
    deploy,
    enable_capability,
    enable_unifi_traffic,
    inspect,
    install,
    main,
    private_root,
    resolve_schedule,
    serve,
    start,
    wait_for_schedule,
)
from plowshare_privacy.collection import scope_fingerprint
from plowshare_privacy.contracts import CollectionPlan, Configuration, utc_now
from plowshare_privacy.journal import FileReceipts, Publication, Receipt
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

    async def test_schedule_resolution_finds_later_page_and_never_mutates_server(
        self,
    ) -> None:
        client = AsyncMock(spec=Client)
        target = ScheduleFileDto(
            project="network-privacy-watch",
            source="server",
            name="network_scan",
            internal_name="actual-later-schedule",
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        page = tuple(
            replace(target, name=f"other-{i}", internal_name=f"file-{i:03}")
            for i in range(100)
        )
        client.request.side_effect = [
            Reply("OK", None, page),
            Reply("OK", None, (target,)),
        ]
        self.assertTrue(await resolve_schedule(self.setup, client))
        self.assertEqual(Configuration.read(self.config).schedule, target.internal_name)
        self.assertEqual(client.request.await_count, 2)
        for call in client.request.call_args_list:
            self.assertIsInstance(call.args[0], ScheduleFilesRequest)
            self.assertEqual(call.args[0].limit, 100)
        self.assertEqual(
            client.request.call_args_list[1].args[0].after, page[-1].internal_name
        )

    async def test_schedule_resolution_revocation_leaves_config_and_admissions_untouched(
        self,
    ) -> None:
        client = AsyncMock(spec=Client)
        target = ScheduleFileDto(
            project="network-privacy-watch",
            source="server",
            name="network_scan",
            internal_name="actual-later-schedule",
            status="active",
            error=None,
            definition=None,
            path="schedules/network_scan.json",
        )
        page = tuple(
            replace(target, name=f"other-{i}", internal_name=f"file-{i:03}")
            for i in range(100)
        )
        before = self.config.read_bytes()
        client.request.side_effect = [
            Reply("OK", None, page),
            Reply("FORBIDDEN", "revoked", ()),
        ]
        with self.assertRaises(Refusal):
            await resolve_schedule(self.setup, client)
        self.assertEqual(self.config.read_bytes(), before)
        self.assertEqual(client.request.await_count, 2)
        self.assertTrue(
            all(
                isinstance(call.args[0], ScheduleFilesRequest)
                for call in client.request.call_args_list
            )
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

    async def test_start_reuses_completed_install_without_admin_login_or_bearer_prompt(
        self,
    ) -> None:
        prepare(self.setup)
        await self.provisioned(self.setup, object())
        private_write(self.setup.output / "installation-completed", "confirmed\n")
        row = json.loads(self.config.read_text())
        row["schedule"] = "actual-schedule"
        self.config.write_text(json.dumps(row))
        with (
            patch("plowshare_privacy.bootstrap.administrator_login") as login,
            patch("plowshare_privacy.bootstrap.getpass") as password,
            patch(
                "plowshare_privacy.bootstrap.execute", new_callable=AsyncMock
            ) as collector,
        ):
            await start(self.setup, "127.0.0.1", 0)
            login.assert_not_called()
            password.assert_not_called()
            arguments = collector.call_args.args[0]
            self.assertTrue(arguments.open_browser)
            self.assertTrue(arguments.dashboard_settings)
            self.assertEqual(arguments.port, 0)
        self.assertTrue((self.root / "dashboard.json").exists())
        self.assertTrue(
            any(self.root.glob("*Network Privacy Watch.command"))
            or (self.root / "start-network-privacy-watch.sh").exists()
        )

    async def test_readiness_wait_only_repeats_schedule_reads(self) -> None:
        prepare(self.setup)
        client = self.client()
        original = client.request.side_effect
        count = 0

        async def request(command: object) -> Reply[object]:
            nonlocal count
            count += 1
            if count == 1:
                return Reply("OK", None, ())
            assert callable(original)
            result = await original(command)
            self.assertIsInstance(result, Reply)
            assert isinstance(result, Reply)
            return result

        client.request.side_effect = request
        with patch("plowshare_privacy.bootstrap.asyncio.sleep", new_callable=AsyncMock):
            await wait_for_schedule(self.setup, client)
        self.assertEqual(
            [call.args[0].operation for call in client.request.call_args_list],
            ["schedule.files", "schedule.files"],
        )

    def test_guided_configuration_defers_devices_and_advanced_settings(self) -> None:
        root = self.root / "guided"
        with patch(
            "builtins.input",
            side_effect=[
                "https://fixture.invalid",
                "collector-one",
                "worker",
                "manager",
                "installer",
                "local-model",
            ],
        ):
            configure(root, self.setup.source, guided=True)
        config = Configuration.read(root / "collector.json")
        self.assertFalse(config.collection.enabled)
        self.assertEqual(config.collection.targets, ())
        self.assertEqual(config.collection.ports, ())

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
            {path.stem for path in scripts},
            {
                "privacy_tick",
                "investigate_network",
                "profile_network_device",
                "assess_network_device",
            },
        )
        for path in scripts:
            if path.name == "privacy_tick.js":
                self.assertIn("'native-only': true", path.read_text())
                self.assertNotIn("model:", path.read_text())
                self.assertIn("'max-model-calls': 0", path.read_text())
            else:
                self.assertIn('model: "local-model"', path.read_text())
        manifest = json.loads((setup.output / "application/plowshare.json").read_text())
        self.assertEqual(
            manifest["service"], {"handle": "worker", "role": "CONTRIBUTOR"}
        )
        self.assertNotIn("executionAccount", manifest)
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

    def tcp_configuration(self) -> Configuration:
        row = json.loads(self.config.read_text())
        row["collection"] = {
            "mode": "tcp",
            "targets": ["192.0.2.12"],
            "ports": [443],
            "timeoutSeconds": 1,
            "concurrency": 1,
        }
        self.config.write_text(json.dumps(row))
        return Configuration.read(self.config)

    async def test_connect_pihole_is_offline_private_and_preserves_completed_history(
        self,
    ) -> None:
        config = self.tcp_configuration()
        evidence = await CountingCollector(configuration(self.root)).collect(
            SECOND, FIRST, None, None
        )
        with FileReceipts(config) as receipts:
            receipts.save(
                Receipt(
                    SECOND,
                    FIRST,
                    config.request_topic,
                    utc_now(),
                    "done",
                    evidence,
                    SECOND,
                )
            )
            receipts.save_publication(Publication(FIRST, utc_now(), "published"))
        with patch("plowshare_privacy.bootstrap.administrator_login") as login:
            connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
            login.assert_not_called()
        configured = Configuration.read(self.config)
        assert configured.collection.pihole is not None
        secret = configured.collection.pihole.password_file
        assert secret is not None
        self.assertEqual(secret.read_text(), "fixture-app-password\n")
        self.assertNotIn("fixture-app-password", self.config.read_text())
        self.assertEqual(configured.project, config.project)
        self.assertEqual(configured.token_environment, config.token_environment)
        if os.name == "posix":
            self.assertEqual(secret.stat().st_mode & 0o777, 0o600)
        with FileReceipts(configured) as receipts:
            self.assertEqual(receipts.publications()[0].state, "published")
            self.assertEqual(receipts.all()[0].evidence, evidence)
        with self.assertRaisesRegex(SetupProblem, "already configured"):
            connect_pihole(self.setup, "https://other.example", "new-password")

    def test_connect_pihole_refuses_busy_and_unsettled_receipts_before_writes(
        self,
    ) -> None:
        config = self.tcp_configuration()
        retained = self.config.read_text()
        with FileReceipts(config) as receipts:
            with self.assertRaises(BlockingIOError):
                connect_pihole(
                    self.setup, "https://pihole.example", "fixture-app-password"
                )
            receipts.save_publication(Publication(FIRST, utc_now(), "pending"))
        with self.assertRaisesRegex(ValueError, "Settle retained work"):
            connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        self.assertEqual(self.config.read_text(), retained)
        self.assertEqual(list(self.root.glob("pihole-password-*")), [])

    def test_connect_pihole_after_config_write_interruption_can_reopen_new_scope(
        self,
    ) -> None:
        self.tcp_configuration()
        with patch.object(
            FileReceipts,
            "complete_scope_change",
            side_effect=OSError("fixture interruption"),
        ):
            with self.assertRaises(OSError):
                connect_pihole(
                    self.setup, "https://pihole.example", "fixture-app-password"
                )
        configured = Configuration.read(self.config)
        with FileReceipts(configured) as receipts:
            self.assertEqual(receipts.all(), ())

    async def test_connect_unifi_preserves_receipts_and_can_coexist_with_pihole(
        self,
    ) -> None:
        config = self.tcp_configuration()
        site = "00000000-0000-4000-8000-000000000001"
        base = "https://unifi.example.invalid/proxy/network/integration/v1"
        evidence = await CountingCollector(configuration(self.root)).collect(
            SECOND, FIRST, None, None
        )
        with FileReceipts(config) as receipts:
            with self.assertRaises(BlockingIOError):
                connect_unifi(self.setup, base, site, "UNIFI_TEST_KEY", None)
            receipts.save(
                Receipt(
                    SECOND,
                    FIRST,
                    config.request_topic,
                    utc_now(),
                    "done",
                    evidence,
                    SECOND,
                )
            )
            receipts.save_publication(Publication(FIRST, utc_now(), "published"))
        connect_unifi(self.setup, base, site, "UNIFI_TEST_KEY", None)
        configured = Configuration.read(self.config)
        assert configured.collection.unifi is not None
        self.assertEqual(configured.collection.unifi.site_id, site)
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        both_sources = Configuration.read(self.config)
        self.assertEqual(both_sources.collection.unifi, configured.collection.unifi)
        with FileReceipts(both_sources) as receipts:
            self.assertEqual(receipts.all()[0].evidence, evidence)
            self.assertEqual(receipts.publications()[0].state, "published")

    def test_enable_unifi_traffic_requires_unifi_and_keeps_scope(self) -> None:
        config = self.tcp_configuration()
        with self.assertRaises(SetupProblem):
            enable_unifi_traffic(self.setup, "default")
        site = "00000000-0000-4000-8000-000000000001"
        base = "https://unifi.example.invalid/proxy/network/integration/v1"
        connect_unifi(self.setup, base, site, "UNIFI_TEST_KEY", None)
        before = Configuration.read(self.config)
        with self.assertRaises(SetupProblem):
            enable_unifi_traffic(self.setup, "../default")
        self.assertEqual(Configuration.read(self.config), before)
        enable_unifi_traffic(self.setup, "default")
        after = Configuration.read(self.config)
        assert after.collection.unifi is not None
        self.assertEqual(after.collection.unifi.traffic_site, "default")
        self.assertEqual(
            scope_fingerprint(after.collection), scope_fingerprint(before.collection)
        )
        self.assertNotEqual(config, after)

    UNIFI_BASE = "https://192.168.1.1/proxy/network/integration/v1"
    UNIFI_SITE = "00000000-0000-4000-8000-000000000001"

    def sources_configuration(self, *sources: dict[str, object]) -> Configuration:
        """A TCP collector whose services are written in the ``sources`` form.

        Each call uses a fresh state directory, as a new installation would,
        so an earlier step's journal never fences the hand-written scope.
        """
        self.tcp_configuration()
        row = json.loads(self.config.read_text())
        row["stateDirectory"] = str(self.root / ("state-" + str(uuid4())))
        row["collection"]["sources"] = list(sources)
        self.config.write_text(json.dumps(row))
        return Configuration.read(self.config)

    def unifi_settings(self, **extra: object) -> dict[str, object]:
        return {
            "apiBase": self.UNIFI_BASE,
            "siteId": self.UNIFI_SITE,
            "apiKeyEnvironment": "UNIFI_TEST_KEY",
            **extra,
        }

    def collection_row(self) -> dict[str, Any]:
        collection = json.loads(self.config.read_text())["collection"]
        assert isinstance(collection, dict)
        return collection

    def test_enable_dhcp_rewrites_to_sources_form_under_scope_fence(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        legacy = Configuration.read(self.config)
        legacy_row = self.collection_row()
        fenced: list[str] = []

        def prepare(receipts: FileReceipts, plan: CollectionPlan) -> None:
            # The fence opens before the configuration is replaced.
            fenced.append("prepare")
            self.assertIn("pihole", self.collection_row())
            prepare_scope_change(receipts, plan)

        def complete(receipts: FileReceipts, plan: CollectionPlan) -> None:
            fenced.append("complete")
            self.assertIn("sources", self.collection_row())
            complete_scope_change(receipts, plan)

        prepare_scope_change = FileReceipts.prepare_scope_change
        complete_scope_change = FileReceipts.complete_scope_change
        with (
            patch.object(
                FileReceipts, "prepare_scope_change", autospec=True, side_effect=prepare
            ),
            patch.object(
                FileReceipts,
                "complete_scope_change",
                autospec=True,
                side_effect=complete,
            ),
            patch("plowshare_privacy.bootstrap.administrator_login") as login,
        ):
            enable_capability(self.setup, "pihole", "dhcp_leases", True)
            login.assert_not_called()
        self.assertEqual(fenced, ["prepare", "complete"])
        collection = self.collection_row()
        self.assertNotIn("pihole", collection)
        self.assertNotIn("unifi", collection)
        self.assertEqual(
            collection["sources"],
            [
                {
                    "kind": "pihole",
                    "id": "pihole",
                    "capabilities": ["dhcp_leases", "dns_queries", "network_table"],
                    "settings": legacy_row["pihole"],
                },
                {
                    "kind": "unifi",
                    "id": "unifi",
                    "capabilities": ["controller_clients", "managed_inventory"],
                    "settings": legacy_row["unifi"],
                },
            ],
        )
        configured = Configuration.read(self.config)
        self.assertNotEqual(
            scope_fingerprint(configured.collection),
            scope_fingerprint(legacy.collection),
        )
        with FileReceipts(configured) as receipts:
            self.assertEqual(
                receipts.collection_scope, scope_fingerprint(configured.collection)
            )

    def test_enabling_an_enabled_capability_writes_nothing(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        retained = self.config.read_bytes()
        with patch.object(FileReceipts, "prepare_scope_change") as prepare:
            enable_capability(self.setup, "pihole", "dns_queries", True)
            enable_capability(self.setup, "pihole", "dhcp_scope", False)
            prepare.assert_not_called()
        self.assertEqual(self.config.read_bytes(), retained)

    def test_turning_a_capability_off_removes_it_from_sources(self) -> None:
        self.sources_configuration(
            {
                "kind": "pihole",
                "id": "home-dns",
                "capabilities": ["network_table", "dns_queries", "dhcp_leases"],
                "settings": {
                    "origin": "https://pihole.example",
                    "passwordEnvironment": "FIXTURE_PIHOLE_PASSWORD",
                },
            }
        )
        enable_capability(self.setup, "home-dns", "dhcp_leases", False)
        entry = self.collection_row()["sources"][0]
        self.assertEqual(entry["capabilities"], ["dns_queries", "network_table"])
        self.assertEqual(entry["id"], "home-dns")

    def test_rejects_unknown_service_and_removing_the_last_capability(self) -> None:
        self.sources_configuration(
            {
                "kind": "pihole",
                "id": "home-dns",
                "capabilities": ["network_table"],
                "settings": {
                    "origin": "https://pihole.example",
                    "passwordEnvironment": "FIXTURE_PIHOLE_PASSWORD",
                },
            }
        )
        retained = self.config.read_bytes()
        with self.assertRaisesRegex(SetupProblem, "No configured service has id dns"):
            enable_capability(self.setup, "dns", "dhcp_leases", True)
        with self.assertRaisesRegex(
            SetupProblem,
            "A service needs at least one capability; remove the service instead",
        ):
            enable_capability(self.setup, "home-dns", "network_table", False)
        self.assertEqual(self.config.read_bytes(), retained)

    def test_rejects_dns_queries_off_while_dns_profiles_are_on(self) -> None:
        self.tcp_configuration()
        row = json.loads(self.config.read_text())
        row["collection"].update(targets=["192.168.1.12"], quickScan=True)
        self.config.write_text(json.dumps(row))
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        row = json.loads(self.config.read_text())
        # DNS profiles are local presence work, outside the scope fingerprint.
        row["collection"]["dnsProfiles"] = True
        self.config.write_text(json.dumps(row))
        retained = self.config.read_bytes()
        with self.assertRaisesRegex(SetupProblem, "DNS activity profiles"):
            enable_capability(self.setup, "pihole", "dns_queries", False)
        self.assertEqual(self.config.read_bytes(), retained)

    def test_rejects_capability_the_kind_does_not_offer(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        retained = self.config.read_bytes()
        with self.assertRaisesRegex(SetupProblem, "kind does not offer"):
            enable_capability(self.setup, "pihole", "traffic_counters", True)
        self.assertEqual(self.config.read_bytes(), retained)

    def test_rejects_unifi_dhcp_without_traffic_site(self) -> None:
        self.tcp_configuration()
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        retained = self.config.read_bytes()
        with self.assertRaisesRegex(SetupProblem, "require trafficSite"):
            enable_capability(self.setup, "unifi", "dhcp_scope", True)
        self.assertEqual(self.config.read_bytes(), retained)
        enable_unifi_traffic(self.setup, "default")
        enable_capability(self.setup, "unifi", "dhcp_scope", True)
        configured = Configuration.read(self.config)
        self.assertIn("dhcp_scope", configured.collection.sources[0].capabilities)

    def test_rejects_changed_configuration(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        config = Configuration.read(self.config)
        retained = self.config.read_bytes()
        with patch.object(
            Configuration,
            "read",
            side_effect=[config, replace(config, schedule="changed-elsewhere")],
        ):
            with self.assertRaisesRegex(
                SetupProblem, "Collector configuration changed"
            ):
                enable_capability(self.setup, "pihole", "dhcp_leases", True)
        self.assertEqual(self.config.read_bytes(), retained)

    def test_running_collector_refused(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        config = Configuration.read(self.config)
        retained = self.config.read_bytes()
        with FileReceipts(config):
            with self.assertRaises(BlockingIOError):
                enable_capability(self.setup, "pihole", "dhcp_leases", True)
        self.assertEqual(self.config.read_bytes(), retained)

    def legacy_pihole_with_network(self, network: str) -> None:
        """A legacy ``pihole`` key carrying ``dhcpNetwork``, on a fresh journal."""
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        row = json.loads(self.config.read_text())
        row["stateDirectory"] = str(self.root / ("state-" + str(uuid4())))
        row["collection"]["pihole"]["dhcpNetwork"] = network
        self.config.write_text(json.dumps(row))

    def test_writers_keep_an_existing_dhcp_network(self) -> None:
        self.legacy_pihole_with_network("192.168.1.0/24")
        retained = self.config.read_bytes()
        # connect_pihole never replaces a configured Pi-hole.
        with self.assertRaisesRegex(SetupProblem, "already configured"):
            connect_pihole(self.setup, "https://other.example", "new-password")
        self.assertEqual(self.config.read_bytes(), retained)
        # The rewrite to the sources form moves the settings verbatim.
        enable_capability(self.setup, "pihole", "dhcp_leases", True)
        (entry,) = self.collection_row()["sources"]
        self.assertEqual(entry["settings"]["dhcpNetwork"], "192.168.1.0/24")
        configured = Configuration.read(self.config).collection.pihole
        assert configured is not None
        self.assertEqual(str(configured.dhcp_network), "192.168.1.0/24")
        # Connecting UniFi beside it leaves the Pi-hole settings alone.
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        self.assertEqual(
            self.collection_row()["sources"][0]["settings"]["dhcpNetwork"],
            "192.168.1.0/24",
        )

    def test_enable_capability_sets_the_dhcp_network_under_the_fence(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        legacy = Configuration.read(self.config)
        with patch.object(
            FileReceipts,
            "prepare_scope_change",
            autospec=True,
            side_effect=FileReceipts.prepare_scope_change,
        ) as prepare:
            enable_capability(
                self.setup, "pihole", "dhcp_scope", True, dhcp_network="192.168.1.0/24"
            )
            prepare.assert_called_once()
        (entry,) = self.collection_row()["sources"]
        self.assertEqual(entry["settings"]["dhcpNetwork"], "192.168.1.0/24")
        self.assertIn("dhcp_scope", entry["capabilities"])
        configured = Configuration.read(self.config)
        assert configured.collection.pihole is not None
        self.assertEqual(
            str(configured.collection.pihole.dhcp_network), "192.168.1.0/24"
        )
        with FileReceipts(configured) as receipts:
            self.assertEqual(
                receipts.collection_scope, scope_fingerprint(configured.collection)
            )
        self.assertNotEqual(
            scope_fingerprint(configured.collection),
            scope_fingerprint(legacy.collection),
        )
        # With the capability already on, it is a pure dhcpNetwork change.
        before = scope_fingerprint(configured.collection)
        enable_capability(
            self.setup, "pihole", "dhcp_scope", True, dhcp_network="10.20.0.0/16"
        )
        (entry,) = self.collection_row()["sources"]
        self.assertEqual(entry["settings"]["dhcpNetwork"], "10.20.0.0/16")
        self.assertEqual(
            entry["capabilities"], ["dhcp_scope", "dns_queries", "network_table"]
        )
        changed = Configuration.read(self.config)
        self.assertNotEqual(scope_fingerprint(changed.collection), before)
        with FileReceipts(changed) as receipts:
            self.assertEqual(
                receipts.collection_scope, scope_fingerprint(changed.collection)
            )
        # The same value again writes nothing.
        retained = self.config.read_bytes()
        with patch.object(FileReceipts, "prepare_scope_change") as prepare:
            enable_capability(
                self.setup, "pihole", "dhcp_scope", True, dhcp_network="10.20.0.0/16"
            )
            prepare.assert_not_called()
        self.assertEqual(self.config.read_bytes(), retained)

    def test_dhcp_network_refused_for_unifi_and_invalid_networks(self) -> None:
        self.tcp_configuration()
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        retained = self.config.read_bytes()
        with self.assertRaisesRegex(SetupProblem, "only for a Pi-hole service"):
            enable_capability(
                self.setup,
                "unifi",
                "controller_clients",
                True,
                dhcp_network="192.168.1.0/24",
            )
        for network in ("8.8.8.0/24", "192.168.1.0/31", "fd00::/64", "lan"):
            with self.subTest(network=network):
                with self.assertRaisesRegex(SetupProblem, "dhcpNetwork"):
                    enable_capability(
                        self.setup, "pihole", "dhcp_scope", True, dhcp_network=network
                    )
        self.assertEqual(self.config.read_bytes(), retained)

    def test_enable_capability_command_passes_the_dhcp_network(self) -> None:
        arguments = [
            "bootstrap",
            "--directory",
            str(self.root),
            "enable-capability",
            "--service",
            "pihole",
            "--capability",
            "dhcp_scope",
            "--dhcp-network",
            "192.168.1.0/24",
        ]
        with (
            patch("sys.argv", arguments),
            patch("plowshare_privacy.bootstrap.private_root", return_value=self.root),
            patch.object(Setup, "read", return_value=self.setup),
            patch("plowshare_privacy.bootstrap.enable_capability") as enable,
        ):
            main()
        enable.assert_called_once_with(
            ANY, "pihole", "dhcp_scope", True, dhcp_network="192.168.1.0/24"
        )

    def test_connect_pihole_adds_a_service_in_either_form(self) -> None:
        # Legacy form: the key is added beside the existing legacy UniFi key.
        self.tcp_configuration()
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        self.assertIn("pihole", self.collection_row())
        self.assertNotIn("sources", self.collection_row())
        # Sources form: Pi-hole becomes the first entry, with explicit capabilities.
        self.sources_configuration(
            {"kind": "unifi", "id": "gateway", "settings": self.unifi_settings()}
        )
        connect_pihole(self.setup, "https://pihole.example", "fixture-app-password")
        collection = self.collection_row()
        self.assertNotIn("pihole", collection)
        entry = collection["sources"][0]
        self.assertEqual(
            (entry["kind"], entry["id"], entry["capabilities"]),
            ("pihole", "pihole", ["dns_queries", "network_table"]),
        )
        self.assertEqual(entry["settings"]["origin"], "https://pihole.example")
        configured = Configuration.read(self.config)
        assert configured.collection.pihole is not None
        self.assertEqual(
            [source.id for source in configured.collection.sources],
            ["pihole", "gateway"],
        )
        with self.assertRaisesRegex(SetupProblem, "already configured"):
            connect_pihole(self.setup, "https://other.example", "new-password")

    def test_connect_unifi_adds_a_service_in_either_form(self) -> None:
        self.tcp_configuration()
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        self.assertIn("unifi", self.collection_row())
        self.sources_configuration(
            {
                "kind": "pihole",
                "id": "home-dns",
                "settings": {
                    "origin": "https://pihole.example",
                    "passwordEnvironment": "FIXTURE_PIHOLE_PASSWORD",
                },
            }
        )
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        collection = self.collection_row()
        self.assertNotIn("unifi", collection)
        self.assertEqual(
            [(entry["kind"], entry["id"]) for entry in collection["sources"]],
            [("pihole", "home-dns"), ("unifi", "unifi")],
        )
        configured = Configuration.read(self.config)
        assert configured.collection.unifi is not None
        self.assertEqual(configured.collection.unifi.site_id, self.UNIFI_SITE)

    def test_bind_unifi_gateway_in_either_form(self) -> None:
        device = "00000000-0000-4000-8000-000000000002"
        self.tcp_configuration()
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        bind_unifi_gateway(self.setup, device, "02:00:00:00:00:01")
        legacy = Configuration.read(self.config).collection.unifi
        assert legacy is not None and legacy.gateway is not None
        self.assertEqual(legacy.gateway.device_id, device)
        self.sources_configuration(
            {"kind": "unifi", "id": "gateway", "settings": self.unifi_settings()}
        )
        bind_unifi_gateway(self.setup, device, "02:00:00:00:00:01")
        collection = self.collection_row()
        self.assertNotIn("unifi", collection)
        unifi = Configuration.read(self.config).collection.unifi
        assert unifi is not None and unifi.gateway is not None
        self.assertEqual(unifi.gateway.device_id, device)

    def test_enable_unifi_traffic_in_either_form(self) -> None:
        self.tcp_configuration()
        connect_unifi(
            self.setup, self.UNIFI_BASE, self.UNIFI_SITE, "UNIFI_TEST_KEY", None
        )
        enable_unifi_traffic(self.setup, "default")
        legacy = Configuration.read(self.config).collection.sources[0]
        self.assertIn("traffic_counters", legacy.capabilities)
        # Explicit capabilities gain traffic_counters with the site name.
        before = self.sources_configuration(
            {
                "kind": "unifi",
                "id": "gateway",
                "capabilities": ["controller_clients", "managed_inventory"],
                "settings": self.unifi_settings(),
            }
        )
        enable_unifi_traffic(self.setup, "default")
        collection = self.collection_row()
        self.assertNotIn("unifi", collection)
        after = Configuration.read(self.config)
        service = after.collection.sources[0]
        assert after.collection.unifi is not None
        self.assertEqual(after.collection.unifi.traffic_site, "default")
        self.assertIn("traffic_counters", service.capabilities)
        self.assertEqual(
            scope_fingerprint(after.collection), scope_fingerprint(before.collection)
        )
