#!/usr/bin/env python3
"""Portable deterministic tests; never sign, capture, post input or claim native ownership."""

import contextlib
import importlib.util
import io
import json
import math
import os
import pathlib
import shutil
import stat
import subprocess
from types import SimpleNamespace
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(
        name, pathlib.Path(__file__).with_name(filename)
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


build = load("native_build_test", "native-build.py")
check = load("native_check_test", "native-check.py")


class NativeScriptsTest(unittest.TestCase):
    def test_deadline_is_finite_monotonic_and_inherited(self):
        for invalid in (0, -1, math.inf, math.nan, 301):
            with self.assertRaises(build.Failure):
                build.Deadline(invalid)
        with patch.object(build.time, "monotonic", return_value=10):
            parent = build.Deadline(2)
        with patch.object(build.time, "monotonic", return_value=11):
            child = build.Deadline(30, parent)
            self.assertEqual(child.remaining(), 1)
        with patch.object(build.time, "monotonic", return_value=12):
            with self.assertRaisesRegex(build.Failure, "DEADLINE_EXPIRED"):
                child.remaining()

    def test_expired_budget_never_launches_process_at_any_entrypoint(self):
        with patch.object(build.time, "monotonic", return_value=1):
            deadline = build.Deadline(1)
            checked_deadline = check.builder.Deadline(1)
        with patch.object(build.time, "monotonic", return_value=3), patch.object(
            build.subprocess, "Popen"
        ) as launch:
            for operation in (
                lambda: build.Process(
                    ["unused"], pathlib.Path("/private/tmp"), deadline
                ),
                lambda: build.command(
                    ["unused"], pathlib.Path("/private/tmp"), deadline
                ),
                lambda: check.invoke(
                    pathlib.Path("/unused"),
                    [],
                    b"",
                    pathlib.Path("/private/tmp"),
                    checked_deadline,
                ),
                lambda: check.check_preflight(
                    pathlib.Path("/unused"),
                    pathlib.Path("/private/tmp"),
                    checked_deadline,
                ),
            ):
                with self.assertRaisesRegex(
                    (build.Failure, check.builder.Failure), "DEADLINE_EXPIRED"
                ):
                    operation()
            launch.assert_not_called()

    def test_partial_process_setup_failure_reaps_spawned_child_and_closes_streams(self):
        children = []
        original = subprocess.Popen

        def launch(*args, **kwargs):
            child = original(*args, **kwargs)
            children.append(child)
            return child

        with tempfile.TemporaryDirectory() as directory, patch.object(
            build.subprocess, "Popen", side_effect=launch
        ), patch.object(
            build.os, "set_blocking", side_effect=OSError("private setup detail")
        ):
            with self.assertRaisesRegex(build.Failure, "PROCESS_START_FAILED"):
                build.Process(
                    [sys.executable, "-c", "import time; time.sleep(30)"],
                    pathlib.Path(directory),
                    build.Deadline(2),
                )
        self.assertEqual(len(children), 1)
        child = children[0]
        self.assertIsNotNone(child.returncode)
        with self.assertRaises(ProcessLookupError):
            os.killpg(child.pid, 0)
        self.assertTrue(child.stdout.closed)
        self.assertTrue(child.stderr.closed)

    def test_bundle_metadata_read_is_bounded_after_stat_and_refuses_symlink_and_fifo(
        self,
    ):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory).resolve()
            executable = root / "Helper.app" / "Contents" / "MacOS" / build.EXECUTABLE
            executable.parent.mkdir(parents=True)
            executable.write_bytes(b"fake executable")
            metadata = executable.parent.parent / "Info.plist"
            metadata.write_bytes(b"x" * 20000)
            original = os.read
            reads = []

            def bounded_read(descriptor, count):
                reads.append(count)
                return original(descriptor, count)

            with patch.object(
                build.os,
                "fstat",
                return_value=SimpleNamespace(st_mode=stat.S_IFREG, st_size=1),
            ), patch.object(build.os, "read", side_effect=bounded_read), patch.object(
                build, "command"
            ) as command:
                with self.assertRaisesRegex(build.Failure, "INVALID_BUNDLE_METADATA"):
                    build.verify_bundle(
                        executable, "org.fixture.Helper", root, build.Deadline(2)
                    )
                command.assert_not_called()
            self.assertEqual(reads, [16385])
            metadata.unlink()
            metadata.symlink_to(executable)
            with self.assertRaisesRegex(build.Failure, "INVALID_BUNDLE_METADATA"):
                build.verify_bundle(
                    executable, "org.fixture.Helper", root, build.Deadline(2)
                )
            metadata.unlink()
            os.mkfifo(metadata)
            with self.assertRaisesRegex(build.Failure, "INVALID_BUNDLE_METADATA"):
                build.verify_bundle(
                    executable, "org.fixture.Helper", root, build.Deadline(2)
                )

    def test_private_environment_does_not_forward_credentials(self):
        with patch.dict(
            os.environ, {"SECRET_TOKEN": "private", "HOME": "/private/home"}
        ):
            environment = build.environment(pathlib.Path("/private/tmp/stage"))
        self.assertEqual(set(environment), {"PATH", "TMPDIR", "LC_ALL"})
        self.assertNotIn("private/home", str(environment))

    def test_existing_destination_is_never_compiled_or_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "helper"
            destination.write_text("original")
            with patch.object(
                build.platform, "system", return_value="Darwin"
            ), patch.object(
                build.platform, "machine", return_value="arm64"
            ), patch.object(
                build, "command"
            ) as command:
                with self.assertRaisesRegex(build.Failure, "DESTINATION_EXISTS"):
                    build.build_artifact(destination, build.Deadline(2))
                command.assert_not_called()
            self.assertEqual(destination.read_text(), "original")

    def test_bundle_stages_share_one_budget_and_publish_after_cache_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "helper.app"
            deadline = build.Deadline(2)
            stages = []

            def command(arguments, stage, passed_deadline, cleanup_seconds=2):
                if arguments[0] == "/usr/bin/xcrun":
                    pathlib.Path(arguments[-1]).write_bytes(b"fake-test-binary")
                    stages.append("compile")
                elif arguments[0] == "/usr/bin/codesign":
                    stages.append("sign" if "--sign" in arguments else "verify")
                    if "--display" in arguments:
                        return b"", b"Identifier=org.fixture.Helper\n"
                else:
                    stages.append("cleanup")
                    for name in arguments[3:]:
                        shutil.rmtree(name)
                if "--display" not in arguments and str(stage) != str(
                    destination.parent
                ):
                    self.assertIs(passed_deadline, deadline)
                return b"", b""

            def publish(source, selected):
                self.assertEqual(stages[-1], "cleanup")
                self.assertFalse((source.parent / "cache").exists())
                self.assertFalse((source.parent / "sources").exists())
                source.rename(selected)
                stages.append("publish")

            with patch.object(
                build.platform, "system", return_value="Darwin"
            ), patch.object(
                build.platform, "machine", return_value="arm64"
            ), patch.object(
                build, "command", side_effect=command
            ), patch.object(
                build, "publish", side_effect=publish
            ):
                build.build_artifact(
                    destination,
                    deadline,
                    identifier="org.fixture.Helper",
                    signing_identity="-",
                )
            self.assertEqual(
                stages,
                [
                    "compile",
                    "sign",
                    "verify",
                    "verify",
                    "cleanup",
                    "publish",
                    "cleanup",
                ],
            )
            self.assertTrue(
                (destination / "Contents/MacOS" / build.EXECUTABLE).is_file()
            )

    def test_uncertain_compiler_cleanup_retains_stage_and_never_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "helper"
            with patch.object(
                build.platform, "system", return_value="Darwin"
            ), patch.object(
                build.platform, "machine", return_value="arm64"
            ), patch.object(
                build, "command", side_effect=build.Failure("PROCESS_CLEANUP_UNCERTAIN")
            ), patch.object(
                build, "publish"
            ) as publish, patch.object(
                build, "remove_stage"
            ) as remove:
                with self.assertRaisesRegex(build.Failure, "PROCESS_CLEANUP_UNCERTAIN"):
                    build.build_artifact(destination, build.Deadline(2))
                publish.assert_not_called()
                remove.assert_not_called()
            self.assertFalse(destination.exists())
            self.assertEqual(
                len(list(destination.parent.glob(".plowshare-native-*"))), 1
            )

    def test_unknown_signing_configuration_never_starts_toolchain(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "helper.app"
            with patch.object(
                build.platform, "system", return_value="Darwin"
            ), patch.object(
                build.platform, "machine", return_value="arm64"
            ), patch.object(
                build, "command"
            ) as command:
                for identifier, identity in (
                    ("not-a-bundle", "-"),
                    ("org.fixture.Helper", ""),
                    ("org.fixture.Helper", "secret\nidentity"),
                ):
                    with self.assertRaises(build.Failure):
                        build.build_artifact(
                            destination,
                            build.Deadline(2),
                            identifier=identifier,
                            signing_identity=identity,
                        )
                command.assert_not_called()

    def test_timeout_reaps_actual_process_group_before_return(self):
        with tempfile.TemporaryDirectory() as directory:
            process = build.Process(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                pathlib.Path(directory),
                build.Deadline(2),
            )
            started = time.monotonic()
            try:
                with self.assertRaisesRegex(build.Failure, "DEADLINE_EXPIRED"):
                    process.finish(build.Deadline(0.05))
            finally:
                process.dispose()
            self.assertIsNotNone(process.process.returncode)
            self.assertFalse(process.group_exists())
            self.assertLess(time.monotonic() - started, 3)

    def test_output_is_bounded_and_private_diagnostics_not_exposed(self):
        with tempfile.TemporaryDirectory() as directory:
            process = build.Process(
                [sys.executable, "-c", "import os; os.write(2,b'private'*300000)"],
                pathlib.Path(directory),
                build.Deadline(2),
            )
            try:
                with self.assertRaisesRegex(
                    build.Failure, "PROCESS_OUTPUT_LIMIT"
                ) as caught:
                    process.finish(build.Deadline(2))
                self.assertNotIn("private", str(caught.exception))
            finally:
                process.dispose()

    def test_cli_errors_never_echo_private_arguments_or_subprocess_exception(self):
        error = io.StringIO()
        with patch.object(
            sys,
            "argv",
            ["native-build.py", "--output", "/private/secret", "--timeout", "SECRET"],
        ), contextlib.redirect_stderr(error):
            with self.assertRaises(SystemExit):
                build.main()
        self.assertNotIn("SECRET", error.getvalue())
        self.assertNotIn("/private/secret", error.getvalue())
        error = io.StringIO()
        with patch.object(
            sys, "argv", ["native-build.py", "--output", "/private/secret"]
        ), patch.object(
            build, "build_artifact", side_effect=OSError("credential-SECRET")
        ), contextlib.redirect_stderr(
            error
        ):
            self.assertEqual(build.main(), 1)
        self.assertNotIn("credential", error.getvalue())
        self.assertNotIn("/private/secret", error.getvalue())

    def test_json_and_permission_boundaries_reject_unknown_duplicate_or_inferred_flags(
        self,
    ):
        for invalid in (b'{"x":1,"x":2}', b'{"x":1,"\\u0078":2}', b'{"x":NaN}', b"[]"):
            with self.assertRaises(check.builder.Failure):
                value = check.strict_json(invalid)
                check.fields(value, ("x",))
        value = {
            "permissions": {
                "screenCapture": True,
                "accessibility": False,
                "eventPosting": False,
                "eventListening": True,
            },
            "availability": "unknown",
            "secureInput": "clear",
            "globalFence": "held",
        }
        result = check.preflight(value)
        self.assertFalse(result.event_posting)
        self.assertTrue(result.event_listening)
        self.assertEqual(result.availability, "unknown")
        for change in (
            {"unexpected": True},
            {"availability": "inferred-ready"},
            {"permissions": {**value["permissions"], "eventPosting": 1}},
        ):
            with self.assertRaises(check.builder.Failure):
                check.preflight({**value, **change})

    def test_protocol_replies_require_utf8_and_ready_requires_independent_proofs(self):
        for encoding in ("utf-16", "utf-32"):
            with self.assertRaises(check.builder.Failure):
                check.strict_json('{"x":1}'.encode(encoding))
        value = {
            "permissions": {
                "screenCapture": True,
                "accessibility": True,
                "eventPosting": True,
                "eventListening": True,
            },
            "availability": "ready",
            "secureInput": "clear",
            "globalFence": "clear",
        }
        self.assertEqual(check.preflight(value).availability, "ready")
        for permission in value["permissions"]:
            with self.assertRaises(check.builder.Failure):
                check.preflight(
                    {
                        **value,
                        "permissions": {**value["permissions"], permission: False},
                    }
                )
        for secure in ("active", "unknown"):
            with self.assertRaises(check.builder.Failure):
                check.preflight({**value, "secureInput": secure})
        self.assertEqual(
            check.preflight({**value, "availability": "unavailable"}).availability,
            "unavailable",
        )

    def test_only_known_bounded_memory_diagnostics_are_accepted(self):
        diagnostic = (
            "2026-11-17 12:34:56.789 helper[123:456] "
            "Error received in message reply handler: Connection invalid\n"
        )
        self.assertTrue(check.memory_diagnostics(diagnostic.encode()))
        self.assertFalse(check.memory_diagnostics(b""))
        for invalid in (
            b"private unexpected diagnostic",
            diagnostic.encode() * 3,
            b"\xff",
            b"SECRET Error received in message reply handler: Connection invalid",
        ):
            with self.assertRaises(check.builder.Failure):
                check.memory_diagnostics(invalid)

    def test_configured_diagnostics_do_not_compile_or_run_memory_fixture(self):
        output = io.StringIO()
        status = check.Preflight(
            False, False, False, False, "unknown", "clear", "clear"
        )
        with patch.object(
            sys,
            "argv",
            [
                "native-check.py",
                "--executable",
                "/private/helper.app/Contents/MacOS/plowshare-computer-helper",
                "--bundle-id",
                "org.fixture.Helper",
            ],
        ), patch.object(check.platform, "system", return_value="Darwin"), patch.object(
            check.builder, "verify_bundle"
        ) as verify, patch.object(
            check, "check_preflight", return_value=status
        ) as preflight, patch.object(
            check.builder, "build_artifact"
        ) as compile_helper, patch.object(
            check, "check_memory"
        ) as memory, contextlib.redirect_stdout(
            output
        ):
            self.assertEqual(check.main(), 0)
        verify.assert_called_once()
        preflight.assert_called_once()
        compile_helper.assert_not_called()
        memory.assert_not_called()
        self.assertIn("Readiness=unknown", output.getvalue())

    def test_memory_reply_requires_exact_owned_main_loop_evidence(self):
        reply = {
            "version": "computer-control-native/self-test/1",
            "ok": True,
            "unicodeScalars": 10,
            "utf16Units": 13,
            "eventRoundTrips": 20,
            "pointerRoundTrip": True,
            "keyRoundTrip": True,
            "scrollRoundTrip": True,
            "cancelledBudgetRejected": True,
            "protocolFramesChecked": 19,
            "acknowledgementCasesChecked": 8,
            "executorCasesChecked": 6,
            "readinessUICasesChecked": 29,
            "readinessCasesChecked": 67,
            "mainLoopCasesChecked": 2,
        }

        def checked(value):
            def invoke(*args, diagnostic_flags=None, **kwargs):
                self.assertEqual(args[1], ["--check-input-memory"])
                diagnostic_flags.append(False)
                return json.dumps(value).encode()

            with patch.object(check, "invoke", side_effect=invoke):
                return check.check_memory(
                    pathlib.Path("/unused"),
                    pathlib.Path("/private/tmp"),
                    check.builder.Deadline(2),
                )

        self.assertFalse(checked(reply))
        for invalid in (
            {
                key: value
                for key, value in reply.items()
                if key != "mainLoopCasesChecked"
            },
            {**reply, "mainLoopCasesChecked": True},
            {**reply, "mainLoopCasesChecked": 1},
            {**reply, "mainLoopCasesChecked": 3},
            {**reply, "unknown": True},
        ):
            with self.assertRaises(check.builder.Failure):
                checked(invalid)

    def test_legacy_capture_and_rejected_v2_control_are_both_checked_without_effects(
        self,
    ):
        packets = []

        def invoke(executable, arguments, request, directory, overall, allowed=(0,)):
            value = json.loads(request)
            packets.append((arguments, value, allowed))
            if arguments:
                self.assertEqual(arguments, ["--control"])
                self.assertEqual(value["operation"], "preflight")
                self.assertEqual(allowed, (1,))
                return b""
            self.assertEqual(value["operation"], "status")
            try:
                check.strict_json(request)
                unique_fields = True
            except check.builder.Failure:
                unique_fields = False
            valid = (
                unique_fields
                and set(value) == {"version", "operation", "timeoutMs"}
                and (
                    value["version"] == check.V1
                    and type(value["timeoutMs"]) is int
                    and value["timeoutMs"] > 0
                )
            )
            if valid:
                return json.dumps(
                    {
                        "version": check.V1,
                        "ok": True,
                        "operation": "status",
                        "screenCaptureAllowed": False,
                        "accessibilityAllowed": False,
                    }
                ).encode()
            return json.dumps(
                {"version": check.V1, "ok": False, "code": "INVALID_REQUEST"}
            ).encode()

        with patch.object(check, "invoke", side_effect=invoke):
            deadline = check.builder.Deadline(2)
            check.check_v1(
                pathlib.Path("/unused"), pathlib.Path("/private/tmp"), deadline
            )
            check.check_control_invalid(
                pathlib.Path("/unused"), pathlib.Path("/private/tmp"), deadline
            )
        self.assertTrue(any(not arguments for arguments, _, _ in packets))
        self.assertTrue(
            any(
                arguments and value["version"] == "computer-control-native/2"
                for arguments, value, _ in packets
            )
        )

    @unittest.skipUnless(sys.platform == "darwin", "Darwin exclusive rename primitive")
    def test_atomic_publication_refuses_racing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source"
            destination = pathlib.Path(directory) / "destination"
            source.mkdir()
            destination.mkdir()
            with self.assertRaisesRegex(build.Failure, "DESTINATION_EXISTS"):
                build.publish(source, destination)
            self.assertTrue(source.is_dir())
            self.assertTrue(destination.is_dir())


if __name__ == "__main__":
    unittest.main()
