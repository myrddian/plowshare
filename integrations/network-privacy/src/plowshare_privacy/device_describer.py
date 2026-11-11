"""Ask one watched device to describe itself over unicast SSDP/UPnP and mDNS.

This is the collector's only active query of a device beyond TCP probes. It
is deliberately narrow:

- Every datagram goes to the one target address (UDP 1900 for SSDP, 5353 for
  mDNS). Nothing is broadcast or multicast, and replies from any other source
  are ignored.
- A UPnP description is fetched only from plain HTTP on that same address
  (any port), with no redirects, a 2 s total timeout and a 64 KiB cap. XML
  that declares a document type or entities is refused before parsing.
- Only product fields survive: manufacturer, model name/number, an explicit
  firmware/software version and service types mapped to short labels. Serial
  numbers, UUIDs, friendly names, instance names, URLs and MACs are dropped.

Silence and malformed answers are normal and produce no description. Only
unexpected local socket failures become diagnostic issue codes.
"""

from __future__ import annotations

import asyncio
import ipaddress
import re
import secrets
import socket
import struct
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any, Callable, Protocol
from urllib.parse import urlsplit

from aiohttp import ClientError, ClientSession, ClientTimeout
from yarl import URL

from .contracts import private_ipv4, utc_now
from .device_facts import (
    MAX_SERVICES,
    DescriptionResult,
    DeviceDescription,
    fact_text,
)

# Protocol-defined ports; neither is deployment configuration.
SSDP_PORT = 1900
MDNS_PORT = 5353
REPLY_WAIT_SECONDS = 2.0
FETCH_TIMEOUT_SECONDS = 2.0
MAX_DESCRIPTION_BYTES = 64 * 1024
MAX_DATAGRAM_BYTES = 9000
MAX_SERVICE_TYPES = 8
MAX_INSTANCES = 8
MAX_QUESTIONS = 8
MAX_RECORDS = 64
PTR = 12
TXT = 16
_QU_IN = 0x8001  # Unicast-response bit plus class IN (RFC 6762 section 5.4).

Name = tuple[bytes, ...]


class MalformedPacket(ValueError):
    """A DNS message that violates the wire format or this reader's bounds."""


def unicast_destination(address: str) -> str:
    """Accept only a canonical private unicast IPv4 host address."""
    ip = ipaddress.IPv4Address(address)
    if (
        str(ip) != address
        or not private_ipv4(address)
        or ip.is_multicast
        or address.endswith((".0", ".255"))
    ):
        raise ValueError("Describe only a watched private unicast device address")
    return address


class DatagramExchange(Protocol):
    """Send one datagram to ``address:port``; return the first reply from that address."""

    async def exchange(
        self, address: str, port: int, payload: bytes, *, wait: float
    ) -> bytes | None: ...


class DescriptionFetch(Protocol):
    """Fetch an already validated description URL; ``None`` for anything unusable."""

    async def fetch(self, url: str) -> bytes | None: ...


class _FirstReply(asyncio.DatagramProtocol):
    """Accept the first bounded datagram whose source IP is the target."""

    def __init__(self, address: str, reply: asyncio.Future[bytes]):
        self.address = address
        self.reply = reply

    def datagram_received(self, data: bytes, addr: tuple[str | Any, int]) -> None:
        # Responders may answer from another port; only the source IP is fixed.
        if (
            addr[0] == self.address
            and len(data) <= MAX_DATAGRAM_BYTES
            and not self.reply.done()
        ):
            self.reply.set_result(bytes(data))

    def error_received(self, exc: Exception) -> None:
        if not self.reply.done():
            self.reply.set_exception(exc)


class UdpExchange:
    """One unconnected IPv4 UDP socket per exchange, without SO_BROADCAST."""

    async def exchange(
        self, address: str, port: int, payload: bytes, *, wait: float
    ) -> bytes | None:
        unicast_destination(address)
        if port not in (SSDP_PORT, MDNS_PORT) or len(payload) > MAX_DATAGRAM_BYTES:
            raise ValueError("Unsupported description query")
        loop = asyncio.get_running_loop()
        reply: asyncio.Future[bytes] = loop.create_future()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: _FirstReply(address, reply), family=socket.AF_INET
        )
        try:
            transport.sendto(payload, (address, port))
            async with asyncio.timeout(wait):
                return await reply
        except TimeoutError:
            return None
        finally:
            transport.close()


