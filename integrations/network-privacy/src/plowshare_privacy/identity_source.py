"""Additional identity observations, independent of DNS and probe scope."""

from dataclasses import dataclass
from typing import Protocol

from .contracts import DeviceIdentity


@dataclass(frozen=True)
class IdentitySample:
    devices: tuple[DeviceIdentity, ...] = ()
    ambiguous_addresses: frozenset[str] = frozenset()
    issues: tuple[str, ...] = ()


class DeviceIdentitySource(Protocol):
    """Return a fresh, bounded sample for an explicitly selected address set."""

    async def identify(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample: ...
