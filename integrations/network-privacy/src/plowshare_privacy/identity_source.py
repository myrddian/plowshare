"""Additional identity observations, independent of DNS and probe scope."""

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

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


@dataclass(frozen=True)
class ManagedProduct:
    manufacturer: str
    model: str
    firmware: str | None
    firmware_updatable: bool | None


class ManagedProductUnavailable(ValueError):
    """The local controller could not confirm the product details."""


@runtime_checkable
class ManagedProductLookup(Protocol):
    """Read product details from a controller for an already observed identity."""

    async def managed_product(
        self, identity: DeviceIdentity
    ) -> ManagedProduct | None: ...
