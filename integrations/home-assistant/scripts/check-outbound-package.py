"""Validate a fresh installed POSIX HA distribution without live endpoints or credentials.

Run after installDist, with an explicit Java 21+ JAVA_HOME. The JVM subprocesses
use only a copied installed launcher/jars and installed examples, outside the
repository. Local listening sockets detect accidental network work; no fixture
accepts a device action. The JSON report contains relative artifact hashes and
bounded check names, never environment values or configuration dumps.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import tempfile
import zipfile
from contextlib import ExitStack
from pathlib import Path
from urllib.parse import unquote, urlsplit

LAUNCHER = "plowshare-integration-home-assistant"
MAX_FILES = 5000
MAX_JAR_ENTRIES = 50000
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_PAYLOAD_BYTES = 512 * 1024 * 1024
FORBIDDEN_PACKAGES = (
    "io/aeyer/plowshare/server/",
    "org/junit/",
    "org/mockito/",
    "org/testcontainers/",
    "okhttp3/mockwebserver/",
    "custom_components/",
)


def require(condition: bool, description: str) -> None:
    if not condition:
        raise AssertionError(description)


def snapshot(root: Path) -> dict[str, str]:
    paths = sorted(root.rglob("*"))
    require(len(paths) <= MAX_FILES, "installed payload exceeds fixture file limit")
    require(
        not any(path.is_symlink() for path in paths),
        "unexpected installed symbolic link",
    )
    files = [path for path in paths if path.is_file()]
    require(
        sum(path.stat().st_size for path in files) <= MAX_PAYLOAD_BYTES,
        "installed payload exceeds fixture byte limit",
    )
    result = {}
    for path in files:
        require(
            path.stat().st_size <= MAX_FILE_BYTES,
            "installed file exceeds fixture byte limit",
        )
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        result[path.relative_to(root).as_posix()] = digest.hexdigest()
    return result


def directory_snapshot(root: Path) -> tuple[str, ...]:
    paths = sorted(root.rglob("*"))
    require(len(paths) <= MAX_FILES, "fixture directory topology exceeds its bound")
    return tuple(path.relative_to(root).as_posix() for path in paths if path.is_dir())


def require_tree_unchanged(
    root: Path, files: dict[str, str], directories: tuple[str, ...], description: str
) -> None:
    require(snapshot(root) == files, description)
    require(directory_snapshot(root) == directories, description)


def inspect_payload(root: Path) -> dict[str, int]:
    require((root / "bin" / LAUNCHER).is_file(), "installed POSIX launcher missing")
    require(
        os.access(root / "bin" / LAUNCHER, os.X_OK),
        "installed launcher is not executable",
    )
    require((root / "PROVIDER-HOST.md").is_file(), "installed operator guide missing")
    jars = sorted((root / "lib").glob("*.jar"))
    require(bool(jars), "installed runtime jars missing")
    found_main = False
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            require(
                len(names) <= MAX_JAR_ENTRIES,
                "installed jar exceeds fixture entry limit",
            )
            for name in names:
                require(
                    not any(
                        name.startswith(prefix) or f"/{prefix}" in name
                        for prefix in FORBIDDEN_PACKAGES
                    ),
                    f"forbidden server/test/Assist package in {jar.name}",
                )
            found_main |= (
                "io/aeyer/plowshare/integrations/ha/HomeAssistantMain.class" in names
            )
    require(found_main, "installed HA operator entry point missing")

    # Repository-relative links are valid in source but fail in a standalone distribution.
    # Every local destination must resolve inside this actual installed payload.
    links = 0
    for document in sorted(root.rglob("*.md")):
        for target in re.findall(
            r"\[[^\]]+\]\(([^)]+)\)", document.read_text(encoding="utf-8")
        ):
            target = target.strip().removeprefix("<").removesuffix(">")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            resolved = (document.parent / unquote(parsed.path)).resolve()
            require(
                resolved.is_relative_to(root),
                f"installed document link escapes payload: {document.relative_to(root)}",
            )
            require(
                resolved.exists(),
                f"installed document link is missing: {document.relative_to(root)}",
            )
            links += 1
    require(links > 0, "installed documentation did not exercise local links")
    require(
        not any(path.name == "custom_components" for path in root.rglob("*")),
        "Assist component was packaged",
    )
    return {"runtime_jars": len(jars), "local_document_links": links}


def credential_names(value: object) -> set[str]:
    if isinstance(value, dict):
        result = (
            {value["tokenEnv"]} if isinstance(value.get("tokenEnv"), str) else set()
        )
        for child in value.values():
            result.update(credential_names(child))
        return result
    if isinstance(value, list):
        return set().union(*(credential_names(child) for child in value))
    return set()


def trap(stack: ExitStack) -> socket.socket:
    listener = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_STREAM))
    listener.bind(("127.0.0.1", 0))
    listener.listen(16)
    listener.setblocking(False)
    return listener


def no_connections(listeners: list[socket.socket]) -> None:
    for listener in listeners:
        try:
            connection, _ = listener.accept()
        except BlockingIOError:
            continue
        connection.close()
        raise AssertionError("offline installed command opened a network connection")


def run_checks(distribution: Path) -> dict[str, object]:
    java_home = os.environ.get("JAVA_HOME")
    require(
        bool(java_home),
        "set an explicit Java 21+ JAVA_HOME before running this fixture",
    )
    java = Path(java_home) / "bin" / "java"
    require(java.is_file(), "configured JAVA_HOME does not contain a Java executable")
    version = subprocess.run(
        [str(java), "-version"], capture_output=True, text=True, timeout=5, check=True
    )
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    require(
        match is not None and int(match.group(1)) >= 21,
        "fixture requires Java 21 or newer",
    )
    require(os.name == "posix", "this fixture validates the installed POSIX launcher")

    source_hashes = snapshot(distribution)
    source_directories = directory_snapshot(distribution)
    payload = inspect_payload(distribution)
    checks: list[str] = []
    with (
        tempfile.TemporaryDirectory(prefix="ha-outbound-installed-") as temporary,
        ExitStack() as stack,
    ):
        scratch = Path(temporary).resolve()
        installed = scratch / "installed"
        shutil.copytree(distribution, installed)
        work = scratch / "consumer"
        work.mkdir()
        require_tree_unchanged(
            installed,
            source_hashes,
            source_directories,
            "fresh copy differs from installed artifact",
        )
        listeners = [trap(stack), trap(stack)]
        origins = [
            f"http://127.0.0.1:{listener.getsockname()[1]}" for listener in listeners
        ]
        configs = {
            "readonly": installed / "examples/outbound/readonly/provider.json",
            "control": installed / "examples/outbound/control/provider.json",
            "legacy": installed / "examples/config.json",
        }
        values: dict[str, dict[str, object]] = {}
        environment = os.environ.copy()
        for name in (
            "CLASSPATH",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "JAVA_OPTS",
            "_JAVA_OPTIONS",
        ):
            environment.pop(name, None)
        for profile, path in configs.items():
            value = json.loads(path.read_text(encoding="utf-8"))
            for name in credential_names(value):
                environment.pop(name, None)
            value["plowshare"] = origins[0]
            if profile == "legacy":
                for binding in value["bindings"].values():
                    binding["configuration"]["endpoint"] = origins[1]
            else:
                value["homeAssistant"]["endpoint"] = origins[1]
            path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
            values[profile] = value

        def command(
            name: str, arguments: list[str], expected: int, diagnostic: str = ""
        ) -> None:
            before = snapshot(scratch)
            directories_before = directory_snapshot(scratch)
            outcome = subprocess.run(
                [str(installed / "bin" / LAUNCHER), *arguments],
                cwd=work,
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            require(outcome.returncode == expected, f"installed check failed: {name}")
            require(
                diagnostic in outcome.stdout + outcome.stderr,
                f"installed diagnostic missing: {name}",
            )
            require(
                len(outcome.stdout) + len(outcome.stderr) <= 16384,
                "installed diagnostics exceed fixture bound",
            )
            require(
                "VALIDATION_PRIVATE_MARKER" not in outcome.stdout + outcome.stderr,
                "installed diagnostics disclosed supplied input",
            )
            no_connections(listeners)
            require_tree_unchanged(
                scratch,
                before,
                directories_before,
                f"offline command changed fixture data or directories: {name}",
            )
            checks.append(name)

        for profile in ("readonly", "control"):
            command(
                f"{profile}: offline provider config",
                ["--provider", "--check", str(configs[profile])],
                0,
            )
        command(
            "legacy: offline manual-alias config",
            ["--check", str(configs["legacy"])],
            0,
        )

        # Corrupt or pending state is neither a reason for offline check to fail nor permission
        # to open, migrate, reset or delete retained effect fences.
        for profile in ("readonly", "control"):
            value = values[profile]
            owners = [value["read"]]
            if "control" in value:
                owners.append(value["control"])
            for owner in owners:
                journal = configs[profile].parent / owner["journal"]
                journal.mkdir(parents=True)
                (journal / "tools.json").write_bytes(
                    b'{"unreadable_pending_intent":"retain-original"}\n'
                )
                (journal / "owner.lock").write_bytes(b"retained-owner\n")
            command(
                f"{profile}: retained journals untouched",
                ["--provider", "--check", str(configs[profile])],
                0,
            )

        invalid = scratch / "invalid-provider.json"
        value = json.loads(json.dumps(values["readonly"]))
        value["externalContext"] = {"systemText": "VALIDATION_PRIVATE_MARKER"}
        invalid.write_text(json.dumps(value), encoding="utf-8")
        command(
            "prompt input remains refused", ["--provider", "--check", str(invalid)], 1
        )
        value = json.loads(json.dumps(values["control"]))
        value["control"]["account"] = "different-principal-VALIDATION_PRIVATE_MARKER"
        invalid.write_text(json.dumps(value), encoding="utf-8")
        command(
            "contradictory control principal refused",
            ["--provider", "--check", str(invalid)],
            1,
        )
        command(
            "malformed provider CLI refused",
            ["--provider", "--unknown", str(invalid)],
            1,
            "Usage:",
        )

    return {
        "result": "PASS",
        "scope": "fresh installed POSIX launcher and jars; no real server, HA, model or database",
        "java_major": int(match.group(1)),
        "platform": os.sys.platform,
        "checks": checks,
        "payload_checks": payload,
        "artifacts": [
            {
                "path": path,
                "sha256": digest,
                "bytes": (distribution / path).stat().st_size,
            }
            for path, digest in source_hashes.items()
        ],
        "fixture_origin_changes": [
            "plowshare",
            "homeAssistant.endpoint or legacy binding endpoint",
        ],
        "source_distribution_unchanged": (
            snapshot(distribution) == source_hashes
            and directory_snapshot(distribution) == source_directories
        ),
        "directory_topology_checked": True,
        "limits": "Local trap listeners prove no observed connections from offline commands. Static template policy and scripted protocol tests do not prove live grants, device effects, fleet availability or Windows installation.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    result = run_checks(arguments.distribution.resolve(strict=True))
    require(
        result["source_distribution_unchanged"],
        "fixture changed the original distribution",
    )
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        f"Installed outbound package validation passed: {len(result['checks'])} checks, {len(result['artifacts'])} artifact files."
    )


if __name__ == "__main__":
    main()
