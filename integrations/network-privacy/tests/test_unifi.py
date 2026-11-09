"""Bounded local UniFi reads and coexistence with other scan evidence."""

import json
import os
import shutil
import ssl
import subprocess
import tempfile
import unittest
from collections.abc import AsyncIterator
from dataclasses import asdict, replace
from hashlib import sha256
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import UUID

from aiohttp import web
from aiohttp.test_utils import TestServer

from plowshare_privacy.collection import NetworkCollector, scope_fingerprint
from plowshare_privacy.contracts import (
    CollectionPlan,
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    Evidence,
    PiHolePlan,
    UniFiPlan,
    utc_now,
)
from plowshare_privacy.identity_source import IdentitySample
from plowshare_privacy.pihole import DeviceData
from plowshare_privacy.unifi import UniFiNetwork

SITE = "00000000-0000-4000-8000-000000000001"
BASE = "https://unifi.example.invalid/proxy/network/integration/v1"
KEY = "PRIVACY_FIXTURE_UNIFI_KEY"
FIRST = "192.0.2.10"
SECOND = "192.0.2.11"
THIRD = "192.0.2.12"
MAC = "00:11:22:33:44:55"
OTHER_MAC = "00:11:22:33:44:66"


def configuration() -> dict[str, str]:
    return {"apiBase": BASE, "siteId": SITE, "apiKeyEnvironment": KEY}


def client(
    index: int, address: str, mac: str = MAC, client_type: str = "WIRED"
) -> dict[str, str]:
    return {
        "id": str(UUID(int=index)),
        "type": client_type,
        "name": "fixture-device",
        "ipAddress": address,
        "macAddress": mac,
        "uplinkDeviceId": "unretained",
    }


def page(
    offset: int, total: int, *clients: dict[str, str], status: int = 200
) -> MagicMock:
    body = json.dumps(
        {
            "offset": offset,
            "limit": 100,
            "totalCount": total,
            "count": len(clients),
            "data": clients,
        }
    ).encode()

    async def chunks(_: int) -> AsyncIterator[bytes]:
        yield body

    request = MagicMock()
    response = request.__aenter__.return_value
    response.status = status
    response.content.iter_chunked = chunks
    return request


