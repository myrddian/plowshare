# Bounded recovery of model-generated Information metadata

Status: accepted

## Platform need

Information tagging and grouping are server-owned metadata stages for every
retained source and report. A model can return valid metadata wrapped in a JSON
code fence despite the tool-free worker's response instructions. Strict wire
parsing rejected these already-paid answers and left automatic tags unavailable.
This is a general model-output boundary problem, independent of any Application.
The user authorized correcting tagging after observing this failure.

## Decision

The Information metadata codec uses the existing shared `LlmJson` parser for
bounded local syntax recovery. Its existing 64 KiB input limit remains, and the
shared parser bounds nesting and recovery work. Known host refusals are rejected
before recovery. Recovered values still pass the existing strict typed metadata
conversion: unknown fields, duplicate fields, ambiguous multiple roots, scalar
coercions, invalid tags and invented group members remain refused. Document
attribution still requires exact source-supported evidence; syntax recovery
cannot grant permissions, establish attribution or change user tags.

The processing lifecycle, generation fences, captured allowance and paid-response
checkpoint remain unchanged. No extra model call is introduced. After the server
fix is deployed, an explicit retry of failed processing can read the stored paid
response again. A rebuild has different regeneration semantics and is unnecessary
for a parsing failure. Invalid output is never converted into empty success.

## Alternatives and compatibility

An SDK consumer can set separate user tags, but that masks neither a failed
server metadata stage nor its attribution/grouping failure. Changing an
Application's prompt cannot repair a previously retained system-worker answer.
Use the general server model-output codec rather than an integration-specific
manual-tag fallback. Public request/tool parsers stay strict; this change adds no
SDK operation, schema, permission, worker enrollment or database migration.

Pure codec tests cover fenced and recoverable output, typed field rejection,
source-backed attribution, grouping membership, host refusals and parser limits.
Mocked repository coverage verifies the same paid answer can be converted on
continuation without invoking the model again. Persistence is unchanged, so
PostgreSQL tests are not required.
