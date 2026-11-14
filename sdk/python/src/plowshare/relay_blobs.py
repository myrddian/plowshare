"""Explicit binary HTTP transfers; WebSocket events carry descriptors only.

Each call submits once. Retain the original request ID and bytes for reconciliation.
Cancellation stops waiting, not durable server work; the bounded worker closes its
connection when it finishes. There is no redirect, credential renewal or replay.
"""

from __future__ import annotations

import asyncio
import hashlib
import http.client
import math
import re
import time
import uuid
from urllib.parse import urlencode, urlsplit

from ._codec import blob_metadata, blob_receipt, http_contract
from .auth import canonical_origin
from .client import CancelledRequest, Delivery, TransportError
from .contracts import (
    RelayBlobDescriptorDto,
    RelayBlobPublishDto,
    RelayBlobPublishedDto,
    RelayBlobReadDto,
)

MAX_RELAY_BLOB_BYTES = 50 * 1024 * 1024


def _connection(origin: str, bearer: str, timeout: float) -> http.client.HTTPConnection:
    parsed = urlsplit(canonical_origin(origin))
    if not re.fullmatch(r"[\x21-\x7e]{1,8192}", bearer):
        raise ValueError("Binary transfer requires a valid caller bearer")
    if not math.isfinite(timeout) or timeout <= 0 or timeout > 300:
        raise ValueError(
            "Binary transfer timeout must be 0..300 seconds, exclusive of zero"
        )
    cls = (
        http.client.HTTPSConnection
        if parsed.scheme == "https"
        else http.client.HTTPConnection
    )
    return cls(parsed.hostname or "", parsed.port, timeout=timeout)


def _transfer(
    connection: http.client.HTTPConnection,
    bearer: str,
    method: str,
    path: str,
    body: bytes | None,
    content_type: str | None,
    limit: int,
    expected: RelayBlobDescriptorDto | None,
) -> bytes:
    if connection.timeout is None:
        raise ValueError("Binary transfer requires a timeout")
    deadline = time.monotonic() + connection.timeout
    headers = {"Authorization": "Bearer " + bearer, "Accept-Encoding": "identity"}
    if content_type is not None:
        headers["Content-Type"] = content_type
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        if not 200 <= response.status < 300:
            raise TransportError(
                Delivery.UNKNOWN,
                f"Binary HTTP transfer refused ({response.status}); reconcile original request",
            )
        if expected is not None:
            for name, value in (
                ("Content-Type", "application/octet-stream"),
                ("Content-Length", str(int(expected.byte_length))),
                ("X-Plowshare-Blob-Sha256", expected.sha256),
                ("X-Plowshare-Blob-Media-Type", expected.media_type),
            ):
                actual = response.getheader(name)
                if (actual is None and name == "Content-Type") or (
                    actual is not None and actual != value
                ):
                    raise TransportError(
                        Delivery.INVALID_RESPONSE,
                        "Binary headers differ from descriptor",
                    )
        chunks: list[bytes] = []
        size = 0
        while True:
            if time.monotonic() >= deadline:
                raise TransportError(Delivery.UNKNOWN, "Binary response timed out")
            chunk = response.read1(min(65536, limit + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > limit:
                raise TransportError(
                    Delivery.INVALID_RESPONSE, "Binary response exceeds its bound"
                )
        return b"".join(chunks)
    except (OSError, http.client.HTTPException) as error:
        raise TransportError(
            Delivery.UNKNOWN, "Binary HTTP transfer failed; delivery may be unknown"
        ) from error
    finally:
        connection.close()


async def publish_relay_blob(
    origin: str,
    bearer: str,
    request: RelayBlobPublishDto,
    data: bytes,
    *,
    timeout: float = 60,
) -> RelayBlobPublishedDto:
    """Publish immutable bytes once and verify the scoped receipt and digest."""
    metadata = blob_metadata(request)
    if not isinstance(data, bytes) or not 1 <= len(data) <= MAX_RELAY_BLOB_BYTES:
        raise ValueError("Binary upload requires 1..50 MiB of bytes")
    connection = _connection(origin, bearer, timeout)
    boundary = "plowshare-" + uuid.uuid4().hex
    # Names are generated fields; metadata is in part bodies, never interpolated into headers.
    parts = [
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'
        ).encode()
        for key, value in metadata.items()
    ]
    parts += [
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="blob.bin"\r\nContent-Type: application/octet-stream\r\n\r\n'
        ).encode(),
        data,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    body = b"".join(parts)
    try:
        raw = await asyncio.wait_for(
            asyncio.to_thread(
                _transfer,
                connection,
                bearer,
                "POST",
                "/v1/relay/blobs",
                body,
                "multipart/form-data; boundary=" + boundary,
                16384,
                None,
            ),
            timeout,
        )
    except TimeoutError as error:
        raise TransportError(
            Delivery.UNKNOWN, "Binary publication timed out; reconcile original request"
        ) from error
    except asyncio.CancelledError as error:
        raise CancelledRequest(
            "Binary publication cancelled; reconcile original request"
        ) from error
    try:
        receipt = blob_receipt(raw)
    except (ValueError, UnicodeError) as error:
        raise TransportError(
            Delivery.INVALID_RESPONSE, "Invalid binary publication receipt"
        ) from error
    if (
        receipt.publication.request_id != request.request_id
        or receipt.publication.project != request.project
        or receipt.publication.topic != request.topic
        or receipt.blob.media_type != request.media_type
        or receipt.blob.byte_length != len(data)
        or receipt.blob.sha256 != hashlib.sha256(data).hexdigest()
    ):
        raise TransportError(
            Delivery.INVALID_RESPONSE, "Foreign binary publication receipt"
        )
    return receipt


async def read_relay_blob(
    origin: str,
    bearer: str,
    request: RelayBlobReadDto,
    expected: RelayBlobDescriptorDto,
    *,
    timeout: float = 60,
) -> bytes:
    """Read under current authority and verify size and SHA-256 before returning bytes."""
    metadata = blob_metadata(request)
    http_contract("RelayBlobDescriptor", expected)
    connection = _connection(origin, bearer, timeout)
    try:
        data = await asyncio.wait_for(
            asyncio.to_thread(
                _transfer,
                connection,
                bearer,
                "GET",
                "/v1/relay/blobs?" + urlencode(metadata),
                None,
                None,
                int(expected.byte_length),
                expected,
            ),
            timeout,
        )
    except TimeoutError as error:
        raise TransportError(Delivery.UNKNOWN, "Binary download timed out") from error
    except asyncio.CancelledError as error:
        raise CancelledRequest(
            "Binary download cancelled; delivery may be unknown"
        ) from error
    if (
        len(data) != expected.byte_length
        or hashlib.sha256(data).hexdigest() != expected.sha256
    ):
        raise TransportError(
            Delivery.INVALID_RESPONSE, "Binary download failed integrity verification"
        )
    return data
