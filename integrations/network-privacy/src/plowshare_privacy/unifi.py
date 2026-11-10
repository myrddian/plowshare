"""Read connected clients through the local UniFi Network Integration API."""

import asyncio
import ipaddress
import os
import re
import ssl
from dataclasses import dataclass

from aiohttp import (
    ClientError,
    ClientSession,
    ClientTimeout,
    Fingerprint,
    ServerFingerprintMismatch,
)

from .contracts import (
    DeviceIdentity,
    UniFiPlan,
    integer,
    items,
    object_fields,
    parse_json,
    text,
    uuid,
)
from .identity_source import IdentitySample, ManagedProduct, ManagedProductUnavailable

_PAGE_SIZE = 100
_MAX_PAGES = 8
_MAX_BYTES = 524288


@dataclass(frozen=True)
class _ClientPage:
    total: int
    ids: tuple[str, ...]
    devices: tuple[DeviceIdentity, ...]


class UniFiNetwork:
    """Read one bounded site snapshot, retaining only selected client addresses."""

    def __init__(self, plan: UniFiPlan):
        key = os.environ.get(plan.api_key_environment)
        if (
            key is None
            or not 1 <= len(key) <= 512
            or any(not 33 <= ord(char) <= 126 for char in key)
        ):
            raise ValueError("Set the configured UniFi API key environment variable")
        self._api_key = key
        site = plan.api_base + "/sites/" + plan.site_id
        self._clients = site + "/clients"
        self._devices = site + "/devices"
        if plan.certificate_sha256 is not None:
            # aiohttp compares the exact peer certificate after the TLS handshake
            # and before it sends HTTP headers. Redirects remain disabled.
            self._tls: ssl.SSLContext | Fingerprint = Fingerprint(
                bytes.fromhex(plan.certificate_sha256)
            )
        else:
            context = ssl.create_default_context()
            if plan.ca_file is not None:
                try:
                    if (
                        not plan.ca_file.is_file()
                        or plan.ca_file.resolve() != plan.ca_file
                    ):
                        raise ValueError("Invalid UniFi CA file")
                    context.load_verify_locations(cafile=str(plan.ca_file))
                except (OSError, ValueError):
                    raise ValueError("Configure a readable UniFi CA file") from None
            self._tls = context

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample:
        if not targets:
            return IdentitySample()
        scope = frozenset(targets)
        seen: set[str] = set()
        devices: dict[str, DeviceIdentity] = {}
        ambiguous: set[str] = set()
        total = None
        offset = 0
        try:
            # The outer timeout covers the entire sweep's lookup, including all pages.
            async with asyncio.timeout(15):
                async with ClientSession(
                    headers={"X-API-Key": self._api_key, "Accept": "application/json"},
                    timeout=ClientTimeout(total=5),
                ) as session:
                    for _ in range(_MAX_PAGES):
                        page = await self._page(session, offset, scope, observed_at)
                        if (
                            total is not None and page.total != total
                        ) or seen.intersection(page.ids):
                            raise ValueError(
                                "UniFi client pagination changed during the read"
                            )
                        total = page.total
                        seen.update(page.ids)
                        for device in page.devices:
                            existing = devices.get(device.address)
                            if existing is not None and existing.mac != device.mac:
                                ambiguous.add(device.address)
                            else:
                                devices.setdefault(device.address, device)
                        offset += len(page.ids)
                        if offset == total:
                            return IdentitySample(
                                tuple(
                                    device
                                    for address, device in devices.items()
                                    if address not in ambiguous
                                ),
                                frozenset(ambiguous),
                                (
                                    "unifi_ambiguous: UniFi reported multiple MAC addresses at a selected IP",
                                )
                                if ambiguous
                                else (),
                            )
        except ServerFingerprintMismatch:
            return IdentitySample(
                issues=("unifi_unavailable: UniFi controller certificate pin mismatch",)
            )
        except (ClientError, TimeoutError, ValueError):
            # A partial page set may hide a conflicting association. Preserve the
            # other sources and report this source as unavailable for the sweep.
            return IdentitySample(
                issues=(
                    "unifi_unavailable: UniFi client inventory could not be read or validated",
                )
            )
        return IdentitySample(
            issues=(
                "unifi_incomplete: UniFi client inventory exceeded the pagination limit",
            )
        )

    async def managed_product(self, identity: DeviceIdentity) -> ManagedProduct | None:
        mac = identity.mac
        if mac is None or not re.fullmatch(r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", mac):
            raise ValueError("Choose an observed device with a MAC address")
        try:
            async with ClientSession(
                headers={"X-API-Key": self._api_key, "Accept": "application/json"},
                timeout=ClientTimeout(total=5),
            ) as session:
                raw = await self._read_page(
                    session,
                    self._devices,
                    {"offset": 0, "limit": 2, "filter": f"macAddress.eq('{mac}')"},
                )
            page = object_fields(
                raw, {"offset", "limit", "count", "totalCount", "data"}
            )
            integer(page["offset"], 0, 0)
            matches = items(page["data"], integer(page["limit"], 1, 2))
            count = integer(page["count"], len(matches), len(matches))
            if integer(page["totalCount"], count, 2**31 - 1) != 1 or count != 1:
                return None
            row = matches[0]
            if not isinstance(row, dict):
                raise ValueError("Expected adopted UniFi device")
            # Both identifiers must still agree on a live controller record.
            # A reused IP, stale client or duplicate match cannot supply a model.
            if (
                text(row.get("macAddress"), 17).lower() != mac
                or row.get("state") != "ONLINE"
                or row.get("ipAddress") != identity.address
            ):
                return None
            firmware = row.get("firmwareVersion")
            upgrade = row.get("firmwareUpdatable")
            if upgrade is not None and type(upgrade) is not bool:
                raise ValueError("Expected UniFi firmware update flag")
            return ManagedProduct(
                "Ubiquiti",
                text(row.get("model"), 128),
                text(firmware, 128) if firmware is not None else None,
                upgrade,
            )
        except (ClientError, TimeoutError, ValueError):
            raise ManagedProductUnavailable(
                "UniFi product details could not be read or validated."
            ) from None

    async def _page(
        self,
        session: ClientSession,
        offset: int,
        scope: frozenset[str],
        observed_at: str,
    ) -> _ClientPage:
        raw = await self._read_page(
            session, self._clients, {"offset": offset, "limit": _PAGE_SIZE}
        )
        return _decode_page(raw, offset, scope, observed_at)

    async def _read_page(
        self, session: ClientSession, url: str, params: dict[str, int | str]
    ) -> object:
        async with session.get(
            url, params=params, ssl=self._tls, allow_redirects=False
        ) as response:
            if response.status != 200:
                raise ValueError("UniFi inventory request failed")
            body = bytearray()
            async for chunk in response.content.iter_chunked(16384):
                body.extend(chunk)
                if len(body) > _MAX_BYTES:
                    raise ValueError("UniFi inventory page exceeded the response limit")
        return parse_json(bytes(body))


def _decode_page(
    value: object, offset: int, scope: frozenset[str], observed_at: str
) -> _ClientPage:
    row = object_fields(value, {"offset", "limit", "count", "totalCount", "data"})
    integer(row["offset"], offset, offset)
    limit = integer(row["limit"], 1, _PAGE_SIZE)
    clients = items(row["data"], limit)
    count = integer(row["count"], len(clients), len(clients))
    total = integer(row["totalCount"], offset + count, 2**31 - 1)
    if not count and offset < total:
        raise ValueError("UniFi returned an empty intermediate page")
    ids = []
    devices = []
    for raw in clients:
        if not isinstance(raw, dict):
            raise ValueError("Expected UniFi client metadata")
        # Connection topology, access policy, IDs and all other fields remain
        # transient. Only project the name, IP and MAC of selected physical clients.
        ids.append(uuid(raw.get("id")))
        if raw.get("type") not in ("WIRED", "WIRELESS") or raw.get("ipAddress") is None:
            continue
        address = str(ipaddress.ip_address(text(raw["ipAddress"], 64)))
        if address not in scope:
            continue
        mac = text(raw.get("macAddress"), 17).lower()
        name = raw.get("name")
        devices.append(
            DeviceIdentity.decode(
                {
                    "address": address,
                    "device_id": "mac:" + mac,
                    "label": None,
                    "hostname": text(name, 128)
                    if name is not None and name != ""
                    else None,
                    "mac": mac,
                    "vendor": None,
                    "source": "unifi",
                    "observed_at": observed_at,
                    "last_seen": observed_at,
                }
            )
        )
    if len(set(ids)) != len(ids):
        raise ValueError("UniFi returned a duplicate client ID")
    return _ClientPage(total, tuple(ids), tuple(devices))
