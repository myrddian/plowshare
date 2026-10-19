from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock, patch

from plowshare import Client, Delivery, Refusal, Reply, TransportError
from plowshare.contracts import (
    CurrentDocumentDto,
    DocumentConnectionDtoSelectionVariant1Dto,
    DocumentConnectionDtoSelectionVariant3Dto,
    InformationAdmissionDto,
    InformationContentBindingDto,
    InformationContentCapabilitiesDto,
    InformationContentGetRequest,
    InformationContentReviseRequest,
    InformationContentViewDto,
    InformationDocumentsGetRequest,
    InformationDocumentsListRequest,
    InformationDocumentsListResultDto,
    InformationInterchangeReadableRequest,
    InformationReviseRequest,
    InformationTypeContractDto,
    InformationTypesCapabilitiesRequest,
    InformationUploadRequest,
    InformationWindowDto,
    PortableDescriptorDto,
    PortableProvenanceDto,
    PortableReadableDto,
    SchemaDefinitionDto,
    SelectionDtoScopeVariant3Dto,
)

from plowshare_agile.board import Board
from plowshare_agile.cli import execute, parser, run
from plowshare_agile.journal import Intent, Journal
from plowshare_agile.model import (
    Configuration,
    Document,
    EpicFields,
    TicketFields,
    body_text,
    tags,
)
from plowshare_agile.sdk import MutationResult, SdkBoardPort, metadata


def uid(number: int) -> str:
    return f"{number:08x}-1111-4111-8111-111111111111"


def configuration(root: Path) -> Configuration:
    return Configuration(
        "https://fixture.invalid",
        "fixture",
        "AGILE_TEST_BEARER",
        root / "requests",
        uid(1),
        uid(2),
        uid(3),
        uid(4),
    )


def entry(number: int = 10) -> CurrentDocumentDto:
    return CurrentDocumentDto(
        owner="fixture-owner",
        resource=uid(number),
        revision=uid(number + 1000),
        scope=SelectionDtoScopeVariant3Dto(
            kind="project", project="fixture", include_global=False
        ),
        source_name=f"agile-ticket/{uid(number)}.md",
        tags=("sdk",),
        title="Extraction title",
    )


def contract(config: Configuration, kind: str = "ticket") -> InformationTypeContractDto:
    return InformationTypeContractDto(
        definition_hash="a" * 64,
        description="Fixture",
        label=kind,
        ordinal=1.0,
        presentation={},
        profile="plowshare-document-metadata/1",
        schema=SchemaDefinitionDto(type_="object"),
        slug="agile-" + kind,
        type_=config.type_id(kind),
        version=config.version_id(kind),
    )


def content(
    config: Configuration, current: CurrentDocumentDto
) -> InformationContentViewDto:
    return InformationContentViewDto(
        authorised_stages=(),
        processing_policy="explicit_only",
        reused=(),
        revision=current.revision,
        content_type=InformationContentBindingDto(
            contract=contract(config),
            metadata=metadata(TicketFields("Retained title")),
            metadata_hash="b" * 64,
        ),
    )


def original(current: CurrentDocumentDto, source: str) -> PortableReadableDto:
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
    return PortableReadableDto(
        format="plowshare-information-bundle/1",
        markdown=source,
        path="documents/fixture.md",
        representation="original_markdown",
        text_hash=digest,
        sidecar=PortableDescriptorDto(
            content_hash=digest,
            media_type="text/markdown",
            ordinal=1.0,
            path="documents/fixture.md",
            resource=current.resource,
            revision=current.revision,
            source_name=current.source_name,
            tags=current.tags,
            title=current.title,
            provenance=PortableProvenanceDto(
                created_at="2026-10-19T00:00:00Z",
                dependencies=(),
                evidence=(),
                kind="source",
                owner=current.owner,
                source_hash=digest,
            ),
        ),
    )


