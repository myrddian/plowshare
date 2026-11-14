"""Project observed address history from the collector's durable scan receipts."""

from collections import defaultdict
from dataclasses import dataclass, field

from .addressing import addressing_comparable, assignment_changed
from .contracts import (
    AddressAssignment,
    AssignmentBasis,
    AssignmentMode,
    CollectionPlan,
    DeviceIdentity,
    DeviceLabel,
    DnsObservation,
    Evidence,
    TcpObservation,
)
from .device_detection import DeviceDetection, detect_device
from .dns_activity import DnsActivity
from .journal import Receipt
from .presence import PresenceCount, PresenceView, epoch
from .traffic import TrafficVolume


@dataclass(frozen=True)
class AddressingView:
    """The address assignment the newest full collection recorded for a card.

    ``observed_at`` is that collection's ``finished_at``. The change fields name
    the latest mode change at this address for the same MAC, decided by the
    ``collection.changes`` rule over consecutive collections that list the
    address; ``changed_in_latest`` says the newest collection made it. This is
    configuration context, never evidence of compromise.
    """

    mode: AssignmentMode
    basis: AssignmentBasis | None
    services: tuple[str, ...]
    expires_at: str | None
    observed_at: str
    previous_mode: AssignmentMode | None = None
    changed_at: str | None = None
    changed_in_latest: bool = False


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
    addressing: AddressingView | None = None


@dataclass(frozen=True)
class CandidateAddress:
    address: str
    label: str | None
    last_checked_at: str | None


@dataclass(frozen=True)
class DeviceInventory:
    devices: tuple[DeviceCard, ...] = ()
    candidates: tuple[CandidateAddress, ...] = ()


@dataclass(frozen=True)
class _ModeChange:
    """One qualifying mode change: ``previous`` -> ``mode`` for ``mac``."""

    previous: AssignmentMode
    mode: AssignmentMode
    changed_at: str
    mac: str | None


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
    # The assignment the newest done collection recorded (``None`` when it
    # recorded none), with what the next collection needs to compare against it.
    assignment: AddressAssignment | None = None
    assignment_at: str | None = None
    assignment_scope: str | None = None
    assignment_comparable: bool = False
    # The latest qualifying change, kept only while it still describes the
    # current assignment (same MAC, same mode).
    mode_change: _ModeChange | None = None
    changed_in_latest: bool = False


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
        # Addressing follows every done collection, whatever it probed, so the
        # card shows the newest collection's assignment or nothing.
        assignments = {item.address: item for item in snapshot.addressing}
        comparable = addressing_comparable(evidence.issues)
        for address, history in addresses.items():
            _fold_assignment(history, assignments.get(address), evidence, comparable)
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
                addressing=_addressing_view(history, history.identity),
            )
        )
    return DeviceInventory(tuple(devices), tuple(candidates))


def _fold_assignment(
    history: _AddressHistory,
    current: AddressAssignment | None,
    evidence: Evidence,
    comparable: bool,
) -> None:
    """Advance one address's assignment by one done collection.

    A change counts only under the ``collection.changes`` rule: the previous
    done collection has the same scope, neither collection has an
    ``addressing_`` issue, and ``assignment_changed`` holds for the two records.
    A stored change is dropped as soon as any later collection records a
    different mode (including ``unknown``) or a different MAC, comparable or
    not, so it can never be paired with a mode it did not change to. A
    collection with no assignment for the address also drops it: the history
    is broken there, so a later collection in the same mode cannot revive it.
    """
    prior = history.assignment
    history.changed_in_latest = False
    if current is None:
        history.mode_change = None
    elif (
        prior is not None
        and history.assignment_scope == evidence.scope
        and history.assignment_comparable
        and comparable
        and assignment_changed(prior, current)
    ):
        history.mode_change = _ModeChange(
            prior.mode, current.mode, evidence.finished_at, current.mac
        )
        history.changed_in_latest = True
    elif history.mode_change is not None and (
        current.mode != history.mode_change.mode
        or current.mac != history.mode_change.mac
    ):
        history.mode_change = None
    history.assignment = current
    history.assignment_at = evidence.finished_at
    history.assignment_scope = evidence.scope
    history.assignment_comparable = comparable


def _addressing_view(
    history: _AddressHistory, identity: DeviceIdentity | None
) -> AddressingView | None:
    """Show only what the newest done collection recorded, never a stale value.

    ``identity`` is the card's final identity, which a quick sighting newer
    than the last full collection may have replaced. When both it and the
    assignment name a MAC and they differ, another device now holds the
    address, so the assignment (and its change) is not shown for it.

    A change is shown only while the same MAC holds the address in the mode it
    changed to, so the card never reads "changed from A to B" about another
    mode or another device.
    """
    current = history.assignment
    if current is None or history.assignment_at is None:
        return None
    if (
        current.mac is not None
        and identity is not None
        and identity.mac is not None
        and current.mac != identity.mac
    ):
        return None
    change = history.mode_change
    if change is not None and (
        change.mac != current.mac or change.mode != current.mode
    ):
        change = None
    return AddressingView(
        mode=current.mode,
        basis=current.basis,
        services=current.services,
        expires_at=current.expires_at,
        observed_at=history.assignment_at,
        previous_mode=change.previous if change else None,
        changed_at=change.changed_at if change else None,
        changed_in_latest=history.changed_in_latest and change is not None,
    )


class InventoryProjection:
    """Reuse one inventory projection while its inputs are the same objects.

    The dashboard and the agent read tools share one instance through the
    worker, so neither recomputes the projection the other just built.
    Receipts are immutable once done and the presence view is a cached object
    (PresenceWatch.view), so identity comparison is the freshness check: a new
    receipt, a new presence view or a changed plan rebuilds; anything else
    returns the projection already in memory without rescanning evidence.
    """

    def __init__(self) -> None:
        self._plan: CollectionPlan | None = None
        self._receipts: tuple[Receipt, ...] = ()
        self._presence: PresenceView | None = None
        self._inventory = DeviceInventory()

    def current(
        self,
        plan: CollectionPlan,
        receipts: tuple[Receipt, ...],
        presence: PresenceView,
    ) -> DeviceInventory:
        done = tuple(item for item in receipts if item.phase == "done")
        # The active plan fences visibility, so a plan change always rebuilds.
        if (
            plan != self._plan
            or presence is not self._presence
            or len(done) != len(self._receipts)
            or any(
                current is not cached for current, cached in zip(done, self._receipts)
            )
        ):
            self._inventory = device_inventory(
                plan.targets, plan.labels, done, presence
            )
            self._presence = presence
            self._plan = plan
            self._receipts = done
        return self._inventory
