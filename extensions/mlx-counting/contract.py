"""Strict Chat Completions boundary for a count-only, single-model companion.

The DTOs contain text, immutable media and canonical JSON strings. Raw JSON maps
are reconstructed only inside the model-library boundary, never passed through
the counter/service contract. Unsupported prompt-affecting fields are refused.
"""

from __future__ import annotations

import base64
import binascii
import json
import math
import re
from dataclasses import dataclass
from typing import Protocol

MAX_BODY = 32 * 1024 * 1024
MAX_TEXT = 1024 * 1024
MAX_IMAGES = 16
MAX_IMAGE_BYTES = 5 * 1024 * 1024


class Refused(ValueError):
    """A fixed, safe error code; never include input or provider error text."""


def strict_json(raw: bytes | str):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Refused("duplicate_json_field")
            result[key] = value
        return result

    try:
        return json.loads(
            raw,
            object_pairs_hook=pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(Refused("invalid_number")),
        )
    except (ValueError, UnicodeError, RecursionError) as error:
        raise Refused("invalid_json") from error


def object_fields(value, allowed, required=()):
    if (
        not isinstance(value, dict)
        or set(value) - set(allowed)
        or not set(required) <= set(value)
    ):
        raise Refused("unsupported_or_missing_fields")
    return value


def text(value, limit=MAX_TEXT, *, identifier=False):
    if not isinstance(value, str):
        raise Refused("invalid_text")
    try:
        if len(value.encode("utf-8")) > limit:
            raise Refused("invalid_text")
    except UnicodeError as error:
        raise Refused("invalid_text") from error
    if identifier and (
        not value or value != value.strip() or any(ord(c) < 32 for c in value)
    ):
        raise Refused("invalid_identifier")
    return value


def sequence(value, limit, *, nonempty=False):
    if not isinstance(value, list) or len(value) > limit or nonempty and not value:
        raise Refused("invalid_list")
    return value


def canonical_object(value):
    """Bound arbitrary schema/argument JSON before retaining its canonical string."""
    if not isinstance(value, dict):
        raise Refused("json_object_required")
    remaining = 20000

    def visit(node, depth):
        nonlocal remaining
        remaining -= 1
        if remaining < 0 or depth > 24:
            raise Refused("json_complexity_bound")
        if isinstance(node, dict):
            for key, item in node.items():
                text(key, 1024)
                visit(item, depth + 1)
        elif isinstance(node, list):
            for item in node:
                visit(item, depth + 1)
        elif isinstance(node, str):
            text(node)
        elif node is not None and type(node) not in (bool, int, float):
            raise Refused("invalid_json_value")
        elif isinstance(node, (int, float)) and (
            abs(node) > 1e100 or not math.isfinite(node)
        ):
            raise Refused("invalid_number")

    visit(value, 0)
    encoded = json.dumps(
        value, ensure_ascii=False, allow_nan=False, separators=(",", ":")
    )
    text(encoded)
    return encoded


@dataclass(frozen=True)
class TextPart:
    value: str


@dataclass(frozen=True)
class ImagePart:
    mime: str
    data: bytes


@dataclass(frozen=True)
class ToolCall:
    identifier: str
    name: str
    arguments_json: str


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters_json: str


@dataclass(frozen=True)
class Message:
    role: str
    parts: tuple[TextPart | ImagePart, ...]
    array_content: bool
    calls: tuple[ToolCall, ...]
    tool_call_id: str | None
    name: str | None


@dataclass(frozen=True)
class CountRequest:
    model: str
    messages: tuple[Message, ...]
    tools: tuple[Tool, ...]
    enable_thinking: bool | None


@dataclass(frozen=True)
class Count:
    model: str
    revision: str
    input_tokens: int

    def __post_init__(self):
        text(self.model, 1024, identifier=True)
        if not re.fullmatch("[a-f0-9]{64}", self.revision):
            raise Refused("invalid_count_revision")
        if (
            type(self.input_tokens) is not int
            or not 0 <= self.input_tokens <= 2**63 - 1
        ):
            raise Refused("invalid_count")


class Counter(Protocol):
    """Counts a complete prompt without inference or durable media ownership."""

    def count(self, request: CountRequest) -> Count: ...


