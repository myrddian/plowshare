"""Per-user service definitions and ownership fences, using a fake process runner."""

from __future__ import annotations

import json
import plistlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from support import EXAMPLES

from plowshare_privacy.service import (
    CollectorService,
    CommandResult,
    ServiceDefinition,
    systemd_argument,
)


class Processes:
    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []
        self.code = 0

    def run(self, arguments: tuple[str, ...]) -> CommandResult:
        self.calls.append(arguments)
        return CommandResult(self.code, "fixture status")


class ServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        (self.root / "collector.json").write_bytes(
            (EXAMPLES / "config.json").read_bytes()
        )
        (self.root / "deployment").mkdir()
        (self.root / "deployment/installation-completed").write_text("confirmed")
        (self.root / "dashboard.json").write_text(
            json.dumps({"bind": "127.0.0.1", "port": 0})
        )
        self.home = self.root / "user"
        self.directory = self.home / "Library/LaunchAgents"
        self.directory.mkdir(parents=True)
        self.executable = self.root / "python with $dollar% space"
        self.executable.write_text("fixture")
        self.executable.chmod(0o700)
        self.processes = Processes()

    def test_launchd_keeps_static_arguments_and_login_startup_without_credentials(
        self,
    ) -> None:
        service = CollectorService(self.root, self.processes, system="darwin")
        with patch("pathlib.Path.home", return_value=self.home):
            definition = service.install(self.directory, self.executable)
        plist = plistlib.loads(Path(definition.path).read_bytes())
        self.assertEqual(plist["ProgramArguments"][0], str(self.executable))
        self.assertEqual(plist["ProgramArguments"][-1], "background")
        self.assertTrue(plist["RunAtLoad"])
        self.assertEqual(plist["Umask"], 0o077)
        self.assertEqual(self.processes.calls[0][0:2], ("launchctl", "bootstrap"))
        service.manage("stop")
        self.assertEqual(self.processes.calls[-1][1:3], ("kill", "SIGTERM"))
        self.assertTrue(Path(definition.path).exists())
        service.manage("uninstall")
        self.assertFalse(Path(definition.path).exists())
        self.assertTrue((self.root / "collector.json").exists())

    def test_systemd_enables_exact_path_and_escapes_expansion_without_shell(
        self,
    ) -> None:
        service = CollectorService(self.root, self.processes, system="linux")
        definition = service.install(self.directory, self.executable)
        content = Path(definition.path).read_text()
        self.assertIn("$$dollar%%", content)
        self.assertIn("Restart=on-failure", content)
        self.assertEqual(self.processes.calls[-1][-1], definition.path)
        self.assertEqual(systemd_argument('x"\\%$'), '"x\\"\\\\%%$$"')
        self.assertEqual(Path(definition.path).stat().st_mode & 0o777, 0o600)

    def test_edited_or_foreign_definition_refuses_management(self) -> None:
        service = CollectorService(self.root, self.processes, system="linux")
        definition = service.install(self.directory, self.executable)
        Path(definition.path).write_text("edited by operator")
        with self.assertRaisesRegex(ValueError, "changed"):
            service.manage("uninstall")
        self.assertTrue(Path(definition.path).exists())
        self.assertEqual(len(self.processes.calls), 2)

    def test_activation_failure_retains_enrollment_and_never_claims_success(
        self,
    ) -> None:
        self.processes.code = 1
        service = CollectorService(self.root, self.processes, system="linux")
        with self.assertRaisesRegex(ValueError, "not confirmed"):
            service.install(self.directory, self.executable)
        self.assertTrue(ServiceDefinition.read(self.root))
        with self.assertRaisesRegex(ValueError, "already enrolled"):
            service.install(self.directory, self.executable)
        self.assertEqual(len(self.processes.calls), 1)

    def test_running_foreground_or_unfinished_setup_does_not_activate_service(
        self,
    ) -> None:
        service = CollectorService(self.root, self.processes, system="linux")
        (self.root / "dashboard-runtime.json").write_text("running")
        with self.assertRaisesRegex(ValueError, "foreground"):
            service.install(self.directory, self.executable)
        (self.root / "dashboard-runtime.json").unlink()
        (self.root / "deployment/installation-completed").unlink()
        with self.assertRaisesRegex(ValueError, "Complete"):
            service.install(self.directory, self.executable)
        self.assertEqual(self.processes.calls, [])

    def test_venv_interpreter_symlink_is_preserved(self) -> None:
        symlink = self.root / "venv-python"
        symlink.symlink_to(self.executable)
        service = CollectorService(self.root, self.processes, system="linux")
        definition = service.install(self.directory, symlink)
        self.assertIn(str(symlink), Path(definition.path).read_text())
