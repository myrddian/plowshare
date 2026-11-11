"""Project observed address history from the collector's durable scan receipts."""

from collections import defaultdict
from dataclasses import dataclass, field

from .contracts import DeviceIdentity, DeviceLabel, DnsObservation, TcpObservation
from .device_detection import DeviceDetection, detect_device
from .dns_activity import DnsActivity
from .journal import Receipt
from .presence import PresenceCount, PresenceView, epoch
from .traffic import TrafficVolume


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
    detection: DeviceDetection
    presence: PresenceCount | None = None
    dns_activity: DnsActivity | None = None
    traffic: TrafficVolume | None = None


@dataclass(frozen=True)
class CandidateAddress:
    address: str
    label: str | None
    last_checked_at: str | None


@dataclass(frozen=True)
class DeviceInventory:
    devices: tuple[DeviceCard, ...] = ()
    candidates: tuple[CandidateAddress, ...] = ()


@dataclass
class _AddressHistory:
    last_checked_at: str | None = None
    revision: str | None = None
    first_response_at: str | None = None
    last_response_at: str | None = None
    identity: DeviceIdentity | None = None
    last_known_identity: DeviceIdentity | None = None
    open_service_observed: bool = False
    services: tuple[TcpObservation, ...] = ()
    dns: tuple[DnsObservation, ...] = ()
    scans: list[str] = field(default_factory=list)


def device_inventory(
    targets: tuple[str, ...],
    labels: tuple[DeviceLabel, ...],
    receipts: tuple[Receipt, ...],
    presence: PresenceView | None = None,
) -> DeviceInventory:
    """Read completed sweeps in journal order. History belongs to an address.

    An open TCP service or an observed identity promotes an address. Refusals can
    come from network equipment, so they update response history without promoting
    a candidate. An earlier association cannot substitute for a current identity
    when suggesting scope changes.
    """
    presence = presence if presence is not None else PresenceView()
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
            statuses = {probe.status for probe in services}
            if "open" in statuses:
                history.open_service_observed = True
            if statuses & {"open", "closed"}:
                if history.first_response_at is None:
                    history.first_response_at = evidence.finished_at
                history.last_response_at = evidence.finished_at
            if index >= recent_start:
                history.scans.append(receipt.scan_id)

    devices = []
    candidates = []
    for address, history in addresses.items():
        full_identity = history.identity
        quick = presence.sightings.get(address)
        quick_identity = quick.identity if quick else None
        if quick_identity is not None:
            sampled = epoch(quick_identity.observed_at)
            if history.last_known_identity is None or sampled > epoch(
                history.last_known_identity.observed_at
            ):
                history.last_known_identity = quick_identity
            if history.last_checked_at is None or sampled > epoch(
                history.last_checked_at
            ):
                history.identity = quick_identity
        if (
            not quick
            and not history.open_service_observed
            and history.last_known_identity is None
        ):
            checked = max(
                (
                    value
                    for value in (history.last_checked_at, presence.checked_at)
                    if value
                ),
                key=epoch,
                default=None,
            )
            candidates.append(CandidateAddress(address, names.get(address), checked))
            continue
        known = history.last_known_identity
        key = known.device_id if known and known.mac else "ip:" + address
        count = presence.counts.get(key)
        if count is None and presence.checked_at:
            count = PresenceCount(
                0, presence.checks, None, key.startswith("ip:"), presence.checked_at
            )
        devices.append(
            DeviceCard(
                address=address,
                name=names.get(address)
                or (history.identity and history.identity.hostname)
                or "Unnamed device",
                identity=history.identity,
                last_seen=history.last_checked_at,
                services=history.services,
                dns=history.dns
                if history.identity == full_identity
                or (
                    full_identity
                    and full_identity.mac
                    and known
                    and full_identity.mac == known.mac
                )
                else (),
                history=tuple(history.scans),
                revision=history.revision,
                first_response_at=history.first_response_at,
                last_response_at=history.last_response_at,
                last_known_identity=history.last_known_identity,
                detection=detect_device(history.identity, history.services),
                presence=count,
                dns_activity=presence.dns.get(quick.key)
                if quick and quick.identity and history.identity == quick.identity
                else None,
                # Same attribution rule as DNS: only the confirmed current MAC.
                traffic=presence.traffic.get(quick.key)
                if quick and quick.identity and history.identity == quick.identity
                else None,
            )
        )
    return DeviceInventory(tuple(devices), tuple(candidates))