class FakePort:
    def __init__(self) -> None:
        self.ticket = Document(
            uid(10),
            uid(1010),
            "fixture-owner",
            ("sdk", "epic:" + uid(20)),
            TicketFields("Ticket"),
        )
        self.epic = Document(
            uid(20), uid(1020), "fixture-owner", (), EpicFields("Epic")
        )
        self.calls: list[Intent] = []
        self.error: Exception | None = None

    async def check(self, *, writing: bool = False) -> None:
        return None

    async def list(self, kind: str, tags: tuple[str, ...]) -> tuple[Document, ...]:
        return (self.ticket,) if kind == "ticket" else (self.epic,)

    async def get(
        self,
        kind: str,
        resource: str,
        revision: str | None = None,
        *,
        body: bool = False,
    ) -> Document:
        selected = self.ticket if kind == "ticket" else self.epic
        if (
            resource != selected.resource
            or revision is not None
            and revision != selected.revision
        ):
            raise ValueError("Unavailable fixture document")
        return selected

    async def submit(self, intent: Intent) -> MutationResult:
        self.calls.append(intent)
        if self.error is not None:
            raise self.error
        return MutationResult(
            intent.request_id,
            "OK",
            intent.resource or uid(10),
            uid(2010),
            intent.action,
        )

    async def editable(self, kind: str, resource: str) -> Document:
        return await self.get(kind, resource, body=True)


