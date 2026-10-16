# ADR 0029: Portable information interchange

Status: accepted implementation contract, 16 October 2026. Deployment is separate.

Plowshare needs public interchange for external authoring and Git workflows.
Existing text windows do not carry original bytes, attached schemas, revision
identities and relationship semantics together. A sequence of SDK reads cannot
provide one permission snapshot, and separate admission calls cannot provide an
atomic import with retained origin and receipt recovery. This is general platform
work under ADR 0001, independent of an adapter; direct database access is not an
SDK alternative.

## Contract

Use authenticated `information.interchange.*` WebSocket operations, typed in all
SDKs. Export exact revision selections, never an inferred filesystem hierarchy.
Readable export returns original UTF-8 Markdown or a labelled extraction, with a
JSON sidecar. A versioned JSON bundle contains original bytes as canonical base64,
SHA-256 hashes, exact schema bindings, owner-authored tags, foreign provenance and
relationships whose endpoints are included. Paths are portable views with strict
relative-path validation; no operation reads or writes a server filesystem path.

Bound a bundle to 16 revisions, 64 relationships, 2 MiB of original bytes and
8 MiB serialized JSON. Refuse larger selections explicitly. Include required input
and reference dependencies within those bounds, checking each through existing
live access predicates. Fail closed when required closure cannot be exported;
do not disclose inaccessible IDs or return partial content. Optional relationships
outside the bundle are omitted, with a constant coverage declaration rather than
private target identifiers or counts. Selected history is explicit and immutable.

Import requires an explicit Personal/project selection and a stable request UUID.
Every foreign resource maps to an explicitly named destination: create-only, or
revise against an exact current revision. Foreign history is admitted in ordinal
order as new local revisions, with fresh UUIDs. Foreign schema versions must map
explicitly to assignable local versions with identical immutable definitions;
managers use existing registry operations to copy/activate definitions first.
Import cannot create types, activate schemas or infer a slug-based match.
Unchanged dependencies may be reused only through explicit foreign-to-local
revision mappings, with live access and matching bytes/media/schema/metadata.
A reused history prefix must belong to the explicit destination resource.
Callers explicitly choose relationship restoration or omission; restoring a link
requires an imported source. Edited bytes have a new content hash while foreign
provenance retains the original source hash.

Validate the complete bundle and destination map before effects. Recheck ownership,
membership, expected heads and type authority inside the committing transaction.
Use the existing information write gates, hooks and durable receipt lifecycle;
admission of revisions, dependencies, relationships and imported-origin records
commits atomically. Reconcile uncertain delivery with the original request; never
replay a mutation with a new identity. Receipt reads recheck current access.

Imports are unprocessed source revisions with `explicit_only` processing policy.
Foreign reports/evidence remain labelled claims, not locally authenticated model
output or restored citations. The authenticated importer owns/revises local records;
foreign authorship is retained separately. No imported permission, project grant,
processing authorization, model output, allowance or lifecycle state is restored.
Selected local processing remains an explicit operation. Existing local policies
and dependency constraints govern all imported records and relationships.

Navigation writes must be enabled to restore relationships. Link targets map to
local resources; pins map to exact local revisions. Required restrictions become
retained dependencies and cannot disappear when an ordinary imported link is
removed. Imported-origin metadata is immutable and redacted with deletion.

## Consequences and verification

This supports bounded document exchange, not server backup/restore, arbitrary
archive extraction, live synchronization, permissions migration or collaborative
editing. Copies are governed by explicitly selected destination policies; foreign
policy claims neither establish membership nor prove redistribution authority.
Reused local revisions keep their original enforced dependencies. Exporting a
document does not grant later access to the source server.
An external copy cannot be remotely revoked; policy is checked at export time.

Verify byte preservation, hashes, schema/metadata round trips, foreign-to-local
mapping, explicit collision decisions, selected history, live/pinned links,
malformed paths/base64, hidden dependencies, permission refusal, stale heads,
atomic rollback, receipt recovery (including schema/metadata JSON key reordering),
deletion and zero automatic processing. Use
mock repositories for service behavior; focused PostgreSQL tests prove migration,
SQL enforcement and transaction semantics. All SDK contracts and human-facing
interchange helpers share the public operations.
