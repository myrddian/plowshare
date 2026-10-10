"""Opt-in, per-user collector services. No root service, shell or credential arguments.

Definitions are generated before activation and retained with a digest. Management
commands only touch that exact definition; edited or foreign units are refused.
The existing receipt locks continue to fence foreground/background overlap.
"""

from __future__ import annotations

import hashlib
import json
import os
import plistlib
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol

from .contracts import Configuration, load_json, object_fields, text
from .private_files import replace_private


@dataclass(frozen=True)
class CommandResult:
    code: int
    output: str


class ProcessRunner(Protocol):
    def run(self, arguments: tuple[str, ...]) -> CommandResult: ...


class SystemProcesses:
    def run(self, arguments: tuple[str, ...]) -> CommandResult:
        result = subprocess.run(
            arguments, capture_output=True, text=True, timeout=20, check=False
        )
        return CommandResult(result.returncode, result.stdout[:4096])


@dataclass(frozen=True)
class ServiceDefinition:
    system: str
    label: str
    path: str
    digest: str

    @classmethod
    def read(cls, root: Path) -> ServiceDefinition:
        row = object_fields(
            load_json(root / "collector-service.json"),
            {"system", "label", "path", "digest"},
        )
        result = cls(
            *(text(row[key], 4096) for key in ("system", "label", "path", "digest"))
        )
        expected = label(root)
        path = Path(result.path)
        suffix = ".plist" if result.system == "darwin" else ".service"
        if (
            result.system not in {"darwin", "linux"}
            or result.label != expected
            or path.name != expected + suffix
            or not path.is_absolute()
            or path.resolve() != path
            or path.is_symlink()
        ):
            raise ValueError("Service definition is foreign or linked")
        if hashlib.sha256(path.read_bytes()).hexdigest() != result.digest:
            raise ValueError("Service definition changed; inspect it before management")
        return result


def label(root: Path) -> str:
    return "io.plowshare.privacy." + hashlib.sha256(str(root).encode()).hexdigest()[:12]


def systemd_argument(value: str) -> str:
    # Unit parsing includes specifier and environment expansion, not shell quoting.
    return (
        '"'
        + value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("%", "%%")
        .replace("$", "$$")
        + '"'
    )


