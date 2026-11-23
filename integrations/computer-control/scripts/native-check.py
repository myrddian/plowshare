#!/usr/bin/env python3
"""Explicit macOS compile/protocol check; never captures pixels or requests permission."""

import importlib.util
import json
import pathlib
import platform
import subprocess
import tempfile


def main() -> None:
    if platform.system() != "Darwin":
        raise SystemExit("The native helper check requires an explicit macOS session.")
    source = pathlib.Path(__file__).with_name("native-build.py")
    spec = importlib.util.spec_from_file_location("native_build", source)
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load native build entry point")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    with tempfile.TemporaryDirectory(prefix="plowshare-computer-native-check-") as directory:
        executable = pathlib.Path(directory) / "capture-helper"
        builder.build(executable, 60)

        def invoke_bytes(request: bytes) -> dict:
            result = subprocess.run(
                [str(executable)], input=request,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                check=True, timeout=3,
            )
            assert not result.stderr, "Helper emitted unexpected diagnostic text"
            assert len(result.stdout) < 8192, "Non-capture response exceeded metadata bound"
            return json.loads(result.stdout)

        def invoke(request: object) -> dict:
            return invoke_bytes(json.dumps(request).encode())

        status = invoke({"version": "computer-control-native/1", "operation": "status", "timeoutMs": 1000})
        assert status["ok"] is True and status["operation"] == "status"
        assert isinstance(status["screenCaptureAllowed"], bool)
        assert isinstance(status["accessibilityAllowed"], bool)
        for extra in ({"extra": True}, {"timeoutMs": 0}, {"timeoutMs": True}, {"version": "invalid"}):
            response = invoke({"version": "computer-control-native/1", "operation": "status", "timeoutMs": 1000, **extra})
            assert response == {"version": "computer-control-native/1", "ok": False, "code": "INVALID_REQUEST"}
        for request in (
            b'{"version":"computer-control-native/1","operation":"status","operation":"capture","timeoutMs":1000}',
            b'{"version":"computer-control-native/1","operation":"status","oper\\u0061tion":"capture","timeoutMs":1000}',
        ):
            assert invoke_bytes(request) == {"version": "computer-control-native/1", "ok": False, "code": "INVALID_REQUEST"}
        print("PASS: native helper compiled for macOS 14.2; strict status protocol and no-prompt permission preflight.")
        print(f"Screen capture allowed: {status['screenCaptureAllowed']}; accessibility allowed: {status['accessibilityAllowed']}. No pixels captured.")


if __name__ == "__main__":
    main()
