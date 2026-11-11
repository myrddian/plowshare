"""The external application's HTTP boundary. Plowshare communication stays on its SDK."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import time
from dataclasses import asdict
from importlib.resources import files
from ipaddress import IPv4Network
from typing import Awaitable, Callable

from aiohttp import web
from plowshare import Refusal, TransportError
from plowshare.contracts import InformationRevisionDto
from plowshare.tools import ToolAttention

from .advisories import AdvisoryLookup, AdvisoryUnavailable, public_model
from .console import OperatorConsole
from .contracts import (
    integer,
    items,
    object_fields,
    parse_json,
    private_ipv4,
    text,
    uuid,
)
from .device_assessments import (
    AssessmentConflict,
    AssessmentControl,
    AssessmentView,
    UnknownDevice,
)
from .device_inventory import DeviceCard
from .device_profiles import DeviceProfile, ProfileFields, ProfileManagement, field
from .diagnostics import refusal_detail
from .discovery import DeviceDiscovery, DiscoveryPlan
from .identity_source import ManagedProductLookup, ManagedProductUnavailable
from .issue_records import IssueManagement
from .journal import Publication
from .monitor import MonitorChoice, MonitorSettings, SettingsBusy
from .operator_state import Preferences
from .privacy_issues import IssueFields, IssueValidation
from .profile_research import ResearchReview
from .schedule_editor import ScheduleManagement
from .tools import DEFINITIONS, PrivacyTools, WorkerTools, decode_call
from .worker import ReconciliationRequired, Worker


def application(
    worker: Worker,
    token: str,
    tools: PrivacyTools | None = None,
    settings: MonitorSettings | None = None,
    discovery: DeviceDiscovery | None = None,
    instance: str | None = None,
    console: OperatorConsole | None = None,
    schedule_editor: ScheduleManagement | None = None,
    profiles: ProfileManagement | None = None,
    issues: IssueManagement | None = None,
    advisories: AdvisoryLookup | None = None,
    managed_products: Callable[[], ManagedProductLookup | None] | None = None,
    profile_research: ResearchReview | None = None,
    assessments: AssessmentControl | None = None,
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
        except (SettingsBusy, ReconciliationRequired, AssessmentConflict) as error:
            response = web.json_response({"error": str(error)}, status=409)
        except UnknownDevice as error:
            response = web.json_response({"error": str(error)}, status=404)
        except ToolAttention:
            response = web.json_response(
                {
                    "error": "A tool invocation has an unsettled receipt. Inspect the provider journal; it has not been resent."
                },
                status=409,
            )
        except IssueValidation as error:
            response = web.json_response({"error": str(error)}, status=400)
        except (AdvisoryUnavailable, ManagedProductUnavailable) as error:
            response = web.json_response({"error": str(error)}, status=503)
        except ValueError:
            response = web.json_response(
                {"error": "Invalid request or unavailable evidence"}, status=400
            )
        except Refusal as error:
            response = web.json_response(
                {"error": refusal_detail(error, "dashboard " + request.path)},
                status=503,
            )
        except TransportError:
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

    app = web.Application(middlewares=[boundary], client_max_size=131072)
    provider: PrivacyTools = tools if tools is not None else WorkerTools(worker)

    def operator() -> OperatorConsole:
        if console is None:
            raise web.HTTPNotFound(text="Operator console is not configured")
        return console

    async def overview(request: web.Request) -> web.Response:
        value = operator()
        health = await value.health()
        inventory = value.inventory()
        return web.json_response(
            {
                "health": asdict(health),
                "quick_presence": asdict(worker.presence_status()),
                "devices": [asdict(item) for item in inventory.devices],
                "candidates": [asdict(item) for item in inventory.candidates],
                "findings": [asdict(item) for item in value.findings()],
                "preferences": asdict(value.store.preferences),
                "investigations": [asdict(item) for item in value.manual],
                "schedule_control": asdict(schedule_editor.control_status())
                if schedule_editor
                else None,
                "pending_deployment": schedule_editor.pending_request()
                if schedule_editor
                else None,
            }
        )

    async def preferences(request: web.Request) -> web.Response:
        operator().store.set_preferences(
            Preferences.decode(parse_json(await request.read()))
        )
        return web.json_response(asdict(operator().store.preferences))

    async def expected(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"id", "expected"})
        if type(row["expected"]) is not bool:
            raise ValueError("Expected requires a boolean")
        operator().acknowledge(text(row["id"], 64), row["expected"])
        return web.json_response({"saved": True})

    async def investigate(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"scan_id", "request_id"})
        return web.json_response(
            asdict(
                await operator().investigate(
                    uuid(row["scan_id"]), uuid(row["request_id"])
                )
            ),
            status=202,
        )

    async def recovery(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        await operator().check_and_resume()
        return web.json_response({"checked": True})

    async def pihole(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()), {"origin", "password", "save"}
        )
        if type(row["save"]) is not bool:
            raise ValueError("Save requires a boolean")
        return web.json_response(
            asdict(
                await operator().connect_pihole(
                    text(row["origin"], 2048), text(row["password"], 4096), row["save"]
                )
            )
        )

    async def moves(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        return web.json_response(
            [asdict(item) for item in await operator().moved_devices()]
        )

    async def accept_move(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()), {"old", "new", "device_id"}
        )
        await operator().accept_move(
            text(row["old"], 64), text(row["new"], 64), text(row["device_id"], 128)
        )
        return web.json_response({"saved": True})

    async def schedule_control(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"paused"})
        if schedule_editor is None or type(row["paused"]) is not bool:
            raise ValueError("Schedule control is unavailable")
        try:
            schedule = await schedule_editor.control(row["paused"])
        finally:
            operator().cache_until = 0
        return web.json_response(
            {
                "schedule": asdict(schedule),
                "control": asdict(schedule_editor.control_status()),
            }
        )

    async def schedule_check(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        if schedule_editor is None:
            raise ValueError("Schedule control is unavailable")
        try:
            schedule = await schedule_editor.check_control()
        finally:
            operator().cache_until = 0
        return web.json_response(
            {
                "schedule": asdict(schedule),
                "control": asdict(schedule_editor.control_status()),
            }
        )

    async def schedule_review(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()), {"preset", "zone", "paused", "password"}
        )
        if schedule_editor is None or type(row["paused"]) is not bool:
            raise ValueError("Schedule editor is unavailable")
        return web.json_response(
            asdict(
                await schedule_editor.review(
                    text(row["preset"], 32),
                    text(row["zone"], 128),
                    row["paused"],
                    text(row["password"], 4096),
                )
            )
        )

    async def schedule_reconcile(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"password"})
        if schedule_editor is None:
            raise ValueError("Schedule editor is unavailable")
        revision = await schedule_editor.reconcile(text(row["password"], 4096))
        operator().cache_until = 0
        return web.json_response({"revision": revision})

    async def schedule_deploy(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()), {"review_id", "store", "password"}
        )
        if schedule_editor is None:
            raise ValueError("Schedule editor is unavailable")
        revision = await schedule_editor.deploy(
            uuid(row["review_id"]), text(row["store"], 128), text(row["password"], 4096)
        )
        operator().cache_until = 0
        return web.json_response({"revision": revision})

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

    def profile_service() -> ProfileManagement:
        if profiles is None:
            raise web.HTTPNotFound(text="Device profiles are not enabled")
        return profiles

    async def profile_list(request: web.Request) -> web.Response:
        service = profile_service()
        return web.json_response(
            {
                "profiles": [asdict(v) for v in await service.list()],
                "intents": [asdict(v) for v in service.writes()],
            }
        )

    async def profile_intents(request: web.Request) -> web.Response:
        return web.json_response([asdict(v) for v in profile_service().writes()])

    async def profile_source(request: web.Request) -> web.Response:
        return web.json_response(
            {
                "text": await profile_service().read(
                    uuid(request.match_info["identity"]),
                    uuid(request.match_info["revision"]),
                )
            }
        )

    def observed_device(request: web.Request) -> DeviceCard:
        address = request.match_info["address"]
        if not private_ipv4(address):
            raise web.HTTPNotFound(text="Choose an observed monitored device")
        device = next(
            (
                card
                for card in operator().inventory().devices
                if card.address == address
            ),
            None,
        )
        if device is None:
            raise web.HTTPNotFound(text="Choose an observed monitored device")
        return device

    async def device_product(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"device_id"})
        device = observed_device(request)
        identity = device.identity
        expected_device = text(row["device_id"], 64)
        if (
            identity is None
            or not identity.mac
            or expected_device != identity.device_id
        ):
            raise SettingsBusy(
                "This device association changed. Reopen the device before checking UniFi."
            )
        lookup = managed_products() if managed_products is not None else None
        if lookup is None or managed_products is None:
            return web.json_response({"available": False, "product": None})
        product = await lookup.managed_product(identity)
        current = observed_device(request).identity
        if (
            current is None
            or current.device_id != identity.device_id
            or managed_products() is not lookup
        ):
            raise SettingsBusy(
                "This device association changed. Reopen the device before checking UniFi."
            )
        return web.json_response(
            {"available": True, "product": asdict(product) if product else None}
        )

    async def device_advisories(request: web.Request) -> web.Response:
        if advisories is None:
            raise web.HTTPNotFound(text="Advisory lookup is not configured")
        row = object_fields(parse_json(await request.read()), {"model"})
        device = observed_device(request)
        model = public_model(text(row["model"], 128))
        # Never derive an external query from an observed hostname, label or address.
        names = {device.name.casefold()}
        for identity in (device.identity, device.last_known_identity):
            if identity:
                names.update(
                    value.casefold()
                    for value in (identity.hostname, identity.label)
                    if value
                )
        if model.casefold() in names:
            raise ValueError(
                "Enter a public manufacturer and model rather than a device name"
            )
        return web.json_response(asdict(await advisories.search(model)))

    def research_service() -> ResearchReview:
        if profile_research is None:
            raise web.HTTPNotFound(text="Background profile research is not available")
        return profile_research

    async def research_list(request: web.Request) -> web.Response:
        service = research_service()
        return web.json_response(
            {
                "enabled": service.ledger.enabled,
                "detail": service.error,
                "entries": [
                    {
                        "request_id": entry.request_id,
                        "address": entry.identity.address,
                        "label": entry.identity.label or entry.identity.hostname,
                        "model": entry.model,
                        "phase": entry.phase,
                        "run_id": entry.run_id,
                    }
                    for entry in reversed(service.ledger.entries)
                    if entry.phase not in {"reviewed", "dismissed"}
                ],
            }
        )

    async def research_setting(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"enabled"})
        if type(row["enabled"]) is not bool:
            raise ValueError("Choose whether to enable background device research")
        research_service().enable(row["enabled"])
        return web.json_response({"enabled": row["enabled"]})

    async def research_detail(request: web.Request) -> web.Response:
        return web.json_response(
            asdict(research_service().entry(uuid(request.match_info["identity"])))
        )

    async def research_editor(request: web.Request) -> web.Response:
        return web.json_response(
            asdict(
                research_service().draft_for_editor(
                    uuid(request.match_info["identity"])
                )
            )
        )

    async def research_review(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()), {"action", "model", "feedback"}
        )
        research_service().review(
            uuid(request.match_info["identity"]),
            text(row["action"], 16),
            text(row["model"], 128) if row["model"] is not None else None,
            field(row["feedback"], 512),
        )
        return web.json_response({"updated": True})

    def assessment_service() -> AssessmentControl:
        if assessments is None:
            raise web.HTTPNotFound(text="Device assessments are not available")
        return assessments

    def assessment_device(request: web.Request) -> str:
        # The service validates the ID and checks it against the current
        # inventory; unknown devices are 404 through UnknownDevice.
        return text(request.match_info["device_id"], 64)

    def assessment_response(view: AssessmentView, status: int = 200) -> web.Response:
        return web.json_response(asdict(view), status=status)

    async def assessment_view(request: web.Request) -> web.Response:
        return assessment_response(
            assessment_service().view(assessment_device(request))
        )

    async def assessment_report(request: web.Request) -> web.Response:
        return web.json_response(
            asdict(await assessment_service().report(assessment_device(request)))
        )

    async def assessment_assess(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        view = await assessment_service().assess(assessment_device(request))
        return assessment_response(view, 202 if view.state == "running" else 200)

    async def assessment_accept(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        view = await assessment_service().accept(assessment_device(request))
        if view.state == "review" and view.pending is None:
            # Refused or unsent: the device stays in review with the reason.
            return web.json_response(
                {"error": view.detail, "assessment": asdict(view)}, status=409
            )
        return assessment_response(view, 202 if view.pending else 200)

    async def assessment_check(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        return assessment_response(
            await assessment_service().check(assessment_device(request))
        )

    async def assessment_dismiss(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        return assessment_response(
            assessment_service().dismiss(assessment_device(request))
        )

    async def assessment_settings(request: web.Request) -> web.Response:
        service = assessment_service()
        return web.json_response(
            {
                "background_limit": service.ledger.background_limit,
                "detail": service.error,
            }
        )

    async def save_assessment_settings(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"background_limit"})
        service = assessment_service()
        if type(row["background_limit"]) is not int:
            raise ValueError("Choose a whole number of assessments a day")
        service.set_background_limit(row["background_limit"])
        return web.json_response(
            {"background_limit": service.ledger.background_limit, "detail": None}
        )

    async def profile_advisories(request: web.Request) -> web.Response:
        if advisories is None:
            raise web.HTTPNotFound(text="Advisory lookup is not configured")
        row = object_fields(parse_json(await request.read()), {"model"})
        reviewed_model = public_model(text(row["model"], 128))
        source = await profile_service().read(
            uuid(request.match_info["identity"]), uuid(request.match_info["revision"])
        )
        saved_model = public_model(DeviceProfile.read(source).fields.model)
        if reviewed_model != saved_model:
            raise ValueError("Review the saved model before sending an advisory lookup")
        return web.json_response(asdict(await advisories.search(saved_model)))

    async def profile_save(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()),
            {"profile_id", "request_id", "expected_revision", "fields"},
        )
        result = await profile_service().save(
            uuid(row["profile_id"]),
            uuid(row["request_id"]),
            uuid(row["expected_revision"])
            if row["expected_revision"] is not None
            else None,
            ProfileFields.decode(row["fields"]),
        )
        return web.json_response(
            asdict(result), status=202 if result.phase == "pending" else 200
        )

    async def profile_reconcile(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        await profile_service().reconcile()
        return web.json_response({"checked": True})

    def issue_service() -> IssueManagement:
        if issues is None:
            raise web.HTTPNotFound(text="Privacy issues are not enabled")
        return issues

    async def issue_list(request: web.Request) -> web.Response:
        service = issue_service()
        return web.json_response(
            {
                "issues": [asdict(v) for v in await service.list()],
                "intents": [asdict(v) for v in service.writes()],
            }
        )

    async def issue_intents(request: web.Request) -> web.Response:
        return web.json_response([asdict(v) for v in issue_service().writes()])

    async def issue_source(request: web.Request) -> web.Response:
        return web.json_response(
            {
                "text": await issue_service().read(
                    uuid(request.match_info["identity"]),
                    uuid(request.match_info["revision"]),
                )
            }
        )

    async def issue_save(request: web.Request) -> web.Response:
        row = object_fields(
            parse_json(await request.read()),
            {"issue_id", "request_id", "expected_revision", "fields"},
        )
        result = await issue_service().save(
            uuid(row["issue_id"]),
            uuid(row["request_id"]),
            uuid(row["expected_revision"])
            if row["expected_revision"] is not None
            else None,
            IssueFields.decode(row["fields"]),
        )
        return web.json_response(
            asdict(result), status=202 if result.phase == "pending" else 200
        )

    async def issue_reconcile(request: web.Request) -> web.Response:
        object_fields(parse_json(await request.read()), set())
        await issue_service().reconcile()
        return web.json_response({"checked": True})

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
            if definition.parameter == "device_id":
                properties["device_id"] = {
                    "type": "string",
                    "pattern": "^mac:[0-9a-f]{2}(:[0-9a-f]{2}){5}$",
                }
            elif definition.parameter:
                properties[definition.parameter] = {"type": "string", "format": "uuid"}
            if definition.name in {
                "network.scope",
                "network.scan_list",
                "network.evidence",
                "network.destinations",
                "network.devices",
            }:
                properties["offset"] = {"type": "integer", "minimum": 0}
                properties["limit"] = {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 50 if definition.name == "network.scan_list" else 32,
                }
            if definition.name == "network.evidence":
                properties["section"] = {
                    "type": "string",
                    "enum": ["summary", "tcp", "dns", "devices", "changes", "issues"],
                }
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
        name = {
            "/": "index.html",
            "/app.js": "app.js",
            "/style.css": "style.css",
            "/markdown.js": "markdown.js",
        }[request.path]
        media = {
            "index.html": "text/html",
            "app.js": "text/javascript",
            "markdown.js": "text/javascript",
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
                "profiles_available": profiles is not None,
                "advisories_available": profiles is not None and advisories is not None,
                "profile_research_available": profile_research is not None,
                "profile_research_enabled": profile_research is not None
                and profile_research.ledger.enabled,
                "profile_drafts_waiting": sum(
                    entry.phase == "review" for entry in profile_research.ledger.entries
                )
                if profile_research is not None
                else 0,
                "issues_available": issues is not None,
                "device_assessments_available": assessments is not None,
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
                    "changes": item.evidence.changes[:32] if item.evidence else (),
                    "change_count": sum(
                        not value.startswith("Baseline")
                        for value in item.evidence.changes
                    )
                    if item.evidence
                    else 0,
                    "issues": item.evidence.issues[:16] if item.evidence else (),
                    "issue_count": len(item.evidence.issues) if item.evidence else 0,
                    "investigation": asdict(item.investigation)
                    if item.investigation
                    else None,
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
            {
                "revision": result.revision,
                "evidence": parse_json(result.evidence.encode()),
            }
        )

    async def collect(request: web.Request) -> web.Response:
        row = object_fields(parse_json(await request.read()), {"request_id"})
        publication = await worker.request_scan(uuid(row["request_id"]))
        return web.json_response(
            asdict(publication), status=202 if publication.state == "published" else 409
        )

    report_cache: tuple[InformationRevisionDto, ...] = ()
    report_cache_until = 0.0
    report_lock = asyncio.Lock()

    async def report_candidates() -> tuple[InformationRevisionDto, ...]:
        nonlocal report_cache, report_cache_until
        async with report_lock:
            if time.monotonic() >= report_cache_until:
                report_cache = await worker.port.reports()
                report_cache_until = time.monotonic() + 20
            return report_cache

    async def reports(request: web.Request) -> web.Response:
        revisions = {item.revision for item in worker.receipts.all() if item.revision}
        result = [
            item
            for item in await report_candidates()
            if isinstance(item.inputs, tuple) and revisions.intersection(item.inputs)
        ]
        return web.json_response(
            [
                {
                    "revision": item.id,
                    "title": item.title
                    if isinstance(item.title, str)
                    else "Network privacy investigation",
                    "inputs": item.inputs,
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
        candidates = await report_candidates()
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

    app.router.add_get("/api/overview", overview)
    for path, handler in (
        ("preferences", preferences),
        ("expected", expected),
        ("investigations", investigate),
        ("recovery", recovery),
        ("pihole", pihole),
        ("device-moves", moves),
        ("device-moves/accept", accept_move),
        ("schedule/control", schedule_control),
        ("schedule/check", schedule_check),
        ("schedule/review", schedule_review),
        ("schedule/deploy", schedule_deploy),
        ("schedule/reconcile", schedule_reconcile),
    ):
        app.router.add_post("/api/" + path, handler)
    for path in ("/", "/app.js", "/style.css", "/markdown.js"):
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
    app.router.add_get("/api/issue-intents", issue_intents)
    app.router.add_get("/api/issues", issue_list)
    app.router.add_get("/api/issues/{identity}/{revision}", issue_source)
    app.router.add_post("/api/issues", issue_save)
    app.router.add_post("/api/issues/reconcile", issue_reconcile)
    app.router.add_get("/api/profile-intents", profile_intents)
    app.router.add_get("/api/profile-research", research_list)
    app.router.add_post("/api/profile-research", research_setting)
    app.router.add_get("/api/profile-research/{identity}", research_detail)
    app.router.add_get("/api/profile-research/{identity}/editor", research_editor)
    app.router.add_post("/api/profile-research/{identity}", research_review)
    app.router.add_post("/api/devices/{address}/product", device_product)
    app.router.add_post("/api/devices/{address}/advisories", device_advisories)
    app.router.add_get("/api/devices/{device_id}/assessment", assessment_view)
    app.router.add_get("/api/devices/{device_id}/assessment/report", assessment_report)
    for action, handler in (
        ("assess", assessment_assess),
        ("accept", assessment_accept),
        ("check", assessment_check),
        ("dismiss", assessment_dismiss),
    ):
        app.router.add_post("/api/devices/{device_id}/assessment/" + action, handler)
    app.router.add_get("/api/device-assessments", assessment_settings)
    app.router.add_post("/api/device-assessments", save_assessment_settings)
    app.router.add_get("/api/profiles", profile_list)
    app.router.add_get("/api/profiles/{identity}/{revision}", profile_source)
    app.router.add_post(
        "/api/profiles/{identity}/{revision}/advisories", profile_advisories
    )
    app.router.add_post("/api/profiles", profile_save)
    app.router.add_post("/api/profiles/reconcile", profile_reconcile)
    app.router.add_get("/api/reports", reports)
    app.router.add_get("/api/reports/{revision}", report)
    return app
