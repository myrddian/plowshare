#!/usr/bin/env python3
"""Build a standalone helper or explicitly signed macOS application bundle."""

import argparse
import ctypes
import errno
import math
import os
import pathlib
import platform
import plistlib
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time

SOURCES = (
    "ScreenCaptureHelper.swift",
    "NativeGlobalFence.swift",
    "NativeBrokerProtocol.swift",
    "NativeInput.swift",
    "NativeObservation.swift",
    "NativeReadinessUIContract.swift",
    "NativeReadinessUI.swift",
    "NativeReadiness.swift",
    "NativeBroker.swift",
)
EXECUTABLE = "plowshare-computer-helper"
OUTPUT_LIMIT = 1024 * 1024


class Failure(Exception):
    """A fixed diagnostic code: never contains subprocess arguments or output."""


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "FAIL: invalid native command options.\n")


class Deadline:
    def __init__(self, seconds: float, parent=None):
        if not math.isfinite(seconds) or not 0 < seconds <= 300:
            raise Failure("INVALID_BUDGET")
        self.end = min(time.monotonic() + seconds, parent.end if parent else math.inf)
        self.remaining()

    def remaining(self):
        remaining = self.end - time.monotonic()
        if remaining <= 0:
            raise Failure("DEADLINE_EXPIRED")
        return remaining


def environment(directory: pathlib.Path):
    # Toolchain selection is explicit; do not forward tokens, credentials or user environment.
    return {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
        "TMPDIR": str(directory),
        "LC_ALL": "C",
    }


class Process:
    """Own a process group until reaped; deadline expiry is not proof of cleanup."""

    def __init__(self, arguments, directory: pathlib.Path, deadline, cleanup_seconds=2):
        self.cleanup_seconds = cleanup_seconds
        self.output = bytearray()
        self.error = bytearray()
        self.selector = selectors.DefaultSelector()
        self.process = None
        try:
            deadline.remaining()
            self.process = subprocess.Popen(
                arguments,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
                env=environment(directory),
                bufsize=0,
            )
            for stream, kind in (
                (self.process.stdout, "out"),
                (self.process.stderr, "err"),
            ):
                os.set_blocking(stream.fileno(), False)
                self.selector.register(stream, selectors.EVENT_READ, kind)
        except (OSError, Failure) as error:
            if self.process is not None:
                self.dispose()
            else:
                self.selector.close()
            if isinstance(error, Failure):
                raise
            raise Failure("PROCESS_START_FAILED") from None

    def pump(self, deadline):
        for key, _ in self.selector.select(min(deadline.remaining(), 0.05)):
            try:
                chunk = os.read(key.fd, 65536)
            except BlockingIOError:
                continue
            if not chunk:
                self.selector.unregister(key.fileobj)
                key.fileobj.close()
                continue
            target = self.output if key.data == "out" else self.error
            target.extend(chunk)
            if len(target) > OUTPUT_LIMIT:
                raise Failure("PROCESS_OUTPUT_LIMIT")
        deadline.remaining()

    def send(self, data: bytes, deadline):
        if self.process.stdin is None:
            raise Failure("PROCESS_INPUT_CLOSED")
        stream = self.process.stdin
        os.set_blocking(stream.fileno(), False)
        view = memoryview(data)
        while view:
            deadline.remaining()
            try:
                count = os.write(stream.fileno(), view)
                view = view[count:]
            except BlockingIOError:
                self.pump(deadline)
            except (BrokenPipeError, OSError):
                raise Failure("PROCESS_INPUT_FAILED") from None

    def close_input(self):
        if self.process.stdin is not None:
            self.process.stdin.close()
            self.process.stdin = None

    def line(self, deadline, limit=8192):
        while b"\n" not in self.output:
            if len(self.output) > limit:
                raise Failure("REPLY_LIMIT")
            if self.process.poll() is not None and not self.selector.get_map():
                raise Failure("REPLY_MISSING")
            self.pump(deadline)
        end = self.output.index(10) + 1
        if end > limit:
            raise Failure("REPLY_LIMIT")
        value = bytes(self.output[:end])
        del self.output[:end]
        return value

    def finish(self, deadline, allowed=(0,)):
        self.close_input()
        while self.process.poll() is None or self.selector.get_map():
            self.pump(deadline)
        self.process.wait(timeout=deadline.remaining())
        deadline.remaining()
        if self.process.returncode not in allowed:
            raise Failure("PROCESS_EXIT_FAILED")
        # A successful direct child must not leave an owned descendant running.
        if self.group_exists():
            raise Failure("PROCESS_DESCENDANTS_REMAIN")
        return bytes(self.output), bytes(self.error)

    def group_exists(self):
        try:
            os.killpg(self.process.pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True

    def dispose(self):
        """Separate finite cleanup allowance. Failure retains the caller's staging directory."""
        if self.process is None:
            return
        cleanup = Deadline(self.cleanup_seconds)
        try:
            if self.group_exists():
                os.killpg(self.process.pid, signal.SIGKILL)
            while self.process.poll() is None or self.group_exists():
                if cleanup.remaining() < 0.01:
                    raise Failure("PROCESS_CLEANUP_UNCERTAIN")
                time.sleep(min(0.01, cleanup.remaining()))
            self.process.wait(timeout=cleanup.remaining())
        except (OSError, subprocess.TimeoutExpired, Failure):
            raise Failure("PROCESS_CLEANUP_UNCERTAIN") from None
        finally:
            self.close_input()
            for key in list(self.selector.get_map().values()):
                self.selector.unregister(key.fileobj)
                key.fileobj.close()
            self.selector.close()
            for stream in (self.process.stdout, self.process.stderr):
                if stream is not None and not stream.closed:
                    stream.close()


def command(arguments, directory, deadline, cleanup_seconds=2):
    process = Process(arguments, directory, deadline, cleanup_seconds)
    try:
        return process.finish(deadline)
    finally:
        process.dispose()


def absolute_path(value):
    path = pathlib.Path(value)
    if not path.is_absolute() or "\x00" in str(path) or ".." in path.parts:
        raise Failure("INVALID_ABSOLUTE_PATH")
    return path


def bundle_identifier(value):
    if len(value) > 255 or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+", value
    ):
        raise Failure("INVALID_BUNDLE_IDENTIFIER")
    return value


