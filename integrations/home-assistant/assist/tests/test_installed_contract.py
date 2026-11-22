"""Exercise the installed artifact across HA, local storage and the real SDK."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

import support


class InstalledContractTests(unittest.TestCase):
    def test_fresh_installed_ha_sdk_contracts_without_checkout_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "assist.zip"
            support.packager.build_archive(archive)
            installed = root / "installed"
            with ZipFile(archive) as source:
                source.extractall(installed)
            # Copy the test consumer too. -I excludes cwd, environment Python
            # paths and the script directory; it admits only the explicit ZIP
            # installation and the isolated environment's real HA dependencies.
            consumer = root / "installed_contract.py"
            shutil.copyfile(Path(__file__).with_name(consumer.name), consumer)
            result = subprocess.run(
                [
                    sys.executable,
                    "-I",
                    str(consumer),
                    str(installed),
                    str(root / "ha-config"),
                ],
                cwd=root,
                capture_output=True,
                text=True,
                timeout=45,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("Ran 3 tests", result.stderr)
            self.assertIn("OK", result.stderr)
