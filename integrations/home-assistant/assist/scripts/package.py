"""Build an independently installable HA component with the unchanged public SDK.

The SDK is not published on PyPI. Copy its complete source/resources and license
into the component's private namespace; never edit or check in that vendor tree.
Build the complete archive before atomically replacing an earlier artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import tempfile
import tomllib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT.parents[2]


def _copy_file(source: Path, destination: Path) -> None:
    """Reject leaf symlinks/special files consistently for docs and license too."""
    if not stat.S_ISREG(source.lstat().st_mode):
        raise ValueError("Package source file must be a regular file")
    shutil.copyfile(source, destination)


def _copy_tree(source: Path, destination: Path) -> None:
    """Copy only regular files/directories; source symlinks cannot escape roots.

    Python caches are generated local state and never installation input. All
    other source files, including non-Python SDK resources, retain exact bytes.
    """
    if not stat.S_ISDIR(source.lstat().st_mode):
        raise ValueError("Package source root must be a real directory")
    destination.mkdir(parents=True, exist_ok=False)
    for item in sorted(source.iterdir()):
        if item.name == "__pycache__":
            continue  # Generated cache directories are explicitly excluded.
        if item.name.startswith("."):
            raise ValueError("Hidden runtime/configuration files are not package input")
        mode = item.lstat().st_mode
        target = destination / item.name
        if stat.S_ISDIR(mode):
            _copy_tree(item, target)
        elif stat.S_ISREG(mode):
            if item.suffix in {".pyc", ".pyo"}:
                continue
            if item.suffix not in {".py", ".json", ".md"} and item.name != "py.typed":
                raise ValueError("Unexpected package source file type")
            _copy_file(item, target)
        else:
            raise ValueError("Package source contains a symlink or special file")


def stage_component(destination: Path) -> Path:
    """Stage the actual installation component; tests consume this same layout.

    The destination must be isolated from source and initially contain no
    component. An existing source vendor namespace is a packaging error rather
    than a second potentially stale SDK authority.
    """
    source = ROOT / "custom_components/plowshare"
    resolved = destination.resolve()
    inputs = (
        source,
        REPOSITORY / "sdk/python/src/plowshare",
        ROOT.parent / "examples/application/profiles",
    )
    if any(
        resolved.is_relative_to(path.resolve())
        or path.resolve().is_relative_to(resolved)
        for path in inputs
    ):
        raise ValueError("Staging destination must not overlap package sources")
    if (source / "_vendor").exists() or (source / "_vendor").is_symlink():
        raise ValueError("Generated SDK vendor trees must not be source input")
    sdk = tomllib.loads((REPOSITORY / "sdk/python/pyproject.toml").read_text())
    manifest = json.loads((source / "manifest.json").read_text())
    if manifest["domain"] != "plowshare":
        raise ValueError("Unexpected HA component namespace")
    if manifest["requirements"] != sdk["project"]["dependencies"]:
        raise ValueError("HA requirements must match the bundled SDK dependencies")
    component = destination / "custom_components/plowshare"
    _copy_tree(source, component)
    vendor = component / "_vendor"
    vendor.mkdir()
    (vendor / "__init__.py").write_text(
        '"""Unmodified public SDK, copied by package.py."""\n', encoding="utf-8"
    )
    _copy_tree(REPOSITORY / "sdk/python/src/plowshare", vendor / "plowshare")
    _copy_file(REPOSITORY / "LICENSE", vendor / "LICENSE")
    return component


def _stage_archive(destination: Path) -> None:
    stage_component(destination)
    _copy_file(ROOT / "README.md", destination / "INSTALL.md")
    profiles = destination / "profiles"
    profiles.mkdir()
    _copy_file(ROOT / "PACKAGE-PROFILES.md", profiles / "README.md")
    source = ROOT.parent / "examples/application/profiles"
    for name in ("readonly", "control", "task"):
        _copy_tree(source / name, profiles / name)
    # This manifest describes artifact bytes, not source acceptance/deployment.
    contents = {
        path.relative_to(destination).as_posix(): hashlib.sha256(
            path.read_bytes()
        ).hexdigest()
        for path in sorted(destination.rglob("*"))
        if path.is_file()
    }
    (destination / "package-manifest.json").write_text(
        json.dumps({"format": 1, "sha256": contents}, indent=2) + "\n",
        encoding="utf-8",
    )


def build_archive(output: Path) -> Path:
    """Publish one complete ZIP atomically; any staging/write error preserves old output.

    Staging finishes before creating an output directory. Archive entries have
    fixed metadata and sorted relative paths. No private HA storage, credentials,
    test files or source checkout paths are collected.
    """
    output = output.absolute()
    resolved = output.resolve()
    # Default build output is allowed; all other checkout paths are source, not
    # an output sink. In particular a CLI typo cannot replace a README/script.
    if resolved.is_relative_to(REPOSITORY.resolve()) and not resolved.is_relative_to(
        (ROOT / "build").resolve()
    ):
        raise ValueError("Archive output must be outside source or in Assist build")
    with tempfile.TemporaryDirectory() as temporary:
        staging = Path(temporary)
        _stage_archive(staging)
        output.parent.mkdir(parents=True, exist_ok=True)
        descriptor, pending_name = tempfile.mkstemp(
            prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
        )
        pending = Path(pending_name)
        try:
            with os.fdopen(descriptor, "w+b") as stream:
                with ZipFile(stream, "w", ZIP_DEFLATED) as archive:
                    for path in sorted(staging.rglob("*")):
                        if path.is_file():
                            entry = ZipInfo(path.relative_to(staging).as_posix())
                            entry.compress_type = ZIP_DEFLATED
                            entry.external_attr = 0o100644 << 16
                            archive.writestr(entry, path.read_bytes())
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(pending, output)
        finally:
            pending.unlink(missing_ok=True)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "build/plowshare-assist.zip"
    )
    args = parser.parse_args()
    try:
        output = build_archive(args.output)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    print(output)


if __name__ == "__main__":
    main()
