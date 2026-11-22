# HA-08 implementation: structured AI Task subset

Implementer: `/root/ha_structured_sdk_implementer`. Independent reviewer:
`/root/ha_structured_sdk_reviewer`. This is a new adapter-only delta after the
independently accepted HA-07 source, not a rewrite of its frozen evidence.

## Delivered contract

`structured.py` converts the actual Home Assistant 2026.9.4 action/helper
`vol.Schema` boundary into frozen typed specifications. The supported schema is
a closed flat root object with exact Required/Optional literal string fields,
optional text descriptions, and exact Text, Number, Boolean or Select selector
classes. Required/optional distinctions are preserved; an empty closed schema
is supported. Schema/marker/selector subclasses, defaults, complex keys, nested
schemas, callable validators, extra-field policies and unknown selector options
are rejected before SDK submission.

Text supports scalar strings and multiple string arrays. Number accepts actual
JSON integers/floats, rejects booleans and non-finite values, and enforces the
configured min/max. Boolean accepts actual booleans. Select accepts configured
enum values, including values from label/value options, with optional multiple
arrays; custom values are unsupported. Known UI metadata is checked and
discarded explicitly: text type/prefix/suffix/autocomplete/multiline,
number mode/step/unit/translation key, select mode/sort/translation key/labels,
and selector read_only. These do not invent value validation, numeric rounding
or authorization. HA's coercing selector/schema validators are never called.

Structured requests preserve the original HA task instructions as an exact
prefix and append a clearly marked adapter output contract through the existing
`TaskRequest.instructions` field. The prepared HA system text is passed
unchanged through the existing context. This retains the documented task/user
semantics: no native response-format API, system-role extension, prompt cache
rewriting, core/SDK change or extra inference allowance is introduced.

Only completed generated text is parsed. Duplicate keys (including escaped key
equivalents), invalid JSON, non-finite constants/exponents, wrong types,
unknown/missing fields, enum mismatches and invalid Unicode are rejected without
coercion, Markdown removal, repair, model retry, research or action replay.
Validated data becomes immutable typed result items and is materialized into
ordinary HA dictionaries/lists only at the return boundary. Invalid completed
data raises a truthful HA error; the original completed work and receipt remain
owned by the existing Plowshare/gateway lifecycle. No generated prose is copied
into the adapter's durable journal or exception messages.

Finite resource boundaries are 64 fields, 128 enum options per field, 256 array
elements, 256 KiB completed UTF8 JSON and container depth 3, checked before
unbounded parser recursion. Metadata has explicit UTF8 limits: 256 bytes per
field name, 4096 per description/UI string and 1024 per enum value. The final
request also passes the existing TaskRequest limits before submission. These
are parser/protocol budgets, not model prose lengths. Strings have no exact
word-count validation or presentation cutoff; modest prose overruns are valid
within the independent raw-byte limit.

## Owned changes and verification

- Added `custom_components/plowshare/structured.py` and `tests/test_structured.py`.
- Updated only the handed-off structured AI Task seam in `ai_task.py` and added
  targeted action/helper boundary tests in `tests/test_ai_task.py`.
- Preserved text-only behavior, task isolation, unsupported tool/attachment
  rejection and existing gateway/result handling.
- Owned Ruff formatting and lint checks passed. No unit, build, database,
  deployment, live-model or device tests were executed by the implementer;
  coordinator owns execution and exact-source evidence.

The coordinator subsequently ran the complete Assist check on this frozen
source: Ruff lint/format and **127 tests passed in 1.287 seconds**, with every
shared source/test hash unchanged during execution. Exact local evidence is
`/private/tmp/ha-08-full-v1.log` and
`/private/tmp/ha-08-full-v1-source.json`. This includes the actual HA structured
service/context cases, one-submission invalid completed-data cases, all existing
text-generation behavior and earlier lifecycle/gateway regressions.

Final owned source SHA256 values:

| Source | SHA256 |
| --- | --- |
| `custom_components/plowshare/structured.py` | `1d60788b772f7c2ec6f3c4d79acc5ee23a914be4b33fc70df8092803dcc50b5b` |
| `custom_components/plowshare/ai_task.py` | `28d806febd0a7918c1227bfbf665368789131c0e6e644bbb6baeb3d7cabf7103` |
| `tests/test_structured.py` | `304cbdb7631acc76b372fa6be42bbaf2f7c15c1f23ca4fe6a9f5281c9406ead0` |
| `tests/test_ai_task.py` | `22f89695978307fa7095408396aed8d1626c5b034d217e35a46d4b557aa64c07` |

HA-08 explicitly supersedes only the handed-off `ai_task.py` structured-results
seam and targeted `test_ai_task.py` coverage from HA-07. HA-07's original report,
candidate, review and evidence remain immutable historical acceptance records;
HA-08's new candidate binds the revised seams and their rechecked text behavior.

New tests cover the four selector families, optional/required fields, actual HA
generate_data action conversion, unchanged prepared system text/instruction
prefix, unsupported-before-submit schemas, request/resource limits, strict JSON
and data rejection, UI-only semantics, immutable data/copying and a tolerated
125-word string for a task requesting 100 words. Invalid completed-result tests
assert one gateway submission and retained unchanged fake completed text; actual
durable result/receipt evidence remains the accepted gateway tests and subsequent
coordinator-run integration checks, not a claim of live server validation.

Package availability and public operator documentation belong to HA-11. No
package guard, setup/configuration, storage, shared runtime, SDK, core, Java or
deployment source changed for this node. Coordinator-run checks are complete;
independent review remains required before the node can be marked done.
