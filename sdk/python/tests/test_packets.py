"""Transport ownership tests: no domain work occurs before verified assembly."""

import base64
import hashlib
import json
import unittest
from uuid import uuid4

from plowshare._packets import CHUNK, MAX_MESSAGE, Packets


def parts(data: bytes) -> list[str]:
    identity = str(uuid4())
    return [
        json.dumps(
            {
                "kind": "transport.segment",
                "version": 1,
                "transferId": identity,
                "segmentNumber": index + 1,
                "segmentCount": (len(data) + CHUNK - 1) // CHUNK,
                "byteOffset": index * CHUNK,
                "totalBytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "data": base64.b64encode(
                    data[index * CHUNK : (index + 1) * CHUNK]
                ).decode("ascii"),
            }
        )
        for index in range((len(data) + CHUNK - 1) // CHUNK)
    ]


class PacketTests(unittest.IsolatedAsyncioTestCase):
    async def test_reordered_split_unicode_dispatches_once(self) -> None:
        transport = Packets(lambda: None)
        text = "x" * (CHUNK - 1) + "😀" + "y"
        wire = parts(text.encode("utf-8"))
        try:
            self.assertIsNone(transport.accept(wire[1])[0])
            self.assertIsNone(transport.accept(wire[1])[0])
            self.assertEqual(text, transport.accept(wire[0])[0])
            with self.assertRaises(ValueError):
                transport.accept(wire[0])
        finally:
            transport.close()

    async def test_invalid_hash_unicode_and_integer_tokens_are_refused(self) -> None:
        for wire in [
            parts(b"\xff")[0],
            parts(b"x")[0].replace('"version": 1', '"version": true'),
            parts(b"x")[0].replace('"version": 1', '"version": 1.0'),
        ]:
            transport = Packets(lambda: None)
            try:
                with self.assertRaises(ValueError):
                    transport.accept(wire)
            finally:
                transport.close()

    async def test_global_reservations_include_waiting_request_buffers(self) -> None:
        first, second, refused = (
            Packets(lambda: None),
            Packets(lambda: None),
            Packets(lambda: None),
        )
        first.reserve_outgoing(MAX_MESSAGE)
        second.reserve_outgoing(MAX_MESSAGE)
        try:
            with self.assertRaises(ValueError):
                refused.reserve_outgoing(MAX_MESSAGE)
        finally:
            first.release_outgoing(MAX_MESSAGE)
            second.release_outgoing(MAX_MESSAGE)
            first.close()
            second.close()
            refused.close()
        next_connection = Packets(lambda: None)
        next_connection.reserve_outgoing(MAX_MESSAGE)
        next_connection.release_outgoing(MAX_MESSAGE)
        next_connection.close()
