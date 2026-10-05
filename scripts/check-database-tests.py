#!/usr/bin/env python3
"""Prevent new Docker fixtures from entering the default Java test run."""

from pathlib import Path
import re
import sys


def violations(root: Path) -> list[Path]:
    """Return test sources importing Testcontainers without the opt-in tag."""
    container_import = re.compile(r"^import (?:static )?org\.testcontainers\.", re.MULTILINE)
    database_tag = re.compile(r'^\s*@Tag\("full-db"\)\s*$', re.MULTILINE)
    sources = (
        source
        for pattern in (
            "*/src/test/java/**/*.java",
            "sdk/*/src/test/java/**/*.java",
            "integrations/*/src/test/java/**/*.java",
        )
        for source in root.glob(pattern)
    )
    return sorted(
        source.relative_to(root)
        for source in sources
        if container_import.search(text := source.read_text()) and not database_tag.search(text)
    )


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    missing = violations(root)
    for source in missing:
        print(f'{source}: Docker tests must declare @Tag("full-db")', file=sys.stderr)
    sys.exit(bool(missing))
