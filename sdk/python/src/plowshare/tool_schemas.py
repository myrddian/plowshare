"""Shared schema conversion and bounded executable profile. No external reference loading."""

from __future__ import annotations

import hashlib
import math
from decimal import Decimal
from types import MappingProxyType
from typing import Mapping, TypeAlias

from ._values import Unset
from .schema_definition import SCHEMA_FIELDS, SchemaDefinition, schema_from_fields

ToolValue: TypeAlias = (
    str
    | int
    | float
    | bool
    | None
    | tuple["ToolValue", ...]
    | Mapping[str, "ToolValue"]
)
DIALECT = "https://json-schema.org/draft/2020-12/schema"
_TYPES = {"object", "array", "string", "number", "integer", "boolean", "null"}


class _Budget:
    def __init__(self) -> None:
        self.remaining = 10000

    def step(self, depth: int) -> None:
        self.remaining -= 1
        if depth > 64 or self.remaining < 0:
            raise ValueError("Schema validation exceeds work bound")


def _text(value: object, maximum: int) -> str:
    if (
        not isinstance(value, str)
        or "\0" in value
        or len(value.encode("utf-16-le")) // 2 > maximum
    ):
        raise ValueError("Invalid schema text")
    return value


def _field(name: str) -> None:
    if (
        not name
        or len(name.encode("utf-16-le")) // 2 > 1024
        or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name)
    ):
        raise ValueError("Invalid tool field")


def _number(value: int | float) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Nonfinite tool number")
    decimal = Decimal(str(value))
    exponent = decimal.normalize().as_tuple().exponent
    if (
        not isinstance(exponent, int)
        or abs(decimal) > 9007199254740991
        or exponent < -18
        or Decimal(str(float(value))) != decimal
    ):
        raise ValueError("Nonportable tool number")


def decode_tool_value(
    source: object, depth: int = 0, budget: _Budget | None = None
) -> ToolValue:
    budget = budget or _Budget()
    budget.step(depth)
    if depth > 32:
        raise ValueError("Tool value exceeds depth bound")
    if source is None or isinstance(source, bool):
        return source
    if isinstance(source, str):
        return _text(source, 4096)
    if isinstance(source, (int, float)):
        _number(source)
        return source
    if isinstance(source, (list, tuple)):
        items: list[object] | tuple[object, ...] = source
        if len(items) > 1024:
            raise ValueError("Too many tool items")
        return tuple(decode_tool_value(item, depth + 1, budget) for item in items)
    if not isinstance(source, Mapping) or len(source) > 1024:
        raise ValueError("Invalid tool object")
    entries: Mapping[object, object] = source
    values: dict[str, ToolValue] = {}
    for key, value in entries.items():
        if not isinstance(key, str):
            raise ValueError("Invalid tool field")
        _field(key)
        values[key] = decode_tool_value(value, depth + 1, budget)
    return MappingProxyType(values)


def _literal(value: object) -> str | int | float | bool | None:
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, str):
        return _text(value, 4096)
    if isinstance(value, (int, float)):
        _number(value)
        return value
    raise ValueError("Schema constants must be scalar")


