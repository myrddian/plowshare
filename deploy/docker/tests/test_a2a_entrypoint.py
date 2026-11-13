"""Exercise receiving secret/bootstrap boundaries without Docker, Java or network I/O."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ENTRYPOINT = Path(__file__).resolve().parents[1] / "a2a-entrypoint.sh"


class A2aEntrypointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = self.root / "private config.json"
        self.config.write_text("{}")
        self.binary = self.root / "java"
        self.binary.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "print(json.dumps({'args': sys.argv[1:], "
            "'token': os.environ.get('PLOWSHARE_TOKEN'), "
            "'caller': os.environ.get('A2A_CALLER_TOKEN')}))\n"
        )
        self.binary.chmod(0o700)
        self.environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith(("PLOWSHARE_", "A2A_"))
        }
        self.environment["PATH"] = str(self.root) + os.pathsep + os.environ["PATH"]

    def launch(self, *arguments, **environment):
        return subprocess.run(
            ["sh", str(ENTRYPOINT), str(self.config), *arguments],
            env={**self.environment, **environment},
            capture_output=True, text=True, timeout=10, check=False,
        )

    def test_private_secret_files_are_quoted_and_forwarded_without_login_fallback(self):
        token = self.root / "service token"
        caller = self.root / "caller token"
        token.write_text("fixture-service\n")
        caller.write_text("fixture-caller\n")
        result = self.launch(
            "owner", PLOWSHARE_TOKEN_FILE=str(token), A2A_CALLER_TOKEN_FILE=str(caller)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads(result.stdout)
        self.assertEqual(value["token"], "fixture-service")
        self.assertEqual(value["caller"], "fixture-caller")
        self.assertEqual(value["args"][-2:], [str(self.config), "owner"])
        self.assertEqual(result.stderr, "")

    def test_password_credentials_cannot_silently_create_another_service_identity(self):
        result = self.launch(
            PLOWSHARE_HANDLE="fixture", PLOWSHARE_PASSWORD="fixture-password",
            A2A_CALLER_TOKEN="fixture-caller",
        )
        self.assertEqual(result.returncode, 78)
        self.assertNotIn("fixture-password", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_missing_external_caller_secret_stops_before_java(self):
        result = self.launch(PLOWSHARE_TOKEN="fixture-service")
        self.assertEqual(result.returncode, 78)
        self.assertEqual(result.stdout, "")
        self.assertNotIn("fixture-service", result.stderr)

    def test_explicit_rotation_command_reaches_the_same_locked_journal_cli(self):
        result = self.launch(
            "rotate-config", "a" * 64,
            PLOWSHARE_TOKEN="fixture-service", A2A_CALLER_TOKEN="fixture-caller",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["args"][-2:], ["rotate-config", "a" * 64])


if __name__ == "__main__":
    unittest.main()
