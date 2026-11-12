"""Explicit deployment inputs; no implicit local server, credentials or listener."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import signal
from contextlib import AsyncExitStack, ExitStack
from dataclasses import asdict
from functools import partial
from ipaddress import ip_address
from pathlib import Path
from typing import Awaitable, Callable
from uuid import uuid4

from aiohttp import web
from plowshare import Client, Refusal, TransportError
from plowshare.contracts import (
    ScheduleDefinitionDto,
    ScheduleDefinitionDtoActionDto,
    ScheduleDefinitionDtoLimitsDto,
    ScheduleDefinitionDtoTargetDto,
    ScheduleSaveRequest,
)
from plowshare.tool_journal import SqliteToolJournal
from plowshare.tools import ToolAttention, ToolProvider, deployment_config

from .advisories import NvdAdvisories
from .assessment_port import SdkAssessmentPort
from .collection import NetworkCollector
from .collector_factory import configured_collector
from .console import FileManualInvestigations, OperatorConsole
from .console_port import SdkConsolePort
from .contracts import Configuration, Snapshot, load_json
from .dashboard import RunningDashboard
from .deep_scan import compose_scanner
from .device_assessments import AssessmentJournal, DeviceAssessments
from .device_describer import LocalDeviceDescriber
from .device_facts import DeviceFactsJournal, DeviceFactsService
from .device_profiles import DeviceProfiles, FileProfileIntents, profile_for
from .discovery import LocalDeviceDiscovery
from .dossier_port import DeviceDossiers, FileDossierIntents, SdkDossierPort
from .identity_source import ManagedProductLookup
from .issue_port import SdkIssuePort
from .issue_records import FileIssueIntents, PrivacyIssues
from .journal import FileReceipts
from .monitor import FileMonitorSettings
from .native_tools import DECLARATIONS, CataloguedProvider, registered
from .operator_state import FileOperatorStore
from .peer import IntegrationPeer, SdkOutgoingPort
from .peer_journal import FilePeerReceipts
from .ports import SdkPrivacyPort
from .presence import PresenceHistory, PresenceView, PresenceWatch
from .profile_port import SdkProfilePort
from .profile_research import ProfileResearch, ResearchJournal, reviewed_model
from .profile_research_port import SdkResearchPort
from .schedule_control import ScheduleRuntime, SdkScheduleRuntime
from .schedule_editor import ScheduleEditor
from .tool_journal_upgrade import open_tool_journal
from .tools import WorkerTools
from .web import application
from .worker import ActiveRequests, ExternalRequests, ReconciliationRequired, Worker


def credential(name: str) -> str:
    value = os.environ.get(name)
    if not value or value != value.strip():
        raise ValueError("Set the configured credential environment variable")
    return value


async def execute(args: argparse.Namespace) -> None:
    config = Configuration.read(Path(args.config))
    advisory_origin = os.environ.get("PLOWSHARE_PRIVACY_NVD_ORIGIN")
    advisories = (
        NvdAdvisories(advisory_origin)
        if args.command == "serve" and advisory_origin is not None
        else None
    )
    if args.command == "check":
        if config.collection.observations_file:
            Snapshot.decode(load_json(config.collection.observations_file))
        print(
            json.dumps(
                {
                    "valid": True,
                    "project": config.project,
                    "collector": config.collector,
                    "mode": config.collection.mode,
                }
            )
        )
        return
    if args.command == "tool-bindings":
        print(
            deployment_config(
                config.project, args.tool_provider, args.tool_account, DECLARATIONS
            )
        )
        return
    if args.command == "scan":
        # Local diagnostics use the same bounded collector without server credentials
        # or durable admission. They are explicitly labelled as unretained evidence.
        identity = str(uuid4())
        evidence = await configured_collector(
            config.collector, config.collection
        ).collect(identity, "diagnostic:" + identity, None, None)
        print(
            json.dumps(
                {"retained": False, "evidence": json.loads(evidence.encode())},
                allow_nan=False,
            )
        )
        return
    async with AsyncExitStack() as connections:
        client = await connections.enter_async_context(
            await Client.connect(
                config.origin, credential(config.token_environment), timeout=15
            )
        )
        if args.command == "install-schedule":
            saved = (
                await client.request(
                    ScheduleSaveRequest(
                        project=config.project,
                        source="server",
                        name="network_scan",
                        overwrite=False,
                        definition=ScheduleDefinitionDto(
                            version=1,
                            cron=args.cron,
                            zone=args.zone,
                            paused=True,
                            action=ScheduleDefinitionDtoActionDto(
                                kind="orchestration",
                                agent="privacy_coordinator",
                                name="privacy_tick",
                                input="A collection occurrence is available through Relay.",
                                mode=None,
                            ),
                            target=ScheduleDefinitionDtoTargetDto(
                                kind="mailbox",
                                project=None,
                                conversation=None,
                                to=None,
                                route=None,
                            ),
                            limits=ScheduleDefinitionDtoLimitsDto(
                                max_model_calls=1, max_turns=8, queue_cap=1
                            ),
                        ),
                    )
                )
            ).require_payload()
            print(
                json.dumps(
                    {
                        "internal_name": saved.internal_name,
                        "status": saved.status,
                        "paused": True,
                        "next": "Set configuration.schedule to internal_name, then explicitly resume this schedule.",
                    }
                )
            )
            return
        with FileReceipts(config) as receipts, ExitStack() as stores:
            operator_store = (
                FileOperatorStore(Path(args.config).parent)
                if getattr(args, "dashboard_settings", False)
                else None
            )
            worker = Worker(
                config,
                SdkPrivacyPort(client, config),
                receipts,
                configured_collector(config.collector, config.collection),
                operator_store,
                PresenceWatch(PresenceHistory(config.state_directory)),
            )
            # The record and request services are composed before the tool
            # provider, whose assistant tools use them (see assistant_tools).
            issues = (
                PrivacyIssues(
                    SdkIssuePort(client, config, worker.port),
                    FileIssueIntents(
                        Path(args.config).parent, config.project, config.collector
                    ),
                )
                if operator_store
                else None
            )
            profiles = (
                DeviceProfiles(
                    SdkProfilePort(client, config, worker.port),
                    FileProfileIntents(
                        Path(args.config).parent, config.project, config.collector
                    ),
                    receipts,
                    lambda: worker.config.collection.targets,
                    issues,
                    worker.presence_view,
                )
                if operator_store
                else None
            )

            # Per-device assessments read the same inventory as the dashboard and
            # the network_device tool. Their ports read the current connection
            # cell, so operator recovery reaches them without a rebuild.
            dossiers = (
                DeviceDossiers(
                    SdkDossierPort(client, config, worker.port),
                    FileDossierIntents(
                        Path(args.config).parent, config.project, config.collector
                    ),
                )
                if operator_store
                else None
            )
            # Deep port scans probe from this host with the collector's identity
            # source. An unreadable journal disables them only; collection starts.
            deep_scans, deep_scans_detail = compose_scanner(
                worker, config.state_directory, operator_store is not None
            )
            assessments = (
                DeviceAssessments(
                    AssessmentJournal(config.state_directory),
                    SdkAssessmentPort(lambda: client, config, lambda: worker.port),
                    dossiers,
                    worker.device_inventory,
                    facts=worker.device_facts,
                    profile=partial(profile_for, profiles)
                    if profiles is not None
                    else None,
                    zone=lambda: worker.config.zone,
                    flagged=deep_scans.flagged
                    if deep_scans is not None
                    else lambda device, report, requested_at: None,
                    deep_scan=deep_scans.latest
                    if deep_scans is not None
                    else lambda device: None,
                )
                if dossiers is not None
                else None
            )

            def assistant_tools() -> WorkerTools:
                """The native provider's tools, with the assistant's services.

                The peer keeps the read and scan tools only; only the native
                provider serves the assistant's request and record tools.
                """
                return WorkerTools(
                    worker,
                    deep_scans=deep_scans,
                    assessments=assessments,
                    profiles=profiles,
                    issues=issues,
                )

            peer = (
                IntegrationPeer(
                    config,
                    SdkOutgoingPort(client, config),
                    WorkerTools(worker),
                    FilePeerReceipts(config, receipts),
                )
                if config.outgoing_peer is not None
                else None
            )
            external: ExternalRequests | None = peer
            native: ToolProvider | None = None
            native_journal: SqliteToolJournal | None = None
            if args.tool_provider is not None:
                if peer is not None:
                    raise ValueError(
                        "Choose either native tools or the outgoing compatibility peer"
                    )
                native_journal = stores.enter_context(
                    open_tool_journal(
                        config.state_directory / "relay-tools",
                        config.project,
                        args.tool_provider,
                        args.tool_account,
                    )
                )
                native = ToolProvider(
                    client,
                    project=config.project,
                    provider=args.tool_provider,
                    account=args.tool_account,
                    tools=registered(assistant_tools()),
                    journal=native_journal,
                )
                external = CataloguedProvider(
                    native, config.state_directory, args.tool_catalog_renew_seconds
                )
            if args.command == "once":
                if external is not None:
                    await external.poll()
                await worker.poll()
                print(json.dumps([asdict(item) for item in receipts.all()]))
                return
            if args.command == "reconcile":
                await worker.reconcile()
                if peer is not None:
                    await peer.reconcile()
                if native is not None:
                    await native.reconcile()
                print("Retained evidence reconciled; no mutation was replayed.")
                return
            active_requests = ActiveRequests(external) if external is not None else None
            stop = asyncio.Event()
            loop = asyncio.get_running_loop()
            for name in (signal.SIGINT, signal.SIGTERM):
                try:
                    loop.add_signal_handler(name, stop.set)
                except NotImplementedError:
                    pass  # Windows retains asyncio.run's normal Ctrl-C cancellation.
            instance = str(uuid4())
            handoff = Path(args.config).parent / "dashboard-runtime.json"
            owns_handoff = False
            settings = (
                FileMonitorSettings(Path(args.config), worker, receipts)
                if operator_store
                else None
            )
            discovery = (
                LocalDeviceDiscovery(Path(args.config).parent)
                if operator_store
                else None
            )

            async def recover(reconcile_manual: Callable[[], Awaitable[None]]) -> None:
                nonlocal client, native
                async with worker.request_lock:
                    if worker.state == "attention_required":
                        # Explicit operator recovery creates one fresh SDK session.
                        # No request is retried; journals remain their owning stores.
                        replacement = await connections.enter_async_context(
                            await Client.connect(
                                worker.config.origin,
                                credential(worker.config.token_environment),
                                timeout=15,
                            )
                        )
                        candidate_port = SdkPrivacyPort(replacement, worker.config)
                        try:
                            available = await candidate_port.available_topics()
                            if (
                                not {
                                    worker.config.request_topic,
                                    worker.config.result_topic,
                                }
                                <= available
                            ):
                                raise ReconciliationRequired(
                                    "Required Relay topics remain unavailable"
                                )
                        except (
                            Refusal,
                            TransportError,
                            ValueError,
                            ReconciliationRequired,
                        ):
                            await replacement.close()
                            raise
                        old = client
                        client = replacement
                        worker.port = candidate_port
                        if issues is not None:
                            issues.port = SdkIssuePort(
                                replacement, worker.config, worker.port
                            )
                        if profiles is not None:
                            profiles.port = SdkProfilePort(
                                replacement, worker.config, worker.port
                            )
                        if console is not None:
                            console.port = SdkConsolePort(replacement, worker.config)
                        if dossiers is not None:
                            dossiers.port = SdkDossierPort(
                                replacement, worker.config, worker.port
                            )
                        if peer is not None:
                            peer.port = SdkOutgoingPort(replacement, worker.config)
                        if native_journal is not None:
                            native = ToolProvider(
                                replacement,
                                project=worker.config.project,
                                provider=args.tool_provider,
                                account=args.tool_account,
                                tools=registered(assistant_tools()),
                                journal=native_journal,
                            )
                            if active_requests is None:
                                raise ValueError("Missing configured provider dispatch")
                            active_requests.target = CataloguedProvider(
                                native,
                                worker.config.state_directory,
                                args.tool_catalog_renew_seconds,
                            )
                        await old.close()
                    await worker.reconcile()
                    if peer is not None:
                        await peer.reconcile()
                    if native is not None:
                        await native.reconcile()
                    topics = await worker.port.available_topics()
                    if (
                        not {worker.config.request_topic, worker.config.result_topic}
                        <= topics
                    ):
                        raise ReconciliationRequired(
                            "Required Relay topics remain unavailable"
                        )
                    if issues is not None:
                        await issues.reconcile()
                    if profiles is not None:
                        await profiles.reconcile()
                    await reconcile_manual()
                    if worker.state == "attention_required":
                        worker.recovery_requested.set()

            console = (
                OperatorConsole(
                    Path(args.config).parent,
                    worker,
                    SdkConsolePort(client, config),
                    settings,
                    discovery,
                    operator_store,
                    recover,
                    FileManualInvestigations(Path(args.config).parent),
                    getattr(args, "credential_expires_at", None),
                )
                if operator_store and settings and discovery
                else None
            )

            def schedule_runtime(current: Configuration) -> ScheduleRuntime:
                if (
                    current.origin != worker.config.origin
                    or current.project != worker.config.project
                ):
                    raise ValueError(
                        "Schedule configuration differs from the active service session"
                    )
                # Capture the connection cell: recovery replaces client without retaining a stale socket.
                return SdkScheduleRuntime(client, current)

            def managed_products() -> ManagedProductLookup | None:
                # Settings can replace the active collector and its configured controller.
                collector = worker.collector
                if isinstance(collector, NetworkCollector) and isinstance(
                    collector.identity_source, ManagedProductLookup
                ):
                    return collector.identity_source
                return None

            def research_presence() -> PresenceView:
                plan = worker.config.collection
                return (
                    worker.presence_view()
                    if (plan.enabled and plan.quick_scan and worker.state == "ready")
                    else PresenceView()
                )

            research = (
                ProfileResearch(
                    ResearchJournal(config.state_directory),
                    SdkResearchPort(lambda: client, config.project),
                    research_presence,
                    receipts,
                    managed_products,
                    advisories,
                    zone=lambda: worker.config.zone,
                )
                if operator_store is not None and config.collection.mode == "tcp"
                else None
            )

            def confirmed_model(device_id: str) -> str | None:
                return (
                    reviewed_model(research.ledger, device_id)
                    if research is not None
                    else None
                )

            # The facts step does nothing until collection.deviceDescriptions is
            # enabled; it reads the active plan so settings apply between devices.
            worker.facts = (
                DeviceFactsService(
                    DeviceFactsJournal(config.state_directory),
                    LocalDeviceDescriber(lambda: worker.config.collection.targets),
                    lambda: worker.config.collection,
                    advisories,
                    managed_products,
                    confirmed_model,
                )
                if config.collection.mode == "tcp"
                else None
            )

            runner = web.AppRunner(
                application(
                    worker,
                    credential(config.web_token_environment),
                    instance=instance,
                    settings=settings,
                    discovery=discovery,
                    console=console,
                    profiles=profiles,
                    issues=issues,
                    advisories=advisories,
                    managed_products=managed_products,
                    profile_research=research,
                    assessments=assessments,
                    deep_scans=deep_scans,
                    deep_scans_detail=deep_scans_detail,
                    advisory_origin=advisory_origin,
                    schedule_editor=ScheduleEditor(
                        Path(args.config).parent, schedule_runtime
                    )
                    if getattr(args, "deployment_controls", False)
                    else None,
                ),
                access_log=None,
            )
            await runner.setup()
            task: asyncio.Task[None] | None = None
            research_task: asyncio.Task[None] | None = None
            assessment_task: asyncio.Task[None] | None = None
            deep_scan_task: asyncio.Task[None] | None = None
            try:
                site = web.TCPSite(runner, args.bind, args.port)
                await site.start()
                if getattr(
                    args, "dashboard_handoff", getattr(args, "open_browser", False)
                ):
                    address = ip_address(args.bind)
                    if not address.is_loopback:
                        raise ValueError(
                            "Automatic dashboard login requires an explicit loopback bind"
                        )
                    port = runner.addresses[0][1]
                    host = (
                        "[" + str(address) + "]"
                        if address.version == 6
                        else str(address)
                    )
                    dashboard = RunningDashboard(
                        instance,
                        f"http://{host}:{port}",
                        credential(config.web_token_environment),
                    )
                    dashboard.write(handoff)
                    owns_handoff = True
                    if getattr(args, "open_browser", False):
                        await dashboard.open()
                    print(
                        "Network Privacy Watch is ready. Use your private launcher to open the dashboard.",
                        flush=True,
                    )
                else:
                    print(
                        "Privacy dashboard listening; use the configured web bearer to unlock its APIs.",
                        flush=True,
                    )
                task = asyncio.create_task(
                    worker.run(
                        stop,
                        active_requests,
                        settings.apply_pending if settings else None,
                    )
                )
                if research is not None:
                    research_task = asyncio.create_task(research.run(stop))
                if assessments is not None:
                    assessment_task = asyncio.create_task(assessments.run(stop))
                if deep_scans is not None:
                    deep_scan_task = asyncio.create_task(deep_scans.run(stop))
                await stop.wait()
            finally:
                stop.set()
                for running in (deep_scan_task, assessment_task, research_task, task):
                    if running is not None:
                        running.cancel()
                for running in (deep_scan_task, assessment_task, research_task, task):
                    if running is not None:
                        try:
                            await running
                        except asyncio.CancelledError:
                            pass
                await runner.cleanup()
                if owns_handoff:
                    handoff.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument(
        "--tool-provider", help="Enable a configured native Relay tool provider"
    )
    parser.add_argument(
        "--tool-account",
        help="Authenticated provider account named in the server binding",
    )
    parser.add_argument(
        "--tool-catalog-renew-seconds",
        type=float,
        default=100,
        help="Catalogue renewal interval, 1-100 seconds; use less than the configured provider lease",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "check", help="Validate offline without credentials, scans or journal writes"
    )
    commands.add_parser(
        "tool-bindings",
        help="Export native tool configuration for operator installation",
    )
    commands.add_parser("once", help="Perform one Relay intake and collection pass")
    commands.add_parser(
        "scan",
        help="Probe the configured scope locally; print unretained diagnostic evidence",
    )
    commands.add_parser(
        "reconcile", help="Read retained records to settle unknown receipts"
    )
    schedule = commands.add_parser(
        "install-schedule", help="Create a paused project schedule through the SDK"
    )
    schedule.add_argument("--cron", required=True)
    schedule.add_argument("--zone", required=True)
    serve = commands.add_parser(
        "serve", help="Run the collector and its authenticated Python web UI"
    )
    serve.add_argument(
        "--dashboard-settings",
        action="store_true",
        help="Enable private operator settings, health and recovery controls",
    )
    serve.add_argument("--bind", required=True)
    serve.add_argument("--port", required=True, type=int)
    args = parser.parse_args()
    if not 1 <= args.tool_catalog_renew_seconds <= 100:
        parser.error("Catalogue renewal must be between 1 and 100 seconds")
    if (
        bool(args.tool_provider) != bool(args.tool_account)
        or args.command == "tool-bindings"
        and not args.tool_provider
    ):
        parser.error("Native tools require both --tool-provider and --tool-account")
    if args.command == "serve" and (
        not args.bind.strip() or not 1 <= args.port <= 65535
    ):
        parser.error("Configure an explicit bind address and port between 1 and 65535")
    try:
        asyncio.run(execute(args))
    except (
        ValueError,
        OSError,
        TransportError,
        Refusal,
        ReconciliationRequired,
        ToolAttention,
    ) as error:
        # Never print tokens, complete server refusals, configuration or private paths.
        raise SystemExit(
            "Privacy application stopped ("
            + type(error).__name__
            + "). Inspect the configured deployment and retained receipts."
        ) from None