def decode_tool_schema(
    source: object, depth: int = 0, budget: _Budget | None = None
) -> SchemaDefinition:
    budget = budget or _Budget()
    budget.step(depth)
    if isinstance(source, SchemaDefinition):
        source = schema_wire(source)
    if not isinstance(source, Mapping):
        raise ValueError("Schema must be an object")
    entries: Mapping[object, object] = source
    fields: dict[str, object] = {}
    for key, value in entries.items():
        if not isinstance(key, str) or key not in SCHEMA_FIELDS:
            raise ValueError("Unsupported schema keyword")
        _, kind = SCHEMA_FIELDS[key]
        decoded: object
        if kind == "text":
            decoded = _text(value, 32768 if key == "description" else 4096)
        elif kind == "boolean":
            if type(value) is not bool:
                raise ValueError("Invalid schema boolean")
            decoded = value
        elif kind in {"number", "integer"}:
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError("Invalid schema number")
            _number(value)
            if kind == "integer" and (
                value < 0 or value > 2147483647 or int(value) != value
            ):
                raise ValueError("Invalid schema bound")
            decoded = int(value) if kind == "integer" else value
        elif kind == "literal":
            decoded = _literal(value)
        elif kind == "schema" or kind == "rule" and not isinstance(value, bool):
            decoded = decode_tool_schema(value, depth + 1, budget)
        elif kind == "rule":
            decoded = value
        elif kind == "map":
            if not isinstance(value, Mapping) or len(value) > 10000:
                raise ValueError("Invalid schema map")
            raw: Mapping[object, object] = value
            schemas: dict[str, SchemaDefinition] = {}
            for name, entry in raw.items():
                if not isinstance(name, str):
                    raise ValueError("Invalid schema property")
                _field(name)
                schemas[name] = decode_tool_schema(entry, depth + 1, budget)
            decoded = MappingProxyType(schemas)
        elif kind == "types" and isinstance(value, str):
            if value not in _TYPES:
                raise ValueError("Invalid schema type")
            decoded = value
        else:
            if not isinstance(value, (tuple, list)) or len(value) > 10000:
                raise ValueError("Invalid schema list")
            items: tuple[object, ...] | list[object] = value
            if kind == "schemas":
                if key in {"allOf", "anyOf", "oneOf"} and not items:
                    raise ValueError("Empty schema composition")
                decoded = tuple(
                    decode_tool_schema(item, depth + 1, budget) for item in items
                )
            elif kind == "literals":
                literals = tuple(_literal(item) for item in items)
                if key == "enum" and (
                    not literals
                    or any(
                        _equal(a, b, _Budget(), 0)
                        for i, a in enumerate(literals)
                        for b in literals[:i]
                    )
                ):
                    raise ValueError("Invalid schema enum")
                decoded = literals
            else:
                strings = tuple(_text(item, 1024) for item in items)
                if kind == "types":
                    if not strings or set(strings) - _TYPES:
                        raise ValueError("Invalid schema types")
                else:
                    for name in strings:
                        _field(name)
                if len(set(strings)) != len(strings):
                    raise ValueError("Duplicate schema entries")
                decoded = strings
        fields[key] = decoded
    schema = schema_from_fields(fields)
    for min_name, max_name in [
        ("minimum", "maximum"),
        ("minLength", "maxLength"),
        ("minItems", "maxItems"),
        ("minProperties", "maxProperties"),
    ]:
        minimum = fields.get(min_name)
        maximum = fields.get(max_name)
        if (
            isinstance(minimum, (int, float))
            and isinstance(maximum, (int, float))
            and minimum > maximum
        ):
            raise ValueError("Inverted schema range")
    if not isinstance(schema.multiple_of, Unset) and schema.multiple_of <= 0:
        raise ValueError("Invalid schema multipleOf")
    return schema


def schema_wire(schema: SchemaDefinition) -> dict[str, object]:
    """Private serialization boundary; no raw schema dictionaries reach handlers."""

    remaining = 10000

    def wire(value: object, depth: int = 0) -> object:
        nonlocal remaining
        remaining -= 1
        if depth > 64 or remaining < 0:
            raise ValueError("Schema exceeds structural bounds")
        if isinstance(value, SchemaDefinition):
            return {
                key: wire(field, depth + 1)
                for key, (name, _) in SCHEMA_FIELDS.items()
                if not isinstance(field := getattr(value, name), Unset)
            }
        if isinstance(value, Mapping):
            entries: Mapping[str, object] = value
            return {key: wire(item, depth + 1) for key, item in entries.items()}
        if isinstance(value, (tuple, list)):
            items: tuple[object, ...] | list[object] = value
            return [wire(item, depth + 1) for item in items]
        return value

    encoded = wire(schema)
    if not isinstance(encoded, dict):
        raise ValueError("Expected schema object")
    return encoded


def _reference(root: SchemaDefinition, ref: str) -> SchemaDefinition:
    if not ref.startswith("#/$defs/") or "/" in ref[8:] or isinstance(root.defs, Unset):
        raise ValueError("Reference must select a root definition")
    import re

    if re.search(r"~(?![01])", ref):
        raise ValueError("Invalid schema reference")
    key = ref[8:].replace("~1", "/").replace("~0", "~")
    if key not in root.defs:
        raise ValueError("Unresolved schema reference")
    return root.defs[key]