def image_part(value):
    part = object_fields(value, {"type", "image_url"}, {"type", "image_url"})
    image = object_fields(part["image_url"], {"url"}, {"url"})
    url = text(image["url"], MAX_IMAGE_BYTES * 4 // 3 + 128)
    match = re.fullmatch(
        r"data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]*={0,2})", url
    )
    if not match:
        raise Refused("unsupported_image")
    try:
        data = base64.b64decode(match[2], validate=True)
    except binascii.Error as error:
        raise Refused("invalid_image_encoding") from error
    if not data or len(data) > MAX_IMAGE_BYTES:
        raise Refused("image_byte_bound")
    return ImagePart(match[1], data)


def parse_request(raw: bytes) -> CountRequest:
    if not raw or len(raw) > MAX_BODY:
        raise Refused("request_byte_bound")
    body = object_fields(
        strict_json(raw),
        {
            "model",
            "messages",
            "tools",
            "tool_choice",
            "chat_template_kwargs",
            "temperature",
            "top_p",
            "top_k",
            "max_tokens",
            "stream",
        },
        {"model", "messages"},
    )
    model = text(body["model"], 1024, identifier=True)
    # Sampling does not affect prompt preprocessing, but malformed options must
    # not masquerade as a count of an otherwise valid generation request.
    for key, low, high in [
        ("temperature", 0, 2),
        ("top_p", 0, 1),
        ("top_k", 0, 1000000),
        ("max_tokens", 1, 10000000),
    ]:
        if key in body:
            value = body[key]
            if (
                type(value) not in (int, float)
                or not math.isfinite(value)
                or not low <= value <= high
            ):
                raise Refused("invalid_sampling")
            if key in ("top_k", "max_tokens") and type(value) is not int:
                raise Refused("invalid_sampling")
    if "stream" in body and body["stream"] is not False:
        raise Refused("unsupported_stream")
    if "tool_choice" in body and body["tool_choice"] != "auto":
        raise Refused("unsupported_tool_choice")
    options = object_fields(body.get("chat_template_kwargs", {}), {"enable_thinking"})
    thinking = options.get("enable_thinking")
    if (
        thinking is not None
        and type(thinking) is not bool
        or "enable_thinking" in options
        and thinking is None
    ):
        raise Refused("invalid_template_option")
    tools = []
    for item in sequence(body.get("tools", []), 128):
        item = object_fields(item, {"type", "function"}, {"type", "function"})
        if item["type"] != "function":
            raise Refused("unsupported_tool")
        function = object_fields(
            item["function"], {"name", "description", "parameters"}, {"name"}
        )
        parameters = function.get("parameters", {"type": "object", "properties": {}})
        encoded = canonical_object(parameters)
        if parameters.get("type") != "object" or not isinstance(
            parameters.get("properties", {}), dict
        ):
            raise Refused("invalid_tool_schema")
        tools.append(
            Tool(
                text(function["name"], 256, identifier=True),
                text(function.get("description", "")),
                encoded,
            )
        )
    if len({tool.name for tool in tools}) != len(tools):
        raise Refused("duplicate_tool")
    messages = []
    seen_calls = {}
    image_count = 0
    for item in sequence(body["messages"], 10000, nonempty=True):
        item = object_fields(
            item,
            {"role", "content", "tool_calls", "tool_call_id", "name"},
            {"role", "content"},
        )
        role = item["role"]
        if role not in ("system", "user", "assistant", "tool"):
            raise Refused("unsupported_role")
        if role == "system" and messages:
            raise Refused("unsupported_system_order")
        parts = []
        content = item["content"]
        if isinstance(content, str):
            parts.append(TextPart(text(content)))
        elif content is None and role == "assistant" and item.get("tool_calls"):
            pass
        else:
            for part in sequence(content, 256, nonempty=True):
                if not isinstance(part, dict):
                    raise Refused("invalid_content")
                if part.get("type") == "text":
                    object_fields(part, {"type", "text"}, {"type", "text"})
                    parts.append(TextPart(text(part["text"])))
                elif part.get("type") == "image_url" and role == "user":
                    parts.append(image_part(part))
                    image_count += 1
                else:
                    raise Refused("unsupported_content")
        calls = []
        for call in sequence(item.get("tool_calls", []), 128):
            call = object_fields(
                call, {"id", "type", "function"}, {"id", "type", "function"}
            )
            if role != "assistant" or call["type"] != "function":
                raise Refused("invalid_tool_call")
            function = object_fields(
                call["function"], {"name", "arguments"}, {"name", "arguments"}
            )
            identifier = text(call["id"], 1024, identifier=True)
            name = text(function["name"], 256, identifier=True)
            arguments = strict_json(text(function["arguments"]))
            if identifier in seen_calls:
                raise Refused("duplicate_tool_call")
            seen_calls[identifier] = name
            calls.append(ToolCall(identifier, name, canonical_object(arguments)))
        tool_id = item.get("tool_call_id")
        name = item.get("name")
        if role == "tool":
            tool_id = text(tool_id, 1024, identifier=True)
            if (
                tool_id not in seen_calls
                or name is not None
                and name != seen_calls[tool_id]
            ):
                raise Refused("unresolved_tool_result")
            name = seen_calls[tool_id]
        elif tool_id is not None:
            raise Refused("unexpected_tool_call_id")
        if name is not None:
            name = text(name, 256, identifier=True)
            if role != "tool":
                raise Refused("unsupported_message_name")
        messages.append(
            Message(
                role,
                tuple(parts),
                isinstance(content, list),
                tuple(calls),
                tool_id,
                name,
            )
        )
    if image_count > MAX_IMAGES:
        raise Refused("image_count_bound")
    return CountRequest(model, tuple(messages), tuple(tools), thinking)
