# Authored knowledge and schema defined document types in Plowshare

Status: proposed design. Updated: 16 October 2026.

[ADR 0027](decisions/0027-authored-information-contracts.md) records the accepted
first-milestone contracts, and the [implementation plan](authored-knowledge-implementation.md)
records its delivery and verification. The [usage guide](authored-information.md)
describes the implemented content types and metadata-only workflow. The wider
knowledge direction remains proposed; deployment is a separate action.

[ADR 0028](decisions/0028-document-navigation-and-reference-policy.md) and the
[navigation implementation plan](authored-navigation-implementation.md) record
the locally implemented navigation milestone: document resolution, relationships, paths and configurable
reference policy. The [navigation guide](authored-navigation.md) describes the local
implementation and its explicit write activation. Review, integration and deployment
remain separate actions.

Develop Plowshare's information system into a knowledge base for agents, with people supplying content and maintaining its metadata, relationships and policy. The direction combines retained documents and cross-references with intersecting metadata facets, building on the revisions, evidence, retrieval and processing that Plowshare already owns. Content may originate in external editors, repositories or agent outputs. Information remains useful to people for reading and navigation independently of a model run.

Schema-defined content types are the first implementation milestone in that direction. A content type defines structured metadata, such as the severity and affected services of an incident report. Each typed information revision retains its exact type version and validated metadata. The document body, source bytes, evidence coordinates and processing lifecycle remain part of the existing information model.

The model takes inspiration from VOIDcore's authored documents and facet navigation; it is a Plowshare information capability with its own ownership and lifecycle. The first milestone implements schemas; ADR 0028 defines the locally implemented relationships and policy-aware backlinks. Collaborative editing, editor-grant expansion and real-time coauthoring are outside this roadmap. VOIDcore integration remains a separate decision.

## Purpose and boundaries

Plowshare can retain and retrieve a runbook today, but its catalogue cannot require structured metadata naming prerequisites and an expected outcome. Tags classify it without establishing that structure. Applications can embed JSON in Markdown, but their validators cannot make that structure an authoritative, discoverable server contract across SDKs, revisions and tools.

Introduce a reusable content-type registry and typed revision metadata. People and authorised model tools may create documents conforming to published types. Schema validation establishes structural conformance; it does not establish factual accuracy, trustworthy authorship, evidence support or authority to execute instructions.

Existing untyped documents remain supported. No AI call is required to publish a schema, validate metadata or retain a typed revision. New bodies continue through ordinary source processing under its existing allowance and readiness rules. Metadata-only revisions use the explicit processing policy below: their creation makes no document-processing model call and does not automatically schedule paid processing. Explicitly configured transition hooks retain their own separate policy and accounting.

### Content replacement and metadata maintenance

Plowshare retains and processes submitted information. The content-authoring
workflow can live in an external editor, a repository or an agent. A minimal
user-facing **EDIT** action means preparing replacement content and submitting it
through the existing revision admission or re-upload path. It creates another
immutable revision of the continuing resource, with an expected starting revision,
current ownership/access checks and authenticated provenance. It does not mutate
previously retained bytes or merge concurrent changes. Reports continue to use
their owning admission rules for prose, inputs and citations. **EDIT** describes
that workflow; it is not a proposal for an additional wire operation.

Metadata is the primary direct maintenance surface. The implemented workflow
already supports type binding, validation and metadata-only revisions that retain
the body and reuse compatible processing outputs. Tags, explicit relationships
and policy remain separate controls with their existing authority and lifecycle;
they do not require a collaborative body editor or new editor grants. Content
replacement retains ordinary processing rules; metadata-only maintenance retains
its explicit-only document-processing policy and separate configured hook rules.

A Git-backed authoring workflow could separately provide branches, diffs, merges
and collaboration, then submit selected content to Plowshare. That workflow is
optional and outside this proposal. The knowledge system does not need to solve
collaborative editing to ingest, classify, discover and supply retained information
to agents.

## Retained information with faceted navigation

The intended knowledge model brings these capabilities together:

| Capability | Role in maintained knowledge |
| --- | --- |
| Stable document identity and immutable revisions | Keep a continuing document while preserving exactly what earlier readers and runs used |
| Markdown bodies and optional content types | Combine readable prose with a declared structure appropriate to a runbook, decision, glossary entry or incident report |
| Authored cross-references and backlinks | Express relationships between documents and help readers discover the surrounding knowledge |
| Tags and intersecting facets | Form aggregate views from sets of documents, combining type, tag, author and time selections without assigning one canonical folder |
| Internal navigation paths | Record a journey through scopes, relationships and facet selections while keeping document identity independent of the route |
| Revision admission and explicit permissions | Accept replacement content and metadata-only revisions under existing authority, preserving attribution and rejecting stale updates |
| Retained evidence and AI processing | Connect maintained knowledge to its sources, investigations and derived outputs |

Schemas supply the structured meaning; relationships connect documents; facets narrow selections; internal paths describe navigation through both. Revision admission and permission rules support information maintenance. These are related parts of one information system. A runbook is useful to a person before an agent consumes it, and a report can remain useful knowledge after its producing job completes.

For example, a billing incident report can reference the runbook used during response and the architectural decision that explains the affected service. A reader can reach it through `content type = incident report`, `tag = billing` and a date range, then follow those relationships. The runbook can independently appear in operations and onboarding views.

### Document sets and explicit relationships

A tag denotes a set of documents. Selecting `billing` produces an aggregate view of accessible documents in that set; a document can belong to several sets. Facet selections intersect those sets, such as documents in both the Billing tag set and the Incident report type set. Membership groups documents for navigation without creating an explicit relationship between every pair of members or to a document named Billing.

Explicit relationships connect continuing documents. The source belongs to the document's resource identity, and the target defaults to another document's resource UUID. For example, `Billing service —has runbook→ Refund recovery` remains a live document-to-document connection when either document gains a new revision. Following it opens the target's latest current revision under its lifecycle and access rules. Backlinks identify the continuing source documents, so edits do not strand discovery on their older revisions.

Only an explicitly revision-pinned reference names one exact revision. Ordinary relationships must not implicitly pin either endpoint merely because a link was created while a particular body revision was current. Relationship changes need an audit history, but that history must not freeze live navigation to an earlier source revision. Evidence citations continue to identify exact retained revisions and remain distinct from ordinary relationships.

### Content, reference visibility and propagation policy

Reference handling is an explicit policy decision. A document whose content is locked may still be permitted to appear as a reference; another document may require its existence and associated references to remain hidden. Content access, discovery and restriction propagation must not be collapsed into one universal rule.

The follow-on policy distinguishes three questions:

