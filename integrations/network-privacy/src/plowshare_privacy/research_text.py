"""Prose contracts shared by product results and retained research references."""

import re

TITLE_WORDS = 200
TITLE_TOLERANCE_WORDS = TITLE_WORDS // 4
# Match the server recovery policy: Unicode whitespace, NEL and BOM delimit words.
WORD = re.compile(r"[^\s\u0085\ufeff]+")


def research_title(value: object) -> str:
    """Validate prose in words; enclosing replies and journals bound raw size."""
    if not isinstance(value, str):
        raise ValueError("Research source title must be text")
    count = len(WORD.findall(value))
    if count == 0 or count > TITLE_WORDS + TITLE_TOLERANCE_WORDS:
        raise ValueError(
            f"Research source title must contain 1 to {TITLE_WORDS + TITLE_TOLERANCE_WORDS} words "
            f"({TITLE_WORDS}-word target with 25% tolerance)"
        )
    if any((ord(c) < 32 and c not in "\t\n\r") or ord(c) == 127 for c in value):
        raise ValueError("Research source title contains forbidden control characters")
    return value
