"""Operational schedule control through the configured Application service session.

An intent is durable before sending once. Unknown delivery fences further controls;
a fresh, project-scoped read may confirm the requested state without resending it.
The service account never gains definition-management authority or human credentials.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol

from plowshare import Client, Refusal, TransportError
from plowshare.contracts import SchedulePauseRequest

from .console_port import ScheduleView, SdkConsolePort
from .contracts import Configuration, load_json, object_fields, text
from .monitor import SettingsBusy
from .private_files import replace_private


@dataclass(frozen=True)
class ScheduleControlStatus:
    available: bool
    pending: bool
    requested_paused: bool | None


class ScheduleRuntime(Protocol):
    async def schedule(self) -> ScheduleView | None: ...
    async def set_paused(self, paused: bool) -> None: ...


class SdkScheduleRuntime:
    def __init__(self, client: Client, config: Configuration):
        self.client, self.config = client, config

    async def schedule(self) -> ScheduleView | None:
        return await SdkConsolePort(self.client, self.config).schedule()

    async def set_paused(self, paused: bool) -> None:
        (
            await self.client.request(
                SchedulePauseRequest(schedule=self.config.schedule, paused=paused)
            )
        ).require_payload()


class ScheduleController:
    def __init__(
        self,
        root: Path,
        lock: asyncio.Lock,
        runtime: Callable[[Configuration], ScheduleRuntime] | None,
    ):
        self.config = root / "collector.json"
        self.lock, self.runtime = lock, runtime
        self.path = root / "deployment/schedule-control.json"
        self.deployment = root / "deployment/schedule-update-intent.json"

    def status(self) -> ScheduleControlStatus:
        if not self.path.exists():
            return ScheduleControlStatus(self.runtime is not None, False, None)
        config = Configuration.read(self.config)
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Schedule control state cannot be linked")
        row = object_fields(
            load_json(self.path), {"project", "schedule", "paused", "state"}
        )
        if (
            text(row["project"], 128) != config.project
            or text(row["schedule"], 128) != config.schedule
            or type(row["paused"]) is not bool
            or row["state"] not in ("pending", "confirmed", "refused")
        ):
            raise ValueError("Schedule control state differs from this setup")
        return ScheduleControlStatus(
            self.runtime is not None, row["state"] == "pending", row["paused"]
        )

    def retain(self, config: Configuration, paused: bool, state: str) -> None:
        replace_private(
            self.path,
            json.dumps(
                {
                    "project": config.project,
                    "schedule": config.schedule,
                    "paused": paused,
                    "state": state,
                }
            )
            + "\n",
        )

    def port(self, config: Configuration) -> ScheduleRuntime:
        if self.runtime is None:
            raise SettingsBusy("Application schedule controls are not configured.")
        return self.runtime(config)

    async def change(self, paused: bool) -> ScheduleView:
        if type(paused) is not bool:
            raise ValueError("paused must be a boolean")
        async with self.lock:
            if self.status().pending or self.deployment.exists():
                raise SettingsBusy(
                    "Check the pending schedule change before another control or deployment."
                )
            config = Configuration.read(self.config)
            port = self.port(config)
            current = await port.schedule()
            if current is None:
                raise SettingsBusy(
                    "The project schedule is not currently available. Nothing was changed."
                )
            if current.paused == paused:
                return current
            self.retain(config, paused, "pending")
            try:
                # The existing service session carries the Application identity and token ceiling.
                # The server rechecks its live manifest grant and project access on this call.
                await port.set_paused(paused)
            except Refusal:
                self.retain(config, paused, "refused")
                raise
            except TransportError:
                raise SettingsBusy(
                    "Schedule change was not confirmed. Choose Check schedule state; the request will not be resent."
                ) from None
            observed = await port.schedule()
            if observed is None or observed.paused != paused:
                raise SettingsBusy(
                    "Schedule change needs confirmation. Choose Check schedule state."
                )
            self.retain(config, paused, "confirmed")
            return observed

    async def check(self) -> ScheduleView:
        async with self.lock:
            status = self.status()
            config = Configuration.read(self.config)
            observed = await self.port(config).schedule()
            if observed is None:
                raise SettingsBusy(
                    "The project schedule is not currently available; no control was sent."
                )
            if status.pending and observed.paused == status.requested_paused:
                self.retain(config, observed.paused, "confirmed")
            return observed
