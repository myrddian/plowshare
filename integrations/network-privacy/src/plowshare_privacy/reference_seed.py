"""The packaged seed list of vendor documentation pages for the device reference library.

The seed is a reviewed, bounded list of https documentation pages the collector may
fetch to ground its device guidance. A seed entry names a product slug, a title, one
https URL and topic tags. The file is ``{"version": 1, "entries": [...]}``; any
unknown field, unsupported version, duplicate URL or oversize list is rejected so a
bad file fails loudly instead of silently narrowing the library.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from .contracts import items, load_json, object_fields, text

SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,47}$")
MAX_SEED_ENTRIES = 20
MAX_URL_CHARACTERS = 2048
MAX_TOPICS = 8
SEED_VERSION = 1
PACKAGED_SEED = Path(__file__).parent / "references" / "seed.json"


@dataclass(frozen=True)
class SeedEntry:
    product: str
    title: str
    url: str
    topics: tuple[str, ...]


def product_slug(value: object) -> str:
    if not isinstance(value, str) or not SLUG.fullmatch(value):
        raise ValueError(
            "A product name must be lowercase letters, digits and hyphens, "
            "starting with a letter or digit, at most 48 characters."
        )
    return value


def reference_url(value: object) -> str:
    """Accept only a plain https URL: no userinfo, no whitespace, bounded length."""
    if (
        not isinstance(value, str)
        or not value
        or len(value) > MAX_URL_CHARACTERS
        or value != value.strip()
        or any(ord(c) <= 32 or ord(c) == 127 for c in value)
    ):
        raise ValueError(
            "A reference link must be a single https address of at most 2048 characters."
        )
    parts = urlsplit(value)
    if parts.scheme != "https" or not parts.hostname:
        raise ValueError("A reference link must use https and name a host.")
    if parts.username is not None or parts.password is not None or "@" in parts.netloc:
        raise ValueError("A reference link cannot contain a username or password.")
    return value


def _topic(value: object) -> str:
    topic = text(value, 48)
    if not SLUG.fullmatch(topic):
        raise ValueError("A topic must be lowercase letters, digits and hyphens.")
    return topic


def _entry(value: object) -> SeedEntry:
    row = object_fields(value, {"product", "title", "url", "topics"})
    return SeedEntry(
        product=product_slug(row["product"]),
        title=text(row["title"], 200),
        url=reference_url(row["url"]),
        topics=tuple(_topic(topic) for topic in items(row["topics"], MAX_TOPICS)),
    )


def load_seed(path: Path | None = None) -> tuple[SeedEntry, ...]:
    """Load and validate the seed; the default is the packaged file."""
    document = object_fields(load_json(path or PACKAGED_SEED), {"version", "entries"})
    if document["version"] != SEED_VERSION or type(document["version"]) is not int:
        raise ValueError("Unsupported reference seed version.")
    entries = tuple(_entry(row) for row in items(document["entries"], MAX_SEED_ENTRIES))
    if len({entry.url for entry in entries}) != len(entries):
        raise ValueError("Reference seed links must be unique.")
    return entries
