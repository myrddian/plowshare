"""Local UI fixture with disposable sample records; never connects to Plowshare."""

from __future__ import annotations

import argparse
import asyncio
import tempfile
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from plowshare import Refusal
from test_agile import configuration, uid

from plowshare_agile.board import Board
from plowshare_agile.journal import Intent, Journal
from plowshare_agile.model import Document, EpicFields, TicketFields
from plowshare_agile.sdk import MutationResult
from plowshare_agile.web import WebOptions, serve

ACCESS_KEY = "fixture-web-access-key-at-least-32-characters"


class PreviewPort:
    def __init__(self) -> None:
        self.documents: dict[tuple[str, str], Document] = {}
        self.receipts: dict[str, tuple[Intent, MutationResult]] = {}
        for number, title in (
            (20, "SDK usability"),
            (21, "Operational reliability"),
            (22, "A calmer workspace"),
        ):
            self.documents[("epic", uid(number))] = Document(
                uid(number),
                uid(number + 1000),
                "maintainer",
                (),
                EpicFields(title),
                f"## Intended outcome\n\n{title} makes everyday work easier to follow.\n\n## Scope\n\nConnect related tickets without giving each one a single parent.",
            )
        rows = (
            (
                10,
                "Explain reconnect failures",
                "bug",
                "ready",
                "high",
                "Alex",
                "Next",
                (20, 21),
                ("sdk", "websocket"),
            ),
            (
                11,
                "Add a quick-start example",
                "feature",
                "backlog",
                "normal",
                None,
                "Next",
                (20,),
                ("sdk", "docs"),
            ),
            (
                12,
                "Keep pending work visible after reconnect",
                "feature",
                "in-progress",
                "high",
                "Alex",
                "Next",
                (20, 21),
                ("sdk", "websocket"),
            ),
            (
                13,
                "Simplify the empty state",
                "chore",
                "backlog",
                "low",
                "Sam",
                None,
                (22,),
                ("interface",),
            ),
            (
                14,
                "Show the retained result clearly",
                "feature",
                "done",
                "normal",
                "Sam",
                "Next",
                (21, 22),
                ("interface",),
            ),
            (
                15,
                "Document explicit retry behavior",
                "chore",
                "ready",
                "normal",
                "Alex",
                None,
                (21,),
                ("docs",),
            ),
        )
        for (
            number,
            title,
            kind,
            status,
            priority,
            assignee,
            iteration,
            epics,
            topics,
        ) in rows:
            self.documents[("ticket", uid(number))] = Document(
                uid(number),
                uid(number + 1000),
                "maintainer",
                tuple("epic:" + uid(epic) for epic in epics) + topics,
                TicketFields(title, kind, status, priority, assignee, iteration),
                "## Description\n\nMake the next step clear when a connection is interrupted.\n\n## Acceptance criteria\n\n- [ ] The outcome is visible.\n- [ ] Existing work is retained.\n\n## Notes\n\nA **sample ticket** for local interface verification. No live project data.",
            )

    async def check(self, *, writing: bool = False) -> None:
        return None

    async def list(self, kind: str, tags: tuple[str, ...]) -> tuple[Document, ...]:
        return tuple(
            replace(doc, body=None)
            for (record_kind, _), doc in self.documents.items()
            if record_kind == kind and all(tag in doc.tags for tag in tags)
        )

    async def get(
        self,
        kind: str,
        resource: str,
        revision: str | None = None,
        *,
        body: bool = False,
    ) -> Document:
        document = self.documents.get((kind, resource))
        if document is None:
            raise Refusal("NOT_FOUND", None)
        if revision is not None and revision != document.revision:
            raise Refusal("CONFLICT", None)
        return document if body else replace(document, body=None)

    async def validate_epics(self, resources: tuple[str, ...]) -> None:
        for resource in resources:
            await self.get("epic", resource)

    async def editable(self, kind: str, resource: str) -> Document:
        return await self.get(kind, resource, body=True)

    async def submit(self, intent: Intent) -> MutationResult:
        previous = self.receipts.get(intent.request_id)
        if previous is not None:
            if previous[0] != intent:
                raise Refusal("CONFLICT", None)
            return previous[1]
        resource = intent.resource or str(uuid4())
        if intent.action == "create":
            document = Document(
                resource, str(uuid4()), "maintainer", (), intent.fields, intent.body
            )
        else:
            current = await self.get(
                intent.kind, resource, intent.expected_revision, body=True
            )
            document = (
                replace(current, tags=intent.tags)
                if intent.action == "tags"
                else replace(
                    current,
                    revision=str(uuid4()),
                    fields=intent.fields,
                    body=current.body if intent.body is None else intent.body,
                )
            )
        self.documents[(intent.kind, resource)] = document
        operation = {
            "create": "information.upload",
            "revise": "information.content.revise",
            "body": "information.revise",
            "tags": "information.tags",
        }[intent.action]
        result = MutationResult(
            intent.request_id, "OK", resource, document.revision, operation
        )
        self.receipts[intent.request_id] = (intent, result)
        return result


async def main(port: int) -> None:
    with tempfile.TemporaryDirectory() as folder:
        config = replace(
            configuration(Path(folder)), project="Agile UI fixture · sample data"
        )
        board = Board(PreviewPort(), Journal(config, "fixture-sdk-bearer"))
        await serve(
            board,
            config,
            WebOptions("127.0.0.1", port, f"http://127.0.0.1:{port}", ACCESS_KEY),
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    asyncio.run(main(parser.parse_args().port))
