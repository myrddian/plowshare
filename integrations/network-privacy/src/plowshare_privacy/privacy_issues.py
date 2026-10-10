"""Source-backed privacy knowledge and advisory plans; these records execute nothing.

An issue is shared project knowledge. Device applicability and operator-reported
mitigation progress live in the device profile and pin one reviewed issue revision.
"""

from __future__ import annotations

import ipaddress
import json
from dataclasses import asdict, dataclass
from datetime import date
from urllib.parse import urlsplit

from .contracts import integer, items, object_fields, parse_json, text, timestamp, uuid

PREFIX = "network-privacy-issue/"
MAX_TEXT = 16384


class IssueValidation(ValueError):
    """A safe operator-facing explanation with no echoed private field values."""


def narrative(value: object, limit: int, *, required: bool = True) -> str:
    if (
        not isinstance(value, str)
        or len(value) > limit
        or any(ord(c) < 32 and c not in "\n\t" for c in value)
        or required
        and not value.strip()
    ):
        raise IssueValidation("Expected bounded issue text")
    return value


def choice(value: object, allowed: set[str]) -> str:
    result = text(value, 64)
    if result not in allowed:
        raise IssueValidation("Unsupported issue status or rule option")
    return result


def day(value: object) -> str:
    result = text(value, 10)
    if date.fromisoformat(result).isoformat() != result:
        raise IssueValidation("Expected an ISO calendar date")
    return result


def domain(value: object) -> str:
    result = text(value, 253)
    labels = result.split(".")
    if len(labels) < 2 or any(
        not 1 <= len(label) <= 63
        or label.startswith("-")
        or label.endswith("-")
        or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in label)
        for label in labels
    ):
        raise IssueValidation("Expected an exact lowercase DNS domain")
    return result


@dataclass(frozen=True)
class Citation:
    url: str
    published_on: str | None

    def __post_init__(self) -> None:
        parts = urlsplit(text(self.url, 1024))
        if (
            parts.scheme != "https"
            or not parts.hostname
            or parts.username is not None
            or parts.password is not None
            or any(c.isspace() for c in self.url)
        ):
            raise IssueValidation("Citations need an HTTPS URL without credentials")
        # Accessing port also rejects malformed port syntax. URLs are retained only;
        # the collector never fetches an operator-supplied citation.
        parts.port
        if self.published_on is not None:
            day(self.published_on)

    @classmethod
    def decode(cls, value: object) -> Citation:
        row = object_fields(value, {"url", "published_on"})
        return cls(
            text(row["url"], 1024),
            day(row["published_on"]) if row["published_on"] is not None else None,
        )