class CollectorService:
    def __init__(
        self, root: Path, runner: ProcessRunner, *, system: str = sys.platform
    ):
        if not root.is_absolute() or root.resolve() != root or root.is_symlink():
            raise ValueError("Service needs an absolute private setup directory")
        if system not in {"darwin", "linux"}:
            raise ValueError(
                "Background services support macOS launchd and Linux user systemd"
            )
        self.root, self.runner, self.system = root, runner, system

    def install(self, directory: Path, executable: Path) -> ServiceDefinition:
        config = Configuration.read(self.root / "collector.json")
        if (
            not (self.root / "deployment/installation-completed").is_file()
            or not (self.root / "dashboard.json").is_file()
        ):
            raise ValueError(
                "Complete guided installation and choose a dashboard bind address first"
            )
        if config.collection.pihole and config.collection.pihole.password_environment:
            raise ValueError(
                "Background Pi-hole collection needs a private passwordFile, not a shell environment"
            )
        for path in (directory, executable.parent):
            if not path.is_absolute() or path.resolve() != path or path.is_symlink():
                raise ValueError("Service paths must be absolute and unlinked")
            text(str(path), 4096)
        if (
            not executable.is_absolute()
            or not directory.is_dir()
            or not executable.is_file()
            or not os.access(executable, os.X_OK)
        ):
            raise ValueError(
                "Choose an existing service directory and Python interpreter"
            )
        if (self.root / "collector-service.json").exists():
            raise ValueError("A service is already enrolled; inspect its status")
        if (self.root / "dashboard-runtime.json").exists():
            raise ValueError(
                "Stop the foreground collector before enrolling its background service"
            )
        if (
            self.system == "darwin"
            and directory != (Path.home() / "Library/LaunchAgents").resolve()
        ):
            raise ValueError(
                "Choose your user Library/LaunchAgents directory for login startup"
            )
        name = label(self.root)
        path = directory / (
            name + (".plist" if self.system == "darwin" else ".service")
        )
        if path.exists():
            raise ValueError("Existing service files are preserved")
        arguments = (
            str(executable),
            "-m",
            "plowshare_privacy.bootstrap",
            "--directory",
            str(self.root),
            "background",
        )
        if self.system == "darwin":
            logs = self.root / "service-logs"
            logs.mkdir(mode=0o700, exist_ok=True)
            if logs.is_symlink() or (
                os.name == "posix" and logs.stat().st_mode & 0o077
            ):
                raise ValueError("Service logs need a private unlinked directory")
            for stream in ("output.log", "error.log"):
                fd = os.open(
                    logs / stream,
                    os.O_WRONLY | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
                    0o600,
                )
                os.close(fd)
                if (logs / stream).stat().st_mode & 0o077:
                    raise ValueError("Service logs must be private")
            content = plistlib.dumps(
                {
                    "Label": name,
                    "ProgramArguments": list(arguments),
                    "WorkingDirectory": str(self.root),
                    "RunAtLoad": True,
                    "KeepAlive": {"SuccessfulExit": False},
                    "ThrottleInterval": 30,
                    "Umask": 0o077,
                    "StandardOutPath": str(logs / "output.log"),
                    "StandardErrorPath": str(logs / "error.log"),
                }
            ).decode()
        else:
            content = (
                "[Unit]\nDescription=Network Privacy Watch collector\nStartLimitIntervalSec=300\nStartLimitBurst=5\n\n[Service]\nType=simple\nExecStart="
                + " ".join(systemd_argument(arg) for arg in arguments)
                + "\nWorkingDirectory="
                + systemd_argument(str(self.root))
                + "\nRestart=on-failure\nRestartSec=30\nUMask=0077\n\n[Install]\nWantedBy=default.target\n"
            )
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as destination:
            destination.write(content)
            destination.flush()
            os.fsync(destination.fileno())
        definition = ServiceDefinition(
            self.system, name, str(path), hashlib.sha256(content.encode()).hexdigest()
        )
        # Retain enrollment before manager calls. Failed activation is inspected,
        # never reported as running or silently replaced on another install.
        replace_private(
            self.root / "collector-service.json", json.dumps(asdict(definition)) + "\n"
        )
        commands = (
            (("launchctl", "bootstrap", f"gui/{os.getuid()}", str(path)),)
            if self.system == "darwin"
            else (
                ("systemctl", "--user", "daemon-reload"),
                ("systemctl", "--user", "enable", "--now", str(path)),
            )
        )
        self._execute(commands)
        return definition

    def manage(self, action: str) -> CommandResult:
        definition = ServiceDefinition.read(self.root)
        if definition.system != self.system:
            raise ValueError("Service belongs to another operating system")
        if self.system == "darwin":
            target = f"gui/{os.getuid()}/{definition.label}"
            commands = {
                "status": (("launchctl", "print", target),),
                "start": (("launchctl", "kickstart", target),),
                "stop": (("launchctl", "kill", "SIGTERM", target),),
                "uninstall": (("launchctl", "bootout", target),),
            }
        else:
            unit = Path(definition.path).name
            commands = {
                "status": (("systemctl", "--user", "is-active", unit),),
                "start": (("systemctl", "--user", "start", unit),),
                "stop": (("systemctl", "--user", "stop", unit),),
                "uninstall": (("systemctl", "--user", "disable", "--now", unit),),
            }
        if action not in commands:
            raise ValueError("Unknown service action")
        result = self.runner.run(commands[action][0])
        if action == "status":
            return result
        if result.code:
            raise ValueError(
                "Service manager did not confirm the action; inspect service status"
            )
        if action == "uninstall":
            Path(definition.path).unlink()
            (self.root / "collector-service.json").unlink()
            if self.system == "linux":
                self._execute((("systemctl", "--user", "daemon-reload"),))
        return result

    def _execute(self, commands: tuple[tuple[str, ...], ...]) -> None:
        for command in commands:
            if self.runner.run(command).code:
                raise ValueError(
                    "Service activation was not confirmed; inspect the retained definition and service status"
                )
