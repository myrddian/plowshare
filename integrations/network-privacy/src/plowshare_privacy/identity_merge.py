"""One precedence rule for merging identity evidence about an address.

Contract of `merge_identities`, a pure function (no I/O, no issue text; callers
emit their own issue strings from the returned sets):

* Validation: every sample must hold at most one record per address, must not
  list a device at an address it also marks ambiguous, and must keep both its
  device and ambiguous addresses inside `scope`; otherwise `ValueError` is raised before any merge.
* Ambiguity: the union of the samples' `ambiguous_addresses`. Those addresses
  get no device, even when another record names them.
* Conflict: if the records for an address name two or more different non-null
  MACs (compared case-insensitively), the address is a conflict and gets no
  device, regardless of rank. A record of any kind without a MAC never
  conflicts; it can only win when no MAC-bearing record outranks it.
* Precedence: otherwise the record whose evidence kind ranks highest in
  `PRECEDENCE` wins whole; its `DeviceIdentity` is returned unchanged, with no
  field coalescing from lower-ranked records. Ties keep the earliest record
  (sample order, then record order). `local_dns` is name-only, so it never wins
  against a MAC-bearing record. `configured` is not in `PRECEDENCE`; callers
  never pass it, but if one is passed it ranks below `local_dns`.
* Order: devices are returned in first-seen order, the order their addresses
  first appear across the samples (sample order, then record order), never
  sorted.
"""

from collections.abc import Sequence
from dataclasses import dataclass

from .contracts import DeviceIdentity, EvidenceKind, evidence_kind
from .identity_source import IdentitySample

# Highest rank first.
PRECEDENCE: tuple[EvidenceKind, ...] = (
    "managed_device",
    "controller_client",
    "dhcp_lease",
    "neighbour_table",
    "local_dns",
)
_INVALID = "Identity source returned invalid or out-of-scope addresses"


@dataclass(frozen=True)
class IdentityMerge:
    devices: tuple[DeviceIdentity, ...]
    conflicts: frozenset[str]
    ambiguous: frozenset[str]


def _rank(identity: DeviceIdentity) -> int:
    kind = evidence_kind(identity)
    return PRECEDENCE.index(kind) if kind in PRECEDENCE else len(PRECEDENCE)


def merge_identities(
    samples: Sequence[IdentitySample], scope: frozenset[str]
) -> IdentityMerge:
    by_address: dict[str, list[DeviceIdentity]] = {}
    ambiguous: set[str] = set()
    for sample in samples:
        addresses = [device.address for device in sample.devices]
        listed = set(addresses)
        if (
            len(listed) != len(addresses)
            or listed & sample.ambiguous_addresses
            or not (listed | sample.ambiguous_addresses) <= scope
        ):
            raise ValueError(_INVALID)
        for device in sample.devices:
            by_address.setdefault(device.address, []).append(device)
        ambiguous |= sample.ambiguous_addresses
    devices: list[DeviceIdentity] = []
    conflicts: set[str] = set()
    # First-seen order: snapshot device order feeds retained evidence text.
    for address in by_address:
        if address in ambiguous:
            continue
        records = by_address[address]
        macs = {record.mac.lower() for record in records if record.mac}
        if len(macs) > 1:
            conflicts.add(address)
            continue
        devices.append(min(records, key=_rank))
    return IdentityMerge(tuple(devices), frozenset(conflicts), frozenset(ambiguous))