class HttpDescriptionFetch:
    """Plain-HTTP GET with no redirects, no proxy, a total timeout and a byte cap."""

    async def fetch(self, url: str) -> bytes | None:
        if urlsplit(url).scheme != "http":
            return None
        try:
            async with ClientSession(
                timeout=ClientTimeout(total=FETCH_TIMEOUT_SECONDS)
            ) as session:
                async with session.get(
                    URL(url),
                    allow_redirects=False,
                    headers={"Accept": "text/xml, application/xml"},
                ) as response:
                    if response.status != 200 or (
                        response.content_length is not None
                        and response.content_length > MAX_DESCRIPTION_BYTES
                    ):
                        return None
                    body = bytearray()
                    async for chunk in response.content.iter_chunked(8192):
                        body.extend(chunk)
                        if len(body) > MAX_DESCRIPTION_BYTES:
                            return None
                    return bytes(body)
        except (ClientError, TimeoutError, ValueError):
            return None


def ssdp_search(address: str) -> bytes:
    """A unicast M-SEARCH for all targets; UDA 1.1 addresses HOST to the device."""
    return (
        "M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {address}:{SSDP_PORT}\r\n"
        'MAN: "ssdp:discover"\r\n'
        "ST: ssdp:all\r\n"
        "\r\n"
    ).encode("ascii")


def ssdp_location(reply: bytes, address: str) -> str | None:
    """The reply's LOCATION, only if it is plain HTTP on exactly ``address``."""
    try:
        lines = reply[:4096].decode("ascii").split("\r\n")
    except UnicodeDecodeError:
        return None
    if not re.fullmatch(r"HTTP/1\.[01] 200( .*)?", lines[0]):
        return None
    location = next(
        (
            line.split(":", 1)[1].strip()
            for line in lines[1:]
            if line.split(":", 1)[0].strip().casefold() == "location" and ":" in line
        ),
        None,
    )
    if (
        location is None
        or len(location) > 512
        or any(c.isspace() or ord(c) < 32 for c in location)
    ):
        return None
    parts = urlsplit(location)
    try:
        port = parts.port
    except ValueError:
        return None
    if (
        parts.scheme != "http"
        or parts.username is not None
        or parts.password is not None
        or parts.hostname != address
        or parts.netloc not in {address, f"{address}:{port}"}
        or parts.fragment
    ):
        return None
    return location


# Plain labels for well-known UPnP device and service types. ``None`` drops
# plumbing types every device has, so the label list stays meaningful.
_UPNP_LABELS: dict[str, str | None] = {
    "basic": None,
    "connectionmanager": None,
    "dial": "screen casting (DIAL)",
    "mediarenderer": "media rendering (DLNA)",
    "renderingcontrol": "media rendering (DLNA)",
    "avtransport": "media transport (DLNA)",
    "mediaserver": "media serving (DLNA)",
    "contentdirectory": "media library (DLNA)",
    "ircc": "remote control (Sony IRCC)",
    "scalarwebapi": "remote control (web API)",
    "remoteuiserver": "remote control (UPnP Remote UI)",
    "internetgatewaydevice": "internet gateway control (UPnP IGD)",
    "wandevice": "internet gateway control (UPnP IGD)",
    "wanconnectiondevice": "internet gateway control (UPnP IGD)",
    "wanipconnection": "internet gateway control (UPnP IGD)",
    "wanpppconnection": "internet gateway control (UPnP IGD)",
    "wancommoninterfaceconfig": "internet gateway control (UPnP IGD)",
    "layer3forwarding": "internet gateway control (UPnP IGD)",
    "printer": "printing",
    "printbasic": "printing",
    "printenhanced": "printing",
    "scanner": "scanning",
    "zoneplayer": "Sonos speaker control",
}
_SHORT_TYPE = re.compile(r"[A-Za-z][A-Za-z0-9_.-]{0,47}")
_VERSION_FIELDS = ("firmwareversion", "softwareversion", "swversion", "firmware")


def upnp_label(urn: str) -> str | None:
    """Map ``urn:<domain>:device|service:<type>:<version>`` to a short label."""
    parts = urn.strip().split(":")
    if len(parts) < 4 or parts[0] != "urn" or parts[2] not in ("device", "service"):
        return None
    kind = parts[3]
    if kind.casefold() in _UPNP_LABELS:
        return _UPNP_LABELS[kind.casefold()]
    return kind if _SHORT_TYPE.fullmatch(kind) else None


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].casefold()


