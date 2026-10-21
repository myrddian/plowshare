"""Shared login lifecycle through isolated files and the public HTTP boundary."""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from plowshare import (
    AuthenticationError,
    AuthenticationUnavailable,
    CredentialError,
    Credentials,
    HttpAuthentication,
    LoginRequired,
    SignIn,
    Standing,
    Tokens,
    connection_key,
)
from plowshare.auth import canonical_origin


class Authority:
    origin = "https://auth.example"

    def __init__(self) -> None:
        self.rotations = 0
        self.status = True
        self.failure: Exception | None = None
        self.started = asyncio.Event()
        self.resume = asyncio.Event()
        self.resume.set()

    async def login(self, handle: str, password: str) -> SignIn:
        return SignIn(Tokens("access0", "refresh0"), False)

    async def standing(self, tokens: Tokens) -> Standing:
        return Standing(self.status or tokens.access != "access0")

    async def refresh(self, tokens: Tokens) -> Tokens:
        self.rotations += 1
        self.started.set()
        await self.resume.wait()
        if self.failure:
            raise self.failure
        return Tokens("access" + str(self.rotations), "refresh" + str(self.rotations))

    async def logout(self, tokens: Tokens) -> None:
        return None


class CredentialTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.auth = Authority()
        self.store = Credentials(self.auth.origin, account="alice", directory=self.root)
        await self.store.login("alice", "fixture-password", self.auth)
        self.file = (
            self.root
            / "connections"
            / connection_key(self.auth.origin, "alice")
            / "credentials"
            / (hashlib.sha256(self.auth.origin.encode()).hexdigest() + ".json")
        )

    async def test_concurrent_access_refusal_rotates_once_and_persists_first(
        self,
    ) -> None:
        self.auth.status = False
        other = Credentials(self.auth.origin, account="alice", directory=self.root)
        results = await asyncio.gather(
            self.store.authenticated(self.auth), other.authenticated(self.auth)
        )
        self.assertEqual(self.auth.rotations, 1)
        self.assertEqual([v.tokens.access for v in results], ["access1", "access1"])
        self.assertEqual((await self.store.session()).tokens, results[0].tokens)
        if os.name == "posix":
            self.assertEqual(self.file.stat().st_mode & 0o777, 0o600)
            self.assertEqual(self.file.parent.stat().st_mode & 0o777, 0o700)

    async def test_custom_connection_refusal_is_not_native_non_delivery_proof(
        self,
    ) -> None:
        self.auth.failure = ConnectionRefusedError(
            errno.ECONNREFUSED, "secret diagnostic"
        )
        with self.assertRaises(LoginRequired) as caught:
            await self.store.renew(self.auth)
        self.assertNotIn("secret", str(caught.exception))
        with self.assertRaises(LoginRequired):
            await self.store.renew(self.auth, rejected_access="older")
        self.assertEqual(self.auth.rotations, 1)
        self.assertTrue(json.loads(self.file.read_text())["renewing"])

    async def test_cancellation_fences_refresh_until_explicit_login(self) -> None:
        self.auth.resume.clear()
        task = asyncio.create_task(self.store.renew(self.auth))
        await self.auth.started.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        with self.assertRaises(LoginRequired):
            await self.store.session()
        await self.store.login("alice", "fixture-password", self.auth)
        self.assertEqual((await self.store.session()).handle, "alice")

    async def test_failed_persistence_does_not_publish_rotated_pair(self) -> None:
        from plowshare import credentials

        write = credentials._write

        def fail_pair(path: Path, value: object) -> None:
            if isinstance(value, dict) and value.get("tokens") == {
                "access": "access1",
                "refresh": "refresh1",
            }:
                raise OSError("fixture persistence failure")
            write(path, value)

        with patch.object(credentials, "_write", fail_pair):
            with self.assertRaises(OSError):
                await self.store.renew(self.auth)
        with self.assertRaises(LoginRequired):
            await self.store.session()
        self.assertTrue(json.loads(self.file.read_text())["renewing"])

    async def test_lock_timeout_never_steals_node_lock(self) -> None:
        lock = self.file.with_suffix(".json.lock")
        lock.mkdir()
        short = Credentials(
            self.auth.origin, account="alice", directory=self.root, lock_timeout=0.03
        )
        with self.assertRaisesRegex(CredentialError, "busy"):
            await short.renew(self.auth)
        self.assertTrue(lock.is_dir())
        self.assertEqual(self.auth.rotations, 0)
        lock.rmdir()

    async def test_multiple_accounts_and_explicit_identity_conflicts(self) -> None:
        await Credentials(self.auth.origin, account="bob", directory=self.root).login(
            "bob", "fixture", self.auth
        )
        with self.assertRaisesRegex(CredentialError, "Multiple"):
            await Credentials(self.auth.origin, directory=self.root).session()
        with self.assertRaises(CredentialError):
            await self.store.login("bob", "fixture", self.auth)
        self.assertEqual((await self.store.session()).handle, "alice")

    async def test_named_selection_and_conflicts(self) -> None:
        key = connection_key(self.auth.origin, "alice")
        (self.root / "config.json").write_text(
            json.dumps(
                {
                    "version": 1,
                    "selected": key,
                    "connections": [
                        {
                            "key": key,
                            "name": "work",
                            "server": self.auth.origin,
                            "account": "alice",
                            "reconnect": True,
                        }
                    ],
                }
            )
        )
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                Credentials.resolve(connection="work", directory=self.root).account,
                "alice",
            )
            self.assertEqual(
                Credentials.resolve(directory=self.root).origin, self.auth.origin
            )
            with self.assertRaises(CredentialError):
                Credentials.resolve(
                    "https://other.example", connection="work", directory=self.root
                )
            with self.assertRaises(CredentialError):
                Credentials.resolve(
                    account="bob", connection="work", directory=self.root
                )
            self.assertIsNone(
                Credentials.resolve(self.auth.origin, directory=self.root).account
            )

    async def test_logout_marker_prevents_legacy_resurrection(self) -> None:
        old = self.file.read_bytes()
        await self.store.logout(self.auth)
        legacy = self.root / "credentials"
        legacy.mkdir()
        (legacy / self.file.name).write_bytes(old)
        with self.assertRaises(LoginRequired):
            await Credentials(self.auth.origin, directory=self.root).session()
        self.assertEqual(self.auth.rotations, 1)

    async def test_migration_moves_legacy_pair_and_validates_marker(self) -> None:
        marker = self.file.parent.parent / "credential-migration.json"
        marker.unlink()
        legacy = self.root / "credentials"
        legacy.mkdir()
        source = legacy / self.file.name
        self.file.replace(source)
        self.assertEqual((await self.store.session()).handle, "alice")
        self.assertFalse(source.exists())
        self.assertTrue(marker.exists())
        marker.write_text(
            json.dumps({"version": 1, "origin": self.auth.origin, "account": "bob"})
        )
        with self.assertRaises(CredentialError):
            await self.store.session()

    async def test_malformed_foreign_and_symlink_state_fail_closed(self) -> None:
        original = self.file.read_text()
        for raw in (
            "null",
            "{}",
            original.replace('"version": 1', '"version": 2'),
            original.replace('"alice"', '"bob"'),
        ):
            with self.subTest(raw=raw[:10]):
                self.file.write_text(raw)
                with self.assertRaises(CredentialError):
                    await self.store.session()
        self.file.unlink()
        target = self.root / "target"
        target.write_text(original)
        self.file.symlink_to(target)
        with self.assertRaises(CredentialError):
            await self.store.session()

    async def test_outage_during_status_does_not_rotate(self) -> None:
        async def unavailable(tokens: Tokens) -> Standing:
            raise AuthenticationUnavailable("fixture outage")

        with patch.object(self.auth, "standing", unavailable):
            with self.assertRaises(AuthenticationUnavailable):
                await self.store.authenticated(self.auth)
        self.assertEqual(self.auth.rotations, 0)
        self.assertEqual((await self.store.session()).tokens.access, "access0")


class HttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.requests: list[tuple[str, dict[str, str], bytes]] = []
        self.status = 204
        self.cookies = [
            "ps_access=new-access; Path=/",
            "ps_refresh=new-refresh; HttpOnly",
        ]
        self.body = b""
        self.hold = False
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.server = await asyncio.start_server(self.respond, "127.0.0.1", 0)
        self.addAsyncCleanup(self.close)
        self.origin = "http://127.0.0.1:" + str(self.server.sockets[0].getsockname()[1])
        self.auth = HttpAuthentication(self.origin, timeout=1)

    async def close(self) -> None:
        self.release.set()
        self.server.close()
        await self.server.wait_closed()

    async def respond(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            raw = await reader.readuntil(b"\r\n\r\n")
            lines = raw.decode().split("\r\n")
            headers = dict(line.split(": ", 1) for line in lines[1:] if ": " in line)
            body = await reader.readexactly(int(headers.get("Content-Length", "0")))
            self.requests.append((lines[0], headers, body))
            self.started.set()
            if self.hold:
                await self.release.wait()
            fields = "".join("Set-Cookie: " + c + "\r\n" for c in self.cookies)
            writer.write(
                f"HTTP/1.1 {self.status} Fixture\r\n{fields}Content-Length: {len(self.body)}\r\nConnection: close\r\n\r\n".encode()
                + self.body
            )
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    async def test_login_status_refresh_logout_http_contract(self) -> None:
        self.status = 200
        self.body = json.dumps(
            {"access": "access", "refresh": "refresh", "mustChangePassword": False}
        ).encode()
        result = await self.auth.login("alice", "fixture-password")
        self.assertEqual(result.tokens, Tokens("access", "refresh"))
        request = self.requests[-1]
        self.assertEqual(request[1]["X-Plowshare-Token-Delivery"], "body")
        self.assertEqual(json.loads(request[2])["handle"], "alice")
        self.status, self.body = 204, b""
        self.assertTrue((await self.auth.standing(result.tokens)).signed_in)
        renewed = await self.auth.refresh(result.tokens)
        self.assertEqual(renewed, Tokens("new-access", "new-refresh"))
        self.assertEqual(self.requests[-1][1]["Cookie"], "ps_refresh=refresh")
        self.assertNotIn("Authorization", self.requests[-1][1])
        await self.auth.logout(renewed)
        self.assertEqual(self.requests[-1][1]["Authorization"], "Bearer new-access")
        self.status = 401
        self.assertFalse((await self.auth.standing(renewed)).signed_in)

    async def test_redirect_outage_and_invalid_cookie_pair_are_not_replayed(
        self,
    ) -> None:
        for status, cookies in (
            (302, self.cookies),
            (503, self.cookies),
            (204, [self.cookies[0]]),
            (204, self.cookies + [self.cookies[0]]),
            (204, ["ps_access=first; ps_access=second", "ps_refresh=refresh"]),
        ):
            with self.subTest(status=status, cookies=len(cookies)):
                self.status, self.cookies = status, cookies
                before = len(self.requests)
                with self.assertRaises(AuthenticationError):
                    await self.auth.refresh(Tokens("access", "refresh"))
                self.assertEqual(len(self.requests), before + 1)

    async def test_http_timeout_retains_fence_without_worker_replay(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            store = Credentials(self.origin, account="alice", directory=Path(root))
            fake = Authority()
            fake.origin = self.origin
            await store.login("alice", "fixture", fake)
            self.hold = True
            with self.assertRaises(LoginRequired):
                await store.renew(HttpAuthentication(self.origin, timeout=0.05))
            with self.assertRaises(LoginRequired):
                await store.renew(self.auth)
            self.assertEqual(len(self.requests), 1)
            self.release.set()

    async def test_malformed_login_and_restricted_login(self) -> None:
        self.status = 200
        for body in (
            b"not-json",
            b"{}",
            b'{"access":"access","mustChangePassword":false}',
        ):
            self.body = body
            with self.assertRaises(AuthenticationError):
                await self.auth.login("alice", "fixture")
        self.body = b'{"access":"restricted","refresh":null,"mustChangePassword":true}'
        with tempfile.TemporaryDirectory() as root:
            store = Credentials(self.origin, account="alice", directory=Path(root))
            result = await store.login("alice", "fixture", self.auth)
            self.assertTrue(result.must_change_password)
            with self.assertRaises(LoginRequired):
                await store.session()

    async def test_native_preconnect_refusal_restores_saved_pair(self) -> None:
        # Reserve then close an ephemeral endpoint: failure occurs before HTTP.
        self.server.close()
        await self.server.wait_closed()
        with tempfile.TemporaryDirectory() as root:
            store = Credentials(self.origin, account="alice", directory=Path(root))
            fake = Authority()
            fake.origin = self.origin
            await store.login("alice", "fixture", fake)
            with self.assertRaises(AuthenticationUnavailable):
                await store.renew(self.auth)
            self.assertEqual((await store.session()).tokens.access, "access0")


class ValueTests(unittest.TestCase):
    def test_origins_and_secrets(self) -> None:
        self.assertEqual(
            canonical_origin("HTTPS://EXAMPLE.COM:443/"), "https://example.com"
        )
        self.assertEqual(canonical_origin("http://[::1]:80"), "http://[::1]")
        for value in (
            "https://u:p@example.com",
            "https://example.com/path",
            "http://faß.de",
            "file:///tmp",
            "https://example.com\n",
            "https://example.com?x=y",
            "http://127.1",
            "http://0x7f000001",
        ):
            with self.assertRaises(AuthenticationError):
                canonical_origin(value)
        self.assertNotIn("private", repr(Tokens("private-access", "private-refresh")))
        for value in ("bad\nheader", "bad;cookie", "", "bad\\cookie"):
            with self.assertRaises(AuthenticationError):
                Tokens(value)
