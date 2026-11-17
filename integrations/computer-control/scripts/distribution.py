#!/usr/bin/env python3
"""Bounded offline installer; executes only an explicit Node prerequisite and fixed smoke."""
import argparse
import ctypes
from dataclasses import dataclass
import errno
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
from types import MappingProxyType
from typing import Mapping
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import time
import zlib

VERSION = "computer-control-distribution/1"
PACKAGES = (
    "plowshare-computer-control",
    "plowshare-client-ts",
    "plowshare-client-node",
    "playwright",
    "playwright-core",
    "ws",
)
MAX_FILES = 10000
MAX_FILE = 16 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024


@dataclass(frozen=True)
class Member:
    path: str
    sha256: str
    byte_length: int
    mode: int


@dataclass(frozen=True)
class Support:
    path: str
    sha256: str
    byte_length: int


@dataclass(frozen=True)
class Package:
    name: str
    version: str
    archive: str
    sha256: str
    byte_length: int
    files: tuple[Member, ...]


@dataclass(frozen=True)
class Manifest:
    source_revision: str
    dirty: bool
    packages: tuple[Package, ...]
    support: tuple[Support, ...]


@dataclass(frozen=True)
class PackageFiles:
    name: str
    version: str
    files: Mapping[str, bytes]


def package_files_value(
    name: str, version: str, files: Mapping[str, bytes]
) -> PackageFiles:
    """Project package.json identity and immutable bytes at its owning boundary."""
    return PackageFiles(name, version, MappingProxyType(dict(files)))


class Failure(Exception):
    """Fixed diagnostics only; never expose operator paths, argv or subprocess output."""


class CleanupUncertain(Failure):
    """The original command may still own its stage; never delete or publish that stage."""


class Deadline:
    def __init__(self, seconds):
        if type(seconds) not in (int, float) or not 0 < seconds <= 300:
            raise Failure("INVALID_BUDGET")
        self.end = time.monotonic() + seconds

    def remaining(self):
        result = self.end - time.monotonic()
        if result <= 0:
            raise Failure("DEADLINE_EXPIRED")
        return result


class Parser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "FAIL: invalid distribution options.\n")


def supported_platform():
    # This installer implements POSIX process groups and file modes, not Windows ACLs.
    if (
        sys.platform != "darwin"
        and not sys.platform.startswith("linux")
        or type(getattr(os, "O_NOFOLLOW", None)) is not int
        or type(getattr(os, "O_NONBLOCK", None)) is not int
        or not callable(getattr(os, "killpg", None))
    ):
        raise Failure("UNSUPPORTED_PLATFORM")


def fields(row, expected):
    if type(row) is not dict or set(row) != set(expected):
        raise Failure("INVALID_MANIFEST")
    return row


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Failure("INVALID_MANIFEST")
            result[key] = value
        return result

    try:
        return json.loads(
            data.decode("utf-8", errors="strict"),
            object_pairs_hook=pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(Failure("INVALID_MANIFEST")),
        )
    except (ValueError, UnicodeError, RecursionError):
        raise Failure("INVALID_MANIFEST") from None


def relative(value):
    if (
        type(value) is not str
        or not value
        or len(value.encode("utf-8")) > 255
        or "\\" in value
        or ":" in value
        or any(ord(c) < 32 for c in value)
        or value.startswith("/")
        or any(p in ("", ".", "..") for p in value.split("/"))
    ):
        raise Failure("INVALID_MEMBER")
    return PurePosixPath(value)


def private_destination(value):
    path = Path(value)
    if not path.is_absolute() or str(path) != value or not path.parent.is_dir():
        raise Failure("INVALID_DESTINATION")
    if path.parent.resolve() != path.parent:
        raise Failure("INVALID_DESTINATION")
    if path.exists() or path.is_symlink():
        raise Failure("DESTINATION_EXISTS")
    return path


def read_regular(path, limit, deadline):
    supported_platform()
    deadline.remaining()
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise Failure("FILE_LIMIT")
        chunks = []
        used = 0
        while True:
            deadline.remaining()
            chunk = os.read(descriptor, min(65536, limit + 1 - used))
            deadline.remaining()
            if not chunk:
                break
            chunks.append(chunk)
            used += len(chunk)
            if used > limit:
                raise Failure("FILE_LIMIT")
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise Failure("INVALID_MANIFEST")
    return value


