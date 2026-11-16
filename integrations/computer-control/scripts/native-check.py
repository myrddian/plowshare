#!/usr/bin/env python3
"""Check native protocols without capture, input posting or permission prompts."""

from dataclasses import dataclass
import importlib.util
import json
import pathlib
import re
import platform
import sys
import tempfile
import uuid

source = pathlib.Path(__file__).with_name("native-build.py")
spec = importlib.util.spec_from_file_location("native_build", source)
if spec is None or spec.loader is None:
    raise SystemExit("FAIL: native build entry point unavailable.")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

V1 = "computer-control-native/1"
V2 = "computer-control-native/2"


@dataclass(frozen=True)
class Preflight:
    screen_capture: bool
    accessibility: bool
    event_posting: bool
    event_listening: bool
    availability: str
    secure_input: str
    global_fence: str


def require(condition):
    if not condition:
        raise builder.Failure("PROTOCOL_CHECK_FAILED")


def fields(value, names):
    require(type(value) is dict and set(value) == set(names))
    return value


def strict_json(data):
    require(len(data) <= 8192)

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result

    def invalid(_):
        raise builder.Failure("INVALID_JSON_REPLY")

    try:
        return json.loads(
            data.decode("utf-8", errors="strict"),
            object_pairs_hook=pairs,
            parse_constant=invalid,
        )
    except (ValueError, UnicodeError):
        raise builder.Failure("INVALID_JSON_REPLY") from None


def preflight(value):
    result = fields(
        value, ("permissions", "availability", "secureInput", "globalFence")
    )
    permissions = fields(
        result["permissions"],
        ("screenCapture", "accessibility", "eventPosting", "eventListening"),
    )
    require(all(type(item) is bool for item in permissions.values()))
    require(
        result["availability"]
        in ("ready", "locked", "inactive", "unknown", "unavailable")
    )
    require(result["secureInput"] in ("clear", "active", "unknown"))
    require(result["globalFence"] in ("clear", "held", "unknown"))
    if result["availability"] == "ready":
        require(all(permissions.values()) and result["secureInput"] == "clear")
    return Preflight(
        permissions["screenCapture"],
        permissions["accessibility"],
        permissions["eventPosting"],
        permissions["eventListening"],
        result["availability"],
        result["secureInput"],
        result["globalFence"],
    )


def frame(operation, deadline):
    return {
        "version": V2,
        "requestId": str(uuid.uuid4()),
        "operation": operation,
        "timeoutMs": max(1, min(1000, int(deadline.remaining() * 1000))),
    }


def reply(process, request, deadline):
    process.send(json.dumps(request).encode() + b"\n", deadline)
    value = fields(
        strict_json(process.line(deadline)),
        ("version", "requestId", "operation", "result"),
    )
    require(
        value["version"] == V2
        and value["requestId"] == request["requestId"]
        and value["operation"] == request["operation"]
    )
    return value["result"]


def check_preflight(executable, directory, overall):
    # Sequential framing matters: EOF or stop must not cancel the preflight in flight.
    deadline = builder.Deadline(2, overall)
    process = builder.Process([str(executable), "--control"], directory, deadline)
    try:
        status = preflight(reply(process, frame("preflight", deadline), deadline))
        deadline = builder.Deadline(2, overall)
        cleanup = fields(
            reply(process, frame("stop", deadline), deadline),
            ("physicalSettlement", "heldInputsReleased"),
        )
        require(
            cleanup["physicalSettlement"] == "settled"
            and cleanup["heldInputsReleased"] is True
        )
        output, error = process.finish(deadline)
        require(not output and not error)
        return status
    finally:
        process.dispose()


def memory_diagnostics(data):
    if not data:
        return False
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeError:
        raise builder.Failure("UNEXPECTED_FRAMEWORK_DIAGNOSTIC") from None
    suffixes = (
        "Error received in message reply handler: Connection invalid",
        "Connection Invalid error for service com.apple.hiservices-xpcservice.",
    )
    prefix = r"(?:\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3,6} [^\r\n\[\]]{1,256}\[\d+:\d+\] )?"
    require(1 <= len(lines) <= 2)
    require(
        all(
            any(re.fullmatch(prefix + re.escape(suffix), line) for suffix in suffixes)
            for line in lines
        )
    )
    return True


def invoke(
    executable,
    arguments,
    request,
    directory,
    overall,
    allowed=(0,),
    diagnostic_flags=None,
):
    deadline = builder.Deadline(2, overall)
    process = builder.Process([str(executable), *arguments], directory, deadline)
    try:
        if request:
            process.send(request, deadline)
        output, error = process.finish(deadline, allowed)
        require(len(output) <= 8192 and len(error) <= 8192)
        if diagnostic_flags is None:
            require(not error)
        else:
            diagnostic_flags.append(memory_diagnostics(error))
        return output
    finally:
        process.dispose()


def check_v1(executable, directory, overall):
    request = {"version": V1, "operation": "status", "timeoutMs": 1000}
    status = fields(
        strict_json(
            invoke(executable, [], json.dumps(request).encode(), directory, overall)
        ),
        ("version", "ok", "operation", "screenCaptureAllowed", "accessibilityAllowed"),
    )
    require(
        status["version"] == V1
        and status["ok"] is True
        and status["operation"] == "status"
    )
    require(
        type(status["screenCaptureAllowed"]) is bool
        and type(status["accessibilityAllowed"]) is bool
    )
    invalid = [
        json.dumps({**request, **extra}).encode()
        for extra in (
            {"extra": True},
            {"timeoutMs": 0},
            {"timeoutMs": True},
            {"version": "invalid"},
        )
    ]
    invalid.extend(
        (
            b'{"version":"computer-control-native/1","operation":"status","operation":"status","timeoutMs":1000}',
            b'{"version":"computer-control-native/1","operation":"status","oper\\u0061tion":"status","timeoutMs":1000}',
        )
    )
    for packet in invalid:
        response = fields(
            strict_json(invoke(executable, [], packet, directory, overall)),
            ("version", "ok", "code"),
        )
        require(
            response["version"] == V1
            and response["ok"] is False
            and response["code"] == "INVALID_REQUEST"
        )


