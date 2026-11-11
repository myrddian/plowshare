"""Additive metadata upgrades retain effects and uncertain phases without replay."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from plowshare.tool_journal import SqliteToolJournal
from plowshare.tools import ToolReceipt, ToolResult, deployment_config
from support import configuration as collector_configuration

from plowshare_privacy.journal import FileReceipts
from plowshare_privacy.native_tools import DECLARATIONS
from plowshare_privacy.tool_journal_upgrade import (
    LEGACY_DECLARATIONS,
    PAGED_DECLARATIONS,
    open_tool_journal,
    receipt_stages,
)

# The six-tool paged binding that deployments already hold. If this digest
# changes, existing paged journals refuse to open: freeze the old declarations
# instead of editing them.
PAGED_BINDING = "c2374bb551061811a7287459a45aa0a0cdbe83002e134de0effc09bdba3c55a6"


def receipt() -> ToolReceipt:
    identity = str(uuid4())
    request = json.dumps(
        {
            "schema": "plowshare-tool/1",
            "invocationId": identity,
            "project": "fixture",
            "provider": "scanner",
            "tool": "network_scope",
            "account": "caller",
            "run": "run",
            "call": "model-call",
            "deadline": "2099-01-01T00:00:00Z",
            "arguments": {},
        }
    )
    return ToolReceipt(
        identity,
        request,
        "done",
        ToolResult("COMPLETED", '{"targets":[]}'),
        "2026-01-01T00:00:00Z",
    )


class JournalUpgradeTest(unittest.TestCase):
    def test_all_phases_survive_copy_restart_with_collector_process_fence(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            configuration = deployment_config(
                "fixture", "scanner", "owner", LEGACY_DECLARATIONS
            )
            rows = []
            with SqliteToolJournal(directory, configuration=configuration) as legacy:
                for phase in range(4):
                    stages = receipt_stages(receipt())
                    for stage in stages[: phase + 1]:
                        legacy.save(stage)
                    rows.append(stages[phase])
                retained = legacy.all()
            config = collector_configuration(Path(temporary).resolve())
            with (
                FileReceipts(config),
                open_tool_journal(directory, "fixture", "scanner", "owner") as current,
            ):
                self.assertEqual(current.all(), retained)
                with self.assertRaises(BlockingIOError):
                    FileReceipts(config)
                item = receipt()
                for stage in receipt_stages(item):
                    current.save(stage)
            with open_tool_journal(directory, "fixture", "scanner", "owner") as current:
                self.assertEqual(set(current.all()), set((*retained, item)))
            with SqliteToolJournal(directory, configuration=configuration) as legacy:
                self.assertEqual(legacy.all(), retained)
            with self.assertRaisesRegex(ValueError, "another deployment"):
                with open_tool_journal(directory, "fixture", "scanner", "foreign"):
                    self.fail("foreign journal admitted")

    def test_interrupted_local_copy_resumes_without_handler_or_publication(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            row = receipt()
            with SqliteToolJournal(
                directory,
                configuration=deployment_config(
                    "fixture", "scanner", "owner", LEGACY_DECLARATIONS
                ),
            ) as legacy:
                for stage in receipt_stages(row):
                    legacy.save(stage)
            original = SqliteToolJournal.save

            def stop_at_publication(
                store: SqliteToolJournal, item: ToolReceipt
            ) -> None:
                if item.phase == "publishing":
                    raise RuntimeError("stopped copy")
                original(store, item)

            with patch.object(SqliteToolJournal, "save", stop_at_publication):
                with self.assertRaisesRegex(RuntimeError, "stopped copy"):
                    with open_tool_journal(directory, "fixture", "scanner", "owner"):
                        self.fail("copy did not stop")
            with open_tool_journal(directory, "fixture", "scanner", "owner") as current:
                self.assertEqual(current.all(), (row,))
            marker = directory.parent / "relay-tools-paged/transition.json"
            value = json.loads(marker.read_text())
            value["receipts"] = True
            marker.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "Foreign tool transition"):
                with open_tool_journal(directory, "fixture", "scanner", "owner"):
                    self.fail("bad marker admitted")

    def test_unmarked_foreign_work_and_changed_source_are_refused(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            target = directory.parent / "relay-tools-paged"
            with SqliteToolJournal(
                target,
                configuration=deployment_config(
                    "fixture", "scanner", "owner", PAGED_DECLARATIONS
                ),
            ) as store:
                store.save(receipt_stages(receipt())[0])
            with self.assertRaisesRegex(ValueError, "conflicting work"):
                with open_tool_journal(directory, "fixture", "scanner", "owner"):
                    self.fail("unmarked effects admitted")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            configuration = deployment_config(
                "fixture", "scanner", "owner", LEGACY_DECLARATIONS
            )
            with SqliteToolJournal(directory, configuration=configuration):
                pass
            with open_tool_journal(directory, "fixture", "scanner", "owner"):
                pass
            with SqliteToolJournal(directory, configuration=configuration) as legacy:
                legacy.save(receipt_stages(receipt())[0])
            with self.assertRaisesRegex(ValueError, "Foreign tool transition"):
                with open_tool_journal(directory, "fixture", "scanner", "owner"):
                    self.fail("changed legacy history admitted")

    def test_transition_refuses_non_read_additions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            modified = tuple(
                replace(tool, parameters=DECLARATIONS[0].parameters)
                if tool.name == "network_scan"
                else tool
                for tool in DECLARATIONS
            )
            with patch("plowshare_privacy.tool_journal_upgrade.DECLARATIONS", modified):
                with self.assertRaisesRegex(ValueError, "optional read-page"):
                    with open_tool_journal(directory, "fixture", "scanner", "owner"):
                        self.fail("scan argument change admitted")


class DeviceToolTransitionTest(unittest.TestCase):
    def test_paged_binding_is_frozen_and_device_tools_are_the_only_additions(
        self,
    ) -> None:
        configuration = deployment_config(
            "fixture", "scanner", "owner", PAGED_DECLARATIONS
        )
        self.assertEqual(
            hashlib.sha256(configuration.encode()).hexdigest(), PAGED_BINDING
        )
        self.assertEqual(
            {tool.name for tool in DECLARATIONS}
            - {tool.name for tool in PAGED_DECLARATIONS},
            {"network_devices", "network_device"},
        )

    def test_paged_history_moves_to_the_device_journal_without_replay(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            paged = directory.parent / "relay-tools-paged"
            # A deployment that already ran the six-tool paged collector.
            with SqliteToolJournal(
                paged,
                configuration=deployment_config(
                    "fixture", "scanner", "owner", PAGED_DECLARATIONS
                ),
            ) as store:
                rows = []
                for phase in range(4):
                    stages = receipt_stages(receipt())
                    for stage in stages[: phase + 1]:
                        store.save(stage)
                    rows.append(stages[phase])
                retained = store.all()
            (paged / "transition.json").write_text(
                json.dumps(
                    {
                        "version": 1,
                        "source": None,
                        "target": hashlib.sha256(
                            deployment_config(
                                "fixture", "scanner", "owner", PAGED_DECLARATIONS
                            ).encode()
                        ).hexdigest(),
                        "receipts": 0,
                        "digest": hashlib.sha256(b"[]").hexdigest(),
                    }
                )
            )
            with open_tool_journal(directory, "fixture", "scanner", "owner") as current:
                self.assertEqual(current.all(), retained)
                item = receipt()
                for stage in receipt_stages(item):
                    current.save(stage)
            with open_tool_journal(directory, "fixture", "scanner", "owner") as current:
                self.assertEqual(set(current.all()), {*retained, item})
            marker = json.loads(
                (directory.parent / "relay-tools-devices/transition.json").read_text()
            )
            self.assertEqual(marker["receipts"], len(retained))
            # The paged history is retained unchanged for inspection.
            with SqliteToolJournal(
                paged,
                configuration=deployment_config(
                    "fixture", "scanner", "owner", PAGED_DECLARATIONS
                ),
            ) as store:
                self.assertEqual(store.all(), retained)
                store.save(receipt_stages(receipt())[0])
            with self.assertRaisesRegex(ValueError, "Foreign tool transition"):
                with open_tool_journal(directory, "fixture", "scanner", "owner"):
                    self.fail("changed paged history admitted")

    def test_fresh_install_opens_only_the_device_journal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve() / "relay-tools"
            with open_tool_journal(directory, "fixture", "scanner", "owner") as current:
                self.assertEqual(current.all(), ())
            self.assertFalse((directory.parent / "relay-tools-paged").exists())
            self.assertTrue((directory.parent / "relay-tools-devices").is_dir())
