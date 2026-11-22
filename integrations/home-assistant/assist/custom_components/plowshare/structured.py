"""A strict, flat HA selector subset using ordinary SDK task instructions.

HA schemas and parsed JSON exist only at conversion boundaries. Immutable local
specifications describe supported values; selector validators are never called
because HA's number and boolean validators coerce generated values. Resource
bounds below protect parsing/metadata, not model prose word counts. There is no
repair or resubmission path: an invalid completed result stays in Plowshare.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass

import voluptuous as vol
from homeassistant.helpers.selector import (
    BooleanSelector,
    NumberSelector,
    SelectSelector,
    TextSelector,
    TextSelectorType,
)

MAX_FIELDS = 64
MAX_OPTIONS = 128
MAX_LIST_ITEMS = 256
MAX_OUTPUT_BYTES = 256 * 1024
MAX_CONTAINER_DEPTH = 3
MAX_NAME_BYTES = 256
MAX_METADATA_BYTES = 4096
MAX_OPTION_BYTES = 1024


class StructureError(ValueError):
    """Unsupported schema or invalid completed data, without private payloads."""


@dataclass(frozen=True)
class TextSpec:
    multiple: bool


@dataclass(frozen=True)
class NumberSpec:
    minimum: float | None
    maximum: float | None


@dataclass(frozen=True)
class BooleanSpec:
    pass


@dataclass(frozen=True)
class SelectSpec:
    options: tuple[str, ...]
    multiple: bool


type ValueSpec = TextSpec | NumberSpec | BooleanSpec | SelectSpec
type Scalar = str | int | float | bool
type OutputValue = Scalar | tuple[str, ...]


@dataclass(frozen=True)
class OutputField:
    name: str
    required: bool
    description: str | None
    value: ValueSpec


@dataclass(frozen=True)
class OutputSpec:
    fields: tuple[OutputField, ...]


@dataclass(frozen=True)
class OutputItem:
    name: str
    value: OutputValue


@dataclass(frozen=True)
class StructuredResult:
    items: tuple[OutputItem, ...]

    def as_ha_data(self) -> dict[str, Scalar | list[str]]:
        """Materialize mutable protocol values only at HA's return boundary."""
        return {
            item.name: list(item.value) if type(item.value) is tuple else item.value
            for item in self.items
        }


def _text(value: object, maximum: int, *, nonempty: bool = False) -> str:
    if type(value) is not str or (nonempty and not value):
        raise StructureError("Structured metadata must contain strings")
    try:
        if len(value) > maximum or len(value.encode("utf-8")) > maximum:
            raise StructureError("Structured metadata exceeds its resource limit")
    except UnicodeError as error:
        raise StructureError("Structured text must be valid Unicode") from error
    return value


def _boolean(value: object) -> bool:
    if type(value) is not bool:
        raise StructureError("Structured boolean configuration is invalid")
    return value


def _number(value: object) -> int | float:
    if type(value) not in (int, float) or (
        type(value) is float and not math.isfinite(value)
    ):
        raise StructureError("Structured numbers must be finite JSON numbers")
    return value


def _configuration(value: object, allowed: set[str]) -> dict:
    if type(value) is not dict or any(
        type(key) is not str or key not in allowed | {"read_only"} for key in value
    ):
        raise StructureError("Unsupported selector configuration")
    if "read_only" in value:
        _boolean(value["read_only"])
    return value


