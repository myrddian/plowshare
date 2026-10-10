"""Explicit deployment inputs; no implicit local server, credentials or listener."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import signal
from contextlib import AsyncExitStack, ExitStack
from dataclasses import asdict
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

from .collector_factory import configured_collector
from .console import FileManualInvestigations, OperatorConsole
from .console_port import SdkConsolePort
from .contracts import Configuration, Snapshot, load_json
from .dashboard import RunningDashboard
from .discovery import LocalDeviceDiscovery
from .journal import FileReceipts
from .monitor import FileMonitorSettings
from .native_tools import DECLARATIONS, CataloguedProvider, registered
from .operator_state import FileOperatorStore
from .peer import IntegrationPeer, SdkOutgoingPort
from .peer_journal import FilePeerReceipts
from .ports import SdkPrivacyPort
from .schedule_editor import ScheduleEditor
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
                {"retained": False, "evidence": asdict(evidence)}, allow_nan=False
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
                exported = deployment_config(
                    config.project, args.tool_provider, args.tool_account, DECLARATIONS
                )
                native_journal = stores.enter_context(
                    SqliteToolJournal(
                        config.state_directory / "relay-tools", configuration=exported
                    )
                )
                native = ToolProvider(
                    client,
                    project=config.project,
                    provider=args.tool_provider,
                    account=args.tool_account,
                    tools=registered(WorkerTools(worker)),
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
                        if console is not None:
                            console.port = SdkConsolePort(replacement, worker.config)
                        if peer is not None:
                            peer.port = SdkOutgoingPort(replacement, worker.config)
                        if native_journal is not None:
                            native = ToolProvider(
                                replacement,
                                project=worker.config.project,
                                provider=args.tool_provider,
                                account=args.tool_account,
                                tools=registered(WorkerTools(worker)),
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
            runner = web.AppRunner(
                application(
                    worker,
                    credential(config.web_token_environment),
                    instance=instance,
                    settings=settings,
                    discovery=discovery,
                    console=console,
                    schedule_editor=ScheduleEditor(Path(args.config).parent)
                    if getattr(args, "deployment_controls", False)
                    else None,
                ),
                access_log=None,
            )
            await runner.setup()
            task: asyncio.Task[None] | None = None
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
                task = asyncio.create_task(worker.run(stop, active_requests))
                await stop.wait()
            finally:
                stop.set()
                if task:
                    task.cancel()
                    try:
                        await task
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
