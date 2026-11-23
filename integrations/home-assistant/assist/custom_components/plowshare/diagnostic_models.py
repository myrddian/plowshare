"""Payload-free local observations; snapshots never probe or resume remote work."""

from dataclasses import dataclass
from datetime import datetime
from typing import Literal, Protocol, runtime_checkable

FailureCategory = Literal[
    "transport", "timeout", "io", "refusal", "verification", "storage"
]


@dataclass(frozen=True)
class CommunicationObservation:
    """Historical local SDK evidence, not a persistent connection/health lease."""

    last_connection_at: datetime | None
    last_successful_request_at: datetime | None
    last_failure_at: datetime | None
    last_failure_category: FailureCategory | None
    last_result_at: datetime | None
    last_result_status: str | None


@dataclass(frozen=True)
class CorrelationCounts:
    """Aggregate the bounded shared store without exposing routing identities."""

    retained: int
    pending: int
    uncertain: int
    preparing: int


@dataclass(frozen=True)
class GatewayDiagnostics:
    closed: bool
    active_owners: int
    unsettled_cleanup_owners: int
    communication: CommunicationObservation
    shared_correlation_store: CorrelationCounts


@runtime_checkable
class DiagnosticSource(Protocol):
    """Synchronous event-loop snapshot, without I/O, writes or SDK submissions."""

    def diagnostics_snapshot(self) -> GatewayDiagnostics: ...


@dataclass(frozen=True)
class RuntimeDiagnostics:
    attached: bool
    setup_finished: bool
    closing: bool
    owned_platforms: int
    conversation: GatewayDiagnostics | None
    task: GatewayDiagnostics | None
