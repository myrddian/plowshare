# Authored information

Content types validate structured metadata on existing information revisions.
They do not verify prose, claims, citations or execution instructions. Bodies,
evidence and permissions still belong to the [information system](information-system.md).

## Use Library

Open Library's sources or reports and select your personal collection or a project.
The server advertises `information.contentTypes.v1` and the
`plowshare-document-metadata/1` profile. Older servers keep the untyped workflow.

1. Open **Document types**. Personal owners and project managers can create an
   immutable draft from an object schema, inspect its version UUID and activate it.
   Activating a new version deprecates the previous active version. Deprecation is
   permanent; create another version to change the definition.
2. Choose **Add source**, enter the body, select an active type and enter the
   complete metadata object. A schema default is an annotation; the server never
   fills it in. Invalid metadata produces bounded field diagnostics and retains
   no revision. URL imports initially create untyped sources; assign metadata after
   their retained revision exists.
3. Open the saved revision. **Document metadata and processing** shows the exact
   bound schema, metadata, reviser and projection origins. Schema conformance is
   distinct from factual verification.
4. Edit the complete metadata object and choose keep, assign or clear typing.
   **Validate metadata** is a read-only preview. **Save metadata-only revision**
   creates another immutable revision against the version currently displayed.
   On a conflict, refresh the source and reapply the edit; the server never merges
   it automatically or substitutes a newer head.
5. A metadata-only revision copies compatible, complete projections without
   document-model calls. Missing work stays pending. Set a processing allowance
   and explicitly select stages to request work. Missing prerequisites are included;
   unrelated stages are not authorised. Changing the allowance alone starts no work.

Creating types requires management authority; changing a document requires its
existing resource owner. Membership, metadata fields and schema authorship do not
grant editing rights. Shared readers can inspect a bound contract through its
attached revision without gaining registry or assignment authority.

## Public operations

Use authenticated WebSocket operations. Type-aware clients supply
`"view":"content-types-v1"`; `information.types.capabilities` is the read-only
probe and returns the profile and whether authored writes are enabled.

| Operation | Purpose |
| --- | --- |
| `information.types.list` | List active/deprecated versions; `drafts:true` requires management authority |
| `information.types.get` | Read a version in the selected registry, or through `attachedRevision` |
| `information.types.validate` | Validate a complete metadata object; returns status and bounded issues |
| `information.types.version.create` | Create a draft; existing types require `type` and `expectedControlRevision`, without replacement identity fields |
| `information.types.activate`, `.deprecate` | Change lifecycle with the current type control revision |
| `information.content.get` | Read a revision's binding, immutable lineage, processing policy and reuse provenance |
| `information.content.revise` | Create a metadata-only revision with `expectedRevision`, explicit typing and a stable `requestId` |

Scope comes from authentication and the selected personal/project namespace. Keep
mutation request UUIDs across uncertain delivery. Reissue the same identity only
to reconcile its receipt; never create another request to guess whether it committed.

For example, create a draft using `information.types.version.create`:

```json
{
  "scope": {"kind":"personal"},
  "view": "content-types-v1",
  "requestId": "11111111-1111-4111-8111-111111111111",
  "slug": "incident-report",
  "label": "Incident report",
  "description": "Structured incident classification",
  "schema": {
    "type": "object",
    "properties": {
      "severity": {"type":"string", "enum":["low","high"]},
      "affectedServices": {"type":"array", "items":{"type":"string"}, "minItems":1}
    },
    "required": ["severity","affectedServices"],
    "additionalProperties": false
  }
}
```

Use the returned `contract.version` and `controlRevision` to activate that draft
with a different request UUID. `information.upload` and
`information.record.report` then accept:

```json
"typing": {
  "mode": "set",
  "version": "22222222-2222-4222-8222-222222222222",
  "metadata": {"severity":"high", "affectedServices":["search"]}
}
```

The version above is illustrative: replace it with the actual returned UUID.
Existing resources additionally require their highest committed revision UUID as
`expectedRevision`. `inherit` preserves the exact existing version and can replace
the complete metadata object; omitted metadata preserves the existing object.
`clear` takes no version or metadata. An existing typed head refuses legacy
by-name writes that omit typing. Revise, replace and typed URL refresh use the same
expected revision as their `revision` argument. Reports use their report admission
path when changing prose, inputs, findings or citations.

