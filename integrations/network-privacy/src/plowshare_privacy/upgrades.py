"""Reviewable source and wheel upgrades with retained request identity and private backups.

The external collector must be stopped. A plan snapshots its configured Application,
preserves identity/authority and local additions, and records source conflicts for
review. Apply submits once; recover reads its receipt. Neither starts runtime work.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, Mapping
from uuid import uuid4
from zipfile import ZipFile

from plowshare.contracts import (
    ApplicationDeploymentReceiptRequest,
    ApplicationDeploymentStatusRequest,
    ApplicationDeployRequest,
    FileStoreReferenceDto,
)

from .bootstrap import SetupProblem
from .contracts import Configuration, load_json, object_fields, text, uuid
from .private_files import replace_private
from .setup import Setup, SetupClient, prepare, private_write


def hashes(source: Path) -> dict[str, str]:
    from .bootstrap import application_files

    return {
        file.path: hashlib.sha256(file.text.encode()).hexdigest()
        for file in application_files(source)
    }


def stopped(setup: Setup) -> None:
    if (setup.collector.parent / "dashboard-runtime.json").exists():
        raise SetupProblem("Stop the collector before preparing or applying an upgrade")
    if not (setup.output / "installation-completed").is_file():
        raise SetupProblem("Recover installation before upgrading")


@dataclass(frozen=True)
class Wheel:
    path: Path
    digest: str
    name: str

    @classmethod
    def inspect(cls, path: Path, expected: str) -> Wheel:
        if (
            not path.is_absolute()
            or path.resolve() != path
            or not path.is_file()
            or path.is_symlink()
            or path.suffix != ".whl"
            or path.stat().st_size > 64 * 1024 * 1024
        ):
            raise SetupProblem(
                "Choose an absolute, unlinked wheel file of at most 64 MiB"
            )
        with ZipFile(path) as wheel:
            if sum(
                entry.file_size for entry in wheel.infolist()
            ) > 128 * 1024 * 1024 or any(
                entry.filename.startswith("/")
                or "\\" in entry.filename
                or ".." in entry.filename.split("/")
                or ".data/" in entry.filename
                or (entry.external_attr >> 16) & 0o170000 == 0o120000
                for entry in wheel.infolist()
            ):
                raise SetupProblem(
                    "Wheel entries must be bounded, unlinked package files"
                )
            metadata = [
                name
                for name in wheel.namelist()
                if name.endswith(".dist-info/METADATA")
            ]
            if len(metadata) != 1 or wheel.getinfo(metadata[0]).file_size > 65536:
                raise SetupProblem("Wheel metadata is invalid")
            content = wheel.read(metadata[0]).decode("utf-8")
            if not re.search(
                r"^Name: " + re.escape(expected) + r"$", content, re.MULTILINE
            ):
                raise SetupProblem("Wheel is not the expected collector or SDK package")
        return cls(path, hashlib.sha256(path.read_bytes()).hexdigest(), expected)


def model_selection(source: Path) -> str:
    content = (source / "agents/privacy_coordinator.md").read_text()
    matches: list[str] = re.findall(
        r"^model: ([A-Za-z0-9_.-]+)$", content, re.MULTILINE
    )
    if len(matches) != 1:
        raise SetupProblem("Review the coordinator model selection before upgrading")
    return matches[0]


def tailor(candidate: Path, selected: str) -> None:
    for path in (
        *candidate.glob("agents/*.md"),
        *candidate.glob("orchestrations/*.md"),
    ):
        content = path.read_text()
        if content.count("\nmodel: reasoning\n") != 1:
            raise SetupProblem("Upgrade agent template model declaration changed")
        path.write_text(
            content.replace("\nmodel: reasoning\n", "\nmodel: " + selected + "\n")
        )
    for path in candidate.glob("orchestrations/*.js"):
        content = path.read_text()
        if path.name == "privacy_tick.js":
            if "'native-only': true" not in content or "model:" in content:
                raise SetupProblem("Upgrade tick must remain native-only")
        else:
            if content.count("model: 'reasoning'") != 1:
                raise SetupProblem("Upgrade inference script model declaration changed")
            path.write_text(
                content.replace("model: 'reasoning'", "model: " + json.dumps(selected))
            )


async def prepare_upgrade(
    setup: Setup,
    client: SetupClient,
    source: Path,
    *,
    collector_wheel: Path | None = None,
    sdk_wheel: Path | None = None,
) -> Path:
    stopped(setup)
    pointer = setup.collector.parent / "upgrade-current.json"
    if pointer.exists():
        previous_stage, _ = selected_plan(setup)
        if not (previous_stage / "completed").is_file():
            raise SetupProblem(
                "A reviewed or pending upgrade already exists. Finish it with --apply or --recover before preparing another."
            )
    config = Configuration.read(setup.collector)
    active = (
        await client.request(ApplicationDeploymentStatusRequest(project=config.project))
    ).require_payload()
    if active.project != config.project or active.active_revision is None:
        raise SetupProblem("Application has no active deployment to upgrade")
    if (collector_wheel is None) != (sdk_wheel is None):
        raise SetupProblem(
            "Upgrade the collector and SDK from matching reviewed wheels together"
        )
    wheels = (
        []
        if collector_wheel is None or sdk_wheel is None
        else [
            Wheel.inspect(sdk_wheel, "plowshare-sdk"),
            Wheel.inspect(collector_wheel, "plowshare-network-privacy"),
        ]
    )
    if wheels and (
        sys.prefix == sys.base_prefix or Path(sys.prefix) in setup.collector.parents
    ):
        raise SetupProblem(
            "Wheel upgrades need a dedicated virtual environment outside private runtime data"
        )
    intent = object_fields(
        load_json(setup.output / "bootstrap-intent.json"),
        {"project", "requestId", "expectedRevision", "destination"},
    )
    if intent["project"] != config.project:
        raise SetupProblem("Foreign deployment destination intent")
    destination = object_fields(intent["destination"], {"store", "path"})
    # Confirm the original admission rather than borrowing a guessed storage alias or destination.
    original = (
        await client.request(
            ApplicationDeploymentReceiptRequest(
                project=config.project, request_id=uuid(intent["requestId"])
            )
        )
    ).require_payload()
    if original.project != config.project or original.request_id != intent["requestId"]:
        raise SetupProblem("Foreign original deployment receipt")
    identifier = str(uuid4())
    stage = setup.collector.parent / "upgrades" / identifier
    stage.mkdir(parents=True, mode=0o700)
    (stage / "backup").mkdir(mode=0o700)
    current_source = setup.output / "application"
    previous = hashes(current_source)
    shutil.copytree(current_source, stage / "backup/application")
    shutil.copy2(setup.collector, stage / "backup/collector.json")
    manifest = object_fields(
        load_json(current_source / "plowshare.json"),
        {"version", "name", "access", "caps", "commands"},
        {"executionAccount", "service", "toolScopes", "toolGrants"},
    )
    candidate_setup = Setup(
        setup.collector,
        source,
        stage / "prepared",
        setup.service,
        setup.manager,
        setup.deployer,
        setup.token_name,
        setup.expiry_days,
    )
    prepare(candidate_setup, owned="service" in manifest)
    candidate = candidate_setup.output / "application"
    tailor(candidate, model_selection(current_source))
    # A package update does not automatically acquire new grants or change its execution identity.
    (candidate / "plowshare.json").write_text(json.dumps(manifest, indent=2) + "\n")
    proposed = hashes(candidate)
    baseline_path = setup.output / "package-baseline.json"
    baseline = (
        object_fields(load_json(baseline_path), {"files"})["files"]
        if baseline_path.exists()
        else None
    )
    if baseline is not None and not isinstance(baseline, dict):
        raise SetupProblem("Invalid installed source baseline")
    conflicts: list[str] = []
    for name, digest in previous.items():
        if name not in proposed:
            # Local additions remain part of the reviewed source, including optional integrations.
            target = candidate / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(current_source / name, target)
        elif (
            name != "plowshare.json"
            and baseline is not None
            and baseline.get(name) != digest
            and proposed[name] != digest
        ):
            conflicts.append(name)
            shutil.copy2(current_source / name, candidate / name)
    proposed = hashes(candidate)
    plan = {
        "version": 1,
        "project": config.project,
        "requestId": identifier,
        "expectedRevision": active.active_revision,
        "destination": destination,
        "previous": previous,
        "candidate": proposed,
        "conflicts": conflicts,
        "wheels": [
            {"path": str(wheel.path), "digest": wheel.digest, "name": wheel.name}
            for wheel in wheels
        ],
        "python": sys.executable,
        "environment": sys.prefix,
    }
    UpgradePlan.decode(plan, config.project, identifier).save(stage)
    replace_private(
        setup.collector.parent / "upgrade-current.json",
        json.dumps({"requestId": identifier}) + "\n",
    )
    print("Upgrade prepared:", identifier)
    print(
        "Changed files:",
        ", ".join(name for name in proposed if proposed[name] != previous.get(name))
        or "none",
    )
    if baseline is None:
        print(
            "No earlier source baseline. Review the candidate against the preserved backup before applying."
        )
    if conflicts:
        print(
            "Local edits preserved; resolve and review conflicting files:",
            ", ".join(conflicts),
        )
    print("Candidate:", candidate)
    print(
        "Run upgrade --apply after reviewing; schedules, collector configuration and evidence remain unchanged."
    )
    return stage


@dataclass(frozen=True)
class UpgradePlan:
    """Validated private plan. Only decode/save touch untrusted JSON values."""

    project: str
    request_id: str
    expected_revision: str
    destination: FileStoreReferenceDto
    previous: Mapping[str, str]
    candidate: Mapping[str, str]
    conflicts: tuple[str, ...]
    wheels: tuple[Wheel, ...]
    python: str
    environment: Path

    @classmethod
    def decode(cls, value: object, project: str, request_id: str) -> UpgradePlan:
        row = object_fields(
            value,
            {
                "version",
                "project",
                "requestId",
                "expectedRevision",
                "destination",
                "previous",
                "candidate",
                "conflicts",
                "wheels",
                "python",
                "environment",
            },
        )
        if (
            type(row["version"]) is not int
            or row["version"] != 1
            or row["project"] != project
            or row["requestId"] != request_id
        ):
            raise SetupProblem("Foreign upgrade plan")
        destination = object_fields(row["destination"], {"store", "path"})
        store = text(destination["store"], 64)
        path = text(destination["path"], 2048)
        if (
            not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", store)
            or path.startswith("/")
            or any(part in ("", ".", "..") for part in path.split("/"))
        ):
            raise SetupProblem("Invalid reviewed FileStore destination")

        def snapshot(value: object) -> dict[str, str]:
            if not isinstance(value, dict) or not 1 <= len(value) <= 128:
                raise SetupProblem("Invalid source snapshot")
            result: dict[str, str] = {}
            for key, digest in value.items():
                if (
                    not isinstance(key, str)
                    or not isinstance(digest, str)
                    or not re.fullmatch(r"[0-9a-f]{64}", digest)
                    or "\\" in key
                    or any(part in ("", ".", "..") for part in key.split("/"))
                    or key.startswith("/")
                ):
                    raise SetupProblem("Invalid source snapshot entry")
                result[key] = digest
            return result

        previous, candidate = snapshot(row["previous"]), snapshot(row["candidate"])
        conflicts = row["conflicts"]
        if not isinstance(conflicts, list) or any(
            not isinstance(name, str) or name not in candidate for name in conflicts
        ):
            raise SetupProblem("Invalid reviewed conflicts")
        wheel_values = row["wheels"]
        if not isinstance(wheel_values, list) or len(wheel_values) not in (0, 2):
            raise SetupProblem("Upgrade needs a paired SDK and collector wheel")
        wheels: list[Wheel] = []
        for entry, expected in zip(
            wheel_values, ("plowshare-sdk", "plowshare-network-privacy")
        ):
            fields = object_fields(entry, {"path", "digest", "name"})
            wheel = Wheel.inspect(Path(text(fields["path"], 4096)), expected)
            if wheel.name != fields["name"] or wheel.digest != fields["digest"]:
                raise SetupProblem("Reviewed upgrade wheel changed")
            wheels.append(wheel)
        environment = Path(text(row["environment"], 4096))
        python = text(row["python"], 4096)
        if not environment.is_absolute() or not Path(python).is_absolute():
            raise SetupProblem("Invalid reviewed Python environment")
        return cls(
            project,
            request_id,
            uuid(row["expectedRevision"]),
            FileStoreReferenceDto(store=store, path=path),
            previous,
            candidate,
            tuple(conflicts),
            tuple(wheels),
            python,
            environment,
        )

    def save(self, stage: Path) -> None:
        replace_private(
            stage / "plan.json",
            json.dumps(
                {
                    "version": 1,
                    "project": self.project,
                    "requestId": self.request_id,
                    "expectedRevision": self.expected_revision,
                    "destination": asdict(self.destination),
                    "previous": dict(self.previous),
                    "candidate": dict(self.candidate),
                    "conflicts": self.conflicts,
                    "wheels": [
                        {"path": str(w.path), "digest": w.digest, "name": w.name}
                        for w in self.wheels
                    ],
                    "python": self.python,
                    "environment": str(self.environment),
                },
                indent=2,
            )
            + "\n",
        )


def selected_plan(setup: Setup) -> tuple[Path, UpgradePlan]:
    current = object_fields(
        load_json(setup.collector.parent / "upgrade-current.json"), {"requestId"}
    )
    request_id = uuid(current["requestId"])
    stage = setup.collector.parent / "upgrades" / request_id
    if stage.resolve() != stage or not stage.is_dir():
        raise SetupProblem("Upgrade plan cannot be linked or missing")
    return stage, UpgradePlan.decode(
        load_json(stage / "plan.json"),
        Configuration.read(setup.collector).project,
        request_id,
    )


def review_upgrade(setup: Setup) -> None:
    stopped(setup)
    stage, plan = selected_plan(setup)
    if (stage / "intent.json").exists():
        raise SetupProblem(
            "An upgrade was submitted; recover its receipt before changing the plan"
        )
    replace(plan, candidate=hashes(stage / "prepared/application"), conflicts=()).save(
        stage
    )
    print("Candidate edits recorded for review. Run upgrade --apply when ready.")


def installed_wheels(wheels: tuple[Wheel, ...]) -> bool:
    """Reconcile pip's reversible local mutation by comparing installed package bytes."""
    from importlib.metadata import PackageNotFoundError, distribution

    for wheel in wheels:
        try:
            installed = distribution(wheel.name)
        except PackageNotFoundError:
            return False
        with ZipFile(wheel.path) as archive:
            for entry in archive.namelist():
                if not (
                    entry.endswith(".py")
                    or entry.endswith("py.typed")
                    or entry.endswith(".dist-info/METADATA")
                ):
                    continue
                target = Path(str(installed.locate_file(entry)))
                if (
                    not target.is_file()
                    or hashlib.sha256(target.read_bytes()).digest()
                    != hashlib.sha256(archive.read(entry)).digest()
                ):
                    return False
    return True


