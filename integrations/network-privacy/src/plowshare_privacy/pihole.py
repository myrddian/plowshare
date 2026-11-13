"""Read-only Pi-hole v6 enrichment through its documented HTTP API.

One collection owns one short-lived session. Only selected client addresses enter
returned evidence; network-table associations are bounded and freshness checked.
No Pi-hole settings, blocking rules or DHCP leases are changed. POST/DELETE are
used only to open/close this adapter's session, and are never replayed.
"""

from __future__ import annotations

import asyncio
import ipaddress
import math
import os
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

import aiohttp

from .addressing import AddressingSample
from .contracts import (
    Capability,
    DeviceIdentity,
    DnsObservation,
    DnsWindow,
    PiHolePlan,
    integer,
    items,
    object_fields,
    parse_json,
    text,
    timestamp,
)
from .dns_activity import QuerySample
from .identity_source import IdentitySample
from .limits import BATCH_SIZE, MAX_TARGETS


@dataclass(frozen=True)
class DeviceData:
    devices: tuple[DeviceIdentity, ...]
    dns: tuple[DnsObservation, ...]
    window: DnsWindow | None
    issues: tuple[str, ...]
    # Addressing facts from the same session, when addressing is enabled.
    addressing: AddressingSample | None = None


class DeviceDataSource(Protocol):
    """Return bounded observations for the selected addresses, with explicit coverage gaps."""

    async def read(self, targets: tuple[str, ...], observed_at: str) -> DeviceData: ...


class PiHoleUnavailable(ValueError):
    """A fixed, credential-free failure description; never include HTTP bodies or URLs."""


def epoch(value: object) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or not 0 <= value <= 4_102_444_800
    ):
        raise ValueError("Invalid Pi-hole timestamp")
    return float(value)


def instant(value: float) -> str:
    return (
        datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")
    )


def password(plan: PiHolePlan) -> str:
    """Read an explicitly configured secret before any external connection."""
    if plan.password_environment is not None:
        value = os.environ.get(plan.password_environment)
    else:
        path = plan.password_file
        if (
            path is None
            or not path.is_file()
            or path.is_symlink()
            or path.resolve() != path
        ):
            raise ValueError("Set the configured private Pi-hole password file")
        if os.name == "posix" and path.stat().st_mode & 0o077:
            raise ValueError(
                "Pi-hole password file must have private permissions (0600)"
            )
        with path.open("rb") as source:
            raw = source.read(4098)
        if len(raw) > 4097:
            raise ValueError("Pi-hole password file exceeds its size limit")
        value = raw.decode("utf-8").removesuffix("\n")
    if value is None or value != value.strip():
        raise ValueError("Set the configured Pi-hole application password")
    return text(value, 4096)


# Pi-hole reports upstream blocking separately. Count only statuses that
# explicitly indicate blocking in this sample, including local CNAME rules.
_BLOCKED_STATUSES = frozenset(
    {
        "GRAVITY",
        "REGEX",
        "DENYLIST",
        "GRAVITY_CNAME",
        "REGEX_CNAME",
        "DENYLIST_CNAME",
        "SPECIAL_DOMAIN",
        "EXTERNAL_BLOCKED_IP",
        "EXTERNAL_BLOCKED_NULL",
        "EXTERNAL_BLOCKED_NXRA",
        "EXTERNAL_BLOCKED_EDE15",
    }
)


# What a ``collection.pihole`` key has always read; direct callers keep it.
_DEFAULT_CAPABILITIES: frozenset[Capability] = frozenset(
    {"network_table", "dns_queries"}
)