The existing multipart `POST /v1/documents` boundary accepts `view`, a JSON
`typing` parameter and `expectedRevision` alongside the uploaded file. Typed
intake requires a stable UUID `requestId`; an expected revision requires explicit
typing. Invalid metadata returns HTTP 422 with the normal `error`/`detail` fields
and bounded `metadataValidation` diagnostics. Authentication and resource ownership
are the same as for WebSocket admission.

For a metadata-only change, send `information.content.revise`:

```json
{
  "scope": {"kind":"personal"},
  "view": "content-types-v1",
  "requestId": "33333333-3333-4333-8333-333333333333",
  "expectedRevision": "44444444-4444-4444-8444-444444444444",
  "typing": {
    "mode": "inherit",
    "metadata": {"severity":"low", "affectedServices":["search"]}
  }
}
```

Replace the expected revision with the saved revision UUID. This request cannot
supply new body bytes, report details or dependency changes. Its result is an
admission receipt for the new revision. A copied report is draft and retains the
original body producer separately from the authenticated metadata reviser.

The TypeScript operation catalog, Java `InformationRequest` records and generated
Python, Go and .NET requests expose these same operations. The CLI uses
`information types version create`, `information content revise` and the other
catalogued verbs with JSON payloads. TUI's information command accepts the dotted
operation name. The MCP information adapter and server model tools expose
published discovery, validation, metadata reads and document writes; type
management remains a human control.

## Processing, filters and limits

New bodies run ordinary processing. Metadata-only revisions retain
`processingPolicy:"explicit_only"`, zero initial document allowance/spending and
per-stage origins. A ready flag alone is insufficient: reuse requires matching
configuration, complete rows and compatible active/target embedding spaces in
both slots. Rows and vector source fences belong to the new revision. Reuse
does not copy spending, conversations, paid checkpoints, finalisation or sharing.
Explicitly configured transition hooks still follow their separate policy and
model accounting; the guarantee concerns automatic document processing.

An explicit-only revision requires `stages` on `information.retry` and
`information.rebuild`. For example, request `stages:["derive","embed"]` after
setting an allowance. Generation changes invalidate earlier grants. Restoration
retains the policy and does not grant new work. Global tag/group sweeps and global
embedding maintenance cannot supply inference authority. Unprepared retained
projections can therefore delay a global embedding target's complete-coverage
activation until their owners request repair.

Browse, facet, search and rank filters accept `contentType` and
`contentTypeVersion` UUIDs, or `untyped:true`. These intersect existing facets
before counts, ranking and paging. Type-aware facet responses include available
type/version UUIDs. Arbitrary custom-metadata queries are outside this milestone.
Legacy views omit typed and explicit-only revisions; unsupported direct typed
reads refuse with `unsupported-view`.

Definitions are at most 64 KiB, metadata 16 KiB and declarative presentation hints
8 KiB. Instances have at most 32 nested levels, 10,000 validation nodes, 1,024
members/items per container and 4,096 UTF-16 units per string. Diagnostics contain
at most 32 issues within 8 KiB and never echo private instance values. The engine
returns the first authoritative mismatch without resetting its work budget.
Patterns, external references and unsupported keywords refuse; format and
defaults remain annotations. Presentation supports declared-property JSON
pointers in `fieldOrder` and `descriptions`, with no renderer code or network access.
Object keys `__proto__`, `prototype` and `constructor` are reserved by the public
SDK codecs and refused in document metadata and schema property/definition names.

Reuse is bounded to 10,000 rows per corpus hierarchy and 64 MiB of retained
projection material. Oversized projections remain unprepared while metadata can
still be saved with compatible extraction. Code revisions are not supported.

Set `plowshare.information.content-types-enabled=false` to stop authored writes
during rollback. Retained bindings, typed reads and processing fences remain in
place. Migrations are additive: existing revisions remain untyped; no byte rewrite
or inferred metadata backfill occurs.