def parse_upnp(document: bytes, observed_at: str) -> DeviceDescription | None:
    """Keep only product fields from a UPnP device description.

    Raises ``ValueError`` (or ``ET.ParseError``) for refused or malformed XML.
    The document must be UTF-8 so the DOCTYPE/ENTITY check sees what expat
    would parse.
    """
    content = document.decode("utf-8-sig")
    lowered = content.casefold()
    if "\x00" in content:
        raise ValueError("Description documents must be UTF-8")
    if "<!doctype" in lowered or "<!entity" in lowered:
        raise ValueError("Refused XML that declares document types or entities")
    root = ET.fromstring(content)
    device = next((child for child in root if _local(child.tag) == "device"), None)
    if _local(root.tag) != "root" or device is None:
        raise ValueError("Not a UPnP device description")
    fields: dict[str, str] = {}
    for child in device:
        name = _local(child.tag)
        if child.text is not None and name not in fields:
            fields[name] = child.text
    firmware = next(
        (
            value
            for name, value in fields.items()
            if name in _VERSION_FIELDS
            or name.endswith(tuple("_" + field for field in _VERSION_FIELDS))
        ),
        None,
    )
    services: list[str] = []
    for element in root.iter():
        if _local(element.tag) in ("devicetype", "servicetype") and element.text:
            label = upnp_label(element.text)
            if label is not None and label not in services:
                services.append(label)
    values = (
        fact_text(fields.get("manufacturer")),
        fact_text(fields.get("modelname")),
        fact_text(fields.get("modelnumber")),
        fact_text(firmware),
    )
    if all(value is None for value in values) and not services:
        return None
    return DeviceDescription(
        "upnp", observed_at, *values, tuple(services[:MAX_SERVICES])
    )


@dataclass(frozen=True)
class DnsRecord:
    name: Name
    rtype: int
    # PTR target name, when ``rtype`` is PTR.
    target: Name | None = None
    # TXT character strings, when ``rtype`` is TXT.
    strings: tuple[bytes, ...] = ()


@dataclass(frozen=True)
class DnsMessage:
    query_id: int
    response: bool
    questions: tuple[tuple[Name, int], ...]
    # Answer, authority and additional records, in order.
    records: tuple[DnsRecord, ...]


def _encode_name(name: Name) -> bytes:
    encoded = bytearray()
    for label in name:
        if not 1 <= len(label) <= 63:
            raise ValueError("DNS labels must be 1 to 63 bytes")
        encoded.append(len(label))
        encoded.extend(label)
    encoded.append(0)
    if len(encoded) > 255:
        raise ValueError("DNS names must be at most 255 bytes")
    return bytes(encoded)


def encode_query(query_id: int, questions: tuple[tuple[Name, int], ...]) -> bytes:
    """A standard query whose questions each request a unicast (QU) answer."""
    if not 1 <= len(questions) <= MAX_QUESTIONS or not 0 <= query_id <= 0xFFFF:
        raise ValueError("Expected one to eight questions and a 16-bit ID")
    packet = bytearray(struct.pack("!HHHHHH", query_id, 0, len(questions), 0, 0, 0))
    for name, rtype in questions:
        packet.extend(_encode_name(name))
        packet.extend(struct.pack("!HH", rtype, _QU_IN))
    return bytes(packet)


def _read_name(packet: bytes, offset: int) -> tuple[Name, int]:
    """Decode a possibly compressed name; return it and the offset after it.

    Every compression pointer must point strictly before the start of the
    label run it continues, so a chain always moves backwards and terminates.
    """
    labels: list[bytes] = []
    total = 1
    position = offset
    run_start = offset
    end: int | None = None
    while True:
        if position >= len(packet):
            raise MalformedPacket("Truncated DNS name")
        length = packet[position]
        if length & 0xC0 == 0xC0:
            if position + 1 >= len(packet):
                raise MalformedPacket("Truncated DNS compression pointer")
            target = ((length & 0x3F) << 8) | packet[position + 1]
            if target >= run_start:
                raise MalformedPacket("DNS compression pointer does not move back")
            if end is None:
                end = position + 2
            position = run_start = target
            continue
        if length & 0xC0:
            raise MalformedPacket("Unsupported DNS label type")
        if length == 0:
            return tuple(labels), end if end is not None else position + 1
        label = packet[position + 1 : position + 1 + length]
        if len(label) != length:
            raise MalformedPacket("Truncated DNS label")
        total += length + 1
        if total > 255:
            raise MalformedPacket("DNS name exceeds 255 bytes")
        labels.append(label)
        position += 1 + length