@dataclass(frozen=True)
class Mitigation:
    mechanism: str
    direction: str
    action: str
    source: str
    destination: str
    protocol: str
    ports: tuple[int, ...]
    reason: str
    impact: str
    verification: str
    rollback: str

    def __post_init__(self) -> None:
        choice(self.mechanism, {"firewall", "pihole", "segmentation", "device_setting"})
        choice(self.direction, {"inbound", "outbound", "lateral", "not_applicable"})
        choice(self.action, {"block", "restrict", "disable"})
        choice(self.protocol, {"tcp", "udp", "any", "not_applicable"})
        narrative(self.source, 253)
        narrative(self.destination, 253)
        for value in (self.reason, self.impact, self.verification, self.rollback):
            narrative(value, 768)
        if len(self.ports) > 8 or len(set(self.ports)) != len(self.ports):
            raise IssueValidation("Choose at most eight unique ports")
        for port in self.ports:
            integer(port, 1, 65535)
        if self.mechanism == "firewall":
            if (
                self.direction == "not_applicable"
                or self.action == "disable"
                or self.protocol == "not_applicable"
            ):
                raise IssueValidation(
                    "A firewall rule needs direction, protocol and block/restrict action"
                )
            if self.direction == "outbound":
                device_endpoint = self.source
            elif self.direction == "inbound":
                device_endpoint = self.destination
            else:
                device_endpoint = (
                    "linked_device"
                    if "linked_device" in {self.source, self.destination}
                    else ""
                )
            if device_endpoint != "linked_device" or self.source == self.destination:
                raise IssueValidation(
                    "A firewall plan must identify the linked device at the proper endpoint"
                )
            peer = self.destination if self.source == "linked_device" else self.source
            if peer != "internet":
                network = ipaddress.ip_network(peer, strict=True)
                canonical = (
                    str(network) if "/" in peer else str(ipaddress.ip_address(peer))
                )
                if canonical != peer:
                    raise IssueValidation(
                        "Use canonical source/destination IP/CIDR or internet"
                    )
                if self.direction == "lateral" and not network.is_private:
                    raise IssueValidation(
                        "Lateral rules must target a private network peer"
                    )
            elif self.direction == "lateral":
                raise IssueValidation("Lateral rules require an explicit private peer")
            if (self.protocol in {"tcp", "udp"}) != bool(self.ports):
                raise IssueValidation(
                    "TCP/UDP need ports; any protocol requires an empty port list"
                )
        elif self.mechanism == "pihole":
            domain(self.destination)
            if self.source != "linked_device":
                raise IssueValidation("Pi-hole plans must identify the linked device")
            if (self.direction, self.action, self.protocol, self.ports) != (
                "outbound",
                "block",
                "not_applicable",
                (),
            ):
                raise IssueValidation(
                    "Pi-hole plans block one exact DNS domain; they do not block ports"
                )
        elif (
            self.protocol != "not_applicable"
            or self.ports
            or self.direction != "not_applicable"
        ):
            raise IssueValidation(
                "Segmentation/settings plans describe changes without port-rule semantics"
            )

    @classmethod
    def decode(cls, value: object) -> Mitigation:
        row = object_fields(
            value,
            {
                "mechanism",
                "direction",
                "action",
                "source",
                "destination",
                "protocol",
                "ports",
                "reason",
                "impact",
                "verification",
                "rollback",
            },
        )
        return cls(
            text(row["mechanism"], 64),
            text(row["direction"], 64),
            text(row["action"], 64),
            narrative(row["source"], 253),
            narrative(row["destination"], 253),
            text(row["protocol"], 64),
            tuple(integer(v, 1, 65535) for v in items(row["ports"], 8)),
            *(
                narrative(row[key], 768)
                for key in ("reason", "impact", "verification", "rollback")
            ),
        )


@dataclass(frozen=True)
class IssueFields:
    title: str
    description: str
    affected_models: str
    firmware: str
    conditions: str
    checked_on: str
    sources: tuple[Citation, ...]
    mitigation: Mitigation

    def __post_init__(self) -> None:
        narrative(self.title, 128)
        for value in (
            self.description,
            self.affected_models,
            self.firmware,
            self.conditions,
        ):
            narrative(value, 1024)
        day(self.checked_on)
        if not 1 <= len(self.sources) <= 4 or len({v.url for v in self.sources}) != len(
            self.sources
        ):
            raise IssueValidation(
                "An issue needs one to four distinct source citations"
            )

    @classmethod
    def decode(cls, value: object) -> IssueFields:
        row = object_fields(
            value,
            {
                "title",
                "description",
                "affected_models",
                "firmware",
                "conditions",
                "checked_on",
                "sources",
                "mitigation",
            },
        )
        return cls(
            narrative(row["title"], 128),
            narrative(row["description"], 1024),
            narrative(row["affected_models"], 1024),
            narrative(row["firmware"], 1024),
            narrative(row["conditions"], 1024),
            day(row["checked_on"]),
            tuple(Citation.decode(v) for v in items(row["sources"], 4)),
            Mitigation.decode(row["mitigation"]),
        )


