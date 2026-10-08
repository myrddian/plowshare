"""Explicit Python gate; dependencies must already be installed in this interpreter."""

import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
# Bytecode is generated output. Keep it out of the source trees inspected by
# the repository's source-text guard, including when running an editable install.
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
