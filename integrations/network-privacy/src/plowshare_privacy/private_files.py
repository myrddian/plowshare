"""Atomic private configuration replacement, including directory durability."""

from __future__ import annotations

import os
from pathlib import Path
from uuid import uuid4


def replace_private(path: Path, content: str) -> None:
    """Atomically replace private configuration, including a durable directory flush."""
    if path.is_symlink() or path.resolve() != path:
        raise ValueError("Private configuration cannot be linked")
    temporary = path.parent / (str(uuid4()) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)
