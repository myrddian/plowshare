"""Per-device traffic volume from cumulative UniFi client byte counters.

UniFi reports, for each connected client, cumulative byte counters that reset
when the client reconnects or the controller restarts. Quick presence samples
them; this module turns consecutive samples into volumes for one device.

Direction follows UniFi's own convention: ``tx`` is what the network sent to
the client and ``rx`` is what it received from the client. The dashboard labels
them "to device" and "from device". A volume is a lower bound: intervals that
straddle a reset are dropped rather than guessed, and the counters say nothing
about which remote hosts the bytes went to.
"""

from dataclasses import dataclass
from typing import Iterable

# Counters are unsigned 64-bit in UniFi; anything outside is malformed input.
MAX_COUNTER = 2**64 - 1


@dataclass(frozen=True)
class TrafficCounter:
    """One device's cumulative counters at one quick check."""

    device: str
    address: str
    to_device: int
    from_device: int


@dataclass(frozen=True)
class TrafficVolume:
    from_at: str
    until_at: str
    samples: int
    to_device_bytes: int
    from_device_bytes: int
    # Intervals dropped because a counter went backwards (reconnect/restart).
    resets: int


def accumulate(
    series: Iterable[tuple[float, int, int]],
) -> tuple[int, int, int, int]:
    """Sum non-negative deltas across ordered (checked, to, from) samples.

    Returns (samples, to_device, from_device, resets). If either counter
    decreases, the whole interval is treated as a reset and contributes
    nothing, because the bytes sent before the reset are unknown.
    """
    samples = 0
    to_total = from_total = resets = 0
    previous: tuple[int, int] | None = None
    for _checked, to_device, from_device in series:
        samples += 1
        if previous is not None:
            to_delta = to_device - previous[0]
            from_delta = from_device - previous[1]
            if to_delta < 0 or from_delta < 0:
                resets += 1
            else:
                to_total += to_delta
                from_total += from_delta
        previous = (to_device, from_device)
    return samples, to_total, from_total, resets
