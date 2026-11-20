#!/usr/bin/env python3
"""Compile the permission-bound helper without running or signing it."""

import argparse
import pathlib
import platform
import subprocess
import tempfile


def build(output: pathlib.Path, timeout: float) -> None:
    if platform.system() != "Darwin":
        raise SystemExit("The ScreenCaptureKit helper requires macOS.")
    if not output.is_absolute():
        raise SystemExit("--output must be an explicit absolute path.")
    architecture = platform.machine()
    if architecture not in {"arm64", "x86_64"}:
        raise SystemExit("Unsupported macOS architecture.")
    source = pathlib.Path(__file__).resolve().parent.parent / "native" / "ScreenCaptureHelper.swift"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="plowshare-computer-swift-") as cache:
        subprocess.run(
            [
                "xcrun", "swiftc", "-parse-as-library", "-target",
                f"{architecture}-apple-macos14.2", "-module-cache-path", cache,
                str(source), "-o", str(output),
            ],
            check=True,
            timeout=timeout,
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--timeout", type=float, default=60)
    options = parser.parse_args()
    if not 0 < options.timeout <= 300:
        parser.error("--timeout must be positive and at most 300 seconds")
    build(options.output, options.timeout)
