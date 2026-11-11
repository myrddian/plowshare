"""The start request and finish result of the ``assess_network_device`` orchestration.

This module is the typed seam between the collector and the Application script
``network-privacy-watch/orchestrations/assess_network_device.js``. The script
validates the same fields again; neither side trusts the other's JSON.

Start (``orchestration.start``): ``agent`` is ``CALLER``, ``definition`` is
``DEFINITION`` and ``request`` is ``AssessmentRequest.encode()``. The dossier
revision must already be retained (``dossier_port.DeviceDossiers.retain``). The
profile and linked issue revisions are taken from that retained dossier by the
script, so report inputs always match what the assessor read.

``previous`` is the newest revision of this device's assessment report, or None
for the first assessment. The server requires it to be a report with the same
name and makes the new report a revision of that resource. Omitting it after a
report exists would create a second resource with the same name.

Finish: a completed run's ``result`` is the JSON decoded by
``AssessmentResult.decode``. A failed run wrote no report; "not checked" by the
reviewer and invalid agent output both fail the run.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Literal

from .contracts import object_fields, parse_json, uuid
from .device_dossier import parse_device_id

DEFINITION = "assess_network_device"
CALLER = "privacy_coordinator"
REPORT_PREFIX = "Network privacy assessment / "
REQUEST_VERSION = 1

ConcernLevel = Literal["potential_breach", "worth_checking", "expected"]
ConcernVerdict = Literal["holds", "weakened", "refuted"]
ReviewOutcome = Literal["accepted", "needs_corrections"]
_LEVELS = frozenset({"potential_breach", "worth_checking", "expected"})
_VERDICTS = frozenset({"holds", "weakened", "refuted"})
_REVIEWS = frozenset({"accepted", "needs_corrections"})
# The script accepts exactly this UTC form; offsets would render a local date.
_UTC = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z")
_CONCERN_ID = re.compile(r"c[1-5]")


def report_name(device_id: str) -> str:
    """The stable report name; every reassessment is a revision of this report."""
    return REPORT_PREFIX + parse_device_id(device_id)


@dataclass(frozen=True)
class AssessmentRequest:
    device_id: str
    dossier: str
    previous: str | None
    requested_at: str

    def __post_init__(self) -> None:
        parse_device_id(self.device_id)
        uuid(self.dossier)
        if self.previous is not None and (uuid(self.previous) == self.dossier):
            raise ValueError("The previous assessment cannot be the dossier")
        if not isinstance(self.requested_at, str) or not _UTC.fullmatch(
            self.requested_at
        ):
            raise ValueError("requested_at must be a UTC timestamp ending in Z")

    def encode(self) -> str:
        return json.dumps(
            {
                "version": REQUEST_VERSION,
                "device_id": self.device_id,
                "dossier": self.dossier,
                "previous": self.previous,
                "requested_at": self.requested_at,
            },
            allow_nan=False,
            separators=(",", ":"),
        )


@dataclass(frozen=True)
class ConcernOutcome:
    """One reviewed concern. Refuted concerns are absent from the report body."""

    id: str
    level: ConcernLevel
    verdict: ConcernVerdict

    @classmethod
    def decode(cls, value: object) -> ConcernOutcome:
        concern = object_fields(value, {"id", "level", "verdict"})
        identity, level, verdict = concern["id"], concern["level"], concern["verdict"]
        if (
            not isinstance(identity, str)
            or not _CONCERN_ID.fullmatch(identity)
            or level not in _LEVELS
            or verdict not in _VERDICTS
        ):
            raise ValueError("Invalid assessment concern")
        return cls(identity, level, verdict)  # type: ignore[arg-type]


def concern_outcomes(value: object) -> tuple[ConcernOutcome, ...]:
    """At most five reviewed concerns with distinct IDs."""
    if not isinstance(value, list) or len(value) > 5:
        raise ValueError("Invalid assessment concerns")
    concerns = tuple(ConcernOutcome.decode(item) for item in value)
    if len({c.id for c in concerns}) != len(concerns):
        raise ValueError("Duplicate assessment concern")
    return concerns


@dataclass(frozen=True)
class AssessmentResult:
    device_id: str
    dossier: str
    report_revision: str
    report_resource: str
    review: ReviewOutcome
    corrected: bool
    concerns: tuple[ConcernOutcome, ...]

    @classmethod
    def decode(cls, text: str) -> AssessmentResult:
        row = object_fields(
            parse_json(text, maximum_bytes=16384),
            {
                "version",
                "device_id",
                "dossier",
                "report",
                "review",
                "corrected",
                "concerns",
            },
        )
        if type(row["version"]) is not int or row["version"] != REQUEST_VERSION:
            raise ValueError("Unsupported assessment result version")
        report = object_fields(row["report"], {"revision", "resource"})
        review, corrected = row["review"], row["corrected"]
        if review not in _REVIEWS or type(corrected) is not bool:
            raise ValueError("Invalid assessment review outcome")
        concerns = concern_outcomes(row["concerns"])
        return cls(
            parse_device_id(row["device_id"]),
            uuid(row["dossier"]),
            uuid(report["revision"]),
            uuid(report["resource"]),
            review,  # type: ignore[arg-type]
            corrected,
            concerns,
        )

    def require(self, request: AssessmentRequest) -> None:
        """Refuse a result that answers a different device or dossier revision."""
        if (self.device_id, self.dossier) != (request.device_id, request.dossier):
            raise ValueError("Assessment result belongs to another request")