def _value_spec(selector: object) -> ValueSpec:
    """Validate known UI metadata, then discard it without claiming constraints.

    Text type/prefix/suffix/autocomplete/multiline and selector read_only are UI
    presentation. Number step controls UI stepping, not numeric rounding. Select
    labels/mode/sort/translation_key also do not change the accepted value set.
    Unknown keys and subclasses fail closed rather than inventing semantics.
    """
    if type(selector) is TextSelector:
        config = _configuration(
            selector.config,
            {"multiple", "multiline", "prefix", "suffix", "type", "autocomplete"},
        )
        for key in ("prefix", "suffix", "autocomplete"):
            if key in config:
                _text(config[key], MAX_METADATA_BYTES)
        if "type" in config and config["type"] not in tuple(TextSelectorType):
            raise StructureError("Unsupported text presentation type")
        if "type" in config and type(config["type"]) is not str:
            raise StructureError("Unsupported text presentation type")
        _boolean(config.get("multiline", False))
        return TextSpec(_boolean(config.get("multiple", False)))
    if type(selector) is BooleanSelector:
        _configuration(selector.config, set())
        return BooleanSpec()
    if type(selector) is NumberSelector:
        config = _configuration(
            selector.config,
            {"min", "max", "step", "unit_of_measurement", "mode", "translation_key"},
        )
        minimum = _number(config["min"]) if "min" in config else None
        maximum = _number(config["max"]) if "max" in config else None
        if minimum is not None and maximum is not None and minimum > maximum:
            raise StructureError("Structured numeric range is invalid")
        step = config.get("step", 1)
        if step != "any" and _number(step) < 0.001:
            raise StructureError("Structured numeric step is invalid")
        if "mode" in config and (
            type(config["mode"]) is not str or config["mode"] not in {"box", "slider"}
        ):
            raise StructureError("Unsupported number presentation mode")
        if config.get("mode") == "slider" and (minimum is None or maximum is None):
            raise StructureError("Structured numeric slider range is missing")
        for key in ("unit_of_measurement", "translation_key"):
            if key in config:
                _text(config[key], MAX_METADATA_BYTES)
        return NumberSpec(minimum, maximum)
    if type(selector) is SelectSelector:
        config = _configuration(
            selector.config,
            {"options", "multiple", "custom_value", "mode", "translation_key", "sort"},
        )
        if _boolean(config.get("custom_value", False)):
            raise StructureError("Custom select values are unsupported")
        _boolean(config.get("sort", False))
        if "mode" in config and (
            type(config["mode"]) is not str
            or config["mode"] not in {"list", "dropdown"}
        ):
            raise StructureError("Unsupported select presentation mode")
        if "translation_key" in config:
            _text(config["translation_key"], MAX_METADATA_BYTES)
        options = config.get("options")
        if type(options) not in (list, tuple) or not 1 <= len(options) <= MAX_OPTIONS:
            raise StructureError("Structured enum options exceed the supported subset")
        values = []
        option_kind = type(options[0])
        for option in options:
            if type(option) is not option_kind:
                raise StructureError("Structured enum options must have one shape")
            if type(option) is dict and set(option) == {"value", "label"}:
                _text(option["label"], MAX_METADATA_BYTES)
                value = _text(option["value"], MAX_OPTION_BYTES)
            else:
                value = _text(option, MAX_OPTION_BYTES)
            values.append(value)
        if len(set(values)) != len(values):
            raise StructureError("Structured enum values must be distinct")
        return SelectSpec(tuple(values), _boolean(config.get("multiple", False)))
    raise StructureError("Unsupported structured selector")


def compile_structure(schema: object) -> OutputSpec:
    """Convert only the flat action/helper schema subset before any SDK call.

    Required/Optional markers must be exact classes with literal string names
    and no defaults. Complex keys, marker/schema/selector subclasses, nested or
    callable schemas and non-closed extra policies have unsupported semantics.
    """
    if (
        type(schema) is not vol.Schema
        or type(schema.schema) is not dict
        or schema.extra != vol.PREVENT_EXTRA
        or type(schema.required) is not bool
        or len(schema.schema) > MAX_FIELDS
    ):
        raise StructureError("Structured schema is outside the supported flat subset")
    fields = []
    for marker, selector in schema.schema.items():
        if (
            type(marker) not in (vol.Required, vol.Optional)
            or marker.default is not vol.UNDEFINED
            or marker.msg is not None
        ):
            raise StructureError("Unsupported structured field marker or default")
        name = _text(marker.schema, MAX_NAME_BYTES, nonempty=True)
        description = (
            _text(marker.description, MAX_METADATA_BYTES)
            if marker.description is not None
            else None
        )
        fields.append(
            OutputField(
                name, type(marker) is vol.Required, description, _value_spec(selector)
            )
        )
    if len({field.name for field in fields}) != len(fields):
        raise StructureError("Structured field names must be distinct")
    return OutputSpec(tuple(fields))


