"""Actual archive installation, SDK integrity and failure-safe packaging."""

import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import support

packager = support.packager

CONSUMER = """
import asyncio
import importlib.resources
import json
import pathlib
import sys
from unittest.mock import patch
sys.path.insert(0, sys.argv[1])
from homeassistant.config_entries import ConfigEntries
from homeassistant.core import HomeAssistant
from custom_components.plowshare import config_flow, ai_task, conversation
from custom_components.plowshare._vendor import plowshare
from custom_components.plowshare._vendor.plowshare import contracts
from custom_components.plowshare._vendor.plowshare._codec import encode_request
assert "conversation.open.ex" in plowshare.OPERATIONS
assert "agent.run.ex" in plowshare.OPERATIONS
assert importlib.resources.files(plowshare).joinpath("_schemas.json").is_file()
assert importlib.resources.files(plowshare).joinpath("py.typed").is_file()
value = contracts.ConversationOpenRequestEx(
    external_conversation="installed-fixture", source="ha-fixture", project="house",
    max_turns=20, max_model_calls=40
)
# Encoding loads generated SDK resources through the relocated package namespace.
operation, payload = encode_request(value)
assert operation == "conversation.open.ex"
assert payload["externalConversation"] == "installed-fixture"
async def run():
    hass = HomeAssistant(sys.argv[2])
    hass.config_entries = ConfigEntries(hass, {})
    flow = config_flow.PlowshareConfigFlow()
    flow.hass = hass
    flow.context = {"source": "user"}
    with patch.object(config_flow, "SdkConversationAgent", side_effect=AssertionError("network")):
        result = await flow.async_step_user()
        assert result["type"] == "form"
        rejected = await flow.async_step_user({"llm_api": "unsupported"})
        assert rejected["errors"] == {"base": "invalid_config"}
    assert not (pathlib.Path(sys.argv[2]) / ".storage").exists()
    await hass.async_stop()
asyncio.run(run())
print("installed HA imports, Ex codec resources and offline config form passed")
"""


