"""Device self-descriptions and advisory facts: unicast, bounded and identifier-free."""

from __future__ import annotations

import asyncio
import json
import struct
import tempfile
import unittest
from dataclasses import asdict, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Awaitable, Callable

from aiohttp import web
from aiohttp.test_utils import TestServer
from support import FixturePort, MemoryReceipts, configuration

from plowshare_privacy.advisories import Advisory, AdvisoryPage, AdvisoryUnavailable
from plowshare_privacy.collection import NetworkCollector, scope_fingerprint
from plowshare_privacy.contracts import CollectionPlan, Configuration, DeviceIdentity
from plowshare_privacy.device_describer import (
    MDNS_PORT,
    PTR,
    SSDP_PORT,
    TXT,
    HttpDescriptionFetch,
    LocalDeviceDescriber,
    MalformedPacket,
    UdpExchange,
    _FirstReply,
    decode_message,
    encode_query,
    parse_upnp,
    ssdp_location,
    upnp_label,
)
from plowshare_privacy.device_facts import (
    DEVICES_PER_CHECK,
    MAX_DEVICES,
    PACE_SECONDS,
    AdvisoryFacts,
    DescriptionResult,
    DeviceDescription,
    DeviceFacts,
    DeviceFactsJournal,
    DeviceFactsService,
    FactsLedger,
)
from plowshare_privacy.identity_source import ManagedProduct
from plowshare_privacy.monitor import MonitorChoice
from plowshare_privacy.presence import (
    PresenceHistory,
    PresenceSample,
    PresenceWatch,
    Sighting,
)
from plowshare_privacy.worker import Worker

TV = "192.168.20.5"
SPEAKER = "192.168.20.6"
START = datetime(2026, 11, 12, 7, 10, tzinfo=timezone.utc)

SONY_LIKE = b"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:av="urn:schemas-sony-com:av">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>Living room TV of Fixture Family</friendlyName>
    <manufacturer>Sony Corporation</manufacturer>
    <manufacturerURL>http://www.sony.net/</manufacturerURL>
    <modelName>KD-55X85K</modelName>
    <modelNumber>KD55X85K</modelNumber>
    <modelDescription>BRAVIA firmware 6.6.0 build 99</modelDescription>
    <serialNumber>4012345</serialNumber>
    <UDN>uuid:00000000-0000-1010-8000-0242ac110002</UDN>
    <presentationURL>http://192.168.20.5/</presentationURL>
    <av:X_SoftwareVersion>6.6.0</av:X_SoftwareVersion>
    <av:X_MacAddress>02:00:00:00:00:01</av:X_MacAddress>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
      </service>
      <service>
        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
      </service>
      <service>
        <serviceType>urn:schemas-sony-com:service:IRCC:1</serviceType>
      </service>
      <service>
        <serviceType>urn:example-vendor:service:Telemetry:2</serviceType>
      </service>
    </serviceList>
  </device>