1. **Content access:** who may read the body, metadata, exact revisions or derived content?
2. **Reference visibility:** who may discover that the document exists, see a link or backlink, and receive which approved identifying fields?
3. **Restriction propagation:** does referencing this document make the source subject to the target's restrictions, transitively?

For example, reference visibility can support both of these behaviours:

| Policy behaviour | What a reader without content access sees |
| --- | --- |
| Content locked, reference visible | An explicitly permitted reference with only policy-approved identity or label fields; opening the document still requires content access, and the ordinary link does not propagate the target's content restriction to its source |
| Existence or reference hidden | No target entry, link, backlink, identifying metadata, preview or contribution to discovery counts |

Reference visibility does not grant content access. A visible link cannot reveal a private title, excerpt or other field unless the policy separately permits its disclosure. Counts and aggregate views apply the relevant discovery policy; content-bearing search results and previews additionally require content access. Missing or unresolved policy fails closed. A reader or link creator cannot choose a permissive mode to bypass the target's policy.

#### Configuration by project and document

Plowshare's reference behaviour is configured at project and document level. Each project supplies defaults for content access, reference visibility and ordinary-reference propagation, and declares which settings documents may override. A document inherits those defaults unless an authorised document policy explicitly overrides an allowed setting. Project configuration can distinguish a default from a mandatory constraint: document overrides may choose another permitted behaviour but cannot weaken a project requirement.

| Configuration level | Responsibility |
| --- | --- |
| Existing platform authority | Authentication, membership, explicit grants, retention and required input/evidence restrictions remain enforced |
| Project policy | Select default behaviours, mandatory constraints and permitted document overrides |
| Document policy | Inherit the defaults or select an authorised override within the project's allowed policy |

Policy administration belongs to server administrators and project managers:

This authority governs the new content/reference policy configuration and its overrides. Existing owner lifecycle controls retain their own contracts and operate within the effective policy; ownership does not additionally grant authority to change that policy configuration.

| Actor | Policy-changing authority |
| --- | --- |
| Server administrator | Manage platform policy and mandatory constraints, project policies and document policies, including personal-document policies, through the applicable audited administration contracts |
| Project manager | Manage defaults, project constraints and permitted document overrides within projects where the actor currently holds `MANAGER`, subject to mandatory platform constraints |
| Document owner, contributor or viewer | No policy-changing authority solely through ownership, editing rights, membership or read access; creating a document inherits its applicable policy |

A document owner who also holds the required administrator or project-manager role acts under that role when changing policy. A manager cannot change another project's target policy through a cross-project link. Personal documents have no project-manager authority; server administrators manage their policy baseline and overrides. Policy administration is distinct from content-read authority and does not itself grant access to a protected body.

Enforcement must use current server-administrator status and current project roles, rechecked when applying the change. Plowshare already exposes these boundaries through `AdminStore.requireServerAdmin` and `ProjectMembers.requireRole(..., MANAGER)`; account existence alone is not administrator authority. Policy updates need an expected policy version and an atomic audit of actor, scope, previous/new settings and outcome. Demotion or removal of management authority must invalidate later policy changes, including from an existing session.

For example, a project may default to content-locked/reference-visible documents with no ordinary-link propagation, while allowing individual documents to require hidden references and propagation. Another project may mandate those stronger restrictions for every document. These are project choices rather than one global navigation behaviour. Personal documents need an explicit account/platform policy baseline and the same controlled document override rules.

Cross-project links evaluate both documents' applicable policies. A source project's permissive defaults cannot bypass a target document's required propagation or disclosure restrictions; source-side constraints may further restrict the result. Policy changes require the administrator/project-manager authority above, are auditable, and are rechecked on subsequent reads. These are server-owned policy settings, separate from schema metadata, tag labels and client display preferences. ADR 0028 and the navigation guide specify the implemented defaults, allowed combinations, optimistic concurrency and refusal of policy widening without a release workflow. The policy-changing roles above remain the authority boundary.

When the applicable policy requires propagation, an explicit reference creates a required access-policy dependency. If A references restricted B, reading A requires both A's own authority and satisfaction of B's applicable restrictions. If C references A, those restrictions also constrain C. Hiding just the link is insufficient in this mode: a source whose required dependency cannot be satisfied is itself unavailable, with its discoverability governed by the applicable visibility policy. A visible-reference policy without ordinary-link propagation may instead leave A readable while B's content remains locked.

Restrictions flow from the referenced target to the referencing source. B does not inherit A's restrictions merely because the system generates a backlink. Each dependency is evaluated under its own scope and sharing rules; no reference copies an access grant or creates project membership. Reference creation requires authority to edit the source and the target's required reference authority. Where policy permits references without content access, that authority is distinct from permission to read the target body.

Tags remain document-set membership. Sharing a tag does not by itself create reference dependencies or propagate a private member's restrictions to every other member. Aggregate views disclose only the members and fields permitted by their applicable policies.

All modes retain these invariants:

- **Live links use live policy.** Ordinary references follow continuing document identities and their latest current revisions. Policy, membership, withdrawal and current-revision changes are rechecked when reopening paths or serving cached projections. A stale cache is not authority to disclose content or references.
- **Pinned content does not pin permission.** Exact revision references retain their target revision, but applicable access and retention restrictions remain live. Pinning cannot preserve revoked access.
- **Propagation is enforced consistently where required.** Browsing, search, retrieval, model reads, history, sharing and export apply the required dependency closure before disclosure. Unresolved dependencies and exhausted evaluation bounds fail closed; cycles require bounded evaluation rather than recursive grants.
- **Navigation policy cannot release derived content.** Existing retained input/evidence dependencies still constrain reports and other derived outputs, even when ordinary links may remain visible without propagation. Hiding or removing a rendered link cannot release protected information already retained in the source body. Removing a relationship is not automatic declassification; authorised release and historical restriction handling need their own rules.

Plowshare already constrains derived outputs through retained information inputs, including uncited inputs. Policy-controlled discovery and propagation for live authored references are implemented as a separate platform milestone under ADR 0028. They cover cross-project evaluation, policy changes, atomic relationship admission and historical retention while keeping ordinary relationships distinct from evidence citations.

### Internal navigation paths

A navigation path is an internal sequence of steps that lands a reader at a selection or document. It does not require an HTTP URL or a public HTTP route. Clients express the journey through navigation state and the platform's public operations, using WebSocket operations where supported. Displaying a path creates no filesystem directory or canonical parent for a document.

Two kinds of steps work together:

- **Facet selection** narrows the current collection. `Operations → Incident reports → Billing` means incident reports tagged Billing in the Operations project. Selecting that tag does not create a relationship to a Billing document. Each selection intersects the existing filters; available refinements and counts are recalculated from the authorised matching records. Search can further narrow that selection.
- **Relationship traversal** follows an explicit connection between documents. `Billing service → Related incidents → Incident 42` follows a declared relationship and opens a target document. Its meaning comes from the relationship contract, not from a matching label, tag or UUID-shaped metadata string.

