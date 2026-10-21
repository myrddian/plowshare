"""SDK request budgets and reuse fences; these do not measure database latency."""

from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from plowshare import Client, Refusal, Reply
from plowshare.contracts import (
    CurrentDocumentDto,
    DocumentConnectionDtoSelectionVariant1Dto,
    InformationAdmissionDto,
    InformationContentBindingDto,
    InformationContentCapabilitiesDto,
    InformationContentGetRequest,
    InformationContentMetadataDto,
    InformationContentReviseRequest,
    InformationDocumentsGetRequest,
    InformationDocumentsListRequest,
    InformationDocumentsListResultDto,
    InformationFilterDto,
    InformationTypeIdentityDto,
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
                        self.projected(
                            row, "ticket" if rows is self.tickets else "epic"
                        )
                        for row in rows[
                            int(request.offset) : int(request.offset + request.limit)
                        ]
                    ),
                    projection="metadata-v1",
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

    def projected(
        self, current: CurrentDocumentDto, kind: str = "ticket"
    ) -> CurrentDocumentDto:
        definition = contract(self.config, kind)
        fields = (
            TicketFields("Typed " + current.revision)
            if kind == "ticket"
            else EpicFields("Epic")
        )
        return replace(
            current,
            content_metadata=InformationContentMetadataDto(
                type_=InformationTypeIdentityDto(
                    type_=definition.type_,
                    version=definition.version,
                    ordinal=definition.ordinal,
                    slug=definition.slug,
                    label=definition.label,
                    definition_hash=definition.definition_hash,
                ),
                metadata_hash="b" * 64,
                metadata=metadata(fields),
            ),
        )

    async def test_cold_and_repeated_board_reads_use_only_projected_pages(self) -> None:
        for tickets, epics, calls in ((50, 10, 2), (100, 20, 3)):
            with self.subTest(tickets=tickets, epics=epics):
                self.tickets = [entry(i + 10) for i in range(tickets)]
                self.epics = [entry(i + 200) for i in range(epics)]
                for attempt in range(2):
                    self.requests.clear()
                    for kind in ("ticket", "epic"):
                        await self.port.list(kind, ())
                    self.assertEqual(len(self.requests), calls)
                    self.assertEqual(self.field_calls(), 0)
                    for request in self.requests:
                        self.assertIsInstance(request, InformationDocumentsListRequest)
                        if isinstance(request, InformationDocumentsListRequest):
                            self.assertEqual(request.projection, "metadata-v1")

    async def test_repeated_reads_always_decode_current_fields_tags_and_owners(
        self,
    ) -> None:
        previous = await self.port.list("ticket", ())
        self.tickets[0] = replace(
            self.tickets[0], tags=("changed",), owner="current-owner", revision=uid(999)
        )
        self.requests.clear()
        current = await self.port.list("ticket", ())
        self.assertNotEqual(current[0].fields, previous[0].fields)
        self.assertEqual(current[0].tags, ("changed",))
        self.assertEqual(current[0].owner, "current-owner")
        self.assertEqual(len(self.requests), 1)
        self.tickets = []
        self.assertEqual(await self.port.list("ticket", ()), ())
        self.assertEqual(self.field_calls(), 0)

    async def test_missing_projection_marker_or_typed_binding_fails_without_fallback(
        self,
    ) -> None:
        self.client.request.side_effect = None
        for page in (
            InformationDocumentsListResultDto(documents=(), limit=100, offset=0),
            InformationDocumentsListResultDto(
                documents=(entry(),), limit=100, offset=0, projection="metadata-v1"
            ),
            InformationDocumentsListResultDto(
                documents=(self.projected(entry(), "epic"),),
                limit=100,
                offset=0,
                projection="metadata-v1",
            ),
        ):
            self.client.request.reset_mock()
            self.client.request.return_value = Reply("OK", None, page)
            with self.assertRaises(ValueError):
                await self.port.list("ticket", ())
            self.assertEqual(self.client.request.await_count, 1)

    async def test_foreign_scope_and_refused_direct_binding_cannot_reuse_prior_reads(
        self,
    ) -> None:
        await self.port.list("ticket", ())
        self.tickets[0] = replace(
            self.tickets[0],
            scope=SelectionDtoScopeVariant3Dto(
                kind="project", project="another", include_global=False
            ),
        )
        self.requests.clear()
        with self.assertRaisesRegex(ValueError, "Foreign document scope"):
            await self.port.list("ticket", ())
        self.assertEqual(len(self.requests), 1)
        self.tickets[0] = entry()
        self.fail_fields = True
        self.requests.clear()
        with self.assertRaises(Refusal):
            await self.port.get("ticket", uid(10))
        self.assertEqual(len(self.requests), 2)

    async def test_epic_validation_uses_one_complete_projected_page(self) -> None:
        await self.port.validate_epics((uid(200), uid(201)))
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(self.field_calls(), 0)
        self.requests.clear()
        with self.assertRaisesRegex(ValueError, "unavailable"):
            await self.port.validate_epics((uid(999),))
        self.assertEqual(len(self.requests), 1)
        self.requests.clear()
        await self.port.validate_epics(())
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            await self.port.validate_epics((uid(200), uid(200)))
        self.assertEqual(self.requests, [])

    async def test_unsupported_server_projection_is_not_replayed_as_item_reads(
        self,
    ) -> None:
        self.client.request.side_effect = Refusal(
            "BAD_REQUEST", "unsupported projection"
        )
        with self.assertRaises(Refusal):
            await self.port.list("ticket", ())
        self.assertEqual(self.client.request.await_count, 1)
        self.assertIsInstance(
            self.client.request.call_args.args[0], InformationDocumentsListRequest
        )

    async def test_cli_reads_do_not_add_unused_capability_probes(self) -> None:
        for command, calls in (
            (["tickets", "list"], 1),
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
            await board.set_epics(uid(10), uid(1010), uid(40), (uid(20),), 0)
            self.assertEqual(order, ["epics", "ticket"])
            order.clear()
            await board.set_topics(uid(10), uid(1010), uid(41), ("topic",), 0)
            self.assertEqual(order, ["ticket"])
            with self.assertRaises(RevisionConflict):
                await board.set_topics(uid(10), uid(999), uid(42), ())
            self.assertFalse(
                (self.config.state_directory / (uid(42) + ".json")).exists()
            )
            self.assertEqual(len(port.calls), 2)
