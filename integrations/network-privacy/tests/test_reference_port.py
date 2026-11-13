"""The reference port sends scoped, idempotent SDK requests and rejects foreign replies."""

from __future__ import annotations

import unittest
from dataclasses import dataclass
from typing import Any, cast

from plowshare import Client
from plowshare.contracts import (
    ChangedInformationDto,
    InformationAcquireRequest,
    InformationAcquisitionDto,
    InformationAcquisitionsRequest,
    InformationRetryRequest,
    InformationRevisionDto,
    InformationStatusRequest,
    InformationTagsReplaceRequest,
    QueuedInformationDto,
    SelectionDtoScopeVariant3Dto,
)

from plowshare_privacy.reference_port import MAX_PAGES, SdkReferencePort

SCOPE = SelectionDtoScopeVariant3Dto(
    kind="project", project="project-1", include_global=False
)
URL = "https://example.test/device.json"


@dataclass
class Reply:
    payload: Any

    def require_payload(self) -> Any:
        return self.payload


class FakeClient:
    """Records requests and replies from a queue of canned payloads."""

    def __init__(self, *payloads: Any) -> None:
        self.payloads = list(payloads)
        self.requests: list[Any] = []

    async def request(self, request: Any) -> Reply:
        self.requests.append(request)
        return Reply(self.payloads.pop(0))


def port(client: FakeClient) -> SdkReferencePort:
    return SdkReferencePort(cast(Client, client), SCOPE)


def acquisition(identifier: str = "a1", url: str = URL) -> InformationAcquisitionDto:
    return InformationAcquisitionDto(id=identifier, state="queued", url=url)


class ReferencePortTest(unittest.IsolatedAsyncioTestCase):
    async def test_acquire_sends_the_scoped_request(self) -> None:
        client = FakeClient(acquisition())
        result = await port(client).acquire(URL, "ref", "req-1")
        self.assertEqual(result.id, "a1")
        self.assertEqual(
            client.requests,
            [
                InformationAcquireRequest(
                    scope=SCOPE, url=URL, name="ref", request_id="req-1"
                )
            ],
        )

    async def test_foreign_acquisition_is_rejected(self) -> None:
        client = FakeClient(acquisition(url="https://other.test/x"))
        with self.assertRaisesRegex(ValueError, "Foreign acquisition"):
            await port(client).acquire(URL, "ref", "req-1")

    async def test_acquisitions_follow_pages_until_a_short_page(self) -> None:
        first = tuple(acquisition(f"a{n}") for n in range(100))
        second = (acquisition("tail"),)
        client = FakeClient(first, second)
        result = await port(client).acquisitions()
        self.assertEqual(len(result), 101)
        self.assertEqual(result[-1].id, "tail")
        self.assertEqual(
            client.requests,
            [
                InformationAcquisitionsRequest(scope=SCOPE, offset=0, limit=100),
                InformationAcquisitionsRequest(scope=SCOPE, offset=100, limit=100),
            ],
        )

    async def test_acquisitions_stop_at_the_page_cap(self) -> None:
        full = tuple(acquisition(f"a{n}") for n in range(100))
        client = FakeClient(*([full] * (MAX_PAGES + 1)))
        with self.assertRaisesRegex(ValueError, "Too many acquisitions"):
            await port(client).acquisitions()
        self.assertEqual(len(client.requests), MAX_PAGES)

    async def test_acquisitions_reject_an_oversized_page(self) -> None:
        oversized = tuple(acquisition(f"a{n}") for n in range(101))
        with self.assertRaisesRegex(ValueError, "longer than requested"):
            await port(FakeClient(oversized)).acquisitions()

    async def test_revision_reads_status_with_tags_version(self) -> None:
        revision = InformationRevisionDto(
            id="r1", kind="source", tags=("a",), tags_version=3
        )
        client = FakeClient(revision)
        result = await port(client).revision("r1")
        self.assertEqual(result.tags_version, 3)
        self.assertEqual(
            client.requests, [InformationStatusRequest(scope=SCOPE, revision="r1")]
        )

    async def test_foreign_revision_is_rejected(self) -> None:
        for reply in (
            InformationRevisionDto(id="other", kind="source", tags_version=1),
            acquisition("r1"),
        ):
            with self.assertRaisesRegex(ValueError, "Foreign revision"):
                await port(FakeClient(reply)).revision("r1")

    async def test_replace_tags_sends_expected_version(self) -> None:
        client = FakeClient(ChangedInformationDto(changed=True))
        await port(client).replace_tags("r1", ("x", "y"), 4, "req-2")
        self.assertEqual(
            client.requests,
            [
                InformationTagsReplaceRequest(
                    scope=SCOPE,
                    revision="r1",
                    tags=("x", "y"),
                    expected_tags_version=4,
                    request_id="req-2",
                )
            ],
        )

    async def test_retry_targets_the_acquisition(self) -> None:
        client = FakeClient(QueuedInformationDto(queued=True))
        await port(client).retry("a1", "req-3")
        self.assertEqual(
            client.requests,
            [
                InformationRetryRequest(
                    scope=SCOPE, acquisition="a1", request_id="req-3"
                )
            ],
        )


if __name__ == "__main__":
    unittest.main()
