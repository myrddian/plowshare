"""Separate single-maintainer UI server, using the CLI's Board and public SDK port.

The browser receives a short-lived app session, never the Plowshare bearer.
This HTTP boundary belongs to this SDK consumer, not to Plowshare core.
"""

from __future__ import annotations

import asyncio
import json
import secrets
import time
from dataclasses import asdict, dataclass, field
from importlib.resources import files
from typing import Awaitable, Callable
from urllib.parse import urlsplit

from aiohttp import web
from plowshare import Refusal, TransportError

from .board import Board, RevisionConflict
from .journal import Intent
from .model import (
    RECORD_KINDS,
    Configuration,
    EpicFields,
    TicketFields,
    body_text,
    choice,
    identity,
    object_fields,
    text,
)
from .sdk import MutationResult


@dataclass(frozen=True)
class WebOptions:
    bind: str
    port: int
    origin: str
    access_key: str = field(repr=False)

    def __post_init__(self) -> None:
        text(self.bind, "bind address", 253)
        if any(c.isspace() for c in self.bind) or not 1 <= self.port <= 65535:
            raise ValueError("Configure a bind address and port between 1 and 65535")
        url = urlsplit(self.origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "Configure the web UI's exact public HTTP(S) origin without a path"
            )
        url.port
        if (
            len(self.access_key) < 32
            or len(self.access_key) > 256
            or self.access_key != self.access_key.strip()
        ):
            raise ValueError(
                "The separate web access key must contain 32–256 characters"
            )


@dataclass(frozen=True)
class Session:
    csrf: str
    expires: float


def _json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


async def _payload(request: web.Request, names: set[str]) -> dict[str, object]:
    if request.content_type != "application/json":
        raise web.HTTPUnsupportedMediaType()
    source = await request.text()
    value: object = json.loads(source, object_pairs_hook=_json_object)
    return object_fields(value, names)


def _fields(kind: str, value: object) -> TicketFields | EpicFields:
    names = (
        {"title", "kind", "status", "priority", "assignee", "iteration"}
        if kind == "ticket"
        else {"title"}
    )
    row = object_fields(value, names)
    return TicketFields.decode(row) if kind == "ticket" else EpicFields.decode(row)


def _strings(value: object) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) > 32:
        raise ValueError("Expected a bounded selection")
    return tuple(text(item, "selection", 64) for item in value)


