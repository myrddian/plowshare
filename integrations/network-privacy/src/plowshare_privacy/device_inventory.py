"""Project observed address history from the collector's durable scan receipts."""

from collections import defaultdict
from dataclasses import dataclass, field

from .contracts import DeviceIdentity, DeviceLabel, DnsObservation, TcpObservation
from .journal import Receipt


@dataclass(frozen=True)
class DeviceCard:
    address: str
    name: str
    identity: DeviceIdentity | None
    # Existing dashboard field for the latest check, including unanswered probes.
    last_seen: str | None
    services: tuple[TcpObservation, ...]
    dns: tuple[DnsObservation, ...]
    history: tuple[str, ...]
    revision: str | None
    first_response_at: str | None
    last_response_at: str | None
    last_known_identity: DeviceIdentity | None


@dataclass
class _AddressHistory:
    last_checked_at: str | None = None
    revision: str | None = None
    first_response_at: str | None = None
    last_response_at: str | None = None
    identity: DeviceIdentity | None = None
    last_known_identity: DeviceIdentity | None = None
    services: tuple[TcpObservation, ...] = ()
    dns: tuple[DnsObservation, ...] = ()
    scans: list[str] = field(default_factory=list)


def device_inventory(
    targets: tuple[str, ...],
    labels: tuple[DeviceLabel, ...],
    receipts: tuple[Receipt, ...],
) -> tuple[DeviceCard, ...]:
    """Read completed sweeps in journal order. History belongs to an address.

    An open or refused TCP connection confirms a response. Unanswered probes update
    the last check but never extend presence. An earlier association is historical
    and must never substitute for current identity when suggesting scope changes.
    """
    names = {item.address: item.label for item in labels}
    addresses = {address: _AddressHistory() for address in targets}
    recent_start = max(0, len(receipts) - 50)
    for index, receipt in enumerate(receipts):
        evidence = receipt.evidence
        if receipt.phase != "done" or evidence is None:
            continue
        snapshot = evidence.snapshot
        probes: dict[str, list[TcpObservation]] = defaultdict(list)
        for probe in snapshot.tcp:
            if probe.address in addresses:
                probes[probe.address].append(probe)
        if not probes:
            continue
        identities = {item.address: item for item in snapshot.devices}
        destinations: dict[str, list[DnsObservation]] = defaultdict(list)
        for query in snapshot.dns:
            if query.device in probes:
                destinations[query.device].append(query)
        for address, services in probes.items():
            history = addresses[address]
            history.last_checked_at = evidence.finished_at
            history.revision = receipt.revision
            history.services = tuple(services)
            history.dns = tuple(destinations.get(address, ()))
            history.identity = identities.get(address)
            if history.identity and history.identity.source != "configured":
                history.last_known_identity = history.identity
            if any(probe.status in {"open", "closed"} for probe in services):
                if history.first_response_at is None:
                    history.first_response_at = evidence.finished_at
                history.last_response_at = evidence.finished_at
            if index >= recent_start:
                history.scans.append(receipt.scan_id)

    return tuple(
        DeviceCard(
            address=address,
            name=names.get(address)
            or (history.identity and history.identity.hostname)
            or "Unnamed device",
            identity=history.identity,
            last_seen=history.last_checked_at,
            services=history.services,
            dns=history.dns,
            history=tuple(history.scans),
            revision=history.revision,
            first_response_at=history.first_response_at,
            last_response_at=history.last_response_at,
            last_known_identity=history.last_known_identity,
        )
        for address, history in addresses.items()
    )
