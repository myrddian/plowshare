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


def compatible_descriptors() -> None:
    old = {tool.name: tool for tool in LEGACY_DECLARATIONS}
    new = {tool.name: tool for tool in DECLARATIONS}
    if set(old) != set(new):
        raise ValueError("Tool identity changed; no automatic journal transition")
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
        additions = {
            "network_scope": {"offset": "INTEGER", "limit": "INTEGER"},
            "network_scan_list": {"offset": "INTEGER", "limit": "INTEGER"},
            "network_evidence": {
                "offset": "INTEGER",
                "limit": "INTEGER",
                "section": "STRING",
            },
            "network_destinations": {"offset": "INTEGER", "limit": "INTEGER"},
        }.get(name, {})
        if any(
            parameter.required or additions.get(parameter.name) != parameter.type
            for parameter in current.parameters
            if parameter.name not in before
        ):
            raise ValueError("Only reviewed optional read-page arguments may be added")


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


@contextmanager
def open_tool_journal(
    directory: Path, project: str, provider: str, account: str
) -> Iterator[SqliteToolJournal]:
    """Keep SDK configuration checks intact while retaining the v1 history.

    Caller must hold the collector receipt process lock throughout this context.
    Copy public typed receipts before admitting a new provider. Partial copies
    are checked and resumed locally; neither handlers nor Relay mutations run.
    A durable marker fences the transition once the complete copy is confirmed.
    Pending phases remain pending and are handled by the SDK's normal recovery.
    """
    compatible_descriptors()
    configuration = deployment_config(project, provider, account, DECLARATIONS)
    target_identity = hashlib.sha256(configuration.encode()).hexdigest()
    target = directory.parent / (directory.name + "-paged")
    marker = target / "transition.json"
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
        else:
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
        yield current
