"""The Addressing bucket: how each selected address was assigned.

Connectors only report decoded facts (``Lease``, ``Reservation``, ``DhcpScope``)
in an ``AddressingSample``; they never decide a mode. ``assign_addresses`` is
the single vendor-neutral decision. For each selected address whose holder has
a confirmed MAC it applies, in order:

1. A reservation or controller fixed IP for this address and this MAC →
   ``dhcp_reserved`` (``dhcp_reservation`` / ``controller_fixed_ip``).
2. An active lease for this address and this MAC → ``dhcp_dynamic``
   (``dhcp_lease``, keeping the lease's expiry).
3. No active lease at the address, and a complete DHCP scope whose network
   contains it:

   - outside the scope's range → ``static`` (``outside_dhcp_range``);
   - inside the range, where that service's lease table was read completely →
     ``static`` (``no_lease_or_reservation``);
   - inside the range, where the service has no lease table →
     ``dhcp_dynamic`` (``inside_dhcp_range``);
   - inside the range with an incomplete lease table → ``unknown``.

4. Anything else → ``unknown``.

Invariant: ``static`` is never a default. It is only ever concluded from a
complete scope (plus, inside the range, a completely read lease table); any
missing, incomplete or contradictory evidence yields ``unknown``. In the same
spirit, rule 3 does not apply while any active lease (for whatever MAC) holds
the address: a lease table showing the address in use is not evidence of a
static assignment.

Conflicts: a reservation for a different MAC from the holder raises
``addressing_conflict`` and the holder is still assessed by the rules above;
configured sources (reservations or fixed IPs) naming different MACs for one
address make it ``unknown`` with ``addressing_conflict``. An address whose
identity has no MAC is ``unknown``.
"""

from __future__ import annotations

import ipaddress
from dataclasses import dataclass
from datetime import datetime
from typing import Literal, Mapping, Sequence

from .contracts import (
    AddressAssignment,
    AssignmentBasis,
    AssignmentMode,
    DeviceIdentity,
)

HOLDER_CONFLICT = (
    "addressing_conflict: a DHCP reservation names a different device than"
    " the one holding the address"
)
CONFIGURED_CONFLICT = (
    "addressing_conflict: configured address assignments disagree on a selected address"
)


@dataclass(frozen=True)
class Lease:
    """An observed DHCP lease; ``expires_at=None`` means it does not expire."""

    address: str
    mac: str
    hostname: str | None
    expires_at: str | None
    service: str


@dataclass(frozen=True)
class Reservation:
    """Configured intent: a DHCP reservation or a controller's fixed IP."""

    address: str
    mac: str
    basis: Literal["dhcp_reservation", "controller_fixed_ip"]
    service: str


@dataclass(frozen=True)
class DhcpScope:
    """A service's DHCP scope: ``network`` with its dynamic range ``start``-``end``.

    ``complete`` means DHCP is active and the settings were read fully.
    ``lease_table`` says whether that service's lease table was read
    completely, incompletely, or does not exist (``none``).
    """

    service: str
    network: ipaddress.IPv4Network
    start: str
    end: str
    complete: bool
    lease_table: Literal["complete", "incomplete", "none"]


@dataclass(frozen=True)
class AddressingSample:
    """One read's addressing facts plus that connector's own fixed-text issues."""

    leases: tuple[Lease, ...] = ()
    reservations: tuple[Reservation, ...] = ()
    scopes: tuple[DhcpScope, ...] = ()
    issues: tuple[str, ...] = ()


def assign_addresses(
    samples: Sequence[AddressingSample],
    devices: Mapping[str, DeviceIdentity],
    targets: tuple[str, ...],
    observed_at: str,
) -> tuple[tuple[AddressAssignment, ...], tuple[str, ...]]:
    """One assignment per target with an identity record in ``devices``, in target order.

    ``devices`` is the snapshot's final merged identity map. Leases expiring at
    or before ``observed_at`` are ignored. When several complete scopes contain
    an address, the first in sample order decides it. Returned issues are the
    samples' own issues in sample order, then the conflicts, deduplicated.
    Raises ValueError for a lease or reservation outside ``targets``.
    """
    watched = frozenset(targets)
    leases = [lease for sample in samples for lease in sample.leases]
    reservations = [item for sample in samples for item in sample.reservations]
    addresses = [fact.address for fact in leases] + [
        fact.address for fact in reservations
    ]
    if not set(addresses) <= watched:
        raise ValueError("Addressing source returned facts outside the selected scope")
    now = _instant(observed_at)
    active = [
        lease
        for lease in leases
        if lease.expires_at is None or _instant(lease.expires_at) > now
    ]
    scopes = [scope for sample in samples for scope in sample.scopes if scope.complete]
    issues = [issue for sample in samples for issue in sample.issues]
    assignments: list[AddressAssignment] = []
    for address in targets:
        identity = devices.get(address)
        if identity is None:
            continue
        configured = [item for item in reservations if item.address == address]
        if len({item.mac for item in configured}) > 1:
            issues.append(CONFIGURED_CONFLICT)
            assignments.append(_unknown(address, identity.mac))
            continue
        mac = identity.mac
        if mac is None:
            assignments.append(_unknown(address, None))
            continue
        if configured and configured[0].mac != mac:
            issues.append(HOLDER_CONFLICT)
        held = [item for item in configured if item.mac == mac]
        if held:
            assignments.append(
                _known(address, mac, "dhcp_reserved", held[0].basis, held)
            )
            continue
        leased = [item for item in active if item.address == address]
        own = [item for item in leased if item.mac == mac]
        if own:
            assignments.append(
                AddressAssignment(
                    address,
                    mac,
                    "dhcp_dynamic",
                    "dhcp_lease",
                    _services(own),
                    own[0].expires_at,
                )
            )
            continue
        decided = None if leased else _by_scope(address, scopes)
        assignments.append(
            _unknown(address, mac)
            if decided is None
            else _known(address, mac, decided[0], decided[1], [decided[2]])
        )
    return tuple(assignments), tuple(dict.fromkeys(issues))


def _by_scope(
    address: str, scopes: list[DhcpScope]
) -> tuple[AssignmentMode, AssignmentBasis, DhcpScope] | None:
    """Rule 3 from the first complete scope containing ``address``, if any."""
    ip = ipaddress.IPv4Address(address)
    scope = next((item for item in scopes if ip in item.network), None)
    if scope is None:
        return None
    start, end = ipaddress.IPv4Address(scope.start), ipaddress.IPv4Address(scope.end)
    if not start <= ip <= end:
        return "static", "outside_dhcp_range", scope
    if scope.lease_table == "complete":
        return "static", "no_lease_or_reservation", scope
    if scope.lease_table == "none":
        return "dhcp_dynamic", "inside_dhcp_range", scope
    return None


def _known(
    address: str,
    mac: str,
    mode: AssignmentMode,
    basis: AssignmentBasis,
    facts: Sequence[Reservation | DhcpScope],
) -> AddressAssignment:
    return AddressAssignment(address, mac, mode, basis, _services(facts), None)


def _unknown(address: str, mac: str | None) -> AddressAssignment:
    return AddressAssignment(address, mac, "unknown", None, (), None)


def _services(facts: Sequence[Lease | Reservation | DhcpScope]) -> tuple[str, ...]:
    """Contributing service ids, deduplicated in first-seen order."""
    return tuple(dict.fromkeys(fact.service for fact in facts))


def _instant(stamp: str) -> float:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()
