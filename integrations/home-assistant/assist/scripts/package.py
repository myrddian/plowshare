"""Refuse installation packaging while required HA prompt fidelity is unavailable.

The SDK is not published on PyPI. Shipping its source/resources avoids requiring
manual pip changes inside HA OS. Test fixtures retain that packaging contract;
do not edit or check in the generated vendor tree.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import tempfile
import tomllib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT.parents[2]


def stage_test_fixture(destination: Path) -> Path:
    """Stage isolated test imports; this is not an installation artifact API."""
    component = destination / "custom_components/plowshare"
    shutil.copytree(
        ROOT / "custom_components/plowshare",
        component,
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    vendor = component / "_vendor"
    vendor.mkdir()
    (vendor / "__init__.py").write_text(
        '"""Unmodified public SDK, copied by package.py."""\n'
    )
    shutil.copytree(
        REPOSITORY / "sdk/python/src/plowshare",
        vendor / "plowshare",
        ignore=shutil.ignore_patterns("__pycache__"),
    )
    shutil.copyfile(REPOSITORY / "LICENSE", vendor / "LICENSE")
    sdk = tomllib.loads((REPOSITORY / "sdk/python/pyproject.toml").read_text())
    manifest = json.loads((component / "manifest.json").read_text())
    if manifest["requirements"] != sdk["project"]["dependencies"]:
        raise ValueError("HA requirements must match the bundled SDK dependencies")
    return component


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "build/plowshare-assist.zip"
    )
    args = parser.parse_args()

    # Load only the dependency-free capability decision, never HA or its SDK.
    # Refuse before creating output directories or replacing an existing archive.
    spec = importlib.util.spec_from_file_location(
        "assist_availability", ROOT / "custom_components/plowshare/availability.py"
    )
    if spec is None or spec.loader is None:
        parser.error("Unable to inspect the Assist publication gate")
    availability = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(availability)
    try:
        availability.require_prompt_fidelity()
    except availability.PromptFidelityUnavailableError as error:
        parser.error(str(error))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        staging = Path(temporary)
        stage_test_fixture(staging)
        with ZipFile(args.output, "w", ZIP_DEFLATED) as archive:
            for path in sorted(staging.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(staging))
    print(args.output.resolve())


if __name__ == "__main__":
    main()