A path can combine scope, relationship and facet steps:

```text
Operations → Billing service → Runbooks → Refund recovery
```

| Step | Meaning |
| --- | --- |
| Operations | Select the project scope |
| Billing service | Open the service document by its stable resource identity |
| Runbooks | Follow its declared relationship to related documents and filter the resulting selection to the runbook content type |
| Refund recovery | Open a matching runbook document |

The same runbook can be reached through another service, a tag selection, a saved view or a direct document selection. Navigating through another path does not move, duplicate or reparent it. Resource UUIDs identify continuing documents, revision UUIDs identify exact retained versions, and path labels are presentation rather than identity.

Today, each retained resource also has a source name unique within its personal or project namespace. That scoped name is its named admission locator: re-ingesting the same namespace/name targets the existing resource and creates a revision under the applicable ownership and admission rules. A source name such as `runbooks/refunds.md` can contain slashes without defining directories, parent documents or relationships. There is currently no canonical knowledge-navigation path. Proposed paths coexist with scoped source names and resolve document targets by stable resource UUID, or revision UUID when explicitly pinned. Changing the journey to a document does not rename its source or change its identity.

Which version opens follows the expected user behaviour:

- **Ordinary navigation opens the latest current revision.** A document selection or unpinned relationship targets the continuing resource UUID. Opening it selects that document's latest current revision under its lifecycle and access rules. Later edits become visible through the same document target; a saved view with that target follows the latest current revision when reopened.
- **An exact reference opens the named revision.** Revision-history selections, pinned relationships and evidence citations retain the specific revision UUID. Later edits do not retarget them or replace the content they reference.

Both cases recheck current access. An unavailable, withdrawn, deleted or inaccessible target is shown as unavailable without exposing private details. Navigation must not silently fall back to an older revision, a different document or a same-named resource in another namespace.

Breadcrumbs record the journey, including relationship and filter steps. Returning to an earlier breadcrumb restores that step's selection; removing a facet broadens the relevant selection without changing document relationships. Saved views retain useful navigation steps and filters, referencing stable identities rather than relying on displayed names. Reopening a saved view reevaluates it against current records and permissions; it is not a frozen result set or an access grant.

ADR 0028 implements these navigation requirements in the local `document-navigation-v1` capability. Document-set views identify members by resource UUID and show one entry per document, opening its latest current revision unless an exact revision was explicitly selected. Existing catalogue operations that enumerate or count revisions retain their contracts; document-level navigation requires an explicit projection rather than silently changing revision counts. The initial schema milestone supplies exact type/version filtering and existing facets. Saved-path persistence and explicit relationship traversal use the separate contracts described in the navigation guide. General custom-metadata fields such as severity or status do not become queryable facets simply because a schema declares them.

Plowshare's current scopes, project membership, explicit sharing and information collections remain authoritative, further constrained by reference dependencies where the applicable policy requires propagation. Borrowing facet navigation does not create VOIDcore's single public pool or remove Plowshare's access boundaries. Saved views and collections can organise a reader's experience without becoming the only permitted location of a document. Every traversal and reopened path rechecks content access, reference visibility and any required propagated restrictions. Counts, backlinks and suggested refinements must respect the applicable live discovery policy, with content reads enforcing their additional access rules. No view may expose fields or identities that its policy protects.

Authored relationships must also stay distinguishable from evidence. A link meaning “related decision” does not prove a claim; a citation identifies evidence from an exact retained revision. Later relationship work should support links to a continuing resource and separately pinned revision references, with explicit semantics for each. It must not reinterpret existing evidence links as ordinary wiki links.

## Storage Markdown and portability

PostgreSQL is Plowshare's authoritative information store. It gives retained bodies and bytes, revisions, metadata, ownership, evidence and processing state a common transactional lifecycle. This storage choice is independent of Markdown as an authoring or exchange format. Storing Markdown bytes in a database does not change the format of those bytes or prevent reading, editing, rendering or exporting them.

Plowshare already reads Markdown and other supported UTF-8 text, retains sources and produces Markdown reports. It also supports ordinary editable workspace Markdown. Workspace files remain useful for repositories, external editors and file-based workflows. A workspace file and an admitted information revision have distinct identities: indexing or publishing a file creates an explicit retained snapshot, and later file edits do not silently rewrite that snapshot. This design does not require every Markdown file to become a database document.

The broader direction supports portable exchange with external authoring workflows. Define two export purposes:

- **Readable export:** an authorised document body as ordinary Markdown when that is its retained format; converted material is labelled as an extraction, with original bytes offered separately when available. Related metadata can use a JSON sidecar so Markdown editors can read the prose without a Plowshare plugin.
- **Structured bundle:** selected authorised revisions, their original bytes, content hashes, metadata, exact schema definitions and a versioned manifest. As relationship features arrive, the manifest records link targets, revision pinning and provenance. A Markdown file alone does not encode all of those semantics.

Export paths are a portable view of selected records, not the database's canonical hierarchy. The bundle records stable identities independently of filenames. It states whether it contains one revision or selected history and identifies omitted, unavailable or unreadable dependencies without revealing private target details. An extraction is never represented as the original file, and an ordinary content export is distinct from a complete server backup.

Reimport is an authenticated admission or revision operation. It validates types and metadata, resolves collisions explicitly, and preserves the distinction between imported provenance and locally authenticated authorship. Foreign identifiers are mapped rather than blindly trusted; imported grants cannot create project membership or expand sharing. Editing an exported Markdown file and importing it back creates a new revision against an explicit starting revision. It does not overwrite cited history.

These are requirements for a later portable interchange capability, not a claim that a complete bundle exporter/importer already exists. The schema milestone keeps definitions and instance metadata serialisable and source bytes intact so that interchange can build on public contracts. Any missing public operation for original bytes, history or relationship closure must be designed as platform work; an exporter must not reach into database tables or server internals.

## Reference implementation and adaptations

The reference is VOIDcore commit `577b26b0575f1c6b9967e27face3f5ce2cd3e4de`. The target baseline is Plowshare public main `e0fc6caef8b4e68deedc4231af26512ba0a50214`, verified against GitHub on 15 October 2026.

