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
spirit, rule 3 does not apply while an active lease or a configured
reservation/fixed IP for a different MAC exists at the address: either fact
contradicts the bases ``no_lease_or_reservation`` and ``outside_dhcp_range``.

MACs are compared case-insensitively. Conflicts: a reservation for a
different MAC from the holder raises ``addressing_conflict``; the holder is
still assessed by rules 1-2 (its own lease) but rule 3 is blocked, so without
a lease of its own it is ``unknown``;
configured sources (reservations or fixed IPs) naming different MACs for one
address make it ``unknown`` with ``addressing_conflict``. An address whose
identity has no MAC is ``unknown``.

Several complete scopes may contain one address (for instance a Pi-hole and a
UniFi gateway both serving the subnet). Rule 3 decides only when every one of
them gives the same ``(mode, basis)``; the assignment then names all their
services. If one of them cannot decide (an in-range address with an incomplete
lease table) the address is ``unknown``; if they decide differently it is
``unknown`` with one deduplicated ``addressing_conflict``. Sample order never
picks a winner. Rule 3 applies to IPv4 addresses only: scopes are IPv4, so an
IPv6 address without a lease or reservation is ``unknown``.
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
    timestamp,
)
from .contracts import dhcp_lan as dhcp_lan  # One allowlist, shared with decoding.

HOLDER_CONFLICT = (
    "addressing_conflict: a DHCP reservation names a different device than"
    " the one holding the address"
)
CONFIGURED_CONFLICT = (
    "addressing_conflict: configured address assignments disagree on a selected address"
)
SCOPE_CONFLICT = (
    "addressing_conflict: DHCP scopes from different services disagree on a"
    " selected address"
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
    an address they must all agree, or it is ``unknown``. Returned issues are the
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
        if len({item.mac.lower() for item in configured}) > 1:
            issues.append(CONFIGURED_CONFLICT)
            assignments.append(_unknown(address, identity.mac))
            continue
        if identity.mac is None:
            assignments.append(_unknown(address, None))
            continue
        mac = identity.mac.lower()
        configured_other = bool(configured) and configured[0].mac.lower() != mac
        if configured_other:
            issues.append(HOLDER_CONFLICT)
        held = [item for item in configured if item.mac.lower() == mac]
        if held:
            assignments.append(
                _known(address, mac, "dhcp_reserved", held[0].basis, held)
            )
            continue
        leased = [item for item in active if item.address == address]
        own = [item for item in leased if item.mac.lower() == mac]
        if own:
            # Several matching leases (one per service) keep the first one's
            # expiry, in sample order.
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
        # Another MAC's lease or reservation contradicts every scope basis.
        decided: _Decision | None = None
        if not (leased or configured_other):
            decided, disagree = _by_scope(address, scopes)
            if disagree:
                issues.append(SCOPE_CONFLICT)
        assignments.append(
            _unknown(address, mac)
            if decided is None
            else _known(address, mac, decided[0], decided[1], decided[2])
        )
    return tuple(assignments), tuple(dict.fromkeys(issues))


_Decision = tuple[AssignmentMode, AssignmentBasis, tuple[DhcpScope, ...]]


def _by_scope(address: str, scopes: list[DhcpScope]) -> tuple[_Decision | None, bool]:
    """Rule 3 from every complete scope containing ``address``: ``(decision, disagree)``.

    The decision exists only when at least one scope contains the address and
    all of them decide the same ``(mode, basis)``; it carries those scopes.
    ``disagree`` is true when two of them decide differently. A non-IPv4
    address is never contained in a scope and never raises.
    """
    ip = ipaddress.ip_address(address)
    if not isinstance(ip, ipaddress.IPv4Address):
        return None, False
    covering = tuple(item for item in scopes if ip in item.network)
    results = {_scope_result(ip, item) for item in covering}
    if len(results) != 1:
        return None, len(results - {None}) > 1
    [result] = results
    if result is None:
        return None, False
    return (result[0], result[1], covering), False


def _scope_result(
    ip: ipaddress.IPv4Address, scope: DhcpScope
) -> tuple[AssignmentMode, AssignmentBasis] | None:
    """Rule 3 for one complete scope containing ``ip``; None when it cannot decide."""
    start, end = ipaddress.IPv4Address(scope.start), ipaddress.IPv4Address(scope.end)
    if not start <= ip <= end:
        return "static", "outside_dhcp_range"
    if scope.lease_table == "complete":
        return "static", "no_lease_or_reservation"
    if scope.lease_table == "none":
        return "dhcp_dynamic", "inside_dhcp_range"
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
    """Seconds since the epoch; raises ValueError for a timestamp without an offset."""
    return datetime.fromisoformat(timestamp(stamp).replace("Z", "+00:00")).timestamp()