def check_v2_invalid(executable, directory, overall):
    request = frame("preflight", overall)
    invalid = [
        json.dumps({**request, **extra}).encode()
        for extra in (
            {"extra": True},
            {"timeoutMs": 0},
            {"timeoutMs": True},
            {"version": "invalid"},
        )
    ]
    duplicate = json.dumps(request).replace(
        '"operation": "preflight"',
        '"operation":"preflight","oper\\u0061tion":"preflight"',
    )
    invalid.append(duplicate.encode())
    for packet in invalid:
        # Every malformed frame is preflight-only, even if validation were broken.
        require(
            not invoke(
                executable, ["--control"], packet + b"\n", directory, overall, (1,)
            )
        )


def check_memory(executable, directory, overall):
    diagnostics = []
    value = fields(
        strict_json(
            invoke(
                executable,
                ["--check-input-memory"],
                b"",
                directory,
                overall,
                diagnostic_flags=diagnostics,
            )
        ),
        (
            "version",
            "ok",
            "unicodeScalars",
            "utf16Units",
            "eventRoundTrips",
            "pointerRoundTrip",
            "keyRoundTrip",
            "scrollRoundTrip",
            "cancelledBudgetRejected",
            "protocolFramesChecked",
            "acknowledgementCasesChecked",
            "executorCasesChecked",
        ),
    )
    fixture = "Aé\n\t🙂e\u0301👩‍💻"
    require(
        value["version"] == "computer-control-native/self-test/1"
        and value["ok"] is True
    )
    expected = {
        "unicodeScalars": len(fixture),
        "utf16Units": len(fixture.encode("utf-16-le")) // 2,
        "eventRoundTrips": len(fixture) * 2,
        "protocolFramesChecked": 19,
        "acknowledgementCasesChecked": 8,
        "executorCasesChecked": 6,
    }
    require(
        all(
            type(value[key]) is int and value[key] == count
            for key, count in expected.items()
        )
    )
    require(
        all(
            value[key] is True
            for key in (
                "pointerRoundTrip",
                "keyRoundTrip",
                "scrollRoundTrip",
                "cancelledBudgetRejected",
            )
        )
    )
    return diagnostics[0]


def main():
    parser = builder.PrivateParser(description=__doc__)
    parser.add_argument(
        "--executable",
        type=pathlib.Path,
        help="Configured packaged executable; skips compilation and temporary fixture tests",
    )
    parser.add_argument(
        "--bundle-id",
        help="Expected configured bundle identifier (required with --executable)",
    )
    parser.add_argument("--timeout", type=float, default=120)
    options = parser.parse_args()
    directory = None
    safe_cleanup = True
    failed = False
    status = None
    heading = ""
    memory_diagnostics = False
    try:
        require(platform.system() == "Darwin")
        require((options.executable is None) == (options.bundle_id is None))
        overall = builder.Deadline(options.timeout)
        directory = pathlib.Path(tempfile.mkdtemp(prefix="plowshare-native-check-"))
        if options.executable is not None:
            executable = builder.absolute_path(options.executable)
            builder.verify_bundle(executable, options.bundle_id, directory, overall)
            status = check_preflight(executable, directory, overall)
            heading = "PASS: configured bundle signature and read-only protocol-2 preflight/stop."
        else:
            executable = directory / "helper"
            builder.build_artifact(executable, overall)
            check_v1(executable, directory, overall)
            status = check_preflight(executable, directory, overall)
            check_v2_invalid(executable, directory, overall)
            memory_diagnostics = check_memory(executable, directory, overall)
            heading = "PASS: temporary compilation, strict protocols 1/2 and no-post memory fixtures."
        overall.remaining()
    except builder.Failure as error:
        safe_cleanup = str(error) != "PROCESS_CLEANUP_UNCERTAIN"
        code = str(error) if re.fullmatch(r"[A-Z_]+", str(error)) else "CHECK_FAILED"
        print(f"FAIL: {code}; no configured/live control claim.", file=sys.stderr)
        failed = True
    except (OSError, ValueError):
        print("FAIL: native check setup did not complete.", file=sys.stderr)
        failed = True
    finally:
        if directory is not None and safe_cleanup:
            try:
                builder.remove_stage(directory, 2)
            except (builder.Failure, OSError):
                # Do not report PASS when the physical cleanup outcome is unknown.
                print(
                    "FAIL: native check cleanup uncertain; private staging retained.",
                    file=sys.stderr,
                )
                failed = True
    if failed or status is None:
        return 1
    print(heading)
    print(
        f"Permissions: capture={status.screen_capture}, accessibility={status.accessibility}, "
        f"posting={status.event_posting}, listening={status.event_listening}."
    )
    print(
        f"Readiness={status.availability}; secure input={status.secure_input}; global fence={status.global_fence}."
    )
    if options.executable is None:
        print(
            f"No-post memory fixture emitted bounded framework diagnostics: {memory_diagnostics} (content suppressed)."
        )
    print(
        "No pixels captured, input posted, consent requested or production ownership acquired."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