| VOIDcore feature | Plowshare design |
| --- | --- |
| Schema slug, version, label, description and JSON Schema definition | Scoped content-type identity with immutable version records and typed schema definitions |
| `draft`, `active`, `deprecated` schema states | Explicit lifecycle transitions with one active version per type |
| Document `type_slug`, `type_version` and frontmatter | Optional content-type binding and metadata on each immutable information revision |
| Separate presentation metadata | Small, declarative presentation hints retained with the schema version |
| Validation when inserting a typed document | Validation on every typed admission and change, including reports and metadata-only revisions |
| Lookup of a writable active or deprecated schema | Exact version IDs; explicit inheritance of an existing version, with no implicit version substitution |
| Per-field frontmatter mutation | Complete metadata objects committed through new revisions; no in-place patching of retained metadata |

VOIDcore's `FrontmatterValidator` uses JSON Schema 2020-12, but its per-field repository updates do not invoke that validator. Port the schema/version concept rather than those mutation paths. Plowshare already has a shared, bounded schema vocabulary and validation profile; reuse those semantics instead of introducing an independent general-purpose validator.

## Four independent classifications

Preserve the current classifications and add a new one:

| Dimension | Example | Meaning |
| --- | --- | --- |
| Resource kind | `source` or `report` | Role in retained information and provenance |
| Document classification | `document` or `code`, plus subtype | Existing format and language classification |
| Media type | `text/markdown` | Representation of the retained source bytes |
| Content type | `incident-report` version 1 | Meaning and constraints of structured document metadata |

Use `contentType` on the wire and `content_type_*` in persistence. Do not repurpose `kind`, `document_type`, `document_subtype`, `report_status`, tags or generated attribution.

A content type can describe both a source and a report. A typed report still obeys the existing report input, evidence, producer, draft/final and publication rules. Its custom metadata cannot replace or forge those records. Code revisions are outside the first release; their document classification and code projections remain unchanged.

## Type identity and ownership

A content type belongs to an existing personal or project namespace. Its stable UUID identifies the type; its slug is unique within that namespace. A version UUID identifies one exact definition. Requests never resolve a slug by searching multiple namespaces.

The initial registry supports personal types and project types, without a global shared registry or Application package installer. Personal types are managed by their account owner. Project types are managed by the project's owner or existing owner-equivalent administrative authority. Ordinary contributors may use an active project type when creating documents they are otherwise allowed to create. Model tools can discover and use published types but cannot create, activate or deprecate them. Service credentials retain their existing Application grants and project fences; this feature grants no new administrative authority.

New assignments use a type in the resource's own namespace. There is no implicit personal-to-project lookup or cross-project binding. A future explicit import can copy a definition into another namespace as a distinct type identity, subject to its own authority checks. This keeps type management independent of document ownership and avoids making schema access an accidental project-membership bridge.

## Schema version lifecycle

Creating a type version records a new immutable definition, presentation hints, profile identity and definition hash. Draft definitions are immutable too: revising a draft creates another numbered version. Version numbers are allocated under the type's lock and never reused.

Activation explicitly selects a version. In one transaction it makes that version active and deprecates the previous active version. Concurrent activation requires an expected control revision and produces a conflict if the registry changed. Deprecation prevents assignment to a new resource. Existing typed resources may continue creating revisions that inherit their exact deprecated version. This preserves ongoing editing without silently upgrading documents. A new explicit assignment, including an upgrade to a different version, requires an active target.

Deprecation neither hides existing documents nor changes their conformance. Definition records cannot be hard-deleted while referenced. This release adds no emergency type revocation mechanism; existing document withdrawal and access controls remain responsible for restricting content.

Schema versions are contracts visible with documents that use them. Activating a version authorises disclosure of its definition and presentation to any authorised reader of an attached revision, including a recipient outside the schema's original namespace. Clients must make this consequence visible during activation. Draft definitions remain visible only to their managers. Reading a shared document exposes its bound contract, not the registry or other documents in its namespace.

## Definition and validation profile

Use the existing `SchemaDefinition` vocabulary and recursive value semantics from `plowshare-protocol`, with the current bounded executable profile documented in `docs/tool-schemas.md`. The document profile is named and versioned independently, initially `plowshare-document-metadata/1`; its identity pins those semantics so later tool-profile changes cannot reinterpret historical documents.

The root must be an object. Support the existing profile's object, array, scalar, composition and local `$defs` constraints. Preserve its portable number rules and distinction between missing values and explicit null. Unknown keywords, unsupported dialects, external or cyclic references, executable expressions and regular-expression constraints are refused at registration. No schema validation makes a network request.

