"""Device assessment runs and report decisions over the public Plowshare SDK.

``AssessmentPort`` is the narrow contract ``device_assessments`` consumes; the
SDK implementation below is wired in ``cli.py``. Every call is project-scoped
to the collector's configured project and runs under its service token.

Delivery rules, which the service relies on:

* ``start`` and ``finalise`` are mutations with a caller-chosen request UUID.
  A ``Refusal`` means the server decided; a ``TransportError`` whose delivery
  is not ``NOT_SUBMITTED`` means the outcome is unknown. Neither is retried
  here, and the service never resends after an unknown outcome.
* ``receipt`` and ``report_state`` are reads that settle those unknowns: the
  account-owned orchestration receipt for a start (GAPS.md, "Project-scoped
  orchestration start receipts") and the report's status for a finalise.
* ``report_state`` also says whether Plowshare has finished processing the
  revision. Plowshare refuses to finalise a report before that, so Accept
  waits for it rather than spending a refused request.
* ``newest_report`` reads the project catalogue, the record of which report
  revisions exist, so a reassessment always names the newest one as feedback.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable, Literal, Protocol

from plowshare import Client
from plowshare.contracts import (
    InformationFilterDto,
    InformationFinaliseRequest,
    InformationListRequest,
    InformationRevisionDto,
    InformationStatusRequest,
    OrchestrationReceiptRequest,
    OrchestrationStartRequest,
    OrchestrationStatusRequest,
    SelectionDtoScopeVariant3Dto,
)

from .assessment_request import CALLER, DEFINITION, AssessmentRequest, report_name
from .contracts import Configuration, identifier, uuid
from .device_dossier import parse_device_id
from .profile_port import RetainedSourceReader

ReportStatus = Literal["draft", "final", "superseded"]
_STATUSES = frozenset({"draft", "final", "superseded"})
# The stages Plowshare's finalise requires to be ready in the revision's current
# generation. Tagging (autoTag, tagGroups) is not among them.
_CONTENT_STAGES = frozenset(
    {"extract", "derive", "embed", "summarise", "summary_embed"}
)
# Each reassessment adds one revision; 1,000 is far beyond normal history.
MAX_PAGES = 10
PAGE = 100


@dataclass(frozen=True)
class ReportState:
    """A report revision's status and whether Plowshare can finalise it yet."""

    status: ReportStatus
    # False while the revision's content stages are still being processed.
    processed: bool


@dataclass(frozen=True)
class AssessmentRun:
    """The retained run state and, once finished, its unvalidated result text."""

    state: str
    result: str | None = None


class AssessmentPort(Protocol):
    async def newest_report(self, device_id: str) -> str | None:
        """The newest revision of this device's assessment report, or None."""
        ...

    async def start(self, request_id: str, request: AssessmentRequest) -> str:
        """Admit the run; returns its opaque run ID."""
        ...

    async def receipt(self, request_id: str) -> str:
        """Read the admission receipt for ``request_id``; returns its run ID."""
        ...

    async def outcome(self, run_id: str) -> AssessmentRun: ...

    async def finalise(self, request_id: str, revision: str) -> None:
        """Make the draft report revision final; refusals propagate unchanged."""
        ...

    async def report_state(self, device_id: str, revision: str) -> ReportState: ...

    async def read_report(self, device_id: str, revision: str) -> str: ...


