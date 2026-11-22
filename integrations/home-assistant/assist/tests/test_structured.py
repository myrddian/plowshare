"""Strict schema/output boundaries against the installed HA selector classes."""

import json
import unittest
from dataclasses import FrozenInstanceError

import support  # noqa: F401
import voluptuous as vol
from homeassistant.helpers.selector import (
    BooleanSelector,
    NumberSelector,
    SelectSelector,
    TextSelector,
)

from custom_components.plowshare.structured import (
    MAX_FIELDS,
    MAX_LIST_ITEMS,
    MAX_METADATA_BYTES,
    MAX_OPTIONS,
    MAX_OUTPUT_BYTES,
    StructureError,
    compile_structure,
    parse_completed,
    render_instructions,
)


def one(selector, *, required=True):
    marker = vol.Required if required else vol.Optional
    return compile_structure(vol.Schema({marker("value"): selector}))


class StructuredTests(unittest.TestCase):
    def test_four_kinds_required_optional_and_strict_values(self):
        spec = compile_structure(
            vol.Schema(
                {
                    vol.Required("note", description="Household note"): TextSelector(),
                    vol.Required("reading"): NumberSelector({"min": 0, "max": 10}),
                    vol.Required("enabled"): BooleanSelector(),
                    vol.Required("mode"): SelectSelector(
                        {"options": [{"value": "eco", "label": "Economy"}]}
                    ),
                    vol.Optional("omitted"): TextSelector(),
                },
                required=True,
            )
        )
        data = {
            "note": "α household note",
            "reading": 1.25,
            "enabled": False,
            "mode": "eco",
        }
        result = parse_completed(json.dumps(data), spec)
        self.assertEqual(result.as_ha_data(), data)
        self.assertIs(type(result.as_ha_data()["reading"]), float)
        with self.assertRaises(FrozenInstanceError):
            spec.fields = ()
        self.assertNotIn("omitted", result.as_ha_data())

    def test_multiple_text_and_select_values_are_immutable_then_copied_for_ha(self):
        for selector, values in (
            (TextSelector({"multiple": True}), ["one", "α"]),
            (SelectSelector({"options": ["a", "b"], "multiple": True}), ["a", "b"]),
        ):
            with self.subTest(selector=selector):
                spec = one(selector)
                result = parse_completed(json.dumps({"value": values}), spec)
                self.assertEqual(result.items[0].value, tuple(values))
                first = result.as_ha_data()
                first["value"].append("mutated")
                self.assertEqual(result.as_ha_data(), {"value": values})
                self.assertEqual(
                    parse_completed('{"value":[]}', spec).as_ha_data(), {"value": []}
                )

    def test_empty_closed_schema_and_optional_absence_are_supported(self):
        self.assertEqual(
            parse_completed("{}", compile_structure(vol.Schema({}))).as_ha_data(), {}
        )
        self.assertEqual(
            parse_completed("{}", one(TextSelector(), required=False)).as_ha_data(), {}
        )

    def test_schema_rejections_never_invoke_custom_validators_or_defaults(self):
        def forbidden(*args):
            raise AssertionError("Adapter must not execute schema validators/defaults")

        class DerivedText(TextSelector):
            pass

        class DerivedSchema(vol.Schema):
            pass

        cases = [
            {},
            DerivedSchema({}),
            vol.Schema(forbidden),
            vol.Schema({"value": TextSelector()}),
            vol.Schema({vol.Required("value"): forbidden}),
            vol.Schema({vol.Required("value"): str}),
            vol.Schema({vol.Required("value"): vol.Schema({"nested": str})}),
            vol.Schema({vol.Required("value"): DerivedText()}),
            vol.Schema({vol.Required("value", default="x"): TextSelector()}),
            vol.Schema({vol.Optional("value", default=forbidden): TextSelector()}),
            vol.Schema({vol.Exclusive("value", "group"): TextSelector()}),
            vol.Schema({vol.Required(vol.Any("a", "b")): TextSelector()}),
            vol.Schema({vol.Required(12): TextSelector()}),
            vol.Schema({vol.Required("value", description={}): TextSelector()}),
            vol.Schema({}, extra=vol.ALLOW_EXTRA),
            vol.Schema({}, extra=vol.REMOVE_EXTRA),
            vol.Schema({}, extra=99),
            vol.Schema({}, required="true"),
        ]
        for schema in cases:
            with self.subTest(schema=schema), self.assertRaises(StructureError):
                compile_structure(schema)

    def test_unknown_or_mutated_selector_config_rejected(self):
        cases = [
            (TextSelector(), "future_constraint", True),
            (TextSelector(), "multiple", "yes"),
            (TextSelector(), "type", "unknown"),
            (BooleanSelector(), "read_only", 1),
            (NumberSelector(), "min", float("nan")),
            (NumberSelector(), "max", float("inf")),
            (NumberSelector(), "step", True),
            (SelectSelector({"options": ["a"]}), "custom_value", True),
            (SelectSelector({"options": ["a"]}), "sort", 1),
            (SelectSelector({"options": ["a"]}), "options", []),
            (SelectSelector({"options": ["a"]}), "options", ["a", "a"]),
            (
                SelectSelector({"options": ["a"]}),
                "options",
                [{"value": "a", "label": "A", "future": 1}],
            ),
        ]
        for selector, key, value in cases:
            selector.config[key] = value
            with (
                self.subTest(selector=selector, key=key),
                self.assertRaises(StructureError),
            ):
                one(selector)
        with self.assertRaises(StructureError):
            one(NumberSelector({"min": 10, "max": 1}))

    def test_known_ui_options_do_not_invent_value_constraints(self):
        text = one(
            TextSelector(
                {
                    "type": "email",
                    "prefix": "ui",
                    "suffix": "only",
                    "autocomplete": "name",
                    "multiline": True,
                    "read_only": True,
                }
            )
        )
        self.assertEqual(
            parse_completed('{"value":"ordinary text"}', text).as_ha_data(),
            {"value": "ordinary text"},
        )
        number = one(
            NumberSelector(
                {
                    "step": 1,
                    "mode": "box",
                    "unit_of_measurement": "m",
                    "translation_key": "measurement",
                }
            )
        )
        self.assertEqual(
            parse_completed('{"value":1.25}', number).as_ha_data(), {"value": 1.25}
        )
        choice = one(
            SelectSelector(
                {
                    "options": [{"value": "a", "label": "Label A"}],
                    "mode": "dropdown",
                    "sort": True,
                    "translation_key": "choice",
                }
            )
        )
        with self.assertRaises(StructureError):
            parse_completed('{"value":"Label A"}', choice)

    def test_wrong_types_missing_extra_fields_and_ranges_rejected(self):
        cases = [
            (TextSelector(), [1, True, None, [], {}]),
            (BooleanSelector(), [1, 0, "true", "false", None]),
            (NumberSelector({"min": 0, "max": 10}), [True, "1", None, -1, 11]),
            (SelectSelector({"options": ["eco"]}), ["Eco", "Economy", 1, None]),
            (TextSelector({"multiple": True}), ["text", [1], [None], {}]),
            (
                SelectSelector({"options": ["eco"], "multiple": True}),
                [["unknown"], "eco", [1]],
            ),
        ]
        for selector, values in cases:
            for value in values:
                with (
                    self.subTest(selector=selector, value=value),
                    self.assertRaises(StructureError),
                ):
                    parse_completed(json.dumps({"value": value}), one(selector))
        for text in ("{}", '{"value":"valid","extra":0}'):
            with self.subTest(text=text), self.assertRaises(StructureError):
                parse_completed(text, one(TextSelector()))

    def test_invalid_json_duplicates_nonfinite_unicode_and_fences_rejected(self):
        spec = one(NumberSelector())
        for text in (
            "not JSON",
            "[]",
            "null",
            "1",
            "{} {}",
            '{"value":1,}',
            '{"value":1,"value":2}',
            '{"value":1,"va\\u006cue":2}',
            '{"value":NaN}',
            '{"value":Infinity}',
            '{"value":-Infinity}',
            '{"value":1e9999}',
            '```json\n{"value":1}\n```',
        ):
            with self.subTest(text=text), self.assertRaises(StructureError):
                parse_completed(text, spec)
        with self.assertRaises(StructureError):
            parse_completed('{"value":"\\ud800"}', one(TextSelector()))

    def test_raw_byte_depth_collection_and_metadata_limits(self):
        for text in (
            '{"value":"' + "x" * MAX_OUTPUT_BYTES + '"}',
            '{"value":"' + "α" * (MAX_OUTPUT_BYTES // 2) + '"}',
            '{"value":' + "[" * 10 + "0" + "]" * 10 + "}",
        ):
            with self.subTest(prefix=text[:30]), self.assertRaises(StructureError):
                parse_completed(text, one(TextSelector()))
        with self.assertRaises(StructureError):
            parse_completed(
                json.dumps({"value": ["x"] * (MAX_LIST_ITEMS + 1)}),
                one(TextSelector({"multiple": True})),
            )
        with self.assertRaises(StructureError):
            compile_structure(
                vol.Schema(
                    {
                        vol.Optional(str(i)): TextSelector()
                        for i in range(MAX_FIELDS + 1)
                    }
                )
            )
        with self.assertRaises(StructureError):
            one(SelectSelector({"options": [str(i) for i in range(MAX_OPTIONS + 1)]}))
        with self.assertRaises(StructureError):
            compile_structure(
                vol.Schema(
                    {
                        vol.Required(
                            "value", description="x" * (MAX_METADATA_BYTES + 1)
                        ): TextSelector()
                    }
                )
            )

    def test_brackets_and_escaped_quotes_inside_strings_do_not_count_as_depth(self):
        value = '[{\\"' * 100
        self.assertEqual(
            parse_completed(
                json.dumps({"value": value}), one(TextSelector())
            ).as_ha_data(),
            {"value": value},
        )

    def test_original_instructions_preserved_and_prose_overruns_not_counted(self):
        original = "  Write about α. Target 100 words.\nKeep source context.\n"
        spec = one(TextSelector())
        rendered = render_instructions(original, spec)
        self.assertTrue(rendered.startswith(original + "\n\n[Plowshare"))
        self.assertIn('"name":"value"', rendered)
        prose = " ".join(["household"] * 125)
        self.assertEqual(
            parse_completed(json.dumps({"value": prose}), spec).as_ha_data(),
            {"value": prose},
        )


if __name__ == "__main__":
    unittest.main()