def sha(value):
    if (
        type(value) is not str
        or len(value) != 64
        or any(c not in "0123456789abcdef" for c in value)
    ):
        raise Failure("INVALID_MANIFEST")
    return value


def manifest(data: bytes) -> Manifest:
    row = fields(
        strict_json(data),
        ("version", "sourceRevision", "dirty", "nodeMinimum", "packages", "support"),
    )
    if (
        row["version"] != VERSION
        or row["nodeMinimum"] != "22.13.0"
        or type(row["sourceRevision"]) is not str
        or len(row["sourceRevision"]) != 40
        or any(c not in "0123456789abcdef" for c in row["sourceRevision"])
        or type(row["dirty"]) is not bool
        or type(row["packages"]) is not list
        or len(row["packages"]) != len(PACKAGES)
    ):
        raise Failure("INVALID_MANIFEST")
    names = set()
    total = 0
    count = 0
    archive_total = 0
    packages = []
    for package in row["packages"]:
        fields(package, ("name", "version", "archive", "sha256", "bytes", "files"))
        name = package["name"]
        if name not in PACKAGES or name in names:
            raise Failure("INVALID_MANIFEST")
        names.add(name)
        if (
            type(package["version"]) is not str
            or not package["version"]
            or len(package["version"]) > 64
            or package["archive"] != "artifacts/" + name + ".tgz"
        ):
            raise Failure("INVALID_MANIFEST")
        sha(package["sha256"])
        archive_total += integer(package["bytes"], 1, MAX_ARCHIVE)
        if type(package["files"]) is not list or len(package["files"]) > MAX_FILES:
            raise Failure("INVALID_MANIFEST")
        paths = set()
        members = []
        for member in package["files"]:
            fields(member, ("path", "sha256", "bytes", "mode"))
            relative(member["path"])
            sha(member["sha256"])
            if (
                member["path"] in paths
                or type(member["mode"]) is not int
                or member["mode"] not in (0o644, 0o755)
            ):
                raise Failure("INVALID_MANIFEST")
            paths.add(member["path"])
            total += integer(member["bytes"], 0, MAX_FILE)
            count += 1
            members.append(
                Member(
                    member["path"], member["sha256"], member["bytes"], member["mode"]
                )
            )
        if "package.json" not in paths:
            raise Failure("INVALID_MANIFEST")
        packages.append(
            Package(
                name,
                package["version"],
                package["archive"],
                package["sha256"],
                package["bytes"],
                tuple(members),
            )
        )
    if total > MAX_TOTAL or count > MAX_FILES or archive_total > MAX_TOTAL:
        raise Failure("FILE_LIMIT")
    if type(row["support"]) is not list or len(row["support"]) != 2:
        raise Failure("INVALID_MANIFEST")
    expected = {"install.py", "smoke.mjs"}
    supports = []
    for support in row["support"]:
        fields(support, ("path", "sha256", "bytes"))
        relative(support["path"])
        if support["path"] not in expected:
            raise Failure("INVALID_MANIFEST")
        expected.remove(support["path"])
        sha(support["sha256"])
        integer(support["bytes"], 1, MAX_FILE)
        supports.append(Support(support["path"], support["sha256"], support["bytes"]))
    # No raw JSON collection crosses into the filesystem/effect contracts.
    return Manifest(
        row["sourceRevision"], row["dirty"], tuple(packages), tuple(supports)
    )


def encode_manifest(value: Manifest) -> bytes:
    """The one writer boundary for immutable internal distribution values."""
    row = {
        "version": VERSION,
        "sourceRevision": value.source_revision,
        "dirty": value.dirty,
        "nodeMinimum": "22.13.0",
        "packages": [
            {
                "name": package.name,
                "version": package.version,
                "archive": package.archive,
                "sha256": package.sha256,
                "bytes": package.byte_length,
                "files": [
                    {
                        "path": member.path,
                        "sha256": member.sha256,
                        "bytes": member.byte_length,
                        "mode": member.mode,
                    }
                    for member in package.files
                ],
            }
            for package in value.packages
        ],
        "support": [
            {"path": member.path, "sha256": member.sha256, "bytes": member.byte_length}
            for member in value.support
        ],
    }
    data = json.dumps(row, indent=2).encode() + b"\n"
    if len(data) > MAX_MANIFEST:
        raise Failure("FILE_LIMIT")
    manifest(data)
    return data