def publish(source, destination):
    """Darwin's exclusive rename cannot replace a destination created during compilation."""
    library = ctypes.CDLL(None, use_errno=True)
    rename = library.renamex_np
    rename.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    if rename(os.fsencode(source), os.fsencode(destination), 4) != 0:  # RENAME_EXCL
        code = ctypes.get_errno()
        raise Failure(
            "DESTINATION_EXISTS" if code == errno.EEXIST else "PUBLICATION_FAILED"
        )


def remove_stage(stage, cleanup_seconds):
    # rm is confined to a private directory created by this invocation; it gets its own cleanup bound.
    command(
        ["/bin/rm", "-rf", "--", str(stage)],
        stage.parent,
        Deadline(cleanup_seconds),
        cleanup_seconds,
    )


def verify_bundle(
    executable, expected_identifier, directory, deadline, cleanup_seconds=2
):
    executable = absolute_path(executable)
    expected_identifier = bundle_identifier(expected_identifier)
    if (
        not executable.is_file()
        or executable.is_symlink()
        or executable.resolve() != executable
    ):
        raise Failure("INVALID_BUNDLE_EXECUTABLE")
    bundle = executable.parent.parent.parent
    if (
        executable.name != EXECUTABLE
        or executable.parent.name != "MacOS"
        or bundle.suffix != ".app"
    ):
        raise Failure("INVALID_BUNDLE_EXECUTABLE")
    info = bundle / "Contents" / "Info.plist"
    descriptor = None
    try:
        deadline.remaining()
        descriptor = os.open(
            info, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
        )
        file = os.fstat(descriptor)
        if not stat.S_ISREG(file.st_mode) or file.st_size > 16384:
            raise Failure("INVALID_BUNDLE_METADATA")
        # The descriptor pins the regular inode; cap+1 defends growth after fstat.
        encoded = os.read(descriptor, 16385)
        if len(encoded) > 16384:
            raise Failure("INVALID_BUNDLE_METADATA")
        deadline.remaining()
        metadata = plistlib.loads(encoded)
    except (OSError, ValueError, plistlib.InvalidFileException):
        raise Failure("INVALID_BUNDLE_METADATA") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if (
        not isinstance(metadata, dict)
        or metadata.get("CFBundleIdentifier") != expected_identifier
        or metadata.get("CFBundleExecutable") != EXECUTABLE
    ):
        raise Failure("BUNDLE_IDENTITY_MISMATCH")
    command(
        [
            "/usr/bin/codesign",
            "--verify",
            "--strict",
            "--all-architectures",
            str(bundle),
        ],
        directory,
        deadline,
        cleanup_seconds,
    )
    _, details = command(
        ["/usr/bin/codesign", "--display", "--verbose=2", str(bundle)],
        directory,
        deadline,
        cleanup_seconds,
    )
    if f"Identifier={expected_identifier}".encode() not in details.splitlines():
        raise Failure("BUNDLE_IDENTITY_MISMATCH")
    deadline.remaining()


