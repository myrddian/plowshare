"""Named external tools over public Relay operations.

Declarations are deployment artifacts, not grants. The operator installs the
exported bindings and explicitly grants tool names to agents. A provider owns
its external effects and supplies a durable ToolJournal; it never runs an agent
or creates a second Plowshare job runtime.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from types import MappingProxyType
from typing import Awaitable, Callable, Literal, Mapping, Protocol
from uuid import UUID, uuid4

from . import contracts as dto
from .client import Client, Delivery, TransportError

Scalar = str | int | float | bool
Arguments = Mapping[str, Scalar]
ParameterType = Literal["STRING", "NUMBER", "INTEGER", "BOOLEAN"]
ResultState = Literal["COMPLETED", "REJECTED", "UNKNOWN"]
Phase = Literal["executing", "ready", "publishing", "done"]
_VERSION = "plowshare-tool/1"


class ToolAttention(RuntimeError):
    """Provider needs retained inspection before another effect or acknowledgement."""


def _identity(value: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value.encode("utf-16-le")) // 2 > 256
        or value != value.strip()
        or any(
            ord(c) < 32 or 127 <= ord(c) <= 159 or c in "\u2028\u2029" for c in value
        )
    ):
        raise ValueError("Invalid tool identity")
    return value


def _text(value: str, maximum: int, *, nonblank: bool = False) -> str:
    if (
        not isinstance(value, str)
        or len(value.encode("utf-16-le")) // 2 > maximum
        or "\0" in value
        or nonblank
        and not value.strip()
    ):
        raise ValueError("Invalid tool text")
    return value


@dataclass(frozen=True)
class Parameter:
    name: str
    type: ParameterType
    description: str = ""
    required: bool = True

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]{0,63}", self.name):
            raise ValueError("Invalid parameter name")
        if self.type not in {"STRING", "NUMBER", "INTEGER", "BOOLEAN"}:
            raise ValueError("Unsupported parameter type")
        _text(self.description, 4096)
        if type(self.required) is not bool:
            raise ValueError("Parameter required must be boolean")


@dataclass(frozen=True)
class ToolDeclaration:
    name: str
    description: str
    parameters: tuple[Parameter, ...] = ()
    timeout_seconds: int = 30

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", self.name):
            raise ValueError("Invalid tool name")
        # Topic segments cannot contain consecutive or trailing separators.
        if not re.fullmatch(r"[a-z][a-z0-9]*(?:_[a-z0-9]+)*", self.name):
            raise ValueError("Tool name cannot contain empty topic segments")
        _text(self.description, 4096, nonblank=True)
        if (
            not isinstance(self.parameters, tuple)
            or len(self.parameters) > 32
            or any(not isinstance(p, Parameter) for p in self.parameters)
            or len({p.name for p in self.parameters}) != len(self.parameters)
            or type(self.timeout_seconds) is not int
            or not 1 <= self.timeout_seconds <= 300
        ):
            raise ValueError("Invalid tool declaration")

    def validate(self, arguments: Arguments) -> None:
        if len(arguments) > 32 or set(arguments) - {p.name for p in self.parameters}:
            raise ValueError("Unknown tool argument")
        for p in self.parameters:
            if p.name not in arguments:
                if p.required:
                    raise ValueError("Required tool argument missing")
                continue
            value = arguments[p.name]
            numeric = type(value) in {int, float}
            if numeric:
                if isinstance(value, float) and not math.isfinite(value):
                    raise ValueError("Invalid tool number")
                decimal = Decimal(str(value))
                exponent = decimal.normalize().as_tuple().exponent
                if (
                    not isinstance(exponent, int)
                    or abs(decimal) > 9007199254740991
                    or exponent < -18
                    or Decimal(str(float(value))) != decimal
                ):
                    raise ValueError("Tool number exceeds precision or scale")
            valid = (
                p.type == "STRING"
                and type(value) is str
                or p.type == "BOOLEAN"
                and type(value) is bool
                or p.type == "NUMBER"
                and numeric
                or p.type == "INTEGER"
                and numeric
                and Decimal(str(value)) == Decimal(str(value)).to_integral_value()
            )
            if not valid:
                raise ValueError("Tool argument type mismatch")
            if isinstance(value, str):
                _text(value, 4096)


@dataclass(frozen=True)
class ToolCall:
    invocation_id: str
    project: str
    provider: str
    tool: str
    account: str
    run: str
    call: str
    deadline: str
    arguments: Arguments


@dataclass(frozen=True)
class ToolResult:
    state: ResultState
    text: str

    def __post_init__(self) -> None:
        if self.state not in {"COMPLETED", "REJECTED", "UNKNOWN"}:
            raise ValueError("Invalid tool result state")
        _text(self.text, 16384, nonblank=True)


@dataclass(frozen=True)
class RegisteredTool:
    declaration: ToolDeclaration
    handler: Callable[[ToolCall], Awaitable[ToolResult]]


@dataclass(frozen=True)
class ToolReceipt:
    invocation_id: str
    request: str
    phase: Phase
    result: ToolResult | None
    occurred_at: str | None = None


class ToolJournal(Protocol):
    """Exclusive provider-owned store. Writes must be durable before returning.

    One owner may execute handlers. Store identity must bind project, provider,
    owning account and declaration revision. Interrupted execution intent is
    UNKNOWN, never a license to run a handler again. Keep receipts beyond broker
    retention and refuse conflicting identities or a full store.
    """

    @property
    def identity(self) -> str: ...
    def all(self) -> tuple[ToolReceipt, ...]: ...
    def save(self, receipt: ToolReceipt) -> None: ...


def deployment_config(
    project: str, provider: str, account: str, tools: tuple[ToolDeclaration, ...]
) -> str:
    """Export operator-reviewable Spring configuration; does not contact a server."""
    _identity(project)
    _identity(account)
    _provider(provider)
    _declarations(tools)
    bindings = [
        {
            "project": project,
            "provider": provider,
            "account": account,
            "name": t.name,
            "description": t.description,
            "parameters": [
                {
                    "name": p.name,
                    "type": p.type,
                    "description": p.description,
                    "required": p.required,
                }
                for p in t.parameters
            ],
            "timeoutSeconds": t.timeout_seconds,
        }
        for t in tools
    ]
    return json.dumps(
        {"plowshare": {"relay": {"tools": {"bindings": bindings}}}}, indent=2
    )


def _provider(value: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", value) or len(value) > 48:
        raise ValueError("Invalid provider name")


def _declarations(tools: tuple[ToolDeclaration, ...]) -> None:
    if not tools or len(tools) > 256 or len({t.name for t in tools}) != len(tools):
        raise ValueError("Invalid or duplicate tool declarations")


def result_request_id(invocation_id: str) -> str:
    _uuid(invocation_id)
    return str(
        UUID(hashlib.sha256(("tool-result:" + invocation_id).encode()).hexdigest()[:32])
    )


def _uuid(value: str) -> str:
    if str(UUID(value)) != value:
        raise ValueError("Tool identity must be a canonical UUID")
    return value


def _time(value: str) -> datetime:
    if not value.endswith("Z"):
        raise ValueError("Tool timestamp must be UTC")
    return datetime.fromisoformat(value[:-1] + "+00:00")


def _object(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or any(not isinstance(k, str) for k in value):
        raise ValueError("Tool envelope must be an object")
    return {str(k): v for k, v in value.items()}


def _pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    row: dict[str, object] = {}
    for key, value in pairs:
        if key in row:
            raise ValueError("Duplicate tool field")
        row[key] = value
    return row


def _portable_float(source: str) -> float:
    value = float(source)
    if not math.isfinite(value) or Decimal(str(value)) != Decimal(source):
        raise ValueError("Tool number cannot round-trip across SDK languages")
    return value


def _decode(source: str) -> ToolCall:
    if len(source.encode("utf-16-le")) // 2 > 32768:
        raise ValueError("Tool envelope exceeds bound")
    row = _object(
        json.loads(source, object_pairs_hook=_pairs, parse_float=_portable_float)
    )
    required = {
        "schema",
        "invocationId",
        "project",
        "provider",
        "tool",
        "account",
        "run",
        "call",
        "deadline",
        "arguments",
    }
    if set(row) != required or row["schema"] != _VERSION:
        raise ValueError("Invalid tool envelope fields or version")
    fields: dict[str, str] = {}
    for key in required - {"arguments", "schema"}:
        value = row[key]
        if not isinstance(value, str):
            raise ValueError("Invalid tool envelope field type")
        fields[key] = _identity(value)
    _provider(fields["provider"])
    ToolDeclaration(fields["tool"], "Envelope validation")
    _uuid(fields["invocationId"])
    _time(fields["deadline"])
    args: dict[str, Scalar] = {}
    for key, value in _object(row["arguments"]).items():
        if not isinstance(value, (str, int, float, bool)):
            raise ValueError("Tool arguments must be declared scalars")
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]{0,63}", key):
            raise ValueError("Invalid tool argument name")
        kind: ParameterType = (
            "STRING"
            if isinstance(value, str)
            else "BOOLEAN"
            if type(value) is bool
            else "NUMBER"
        )
        ToolDeclaration(
            "validate", "Validate scalar", (Parameter(key, kind),)
        ).validate({key: value})
        args[key] = value
    if len(args) > 32:
        raise ValueError("Too many tool arguments")
    return ToolCall(
        fields["invocationId"],
        fields["project"],
        fields["provider"],
        fields["tool"],
        fields["account"],
        fields["run"],
        fields["call"],
        fields["deadline"],
        MappingProxyType(args),
    )


class ToolProvider:
    """Serial provider façade. Polling never re-executes retained invocation intent."""

    def __init__(
        self,
        client: Client,
        *,
        project: str,
        provider: str,
        account: str,
        tools: tuple[RegisteredTool, ...],
        journal: ToolJournal,
    ):
        self._client = client
        self.project = _identity(project)
        _provider(provider)
        self.provider = provider
        _declarations(tuple(t.declaration for t in tools))
        self._tools = {t.declaration.name: t for t in tools}
        config = deployment_config(
            project, provider, account, tuple(t.declaration for t in tools)
        )
        if journal.identity != hashlib.sha256(config.encode()).hexdigest():
            raise ValueError("Tool journal belongs to another deployment binding")
        self._publisher = (
            "sdk:" + hashlib.sha256(_identity(account).encode()).hexdigest()
        )
        self._journal = journal
        self._consumer = str(uuid4())
        self._lock = asyncio.Lock()

    async def poll(self) -> int:
        async with self._lock:
            # Recovery precedes new intake. A lost result write must be reconciled
            # explicitly, and blocks new effects until the operator resolves it.
            for retained_receipt in self._receipts():
                recovery = retained_receipt
                if recovery.phase == "publishing":
                    raise ToolAttention(
                        "Uncertain tool result publication; use reconcile before polling"
                    )
                if recovery.phase == "executing":
                    recovery = ToolReceipt(
                        recovery.invocation_id,
                        recovery.request,
                        "ready",
                        ToolResult(
                            "UNKNOWN",
                            "Provider restarted after execution intent; external effects may have occurred.",
                        ),
                    )
                    self._journal.save(recovery)
                if recovery.phase == "ready":
                    await self._publish(recovery)
            count = 0
            for name, registered in self._tools.items():
                topic = self._topic(name, "request")
                batch = (
                    await self._client.request(
                        dto.RelayConsumeRequest(
                            project=self.project,
                            topic=topic,
                            group="tool-provider",
                            consumer_id=self._consumer,
                            start="OLDEST_RETAINED",
                            limit=1,
                            wait_ms=0,
                        )
                    )
                ).require_payload()
                if batch.status == "GAP":
                    raise ToolAttention(
                        "Tool request history expired; inspect the gap before acknowledging"
                    )
                if batch.status != "DATA":
                    continue
                if (batch.project, batch.topic, batch.group, batch.consumer_id) != (
                    self.project,
                    topic,
                    "tool-provider",
                    self._consumer,
                ):
                    raise ValueError("Tool batch belongs to another provider scope")
                event = batch.events[0]
                if (
                    event.publisher != "tool-runtime"
                    or event.payload.kind != "TEXT"
                    or event.payload.text is None
                ):
                    raise ValueError("Tool request publisher or payload is invalid")
                call = _decode(event.payload.text)
                if (call.project, call.provider, call.tool, call.invocation_id) != (
                    self.project,
                    self.provider,
                    name,
                    event.event_id,
                ) or event.correlation_id != call.invocation_id:
                    raise ValueError("Tool request belongs to another binding")
                registered.declaration.validate(call.arguments)
                receipt = next(
                    (
                        r
                        for r in self._receipts()
                        if r.invocation_id == call.invocation_id
                    ),
                    None,
                )
                fresh = receipt is None
                if receipt is not None and receipt.request != event.payload.text:
                    raise ValueError("Conflicting tool invocation identity")
                if receipt is None:
                    receipt = ToolReceipt(
                        call.invocation_id, event.payload.text, "executing", None
                    )
                    self._journal.save(receipt)
                if batch.batch_id is None or batch.fence is None:
                    raise ValueError("Tool batch has no acknowledgement authority")
                (
                    await self._client.request(
                        dto.RelayAckRequest(
                            project=self.project,
                            topic=topic,
                            group="tool-provider",
                            consumer_id=self._consumer,
                            batch_id=batch.batch_id,
                            fence=batch.fence,
                        )
                    )
                ).require_payload()
                if fresh:
                    remaining = (
                        _time(call.deadline) - datetime.now(timezone.utc)
                    ).total_seconds()
                    if remaining <= 0:
                        result = ToolResult(
                            "REJECTED",
                            "Tool deadline expired before execution; no handler ran.",
                        )
                    else:
                        try:
                            result = await asyncio.wait_for(
                                registered.handler(call),
                                timeout=min(
                                    remaining, registered.declaration.timeout_seconds
                                ),
                            )
                            if not isinstance(result, ToolResult):
                                raise ValueError("Handler must return a ToolResult")
                            self._result_text(call, result)
                        except asyncio.TimeoutError:
                            result = ToolResult(
                                "UNKNOWN",
                                "Handler deadline expired; external effects may have occurred.",
                            )
                        except Exception:
                            # Do not expose exception text, credentials, or private arguments.
                            # Exceptions after intent cannot prove absence of external effects.
                            result = ToolResult(
                                "UNKNOWN",
                                "Handler failed after execution intent; external effects may have occurred.",
                            )
                    receipt = ToolReceipt(
                        call.invocation_id, event.payload.text, "ready", result
                    )
                    self._journal.save(receipt)
                if receipt.phase == "ready":
                    await self._publish(receipt)
                count += 1
            return count

    async def reconcile(self) -> int:
        """Read-only settlement of exact retained results. Never publishes or invokes handlers."""
        async with self._lock:
            count = 0
            for receipt in self._receipts():
                if receipt.phase != "publishing":
                    continue
                call = _decode(receipt.request)
                after = "0"
                for _ in range(100):
                    page = (
                        await self._client.request(
                            dto.RelayLogRequest(
                                selection=dto.RelayLogPayloadVariant1Dto(
                                    project=self.project,
                                    system=False,
                                    topic=self._topic(call.tool, "result"),
                                    after=after,
                                    limit=100,
                                )
                            )
                        )
                    ).require_payload()
                    matched = next(
                        (
                            e
                            for e in page.events
                            if e.event_id == result_request_id(call.invocation_id)
                        ),
                        None,
                    )
                    if matched is not None:
                        if (
                            matched.payload.kind != "TEXT"
                            or matched.payload.text
                            != self._result_text(call, receipt.result)
                            or matched.correlation_id != call.invocation_id
                            or matched.causation_id != call.invocation_id
                            or receipt.occurred_at is None
                            or _time(matched.occurred_at) != _time(receipt.occurred_at)
                            or matched.publisher != self._publisher
                        ):
                            raise ValueError(
                                "Retained tool result conflicts with the provider receipt"
                            )
                        self._journal.save(
                            ToolReceipt(
                                receipt.invocation_id,
                                receipt.request,
                                "done",
                                receipt.result,
                                receipt.occurred_at,
                            )
                        )
                        count += 1
                        break
                    if not page.events or page.events[-1].position == after:
                        break
                    after = page.events[-1].position
                else:
                    raise ToolAttention(
                        "Tool result reconciliation exceeded the retained read bound"
                    )
            return count

    def _receipts(self) -> tuple[ToolReceipt, ...]:
        rows = self._journal.all()
        if len(rows) > 1000 or len({row.invocation_id for row in rows}) != len(rows):
            raise ValueError("Invalid tool journal size or identities")
        for receipt in rows:
            call = _decode(receipt.request)
            registered = next(
                (
                    tool
                    for tool in self._tools.values()
                    if tool.declaration.name == call.tool
                ),
                None,
            )
            if (
                call.invocation_id != receipt.invocation_id
                or call.project != self.project
                or call.provider != self.provider
                or registered is None
            ):
                raise ValueError("Foreign tool receipt")
            registered.declaration.validate(call.arguments)
            if (
                receipt.phase not in {"executing", "ready", "publishing", "done"}
                or (receipt.phase == "executing") != (receipt.result is None)
                or (receipt.phase in {"publishing", "done"})
                != (receipt.occurred_at is not None)
            ):
                raise ValueError("Invalid tool receipt phase")
            if receipt.result is not None:
                self._result_text(call, receipt.result)
            if receipt.occurred_at is not None:
                _time(receipt.occurred_at)
        return rows

    def _topic(self, name: str, suffix: str) -> str:
        return f"tool.{self.provider}.{name}.{suffix}"

    @staticmethod
    def _result_text(call: ToolCall, result: ToolResult | None) -> str:
        if result is None:
            raise ValueError("Tool result receipt is missing")
        text = json.dumps(
            {
                "schema": _VERSION,
                "invocationId": call.invocation_id,
                "project": call.project,
                "provider": call.provider,
                "tool": call.tool,
                "state": result.state,
                "text": result.text,
            },
            ensure_ascii=True,
            separators=(",", ":"),
        )
        if len(text) > 32768:
            raise ValueError("Encoded tool result exceeds envelope bound")
        return text

    async def _publish(self, receipt: ToolReceipt) -> None:
        call = _decode(receipt.request)
        occurred = (
            datetime.now(timezone.utc)
            .isoformat(timespec="microseconds")
            .replace("+00:00", "Z")
        )
        intent = ToolReceipt(
            receipt.invocation_id,
            receipt.request,
            "publishing",
            receipt.result,
            occurred,
        )
        self._journal.save(intent)
        try:
            reply = (
                await self._client.request(
                    dto.RelayPublishRequest(
                        project=self.project,
                        topic=self._topic(call.tool, "result"),
                        request_id=result_request_id(call.invocation_id),
                        occurred_at=occurred,
                        correlation_id=call.invocation_id,
                        parent_topic=self._topic(call.tool, "request"),
                        parent_event_id=call.invocation_id,
                        text=self._result_text(call, receipt.result),
                    )
                )
            ).require_payload()
            if (reply.project, reply.topic, reply.request_id) != (
                self.project,
                self._topic(call.tool, "result"),
                result_request_id(call.invocation_id),
            ):
                raise ValueError(
                    "Tool publication receipt belongs to another invocation"
                )
        except TransportError as error:
            if error.delivery == Delivery.NOT_SUBMITTED:
                self._journal.save(receipt)
            raise
        self._journal.save(
            ToolReceipt(
                receipt.invocation_id, receipt.request, "done", receipt.result, occurred
            )
        )
