"""Private preparation and account provisioning with mocked public SDK replies."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from aiohttp import web
from aiohttp.test_utils import TestServer
from plowshare import Client, Delivery, Reply, TransportError
from plowshare.contracts import (
    AdminServiceTokenCreateRequest,
    AdminStatusDto,
    ProjectAccessDto,
    ProjectGrantDto,
    ProjectMemberAddRequest,
    ProjectMembersDto,
    ServerAccountDto,
    ServiceAccountDto,
    ServiceCredentialDto,
    ServiceScopeDto,
    ServiceTokenDto,
)
from support import EXAMPLES, STAMP

from plowshare_privacy.setup import (
    Setup,
    administrator_login,
    prepare,
    private_write,
    provision,
)

TOKEN_ID = "00000000-0000-0000-0000-000000000001"
PRINCIPAL = "@service/" + TOKEN_ID


class SetupTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        config = json.loads((EXAMPLES / "config.json").read_text())
        config["stateDirectory"] = str(self.root / "collector-state")
        self.collector = self.root / "config.json"
        self.collector.write_text(json.dumps(config))
        self.setup = Setup(
            self.collector,
            EXAMPLES.parent / "network-privacy-watch",
            self.root / "private",
            "privacy-worker",
            "operator",
            "installer",
            "collector",
            30,
        )

    def client(self, *, lost_token: bool = False) -> AsyncMock:
        client = AsyncMock(spec=Client)
        replies = [
            Reply("OK", None, AdminStatusDto(handle="installer", server_admin=True)),
            Reply(
                "OK",
                None,
                (
                    ServerAccountDto(
                        created_at=STAMP,
                        enabled=True,
                        handle="operator",
                        must_change_password=False,
                        server_admin=False,
                    ),
                ),
            ),
            Reply(
                "OK",
                None,
                ProjectAccessDto(
                    history=(),
                    members=(ProjectGrantDto(handle="installer", role="MANAGER"),),
                    permissions=(),
                    project="network-privacy-watch",
                    role="MANAGER",
                ),
            ),
            Reply("OK", None, ()),
            Reply(
                "CREATED",
                None,
                ServiceAccountDto(
                    created_at=STAMP, enabled=True, handle="privacy-worker"
                ),
            ),
            Reply(
                "OK",
                None,
                ProjectMembersDto(
                    project="network-privacy-watch", members=("installer", "operator")
                ),
            ),
            Reply(
                "OK",
                None,
                ProjectMembersDto(
                    project="network-privacy-watch",
                    members=("installer", "operator", "privacy-worker"),
                ),
            ),
            TransportError(Delivery.UNKNOWN, "fixture lost reply")
            if lost_token
            else Reply(
                "CREATED",
                None,
                ServiceCredentialDto(
                    credential="pss_fixture_secret",
                    token=ServiceTokenDto(
                        created_at=STAMP,
                        expires_at="2026-02-01T00:00:00Z",
                        id=TOKEN_ID,
                        name="collector",
                        principal=PRINCIPAL,
                        revoked_at=None,
                        scopes=(
                            ServiceScopeDto(
                                project="network-privacy-watch", role="CONTRIBUTOR"
                            ),
                        ),
                    ),
                ),
            ),
        ]
        client.request.side_effect = replies
        return client

    async def test_service_credential_and_manager_grants_remain_separate(self) -> None:
        prepare(self.setup)
        client = self.client()
        await provision(self.setup, client)
        credential = self.setup.output / "service-token"
        self.assertEqual(credential.read_text(), "pss_fixture_secret\n")
        if os.name == "posix":
            self.assertEqual(credential.stat().st_mode & 0o777, 0o600)
            self.assertEqual(self.setup.output.stat().st_mode & 0o777, 0o700)
        identity = json.loads((self.setup.output / "identity.json").read_text())
        self.assertEqual(identity["principal"], PRINCIPAL)
        manifest = json.loads(
            (self.setup.output / "application/plowshare.json").read_text()
        )
        self.assertEqual(manifest["executionAccount"], PRINCIPAL)
        worker = json.loads(
            (self.setup.output / "application/server/relay-workers.json").read_text()
        )
        self.assertEqual(worker, {"version": 1, "account": PRINCIPAL})
        self.assertEqual(
            manifest["toolGrants"],
            [{"toolScope": "network_scanning", "agent": "privacy_coordinator"}],
        )
        for name in ("tools", "ports"):
            row = json.loads(
                (self.setup.output / f"application/server/{name}.json").read_text()
            )
            for binding in row["bindings"] + row.get("providers", []):
                self.assertEqual(binding["account"], PRINCIPAL)
        requests = [call.args[0] for call in client.request.call_args_list]
        grants = [
            request
            for request in requests
            if isinstance(request, ProjectMemberAddRequest)
        ]
        self.assertEqual(
            [(g.handle, g.role) for g in grants],
            [("operator", "MANAGER"), ("privacy-worker", "CONTRIBUTOR")],
        )
        issuance = next(
            r for r in requests if isinstance(r, AdminServiceTokenCreateRequest)
        )
        self.assertEqual(issuance.scopes[0].role, "CONTRIBUTOR")
        self.assertEqual(len(issuance.scopes), 1)
        self.assertTrue((self.setup.output / "provisioning-completed").exists())

    async def test_lost_credential_reply_leaves_intent_and_blocks_new_mutations(
        self,
    ) -> None:
        prepare(self.setup)
        with self.assertRaises(TransportError):
            await provision(self.setup, self.client(lost_token=True))
        self.assertTrue((self.setup.output / "provisioning-intent.json").exists())
        self.assertFalse((self.setup.output / "service-token").exists())
        client = self.client()
        with self.assertRaises(FileExistsError):
            await provision(self.setup, client)
        self.assertEqual(client.request.call_count, 4)

    async def test_non_admin_and_unknown_human_cannot_provision(self) -> None:
        prepare(self.setup)
        client = self.client()
        client.request.side_effect = [
            Reply("OK", None, AdminStatusDto(handle="installer", server_admin=False))
        ]
        with self.assertRaises(ValueError):
            await provision(self.setup, client)
        self.assertEqual(client.request.call_count, 1)
        self.assertFalse((self.setup.output / "provisioning-intent.json").exists())
        client = self.client()
        client.request.side_effect = [
            Reply("OK", None, AdminStatusDto(handle="installer", server_admin=True)),
            Reply("OK", None, ()),
        ]
        with self.assertRaises(ValueError):
            await provision(self.setup, client)
        self.assertEqual(client.request.call_count, 2)
        self.assertFalse((self.setup.output / "provisioning-intent.json").exists())

    def test_preparation_deduplicates_management_and_preserves_existing_output(
        self,
    ) -> None:
        setup = replace(self.setup, manager="installer")
        prepare(setup)
        manifest = json.loads((setup.output / "application/plowshare.json").read_text())
        self.assertEqual(
            manifest["access"]["accounts"],
            [
                {"handle": "privacy-worker", "role": "CONTRIBUTOR"},
                {"handle": "installer", "role": "MANAGER"},
            ],
        )
        with self.assertRaises(ValueError):
            prepare(setup)
        with self.assertRaises(FileExistsError):
            private_write(setup.output / "setup.json", "overwrite")

    def test_blank_setup_fields_prompt_and_identity_cannot_be_the_human(self) -> None:
        path = self.root / "setup-input.json"
        row = json.loads((EXAMPLES / "setup.json").read_text())
        path.write_text(json.dumps(row))
        with patch(
            "builtins.input",
            side_effect=[
                str(self.collector),
                str(self.setup.source),
                str(self.setup.output),
                "privacy-worker",
                "operator",
                "installer",
            ],
        ):
            result = Setup.read(path, prompt=True)
        self.assertEqual(result.service, "privacy-worker")
        prepare(result)
        saved = json.loads((result.output / "setup.json").read_text())
        saved["serviceAccount"] = "operator"
        path.write_text(json.dumps(saved))
        with self.assertRaises(ValueError):
            Setup.read(path)

    def test_linked_source_and_mismatched_project_refuse_before_output(self) -> None:
        source = self.root / "source"
        source.mkdir()
        (source / "plowshare.json").symlink_to(self.setup.source / "plowshare.json")
        with self.assertRaises(ValueError):
            prepare(replace(self.setup, source=source))
        self.assertFalse(self.setup.output.exists())
        config = json.loads(self.collector.read_text())
        config["project"] = "other-project"
        self.collector.write_text(json.dumps(config))
        with self.assertRaises(ValueError):
            prepare(self.setup)
        self.assertFalse(self.setup.output.exists())

    async def test_setup_login_uses_public_boundary_and_revokes_on_failure(
        self,
    ) -> None:
        requests: list[str] = []

        async def login(request: web.Request) -> web.Response:
            requests.append(request.path)
            self.assertEqual(request.headers["X-Plowshare-Token-Delivery"], "body")
            self.assertEqual(
                await request.json(),
                {"handle": "installer", "password": "fixture-password"},
            )
            return web.json_response(
                {
                    "access": "fixture-access",
                    "refresh": "fixture-refresh",
                    "mustChangePassword": False,
                }
            )

        async def logout(request: web.Request) -> web.Response:
            requests.append(request.path)
            self.assertEqual(request.headers["Authorization"], "Bearer fixture-access")
            return web.Response(status=204)

        application = web.Application()
        application.router.add_post("/v1/auth/login", login)
        application.router.add_post("/v1/auth/logout", logout)
        async with TestServer(application) as server:
            with self.assertRaisesRegex(ValueError, "fixture provisioning failure"):
                async with administrator_login(
                    str(server.make_url("")), "installer", "fixture-password"
                ) as token:
                    self.assertEqual(token, "fixture-access")
                    raise ValueError("fixture provisioning failure")
        self.assertEqual(requests, ["/v1/auth/login", "/v1/auth/logout"])

    async def test_login_does_not_follow_a_redirect_or_retry(self) -> None:
        calls: list[str] = []

        async def handle(request: web.Request) -> web.Response:
            calls.append(request.path)
            return web.Response(status=307, headers={"Location": "/redirected"})

        application = web.Application()
        application.router.add_post("/v1/auth/login", handle)
        application.router.add_post("/redirected", handle)
        async with TestServer(application) as server:
            with self.assertRaises(ValueError):
                async with administrator_login(
                    str(server.make_url("")), "installer", "fixture-password"
                ):
                    self.fail("Redirect cannot authenticate setup")
        self.assertEqual(calls, ["/v1/auth/login"])