def validate_tool_schema(source: SchemaDefinition) -> SchemaDefinition:
    root = decode_tool_schema(source)
    if root.type_ not in ("object", ("object",)):
        raise ValueError("Tool schema must have object type")
    budget = _Budget()

    def check(schema: SchemaDefinition, depth: int) -> None:
        budget.step(depth)
        if (
            not isinstance(schema.schema, Unset)
            and schema.schema != DIALECT
            or any(
                not isinstance(value, Unset)
                for value in [
                    schema.id,
                    schema.anchor,
                    schema.pattern,
                    schema.pattern_properties,
                ]
            )
        ):
            raise ValueError("Unsupported executable schema feature")
        if not isinstance(schema.ref, Unset):
            check(_reference(root, schema.ref), depth + 1)
        for value in [schema.defs, schema.properties]:
            if not isinstance(value, Unset):
                for child in value.values():
                    check(child, depth + 1)
        for children in [
            schema.prefix_items,
            schema.all_of,
            schema.any_of,
            schema.one_of,
        ]:
            if not isinstance(children, Unset):
                for child in children:
                    check(child, depth + 1)
        for nested_child in [
            schema.items,
            schema.contains,
            schema.not_,
            schema.if_,
            schema.then,
            schema.else_,
            schema.additional_properties,
        ]:
            if isinstance(nested_child, SchemaDefinition):
                check(nested_child, depth + 1)

    check(root, 0)
    return root


def _bound(
    value: int | float, minimum: int | float | Unset, maximum: int | float | Unset
) -> bool:
    return (isinstance(minimum, Unset) or value >= minimum) and (
        isinstance(maximum, Unset) or value <= maximum
    )


def _equal(a: ToolValue, b: ToolValue, budget: _Budget, depth: int) -> bool:
    budget.step(depth)
    if isinstance(a, bool) or isinstance(b, bool):
        return type(a) is type(b) and a == b
    if isinstance(a, tuple) and isinstance(b, tuple):
        return len(a) == len(b) and all(
            _equal(x, y, budget, depth + 1) for x, y in zip(a, b)
        )
    if isinstance(a, Mapping) and isinstance(b, Mapping):
        return set(a) == set(b) and all(
            _equal(a[k], b[k], budget, depth + 1) for k in a
        )
    return a == b


