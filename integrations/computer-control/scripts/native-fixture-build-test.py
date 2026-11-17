#!/usr/bin/env python3
"""Fixture packaging regressions; no real signing, GUI, capture or input."""

import contextlib
import importlib.util
import io
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "fixture_build", pathlib.Path(__file__).with_name("native-fixture-build.py")
)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
builder = fixture.builder


class FixtureBuildTest(unittest.TestCase):
    def test_existing_destination_is_unchanged_without_tool_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "Fixture.app"
            destination.mkdir()
            marker = destination / "untouched"
            marker.write_text("original")
            with patch.object(
                fixture.platform, "system", return_value="Darwin"
            ), patch.object(
                fixture.platform, "machine", return_value="arm64"
            ), patch.object(
                builder, "command"
            ) as command:
                with self.assertRaisesRegex(builder.Failure, "DESTINATION_EXISTS"):
                    fixture.build_fixture(
                        destination, "org.fixture.Controlled", "-", builder.Deadline(2)
                    )
                command.assert_not_called()
            self.assertEqual(marker.read_text(), "original")

    def test_shared_compile_sign_verify_cleanup_budget_and_no_gui_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "Fixture.app"
            deadline = builder.Deadline(2)
            operations = []

            def command(arguments, stage, selected, cleanup_seconds=2):
                operations.append(arguments[0])
                if arguments[0] == "/usr/bin/xcrun":
                    pathlib.Path(arguments[-1]).write_bytes(b"fixture-test-only")
                elif arguments[0] == "/bin/rm":
                    for name in arguments[3:]:
                        path = pathlib.Path(name)
                        if path.is_dir():
                            shutil.rmtree(path)
                        else:
                            path.unlink()
                else:
                    self.assertEqual(arguments[0], "/usr/bin/codesign")
                    if "--display" in arguments:
                        return b"", b"Identifier=org.fixture.Controlled\n"
                if stage != destination.parent:
                    self.assertIs(selected, deadline)
                return b"", b""

            def publish(source, target):
                self.assertFalse((source.parent / "cache").exists())
                self.assertFalse(
                    (source.parent / "ControlledNativeFixture.swift").exists()
                )
                source.rename(target)

            with patch.object(
                fixture.platform, "system", return_value="Darwin"
            ), patch.object(
                fixture.platform, "machine", return_value="arm64"
            ), patch.object(
                builder, "command", side_effect=command
            ), patch.object(
                builder, "publish", side_effect=publish
            ):
                fixture.build_fixture(
                    destination, "org.fixture.Controlled", "-", deadline
                )
            self.assertTrue(
                (destination / "Contents/MacOS" / fixture.EXECUTABLE).is_file()
            )
            self.assertEqual(operations.count("/usr/bin/xcrun"), 1)
            self.assertEqual(operations.count("/usr/bin/codesign"), 3)
            self.assertEqual(operations.count("/bin/rm"), 2)

    def test_uncertain_process_cleanup_preserves_stage_without_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory).resolve() / "Fixture.app"
            with patch.object(
                fixture.platform, "system", return_value="Darwin"
            ), patch.object(
                fixture.platform, "machine", return_value="arm64"
            ), patch.object(
                builder,
                "command",
                side_effect=builder.Failure("PROCESS_CLEANUP_UNCERTAIN"),
            ), patch.object(
                builder, "publish"
            ) as publish, patch.object(
                builder, "remove_stage"
            ) as cleanup:
                with self.assertRaisesRegex(
                    builder.Failure, "PROCESS_CLEANUP_UNCERTAIN"
                ):
                    fixture.build_fixture(
                        destination, "org.fixture.Controlled", "-", builder.Deadline(2)
                    )
                publish.assert_not_called()
                cleanup.assert_not_called()
            self.assertEqual(
                len(list(destination.parent.glob(".plowshare-native-fixture-*"))), 1
            )
            self.assertFalse(destination.exists())

    def test_cli_diagnostics_never_echo_private_paths_or_signing_identity(self):
        error = io.StringIO()
        with patch.object(
            sys,
            "argv",
            [
                "native-fixture-build.py",
                "--bundle",
                "/private/operator/Fixture.app",
                "--bundle-id",
                "org.fixture.Controlled",
                "--signing-identity",
                "PRIVATE-IDENTITY",
            ],
        ), patch.object(
            fixture, "build_fixture", side_effect=OSError("private keychain path")
        ), contextlib.redirect_stderr(
            error
        ):
            self.assertEqual(fixture.main(), 1)
        self.assertNotIn("PRIVATE-IDENTITY", error.getvalue())
        self.assertNotIn("operator", error.getvalue())
        self.assertNotIn("keychain", error.getvalue())


if __name__ == "__main__":
    unittest.main()
