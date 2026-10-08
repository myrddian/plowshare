"""Prepare private application source, then provision its separate execution identity.

Preparation is offline. Provisioning uses public typed SDK operations once each.
A retained intent blocks reruns after a lost reply; operators must reconcile the
account, grants and token metadata rather than issuing another credential blindly.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
from contextlib import asynccontextmanager
from dataclasses import dataclass
from getpass import getpass
from pathlib import Path
from typing import AsyncIterator, Literal, Protocol, TypeVar
from uuid import UUID

from aiohttp import ClientError, ClientSession, ClientTimeout, DummyCookieJar
from plowshare import Client, Refusal, Reply, TransportError
from plowshare.contracts import (
    AdminAccountsRequest,
    AdminServiceAccountCreateRequest,
    AdminServiceAccountsRequest,
    AdminServiceTokenCreatePayloadDtoScopesItemDto,
    AdminServiceTokenCreateRequest,
    AdminServiceTokensRequest,
    AdminStatusRequest,
    ProjectAccessRequest,
    ProjectMemberAddRequest,
    ProjectMemberRoleRequest,
    Request,
)

from .contracts import (
    Configuration,
    integer,
    load_json,
    object_fields,
    parse_json,
    text,
)

T = TypeVar("T")


class SetupClient(Protocol):
    async def request(self, request: Request[T]) -> Reply[T]: ...


@asynccontextmanager
async def administrator_login(
    origin: str, handle: str, password: str
) -> AsyncIterator[str]:
    """Login is the explicit public HTTP boundary; application work uses the SDK.

    This setup-only session does not read or rotate desktop/CLI credentials.
    There are no redirects, retries, refreshes or saved human credentials.
    Logout revokes the temporary session even when SDK provisioning fails.
    """
    async with ClientSession(
        timeout=ClientTimeout(total=15), cookie_jar=DummyCookieJar()
    ) as http:
        async with http.post(
            origin.rstrip("/") + "/v1/auth/login",
            json={"handle": handle, "password": password},
            headers={"X-Plowshare-Token-Delivery": "body"},
            allow_redirects=False,
        ) as response:
            if response.status != 200:
                raise ValueError("Administrator login refused")
            content = bytearray()
            async for chunk in response.content.iter_chunked(16384):
                content.extend(chunk)
                if len(content) > 65536:
                    raise ValueError("Login response exceeds size limit")
            row = object_fields(
                parse_json(bytes(content)), {"access", "refresh", "mustChangePassword"}
            )
            token = text(row["access"], 8192)
            text(row["refresh"], 8192)  # Validate, then discard; setup never refreshes.
        try:
            if row["mustChangePassword"] is not False:
                raise ValueError("Complete the human password change in the CLI first")
            yield token
        finally:
            async with http.post(
                origin.rstrip("/") + "/v1/auth/logout",
                headers={"Authorization": "Bearer " + token},
                allow_redirects=False,
            ) as response:
                if response.status != 204:
                    raise ValueError(
                        "Setup logout was not confirmed; inspect administrator sessions"
                    )


@dataclass(frozen=True)
class Setup:
    collector: Path
    source: Path
    output: Path
    service: str
    manager: str
    deployer: str
    token_name: str
    expiry_days: int

    @classmethod
    def read(cls, path: Path, *, prompt: bool = False) -> Setup:
        row = object_fields(
            load_json(path),
            {
                "collectorConfig",
                "applicationSource",
                "outputDirectory",
                "serviceAccount",
                "managerAccount",
                "deploymentAccount",
                "tokenName",
                "expiresInDays",
            },
        )
        for key, value in row.items():
            if prompt and value == "":
                row[key] = input(f"{key}: ")
        names = tuple(
            text(row[key], 128)
            for key in (
                "serviceAccount",
                "managerAccount",
                "deploymentAccount",
                "tokenName",
            )
        )
        if any(name != name.strip() for name in names):
            raise ValueError(
                "Account handles and token name cannot have edge whitespace"
            )
        if names[0] in names[1:3] or names[0].startswith("@service/"):
            raise ValueError("Choose a separate service account handle")
        paths = tuple(
            Path(text(row[key], 4096))
            for key in ("collectorConfig", "applicationSource", "outputDirectory")
        )
        if any(not p.is_absolute() or p.resolve() != p for p in paths):
            raise ValueError("Setup paths must be absolute and unlinked")
        if paths[2] == paths[1] or paths[1] in paths[2].parents:
            raise ValueError("Private output must be outside application source")
        return cls(
            paths[0],
            paths[1],
            paths[2],
            names[0],
            names[1],
            names[2],
            names[3],
            integer(row["expiresInDays"], 1, 365),
        )


def private_write(path: Path, content: str) -> None:
    """Exclusive creation keeps reruns from overwriting credentials or receipts."""
    with open(
        path, "x", encoding="utf-8", opener=lambda p, flags: os.open(p, flags, 0o600)
    ) as target:
        target.write(content)
        target.flush()
        os.fsync(target.fileno())
    if os.name == "posix":
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


def prepare(setup: Setup) -> None:
    config = Configuration.read(setup.collector)
    # Read every source file before making output; linked files/directories are
    # never copied, and output cannot accidentally become tracked package source.
    entries = tuple(setup.source.rglob("*"))
    if not entries or any(
        p.is_symlink() or not (p.is_file() or p.is_dir()) for p in entries
    ):
        raise ValueError(
            "Application source must contain only unlinked files/directories"
        )
    manifest = object_fields(
        load_json(setup.source / "plowshare.json"),
        {"version", "name", "access", "caps", "commands"},
        {"executionAccount", "toolScopes", "toolGrants"},
    )
    if manifest["name"] != config.project or manifest["version"] != 1:
        raise ValueError("Collector and application project must match")
    if setup.output.exists():
        raise ValueError(
            "Choose a new private output directory; existing output is preserved"
        )
    setup.output.mkdir(mode=0o700)
    shutil.copytree(setup.source, setup.output / "application")
    grants = {
        setup.service: "CONTRIBUTOR",
        setup.manager: "MANAGER",
        setup.deployer: "MANAGER",
    }
    manifest["access"] = {
        "accounts": [
            {"handle": handle, "role": role} for handle, role in grants.items()
        ]
    }
    (setup.output / "application/plowshare.json").write_text(
        json.dumps(manifest, indent=2) + "\n"
    )
    bind_provider(setup, setup.service)
    private_write(
        setup.output / "setup.json",
        json.dumps(
            {
                "collectorConfig": str(setup.collector),
                "applicationSource": str(setup.source),
                "outputDirectory": str(setup.output),
                "serviceAccount": setup.service,
                "managerAccount": setup.manager,
                "deploymentAccount": setup.deployer,
                "tokenName": setup.token_name,
                "expiresInDays": setup.expiry_days,
            },
            indent=2,
        )
        + "\n",
    )


def bind_provider(setup: Setup, account: str) -> None:
    manifest_path = setup.output / "application/plowshare.json"
    manifest = object_fields(
        load_json(manifest_path),
        {"version", "name", "access", "caps", "commands"},
        {"executionAccount", "toolScopes", "toolGrants"},
    )
    manifest["executionAccount"] = account
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    for name in ("tools", "ports"):
        path = setup.output / f"application/server/{name}.json"
        row = object_fields(load_json(path), {"version", "bindings"}, {"providers"})
        for key in ("bindings", "providers"):
            if key not in row:
                continue
            values = row[key]
            if not isinstance(values, list):
                raise ValueError("Invalid application authority array")
            for value in values:
                if not isinstance(value, dict) or "account" not in value:
                    raise ValueError("Invalid application account declaration")
                value["account"] = account
        path.write_text(json.dumps(row, indent=2) + "\n")


async def provision(setup: Setup, client: SetupClient) -> None:
    status = (await client.request(AdminStatusRequest())).require_payload()
    if not status.server_admin or status.handle != setup.deployer:
        raise ValueError("Connect as the configured human deployment administrator")
    humans = (await client.request(AdminAccountsRequest())).require_payload()
    if not any(a.handle == setup.manager and a.enabled for a in humans):
        raise ValueError("Create/enable the human manager account before provisioning")
    config = Configuration.read(setup.collector)
    # This read also proves first deployment has created the project. Do not use
    # project.create here: its provisioned workspace prevents first source deploy.
    access = (
        await client.request(ProjectAccessRequest(project=config.project))
    ).require_payload()
    accounts = (await client.request(AdminServiceAccountsRequest())).require_payload()
    existing = next((a for a in accounts if a.handle == setup.service), None)
    if existing is not None and not existing.enabled:
        raise ValueError("Service account is disabled; inspect it before continuing")
    if existing is not None:
        tokens = (
            await client.request(AdminServiceTokensRequest(handle=setup.service))
        ).require_payload()
        if any(t.name == setup.token_name for t in tokens):
            raise ValueError(
                "Named token already exists; reconcile/rotate it, never issue a duplicate"
            )
    # Written and flushed before any mutation. A partial failure is deliberately
    # not resumable without explicit operator reconciliation (including lost secret).
    private_write(
        setup.output / "provisioning-intent.json",
        json.dumps(
            {
                "project": config.project,
                "serviceAccount": setup.service,
                "managerAccount": setup.manager,
                "tokenName": setup.token_name,
            }
        )
        + "\n",
    )
    if existing is None:
        created = (
            await client.request(AdminServiceAccountCreateRequest(handle=setup.service))
        ).require_payload()
        if created.handle != setup.service or not created.enabled:
            raise ValueError("Created service account does not match setup")
    grants: tuple[tuple[str, Literal["MANAGER", "CONTRIBUTOR"]], ...] = (
        (setup.manager, "MANAGER"),
        (setup.service, "CONTRIBUTOR"),
    )
    for handle, role in grants:
        grant = next((g for g in access.members if g.handle == handle), None)
        if grant is None:
            (
                await client.request(
                    ProjectMemberAddRequest(
                        project=config.project, handle=handle, role=role
                    )
                )
            ).require_payload()
        elif grant.role != role:
            (
                await client.request(
                    ProjectMemberRoleRequest(
                        project=config.project, handle=handle, role=role
                    )
                )
            ).require_payload()
    issued = (
        await client.request(
            AdminServiceTokenCreateRequest(
                handle=setup.service,
                name=setup.token_name,
                expires_in_days=setup.expiry_days,
                scopes=(
                    AdminServiceTokenCreatePayloadDtoScopesItemDto(
                        project=config.project, role="CONTRIBUTOR"
                    ),
                ),
            )
        )
    ).require_payload()
    if (
        issued.token.principal != "@service/" + str(UUID(issued.token.id))
        or not issued.credential.startswith("pss_")
        or issued.token.name != setup.token_name
        or len(issued.token.scopes) != 1
        or issued.token.scopes[0].project != config.project
        or issued.token.scopes[0].role != "CONTRIBUTOR"
    ):
        raise ValueError("Invalid service execution identity")
    private_write(setup.output / "service-token", issued.credential + "\n")
    private_write(
        setup.output / "identity.json",
        json.dumps(
            {
                "handle": setup.service,
                "principal": issued.token.principal,
                "tokenId": issued.token.id,
                "expiresAt": issued.token.expires_at,
            },
            indent=2,
        )
        + "\n",
    )
    bind_provider(setup, issued.token.principal)
    private_write(
        setup.output / "provisioning-completed",
        "Redeploy the prepared application before starting Python.\n",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument(
        "--login",
        action="store_true",
        help="Prompt for the deployment administrator's password in a temporary session",
    )
    parser.add_argument("command", choices=("prepare", "provision"))
    args = parser.parse_args()
    try:
        setup = Setup.read(args.config, prompt=args.command == "prepare")
        if args.command == "prepare":
            prepare(setup)
            print(
                "Private source prepared. Deploy it paused, then provision using its saved setup.json."
            )
        else:
            if (setup.output / "provisioning-intent.json").exists():
                raise ValueError(
                    "Provisioning was already attempted; inspect retained identity and reconcile"
                )
            config = Configuration.read(setup.collector)
            token = os.environ.get("PLOWSHARE_SETUP_TOKEN", "")
            if not args.login and (
                not token or token != token.strip() or token.startswith("pss_")
            ):
                raise ValueError(
                    "Set a human administrator bearer in PLOWSHARE_SETUP_TOKEN"
                )

            async def connected() -> None:
                async def using(bearer: str) -> None:
                    async with await Client.connect(
                        config.origin, bearer, timeout=15
                    ) as client:
                        await provision(setup, client)

                if args.login:
                    async with administrator_login(
                        config.origin,
                        setup.deployer,
                        getpass("Deployment administrator password: "),
                    ) as bearer:
                        await using(bearer)
                else:
                    await using(token)

            asyncio.run(connected())
            print(
                "Service credential saved privately; human has application MANAGER access. Redeploy the prepared source."
            )
    except (
        ValueError,
        OSError,
        Refusal,
        TransportError,
        ClientError,
        TimeoutError,
    ) as error:
        raise SystemExit(
            "Setup stopped ("
            + type(error).__name__
            + "). Inspect private setup receipts and the recovery guide; credentials were not printed."
        ) from None


if __name__ == "__main__":
    main()
