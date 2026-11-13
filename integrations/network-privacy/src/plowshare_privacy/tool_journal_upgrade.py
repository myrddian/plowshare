"""A controlled additive metadata upgrade through public SDK journal interfaces.

Never reinterpret a foreign binding or erase old receipts. Both SDK stores retain
their own exact configuration fence. The legacy store stays open while the new
provider runs. The collector's independent receipt lock excludes old binaries.
"""

from __future__ import annotations

import hashlib
import json
from contextlib import ExitStack, contextmanager
from dataclasses import asdict, replace
from pathlib import Path
from typing import Iterator

from plowshare.tool_journal import SqliteToolJournal
from plowshare.tools import Parameter, ToolDeclaration, ToolReceipt, deployment_config

from .contracts import load_json, object_fields
from .native_tools import DECLARATIONS
from .private_files import replace_private
from .tools import DEFINITIONS

# These exact v1 descriptors are the historical SDK binding, not an alternate
# registry. Only the reviewed paged-read additions are allowed to migrate it.
LEGACY_DECLARATIONS = (
    ToolDeclaration(
        "network_scope",
        "Read the configured collector scope and probe limits.",
        (),
        timeout_seconds=30,
    ),
    ToolDeclaration(
        "network_scan",
        "Request a scan through Relay using a caller-retained UUID. Admission is not completion.",
        (),
        timeout_seconds=30,
    ),
    ToolDeclaration(
        "network_scan_status",
        "Read a manual request's local publication and collection receipts.",
        (
            Parameter(
                "request_id",
                "STRING",
                "Canonical UUID of the retained request or scan.",
            ),
        ),
        timeout_seconds=30,
    ),
    ToolDeclaration(
        "network_scan_list",
        "List the latest 50 local collection receipts.",
        (),
        timeout_seconds=30,
    ),
    ToolDeclaration(
        "network_evidence",
        "Read a collection's observations, comparison, gaps and retained revision.",
        (
            Parameter(
                "scan_id", "STRING", "Canonical UUID of the retained request or scan."
            ),
        ),
        timeout_seconds=30,
    ),
    ToolDeclaration(
        "network_destinations",
        "Read observed DNS destinations from a collection; queries do not prove transmitted content.",
        (
            Parameter(
                "scan_id", "STRING", "Canonical UUID of the retained request or scan."
            ),
        ),
        timeout_seconds=30,
    ),
)


# Tools added after the paged binding. Only read tools may be added this way;
# their history starts empty, so nothing about earlier receipts changes.
DEVICE_TOOLS = frozenset({"network_devices", "network_device"})
# The assistant's request and record tools, added after the device binding.
# They are the one reviewed non-read addition: new tools start with empty
# history, and each write carries its own invocation idempotency key.
ASSISTANT_TOOLS = frozenset(
    {
        "network_request_deep_scan",
        "network_request_assessment",
        "network_note_profile",
        "network_record_issue",
    }
)
# The assistant's reference request, added after the assistant binding. It is
# the second reviewed non-read addition: it only records a request for the
# owner, starts with empty history and is keyed by its invocation.
REFERENCE_TOOLS = frozenset({"network_request_reference"})
# The exact six-tool paged binding that deployments already hold. It is the
# current declarations without the later tools; a test pins its digest so an
# edit to an older tool cannot silently orphan existing paged journals.
PAGED_DECLARATIONS = tuple(
    tool
    for tool in DECLARATIONS
    if tool.name not in DEVICE_TOOLS | ASSISTANT_TOOLS | REFERENCE_TOOLS
)
# The exact eight-tool device binding, pinned the same way.
DEVICE_DECLARATIONS = tuple(
    tool for tool in DECLARATIONS if tool.name not in ASSISTANT_TOOLS | REFERENCE_TOOLS
)
# The exact twelve-tool assistant binding, pinned the same way.
ASSISTANT_DECLARATIONS = tuple(
    tool for tool in DECLARATIONS if tool.name not in REFERENCE_TOOLS
)
# Optional read-page arguments reviewed for the v1 to paged transition.
_PAGE_ADDITIONS = {
    "network_scope": {"offset": "INTEGER", "limit": "INTEGER"},
    "network_scan_list": {"offset": "INTEGER", "limit": "INTEGER"},
    "network_evidence": {
        "offset": "INTEGER",
        "limit": "INTEGER",
        "section": "STRING",
    },
    "network_destinations": {"offset": "INTEGER", "limit": "INTEGER"},
}


