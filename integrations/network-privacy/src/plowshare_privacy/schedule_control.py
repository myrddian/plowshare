"""Operational schedule control through a temporary registered-owner session.

An intent is durable before sending once. Unknown delivery fences further controls;
a fresh, project-scoped read may confirm the requested state without resending it.
The service account never gains schedule-management authority or human credentials.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path

from plowshare import Client, Refusal, TransportError
from plowshare.contracts import SchedulePauseRequest

from .console_port import ScheduleView, SdkConsolePort
from .contracts import Configuration, load_json, object_fields, text
from .monitor import SettingsBusy
from .private_files import replace_private
from .setup import Setup, administrator_login


@dataclass(frozen=True)
class ScheduleControlStatus:
    account: str
    pending: bool
    requested_paused: bool | None


class ScheduleController:
    def __init__(self, root: Path, setup: Setup, lock: asyncio.Lock):
        self.setup, self.lock = setup, lock
        self.path = root / "deployment/schedule-control.json"
        self.deployment = root / "deployment/schedule-update-intent.json"

    def status(self) -> ScheduleControlStatus:
        if not self.path.exists():
            return ScheduleControlStatus(self.setup.deployer, False, None)
        config = Configuration.read(self.setup.collector)
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
            self.setup.deployer, row["state"] == "pending", row["paused"]
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

    async def change(self, paused: bool, password: str) -> ScheduleView:
        if type(paused) is not bool:
            raise ValueError("paused must be a boolean")
        async with self.lock:
            if self.status().pending or self.deployment.exists():
                raise SettingsBusy(
                    "Check the pending schedule change before another control or deployment."
                )
            config = Configuration.read(self.setup.collector)
            # The source is registered under the deployment account. The server
            # checks ownership, current project management and action grants.
            async with administrator_login(
                config.origin, self.setup.deployer, password
            ) as bearer:
                async with await Client.connect(
                    config.origin, bearer, timeout=15
                ) as client:
                    port = SdkConsolePort(client, config)
                    current = await port.schedule()
                    if current is None:
                        raise SettingsBusy(
                            "The project schedule is not currently available. Nothing was changed."
                        )
                    if current.owner != self.setup.deployer:
                        raise SettingsBusy(
                            "This schedule belongs to another account. The current public control requires its registered owner; no control was sent."
                        )
                    if current.paused == paused:
                        return current
                    self.retain(config, paused, "pending")
                    try:
                        (
                            await client.request(
                                SchedulePauseRequest(
                                    schedule=config.schedule, paused=paused
                                )
                            )
                        ).require_payload()
                    except Refusal:
                        self.retain(config, paused, "refused")
                        raise
                    except TransportError:
                        raise SettingsBusy(
                            "Schedule change was not confirmed. Choose Check schedule state; the request will not be resent."
                        ) from None
                    # Acknowledgement alone must not be presented as fresh runtime
                    # state. Failed reads retain the intent across process restarts.
                    observed = await port.schedule()
                    if observed is None or observed.paused != paused:
                        raise SettingsBusy(
                            "Schedule change needs confirmation. Choose Check schedule state."
                        )
                    self.retain(config, paused, "confirmed")
                    return observed

    async def check(self, password: str) -> ScheduleView:
        async with self.lock:
            status = self.status()
            config = Configuration.read(self.setup.collector)
            async with administrator_login(
                config.origin, self.setup.deployer, password
            ) as bearer:
                async with await Client.connect(
                    config.origin, bearer, timeout=15
                ) as client:
                    observed = await SdkConsolePort(client, config).schedule()
            if observed is None:
                raise SettingsBusy(
                    "The project schedule is not currently available; no control was sent."
                )
            if status.pending and observed.paused == status.requested_paused:
                self.retain(config, observed.paused, "confirmed")
            return observed