def render_instructions(instructions: str, spec: OutputSpec) -> str:
    """Append explicit adapter content; original HA instructions remain a prefix.

    This is ordinary task/user text, never a provider response-format parameter
    or a native system-role override. The existing TaskRequest validates the
    complete request size before submission. No cache-oriented rewrite occurs.
    """
    if type(instructions) is not str:
        raise StructureError("Task instructions must be text")
    fields = []
    for field in spec.fields:
        value = field.value
        item = {"name": field.name, "required": field.required}
        if field.description is not None:
            item["description"] = field.description
        if type(value) is TextSpec:
            item.update(type="string", multiple=value.multiple)
        elif type(value) is SelectSpec:
            item.update(type="string", multiple=value.multiple, enum=value.options)
        elif type(value) is BooleanSpec:
            item.update(type="boolean")
        else:
            item.update(type="number", minimum=value.minimum, maximum=value.maximum)
        fields.append(item)
    return (
        instructions
        + "\n\n[Plowshare Home Assistant adapter output contract]\n"
        + "Return one JSON object only, without Markdown or extra keys. "
        + "Include every required field; optional fields may be omitted. "
        + "For multiple=true return a JSON array of strings; otherwise return "
        + "the declared scalar type. Enum values are exact strings. Numbers must "
        + "be finite and within any minimum/maximum. Do not coerce types.\n"
        + json.dumps(fields, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    )


def _check_depth(text: str) -> None:
    """Bound parser nesting before json.loads, honoring quoted JSON escapes."""
    depth = 0
    quoted = False
    escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > MAX_CONTAINER_DEPTH:
                raise StructureError("Structured output exceeds the parser depth limit")
        elif char in "]}":
            depth -= 1


def _object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise StructureError("Structured output contains duplicate keys")
        result[key] = value
    return result


def _constant(value: str) -> object:
    raise StructureError("Structured output contains a non-finite number")


def _validate_value(value: object, spec: ValueSpec) -> OutputValue:
    if type(spec) is BooleanSpec:
        return _boolean(value)
    if type(spec) is NumberSpec:
        number = _number(value)
        if (spec.minimum is not None and number < spec.minimum) or (
            spec.maximum is not None and number > spec.maximum
        ):
            raise StructureError("Structured output number is outside its range")
        return number
    if spec.multiple:
        if type(value) is not list or len(value) > MAX_LIST_ITEMS:
            raise StructureError("Structured output list is invalid or too large")
        values = tuple(_text(item, MAX_OUTPUT_BYTES) for item in value)
    else:
        values = (_text(value, MAX_OUTPUT_BYTES),)
    if type(spec) is SelectSpec and any(value not in spec.options for value in values):
        raise StructureError("Structured output contains an unknown enum value")
    return values if spec.multiple else values[0]


def parse_completed(text: str, spec: OutputSpec) -> StructuredResult:
    """Validate completed text without coercion, repair, shortening or replay.

    There is deliberately no prose length target: modest word-count overruns
    remain valid strings. UTF8 bytes, nesting, fields and arrays are independent
    finite resource limits. Parsed dictionaries never leave this boundary.
    """
    _text(text, MAX_OUTPUT_BYTES)
    _check_depth(text)
    try:
        data = json.loads(text, object_pairs_hook=_object, parse_constant=_constant)
    except (ValueError, RecursionError) as error:
        raise StructureError("Structured output is not valid supported JSON") from error
    if type(data) is not dict or len(data) > MAX_FIELDS:
        raise StructureError("Structured output must be a bounded JSON object")
    names = {field.name for field in spec.fields}
    if any(key not in names for key in data):
        raise StructureError("Structured output contains unexpected fields")
    items = []
    for field in spec.fields:
        if field.name not in data:
            if field.required:
                raise StructureError("Structured output is missing required fields")
            continue
        items.append(
            OutputItem(field.name, _validate_value(data[field.name], field.value))
        )
    return StructuredResult(tuple(items))
