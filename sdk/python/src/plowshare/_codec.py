"""Owning JSON boundary. No unchecked JSON object escapes into SDK contracts."""

from __future__ import annotations

import json
from dataclasses import is_dataclass
from importlib.resources import files
from typing import Mapping, cast

from . import contracts
from ._validation import check, finite
from ._values import UNSET

_SCHEMAS = cast(
    dict[str, object],
    json.loads(files(__package__).joinpath("_schemas.json").read_text()),
)
_DEFS = cast(dict[str, dict[str, object]], _SCHEMAS["$defs"])

_MODEL_BY_ID = {
    id(shape): contracts._MODELS[key]
    for key, shape in _DEFS.items()
    if key in contracts._MODELS
}


def _object(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or any(not isinstance(k, str) for k in value):
        raise ValueError("Expected an object at the protocol boundary")
    return cast(dict[str, object], value)


def _schema(value: object) -> dict[str, object]:
    return _object(value)


def _decode(
    schema: dict[str, object], value: object, input: bool, depth: int = 0
) -> object:
    """Validate recursively, then construct an immutable DTO or a typed collection."""
    check(schema, value)
    if depth > 64 or schema.get("forbidden"):
        raise ValueError("Contract depth or forbidden field")
    if "$ref" in schema:
        key = cast(str, schema["$ref"]).split("/")[-1]
        return _decode(_DEFS[key], value, input, depth + 1)
    if "anyOf" in schema:
        for variant in cast(list[dict[str, object]], schema["anyOf"]):
            try:
                return _decode(variant, value, input, depth + 1)
            except ValueError:
                continue  # A union accepts a complete declared shape, never a partial match.
        raise ValueError("Invalid protocol variant")
    if "const" in schema:
        literal = schema["const"]
        if type(value) is not type(literal) or value != literal:
            raise ValueError("Invalid protocol literal")
        return value
    kind = schema.get("type")
    if schema.get("optional") or kind == "null":
        if value is not None:
            raise ValueError("Expected null")
        return None
    if kind == "string":
        if not isinstance(value, str) or len(value) > 50 * 1024 * 1024:
            raise ValueError("Invalid bounded protocol text")
        return value
    if kind == "number":
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not finite(value)
        ):
            raise ValueError("Invalid finite protocol number")
        return value
    if kind == "boolean":
        if not isinstance(value, bool):
            raise ValueError("Expected a boolean")
        return value
    if kind == "array":
        if (
            not isinstance(value, (list, tuple))
            or len(value) > int(cast(int, schema.get("maxItems", 10000)))
            or len(value) < int(cast(int, schema.get("minItems", 0)))
        ):
            raise ValueError("Invalid bounded protocol collection")
        items = cast(list[dict[str, object]] | None, schema.get("prefixItems"))
        item = cast(dict[str, object] | None, schema.get("items"))
        return tuple(
            _decode(items[i] if items else _schema(item), v, input, depth + 1)
            for i, v in enumerate(value)
        )
    if kind == "object":
        row = _object(value)
        if len(row) > 10000 or any(
            k in row for k in ("__proto__", "prototype", "constructor")
        ):
            raise ValueError("Invalid protocol object")
        for key in cast(list[str], schema.get("required", [])):
            if key not in row:
                raise ValueError("Missing required protocol field: " + key)
        properties = cast(dict[str, dict[str, object]], schema.get("properties", {}))
        extra = schema.get("additionalProperties", False)
        result: dict[str, object] = {}
        for key, entry in row.items():
            field = properties.get(key)
            if field is None:
                if isinstance(extra, dict):
                    field = _schema(extra)
                elif input:
                    raise ValueError("Unsupported protocol field: " + key)
                else:
                    continue  # Forward-compatible responses expose only known DTO fields.
            result[key] = _decode(field, entry, input, depth + 1)
        model = _MODEL_BY_ID.get(id(schema))
        if model is not None:
            fields = contracts._FIELDS[model]
            return model(**{fields[k]: v for k, v in result.items()})
        from types import MappingProxyType

        return MappingProxyType(result)
    raise ValueError("Missing owned protocol shape")