def decode_message(packet: bytes) -> DnsMessage:
    """Strictly decode one bounded DNS message; reject anything malformed."""
    if not 12 <= len(packet) <= MAX_DATAGRAM_BYTES:
        raise MalformedPacket("DNS message size out of bounds")
    query_id, flags, qdcount, ancount, nscount, arcount = struct.unpack_from(
        "!HHHHHH", packet
    )
    if qdcount > MAX_QUESTIONS or ancount + nscount + arcount > MAX_RECORDS:
        raise MalformedPacket("DNS message has too many entries")
    offset = 12
    questions: list[tuple[Name, int]] = []
    for _ in range(qdcount):
        name, offset = _read_name(packet, offset)
        if offset + 4 > len(packet):
            raise MalformedPacket("Truncated DNS question")
        rtype, _ = struct.unpack_from("!HH", packet, offset)
        offset += 4
        questions.append((name, rtype))
    records: list[DnsRecord] = []
    for _ in range(ancount + nscount + arcount):
        name, offset = _read_name(packet, offset)
        if offset + 10 > len(packet):
            raise MalformedPacket("Truncated DNS record")
        rtype, _, _, length = struct.unpack_from("!HHIH", packet, offset)
        start = offset + 10
        end = start + length
        if end > len(packet):
            raise MalformedPacket("Truncated DNS record data")
        if rtype == PTR:
            target, after = _read_name(packet, start)
            if after != end:
                raise MalformedPacket("PTR data does not match its length")
            records.append(DnsRecord(name, rtype, target=target))
        elif rtype == TXT:
            strings: list[bytes] = []
            position = start
            while position < end:
                size = packet[position]
                if position + 1 + size > end or len(strings) >= 64:
                    raise MalformedPacket("Malformed TXT data")
                strings.append(packet[position + 1 : position + 1 + size])
                position += 1 + size
            records.append(DnsRecord(name, rtype, strings=tuple(strings)))
        else:
            records.append(DnsRecord(name, rtype))
        offset = end
    if offset != len(packet):
        raise MalformedPacket("Trailing bytes after DNS message")
    return DnsMessage(query_id, bool(flags & 0x8000), tuple(questions), tuple(records))


def _key(name: Name) -> Name:
    return tuple(label.lower() for label in name)


_SERVICES: Name = (b"_services", b"_dns-sd", b"_udp", b"local")
_SERVICE_TYPE = re.compile(rb"_[A-Za-z0-9][A-Za-z0-9-]{0,62}")
_MDNS_LABELS: dict[str, str | None] = {
    "googlecast": "screen casting (Google Cast)",
    "airplay": "AirPlay",
    "raop": "AirPlay audio",
    "ipp": "printing",
    "ipps": "printing",
    "printer": "printing",
    "pdl-datastream": "printing",
    "uscan": "scanning",
    "uscans": "scanning",
    "scanner": "scanning",
    "spotify-connect": "Spotify Connect",
    "hap": "HomeKit accessory",
    "homekit": "HomeKit",
    "companion-link": "Apple companion link",
    "androidtvremote2": "remote control (Android TV)",
    "amzn-wplay": "screen casting (Amazon)",
    "sonos": "Sonos speaker control",
    "matter": "Matter",
    "matterc": "Matter",
    "http": "web interface",
    "https": "web interface",
    "ssh": "remote shell (SSH)",
    "smb": "file sharing (SMB)",
    "afpovertcp": "file sharing (AFP)",
    "device-info": None,
    "sleep-proxy": None,
}
# TXT keys that carry product fields, in precedence order. Everything else,
# including Google Cast "fn" (friendly name) and "id", is discarded.
_TXT_MODEL = ("md", "model", "am", "usb_mdl", "ty", "product")
_TXT_MAKER = ("usb_mfg", "manufacturer", "mfg")
_TXT_FIRMWARE = ("fv",)


def _service_type(name: Name) -> Name | None:
    if (
        len(name) == 3
        and _SERVICE_TYPE.fullmatch(name[0])
        and name[1].lower() in (b"_tcp", b"_udp")
        and name[2].lower() == b"local"
    ):
        return name
    return None


def mdns_label(service: Name) -> str | None:
    kind = service[0][1:].decode("ascii").lower()
    if kind in _MDNS_LABELS:
        return _MDNS_LABELS[kind]
    return kind


def _txt_fields(strings: tuple[bytes, ...]) -> dict[str, str]:
    fields: dict[str, str] = {}
    for entry in strings:
        key, separator, value = entry.partition(b"=")
        if not separator:
            continue
        try:
            name, decoded = key.decode("ascii").casefold(), value.decode("utf-8")
        except UnicodeDecodeError:
            continue
        fields.setdefault(name, decoded)
    return fields


