"""Closed application DTOs and strict JSON boundaries, independent of SDK envelopes."""

from __future__ import annotations

import ipaddress
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import AbstractSet, Literal
from urllib.parse import urlsplit
from uuid import UUID

MAX_FILE_BYTES = 1_048_576


def object_fields(
    value: object, required: set[str], optional: AbstractSet[str] = frozenset()
) -> dict[str, object]:
    """Validate an object at a JSON boundary; dictionaries never enter domain contracts."""
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise ValueError("Expected a JSON object")
    row: dict[str, object] = {key: item for key, item in value.items()}
    if set(row) - required - optional or required - set(row):
        raise ValueError("Missing or unsupported fields")
    return row


def text(value: object, limit: int = 256) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > limit
        or any(ord(c) < 32 for c in value)
    ):
        raise ValueError("Expected bounded nonempty text without control characters")
    return value


def identifier(value: object) -> str:
    result = text(value, 128)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", result):
        raise ValueError("Invalid identifier")
    return result


def integer(value: object, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Integer outside supported bounds")
    return value


def items(value: object, maximum: int) -> list[object]:
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError("Expected bounded array")
    return list(value)


def timestamp(value: object) -> str:
    result = text(value, 64)
    parsed = datetime.fromisoformat(result.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Timestamp requires an offset")
    return result


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def same_instant(first: str, second: str) -> bool:
    """Retained servers may normalize fractional seconds or UTC offset spelling."""
    return datetime.fromisoformat(
        timestamp(first).replace("Z", "+00:00")
    ) == datetime.fromisoformat(timestamp(second).replace("Z", "+00:00"))


def uuid(value: object) -> str:
    result = text(value, 36)
    if str(UUID(result)) != result:
        raise ValueError("Expected canonical UUID")
    return result


def load_json(path: Path) -> object:
    with path.open("rb") as source:
        content = source.read(MAX_FILE_BYTES + 1)
    if len(content) > MAX_FILE_BYTES:
        raise ValueError("JSON file exceeds the size limit")

    return parse_json(content)


def parse_json(content: str | bytes) -> object:
    if len(content.encode() if isinstance(content, str) else content) > MAX_FILE_BYTES:
        raise ValueError("JSON exceeds the size limit")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        row: dict[str, object] = {}
        for key, value in pairs:
            if key in row:
                raise ValueError("Duplicate JSON field")
            row[key] = value
        return row

    def invalid_constant(value: str) -> object:
        raise ValueError("Nonfinite JSON number")

    result: object = json.loads(
        content, object_pairs_hook=unique, parse_constant=invalid_constant
    )
    return result


@dataclass(frozen=True)
class TcpObservation:
    address: str
    port: int
    status: Literal["open", "closed", "timeout", "unreachable"]

    @classmethod
    def decode(cls, value: object) -> TcpObservation:
        row = object_fields(value, {"address", "port", "status"})
        address = str(ipaddress.ip_address(text(row["address"])))
        status = row["status"]
        if status not in ("open", "closed", "timeout", "unreachable"):
            raise ValueError("Unsupported TCP status")
        if status == "open":
            return cls(address, integer(row["port"], 1, 65535), "open")
        if status == "closed":
            return cls(address, integer(row["port"], 1, 65535), "closed")
        if status == "timeout":
            return cls(address, integer(row["port"], 1, 65535), "timeout")
        return cls(address, integer(row["port"], 1, 65535), "unreachable")


@dataclass(frozen=True)
class DnsObservation:
    device: str
    domain: str
    queries: int

    @classmethod
    def decode(cls, value: object) -> DnsObservation:
        row = object_fields(value, {"device", "domain", "queries"})
        domain = text(row["domain"], 253)
        if any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in domain.split(".")
        ):
            raise ValueError("Expected a lowercase DNS name")
        return cls(
            identifier(row["device"]), domain, integer(row["queries"], 1, 1_000_000)
        )


@dataclass(frozen=True)
class Snapshot:
    """A bounded export/fixture. DNS queries are observations, not proof of payload exfiltration."""

    observed_at: str
    tcp: tuple[TcpObservation, ...]
    dns: tuple[DnsObservation, ...]

    @classmethod
    def decode(cls, value: object) -> Snapshot:
        row = object_fields(value, {"version", "observed_at", "tcp", "dns"})
        integer(row["version"], 1, 1)
        tcp = tuple(TcpObservation.decode(item) for item in items(row["tcp"], 256))
        dns = tuple(DnsObservation.decode(item) for item in items(row["dns"], 256))
        if len({(item.address, item.port) for item in tcp}) != len(tcp) or len(
            {(item.device, item.domain) for item in dns}
        ) != len(dns):
            raise ValueError("Duplicate observation")
        return cls(timestamp(row["observed_at"]), tcp, dns)


@dataclass(frozen=True)
class CollectionPlan:
    mode: Literal["tcp", "fixture"]
    targets: tuple[str, ...]
    ports: tuple[int, ...]
    timeout: float
    concurrency: int
    observations_file: Path | None
    max_observation_age: int
    enabled: bool = True


@dataclass(frozen=True)
class Configuration:
    origin: str
    project: str
    collector: str
    token_environment: str
    web_token_environment: str
    state_directory: Path
    request_topic: str
    result_topic: str
    group: str
    schedule: str
    collection: CollectionPlan
    outgoing_peer: str | None = None

    @classmethod
    def read(cls, path: Path) -> Configuration:
        return cls.decode(load_json(path), path)

    @classmethod
    def decode(cls, value: object, path: Path) -> Configuration:
        """Validate a complete configuration before installing or saving it."""
        row = object_fields(
            value,
            {
                "origin",
                "project",
                "collector",
                "tokenEnvironment",
                "webTokenEnvironment",
                "stateDirectory",
                "requestTopic",
                "resultTopic",
                "group",
                "schedule",
                "collection",
            },
            {"outgoingPeer"},
        )
        origin = text(row["origin"], 2048)
        url = urlsplit(origin)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError(
                "Configure an HTTP(S) origin without credentials or a path"
            )
        url.port  # Reject a malformed port before any connection.
        directory = Path(text(row["stateDirectory"], 4096))
        if not directory.is_absolute() or directory.is_symlink():
            raise ValueError(
                "State directory must be an absolute, unlinked private path"
            )
        plan = object_fields(
            row["collection"],
            {"mode"},
            {
                "targets",
                "ports",
                "timeoutSeconds",
                "concurrency",
                "observationsFile",
                "maxObservationAgeSeconds",
                "enabled",
            },
        )
        mode = plan["mode"]
        if mode not in ("tcp", "fixture"):
            raise ValueError("Collection mode must be tcp or fixture")
        observations = (
            Path(text(plan["observationsFile"], 4096))
            if "observationsFile" in plan
            else None
        )
        if observations is not None and not observations.is_absolute():
            observations = path.resolve().parent / observations
        if observations is not None and observations.is_symlink():
            raise ValueError("Observation input cannot be a symbolic link")
        if mode == "fixture":
            if observations is None or set(plan) != {"mode", "observationsFile"}:
                raise ValueError("Fixture mode requires only observationsFile")
            collection = CollectionPlan("fixture", (), (), 1.0, 1, observations, 0)
        else:
            enabled = plan.get("enabled", True)
            if type(enabled) is not bool:
                raise ValueError("TCP collection enabled must be a boolean")
            if not {"targets", "ports", "timeoutSeconds", "concurrency"} <= set(plan):
                raise ValueError(
                    "TCP collection requires explicit targets, ports and limits"
                )
            targets = tuple(
                str(ipaddress.ip_address(text(item)))
                for item in items(plan["targets"], 32)
            )
            ports = tuple(integer(item, 1, 65535) for item in items(plan["ports"], 16))
            timeout = plan["timeoutSeconds"]
            if (
                isinstance(timeout, bool)
                or not isinstance(timeout, (int, float))
                or not math.isfinite(timeout)
                or not 0.1 <= timeout <= 5
            ):
                raise ValueError("Timeout must be between 0.1 and 5 seconds")
            if (
                enabled
                and (not targets or not ports)
                or len(targets) * len(ports) > 256
                or len(set(targets)) != len(targets)
                or len(set(ports)) != len(ports)
            ):
                raise ValueError(
                    "Configure unique nonempty targets/ports, at most 256 probes"
                )
            if observations is None and "maxObservationAgeSeconds" in plan:
                raise ValueError("Observation age requires an observation file")
            age = (
                integer(plan.get("maxObservationAgeSeconds"), 1, 86400)
                if observations
                else 0
            )
            collection = CollectionPlan(
                "tcp",
                targets,
                ports,
                float(timeout),
                integer(plan["concurrency"], 1, 32),
                observations,
                age,
                enabled,
            )
        tokens = (
            identifier(row["tokenEnvironment"]),
            identifier(row["webTokenEnvironment"]),
        )
        if any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) for name in tokens):
            raise ValueError("Invalid credential environment name")
        request_topic, result_topic = (
            identifier(row["requestTopic"]),
            identifier(row["resultTopic"]),
        )
        if request_topic == result_topic or "schedule.due" in {
            request_topic,
            result_topic,
        }:
            raise ValueError("Request, result and schedule topics must be distinct")
        return cls(
            origin,
            identifier(row["project"]),
            identifier(row["collector"]),
            tokens[0],
            tokens[1],
            directory,
            request_topic,
            result_topic,
            identifier(row["group"]),
            identifier(row["schedule"]),
            collection,
            identifier(row["outgoingPeer"]) if "outgoingPeer" in row else None,
        )


