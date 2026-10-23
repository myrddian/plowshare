"""Deterministic commands; AI and a running local web service are optional."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from dataclasses import asdict, is_dataclass
from importlib.resources import files
from pathlib import Path

from plowshare import Client, Refusal, TransportError

from .auth import agent_credential, user_credential
from .board import Board
from .journal import Intent, Journal
from .model import (
    KINDS,
    PRIORITIES,
    STATUSES,
    Configuration,
    EpicFields,
    TicketFields,
    body_text,
    identity,
    object_fields,
    read_json,
    tags,
)
from .sdk import SdkBoardPort


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        prog="plowshare-agile",
        description="A small agile board backed by Plowshare documents",
    )
    root.add_argument(
        "--config",
        type=Path,
        help="Private TOML configuration; required for server commands",
    )
    root.add_argument(
        "--is-user",
        action="store_true",
        help="Reuse a saved Plowshare user login through the SDK",
    )
    root.add_argument("--account", help="Select a saved Plowshare user account")
    root.add_argument("--connection", help="Select a named Plowshare connection")
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="Probe public typed-document support")
    serve = commands.add_parser(
        "serve", help="Run the separate single-maintainer web UI"
    )
    serve.add_argument("--bind", required=True)
    serve.add_argument("--port", required=True, type=int)
    serve.add_argument("--public-origin", required=True)
    serve.add_argument("--access-token-environment", required=True)
    schema = commands.add_parser(
        "schema", help="Print a bundled schema without connecting"
    )
    schema.add_argument("kind", choices=("ticket", "epic"))
    requests = commands.add_parser(
        "request", help="Inspect or explicitly recover a retained request"
    )
    request_commands = requests.add_subparsers(dest="action", required=True)
    for verb in ("show", "recover"):
        request_commands.add_parser(verb).add_argument("request_id", type=identity)
    for plural, kind in (("tickets", "ticket"), ("epics", "epic")):
        listing = (
            commands.add_parser(plural)
            .add_subparsers(dest="action", required=True)
            .add_parser("list")
        )
        listing.set_defaults(kind=kind)
        listing.add_argument("--tag", action="append", default=[])
        listing.add_argument("--epic", action="append", default=[], type=identity)
        listing.add_argument("--status", choices=STATUSES)
        listing.add_argument("--assignee")
        listing.add_argument("--iteration")
    for kind in ("ticket", "epic"):
        actions = commands.add_parser(kind).add_subparsers(dest="action", required=True)
        show = actions.add_parser("show")
        show.set_defaults(kind=kind)
        show.add_argument("resource", type=identity)
        show.add_argument(
            "--body",
            action="store_true",
            help="Read retained extracted body; readiness is required",
        )
        create = actions.add_parser("create")
        create.set_defaults(kind=kind)
        create.add_argument("--title", required=True)
        create.add_argument("--body-file", required=True, type=Path)
        create.add_argument("--request-id", required=True, type=identity)
        if kind == "ticket":
            create.add_argument(
                "--kind", choices=KINDS, default="feature", dest="ticket_kind"
            )
            create.add_argument("--priority", choices=PRIORITIES, default="normal")
            create.add_argument("--assignee")
            create.add_argument("--iteration")
            for verb in ("move", "update", "set-epics", "set-tags"):
                change = actions.add_parser(verb)
                change.add_argument("resource", type=identity)
                change.add_argument("--expected-revision", required=True, type=identity)
                change.add_argument("--request-id", required=True, type=identity)
                if verb in ("set-epics", "set-tags"):
                    change.add_argument(
                        "--expected-tags-version", required=True, type=int
                    )
                if verb == "move":
                    change.add_argument("status", choices=STATUSES)
                elif verb == "update":
                    change.add_argument(
                        "--fields-file",
                        required=True,
                        type=Path,
                        help="Complete ticket metadata object",
                    )
                    change.add_argument(
                        "--body-file",
                        type=Path,
                        help="Also replace body bytes through typed revision admission",
                    )
                elif verb == "set-epics":
                    change.add_argument(
                        "--epic",
                        action="append",
                        default=[],
                        type=identity,
                        help="Repeat for multiple epics; omission clears membership",
                    )
                else:
                    change.add_argument(
                        "--tag",
                        action="append",
                        default=[],
                        help="Replace topic tags, preserving epics; omission clears topics",
                    )
    return root


def output(value: object) -> None:
    if is_dataclass(value) and not isinstance(value, type):
        value = asdict(value)
    print(json.dumps(value, ensure_ascii=False, allow_nan=False))


async def execute(args: argparse.Namespace) -> None:
    if args.command == "schema":
        print(
            files("plowshare_agile")
            .joinpath("schemas", args.kind + ".json")
            .read_text(encoding="utf-8"),
            end="",
        )
        return
    if args.config is None:
        raise ValueError("Server commands require --config")
    config = Configuration.read(args.config)
    if not args.is_user and (args.account is not None or args.connection is not None):
        raise ValueError("--account and --connection require --is-user")
    if args.is_user and args.command == "serve":
        raise ValueError(
            "serve requires agent mode; per-user web login is not implemented"
        )
    # Parse local creation/update inputs before opening any server connection.
    create_intent: Intent | None = None
    update_fields: TicketFields | None = None
    update_body: str | None = None
    if args.command in {"ticket", "epic"} and args.action == "create":
        if args.body_file.stat().st_size > 65536:
            raise ValueError("Body file exceeds 64 KiB")
        body = body_text(args.body_file.read_text(encoding="utf-8"))
        fields = (
            TicketFields(
                args.title,
                args.ticket_kind,
                priority=args.priority,
                assignee=args.assignee,
                iteration=args.iteration,
            )
            if args.kind == "ticket"
            else EpicFields(args.title)
        )
        create_intent = Intent("create", args.kind, args.request_id, fields, body=body)
    if args.command == "ticket" and args.action == "update":
        update_fields = TicketFields.decode(
            object_fields(
                read_json(args.fields_file),
                {"title", "kind", "status", "priority", "assignee", "iteration"},
            )
        )
    if args.command == "epics" and any(
        value is not None for value in (args.status, args.assignee, args.iteration)
    ):
        raise ValueError("Ticket-only metadata filters cannot select epics")
    if (
        args.command == "ticket"
        and args.action == "update"
        and args.body_file is not None
    ):
        if args.body_file.stat().st_size > 65536:
            raise ValueError("Body file exceeds 64 KiB")
        update_body = body_text(args.body_file.read_text(encoding="utf-8"))
    credential = (
        await user_credential(
            config,
            account=args.account,
            connection=args.connection,
        )
        if args.is_user
        else agent_credential(config)
    )
    token = credential.token
    journal = Journal(config, token, user_account=credential.user_account)
    if args.command == "request" and args.action == "show":
        intent, outcome = journal.read(args.request_id)
        output(
            {
                "request_id": intent.request_id,
                "action": intent.action,
                "outcome": outcome,
                "source": "local-request-journal",
            }
        )
        return
    if args.command == "serve":
        try:
            from .web import WebOptions, serve
        except ModuleNotFoundError as error:
            if error.name == "aiohttp":
                raise ValueError(
                    "Install plowshare-agile[web] to use the web UI"
                ) from None
            raise
        access_key = os.environ.get(args.access_token_environment, "")
        if access_key == token:
            raise ValueError("Use a separate web access key, not the Plowshare bearer")
        web_options = WebOptions(args.bind, args.port, args.public_origin, access_key)
    async with await Client.connect(config.origin, token) as client:
        port = SdkBoardPort(client, config)
        writing = (
            args.command in {"ticket", "epic"}
            and args.action != "show"
            or args.command == "request"
            and args.action == "recover"
        )
        # Read operations enforce their own availability and current authority.
        # Only explicit probes and writes need a typed-write readiness request;
        # serve's watchdog owns its startup and ongoing readiness checks.
        if writing or args.command == "check":
            await port.check(writing=writing)
        board = Board(port, journal)
        if args.command == "check":
            output(
                {"ready": True, "project": config.project, "transport": "public-sdk"}
            )
        elif args.command == "serve":
            await serve(board, config, web_options)
        elif args.command == "request":
            output(await board.recover(args.request_id))
        elif args.command in {"tickets", "epics"}:
            selected_tags = tags(
                tuple(args.tag) + tuple("epic:" + v for v in args.epic)
            )
            documents = await port.list(args.kind, selected_tags)
            selected = []
            for document in documents:
                fields = document.fields
                if isinstance(fields, TicketFields):
                    if (
                        args.status is not None
                        and fields.status != args.status
                        or args.assignee is not None
                        and fields.assignee != args.assignee
                        or args.iteration is not None
                        and fields.iteration != args.iteration
                    ):
                        continue
                elif any(
                    v is not None for v in (args.status, args.assignee, args.iteration)
                ):
                    raise ValueError("Ticket-only metadata filters cannot select epics")
                selected.append(asdict(document))
            output({"documents": selected, "count": len(selected), "complete": True})
        elif args.action == "show":
            output(await port.get(args.kind, args.resource, body=args.body))
        elif create_intent is not None:
            output(await board.submit(create_intent))
        elif args.action == "move":
            output(
                await board.move(
                    args.resource, args.expected_revision, args.request_id, args.status
                )
            )
        elif args.action == "update" and update_fields is not None:
            output(
                await board.update(
                    "ticket",
                    args.resource,
                    args.expected_revision,
                    args.request_id,
                    update_fields,
                    update_body,
                )
            )
        elif args.action == "set-epics":
            output(
                await board.set_epics(
                    args.resource,
                    args.expected_revision,
                    args.request_id,
                    tuple(args.epic),
                    args.expected_tags_version,
                )
            )
        elif args.action == "set-tags":
            output(
                await board.set_topics(
                    args.resource,
                    args.expected_revision,
                    args.request_id,
                    tuple(args.tag),
                    args.expected_tags_version,
                )
            )
        else:
            raise ValueError("Unsupported command")


def run(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        asyncio.run(execute(args))
        return 0
    except Refusal as error:
        print(
            json.dumps({"error": "server-refusal", "code": error.code}), file=sys.stderr
        )
        return 3
    except TransportError as error:
        print(
            json.dumps(
                {
                    "error": "transport",
                    "delivery": error.delivery.value,
                    "action": "Inspect the retained request; recover explicitly. No write was retried.",
                }
            ),
            file=sys.stderr,
        )
        return 4
    except (ValueError, OSError) as error:
        message = (
            str(error)
            if isinstance(error, ValueError)
            else "Unable to access a configured file"
        )
        print(
            json.dumps({"error": "invalid-input-or-response", "message": message}),
            file=sys.stderr,
        )
        return 2
    except KeyboardInterrupt:
        return 130


def main() -> None:
    raise SystemExit(run())