def _compatible(
    previous: tuple[ToolDeclaration, ...],
    following: tuple[ToolDeclaration, ...],
    additions: dict[str, dict[str, str]],
    added_tools: frozenset[str],
    reviewed_writes: frozenset[str] = frozenset(),
) -> None:
    old = {tool.name: tool for tool in previous}
    new = {tool.name: tool for tool in following}
    if set(new) != set(old) | added_tools or set(old) & added_tools:
        raise ValueError("Tool identity changed; no automatic journal transition")
    reads = {
        definition.name.replace(".", "_")
        for definition in DEFINITIONS
        if definition.effect == "read"
    }
    if not added_tools <= reads | reviewed_writes:
        raise ValueError("Only read tools may be added by a journal transition")
    for name, prior in old.items():
        current = new[name]
        before = {parameter.name: parameter for parameter in prior.parameters}
        after = {parameter.name: parameter for parameter in current.parameters}
        if (
            current.input_schema is not None
            or prior.timeout_seconds != current.timeout_seconds
        ):
            raise ValueError("Tool schema changed beyond the reviewed transition")
        for parameter in before.values():
            if parameter.name not in after or (parameter.type, parameter.required) != (
                after[parameter.name].type,
                after[parameter.name].required,
            ):
                raise ValueError("Required tool arguments changed")
        allowed = additions.get(name, {})
        if any(
            parameter.required or allowed.get(parameter.name) != parameter.type
            for parameter in current.parameters
            if parameter.name not in before
        ):
            raise ValueError("Only reviewed optional read-page arguments may be added")


def compatible_descriptors() -> None:
    """Each transition step may add only reviewed arguments, read tools or the
    reviewed assistant and reference tools."""
    _compatible(LEGACY_DECLARATIONS, PAGED_DECLARATIONS, _PAGE_ADDITIONS, frozenset())
    _compatible(PAGED_DECLARATIONS, DEVICE_DECLARATIONS, {}, DEVICE_TOOLS)
    _compatible(
        DEVICE_DECLARATIONS,
        ASSISTANT_DECLARATIONS,
        {},
        ASSISTANT_TOOLS,
        ASSISTANT_TOOLS,
    )
    _compatible(
        ASSISTANT_DECLARATIONS, DECLARATIONS, {}, REFERENCE_TOOLS, REFERENCE_TOOLS
    )


def receipt_stages(receipt: ToolReceipt) -> tuple[ToolReceipt, ...]:
    """Copy history through the SDK's legal local transitions, without execution.

    The destination may contain a prefix after a stopped copy. Immutable request,
    result and publication identity must match the source before advancing it.
    """
    stages = [replace(receipt, phase="executing", result=None, occurred_at=None)]
    if receipt.phase != "executing":
        stages.append(replace(receipt, phase="ready", occurred_at=None))
    if receipt.phase in {"publishing", "done"}:
        stages.append(replace(receipt, phase="publishing"))
    if receipt.phase == "done":
        stages.append(receipt)
    return tuple(stages)


def receipt_digest(receipts: tuple[ToolReceipt, ...]) -> str:
    return hashlib.sha256(
        json.dumps([asdict(receipt) for receipt in receipts], sort_keys=True).encode()
    ).hexdigest()