</root>
"""


def at(seconds: float) -> str:
    return (START + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def identity(address: str, mac: str, source: str = "unifi") -> DeviceIdentity:
    return DeviceIdentity(
        address,
        "mac:" + mac,
        None,
        "fixture-hostname",
        mac,
        None,
        "unifi" if source == "unifi" else "pihole-v6",
        at(0),
        at(0),
    )


def plan(*targets: str, enabled: bool = True) -> CollectionPlan:
    return CollectionPlan(
        "tcp",
        targets,
        (443,),
        1,
        4,
        None,
        0,
        quick_scan=True,
        device_descriptions=enabled,
    )


def label(text: bytes) -> bytes:
    return bytes([len(text)]) + text


def name(*labels: bytes) -> bytes:
    return b"".join(label(item) for item in labels) + b"\0"


def record(owner: bytes, rtype: int, rdata: bytes) -> bytes:
    return owner + struct.pack("!HHIH", rtype, 0x8001, 120, len(rdata)) + rdata


def response(
    query_id: int, answers: list[bytes], additional: list[bytes] | None = None
) -> bytes:
    extra = additional or []
    header = struct.pack("!HHHHHH", query_id, 0x8400, 0, len(answers), 0, len(extra))
    return header + b"".join(answers) + b"".join(extra)


SERVICES = name(b"_services", b"_dns-sd", b"_udp", b"local")
CAST = name(b"_googlecast", b"_tcp", b"local")
CAST_INSTANCE = name(b"Fixture Family Kitchen", b"_googlecast", b"_tcp", b"local")


def txt(*entries: bytes) -> bytes:
    return b"".join(label(entry) for entry in entries)


class FakeDatagrams:
    """Answers like a device; records every destination it was asked to reach."""

    def __init__(self, ssdp: bytes | None = None, mdns: bool = True) -> None:
        self.sent: list[tuple[str, int, bytes]] = []
        self.ssdp = ssdp
        self.mdns = mdns

    async def exchange(
        self, address: str, port: int, payload: bytes, *, wait: float
    ) -> bytes | None:
        assert wait <= 2
        self.sent.append((address, port, payload))
        if port == SSDP_PORT:
            return self.ssdp
        if not self.mdns:
            return None
        query = decode_message(payload)
        asked = query.questions[0]
        if asked == ((b"_services", b"_dns-sd", b"_udp", b"local"), PTR):
            return response(query.query_id, [record(SERVICES, PTR, CAST)])
        if asked[1] == PTR:
            # The instance arrives with its TXT record in the additional section.
            return response(
                query.query_id,
                [record(CAST, PTR, CAST_INSTANCE)],
                [
                    record(
                        CAST_INSTANCE,
                        TXT,
                        txt(
                            b"id=0123456789abcdef0123456789abcdef",
                            b"md=Chromecast",
                            b"fn=Fixture Family Kitchen",
                            b"rs=",
                        ),
                    )
                ],
            )
        return None


class FakeFetch:
    def __init__(self, document: bytes | None = SONY_LIKE) -> None:
        self.urls: list[str] = []
        self.document = document

    async def fetch(self, url: str) -> bytes | None:
        self.urls.append(url)
        return self.document


def ssdp_reply(location: str) -> bytes:
    return (
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n"
        f"LOCATION: {location}\r\nST: upnp:rootdevice\r\n"
        "USN: uuid:00000000-0000-1010-8000-0242ac110002::upnp:rootdevice\r\n\r\n"
    ).encode()


class UpnpTest(unittest.TestCase):
    def test_sony_like_description_keeps_product_fields_and_drops_identifiers(
        self,
    ) -> None:
        description = parse_upnp(SONY_LIKE, at(0))
        assert description is not None
        self.assertEqual(description.source, "upnp")
        self.assertEqual(description.manufacturer, "Sony Corporation")
        self.assertEqual(description.model_name, "KD-55X85K")
        self.assertEqual(description.model_number, "KD55X85K")
        # Only an explicit version field counts, never modelDescription prose.
        self.assertEqual(description.firmware, "6.6.0")
        self.assertEqual(
            description.services,
            (
                "media rendering (DLNA)",
                "media transport (DLNA)",
                "screen casting (DIAL)",
                "remote control (Sony IRCC)",
                "Telemetry",
            ),
        )
        stored = json.dumps(asdict(description))
        for secret in (
            "4012345",
            "uuid:",
            "Fixture Family",
            "02:00:00:00:00:01",
            "192.168.20.5",
            "BRAVIA firmware",
        ):
            self.assertNotIn(secret, stored)

    def test_doctype_and_entity_declarations_are_refused(self) -> None:
        for document in (
            b'<?xml version="1.0"?><!DOCTYPE root [<!ENTITY a "x">]><root/>',
            b"<root><!entity a 'b'></root>",
            b"<!doctype root><root/>",
        ):
            with self.assertRaisesRegex(ValueError, "document types or entities"):
                parse_upnp(document, at(0))
        with self.assertRaises(ValueError):
            parse_upnp("<root/>".encode("utf-16"), at(0))

    def test_upnp_labels(self) -> None:
        self.assertEqual(
            upnp_label("urn:dial-multiscreen-org:service:dial:1"),
            "screen casting (DIAL)",
        )
        self.assertEqual(
            upnp_label("urn:schemas-upnp-org:device:MediaRenderer:1"),
            "media rendering (DLNA)",
        )
        self.assertIsNone(upnp_label("urn:schemas-upnp-org:device:Basic:1"))
        self.assertIsNone(upnp_label("not-a-urn"))
        self.assertIsNone(upnp_label("urn:x:service:<script>:1"))

    def test_location_must_be_plain_http_on_the_device_address(self) -> None:
        self.assertEqual(
            ssdp_location(ssdp_reply("http://192.168.20.5:52323/dmr.xml"), TV),
            "http://192.168.20.5:52323/dmr.xml",
        )
        for location in (
            "http://192.168.20.9:52323/dmr.xml",
            "http://tv.local:52323/dmr.xml",
            "https://192.168.20.5/dmr.xml",
            "http://user@192.168.20.5/dmr.xml",
            "http://192.168.20.5.attacker.example/dmr.xml",
            "http://192.168.020.5/dmr.xml",
            "http://192.168.20.5:99999/dmr.xml",
            "file:///etc/passwd",
        ):
            self.assertIsNone(ssdp_location(ssdp_reply(location), TV), location)
        self.assertIsNone(ssdp_location(b"HTTP/1.1 404 Not Found\r\n\r\n", TV))
        self.assertIsNone(ssdp_location(b"\xff\xfe garbage", TV))


class FetchTest(unittest.IsolatedAsyncioTestCase):
    async def serve(
        self, handler: Callable[[web.Request], Awaitable[web.StreamResponse]]
    ) -> str:
        app = web.Application()
        app.router.add_get("/{tail:.*}", handler)
        server = TestServer(app, host="127.0.0.1")
        await server.start_server()
        self.addAsyncCleanup(server.close)
        return str(server.make_url("/description.xml"))

    async def test_fetch_refuses_redirects_and_oversized_documents(self) -> None:
        async def redirect(request: web.Request) -> web.StreamResponse:
            raise web.HTTPFound("http://192.168.20.9/elsewhere.xml")

        async def large(request: web.Request) -> web.StreamResponse:
            return web.Response(body=b"<root>" + b"x" * 70000 + b"</root>")

        async def chunked(request: web.Request) -> web.StreamResponse:
            reply = web.StreamResponse()
            reply.enable_chunked_encoding()
            await reply.prepare(request)
            for _ in range(20):
                await reply.write(b"x" * 4096)
            return reply

        async def small(request: web.Request) -> web.StreamResponse:
            return web.Response(body=SONY_LIKE)

        fetch = HttpDescriptionFetch()
        self.assertIsNone(await fetch.fetch(await self.serve(redirect)))
        self.assertIsNone(await fetch.fetch(await self.serve(large)))
        self.assertIsNone(await fetch.fetch(await self.serve(chunked)))
        self.assertEqual(await fetch.fetch(await self.serve(small)), SONY_LIKE)
        self.assertIsNone(await fetch.fetch("https://192.168.20.5/description.xml"))


class DnsWireTest(unittest.TestCase):
    def test_query_round_trip_requests_unicast_replies(self) -> None:
        questions = (
            ((b"_services", b"_dns-sd", b"_udp", b"local"), PTR),
            ((b"Instance", b"_ipp", b"_tcp", b"local"), TXT),
        )
        packet = encode_query(4660, questions)
        message = decode_message(packet)
        self.assertEqual(message.query_id, 4660)
        self.assertFalse(message.response)
        self.assertEqual(message.questions, questions)
        # Every question asks for a unicast (QU) reply in the IN class.
        self.assertEqual(packet[-2:], b"\x80\x01")

    def test_compressed_names_decode(self) -> None:
        header = struct.pack("!HHHHHH", 7, 0x8400, 0, 1, 0, 0)
        owner = name(b"_googlecast", b"_tcp", b"local")
        # The PTR target compresses its suffix to the owner name at offset 12.
        target = label(b"Kitchen") + b"\xc0\x0c"
        packet = header + record(owner, PTR, target)
        message = decode_message(packet)
        self.assertEqual(
            message.records[0].target,
            (b"Kitchen", b"_googlecast", b"_tcp", b"local"),
        )

    def test_malformed_packets_are_rejected(self) -> None:
        header = struct.pack("!HHHHHH", 7, 0x8400, 0, 1, 0, 0)
        loop = header + b"\xc0\x0c" + struct.pack("!HHIH", PTR, 1, 0, 0)
        forward = header + b"\xc0\x20" + struct.pack("!HHIH", PTR, 1, 0, 0) + b"\0" * 32
        mutual = header + label(b"a") + b"\xc0\x0c" + struct.pack("!HHIH", PTR, 1, 0, 0)
        long_name = header + (label(b"a" * 63) * 5) + b"\0"
        cases = {
            "short header": b"\0" * 11,
            "self pointer": loop,
            "forward pointer": forward,
            "pointer back into the same name": mutual,
            "truncated rdata": header
            + name(b"x")
            + struct.pack("!HHIH", TXT, 1, 0, 40)
            + b"abc",
            "truncated label": header + b"\x05ab",
            "overlong name": long_name + struct.pack("!HHIH", TXT, 1, 0, 0),
            "reserved label type": header + b"\x80" + struct.pack("!HHIH", 1, 1, 0, 0),
            "excess records": struct.pack("!HHHHHH", 7, 0x8400, 0, 200, 0, 0),
            "bad txt": header
            + name(b"x")
            + struct.pack("!HHIH", TXT, 1, 0, 2)
            + b"\x05a",
            "trailing bytes": header
            + name(b"x")
            + struct.pack("!HHIH", 1, 1, 0, 0)
            + b"z",
            "oversized": b"\0" * 9001,
        }
        for description, packet in cases.items():
            with self.assertRaises(MalformedPacket, msg=description):
                decode_message(packet)

    def test_query_encoder_bounds_labels(self) -> None:
        with self.assertRaises(ValueError):
            encode_query(1, (((b"a" * 64,), PTR),))
        with self.assertRaises(ValueError):
            encode_query(1, (((b"",), PTR),))
        with self.assertRaises(ValueError):
            encode_query(1, tuple(((b"a",), PTR) for _ in range(9)))


class DescriberTest(unittest.IsolatedAsyncioTestCase):
    async def test_describes_one_watched_device_over_unicast_only(self) -> None:
        datagrams = FakeDatagrams(ssdp_reply("http://192.168.20.5:52323/dmr.xml"))
        fetch = FakeFetch()
        describer = LocalDeviceDescriber(
            lambda: (TV, SPEAKER), datagrams, fetch, clock=lambda: at(0)
        )
        result = await describer.describe(TV)
        self.assertEqual(result.issues, ())
        self.assertEqual(
            {(address, port) for address, port, _ in datagrams.sent},
            {(TV, SSDP_PORT), (TV, MDNS_PORT)},
        )
        self.assertLessEqual(len(datagrams.sent), 4)
        self.assertIn(b"ST: ssdp:all", datagrams.sent[0][2])
        self.assertEqual(fetch.urls, ["http://192.168.20.5:52323/dmr.xml"])
        upnp, mdns = result.descriptions
        self.assertEqual(upnp.model_name, "KD-55X85K")
        self.assertEqual(mdns.source, "mdns")
        self.assertEqual(mdns.model_name, "Chromecast")
        self.assertEqual(mdns.services, ("screen casting (Google Cast)",))
        stored = json.dumps([asdict(item) for item in result.descriptions])
        self.assertNotIn("Fixture Family", stored)
        self.assertNotIn("0123456789abcdef", stored)

    async def test_foreign_location_is_never_fetched(self) -> None:
        datagrams = FakeDatagrams(ssdp_reply("http://192.168.20.9/dmr.xml"), mdns=False)
        fetch = FakeFetch()
        describer = LocalDeviceDescriber(lambda: (TV,), datagrams, fetch)
        result = await describer.describe(TV)
        self.assertEqual(result, DescriptionResult())
        self.assertEqual(fetch.urls, [])

    async def test_hostile_documents_become_no_description(self) -> None:
        datagrams = FakeDatagrams(ssdp_reply("http://192.168.20.5/d.xml"), mdns=False)
        for document in (
            b"<!DOCTYPE x [<!ENTITY a 'b'>]><root/>",
            b"<root><device>",
            b"<other/>",
            None,
        ):
            describer = LocalDeviceDescriber(
                lambda: (TV,), datagrams, FakeFetch(document)
            )
            self.assertEqual(await describer.describe(TV), DescriptionResult())

    async def test_refuses_addresses_outside_the_watch_list(self) -> None:
        datagrams = FakeDatagrams()
        describer = LocalDeviceDescriber(
            lambda: (TV, "8.8.8.8", "192.168.20.255", "224.0.0.251"),
            datagrams,
            FakeFetch(),
        )
        for address in (SPEAKER, "8.8.8.8", "192.168.20.255", "224.0.0.251"):
            with self.assertRaisesRegex(ValueError, "watched private"):
                await describer.describe(address)
        self.assertEqual(datagrams.sent, [])

    async def test_socket_errors_are_diagnostics_not_failures(self) -> None:
        class Unreachable:
            async def exchange(
                self, address: str, port: int, payload: bytes, *, wait: float
            ) -> bytes | None:
                if port == SSDP_PORT:
                    raise ConnectionRefusedError()
                raise OSError(65, "No route to host")

        describer = LocalDeviceDescriber(lambda: (TV,), Unreachable(), FakeFetch())
        result = await describer.describe(TV)
        self.assertEqual(result.descriptions, ())
        self.assertEqual(result.issues, ("device_description_unavailable: OSError",))

    async def test_udp_exchange_refuses_non_unicast_and_filters_sources(
        self,
    ) -> None:
        exchange = UdpExchange()
        for address in ("224.0.0.251", "255.255.255.255", "8.8.8.8", "192.168.1.0"):
            with self.assertRaises(ValueError):
                await exchange.exchange(address, MDNS_PORT, b"x", wait=0.1)
        reply: asyncio.Future[bytes] = asyncio.get_running_loop().create_future()
        protocol = _FirstReply(TV, reply)
        protocol.datagram_received(b"spoofed", ("192.168.20.9", MDNS_PORT))
        protocol.datagram_received(b"x" * 9001, (TV, MDNS_PORT))
        self.assertFalse(reply.done())
        protocol.datagram_received(b"answer", (TV, 49152))
        self.assertEqual(await reply, b"answer")


class StaticDescriber:
    def __init__(self, *descriptions: DeviceDescription) -> None:
        self.descriptions = descriptions
        self.asked: list[str] = []

    async def describe(self, address: str) -> DescriptionResult:
        self.asked.append(address)
        return DescriptionResult(self.descriptions)


class CountingAdvisories:
    def __init__(self, fail: bool = False) -> None:
        self.models: list[str] = []
        self.fail = fail
        self.now = at(0)

    async def search(self, model: str) -> AdvisoryPage:
        self.models.append(model)
        if self.fail:
            raise AdvisoryUnavailable("NVD did not answer the lookup.")
        return AdvisoryPage(
            model,
            self.now,
            1,
            (
                Advisory(
                    "CVE-2026-0001",
                    "Fixture advisory.",
                    "2026-01-01",
                    "HIGH",
                    False,
                ),
            ),
        )


class Products:
    def __init__(self, product: ManagedProduct | None) -> None:
        self.product = product
        self.asked = 0

    async def managed_product(self, identity: DeviceIdentity) -> ManagedProduct | None:
        self.asked += 1
        return self.product


UPNP = DeviceDescription(
    "upnp",
    at(0),
    manufacturer="Sony Corporation",
    model_name="KD-55X85K",
    firmware="6.6.0",
    services=("screen casting (DIAL)",),
)


class FactsServiceTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.now = 0.0
        self.sleeps: list[float] = []
        self.plan = plan(TV, SPEAKER, "192.168.20.7", "192.168.20.8", "192.168.20.9")

    def service(
        self,
        describer: StaticDescriber,
        advisories: CountingAdvisories | None = None,
        products: Products | None = None,
        profile: dict[str, str] | None = None,
    ) -> DeviceFactsService:
        async def sleep(seconds: float) -> None:
            self.sleeps.append(seconds)

        models = profile or {}
        return DeviceFactsService(
            DeviceFactsJournal(self.root),
            describer,
            lambda: self.plan,
            advisories,
            lambda: products,
            models.get,
            clock=lambda: at(self.now),
            sleep=sleep,
        )

    def sample(self, *sightings: Sighting, **extra: object) -> PresenceSample:
        return PresenceSample(at(self.now), sightings, **extra)  # type: ignore[arg-type]

    async def test_describes_once_a_day_and_again_on_a_new_association(
        self,
    ) -> None:
        describer = StaticDescriber(UPNP)
        service = self.service(describer)
        mac = "02:00:00:00:00:10"
        await service.refresh(self.sample(Sighting(TV, identity(TV, mac))))
        facts = service.facts("mac:" + mac)
        assert facts is not None
        self.assertEqual(facts.descriptions, (UPNP,))
        self.assertEqual(facts.address, TV)
        self.now = 3600
        await service.refresh(self.sample(Sighting(TV, identity(TV, mac))))
        self.assertEqual(describer.asked, [TV])
        # The same MAC at a new watched address is a new association.
        await service.refresh(self.sample(Sighting(SPEAKER, identity(SPEAKER, mac))))
        self.assertEqual(describer.asked, [TV, SPEAKER])
        self.now = 3600 + 86400
        await service.refresh(self.sample(Sighting(SPEAKER, identity(SPEAKER, mac))))
        self.assertEqual(describer.asked, [TV, SPEAKER, SPEAKER])
        # Facts survive a restart through the private journal.
        reopened = self.service(StaticDescriber())
        self.assertEqual(reopened.facts("mac:" + mac), service.facts("mac:" + mac))
        self.assertEqual(
            (self.root / "device-facts.json").stat().st_mode & 0o777, 0o600
        )

    async def test_silence_keeps_earlier_facts_and_updated_at(self) -> None:
        mac = "02:00:00:00:00:10"
        service = self.service(StaticDescriber(UPNP))
        await service.refresh(self.sample(Sighting(TV, identity(TV, mac))))
        self.now = 90000
        silent = self.service(StaticDescriber())
        await silent.refresh(self.sample(Sighting(TV, identity(TV, mac))))
        facts = silent.facts("mac:" + mac)
        assert facts is not None
        self.assertEqual(facts.descriptions, (UPNP,))
        self.assertEqual(facts.described_at, at(90000))
        self.assertEqual(facts.updated_at, at(0))
        changed = self.service(
            StaticDescriber(replace(UPNP, observed_at=at(90000), firmware="6.7.0"))
        )
        self.now = 200000
        await changed.refresh(self.sample(Sighting(TV, identity(TV, mac))))
        facts = changed.facts("mac:" + mac)
        assert facts is not None
        self.assertEqual(facts.updated_at, at(200000))

    async def test_scheduler_limits_each_check_and_picks_oldest_first(self) -> None:
        describer = StaticDescriber()
        service = self.service(describer)
        addresses = (TV, SPEAKER, "192.168.20.7", "192.168.20.8", "192.168.20.9")
        sightings = tuple(
            Sighting(address, identity(address, f"02:00:00:00:00:2{index}"))
            for index, address in enumerate(addresses)
        )
        await service.refresh(self.sample(*sightings))
        self.assertEqual(len(describer.asked), DEVICES_PER_CHECK)
        self.assertEqual(self.sleeps, [PACE_SECONDS] * (DEVICES_PER_CHECK - 1))
        self.assertGreaterEqual(PACE_SECONDS, 0.25)
        self.now = 120
        await service.refresh(self.sample(*sightings))
        # Only the never-described device remains due.
        self.assertEqual(describer.asked[-1], "192.168.20.9")
        self.assertEqual(len(describer.asked), 5)
        self.now = 86400 + 60
        await service.refresh(self.sample(*reversed(sightings)))
        self.assertEqual(describer.asked[5:], list(addresses[:4]))

    async def test_only_confirmed_non_infrastructure_watched_macs(self) -> None:
        describer = StaticDescriber(UPNP)
        service = self.service(describer)
        switch = identity(SPEAKER, "02:00:00:00:00:31")
        outside = identity("192.168.30.1", "02:00:00:00:00:32")
        conflicted = identity("192.168.20.7", "02:00:00:00:00:33")
        await service.refresh(
            self.sample(
                Sighting(TV),
                Sighting(SPEAKER, switch),
                Sighting("192.168.30.1", outside),
                Sighting("192.168.20.7", conflicted),
                ambiguous_addresses=frozenset({"192.168.20.7"}),
                infrastructure=frozenset({switch.device_id}),
            )
        )
        self.assertEqual(describer.asked, [])

    async def test_disabled_flag_describes_nothing(self) -> None:
        describer = StaticDescriber(UPNP)
        service = self.service(describer)
        self.plan = replace(self.plan, device_descriptions=False)
        await service.refresh(
            self.sample(Sighting(TV, identity(TV, "02:00:00:00:00:10")))
        )
        self.assertEqual(describer.asked, [])

    async def test_advisory_model_precedence(self) -> None:
        mac = "02:00:00:00:00:10"
        sighting = Sighting(TV, identity(TV, mac))
        cases: tuple[tuple[dict[str, str], Products | None, str, str], ...] = (
            ({"mac:" + mac: "Sony XR-65A80K"}, None, "Sony XR-65A80K", "profile"),
            (
                {},
                Products(ManagedProduct("Ubiquiti", "U6-Pro", None, None)),
                "Ubiquiti U6-Pro",
                "unifi",
            ),
            ({}, Products(None), "Sony KD-55X85K", "device"),
        )
        for profile, products, model, source in cases:
            (self.root / "device-facts.json").unlink(missing_ok=True)
            advisories = CountingAdvisories()
            service = self.service(StaticDescriber(UPNP), advisories, products, profile)
            await service.refresh(self.sample(sighting))
            facts = service.facts("mac:" + mac)
            assert facts is not None and facts.advisories is not None
            self.assertEqual(
                (facts.advisories.model, facts.advisories.model_source),
                (model, source),
            )
            self.assertEqual(facts.advisories.status, "searched")
            self.assertEqual(advisories.models, [model])

    async def test_advisories_cache_each_model_for_a_week(self) -> None:
        advisories = CountingAdvisories()
        describer = StaticDescriber(UPNP)
        service = self.service(describer, advisories)
        first = Sighting(TV, identity(TV, "02:00:00:00:00:41"))
        second = Sighting(SPEAKER, identity(SPEAKER, "02:00:00:00:00:42"))
        await service.refresh(self.sample(first, second))
        self.assertEqual(advisories.models, ["Sony KD-55X85K"])
        self.now = 3 * 86400
        await service.refresh(self.sample(first, second))
        self.assertEqual(len(advisories.models), 1)
        self.now = 8 * 86400
        advisories.now = at(self.now)
        await service.refresh(self.sample(first, second))
        self.assertEqual(len(advisories.models), 2)
        facts = service.facts(first.key)
        assert facts is not None and facts.advisories is not None
        assert facts.advisories.page is not None
        self.assertEqual(facts.advisories.page.advisories[0].cve, "CVE-2026-0001")

    async def test_unavailable_and_unconfigured_lookups(self) -> None:
        mac = "02:00:00:00:00:10"
        sighting = Sighting(TV, identity(TV, mac))
        service = self.service(StaticDescriber(UPNP), None)
        await service.refresh(self.sample(sighting))
        facts = service.facts("mac:" + mac)
        assert facts is not None and facts.advisories is not None
        self.assertEqual(facts.advisories.status, "not_configured")
        self.assertEqual(facts.advisories.model, "Sony KD-55X85K")
        (self.root / "device-facts.json").unlink()
        failing = self.service(StaticDescriber(UPNP), CountingAdvisories(fail=True))
        await failing.refresh(self.sample(sighting))
        facts = failing.facts("mac:" + mac)
        assert facts is not None and facts.advisories is not None
        self.assertEqual(facts.advisories.status, "unavailable")
        (self.root / "device-facts.json").unlink()
        unknown = self.service(StaticDescriber(), CountingAdvisories())
        await unknown.refresh(self.sample(sighting))
        facts = unknown.facts("mac:" + mac)
        assert facts is not None and facts.advisories is not None
        self.assertEqual(facts.advisories.status, "model_unknown")

    async def test_journal_is_bounded_and_fails_clearly_when_corrupt(self) -> None:
        journal = DeviceFactsJournal(self.root)
        devices = tuple(
            DeviceFacts(
                f"mac:02:00:00:00:{index // 256:02x}:{index % 256:02x}",
                TV,
                at(index),
                at(index),
            )
            for index in range(MAX_DEVICES)
        )
        journal.save(FactsLedger(devices=devices))
        with self.assertRaises(ValueError):
            journal.save(
                FactsLedger(
                    devices=(
                        *devices,
                        DeviceFacts("mac:02:00:00:01:00:00", TV, at(0), at(0)),
                    )
                )
            )
        service = self.service(StaticDescriber())
        await service.refresh(
            self.sample(Sighting(SPEAKER, identity(SPEAKER, "02:00:00:00:09:99")))
        )
        retained = journal.read().devices
        self.assertEqual(len(retained), MAX_DEVICES)
        self.assertNotIn(devices[0].device_id, {item.device_id for item in retained})
        self.assertIn("mac:02:00:00:00:09:99", {item.device_id for item in retained})
        (self.root / "device-facts.json").write_text("{not json")
        with self.assertRaises(ValueError):
            journal.read()
        broken = self.service(StaticDescriber(UPNP))
        self.assertIsNotNone(broken.error)
        await broken.refresh(
            self.sample(Sighting(TV, identity(TV, "02:00:00:00:00:10")))
        )
        self.assertEqual((self.root / "device-facts.json").read_text(), "{not json")

    def test_records_validate_on_decode(self) -> None:
        with self.assertRaises(ValueError):
            DeviceDescription("upnp", at(0), model_name="x" * 200)
        with self.assertRaises(ValueError):
            DeviceDescription("upnp", at(0), model_name="02:00:00:00:00:01")
        with self.assertRaises(ValueError):
            DeviceDescription("ssdp", at(0), model_name="KD-55X85K")  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            DeviceDescription("upnp", at(0))
        with self.assertRaises(ValueError):
            AdvisoryFacts("searched", at(0), "Sony KD-55X85K", "device")
        with self.assertRaises(ValueError):
            DeviceFacts("ip:192.168.20.5", TV, at(0), at(0))
        with self.assertRaises(ValueError):
            DeviceFacts("mac:02:00:00:00:00:10", "8.8.8.8", at(0), at(0))
        facts = DeviceFacts(
            "mac:02:00:00:00:00:10",
            TV,
            at(0),
            at(0),
            (UPNP,),
            AdvisoryFacts("model_unknown", at(0)),
        )
        self.assertEqual(
            FactsLedger.decode(json.loads(json.dumps(asdict(FactsLedger((facts,)))))),
            FactsLedger((facts,)),
        )


class ConfigurationTest(unittest.TestCase):
    def row(self, **collection: object) -> dict[str, object]:
        return {
            "origin": "https://plowshare.example.invalid",
            "project": "network-privacy-watch",
            "collector": "home-network",
            "tokenEnvironment": "PLOWSHARE_TOKEN",
            "webTokenEnvironment": "PRIVACY_WEB_TOKEN",
            "stateDirectory": "/var/lib/privacy-fixture",
            "requestTopic": "privacy.scan.requests",
            "resultTopic": "privacy.scan.completed",
            "group": "privacy-home-network",
            "schedule": "scheduled-example",
            "collection": {
                "mode": "tcp",
                "targets": [TV],
                "ports": [443],
                "timeoutSeconds": 1,
                "concurrency": 4,
                **collection,
            },
        }

    def test_device_descriptions_require_quick_checks(self) -> None:
        path = Path("/var/lib/privacy-fixture/collector.json")
        config = Configuration.decode(
            self.row(quickScan=True, deviceDescriptions=True), path
        )
        self.assertTrue(config.collection.device_descriptions)
        self.assertFalse(
            Configuration.decode(
                self.row(quickScan=True), path
            ).collection.device_descriptions
        )
        with self.assertRaisesRegex(ValueError, "require quick presence checks"):
            Configuration.decode(self.row(deviceDescriptions=True), path)
        with self.assertRaisesRegex(ValueError, "must be a boolean"):
            Configuration.decode(
                self.row(quickScan=True, deviceDescriptions="yes"), path
            )
        # Asking devices is local presence work; it never changes scan identity.
        self.assertEqual(
            scope_fingerprint(config.collection),
            scope_fingerprint(replace(config.collection, device_descriptions=False)),
        )

    def test_monitor_choice_accepts_only_booleans(self) -> None:
        choice = MonitorChoice.decode(
            {
                "enabled": True,
                "targets": [TV],
                "ports": [443],
                "quickScan": True,
                "deviceDescriptions": True,
            }
        )
        self.assertTrue(choice.device_descriptions)
        with self.assertRaisesRegex(ValueError, "must be a boolean"):
            MonitorChoice.decode(
                {
                    "enabled": True,
                    "targets": [TV],
                    "ports": [443],
                    "deviceDescriptions": 1,
                }
            )


class RecordingFacts:
    def __init__(self) -> None:
        self.samples: list[PresenceSample] = []
        self.release = asyncio.Event()

    def facts(self, device_id: str) -> DeviceFacts | None:
        return None

    async def refresh(self, sample: PresenceSample) -> None:
        self.samples.append(sample)
        await self.release.wait()


class WorkerWiringTest(unittest.IsolatedAsyncioTestCase):
    async def test_facts_step_follows_recorded_checks_without_holding_the_lock(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            watched = plan(TV)
            config = replace(configuration(root), collection=watched)
            clock = 0.0
            watch = PresenceWatch(
                PresenceHistory(root),
                monotonic=lambda: clock,
                clock=lambda: at(clock),
                delay=lambda: 120.0,
            )
            facts = RecordingFacts()
            collector = NetworkCollector("fixture", watched)
            worker = Worker(
                config, FixturePort(config), MemoryReceipts(), collector, presence=watch
            )
            worker.facts = facts

            async def sample(since: str) -> PresenceSample:
                return PresenceSample(
                    at(clock), (Sighting(TV, identity(TV, "02:00:00:00:00:10")),)
                )

            collector.collect_presence = sample  # type: ignore[method-assign]
            await worker.poll_presence()
            self.assertEqual(facts.samples, [])
            clock = 120
            await worker.poll_presence()
            await asyncio.sleep(0)
            self.assertEqual(len(facts.samples), 1)
            self.assertFalse(worker.lock.locked())
            # A running step is never overlapped; the next check skips it.
            clock = 240
            await worker.poll_presence()
            await asyncio.sleep(0)
            self.assertEqual(len(facts.samples), 1)
            facts.release.set()
            await asyncio.sleep(0)
            clock = 360
            await worker.poll_presence()
            await asyncio.sleep(0)
            self.assertEqual(len(facts.samples), 2)
            worker.config = replace(
                config, collection=replace(watched, device_descriptions=False)
            )
            clock = 480
            await worker.poll_presence()
            await asyncio.sleep(0)
            self.assertEqual(len(facts.samples), 2)
            self.assertIsNone(worker.device_facts("mac:02:00:00:00:00:10"))
            await worker.stop_facts()


if __name__ == "__main__":
    unittest.main()
