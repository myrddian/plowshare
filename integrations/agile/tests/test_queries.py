"""SDK request budgets and reuse fences; these do not measure database latency."""

from __future__ import annotations

import asyncio
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from plowshare import Client, Refusal, Reply
from plowshare.contracts import (
    DocumentConnectionDtoSelectionVariant1Dto,
    InformationAdmissionDto,
    InformationContentBindingDto,
    InformationContentCapabilitiesDto,
    InformationContentGetRequest,
    InformationContentReviseRequest,
    InformationDocumentsGetRequest,
    InformationDocumentsListRequest,
    InformationDocumentsListResultDto,
    InformationFilterDto,
    InformationTypesCapabilitiesRequest,
    SelectionDtoScopeVariant3Dto,
)
from test_agile import FakePort, configuration, content, contract, entry, uid

from plowshare_agile.board import Board, RevisionConflict
from plowshare_agile.cli import execute, parser
from plowshare_agile.journal import Journal
from plowshare_agile.model import Document, EpicFields, TicketFields
from plowshare_agile.sdk import SdkBoardPort, metadata


class QueryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.config = configuration(Path(self.folder.name))
        self.client = AsyncMock(spec=Client)
        self.port = SdkBoardPort(self.client, self.config)
        self.tickets = [entry(10), entry(11)]
        self.epics = [entry(200), entry(201)]
        self.requests: list[object] = []
        self.fail_fields = False
        self.client.request.side_effect = self.request

    async def request(self, request: object) -> Reply[object]:
        self.requests.append(request)
        if isinstance(request, InformationTypesCapabilitiesRequest):
            return Reply(
                "OK",
                None,
                InformationContentCapabilitiesDto(
                    capability="information.contentTypes.v1",
                    profile="plowshare-document-metadata/1",
                    typed_writes=True,
                    view="content-types-v1",
                ),
            )
        if isinstance(request, InformationContentReviseRequest):
            current = self.tickets[0]
            self.assertEqual(request.expected_revision, current.revision)
            self.tickets[0] = replace(current, revision=uid(999))
            return Reply(
                "OK",
                None,
                InformationAdmissionDto(
                    resource=current.resource,
                    revision=uid(999),
                    created=False,
                ),
            )
        if isinstance(request, InformationDocumentsListRequest):
            assert isinstance(request.filter, InformationFilterDto)
            assert isinstance(request.offset, (int, float))
            assert isinstance(request.limit, (int, float))
            rows = (
                self.tickets
                if request.filter.content_type == self.config.ticket_type
                else self.epics
            )
            return Reply(
                "OK",
                None,
                InformationDocumentsListResultDto(
                    documents=tuple(
                        rows[int(request.offset) : int(request.offset + request.limit)]
                    ),
                    offset=float(request.offset),
                    limit=float(request.limit),
                ),
            )
        if isinstance(request, InformationDocumentsGetRequest):
            current = next(
                v for v in self.tickets if v.resource == request.target.resource
            )
            return Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant1Dto(
                    kind="readable", document=current
                ),
            )
        if isinstance(request, InformationContentGetRequest):
            if self.fail_fields:
                raise Refusal("NOT_FOUND", None)
            current = next(
                v for v in self.tickets + self.epics if v.revision == request.revision
            )
            kind = "ticket" if current in self.tickets else "epic"
            fields = (
                TicketFields("Typed " + current.revision)
                if kind == "ticket"
                else EpicFields("Epic")
            )
            return Reply(
                "OK",
                None,
                replace(
                    content(self.config, current),
                    content_type=InformationContentBindingDto(
                        contract=contract(self.config, kind),
                        metadata=metadata(fields),
                        metadata_hash="b" * 64,
                    ),
                ),
            )
        raise AssertionError(f"Unexpected SDK request: {type(request).__name__}")

    def field_calls(self) -> int:
        return sum(isinstance(v, InformationContentGetRequest) for v in self.requests)

    async def test_one_edit_does_not_churn_a_full_board_cache(self) -> None:
        self.tickets = [entry(i + 10) for i in range(100)]
        self.epics = [entry(i + 200) for i in range(20)]
        for kind in ("ticket", "epic"):
            await self.port.list(kind, ())
        # A targeted post-save read admits a new revision in the middle of the
        # next scan. Keeping its old revision would evict a different row and
        # cause a chain of misses on this capacity-sized board.
        self.tickets[50] = replace(self.tickets[50], revision=uid(999))
        await self.port.get("ticket", self.tickets[50].resource)
        self.requests.clear()
        for kind in ("ticket", "epic"):
            await self.port.list(kind, ())
        self.assertEqual(len(self.requests), 3)
        self.assertEqual(self.field_calls(), 0)
        self.assertEqual(len(self.port._fields), 120)
        self.assertEqual(len(self.port._field_keys), 120)

    async def test_cli_reads_do_not_add_unused_capability_probes(self) -> None:
        for command, calls in (
            (["tickets", "list"], 3),
            (["ticket", "show", uid(10)], 2),
        ):
            self.requests.clear()
            args = parser().parse_args(["--config", "fixture.toml", *command])
            with (
                patch(
                    "plowshare_agile.cli.Configuration.read", return_value=self.config
                ),
                patch.dict("os.environ", {"AGILE_TEST_BEARER": "fixture-bearer"}),
                patch("plowshare_agile.cli.Client.connect", new_callable=AsyncMock),
                patch("plowshare_agile.cli.SdkBoardPort", return_value=self.port),
                redirect_stdout(io.StringIO()),
            ):
                await execute(args)
            self.assertEqual(len(self.requests), calls)

    async def test_cold_and_unchanged_board_request_budgets(self) -> None:
        for tickets, epics, cold, warm in ((50, 10, 62, 2), (100, 20, 123, 3)):
            with self.subTest(tickets=tickets, epics=epics):
                self.port = SdkBoardPort(self.client, self.config)
                self.tickets = [entry(i + 10) for i in range(tickets)]
                self.epics = [entry(i + 200) for i in range(epics)]
                self.requests.clear()
                for kind in ("ticket", "epic"):
                    await self.port.list(kind, ())
                self.assertEqual(len(self.requests), cold)
                self.assertEqual(self.field_calls(), tickets + epics)
                self.requests.clear()
                for kind in ("ticket", "epic"):
                    await self.port.list(kind, ())
                self.assertEqual(len(self.requests), warm)
                self.assertEqual(self.field_calls(), 0)

    async def test_metadata_save_and_targeted_fresh_read_take_six_sdk_calls(
        self,
    ) -> None:
        board = Board(self.port, Journal(self.config, "fixture-bearer"))
        # Same server-side sequence as one BFF save followed by the browser's
        # narrow refresh. The receipt contains no fields, so the final read is
        # deliberate current-state evidence rather than replaying submitted data.
        await self.port.check(writing=True)
        result = await board.update(
            "ticket", uid(10), uid(1010), uid(45), TicketFields("Submitted")
        )
        document = await self.port.get("ticket", result.resource)
        self.assertEqual(document.revision, uid(999))
        self.assertNotEqual(document.fields.title, "Submitted")
        self.assertEqual(
            [type(v) for v in self.requests],
            [
                InformationTypesCapabilitiesRequest,
                InformationDocumentsGetRequest,
                InformationContentGetRequest,
                InformationContentReviseRequest,
                InformationDocumentsGetRequest,
                InformationContentGetRequest,
            ],
        )

    async def test_list_reuses_only_exact_revisions_with_fresh_tags_and_authority(
        self,
    ) -> None:
        previous = await self.port.list("ticket", ())
        self.tickets[0] = replace(
            self.tickets[0], tags=("changed",), owner="current-owner"
        )
        self.tickets[1] = replace(self.tickets[1], revision=uid(999))
        self.requests.clear()
        current = await self.port.list("ticket", ())
        self.assertEqual(current[0].fields, previous[0].fields)
        self.assertEqual(current[0].tags, ("changed",))
        self.assertEqual(current[0].owner, "current-owner")
        self.assertNotEqual(current[1].fields, previous[1].fields)
        self.assertEqual(self.field_calls(), 1)
        self.tickets = []  # An unreadable/redacted/unselected row is absent from the fresh filtered page.
        self.requests.clear()
        self.assertEqual(await self.port.list("ticket", ()), ())
        self.assertEqual(len(self.requests), 1)

    async def test_cached_fields_do_not_bypass_scope_or_direct_get_binding_checks(
        self,
    ) -> None:
        await self.port.list("ticket", ())
        self.tickets[0] = replace(
            self.tickets[0],
            scope=SelectionDtoScopeVariant3Dto(
                kind="project",
                project="another",
                include_global=False,
            ),
        )
        self.requests.clear()
        with self.assertRaisesRegex(ValueError, "Foreign document scope"):
            await self.port.list("ticket", ())
        self.assertEqual(len(self.requests), 1)
        self.tickets[0] = entry()
        self.fail_fields = True  # A cached revision cannot hide a now-refused binding.
        self.requests.clear()
        with self.assertRaises(Refusal):
            await self.port.get("ticket", uid(10))
        self.assertEqual(len(self.requests), 2)

    async def test_failed_field_load_is_not_cached_or_automatically_retried(
        self,
    ) -> None:
        self.fail_fields = True
        with self.assertRaises(Refusal):
            await self.port.list("ticket", ())
        self.assertEqual(self.field_calls(), 1)
        self.fail_fields = False
        self.requests.clear()
        self.assertEqual(len(await self.port.list("ticket", ())), 2)
        self.assertEqual(self.field_calls(), 2)

    async def test_concurrent_loads_share_fields_but_each_gets_authorized_pages(
        self,
    ) -> None:
        self.tickets = [entry()]
        started, release = asyncio.Event(), asyncio.Event()

        async def request(request: object) -> Reply[object]:
            if isinstance(request, InformationContentGetRequest):
                started.set()
                await release.wait()
            return await self.request(request)

        self.client.request.side_effect = request
        first = asyncio.create_task(self.port.list("ticket", ()))
        await started.wait()
        second = asyncio.create_task(self.port.list("ticket", ()))
        # Wait for the second fresh page, not an arbitrary timing delay.
        while len(self.requests) < 2:
            await asyncio.sleep(0)
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        release.set()
        self.assertEqual(len(await second), 1)
        self.assertEqual(self.field_calls(), 1)
        self.assertEqual(len(self.requests), 3)

    async def test_lru_entry_and_byte_limits_and_connection_isolation(self) -> None:
        with patch.object(self.port, "FIELD_ENTRIES", 2):
            await self.port.list("ticket", ())
            self.tickets.reverse()
            self.tickets.append(entry(12))
            await self.port.list("ticket", ())  # 11, 10, 12 -> evicts 11.
            self.tickets = [entry(10)]
            self.requests.clear()
            await self.port.list("ticket", ())
            self.assertEqual(self.field_calls(), 0)
            self.tickets = [entry(11)]
            await self.port.list("ticket", ())
            self.assertEqual(self.field_calls(), 1)
            self.assertLessEqual(len(self.port._fields), 2)
        other = SdkBoardPort(self.client, self.config)
        self.requests.clear()
        await other.list("ticket", ())
        self.assertEqual(self.field_calls(), 1)
        self.port = SdkBoardPort(self.client, self.config)
        with patch.object(self.port, "FIELD_BYTES", 5000):
            self.tickets = [entry(10), entry(11)]
            await self.port.list("ticket", ())
            self.assertLessEqual(self.port._field_bytes, 5000)
            self.assertLessEqual(len(self.port._fields), 1)
        self.port = SdkBoardPort(self.client, self.config)
        with patch.object(self.port, "FIELD_BYTES", 1):
            await self.port.list("ticket", ())
            self.assertEqual(len(self.port._fields), 0)

    async def test_inflight_admission_is_bounded(self) -> None:
        self.tickets = [entry()]
        started, release = asyncio.Event(), asyncio.Event()

        async def request(request: object) -> Reply[object]:
            if isinstance(request, InformationContentGetRequest):
                started.set()
                await release.wait()
            return await self.request(request)

        self.client.request.side_effect = request
        with patch.object(self.port, "FIELD_LOADS", 1):
            first = asyncio.create_task(self.port.list("ticket", ()))
            await started.wait()
            self.tickets = [entry(11)]
            try:
                with self.assertRaisesRegex(ValueError, "Too many metadata reads"):
                    await self.port.list("ticket", ())
                self.assertEqual(len(self.port._field_loads), 1)
            finally:
                self.tickets = [entry()]
                release.set()
                await first
        self.assertEqual(self.field_calls(), 1)

    async def test_epic_validation_batches_heads_and_hydrates_only_selected_fields(
        self,
    ) -> None:
        await self.port.validate_epics((uid(200),))
        self.assertEqual(len(self.requests), 2)
        self.assertEqual(self.field_calls(), 1)
        self.requests.clear()
        await self.port.validate_epics((uid(200), uid(201)))
        self.assertEqual(
            len(self.requests), 2
        )  # Fresh page plus the newly selected field binding.
        self.requests.clear()
        with self.assertRaisesRegex(ValueError, "unavailable"):
            await self.port.validate_epics((uid(999),))
        self.assertEqual(len(self.requests), 1)
        self.requests.clear()
        await self.port.validate_epics(())
        self.assertEqual(self.requests, [])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            await self.port.validate_epics((uid(200), uid(200)))
        self.assertEqual(self.requests, [])

    async def test_tag_actions_read_ticket_once_after_epic_validation_and_reject_stale_heads(
        self,
    ) -> None:
        port = FakePort()
        board = Board(port, Journal(self.config, "fixture-bearer"))
        order: list[str] = []

        async def validate(resources: tuple[str, ...]) -> None:
            order.append("epics")

        original_get = port.get

        async def get(
            kind: str, resource: str, revision: str | None = None, *, body: bool = False
        ) -> Document:
            order.append(kind)
            return await original_get(kind, resource, revision, body=body)

        with (
            patch.object(port, "get", side_effect=get),
            patch.object(port, "validate_epics", side_effect=validate),
        ):
            await board.set_epics(uid(10), uid(1010), uid(40), (uid(20),))
            self.assertEqual(order, ["epics", "ticket"])
            order.clear()
            await board.set_topics(uid(10), uid(1010), uid(41), ("topic",))
            self.assertEqual(order, ["ticket"])
            with self.assertRaises(RevisionConflict):
                await board.set_topics(uid(10), uid(999), uid(42), ())
            self.assertFalse(
                (self.config.state_directory / (uid(42) + ".json")).exists()
            )
            self.assertEqual(len(port.calls), 2)