class LocalDeviceDescriber:
    """Read SSDP/UPnP and mDNS self-descriptions from one watched address."""

    def __init__(
        self,
        targets: Callable[[], tuple[str, ...]],
        datagrams: DatagramExchange | None = None,
        http: DescriptionFetch | None = None,
        *,
        clock: Callable[[], str] = utc_now,
        query_id: Callable[[], int] = lambda: secrets.randbelow(0x10000),
    ):
        self.targets = targets
        self.datagrams = datagrams or UdpExchange()
        self.http = http or HttpDescriptionFetch()
        self.clock = clock
        self.query_id = query_id

    async def describe(self, address: str) -> DescriptionResult:
        try:
            unicast_destination(address)
        except ValueError:
            raise ValueError(
                "Describe only a watched private unicast device address"
            ) from None
        if address not in self.targets():
            raise ValueError("Describe only a watched private unicast device address")
        descriptions: list[DeviceDescription] = []
        issues: list[str] = []
        for reader in (self._upnp, self._mdns):
            try:
                description = await reader(address)
            except (ConnectionRefusedError, TimeoutError):
                description = None  # Nothing listening, or no answer in time.
            except OSError as error:
                description = None
                issues.append("device_description_unavailable: " + type(error).__name__)
            except (ValueError, ET.ParseError):
                description = None  # A refused or malformed answer is no description.
            if description is not None:
                descriptions.append(description)
        return DescriptionResult(tuple(descriptions), tuple(issues))

    async def _upnp(self, address: str) -> DeviceDescription | None:
        reply = await self.datagrams.exchange(
            address, SSDP_PORT, ssdp_search(address), wait=REPLY_WAIT_SECONDS
        )
        location = ssdp_location(reply, address) if reply is not None else None
        if location is None:
            return None
        document = await self.http.fetch(location)
        if document is None or len(document) > MAX_DESCRIPTION_BYTES:
            return None
        return parse_upnp(document, self.clock())

    async def _query(
        self, address: str, questions: tuple[tuple[Name, int], ...]
    ) -> tuple[DnsRecord, ...]:
        query_id = self.query_id()
        reply = await self.datagrams.exchange(
            address,
            MDNS_PORT,
            encode_query(query_id, questions),
            wait=REPLY_WAIT_SECONDS,
        )
        if reply is None:
            return ()
        message = decode_message(reply)
        # A legacy unicast reply echoes the query ID (RFC 6762 section 6.7).
        if not message.response or message.query_id != query_id:
            return ()
        return message.records

    async def _mdns(self, address: str) -> DeviceDescription | None:
        types: dict[Name, Name] = {}
        for item in await self._query(address, ((_SERVICES, PTR),)):
            service = _service_type(item.target) if item.target else None
            if (
                item.rtype == PTR
                and _key(item.name) == _key(_SERVICES)
                and service is not None
                and len(types) < MAX_SERVICE_TYPES
            ):
                types.setdefault(_key(service), service)
        if not types:
            return None
        records = list(
            await self._query(address, tuple((item, PTR) for item in types.values()))
        )
        # Instance names stay in memory only to request their TXT records.
        instances: dict[Name, Name] = {}
        for item in records:
            target = item.target
            if (
                item.rtype == PTR
                and _key(item.name) in types
                and target is not None
                and len(target) == 4
                and _key(target[1:]) == _key(item.name)
                and len(instances) < MAX_INSTANCES
            ):
                instances.setdefault(_key(target), target)
        texts = {_key(item.name) for item in records if item.rtype == TXT}
        missing = tuple(
            (name, TXT) for key, name in instances.items() if key not in texts
        )
        if missing:
            records.extend(await self._query(address, missing))
        fields: dict[str, str] = {}
        for item in records:
            if item.rtype == TXT and _key(item.name) in instances:
                for key, value in _txt_fields(item.strings).items():
                    fields.setdefault(key, value)

        def first(keys: tuple[str, ...]) -> str | None:
            for key in keys:
                value = fact_text(fields.get(key, "").strip("()"))
                if value is not None:
                    return value
            return None

        services: list[str] = []
        for service in types.values():
            label = mdns_label(service)
            if label is not None and label not in services:
                services.append(label)
        model, maker, firmware = (
            first(_TXT_MODEL),
            first(_TXT_MAKER),
            first(_TXT_FIRMWARE),
        )
        if model is None and maker is None and firmware is None and not services:
            return None
        return DeviceDescription(
            "mdns",
            self.clock(),
            manufacturer=maker,
            model_name=model,
            firmware=firmware,
            services=tuple(services[:MAX_SERVICES]),
        )
