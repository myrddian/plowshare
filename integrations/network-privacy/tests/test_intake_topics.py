"""Discover typed schedule streams without depending on private topic names."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

from plowshare import Client, Reply
from plowshare.contracts import (
    RelayTopicDto,
    RelayTopicsResultDto,
    RelayTopicsResultDtoScopeDto,
)
from support import configuration

from plowshare_privacy.ports import AvailableTopics, SdkPrivacyPort


def topic(name: str, *, scheduled: bool = False) -> RelayTopicDto:
    return RelayTopicDto(
        name=name,
        kind="SCHEDULE_DUE" if scheduled else "TEXT",
        generation=str(uuid4()),
        through="0",
        expired_through="0",
        retention_seconds="345600",
        max_records=None,
    )


class IntakeTopicsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.config = configuration(Path(folder.name))
        self.client = AsyncMock(spec=Client)
        self.port = SdkPrivacyPort(self.client, self.config)

    def response(self, *rows: RelayTopicDto, project: str | None = None) -> None:
        self.client.request.return_value = Reply(
            "OK",
            None,
            RelayTopicsResultDto(
                scope=RelayTopicsResultDtoScopeDto(
                    project=project or self.config.project, system=False
                ),
                topics=rows,
            ),
        )

    async def test_kind_selects_schedule_and_names_never_imply_kind(self) -> None:
        self.response(
            topic("opaque-stream", scheduled=True),
            topic("schedule.due"),
            topic(self.config.request_topic),
        )
        self.assertEqual(
            await self.port.available_topics(),
            AvailableTopics(
                frozenset({"opaque-stream", "schedule.due", self.config.request_topic}),
                frozenset({"opaque-stream"}),
            ),
        )
        self.client.request.assert_awaited_once()

    async def test_foreign_scope_is_not_consumed(self) -> None:
        self.response(topic("opaque-stream", scheduled=True), project="other")
        with self.assertRaisesRegex(ValueError, "Foreign Relay"):
            await self.port.available_topics()

    async def test_full_listing_does_not_guess_which_topics_are_absent(self) -> None:
        self.response(*(topic(f"stream-{index}") for index in range(100)))
        with self.assertRaisesRegex(ValueError, "listing is full"):
            await self.port.available_topics()

    async def test_duplicate_topics_are_invalid(self) -> None:
        self.response(topic("stream"), topic("stream", scheduled=True))
        with self.assertRaisesRegex(ValueError, "Duplicate Relay"):
            await self.port.available_topics()

    async def test_schedule_kind_on_request_topic_is_refused(self) -> None:
        self.response(topic(self.config.request_topic, scheduled=True))
        with self.assertRaisesRegex(ValueError, "cannot carry schedules"):
            await self.port.available_topics()