def install_wheels(
    setup: Setup,
    stage: Path,
    plan: UpgradePlan,
    runner: Callable[[tuple[str, ...]], None] | None = None,
    *,
    repair: bool = False,
) -> None:
    if not plan.wheels:
        return
    if (
        plan.python != sys.executable
        or plan.environment != Path(sys.prefix)
        or sys.prefix == sys.base_prefix
    ):
        raise SetupProblem(
            "Use the same dedicated Python environment that prepared this upgrade"
        )
    intent = stage / "collector-upgrade-intent.json"
    if intent.exists():
        if installed_wheels(plan.wheels):
            replace_private(
                stage / "collector-upgrade-completed",
                "Reviewed package bytes reconciled.\n",
            )
            return
        if not repair:
            raise SetupProblem(
                "Package installation was interrupted. Its environment backup is preserved. Run upgrade --repair-collector to explicitly reinstall the retained reviewed wheels."
            )
    else:
        shutil.copytree(
            plan.environment, stage / "backup/collector-environment", symlinks=True
        )
        private_write(
            intent, json.dumps({"files": [str(w.path) for w in plan.wheels]}) + "\n"
        )
    command = (
        sys.executable,
        "-m",
        "pip",
        "install",
        "--force-reinstall",
        "--no-index",
        "--no-deps",
        "--disable-pip-version-check",
        *(str(w.path) for w in plan.wheels),
    )
    if runner is not None:
        runner(command)
    else:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=60, check=False
        )
        if completed.returncode != 0:
            raise SetupProblem(
                "Collector wheel installation failed. The stopped environment backup is preserved; use upgrade --repair-collector after inspecting it."
            )
    if not installed_wheels(plan.wheels):
        raise SetupProblem(
            "Installed collector files do not match the reviewed wheels; inspect the stopped environment and its backup"
        )
    replace_private(
        stage / "collector-upgrade-completed",
        "Reviewed SDK and collector bytes verified.\n",
    )


