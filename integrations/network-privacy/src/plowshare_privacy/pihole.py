"""Read-only Pi-hole v6 enrichment through its documented HTTP API.

One collection owns one short-lived session. Only selected client addresses enter
returned evidence; network-table associations are bounded and freshness checked.
No Pi-hole settings, blocking rules or DHCP leases are changed. POST/DELETE are
used only to open/close this adapter's session, and are never replayed.

DHCP settings (``GET config/dhcp``) are read once per full collection, and
only when ``dhcp_scope``, ``dhcp_reservations`` or ``dhcp_leases`` is enabled.
With ``dhcp_leases`` and DHCP active, the lease table (``GET dhcp/leases``) is
read once too, by full collections and identity checks, never by presence or
activity reads; ``local_dns_names`` reads ``config/dns/hosts`` the same way.
A full collection (``read_collection``) makes these reads in its first batch's
session, for all of its targets, and reuses them for every later batch; each
batch's session reads only its network table and queries. The collection
therefore yields exactly one DHCP scope, so one batch's failed read can never
leave another batch's scope to decide its addresses. Everything is decoded here
into ``DhcpScope``, ``Reservation``, ``Lease`` and ``DeviceIdentity`` facts; raw
responses never leave this module.
"""

from __future__ import annotations

import asyncio
import ipaddress
import math
import os
import re
from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Protocol, runtime_checkable

import aiohttp

from .addressing import AddressingSample, DhcpScope, Lease, Reservation
from .contracts import (
    Capability,
    DeviceIdentity,
    DnsObservation,
    DnsWindow,
    PiHolePlan,
    integer,
    items,
    mac_address,
    object_fields,
    parse_json,
    text,
    timestamp,
)
from .dns_activity import QuerySample
from .identity_merge import merge_identities
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


@dataclass(frozen=True)
class CollectionData:
    """One full collection's device reads: one ``DeviceData`` per batch, in order.

    ``addressing`` holds the collection's addressing facts, read once for all
    batches (None when no addressing capability is enabled); every batch's own
    ``addressing`` is None.
    """

    batches: tuple[DeviceData, ...]
    addressing: AddressingSample | None


@runtime_checkable
class CollectionDeviceSource(DeviceDataSource, Protocol):
    """A device source whose collection-wide facts are read once per collection.

    ``read_collection`` reads every batch in order and returns one
    ``DeviceData`` per batch plus the collection's single addressing sample. A
    source without it is read batch by batch through ``read``.
    """

    async def read_collection(
        self, batches: tuple[tuple[str, ...], ...], observed_at: str
    ) -> CollectionData: ...


@dataclass(frozen=True)
class PiHoleDhcp:
    """What one ``config/dhcp`` read established.

    ``active=None`` means the settings could not be read (``issues`` says so);
    ``False`` means this Pi-hole does not serve DHCP, which is not an issue and
    yields no scope or reservations. ``scope`` and ``reservations`` are decoded
    only for the enabled ``dhcp_scope`` / ``dhcp_reservations`` capabilities.
    """

    active: bool | None
    scope: DhcpScope | None
    reservations: tuple[Reservation, ...]
    issues: tuple[str, ...]


