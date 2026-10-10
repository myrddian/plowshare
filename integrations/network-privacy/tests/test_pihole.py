"""Pi-hole v6 API sessions, scope and coverage on isolated real HTTP listeners."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import tempfile
import unittest
from dataclasses import asdict, replace
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from aiohttp import web
from aiohttp.test_utils import TestServer
from support import EXAMPLES, FixturePort, MemoryReceipts, configuration

from plowshare_privacy.collection import NetworkCollector, changes, scope_fingerprint
from plowshare_privacy.collector_factory import configured_collector
from plowshare_privacy.contracts import (
    CollectionPlan,
    Configuration,
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    DnsWindow,
    Evidence,
    PiHolePlan,
    Snapshot,
    device_labels,
    utc_now,
)
from plowshare_privacy.journal import Receipt
from plowshare_privacy.pihole import DeviceData, PiHoleV6
from plowshare_privacy.tools import (
    DestinationsCall,
    DestinationsResult,
    EvidenceCall,
    EvidenceResult,
    ScopeCall,
    ScopeResult,
    WorkerTools,
)
from plowshare_privacy.worker import Worker

ADDRESS = "192.0.2.12"
MAC = "00:11:22:33:44:55"
SECRET = "fixture-pihole-app-password"
SID = "fixture-session-identifier"


class PiHoleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.now = datetime.now(timezone.utc).timestamp()
        self.mode = "normal"
        self.calls: list[tuple[str, str]] = []
        self.query_clients: list[str] = []
        self.stolen = False
        app = web.Application()
        app.router.add_route("*", "/api/auth", self.auth)
        app.router.add_get("/api/network/devices", self.devices)
        app.router.add_get("/api/queries", self.queries)
        app.router.add_route("*", "/stolen", self.stolen_request)
        self.server = TestServer(app)
        await self.server.start_server()
        self.addAsyncCleanup(self.server.close)
        self.origin = str(self.server.make_url("/")).rstrip("/")
        self.plan = PiHolePlan.decode(
            {"origin": self.origin, "passwordEnvironment": "PIHOLE_TEST_PASSWORD"}
        )
        self.environment = patch.dict(os.environ, {"PIHOLE_TEST_PASSWORD": SECRET})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    async def stolen_request(self, request: web.Request) -> web.Response:
        self.stolen = True
        return web.json_response({})

    async def auth(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        if request.method == "DELETE":
            self.assertEqual(request.headers.get("X-FTL-SID"), SID)
            if self.mode == "cleanup_failure":
                return web.Response(status=503)
            return web.Response(status=204)
        self.assertEqual(request.method, "POST")
        self.assertEqual(await request.json(), {"password": SECRET})
        if self.mode == "authentication_failure":
            return web.json_response({"error": {"message": SECRET}}, status=401)
        if self.mode == "redirect":
            return web.Response(
                status=307, headers={"Location": self.origin + "/stolen"}
            )
        return web.json_response(
            {
                "session": {
                    "valid": True,
                    "sid": SID,
                    "csrf": "fixture",
                    "validity": 300,
                    "totp": False,
                    "message": "correct password",
                },
                "took": 0.01,
            }
        )

    def device(self, address: str, mac: str = MAC) -> dict[str, object]:
        return {
            "id": 1,
            "hwaddr": mac,
            "interface": "test",
            "firstSeen": int(self.now) - 7200,
            "lastQuery": int(self.now),
            "numQueries": 10,
            "macVendor": "Fixture vendor",
            "ips": [
                {
                    "ip": address,
                    "name": "living-room-tv",
                    "lastSeen": int(self.now) - (90000 if self.mode == "stale" else 1),
                    "nameUpdated": int(self.now) - 1,
                }
            ],
        }

    async def devices(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        self.assertEqual(request.headers.get("X-FTL-SID"), SID)
        self.assertNotIn("Cookie", request.headers)
        self.assertEqual(
            dict(request.query), {"max_devices": "256", "max_addresses": "16"}
        )
        devices = [self.device(ADDRESS), self.device("192.0.2.99")]
        if self.mode == "ambiguous":
            devices.append(self.device(ADDRESS, "00:11:22:33:44:66"))
        if self.mode == "identity_failure":
            return web.Response(status=503)
        if self.mode == "no_mac":
            devices[0]["hwaddr"] = "ip-" + ADDRESS
            devices[0]["macVendor"] = ""
        return web.json_response({"devices": devices, "took": 0.01})

    async def queries(self, request: web.Request) -> web.Response:
        self.calls.append((request.method, request.path))
        self.assertEqual(request.headers.get("X-FTL-SID"), SID)
        self.assertEqual(request.query["disk"], "false")
        self.assertEqual(int(request.query["length"]), self.plan.query_limit)
        address = request.query["client_ip"]
        self.query_clients.append(address)
        self.assertEqual(address, ADDRESS)
        if self.mode == "timeout":
            await asyncio.sleep(0.3)
        if self.mode == "oversized":
            return web.Response(body=b"x" * 1_048_577)
        query: dict[str, object] = {
            "id": 100,
            "time": self.now - 1,
            "domain": "Telemetry.Example.",
            "client": {
                "ip": "192.0.2.99" if self.mode == "foreign_query" else address,
                "name": "living-room-tv",
            },
            "type": "A",
            "status": "FORWARDED",
            "reply": {"type": "IP", "time": 1},
            "cname": None,
            "list_id": None,
            "dnssec": None,
            "upstream": None,
            "ede": {"code": 0, "text": None},
        }
        if self.mode == "outside_window":
            query["time"] = self.now - 7200
        if self.mode == "invalid_domain":
            query["domain"] = "_service._tcp.example"
        queries = [query, dict(query, id=101)]
        if self.mode == "duplicate":
            queries[1]["id"] = 100
        available = len(queries) + (10 if self.mode == "truncated" else 0)
        return web.json_response(
            {
                "queries": queries,
                "recordsFiltered": available,
                "recordsTotal": available,
                "cursor": 99,
                "earliest_timestamp": self.now
                - (60 if self.mode == "short_history" else 7200),
                "earliest_timestamp_disk": self.now - 7200,
                "took": 0.01,
            }
        )

    async def test_selected_device_and_query_sample_use_one_private_session(
        self,
    ) -> None:
        result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
        self.assertEqual(result.issues, ())
        self.assertEqual(len(result.devices), 1)
        self.assertEqual(result.devices[0].device_id, "mac:" + MAC)
        self.assertEqual(result.devices[0].hostname, "living-room-tv")
        self.assertEqual(result.devices[0].source, "pihole-v6")
        self.assertEqual(result.dns[0].domain, "telemetry.example")
        self.assertEqual(result.dns[0].queries, 2)
        self.assertIsNotNone(result.window)
        assert result.window is not None
        self.assertTrue(result.window.complete)
        self.assertEqual(result.window.queries_read, 2)
        self.assertEqual(self.query_clients, [ADDRESS])
        self.assertEqual(
            self.calls,
            [
                ("POST", "/api/auth"),
                ("GET", "/api/network/devices"),
                ("GET", "/api/queries"),
                ("DELETE", "/api/auth"),
            ],
        )
        serialized = json.dumps(asdict(result))
        self.assertNotIn(SECRET, serialized)
        self.assertNotIn(SID, serialized)
        self.assertNotIn("192.0.2.99", serialized)

    async def test_foreign_duplicate_and_out_of_window_queries_are_gaps(self) -> None:
        for mode in ("foreign_query", "duplicate", "outside_window", "oversized"):
            with self.subTest(mode=mode):
                self.mode = mode
                result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
                self.assertEqual(result.dns, ())
                self.assertTrue(
                    any(issue.startswith("dns_unavailable") for issue in result.issues)
                )
                self.assertEqual(result.devices[0].mac, MAC)
                assert result.window is not None
                self.assertFalse(result.window.complete)
                self.assertEqual(self.calls[-1], ("DELETE", "/api/auth"))

    async def test_stale_conflicting_and_unavailable_identity_is_not_reused(
        self,
    ) -> None:
        for mode in ("stale", "ambiguous", "identity_failure"):
            self.mode = mode
            result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
            self.assertEqual(result.devices, ())
            self.assertTrue(
                any(issue.startswith("identity_") for issue in result.issues)
            )
            self.assertEqual(result.dns[0].device, ADDRESS)

    async def test_truncation_short_history_and_invalid_names_mark_partial_coverage(
        self,
    ) -> None:
        for mode in ("truncated", "short_history", "invalid_domain"):
            self.mode = mode
            result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
            self.assertTrue(any(issue.startswith("dns_") for issue in result.issues))
            assert result.window is not None
            self.assertFalse(result.window.complete)

    async def test_auth_refusal_and_redirect_are_not_replayed_or_leaked(self) -> None:
        for mode in ("authentication_failure", "redirect"):
            self.mode = mode
            self.calls.clear()
            result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
            self.assertIsNone(result.window)
            self.assertEqual(self.calls, [("POST", "/api/auth")])
            self.assertNotIn(SECRET, json.dumps(asdict(result)))
            self.assertFalse(self.stolen)

    async def test_request_timeout_and_logout_failure_remain_visible(self) -> None:
        self.mode = "timeout"
        result = await PiHoleV6(replace(self.plan, timeout=0.1)).read(
            (ADDRESS,), utc_now()
        )
        self.assertTrue(
            any(issue.startswith("dns_unavailable") for issue in result.issues)
        )
        self.mode = "cleanup_failure"
        result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
        self.assertTrue(
            any(
                issue.startswith("pihole_session_cleanup_failed")
                for issue in result.issues
            )
        )
        self.assertEqual(len(result.dns), 1)

    async def test_ip_fallback_does_not_invent_a_mac_or_vendor(self) -> None:
        self.mode = "no_mac"
        result = await PiHoleV6(self.plan).read((ADDRESS,), utc_now())
        self.assertEqual(result.devices[0].device_id, "ip:" + ADDRESS)
        self.assertIsNone(result.devices[0].mac)
        self.assertIsNone(result.devices[0].vendor)

    async def test_collector_refuses_foreign_source_and_discloses_evidence_trimming(
        self,
    ) -> None:
        observed = utc_now()
        data = DeviceData(
            (), (DnsObservation("192.0.2.99", "foreign.example", 1),), None, ()
        )

        class Source:
            async def read(
                self, targets: tuple[str, ...], observed_at: str
            ) -> DeviceData:
                return data

        plan = CollectionPlan("tcp", (ADDRESS,), (443,), 1, 1, None, 0)
        collector = NetworkCollector("fixture-collector", plan, Source())
        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=ConnectionRefusedError(61, "fixture refused"),
        ):
            with self.assertRaisesRegex(ValueError, "outside the configured scope"):
                await collector.collect(str(uuid4()), "fixture:event", None, None)
            dns = tuple(
                DnsObservation(
                    ADDRESS,
                    "a" * 63
                    + "."
                    + "b" * 63
                    + "."
                    + "c" * 63
                    + "."
                    + str(index)
                    + ".example",
                    1,
                )
                for index in range(256)
            )
            data = DeviceData(
                (), dns, DnsWindow("pihole-v6", observed, observed, 256, 256, True), ()
            )
            evidence = await collector.collect(
                str(uuid4()), "fixture:event", None, None
            )
        self.assertLessEqual(len(evidence.encode().encode()), 16000)
        wrapped = json.dumps(
            {"revision": str(uuid4()), "evidence": asdict(evidence)},
            separators=(",", ":"),
        )
        self.assertLessEqual(len(wrapped), 16384)
        self.assertLess(len(evidence.snapshot.dns), 256)
        self.assertTrue(
            any(issue.startswith("dns_truncated") for issue in evidence.issues)
        )
        self.assertEqual(len(evidence.snapshot.tcp), 1)
        assert evidence.snapshot.dns_window is not None
        self.assertFalse(evidence.snapshot.dns_window.complete)
        self.assertEqual(evidence, Evidence.decode(json.loads(evidence.encode())))

    async def test_real_collection_retains_identity_labels_and_dns_without_server(
        self,
    ) -> None:
        async def accept(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            writer.close()
            await writer.wait_closed()

        listener = await asyncio.start_server(accept, "127.0.0.1", 0)
        self.addAsyncCleanup(listener.wait_closed)
        self.addCleanup(listener.close)
        plan = CollectionPlan(
            "tcp",
            (ADDRESS,),
            (listener.sockets[0].getsockname()[1],),
            1,
            1,
            None,
            0,
            labels=(DeviceLabel(ADDRESS, "Living room TV"),),
            pihole=self.plan,
        )
        original = asyncio.open_connection

        async def connection(
            address: str, port: int
        ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
            self.assertEqual(address, ADDRESS)
            return await original("127.0.0.1", port)

        with patch(
            "plowshare_privacy.collection.asyncio.open_connection",
            side_effect=connection,
        ):
            evidence = await configured_collector("fixture-collector", plan).collect(
                str(uuid4()), "fixture:event", None, None
            )
        self.assertEqual(evidence.snapshot.devices[0].label, "Living room TV")
        self.assertEqual(evidence.snapshot.devices[0].mac, MAC)
        self.assertEqual(evidence.snapshot.tcp[0].status, "open")
        self.assertEqual(evidence.snapshot.dns[0].queries, 2)
        self.assertEqual(evidence, Evidence.decode(json.loads(evidence.encode())))
        self.assertLess(len(evidence.encode().encode()), 32768)
        self.assertEqual(evidence.issues, ())
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            config = replace(config, collection=plan)
            receipts = MemoryReceipts()
            receipts.save(
                Receipt(
                    evidence.scan_id,
                    evidence.source_event,
                    config.request_topic,
                    utc_now(),
                    "done",
                    evidence,
                    str(uuid4()),
                )
            )
            worker = Worker(
                config,
                FixturePort(config),
                receipts,
                configured_collector(config.collector, plan),
            )
            tools = WorkerTools(worker)
            scope = await tools.execute(ScopeCall())
            assert isinstance(scope, ScopeResult)
            self.assertTrue(scope.pihole_configured)
            self.assertEqual(scope.device_labels, plan.labels)
            result = await tools.execute(EvidenceCall(evidence.scan_id))
            assert isinstance(result, EvidenceResult)
            self.assertEqual(
                result.evidence.snapshot.devices, evidence.snapshot.devices
            )
            destinations = await tools.execute(DestinationsCall(evidence.scan_id))
            assert isinstance(destinations, DestinationsResult)
            self.assertEqual(destinations.devices, evidence.snapshot.devices)
            self.assertEqual(destinations.dns_window, evidence.snapshot.dns_window)


class PiHoleConfigurationTest(unittest.TestCase):
    def test_credentials_and_origin_are_explicit_and_private(self) -> None:
        for row in (
            {"origin": "https://pihole.example"},
            {
                "origin": "https://user:password@pihole.example",
                "passwordEnvironment": "TEST_PASSWORD",
            },
            {
                "origin": "https://pihole.example/api",
                "passwordEnvironment": "TEST_PASSWORD",
            },
            {
                "origin": "https://pihole.example",
                "passwordEnvironment": "TEST_PASSWORD",
                "passwordFile": "/tmp/file",
            },
            {
                "origin": "https://pihole.example",
                "passwordEnvironment": "TEST_PASSWORD",
                "queryLimit": 257,
            },
        ):
            with self.assertRaises(ValueError):
                PiHolePlan.decode(row)
        plan = PiHolePlan.decode(
            {"origin": "https://pihole.example", "passwordEnvironment": "TEST_PASSWORD"}
        )
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "application password"):
                PiHoleV6(plan)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder).resolve() / "secret"
            path.write_text(SECRET + "\n")
            path.chmod(0o600)
            plan = PiHolePlan.decode(
                {"origin": "https://pihole.example", "passwordFile": str(path)}
            )
            PiHoleV6(plan)
            if os.name == "posix":
                path.chmod(0o644)
                with self.assertRaisesRegex(ValueError, "private permissions"):
                    PiHoleV6(plan)

    def test_legacy_receipts_keep_exact_encoding_and_scope_fingerprint(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            plan = config.collection
            old = asdict(plan)
            old.pop("enabled")
            old.pop("labels")
            old.pop("pihole")
            old["observations_file"] = str(plan.observations_file)
            self.assertEqual(
                scope_fingerprint(plan),
                hashlib.sha256(json.dumps(old, sort_keys=True).encode()).hexdigest(),
            )
            raw: dict[str, object] = {
                "scan_id": str(uuid4()),
                "source_event": "fixture:event",
                "collector": "fixture",
                "mode": "fixture",
                "scope": "fixture",
                "started_at": utc_now(),
                "finished_at": utc_now(),
                "snapshot": {"observed_at": utc_now(), "tcp": [], "dns": []},
                "issues": [],
                "changes": [],
                "previous_revision": None,
            }
            retained = json.dumps(raw, indent=2, allow_nan=False)
            self.assertEqual(Evidence.decode(raw).encode(), retained)

    def test_labels_are_selected_scope_and_do_not_imply_mac_identity(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder).resolve() / "config.json"
            payload = json.loads((EXAMPLES / "config.json").read_text())
            payload["stateDirectory"] = str(Path(folder).resolve() / "state")
            payload["collection"] = {
                "mode": "tcp",
                "targets": [ADDRESS],
                "ports": [443],
                "timeoutSeconds": 1,
                "concurrency": 1,
                "deviceLabels": {ADDRESS: "Living room TV"},
            }
            config = Configuration.decode(payload, path)
            self.assertEqual(
                config.collection.labels, (DeviceLabel(ADDRESS, "Living room TV"),)
            )
            payload["collection"]["deviceLabels"]["192.0.2.99"] = "Foreign device"
            with self.assertRaises(ValueError):
                Configuration.decode(payload, path)

    def test_unicode_label_metadata_cannot_overflow_native_scope_replies(self) -> None:
        targets = tuple("192.0.2." + str(index) for index in range(1, 33))
        with self.assertRaisesRegex(ValueError, "tool reply budget"):
            device_labels({address: "😀" * 128 for address in targets}, targets)

    def test_mac_changes_do_not_attribute_new_dns_to_the_previous_device(self) -> None:
        observed = utc_now()
        first = DeviceIdentity(
            ADDRESS,
            "mac:" + MAC,
            None,
            "tv",
            MAC,
            None,
            "pihole-v6",
            observed,
            observed,
        )
        second = replace(
            first, mac="00:11:22:33:44:66", device_id="mac:00:11:22:33:44:66"
        )
        snapshot = Snapshot(
            observed, (), (DnsObservation(ADDRESS, "first.example", 1),), (first,)
        )
        previous = Evidence(
            str(uuid4()),
            "fixture:event",
            "fixture",
            "tcp",
            "same-scope",
            observed,
            observed,
            snapshot,
            (),
            (),
            None,
        )
        current = replace(
            snapshot,
            dns=(DnsObservation(ADDRESS, "new.example", 1),),
            devices=(second,),
        )
        delta = changes(previous, current, "same-scope", ())
        self.assertEqual(len(delta), 1)
        self.assertIn("Device association", delta[0])
