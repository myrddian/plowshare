from __future__ import annotations

import asyncio
import hashlib
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from plowshare import Delivery, TransportError, publish_relay_blob, read_relay_blob
from plowshare.contracts import (
    RelayBlobDescriptorDto,
    RelayBlobPublishDto,
    RelayBlobReadDto,
)

UUID = "11111111-1111-1111-1111-111111111111"
DATA = b"\x00binary\xff"
REQUEST = RelayBlobPublishDto(
    request_id=UUID,
    project="project",
    topic="event",
    occurred_at="2026-11-13T00:00:00Z",
    media_type="application/octet-stream",
)
READ = RelayBlobReadDto(
    project="project", topic="event", group="workers", event_id=UUID
)
DESCRIPTOR = RelayBlobDescriptorDto(
    sha256=hashlib.sha256(DATA).hexdigest(),
    byte_length=len(DATA),
    media_type="application/octet-stream",
)


class BinaryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.calls = 0
        self.kind = "valid"
        self.started = threading.Event()
        self.release = threading.Event()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: object) -> None:
                pass

            def do_POST(self) -> None:
                owner.calls += 1
                body = self.rfile.read(int(self.headers["Content-Length"]))
                owner.assertIn(DATA, body)
                owner.assertIn(UUID.encode(), body)
                owner.assertEqual("Bearer fixture", self.headers["Authorization"])
                owner.started.set()
                if owner.kind == "wait":
                    owner.release.wait(2)
                    return
                if owner.kind == "redirect":
                    self.send_response(307)
                    self.send_header("Location", "/leak")
                    self.end_headers()
                    return
                receipt = {
                    "publication": {
                        "requestId": UUID,
                        "project": "other" if owner.kind == "foreign" else "project",
                        "topic": "event",
                        "position": "9007199254740993",
                        "publishedAt": "2026-11-13T00:00:00Z",
                    },
                    "blob": {
                        "sha256": DESCRIPTOR.sha256,
                        "byteLength": len(DATA),
                        "mediaType": "application/octet-stream",
                    },
                    "future": True,
                }
                raw = (
                    b"x" * 16385
                    if owner.kind == "overflow"
                    else json.dumps(receipt).encode()
                )
                self.send_response(200)
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self) -> None:
                owner.calls += 1
                owner.assertIn("eventId=" + UUID, self.path)
                self.send_response(403 if owner.kind == "refused" else 200)
                self.send_header(
                    "Content-Type",
                    "text/plain"
                    if owner.kind == "headers"
                    else "application/octet-stream",
                )
                self.end_headers()
                raw = {
                    "short": DATA[:2],
                    "overflow": DATA + b"x",
                    "hash": b"x" * len(DATA),
                }.get(owner.kind, DATA)
                self.wfile.write(raw)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    async def asyncTearDown(self) -> None:
        self.release.set()
        await asyncio.to_thread(self.server.shutdown)
        self.server.server_close()
        self.thread.join()

    async def test_round_trip_and_large_decimal_position(self) -> None:
        receipt = await publish_relay_blob(self.origin, "fixture", REQUEST, DATA)
        self.assertEqual("9007199254740993", receipt.publication.position)
        self.assertEqual(
            DATA, await read_relay_blob(self.origin, "fixture", READ, receipt.blob)
        )
        self.assertEqual(2, self.calls)

    async def test_foreign_receipt_redirect_and_overflow_are_never_replayed(
        self,
    ) -> None:
        for kind in ("foreign", "redirect", "overflow"):
            self.kind = kind
            previous = self.calls
            with self.assertRaises(TransportError):
                await publish_relay_blob(self.origin, "fixture", REQUEST, DATA)
            self.assertEqual(previous + 1, self.calls)

    async def test_download_refusal_and_integrity(self) -> None:
        for kind in ("short", "overflow", "hash", "headers", "refused"):
            self.kind = kind
            with self.assertRaises(TransportError):
                await read_relay_blob(self.origin, "fixture", READ, DESCRIPTOR)
        self.assertEqual(5, self.calls)

    async def test_validation_precedes_submission(self) -> None:
        for origin in ("", "http://user:secret@example.test", self.origin + "/path"):
            with self.assertRaises(ValueError):
                await publish_relay_blob(origin, "fixture", REQUEST, DATA)
        with self.assertRaises(ValueError):
            await publish_relay_blob(self.origin, "fixture\r\nleak", REQUEST, DATA)
        with self.assertRaises(ValueError):
            await publish_relay_blob(self.origin, "fixture", REQUEST, b"")
        with self.assertRaises(ValueError):
            await read_relay_blob(
                self.origin,
                "fixture",
                READ,
                RelayBlobDescriptorDto(
                    sha256=DESCRIPTOR.sha256, byte_length=1.5, media_type="text/plain"
                ),
            )
        self.assertEqual(0, self.calls)

    async def test_cancelled_submitted_upload_retains_unknown_delivery(self) -> None:
        self.kind = "wait"
        task = asyncio.create_task(
            publish_relay_blob(self.origin, "fixture", REQUEST, DATA)
        )
        self.assertTrue(await asyncio.to_thread(self.started.wait, 2))
        task.cancel()
        from plowshare import CancelledRequest

        with self.assertRaises(CancelledRequest) as failure:
            await task
        self.release.set()
        self.assertEqual(Delivery.UNKNOWN, failure.exception.delivery)
        self.assertEqual(1, self.calls)