`format`, `default`, `readOnly` and `writeOnly` retain their existing annotation meaning. In particular, `format: date-time` does not enforce a date, defaults are not inserted, and `readOnly` does not create field-level access control. A schema requiring a validated domain-specific value needs a separately designed profile extension; clients must not present annotations as enforced guarantees. See the [JSON Schema validation specification](https://json-schema.org/draft/2020-12/json-schema-validation).

Initial document-specific limits are 64 KiB of compact UTF-8 schema JSON and 16 KiB of compact UTF-8 instance metadata after structural decoding, in addition to the shared profile's structural, scalar and work budgets. Presentation hints are limited to 8 KiB. Enclosing operation limits still apply. Admission rejects overflow without truncation. Cache compiled definitions by version UUID, profile and hash, using a bounded cache.

Hash schema definitions and metadata with the existing corrected canonical token encoding: object keys sort by unsigned UTF-8 bytes and equivalent portable numbers have the same identity. Do not hash arbitrary JSON serializer output. Definition identity includes the profile and immutable presentation as well as the schema; mutable lifecycle state is recorded separately. New document contracts accept the canonical hash only, without importing historical tool-invocation compatibility hashes.

Convert schema and metadata JSON at the transport boundary into immutable, validated values. Domain services do not accept arbitrary `JsonNode` or map bags. Define a pure schema/value validation contract in the shared protocol layer and consume it through document-specific and tool-specific facades. Document services must not depend on Relay declarations or tool invocation classes. The existing `ToolSchemas` entry point and tool-facing values remain compatibility facades; sharing semantics does not require document callers to construct tool invocations.

This is shared machinery work, not an assumption that the existing validator already offers a document API. It currently returns a generic mismatch; the shared contract must additionally produce bounded structured issues without changing existing tool acceptance, numeric semantics, work budgets or canonical fingerprints. Collecting diagnostics consumes the same validation work budget, including composition and reference traversal. Document errors distinguish invalid metadata from exhausted validation budgets without exposing instance values. Cross-SDK fixtures must prove that the refactor preserves existing tool behaviour as well as the independently pinned document profile.

## Body and metadata representation

The body stays in the existing retained bytes and extraction, including Markdown supplied by a person, a workspace or an authorised agent. Metadata is an explicit JSON object accompanying the document; the server does not parse YAML frontmatter out of Markdown in this release. A future file interchange adapter can define a frontmatter mapping without changing the native admission contract. Keeping the first contract explicit avoids ambiguous parsing and preserves source hashes and extraction coordinates. Documents in supported non-code formats, including PDFs, may carry caller-supplied metadata; the type constrains that metadata, not the PDF's prose or bytes.

An illustrative type definition is:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "severity": { "type": "string", "enum": ["low", "medium", "high"] },
    "services": {
      "type": "array",
      "items": { "type": "string", "minLength": 1, "maxLength": 120 },
      "minItems": 1,
      "maxItems": 16,
      "uniqueItems": true
    },
    "status": { "type": "string", "enum": ["investigating", "resolved"] }
  },
  "required": ["severity", "services", "status"],
  "additionalProperties": false
}
```

An incident document can carry this metadata alongside its Markdown body:

```json
{
  "severity": "high",
  "services": ["billing"],
  "status": "investigating"
}
```

The custom `status` belongs to the metadata object and cannot change a report's draft/final status, an information revision's availability or a job's lifecycle.

For a runbook, a schema can require nonempty `prerequisites` and `expectedOutcome` metadata. A document carrying those fields can still omit a necessary step from its Markdown body or make an incorrect claim. The first milestone does not compare metadata with prose, assert body completeness or verify safety and factual support. Clients describe successful validation as metadata conformance to an exact type version, never as a verified runbook or verified report. Body validation and evidence-supported metadata would require separately designed capabilities.

## Immutable revisions and typing changes

Every typed revision retains a version UUID, definition hash, validation-profile identity and complete metadata object. Changing the body, metadata or type binding creates a new information revision. Previously cited text and its coordinates remain attached to the old revision. Rebuilding embeddings or tags does not alter typing or revalidate against a newer schema.

For a typed resource, each admission explicitly uses one of three modes:

| Mode | Behaviour |
| --- | --- |
| `inherit` | Keep the starting revision's exact version; preserve its metadata when omitted, or accept a supplied complete replacement object; validate the complete candidate against that version |
| `set` | Supply an exact active version UUID and a complete replacement metadata object |
| `clear` | Explicitly produce an untyped revision with empty metadata |

Metadata-only changes use a new revision operation and the same modes. They copy retained source bytes, media type and existing provenance from their starting revision, never an external URL. They cannot supply replacement body bytes or change report details, input dependencies or citations. Missing legacy source bytes produce an explicit refusal rather than reconstruction from passages. A body replacement uses the appropriate existing source or report admission path and its ordinary processing policy, even if its supplied bytes happen to match an earlier revision.

Typed updates require `expectedRevision`, a revision UUID rather than an ordinal. It identifies both the starting revision and the resource's highest committed revision, determined by ordinal under the resource lock. The server verifies that it belongs to the selected resource and namespace, checks current authority and creates the next ordinal atomically. `inherit` always uses that exact revision's binding and metadata; the server never selects a different readable or final revision as the inheritance source. Metadata-only operations also copy their body and provenance from that UUID. Stale writers receive a conflict containing no unreadable revision details. Clients reload and let the author reapply an edit; they do not silently merge metadata or retry against a newer head. An intentional branch or copy requires a new resource name.

Untyped legacy requests continue unchanged for untyped resources. An old client writing by name to a currently typed resource is refused with a machine-readable typing-required error, rather than silently producing an untyped revision. Explicit `clear` requires current owner authority and the expected revision. Historical idempotent receipts remain recoverable after a resource becomes typed.

Do not deduplicate typed admissions by body hash alone. The retained admission fingerprint includes the exact type version, profile, metadata hash, typing mode, expected revision UUID and requested operation's processing policy as well as the existing content/provenance tuple. A metadata-only change with identical body bytes is still a distinct revision. Preserve the shipped fingerprint algorithm exactly when the extension is absent on legacy requests.

## Report revision provenance

An unchanged-body metadata revision preserves the original producing run and its recorded definition hash as attribution for the body. It separately records the authenticated revising actor, starting revision and typing change; the original producer did not author the new metadata merely because its body was copied. Preserve report details, all input dependencies and exact citations. The new revision enters draft status and does not inherit finalisation, publication or revision-specific sharing grants. Live resource policy and input restrictions remain authoritative, and publication requires the existing explicit owner action.

A body-edited report records the actor and actual run that produced the edited content. The earlier report and its producer remain lineage, not a claim that the earlier run produced the replacement prose. Human edits do not fabricate a producing run. Apply the existing report admission and feedback rules, including input and citation validation, and retain prior report relationships through an explicit typed lineage contract. Metadata-only revision admission must not become a shortcut for changing findings, dependencies or report authorship. This lineage contract and its client presentation must be decided in the platform ADR before exposing report revision support.

## Metadata-only processing policy

Adopt the following behaviour for the first milestone. A metadata-only revision is a deterministic owner-authorised transition: it validates metadata, retains the unchanged body and commits its own revision without invoking document-processing models, fetching a source or automatically scheduling paid document processing. It does not require a positive document model-call allowance merely to save metadata. New bodies retain the ordinary admission lifecycle. Existing configured transition hooks still apply and may independently invoke models under their own authority and accounting; clients must distinguish that configured hook usage from document processing. This distinction is a server contract, not a client preference or an assumption that copied bytes already have ready projections.

Reuse is limited to completed, committed outputs from the starting revision that match the unchanged source and extraction hashes, each stage's input identity and the currently configured pipeline fingerprint. Reuse is evaluated per stage in dependency order. Copy eligible outputs into revision-owned snapshots through specialist repository operations, retaining the originating revision, generation and stage fingerprint as provenance. Never reuse a failed or running stage, a private paid-response checkpoint, an incompatible projection or an output that has not passed its completion gates. A stage is marked ready only when its required outputs and upstream dependencies have been retained and verified for the new revision. Content-type metadata remains outside body processing inputs in this release.

Prepare the exact reuse plan with the revision transition and expose it to the existing gates. In the final transaction, recheck source availability, authority, expected head, originating generation and output compatibility; commit the new bytes, typing, eligible projection snapshots, reuse provenance, receipt and processing policy atomically. A changed reuse candidate causes an explicit conflict rather than substitution after approval. Reuse does not replay the originating model stage or its hooks, but the new revision transition still passes its own pre/post gates. No hooks, models or network calls run inside the transaction.

Retain an explicit-only processing policy with the new revision. Missing or incompatible stages remain unprepared and visible in status; do not mark them ready or permanently skipped to conceal absent capability. Automatic workers, startup recovery and tagging/grouping sweeps must respect this policy, including after restart. The owner can explicitly request the applicable retry/rebuild controls, with the required allowance, permissions and gates. Extend those public contracts with typed stage selection where necessary; existing whole-revision retry behaviour alone does not establish selective processing. The request durably authorises only the selected processing and its required dependencies, so interrupted authorised work can resume under its existing fences without enabling unrelated automatic enrichment. Reads use each capability's existing readiness rules and report finalisation still requires all five compatible content-processing stages. If reuse cannot satisfy them, the report remains draft until explicit processing completes.

Reuse creates no new inference charge. Original model spending and usage attribution remain with the work that incurred them; do not copy spending as a new charge, duplicate usage records, transfer unused allowance or reset an existing allowance through metadata edits. No paid allowance is implicitly allocated to the metadata-only revision. Explicit processing establishes its revision's allowance through the owning controls, records new spending there and obeys existing accounting and execution limits.

Projection copies belong to the new revision and use its live access predicates. Preserve the original report input restrictions; reuse creates no new sharing grant or owner authority. Deletion atomically redacts each revision's own bytes, metadata and copied projections under the existing revision-specific deletion semantics. A retained origin identifier is provenance, not a pointer that can retrieve deleted content or bypass access checks. Resolving an origin's content or run details requires that target's current read authority; a shared revision cannot expose private origin details through its provenance view. Independently retained copies in another authorised revision remain subject to that revision's lifecycle.

Safe reuse and the durable explicit-only policy are required implementation work in this milestone. They are not capabilities supplied by the current admission path, which creates processing steps for each new revision. This adds repository, processing, accounting and compatibility coverage, but prevents routine metadata maintenance from unexpectedly spending model allowance. An implementation must not substitute ordinary automatic processing when reuse is unavailable; it must expose the unprepared capability and preserve the explicit-only policy.

## Public operations

All new information operations use authenticated `/v1/events` through the public SDKs. Names below are proposed contracts, subject to the usual operation-inventory review.

| Operation | Purpose and authority |
| --- | --- |
| `information.types.list` | Page active/deprecated types in an explicit personal or project scope; manager-only option for drafts |
| `information.types.get` | Read an exact version and contract; attached-document access is checked through the document, not an arbitrary registry lookup |
| `information.types.version.create` | Manager creates a type or immutable draft version, using an idempotency key |
| `information.types.activate` | Manager selects a version with expected control revision |
| `information.types.deprecate` | Manager deprecates a version with expected control revision |
| `information.types.validate` | Authorised caller checks a complete metadata object against an exact visible version; no document, model call or durable job is created |
| `information.content.revise` | Owner creates a metadata-only revision with a complete typing change, unchanged retained body, expected revision UUID and explicit-only processing |

Extend `information.upload`, `information.revise`, `information.replace` and `information.record.report` with the typed admission contract. Their existing scope, ownership, report evidence and hook requirements remain authoritative. URL acquisition initially creates untyped sources. Refresh of an already typed resource must explicitly inherit/set/clear typing against the expected revision; its durable acquisition retains that intent and refuses a stale head at final commit.

The conceptual upload extension is:

```json
{
  "typing": {
    "mode": "set",
    "version": "6be3aa6d-87f2-4ed8-9350-82b6c13cec31",
    "metadata": {
      "severity": "high",
      "services": ["billing"],
      "status": "investigating"
    }
  }
}
```

This fragment is additional data within the existing admission request, not a new HTTP endpoint. A repeat request ID with different typing or metadata is a conflict. A committed duplicate recovers its existing receipt after current access checks, without rerunning validation, hooks or model processing. Uncertain submissions are reconciled through retained receipts/status; clients do not automatically resubmit them under new identities.

Validation failures return a stable code and bounded issues with instance JSON Pointer, schema pointer, keyword and short message. Messages do not echo document values. Unknown and inaccessible type IDs have indistinguishable refusal behaviour. Validation against drafts is manager-only; published versions are usable under the namespace and attached-document rules.

## Admission order and execution safety

The service first authenticates and checks scope and ownership, then resolves an existing receipt. For new work, it resolves the permitted type version, validates bounded immutable metadata and prepares the admission identity before durable publication or paid processing.

Use the existing prepared-transition and stage-gate pattern. Inside the final transaction, recheck authority, expected revision and assignment eligibility; commit revision, type binding, validated metadata, receipt and the operation's processing state atomically. Ordinary admissions commit pending processing; metadata-only revisions commit verified reusable outputs and their durable explicit-only policy. Schema activation/deprecation races are resolved using current state at commit. No hook or network call occurs inside that transaction.

Validation failure leaves no accepted revision or processing job and spends no model allowance. Metadata must be included in the candidate identity shown to gates and retained through their existing checkpoint mechanism, rather than being appended after approval. Schema-management mutations use the same durable idempotency and pre/post gate discipline, with typed identities; they do not fabricate document jobs for drafts. This capability adds no new automatic retry semantics.

## Retrieval and presentation

Add exact content-type/version selections to information browse/search/rank requests and return content-type identity with revision metadata. Keep these distinct from the existing `kind` and `subtype` facets. An untyped selection is explicit. Filter before counts, ranking, paging and available facet values using the existing live permission predicates. Cross-namespace type labels with the same slug remain distinct identities.

The first release includes typed authoring through SDK/CLI contracts and a minimal human Library workflow: select an active type, enter a body and complete metadata object, receive bounded field errors, save, read the exact bound contract and revise metadata against the displayed revision UUID. A bounded metadata panel displays conformance, revising attribution, reuse provenance and any unprepared processing capabilities. A plain structured metadata editor is sufficient; a general schema-driven form builder is not required. Stale edits present a conflict and reload/reapply flow. Clients explain that a metadata save does not schedule paid processing and show when explicit processing is needed before retrieval or report finalisation.

Declarative presentation hints contain a field order and property descriptions only. Paths must resolve to declared properties; they cannot reference ownership, producer fields or other server records. No custom HTML, JavaScript, templates or URL fetching is allowed. Unknown hints are refused rather than interpreted by individual clients.

Preserve the body's extraction and evidence offsets. Custom metadata is initially available through explicit metadata reads and exact type filters, not concatenated into searchable text or automatically injected into model context. General arbitrary-field querying, field-level facets and metadata evidence coordinates are later capabilities. If a workflow uses metadata, that read records the same information dependency as reading its document. Schema labels and descriptions are untrusted content, never instructions or grants.

Type-specific schemas must not narrow a model tool's general admission schema dynamically into an unsupported provider shape. Models discover a type, supply its version and metadata, and receive authoritative server validation. Structured generation is optional client assistance; it does not replace server validation or require a model to consume the full schema.

## Persistence and deletion

Use specialist repositories and new Flyway migrations. The conceptual additions are:

| Record | Main fields and invariants |
| --- | --- |
| `information_content_types` | UUID, existing namespace identity, slug, manager ownership coordinates, control revision and creation time; unique namespace/slug |
| `information_content_type_versions` | UUID, type UUID, positive version, definition, presentation, profile, hashes, creator and lifecycle state; unique type/version; immutable definition fields |
| `information_revision_content` | Revision UUID primary/foreign key, type-version UUID, exact hashes/profile and metadata object; inserted atomically with its revision |
| Revision lineage and processing records | Starting revision UUID, authenticated revising actor, durable automatic/explicit-only processing policy and per-stage reuse origin/fingerprint; reuse snapshots are owned by the new revision |
| Type-management receipts/events | Caller, request UUID, operation, canonical fingerprint and committed result; integrate with existing durable control conventions |

The absence of `information_revision_content` means untyped. A typed row always carries an object, including `{}` when permitted; there is no typed-with-missing-metadata state. All supplied IDs are checked through authorised repository methods. Invariants are defended at service and persistence boundaries.

Revision metadata can contain private content. Explicit document deletion must atomically redact it with source bytes, extraction and existing evidence/report content. A tombstone may retain type-version identity and hash without retaining instance values; its deletion marker distinguishes redaction from an originally valid empty object, and typed metadata reads refuse deleted revisions. Extend immutability rules with the same narrowly defined deletion exception as existing retained content. Shared definitions have an independent registry lifecycle and are not erased merely because one document is deleted. Definition authoring must explain that schemas are published contracts and not a place for secrets.

Historical schema definitions remain available through authorised attached revisions even after registry membership changes. Reading metadata, schema snapshots, facets and validation messages must never disclose an unreadable document. Revoking project membership or withdrawing an input retains Plowshare's existing effect on dependent outputs.

## Compatibility and rollout

Add tables and optional fields; do not rewrite existing document bytes, backfill inferred types or alter evidence coordinates. Do not assign a Flyway number until implementation integrates with current main. Extend the immutability and deletion triggers deliberately rather than bypassing them in application code.

Advertise the feature in server capabilities. Old clients retain full use of untyped resources; typed writes require supporting clients and fail clearly otherwise. Typed records are visible only through client codecs that can preserve and present their contract. Audit each SDK's unknown-field behaviour and add fixtures before deployment; do not assume optional fields are backward compatible with strict decoders. If needed, negotiate the typed revision view by capability instead of exposing an unreadable shape to an old client.

Enable typed writes only after every exposed admission path enforces typing and metadata-only revisions retain their explicit-only processing policy across workers and restarts. This includes source/report tools, by-name writes, multipart upload and durable URL refresh. Unsupported paths must refuse clearly; they cannot silently strip typing or invoke ordinary processing as a fallback. Release acceptance also requires the minimal human authoring/read/revise flow. A registry-only or upload-only prototype is not an enabled first release.

An older server must not continue writing typed resources without validation. Deployment rollback therefore needs either a schema-aware compatibility release or a write stop while returning to an older binary. Retain the additive tables and contracts; do not drop typed metadata or pretend that disabling a feature flag makes old code safe.

## Delivery direction

The broader destination is an information and knowledge system for agents, with metadata maintenance, explicit relationships, facets and optional portable Markdown exchange. Deliver it through bounded platform changes that extend the existing retained information lifecycle:

| Stage | Result | Scope |
| --- | --- | --- |
| Schema-defined content types | Documents have optional validated metadata, a discoverable semantic type and admission, reading and metadata maintenance | Detailed implementation milestone below, including safe metadata-only processing |
| Information navigation | Browse retained knowledge through internal navigation paths, facet steps, breadcrumbs and saved views | Local implementation under ADR 0028; internal paths and account-owned saved views; publication remains separate |
| Authored relationships | Add explicit relationship traversal to internal paths, with cross-references, project/document policy-controlled visibility and propagation, and permission-aware backlinks distinct from evidence citations | Local implementation under ADR 0028, including project defaults/constraints, document overrides, live/pinned targets and retained cross-project obligations; authorised release remains deferred |
| Agent workflow validation and release | Verify ingestion, metadata classification, discovery, relationship traversal, agent consumption and retained provenance together | Local end-to-end acceptance passes with scripted models; see the [validation record](authored-knowledge-implementation.md#full-agent-workflow-validation--16-october-2026); review, integration and release remain separate |
| Portable interchange | Readable Markdown exports and structured bundles support external authoring and authorised revision admission | Optional follow-on public export/import contracts; the current schema design preserves the necessary data |

These stages describe a direction, not an instruction to implement all of them in the schema change. The complete agent information workflow now has local end-to-end acceptance coverage, with the model and deployment limits recorded in the implementation plan. The next step is review, integration and release of the implemented capabilities; SDK execution now requires explicit project or conversation selection, with named-project Application service coverage recorded alongside the Personal workflow. Collaborative editing and editor grants are not prerequisites or later stages of this roadmap. Portable representation and stable identities remain requirements from the first stage, even where optional export/import arrives later. AI retrieval and research continue to use the same knowledge and evidence records.

## Schema milestone implementation sequence

1. **Protocol and profile decision.** Finalise scoped identities, lifecycle, bounded diagnostic errors, revision UUID concurrency and typed admission DTOs. Define the pure shared validation contract and pin profile semantics with tool/document fixtures. Decide metadata-only reuse, explicit processing authority, accounting and report lineage contracts in an accepted platform ADR before implementation.
2. **Registry and persistence.** Implement repository interfaces, immutable definitions, management receipts, lifecycle concurrency and access rules. Add additive migrations, revision attribution, reuse provenance and durable processing policy, with deletion/immutability coverage.
3. **Typed revision admission and reuse.** Cover upload, revise, replace, metadata-only changes, reports and refresh commit. Implement atomic compatible projection reuse, explicit-only scheduling and owner-requested repair without new charges for reused work. Preserve fingerprints for legacy calls and prevent by-name bypass of typed state.
4. **SDK and discovery.** Generate shared operation schemas and native contracts. Add type discovery, validation, admission, metadata reads and permission-aware filters. Update information model tools without adding schema-management authority.
5. **User surfaces and documentation.** Deliver the Library authoring/read/metadata-revise flow, content-type selector, metadata and provenance views, conflict handling and type-management controls for authorised people. Explain conformance, explicit processing, annotations, errors, migration behaviour and limits.

A useful first vertical slice is an account-owned `incident-report` type, one valid Markdown source, one rejected source missing `severity`, a metadata-only second revision with compatible outputs reused without paid processing, and recovery of the original request receipt. Include a second case with incompatible or missing outputs that saves metadata successfully, exposes unprepared capabilities and waits for explicit processing. Acceptance still requires all exposed admission paths to enforce typing before enabling typed writes and the minimal human workflow to function; an upload-only prototype must not be enabled against resources writable through bypassing paths.

## Verification and acceptance

Use mock repositories for service behaviour and shared contract fixtures for codecs/profile validation. Focused PostgreSQL tests are justified only for new migrations, relational constraints, transaction rollback, concurrent version activation/revision allocation and projection reuse, immutable-field triggers, typed admission idempotency, durable processing policy and atomic deletion redaction. Keep them behind `-PfullDb` and select the smallest required suites.

Required cases include:

- Valid metadata, required/unknown fields, explicit null, nested collections, numeric portability, Unicode lengths, forbidden references, unsupported keywords and all budgets.
- Shared validator refactoring preserves existing tool acceptance, rejection, fingerprint bytes and SDK fixtures; structured issues consume bounded work and never echo private values. Runbook metadata can conform while body completeness and factual accuracy remain unverified.
- Draft/active/deprecated transitions; inherited deprecated bindings; explicit upgrades; schema conflicts; concurrent writers; exact version retention.
- Expected revision UUID belongs to the selected resource and names its highest committed revision; inheritance and source copying use that same UUID. Stale edits conflict without implicit merge, head substitution or private revision disclosure.
- Same body with different metadata creates a distinct revision; old citations and hashes remain valid; metadata-only revisions preserve source/provenance and make reports draft. Original body producer and metadata reviser remain distinguishable; body-edited reports do not falsely attribute replacement prose to the original run.
- Metadata-only saves need no positive document model allowance and make no document-processing model or acquisition call. Configured hook gates and their independent accounting remain intact. Compatible completed outputs are reused atomically with origin provenance and unchanged evidence offsets; failed, running, incompatible and gated/private outputs are never reused. Reuse races conflict without publishing a different plan after approval.
- Missing/stale outputs remain visibly unprepared; automatic workers and enrichment sweeps do not process explicit-only revisions, including after restart. Owner-requested repair respects stage selection, dependencies, access, allowance and gates. Reused outputs create no new usage charge or allowance transfer, and report finalisation still requires five compatible ready stages.
- Deletion redacts each revision's copied projections and metadata atomically; reuse provenance cannot retrieve deleted origin content or bypass live access. Revision-specific sharing grants are not inherited, and original report input restrictions remain effective.
- Every admission path refuses invalid metadata and silent stripping of typing, including URL refresh, legacy by-name upload and report recording.
- Same request/same payload recovers the receipt; changed payload conflicts; schema deprecation after commit does not replay hooks or reject an authorised receipt read.
- Owner/project/service-token fences, no model schema-management grants, cross-namespace refusal, attached-contract visibility and no inaccessible-type enumeration.
- Permission filtering before facets/ranking, no metadata in extraction offsets or automatic model context, and complete metadata redaction on deletion.
- Legacy untyped fixtures and fingerprint bytes stay unchanged; all SDKs preserve explicit null and type identity; unsupported clients fail visibly.
- A person can select a type, enter body/metadata, correct validation errors, save, read the bound contract and revise metadata in Library. The interface distinguishes conformance from verification and exposes explicit processing and conflict recovery.

Design review must also preserve the broader direction: schemas remain independent of storage format, the same record can appear in several facet views, evidence retains revision-specific meaning, and native document bodies and metadata have an explicit portable representation. Later export/import acceptance must verify retained Markdown byte preservation, schema/metadata round trips, identity remapping, safe paths, link handling, access-filtered selection and no automatic restoration of foreign permissions.

Before merging implementation, run formatter/check gates, operation/schema/manifest generation checks, SDK conformance and the focused persistence coverage required above. Successful design review is not evidence of a deployed capability.

## Alternatives and recommended decisions

Tags alone cannot validate required fields. YAML parsing alone cannot give source revisions an authoritative typed metadata contract. An Application-owned JSON validator cannot enforce cross-client admission and discovery. Extending `kind` or format classification would collapse independent concepts. A second document store would duplicate retained revision and access lifecycles. These alternatives do not address the platform need.

Adopt an agent information and knowledge system with explicit relationships and faceted navigation as the broader direction. Keep content authoring external or as minimal replacement-content admission, and make metadata the primary direct maintenance surface. Implement optional content types on existing information revisions first, using scoped definitions, exact version pinning and the shared bounded validation profile. Include safe projection reuse and durable explicit-only processing for metadata-only revisions, with a minimal human authoring/read/revise workflow. This increases the first milestone's lifecycle and verification work but keeps ordinary metadata maintenance independent of paid inference. PostgreSQL owns authoritative state; Markdown remains an authoring and exchange format, with portable interchange developed through explicit public contracts.

Keep default insertion, date/URI assertion, arbitrary field queries, model-generated metadata, Application-distributed type packages, editor grants, relationship APIs and export/import tooling out of the first schema release. Those boundaries preserve an achievable first milestone without reducing the knowledge model to a metadata add-on. VOIDcore integration remains a separate decision.

## Source references

- [VOIDcore typed substrate migration](https://github.com/myrddian/VoidCore/blob/577b26b0575f1c6b9967e27face3f5ce2cd3e4de/app/src/main/resources/db/migration/V11__document_substrate_typed.sql), schema records, `SchemaRepository`, `FrontmatterValidator` and `DocumentRepository` at the same commit. [ADR-023](https://github.com/myrddian/VoidCore/blob/577b26b0575f1c6b9967e27face3f5ce2cd3e4de/docs/DECISIONS.md) supplies the authored-document and facet-navigation model.
- [Plowshare information system](https://github.com/myrddian/plowshare/blob/e0fc6caef8b4e68deedc4231af26512ba0a50214/docs/information-system.md), `InformationRequest`, `InformationCatalogue`, `InformationCatalogueFingerprint` and `V83__information_lifecycle.sql` at the target baseline.
- [Knowledge evidence and research](https://github.com/myrddian/plowshare/blob/e0fc6caef8b4e68deedc4231af26512ba0a50214/docs/manual/06-knowledge-and-research.md) and `TextExtraction` distinguish existing workspace Markdown, retained source revisions, reports and supported text ingestion.
- [Shared schema profile](https://github.com/myrddian/plowshare/blob/e0fc6caef8b4e68deedc4231af26512ba0a50214/docs/tool-schemas.md) and decisions 0018, 0022 and 0023 for typed values, validation limits and schema identity.
- [Platform integration boundary](https://github.com/myrddian/plowshare/blob/e0fc6caef8b4e68deedc4231af26512ba0a50214/docs/decisions/0001-integration-boundary.md). This proposal addresses general authored-information needs and authorises no adapter work.
- [JSON Schema 2020-12 validation](https://json-schema.org/draft/2020-12/json-schema-validation), especially the separation between assertions and annotations.
