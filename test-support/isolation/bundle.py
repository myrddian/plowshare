#!/usr/bin/env python3
"""Bundle public Linux acceptance artifacts, without caches, credentials or host metadata."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


def public_metadata(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = info.gid = 1000
    info.uname = info.gname = ""
    info.pax_headers = {}
    return info


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new .tgz file; existing files are not replaced")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repository, text=True
    ).strip()
    if subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=repository, text=True
    ).strip():
        parser.error("commit source changes before creating a revision-labelled bundle")
    with tempfile.TemporaryDirectory(prefix="plowshare-isolation-bundle-") as temporary:
        stage = Path(temporary)
        for name in [
            "plowshare-protocol/build/classes/java/main",
            "plowshare-server/build/isolation-acceptance",
            "sdk/node/build",
            "test-support/isolation",
        ]:
            shutil.copytree(repository / name, stage / name)
        # Only the published neutral SDK directories; omit test output and compiler caches.
        for part in ["binding", "jobs", "operations"]:
            name = Path("sdk/typescript/build") / part
            shutil.copytree(repository / name, stage / name,
                            ignore=shutil.ignore_patterns("*.tsbuildinfo"))
        test = Path("plowshare-protocol/build/classes/java/test/io/aeyer/plowshare/protocol")
        (stage / test).mkdir(parents=True)
        shutil.copy2(repository / test / "BubblewrapAcceptance.class", stage / test)
        for package in ["node", "typescript"]:
            shutil.copy2(repository / "sdk" / package / "package.json", stage / "sdk" / package)
        # Only the public neutral SDK is needed by runner; never copy an operator's node_modules.
        modules = stage / "sdk/node/node_modules"
        modules.mkdir()
        (modules / "plowshare-client-ts").symlink_to("../../typescript", target_is_directory=True)
        (stage / "source-revision.txt").write_text(revision + "\n")
        with args.output.open("xb") as output:
            with tarfile.open(fileobj=output, mode="w:gz") as archive:
                archive.add(stage, arcname=".", filter=public_metadata)
    print(f"Public acceptance bundle: {args.output} (source {revision})")


if __name__ == "__main__":
    main()
