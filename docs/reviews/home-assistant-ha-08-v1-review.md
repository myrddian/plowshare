# HA-08 independent review — v1

Verdict: **APPROVE**. No open blocking findings against this frozen structured
AI Task candidate.

Reviewer: `/root/ha_structured_sdk_reviewer`.
Implementer: `/root/ha_structured_sdk_implementer`.
Date: 22 November 2026 (Australia/Melbourne).
Candidate: `docs/reviews/home-assistant-ha-08-v1-candidate.json`.
Candidate SHA-256:
`9685b039703ae04d9a10727ff042c1b2f922cee179a19a029b19cb56870fad88`.
Base: `6de685f63e060f3003cb93233d2567e004269755`.

## Independence and exact source

The reviewer authored no implementation, tests, plan, ledger, core or SDK source
and ran no builds, tests, installations, database, model, device or deployment
operations. Review inspected the current adapter, actual installed HA 2026.9.4
action/helper, selector and Voluptuous contracts, frozen evidence and coordinator
results. This new report is the reviewer's only repository edit.

Independently verified the candidate digest, all five bound file hashes, its
frozen ledger snapshot and scoped diff, and its accepted HA-07 dependency
manifest. Implementer and reviewer are independent, and HA-08 is still in review.
The completed check's 28-file source snapshot matches current bytes. All 4,042
protected core, protocol, SDK and shared-runtime hashes remain unchanged.

HA-08 explicitly supersedes the handed-off structured-results seam in
`ai_task.py` and targeted `test_ai_task.py` coverage from accepted HA-07. Those
two files correctly differ from the historical HA-07 candidate. All other
HA-07-bound files still match; the original candidate, review and source evidence
remain historical acceptance records. Existing text behavior, setup, storage,
gateway and transport cases were rechecked with the revised seam.

## Supported behavior

The compiler accepts only exact closed flat Voluptuous schemas with literal
Required/Optional string fields and the documented Text, Number, Boolean and
Select selectors. It produces immutable typed specifications before SDK
submission. Defaults, complex keys, subclasses, nested/callable schemas,
unsupported extra-field policies and unknown selector options fail explicitly.
An empty closed schema and absent optional fields retain their actual semantics.

Text supports scalar strings and string arrays. Numbers accept actual finite
JSON numbers, exclude booleans and enforce min/max. Booleans require actual JSON
booleans. Select validates exact configured values, including the value rather
than label in labeled options, and supports multiple arrays; custom values are
unsupported. Known UI metadata is validated and discarded transparently. Text
type is not an output constraint, Number.step does not round values, and
read_only does not create authorization. HA's coercing validators are not called
on model output.

Original HA instructions remain an exact prefix to separately marked adapter
format guidance in ordinary task content. Prepared HA system text passes through
unchanged with the accepted task/user semantics. No native system-role parity,
response-format parameter, prompt normalization, cache correction or additional
core capability is introduced.

Only completed text is parsed, before recording successful assistant content or
returning HA data. Duplicate keys, non-finite numbers, malformed JSON, invalid
Unicode, wrong types, missing/unknown fields and enum/range violations fail
without coercion, Markdown stripping or repair. Immutable result items convert
to ordinary dictionaries/lists only at the HA return boundary. Invalid completed
work raises a truthful error while the original gateway/platform result remains
retained; no retry, model call, research or action is added to fix presentation.
Pending or uncertain prose cannot become generated data.

Parser bytes, nesting, fields, enum options, lists and metadata have independent
finite limits; the complete instructions also pass the existing request limit
before submission. These resource bounds are separate from prose word guidance.
A modest word-count overrun remains accepted within the raw byte boundary.

## Actual verification

The coordinator's complete check passed Ruff lint, Ruff format checking and
**127 tests in 1.287 seconds**, with no failures, errors or skips. The reviewer
read the complete log and independently matched these artifacts:

| Artifact | SHA-256 |
| --- | --- |
| `/private/tmp/ha-08-full-v1.log` | `1ff9626a10cf139169cb1162f2914a6a90d48ee1f23b34176aeb47237cf442de` |
| `/private/tmp/ha-08-full-v1-source.json` | `0e89bdb5ce4287ea82f8e272b2d830ad410e354ebbd6c673ecfab6e034ce36f3` |

Coverage includes the real HA structured generate_data service conversion,
unchanged prepared context and instruction prefix, all four selector families,
strict invalid-output/resource cases and immutable data copies. Invalid completed
results assert exactly one gateway submission and unchanged retained fake text;
existing gateway/SDK and persistence regressions provide separate lifecycle
evidence. The source remained unchanged during execution. Expected diagnostics
from deliberate negative cases do not represent test failures.

## Acceptance limits

This approves HA-08 for coordinator acceptance only. Package availability and
operator documentation remain HA-11 work. No installation, target-instance
compatibility, real provider schema compliance, live grants/device behavior,
voice operation or deployment is proved by these local fixtures. Arbitrary HA
schema constructs remain unsupported; a model's JSON compliance is checked,
never guaranteed. No automatic shared recovery capability, native system-role
equivalence or cache performance claim is made. Optional cache measurements,
deployment and live validation keep their existing authorization boundaries.