def validate_schema_arguments(source: SchemaDefinition, input_value: ToolValue) -> None:
    root = validate_tool_schema(source)
    value = decode_tool_value(input_value)
    budget = _Budget()

    def matches(s: SchemaDefinition, v: ToolValue, depth: int) -> bool:
        budget.step(depth)
        if not isinstance(s.ref, Unset) and not matches(
            _reference(root, s.ref), v, depth + 1
        ):
            return False
        if not isinstance(s.type_, Unset):
            types = (s.type_,) if isinstance(s.type_, str) else s.type_
            kind = (
                "null"
                if v is None
                else "boolean"
                if isinstance(v, bool)
                else "string"
                if isinstance(v, str)
                else "number"
                if isinstance(v, (int, float))
                else "array"
                if isinstance(v, tuple)
                else "object"
            )
            if kind not in types and not (
                kind == "number"
                and "integer" in types
                and isinstance(v, (int, float))
                and int(v) == v
            ):
                return False
        if not isinstance(s.const_, Unset) and not _equal(
            s.const_, v, budget, depth + 1
        ):
            return False
        if not isinstance(s.enum_, Unset) and not any(
            _equal(item, v, budget, depth + 1) for item in s.enum_
        ):
            return False
        if not isinstance(s.all_of, Unset) and not all(
            matches(child, v, depth + 1) for child in s.all_of
        ):
            return False
        if not isinstance(s.any_of, Unset) and not any(
            matches(child, v, depth + 1) for child in s.any_of
        ):
            return False
        if (
            not isinstance(s.one_of, Unset)
            and sum(matches(child, v, depth + 1) for child in s.one_of) != 1
        ):
            return False
        if not isinstance(s.not_, Unset) and matches(s.not_, v, depth + 1):
            return False
        if not isinstance(s.if_, Unset):
            branch = s.then if matches(s.if_, v, depth + 1) else s.else_
            if not isinstance(branch, Unset) and not matches(branch, v, depth + 1):
                return False
        if isinstance(v, str) and not _bound(len(v), s.min_length, s.max_length):
            return False
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            if (
                not _bound(v, s.minimum, s.maximum)
                or not isinstance(s.exclusive_minimum, Unset)
                and v <= s.exclusive_minimum
                or not isinstance(s.exclusive_maximum, Unset)
                and v >= s.exclusive_maximum
                or not isinstance(s.multiple_of, Unset)
                and Decimal(str(v)) % Decimal(str(s.multiple_of)) != 0
            ):
                return False
        if isinstance(v, Mapping):
            if (
                not _bound(len(v), s.min_properties, s.max_properties)
                or not isinstance(s.required, Unset)
                and set(s.required) - set(v)
            ):
                return False
            for key, item in v.items():
                child = (
                    s.properties.get(key)
                    if not isinstance(s.properties, Unset)
                    else None
                )
                if child is not None:
                    if not matches(child, item, depth + 1):
                        return False
                elif s.additional_properties is False:
                    return False
                elif isinstance(
                    s.additional_properties, SchemaDefinition
                ) and not matches(s.additional_properties, item, depth + 1):
                    return False
        if isinstance(v, tuple):
            if not _bound(len(v), s.min_items, s.max_items):
                return False
            prefix = s.prefix_items if not isinstance(s.prefix_items, Unset) else ()
            contained = False
            for i, item in enumerate(v):
                item_schema = prefix[i] if i < len(prefix) else s.items
                if not isinstance(item_schema, Unset) and not matches(
                    item_schema, item, depth + 1
                ):
                    return False
                if not isinstance(s.contains, Unset) and matches(
                    s.contains, item, depth + 1
                ):
                    contained = True
                if s.unique_items is True and any(
                    _equal(item, other, budget, depth + 1) for other in v[:i]
                ):
                    return False
            if not isinstance(s.contains, Unset) and not contained:
                return False
        return True

    if not matches(root, value, 0):
        raise ValueError("Tool arguments do not satisfy the offered schema")


def schema_fingerprint(source: SchemaDefinition) -> str:
    """Emit only the canonical UTF-8-key identity for new calls."""
    return _schema_fingerprint(source, legacy_ordering=False)


def schema_fingerprint_matches(source: SchemaDefinition, offered: str | None) -> bool:
    """Validate exact schemas with historical Java ordering, without rewriting retained requests."""
    return offered == schema_fingerprint(source) or offered == _schema_fingerprint(
        source, legacy_ordering=True
    )


def _schema_fingerprint(source: SchemaDefinition, *, legacy_ordering: bool) -> str:
    def canonical(v: object) -> str:
        if v is None:
            return "z;"
        if isinstance(v, bool):
            return "b1;" if v else "b0;"
        if isinstance(v, str):
            return "s" + v.encode().hex() + ";"
        if isinstance(v, (float, int)):
            d = Decimal(str(v)).normalize()
            parts = d.as_tuple()
            digits = "".join(str(x) for x in parts.digits)
            return (
                "n"
                + ("-" if parts.sign and d else "")
                + digits
                + "e"
                + str(parts.exponent if d else 0)
                + ";"
            )
        if isinstance(v, (list, tuple)):
            items: list[object] | tuple[object, ...] = v
            return "[" + "".join(canonical(x) for x in items) + "]"
        if isinstance(v, Mapping):
            entries: Mapping[str, object] = v
            return (
                "{"
                + "".join(
                    canonical(k) + canonical(entries[k])
                    for k in sorted(
                        entries,
                        key=lambda x: (
                            "s" + x.encode().hex() + ";"
                            if legacy_ordering
                            else x.encode().hex()
                        ),
                    )
                )
                + "}"
            )
        raise ValueError("Invalid canonical schema")

    return hashlib.sha256(
        canonical(schema_wire(validate_tool_schema(source))).encode()
    ).hexdigest()
