"""The same validation and fingerprint fixtures run in every SDK, without MCP or a database."""

import json
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Mapping

from plowshare.schema_definition import SchemaDefinition
from plowshare.tool_schemas import (
    decode_tool_schema,
    decode_tool_value,
    schema_fingerprint,
    schema_fingerprint_matches,
    schema_wire,
    validate_schema_arguments,
    validate_tool_schema,
)
from plowshare.tools import ToolDeclaration, _decode


class SharedToolSchemaTest(unittest.TestCase):
    def test_shared_profile_and_fingerprints(self) -> None:
        source: object = json.loads(
            (
                Path(__file__).resolve().parents[3]
                / "test-support/contracts/tool-schemas.json"
            ).read_text()
        )
        if not isinstance(source, dict):
            raise AssertionError("Missing fixtures")
        rows: object = source.get("cases")
        if not isinstance(rows, list):
            raise AssertionError("Missing cases")
        entries: list[object] = rows
        for entry in entries:
            if not isinstance(entry, dict):
                raise AssertionError("Invalid fixture")
            row: dict[str, object] = entry
            schema_source = row["schemaSource"]
            args_source = row["argumentsSource"]
            if not isinstance(schema_source, str) or not isinstance(args_source, str):
                raise AssertionError("Invalid fixture text")
            with self.subTest(name=row["name"]):
                if row["validSchema"] is not True:
                    with self.assertRaises(ValueError):
                        validate_tool_schema(
                            decode_tool_schema(json.loads(schema_source))
                        )
                    continue
                schema = validate_tool_schema(
                    decode_tool_schema(json.loads(schema_source))
                )
                self.assertEqual(
                    json.loads(json.dumps(schema_wire(schema))),
                    json.loads(schema_source),
                )
                self.assertEqual(schema_fingerprint(schema), row["fingerprint"])
                self.assertFalse(schema_fingerprint_matches(schema, "0" * 64))
                legacy = row.get("legacyFingerprint")
                if isinstance(legacy, str):
                    self.assertNotEqual(schema_fingerprint(schema), legacy)
                    self.assertTrue(schema_fingerprint_matches(schema, legacy))
                    self.assertFalse(
                        schema_fingerprint_matches(
                            replace(schema, description="changed"), legacy
                        )
                    )
                if row["validArguments"] is True:
                    validate_schema_arguments(
                        schema, decode_tool_value(json.loads(args_source))
                    )
                else:
                    with self.assertRaises(ValueError):
                        validate_schema_arguments(
                            schema, decode_tool_value(json.loads(args_source))
                        )

    def test_nested_version_and_immutable_values(self) -> None:
        schema = SchemaDefinition(
            type_="object",
            properties={
                "value": SchemaDefinition(
                    type_=("array", "null"), items=SchemaDefinition(type_="integer")
                )
            },
            required=("value",),
            additional_properties=False,
        )
        tool = ToolDeclaration("inspect", "Inspect nested values", input_schema=schema)
        fingerprint = schema_fingerprint(schema)
        call = _decode(
            json.dumps(
                {
                    "schema": "plowshare-tool/2",
                    "invocationId": "11111111-1111-1111-1111-111111111111",
                    "project": "fixture",
                    "provider": "scanner",
                    "tool": "inspect",
                    "account": "caller",
                    "run": "run",
                    "call": "call",
                    "deadline": "2099-01-01T00:00:00Z",
                    "schemaFingerprint": fingerprint,
                    "arguments": {"value": [1, 2]},
                }
            )
        )
        tool.validate(call.arguments)
        self.assertEqual(call.arguments["value"], (1, 2))
        self.assertIsInstance(call.arguments, Mapping)
        with self.assertRaises(ValueError):
            tool.validate({})
        tool.validate({"value": None})

    def test_cycle_and_depth_bounds(self) -> None:
        value: object = None
        for _ in range(34):
            value = [value]
        with self.assertRaises(ValueError):
            decode_tool_value(value)
        with self.assertRaises(ValueError):
            decode_tool_value([None] * 1025)
