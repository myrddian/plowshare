"""The external application's HTTP boundary. Plowshare communication stays on its SDK."""

from __future__ import annotations

import hashlib
import hmac
from dataclasses import asdict
from importlib.resources import files
from ipaddress import IPv4Network
from typing import Awaitable, Callable

from aiohttp import web
from plowshare import Refusal, TransportError

from .contracts import integer, items, object_fields, parse_json, text, uuid
from .discovery import DeviceDiscovery, DiscoveryPlan
from .journal import Publication
from .monitor import MonitorChoice, MonitorSettings, SettingsBusy
from .tools import DEFINITIONS, PrivacyTools, WorkerTools, decode_call
from .worker import Worker


def application(
    worker: Worker,
    token: str,
    tools: PrivacyTools | None = None,
    settings: MonitorSettings | None = None,
    discovery: DeviceDiscovery | None = None,
    instance: str | None = None,
) -> web.Application:
    """Serve static UI and bearer-protected APIs without exposing deployment credentials.

    Browser sessions use an HttpOnly, SameSite=Strict cookie. Bearers remain
    supported for remote/operator API clients. Cookie mutations require the exact
    same origin; mutations require JSON plus an authenticated session or bearer;
    cross-origin access is unavailable. Static assets contain no household data.
    Use TLS at the deployment proxy when serving beyond the collector machine.
    """
    if len(token) < 24 or any(ord(character) < 33 for character in token):
        raise ValueError(
            "Web bearer must contain at least 24 non-whitespace characters"
        )

    if instance is not None:
        uuid(instance)
    cookie = "privacy_" + hashlib.sha256(token.encode()).hexdigest()[:16]

    @web.middleware
    async def boundary(
        request: web.Request,
        handler: Callable[[web.Request], Awaitable[web.StreamResponse]],
    ) -> web.StreamResponse:
        if request.path.startswith("/api/"):
            expected = "Bearer " + token
            bearer_authorized = hmac.compare_digest(
                request.headers.get("Authorization", "").encode(), expected.encode()
            )
            session_authorized = hmac.compare_digest(
                request.cookies.get(cookie, "").encode(), token.encode()
            )
            if not bearer_authorized and not session_authorized:
                raise web.HTTPUnauthorized(text="Dashboard authentication required")
            if (
                session_authorized
                and not bearer_authorized
                and request.method == "POST"
            ):
                if (
                    request.headers.get("Origin")
                    != request.scheme + "://" + request.host
                ):
                    raise web.HTTPForbidden(
                        text="A same-origin dashboard request is required"
                    )
            if request.method == "POST" and request.content_type != "application/json":
                raise web.HTTPUnsupportedMediaType(text="JSON is required")
        try:
            response = await handler(request)
        except SettingsBusy as error:
            response = web.json_response({"error": str(error)}, status=409)
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

    async def session_status(request: web.Request) -> web.Response:
        return web.json_response({"instance": instance})

    async def connect_session(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        response = web.json_response({"connected": True})
        response.set_cookie(
            cookie,
            token,
            httponly=True,
            samesite="Strict",
            secure=request.secure,
            path="/api",
        )
        return response

    async def disconnect_session(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        response = web.json_response({"connected": False})
        response.del_cookie(cookie, path="/api")
        return response

    async def monitoring(request: web.Request) -> web.Response:
        if settings is None:
            raise web.HTTPNotFound(text="Dashboard settings are not enabled")
        return web.json_response(asdict(settings.view()))

    async def save_monitoring(request: web.Request) -> web.Response:
        choice = MonitorChoice.decode(parse_json(await request.read()))
        if settings is None:
            raise web.HTTPNotFound(text="Dashboard settings are not enabled")
        return web.json_response(asdict(await settings.save(choice)))

    async def previous_devices(request: web.Request) -> web.Response:
        result = discovery.latest() if discovery is not None else None
        return web.json_response(asdict(result) if result is not None else None)

    async def find_devices(request: web.Request) -> web.Response:
        if discovery is None:
            raise web.HTTPNotFound(text="Dashboard discovery is not enabled")
        row = object_fields(parse_json(await request.read()), {"network", "ports"})
        plan = DiscoveryPlan(
            IPv4Network(text(row["network"], 64), strict=True),
            tuple(integer(port, 1, 65535) for port in items(row["ports"], 8)),
            0.5,
            32,
        )
        # Discovery is an operator request, never an agent tool. The HTTP bearer
        # must be accepted before any network probe; there is no silent subnet scan.
        return web.json_response(asdict(await discovery.scan(plan)))

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
                "collection_enabled": worker.config.collection.enabled,
                "settings_available": settings is not None,
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
    app.router.add_get("/api/session/status", session_status)
    app.router.add_post("/api/session", connect_session)
    app.router.add_post("/api/session/logout", disconnect_session)
    app.router.add_get("/api/monitoring", monitoring)
    app.router.add_post("/api/monitoring", save_monitoring)
    app.router.add_get("/api/discovery", previous_devices)
    app.router.add_post("/api/discovery", find_devices)
    app.router.add_get("/api/status", status)
    app.router.add_get("/api/tools", tool_catalog)
    app.router.add_post("/api/tools/{name}", call_tool)
    app.router.add_get("/api/scans", scans)
    app.router.add_get("/api/scans/{scan_id}", evidence)
    app.router.add_post("/api/scans", collect)
    app.router.add_get("/api/reports", reports)
    app.router.add_get("/api/reports/{revision}", report)
    return app
