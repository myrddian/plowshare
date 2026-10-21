"""Public authentication HTTP boundary; application operations remain WebSocket-only.

Requests never follow redirects or retry. Only a native DNS/connection-refused
failure before HTTP submission proves non-delivery. Cancellation or a failure
after connect is ambiguous, particularly for single-use refresh credentials.
"""

from __future__ import annotations

import asyncio
import http.client
import ipaddress
import json
import math
import re
import socket
import threading
from dataclasses import dataclass, field
from http.cookies import CookieError, SimpleCookie
from typing import Protocol
from urllib.parse import urlsplit


class AuthenticationError(ValueError):
    """Authentication failed without exposing credentials or response bodies."""


class AuthenticationUnavailable(AuthenticationError):
    """An authentication transport failure; delivery may be uncertain."""


class _AuthenticationNotSubmitted(AuthenticationUnavailable):
    """Private native pre-connect proof, never inferred from an injected error."""


def canonical_origin(value: str) -> str:
    """Canonical ASCII HTTP origin, compatible with the Node credential keys.

    Unicode DNS input must use its explicit ASCII/IDNA spelling: stdlib IDNA2003
    and browser UTS46 can resolve the same Unicode input to different servers.
    Never silently select a different identity/server through that conversion.
    """
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        if (
            not isinstance(value, str)
            or len(value) > 2048
            or any(ord(c) <= 32 or ord(c) == 127 for c in value)
            or parsed.scheme not in {"http", "https"}
            or not host
            or not host.isascii()
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
            or "\\" in value
        ):
            raise ValueError
        if ":" in host:
            host = "[" + ipaddress.IPv6Address(host).compressed + "]"
        elif not re.fullmatch(r"[A-Za-z0-9._-]+", host):
            raise ValueError
        elif re.fullmatch(
            r"(?:[0-9]+|0[xX][0-9a-fA-F]+)", host.rstrip(".").split(".")[-1]
        ):
            # WHATWG accepts shortened/octal/hex IPv4 spellings. Python does not
            # resolve all of them identically; require canonical dotted decimal.
            if str(ipaddress.IPv4Address(host)) != host:
                raise ValueError
        port = parsed.port
        suffix = (
            ""
            if port is None or port == (443 if parsed.scheme == "https" else 80)
            else ":" + str(port)
        )
        return parsed.scheme + "://" + host + suffix
    except (ValueError, TypeError, AttributeError):
        raise AuthenticationError(
            "Use an explicit ASCII HTTP(S) server origin without credentials or path"
        ) from None


def account_handle(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", value):
        raise AuthenticationError("Choose a valid ordinary account handle")
    return value


def _secret(value: object) -> str:
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 4096
        or any(ord(c) < 33 or ord(c) > 126 or c in ';,"\\' for c in value)
    ):
        raise AuthenticationError("Invalid authentication credential")
    return value


@dataclass(frozen=True)
class Tokens:
    access: str = field(repr=False)
    refresh: str | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        _secret(self.access)
        if self.refresh is not None:
            _secret(self.refresh)


@dataclass(frozen=True)
class Standing:
    signed_in: bool
    must_change_password: bool = False


@dataclass(frozen=True)
class SignIn:
    tokens: Tokens
    must_change_password: bool
    setup_required: bool = False


class Authentication(Protocol):
    """Narrow account authentication capability; never an application transport."""

    @property
    def origin(self) -> str: ...

    async def login(self, handle: str, password: str) -> SignIn: ...
    async def standing(self, tokens: Tokens) -> Standing: ...
    async def refresh(self, tokens: Tokens) -> Tokens: ...
    async def logout(self, tokens: Tokens) -> None: ...


@dataclass(frozen=True)
class _Response:
    status: int
    headers: tuple[tuple[str, str], ...] = field(repr=False)
    body: bytes = field(repr=False)

    def header(self, name: str) -> str | None:
        return next((v for k, v in self.headers if k.lower() == name.lower()), None)


@dataclass
class _ExchangeState:
    cancelled: threading.Event = field(default_factory=threading.Event)
    sock: socket.socket | None = None