class ModelTests(unittest.TestCase):
    def test_fields_reject_invalid_values_and_unknown_members(self) -> None:
        for values in (
            {"title": " "},
            {"title": "x", "status": "closed"},
            {"title": "x", "assignee": " "},
            {"title": "x", "priority": "urgent"},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                TicketFields(**values)
        with self.assertRaises(ValueError):
            TicketFields.decode({**metadata(TicketFields("x")), "owner": "other"})

    def test_tags_are_bounded_and_epic_identity_is_explicit(self) -> None:
        self.assertEqual(tags(("sdk", "epic:" + uid(20))), ("epic:" + uid(20), "sdk"))
        for values in (
            ("sdk", "sdk"),
            ("SDK",),
            ("epic:renamable-title",),
            tuple(str(i) for i in range(33)),
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                tags(values)

    def test_body_bounds_count_utf16_units(self) -> None:
        self.assertEqual(body_text("🙂" * 8192), "🙂" * 8192)
        with self.assertRaises(ValueError):
            body_text("🙂" * 8193)

    def test_configuration_rejects_missing_values_and_non_origins(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            for origin in (
                "",
                "https://user:secret@fixture.invalid",
                "https://fixture.invalid/path",
                "file:///tmp/server",
            ):
                with self.subTest(origin=origin), self.assertRaises(ValueError):
                    replace(config, origin=origin)
            with self.assertRaises(ValueError):
                replace(config, ticket_type=config.epic_type)
            path = Path(folder) / "config.toml"
            path.write_text('project = "fixture"\n')
            with self.assertRaises(ValueError):
                Configuration.read(path)

    def test_offline_schema_commands_need_no_configuration(self) -> None:
        for kind in ("ticket", "epic"):
            with redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(run(["schema", kind]), 0)
            value = json.loads(stream.getvalue())
            self.assertEqual(set(value["required"]), set(value["properties"]))

    def test_cli_requires_configuration_without_connecting(self) -> None:
        with patch(
            "plowshare_agile.cli.Client.connect", new_callable=AsyncMock
        ) as connect:
            with redirect_stderr(io.StringIO()):
                self.assertEqual(run(["tickets", "list"]), 2)
            connect.assert_not_awaited()


class JournalTests(unittest.TestCase):
    def test_private_roundtrip_rejects_duplicate_request_and_changed_binding(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            journal = Journal(config, "fixture-bearer")
            intent = Intent(
                "create",
                "ticket",
                uid(30),
                TicketFields("Private body"),
                body="# Ticket\n",
            )
            journal.prepare(intent)
            self.assertEqual(journal.read(uid(30)), (intent, "pending"))
            self.assertEqual(
                (config.state_directory / (uid(30) + ".json")).stat().st_mode & 0o777,
                0o600,
            )
            self.assertEqual(config.state_directory.stat().st_mode & 0o777, 0o700)
            self.assertNotIn(
                "fixture-bearer",
                (config.state_directory / (uid(30) + ".json")).read_text(),
            )
            with self.assertRaises(ValueError):
                journal.prepare(intent)
            for other in (
                Journal(replace(config, project="other"), "fixture-bearer"),
                Journal(config, "different-bearer"),
            ):
                with self.assertRaises(ValueError):
                    other.read(uid(30))
            journal.settle(intent, "confirmed")
            self.assertEqual(journal.read(uid(30))[1], "confirmed")

    def test_invalid_or_oversized_request_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            config = configuration(Path(folder))
            journal = Journal(config, "fixture-bearer")
            intent = Intent(
                "create", "epic", uid(31), EpicFields("Epic"), body="# Outcome"
            )
            journal.prepare(intent)
            path = config.state_directory / (uid(31) + ".json")
            path.write_text('{"version":1,"version":1}')
            with self.assertRaises(ValueError):
                journal.read(uid(31))
            path.write_text("x" * 65537)
            with self.assertRaises(ValueError):
                journal.read(uid(31))


class BoardTests(unittest.IsolatedAsyncioTestCase):
    async def test_unknown_delivery_is_pending_and_only_explicit_recovery_resubmits(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            journal = Journal(configuration(Path(folder)), "fixture-bearer")
            port = FakePort()
            port.error = TransportError(Delivery.UNKNOWN, "fixture timeout")
            board = Board(port, journal)
            with self.assertRaises(TransportError):
                await board.move(uid(10), uid(1010), uid(32), "in-progress")
            self.assertEqual(len(port.calls), 1)
            intent, state = journal.read(uid(32))
            self.assertEqual(state, "pending")
            port.ticket = replace(
                port.ticket, revision=uid(999), fields=TicketFields("Changed")
            )
            port.error = None
            await board.recover(uid(32))
            self.assertEqual(port.calls, [intent, intent])
            self.assertEqual(journal.read(uid(32))[1], "confirmed")

    async def test_denied_write_is_not_retried(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            journal = Journal(configuration(Path(folder)), "fixture-bearer")
            port = FakePort()
            port.error = Refusal("NOT_FOUND", "private diagnostic")
            with self.assertRaises(Refusal):
                await Board(port, journal).move(uid(10), uid(1010), uid(33), "done")
            self.assertEqual(len(port.calls), 1)
            self.assertEqual(journal.read(uid(33))[1], "refused")

    async def test_internal_error_keeps_the_exact_intent_pending(self) -> None:
        for code in ("INTERNAL_ERROR", "ARCHIVE_UNAVAILABLE"):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as folder:
                journal = Journal(configuration(Path(folder)), "fixture-bearer")
                port = FakePort()
                port.error = Refusal(code, "private diagnostic")
                board = Board(port, journal)
                with self.assertRaises(Refusal):
                    await board.move(uid(10), uid(1010), uid(33), "done")
                intent, state = journal.read(uid(33))
                self.assertEqual(state, "pending")
                self.assertEqual(port.calls, [intent])
                port.error = None
                await board.recover(uid(33))
                self.assertEqual(port.calls, [intent, intent])
                self.assertEqual(journal.read(uid(33))[1], "confirmed")

    async def test_membership_updates_preserve_topics_and_topic_updates_preserve_epics(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as folder:
            port = FakePort()
            board = Board(port, Journal(configuration(Path(folder)), "fixture-bearer"))
            await board.set_epics(uid(10), uid(1010), uid(34), ())
            self.assertEqual(port.calls[-1].tags, ("sdk",))
            await board.set_epics(uid(10), uid(1010), uid(35), (uid(20),))
            self.assertEqual(port.calls[-1].tags, ("epic:" + uid(20), "sdk"))
            await board.set_topics(uid(10), uid(1010), uid(36), ("websocket",))
            self.assertEqual(port.calls[-1].tags, ("epic:" + uid(20), "websocket"))

    async def test_unknown_epic_cannot_produce_a_tag_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            port = FakePort()
            board = Board(port, Journal(configuration(Path(folder)), "fixture-bearer"))
            with self.assertRaises(ValueError):
                await board.set_epics(uid(10), uid(1010), uid(37), (uid(888),))
            self.assertEqual(port.calls, [])


class SdkBoundaryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.config = configuration(Path(self.folder.name))
        self.client = AsyncMock(spec=Client)
        self.port = SdkBoardPort(self.client, self.config)

    async def test_current_document_and_bound_metadata_are_separate_typed_reads(
        self,
    ) -> None:
        current = entry()
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant1Dto(
                    kind="readable", document=current
                ),
            ),
            Reply("OK", None, content(self.config, current)),
        ]
        document = await self.port.get("ticket", current.resource)
        self.assertEqual(document.fields.title, "Retained title")
        request = self.client.request.call_args_list[0].args[0]
        self.assertIsInstance(request, InformationDocumentsGetRequest)
        self.assertEqual(request.scope.project, "fixture")
        self.assertFalse(request.scope.include_global)
        self.assertIsInstance(
            self.client.request.call_args_list[1].args[0], InformationContentGetRequest
        )

    async def test_foreign_type_and_unavailable_head_are_refused_without_fallback(
        self,
    ) -> None:
        current = entry()
        wrong = replace(
            content(self.config, current),
            content_type=InformationContentBindingDto(
                contract=contract(self.config, "epic"),
                metadata={"title": "Foreign"},
                metadata_hash="b" * 64,
            ),
        )
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant1Dto(
                    kind="readable", document=current
                ),
            ),
            Reply("OK", None, wrong),
        ]
        with self.assertRaises(ValueError):
            await self.port.get("ticket", current.resource)
        self.client.request.reset_mock()
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant3Dto(kind="unavailable"),
            )
        ]
        with self.assertRaises(ValueError):
            await self.port.get("ticket", current.resource)
        self.assertEqual(self.client.request.await_count, 1)

    async def test_foreign_resource_selection_is_refused(self) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            DocumentConnectionDtoSelectionVariant1Dto(
                kind="readable", document=entry(11)
            ),
        )
        with self.assertRaises(ValueError):
            await self.port.get("ticket", uid(10))
        self.assertEqual(self.client.request.await_count, 1)

    async def test_body_uses_bounded_unicode_windows(self) -> None:
        current = entry()
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant1Dto(
                    kind="readable", document=current
                ),
            ),
            Reply("OK", None, content(self.config, current)),
            Reply(
                "OK",
                None,
                InformationWindowDto(
                    revision=current.revision, start=0.0, end=2.0, total=3.0, text="🙂"
                ),
            ),
            Reply(
                "OK",
                None,
                InformationWindowDto(
                    revision=current.revision, start=2.0, end=3.0, total=3.0, text="x"
                ),
            ),
        ]
        self.assertEqual(
            (await self.port.get("ticket", current.resource, body=True)).body, "🙂x"
        )
        self.assertEqual(self.client.request.call_args_list[-1].args[0].offset, 2)

    async def test_unbounded_body_is_not_partially_returned(self) -> None:
        current = entry()
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                DocumentConnectionDtoSelectionVariant1Dto(
                    kind="readable", document=current
                ),
            ),
            Reply("OK", None, content(self.config, current)),
            Reply(
                "OK",
                None,
                InformationWindowDto(
                    revision=current.revision,
                    start=0.0,
                    end=0.0,
                    total=99999.0,
                    text="",
                ),
            ),
        ]
        with self.assertRaises(ValueError):
            await self.port.get("ticket", current.resource, body=True)

    async def test_full_board_probes_one_extra_entry_to_detect_overflow(self) -> None:
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                InformationDocumentsListResultDto(
                    documents=tuple(entry(i) for i in range(100)),
                    limit=100.0,
                    offset=0.0,
                ),
            ),
            Reply(
                "OK",
                None,
                InformationDocumentsListResultDto(
                    documents=(entry(101),), limit=1.0, offset=100.0
                ),
            ),
        ]
        with self.assertRaises(ValueError):
            await self.port.list("ticket", ("sdk",))
        self.assertEqual(self.client.request.await_count, 2)
        request = self.client.request.call_args_list[-1].args[0]
        self.assertIsInstance(request, InformationDocumentsListRequest)
        self.assertEqual(request.limit, 1)
        self.assertEqual(request.filter.tags, ("sdk",))

    async def test_duplicate_resources_are_not_counted_as_a_complete_board(
        self,
    ) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            InformationDocumentsListResultDto(
                documents=(entry(), entry()), limit=100.0, offset=0.0
            ),
        )
        with self.assertRaises(ValueError):
            await self.port.list("ticket", ())
        self.assertEqual(self.client.request.await_count, 1)

    async def test_disabled_typed_writes_fail_before_any_mutation(self) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            InformationContentCapabilitiesDto(
                capability="information.contentTypes.v1",
                profile="plowshare-document-metadata/1",
                typed_writes=False,
                view="content-types-v1",
            ),
        )
        with self.assertRaises(ValueError):
            await self.port.check(writing=True)
        self.assertIsInstance(
            self.client.request.call_args.args[0], InformationTypesCapabilitiesRequest
        )

    async def test_create_and_move_emit_public_typed_requests(self) -> None:
        self.client.request.side_effect = [
            Reply("OK", None, contract(self.config)),
            Reply(
                "ACCEPTED",
                None,
                InformationAdmissionDto(
                    created=True, resource=uid(10), revision=uid(1010)
                ),
            ),
        ]
        result = await self.port.submit(
            Intent("create", "ticket", uid(40), TicketFields("Ticket"), body="# Ticket")
        )
        self.assertEqual(result.code, "ACCEPTED")
        request = self.client.request.call_args.args[0]
        self.assertIsInstance(request, InformationUploadRequest)
        self.assertEqual(request.name, "agile-ticket/" + uid(40) + ".md")
        self.assertEqual(request.typing.version, self.config.ticket_version)
        self.client.request.side_effect = [
            Reply(
                "OK",
                None,
                InformationAdmissionDto(
                    created=False, resource=uid(10), revision=uid(2010)
                ),
            )
        ]
        await self.port.submit(
            Intent(
                "revise",
                "ticket",
                uid(41),
                TicketFields("Ticket", status="done"),
                uid(10),
                uid(1010),
            )
        )
        request = self.client.request.call_args.args[0]
        self.assertIsInstance(request, InformationContentReviseRequest)
        self.assertEqual(request.expected_revision, uid(1010))
        self.assertEqual(request.typing.metadata["status"], "done")

    async def test_editable_body_uses_original_markdown_and_rejects_extraction(
        self,
    ) -> None:
        current = entry()
        source = "\ufeff## Heading\r\n\r\n**Original** 🙂\r\n"
        portable = original(current, source)
        for result, accepted in (
            (portable, True),
            (replace(portable, representation="extraction"), False),
            (replace(portable, text_hash="0" * 64), False),
            (
                replace(portable, sidecar=replace(portable.sidecar, resource=uid(99))),
                False,
            ),
        ):
            self.client.request.side_effect = [
                Reply(
                    "OK",
                    None,
                    DocumentConnectionDtoSelectionVariant1Dto(
                        kind="readable", document=current
                    ),
                ),
                Reply("OK", None, content(self.config, current)),
                Reply("OK", None, result),
            ]
            if accepted:
                self.assertEqual(
                    (await self.port.editable("ticket", current.resource)).body, source
                )
            else:
                with self.assertRaises(ValueError):
                    await self.port.editable("ticket", current.resource)
            self.assertIsInstance(
                self.client.request.call_args.args[0],
                InformationInterchangeReadableRequest,
            )

    async def test_body_revision_preserves_exact_input_and_expected_head(self) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            InformationAdmissionDto(
                created=False, resource=uid(10), revision=uid(2010)
            ),
        )
        source = "## Notes\r\n\r\n**Edited**\r\n"
        await self.port.submit(
            Intent(
                "body",
                "ticket",
                uid(51),
                TicketFields("Ticket"),
                uid(10),
                uid(1010),
                source,
            )
        )
        request = self.client.request.call_args.args[0]
        self.assertIsInstance(request, InformationReviseRequest)
        self.assertEqual(request.revision, uid(1010))
        self.assertEqual(request.expected_revision, uid(1010))
        self.assertEqual(request.text, source)

    async def test_body_file_failure_precedes_connection(self) -> None:
        config_file = Path(self.folder.name) / "config.toml"
        config_file.write_text(
            "\n".join(
                f'{key} = "{value}"'
                for key, value in {
                    "origin": self.config.origin,
                    "project": self.config.project,
                    "token_environment": self.config.token_environment,
                    "state_directory": "requests",
                    "ticket_type": self.config.ticket_type,
                    "ticket_version": self.config.ticket_version,
                    "epic_type": self.config.epic_type,
                    "epic_version": self.config.epic_version,
                }.items()
            )
        )
        body = Path(self.folder.name) / "empty.md"
        body.write_text(" ")
        args = parser().parse_args(
            [
                "--config",
                str(config_file),
                "ticket",
                "create",
                "--title",
                "Ticket",
                "--body-file",
                str(body),
                "--request-id",
                uid(50),
            ]
        )
        with (
            patch.dict("os.environ", {"AGILE_TEST_BEARER": "fixture-bearer"}),
            patch(
                "plowshare_agile.cli.Client.connect", new_callable=AsyncMock
            ) as connect,
        ):
            with self.assertRaises(ValueError):
                await execute(args)
            connect.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
