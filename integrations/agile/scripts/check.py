"""Explicit check gate for the external package; never starts PostgreSQL."""

import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
environment = dict(os.environ, PYTHONPYCACHEPREFIX=str(root / "build/python-cache"))
for arguments in (
    ["ruff", "check", "."],
    ["ruff", "format", "--check", "."],
    ["mypy", "src", "tests", "scripts"],
    ["unittest", "discover", "-s", "tests", "-p", "test_*.py"],
):
    subprocess.run(
        [sys.executable, "-m", *arguments], cwd=root, env=environment, check=True
    )

subprocess.run(["node", "scripts/build_web.mjs", "--check"], cwd=root, check=True)
subprocess.run(
    ["node", "--test", *map(str, sorted((root / "tests").glob("web_*.test.*")))],
    cwd=root,
    check=True,
)