class HttpAuthentication:
    """Native, redirect-free HTTP auth with bounded bodies and verified TLS.

    Blocking HTTP runs in a worker thread. Cancelling its await can leave a
    refresh running on the server; Credentials persists the renewal fence before
    starting it and never lets the worker write a returned pair after cancellation.
    """

    def __init__(
        self,
        origin: str,
        *,
        timeout: float = 30,
        access_cookie: str = "ps_access",
        refresh_cookie: str = "ps_refresh",
    ):
        self.origin = canonical_origin(origin)
        if not math.isfinite(timeout) or timeout <= 0:
            raise AuthenticationError("Authentication timeout must be positive")
        for name in (access_cookie, refresh_cookie):
            if not re.fullmatch(r"[A-Za-z0-9_]{1,64}", name):
                raise AuthenticationError("Invalid authentication cookie name")
        self.timeout = timeout
        self.access_cookie, self.refresh_cookie = access_cookie, refresh_cookie

    async def _request(
        self, method: str, path: str, headers: dict[str, str], body: bytes | None = None
    ) -> _Response:
        parsed = urlsplit(self.origin)
        host = parsed.hostname
        assert host is not None  # canonical_origin validated it.
        connection = (
            http.client.HTTPSConnection(host, parsed.port, timeout=self.timeout)
            if parsed.scheme == "https"
            else http.client.HTTPConnection(host, parsed.port, timeout=self.timeout)
        )
        state = _ExchangeState()
        try:
            async with asyncio.timeout(self.timeout):
                return await asyncio.to_thread(
                    self._exchange, connection, state, method, path, headers, body
                )
        except (TimeoutError, asyncio.CancelledError) as error:
            state.cancelled.set()
            # Wake a blocked response read. The worker owns close(); if still in
            # connect it checks the event before submitting any HTTP request.
            sock = state.sock
            if sock is not None:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass  # Already closed; cleanup must not hide cancellation.
            if isinstance(error, asyncio.CancelledError):
                raise
            raise AuthenticationUnavailable("Authentication deadline expired") from None

    def _exchange(
        self,
        connection: http.client.HTTPConnection,
        state: _ExchangeState,
        method: str,
        path: str,
        headers: dict[str, str],
        body: bytes | None,
    ) -> _Response:
        try:
            try:
                connection.connect()
            except (socket.gaierror, ConnectionRefusedError):
                raise _AuthenticationNotSubmitted(
                    "Could not reach the configured authentication server"
                ) from None
            # Keep the socket even when HTTPConnection detaches it for a
            # Connection: close response; cancellation must still wake read().
            state.sock = connection.sock
            if state.cancelled.is_set():
                raise AuthenticationUnavailable(
                    "Authentication cancelled before submission"
                )
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read(65537)
            if len(payload) > 65536:
                raise AuthenticationError(
                    "Authentication response exceeds its size limit"
                )
            return _Response(response.status, tuple(response.getheaders()), payload)
        except AuthenticationError:
            raise
        except (OSError, http.client.HTTPException, ValueError):
            raise AuthenticationUnavailable(
                "Authentication transport failed; delivery may be uncertain"
            ) from None
        finally:
            connection.close()

    async def login(self, handle: str, password: str) -> SignIn:
        account_handle(handle)
        if not isinstance(password, str) or not password or len(password) > 4096:
            raise AuthenticationError("A bounded nonempty password is required")
        response = await self._request(
            "POST",
            "/v1/auth/login",
            {"Content-Type": "application/json", "X-Plowshare-Token-Delivery": "body"},
            json.dumps({"handle": handle, "password": password}).encode(),
        )
        if response.status != 200:
            raise AuthenticationError("Sign-in refused or unavailable")
        try:
            raw: object = json.loads(response.body)
            if not isinstance(raw, dict):
                raise ValueError
            access = _secret(raw.get("access"))
            refresh = raw.get("refresh")
            checked_refresh = None if refresh is None else _secret(refresh)
            must_change = raw.get("mustChangePassword")
            if type(must_change) is not bool or (
                not must_change and checked_refresh is None
            ):
                raise ValueError
            return SignIn(
                Tokens(access, checked_refresh),
                must_change,
                response.header("X-Plowshare-Setup-Required") == "true",
            )
        except (ValueError, UnicodeError):
            raise AuthenticationError("Invalid sign-in response") from None

    async def standing(self, tokens: Tokens) -> Standing:
        response = await self._request(
            "GET",
            "/v1/auth/session",
            {"Authorization": "Bearer " + tokens.access},
        )
        if response.status == 401:
            return Standing(False)
        if response.status != 204:
            raise AuthenticationError("Session status unavailable")
        return Standing(
            True, response.header("X-Plowshare-Must-Change-Password") == "true"
        )

    async def refresh(self, tokens: Tokens) -> Tokens:
        if tokens.refresh is None:
            raise AuthenticationError("Session requires interactive login")
        response = await self._request(
            "POST",
            "/v1/auth/refresh",
            {"Cookie": self.refresh_cookie + "=" + tokens.refresh},
        )
        if response.status != 204:
            raise AuthenticationError(
                "Session renewal refused or unavailable; sign in again"
            )
        jar: dict[str, str] = {}
        try:
            for name, value in response.headers:
                if name.lower() != "set-cookie":
                    continue
                cookie = SimpleCookie()
                # A Set-Cookie field defines one cookie; later semicolon fields
                # are attributes, never a second token or a replacement value.
                first, *attributes = value.split(";")
                if any(
                    a.strip().partition("=")[0]
                    in {self.access_cookie, self.refresh_cookie}
                    for a in attributes
                ):
                    raise ValueError
                cookie.load(first)
                for key in (self.access_cookie, self.refresh_cookie):
                    if key in cookie:
                        if key in jar:
                            raise ValueError
                        jar[key] = _secret(cookie[key].value)
            return Tokens(jar[self.access_cookie], jar[self.refresh_cookie])
        except (CookieError, KeyError, ValueError):
            raise AuthenticationError(
                "Renewal did not return an unambiguous credential pair"
            ) from None

    async def logout(self, tokens: Tokens) -> None:
        response = await self._request(
            "POST",
            "/v1/auth/logout",
            {"Authorization": "Bearer " + tokens.access},
        )
        if response.status != 204:
            raise AuthenticationError(
                "Logout failed; retain the saved session for recovery"
            )
