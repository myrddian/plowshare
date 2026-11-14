"""Typed readers bound from the configured services' enabled capabilities.

A `SourceSet` is what the connector registry (`connectors.bind`) hands the
collector: each field holds only readers whose capability the operator enabled,
so a disabled capability has no reader and is never read. Readers return frozen
dataclasses; raw JSON never crosses this boundary.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, Protocol, TypeVar

from .addressing import AddressingSample
from .contracts import ConnectorKind
from .dns_activity import QuerySample
from .identity_source import (
    DeviceIdentitySource,
    IdentitySample,
    ManagedInventory,
    ManagedProductLookup,
)

# The device-read contracts live with their first implementation; they are
# re-exported here so the vendor-neutral collector imports no connector module.
from .pihole import (
    CollectionData,
    CollectionDeviceSource,
    DeviceData,
    DeviceDataSource,
)
from .traffic import TrafficCounter


class PresenceReader(Protocol):
    """Fresh address associations for a watched scope, without DNS history."""

    async def presence(
        self, targets: tuple[str, ...], observed_at: str
    ) -> IdentitySample: ...


class DnsActivityReader(Protocol):
    """Query samples in the half-open window ``[since, observed_at)``."""

    async def activity(
        self, targets: tuple[str, ...], since: str, observed_at: str
    ) -> tuple[tuple[QuerySample, ...], tuple[str, ...]]: ...


class TrafficReader(Protocol):
    """Cumulative byte counters for clients at ``targets``; failures are issues."""

    async def traffic(
        self, targets: tuple[str, ...]
    ) -> tuple[tuple[TrafficCounter, ...], tuple[str, ...]]: ...


class AddressingReader(Protocol):
    """Addressing facts (leases, reservations, scopes) for ``targets``; gaps are issues."""

    async def addressing(
        self, targets: tuple[str, ...], observed_at: str
    ) -> AddressingSample: ...


__all__ = [
    "AddressingReader",
    "Bound",
    "CollectionData",
    "CollectionDeviceSource",
    "DeviceData",
    "DeviceDataSource",
    "DnsActivityReader",
    "PresenceReader",
    "SourceSet",
    "TrafficReader",
]

R = TypeVar("R", covariant=True)


@dataclass(frozen=True)
class Bound(Generic[R]):
    """A reader together with the configured service it reads."""

    service: str
    kind: ConnectorKind
    reader: R


@dataclass(frozen=True)
class SourceSet:
    # One session per full collection, returning identity and/or DNS.
    device_reads: tuple[Bound[DeviceDataSource], ...] = ()
    # Already merged per service (e.g. UniFi clients with adopted inventory).
    identity: tuple[Bound[DeviceIdentitySource], ...] = ()
    presence: tuple[Bound[PresenceReader], ...] = ()
    dns_activity: Bound[DnsActivityReader] | None = None
    traffic: Bound[TrafficReader] | None = None
    product: ManagedProductLookup | None = None
    # Adopted inventories, consulted by ``recently_managed``.
    managed: tuple[ManagedInventory, ...] = ()
    # Addressing facts read outside a device-read session.
    addressing: tuple[Bound[AddressingReader], ...] = ()

    @property
    def confirms_identity(self) -> bool:
        """Whether any enabled capability can tie an address to a MAC.

        Identity bindings do. A presence binding exists only where a network
        table is read, and that same service's ``device_reads`` returns the
        table's associations; a DNS-only device read cannot confirm identity.
        """
        return bool(self.identity or self.presence)