class SdkAssessmentPort:
    """``AssessmentPort`` in the configured project only.

    ``client`` and ``reader`` are cells: operator recovery replaces the SDK
    connection, and each call uses the current one.
    """

    def __init__(
        self,
        client: Callable[[], Client],
        config: Configuration,
        reader: Callable[[], RetainedSourceReader],
    ):
        self.client, self.reader, self.project = client, reader, config.project
        self.scope = SelectionDtoScopeVariant3Dto(
            kind="project", project=config.project, include_global=False
        )

    async def newest_report(self, device_id: str) -> str | None:
        name = report_name(device_id)
        found: list[tuple[int, str, str]] = []
        for page in range(MAX_PAGES):
            rows = (
                await self.client().request(
                    InformationListRequest(
                        scope=self.scope,
                        kind="report",
                        filter=InformationFilterDto(search=name),
                        limit=PAGE,
                        offset=page * PAGE,
                    ),
                    timeout=15,
                )
            ).require_payload()
            for row in rows:
                if not isinstance(row.source_name, str):
                    # Search is lexical. A row without its name cannot be ruled
                    # out, and missing ours would start a second resource.
                    raise ValueError("Report catalogue entry lacks its name")
                if row.source_name != name:
                    continue
                if row.kind != "report" or not isinstance(row.resource_id, str):
                    raise ValueError("Foreign assessment report catalogue entry")
                found.append((_ordinal(row), uuid(row.id), uuid(row.resource_id)))
            if len(rows) < PAGE:
                break
        else:
            raise ValueError(
                "Assessment report history exceeds its read bound; inspect the project's reports"
            )
        if not found:
            return None
        if len({resource for _, _, resource in found}) != 1:
            raise ValueError(
                "Two report resources share this device's assessment name; inspect the project's reports"
            )
        if len({ordinal for ordinal, _, _ in found}) != len(found):
            raise ValueError("Assessment report history has duplicate ordinals")
        return max(found)[1]

    async def start(self, request_id: str, request: AssessmentRequest) -> str:
        admission = (
            await self.client().request(
                OrchestrationStartRequest(
                    project=self.project,
                    agent=CALLER,
                    definition=DEFINITION,
                    request=request.encode(),
                    request_id=uuid(request_id),
                ),
                timeout=15,
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign device assessment admission")
        return identifier(admission.id)

    async def receipt(self, request_id: str) -> str:
        admission = (
            await self.client().request(
                OrchestrationReceiptRequest(request_id=uuid(request_id)), timeout=15
            )
        ).require_payload()
        if admission.request_id != request_id:
            raise ValueError("Foreign device assessment receipt")
        return identifier(admission.id)

    async def outcome(self, run_id: str) -> AssessmentRun:
        view = (
            (
                await self.client().request(
                    OrchestrationStatusRequest(id=identifier(run_id)), timeout=15
                )
            )
            .require_payload()
            .orchestration
        )
        if (view.id, view.project, view.definition, view.caller_agent) != (
            run_id,
            self.project,
            DEFINITION,
            CALLER,
        ):
            raise ValueError("Foreign device assessment run")
        return AssessmentRun(view.state, view.result)

    async def finalise(self, request_id: str, revision: str) -> None:
        result = (
            await self.client().request(
                InformationFinaliseRequest(
                    scope=self.scope,
                    request_id=uuid(request_id),
                    revision=uuid(revision),
                ),
                timeout=15,
            )
        ).require_payload()
        if result.finalised is not True:
            raise ValueError("Finalise reply did not confirm the report")

    async def report_state(self, device_id: str, revision: str) -> ReportState:
        status = (
            await self.client().request(
                InformationStatusRequest(scope=self.scope, revision=uuid(revision)),
                timeout=15,
            )
        ).require_payload()
        if (
            not isinstance(status, InformationRevisionDto)
            or status.id != revision
            or status.kind != "report"
            or status.source_name != report_name(parse_device_id(device_id))
            or status.report_status not in _STATUSES
        ):
            raise ValueError("Assessment report status is foreign or incomplete")
        return ReportState(status.report_status, _processed(status))

    async def read_report(self, device_id: str, revision: str) -> str:
        parse_device_id(device_id)
        return await self.reader().read_source(uuid(revision))


def _processed(status: InformationRevisionDto) -> bool:
    """Whether every content stage of the current generation is ready.

    The current generation is the revision's own, or the newest among its
    steps when the status omits it. A status without steps cannot show that
    processing is pending; finalise then decides, and a refusal while
    processing is reported as such by the service.
    """
    steps = status.steps
    if not isinstance(steps, tuple):
        return True
    generation: float | None = (
        status.generation
        if isinstance(status.generation, (int, float))
        and not isinstance(status.generation, bool)
        else max((step.generation for step in steps), default=None)
    )
    ready = {
        step.stage
        for step in steps
        if step.generation == generation and step.state == "ready"
    }
    return _CONTENT_STAGES <= ready


def _ordinal(row: InformationRevisionDto) -> int:
    value = row.ordinal
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value % 1
        or not 1 <= value <= 1000000
    ):
        raise ValueError("Report revision ordinal must be a whole number")
    return int(value)
