from __future__ import annotations

import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from plowshare import (
    Credentials,
    Delivery,
    LoginRequired,
    SignIn,
    Standing,
    Tokens,
    TransportError,
    UserSession,
)
from test_agile import FakePort, configuration, uid

from plowshare_agile.auth import CliCredential, agent_credential, user_credential
from plowshare_agile.board import Board
from plowshare_agile.cli import execute, parser
from plowshare_agile.journal import Intent, Journal
from plowshare_agile.model import Configuration, TicketFields


class UserAuthority:
    """Mock the SDK HTTP capability, keeping its real store/renewal lifecycle."""

    def __init__(self, origin: str) -> None:
        self.origin = origin
        self.rotations = 0

    async def login(self, handle: str, password: str) -> SignIn:
        return SignIn(Tokens("user-access-before", "refresh-before"), False)

    async def standing(self, tokens: Tokens) -> Standing:
        return Standing(tokens.access != "user-access-before")

    async def refresh(self, tokens: Tokens) -> Tokens:
        self.rotations += 1
        return Tokens("user-access-after", "refresh-after")

    async def logout(self, tokens: Tokens) -> None:
        return None


class AuthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.config = configuration(self.root)

    def config_file(self) -> Path:
        path = self.root / "config.toml"
        path.write_text(
            "\n".join(f'{k} = "{v}"' for k, v in asdict(self.config).items())
        )
        return path

    async def test_agent_mode_requires_explicit_service_token_and_never_reads_users(
        self,
    ) -> None:
        for value in (None, "user-access", "pss_bad\nsecond", " pss_bad"):
            with self.subTest(value=value), patch.dict(os.environ, {}, clear=True):
                if value is not None:
                    os.environ[self.config.token_environment] = value
                with self.assertRaises(ValueError):
                    agent_credential(self.config)
        with patch.dict(os.environ, {self.config.token_environment: "pss_fixture"}):
            self.assertEqual(agent_credential(self.config).token, "pss_fixture")
        path = self.config_file()
        with (
            patch.dict(os.environ, {self.config.token_environment: "pss_fixture"}),
            patch(
                "plowshare_agile.cli.user_credential", new_callable=AsyncMock
            ) as user,
            patch("plowshare_agile.cli.Client.connect", new_callable=AsyncMock),
            patch("plowshare_agile.cli.SdkBoardPort", return_value=FakePort()),
            redirect_stdout(io.StringIO()),
        ):
            await execute(
                parser().parse_args(["--config", str(path), "tickets", "list"])
            )
            user.assert_not_awaited()

    async def test_user_mode_delegates_selection_and_authentication_to_sdk(
        self,
    ) -> None:
        session = UserSession(
            self.config.origin, "alice", Tokens("user-access", "user-refresh")
        )
        with patch("plowshare_agile.auth.Credentials.resolve") as resolve:
            resolve.return_value.authenticated = AsyncMock(return_value=session)
            with patch.dict(
                os.environ,
                {
                    self.config.token_environment: "pss_ignored",
                    "PLOWSHARE_PASSWORD": "ignored",
                },
            ):
                credential = await user_credential(
                    self.config, account="alice", connection="Work"
                )
            resolve.assert_called_once_with(
                self.config.origin, account="alice", connection="Work"
            )
            resolve.return_value.authenticated.assert_awaited_once_with()
        self.assertEqual(credential, CliCredential("user-access", "alice"))
        self.assertNotIn(credential.token, repr(credential))

    async def test_missing_user_login_never_falls_back_to_agent_or_password(
        self,
    ) -> None:
        with (
            patch.dict(
                os.environ,
                {
                    "PLOWSHARE_CONFIG_DIR": str(self.root / "login"),
                    self.config.token_environment: "pss_ignored",
                    "PLOWSHARE_PASSWORD": "ignored",
                },
                clear=True,
            ),
            patch(
                "plowshare_agile.cli.Client.connect", new_callable=AsyncMock
            ) as connect,
        ):
            with self.assertRaises(LoginRequired):
                await execute(
                    parser().parse_args(
                        [
                            "--config",
                            str(self.config_file()),
                            "--is-user",
                            "--account",
                            "alice",
                            "tickets",
                            "list",
                        ]
                    )
                )
            connect.assert_not_awaited()

    async def test_cli_modes_reject_invalid_combinations_before_authentication(
        self,
    ) -> None:
        with patch(
            "plowshare_agile.cli.user_credential", new_callable=AsyncMock
        ) as user:
            for arguments in (
                ["--account", "alice", "tickets", "list"],
                ["--connection", "Fixture", "tickets", "list"],
                [
                    "--is-user",
                    "serve",
                    "--bind",
                    "fixture.invalid",
                    "--port",
                    "9000",
                    "--public-origin",
                    "https://fixture.invalid",
                    "--access-token-environment",
                    "WEB_KEY",
                ],
            ):
                with self.assertRaises(ValueError):
                    await execute(
                        parser().parse_args(
                            ["--config", str(self.config_file()), *arguments]
                        )
                    )
            user.assert_not_awaited()

    async def test_cli_recovers_exact_pending_request_after_user_renewal(self) -> None:
        path = self.config_file()
        auth = UserAuthority(self.config.origin)
        store = Credentials(
            self.config.origin, account="alice", directory=self.root / "login"
        )
        await store.login("alice", "fixture-password", auth)
        journal = Journal(self.config, "user-access-before", user_account="alice")
        intent = Intent(
            "create", "ticket", uid(70), TicketFields("Retained"), body="# Exact\r\n"
        )
        port = FakePort()
        port.error = TransportError(Delivery.UNKNOWN, "fixture uncertain write")
        with self.assertRaises(TransportError):
            await Board(port, journal).submit(intent)
        retained = journal._path(intent.request_id).read_bytes()
        port.error = None
        with (
            patch.dict(
                os.environ,
                {"PLOWSHARE_CONFIG_DIR": str(self.root / "login")},
                clear=True,
            ),
            patch("plowshare.credentials.HttpAuthentication", return_value=auth),
            patch(
                "plowshare_agile.cli.Client.connect", new_callable=AsyncMock
            ) as connect,
            patch("plowshare_agile.cli.SdkBoardPort", return_value=port),
            redirect_stdout(io.StringIO()),
        ):
            await execute(
                parser().parse_args(
                    [
                        "--config",
                        str(path),
                        "--is-user",
                        "request",
                        "recover",
                        intent.request_id,
                    ]
                )
            )
            self.assertEqual(
                connect.call_args.args, (self.config.origin, "user-access-after")
            )
        self.assertEqual(auth.rotations, 1)
        self.assertEqual((await store.session()).tokens.access, "user-access-after")
        self.assertEqual(port.calls, [intent, intent])
        self.assertEqual(
            Journal(self.config, "user-access-third", user_account="alice").read(
                intent.request_id
            ),
            (intent, "confirmed"),
        )
        self.assertNotIn(b"user-access-before", retained)
        self.assertNotIn(b"user-access-after", retained)

    async def test_identity_and_scope_changes_cannot_recover_or_migrate_legacy(
        self,
    ) -> None:
        intent = Intent(
            "create", "ticket", uid(71), TicketFields("Pending"), body="# Exact"
        )
        Journal(self.config, "before", user_account="alice").prepare(intent)
        different: list[tuple[Configuration, str | None]] = [
            (self.config, "bob"),
            (self.config, None),
            (replace(self.config, origin="https://other.invalid"), "alice"),
            (replace(self.config, project="other"), "alice"),
            (replace(self.config, ticket_version=uid(99)), "alice"),
        ]
        for config, account in different:
            port = FakePort()
            with self.assertRaises(ValueError):
                await Board(
                    port, Journal(config, "after", user_account=account)
                ).recover(intent.request_id)
            self.assertEqual(port.calls, [])
        legacy = replace(intent, request_id=uid(72))
        Journal(self.config, "legacy").prepare(legacy)
        with self.assertRaises(ValueError):
            Journal(self.config, "legacy", user_account="alice").read(legacy.request_id)
        self.assertEqual(
            Journal(self.config, "legacy").read(legacy.request_id), (legacy, "pending")
        )


if __name__ == "__main__":
    unittest.main()
