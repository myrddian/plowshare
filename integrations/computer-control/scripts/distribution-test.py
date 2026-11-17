#!/usr/bin/env python3
"""Portable offline packaging faults; no network, authentication, browser or native helper."""
import gzip
import contextlib
from dataclasses import FrozenInstanceError
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import subprocess
import shutil
import sys
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


dist = load("offline_distribution_test", "distribution.py")
build = load("offline_distribution_builder_test", "build-distribution.py")


def archive(rows, format=tarfile.USTAR_FORMAT):
    target = io.BytesIO()
    with tarfile.open(fileobj=target, mode="w:gz", format=format) as output:
        for name, data, kind in rows:
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            item.type = kind
            output.addfile(item, io.BytesIO(data))
    return target.getvalue()


def bundle(directory):
    (directory / "artifacts").mkdir()
    packages = []
    for name in dist.PACKAGES:
        payload = json.dumps({"name": name, "version": "0.1.0"}).encode()
        data = archive([("package/package.json", payload, tarfile.REGTYPE)])
        (directory / "artifacts" / (name + ".tgz")).write_bytes(data)
        packages.append(
            {
                "name": name,
                "version": "0.1.0",
                "archive": "artifacts/" + name + ".tgz",
                "sha256": dist.digest(data),
                "bytes": len(data),
                "files": [
                    {
                        "path": "package.json",
                        "sha256": dist.digest(payload),
                        "bytes": len(payload),
                        "mode": 0o644,
                    }
                ],
            }
        )
    support = []
    for name in ("install.py", "smoke.mjs"):
        data = b"fixture"
        (directory / name).write_bytes(data)
        support.append({"path": name, "sha256": dist.digest(data), "bytes": len(data)})
    info = {
        "version": dist.VERSION,
        "sourceRevision": "a" * 40,
        "dirty": True,
        "nodeMinimum": "22.13.0",
        "packages": packages,
        "support": support,
    }
    (directory / "manifest.json").write_text(json.dumps(info))
    return info


