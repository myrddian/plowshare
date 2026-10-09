"""Portable constraint vocabulary generated with the native DTO graph."""

from __future__ import annotations

import json
import math
import re
from datetime import datetime
from typing import cast
from urllib.parse import urlsplit


def _get(row: object, path: str) -> object:
    for key in path.split("."):
        if not isinstance(row, dict):
            return None
        row = row.get(key)
    return row


def _present(value: object) -> bool:
    return value is not None and value is not False and value != "" and value != []


def _rule(rule: object, row: object) -> object:
    if not isinstance(rule, dict):
        return rule
    expression = cast(dict[str, object], rule)
    op, args = next(iter(expression.items()))
    if op == "get":
        return _get(row, cast(str, args))
    if op == "has":
        return isinstance(row, dict) and args in row
    if op == "present":
        return _present(_get(row, cast(str, args)))
    if op in {"exactlyOne", "atMostOne"}:
        count = sum(_present(_get(row, key)) for key in cast(list[str], args))
        return count == 1 if op == "exactlyOne" else count <= 1
    if op == "not":
        return not _rule(args, row)
    values = [_rule(a, row) for a in cast(list[object], args)]
    if op == "and":
        return all(values)
    if op == "or":
        return any(values)
    if op == "contains":
        return isinstance(values[0], list) and values[1] in values[0]
    if op == "eq":
        return type(values[0]) is type(values[1]) and values[0] == values[1]
    if op == "gt":
        a, b = values
        return isinstance(a, (int, float)) and isinstance(b, (int, float)) and a > b
    raise ValueError("Unknown owned constraint")


def finite(value: int | float) -> bool:
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _application_files(value: list[object] | tuple[object, ...]) -> bool:
    paths: set[str] = set()
    total = 0
    manifest = False
    for item in value:
        if not isinstance(item, dict):
            return False
        path, text = item.get("path"), item.get("text")
        if not isinstance(path, str) or not isinstance(text, str):
            return False
        if (
            len(path) > 512
            or len(path.split("/")) > 17
            or re.fullmatch(
                r"[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*",
                path,
            )
            is None
        ):
            return False
        if (
            any(
                segment in (".", "..", "node_modules", "build", "__pycache__")
                for segment in path.split("/")
            )
            or path.lower() in paths
        ):
            return False
        try:
            count = len(text.encode("utf-8"))
        except UnicodeEncodeError:
            return False
        if "\0" in text or count > 65536:
            return False
        total += count
        paths.add(path.lower())
        manifest |= path == "plowshare.json"
    return (
        total <= 131072
        and manifest
        and not any(other.startswith(path + "/") for path in paths for other in paths)
    )


def check(schema: dict[str, object], value: object) -> None:
    if value is None:
        return
    invalid = False
    if isinstance(value, str):
        length = len(value.encode("utf-16-le", errors="surrogatepass")) // 2
        invalid = length < cast(int, schema.get("minLength", 0)) or length > cast(
            int, schema.get("maxLength", 50 * 1024 * 1024)
        )
        if "maxUtf8Bytes" in schema:
            try:
                invalid |= len(value.encode("utf-8")) > cast(
                    int, schema["maxUtf8Bytes"]
                )
            except UnicodeEncodeError:
                invalid = True
        invalid |= bool(schema.get("nonblank")) and not value.strip()
        invalid |= bool(schema.get("trimmed")) and value != value.strip()
        invalid |= bool(schema.get("noNul")) and "\0" in value
        invalid |= (
            "pattern" in schema
            and re.search(cast(str, schema["pattern"]), value) is None
        )
        if schema.get("web"):
            try:
                address = urlsplit(value)
                invalid |= address.port is not None and not 0 < address.port <= 65535
            except ValueError:
                invalid = True
        if schema.get("timestamp"):
            try:
                datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                invalid = True
        invalid |= value in cast(list[str], schema.get("disallow", []))
        invalid |= bool(schema.get("safeRelativePath")) and (
            value.startswith("/") or "\\" in value or ".." in value.split("/")
        )
        invalid |= bool(schema.get("canonicalRelativePath")) and (
            "\\" in value
            or ":" in value
            or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value)
            or bool(value)
            and any(
                part in ("", ".", "..") or part.lower() == ".git"
                for part in value.split("/")
            )
        )
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        invalid |= bool(schema.get("integer")) and (
            not finite(value) or int(value) != value
        )
        if schema.get("safePrecision"):
            literal = str(value).lower()
            exponent = literal.split("e")[1] if "e" in literal else None
            invalid |= int(value) == value and abs(value) > 9007199254740991
            invalid |= (
                abs(int(exponent)) > 18
                if exponent
                else len(literal.split(".")[1]) > 18
                if "." in literal
                else False
            )
        invalid |= "minimum" in schema and value < cast(float, schema["minimum"])
        invalid |= "maximum" in schema and value > cast(float, schema["maximum"])
    if isinstance(value, (list, tuple)):
        invalid |= len(value) < cast(int, schema.get("minItems", 0)) or len(
            value
        ) > cast(int, schema.get("maxItems", 10000))
        invalid |= bool(schema.get("uniqueItems")) and len(
            {json.dumps(v, sort_keys=True) for v in value}
        ) != len(value)
        if schema.get("applicationFiles"):
            invalid |= not _application_files(value)
        if "element" in schema:
            for entry in value:
                check(cast(dict[str, object], schema["element"]), entry)
    if isinstance(value, dict):
        invalid |= len(value) > cast(int, schema.get("maxProperties", 10000))
        for key, entry in value.items():
            invalid |= (
                "keyPattern" in schema
                and re.search(cast(str, schema["keyPattern"]), key) is None
            )
            if "values" in schema:
                check(cast(dict[str, object], schema["values"]), entry)
    invalid |= any(
        not _rule(r, value) for r in cast(list[object], schema.get("rules", []))
    )
    if invalid:
        raise ValueError("Value violates the owned protocol constraints")
