"""Read connected clients through the local UniFi Network Integration API."""

import asyncio
import ipaddress
import os
import ssl
from dataclasses import dataclass

from aiohttp import ClientError, ClientSession, ClientTimeout

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
from .identity_source import IdentitySample

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
        self._clients = plan.api_base + "/sites/" + plan.site_id + "/clients"
        self._tls = ssl.create_default_context()
        if plan.ca_file is not None:
            try:
                if not plan.ca_file.is_file() or plan.ca_file.resolve() != plan.ca_file:
                    raise ValueError("Invalid UniFi CA file")
                self._tls.load_verify_locations(cafile=str(plan.ca_file))
            except (OSError, ValueError):
                raise ValueError("Configure a readable UniFi CA file") from None

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

    async def _page(
        self,
        session: ClientSession,
        offset: int,
        scope: frozenset[str],
        observed_at: str,
    ) -> _ClientPage:
        async with session.get(
            self._clients,
            params={"offset": offset, "limit": _PAGE_SIZE},
            ssl=self._tls,
            allow_redirects=False,
        ) as response:
            if response.status != 200:
                raise ValueError("UniFi client inventory request failed")
            body = bytearray()
            async for chunk in response.content.iter_chunked(16384):
                body.extend(chunk)
                if len(body) > _MAX_BYTES:
                    raise ValueError("UniFi client page exceeded the response limit")
        return _decode_page(parse_json(bytes(body)), offset, scope, observed_at)


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