def _transition(
    stores: ExitStack,
    legacy: SqliteToolJournal | None,
    target: Path,
    configuration: str,
) -> SqliteToolJournal:
    """Open ``target`` and copy ``legacy``'s frozen history into it exactly once.

    The marker records the source binding, count and digest. Once it exists, the
    source must still match it: a source that gained work after the copy means
    an older binary ran, and its effects would otherwise be lost.
    """
    target_identity = hashlib.sha256(configuration.encode()).hexdigest()
    marker = target / "transition.json"
    current = stores.enter_context(
        SqliteToolJournal(target, configuration=configuration)
    )
    source = legacy.all() if legacy else ()
    digest = receipt_digest(source)
    if marker.exists():
        if marker.is_symlink() or marker.resolve() != marker:
            raise ValueError("Tool transition receipt cannot be linked")
        row = object_fields(
            load_json(marker), {"version", "source", "target", "receipts", "digest"}
        )
        if (
            type(row["version"]) is not int
            or row["version"] != 1
            or row["target"] != target_identity
            or row["source"] != (legacy.identity if legacy else None)
            or type(row["receipts"]) is not int
            or row["receipts"] != len(source)
            or row["digest"] != digest
        ):
            raise ValueError("Foreign tool transition receipt")
        return current
    retained = {receipt.invocation_id: receipt for receipt in source}
    existing = current.all()
    if any(
        receipt.invocation_id not in retained
        or receipt not in receipt_stages(retained[receipt.invocation_id])
        for receipt in existing
    ):
        raise ValueError("Unmarked tool journal contains conflicting work")
    copied = {receipt.invocation_id: receipt for receipt in existing}
    for receipt in source:
        stages = receipt_stages(receipt)
        start = (
            stages.index(copied[receipt.invocation_id]) + 1
            if receipt.invocation_id in copied
            else 0
        )
        for stage in stages[start:]:
            current.save(stage)
    if current.all() != source:
        raise ValueError("Tool receipt copy did not settle")
    replace_private(
        marker,
        json.dumps(
            {
                "version": 1,
                "source": legacy.identity if legacy else None,
                "target": target_identity,
                "receipts": len(source),
                "digest": digest,
            }
        )
        + "\n",
    )
    return current


@contextmanager
def open_tool_journal(
    directory: Path, project: str, provider: str, account: str
) -> Iterator[SqliteToolJournal]:
    """Keep SDK configuration checks intact while retaining earlier histories.

    Caller must hold the collector receipt process lock throughout this context.
    The chain is v1 ``directory`` -> ``<directory>-paged`` (six tools with read
    pagination) -> ``<directory>-devices`` (adds the device read tools) ->
    ``<directory>-assistant`` (adds the assistant's request and record tools) ->
    ``<directory>-references`` (adds the assistant's reference request). Each
    step copies public typed receipts before admitting the new provider. Partial
    copies are checked and resumed locally; neither handlers nor Relay mutations
    run. A durable marker fences each step once its complete copy is confirmed.
    Pending phases remain pending and are handled by the SDK's normal recovery
    in the newest journal. Older journals stay open, unchanged, for the life of
    the context, and a fresh install creates only the newest journal.
    """
    compatible_descriptors()
    paged = directory.parent / (directory.name + "-paged")
    devices = directory.parent / (directory.name + "-devices")
    assistant = directory.parent / (directory.name + "-assistant")
    references = directory.parent / (directory.name + "-references")
    with ExitStack() as stores:
        legacy = None
        if (directory / "relay-tools.sqlite").exists():
            legacy = stores.enter_context(
                SqliteToolJournal(
                    directory,
                    configuration=deployment_config(
                        project, provider, account, LEGACY_DECLARATIONS
                    ),
                )
            )
        previous = None
        if legacy is not None or (paged / "relay-tools.sqlite").exists():
            previous = _transition(
                stores,
                legacy,
                paged,
                deployment_config(project, provider, account, PAGED_DECLARATIONS),
            )
        device_journal = None
        if previous is not None or (devices / "relay-tools.sqlite").exists():
            device_journal = _transition(
                stores,
                previous,
                devices,
                deployment_config(project, provider, account, DEVICE_DECLARATIONS),
            )
        assistant_journal = None
        if device_journal is not None or (assistant / "relay-tools.sqlite").exists():
            assistant_journal = _transition(
                stores,
                device_journal,
                assistant,
                deployment_config(project, provider, account, ASSISTANT_DECLARATIONS),
            )
        yield _transition(
            stores,
            assistant_journal,
            references,
            deployment_config(project, provider, account, DECLARATIONS),
        )
