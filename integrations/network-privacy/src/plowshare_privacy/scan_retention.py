"""What a device card keeps after its old scans leave the local journal.

The journal holds full evidence only for a recent window of settled scans. The
project information store still owns every retained evidence revision. Before a
scan leaves the window, the few per-address facts that the device inventory
derives from *all* history are folded into one small summary per address, so a
device does not lose its first and last response, its last known identity or
its place on the dashboard merely because the scan that established them aged
out.
"""

from __future__ import annotations

import ipaddress
from dataclasses import asdict, dataclass
from datetime import datetime, timezone

from .contracts import DeviceIdentity, Evidence, object_fields, text, timestamp
from .limits import MAX_CARRIED_ADDRESSES


@dataclass(frozen=True)
class CarriedAddress:
    """History of one address taken from scans no longer held locally.

    The fields mirror the inventory's own rules: a response is an open or
    closed TCP answer, a known identity is never the configured placeholder,
    and one observed open service keeps the address a device.
    """

    address: str
    first_response_at: str | None
    last_response_at: str | None
    last_known_identity: DeviceIdentity | None
    open_service_observed: bool

    def encode(self) -> dict[str, object]:
        row = asdict(self)
        known = row["last_known_identity"]
        if known is not None:
            # Match the evidence codec: absent provenance stays absent.
            for key in ("evidence", "service"):
                if known[key] is None:
                    del known[key]
        return row

    @classmethod
    def decode(cls, value: object) -> CarriedAddress:
        row = object_fields(
            value,
            {
                "address",
                "first_response_at",
                "last_response_at",
                "last_known_identity",
                "open_service_observed",
            },
        )
        if type(row["open_service_observed"]) is not bool:
            raise ValueError("Carried history requires a boolean service flag")
        address = str(ipaddress.ip_address(text(row["address"])))
        known = (
            DeviceIdentity.decode(row["last_known_identity"])
            if row["last_known_identity"] is not None
            else None
        )
        if known is not None and known.address != address:
            raise ValueError("Carried identity belongs to another address")
        return cls(
            address,
            timestamp(row["first_response_at"])
            if row["first_response_at"] is not None
            else None,
            timestamp(row["last_response_at"])
            if row["last_response_at"] is not None
            else None,
            known,
            row["open_service_observed"],
        )


def _instant(value: str | None) -> datetime:
    if value is None:
        return datetime.min.replace(tzinfo=timezone.utc)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _address_order(item: CarriedAddress) -> tuple[int, int]:
    address = ipaddress.ip_address(item.address)
    return address.version, int(address)


def carry_forward(
    carried: tuple[CarriedAddress, ...], evidence: Evidence
) -> tuple[CarriedAddress, ...]:
    """Fold one departing scan into the carried summaries.

    Scans must be folded oldest first, the order in which the inventory reads
    them. An address with nothing worth keeping (only unanswered probes and no
    real identity) gets no entry. When the bound is reached, the address that
    answered longest ago makes room; it then reads as it would have before any
    carried history existed.
    """
    result = {item.address: item for item in carried}
    identities = {item.address: item for item in evidence.snapshot.devices}
    statuses: dict[str, set[str]] = {}
    for probe in evidence.snapshot.tcp:
        statuses.setdefault(probe.address, set()).add(probe.status)
    for address, seen in statuses.items():
        previous = result.get(address, CarriedAddress(address, None, None, None, False))
        identity = identities.get(address)
        responded = bool(seen & {"open", "closed"})
        updated = CarriedAddress(
            address,
            previous.first_response_at or (evidence.finished_at if responded else None),
            evidence.finished_at if responded else previous.last_response_at,
            identity
            if identity is not None and identity.source != "configured"
            else previous.last_known_identity,
            previous.open_service_observed or "open" in seen,
        )
        if (
            updated.last_response_at is not None
            or updated.last_known_identity is not None
            or updated.open_service_observed
        ):
            result[address] = updated
    kept = sorted(
        result.values(), key=lambda item: _instant(item.last_response_at), reverse=True
    )[:MAX_CARRIED_ADDRESSES]
    return tuple(sorted(kept, key=_address_order))