DHCP_UNAVAILABLE = "addressing_unavailable: Pi-hole DHCP settings could not be read"
DHCP_INVALID_RESERVATION = (
    "addressing_partial: Pi-hole returned an invalid DHCP reservation"
)
DHCP_INVALID_RANGE = "addressing_partial: Pi-hole returned an invalid DHCP range"
DHCP_RESERVATION_LIMIT = "addressing_partial: Pi-hole DHCP reservation limit reached"
# Pi-hole returns the whole ``dhcp.hosts`` list; at most this many are decoded.
MAX_RESERVATIONS = 512
DHCP_INVALID_LEASE = "addressing_partial: Pi-hole returned an invalid DHCP lease"
DHCP_LEASE_LIMIT = "addressing_partial: Pi-hole DHCP lease limit reached"
DHCP_LEASES_UNREADABLE = "addressing_partial: Pi-hole DHCP leases could not be read"
# The identity side of the same lease-read gaps.
_IDENTITY_INVALID_LEASE = "identity_partial: Pi-hole returned an invalid DHCP lease"
_IDENTITY_LEASE_LIMIT = "identity_partial: Pi-hole DHCP lease limit reached"
_IDENTITY_LEASES_UNREADABLE = (
    "identity_unavailable: Pi-hole DHCP leases could not be read or validated"
)
_LEASES_DISAGREE = (
    "identity_ambiguous: Pi-hole DHCP lease and network table name different "
    "devices for a selected address"
)
_LEASES_AMBIGUOUS = (
    "identity_ambiguous: Pi-hole DHCP lists multiple leases for a selected address"
)
_NO_ASSOCIATION = (
    "identity_unavailable: some selected addresses have no fresh unambiguous "
    "Pi-hole association"
)
# Pi-hole returns the whole lease table; at most this many entries are decoded,
# and a read reaching it cannot show the table was complete.
MAX_LEASES = 1024
# The capabilities that need ``config/dhcp``: leases are read only while it
# reports DHCP active.
_DHCP_SETTINGS: frozenset[Capability] = frozenset(
    {"dhcp_scope", "dhcp_reservations", "dhcp_leases"}
)
_LOCAL_DNS_INVALID = "identity_partial: Pi-hole returned an invalid local DNS record"
_LOCAL_DNS_LIMIT = "identity_partial: Pi-hole local DNS record limit reached"
_LOCAL_DNS_UNREADABLE = "identity_partial: Pi-hole local DNS records could not be read"
# Pi-hole returns the whole ``dns.hosts`` list; at most this many are decoded.
MAX_LOCAL_DNS = 512
# Optional trailing ``dhcp.hosts`` fields accepted after MAC and address: a
# hostname and a lease time ("24h", "infinite").
_HOST_FIELD = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?")


def _ipv4(value: object) -> ipaddress.IPv4Address:
    """A dotted-quad IPv4 address; raises ValueError for anything else."""
    return ipaddress.IPv4Address(text(value, 15))


def _dhcp_network(router: object, netmask: object) -> ipaddress.IPv4Network:
    """The network of ``router``/``netmask``.

    The netmask must be a contiguous, nonzero netmask (Pi-hole reports an empty
    or zero netmask when it is left to interface discovery, which this read
    cannot see); a hostmask is not accepted.
    """
    gateway, mask = _ipv4(router), int(_ipv4(netmask))
    hosts = ~mask & 0xFFFFFFFF
    if gateway.is_unspecified or mask == 0 or hosts & (hosts + 1):
        raise ValueError("Invalid Pi-hole DHCP network")
    return ipaddress.IPv4Network((gateway, 32 - hosts.bit_length()), strict=False)


def _reservation(entry: object) -> tuple[str, str]:
    """``(address, mac)`` of one ``dhcp.hosts`` entry.

    Pi-hole stores these as dnsmasq ``dhcp-host`` strings. Only the plain form
    ``MAC,IPv4[,NAME[,LEASE_TIME]]`` names one device and one address; client
    ids, tags, several MACs, ``ignore`` and IPv6 forms raise ValueError, since
    what they reserve cannot be stated exactly.
    """
    fields = text(entry, 512).split(",")
    if not 2 <= len(fields) <= 4:
        raise ValueError("Unsupported Pi-hole DHCP reservation")
    mac = mac_address(fields[0])
    address = str(_ipv4(fields[1]))
    for extra in fields[2:]:
        if not _HOST_FIELD.fullmatch(extra) or extra.lower() == "ignore":
            raise ValueError("Unsupported Pi-hole DHCP reservation")
    return address, mac


def _local_record(entry: object) -> tuple[str, str]:
    """``(address, name)`` of one ``dns.hosts`` entry ``"IP NAME [NAME ...]"``.

    The address must be a valid IPv4 or IPv6 address; the first name is kept
    and must be 1 to 128 printable characters. Anything else raises ValueError.
    """
    fields = text(entry, 1024).split()
    if len(fields) < 2:
        raise ValueError("Unsupported Pi-hole local DNS record")
    address = str(ipaddress.ip_address(fields[0]))
    name = fields[1]
    if len(name) > 128 or not name.isprintable():
        raise ValueError("Unsupported Pi-hole local DNS record")
    return address, name