class PiHoleV6:
    """One Pi-hole service, reading only its enabled capabilities.

    Without ``network_table`` no ``network/devices`` request is made, and
    without ``dns_queries`` no ``queries`` request is made; a read that needs
    neither opens no session. ``service`` names the configured service.
    """

    def __init__(
        self,
        plan: PiHolePlan,
        *,
        capabilities: frozenset[Capability] = _DEFAULT_CAPABILITIES,
        service: str = "pihole",
        application_password: str | None = None,
    ):
        self.plan = plan
        self.capabilities = capabilities
        self.service = service
        # Configuration errors fail at startup, rather than silently dropping a source.
        self._password = (
            password(plan)
            if application_password is None
            else text(application_password, 4096)
        )
        if self._password != self._password.strip():
            raise ValueError(
                "Pi-hole application password cannot contain edge whitespace"
            )

    async def _request(
        self,
        session: aiohttp.ClientSession,
        method: str,
        path: str,
        *,
        sid: str | None = None,
        parameters: dict[str, str] | None = None,
    ) -> object:
        """HTTP/JSON boundary: size, timeout, redirects and response status are fenced."""
        async with session.request(
            method,
            self.plan.origin + "/api/" + path,
            headers={"X-FTL-SID": sid} if sid else {},
            params=parameters,
            json={"password": self._password} if method == "POST" else None,
            allow_redirects=False,
        ) as response:
            if response.status in {401, 403}:
                raise PiHoleUnavailable(
                    "authentication refused; check the application password"
                )
            if response.status != (204 if method == "DELETE" else 200):
                raise PiHoleUnavailable(
                    "API request failed; check Pi-hole availability"
                )
            if method == "DELETE":
                return None
            content = bytearray()
            async for chunk in response.content.iter_chunked(8192):
                content.extend(chunk)
                if len(content) > 1_048_576:
                    raise PiHoleUnavailable("API response exceeded the size limit")
            return parse_json(bytes(content))

    def _devices(
        self, raw: object, targets: tuple[str, ...], observed: float
    ) -> IdentitySample:
        if not isinstance(raw, dict):
            raise ValueError("Expected a Pi-hole network table")
        result: dict[str, list[DeviceIdentity]] = {}
        issues: set[str] = set()
        scope = frozenset(targets)
        values = items(raw.get("devices"), 256)
        partial = "identity_partial: Pi-hole returned an invalid network-table record"
        if len(values) == 256:
            issues.add("identity_partial: Pi-hole network-device limit reached")
        for value in values:
            try:
                if not isinstance(value, dict):
                    raise ValueError("Expected Pi-hole device metadata")
                addresses = items(value.get("ips"), 16)
                hardware = value.get("hwaddr")
                mac = (
                    hardware.lower()
                    if isinstance(hardware, str)
                    and re.fullmatch(r"[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}", hardware)
                    and hardware.lower()
                    not in {"00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff"}
                    else None
                )
            except ValueError:
                issues.add(partial)
                continue
            if len(addresses) == 16:
                issues.add("identity_partial: Pi-hole per-device address limit reached")
            for address in addresses:
                try:
                    if not isinstance(address, dict):
                        raise ValueError("Expected Pi-hole address metadata")
                    ip = str(ipaddress.ip_address(text(address.get("ip"), 64)))
                    if ip not in scope:
                        continue
                    seen = epoch(address.get("lastSeen"))
                    if (
                        seen > observed + 5
                        or observed - seen > self.plan.max_identity_age
                    ):
                        issues.add(
                            "identity_stale: Pi-hole address associations are outside the freshness window"
                        )
                        continue
                    hostname = address.get("name")
                    vendor = value.get("macVendor")
                    identity = DeviceIdentity.decode(
                        {
                            "address": ip,
                            "device_id": "mac:" + mac if mac else "ip:" + ip,
                            "label": None,
                            "hostname": hostname
                            if hostname not in ("", None)
                            else None,
                            "mac": mac,
                            "vendor": vendor if vendor not in ("", None) else None,
                            "source": "pihole-v6",
                            "observed_at": instant(observed),
                            "last_seen": instant(seen),
                        }
                    )
                except ValueError:
                    issues.add(partial)
                    continue
                result.setdefault(ip, []).append(identity)
        devices: list[DeviceIdentity] = []
        ambiguous: set[str] = set()
        for address, candidates in sorted(result.items()):
            if len({c.device_id for c in candidates}) != 1:
                ambiguous.add(address)
                issues.add(
                    "identity_ambiguous: Pi-hole lists multiple devices for a selected address"
                )
                continue
            devices.append(max(candidates, key=lambda device: device.last_seen or ""))
        if {d.address for d in devices} != set(targets):
            issues.add(
                "identity_unavailable: some selected addresses have no fresh unambiguous Pi-hole association"
            )
        return IdentitySample(
            tuple(devices), frozenset(ambiguous), tuple(sorted(issues))
        )

    def _queries(
        self,
        raw: object,
        address: str,
        start: float,
        end: float,
        *,
        half_open: bool = False,
    ) -> QuerySample:
        row = object_fields(
            raw,
            {"queries", "recordsFiltered"},
            {
                "took",
                "cursor",
                "recordsTotal",
                "draw",
                "earliest_timestamp",
                "earliest_timestamp_disk",
            },
        )
        values = items(row["queries"], self.plan.query_limit)
        available = integer(row["recordsFiltered"], len(values), 2**48 - 1)
        counts: Counter[str] = Counter()
        blocked: Counter[str] = Counter()
        ids: set[int] = set()
        truncated = available > len(values)
        issues: set[str] = set()
        for value in values:
            query = object_fields(
                value,
                {"id", "time", "domain", "client"},
                {
                    "type",
                    "cname",
                    "status",
                    "dnssec",
                    "reply",
                    "list_id",
                    "upstream",
                    "ede",
                },
            )
            client = object_fields(query["client"], {"ip"}, {"name"})
            at = epoch(query["time"])
            if (
                str(ipaddress.ip_address(text(client["ip"], 64))) != address
                or not start <= at <= end
                or (half_open and at == end)
            ):
                raise ValueError(
                    "Pi-hole returned queries outside the selected scope/window"
                )
            identity = integer(query["id"], 0, 2**53 - 1)
            if identity in ids:
                raise ValueError("Duplicate Pi-hole query")
            ids.add(identity)
            try:
                domain = text(query["domain"], 254).lower().removesuffix(".")
                observation = DnsObservation.decode(
                    {"device": address, "domain": domain, "queries": 1}
                )
            except ValueError:
                issues.add(
                    "dns_partial: some Pi-hole query names cannot be normalized as DNS names"
                )
                continue
            counts[observation.domain] += 1
            status = query.get("status")
            if half_open and isinstance(status, str) and status in _BLOCKED_STATUSES:
                blocked[observation.domain] += 1
        earliest = row.get("earliest_timestamp")
        if earliest is None or epoch(earliest) == 0 or epoch(earliest) > start:
            issues.add(
                "dns_partial: Pi-hole retained history does not establish the full requested window"
            )
        if truncated:
            issues.add(
                "dns_truncated: Pi-hole query sample reached the configured per-device limit"
            )
        return QuerySample(
            tuple(
                DnsObservation(address, domain, count)
                for domain, count in sorted(counts.items())
            ),
            len(values),
            available,
            tuple(sorted(issues)),
            dict(blocked),
        )

    async def read(self, targets: tuple[str, ...], observed_at: str) -> DeviceData:
        sample, _, _ = await self._read(targets, observed_at, queries=True)
        return sample

    async def identify(self, targets: tuple[str, ...], observed_at: str) -> DeviceData:
        """Operator-only identity checks; no DNS history is fetched or retained."""
        if len(targets) > BATCH_SIZE:
            raise ValueError("Pi-hole requires at most 32 unique selected addresses")
        sample, _, _ = await self._read(targets, observed_at, queries=False)
        return sample

    async def presence(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        """Read the network table once for the watched scope, without querying DNS logs."""
        sample, ambiguous, _ = await self._read(targets, observed_at, queries=False)
        return IdentitySample(sample.devices, ambiguous, sample.issues)

    async def activity(
        self, targets: tuple[str, ...], since: str, observed_at: str
    ) -> tuple[tuple[QuerySample, ...], tuple[str, ...]]:
        """Read query logs for confirmed clients only, without re-reading the device table."""
        data, _, samples = await self._read(
            targets, observed_at, queries=True, since=since
        )
        return samples, data.issues

    async def _read(
        self,
        targets: tuple[str, ...],
        observed_at: str,
        *,
        queries: bool,
        since: str | None = None,
    ) -> tuple[DeviceData, frozenset[str], tuple[QuerySample, ...]]:
        limit = BATCH_SIZE if queries and since is None else MAX_TARGETS
        if len(targets) > limit or len(set(targets)) != len(targets):
            raise ValueError(
                f"Pi-hole requires at most {limit} unique selected addresses"
            )
        targets = tuple(str(ipaddress.ip_address(address)) for address in targets)
        end = datetime.fromisoformat(timestamp(observed_at)).timestamp()
        start = (
            datetime.fromisoformat(timestamp(since)).timestamp()
            if since is not None
            else end - self.plan.lookback_seconds
        )
        if start >= end:
            raise ValueError("Pi-hole query window must advance")
        # A disabled capability is not offered: it is never requested and
        # never reported as a gap.
        table = since is None and "network_table" in self.capabilities
        queries = queries and "dns_queries" in self.capabilities
        samples: tuple[QuerySample, ...] = ()
        identity = IdentitySample()
        dns: tuple[DnsObservation, ...] = ()
        window: DnsWindow | None = None
        issues: set[str] = set()
        sid: str | None = None
        if not table and not queries:
            return DeviceData((), (), None, ()), frozenset(), ()
        async with aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=self.plan.timeout),
            cookie_jar=aiohttp.DummyCookieJar(),
            trust_env=False,
        ) as session:
            try:
                auth = object_fields(
                    await self._request(session, "POST", "auth"), {"session"}, {"took"}
                )
                accepted = object_fields(
                    auth["session"],
                    {"valid", "sid"},
                    {"totp", "csrf", "validity", "message"},
                )
                if accepted["valid"] is not True:
                    raise PiHoleUnavailable(
                        "authentication refused; use a Pi-hole application password"
                    )
                sid = text(accepted["sid"], 256)
                if table:
                    try:
                        identity = self._devices(
                            await self._request(
                                session,
                                "GET",
                                "network/devices",
                                sid=sid,
                                parameters={
                                    "max_devices": "256",
                                    "max_addresses": "16",
                                },
                            ),
                            targets,
                            end,
                        )
                        issues.update(identity.issues)
                    except (
                        PiHoleUnavailable,
                        ValueError,
                        aiohttp.ClientError,
                        TimeoutError,
                    ):
                        issues.add(
                            "identity_unavailable: Pi-hole device records could not be read or validated"
                        )
                if queries:
                    semaphore = asyncio.Semaphore(4)

                    async def sample(address: str) -> QuerySample:
                        async with semaphore:
                            try:
                                raw = await self._request(
                                    session,
                                    "GET",
                                    "queries",
                                    sid=sid,
                                    parameters={
                                        "client_ip": address,
                                        "from": str(start),
                                        "until": str(end),
                                        "length": str(self.plan.query_limit),
                                        "disk": "false",
                                    },
                                )
                                return self._queries(
                                    raw,
                                    address,
                                    start,
                                    end,
                                    half_open=since is not None,
                                )
                            except (
                                PiHoleUnavailable,
                                ValueError,
                                aiohttp.ClientError,
                                TimeoutError,
                            ):
                                return QuerySample(
                                    (),
                                    0,
                                    0,
                                    (
                                        "dns_unavailable: Pi-hole queries could not be read or validated for a selected device",
                                    ),
                                )

                    samples = tuple(
                        await asyncio.gather(*(sample(address) for address in targets))
                    )
                    for sample_result in samples:
                        issues.update(sample_result.issues)
                    if since is None:
                        dns = tuple(
                            observation
                            for sample_result in samples
                            for observation in sample_result.dns
                        )
                        if len(dns) > 256:
                            dns = dns[:256]
                            issues.add(
                                "dns_truncated: normalized Pi-hole destination limit reached"
                            )
                        window = DnsWindow(
                            "pihole-v6",
                            instant(start),
                            instant(end),
                            sum(sample.read for sample in samples),
                            sum(sample.available for sample in samples),
                            not any(issue.startswith("dns_") for issue in issues),
                        )
            except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
                # Unchanged for the default capabilities, including activity
                # reads, which have always reported both gaps.
                if "network_table" in self.capabilities:
                    issues.add(
                        "identity_unavailable: Pi-hole authentication or connection failed"
                    )
                if queries:
                    issues.add(
                        "dns_unavailable: Pi-hole authentication or connection failed"
                    )
            finally:
                if sid is not None:
                    try:
                        await self._request(session, "DELETE", "auth", sid=sid)
                    except (
                        PiHoleUnavailable,
                        ValueError,
                        aiohttp.ClientError,
                        TimeoutError,
                    ):
                        issues.add(
                            "pihole_session_cleanup_failed: the API session may remain until expiry"
                        )
        return (
            DeviceData(identity.devices, dns, window, tuple(sorted(issues))),
            identity.ambiguous_addresses,
            samples,
        )
