# Information discovery and read selection

The information system is the authoritative inventory of retained sources and
reports. Homes are governed by scoped RBAC and resource policy. Deprecated corpus
tools and manual search results cannot establish that an application's reports,
scans or tag metadata are absent.

## Decision

Provide scope discovery and cursor-based metadata list, lexical search, facets
and exact revision reads over authenticated WebSocket operations. Scope IDs are
locators, never grants. Ordinary discovery uses the acting identity's current
effective information READ across selected scopes, independently of the execution
and write home. This deliberately extends ADR 0030's retained-home selection for
this read-only suite. Existing controls retain their narrower selection behavior.

Credential/owner limits, live RBAC, mandatory privacy, current root audiences and
dependency/reference closure restrictions still govern each request. Contained
principals act as themselves, not their container's account. Confined/contained
runs retain a server-owned home/subtree ceiling and cannot select general knowledge.
No payload or cursor can supply or widen that ceiling.

Execution authority, write destinations, publication, membership, allowances and
accounting stay with their owning APIs. Disclosed metadata and body reads retain
their source revisions under the actual read selections. Facets retain all
contributors in the same SQL statement as their count, without transferring the
corpus into model context. Later log/report readers recheck those dependencies.

The repository resolves one live authority relation for a multi-scope candidate
selection before page limits and aggregates. Inventory selects current heads;
exact reads may target history. Search reuses stored content vectors and bounded
metadata expressions. There is no application query-per-project merge or shared
permission cache.

Signed keyset cursors bind the acting identity, confinement, operation, selection
and filters. They expire after fifteen minutes or a process restart and confer
no authority. Newer inserts do not shift offsets; edits, new heads and revocation
can change later pages. Clients distinguish completed traversal, partial coverage,
no matches, unavailable content and refusal.

## Agent context and compatibility

Shipped agents replace deprecated corpus discovery grants with the information
suite. Existing transport and custom-agent compatibility tools remain registered;
they are not the shipped inventory workflow. Public legacy operations retain
their response shapes. New discovery operations are typed across the SDKs.

The information-discovery skill uses existing `NEW` mode with the read-only
Librarian. Source reading stays in a fresh child conversation and a concise answer
returns through the durable skill lifecycle. Librarian remains exported and
independently delegable. No new mode enum or durable runtime is needed. The skill
guides tool use; services and repositories enforce access.

Document evidence capabilities remain separate from corpus discovery. Shipped
readers and the skill retain `document_retrieve`, `document_outline` and
`document_citations`, with required explicit `scopes`. Their `document` locator is
an exact revision UUID, matching discovery results. Semantic passage ranking and
outline projections apply the same live scope/dependency rules before reading;
stored projection hashes must match the retained revision. Provider admission,
allowances and invocation identity stay with the durable execution home. Source
authorization is independently checked before inference and again at disclosure.

Citation reads require both readable selected sources and owned readable
conversations, including current READ on the conversation home under the acting
credential and its owner's limits. Ownership alone overrides neither role
revocation nor credential narrowing. Server confinement limits both citation
conversation homes and every retained input across the same ancestry that citation reads inherit;
revision dependencies and references must remain within that boundary too.
These gates precede pagination and audit retention. An explicit inaccessible
conversation is refused, while collection reads omit it before applying limits.
Missing documents cannot establish source authority and are omitted; missing
paragraphs retain their `paragraph_gone` standing without text. Results retain the cited
sources and inherit the source conversations' input dependencies, so a citation
cannot shed a restriction belonging to its conversational context. These bounded
content reads do not establish inventory completeness. Legacy public transport
selection and response contracts remain unchanged.

## Verification

Mocked tests cover strict decoding, query/identity/confinement-bound cursors,
expiry, UTF-16 windows, runtime binding and skill/callee capabilities. Focused
PostgreSQL tests cover migration, authorization before paging, metadata discovery
without indexing, heads, live revocation, dependency refusals, confinement and
retention of facet contributors. Shared SDK fixtures cover valid and malformed
scope, cursor, filter and read requests.
