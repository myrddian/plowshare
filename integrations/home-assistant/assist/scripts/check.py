"""Explicit HA runtime check; no PostgreSQL, live HA or model provider required."""

import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for arguments in (
    ["ruff", "check", "."],
    ["ruff", "format", "--check", "."],
    ["unittest", "discover", "-s", "tests", "-v"],
):
    subprocess.run([sys.executable, "-m", *arguments], cwd=root, check=True)
