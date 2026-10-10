"""Private operator preferences and acknowledgements, separate from scan evidence.

Only authenticated operator APIs write these records. Expected findings remain in
original evidence; acknowledgement controls presentation and future investigation
admission. The collector journal owns decisions once a completion is publishing.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

from .contracts import Evidence, integer, items, load_json, object_fields, text, utc_now

if TYPE_CHECKING:
    from .journal import Receipt
from .private_files import replace_private


@dataclass(frozen=True)
class Preferences:
    automatic_investigations: bool = True
    daily_limit: int = 8
    cooldown_minutes: int = 60
    browser_notifications: bool = False

    @classmethod
    def decode(cls, value: object) -> Preferences:
        row = object_fields(
            value,
            {
                "automatic_investigations",
                "daily_limit",
                "cooldown_minutes",
                "browser_notifications",
            },
        )
        if (
            type(row["automatic_investigations"]) is not bool
            or type(row["browser_notifications"]) is not bool
        ):
            raise ValueError("Preferences require explicit booleans")
        return cls(
            row["automatic_investigations"],
            integer(row["daily_limit"], 1, 50),
            integer(row["cooldown_minutes"], 0, 1440),
            row["browser_notifications"],
        )


@dataclass(frozen=True)
class InvestigationDecision:
    requested: bool
    reason: str
    decided_at: str

    @classmethod
    def decode(cls, value: object) -> InvestigationDecision:
        from .contracts import timestamp

        row = object_fields(value, {"requested", "reason", "decided_at"})
        if type(row["requested"]) is not bool:
            raise ValueError("Invalid investigation decision")
        return cls(
            row["requested"], text(row["reason"], 256), timestamp(row["decided_at"])
        )


@dataclass(frozen=True)
class Finding:
    id: str
    scan_id: str
    revision: str | None
    observed_at: str
    device_id: str | None
    address: str | None
    kind: str
    title: str
    explanation: str
    expected: bool


def findings(receipt: Receipt, expected: frozenset[str]) -> tuple[Finding, ...]:
    evidence = receipt.evidence
    if evidence is None:
        return ()
    identities = {item.address: item for item in evidence.snapshot.devices}
    result = []
    for change in evidence.changes:
        if change.startswith("Baseline collection"):
            continue
        kind, address, device_id = "coverage", None, None
        explanation = "Compare the collection gaps with earlier evidence. Missing observations do not establish that a device disappeared."
        # These exact phrases are emitted by collection.changes; all original text
        # stays in evidence. Domain keys follow a discovered MAC where available.
        domain_key = None
        for query in evidence.snapshot.dns:
            if (
                change
                == f"New observed DNS destination for {query.device}: {query.domain}"
            ):
                kind, address, domain_key = "dns", query.device, query.domain
                identity = identities.get(address)
                device_id = identity.device_id if identity else "ip:" + address
                explanation = "The observation source recorded this DNS name in the sample. It does not establish a connection, payload content or malicious behavior."
                break
        if change.startswith("TCP "):
            kind = "service"
            address = next(
                (
                    item.address
                    for item in evidence.snapshot.tcp
                    if change.startswith(f"TCP {item.address}:{item.port}:")
                ),
                None,
            )
            if address:
                identity = identities.get(address)
                device_id = identity.device_id if identity else "ip:" + address
            explanation = "A selected TCP service responded differently. Check whether an update or configuration change explains it."
        elif change.startswith("Device association at "):
            kind = "identity"
            explanation = "An address has a different observed MAC association. DHCP reuse and MAC randomization can affect attribution."
        key = (
            [device_id, "dns", domain_key]
            if domain_key
            else [evidence.scope, device_id, change]
        )
        identity_key = hashlib.sha256(json.dumps(key).encode()).hexdigest()
        result.append(
            Finding(
                identity_key,
                evidence.scan_id,
                receipt.revision,
                evidence.finished_at,
                device_id,
                address,
                kind,
                change,
                explanation,
                identity_key in expected,
            )
        )
    return tuple(result)


class OperatorStore(Protocol):
    @property
    def preferences(self) -> Preferences: ...
    @property
    def expected(self) -> frozenset[str]: ...
    def set_preferences(self, value: Preferences) -> None: ...
    def acknowledge(self, identity: str, expected: bool) -> None: ...
    def decide(
        self, evidence: Evidence, receipts: tuple[Receipt, ...]
    ) -> InvestigationDecision: ...


class FileOperatorStore:
    def __init__(self, root: Path):
        self.path = root / "operator-preferences.json"
        self._preferences = Preferences()
        self._expected: frozenset[str] = frozenset()
        if self.path.exists():
            if self.path.is_symlink() or self.path.resolve() != self.path:
                raise ValueError("Operator state must be unlinked")
            row = object_fields(
                load_json(self.path), {"version", "preferences", "expected"}
            )
            integer(row["version"], 1, 1)
            self._preferences = Preferences.decode(row["preferences"])
            values = tuple(text(item, 64) for item in items(row["expected"], 512))
            if len(set(values)) != len(values) or any(
                len(item) != 64 or any(c not in "0123456789abcdef" for c in item)
                for item in values
            ):
                raise ValueError("Invalid expected-finding identities")
            self._expected = frozenset(values)

    @property
    def preferences(self) -> Preferences:
        return self._preferences

    @property
    def expected(self) -> frozenset[str]:
        return self._expected

    def _save(self, preferences: Preferences, expected: frozenset[str]) -> None:
        replace_private(
            self.path,
            json.dumps(
                {
                    "version": 1,
                    "preferences": asdict(preferences),
                    "expected": sorted(expected),
                }
            )
            + "\n",
        )
        self._preferences, self._expected = preferences, expected

    def set_preferences(self, value: Preferences) -> None:
        self._save(value, self.expected)

    def acknowledge(self, identity: str, expected: bool) -> None:
        if len(identity) != 64 or any(c not in "0123456789abcdef" for c in identity):
            raise ValueError("Invalid finding identity")
        updated = self.expected | {identity} if expected else self.expected - {identity}
        if len(updated) > 512:
            raise ValueError(
                "Expected-finding limit reached; remove an old acknowledgement first"
            )
        self._save(self.preferences, frozenset(updated))

    def decide(
        self, evidence: Evidence, receipts: tuple[Receipt, ...]
    ) -> InvestigationDecision:
        now = utc_now()
        from .journal import Receipt

        candidate = Receipt(
            evidence.scan_id,
            evidence.source_event,
            "privacy.scan.completed",
            evidence.started_at,
            evidence=evidence,
        )
        meaningful = any(
            not item.expected for item in findings(candidate, self.expected)
        )
        baseline = any(
            change.startswith("Baseline collection") for change in evidence.changes
        )
        policy = self.preferences
        if not policy.automatic_investigations:
            return InvestigationDecision(
                False, "Automatic investigations are disabled", now
            )
        if not meaningful and not baseline:
            return InvestigationDecision(False, "No new unacknowledged findings", now)
        # Legacy completions may already have admitted work. Count their changed
        # evidence conservatively instead of granting a fresh budget on upgrade.
        dates = []
        for receipt in receipts:
            when = None
            if receipt.investigation and receipt.investigation.requested:
                when = receipt.investigation.decided_at
            elif (
                receipt.investigation is None
                and receipt.phase in {"publishing", "done"}
                and receipt.evidence
                and receipt.evidence.changes
            ):
                when = receipt.evidence.finished_at
            if when:
                dates.append(datetime.fromisoformat(when.replace("Z", "+00:00")))
        today = datetime.now(timezone.utc).date()
        if sum(date.date() == today for date in dates) >= policy.daily_limit:
            return InvestigationDecision(
                False, "Daily automatic-investigation limit reached", now
            )
        if (
            policy.cooldown_minutes > 0
            and dates
            and (datetime.now(timezone.utc) - max(dates)).total_seconds()
            < policy.cooldown_minutes * 60
        ):
            return InvestigationDecision(
                False, "Automatic-investigation cooldown is active", now
            )
        return InvestigationDecision(
            True, "Baseline or new unacknowledged findings", now
        )