def publish_source(setup: Setup, stage: Path, plan: UpgradePlan) -> None:
    """Resume either side of the directory swap without deleting source or replaying deployment."""
    current = setup.output / "application"
    previous = stage / "previous-application"
    temporary = setup.output / ("application-" + plan.request_id)
    if current.exists():
        local = hashes(current)
        if local == plan.candidate:
            return
        if local != plan.previous or previous.exists():
            raise SetupProblem(
                "Private source changed; preserve it before recovering this upgrade"
            )
    elif not previous.exists() or hashes(previous) != plan.previous:
        raise SetupProblem(
            "Private source swap cannot be reconciled; preserve the retained backup"
        )
    if temporary.exists():
        if hashes(temporary) != plan.candidate:
            raise SetupProblem(
                "Staged local source changed; inspect it before recovery"
            )
    else:
        shutil.copytree(stage / "prepared/application", temporary)
    if current.exists():
        current.rename(previous)
    temporary.rename(current)


async def apply_upgrade(
    setup: Setup,
    client: SetupClient,
    *,
    recover: bool = False,
    runner: Callable[[tuple[str, ...]], None] | None = None,
    repair: bool = False,
) -> None:
    from .bootstrap import application_files

    stopped(setup)
    stage, plan = selected_plan(setup)
    candidate = stage / "prepared/application"
    if hashes(candidate) != plan.candidate or plan.conflicts:
        raise SetupProblem(
            "Resolve candidate conflicts and run upgrade --review before applying"
        )
    project = plan.project
    request_id = plan.request_id
    active = (
        await client.request(ApplicationDeploymentStatusRequest(project=project))
    ).require_payload()
    intent = stage / "intent.json"
    if recover:
        if not intent.exists():
            raise SetupProblem("No submitted upgrade to recover")
        receipt = (
            await client.request(
                ApplicationDeploymentReceiptRequest(
                    project=project, request_id=request_id
                )
            )
        ).require_payload()
    else:
        if intent.exists():
            raise SetupProblem(
                "Upgrade was submitted; use upgrade --recover, never replay its request"
            )
        if (
            active.project != project
            or active.active_revision != plan.expected_revision
        ):
            raise SetupProblem(
                "Active Application revision changed; prepare a new reviewed upgrade"
            )
        if hashes(setup.output / "application") != plan.previous:
            raise SetupProblem(
                "Private Application source changed after upgrade preparation"
            )
        request = ApplicationDeployRequest(
            project=project,
            request_id=request_id,
            expected_revision=uuid(plan.expected_revision),
            destination=plan.destination,
            writable_areas=(),
            files=application_files(candidate),
        )
        private_write(
            intent,
            json.dumps(
                {
                    "project": project,
                    "requestId": request_id,
                    "expectedRevision": plan.expected_revision,
                }
            )
            + "\n",
        )
        receipt = (await client.request(request)).require_payload()
    if receipt.project != project or receipt.request_id != request_id:
        raise SetupProblem("Foreign upgrade receipt")
    confirmed = (
        await client.request(ApplicationDeploymentStatusRequest(project=project))
    ).require_payload()
    if (
        confirmed.project != project
        or confirmed.active_revision != receipt.release.revision
    ):
        raise SetupProblem(
            "Upgrade committed but is not the active revision; inspect status before local changes"
        )
    publish_source(setup, stage, plan)
    replace_private(
        setup.output / "package-baseline.json",
        json.dumps({"files": dict(plan.candidate)}, indent=2) + "\n",
    )
    replace_private(
        stage / "receipt.json", json.dumps(asdict(receipt), indent=2) + "\n"
    )
    if not (stage / "collector-upgrade-completed").exists():
        install_wheels(setup, stage, plan, runner, repair=repair)
    replace_private(stage / "completed", "Retained Application upgrade confirmed.\n")
    print("Upgrade confirmed:", receipt.release.revision)
    print(
        "Collector configuration, identity and evidence preserved. Restart the external collector when ready."
    )