@dataclass(frozen=True)
class _LeaseRead:
    """One ``dhcp/leases`` read for all of a collection's targets.

    ``leases`` are the active in-scope leases. ``identities`` has one record
    per address leased to a single MAC, without a vendor (each batch adds its
    own table's, see ``_lease_sample``); an address leased to several MACs is
    in ``ambiguous`` instead. ``complete`` holds only when the table was decoded
    fully below ``MAX_LEASES``. Both issue tuples describe the whole table.
    """

    leases: tuple[Lease, ...]
    identities: tuple[DeviceIdentity, ...]
    ambiguous: frozenset[str]
    complete: bool
    identity_issues: tuple[str, ...]
    addressing_issues: tuple[str, ...]


_UNREAD_LEASES = _LeaseRead(
    (),
    (),
    frozenset(),
    False,
    (_IDENTITY_LEASES_UNREADABLE,),
    (DHCP_LEASES_UNREADABLE,),
)


def _lease_sample(
    leased: _LeaseRead, targets: tuple[str, ...], table: tuple[DeviceIdentity, ...]
) -> IdentitySample:
    """The lease identities of one batch's ``targets``.

    A lease identity copies the vendor of the batch ``table`` record with the
    same MAC (Pi-hole leases carry none). The whole table's issues are kept;
    the multiple-leases issue only when this batch holds such an address.
    """
    scope = frozenset(targets)
    vendors = {device.mac: device.vendor for device in table if device.mac}
    ambiguous = leased.ambiguous & scope
    return IdentitySample(
        tuple(
            # Every lease identity has a MAC.
            replace(device, vendor=vendors.get(device.mac or ""))
            for device in leased.identities
            if device.address in scope
        ),
        ambiguous,
        (*leased.identity_issues, *((_LEASES_AMBIGUOUS,) if ambiguous else ())),
    )


@dataclass(frozen=True)
class _SharedReads:
    """The reads a full collection makes once, for all of its targets.

    ``reached`` is False when the session that should make them failed before
    they ran; then they hold nothing. ``dhcp`` is None when no DHCP capability
    needed the settings, ``leased`` when no lease table was read (capability
    off, or DHCP not active or unreadable). ``names`` are the local DNS names
    of the collection's targets, ``name_issues`` their read's issues.
    """

    reached: bool
    dhcp: PiHoleDhcp | None = None
    leased: _LeaseRead | None = None
    names: Mapping[str, str] = field(default_factory=dict)
    name_issues: tuple[str, ...] = ()


@dataclass(frozen=True)
class _SessionRead:
    """One session's results; ``withheld`` are leased addresses it found ambiguous."""

    data: DeviceData
    ambiguous: frozenset[str]
    samples: tuple[QuerySample, ...]
    shared: _SharedReads
    withheld: frozenset[str]


def _lease_expiry(value: object, observed: float) -> tuple[bool, str | None]:
    """``(active, expires_at)`` of a lease's ``expires`` epoch.

    Pi-hole documents ``0`` as a lease that never expires (``None``). A lease
    expiring at or before ``observed`` is inactive. A negative, non-integer or
    boolean value raises ValueError.
    """
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("Invalid Pi-hole lease expiry")
    if value == 0:
        return True, None
    expires = epoch(value)
    return expires > observed, instant(expires)


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


_CLEANUP_FAILED = (
    "pihole_session_cleanup_failed: the API session may remain until expiry"
)

# What a ``collection.pihole`` key has always read; direct callers keep it.
_DEFAULT_CAPABILITIES: frozenset[Capability] = frozenset(
    {"network_table", "dns_queries"}
)