class DistributionTest(unittest.TestCase):
    def test_manifest_projects_frozen_values_before_install_effects(self):
        with tempfile.TemporaryDirectory() as temporary:
            info = bundle(Path(temporary))
            decoded = dist.manifest(json.dumps(info).encode())
            self.assertIsInstance(decoded, dist.Manifest)
            self.assertIsInstance(decoded.packages, tuple)
            self.assertIsInstance(decoded.packages[0], dist.Package)
            self.assertIsInstance(decoded.packages[0].files, tuple)
            self.assertIsInstance(decoded.packages[0].files[0], dist.Member)
            self.assertIsInstance(decoded.support[0], dist.Support)
            with self.assertRaises(FrozenInstanceError):
                decoded.packages[0].name = "changed"
            with self.assertRaises(FrozenInstanceError):
                decoded.packages[0].files[0].path = "../changed"
            self.assertEqual(dist.manifest(dist.encode_manifest(decoded)), decoded)
            original = {"package.json": b"original"}
            source = dist.package_files_value("ws", "8.22.0", original)
            original["package.json"] = b"changed"
            self.assertEqual(source.files["package.json"], b"original")
            with self.assertRaises(TypeError):
                source.files["package.json"] = b"changed"

    def test_package_identity_and_file_selectors_refuse_before_collection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for metadata, mode in [
                ({"name": "unlisted", "version": "0.1.0"}, "vendor"),
                ({"name": "ws", "version": ""}, "vendor"),
                (
                    {
                        "name": "plowshare-computer-control",
                        "version": "0.1.0",
                        "exports": [],
                    },
                    "provider",
                ),
                (
                    {
                        "name": "plowshare-client-ts",
                        "version": "0.1.0",
                        "files": "**/*",
                    },
                    "sdk",
                ),
                (
                    {
                        "name": "plowshare-client-ts",
                        "version": "0.1.0",
                        "files": ["../private"],
                    },
                    "sdk",
                ),
                (
                    {
                        "name": "plowshare-client-ts",
                        "version": "0.1.0",
                        "files": [True],
                    },
                    "sdk",
                ),
            ]:
                (root / "package.json").write_text(json.dumps(metadata))
                with self.assertRaises(build.dist.Failure):
                    build.package_files(root, mode, dist.Deadline(2), 100, 100000)

    def test_sdk_discovery_is_lazy_and_stops_at_its_visit_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = json.dumps(
                {
                    "name": "plowshare-client-ts",
                    "version": "0.1.0",
                    "files": ["build/*.js"],
                }
            ).encode()
            (root / "build").mkdir()
            visited = []
            closed = []

            def entries():
                for index in range(6):
                    if index == 5:
                        raise AssertionError(
                            "Discovery was exhausted before applying its bound"
                        )
                    visited.append(index)
                    yield SimpleNamespace(
                        name="fixture.js",
                        path=str(root / "build/fixture.js"),
                        is_symlink=lambda: False,
                        is_dir=lambda **_: False,
                    )

            @contextlib.contextmanager
            def discover(_directory):
                iterator = entries()
                try:
                    yield iterator
                finally:
                    iterator.close()
                    closed.append(True)

            with patch.object(build, "MAX_DISCOVERY_ENTRIES", 4), patch.object(
                build.os, "scandir", side_effect=discover
            ), patch.object(
                build.dist,
                "read_regular",
                side_effect=lambda path, *_: (
                    metadata if path.name == "package.json" else b"fixture"
                ),
            ):
                with self.assertRaisesRegex(build.dist.Failure, "FILE_LIMIT"):
                    build.package_files(
                        root, "sdk", build.dist.Deadline(2), 100, 100000
                    )
            self.assertEqual(len(visited), 5)
            self.assertEqual(closed, [True])

    @unittest.skipUnless(os.name == "posix", "POSIX package discovery")
    def test_discovery_refuses_symlink_parents_and_prunes_excluded_trees(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "actual").mkdir()
            (root / "build").symlink_to(root / "actual", target_is_directory=True)
            metadata = {
                "name": "plowshare-client-ts",
                "version": "0.1.0",
                "files": ["build/*.js"],
            }
            (root / "package.json").write_text(json.dumps(metadata))
            with patch.object(build.os, "scandir") as scan:
                with self.assertRaisesRegex(build.dist.Failure, "PACKAGE_LINK"):
                    build.package_files(
                        root, "sdk", build.dist.Deadline(2), 100, 100000
                    )
                scan.assert_not_called()
            (root / "build").unlink()
            (root / "node_modules").mkdir()
            (root / "node_modules/never-read").symlink_to(
                root / "actual", target_is_directory=True
            )
            (root / "package.json").write_text(
                json.dumps({"name": "ws", "version": "8.22.0"})
            )
            actual_scan = os.scandir
            with patch.object(build.os, "scandir", wraps=actual_scan) as scan:
                selected = build.package_files(
                    root, "vendor", build.dist.Deadline(2), 100, 100000
                )
            self.assertNotIn("node_modules/never-read", selected.files)
            self.assertFalse(
                any(
                    Path(call.args[0]) == root / "node_modules"
                    for call in scan.call_args_list
                )
            )

    def test_unsupported_platform_refuses_before_posix_io_or_process_launch(self):
        error = io.StringIO()
        with patch.object(dist.sys, "platform", "win32"), patch.object(
            dist.os, "open"
        ) as opened, patch.object(dist.subprocess, "Popen") as launched, patch.object(
            sys, "argv", ["install.py", "--prefix", "/unused", "--node", "/unused"]
        ), contextlib.redirect_stderr(
            error
        ):
            with self.assertRaisesRegex(dist.Failure, "UNSUPPORTED_PLATFORM"):
                dist.supported_platform()
            self.assertEqual(dist.main(), 1)
        opened.assert_not_called()
        launched.assert_not_called()
        self.assertNotIn("Traceback", error.getvalue())
        self.assertNotIn("/unused", error.getvalue())

    def test_failed_kill_is_typed_uncertainty_even_if_short_lived_leader_exits(self):
        original = os.killpg

        def denied(pid, signal):
            if signal != 0:
                raise PermissionError("private kill failure")
            return original(pid, signal)

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            dist.os, "killpg", side_effect=denied
        ), patch.object(
            dist.selectors, "DefaultSelector", side_effect=OSError("setup failed")
        ):
            with self.assertRaises(dist.CleanupUncertain):
                dist.run([sys.executable, "-c", "pass"], temporary, dist.Deadline(2))

    def test_consumer_check_retains_parent_owner_after_uncertain_child(self):
        consumer = load("offline_consumer_test", "check-consumer.py")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "owner"
            root.mkdir()
            with patch.object(
                consumer.tempfile, "mkdtemp", return_value=str(root)
            ), patch.object(
                consumer.builder,
                "build",
                side_effect=consumer.dist.CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN"),
            ), patch.object(
                consumer.dist, "cleanup"
            ) as cleanup, patch.object(
                sys, "argv", ["check-consumer.py", "--node", sys.executable]
            ), contextlib.redirect_stderr(
                io.StringIO()
            ):
                self.assertEqual(consumer.main(), 1)
            cleanup.assert_not_called()
            self.assertTrue(root.is_dir())

    def test_builder_uncertainty_retains_only_its_original_stage(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            output = root / "distribution"
            with patch.object(
                build.dist,
                "run",
                side_effect=[
                    b'["/unused/playwright","/unused/core","/unused/ws"]',
                    b"a" * 40,
                    b"",
                ],
            ), patch.object(
                build,
                "artifact",
                side_effect=build.dist.CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN"),
            ), patch.object(
                build.dist, "cleanup"
            ) as cleanup, patch.object(
                build.dist, "publish"
            ) as publish:
                with self.assertRaises(build.dist.CleanupUncertain):
                    build.build(output, Path(sys.executable), build.dist.Deadline(2))
            cleanup.assert_not_called()
            publish.assert_not_called()
            self.assertEqual(len(list(root.glob(".computer-pack-*"))), 1)
            self.assertFalse(output.exists())

    def test_unreaped_smoke_retains_exact_owned_stage_and_never_publishes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            bundle(source)
            count = 0

            def uncertain(command, *args, **kwargs):
                nonlocal count
                count += 1
                if count == 2:
                    raise dist.CleanupUncertain("PROCESS_CLEANUP_UNCERTAIN")
                return b""

            with patch.object(dist, "run", side_effect=uncertain), patch.object(
                dist, "cleanup"
            ) as cleanup, patch.object(dist, "publish") as publish:
                with self.assertRaises(dist.CleanupUncertain):
                    dist.install(
                        source,
                        root / "installed",
                        Path(sys.executable),
                        dist.Deadline(2),
                    )
            cleanup.assert_not_called()
            publish.assert_not_called()
            stages = list(root.glob(".computer-install-*"))
            self.assertEqual(len(stages), 1)
            self.assertTrue((stages[0] / "node_modules").is_dir())
            self.assertFalse((root / "installed").exists())

    def test_malformed_manifest_types_have_fixed_cli_failure_without_traceback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            info = bundle(root)
            variants = []
            for path in ([], {}):
                changed = json.loads(json.dumps(info))
                changed["support"][0]["path"] = path
                variants.append(changed)
            for mode in (420.0, 493.0, True):
                changed = json.loads(json.dumps(info))
                changed["packages"][0]["files"][0]["mode"] = mode
                variants.append(changed)
            for changed in variants:
                (root / "manifest.json").write_text(json.dumps(changed))
                error = io.StringIO()
                with patch.object(
                    dist, "__file__", str(root / "install.py")
                ), patch.object(
                    sys,
                    "argv",
                    [
                        "install.py",
                        "--prefix",
                        str(root / "installed"),
                        "--node",
                        sys.executable,
                    ],
                ), patch.object(
                    dist, "run", return_value=b""
                ), contextlib.redirect_stderr(
                    error
                ):
                    self.assertEqual(dist.main(), 1)
                self.assertNotIn("Traceback", error.getvalue())
                self.assertNotIn(str(root), error.getvalue())
                self.assertFalse((root / "installed").exists())

    def test_post_spawn_selector_failure_reaps_the_exact_child(self):
        children = []
        original = subprocess.Popen

        def launch(*args, **kwargs):
            child = original(*args, **kwargs)
            children.append(child)
            return child

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            dist.subprocess, "Popen", side_effect=launch
        ), patch.object(
            dist.selectors,
            "DefaultSelector",
            side_effect=OSError("private setup error"),
        ):
            with self.assertRaises(dist.Failure):
                dist.run(
                    [sys.executable, "-c", "import time;time.sleep(30)"],
                    temporary,
                    dist.Deadline(2),
                )
        self.assertEqual(len(children), 1)
        self.assertIsNotNone(children[0].returncode)
        self.assertTrue(children[0].stdout.closed)
        self.assertTrue(children[0].stderr.closed)
        with self.assertRaises(ProcessLookupError):
            os.killpg(children[0].pid, 0)

    def test_manifest_rejects_unknown_duplicate_fields_package_count_and_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            info = bundle(Path(temporary))
            self.assertEqual(len(dist.manifest(json.dumps(info).encode()).packages), 6)
            for changed in (
                {**info, "unknown": True},
                {**info, "packages": info["packages"][:5]},
                {**info, "nodeMinimum": "20"},
                {**info, "dirty": 1},
            ):
                with self.assertRaises(dist.Failure):
                    dist.manifest(json.dumps(changed).encode())
            for invalid in (
                "../secret",
                "/secret",
                "a//b",
                "a/./b",
                "a\\b",
                "C:secret",
            ):
                with self.assertRaises(dist.Failure):
                    dist.relative(invalid)
            with self.assertRaises(dist.Failure):
                dist.strict_json(b'{"x":1,"\\u0078":2}')
            with self.assertRaises(dist.Failure):
                dist.strict_json('{"x":1}'.encode("utf-16"))

    def test_full_offline_install_checks_all_hashes_and_publishes_unused_prefix(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            info = bundle(source)
            prefix = root / "installed"
            with patch.object(dist, "run", return_value=b"") as run, patch.object(
                dist,
                "cleanup",
                side_effect=lambda stage: (
                    shutil.rmtree(stage) if stage.exists() else None
                ),
            ):
                dist.install(source, prefix, Path(sys.executable), dist.Deadline(2))
            self.assertEqual(run.call_count, 3)
            for package in info["packages"]:
                self.assertTrue(
                    (
                        prefix / "node_modules" / package["name"] / "package.json"
                    ).is_file()
                )
            with self.assertRaises(dist.Failure):
                dist.install(source, prefix, Path(sys.executable), dist.Deadline(2))

    def test_corrupt_archive_never_publishes_or_executes_smoke(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            info = bundle(source)
            (source / info["packages"][0]["archive"]).write_bytes(b"corrupt")
            with patch.object(dist, "run", return_value=b"") as run, patch.object(
                dist,
                "cleanup",
                side_effect=lambda stage: (
                    shutil.rmtree(stage) if stage.exists() else None
                ),
            ):
                with self.assertRaises(dist.Failure):
                    dist.install(
                        source,
                        root / "installed",
                        Path(sys.executable),
                        dist.Deadline(2),
                    )
            self.assertEqual(run.call_count, 1)
            self.assertFalse((root / "installed").exists())
            self.assertFalse(list(root.glob(".computer-install-*")))

    def test_tar_specials_duplicates_traversal_and_pax_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            info = bundle(root)
            package = dist.manifest(json.dumps(info).encode()).packages[0]
            payload = json.dumps({"name": package.name, "version": "0.1.0"}).encode()
            variants = [
                archive([("package/package.json", b"", tarfile.SYMTYPE)]),
                archive([("package/../escape", payload, tarfile.REGTYPE)]),
                archive([("package/package.json", payload, tarfile.REGTYPE)] * 2),
            ]
            extended = io.BytesIO()
            with tarfile.open(
                fileobj=extended, mode="w:gz", format=tarfile.PAX_FORMAT
            ) as out:
                item = tarfile.TarInfo("package/package.json")
                item.size = len(payload)
                item.pax_headers = {"comment": "no metadata extension"}
                out.addfile(item, io.BytesIO(payload))
            variants.append(extended.getvalue())
            for index, data in enumerate(variants):
                stage = root / str(index)
                stage.mkdir()
                with self.assertRaises(dist.Failure):
                    dist.unpack(package, data, stage / "out", stage, dist.Deadline(2))

    def test_decompression_is_bounded_before_tar_parsing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(dist, "MAX_TOTAL", 512):
                with self.assertRaisesRegex(dist.Failure, "ARCHIVE_LIMIT"):
                    dist.unpack(
                        dist.Package("fixture", "0.1.0", "", "", 0, ()),
                        gzip.compress(b"X" * 513),
                        root / "out",
                        root,
                        dist.Deadline(2),
                    )
            self.assertFalse((root / "out").exists())

    def test_manifest_count_and_byte_caps_precede_install_work(self):
        with tempfile.TemporaryDirectory() as temporary:
            info = bundle(Path(temporary))
            with patch.object(dist, "MAX_FILES", 5):
                with self.assertRaises(dist.Failure):
                    dist.manifest(json.dumps(info).encode())
            info["packages"][0]["files"][0]["bytes"] = dist.MAX_FILE + 1
            with self.assertRaises(dist.Failure):
                dist.manifest(json.dumps(info).encode())

    def test_command_deadline_prevents_spawn_and_timeout_reaps_owned_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            deadline = dist.Deadline(0.01)
            time.sleep(0.02)
            with patch.object(dist.subprocess, "Popen") as launch:
                with self.assertRaises(dist.Failure):
                    dist.run([sys.executable], temporary, deadline)
                launch.assert_not_called()
            children = []
            original = subprocess.Popen

            def launch(*args, **kwargs):
                child = original(*args, **kwargs)
                children.append(child)
                return child

            with patch.object(dist.subprocess, "Popen", side_effect=launch):
                with self.assertRaises(dist.Failure):
                    dist.run(
                        [sys.executable, "-c", "import time;time.sleep(30)"],
                        temporary,
                        dist.Deadline(0.05),
                    )
            self.assertIsNotNone(children[0].returncode)
            with self.assertRaises(ProcessLookupError):
                os.killpg(children[0].pid, 0)

    def test_process_output_is_bounded_and_errors_never_contain_raw_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(dist.Failure) as caught:
                dist.run(
                    [
                        sys.executable,
                        "-c",
                        "import os;os.write(2,b'PRIVATE_SECRET'*100000)",
                    ],
                    temporary,
                    dist.Deadline(2),
                )
            self.assertNotIn("PRIVATE_SECRET", str(caught.exception))

    def test_exclusive_publication_never_replaces_existing_or_symlink_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source = root / "source"
            source.mkdir()
            destination = root / "existing"
            destination.mkdir()
            with self.assertRaises(dist.Failure):
                dist.publish(source, destination)
            self.assertTrue(source.is_dir())
            destination.rmdir()
            destination.symlink_to(source)
            with self.assertRaises(dist.Failure):
                dist.publish(source, destination)
            self.assertTrue(destination.is_symlink())
            self.assertTrue(source.is_dir())

    def test_package_collection_excludes_provider_tests_and_requires_prebuilt_entries(
        self,
    ):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "build/computer").mkdir(parents=True)
            metadata = {
                "name": "plowshare-computer-control",
                "version": "0.1.0",
                "exports": {".": {}},
                "dependencies": {},
            }
            (root / "package.json").write_text(json.dumps(metadata))
            for name in (
                "index.js",
                "index.d.ts",
                "main.js",
                "secret.test.js",
                "fixture.test-support.js",
                "acceptance.js",
            ):
                (root / "build/computer" / name).write_text("fixture")
            source = build.package_files(
                root, "provider", dist.Deadline(2), 100, 100000
            )
            files = source.files
            self.assertNotIn("build/computer/secret.test.js", files)
            self.assertNotIn("build/computer/fixture.test-support.js", files)
            self.assertNotIn("build/computer/acceptance.js", files)
            self.assertIn("LICENSE", files)
            self.assertIn("NOTICE", files)
            self.assertNotIn("link:", files["package.json"].decode())
            first = build.artifact(
                root, "provider", root / "first.tgz", dist.Deadline(2), 100, 100000
            )
            second = build.artifact(
                root, "provider", root / "second.tgz", dist.Deadline(2), 100, 100000
            )
            self.assertEqual(first.sha256, second.sha256)
            with self.assertRaises(build.dist.Failure):
                build.package_files(root, "provider", dist.Deadline(2), 1, 100000)


if __name__ == "__main__":
    unittest.main()
