"""Review/deploy an Application-owned schedule through public administrator APIs.

Each draft pins every source byte and the active revision. A retained intent blocks
re-entry even when a reply was lost. Passwords remain in memory for a temporary
login; neither service-account privileges nor immutable schedule ownership change.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from plowshare import Client
from plowshare.contracts import (
    AdminStatusRequest,
    ApplicationDeploymentReceiptRequest,
    ApplicationDeploymentStatusRequest,
    ApplicationDeployRequest,
    ApplicationSourceFileDto,
    FilestoreListRequest,
    FileStoreReferenceDto,
)

from .contracts import Configuration, integer, load_json, object_fields, text, uuid
from .monitor import SettingsBusy
from .private_files import replace_private
from .setup import Setup, administrator_login, private_write

PRESETS = {"15m": "0 */15 * * * *", "hourly": "0 0 * * * *", "daily": "0 0 9 * * *"}


@dataclass(frozen=True)
class ScheduleReview:
    id: str
    expected_revision: str
    cron: str
    zone: str
    paused: bool
    stores: tuple[str, ...]
    source_digest: str
    sources: tuple[ApplicationSourceFileDto, ...]


@dataclass(frozen=True)
class Draft:
    review: ScheduleReview
    files: tuple[ApplicationSourceFileDto, ...]


@dataclass(frozen=True)
class RetainedRelease:
    project: str
    revision: str
    digest: str
    file_count: int

    @classmethod
    def read(cls, path: Path) -> RetainedRelease:
        if path.is_symlink() or path.resolve() != path:
            raise ValueError("Retained release must be unlinked")
        row = object_fields(load_json(path), {"project", "request_id", "release"})
        uuid(row["request_id"])
        release = object_fields(row["release"], {"revision", "digest", "file_count"})
        digest = text(release["digest"], 64)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Invalid retained source digest")
        return cls(
            text(row["project"], 128),
            uuid(release["revision"]),
            digest,
            integer(release["file_count"], 1, 128),
        )


def source_text(value: object) -> str:
    # Application source is multiline UTF-8, unlike scalar configuration text.
    if (
        not isinstance(value, str)
        or len(value.encode("utf-8")) > 65536
        or "\0" in value
    ):
        raise ValueError("Invalid retained Application source text")
    return value


class ScheduleManagement(Protocol):
    def pending_request(self) -> str | None: ...
    async def reconcile(self, password: str) -> str | None: ...
    async def review(
        self, preset: str, zone: str, paused: bool, password: str
    ) -> ScheduleReview: ...
    async def deploy(self, review_id: str, store: str, password: str) -> str: ...


class ScheduleEditor:
    def __init__(self, root: Path):
        self.root = root
        self.setup = Setup.read(root / "deployment/setup.json")
        if (
            self.setup.output != root / "deployment"
            or self.setup.collector != root / "collector.json"
        ):
            raise ValueError("Deployment setup does not belong to this collector")
        self.lock = asyncio.Lock()
        self.draft: Draft | None = None
        self.pending = root / "deployment/schedule-update-intent.json"
        self.receipt = root / "deployment/schedule-update-receipt.json"

    async def review(
        self, preset: str, zone: str, paused: bool, password: str
    ) -> ScheduleReview:
        from .bootstrap import application_files

        if preset not in PRESETS or type(paused) is not bool:
            raise ValueError("Choose a listed schedule frequency")
        try:
            ZoneInfo(text(zone, 128))
        except ZoneInfoNotFoundError:
            raise ValueError("Choose an available IANA time zone") from None
        async with self.lock:
            if self.pending.exists():
                raise SettingsBusy(
                    "A schedule deployment intent is retained. Inspect its receipt before preparing another revision."
                )
            config = Configuration.read(self.setup.collector)
            async with administrator_login(
                config.origin, self.setup.deployer, password
            ) as bearer:
                async with await Client.connect(
                    config.origin, bearer, timeout=15
                ) as client:
                    administrator = (
                        await client.request(AdminStatusRequest())
                    ).require_payload()
                    if (
                        not administrator.server_admin
                        or administrator.handle != self.setup.deployer
                    ):
                        raise ValueError("Use the configured deployment administrator")
                    status = (
                        await client.request(
                            ApplicationDeploymentStatusRequest(project=config.project)
                        )
                    ).require_payload()
                    stores = (
                        await client.request(FilestoreListRequest())
                    ).require_payload()
            if status.project != config.project or status.active_revision is None:
                raise ValueError("No active Application revision")
            known = (
                self.receipt
                if self.receipt.exists()
                else self.root / "deployment/identity-receipt.json"
            )
            previous = RetainedRelease.read(known)
            if (
                previous.project != config.project
                or previous.revision != status.active_revision
            ):
                raise SettingsBusy(
                    "Application changed outside this setup. Review and synchronize its prepared source before deploying."
                )
            files = application_files(self.setup.output / "application")
            selected = next(
                (item for item in files if item.path == "schedules/network_scan.json"),
                None,
            )
            if selected is None:
                raise SettingsBusy("Prepared Application has no network scan schedule")
            row = object_fields(
                json.loads(selected.text),
                {"version", "cron", "zone", "paused", "action", "target", "limits"},
            )
            row.update(cron=PRESETS[preset], zone=zone, paused=paused)
            prepared = tuple(
                ApplicationSourceFileDto(
                    path=item.path, text=json.dumps(row, indent=2) + "\n"
                )
                if item.path == selected.path
                else item
                for item in files
            )
            digest = hashlib.sha256(
                json.dumps([asdict(item) for item in prepared]).encode()
            ).hexdigest()
            result = ScheduleReview(
                str(uuid4()),
                uuid(status.active_revision),
                PRESETS[preset],
                zone,
                paused,
                tuple(
                    sorted(
                        item.alias for item in stores.stores if item.role == "MANAGER"
                    )
                ),
                digest,
                prepared,
            )
            if not result.stores:
                raise ValueError("Administrator has no managed FileStore destination")
            self.draft = Draft(result, prepared)
            return result

    async def deploy(self, review_id: str, store: str, password: str) -> str:
        from .bootstrap import application_files

        async with self.lock:
            draft = self.draft
            if (
                draft is None
                or draft.review.id != review_id
                or store not in draft.review.stores
                or self.pending.exists()
            ):
                raise ValueError(
                    "Review a fresh schedule draft and choose a listed FileStore"
                )
            # Refuse externally edited prepared source; the reviewed schedule is the
            # only permitted difference. A new process requires a fresh review.
            existing = application_files(self.setup.output / "application")
            baseline = tuple(
                item
                for item in draft.files
                if item.path != "schedules/network_scan.json"
            )
            if (
                tuple(
                    item
                    for item in existing
                    if item.path != "schedules/network_scan.json"
                )
                != baseline
            ):
                raise ValueError("Prepared Application source changed since review")
            config = Configuration.read(self.setup.collector)
            destination = object_fields(
                load_json(self.root / "destination.json"), {"path"}
            )
            request = ApplicationDeployRequest(
                project=config.project,
                request_id=draft.review.id,
                expected_revision=draft.review.expected_revision,
                destination=FileStoreReferenceDto(
                    store=store, path=text(destination["path"], 512)
                ),
                writable_areas=(),
                files=draft.files,
            )
            async with administrator_login(
                config.origin, self.setup.deployer, password
            ) as bearer:
                async with await Client.connect(
                    config.origin, bearer, timeout=15
                ) as client:
                    private_write(
                        self.pending,
                        json.dumps(
                            {
                                "request": asdict(request),
                                "sourceDigest": draft.review.source_digest,
                            }
                        )
                        + "\n",
                    )
                    result = (await client.request(request)).require_payload()
                    if (
                        result.project != config.project
                        or result.request_id != request.request_id
                    ):
                        raise ValueError("Foreign deployment receipt")
                    replace_private(
                        self.receipt, json.dumps(asdict(result), indent=2) + "\n"
                    )
                    private_write(
                        self.root
                        / "deployment"
                        / ("schedule-" + review_id + "-receipt.json"),
                        json.dumps(asdict(result)) + "\n",
                    )
                    selected = next(
                        item
                        for item in draft.files
                        if item.path == "schedules/network_scan.json"
                    )
                    replace_private(
                        self.setup.output / "application/schedules/network_scan.json",
                        selected.text,
                    )
                    # Archive the intent only after the server receipt and local
                    # prepared source are durable. Unknown delivery keeps the fence.
                    self.pending.rename(
                        self.root
                        / "deployment"
                        / ("schedule-" + review_id + "-intent.json")
                    )
                    self.draft = None
                    return result.release.revision

    def pending_request(self) -> str | None:
        if not self.pending.exists():
            return None
        row = object_fields(load_json(self.pending), {"request", "sourceDigest"})
        request = object_fields(
            row["request"],
            {
                "project",
                "request_id",
                "expected_revision",
                "destination",
                "writable_areas",
                "files",
            },
        )
        return uuid(request["request_id"])

    async def reconcile(self, password: str) -> str | None:
        from .bootstrap import application_files
        from .contracts import items

        async with self.lock:
            identity = self.pending_request()
            if identity is None:
                return None
            config = Configuration.read(self.setup.collector)
            row = object_fields(load_json(self.pending), {"request", "sourceDigest"})
            request = object_fields(
                row["request"],
                {
                    "project",
                    "request_id",
                    "expected_revision",
                    "destination",
                    "writable_areas",
                    "files",
                },
            )
            if request["project"] != config.project:
                raise ValueError("Foreign deployment intent")
            files = []
            for value in items(request["files"], 128):
                file = object_fields(value, {"path", "text"})
                files.append(
                    ApplicationSourceFileDto(
                        path=text(file["path"], 512), text=source_text(file["text"])
                    )
                )
            if sum(
                len(item.text.encode("utf-8")) for item in files
            ) > 131072 or hashlib.sha256(
                json.dumps([asdict(item) for item in files]).encode()
            ).hexdigest() != text(row["sourceDigest"], 64):
                raise ValueError(
                    "Retained deployment source no longer matches its reviewed digest"
                )
            selected = next(
                (item for item in files if item.path == "schedules/network_scan.json"),
                None,
            )
            if selected is None or len({item.path for item in files}) != len(files):
                raise ValueError("Incomplete deployment intent")
            existing = application_files(self.setup.output / "application")
            if tuple(item for item in existing if item.path != selected.path) != tuple(
                item for item in files if item.path != selected.path
            ):
                raise SettingsBusy(
                    "Prepared source changed while deployment was unsettled; inspect retained files first"
                )
            async with administrator_login(
                config.origin, self.setup.deployer, password
            ) as bearer:
                async with await Client.connect(
                    config.origin, bearer, timeout=15
                ) as client:
                    result = (
                        await client.request(
                            ApplicationDeploymentReceiptRequest(
                                project=config.project, request_id=identity
                            )
                        )
                    ).require_payload()
                    status = (
                        await client.request(
                            ApplicationDeploymentStatusRequest(project=config.project)
                        )
                    ).require_payload()
            if (
                result.project != config.project
                or result.request_id != identity
                or result.release.file_count != len(files)
                or status.project != config.project
                or status.active_revision != result.release.revision
            ):
                raise SettingsBusy(
                    "Receipt does not establish this intent as the active revision; no mutation was replayed"
                )
            replace_private(self.receipt, json.dumps(asdict(result), indent=2) + "\n")
            private_write(
                self.root / "deployment" / ("schedule-" + identity + "-receipt.json"),
                json.dumps(asdict(result)) + "\n",
            )
            replace_private(
                self.setup.output / "application/schedules/network_scan.json",
                selected.text,
            )
            self.pending.rename(
                self.root / "deployment" / ("schedule-" + identity + "-intent.json")
            )
            self.draft = None
            return result.release.revision
