"""Bounded physical owners for read-only configuration probes.

Config flows are short-lived. A false/failed SDK close cannot disappear with the
flow object: the per-HA pool retains its owner and admission slot until a later
explicit attempt proves closure. Nothing here submits agent or conversation work.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol

from homeassistant.core import HomeAssistant
from homeassistant.util.hass_dict import HassKey

from .const import CLEANUP_TIMEOUT, MAX_VALIDATION_PROBES
from .models import Session, Settings

_LOGGER = logging.getLogger(__name__)


class ValidationProbe(Protocol):
    async def validate(self, required_agents: tuple[str, ...] = ()) -> None: ...

    async def async_close(self) -> bool: ...


ProbeFactory = Callable[
    [Settings, dict[str, Session], Callable[[], Awaitable[None]]], ValidationProbe
]


class ValidationUnavailableError(RuntimeError):
    """Probe cleanup did not establish a usable configuration connection."""


class ValidationBusyError(ValidationUnavailableError):
    """All bounded configuration-probe owners are still occupied."""


@dataclass
class _Owner:
    probe: ValidationProbe
    close_task: asyncio.Task[bool] | None = None
    validation_finished: bool = False


async def _reject_save() -> None:
    # Read-only validation cannot acquire a submission lifecycle by accident.
    raise RuntimeError("Configuration validation cannot persist submission intent")


class ValidationProbePool:
    def __init__(self) -> None:
        self._owners: dict[int, _Owner] = {}
        self._next_owner = 0

    def _closed(self, identity: int, task: asyncio.Task[bool]) -> None:
        # Always observe settlement before testing identity. A later explicit
        # retry may already own a newer close task when this callback runs.
        settled = False
        if not task.cancelled():
            try:
                settled = task.result()
            except Exception:
                _LOGGER.warning(
                    "Plowshare configuration cleanup failed; owner retained"
                )
        record = self._owners.get(identity)
        if record is not None and record.close_task is task and settled is True:
            del self._owners[identity]

    def _close_attempt(self, identity: int, record: _Owner) -> asyncio.Task[bool]:
        if record.close_task is not None and not record.close_task.done():
            return record.close_task

        async def close() -> bool:
            try:
                async with asyncio.timeout(CLEANUP_TIMEOUT):
                    return await record.probe.async_close()
            except Exception:
                # Python 3.14 shield logs a failed inner future after the caller
                # stops waiting. Represent cleanup failure as an explicit false
                # settlement, retain the owner, and log only this fixed diagnostic.
                _LOGGER.warning(
                    "Plowshare configuration cleanup failed; owner retained"
                )
                return False

        record.close_task = asyncio.create_task(close())
        record.close_task.add_done_callback(lambda task: self._closed(identity, task))
        return record.close_task

    async def _reap(self) -> None:
        # One bounded retry per retained owner on an explicit new flow attempt.
        # No timer loop or unbounded background retry is created.
        attempts = [
            (identity, self._close_attempt(identity, owner))
            for identity, owner in tuple(self._owners.items())
            if owner.validation_finished
        ]
        if attempts:
            await asyncio.sleep(0)
        for identity, task in attempts:
            if task.done():
                self._closed(identity, task)

    async def _finish(self, identity: int, record: _Owner) -> bool:
        task = self._close_attempt(identity, record)
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                settled = await asyncio.shield(task)
        except TimeoutError, OSError:
            return False
        except asyncio.CancelledError:
            # The task and owner stay in the pool. Caller cancellation does not
            # prove that the physical connection was closed.
            raise
        except Exception:
            return False
        self._closed(identity, task)
        return settled is True

    async def async_validate(
        self,
        settings: Settings,
        required_agents: tuple[str, ...],
        factory: ProbeFactory,
    ) -> None:
        await self._reap()
        if len(self._owners) >= MAX_VALIDATION_PROBES:
            raise ValidationBusyError("Configuration probes are still settling")
        # Event-loop confined claim, with no await before retaining the owner.
        identity = self._next_owner
        self._next_owner += 1
        record = _Owner(factory(settings, {}, _reject_save))
        self._owners[identity] = record
        try:
            await record.probe.validate(required_agents=required_agents)
        except BaseException:
            record.validation_finished = True
            await self._finish(identity, record)
            raise
        record.validation_finished = True
        if not await self._finish(identity, record):
            raise ValidationUnavailableError("Configuration cleanup did not settle")


_POOL: HassKey[ValidationProbePool] = HassKey("plowshare_configuration_probes")


def probe_pool(hass: HomeAssistant) -> ValidationProbePool:
    pool = hass.data.get(_POOL)
    if pool is None:
        pool = ValidationProbePool()
        hass.data[_POOL] = pool
    return pool