class PiHoleV6:
    """One Pi-hole service, reading only its enabled capabilities.

    Without ``network_table`` no ``network/devices`` request is made, without
    ``dns_queries`` no ``queries`` request is made, without a DHCP capability
    no ``config/dhcp`` request is made, and without ``dhcp_leases`` no
    ``dhcp/leases`` request is made; a read that needs none of them opens no
    session. ``service`` names the configured service and is recorded on its
    addressing facts and lease identities.
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
            issues.add(_NO_ASSOCIATION)
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
        """A one-batch full collection read; it alone carries addressing facts."""
        read = await self.read_collection((targets,), observed_at)
        return replace(read.batches[0], addressing=read.addressing)

    async def read_collection(
        self, batches: tuple[tuple[str, ...], ...], observed_at: str
    ) -> CollectionData:
        """A full collection: one session per batch, collection-wide facts once.

        Every batch is validated before any request. The first batch's session
        reads ``config/dhcp``, ``dhcp/leases`` and ``config/dns/hosts`` (as
        enabled) for every target of the collection; each batch's identities
        are merged with the leases and names of its own addresses only. The
        single addressing sample is built after the last batch, so a lease
        withheld by any batch leaves the collection's lease table incomplete.
        If that first session fails, no batch has lease or name evidence and
        the collection's addressing is ``addressing_unavailable``.
        """
        batches = tuple(
            tuple(str(ipaddress.ip_address(address)) for address in batch)
            for batch in batches
        )
        everyone = tuple(address for batch in batches for address in batch)
        if (
            any(len(batch) > BATCH_SIZE for batch in batches)
            or len(everyone) > MAX_TARGETS
            or len(set(everyone)) != len(everyone)
        ):
            raise ValueError(
                f"Pi-hole requires at most {BATCH_SIZE} unique selected addresses"
                " per batch"
            )
        parts: list[DeviceData] = []
        shared: _SharedReads | None = None
        withheld: set[str] = set()
        for batch in batches:
            result = await self._read(
                batch,
                observed_at,
                queries=True,
                addressing=True,
                leases=True,
                names=True,
                everyone=everyone,
                shared=shared,
            )
            parts.append(result.data)
            shared = result.shared
            withheld |= result.withheld
        addressing = (
            self._addressing(shared, frozenset(withheld))
            if shared is not None and self.capabilities & _DHCP_SETTINGS
            else None
        )
        return CollectionData(tuple(parts), addressing)

    def _addressing(
        self, shared: _SharedReads, withheld: frozenset[str]
    ) -> AddressingSample:
        """The collection's one addressing sample from its shared reads.

        A first session that failed before the settings read is a gap. Leases
        at ``withheld`` addresses (contradicted in some batch) are dropped, and
        the lease table is then incomplete, so no in-range static is inferred
        from a read that held a contradiction.
        """
        read = (
            shared.dhcp
            if shared.dhcp is not None
            else PiHoleDhcp(None, None, (), (DHCP_UNAVAILABLE,))
        )
        scope = read.scope
        leases: tuple[Lease, ...] = ()
        lease_issues: tuple[str, ...] = ()
        if shared.leased is not None:
            leases = tuple(
                item for item in shared.leased.leases if item.address not in withheld
            )
            lease_issues = shared.leased.addressing_issues
            complete = shared.leased.complete and leases == shared.leased.leases
            if scope is not None and complete:
                scope = replace(scope, lease_table="complete")
        return AddressingSample(
            leases=leases,
            reservations=read.reservations,
            scopes=(scope,) if scope is not None else (),
            issues=(*read.issues, *lease_issues),
        )

    async def _authenticate(self, session: aiohttp.ClientSession) -> str:
        """Open this adapter's API session and return its id (never replayed)."""
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
        return text(accepted["sid"], 256)

    def _session(self) -> aiohttp.ClientSession:
        """A client without cookies or proxy settings, bounded by the plan's timeout."""
        return aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=self.plan.timeout),
            cookie_jar=aiohttp.DummyCookieJar(),
            trust_env=False,
        )

    async def offered(self) -> PiHoleDhcp:
        """Whether this Pi-hole serves DHCP, in its own session, for a connection test.

        No targets are selected, so no reservation is kept. Failures are
        ``active=None`` with fixed-text issues.
        """
        issues: list[str] = []
        result = PiHoleDhcp(None, None, (), (DHCP_UNAVAILABLE,))
        sid: str | None = None
        async with self._session() as session:
            try:
                sid = await self._authenticate(session)
                result = await self._dhcp(session, sid, ())
            except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
                pass
            finally:
                if sid is not None and not await self._close(session, sid):
                    issues.append(_CLEANUP_FAILED)
        return replace(result, issues=(*result.issues, *issues))

    async def _close(self, session: aiohttp.ClientSession, sid: str) -> bool:
        """Close the API session; False when it may remain until expiry."""
        try:
            await self._request(session, "DELETE", "auth", sid=sid)
        except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
            return False
        return True

    async def _dhcp(
        self, session: aiohttp.ClientSession, sid: str, targets: tuple[str, ...]
    ) -> PiHoleDhcp:
        """One ``GET config/dhcp``, decoded for the enabled DHCP capabilities.

        The settings object must hold a boolean ``active``, or the read is a gap
        (``addressing_unavailable``). With DHCP active, the scope is built from
        ``router``/``netmask`` and ``start``-``end``: undecodable range settings
        give no scope, and a range outside the network or reversed gives an
        incomplete one, both with ``addressing_partial``. Its lease table is
        ``incomplete`` here; only a complete lease read in the same session
        (``_read``) marks it ``complete``. Reservations are kept only for
        ``targets``; at most ``MAX_RESERVATIONS`` entries are decoded and an
        entry not in the plain form is skipped with ``addressing_partial``.
        Such a partial reservation read also leaves the scope incomplete: a
        rejected entry could reserve a selected address, which the scope would
        otherwise call ``static``.
        """
        try:
            raw = await self._request(session, "GET", "config/dhcp", sid=sid)
            if not isinstance(raw, dict) or not isinstance(raw.get("config"), dict):
                raise ValueError("Expected Pi-hole configuration")
            settings = raw["config"].get("dhcp")
            if (
                not isinstance(settings, dict)
                or type(settings.get("active")) is not bool
            ):
                raise ValueError("Expected Pi-hole DHCP settings")
        except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
            return PiHoleDhcp(None, None, (), (DHCP_UNAVAILABLE,))
        if settings["active"] is False:
            return PiHoleDhcp(False, None, (), ())
        issues: list[str] = []
        scope: DhcpScope | None = None
        if "dhcp_scope" in self.capabilities:
            try:
                network = _dhcp_network(settings.get("router"), settings.get("netmask"))
                start, end = _ipv4(settings.get("start")), _ipv4(settings.get("end"))
            except ValueError:
                issues.append(DHCP_INVALID_RANGE)
            else:
                valid = start in network and end in network and start <= end
                if not valid:
                    issues.append(DHCP_INVALID_RANGE)
                scope = DhcpScope(
                    self.service, network, str(start), str(end), valid, "incomplete"
                )
        reservations: list[Reservation] = []
        if "dhcp_reservations" in self.capabilities:
            first_issue = len(issues)
            hosts = settings.get("hosts")
            if not isinstance(hosts, list):
                issues.append(DHCP_INVALID_RESERVATION)
                hosts = []
            if len(hosts) > MAX_RESERVATIONS:
                issues.append(DHCP_RESERVATION_LIMIT)
            watched = frozenset(targets)
            for entry in hosts[:MAX_RESERVATIONS]:
                try:
                    address, mac = _reservation(entry)
                except ValueError:
                    if DHCP_INVALID_RESERVATION not in issues:
                        issues.append(DHCP_INVALID_RESERVATION)
                    continue
                if address in watched:
                    reservations.append(
                        Reservation(address, mac, "dhcp_reservation", self.service)
                    )
            if scope is not None and len(issues) > first_issue:
                scope = replace(scope, complete=False)
        return PiHoleDhcp(
            True,
            scope,
            tuple(dict.fromkeys(reservations)),
            tuple(dict.fromkeys(issues)),
        )

    async def _leases(
        self,
        session: aiohttp.ClientSession,
        sid: str,
        targets: tuple[str, ...],
        observed: float,
    ) -> _LeaseRead:
        """One ``GET dhcp/leases``, decoded into active leases for ``targets``.

        Every decoded entry is validated, in scope or not, because a malformed
        entry could be any address: it is skipped and the read is incomplete.
        Only the first ``MAX_LEASES`` entries are decoded. Expired leases are
        dropped. No active lease for a selected address is not a coverage gap,
        unlike a missing table association: leases describe DHCP clients only,
        and statically addressed devices legitimately have none.
        """
        try:
            raw = await self._request(session, "GET", "dhcp/leases", sid=sid)
            if not isinstance(raw, dict) or not isinstance(raw.get("leases"), list):
                raise ValueError("Expected a Pi-hole lease table")
        except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
            return _UNREAD_LEASES
        values: list[object] = raw["leases"]
        addressing_issues: list[str] = []
        identity_issues: list[str] = []
        if len(values) >= MAX_LEASES:
            addressing_issues.append(DHCP_LEASE_LIMIT)
            identity_issues.append(_IDENTITY_LEASE_LIMIT)
        scope = frozenset(targets)
        leases: list[Lease] = []
        by_address: dict[str, list[DeviceIdentity]] = {}
        for value in values[:MAX_LEASES]:
            try:
                if not isinstance(value, dict):
                    raise ValueError("Expected a Pi-hole lease")
                mac = mac_address(value.get("hwaddr"))
                address = str(ipaddress.ip_address(text(value.get("ip"), 64)))
                active, expires_at = _lease_expiry(value.get("expires"), observed)
                name = value.get("name")
                hostname = None if name in (None, "", "*") else text(name, 128)
                if not active or address not in scope:
                    continue
                identity = DeviceIdentity.decode(
                    {
                        "address": address,
                        "device_id": "mac:" + mac,
                        "label": None,
                        "hostname": hostname,
                        "mac": mac,
                        "vendor": None,
                        "source": "pihole-v6",
                        "observed_at": instant(observed),
                        "last_seen": None,
                        "evidence": "dhcp_lease",
                        "service": self.service,
                    }
                )
            except ValueError:
                if DHCP_INVALID_LEASE not in addressing_issues:
                    addressing_issues.append(DHCP_INVALID_LEASE)
                    identity_issues.append(_IDENTITY_INVALID_LEASE)
                continue
            leases.append(Lease(address, mac, hostname, expires_at, self.service))
            by_address.setdefault(address, []).append(identity)
        devices: list[DeviceIdentity] = []
        ambiguous: set[str] = set()
        for address, candidates in by_address.items():
            if len({candidate.mac for candidate in candidates}) > 1:
                ambiguous.add(address)
                continue
            # The same MAC listed twice: the first entry, as addressing keeps it.
            devices.append(candidates[0])
        return _LeaseRead(
            tuple(dict.fromkeys(leases)),
            tuple(devices),
            frozenset(ambiguous),
            not addressing_issues,
            tuple(identity_issues),
            tuple(addressing_issues),
        )

    async def _local_names(
        self, session: aiohttp.ClientSession, sid: str, targets: tuple[str, ...]
    ) -> tuple[dict[str, str], tuple[str, ...]]:
        """One ``GET config/dns/hosts``: ``({address: name}, issues)`` for ``targets``.

        Every decoded entry is validated, in scope or not; an invalid one is
        skipped with one deduplicated issue. Only the first ``MAX_LOCAL_DNS``
        entries are decoded. The first record for an address wins. An
        unreadable response gives no names and a fixed issue, and never
        affects the other reads of the session.
        """
        try:
            raw = await self._request(session, "GET", "config/dns/hosts", sid=sid)
            if not isinstance(raw, dict) or not isinstance(raw.get("config"), dict):
                raise ValueError("Expected Pi-hole configuration")
            dns = raw["config"].get("dns")
            if not isinstance(dns, dict) or not isinstance(dns.get("hosts"), list):
                raise ValueError("Expected Pi-hole local DNS records")
        except (PiHoleUnavailable, ValueError, aiohttp.ClientError, TimeoutError):
            return {}, (_LOCAL_DNS_UNREADABLE,)
        hosts: list[object] = dns["hosts"]
        issues: list[str] = []
        if len(hosts) > MAX_LOCAL_DNS:
            issues.append(_LOCAL_DNS_LIMIT)
        scope = frozenset(targets)
        names: dict[str, str] = {}
        for entry in hosts[:MAX_LOCAL_DNS]:
            try:
                address, name = _local_record(entry)
            except ValueError:
                if _LOCAL_DNS_INVALID not in issues:
                    issues.append(_LOCAL_DNS_INVALID)
                continue
            if address in scope:
                names.setdefault(address, name)
        return names, tuple(issues)

    def _apply_names(
        self,
        devices: tuple[DeviceIdentity, ...],
        names: dict[str, str],
        ambiguous: frozenset[str],
        observed: float,
    ) -> tuple[DeviceIdentity, ...]:
        """Add local DNS names to the final identities; a name is never a MAC.

        An identity without a hostname gets the name (evidence and source
        unchanged); an address with no identity gets an IP-only ``local_dns``
        record. An address Pi-hole found ambiguous is left alone, so a name
        cannot hide the ambiguity. Address order is kept.
        """
        by_address = {device.address: device for device in devices}
        for address, name in names.items():
            if address in ambiguous:
                continue
            existing = by_address.get(address)
            if existing is None:
                by_address[address] = DeviceIdentity(
                    address,
                    "ip:" + address,
                    None,
                    name,
                    None,
                    None,
                    "pihole-v6",
                    instant(observed),
                    None,
                    evidence="local_dns",
                    service=self.service,
                )
            elif existing.hostname is None:
                by_address[address] = replace(existing, hostname=name)
        return tuple(sorted(by_address.values(), key=lambda device: device.address))

    async def identify(self, targets: tuple[str, ...], observed_at: str) -> DeviceData:
        """Operator-only identity checks; no DNS history is fetched or retained.

        With ``dhcp_leases`` enabled the lease table is identity evidence here
        too, and with ``local_dns_names`` the local DNS names are applied; no
        addressing facts are returned.
        """
        if len(targets) > BATCH_SIZE:
            raise ValueError("Pi-hole requires at most 32 unique selected addresses")
        result = await self._read(
            targets, observed_at, queries=False, leases=True, names=True
        )
        return result.data

    async def presence(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        """Read the network table once for the watched scope, without querying DNS logs."""
        result = await self._read(targets, observed_at, queries=False)
        return IdentitySample(result.data.devices, result.ambiguous, result.data.issues)

    async def activity(
        self, targets: tuple[str, ...], since: str, observed_at: str
    ) -> tuple[tuple[QuerySample, ...], tuple[str, ...]]:
        """Read query logs for confirmed clients only, without re-reading the device table."""
        result = await self._read(targets, observed_at, queries=True, since=since)
        return result.samples, result.data.issues

    async def _read(
        self,
        targets: tuple[str, ...],
        observed_at: str,
        *,
        queries: bool,
        since: str | None = None,
        addressing: bool = False,
        leases: bool = False,
        names: bool = False,
        everyone: tuple[str, ...] | None = None,
        shared: _SharedReads | None = None,
    ) -> _SessionRead:
        """One session reading the enabled capabilities for ``targets``.

        ``addressing`` reads the DHCP settings for the collection's addressing
        facts (full collections only; ``read_collection`` builds the sample);
        ``leases`` reads the lease table as identity evidence when
        ``dhcp_leases`` is enabled and ``config/dhcp`` reports DHCP active.
        ``names`` reads ``config/dns/hosts`` when ``local_dns_names`` is enabled
        and applies it after the merge (``_apply_names``).

        Without ``shared`` this session makes those reads itself, for
        ``everyone`` (default ``targets``), and returns them in ``shared``; with
        it, it reuses them and makes none, so a session is opened only for the
        network table or queries. If the reads were never reached, a batch that
        wanted leases or names reports them unreadable. Lease identities are
        merged with this batch's network table by ``merge_identities``: a lease
        outranks a table record with the same MAC, and a different MAC leaves
        the address ambiguous (and its lease ``withheld``).
        """
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
        lease_read = leases and since is None and "dhcp_leases" in self.capabilities
        name_read = names and since is None and "local_dns_names" in self.capabilities
        dhcp_settings = (
            addressing and bool(self.capabilities & _DHCP_SETTINGS)
        ) or lease_read
        # Only a session without shared reads makes them (the first batch).
        reads_shared = shared is None
        wide = targets if everyone is None else everyone
        open_shared = reads_shared and (dhcp_settings or name_read)
        dhcp: PiHoleDhcp | None = None
        leased: _LeaseRead | None = None
        local: dict[str, str] = {}
        name_issues: tuple[str, ...] = ()
        reached = False
        samples: tuple[QuerySample, ...] = ()
        identity = IdentitySample()
        dns: tuple[DnsObservation, ...] = ()
        window: DnsWindow | None = None
        issues: set[str] = set()
        sid: str | None = None
        if table or queries or open_shared:
            async with self._session() as session:
                try:
                    sid = await self._authenticate(session)
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
                    if open_shared:
                        if dhcp_settings:
                            dhcp = await self._dhcp(session, sid, wide)
                        if lease_read and dhcp is not None and dhcp.active is True:
                            leased = await self._leases(session, sid, wide, end)
                        if name_read:
                            local, name_issues = await self._local_names(
                                session, sid, wide
                            )
                        reached = True
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
                            await asyncio.gather(
                                *(sample(address) for address in targets)
                            )
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
                except (
                    PiHoleUnavailable,
                    ValueError,
                    aiohttp.ClientError,
                    TimeoutError,
                ):
                    # Unchanged for the default capabilities, including activity
                    # reads, which have always reported both gaps.
                    if "network_table" in self.capabilities or lease_read or name_read:
                        issues.add(
                            "identity_unavailable: Pi-hole authentication or connection failed"
                        )
                    if queries:
                        issues.add(
                            "dns_unavailable: Pi-hole authentication or connection failed"
                        )
                finally:
                    if sid is not None and not await self._close(session, sid):
                        issues.add(_CLEANUP_FAILED)
        if reads_shared:
            shared = (
                _SharedReads(True, dhcp, leased, local, name_issues)
                if reached
                else _SharedReads(False)
            )
        else:
            assert shared is not None
            if not shared.reached:
                # The first session failed: this batch has no leases or names.
                if lease_read:
                    issues.add(_IDENTITY_LEASES_UNREADABLE)
                if name_read:
                    issues.add(_LOCAL_DNS_UNREADABLE)
        devices = identity.devices
        ambiguous = identity.ambiguous_addresses
        withheld: frozenset[str] = frozenset()
        if name_read:
            issues.update(shared.name_issues)
        if lease_read and shared.dhcp is not None and shared.dhcp.active is None:
            # DHCP settings unreadable: whether leases exist is unknown.
            issues.add(_IDENTITY_LEASES_UNREADABLE)
        if lease_read and shared.leased is not None:
            batch_leases = _lease_sample(shared.leased, targets, identity.devices)
            issues.update(batch_leases.issues)
            merge = merge_identities((identity, batch_leases), frozenset(targets))
            # Address order, as the network table alone returns its devices.
            devices = tuple(sorted(merge.devices, key=lambda device: device.address))
            ambiguous = ambiguous | merge.ambiguous | merge.conflicts
            if merge.conflicts:
                issues.add(_LEASES_DISAGREE)
            if {device.address for device in devices} == set(targets):
                issues.discard(_NO_ASSOCIATION)
            # DeviceData carries no ambiguity across services, so another
            # service may still confirm an address Pi-hole found contradictory.
            # Its leases there are withheld so they can never decide
            # dhcp_dynamic (``_addressing`` then marks the table incomplete).
            withheld = frozenset(
                item.address
                for item in shared.leased.leases
                if item.address in ambiguous
            )
        names_here = {
            address: name
            for address, name in shared.names.items()
            if address in frozenset(targets)
        }
        if names_here:
            devices = self._apply_names(devices, names_here, ambiguous, end)
        return _SessionRead(
            DeviceData(devices, dns, window, tuple(sorted(issues))),
            ambiguous,
            samples,
            shared,
            withheld,
        )
