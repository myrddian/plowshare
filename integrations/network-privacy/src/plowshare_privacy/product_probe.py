"""Fixed, unauthenticated HTTP observations of one currently monitored device.

These are information reads, never remote-control or exploit requests. No model
chooses an address, method, path or payload. Redirects, proxies, cookies and
credentials are disabled. A successful information read does not prove that
control commands are permitted. Only bounded classifications survive; response
bodies, identifiers and headers are not retained.
"""

from __future__ import annotations

import ipaddress
import json
from dataclasses import dataclass
from typing import Protocol

from aiohttp import ClientError, ClientSession, ClientTimeout, DummyCookieJar

from .contracts import integer, object_fields, private_ipv4, text, timestamp, utc_now

MAX_BYTES = 8192
CHECKS = ("http_root", "sony_system_information")
OUTCOMES = (
    "http_response",
    "information_without_credentials",
    "authentication_required",
    "api_error",
    "unavailable",
    "oversize",
)


@dataclass(frozen=True)
class ProbeObservation:
    check: str
    checked_at: str
    outcome: str
    http_status: int | None

    def __post_init__(self) -> None:
        if self.check not in CHECKS or self.outcome not in OUTCOMES:
            raise ValueError("Unsupported device check")
        timestamp(self.checked_at)
        if self.http_status is not None:
            integer(self.http_status, 100, 599)

    @classmethod
    def decode(cls, value: object) -> ProbeObservation:
        row = object_fields(value, {"check", "checked_at", "outcome", "http_status"})
        return cls(
            text(row["check"], 64),
            timestamp(row["checked_at"]),
            text(row["outcome"], 64),
            integer(row["http_status"], 100, 599)
            if row["http_status"] is not None
            else None,
        )


class ProductProbe(Protocol):
    async def inspect(
        self, address: str, sony: bool
    ) -> tuple[ProbeObservation, ...]: ...


class HttpProductProbe:
    async def inspect(self, address: str, sony: bool) -> tuple[ProbeObservation, ...]:
        if str(ipaddress.IPv4Address(address)) != address or not private_ipv4(address):
            raise ValueError("Device checks require a private IPv4 target")
        results: list[ProbeObservation] = []
        async with ClientSession(
            timeout=ClientTimeout(total=3),
            cookie_jar=DummyCookieJar(),
            trust_env=False,
            auto_decompress=False,
        ) as session:
            for check in CHECKS if sony else CHECKS[:1]:
                path = "/" if check == "http_root" else "/sony/system"
                # getPowerStatus reads the display state; it never changes it.
                payload = (
                    None
                    if check == "http_root"
                    else {
                        "method": "getPowerStatus",
                        "id": 1,
                        "params": [],
                        "version": "1.0",
                    }
                )
                status: int | None = None
                outcome = "unavailable"
                try:
                    async with session.request(
                        "GET" if payload is None else "POST",
                        f"http://{address}:80{path}",
                        json=payload,
                        allow_redirects=False,
                    ) as response:
                        status = response.status
                        body = b""
                        while len(body) <= MAX_BYTES:
                            chunk = await response.content.read(
                                MAX_BYTES + 1 - len(body)
                            )
                            if not chunk:
                                break
                            body += chunk
                        if len(body) > MAX_BYTES:
                            outcome = "oversize"
                        elif status in (401, 403):
                            outcome = "authentication_required"
                        elif payload is None:
                            outcome = "http_response"
                        else:
                            outcome = sony_response(status, body)
                except (ClientError, TimeoutError, OSError):
                    # Silence is an observation from this vantage, never closure.
                    outcome = "unavailable"
                results.append(ProbeObservation(check, utc_now(), outcome, status))
        return tuple(results)


def sony_response(status: int, body: bytes) -> str:
    """Require the expected typed response, not merely HTTP 200 or a result key."""
    try:
        value = json.loads(body)
    except (ValueError, UnicodeError, RecursionError):
        return "http_response"
    if (
        not isinstance(value, dict)
        or type(value.get("id")) is not int
        or value.get("id") != 1
    ):
        return "http_response"
    error = value.get("error")
    if isinstance(error, list) and error:
        return "authentication_required" if error[0] == 403 else "api_error"
    if error is not None:
        return "http_response"
    result = value.get("result")
    if (
        status == 200
        and isinstance(result, list)
        and len(result) == 1
        and isinstance(result[0], dict)
        and result[0].get("status") in ("active", "standby")
    ):
        return "information_without_credentials"
    return "http_response"
