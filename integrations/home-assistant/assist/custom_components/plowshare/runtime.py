"""HA entry lifecycle composition; Plowshare remains the durable agent runtime."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Protocol

from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.setup import SLOW_SETUP_MAX_WAIT
from homeassistant.util.hass_dict import HassKey

from .agent import GatewayPort
from .const import CLEANUP_TIMEOUT
from .storage import CorrelationPersistence

_LOGGER = logging.getLogger(__name__)


class PlatformSetupIncomplete(RuntimeError):
    """A platform owner has not supplied a complete setup acknowledgment."""


class PlatformLifecycle(Protocol):
    """Supported HA per-platform operations; cleanup is not a device effect."""

    async def async_forward(self, platform: Platform) -> None: ...

    async def async_unload(self, platform: Platform) -> bool: ...


class EntryRuntime:
    """Retain gateways and their shared writer until every physical owner settles."""

    def __init__(
        self,
        journal: CorrelationPersistence,
        platform_lifecycle: PlatformLifecycle | None = None,
    ) -> None:
        self.journal = journal
        self._conversation: GatewayPort | None = None
        self._task: GatewayPort | None = None
        self._attached = False
        self._closing = False
        self._setup_finished = asyncio.Event()
        self._shutdown: asyncio.Task[bool] | None = None
        self._platform_lifecycle = platform_lifecycle
        self._forwards: dict[Platform, asyncio.Task[None]] = {}
        self._unloads: dict[Platform, asyncio.Task[bool]] = {}
        self._remaining_platforms: set[Platform] = set()

    @property
    def conversation(self) -> GatewayPort | None:
        return None if self._closing else self._conversation

    @property
    def task(self) -> GatewayPort | None:
        return None if self._closing else self._task

    @property
    def closing(self) -> bool:
        return self._closing

    @property
    def platforms(self) -> tuple[Platform, ...]:
        """Originally attached platforms remain known after intake is fenced."""
        return tuple(
            platform
            for platform, port in (
                (Platform.CONVERSATION, self._conversation),
                (Platform.AI_TASK, self._task),
            )
            if port is not None
        )

    def finish_setup(self) -> None:
        """Release composition; separately owned forwards still need completion proof."""
        self._setup_finished.set()

    def begin_close(self, hass: HomeAssistant) -> None:
        """Synchronously fence entity lookup before scheduling physical cleanup."""
        self._closing = True
        if self._shutdown is None or self._shutdown.done():
            self._shutdown = hass.async_create_task(
                self.async_close(), "Plowshare runtime cleanup"
            )

    def attach(
        self, conversation: GatewayPort | None, task: GatewayPort | None
    ) -> None:
        """Composition happens once, before HA receives any platform entities."""
        if self._attached or self._closing or (conversation is None and task is None):
            raise RuntimeError("HA runtime profiles cannot be replaced")
        self._conversation, self._task = conversation, task
        self._attached = True

    @staticmethod
    def _observe(future: asyncio.Future[object]) -> None:
        if not future.cancelled():
            future.exception()

    async def async_forward(self) -> None:
        """Own each forward until completion, independent of its caller wait.

        Singleton public calls isolate HA's nested gather: a failed sibling may
        not make a still-running platform disappear behind an aggregate error.
        At most the two configured platforms are started. asyncio.wait observes
        their tasks without cancelling them on caller cancellation or expiry.
        """
        if self._closing or self._forwards or self._platform_lifecycle is None:
            raise PlatformSetupIncomplete("HA platform forwarding is not available")
        for platform in self.platforms:
            self._remaining_platforms.add(platform)
            task = asyncio.create_task(self._platform_lifecycle.async_forward(platform))
            task.add_done_callback(self._observe)
            self._forwards[platform] = task
        _, pending = await asyncio.wait(
            tuple(self._forwards.values()), timeout=SLOW_SETUP_MAX_WAIT
        )
        if pending or any(
            task.cancelled() or task.exception() is not None
            for task in self._forwards.values()
        ):
            raise PlatformSetupIncomplete("HA platform forwarding needs inspection")

    async def _close_platforms(self) -> bool:
        if self._platform_lifecycle is None:
            return not self._remaining_platforms
        complete = True
        for platform in tuple(self._remaining_platforms):
            if platform not in self._remaining_platforms:
                continue
            task = self._unloads.get(platform)
            if task is None:
                task = asyncio.create_task(
                    self._platform_lifecycle.async_unload(platform)
                )
                task.add_done_callback(self._observe)
                self._unloads[platform] = task
            _, pending = await asyncio.wait((task,), timeout=CLEANUP_TIMEOUT)
            if pending or task.cancelled():
                complete = False
                continue
            failure = task.exception()
            if failure is None and task.result() is True:
                self._remaining_platforms.discard(platform)
            else:
                complete = False
                _LOGGER.error(
                    "HA platform cleanup did not succeed (%s)", platform.value
                )
            # A settled failure can be retried at the next explicit cleanup
            # attempt. A successful sibling is removed and is never replayed.
            if self._unloads.get(platform) is task:
                del self._unloads[platform]
        return complete and not self._remaining_platforms

    async def async_close(self) -> bool:
        self._closing = True
        try:
            async with asyncio.timeout(CLEANUP_TIMEOUT):
                await self._setup_finished.wait()
        except TimeoutError:
            return False
        if self._forwards:
            _, pending = await asyncio.wait(
                tuple(self._forwards.values()), timeout=CLEANUP_TIMEOUT
            )
            if pending or any(task.cancelled() for task in self._forwards.values()):
                return False
        ports = tuple(p for p in (self._conversation, self._task) if p is not None)
        results = await asyncio.gather(
            *(port.async_close() for port in ports), return_exceptions=True
        )
        if not all(result is True for result in results):
            return False
        if not await self._close_platforms():
            return False
        # Accepted gateway calls may still commit receipts during gateway drain.
        # Only after they settle is it safe to stop admission to the shared file.
        return await self.journal.async_close()


@dataclass
class RuntimeOwners:
    """Keep an unsettled setup/unload owner even if HA clears runtime_data."""

    entries: dict[str, EntryRuntime] = field(default_factory=dict)

    def release(self, entry_id: str, runtime: EntryRuntime) -> None:
        if self.entries.get(entry_id) is runtime:
            del self.entries[entry_id]


OWNERS: HassKey[RuntimeOwners] = HassKey("plowshare.runtime_owners")


def owners(hass: HomeAssistant) -> RuntimeOwners:
    if OWNERS not in hass.data:
        hass.data[OWNERS] = RuntimeOwners()
    return hass.data[OWNERS]
