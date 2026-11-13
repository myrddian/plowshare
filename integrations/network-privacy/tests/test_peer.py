"""Retired peer evidence is validated read-only, never adopted as a new tool call."""

from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from support import configuration

from plowshare_privacy.cli import execute
from plowshare_privacy.contracts import Configuration, load_json
from plowshare_privacy.journal import FileReceipts
from plowshare_privacy.peer_journal import PeerReceipt, require_settled_legacy_peer
from plowshare_privacy.worker import ReconciliationRequired


class LegacyPeerFenceTest(unittest.TestCase):
    def test_clean_and_terminal_history_remain_read_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            with FileReceipts(config) as owner:
                self.assertEqual((), require_settled_legacy_peer(owner))
                receipt = PeerReceipt(
                    str(uuid4()), 3, "done", "COMPLETED", '{"scan":"private"}'
                )
                path = owner.directory / "outgoing.json"
                path.write_text(
                    json.dumps(
                        {
                            "version": 1,
                            "identity": owner.identity + ":scanner",
                            "receipts": [asdict(receipt)],
                        }
                    )
                )
                original = path.read_bytes()
                self.assertEqual((receipt,), require_settled_legacy_peer(owner))
                self.assertEqual(original, path.read_bytes())

    def test_every_unresolved_phase_fences_intake_without_mutating_evidence(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            with FileReceipts(config) as owner:
                path = owner.directory / "outgoing.json"
                base = PeerReceipt(str(uuid4()), 1, "done", "UNKNOWN", "private-result")
                for phase in ("executing", "ready", "reporting"):
                    row = asdict(base)
                    row["phase"] = phase
                    path.write_text(
                        json.dumps(
                            {
                                "version": 1,
                                "identity": owner.identity + ":scanner",
                                "receipts": [row],
                            }
                        )
                    )
                    original = path.read_bytes()
                    with self.assertRaises(ReconciliationRequired) as caught:
                        require_settled_legacy_peer(owner)
                    self.assertNotIn("private-result", str(caught.exception))
                    self.assertEqual(original, path.read_bytes())

    def test_foreign_corrupt_duplicate_oversized_and_linked_terminal_evidence_is_refused(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            with FileReceipts(config) as owner:
                path = owner.directory / "outgoing.json"
                receipt = asdict(
                    PeerReceipt(str(uuid4()), 1, "done", "COMPLETED", "retained")
                )
                row = {
                    "version": 1,
                    "identity": owner.identity + ":scanner",
                    "receipts": [receipt],
                }
                for invalid in (
                    {**row, "identity": "foreign:scanner"},
                    {**row, "identity": owner.identity + ":"},
                    {**row, "version": 2},
                    {**row, "receipts": [receipt, receipt]},
                    {**row, "receipts": [receipt] * 1001},
                    {**row, "receipts": [{**receipt, "phase": "invalid"}]},
                ):
                    path.write_text(json.dumps(invalid))
                    with self.assertRaises(ValueError):
                        require_settled_legacy_peer(owner)
                path.write_text('{"version":1,"version":1}')
                with self.assertRaises(ValueError):
                    require_settled_legacy_peer(owner)
                path.write_text("x" * 1_048_577)
                with self.assertRaises(ValueError):
                    require_settled_legacy_peer(owner)
                path.unlink()
                path.symlink_to(owner.directory / "missing.json")
                with self.assertRaisesRegex(ValueError, "linked"):
                    require_settled_legacy_peer(owner)

    def test_retired_configuration_fails_before_server_connection(self) -> None:
        example = Path(__file__).resolve().parents[1] / "examples/config.json"
        # Use the shipped exact schema, then inject only the retired field.
        source = load_json(example)
        if not isinstance(source, dict):
            raise AssertionError("Expected example object")
        source["outgoingPeer"] = "old-peer"
        with self.assertRaisesRegex(ValueError, "outgoingPeer is retired"):
            Configuration.decode(source, example)


class StartupFenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_unresolved_old_work_stops_before_sdk_intake_or_collection(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            config = configuration(Path(temporary))
            with FileReceipts(config) as owner:
                path = owner.directory / "outgoing.json"
                path.write_text(
                    json.dumps(
                        {
                            "version": 1,
                            "identity": owner.identity + ":scanner",
                            "receipts": [
                                asdict(
                                    PeerReceipt(
                                        str(uuid4()),
                                        1,
                                        "reporting",
                                        "UNKNOWN",
                                        "private",
                                    )
                                )
                            ],
                        }
                    )
                )
            client = AsyncMock()
            client.__aenter__.return_value = client
            args = argparse.Namespace(
                config="private",
                command="once",
                tool_provider="scanner",
                tool_account="provider",
            )
            with (
                patch("plowshare_privacy.cli.Configuration.read", return_value=config),
                patch("plowshare_privacy.cli.credential", return_value="fixture"),
                patch(
                    "plowshare_privacy.cli.Client.connect",
                    new=AsyncMock(return_value=client),
                ),
                patch("plowshare_privacy.cli.Worker") as worker,
            ):
                with self.assertRaises(ReconciliationRequired):
                    await execute(args)
                client.request.assert_not_awaited()
                worker.assert_not_called()