def run(command, directory, deadline, allowed=(0,)):
    supported_platform()
    deadline.remaining()
    child = subprocess.Popen(
        command,
        cwd=directory,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
        env={"PATH": os.defpath, "LC_ALL": "C"},
    )
    selector = None
    output = bytearray()
    total = 0
    try:
        selector = selectors.DefaultSelector()
        for stream in (child.stdout, child.stderr):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ)
        while selector.get_map():
            deadline.remaining()
            for key, _ in selector.select(min(0.05, deadline.remaining())):
                chunk = os.read(key.fileobj.fileno(), 8192)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                total += len(chunk)
                if total > 65536:
                    raise Failure("COMMAND_OUTPUT_LIMIT")
                if key.fileobj is child.stdout:
                    output.extend(chunk)
        child.wait(timeout=deadline.remaining())
        deadline.remaining()
        if child.returncode not in allowed:
            raise Failure("PREREQUISITE_OR_SMOKE_FAILED")
        return bytes(output)
    except (OSError, subprocess.TimeoutExpired, Failure, KeyboardInterrupt):
        cleanup_end = time.monotonic() + 2
        kill_failed = False
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError:
            kill_failed = True
        try:
            child.wait(timeout=max(0.001, cleanup_end - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN") from None
        # Reaping the leader alone does not settle an inherited command group.
        while True:
            try:
                os.killpg(child.pid, 0)
            except ProcessLookupError:
                break
            except OSError:
                kill_failed = True
                break
            if time.monotonic() >= cleanup_end:
                raise CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN") from None
            time.sleep(min(0.01, max(0, cleanup_end - time.monotonic())))
        if kill_failed:
            raise CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN") from None
        raise Failure("DEADLINE_OR_COMMAND_FAILED") from None
    finally:
        if selector is not None:
            selector.close()
        child.stdout.close()
        child.stderr.close()


def cleanup(stage):
    if stage.exists():
        # Separate finite cleanup unit, only for this invocation's exact private stage.
        run(["/bin/rm", "-rf", "--", str(stage)], stage.parent, Deadline(2))


def publish(source, destination):
    supported_platform()
    # Never emulate NOREPLACE with exists()+rename(): that can overwrite a racing destination.
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        fn = libc.renamex_np
        fn.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint)
        result = fn(os.fsencode(source), os.fsencode(destination), 4)
    elif sys.platform.startswith("linux") and hasattr(libc, "renameat2"):
        fn = libc.renameat2
        fn.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        result = fn(-100, os.fsencode(source), -100, os.fsencode(destination), 1)
    else:
        raise Failure("ATOMIC_PUBLICATION_UNAVAILABLE")
    if result != 0:
        if ctypes.get_errno() in (errno.EEXIST, errno.ENOTEMPTY):
            raise Failure("DESTINATION_EXISTS")
        raise Failure("PUBLICATION_FAILED")


def octal(data):
    value = data.rstrip(b"\0 ").lstrip(b" ")
    if not value or any(c not in b"01234567" for c in value):
        raise Failure("INVALID_ARCHIVE")
    return int(value, 8)


def unpack(package: Package, data, destination, stage, deadline):
    # Bound the complete decompressed STREAM before parsing even one tar/PAX header.
    raw = stage / (package.name + ".tar")
    with raw.open("xb") as target:
        import io

        with gzip.GzipFile(fileobj=io.BytesIO(data)) as source:
            used = 0
            while True:
                deadline.remaining()
                chunk = source.read(min(65536, MAX_TOTAL + 1 - used))
                deadline.remaining()
                if not chunk:
                    break
                used += len(chunk)
                if used > MAX_TOTAL:
                    raise Failure("ARCHIVE_LIMIT")
                target.write(chunk)
    expected = {item.path: item for item in package.files}
    found = set()
    destination.mkdir(parents=True)
    with raw.open("rb") as source:
        while True:
            deadline.remaining()
            header = source.read(512)
            if len(header) != 512:
                raise Failure("INVALID_ARCHIVE")
            if header == bytes(512):
                while True:
                    deadline.remaining()
                    tail = source.read(65536)
                    if not tail:
                        break
                    if any(tail):
                        raise Failure("INVALID_ARCHIVE")
                break
            if (
                sum(header[:148]) + 8 * 32 + sum(header[156:]) != octal(header[148:156])
                or header[156:157] not in (b"0", b"\0")
                or header[257:263] != b"ustar\0"
            ):
                raise Failure("INVALID_ARCHIVE")
            try:
                name = header[:100].split(b"\0", 1)[0].decode("utf-8", "strict")
                prefix = header[345:500].split(b"\0", 1)[0].decode("utf-8", "strict")
            except UnicodeError:
                raise Failure("INVALID_ARCHIVE") from None
            full = (prefix + "/" if prefix else "") + name
            relative(full)
            if not full.startswith("package/"):
                raise Failure("INVALID_ARCHIVE")
            path = full[len("package/") :]
            relative(path)
            item = expected.get(path)
            size = octal(header[124:136])
            if (
                item is None
                or path in found
                or size != item.byte_length
                or octal(header[100:108]) != item.mode
            ):
                raise Failure("INVALID_ARCHIVE")
            found.add(path)
            payload = source.read(size)
            if len(payload) != size or digest(payload) != item.sha256:
                raise Failure("HASH_MISMATCH")
            padding = source.read((-size) % 512)
            if len(padding) != (-size) % 512 or any(padding):
                raise Failure("INVALID_ARCHIVE")
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            deadline.remaining()
            with target.open("xb") as output:
                output.write(payload)
            os.chmod(target, item.mode)
    raw.unlink()
    if found != set(expected):
        raise Failure("INVALID_ARCHIVE")


LAUNCHER = b"""#!/usr/bin/env node
import { main } from '../node_modules/plowshare-computer-control/build/computer/main.js';
try { await main(process.argv.slice(2)); }
catch { process.stderr.write('computer-control: HOST_FAILED. No operation was replayed.\\n'); process.exitCode=1; }
"""


def install(directory, prefix, node, deadline):
    supported_platform()
    prefix = private_destination(str(prefix))
    node = Path(node)
    if not node.is_absolute():
        raise Failure("NODE_PREREQUISITE_REQUIRED")
    run(
        [
            str(node),
            "-e",
            "const [a,b]=process.versions.node.split('.').map(Number); if(a<22||(a===22&&b<13))process.exit(1)",
        ],
        directory,
        deadline,
    )
    manifest_bytes = read_regular(directory / "manifest.json", MAX_MANIFEST, deadline)
    info = manifest(manifest_bytes)
    support = {}
    for member in info.support:
        data = read_regular(directory / member.path, MAX_FILE, deadline)
        if len(data) != member.byte_length or digest(data) != member.sha256:
            raise Failure("HASH_MISMATCH")
        support[member.path] = data
    stage = Path(tempfile.mkdtemp(prefix=".computer-install-", dir=prefix.parent))
    os.chmod(stage, 0o700)
    safe_cleanup = True
    try:
        artifact = stage
        for package in info.packages:
            data = read_regular(directory / package.archive, MAX_ARCHIVE, deadline)
            if len(data) != package.byte_length or digest(data) != package.sha256:
                raise Failure("HASH_MISMATCH")
            unpack(
                package,
                data,
                artifact / "node_modules" / package.name,
                stage,
                deadline,
            )
        (artifact / "bin").mkdir()
        (artifact / "bin/computer-control.mjs").write_bytes(LAUNCHER)
        os.chmod(artifact / "bin/computer-control.mjs", 0o755)
        (artifact / "smoke.mjs").write_bytes(support["smoke.mjs"])
        (artifact / "manifest.json").write_bytes(manifest_bytes)
        run([str(node), str(artifact / "smoke.mjs")], artifact, deadline)
        run(
            [str(node), str(artifact / "bin/computer-control.mjs")],
            artifact,
            deadline,
            allowed=(1,),
        )
        deadline.remaining()
        publish(artifact, prefix)
    except CleanupUncertain:
        safe_cleanup = False
        raise
    finally:
        # Only this exact private stage is removed; never a destination/foreign stale stage.
        if safe_cleanup:
            cleanup(stage)


def main():
    parser = Parser(description=__doc__)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--node", required=True)
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    try:
        install(
            Path(__file__).resolve().parent,
            args.prefix,
            args.node,
            Deadline(args.timeout),
        )
        print(
            "PASS: verified offline provider and public SDK installation; Node prerequisite checked. No server or desktop contacted."
        )
        return 0
    except (Failure, OSError, ValueError, EOFError, zlib.error):
        print(
            "FAIL: offline distribution validation or installation failed; no operation was replayed.",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
