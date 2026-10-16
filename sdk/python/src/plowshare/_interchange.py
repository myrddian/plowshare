"""Private portable graph validation; no foreign authority leaves this codec boundary."""

from __future__ import annotations

import base64
import hashlib
import re
from typing import cast


def path(value: object) -> bool:
    return (
        isinstance(value, str)
        and 0 < len(value) <= 240
        and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", value) is not None
        and all(
            part.lower() not in ("", ".", "..", ".git", ".hg", ".svn")
            and not part.endswith(".")
            and re.fullmatch(r"(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?", part)
            is None
            for part in value.split("/")
        )
    )


def _object(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or any(not isinstance(k, str) for k in value):
        raise ValueError("Invalid portable object")
    return cast(dict[str, object], value)


def _list(value: object) -> list[object]:
    if not isinstance(value, list):
        raise ValueError("Invalid portable list")
    return cast(list[object], value)


def _text(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("Invalid portable identity")
    return value


def _descriptor(value: object) -> dict[str, object]:
    d = _object(value)
    if not path(d.get("path")):
        raise ValueError("Unsafe portable path")
    provenance = _object(d.get("provenance"))
    for raw in _list(provenance.get("evidence")):
        e = _object(raw)
        start, end = e.get("start"), e.get("end")
        if (
            type(start) is not int
            or type(end) is not int
            or start < 0
            or end <= start
            or len(_text(e.get("quote")).encode("utf-16-le")) // 2 != end - start
        ):
            raise ValueError("Invalid portable evidence span")
    return d


def _bundle(value: object) -> dict[str, object]:
    b = _object(value)
    docs = _list(b.get("documents"))
    if not 1 <= len(docs) <= 16:
        raise ValueError("Portable record bound")
    revisions: dict[str, dict[str, object]] = {}
    paths: set[str] = set()
    ordinals: set[tuple[str, object]] = set()
    resources: set[str] = set()
    total = 0
    for raw in docs:
        doc = _object(raw)
        d = _descriptor(doc.get("descriptor"))
        revision, resource = _text(d.get("revision")), _text(d.get("resource"))
        filename = _text(d.get("path")).lower()
        ordinal = d.get("ordinal")
        if type(ordinal) is not int:
            raise ValueError("Invalid portable ordinal")
        if (
            revision in revisions
            or filename in paths
            or (resource, ordinal) in ordinals
        ):
            raise ValueError("Duplicate portable identity")
        encoded = _text(doc.get("bytesBase64"))
        if len(encoded) > 2796204:
            raise ValueError("Portable byte bound")
        original = base64.b64decode(encoded, validate=True)
        if (
            not original
            or base64.b64encode(original).decode() != encoded
            or hashlib.sha256(original).hexdigest() != d.get("contentHash")
        ):
            raise ValueError("Invalid portable bytes/hash")
        total += len(original)
        revisions[revision] = d
        paths.add(filename)
        ordinals.add((resource, ordinal))
        resources.add(resource)
    if total > 2097152 or any(
        other.startswith(filename + "/") for filename in paths for other in paths
    ):
        raise ValueError("Portable size/path collision")
    for d in revisions.values():
        p = _object(d.get("provenance"))
        dependencies = [_text(v) for v in _list(p.get("dependencies"))]
        if len(set(dependencies)) != len(dependencies) or any(
            v not in revisions or v == d.get("revision") for v in dependencies
        ):
            raise ValueError("Portable dependency missing")
        for raw in _list(p.get("evidence")):
            if _text(_object(raw).get("revision")) not in revisions:
                raise ValueError("Portable evidence source missing")
    edges: set[tuple[str, str, str | None, str]] = set()
    for raw in _list(b.get("relationships")):
        e = _object(raw)
        source, target = _text(e.get("source")), _text(e.get("target"))
        pin = None if "pinnedRevision" not in e else _text(e["pinnedRevision"])
        edge = source, target, pin, _text(e.get("relation"))
        if (
            source not in resources
            or target not in resources
            or edge in edges
            or pin is not None
            and revisions.get(pin, {}).get("resource") != target
        ):
            raise ValueError("Invalid portable relationship")
        edges.add(edge)
    return b


def check(value: object, kind: str) -> None:
    if kind == "readable":
        readable = _object(value)
        # Hash the exact UTF-8 text, preserving BOM and line endings; do not normalize or repair.
        if hashlib.sha256(
            _text(readable.get("markdown")).encode("utf-8")
        ).hexdigest() != readable.get("textHash"):
            raise ValueError("Readable text hash differs")
    elif kind == "descriptor":
        _descriptor(value)
    elif kind == "bundle":
        _bundle(value)
    elif kind == "plan":
        plan = _object(value)
        b = _bundle(plan.get("bundle"))
        documents = _list(b.get("documents"))
        all_revisions = {
            _text(_object(_object(d).get("descriptor")).get("revision"))
            for d in documents
        }
        reused: set[str] = set()
        local: set[str] = set()
        for raw in _list(plan.get("reuse")):
            r = _object(raw)
            foreign, revision = (
                _text(r.get("foreignRevision")),
                _text(r.get("localRevision")),
            )
            if foreign not in all_revisions or foreign in reused or revision in local:
                raise ValueError("Invalid portable revision reuse")
            reused.add(foreign)
            local.add(revision)
        resources = {
            _text(_object(_object(d).get("descriptor")).get("resource"))
            for d in documents
            if _text(_object(_object(d).get("descriptor")).get("revision"))
            not in reused
        }
        if not resources:
            raise ValueError("Import must admit a new revision")
        if plan.get("restoreRelationships") is True and any(
            _text(_object(e).get("source")) not in resources
            for e in _list(b.get("relationships"))
        ):
            raise ValueError("Relationship restoration needs an imported source")
        if plan.get("restoreRelationships") is True:
            descriptors = {
                _text(_object(_object(d).get("descriptor")).get("revision")): _object(
                    _object(d).get("descriptor")
                )
                for d in documents
            }
            for raw in _list(b.get("relationships")):
                link = _object(raw)
                if "pinnedRevision" in link:
                    pinned = descriptors[_text(link["pinnedRevision"])]
                    if pinned.get("contentHash") != _object(
                        pinned.get("provenance")
                    ).get("sourceHash"):
                        raise ValueError("Cannot restore an exact pin to edited bytes")
        selected: set[str] = set()
        names: set[str] = set()
        for raw in _list(plan.get("destinations")):
            d = _object(raw)
            resource, name = _text(d.get("resource")), _text(d.get("name"))
            if resource in selected or name in names:
                raise ValueError("Duplicate/unsafe portable destination")
            selected.add(resource)
            names.add(name)
        if selected != resources:
            raise ValueError("Explicit portable destination mapping required")
        versions: set[str] = set()
        for raw in _list(plan.get("types")):
            version = _text(_object(raw).get("foreignVersion"))
            if version in versions:
                raise ValueError("Duplicate portable type mapping")
            versions.add(version)