class UniFiTest(unittest.IsolatedAsyncioTestCase):
    def test_validate_source_configuration(self) -> None:
        for invalid in (
            {"apiBase": BASE.replace("https:", "http:")},
            {"apiBase": BASE + "?redirect=elsewhere"},
            {"apiBase": "https://user@unifi.example.invalid/integration/v1"},
            {"apiBase": "https://unifi.example.invalid/other"},
            {"siteId": "default"},
            {"apiKeyEnvironment": "invalid-key-name"},
            {"caFile": "relative.pem"},
            {"certificateSha256": "aa" * 20},
            {"certificateSha256": "gg" * 32},
            {"certificateSha256": "aa" * 32, "caFile": "/trusted/ca.pem"},
            {"apiKey": "do-not-store"},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                UniFiPlan.decode(configuration() | invalid)
        with (
            patch.dict(os.environ, {}, clear=True),
            self.assertRaisesRegex(ValueError, "environment"),
        ):
            UniFiNetwork(UniFiPlan.decode(configuration()))

    def test_standard_tls_keeps_existing_receipt_scope(self) -> None:
        unifi = UniFiPlan.decode(configuration())
        plan = CollectionPlan("tcp", (FIRST,), (443,), 0.1, 1, None, 3600, unifi=unifi)
        original = asdict(plan)
        original.pop("enabled")
        original.pop("labels")
        original.pop("pihole")
        original["unifi"].pop("certificate_sha256")
        expected = sha256(json.dumps(original, sort_keys=True).encode()).hexdigest()
        self.assertEqual(scope_fingerprint(plan), expected)
        self.assertNotEqual(
            scope_fingerprint(
                replace(plan, unifi=replace(unifi, certificate_sha256="aa" * 32))
            ),
            expected,
        )

    @unittest.skipUnless(
        shutil.which("openssl"), "requires OpenSSL for a local TLS fixture"
    )
    async def test_certificate_pin_verifies_before_sending_the_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert = Path(directory) / "leaf.pem"
            key = Path(directory) / "key.pem"
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-days",
                    "1",
                    "-subj",
                    "/CN=fixture.invalid",
                    "-out",
                    str(cert),
                    "-keyout",
                    str(key),
                ],
                check=True,
                capture_output=True,
            )
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            pin = sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()

        received = []

        async def connected_clients(request: web.Request) -> web.Response:
            received.append(request.headers["X-API-Key"])
            return web.json_response(
                {
                    "offset": 0,
                    "limit": 100,
                    "totalCount": 1,
                    "count": 1,
                    "data": [client(1, FIRST)],
                }
            )

        app = web.Application()
        app.router.add_get(f"/integration/v1/sites/{SITE}/clients", connected_clients)
        server = TestServer(app)
        await server.start_server(ssl=context)
        self.addAsyncCleanup(server.close)
        settings = configuration() | {
            "apiBase": str(server.make_url("/integration/v1"))
        }
        with patch.dict(os.environ, {KEY: "fixture-key"}):
            wrong = UniFiNetwork(
                UniFiPlan.decode(settings | {"certificateSha256": "00" * 32})
            )
            refused = await wrong.identify((FIRST,), utc_now())
            self.assertEqual(refused.devices, ())
            self.assertIn("pin mismatch", refused.issues[0])
            self.assertEqual(received, [])
            right = UniFiNetwork(
                UniFiPlan.decode(settings | {"certificateSha256": pin.upper()})
            )
            observed = await right.identify((FIRST,), utc_now())
        self.assertEqual([device.address for device in observed.devices], [FIRST])
        self.assertEqual(received, ["fixture-key"])

    async def test_paginates_only_connected_physical_clients_within_scope(self) -> None:
        with (
            patch.dict(os.environ, {KEY: "fixture-key"}),
            patch("plowshare_privacy.unifi.ClientSession") as factory,
        ):
            session = factory.return_value.__aenter__.return_value
            session.get = MagicMock(
                side_effect=[
                    page(0, 5, client(1, FIRST), client(2, SECOND), client(3, THIRD)),
                    page(
                        3,
                        5,
                        client(4, FIRST, OTHER_MAC),
                        client(5, "192.0.2.13", client_type="VPN"),
                    ),
                ]
            )
            source = UniFiNetwork(UniFiPlan.decode(configuration()))
            sample = await source.identify((FIRST, SECOND, "192.0.2.13"), utc_now())
        self.assertEqual(sample.ambiguous_addresses, frozenset({FIRST}))
        self.assertEqual([row.address for row in sample.devices], [SECOND])
        self.assertEqual(sample.devices[0].source, "unifi")
        self.assertEqual(sample.devices[0].hostname, "fixture-device")
        self.assertIn("unifi_ambiguous", sample.issues[0])
        self.assertEqual(
            factory.call_args.kwargs["headers"]["X-API-Key"], "fixture-key"
        )
        calls = session.get.call_args_list
        self.assertEqual(
            [call.args[0] for call in calls], [f"{BASE}/sites/{SITE}/clients"] * 2
        )
        self.assertEqual(
            [call.kwargs["params"] for call in calls],
            [
                {"offset": 0, "limit": 100},
                {"offset": 3, "limit": 100},
            ],
        )
        for call in calls:
            self.assertFalse(call.kwargs["allow_redirects"])
            self.assertTrue(call.kwargs["ssl"].check_hostname)

    async def test_refused_or_inconsistent_pages_withhold_partial_associations(
        self,
    ) -> None:
        for failed in (
            page(1, 2, status=401),
            page(0, 2, client(2, SECOND)),
            page(1, 2, client(1, SECOND)),
        ):
            with (
                patch.dict(os.environ, {KEY: "fixture-key"}),
                patch("plowshare_privacy.unifi.ClientSession") as factory,
            ):
                session = factory.return_value.__aenter__.return_value
                session.get = MagicMock(
                    side_effect=[page(0, 2, client(1, FIRST)), failed]
                )
                source = UniFiNetwork(UniFiPlan.decode(configuration()))
                sample = await source.identify((FIRST, SECOND), utc_now())
            self.assertEqual(sample.devices, ())
            self.assertIn("unifi_unavailable", sample.issues[0])

    async def test_sweep_retains_dns_and_withholds_disputed_identity(self) -> None:
        now = utc_now()
        plan = CollectionPlan(
            "tcp",
            (FIRST, SECOND, THIRD),
            (443,),
            0.1,
            1,
            None,
            3600,
            labels=(DeviceLabel(FIRST, "first"), DeviceLabel(SECOND, "second")),
            pihole=PiHolePlan.decode(
                {
                    "origin": "https://pihole.example.invalid",
                    "passwordEnvironment": "PIHOLE_TEST_PASSWORD",
                }
            ),
            unifi=UniFiPlan.decode(configuration()),
        )
        dns = DnsObservation(FIRST, "example.invalid", 1)
        pihole = MagicMock()
        pihole.read = AsyncMock(
            return_value=DeviceData(
                (
                    DeviceIdentity(
                        FIRST,
                        "mac:" + MAC,
                        None,
                        "old",
                        MAC,
                        None,
                        "pihole-v6",
                        now,
                        now,
                    ),
                ),
                (dns,),
                None,
                (),
            )
        )
        unifi = MagicMock()
        unifi.identify = AsyncMock(
            return_value=IdentitySample(
                (
                    DeviceIdentity(
                        FIRST,
                        "mac:" + OTHER_MAC,
                        None,
                        "changed",
                        OTHER_MAC,
                        None,
                        "unifi",
                        now,
                        now,
                    ),
                    DeviceIdentity(
                        SECOND,
                        "mac:" + MAC,
                        None,
                        "current",
                        MAC,
                        None,
                        "unifi",
                        now,
                        now,
                    ),
                )
            )
        )
        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=TimeoutError,
        ):
            evidence = await NetworkCollector("fixture", plan, pihole, unifi).collect(
                SITE,
                "00000000-0000-4000-8000-000000000002",
                None,
                None,
            )
        self.assertEqual(unifi.identify.call_args.args[0], plan.targets)
        unifi.identify.assert_awaited_once()
        self.assertEqual(evidence.snapshot.dns, (dns,))
        self.assertEqual(
            [device.source for device in evidence.snapshot.devices],
            ["configured", "unifi", "configured"],
        )
        self.assertEqual(
            [device.label for device in evidence.snapshot.devices],
            ["first", "second", None],
        )
        self.assertTrue(
            any(issue.startswith("identity_conflict:") for issue in evidence.issues)
        )
        self.assertEqual(Evidence.decode(json.loads(evidence.encode())), evidence)
