#!/usr/bin/env python3
"""Package an explicitly signed controlled AppKit fixture; never launch its GUI."""

import importlib.util
import pathlib
import platform
import plistlib
import re
import shutil
import sys
import tempfile

spec = importlib.util.spec_from_file_location(
    "native_fixture_builder", pathlib.Path(__file__).with_name("native-build.py")
)
if spec is None or spec.loader is None:
    raise SystemExit("FAIL: fixture build capability unavailable.")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
EXECUTABLE = "plowshare-native-fixture"


def build_fixture(output, identifier, signing_identity, deadline, cleanup_seconds=2):
    if platform.system() != "Darwin":
        raise builder.Failure("MACOS_REQUIRED")
    output = builder.absolute_path(output)
    builder.bundle_identifier(identifier)
    if (
        output.suffix != ".app"
        or not signing_identity
        or len(signing_identity) > 512
        or any(ord(character) < 32 for character in signing_identity)
    ):
        raise builder.Failure("INVALID_SIGNING_CONFIGURATION")
    architecture = platform.machine()
    if architecture not in {"arm64", "x86_64"}:
        raise builder.Failure("UNSUPPORTED_ARCHITECTURE")
    if output.exists() or output.is_symlink():
        raise builder.Failure("DESTINATION_EXISTS")
    if not output.parent.is_dir():
        raise builder.Failure("DESTINATION_PARENT_REQUIRED")
    deadline.remaining()
    stage = pathlib.Path(
        tempfile.mkdtemp(prefix=".plowshare-native-fixture-", dir=output.parent)
    )
    safe_cleanup = True
    try:
        source = (
            pathlib.Path(__file__).resolve().parent.parent
            / "native"
            / "fixture"
            / "ControlledNativeFixture.swift"
        )
        if source.stat().st_size > builder.OUTPUT_LIMIT:
            raise builder.Failure("SOURCE_LIMIT")
        staged_source = stage / "ControlledNativeFixture.swift"
        shutil.copyfile(source, staged_source)
        cache = stage / "cache"
        cache.mkdir(mode=0o700)
        artifact = stage / "fixture.app"
        executable = artifact / "Contents" / "MacOS" / EXECUTABLE
        executable.parent.mkdir(parents=True, mode=0o700)
        metadata = {
            "CFBundleIdentifier": identifier,
            "CFBundleExecutable": EXECUTABLE,
            "CFBundleName": "Plowshare Controlled Native Fixture",
            "CFBundlePackageType": "APPL",
            "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleVersion": "1",
            "CFBundleShortVersionString": "0.1.0",
            "LSMinimumSystemVersion": "14.2",
        }
        (artifact / "Contents" / "Info.plist").write_bytes(plistlib.dumps(metadata))
        builder.command(
            [
                "/usr/bin/xcrun",
                "swiftc",
                "-parse-as-library",
                "-target",
                f"{architecture}-apple-macos14.2",
                "-module-cache-path",
                str(cache),
                str(staged_source),
                "-o",
                str(executable),
            ],
            stage,
            deadline,
            cleanup_seconds,
        )
        builder.command(
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
        builder.command(
            [
                "/usr/bin/codesign",
                "--verify",
                "--strict",
                "--all-architectures",
                str(artifact),
            ],
            stage,
            deadline,
            cleanup_seconds,
        )
        _, details = builder.command(
            [
                "/usr/bin/codesign",
                "--display",
                "--verbose=2",
                str(artifact),
            ],
            stage,
            deadline,
            cleanup_seconds,
        )
        if f"Identifier={identifier}".encode() not in details.splitlines():
            raise builder.Failure("BUNDLE_IDENTITY_MISMATCH")
        builder.command(
            ["/bin/rm", "-rf", "--", str(staged_source), str(cache)],
            stage,
            deadline,
            cleanup_seconds,
        )
        deadline.remaining()
        builder.publish(artifact, output)
        deadline.remaining()
    except builder.Failure as error:
        safe_cleanup = str(error) != "PROCESS_CLEANUP_UNCERTAIN"
        raise
    finally:
        if safe_cleanup:
            builder.remove_stage(stage, cleanup_seconds)


def main():
    parser = builder.PrivateParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=pathlib.Path)
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument(
        "--signing-identity",
        required=True,
        help="Explicit identity; '-' is development ad-hoc signing",
    )
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--cleanup-timeout", type=float, default=2)
    options = parser.parse_args()
    try:
        builder.Deadline(options.cleanup_timeout)
        if options.cleanup_timeout > 10:
            raise builder.Failure("INVALID_CLEANUP_BUDGET")
        build_fixture(
            options.bundle,
            options.bundle_id,
            options.signing_identity,
            builder.Deadline(options.timeout),
            options.cleanup_timeout,
        )
    except (builder.Failure, OSError, ValueError) as error:
        code = (
            str(error)
            if isinstance(error, builder.Failure)
            and re.fullmatch(r"[A-Z_]+", str(error))
            else "FIXTURE_BUILD_FAILED"
        )
        print(
            f"FAIL: {code}; fixture destination may require inspection.",
            file=sys.stderr,
        )
        return 1
    print("PASS: controlled fixture packaged; no GUI, input or capture started.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
