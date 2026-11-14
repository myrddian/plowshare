"""Exercise the real hook with Git and a stub Gradle wrapper, without pushing."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class GitHooksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="plowshare-hooks-")
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        # Keep developer/global Git hooks and environment from influencing fixtures.
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.run_command("git", "init", "--quiet")
        (self.repo / ".githooks").mkdir()
        shutil.copy2(ROOT / ".githooks/pre-push", self.repo / ".githooks/pre-push")
        (self.repo / "gradlew").write_text(
            '#!/bin/sh\nprintf "%s\\n" "$@" > gradle-arguments\nexit "${FORMAT_EXIT:-0}"\n'
        )
        (self.repo / "gradlew").chmod(0o755)
        (self.repo / "Example.java").write_text("class Example {}\n")
        self.assertEqual(0, self.run_command("git", "add", ".githooks/pre-push", "gradlew", "Example.java").returncode)
        committed = self.run_command(
            "git", "-c", "user.name=Hook fixture", "-c", "user.email=fixture@example.invalid",
            "commit", "--quiet", "-m", "Hook fixture",
        )
        self.assertEqual(0, committed.returncode, committed.stderr)
        self.oid = self.run_command("git", "rev-parse", "HEAD").stdout.strip()

    def run_command(self, *args, input=None, cwd=None):
        return subprocess.run(
            args, cwd=cwd or self.repo, env=self.env, input=input,
            text=True, capture_output=True, check=False,
        )

    def install(self):
        return self.run_command("sh", str(ROOT / "scripts/install-git-hooks.sh"))

    def push_hook(self, refs=None, cwd=None):
        if refs is None:
            refs = f"refs/heads/main {self.oid} refs/heads/main {'0' * 40}\n"
        return self.run_command(str(self.repo / ".githooks/pre-push"), input=refs, cwd=cwd)

    def test_installs_and_repeats_without_changing_configuration(self):
        self.assertEqual(0, self.install().returncode)
        self.assertEqual(0, self.install().returncode)
        configured = self.run_command("git", "config", "--local", "--get", "core.hooksPath")
        self.assertEqual(".githooks", configured.stdout.strip())

    def test_preserves_custom_hooks_path(self):
        self.run_command("git", "config", "--local", "core.hooksPath", "custom-hooks")
        self.assertNotEqual(0, self.install().returncode)
        configured = self.run_command("git", "config", "--local", "--get", "core.hooksPath")
        self.assertEqual("custom-hooks", configured.stdout.strip())

    def test_preserves_existing_executable_hook(self):
        existing = self.repo / ".git/hooks/pre-push"
        existing.write_text("#!/bin/sh\nexit 0\n")
        existing.chmod(0o755)
        self.assertNotEqual(0, self.install().returncode)
        self.assertEqual("#!/bin/sh\nexit 0\n", existing.read_text())
        self.assertEqual(1, self.run_command("git", "config", "--get", "core.hooksPath").returncode)

    def test_checks_formatting_from_repository_root(self):
        nested = self.repo / "nested"
        nested.mkdir()
        self.assertEqual(0, self.push_hook(cwd=nested).returncode)
        self.assertEqual("--console=plain\nformatCheck\n", (self.repo / "gradle-arguments").read_text())

    def test_failed_formatting_refuses_push(self):
        self.env["FORMAT_EXIT"] = "7"
        result = self.push_hook()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Push refused", result.stderr)
        self.assertIn("review and commit", result.stderr)

    def test_delete_only_and_empty_push_do_not_run_gradle(self):
        for refs in ("", f"(delete) {'0' * 40} refs/heads/old {'1' * 40}\n"):
            with self.subTest(refs=refs):
                self.assertEqual(0, self.push_hook(refs).returncode)
                self.assertFalse((self.repo / "gradle-arguments").exists())

    def test_mixed_push_checks_once(self):
        refs = (
            f"(delete) {'0' * 40} refs/heads/old {'1' * 40}\n"
            f"refs/heads/main {self.oid} refs/heads/main {'1' * 40}\n"
        )
        self.assertEqual(0, self.push_hook(refs).returncode)
        self.assertEqual("--console=plain\nformatCheck\n", (self.repo / "gradle-arguments").read_text())

    def test_git_invokes_installed_gate_before_updating_remote(self):
        remote = self.repo / "remote.git"
        self.assertEqual(0, self.run_command("git", "init", "--bare", "--quiet", str(remote)).returncode)
        self.assertEqual(0, self.install().returncode)
        self.env["FORMAT_EXIT"] = "7"
        failed = self.run_command("git", "push", str(remote), "HEAD:refs/heads/checked")
        self.assertNotEqual(0, failed.returncode)
        self.assertIn("Push refused", failed.stderr)
        self.assertNotEqual(
            0, self.run_command("git", "--git-dir", str(remote), "rev-parse", "--verify", "refs/heads/checked").returncode
        )
        self.env["FORMAT_EXIT"] = "0"
        accepted = self.run_command("git", "push", str(remote), "HEAD:refs/heads/checked")
        self.assertEqual(0, accepted.returncode, accepted.stderr)

    def test_uncommitted_source_fix_cannot_mask_outgoing_commit(self):
        (self.repo / "Example.java").write_text("class Example {\n}\n")
        result = self.push_hook()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differ from the working tree", result.stderr)
        self.assertFalse((self.repo / "gradle-arguments").exists())

    def test_staged_formatter_config_change_cannot_mask_outgoing_commit(self):
        (self.repo / ".prettierrc.json").write_text('{}\n')
        self.run_command("git", "add", ".prettierrc.json")
        result = self.push_hook()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differ from the working tree", result.stderr)

    def test_unrelated_dirty_document_does_not_block_push(self):
        (self.repo / "notes.md").write_text("Unrelated work\n")
        self.run_command("git", "add", "notes.md")
        self.assertEqual(0, self.push_hook().returncode)


if __name__ == "__main__":
    unittest.main()
