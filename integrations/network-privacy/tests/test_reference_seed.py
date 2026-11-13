from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from plowshare_privacy.reference_seed import (
    MAX_SEED_ENTRIES,
    load_seed,
    product_slug,
    reference_url,
)


def entry(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "product": "caddy",
        "title": "Caddy API",
        "url": "https://caddyserver.com/docs/api",
        "topics": [],
    }
    row.update(overrides)
    return row


def write_seed(root: Path, document: object) -> Path:
    path = root / "seed.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    path.chmod(0o600)
    return path


class ReferenceSeedTests(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def test_packaged_seed_loads(self) -> None:
        seed = load_seed()
        self.assertTrue(1 <= len(seed) <= MAX_SEED_ENTRIES)
        self.assertIn("caddy", {item.product for item in seed})

    def test_valid_file_round_trips_entry_fields(self) -> None:
        seed = load_seed(
            write_seed(
                self.root,
                {"version": 1, "entries": [entry(topics=["admin-api"])]},
            )
        )
        self.assertEqual(("admin-api",), seed[0].topics)
        self.assertEqual("https://caddyserver.com/docs/api", seed[0].url)

    def test_rejects_http_userinfo_and_long_urls(self) -> None:
        for bad in (
            "http://caddyserver.com/docs/api",
            "https://u:p@x.test/a",
            "https://x.test/" + "a" * 2048,
            " https://x.test/a",
            "https:///a",
            42,
        ):
            with self.assertRaises(ValueError, msg=str(bad)):
                reference_url(bad)

    def test_accepts_https_url(self) -> None:
        self.assertEqual("https://x.test/a?b=1", reference_url("https://x.test/a?b=1"))

    def test_slug_rules(self) -> None:
        self.assertEqual("pi-hole", product_slug("pi-hole"))
        for bad in ("Caddy Server", "", "-x", "a" * 49, None):
            with self.assertRaises(ValueError, msg=str(bad)):
                product_slug(bad)

    def test_rejects_bad_slug_duplicate_url_and_oversize(self) -> None:
        cases = (
            [entry(product="Caddy Server")],
            [entry(url="https://x.test/a"), entry(url="https://x.test/a")],
            [entry(url=f"https://x.test/{i}") for i in range(21)],
        )
        for entries in cases:
            with self.assertRaises(ValueError):
                load_seed(write_seed(self.root, {"version": 1, "entries": entries}))

    def test_rejects_unknown_fields_and_version(self) -> None:
        for document in (
            {"version": 2, "entries": [entry()]},
            {"version": 1, "entries": [entry(extra="x")]},
            {"version": 1},
        ):
            with self.assertRaises(ValueError):
                load_seed(write_seed(self.root, document))

    def test_rejects_bad_topics(self) -> None:
        for topics in ("admin-api", [1], ["Bad Topic"], ["a"] * 9):
            with self.assertRaises(ValueError, msg=str(topics)):
                load_seed(
                    write_seed(
                        self.root, {"version": 1, "entries": [entry(topics=topics)]}
                    )
                )


if __name__ == "__main__":
    unittest.main()
