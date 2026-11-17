"""Stage a test-only fixture, then import its real SDK and HA modules."""

import atexit
import importlib.util
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "package_assist", ROOT / "scripts/package.py"
)
assert spec is not None and spec.loader is not None
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)
temporary = tempfile.TemporaryDirectory()
atexit.register(temporary.cleanup)
STAGING = Path(temporary.name)
COMPONENT = packager.stage_test_fixture(STAGING)
sys.path.insert(0, str(STAGING))
