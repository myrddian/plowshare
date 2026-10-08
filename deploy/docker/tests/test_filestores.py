"""Exercise image FileStore initialization with temporary filesystem state, without Docker."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class FileStoreStartupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.data = self.root / "data"
        self.workspace = self.root / 'workspace "quoted" \\ path'
        self.data.mkdir()
        self.workspace.mkdir()
        self.registry = self.data / "filestore.js"
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("PLOWSHARE_")
        }
        self.environment.update(
            PLOWSHARE_DATA_DIR=str(self.data),
            PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY=str(self.workspace),
            PLOWSHARE_ADMIN_HANDLE="operator",
        )

    def initialize(self, **values):
        return subprocess.run(
            [
                "sh",
                "-ec",
                '. "$1"; printf "%s" "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE"',
                "startup-test",
                str(ROOT / "server-filestores.sh"),
            ],
            env={**self.environment, **values},
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )

    def definition(self):
        source = self.registry.read_text()
        self.assertTrue(source.startswith("export default "))
        self.assertTrue(source.endswith(";\n"))
        return json.loads(source.removeprefix("export default ").removesuffix(";\n"))

    def test_initializes_persistent_registry_and_separate_private_applications(self):
        result = self.initialize()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.registry))
        definition = self.definition()
        self.assertEqual(definition["version"], 1)
        self.assertEqual(definition["defaultStore"], "applications")
        store = definition["fileStores"]["applications"]
        self.assertEqual(store["root"], str(self.workspace / "applications"))
        self.assertEqual(
            store["access"]["accounts"],
            [
                {
                    "handle": self.environment["PLOWSHARE_ADMIN_HANDLE"],
                    "role": "MANAGER",
                }
            ],
        )
        self.assertEqual(self.registry.stat().st_mode & 0o777, 0o600)
        self.assertEqual(
            (self.workspace / "applications").stat().st_mode & 0o777, 0o700
        )
        self.assertFalse((self.data / "applications").exists())

    def test_restart_preserves_operator_content_modes_and_grants(self):
        self.assertEqual(self.initialize().returncode, 0)
        self.registry.write_text("operator-owned replacement\n")
        self.registry.chmod(0o640)
        directory = self.workspace / "applications"
        directory.chmod(0o750)
        self.assertEqual(
            self.initialize(PLOWSHARE_ADMIN_HANDLE="different").returncode, 0
        )
        self.assertEqual(self.registry.read_text(), "operator-owned replacement\n")
        self.assertEqual(self.registry.stat().st_mode & 0o777, 0o640)
        self.assertEqual(directory.stat().st_mode & 0o777, 0o750)

    def test_explicit_registry_skips_bootstrap_even_without_a_workspace(self):
        result = self.initialize(
            PLOWSHARE_FILESTORES_CONFIG_FILE=str(self.root / "operator.js"),
            PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY="",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.registry.exists())
        self.assertFalse((self.workspace / "applications").exists())

    def test_explicit_manager_and_empty_manager_do_not_grant_other_accounts(self):
        self.assertEqual(
            self.initialize(PLOWSHARE_FILESTORES_MANAGER_HANDLE="deployer").returncode,
            0,
        )
        accounts = self.definition()["fileStores"]["applications"]["access"]["accounts"]
        self.assertEqual(accounts, [{"handle": "deployer", "role": "MANAGER"}])
        self.registry.unlink()
        result = self.initialize(PLOWSHARE_FILESTORES_MANAGER_HANDLE="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.definition()["fileStores"]["applications"]["access"]["accounts"], []
        )
        self.assertIn("without account grants", result.stderr)

    def test_missing_admin_initializes_without_implicit_access(self):
        result = self.initialize(PLOWSHARE_ADMIN_HANDLE="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.definition()["fileStores"]["applications"]["access"]["accounts"], []
        )

    def test_invalid_values_refuse_before_creating_registry_or_application_directory(
        self,
    ):
        for values in (
            {"PLOWSHARE_DATA_DIR": "relative"},
            {"PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY": ""},
            {"PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY": "relative"},
            {"PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY": str(self.root / "missing")},
            {"PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY": str(self.data)},
            {"PLOWSHARE_ADMIN_HANDLE": " leading"},
            {"PLOWSHARE_ADMIN_HANDLE": "trailing "},
            {"PLOWSHARE_ADMIN_HANDLE": "bad\nhandle"},
            {"PLOWSHARE_ADMIN_HANDLE": 'operator "quoted" \\ handle'},
            {"PLOWSHARE_ADMIN_HANDLE": "x" * 65},
        ):
            with self.subTest(values=values):
                self.assertNotEqual(self.initialize(**values).returncode, 0)
                self.assertFalse(self.registry.exists())
                self.assertFalse((self.workspace / "applications").exists())

    def test_linked_registry_is_refused_without_overwriting_target(self):
        target = self.root / "target.js"
        target.write_text("preserve\n")
        self.registry.symlink_to(target)
        self.assertNotEqual(self.initialize().returncode, 0)
        self.assertEqual(target.read_text(), "preserve\n")
        self.assertFalse((self.workspace / "applications").exists())

    def test_linked_applications_directory_is_refused(self):
        target = self.root / "outside"
        target.mkdir()
        (self.workspace / "applications").symlink_to(target, target_is_directory=True)
        self.assertNotEqual(self.initialize().returncode, 0)
        self.assertFalse(self.registry.exists())
        self.assertEqual(list(target.iterdir()), [])

    def test_image_packages_and_sources_initializer(self):
        dockerfile = (ROOT / "server.Dockerfile").read_text()
        self.assertIn(
            "COPY deploy/docker/server-filestores.sh /usr/local/bin/server-filestores.sh",
            dockerfile,
        )
        self.assertIn(
            "!deploy/docker/server-filestores.sh",
            (ROOT / "server.Dockerfile.dockerignore").read_text(),
        )
        self.assertIn(
            '. "$(dirname "$0")/server-filestores.sh"',
            (ROOT / "server-entrypoint.sh").read_text(),
        )

    def test_actual_entrypoint_passes_default_to_java_with_empty_explicit_setting(self):
        image = self.root / "image"
        image.mkdir()
        for name in ("server-entrypoint.sh", "server-filestores.sh"):
            shutil.copy2(ROOT / name, image / name)
        tools = self.root / "bin"
        tools.mkdir()
        java = tools / "java"
        java.write_text(
            '#!/bin/sh\nprintf "%s" "$PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE"\n'
        )
        java.chmod(0o755)
        # The native cache is unrelated to this check; keep its mkdir in the
        # temporary fixture while exercising the real entrypoint unchanged.
        mkdir = tools / "mkdir"
        mkdir.write_text(
            '#!/bin/sh\nif [ "$*" = "-p /tmp/plowshare-runtime" ]; then exit 0; fi\n'
            'exec /bin/mkdir "$@"\n'
        )
        mkdir.chmod(0o755)
        environment = {
            **self.environment,
            "PATH": str(tools) + os.pathsep + os.environ["PATH"],
            "PLOWSHARE_DB_PASSWORD": "fixture-only",
            "PLOWSHARE_FILESTORES_CONFIG_FILE": "",
        }
        result = subprocess.run(
            ["sh", str(image / "server-entrypoint.sh")],
            env=environment,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.registry))
        self.assertEqual(self.definition()["defaultStore"], "applications")


if __name__ == "__main__":
    unittest.main()