class WebUI:
    def __init__(self, board: Board, project: str, options: WebOptions):
        self.board, self.project, self.options = board, project, options
        self.sessions: dict[str, Session] = {}
        self.mutations = asyncio.Lock()

    def _session(self, request: web.Request) -> Session:
        key = request.cookies.get("agile_session", "")
        session = self.sessions.get(key)
        if session is None or session.expires < time.monotonic():
            self.sessions.pop(key, None)
            raise web.HTTPUnauthorized()
        return session

    def _pending(self, request: web.Request) -> bool:
        key = request.headers.get("X-Agile-Request")
        if key is None:
            return False
        try:
            return self.board.journal.read(identity(key))[1] == "pending"
        except FileNotFoundError:
            return False
        except (ValueError, OSError):
            # An unusable receipt cannot prove that a write did not happen.
            return True

    def application(self) -> web.Application:
        @web.middleware
        async def boundary(
            request: web.Request,
            handler: Callable[[web.Request], Awaitable[web.StreamResponse]],
        ) -> web.StreamResponse:
            try:
                if request.host.lower() != urlsplit(self.options.origin).netloc.lower():
                    raise web.HTTPForbidden()
                modifying = request.method not in {"GET", "HEAD"}
                if modifying and request.headers.get("Origin") != self.options.origin:
                    raise web.HTTPForbidden()
                if (
                    request.path.startswith("/api/")
                    or request.path == "/session"
                    and request.method != "POST"
                ):
                    session = self._session(request)
                    if modifying and not secrets.compare_digest(
                        request.headers.get("X-Agile-CSRF", "").encode(),
                        session.csrf.encode(),
                    ):
                        raise web.HTTPForbidden()
                response = await handler(request)
            except RevisionConflict:
                response = web.json_response(
                    {
                        "error": "revision-conflict",
                        "code": "CONFLICT",
                        "pending": False,
                    },
                    status=409,
                )
            except Refusal as error:
                response = web.json_response(
                    {
                        "error": "server-refusal",
                        "code": error.code,
                        "pending": self._pending(request),
                    },
                    status=(
                        503
                        if self._pending(request) or error.code == "INTERNAL_ERROR"
                        else 409
                        if error.code == "CONFLICT"
                        else 403
                    ),
                )
            except TransportError as error:
                response = web.json_response(
                    {
                        "error": "transport",
                        "delivery": error.delivery.value,
                        "pending": self._pending(request),
                    },
                    status=503,
                )
            except ValueError as error:
                response = web.json_response(
                    {
                        "error": "invalid-input-or-response",
                        "message": str(error),
                        "pending": self._pending(request),
                    },
                    status=400,
                )
            except TimeoutError:
                response = web.json_response(
                    {"error": "upstream-timeout", "pending": self._pending(request)},
                    status=503,
                )
            except OSError:
                response = web.json_response(
                    {"error": "local-request-state-unavailable", "pending": True},
                    status=503,
                )
            except web.HTTPException as error:
                response = web.json_response(
                    {"error": error.reason, "pending": False}, status=error.status
                )
            response.headers.update(
                {
                    "Cache-Control": "no-store",
                    "X-Content-Type-Options": "nosniff",
                    "Referrer-Policy": "no-referrer",
                    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
                }
            )
            return response

        app = web.Application(middlewares=[boundary], client_max_size=65536)
        app.add_routes(
            [
                web.get("/", self.asset),
                web.get("/health", self.health),
                web.get("/app.mjs", self.asset),
                web.get("/model.mjs", self.asset),
                web.get("/reads.mjs", self.asset),
                web.get("/styles.css", self.asset),
                web.get("/markdown.js", self.asset),
                web.post("/session", self.login),
                web.get("/session", self.session),
                web.delete("/session", self.logout),
                web.get("/api/board", self.snapshot),
                web.get("/api/documents/{kind}/{resource}", self.document),
                web.post("/api/documents", self.create),
                web.post("/api/documents/{kind}/{resource}", self.update),
                web.post("/api/tickets/{resource}/epics", self.epics),
                web.post("/api/tickets/{resource}/tags", self.topics),
                web.post("/api/requests/{request_id}/recover", self.recover),
            ]
        )
        return app

    async def health(self, request: web.Request) -> web.Response:
        # A minimal readiness result; never exposes project data or diagnostics.
        async with asyncio.timeout(10):
            await self.board.port.check(writing=True)
        return web.Response(status=204)

    async def asset(self, request: web.Request) -> web.Response:
        name = "index.html" if request.path == "/" else request.path[1:]
        types = {
            "index.html": "text/html",
            "styles.css": "text/css",
            "app.mjs": "application/javascript",
            "model.mjs": "application/javascript",
            "reads.mjs": "application/javascript",
            "markdown.js": "application/javascript",
        }
        return web.Response(
            body=files("plowshare_agile").joinpath("web", name).read_bytes(),
            content_type=types[name],
        )

    async def login(self, request: web.Request) -> web.Response:
        row = await _payload(request, {"accessKey"})
        key = text(row["accessKey"], "access key", 256)
        if not secrets.compare_digest(key.encode(), self.options.access_key.encode()):
            raise web.HTTPUnauthorized()
        now = time.monotonic()
        self.sessions = {
            key: value for key, value in self.sessions.items() if value.expires > now
        }
        if len(self.sessions) >= 8:
            raise web.HTTPTooManyRequests()
        session_key = secrets.token_urlsafe(32)
        session = Session(secrets.token_urlsafe(32), now + 8 * 3600)
        self.sessions[session_key] = session
        response = web.json_response({"csrf": session.csrf, "project": self.project})
        response.set_cookie(
            "agile_session",
            session_key,
            httponly=True,
            secure=self.options.origin.startswith("https:"),
            samesite="Strict",
            max_age=8 * 3600,
        )
        return response

    async def session(self, request: web.Request) -> web.Response:
        return web.json_response(
            {"csrf": self._session(request).csrf, "project": self.project}
        )

    async def logout(self, request: web.Request) -> web.Response:
        self.sessions.pop(request.cookies.get("agile_session", ""), None)
        response = web.json_response({"signedOut": True})
        response.del_cookie("agile_session")
        return response

    async def snapshot(self, request: web.Request) -> web.Response:
        tickets = await self.board.port.list("ticket", ())
        epics = await self.board.port.list("epic", ())
        return web.json_response(
            {
                "project": self.project,
                "tickets": [asdict(v) for v in tickets],
                "epics": [asdict(v) for v in epics],
            }
        )

    async def document(self, request: web.Request) -> web.Response:
        kind = choice(request.match_info["kind"], RECORD_KINDS, "document kind")
        if set(request.query) - {"body"} or request.query.get("body", "0") not in {
            "0",
            "1",
        }:
            raise ValueError("Unsupported document read")
        resource = identity(request.match_info["resource"])
        document = (
            await self.board.port.editable(kind, resource)
            if request.query.get("body") == "1"
            else await self.board.port.get(kind, resource)
        )
        return web.json_response(asdict(document))

    def _request_id(self, request: web.Request, row: dict[str, object]) -> str:
        key = identity(row["requestId"])
        if request.headers.get("X-Agile-Request") != key:
            raise ValueError("Request correlation differs")
        return key

    async def _mutate(
        self, action: Callable[[], Awaitable[MutationResult]]
    ) -> web.Response:
        # Serialize this process's operator actions, including tag snapshots.
        # Direct CLI writers remain subject to the existing single-maintainer limit.
        async with self.mutations:
            await self.board.port.check(writing=True)
            return web.json_response(asdict(await action()))

    async def create(self, request: web.Request) -> web.Response:
        row = await _payload(request, {"requestId", "kind", "fields", "body"})
        key = self._request_id(request, row)
        kind = choice(row["kind"], RECORD_KINDS, "document kind")
        if not isinstance(row["body"], str):
            raise ValueError("Expected Markdown body")
        intent = Intent(
            "create",
            kind,
            key,
            _fields(kind, row["fields"]),
            body=body_text(row["body"]),
        )
        return await self._mutate(lambda: self.board.submit(intent))

    async def update(self, request: web.Request) -> web.Response:
        row = await _payload(
            request, {"requestId", "expectedRevision", "fields", "body"}
        )
        key = self._request_id(request, row)
        kind = choice(request.match_info["kind"], RECORD_KINDS, "document kind")
        resource = identity(request.match_info["resource"])
        expected = identity(row["expectedRevision"])
        fields = _fields(kind, row["fields"])
        body = row["body"]
        if body is not None and not isinstance(body, str):
            raise ValueError("Expected Markdown body or null")
        if body is not None:
            body_text(body)
        return await self._mutate(
            lambda: self.board.update(kind, resource, expected, key, fields, body)
        )

    async def epics(self, request: web.Request) -> web.Response:
        row = await _payload(request, {"requestId", "expectedRevision", "epics"})
        key = self._request_id(request, row)
        resource = identity(request.match_info["resource"])
        expected = identity(row["expectedRevision"])
        selected = tuple(identity(value) for value in _strings(row["epics"]))
        return await self._mutate(
            lambda: self.board.set_epics(resource, expected, key, selected)
        )

    async def topics(self, request: web.Request) -> web.Response:
        row = await _payload(request, {"requestId", "expectedRevision", "tags"})
        key = self._request_id(request, row)
        resource = identity(request.match_info["resource"])
        expected = identity(row["expectedRevision"])
        selected = _strings(row["tags"])
        return await self._mutate(
            lambda: self.board.set_topics(resource, expected, key, selected)
        )

    async def recover(self, request: web.Request) -> web.Response:
        row = await _payload(request, {"requestId"})
        key = self._request_id(request, row)
        if identity(request.match_info["request_id"]) != key:
            raise ValueError("Recovery identity differs")
        return await self._mutate(lambda: self.board.recover(key))


async def serve(board: Board, config: Configuration, options: WebOptions) -> None:
    runner = web.AppRunner(
        WebUI(board, config.project, options).application(), access_log=None
    )
    await runner.setup()
    try:
        await web.TCPSite(runner, options.bind, options.port).start()
        print(f"Excalibur: {options.origin}", flush=True)
        await watch_upstream(board)
    finally:
        await runner.cleanup()


async def watch_upstream(board: Board) -> None:
    """Exit on lost upstream authority/transport so a supervisor can restart.

    Only readiness reads repeat. An interrupted mutation remains in the private
    journal for explicit recovery; reconnecting never resubmits user writes.
    """
    while True:
        async with asyncio.timeout(10):
            await board.port.check(writing=True)
        await asyncio.sleep(30)
