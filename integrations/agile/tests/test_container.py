from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from plowshare_agile.container import run, secret


class ContainerTests(unittest.TestCase):
    def test_secrets_require_explicit_bounded_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "key"
            with patch.dict(os.environ, {}, clear=True):
                with self.assertRaises(ValueError):
                    secret("KEY_FILE")
                os.environ["KEY_FILE"] = str(path)
                path.write_text("private-fixture-secret\n")
                self.assertEqual(secret("KEY_FILE"), "private-fixture-secret")
                for value in ("", " private", "private\nsecond", "a" * 1025):
                    path.write_text(value)
                    with self.assertRaises(ValueError):
                        secret("KEY_FILE")

    def test_container_refuses_missing_settings_before_startup(self) -> None:
        with (
            patch.dict(os.environ, {}, clear=True),
            patch("sys.argv", ["container"]),
            patch("plowshare_agile.container.main") as main,
        ):
            with self.assertRaises(ValueError):
                run()
            main.assert_not_called()
