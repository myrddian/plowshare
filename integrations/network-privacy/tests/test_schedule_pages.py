"""Paged inspection stays typed, bounded and read-only on errors."""

from __future__ import annotations

import unittest
from dataclasses import replace
from unittest.mock import AsyncMock, patch

from plowshare import Client, Reply
from plowshare.contracts import ScheduleFileDto, ScheduleFilesRequest

from plowshare_privacy.schedule_pages import schedule_files


class SchedulePagesTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.client = AsyncMock(spec=Client)
        self.row = ScheduleFileDto(
            project="project",
            source="server",
            name="scan",
            internal_name="cursor",
            status="active",
            error=None,
            definition=None,
            path="schedules/scan.json",
        )

    async def test_repeated_cursor_fails_without_replaying_a_read(self) -> None:
        page = tuple(
            replace(self.row, internal_name=f"cursor-{i:03}") for i in range(100)
        )
        self.client.request.side_effect = [
            Reply("OK", None, page),
            Reply("OK", None, (page[-1],)),
        ]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            _ = [row async for row in schedule_files(self.client, "project")]
        self.assertEqual(self.client.request.await_count, 2)
        self.assertTrue(
            all(
                isinstance(call.args[0], ScheduleFilesRequest)
                for call in self.client.request.call_args_list
            )
        )

    async def test_full_last_page_exhausts_budget_instead_of_claiming_completeness(
        self,
    ) -> None:
        page = tuple(
            replace(self.row, internal_name=f"cursor-{i:03}") for i in range(100)
        )
        self.client.request.return_value = Reply("OK", None, page)
        with patch("plowshare_privacy.schedule_pages.MAX_PAGES", 1):
            with self.assertRaisesRegex(ValueError, "page budget"):
                _ = [row async for row in schedule_files(self.client, "project")]
        self.client.request.assert_awaited_once()

    async def test_oversized_page_is_not_silently_truncated(self) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            tuple(
                replace(self.row, internal_name=f"cursor-{i:03}") for i in range(101)
            ),
        )
        with self.assertRaisesRegex(ValueError, "bound"):
            _ = [row async for row in schedule_files(self.client, "project")]
        self.client.request.assert_awaited_once()
