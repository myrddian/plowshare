"""Reconnect sessions, never operations; receipt settlement owns permission to resume."""

from __future__ import annotations

import asyncio
from typing import Awaitable, Callable

from plowshare import Delivery, TransportError

INITIAL_RECONNECT_SECONDS = 2.0
MAX_RECONNECT_SECONDS = 60.0


async def wait_for_recovery(
    stop: asyncio.Event,
    resumed: asyncio.Event,
    recover: Callable[[], Awaitable[None]] | None,
) -> None:
    """Wait for manual recovery or retry a session/receipt-only recovery callback.

    Only transport failures are retried, with exponential backoff capped at one
    minute. A refusal, invalid response or unresolved receipt escapes to the
    worker's attention state. Shutdown and manual recovery interrupt both the
    backoff and an in-flight attempt; cancellation closes candidate sessions in
    the composition callback. No intake, handler or mutation is replayed here.
    """
    delay = INITIAL_RECONNECT_SECONDS
    stopped = asyncio.create_task(stop.wait())
    manual = asyncio.create_task(resumed.wait())
    try:
        while not stop.is_set() and not resumed.is_set():
            interrupted, _ = await asyncio.wait(
                (stopped, manual),
                timeout=delay if recover is not None else None,
                return_when=asyncio.FIRST_COMPLETED,
            )
            if interrupted:
                return
            assert recover is not None
            attempt = asyncio.ensure_future(recover())
            try:
                finished, _ = await asyncio.wait(
                    (stopped, manual, attempt),
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if attempt not in finished:
                    return
                try:
                    await attempt
                except TransportError as error:
                    if error.delivery == Delivery.INVALID_RESPONSE:
                        raise
                    delay = min(delay * 2, MAX_RECONNECT_SECONDS)
                else:
                    resumed.set()
            finally:
                if not attempt.done():
                    attempt.cancel()
                await asyncio.gather(attempt, return_exceptions=True)
    finally:
        stopped.cancel()
        manual.cancel()
        await asyncio.gather(stopped, manual, return_exceptions=True)
