"""Application identity setup consumes public SDK operations; secrets remain in private files.

Recovery reads the original deployment and credential receipts. It never replays an
uncertain mutation or starts a collector, scan, schedule or investigation.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict
from datetime import datetime, timezone
from uuid import uuid4

from plowshare.contracts import (
    ApplicationDeploymentReceiptRequest,
    ApplicationDeploymentStatusRequest,
    ApplicationIdentityStatusRequest,
    ApplicationTokenIssueRequest,
    ApplicationTokenReceiptDto,
    ApplicationTokenReceiptRequest,
    ProjectAccessRequest,
    ProjectMemberAddRequest,
    ProjectMemberRoleRequest,
)

from .bootstrap import SetupProblem
from .contracts import Configuration, load_json, object_fields, text, uuid
from .private_files import replace_private
from .setup import Setup, SetupClient, private_write


def save_metadata(setup: Setup, receipt: ApplicationTokenReceiptDto) -> None:
    """Publish the private runtime views only from a request-linked, durably saved secret."""
    artifact = setup.output / ("credential-" + uuid(receipt.request_id) + ".json")
    secret = object_fields(load_json(artifact), {"requestId", "credential"})
    credential = text(secret["credential"], 128)
    if secret["requestId"] != receipt.request_id or not re.fullmatch(
        r"pss_[0-9a-f]{48}", credential
    ):
        raise SetupProblem("Saved credential is not linked to its confirmed issuance")
    replace_private(setup.output / "service-token", credential + "\n")
    replace_private(
        setup.output / "identity.json",
        json.dumps(
            {
                "handle": setup.service,
                "principal": receipt.principal,
                "tokenId": receipt.token_id,
                "expiresAt": receipt.expires_at,
            },
            indent=2,
        )
        + "\n",
    )


async def manager_grant(setup: Setup, client: SetupClient) -> None:
    config = Configuration.read(setup.collector)
    access = (
        await client.request(ProjectAccessRequest(project=config.project))
    ).require_payload()
    existing = next(
        (row for row in access.members if row.handle == setup.manager), None
    )
    intent = setup.output / "manager-grant-intent.json"
    if existing is not None and existing.role == "MANAGER":
        return
    if intent.exists():
        raise SetupProblem(
            "Manager grant was attempted but is not confirmed. Inspect project access before deliberately changing membership."
        )
    private_write(
        intent,
        json.dumps(
            {"project": config.project, "handle": setup.manager, "role": "MANAGER"}
        )
        + "\n",
    )
    if existing is None:
        (
            await client.request(
                ProjectMemberAddRequest(
                    project=config.project, handle=setup.manager, role="MANAGER"
                )
            )
        ).require_payload()
    else:
        (
            await client.request(
                ProjectMemberRoleRequest(
                    project=config.project, handle=setup.manager, role="MANAGER"
                )
            )
        ).require_payload()
    # Recovery can confirm a lost membership reply by this exact live postcondition.
    confirmed = (
        await client.request(ProjectAccessRequest(project=config.project))
    ).require_payload()
    if not any(
        row.handle == setup.manager and row.role == "MANAGER"
        for row in confirmed.members
    ):
        raise SetupProblem("Human manager grant is not confirmed")


async def issue_credential(
    setup: Setup, client: SetupClient, *, rotate: bool = False
) -> None:
    config = Configuration.read(setup.collector)
    status = (
        await client.request(ApplicationIdentityStatusRequest(project=config.project))
    ).require_payload()
    identity = status.identity
    if (
        status.project != config.project
        or identity is None
        or identity.service.handle != setup.service
        or identity.state != "ACTIVE"
    ):
        raise SetupProblem(
            "Deployed Application does not own the configured service identity"
        )
    if identity.credential_issued != rotate:
        raise SetupProblem(
            "Inspect the retained identity before choosing issuance or rotation"
        )
    request_id = str(uuid4())
    request = ApplicationTokenIssueRequest(
        project=config.project,
        request_id=request_id,
        expires_in_days=setup.expiry_days,
        rotate=rotate,
    )
    intent_name = (
        "credential-intent.json"
        if not rotate
        else "credential-rotate-" + request_id + "-intent.json"
    )
    private_write(
        setup.output / intent_name,
        json.dumps(
            {
                "project": config.project,
                "requestId": request_id,
                "expiresInDays": setup.expiry_days,
                "rotate": rotate,
                "tokenId": identity.token_id,
            },
            indent=2,
        )
        + "\n",
    )
    replace_private(
        setup.output / "credential-current.json",
        json.dumps({"intent": intent_name}) + "\n",
    )
    issued = (await client.request(request)).require_payload()
    receipt = issued.receipt
    if (
        receipt.project != config.project
        or receipt.request_id != request_id
        or receipt.token_id != identity.token_id
        or receipt.principal != identity.principal
    ):
        raise SetupProblem("Foreign Application credential receipt")
    # A single exclusive, flushed artifact links the secret to the mutation UUID. A crash while
    # updating the legacy runtime files can be recovered without issuing or rotating again.
    private_write(
        setup.output / ("credential-" + request_id + ".json"),
        json.dumps({"requestId": request_id, "credential": issued.credential}) + "\n",
    )
    private_write(
        setup.output / ("credential-" + request_id + "-receipt.json"),
        json.dumps(asdict(receipt), indent=2) + "\n",
    )
    save_metadata(setup, receipt)


async def provision_owned(setup: Setup, client: SetupClient) -> None:
    await manager_grant(setup, client)
    await issue_credential(setup, client)


async def recover_installation(
    setup: Setup, client: SetupClient, *, rotate: bool = False
) -> None:
    from .bootstrap import wait_for_schedule

    config = Configuration.read(setup.collector)
    manifest = object_fields(
        load_json(setup.output / "application/plowshare.json"),
        {"version", "name", "access", "caps", "commands"},
        {"executionAccount", "toolScopes", "toolGrants", "service"},
    )
    if manifest.get("service") != {"handle": setup.service, "role": "CONTRIBUTOR"}:
        raise SetupProblem(
            "This recovery command requires an Application-owned service declaration. Keep manual identities and follow their existing receipt guide."
        )
    intent = object_fields(
        load_json(setup.output / "bootstrap-intent.json"),
        {"project", "requestId", "expectedRevision", "destination"},
    )
    if intent["project"] != config.project:
        raise SetupProblem("Foreign installation intent")
    receipt = (
        await client.request(
            ApplicationDeploymentReceiptRequest(
                project=config.project, request_id=uuid(intent["requestId"])
            )
        )
    ).require_payload()
    status = (
        await client.request(ApplicationDeploymentStatusRequest(project=config.project))
    ).require_payload()
    if (
        receipt.project != config.project
        or receipt.request_id != intent["requestId"]
        or status.active_revision != receipt.release.revision
    ):
        raise SetupProblem(
            "The attempted installation is not the active retained deployment; inspect status"
        )
    if not (setup.output / "bootstrap-receipt.json").exists():
        private_write(
            setup.output / "bootstrap-receipt.json",
            json.dumps(asdict(receipt), indent=2) + "\n",
        )
    await manager_grant(setup, client)
    current = setup.output / "credential-current.json"
    if current.exists():
        pointer = object_fields(load_json(current), {"intent"})
        name = text(pointer["intent"], 128)
        if not re.fullmatch(r"credential(?:-rotate-[0-9a-f-]{36})?-intent\.json", name):
            raise SetupProblem("Invalid credential intent selection")
        issuance = object_fields(
            load_json(setup.output / name),
            {"project", "requestId", "expiresInDays", "rotate", "tokenId"},
        )
        original = (
            await client.request(
                ApplicationTokenReceiptRequest(
                    project=config.project, request_id=uuid(issuance["requestId"])
                )
            )
        ).require_payload()
        if (
            original.project != config.project
            or original.request_id != issuance["requestId"]
            or original.token_id != issuance["tokenId"]
        ):
            raise SetupProblem("Foreign credential receipt")
        artifact = setup.output / ("credential-" + original.request_id + ".json")
        retained = (
            (
                await client.request(
                    ApplicationIdentityStatusRequest(project=config.project)
                )
            )
            .require_payload()
            .identity
        )
        if (
            retained is None
            or retained.token_id != original.token_id
            or retained.state != "ACTIVE"
        ):
            raise SetupProblem(
                "Retained credential no longer belongs to this Application"
            )
        if (
            retained.credential_request_id != original.request_id
            or retained.revoked_at is not None
            or datetime.fromisoformat(retained.expires_at.replace("Z", "+00:00"))
            <= datetime.now(timezone.utc)
        ):
            if not rotate:
                raise SetupProblem(
                    "This saved secret was superseded, revoked or expired. Inspect identity; use recover --rotate for an explicit new rotation of the retained token."
                )
            await issue_credential(setup, client, rotate=True)
        elif artifact.exists():
            save_metadata(setup, original)
        elif rotate:
            # A reviewed new request rotates the same retained token after confirmed lost delivery.
            await issue_credential(setup, client, rotate=True)
        else:
            raise SetupProblem(
                "Credential issuance is confirmed but its secret was not saved. Run recover --rotate to explicitly rotate this same token; the original request will not be replayed."
            )
    elif (setup.output / "credential-intent.json").exists():
        # Crash before recording the pointer means this request may never have been sent, but
        # absence is not a delivery proof. Select its original receipt explicitly before action.
        replace_private(
            current, json.dumps({"intent": "credential-intent.json"}) + "\n"
        )
        await recover_installation(setup, client, rotate=rotate)
        return
    else:
        await issue_credential(setup, client)
    completed = setup.output / "installation-completed"
    if not completed.exists():
        private_write(
            completed, "Retained deployment and credential issuance reconciled.\n"
        )
    await wait_for_schedule(setup, client)
    print(
        "Installation recovered. The schedule remains paused. Run start to open the dashboard."
    )