def build_artifact(
    output, deadline, *, identifier=None, signing_identity=None, cleanup_seconds=2
):
    if platform.system() != "Darwin":
        raise Failure("MACOS_REQUIRED")
    output = absolute_path(output)
    architecture = platform.machine()
    if architecture not in {"arm64", "x86_64"}:
        raise Failure("UNSUPPORTED_ARCHITECTURE")
    bundled = identifier is not None
    if bundled:
        bundle_identifier(identifier)
        if (
            output.suffix != ".app"
            or not signing_identity
            or len(signing_identity) > 512
            or any(ord(c) < 32 for c in signing_identity)
        ):
            raise Failure("INVALID_SIGNING_CONFIGURATION")
    if output.exists() or output.is_symlink():
        raise Failure("DESTINATION_EXISTS")
    if not output.parent.is_dir():
        raise Failure("DESTINATION_PARENT_REQUIRED")
    deadline.remaining()
    stage = pathlib.Path(
        tempfile.mkdtemp(prefix=".plowshare-native-", dir=output.parent)
    )
    safe_cleanup = True
    try:
        source_directory = pathlib.Path(__file__).resolve().parent.parent / "native"
        sources = stage / "sources"
        sources.mkdir(mode=0o700)
        for name in SOURCES:
            deadline.remaining()
            source = source_directory / name
            if source.stat().st_size > OUTPUT_LIMIT:
                raise Failure("SOURCE_LIMIT")
            shutil.copyfile(source, sources / name)
        cache = stage / "cache"
        cache.mkdir(mode=0o700)
        artifact = stage / ("helper.app" if bundled else "helper")
        executable = (
            artifact / "Contents" / "MacOS" / EXECUTABLE if bundled else artifact
        )
        if bundled:
            executable.parent.mkdir(parents=True, mode=0o700)
            metadata = {
                "CFBundleIdentifier": identifier,
                "CFBundleExecutable": EXECUTABLE,
                "CFBundleName": "Plowshare Computer Helper",
                "CFBundlePackageType": "APPL",
                "CFBundleInfoDictionaryVersion": "6.0",
                "CFBundleVersion": "1",
                "CFBundleShortVersionString": "0.1.0",
                "LSMinimumSystemVersion": "14.2",
                "LSUIElement": True,
            }
            (artifact / "Contents" / "Info.plist").write_bytes(plistlib.dumps(metadata))
        command(
            [
                "/usr/bin/xcrun",
                "swiftc",
                "-parse-as-library",
                "-target",
                f"{architecture}-apple-macos14.2",
                "-module-cache-path",
                str(cache),
                *[str(sources / name) for name in SOURCES],
                "-o",
                str(executable),
            ],
            stage,
            deadline,
            cleanup_seconds,
        )
        if bundled:
            command(
                [
                    "/usr/bin/codesign",
                    "--force",
                    "--sign",
                    signing_identity,
                    "--identifier",
                    identifier,
                    "--timestamp=none",
                    str(artifact),
                ],
                stage,
                deadline,
                cleanup_seconds,
            )
            verify_bundle(executable, identifier, stage, deadline, cleanup_seconds)
        # Only the verified artifact survives publication; toolchain/cache cleanup must finish first.
        command(
            ["/bin/rm", "-rf", "--", str(sources), str(cache)],
            stage,
            deadline,
            cleanup_seconds,
        )
        deadline.remaining()
        publish(artifact, output)
        deadline.remaining()
    except Failure as error:
        safe_cleanup = str(error) != "PROCESS_CLEANUP_UNCERTAIN"
        raise
    finally:
        if safe_cleanup:
            remove_stage(stage, cleanup_seconds)


def build(output: pathlib.Path, timeout: float) -> None:
    """Compatibility entry point: standalone P1 compilation, never run or sign it."""
    build_artifact(output, Deadline(timeout))


def main():
    parser = PrivateParser(description=__doc__)
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--output", type=pathlib.Path)
    destination.add_argument("--bundle", type=pathlib.Path)
    parser.add_argument("--bundle-id")
    parser.add_argument(
        "--signing-identity",
        help="Explicit identity; '-' requests development ad-hoc signing",
    )
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--cleanup-timeout", type=float, default=2)
    options = parser.parse_args()
    try:
        Deadline(options.cleanup_timeout)
        if options.cleanup_timeout > 10:
            raise Failure("INVALID_CLEANUP_BUDGET")
        if options.bundle is None and (
            options.bundle_id is not None or options.signing_identity is not None
        ):
            raise Failure("BUNDLE_CONFIGURATION_REQUIRED")
        if options.bundle is not None and (
            options.bundle_id is None or options.signing_identity is None
        ):
            raise Failure("EXPLICIT_SIGNING_CONFIGURATION_REQUIRED")
        build_artifact(
            options.bundle or options.output,
            Deadline(options.timeout),
            identifier=options.bundle_id,
            signing_identity=options.signing_identity,
            cleanup_seconds=options.cleanup_timeout,
        )
    except (Failure, OSError, ValueError) as error:
        # Deliberately omit exception repr: paths, tool output and signing identity can be private.
        code = (
            str(error)
            if isinstance(error, Failure) and re.fullmatch(r"[A-Z_]+", str(error))
            else "BUILD_FAILED"
        )
        print(f"FAIL: {code}; destination may require inspection.", file=sys.stderr)
        return 1
    print(
        "PASS: native artifact built; no helper execution or permission request performed."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
