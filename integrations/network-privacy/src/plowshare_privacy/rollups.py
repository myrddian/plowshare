"""Durable, deterministic daily and monthly summaries of settled collections."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Iterable
from dataclasses import asdict, dataclass, replace
from datetime import date, timedelta
from typing import Callable, Literal, Protocol
from uuid import NAMESPACE_URL, uuid5

from plowshare import CancelledRequest, Delivery, Refusal, TransportError
from plowshare.contracts import InformationAdmissionDto, InformationRevisionDto

from .contracts import (
    Configuration,
    household_day,
    household_zone,
    integer,
    items,
    load_json,
    local_time,
    object_fields,
    text,
    timestamp,
    utc_now,
    uuid,
)
from .journal import Receipt
from .private_files import replace_private

# The accumulator survives the much shorter raw-evidence window. Unpublished
# periods are never silently discarded to make room for a new period.
_MAX_DAYS = 100
_MAX_SCANS_PER_DAY = 4096
_MAX_LINES = 48
_MAX_LABEL = 240
_MAX_JOURNAL_BYTES = 12 * 1024 * 1024
_RETRY_SECONDS = 30
DayPhase = Literal["open", "submitting", "published", "hiding", "hidden"]
MonthPhase = Literal["submitting", "published", "readable"]


class RollupPort(Protocol):
    async def upload_document(
        self, name: str, body: str, request_id: str
    ) -> InformationAdmissionDto: ...
    async def sources(self, name: str) -> tuple[InformationRevisionDto, ...]: ...
    async def read_source(self, revision: str) -> str: ...
    async def source_status(self, revision: str) -> InformationRevisionDto: ...
    async def exclude_source(self, revision: str, request_id: str) -> None: ...


@dataclass(frozen=True)
class Repetitions:
    label: str
    count: int
    first: str
    last: str

    @classmethod
    def decode(cls, value: object) -> Repetitions:
        row = object_fields(value, {"label", "count", "first", "last"})
        return cls(
            text(row["label"], _MAX_LABEL),
            integer(row["count"], 1, _MAX_SCANS_PER_DAY),
            timestamp(row["first"]),
            timestamp(row["last"]),
        )


@dataclass(frozen=True)
class Day:
    day: date
    zone: str
    scans: tuple[str, ...] = ()
    first: str | None = None
    last: str | None = None
    fixture_count: int = 0
    baseline_count: int = 0
    change_count: int = 0
    issue_count: int = 0
    changes: tuple[Repetitions, ...] = ()
    issues: tuple[Repetitions, ...] = ()
    phase: DayPhase = "open"
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> Day:
        row = object_fields(
            value,
            {
                "day",
                "zone",
                "scans",
                "first",
                "last",
                "fixture_count",
                "baseline_count",
                "change_count",
                "issue_count",
                "changes",
                "issues",
                "phase",
                "revision",
            },
        )
        phases: tuple[DayPhase, ...] = (
            "open",
            "submitting",
            "published",
            "hiding",
            "hidden",
        )
        phase = next((phase for phase in phases if phase == row["phase"]), None)
        scans = tuple(uuid(scan) for scan in items(row["scans"], _MAX_SCANS_PER_DAY))
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        if (
            phase is None
            or not scans
            or len(set(scans)) != len(scans)
            or (phase in {"published", "hiding", "hidden"}) != (revision is not None)
        ):
            raise ValueError("Invalid daily rollup state")
        return cls(
            date.fromisoformat(text(row["day"], 10)),
            household_zone(row["zone"]),
            scans,
            timestamp(row["first"]),
            timestamp(row["last"]),
            integer(row["fixture_count"], 0, len(scans)),
            integer(row["baseline_count"], 0, len(scans)),
            integer(row["change_count"], 0, 1024 * len(scans)),
            integer(row["issue_count"], 0, 1024 * len(scans)),
            tuple(
                Repetitions.decode(item) for item in items(row["changes"], _MAX_LINES)
            ),
            tuple(
                Repetitions.decode(item) for item in items(row["issues"], _MAX_LINES)
            ),
            phase,
            revision,
        )

    def add(self, receipt: Receipt) -> Day:
        evidence = receipt.evidence
        if (
            evidence is None
            or self.phase != "open"
            or len(self.scans) >= _MAX_SCANS_PER_DAY
        ):
            raise ValueError("The daily rollup cannot accept another collection")
        when = evidence.finished_at
        if household_day(when, self.zone) != self.day:
            raise ValueError("Collection timestamp does not match its reporting day")
        changes = tuple(
            change for change in evidence.changes if not change.startswith("Baseline")
        )
        return replace(
            self,
            scans=(*self.scans, receipt.scan_id),
            first=min(self.first or when, when),
            last=max(self.last or when, when),
            fixture_count=self.fixture_count + int(evidence.mode == "fixture"),
            baseline_count=self.baseline_count + int(evidence.is_baseline),
            change_count=self.change_count + len(changes),
            issue_count=self.issue_count + len(evidence.issues),
            changes=_tally(self.changes, changes, when),
            issues=_tally(self.issues, evidence.issues, when),
        )


@dataclass(frozen=True)
class Month:
    month: date
    phase: MonthPhase = "submitting"
    revision: str | None = None

    @classmethod
    def decode(cls, value: object) -> Month:
        row = object_fields(value, {"month", "phase", "revision"})
        month = date.fromisoformat(text(row["month"], 10))
        revision = uuid(row["revision"]) if row["revision"] is not None else None
        phases: tuple[MonthPhase, ...] = ("submitting", "published", "readable")
        phase = next((phase for phase in phases if phase == row["phase"]), None)
        if (
            month.day != 1
            or phase is None
            or (phase == "submitting") != (revision is None)
        ):
            raise ValueError("Invalid monthly rollup state")
        return cls(month, phase, revision)


def _tally(
    existing: tuple[Repetitions, ...], values: tuple[str, ...], when: str
) -> tuple[Repetitions, ...]:
    counts = {item.label: item for item in existing}
    # Count each note at most once per collection. Totals in the report also
    # disclose the entries omitted from the bounded detail.
    labels = dict.fromkeys(" ".join(value[:_MAX_LABEL].split()) for value in values)
    for label in labels:
        if not label:
            continue
        previous = counts.get(label)
        if previous is not None:
            counts[label] = Repetitions(
                label,
                previous.count + 1,
                min(previous.first, when),
                max(previous.last, when),
            )
        elif len(counts) < _MAX_LINES:
            counts[label] = Repetitions(label, 1, when, when)
    return tuple(counts.values())


def _line(value: Repetitions, zone: str) -> str:
    label = re.sub(r"([\\`*_[\]<>~|&!])", lambda match: "\\" + match[0], value.label)
    first = local_time(value.first, zone)
    last = local_time(value.last, zone)
    times = first if first == last else f"{first} to {last}"
    return f"- {label} ({value.count} collection{'s' if value.count != 1 else ''}, {times})"


def _details(
    title: str, values: tuple[Repetitions, ...], total: int, zone: str
) -> list[str]:
    lines = [f"## {title}", ""]
    lines.extend(_line(value, zone) for value in values)
    if total == 0:
        lines.append("None recorded.")
    elif total > sum(value.count for value in values):
        lines.append(
            "The entry total includes additional or repeated notes omitted here."
        )
    return [*lines, ""]


def _daily(day: Day) -> str:
    assert day.first is not None and day.last is not None
    return "\n".join(
        [
            f"# Network Privacy Watch daily report · {day.day}",
            "",
            f"{len(day.scans)} settled collections in {day.zone}, from "
            f"{local_time(day.first, day.zone)} to {local_time(day.last, day.zone)}.",
            f"{day.change_count} comparison entries, {day.issue_count} coverage notes, "
            f"{day.baseline_count} baselines and {day.fixture_count} synthetic collections.",
            "",
            *_details("Changes", day.changes, day.change_count, day.zone),
            *_details("Coverage notes", day.issues, day.issue_count, day.zone),
            "Counts reflect the collections available to reporting. Gaps limit what the collector can establish.",
            "",
        ]
    )


def _monthly(month: date, days: tuple[Day, ...]) -> str:
    def combine(values: Iterable[Repetitions]) -> tuple[Repetitions, ...]:
        totals: dict[str, Repetitions] = {}
        for value in values:
            previous = totals.get(value.label)
            if previous is not None:
                totals[value.label] = Repetitions(
                    value.label,
                    previous.count + value.count,
                    min(previous.first, value.first),
                    max(previous.last, value.last),
                )
            elif len(totals) < _MAX_LINES:
                totals[value.label] = value
        return tuple(totals.values())

    changes = sum(day.change_count for day in days)
    issues = sum(day.issue_count for day in days)
    lines = [
        f"# Network Privacy Watch monthly rollup · {month:%Y-%m}",
        "",
        f"{sum(len(day.scans) for day in days)} settled collections across {len(days)} days, "
        f"with {changes} comparison entries and {issues} coverage notes.",
        "Days without a recorded collection are absent from this rollup.",
        "",
        *_details(
            "Changes",
            combine(value for day in days for value in day.changes),
            changes,
            days[-1].zone,
        ),
        *_details(
            "Coverage notes",
            combine(value for day in days for value in day.issues),
            issues,
            days[-1].zone,
        ),
        "## Collections by day",
        "",
    ]
    for day in days:
        lines.append(
            f"- {day.day} ({day.zone}): {len(day.scans)} collections, {day.change_count} comparison entries, "
            f"{day.issue_count} coverage notes, {day.baseline_count} baselines and "
            f"{day.fixture_count} synthetic collections. Daily source revision `{day.revision}`."
        )
    lines.append("")
    return "\n".join(lines)


def _next_month(month: date) -> date:
    return (month.replace(day=28) + timedelta(days=4)).replace(day=1)


class CollectionRollups:
    """One collector owns the ledger through the outer scan-journal lock.

    Capture precedes settling the local receipt, so raw-evidence retention can
    never remove an uncounted scan. Every remote mutation persists its intent
    first. A lost reply is reconciled by an exact read, never blindly replayed.
    """

    def __init__(self, config: Configuration, *, clock: Callable[[], str] = utc_now):
        self.path = config.state_directory.resolve() / "collection-rollups.json"
        self.project = config.project
        self.collector = config.collector
        # The calendar labels also identify the immutable remote documents.
        self.zone = config.zone
        self.clock = clock
        self.days: dict[date, Day] = {}
        self.months: dict[date, Month] = {}
        self.retired_through: date | None = None
        self.detail: str | None = None
        self.retry_at = 0.0
        self._last_period: tuple[date, bool] | None = None
        self.initialized = self.path.exists()
        if self.path.is_symlink():
            raise ValueError("The collection rollup journal cannot be linked")
        if self.initialized:
            row = object_fields(
                load_json(self.path, maximum_bytes=_MAX_JOURNAL_BYTES),
                {
                    "version",
                    "project",
                    "collector",
                    "days",
                    "months",
                    "retired_through",
                    "zone",
                },
            )
            if (row["version"], row["project"], row["collector"]) != (
                1,
                self.project,
                self.collector,
            ):
                raise ValueError("Foreign collection rollup journal")
            self.zone = household_zone(row["zone"])
            days = tuple(Day.decode(item) for item in items(row["days"], _MAX_DAYS))
            months = tuple(Month.decode(item) for item in items(row["months"], 5))
            self.days = {day.day: day for day in days}
            self.months = {month.month: month for month in months}
            if len(days) != len(self.days) or len(months) != len(self.months):
                raise ValueError("Duplicate rollup period")
            if row["retired_through"] is not None:
                self.retired_through = date.fromisoformat(
                    text(row["retired_through"], 10)
                )

    def seed(self, receipts: tuple[Receipt, ...]) -> None:
        """Adopt today's locally retained receipts on first use, once."""
        if self.initialized:
            return
        today = household_day(self.clock(), self.zone)
        day = Day(today, self.zone)
        for receipt in receipts:
            if (
                receipt.phase == "done"
                and receipt.evidence is not None
                and household_day(receipt.evidence.finished_at, self.zone) == today
            ):
                day = day.add(receipt)
        self._save(
            {today: day} if day.scans else {},
            self.months,
            self.retired_through,
        )
        self.initialized = True

    def record(self, receipt: Receipt) -> date:
        evidence = receipt.evidence
        if evidence is None:
            raise ValueError("A collection rollup needs settled evidence")
        for existing in self.days.values():
            if receipt.scan_id in existing.scans:
                return existing.day
        day = household_day(evidence.finished_at, self.zone)
        if day.replace(day=1) in self.months or (
            self.retired_through is not None and day <= self.retired_through
        ):
            raise ValueError("Collection time falls within a closed reporting period")
        updated = self.days.get(day, Day(day, self.zone)).add(receipt)
        self._put_day(updated)
        return day

    async def advance(self, port: RollupPort) -> None:
        """Perform at most one period transition under the collector's poll owner."""
        if time.monotonic() < self.retry_at:
            return
        try:
            self.detail = await self._advance(port)
            if self.detail:
                self.retry_at = time.monotonic() + _RETRY_SECONDS
        except (Refusal, TransportError, ValueError, OSError) as error:
            self.detail = f"Report publication or retention needs attention ({type(error).__name__})."
            self.retry_at = time.monotonic() + _RETRY_SECONDS

    async def _advance(self, port: RollupPort) -> str | None:
        today = household_day(self.clock(), self.zone)
        days = tuple(self.days[key] for key in sorted(self.days))
        groups: dict[date, list[Day]] = {}
        candidates: list[tuple[tuple[date, bool], Day | Month]] = []
        for day in days:
            groups.setdefault(day.day.replace(day=1), []).append(day)
            if day.phase == "submitting" or day.phase == "open" and day.day < today:
                candidates.append(((day.day, False), day))
        for period, group in groups.items():
            month = self.months.get(period)
            if (
                month is None
                and period < today.replace(day=1)
                and all(day.phase == "published" for day in group)
            ):
                month = Month(period)
            if month is not None:
                candidates.append(
                    ((_next_month(period) - timedelta(days=1), True), month)
                )
        if not candidates:
            return None

        # A failed or uncertain period keeps its state and its turn in the
        # rotation. Advance the cursor before IO so refusals cannot starve
        # unrelated daily publications, monthly publications or receipt reads.
        candidates.sort(key=lambda candidate: candidate[0])
        previous = self._last_period
        key, candidate = next(
            (entry for entry in candidates if previous is None or entry[0] > previous),
            candidates[0],
        )
        self._last_period = key
        if isinstance(candidate, Day):
            return await self._advance_day(port, candidate)
        month_days = tuple(groups[candidate.month])
        if candidate.month in self.months:
            return await self._advance_month(port, candidate, month_days)
        await self._publish_month(port, candidate, month_days)
        return None

    async def _advance_day(self, port: RollupPort, day: Day) -> str | None:
        if day.phase == "submitting":
            revision = await self._find(port, "daily", day.day.isoformat(), _daily(day))
            if revision is None:
                return f"The daily publication for {day.day} is awaiting an exact Plowshare receipt."
        else:
            self._put_day(replace(day, phase="submitting"))
            try:
                revision = await self._upload(
                    port, "daily", day.day.isoformat(), _daily(day)
                )
            except (Refusal, TransportError, CancelledRequest) as error:
                if (
                    isinstance(error, Refusal)
                    or error.delivery == Delivery.NOT_SUBMITTED
                ):
                    self._put_day(day)
                raise
        self._put_day(replace(day, phase="published", revision=revision))
        return None

    async def _publish_month(
        self, port: RollupPort, month: Month, days: tuple[Day, ...]
    ) -> None:
        self._put_month(month)
        try:
            revision = await self._upload(
                port, "monthly", f"{month.month:%Y-%m}", _monthly(month.month, days)
            )
        except (Refusal, TransportError, CancelledRequest) as error:
            if isinstance(error, Refusal) or error.delivery == Delivery.NOT_SUBMITTED:
                self._save(
                    self.days,
                    {
                        key: value
                        for key, value in self.months.items()
                        if key != month.month
                    },
                    self.retired_through,
                )
            raise
        self._put_month(replace(month, phase="published", revision=revision))

    async def _advance_month(
        self, port: RollupPort, month: Month, days: tuple[Day, ...]
    ) -> str | None:
        body = _monthly(month.month, days)
        if month.phase == "submitting":
            revision = await self._find(port, "monthly", f"{month.month:%Y-%m}", body)
            if revision is None:
                return "The monthly publication is awaiting an exact Plowshare receipt."
            self._put_month(replace(month, phase="published", revision=revision))
            return None
        if month.phase == "published":
            assert month.revision is not None
            if await port.read_source(month.revision) != body:
                raise ValueError(
                    "The readable monthly rollup differs from the published report"
                )
            self._put_month(replace(month, phase="readable"))
            return None

        for day in days:
            if day.phase == "hidden":
                continue
            assert day.revision is not None
            if day.phase == "hiding":
                status = await port.source_status(day.revision)
                if status.excluded is not True and status.availability != "excluded":
                    return "A daily exclusion is awaiting confirmation."
            else:
                self._put_day(replace(day, phase="hiding"))
                try:
                    await port.exclude_source(
                        day.revision, self._identity("hide", day.revision)
                    )
                except (Refusal, TransportError, CancelledRequest) as error:
                    if (
                        isinstance(error, Refusal)
                        or error.delivery == Delivery.NOT_SUBMITTED
                    ):
                        self._put_day(day)
                    raise
            self._put_day(replace(day, phase="hidden"))
            return None

        # The monthly document owns the durable history now. Keep just the
        # cutoff locally, so restarts never republish retired periods.
        self._save(
            {
                key: value
                for key, value in self.days.items()
                if value.day.replace(day=1) != month.month
            },
            {key: value for key, value in self.months.items() if key != month.month},
            max(
                _next_month(month.month) - timedelta(days=1),
                self.retired_through or date.min,
            ),
        )
        return None

    def _name(self, kind: str, period: str) -> str:
        label = "Daily report" if kind == "daily" else "Monthly rollup"
        return f"network-privacy/{self.collector}/rollups/{label} {period}.md"

    def _identity(self, kind: str, period: str) -> str:
        return str(
            uuid5(
                NAMESPACE_URL,
                f"network-privacy/{self.project}/{self.collector}/rollups/{kind}/{period}",
            )
        )

    async def _upload(self, port: RollupPort, kind: str, period: str, body: str) -> str:
        result = await port.upload_document(
            self._name(kind, period), body, self._identity(kind, period)
        )
        return uuid(result.revision)

    async def _find(
        self, port: RollupPort, kind: str, period: str, body: str
    ) -> str | None:
        name = self._name(kind, period)
        matches = [row for row in await port.sources(name) if row.source_name == name]
        if len(matches) != 1 or await port.read_source(matches[0].id) != body:
            return None
        return uuid(matches[0].id)

    def _put_day(self, day: Day) -> None:
        self._save(
            {**self.days, day.day: day},
            self.months,
            self.retired_through,
        )

    def _put_month(self, month: Month) -> None:
        self._save(
            self.days,
            {**self.months, month.month: month},
            self.retired_through,
        )

    def _save(
        self,
        days: dict[date, Day],
        months: dict[date, Month],
        retired_through: date | None,
    ) -> None:
        if len(days) > _MAX_DAYS or len(months) > 5:
            raise ValueError(
                "Resolve outstanding collection reports before the local rollup ledger fills"
            )
        encoded = (
            json.dumps(
                {
                    "version": 1,
                    "project": self.project,
                    "collector": self.collector,
                    "days": [asdict(day) for day in days.values()],
                    "months": [asdict(month) for month in months.values()],
                    "retired_through": retired_through,
                    "zone": self.zone,
                },
                default=lambda value: value.isoformat(),
                ensure_ascii=False,
            )
            + "\n"
        )
        if len(encoded.encode()) > _MAX_JOURNAL_BYTES:
            raise ValueError("The collection rollup journal exceeds its size limit")
        replace_private(self.path, encoded)
        self.days, self.months, self.retired_through = days, months, retired_through