@dataclass(frozen=True)
class ScanRequest:
    """Requests select the deployed collector, never addresses, ports, commands or paths."""

    request_id: str
    collector: str

    @classmethod
    def decode(cls, value: object) -> ScanRequest:
        row = object_fields(value, {"version", "request_id", "collector"})
        integer(row["version"], 1, 1)
        return cls(uuid(row["request_id"]), identifier(row["collector"]))


@dataclass(frozen=True)
class Evidence:
    scan_id: str
    source_event: str
    collector: str
    mode: Literal["tcp", "fixture"]
    scope: str
    started_at: str
    finished_at: str
    snapshot: Snapshot
    issues: tuple[str, ...]
    changes: tuple[str, ...]
    previous_revision: str | None

    def encode(self) -> str:
        return json.dumps(asdict(self), indent=2, allow_nan=False)

    @classmethod
    def decode(cls, value: object) -> Evidence:
        row = object_fields(
            value,
            {
                "scan_id",
                "source_event",
                "collector",
                "mode",
                "scope",
                "started_at",
                "finished_at",
                "snapshot",
                "issues",
                "changes",
                "previous_revision",
            },
        )
        mode = row["mode"]
        if mode not in ("tcp", "fixture"):
            raise ValueError("Invalid evidence mode")
        snapshot = object_fields(row["snapshot"], {"observed_at", "tcp", "dns"})
        snapshot["version"] = 1
        return cls(
            uuid(row["scan_id"]),
            text(row["source_event"]),
            identifier(row["collector"]),
            "tcp" if mode == "tcp" else "fixture",
            identifier(row["scope"]),
            timestamp(row["started_at"]),
            timestamp(row["finished_at"]),
            Snapshot.decode(snapshot),
            tuple(text(item) for item in items(row["issues"], 32)),
            tuple(text(item, 1024) for item in items(row["changes"], 512)),
            uuid(row["previous_revision"])
            if row["previous_revision"] is not None
            else None,
        )