def _wire(value: object, depth: int = 0) -> object:
    if depth > 64:
        raise ValueError("Contract depth exceeded")
    fields = contracts._FIELDS.get(type(value))
    if fields is not None and is_dataclass(value):
        return {
            field: _wire(entry, depth + 1)
            for field, attr in fields.items()
            if (entry := getattr(value, attr)) is not UNSET
        }
    if isinstance(value, Mapping):
        return {k: _wire(v, depth + 1) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [_wire(v, depth + 1) for v in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise ValueError("Expected a generated protocol DTO")


def encode_request(request: contracts.Request[object]) -> tuple[str, object]:
    operation = contracts._REQUESTS.get(type(request))
    if operation is None:
        raise ValueError("Expected an operation-specific generated request")
    value = (
        _wire(request.selection)
        if isinstance(
            request,
            (
                contracts.ConversationFollowRequest,
                contracts.RelayLogRequest,
                contracts.RelayTopicsRequest,
            ),
        )
        else _wire(request)
    )
    schema = _schema(_object(_SCHEMAS["inputs"])[operation])
    _decode(schema, value, True)
    return operation, value


def decode_reply(operation: str, value: object) -> object:
    return _decode(_schema(_object(_SCHEMAS["results"])[operation]), value, False)


def decode_push(value: object) -> contracts.ServerPush:
    row = _object(value)
    if "protocol_version" in row:
        from ._protocol import PROTOCOL_VERSION

        if row.get("protocol_version") != PROTOCOL_VERSION or row.get("id") is not None:
            raise ValueError("Invalid notification envelope")
        row = dict(_object(row.get("payload")), type=row.get("type"))
    return cast(contracts.ServerPush, _decode(_schema(_SCHEMAS["pushes"]), row, False))


def deployment_matches(operation: str, request: object, result: object) -> bool:
    """A structural reply cannot certify a different project's retained receipt."""
    if operation.startswith(("tool.scope.host.", "tool.scope.worker.")):
        return hosted_matches(operation, request, result)
    if operation in {
        "application.identity.status",
        "application.token.issue",
        "application.token.receipt",
    }:
        asked = _object(request)
        if isinstance(result, contracts.ApplicationIdentityStatusDto):
            identity = result.identity
            return (
                operation == "application.identity.status"
                and result.project == asked.get("project")
                and (
                    identity is None
                    or identity.project == result.project
                    and identity.principal == "@service/" + identity.token_id
                )
            )
        receipt = (
            result.receipt
            if isinstance(result, contracts.ApplicationCredentialDto)
            else result
        )
        if not isinstance(receipt, contracts.ApplicationTokenReceiptDto):
            return False
        request_id = asked.get("requestId")
        return (
            receipt.project == asked.get("project")
            and isinstance(request_id, str)
            and receipt.request_id.lower() == request_id.lower()
            and receipt.principal == "@service/" + receipt.token_id
        )
    if operation not in {
        "application.deploy",
        "application.activate",
        "application.deployment.status",
        "application.deployment.receipt",
    }:
        return True
    request = _object(request)
    if isinstance(result, contracts.ApplicationDeploymentStatusDto):
        return (
            operation == "application.deployment.status"
            and result.project == request.get("project")
        )
    if not isinstance(result, contracts.ApplicationDeploymentReceiptDto):
        return False
    request_id = request.get("requestId")
    revision = request.get("revision")
    return (
        result.project == request.get("project")
        and isinstance(request_id, str)
        and result.request_id.lower() == request_id.lower()
        and (
            operation != "application.activate"
            or isinstance(revision, str)
            and result.release.revision.lower() == revision.lower()
        )
    )


def hosted_matches(operation: str, request: object, result: object) -> bool:
    """Correlate only closed hosted operations; IDs never establish server authority."""
    asked = _object(_wire(request))
    answered = _wire(result)
    if operation == "tool.scope.host.list":
        return isinstance(answered, list) and all(
            _object(row).get("project") == asked.get("project") for row in answered
        )
    row = _object(answered)
    if operation in {"tool.scope.worker.credential", "tool.scope.worker.read"}:
        keys = ["binding", "registration", "invocation"]
        if operation.endswith("credential"):
            keys.append("purpose")
        return all(row.get(k) == asked.get(k) for k in keys)
    if operation == "tool.scope.worker.status":
        return row.get("binding") == asked.get("binding")
    nested = {
        "tool.scope.worker.publish": "publication",
        "tool.scope.worker.consume": "consumption",
        "tool.scope.worker.ack": "acknowledgement",
    }.get(operation)
    if nested is not None:
        port = _object(asked.get(nested))
        if not all(
            row.get(k) == port.get(k)
            for k in ["project", "topic", "group", "consumerId", "requestId", "batchId"]
            if k in port and k in row
        ):
            return False
        if operation != "tool.scope.worker.consume":
            return True
        events, limit = row.get("events"), port.get("limit")
        return (
            isinstance(events, list)
            and isinstance(limit, (int, float))
            and len(events) <= limit
        )
    binding = (
        row if operation == "tool.scope.host.status" else _object(row.get("binding"))
    )
    if any(binding.get(k) != asked[k] for k in ["project", "binding"] if k in asked):
        return False
    if operation == "tool.scope.worker.renew":
        return row.get("registration") == asked.get("registration")
    if operation == "tool.scope.worker.register":
        return row.get("registration") == asked.get("requestId") and binding.get(
            "generation"
        ) == asked.get("expectedGeneration")
    if "requestId" in asked and row.get("requestId") != asked["requestId"]:
        return False
    if operation in {"tool.scope.host.receipt", "tool.scope.host.status"}:
        return True
    expected = asked.get("expectedGeneration")
    if not isinstance(expected, (int, float)) and expected is not None:
        return False
    if row.get("operation") != operation or binding.get("generation") != (
        1 if expected is None else expected + 1
    ):
        return False
    if operation == "tool.scope.host.save":
        return binding.get("definition") == asked.get("definition")
    return binding.get("enabled") == (asked.get("action") == "ENABLE") and binding.get(
        "deleted"
    ) == (asked.get("action") == "DELETE")
