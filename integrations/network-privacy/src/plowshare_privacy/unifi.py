"""Read connected clients through the local UniFi Network Integration API."""

import asyncio
import ipaddress
import os
import ssl
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from urllib.parse import urlsplit

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
    mac_address,
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


@dataclass(frozen=True)
class _AdoptedDevice:
    id: str
    address: str | None
    mac: str
    name: str
    product: ManagedProduct


def _inventory_page(
    value: object, offset: int, limit: int
) -> tuple[tuple[object, ...], int]:
    row = object_fields(value, {"offset", "limit", "count", "totalCount", "data"})
    integer(row["offset"], offset, offset)
    rows = tuple(items(row["data"], integer(row["limit"], 1, limit)))
    count = integer(row["count"], len(rows), len(rows))
    total = integer(row["totalCount"], offset + count, 2**31 - 1)
    if not count and offset < total:
        raise ValueError("UniFi returned an empty intermediate page")
    return rows, total


def _product(row: dict[str, object]) -> ManagedProduct:
    firmware = row.get("firmwareVersion")
    upgrade = row.get("firmwareUpdatable")
    if upgrade is not None and type(upgrade) is not bool:
        raise ValueError("Expected UniFi firmware update flag")
    return ManagedProduct(
        "Ubiquiti",
        text(row.get("model"), 128),
        text(firmware, 128) if firmware else None,
        upgrade,
    )


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
        self._networks = site + "/networks"
        self._gateway = plan.gateway
        self._controller_host = urlsplit(plan.api_base).hostname
        self._managed_keys: frozenset[str] = frozenset()
        self._managed_until = 0.0
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

    def recently_managed(self, identity: DeviceIdentity) -> bool:
        return (
            time.monotonic() < self._managed_until
            and identity.device_id in self._managed_keys
        )

    async def _default_gateway(self, session: ClientSession, address: str) -> bool:
        # The Network API can report WAN IP on the adopted gateway. A LAN alias
        # requires both the operator's explicit binding and the controller's
        # current default-network address. A model/name alone cannot join them.
        rows, total = _inventory_page(
            await self._read_page(
                session, self._networks, {"offset": 0, "limit": _PAGE_SIZE}
            ),
            0,
            _PAGE_SIZE,
        )
        if len(rows) != total:
            raise ValueError("Incomplete UniFi network inventory")
        networks = [
            row
            for row in rows
            if isinstance(row, dict)
            and row.get("default") is True
            and row.get("enabled") is True
            and row.get("management") == "GATEWAY"
        ]
        if len(networks) != 1:
            return False
        network_id = uuid(networks[0].get("id"))
        detail = await self._read_page(session, self._networks + "/" + network_id, {})
        if not isinstance(detail, dict) or detail.get("id") != network_id:
            raise ValueError("Foreign UniFi gateway network")
        ipv4 = detail.get("ipv4Configuration")
        return (
            detail.get("enabled") is True
            and detail.get("management") == "GATEWAY"
            and isinstance(ipv4, dict)
            and ipv4.get("hostIpAddress") == address
        )

    async def _adopted_inventory(
        self,
        session: ClientSession,
        targets: frozenset[str],
        client_macs: frozenset[str],
    ) -> tuple[_AdoptedDevice, ...]:
        gateway_host = (
            self._controller_host
            if self._gateway and self._controller_host in targets
            else None
        )
        rows = []
        ids: set[str] = set()
        offset = 0
        total = None
        for _ in range(_MAX_PAGES):
            raw, count = _inventory_page(
                await self._read_page(
                    session, self._devices, {"offset": offset, "limit": _PAGE_SIZE}
                ),
                offset,
                _PAGE_SIZE,
            )
            if total is not None and count != total:
                raise ValueError("UniFi adopted-device pagination changed")
            total = count
            for row in raw:
                if not isinstance(row, dict):
                    raise ValueError("Expected adopted UniFi device")
                identity = uuid(row.get("id"))
                if identity in ids:
                    raise ValueError("Duplicate adopted UniFi device")
                ids.add(identity)
                if row.get("state") != "ONLINE":
                    continue
                address = row.get("ipAddress")
                raw_mac = row.get("macAddress")
                configured_gateway = (
                    gateway_host is not None
                    and self._gateway is not None
                    and identity == self._gateway.device_id
                )
                if (
                    isinstance(address, str)
                    and address in targets
                    or isinstance(raw_mac, str)
                    and raw_mac.lower() in client_macs
                    or configured_gateway
                ):
                    rows.append(
                        _AdoptedDevice(
                            identity,
                            address if isinstance(address, str) else None,
                            mac_address(raw_mac),
                            text(row.get("name"), 128),
                            _product(row),
                        )
                    )
            offset += len(raw)
            if offset == total:
                return tuple(rows)
        raise ValueError("UniFi adopted-device inventory exceeded the page limit")

    async def managed_sightings(
        self,
        targets: tuple[str, ...],
        observed_at: str,
        clients: tuple[DeviceIdentity, ...] = (),
    ) -> IdentitySample:
        scope = frozenset(targets)
        client_addresses: dict[str, list[str]] = defaultdict(list)
        for client in clients:
            if client.mac and client.address in scope:
                client_addresses[client.mac].append(client.address)
        devices: dict[str, DeviceIdentity] = {}
        ambiguous: set[str] = set()
        issues = []
        try:
            async with (
                asyncio.timeout(15),
                ClientSession(
                    headers={"X-API-Key": self._api_key, "Accept": "application/json"},
                    timeout=ClientTimeout(total=5),
                ) as session,
            ):
                rows = await self._adopted_inventory(
                    session, scope, frozenset(client_addresses)
                )
                duplicate_macs = {
                    mac
                    for mac, count in Counter(row.mac for row in rows).items()
                    if count > 1
                }
                host = self._controller_host
                gateway = self._gateway
                gateway_row = next(
                    (row for row in rows if gateway and row.id == gateway.device_id),
                    None,
                )
                if gateway and host is not None and host in scope:
                    try:
                        if gateway_row is None or not await self._default_gateway(
                            session, host
                        ):
                            issues.append(
                                "unifi_gateway_unconfirmed: configured device and default LAN gateway could not be matched"
                            )
                        else:
                            rows = (
                                *rows,
                                _AdoptedDevice(
                                    gateway_row.id,
                                    host,
                                    gateway.lan_mac,
                                    gateway_row.name,
                                    gateway_row.product,
                                ),
                            )
                    except (ClientError, TimeoutError, ValueError):
                        issues.append(
                            "unifi_gateway_unconfirmed: default LAN gateway could not be read"
                        )
        except (ClientError, TimeoutError, ValueError):
            self._managed_keys = frozenset()
            return IdentitySample(
                issues=(
                    "unifi_managed_unavailable: adopted-device inventory could not be read or validated",
                )
            )

        for row in rows:
            # Connected-client data owns the live address of a matching MAC.
            # An adopted record's address may be a different interface or stale.
            addresses = client_addresses.get(row.mac)
            if addresses and row.mac in duplicate_macs:
                continue
            for address in addresses or ([row.address] if row.address in scope else []):
                existing = devices.get(address)
                if existing is not None and existing.mac != row.mac:
                    ambiguous.add(address)
                    continue
                devices[address] = DeviceIdentity(
                    address,
                    "mac:" + row.mac,
                    None,
                    row.name,
                    row.mac,
                    row.product.manufacturer,
                    "unifi",
                    observed_at,
                    observed_at,
                )
        if duplicate_macs & client_addresses.keys():
            issues.append(
                "unifi_managed_ambiguous: multiple adopted records matched a watched client MAC"
            )
        if ambiguous:
            issues.append(
                "unifi_managed_ambiguous: adopted devices reported different MACs at a watched address"
            )
        accepted = tuple(
            device for address, device in devices.items() if address not in ambiguous
        )
        self._managed_keys = frozenset(
            device.device_id for device in accepted if device.mac not in duplicate_macs
        )
        self._managed_until = time.monotonic() + 360
        return IdentitySample(accepted, frozenset(ambiguous), tuple(issues))

    async def managed_product(self, identity: DeviceIdentity) -> ManagedProduct | None:
        mac = mac_address(identity.mac)
        try:
            async with ClientSession(
                headers={"X-API-Key": self._api_key, "Accept": "application/json"},
                timeout=ClientTimeout(total=5),
            ) as session:
                gateway = self._gateway
                if (
                    gateway
                    and identity.address == self._controller_host
                    and mac == gateway.lan_mac
                ):
                    raw = await self._read_page(
                        session, self._devices + "/" + gateway.device_id, {}
                    )
                    if (
                        not isinstance(raw, dict)
                        or raw.get("id") != gateway.device_id
                        or raw.get("state") != "ONLINE"
                    ):
                        return None
                    if not await self._default_gateway(session, identity.address):
                        return None
                    return _product(raw)
                raw = await self._read_page(
                    session,
                    self._devices,
                    {"offset": 0, "limit": 2, "filter": f"macAddress.eq('{mac}')"},
                )
            matches, total = _inventory_page(raw, 0, 2)
            if total != 1 or len(matches) != 1:
                return None
            row = matches[0]
            if not isinstance(row, dict):
                raise ValueError("Expected adopted UniFi device")
            if (
                mac_address(row.get("macAddress")) != mac
                or row.get("state") != "ONLINE"
            ):
                return None
            return _product(row)
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