class PackagingTests(unittest.TestCase):
    def test_cli_archive_installs_outside_checkout_with_exact_sdk_and_profiles(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "artifacts/assist.zip"
            result = subprocess.run(
                [
                    sys.executable,
                    str(support.ROOT / "scripts/package.py"),
                    "--output",
                    str(output),
                ],
                cwd=root,
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            installed = root / "installed"
            installed.mkdir()
            with ZipFile(output) as archive:
                names = archive.namelist()
                self.assertEqual(names, sorted(names))
                self.assertEqual(len(names), len(set(names)))
                self.assertTrue(
                    all(
                        not name.startswith("/") and ".." not in Path(name).parts
                        for name in names
                    )
                )
                self.assertTrue(
                    all(
                        "__pycache__" not in name
                        and not name.endswith((".pyc", ".pyo"))
                        for name in names
                    )
                )
                self.assertTrue(
                    all(
                        name.startswith(("custom_components/plowshare/", "profiles/"))
                        or name in {"INSTALL.md", "package-manifest.json"}
                        for name in names
                    )
                )
                archive.extractall(installed)
            manifest = json.loads((installed / "package-manifest.json").read_text())
            actual = {
                p.relative_to(installed).as_posix(): hashlib.sha256(
                    p.read_bytes()
                ).hexdigest()
                for p in installed.rglob("*")
                if p.is_file() and p.name != "package-manifest.json"
            }
            self.assertEqual(manifest, {"format": 1, "sha256": actual})
            source = support.ROOT.parents[2] / "sdk/python/src/plowshare"
            vendor = installed / "custom_components/plowshare/_vendor"
            originals = {
                p.relative_to(source)
                for p in source.rglob("*")
                if p.is_file()
                and "__pycache__" not in p.parts
                and p.suffix not in {".pyc", ".pyo"}
            }
            bundled = {
                p.relative_to(vendor / "plowshare")
                for p in (vendor / "plowshare").rglob("*")
                if p.is_file()
            }
            self.assertEqual(originals, bundled)
            for relative in originals:
                self.assertEqual(
                    (source / relative).read_bytes(),
                    (vendor / "plowshare" / relative).read_bytes(),
                )
            self.assertEqual(
                (support.ROOT.parents[2] / "LICENSE").read_bytes(),
                (vendor / "LICENSE").read_bytes(),
            )
            for profile in ("readonly", "control", "task"):
                original = (
                    support.ROOT.parent / "examples/application/profiles" / profile
                )
                for file in original.rglob("*"):
                    if file.is_file():
                        self.assertEqual(
                            file.read_bytes(),
                            (
                                installed
                                / "profiles"
                                / profile
                                / file.relative_to(original)
                            ).read_bytes(),
                        )
            for doc in (installed / "INSTALL.md", installed / "profiles/README.md"):
                for target in re.findall(r"\]\(([^)]+)\)", doc.read_text()):
                    if "://" not in target and not target.startswith("#"):
                        self.assertTrue(
                            (doc.parent / target.split("#")[0]).exists(), target
                        )
            consumer = subprocess.run(
                [
                    sys.executable,
                    "-I",
                    "-c",
                    CONSUMER,
                    str(installed),
                    str(root / "ha-config"),
                ],
                cwd=root,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(consumer.returncode, 0, consumer.stderr)
            self.assertIn("offline config form passed", consumer.stdout)

    def test_failed_staging_and_archive_write_preserve_existing_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "nested/assist.zip"
            with patch.object(
                packager, "_stage_archive", side_effect=ValueError("invalid dependency")
            ):
                with self.assertRaisesRegex(ValueError, "invalid dependency"):
                    packager.build_archive(output)
            self.assertFalse(output.parent.exists())
            output.parent.mkdir()
            output.write_bytes(b"previous verified archive")
            with patch.object(
                packager, "_stage_archive", side_effect=ValueError("invalid dependency")
            ):
                with self.assertRaises(ValueError):
                    packager.build_archive(output)
            self.assertEqual(output.read_bytes(), b"previous verified archive")
            with patch.object(
                packager.ZipFile, "writestr", side_effect=OSError("disk full")
            ):
                with self.assertRaisesRegex(OSError, "disk full"):
                    packager.build_archive(output)
            self.assertEqual(output.read_bytes(), b"previous verified archive")
            self.assertEqual(list(output.parent.iterdir()), [output])

    def test_source_destinations_are_rejected_without_mutating_input(self):
        source = support.ROOT / "custom_components/plowshare"
        with self.assertRaisesRegex(ValueError, "overlap"):
            packager.stage_component(source / "accidental-output")
        self.assertFalse((source / "accidental-output").exists())
        for source_file in (
            support.ROOT / "README.md",
            support.ROOT / "scripts/package.py",
        ):
            original = source_file.read_bytes()
            with self.assertRaisesRegex(ValueError, "outside source"):
                packager.build_archive(source_file)
            self.assertEqual(source_file.read_bytes(), original)

    def test_dependency_mismatch_refuses_before_component_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "output"
            with patch.object(
                packager.tomllib,
                "loads",
                return_value={"project": {"dependencies": ["unexpected"]}},
            ):
                with self.assertRaisesRegex(ValueError, "requirements"):
                    packager.stage_component(destination)
            self.assertFalse(destination.exists())

    def test_hidden_special_and_unexpected_sources_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            for name in (".env", ".storage", "private-token.txt"):
                contamination = source / name
                contamination.write_text("private")
                with self.subTest(name=name), self.assertRaises(ValueError):
                    packager._copy_tree(
                        source, root / name.replace(".", "_").replace("-", "_")
                    )
                contamination.unlink()
            link = source / "outside.py"
            link.symlink_to(support.ROOT / "scripts/package.py")
            with self.assertRaisesRegex(ValueError, "symlink"):
                packager._copy_tree(source, root / "symlink-output")
            with self.assertRaisesRegex(ValueError, "regular"):
                packager._copy_file(link, root / "copied.py")

    def test_source_vendor_namespace_is_refused(self):
        source = support.ROOT / "custom_components/plowshare/_vendor"
        exists = Path.exists
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(
                Path, "exists", lambda path: path == source or exists(path)
            ):
                with self.assertRaisesRegex(ValueError, "vendor"):
                    packager.stage_component(Path(temporary) / "stage")
