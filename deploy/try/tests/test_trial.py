"""Verify trial setup, literal credentials and rejection before Docker startup."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TrialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        for name in ("try.sh", "compose.yaml"):
            shutil.copy2(ROOT / name, self.bundle / name)
        (self.bundle / "images.env").write_text("PLOWSHARE_SERVER_IMAGE=ghcr.io/myrddian/plowshare-server@sha256:" + "a" * 64 + "\n")
        tools = self.root / "bin"
        tools.mkdir()
        self.calls = self.root / "calls"
        docker = tools / "docker"
        docker.write_text('#!/bin/sh\ncase "$*" in "compose version --short") echo 2.30.0 ;; *) printf "%s\\n" "$*" >> "$CALLS" ;; esac\n')
        docker.chmod(0o755)
        self.state = self.root / "settings"
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("PLOWSHARE_", "LLM_", "COMPOSE_"))}
        self.env.update(PATH=str(tools) + ":" + os.environ["PATH"], CALLS=str(self.calls),
                        PLOWSHARE_TRY_STATE=str(self.state), LLM_BASE_URL="https://models.example.test/v1",
                        LLM_CHAT_MODEL="chat", LLM_EMBEDDING_MODEL="embedding",
                        LLM_API_KEY='literal$secret\'"#', PLOWSHARE_ADMIN_HANDLE="trial",
                        PLOWSHARE_ADMIN_PASSWORD='literal$password\'"#')

    def run_trial(self, action="configure", **values):
        return subprocess.run([str(self.bundle / "try.sh"), action], env={**self.env, **values},
                              capture_output=True, text=True, timeout=10)

    def test_configuration_preserves_literal_secrets_and_does_not_start(self):
        result = self.run_trial()
        self.assertEqual(result.returncode, 0, result.stderr)
        contents = (self.state / "server.env").read_text()
        self.assertIn("LLM_API_KEY=" + self.env["LLM_API_KEY"] + "\n", contents)
        self.assertNotIn(self.env["LLM_API_KEY"], result.stdout + result.stderr)
        for name in ("compose.env", "server.env", "database.env"):
            self.assertEqual((self.state / name).stat().st_mode & 0o777, 0o600)
        self.assertNotIn(" up ", self.calls.read_text())
        original = (self.state / "server.env").read_bytes()
        self.assertEqual(self.run_trial(LLM_API_KEY="changed").returncode, 0)
        self.assertEqual((self.state / "server.env").read_bytes(), original)

    def test_invalid_values_fail_before_config_or_start(self):
        for key, value in (("LLM_BASE_URL", "https://user:secret@example.test/v1"),
                           ("LLM_BASE_URL", "https://example.test/v1?key=secret"),
                           ("PLOWSHARE_ADMIN_HANDLE", "invalid user"),
                           ("PLOWSHARE_ADMIN_PASSWORD", "short"),
                           ("PLOWSHARE_TRY_PORT", "65536"),
                           ("LLM_CHAT_MODEL", "model\nINJECTED=value"),
                           ("LLM_API_KEY", "secret\rINJECTED=value")):
            with self.subTest(key=key, value=value):
                self.assertNotEqual(self.run_trial(**{key: value}).returncode, 0)
                self.assertFalse((self.state / "compose.env").exists())
                self.assertFalse(self.calls.exists())

    def test_state_in_bundle_and_symlink_into_bundle_are_rejected(self):
        self.assertNotEqual(self.run_trial(PLOWSHARE_TRY_STATE=str(self.bundle / "state")).returncode, 0)
        link = self.root / "linked-state"
        link.symlink_to(self.bundle, target_is_directory=True)
        self.assertNotEqual(self.run_trial(PLOWSHARE_TRY_STATE=str(link)).returncode, 0)
        self.assertFalse((self.bundle / "server.env").exists())

    def test_mutable_image_is_rejected(self):
        (self.bundle / "images.env").write_text("PLOWSHARE_SERVER_IMAGE=ghcr.io/myrddian/plowshare-server:latest\n")
        self.assertNotEqual(self.run_trial().returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_partial_existing_configuration_is_not_overwritten(self):
        self.state.mkdir()
        (self.state / "server.env").write_text("preserve\n")
        self.assertNotEqual(self.run_trial().returncode, 0)
        self.assertEqual((self.state / "server.env").read_text(), "preserve\n")

    def test_stop_retains_state_and_volumes(self):
        self.assertEqual(self.run_trial().returncode, 0)
        result = self.run_trial("stop")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(" stop", self.calls.read_text())
        self.assertNotIn("down", self.calls.read_text())
        self.assertTrue((self.state / "server.env").exists())


if __name__ == "__main__":
    unittest.main()
