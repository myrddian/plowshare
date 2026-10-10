"""Guided, private setup for Network Privacy Watch using the public Python SDK.

Configure is offline. Install performs two retained source deployments around
service-account provisioning; it never retries mutations after a lost reply.
Status is read-only on the server. Start reuses installation and opens a local
dashboard; serve supports an explicitly configured remote collector listener.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import secrets
import shlex
import sys
from dataclasses import asdict
from getpass import getpass
from ipaddress import IPv4Network, ip_address
from pathlib import Path
from uuid import uuid4

from aiohttp import ClientError
from plowshare import Client, Refusal, TransportError
from plowshare.contracts import (
    AdminAccountsRequest,
    AdminStatusRequest,
    ApplicationDeploymentReceiptRequest,
    ApplicationDeploymentStatusRequest,
    ApplicationDeployRequest,
    ApplicationSourceFileDto,
    FilestoreListRequest,
    FileStoreReferenceDto,
    ScheduleFilesRequest,
)
from plowshare.tools import ToolAttention

from .cli import execute
from .contracts import Configuration, integer, load_json, object_fields, text
from .dashboard import reopen
from .discovery import DiscoveryPlan, discover
from .monitor import replace_private
from .setup import (
    Setup,
    SetupClient,
    administrator_login,
    prepare,
    private_write,
    provision,
)
from .worker import ReconciliationRequired


class SetupProblem(ValueError):
    """An operator-facing error containing no credentials or server payloads."""


def ask(label: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default is not None else ""
    value = input(label + suffix + ": ").strip()
    return text(value or default, 4096)


def private_root(path: Path) -> Path:
    """Refuse linked paths and tracked checkout destinations before writing secrets."""
    if not path.is_absolute() or path.resolve() != path:
        raise SetupProblem("Choose an absolute, unlinked private directory.")
    if any((parent / ".git").exists() for parent in (path, *path.parents)):
        raise SetupProblem("Keep the private setup directory outside a Git checkout.")
    return path


def configure(directory: Path, source: Path, *, guided: bool = False) -> None:
    """Collect deployment inputs and prepare a private Application without network I/O."""
    root = private_root(directory)
    if root.exists():
        raise SetupProblem(
            "Choose a new private directory; existing files are preserved."
        )
    if not source.is_absolute() or source.resolve() != source or not source.is_dir():
        raise SetupProblem(
            "Choose the absolute, unlinked network-privacy-watch folder."
        )
    print("Configure the external collector and its separate application identity.")
    print(
        "The server must already have model bindings, an administrator and a FileStore."
    )
    origin = ask("Plowshare HTTP(S) origin (include your port if needed)")
    collector = ask("Collector identifier", "home-network")
    target_input = (
        ""
        if guided
        else input(
            "Device IPs (comma-separated; Enter to configure collection later): "
        ).strip()
    )
    targets = [part.strip() for part in target_input.split(",")] if target_input else []
    ports = (
        [int(part.strip()) for part in ask("TCP ports (comma-separated)").split(",")]
        if targets
        else []
    )
    timeout = 1.0 if guided else float(ask("Probe timeout in seconds", "1"))
    concurrency = 4 if guided else int(ask("Concurrent probes", "4"))
    service = ask("Separate service account handle")
    manager = ask("Existing human application manager handle")
    deployer = ask("Existing server administrator handle", manager)
    model = ask("Existing server model binding for all three agents", "reasoning")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", model):
        raise SetupProblem(
            "Choose a model binding identifier without spaces or newlines."
        )
    expiry = (
        30
        if guided
        else integer(int(ask("Service credential lifetime in days", "30")), 1, 365)
    )
    destination = (
        source.name
        if guided
        else ask(
            "Application path within the selected server FileStore",
            "network-privacy-watch",
        )
    )
    if not re.fullmatch(
        r"[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*", destination
    ):
        raise SetupProblem(
            "Choose a portable relative FileStore path without dots or traversal segments."
        )
    # The DTO loader validates the full collector plan before any remote effects.
    # Partial offline output can be inspected or removed; it contains no issued credential.
    root.mkdir(mode=0o700)
    config = root / "collector.json"
    private_write(
        config,
        json.dumps(
            {
                "origin": origin,
                "project": "network-privacy-watch",
                "collector": collector,
                "tokenEnvironment": "PLOWSHARE_TOKEN",
                "webTokenEnvironment": "PRIVACY_WEB_TOKEN",
                "stateDirectory": str(root / "state"),
                "requestTopic": "privacy.scan.requests",
                "resultTopic": "privacy.scan.completed",
                "group": "privacy-" + collector,
                "schedule": "REPLACE_WITH_SCHEDULE_INTERNAL_NAME",
                "collection": {
                    "mode": "tcp",
                    "enabled": bool(targets),
                    "targets": targets,
                    "ports": ports,
                    "timeoutSeconds": timeout,
                    "concurrency": concurrency,
                },
            },
            indent=2,
        )
        + "\n",
    )
    Configuration.read(config)
    setup = Setup(
        config,
        source,
        root / "deployment",
        service,
        manager,
        deployer,
        "network-privacy-collector",
        expiry,
    )
    # Reuse the strict setup boundary even for interactive input.
    settings = root / "setup-input.json"
    private_write(
        settings,
        json.dumps(
            {
                "collectorConfig": str(config),
                "applicationSource": str(source),
                "outputDirectory": str(setup.output),
                "serviceAccount": service,
                "managerAccount": manager,
                "deploymentAccount": deployer,
                "tokenName": setup.token_name,
                "expiresInDays": expiry,
            },
            indent=2,
        )
        + "\n",
    )
    prepare(Setup.read(settings))
    definitions = tuple((setup.output / "application/agents").glob("*.md")) + tuple(
        (setup.output / "application/orchestrations").glob("*.md")
    )
    for agent in definitions:
        content = agent.read_text(encoding="utf-8")
        if content.count("\nmodel: reasoning\n") != 1:
            raise SetupProblem(
                "Agent model declaration changed; review the source package."
            )
        agent.write_text(
            content.replace("\nmodel: reasoning\n", "\nmodel: " + model + "\n"),
            encoding="utf-8",
        )
    # These bundled templates own exactly one manifest model declaration. Scripts must
    # use the selected binding too, even when their conductor itself needs no inference.
    for script in (setup.output / "application/orchestrations").glob("*.js"):
        content = script.read_text(encoding="utf-8")
        declaration = "model: 'reasoning'"
        if content.count(declaration) != 1:
            raise SetupProblem(
                "Script model declaration changed; review the source package."
            )
        script.write_text(
            content.replace(declaration, "model: " + json.dumps(model)),
            encoding="utf-8",
        )
    private_write(
        root / "destination.json", json.dumps({"path": destination}, indent=2) + "\n"
    )
    (root / "state").mkdir(mode=0o700)
    print(
        "Prepared collector.json and deployment/application. Review both before install."
    )
    if not targets:
        print(
            "Collection is disabled. Discover devices and configure targets/ports when ready."
        )
    print("No login, network probes or server changes were performed.")


def application_files(source: Path) -> tuple[ApplicationSourceFileDto, ...]:
    """Snapshot bounded UTF-8 source only; reject links, devices and ambiguous names.

    Limits match source deployment. Generated and hidden directories are skipped;
    a root .plowshare directory is refused because Application resources live at root.
    No package hook, Python process or shell command is executed.
    """
    if source.resolve() != source or not source.is_dir():
        raise SetupProblem("Application source must be an unlinked directory.")
    files: list[ApplicationSourceFileDto] = []
    names: set[str] = set()
    total = 0
    entries = 0

    def visit(folder: Path, depth: int) -> None:
        nonlocal total, entries
        if depth > 16:
            raise SetupProblem("Application source exceeds 16 directory levels.")
        for path in sorted(folder.iterdir()):
            if depth == 0 and path.name == ".plowshare":
                raise SetupProblem(
                    "Application resources belong at root; remove .plowshare."
                )
            if path.name.startswith(".") or path.name in {
                "build",
                "node_modules",
                "__pycache__",
            }:
                continue
            entries += 1
            if entries > 2048 or path.is_symlink() or path.resolve() != path:
                raise SetupProblem("Application source has links or too many entries.")
            if path.is_dir():
                visit(path, depth + 1)
                continue
            name = path.relative_to(source).as_posix()
            if (
                not path.is_file()
                or len(name) > 512
                or not re.fullmatch(
                    r"[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*",
                    name,
                )
            ):
                raise SetupProblem(
                    "Application source needs portable regular text files."
                )
            with path.open("rb") as stream:
                content = stream.read(65537)
            total += len(content)
            if (
                len(content) > 65536
                or total > 131072
                or len(files) >= 128
                or name.lower() in names
            ):
                raise SetupProblem(
                    "Application source exceeds package limits or has duplicate paths."
                )
            decoded = content.decode("utf-8")
            if "\0" in decoded:
                raise SetupProblem("Application source cannot contain NUL characters.")
            names.add(name.lower())
            files.append(ApplicationSourceFileDto(path=name, text=decoded))

    visit(source, 0)
    if "plowshare.json" not in {file.path for file in files}:
        raise SetupProblem("Application source needs a root plowshare.json.")
    return tuple(files)


async def deploy(
    setup: Setup,
    client: SetupClient,
    destination: FileStoreReferenceDto,
    phase: str,
    expected: str | None,
) -> str:
    """Flush request identity before sending once; retain confirmed receipt privately."""
    config = Configuration.read(setup.collector)
    request = ApplicationDeployRequest(
        project=config.project,
        request_id=str(uuid4()),
        expected_revision=expected,
        destination=destination,
        writable_areas=(),
        files=application_files(setup.output / "application"),
    )
    private_write(
        setup.output / f"{phase}-intent.json",
        json.dumps(
            {
                "project": config.project,
                "requestId": request.request_id,
                "expectedRevision": expected,
                "destination": asdict(destination),
            },
            indent=2,
        )
        + "\n",
    )
    receipt = (await client.request(request)).require_payload()
    if receipt.project != config.project or receipt.request_id != request.request_id:
        raise SetupProblem(
            "Deployment returned a foreign receipt; inspect retained state."
        )
    private_write(
        setup.output / f"{phase}-receipt.json",
        json.dumps(asdict(receipt), indent=2) + "\n",
    )
    status = (
        await client.request(ApplicationDeploymentStatusRequest(project=config.project))
    ).require_payload()
    if (
        status.project != config.project
        or status.active_revision != receipt.release.revision
    ):
        raise SetupProblem(
            "Deployment is retained but another revision is active; inspect status."
        )
    return receipt.release.revision


async def install(
    setup: Setup, client: SetupClient, destination_path: str, *, guided: bool = False
) -> None:
    """Install a fresh paused application, issue its identity, then activate that identity.

    Any prior deployment/provisioning intent blocks re-entry before contacting the
    server. An existing application is refused rather than silently overwritten.
    """
    if any(
        (setup.output / name).exists()
        for name in (
            "bootstrap-intent.json",
            "provisioning-intent.json",
            "identity-intent.json",
        )
    ):
        raise SetupProblem(
            "Install was already attempted. Use status and the recovery guide; do not retry install."
        )
    config = Configuration.read(setup.collector)
    administrator = (await client.request(AdminStatusRequest())).require_payload()
    if not administrator.server_admin or administrator.handle != setup.deployer:
        raise SetupProblem("Log in as the configured deployment administrator.")
    humans = (await client.request(AdminAccountsRequest())).require_payload()
    if not any(a.enabled and a.handle == setup.manager for a in humans):
        raise SetupProblem("Create/enable the human manager account first.")
    status = (
        await client.request(ApplicationDeploymentStatusRequest(project=config.project))
    ).require_payload()
    if (
        status.project != config.project
        or status.active_revision is not None
        or status.releases
    ):
        raise SetupProblem(
            "This installer is for a first deployment. Use the update guide for existing applications."
        )
    stores = (await client.request(FilestoreListRequest())).require_payload()
    choices = sorted(store.alias for store in stores.stores if store.role == "MANAGER")
    if not choices:
        raise SetupProblem(
            "No FileStore with MANAGER access. Configure a server FileStore and grant the administrator access first."
        )
    if guided and len(choices) == 1:
        selected = 1
        print("Installing in FileStore:", choices[0])
    else:
        print("Choose the server FileStore destination:")
        for index, alias in enumerate(choices, 1):
            print(f"  {index}. {alias}")
        selected = integer(int(ask("FileStore number")), 1, len(choices))
    destination = FileStoreReferenceDto(
        store=choices[selected - 1], path=destination_path
    )
    # Check prepared source is still paused before committing the first intent.
    schedule = object_fields(
        load_json(setup.output / "application/schedules/network_scan.json"),
        {"version", "cron", "zone", "paused", "action", "target", "limits"},
    )
    if schedule["paused"] is not True:
        raise SetupProblem(
            "Keep the packaged network_scan schedule paused during installation."
        )
    revision = await deploy(setup, client, destination, "bootstrap", None)
    print("Paused Application deployed; provisioning its separate execution identity.")
    await provision(setup, client)
    await deploy(setup, client, destination, "identity", revision)
    private_write(
        setup.output / "installation-completed",
        "Two deployments and service provisioning confirmed.\n",
    )
    if guided:
        await wait_for_schedule(setup, client)
        print("Application and service account are ready.")
    else:
        await inspect(setup, client)
        print("Application installed. Run start to open the collector dashboard.")


async def inspect(setup: Setup, client: SetupClient) -> None:
    """Read retained status/receipts and resolve the schedule into local configuration.

    This can be run repeatedly: no accounts, tokens, deployments or schedules are
    changed on the server. It does not convert missing receipts into permission to retry.
    """
    config = Configuration.read(setup.collector)
    status = (
        await client.request(ApplicationDeploymentStatusRequest(project=config.project))
    ).require_payload()
    if status.project != config.project:
        raise SetupProblem("Deployment status returned a foreign project.")
    print("Active Application revision:", status.active_revision or "none")
    for phase in ("bootstrap", "identity"):
        path = setup.output / f"{phase}-intent.json"
        if not path.exists():
            continue
        intent = object_fields(
            load_json(path), {"project", "requestId", "expectedRevision", "destination"}
        )
        print(phase + " request UUID:", text(intent["requestId"], 36))
        receipt = (
            await client.request(
                ApplicationDeploymentReceiptRequest(
                    project=config.project, request_id=text(intent["requestId"], 36)
                )
            )
        ).require_payload()
        print(phase + " retained revision:", receipt.release.revision)
    if await resolve_schedule(setup, client):
        print(
            "Collector configuration now uses the deployed schedule identity:",
            Configuration.read(setup.collector).schedule,
        )
        print(
            "Review the paused schedule and verify service-owned Relay worker enrollment before resuming it."
        )
    else:
        print("Schedule is still registering. Run start to wait for readiness.")


async def resolve_schedule(setup: Setup, client: SetupClient) -> bool:
    """Resolve one active source schedule using read-only server requests."""
    config = Configuration.read(setup.collector)
    schedules = (await client.request(ScheduleFilesRequest())).require_payload()
    matches = [
        s
        for s in schedules
        if s.project == config.project
        and s.source == "server"
        and s.name == "network_scan"
        and s.status == "active"
    ]
    if len(matches) != 1:
        return False
    row = object_fields(
        load_json(setup.collector),
        {
            "origin",
            "project",
            "collector",
            "tokenEnvironment",
            "webTokenEnvironment",
            "stateDirectory",
            "requestTopic",
            "resultTopic",
            "group",
            "schedule",
            "collection",
        },
        {"outgoingPeer"},
    )
    row["schedule"] = matches[0].internal_name
    replace_private(setup.collector, json.dumps(row, indent=2) + "\n")
    return True


async def wait_for_schedule(setup: Setup, client: SetupClient) -> None:
    """Wait briefly for source reconciliation, without repeating any mutation."""
    print("Waiting for the Application to become ready…", flush=True)
    for attempt in range(30):
        if await resolve_schedule(setup, client):
            return
        if attempt < 29:
            await asyncio.sleep(1)
    raise SetupProblem(
        "The Application is installed but its schedule is still registering. Run start again later; installation will not be repeated."
    )


async def serve(setup: Setup, bind: str, port: int, *, automatic: bool = False) -> None:
    """Load the private service credential into this collector process, never the browser."""
    if not (setup.output / "installation-completed").is_file():
        raise SetupProblem(
            "Installation is incomplete. Inspect status and recovery before starting Python."
        )
    config = Configuration.read(setup.collector)
    if config.token_environment == config.web_token_environment:
        raise SetupProblem(
            "Use distinct service and dashboard credential environment names."
        )
    if not bind.strip() or bind != bind.strip():
        raise SetupProblem(
            "Configure an explicit dashboard bind address without edge whitespace."
        )
    integer(port, 0 if automatic else 1, 65535)
    if config.schedule == "REPLACE_WITH_SCHEDULE_INTERNAL_NAME":
        raise SetupProblem(
            "Run status to resolve the deployed schedule before starting Python."
        )
    identity = object_fields(
        load_json(setup.output / "identity.json"),
        {"handle", "principal", "tokenId", "expiresAt"},
    )
    principal = text(identity["principal"], 128)
    if principal != "@service/" + text(identity["tokenId"], 36):
        raise SetupProblem(
            "Service identity is inconsistent; inspect provisioning metadata."
        )
    credential = (
        (setup.output / "service-token").read_text(encoding="utf-8").rstrip("\n")
    )
    if not credential.startswith("pss_") or credential != credential.strip():
        raise SetupProblem("Invalid private service credential.")
    web_token = (
        secrets.token_urlsafe(32)
        if automatic
        else os.environ.get(config.web_token_environment)
    )
    if web_token is None:
        web_token = getpass(
            "Dashboard bearer (at least 24 non-whitespace characters): "
        )
    if len(web_token) < 24 or any(c.isspace() for c in web_token):
        raise SetupProblem(
            "Dashboard bearer needs at least 24 non-whitespace characters."
        )
    previous = {
        name: os.environ.get(name)
        for name in (config.token_environment, config.web_token_environment)
    }
    try:
        os.environ[config.token_environment] = credential
        os.environ[config.web_token_environment] = web_token
        await execute(
            argparse.Namespace(
                config=str(setup.collector),
                command="serve",
                tool_provider="privacy-scanner",
                tool_account=principal,
                tool_catalog_renew_seconds=100,
                bind=bind,
                port=port,
                open_browser=automatic,
                dashboard_settings=True,
            )
        )
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def launcher(root: Path, bind: str, port: int) -> Path:
    """Remember explicit listener configuration and make a credential-free launcher."""
    if not ip_address(bind).is_loopback:
        raise SetupProblem(
            "Automatic dashboard login requires a loopback bind IP. Use serve for remote hosting."
        )
    integer(port, 0, 65535)
    replace_private(
        root / "dashboard.json", json.dumps({"bind": bind, "port": port}) + "\n"
    )
    name = (
        "Start Network Privacy Watch.command"
        if sys.platform == "darwin"
        else "start-network-privacy-watch.sh"
    )
    path = root / name
    arguments = [
        sys.executable,
        "-m",
        "plowshare_privacy.bootstrap",
        "--directory",
        str(root),
        "start",
    ]
    replace_private(
        path,
        "#!/bin/sh\nexec " + " ".join(shlex.quote(value) for value in arguments) + "\n",
    )
    path.chmod(0o700)
    return path


async def start(setup: Setup, bind: str, port: int) -> None:
    """Reuse confirmed installation; new/unfinished installs retain existing fences."""
    if not ip_address(bind).is_loopback:
        raise SetupProblem("Choose a loopback bind IP for automatic dashboard login.")
    integer(port, 0, 65535)
    if await reopen(setup.collector.parent / "dashboard-runtime.json"):
        print("Opened the running Network Privacy Watch dashboard.")
        return
    installed = (setup.output / "installation-completed").is_file()
    config = Configuration.read(setup.collector)
    if not installed or config.schedule == "REPLACE_WITH_SCHEDULE_INTERNAL_NAME":
        async with administrator_login(
            config.origin, setup.deployer, getpass("Plowshare administrator password: ")
        ) as bearer:
            async with await Client.connect(
                config.origin, bearer, timeout=15
            ) as client:
                if installed:
                    await wait_for_schedule(setup, client)
                else:
                    row = object_fields(
                        load_json(setup.collector.parent / "destination.json"), {"path"}
                    )
                    await install(setup, client, text(row["path"], 512), guided=True)
    path = launcher(setup.collector.parent, bind, port)
    print("Next time, open", path.name, "in your private setup folder.", flush=True)
    await serve(setup, bind, port, automatic=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--directory",
        type=Path,
        help="Absolute private directory outside Git; not required for discover",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    scan = commands.add_parser(
        "discover", help="Discover responding devices on an explicit private LAN subnet"
    )
    scan.add_argument(
        "--network", required=True, help="Private IPv4 CIDR, at most 256 addresses"
    )
    scan.add_argument(
        "--ports", required=True, help="Comma-separated TCP ports, at most eight"
    )
    scan.add_argument("--timeout", type=float, default=0.5)
    scan.add_argument("--concurrency", type=int, default=32)
    create = commands.add_parser(
        "configure", help="Prompt for private configuration and prepare source offline"
    )
    create.add_argument(
        "--source",
        type=Path,
        required=True,
        help="Absolute network-privacy-watch Application folder",
    )
    commands.add_parser(
        "install",
        help="Log in temporarily, deploy paused, provision service and redeploy",
    )
    commands.add_parser(
        "status",
        help="Read retained server status and fill the local schedule identity",
    )
    launch = commands.add_parser(
        "start", help="Install if needed, then open the authenticated local dashboard"
    )
    launch.add_argument(
        "--source",
        type=Path,
        help="Application source; required only for a new private directory",
    )
    launch.add_argument(
        "--bind", help="Explicit loopback IP; remembered for subsequent starts"
    )
    launch.add_argument(
        "--port",
        type=int,
        help="Listener port; 0 lets the operating system choose a free port",
    )
    run = commands.add_parser(
        "serve", help="Start the separate Python collector with private credentials"
    )
    run.add_argument("--bind", required=True)
    run.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    try:
        if args.command == "discover":
            plan = DiscoveryPlan(
                IPv4Network(args.network, strict=True),
                tuple(int(port.strip()) for port in args.ports.split(",")),
                args.timeout,
                args.concurrency,
            )
            print(json.dumps(asdict(asyncio.run(discover(plan))), indent=2))
            return
        if args.directory is None:
            raise SetupProblem(
                "Set --directory for configure, install, status, start or serve."
            )
        root = private_root(args.directory)
        if args.command == "configure":
            configure(root, args.source)
            return
        if args.command == "start" and not root.exists():
            if args.source is None:
                raise SetupProblem(
                    "For a new setup, supply --source with the Application folder."
                )
            configure(root, args.source, guided=True)
        setup = Setup.read(root / "deployment/setup.json")
        if (
            setup.output != root / "deployment"
            or setup.collector != root / "collector.json"
        ):
            raise SetupProblem("Setup paths do not match this private directory.")
        if args.command == "start":
            saved = (
                object_fields(load_json(root / "dashboard.json"), {"bind", "port"})
                if (root / "dashboard.json").exists()
                else None
            )
            bind = args.bind or (
                text(saved["bind"])
                if saved
                else ask("Local dashboard bind IP (for example 127.0.0.1)")
            )
            port = (
                args.port
                if args.port is not None
                else integer(saved["port"], 0, 65535)
                if saved
                else 0
            )
            asyncio.run(start(setup, bind, port))
            return
        if args.command == "serve":
            text(args.bind)
            integer(args.port, 1, 65535)
            asyncio.run(serve(setup, args.bind, args.port))
            return

        async def connected() -> None:
            async with administrator_login(
                Configuration.read(setup.collector).origin,
                setup.deployer,
                getpass("Deployment administrator password: "),
            ) as bearer:
                async with await Client.connect(
                    Configuration.read(setup.collector).origin, bearer, timeout=15
                ) as client:
                    if args.command == "install":
                        row = object_fields(
                            load_json(root / "destination.json"), {"path"}
                        )
                        await install(setup, client, text(row["path"], 512))
                    else:
                        await inspect(setup, client)

        asyncio.run(connected())
    except BlockingIOError:
        raise SystemExit(
            "Network Privacy Watch is already running. Close its earlier collector terminal, then open the launcher again."
        ) from None
    except SetupProblem as error:
        raise SystemExit(str(error)) from None
    except (
        ValueError,
        OSError,
        Refusal,
        TransportError,
        ClientError,
        TimeoutError,
        ReconciliationRequired,
        ToolAttention,
    ) as error:
        raise SystemExit(
            "Setup stopped ("
            + type(error).__name__
            + "). Read SETUP.md and retained intents before repeating a server mutation. Credentials were not printed."
        ) from None


if __name__ == "__main__":
    main()
