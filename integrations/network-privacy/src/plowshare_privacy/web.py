"""The external application's HTTP boundary. Plowshare communication stays on its SDK."""

from __future__ import annotations

import hmac
from dataclasses import asdict
from importlib.resources import files
from typing import Awaitable, Callable

from aiohttp import web
from plowshare import Refusal, TransportError

from .contracts import object_fields, parse_json, uuid
from .journal import Publication
from .tools import DEFINITIONS, PrivacyTools, WorkerTools, decode_call
from .worker import Worker


def application(
    worker: Worker, token: str, tools: PrivacyTools | None = None
) -> web.Application:
    """Serve static UI and bearer-protected APIs without exposing deployment credentials.

    Browser credentials stay in memory. Mutations require JSON plus a bearer;
    cross-origin access is unavailable. Static assets contain no household data.
    Use TLS at the deployment proxy when serving beyond the collector machine.
    """
    if len(token) < 24 or any(ord(character) < 33 for character in token):
        raise ValueError(
            "Web bearer must contain at least 24 non-whitespace characters"
        )

    @web.middleware
    async def boundary(
        request: web.Request,
        handler: Callable[[web.Request], Awaitable[web.StreamResponse]],
    ) -> web.StreamResponse:
        if request.path.startswith("/api/"):
            expected = "Bearer " + token
            if not hmac.compare_digest(
                request.headers.get("Authorization", "").encode(), expected.encode()
            ):
                raise web.HTTPUnauthorized(text="Dashboard authentication required")
            if request.method == "POST" and request.content_type != "application/json":
                raise web.HTTPUnsupportedMediaType(text="JSON is required")
        try:
            response = await handler(request)
        except ValueError:
            response = web.json_response(
                {"error": "Invalid request or unavailable evidence"}, status=400
            )
        except (TransportError, Refusal):
            response = web.json_response(
                {
                    "error": "Plowshare request did not settle; inspect its retained identity before trying again"
                },
                status=503,
            )
        response.headers.update(
            {
                "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff",
                "Referrer-Policy": "no-referrer",
                "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",
            }
        )
        return response

    app = web.Application(middlewares=[boundary], client_max_size=4096)
    provider: PrivacyTools = tools if tools is not None else WorkerTools(worker)

    async def tool_catalog(request: web.Request) -> web.Response:
        # Schemas describe this HTTP boundary; raw dictionaries do not enter the provider.
        result: list[dict[str, object]] = []
        for definition in DEFINITIONS:
            properties: dict[str, object] = {}
            if definition.parameter:
                properties[definition.parameter] = {"type": "string", "format": "uuid"}
            result.append(
                {
                    "name": definition.name,
                    "description": definition.description,
                    "effect": definition.effect,
                    "input_schema": {
                        "type": "object",
                        "properties": properties,
                        "required": [definition.parameter]
                        if definition.parameter
                        else [],
                        "additionalProperties": False,
                    },
                }
            )
        return web.json_response(result)

    async def call_tool(request: web.Request) -> web.Response:
        call = decode_call(request.match_info["name"], parse_json(await request.read()))
        result = await provider.execute(call)
        status = 200
        if isinstance(result, Publication):
            status = 202 if result.state == "published" else 409
        return web.json_response(asdict(result), status=status)

    async def asset(request: web.Request) -> web.Response:
        name = {"/": "index.html", "/app.js": "app.js", "/style.css": "style.css"}[
            request.path
        ]
        media = {
            "index.html": "text/html",
            "app.js": "text/javascript",
            "style.css": "text/css",
        }[name]
        return web.Response(
            body=files("plowshare_privacy").joinpath("web", name).read_bytes(),
            content_type=media,
        )

    async def status(request: web.Request) -> web.Response:
        return web.json_response(
            {
                "project": worker.config.project,
                "collector": worker.config.collector,
                "mode": worker.config.collection.mode,
                "state": worker.state,
                "detail": worker.detail,
                "requests": [
                    asdict(item) for item in reversed(worker.receipts.publications())
                ][:20],
            }
        )

    async def scans(request: web.Request) -> web.Response:
        return web.json_response(
            [
                {
                    "scan_id": item.scan_id,
                    "phase": item.phase,
                    "occurred_at": item.occurred_at,
                    "revision": item.revision,
                    "mode": item.evidence.mode
                    if item.evidence
                    else worker.config.collection.mode,
                    "changes": item.evidence.changes if item.evidence else (),
                    "issues": item.evidence.issues if item.evidence else (),
                }
                for item in reversed(worker.receipts.all())
            ][:50]
        )

    async def evidence(request: web.Request) -> web.Response:
        scan_id = uuid(request.match_info["scan_id"])
        result = next(
            (item for item in worker.receipts.all() if item.scan_id == scan_id), None
        )
        if result is None or result.evidence is None:
            raise web.HTTPNotFound(text="Evidence is not available")
        return web.json_response(
            {"revision": result.revision, "evidence": asdict(result.evidence)}
        )

    async def collect(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"request_id"})
        publication = await worker.request_scan(uuid(row["request_id"]))
        return web.json_response(
            asdict(publication), status=202 if publication.state == "published" else 409
        )

    async def reports(request: web.Request) -> web.Response:
        revisions = {item.revision for item in worker.receipts.all() if item.revision}
        result = [
            item
            for item in await worker.port.reports()
            if isinstance(item.inputs, tuple) and revisions.intersection(item.inputs)
        ]
        return web.json_response(
            [
                {
                    "revision": item.id,
                    "title": item.title
                    if isinstance(item.title, str)
                    else "Network privacy investigation",
                    "status": item.report_status
                    if isinstance(item.report_status, str)
                    else "unknown",
                }
                for item in result
            ]
        )

    async def report(request: web.Request) -> web.Response:
        revision = uuid(request.match_info["revision"])
        known = {item.revision for item in worker.receipts.all() if item.revision}
        candidates = await worker.port.reports()
        if not any(
            item.id == revision
            and isinstance(item.inputs, tuple)
            and known.intersection(item.inputs)
            for item in candidates
        ):
            raise web.HTTPNotFound(text="Investigation report unavailable")
        return web.json_response(
            {"revision": revision, "text": await worker.port.read_source(revision)}
        )

    for path in ("/", "/app.js", "/style.css"):
        app.router.add_get(path, asset)
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/tools", tool_catalog)
    app.router.add_post("/api/tools/{name}", call_tool)
    app.router.add_get("/api/scans", scans)
    app.router.add_get("/api/scans/{scan_id}", evidence)
    app.router.add_post("/api/scans", collect)
    app.router.add_get("/api/reports", reports)
    app.router.add_get("/api/reports/{revision}", report)
    return app