@dataclass(frozen=True)
class IssueLink:
    issue_id: str
    revision: str
    applicability: str
    mitigation_status: str
    notes: str
    evidence: tuple[str, ...]

    def __post_init__(self) -> None:
        uuid(self.issue_id)
        uuid(self.revision)
        choice(
            self.applicability,
            {
                "potentially_affected",
                "confirmed_affected",
                "not_affected",
                "mitigated",
                "unresolved",
            },
        )
        choice(
            self.mitigation_status,
            {"proposed", "accepted", "applied", "verified", "reverted"},
        )
        narrative(self.notes, 512, required=False)
        if len(self.evidence) > 4 or len(set(self.evidence)) != len(self.evidence):
            raise IssueValidation(
                "Choose at most four distinct retained evidence revisions"
            )
        for revision in self.evidence:
            uuid(revision)
        if (
            self.applicability == "confirmed_affected"
            or self.mitigation_status in {"applied", "verified", "reverted"}
        ) and not self.notes.strip():
            raise IssueValidation(
                "Confirmation and performed actions need an operator explanation"
            )
        if self.applicability == "mitigated" and self.mitigation_status != "verified":
            raise IssueValidation(
                "Record verified mitigation before marking a device mitigated"
            )

    @classmethod
    def decode(cls, value: object) -> IssueLink:
        row = object_fields(
            value,
            {
                "issue_id",
                "revision",
                "applicability",
                "mitigation_status",
                "notes",
                "evidence",
            },
        )
        return cls(
            uuid(row["issue_id"]),
            uuid(row["revision"]),
            text(row["applicability"], 64),
            text(row["mitigation_status"], 64),
            narrative(row["notes"], 512, required=False),
            tuple(uuid(v) for v in items(row["evidence"], 4)),
        )

    def markdown(self) -> str:
        return f"- Issue: `{PREFIX}{self.issue_id}.md`\n  Reviewed Information revision: `{self.revision}`\n  Applicability: {self.applicability}\n  Operator-reported mitigation: {self.mitigation_status}\n  Assessment/result: {self.notes or 'Not supplied'}\n  Evidence revisions: {', '.join(self.evidence) or 'None linked'}\n"


@dataclass(frozen=True)
class PrivacyIssue:
    version: int
    issue_id: str
    write_id: str
    updated_at: str
    fields: IssueFields

    @classmethod
    def decode(cls, value: object) -> PrivacyIssue:
        row = object_fields(
            value, {"version", "issue_id", "write_id", "updated_at", "fields"}
        )
        if type(row["version"]) is not int or row["version"] != 1:
            raise IssueValidation("Unsupported privacy issue version")
        return cls(
            1,
            uuid(row["issue_id"]),
            uuid(row["write_id"]),
            timestamp(row["updated_at"]),
            IssueFields.decode(row["fields"]),
        )

    def markdown(self) -> str:
        fields, plan = self.fields, self.fields.mitigation
        sources = "\n".join(
            f"- {source.url} (published: {source.published_on or 'unknown'})"
            for source in fields.sources
        )
        ports = (
            ", ".join(str(port) for port in plan.ports)
            or "not applicable / all protocols as specified"
        )
        sections = (
            f"# Privacy issue: {fields.title}",
            fields.description,
            "## Applicability",
            f"Models: {fields.affected_models}",
            f"Firmware: {fields.firmware}",
            f"Conditions/settings: {fields.conditions}",
            f"Sources last checked: {fields.checked_on}",
            "## Sources",
            sources,
            "## Proposed mitigation",
            f"Mechanism: {plan.mechanism}",
            "Device: the explicitly linked device profile, subject to identity confirmation",
            f"Direction: {plan.direction}",
            f"Action: {plan.action}",
            f"Source: {plan.source}",
            f"Destination: {plan.destination}",
            f"Protocol: {plan.protocol}",
            f"Destination ports: {ports}",
            f"Reason/evidence: {plan.reason}",
            f"Expected impact: {plan.impact}",
            f"Verification: {plan.verification}",
            f"Rollback: {plan.rollback}",
            "This is an advisory plan. The collector does not execute rules or fetch citation URLs. Applicability, decision timestamps and operator-reported results are retained in device profile revisions. Listening-port scans cannot establish outbound traffic or rule effectiveness.",
            "## Record metadata",
            "```json\n"
            + json.dumps(asdict(self), ensure_ascii=False, sort_keys=True, indent=2)
            + "\n```",
        )
        result = "\n\n".join(sections) + "\n"
        if len(result.encode("utf-8")) > MAX_TEXT:
            raise IssueValidation("Privacy issue exceeds the 16 KiB document bound")
        return result

    @classmethod
    def read(cls, source: str) -> PrivacyIssue:
        marker = "\n\n## Record metadata\n\n```json\n"
        start = source.rfind(marker)
        end = source.find("\n```\n", start + len(marker))
        if start < 0 or end < 0 or len(source.encode("utf-8")) > MAX_TEXT:
            raise IssueValidation("Unavailable privacy issue")
        result = cls.decode(parse_json(source[start + len(marker) : end].encode()))
        if result.markdown() != source:
            raise IssueValidation("Issue text differs from its validated record")
        return result
