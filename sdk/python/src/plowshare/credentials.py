"""Shared native user-login storage and noninteractive session selection.

Version-1 files, connection keys, migration markers and mkdir locks are shared
with the Node SDK. Lock ownership covers reload, renewal intent, HTTP rotation
and atomic persistence. No consumer may rotate a cached refresh token itself.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import os
import re
import stat
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

from .auth import (
    Authentication,
    AuthenticationError,
    AuthenticationUnavailable,
    HttpAuthentication,
    SignIn,
    Tokens,
    _AuthenticationNotSubmitted,
    account_handle,
    canonical_origin,
)


class CredentialError(AuthenticationError):
    """Saved authentication state cannot safely be used."""


class LoginRequired(CredentialError):
    """An explicit login is needed; no other account or credential is selected."""


@dataclass(frozen=True)
class UserSession:
    origin: str
    handle: str
    tokens: Tokens


@dataclass(frozen=True)
class NamedConnection:
    key: str
    name: str
    server: str
    account: str
    reconnect: bool


@dataclass(frozen=True)
class _Configuration:
    connections: tuple[NamedConnection, ...] = ()
    selected: str | None = None


@dataclass(frozen=True)
class _Saved:
    origin: str
    handle: str
    tokens: Tokens
    renewing: bool = False


@dataclass(frozen=True)
class _Migration:
    origin: str
    account: str


def configuration_directory() -> Path:
    """Use the same root precedence as Node; overrides must be absolute."""
    value = os.environ.get("PLOWSHARE_CONFIG_DIR")
    if value is None:
        value = os.environ.get("PLOWSHARE_DESKTOP_CONFIG")
    root = Path(value) if value is not None else Path.home() / ".plowshare"
    if not root.is_absolute():
        raise CredentialError("Configure an absolute Plowshare configuration directory")
    return root


def connection_key(origin: str, account: str) -> str:
    payload = json.dumps(
        [canonical_origin(origin), account_handle(account)],
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode()).hexdigest()


def _directory(path: Path) -> None:
    try:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or path.is_symlink():
            raise CredentialError("Authentication state needs real private directories")
        if os.name == "posix" and info.st_uid != os.getuid():
            raise CredentialError("Authentication state belongs to another OS user")
        path.chmod(0o700)
    except OSError:
        raise CredentialError("Unable to access private authentication state") from None


def _read(path: Path) -> object:
    """Bounded, no-follow file boundary. Absence alone returns None."""
    try:
        flags = os.O_RDONLY | (os.O_NOFOLLOW if hasattr(os, "O_NOFOLLOW") else 0)
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if (
                path.is_symlink()
                or not stat.S_ISREG(info.st_mode)
                or info.st_size > 2_000_000
                or (os.name == "posix" and info.st_uid != os.getuid())
            ):
                raise CredentialError(
                    "Authentication state must be a bounded private file"
                )
            if os.name == "posix":
                os.fchmod(stream.fileno(), 0o600)
            data = stream.read(2_000_001)
        if len(data) > 2_000_000:
            raise ValueError
        value: object = json.loads(data)
        # JSON null is not absence: don't let it enable a legacy fallback.
        if value is None:
            raise ValueError
        return value
    except FileNotFoundError:
        return None
    except (OSError, ValueError, UnicodeError):
        raise CredentialError(
            "Authentication state is unreadable; sign in explicitly"
        ) from None


def _fields(
    value: object, required: set[str], optional: set[str] | None = None
) -> dict[str, object]:
    if not isinstance(value, dict) or not all(isinstance(k, str) for k in value):
        raise CredentialError("Invalid authentication state")
    names = set(value)
    if not required <= names or names - required - (optional or set()):
        raise CredentialError("Invalid authentication state fields")
    return {k: v for k, v in value.items() if isinstance(k, str)}


def _version(value: object) -> None:
    if type(value) is not int or value != 1:
        raise CredentialError("Unsupported authentication state version")


def _saved(value: object) -> _Saved:
    row = _fields(value, {"version", "origin", "handle", "tokens"}, {"renewing"})
    _version(row["version"])
    origin = row["origin"]
    if not isinstance(origin, str) or canonical_origin(origin) != origin:
        raise CredentialError("Invalid saved authentication origin")
    handle = account_handle(row["handle"])
    tokens = _fields(row["tokens"], {"access", "refresh"})
    access, refresh = tokens["access"], tokens["refresh"]
    if (
        not isinstance(access, str)
        or not isinstance(refresh, str)
        or access.startswith("pss_")
    ):
        raise CredentialError("A saved ordinary user session is required")
    renewing = row.get("renewing", False)
    if type(renewing) is not bool:
        raise CredentialError("Invalid renewal state")
    return _Saved(origin, handle, Tokens(access, refresh), renewing)


def _migration(value: object) -> _Migration:
    row = _fields(value, {"version", "origin", "account"})
    _version(row["version"])
    origin = row["origin"]
    if not isinstance(origin, str) or canonical_origin(origin) != origin:
        raise CredentialError("Invalid credential migration origin")
    return _Migration(origin, account_handle(row["account"]))


def _connection_name(value: object) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > 100
        or any(ord(c) < 32 or ord(c) == 127 for c in value)
    ):
        raise CredentialError("Choose a valid named connection")
    return value


def _configuration(value: object) -> _Configuration:
    if value is None:
        return _Configuration()
    row = _fields(
        value,
        {"version", "connections"},
        {"selected", "fileStoreDefault", "connectionSetupPending"},
    )
    _version(row["version"])
    raw = row["connections"]
    if not isinstance(raw, list) or len(raw) > 100:
        raise CredentialError("Invalid connection list")
    entries: list[NamedConnection] = []
    for item in raw:
        fields = _fields(item, {"key", "name", "server", "account", "reconnect"})
        origin = fields["server"]
        if not isinstance(origin, str) or canonical_origin(origin) != origin:
            raise CredentialError("Invalid named server")
        account = account_handle(fields["account"])
        reconnect = fields["reconnect"]
        key = connection_key(origin, account)
        if fields["key"] != key or type(reconnect) is not bool:
            raise CredentialError("Connection identity differs from its key")
        entries.append(
            NamedConnection(
                key, _connection_name(fields["name"]), origin, account, reconnect
            )
        )
    if len({v.name for v in entries}) != len(entries) or len(
        {v.key for v in entries}
    ) != len(entries):
        raise CredentialError("Duplicate named connection or identity")
    selected = row.get("selected")
    if selected is not None and (
        not isinstance(selected, str) or selected not in {v.key for v in entries}
    ):
        raise CredentialError("Selected connection is missing")
    if "selected" in row and selected is None:
        raise CredentialError("Invalid selected connection")
    # Validate shared optional configuration before projecting only identities.
    if "fileStoreDefault" in row:
        default = _fields(row["fileStoreDefault"], {"alias", "root"})
        alias, root = default["alias"], default["root"]
        if (
            not isinstance(alias, str)
            or not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", alias)
            or not isinstance(root, str)
            or not Path(root).is_absolute()
            or len(root) > 4096
            or any(ord(c) < 32 or ord(c) == 127 for c in root)
        ):
            raise CredentialError(
                "Invalid FileStore default in connection configuration"
            )
    if "connectionSetupPending" in row and (
        row["connectionSetupPending"] is not True
        or entries
        or selected is not None
        or "fileStoreDefault" not in row
    ):
        raise CredentialError("Invalid pending connection setup")
    return _Configuration(tuple(entries), selected)


def _sync_directory(path: Path) -> None:
    if os.name == "posix":
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _write(path: Path, value: object) -> None:
    temporary = path.with_name(path.name + "." + str(uuid4()))
    try:
        # Replacing a symlink would not follow it, but refusing it also preserves
        # the owner's diagnostic evidence rather than silently repairing state.
        if path.is_symlink():
            raise CredentialError("Authentication files must not be symlinks")
        with os.fdopen(
            os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb"
        ) as output:
            output.write(
                json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
            )
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        _sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


class Credentials:
    """One origin/account's SDK-owned saved user credentials.

    ``session`` loads local state, not authority. ``authenticated`` checks the
    public session endpoint and renews only after an explicit 401. No method
    reconnects a WebSocket or replays application operations. All clients use the
    same account lock, including Node clients; stale locks are never stolen.
    """

    def __init__(
        self,
        origin: str,
        *,
        account: str | None = None,
        directory: Path | None = None,
        lock_timeout: float = 10,
    ):
        self.origin = canonical_origin(origin)
        self.account = None if account is None else account_handle(account)
        self.root = configuration_directory() if directory is None else directory
        if (
            not self.root.is_absolute()
            or not math.isfinite(lock_timeout)
            or lock_timeout <= 0
        ):
            raise CredentialError(
                "Configure an absolute state directory and positive lock timeout"
            )
        self.lock_timeout = lock_timeout
        self._legacy = [self.root / "credentials"]
        if (
            directory is None
            and "PLOWSHARE_CONFIG_DIR" not in os.environ
            and "PLOWSHARE_DESKTOP_CONFIG" not in os.environ
        ):
            xdg = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
            if not xdg.is_absolute():
                raise CredentialError("XDG_CONFIG_HOME must be absolute")
            self._legacy.append(xdg / "plowshare" / "credentials")

    @classmethod
    def resolve(
        cls,
        origin: str | None = None,
        *,
        account: str | None = None,
        connection: str | None = None,
        directory: Path | None = None,
    ) -> Credentials:
        """Read the native named registry with Node's explicit-selector precedence."""
        root = configuration_directory() if directory is None else directory
        if not root.is_absolute():
            raise CredentialError("Configure an absolute authentication directory")
        _directory(root)
        config = _configuration(_read(root / "config.json"))
        name = (
            connection
            if connection is not None
            else os.environ.get("PLOWSHARE_CONNECTION")
        )
        server = origin if origin is not None else os.environ.get("PLOWSHARE_URL")
        handle = (
            account
            if account is not None
            else os.environ.get("PLOWSHARE_ACCOUNT", os.environ.get("PLOWSHARE_HANDLE"))
        )
        if name is not None:
            _connection_name(name)
            chosen = next((r for r in config.connections if r.name == name), None)
            if chosen is None:
                raise CredentialError("Unknown named connection")
        elif server is None and handle is None:
            chosen = next(
                (r for r in config.connections if r.key == config.selected), None
            )
        else:
            chosen = None
        if chosen is not None:
            if (server is not None and canonical_origin(server) != chosen.server) or (
                handle is not None and handle != chosen.account
            ):
                raise CredentialError(
                    "Connection conflicts with the explicit server/account"
                )
            server, handle = chosen.server, chosen.account
        if server is None:
            raise CredentialError("Select an explicit server or named connection")
        return cls(server, account=handle, directory=directory)

    def _for_account(self, account: str) -> Credentials:
        chosen = Credentials(
            self.origin,
            account=account,
            directory=self.root,
            lock_timeout=self.lock_timeout,
        )
        chosen._legacy = self._legacy
        return chosen

    @property
    def _scope(self) -> Path:
        if self.account is None:
            raise CredentialError("An account must be resolved before storage access")
        return self.root / "connections" / connection_key(self.origin, self.account)

    @property
    def _file(self) -> Path:
        return (
            self._scope
            / "credentials"
            / (hashlib.sha256(self.origin.encode()).hexdigest() + ".json")
        )

    def _directories(self) -> None:
        for path in (
            self.root,
            self.root / "connections",
            self._scope,
            self._scope / "credentials",
        ):
            _directory(path)

    @asynccontextmanager
    async def _lock(self, file: Path) -> AsyncIterator[None]:
        lock = file.with_name(file.name + ".lock")
        deadline = time.monotonic() + self.lock_timeout
        while True:
            try:
                lock.mkdir(mode=0o700)
                break
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise CredentialError(
                        "Credential store is busy; inspect interrupted locks, never steal them"
                    ) from None
                await asyncio.sleep(0.025)
        try:
            yield
        finally:
            lock.rmdir()

    def _mark_migrated(self) -> None:
        _write(
            self._scope / "credential-migration.json",
            {"version": 1, "origin": self.origin, "account": self.account},
        )

    async def _migrate(self) -> None:
        marker = _read(self._scope / "credential-migration.json")
        if marker is not None:
            claim = _migration(marker)
            if claim.origin != self.origin or claim.account != self.account:
                raise CredentialError(
                    "Credential migration belongs to another identity"
                )
            return  # A logout marker forbids resurrection from an old copy.
        if _read(self._file) is not None:
            return
        for directory in dict.fromkeys(self._legacy):
            if not directory.exists():
                continue
            _directory(directory)
            file = directory / self._file.name
            async with self._lock(file):
                raw = _read(file)
                if raw is None:
                    continue
                saved = _saved(raw)
                if saved.origin != self.origin:
                    raise CredentialError("Legacy authentication origin differs")
                if saved.handle != self.account:
                    continue
                if saved.renewing:
                    raise CredentialError(
                        "Legacy renewal was interrupted; sign in explicitly"
                    )
                os.replace(file, self._file)
                _sync_directory(self._file.parent)
                # Move rather than copy a single-use refresh token, under both
                # SDK locks. Mark before deleting/reusing any legacy source.
                self._mark_migrated()
                _sync_directory(directory)
                return

    def _load(self) -> _Saved:
        raw = _read(self._file)
        if raw is None:
            raise LoginRequired("No saved login; sign in explicitly")
        saved = _saved(raw)
        if saved.origin != self.origin or saved.handle != self.account:
            raise CredentialError("Saved session belongs to another identity")
        if saved.renewing:
            raise LoginRequired(
                "Previous renewal was interrupted; sign in again, never replay refresh"
            )
        return saved

    def _save(self, saved: _Saved) -> None:
        _write(
            self._file,
            {
                "version": 1,
                "origin": self.origin,
                "handle": saved.handle,
                "tokens": {
                    "access": saved.tokens.access,
                    "refresh": saved.tokens.refresh,
                },
                **({"renewing": True} if saved.renewing else {}),
            },
        )

    async def _chosen(self) -> Credentials:
        if self.account is not None:
            return self
        _directory(self.root)
        accounts: set[str] = set()
        parent = self.root / "connections"
        if parent.exists() or parent.is_symlink():
            _directory(parent)
            directories = list(parent.iterdir())
            if len(directories) > 1024:
                raise CredentialError(
                    "Too many local identities; choose an account explicitly"
                )
            for path in directories:
                if not re.fullmatch(r"[0-9a-f]{64}", path.name):
                    continue
                _directory(path)
                raw_marker = _read(path / "credential-migration.json")
                if raw_marker is not None:
                    marker = _migration(raw_marker)
                    if path.name != connection_key(marker.origin, marker.account):
                        raise CredentialError(
                            "Migration identity differs from its directory"
                        )
                    if marker.origin == self.origin:
                        accounts.add(marker.account)
                folder = path / "credentials"
                if folder.exists() or folder.is_symlink():
                    _directory(folder)
                    raw = _read(
                        folder
                        / (hashlib.sha256(self.origin.encode()).hexdigest() + ".json")
                    )
                    if raw is not None:
                        saved = _saved(raw)
                        if saved.origin != self.origin or path.name != connection_key(
                            saved.origin, saved.handle
                        ):
                            raise CredentialError(
                                "Saved identity differs from its directory"
                            )
                        accounts.add(saved.handle)
        if len(accounts) > 1:
            raise CredentialError(
                "Multiple accounts are saved; choose an account or named connection"
            )
        if accounts:
            return self._for_account(next(iter(accounts)))
        # No scoped identity/marker exists. A legacy identity may be moved, never
        # guessed from a token or a filename. Account locks revalidate the record.
        for folder in dict.fromkeys(self._legacy):
            if folder.exists() or folder.is_symlink():
                _directory(folder)
                raw = _read(
                    folder
                    / (hashlib.sha256(self.origin.encode()).hexdigest() + ".json")
                )
                if raw is not None:
                    saved = _saved(raw)
                    if saved.origin != self.origin:
                        raise CredentialError("Legacy origin differs")
                    return self._for_account(saved.handle)
        raise LoginRequired("No saved login; sign in explicitly")

    def _authentication(self, auth: Authentication | None) -> Authentication:
        selected = HttpAuthentication(self.origin) if auth is None else auth
        if selected.origin != self.origin:
            raise CredentialError("Authentication capability belongs to another server")
        return selected

    async def session(self) -> UserSession:
        chosen = await self._chosen()
        chosen._directories()
        async with chosen._lock(chosen._file):
            await chosen._migrate()
            saved = chosen._load()
            return UserSession(saved.origin, saved.handle, saved.tokens)

    async def login(
        self, handle: str, password: str, auth: Authentication | None = None
    ) -> SignIn:
        account_handle(handle)
        if self.account is not None and self.account != handle:
            raise CredentialError("Login conflicts with the selected account")
        chosen = self._for_account(handle)
        authentication = self._authentication(auth)
        chosen._directories()
        async with chosen._lock(chosen._file):
            result = await authentication.login(handle, password)
            if not result.must_change_password and result.tokens.refresh is not None:
                chosen._save(_Saved(self.origin, handle, result.tokens))
                chosen._mark_migrated()
            return result

    async def _rotate(self, auth: Authentication, saved: _Saved) -> Tokens:
        self._save(_Saved(saved.origin, saved.handle, saved.tokens, True))
        try:
            tokens = await auth.refresh(saved.tokens)
            if tokens.refresh is None or tokens.access.startswith("pss_"):
                raise CredentialError("Invalid renewed user session")
        except _AuthenticationNotSubmitted:
            # Only the native pre-connect boundary can establish non-delivery.
            self._save(saved)
            raise AuthenticationUnavailable(
                "Authentication server unreachable; saved session retained"
            ) from None
        except asyncio.CancelledError:
            raise  # The durable renewing fence remains set.
        except Exception:
            raise LoginRequired(
                "Renewal did not complete; sign in again, never replay refresh"
            ) from None
        self._save(_Saved(saved.origin, saved.handle, tokens))
        return tokens

    async def renew(
        self, auth: Authentication | None = None, *, rejected_access: str | None = None
    ) -> Tokens:
        if rejected_access is not None:
            Tokens(rejected_access)
        authentication = self._authentication(auth)
        chosen = await self._chosen()
        chosen._directories()
        async with chosen._lock(chosen._file):
            await chosen._migrate()
            saved = chosen._load()
            if rejected_access is not None and saved.tokens.access != rejected_access:
                return saved.tokens  # Another native client already rotated it.
            return await chosen._rotate(authentication, saved)

    async def authenticated(self, auth: Authentication | None = None) -> UserSession:
        """Validate one saved identity; renew only for an explicit access refusal."""
        authentication = self._authentication(auth)
        # Pin account inference once: concurrent local selection changes cannot
        # send recovery into another account halfway through authentication.
        chosen = await self._chosen()
        session = await chosen.session()
        before = await authentication.standing(session.tokens)
        if before.signed_in:
            if before.must_change_password:
                raise LoginRequired("Account requires an interactive password change")
            return session
        await chosen.renew(authentication, rejected_access=session.tokens.access)
        current = await chosen.session()
        after = await authentication.standing(current.tokens)
        if not after.signed_in or after.must_change_password:
            raise LoginRequired("Saved session requires explicit login")
        return current

    async def logout(self, auth: Authentication | None = None) -> None:
        authentication = self._authentication(auth)
        chosen = await self._chosen()
        chosen._directories()
        async with chosen._lock(chosen._file):
            await chosen._migrate()
            saved = chosen._load()
            tokens = await chosen._rotate(authentication, saved)
            await authentication.logout(tokens)
            chosen._mark_migrated()
            chosen._file.unlink()
            _sync_directory(chosen._file.parent)
