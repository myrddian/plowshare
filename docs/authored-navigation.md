# Document navigation and reference policy

Document navigation extends the [authored information workflow](authored-information.md)
with current-document browsing, explicit relationships, backlinks and saved internal
paths. Documents retain resource UUIDs across immutable body revisions. Paths are
selections, not HTTP URLs, directories or canonical document names.

The implementation is local source work until separately reviewed, integrated and
deployed. [ADR 0028](decisions/0028-document-navigation-and-reference-policy.md)
records the contract and [the implementation plan](authored-navigation-implementation.md)
tracks verification.

## Enable writes

The server configuration `plowshare.information.navigation-writes-enabled` defaults
to `true`. Relationship, policy and saved-view writes are available within the
caller's existing permissions and scopes. Operators can set it to `false` to suspend
those writes across the server; reads, capability probing and enforcement of
stored obligations remain active. The switch does not select a reference policy
or release inherited restrictions. Changing it requires a server restart. See
[information feature switches](server-administration.md#information-feature-switches)
for both switches, environment overrides, affected operations and upgrade behavior.
Do not roll back to a binary that cannot enforce the retained records.

Before adding references, a server administrator or project manager should inspect
and initialise the applicable project policy. The initial baseline uses existing
content authority, hidden references and propagation. No existing tags or collection
links become authored relationships automatically.

For example, a manager can initialise a project with existing content access,
visible approved references and no ordinary-link propagation. Document overrides
remain subject to mandatory platform/project constraints. Personal-document policy
is administered by server administrators.

## Browse and save a path

In Library → Documents → Manage document sources and imports, open **Document
navigation**. Choose the source collection and use **Browse selected collection**.
The new view shows one current entry per resource. Existing source/version views
retain their revision lists and counts.

1. Open a service document such as Billing service.
2. Follow a visible relationship, or enter a relationship name in **Browse related
   documents** to form a related-document selection.
3. Narrow a selection by tags, a content type or text search. Tag selections form
   intersecting document sets; sharing a tag creates no pairwise relationship.
4. Open Refund recovery. Breadcrumbs restore earlier selections.
5. Enter a saved-path name and save. Reopening resolves current heads and checks
   current access again. Saved views belong to the account, not a project editor group.
   Each account can retain up to 100 active views.

Ordinary document targets follow the latest committed revision. A withdrawn,
deleted or inaccessible head is unavailable; an older matching/readable revision
is never silently substituted. **Pin this version in the path** makes that selection
exact. Pinning freezes content identity, while permissions remain live. The pinned
reader omits the live relationship graph; return to an unpinned document selection
to navigate current resource relationships.

A document owner with the required current project contribution role can add or
remove outgoing relationships. Relationships belong to the source resource, so
body revisions preserve them. A relationship can follow the continuing target or
explicitly pin the selected target revision. Backlinks are filtered inverse views,
not independently authored reverse dependencies.

The Library picker selects documents in the current collection. The public SDK
also accepts a known target resource UUID for cross-project or explicitly permitted
locked references; this does not create a global directory of private documents.
Cross-project traversal checks the target's durable scope. Service identities retain
the selected project and no-Shared ceiling.

## Read typed instance fields in a page

Public SDK callers can request `projection:"metadata-v1"` on
`information.documents.list`, together with `view:"content-types-v1"`. The same
scope, filters, traversal and limit/offset apply. The response echoes the
projection marker, including on empty pages. Typed rows include `contentMetadata`:

```json
{
  "type": {
    "type": "11111111-1111-4111-8111-111111111111",
    "version": "22222222-2222-4222-8222-222222222222",
    "ordinal": 1,
    "slug": "incident",
    "label": "Incident",
    "definitionHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "metadataHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "metadata": {"severity": "high", "note": null}
}
```

The UUIDs and digests above illustrate shape only. This projection omits bodies,
schema definitions, presentation and processing state. Missing contentMetadata
does not distinguish untyped and redacted bindings. It grants no additional
access. Omit projection for the unchanged discovery-only response.

At most 100 rows and 512 KiB of aggregate stored metadata JSONB text can be read
per page; each instance retains its existing 16-KiB metadata limit. An oversized
page fails explicitly: reduce limit and start the read again. No fields or rows
are silently truncated. Clients should verify the projection marker and require
support rather than hydrate each row as an implicit fallback. For internal
agents, `information_read` accepts the same projection and records returned
revisions through its normal consumed-input path. See
[ADR 0033](decisions/0033-paged-document-metadata-projection.md).

## Configure policy

Each policy has three independent controls:

| Control | Meaning |
| --- | --- |
| `ownerOnly` | Further restrict content to the document owner; existing scope, lifecycle and input checks still apply |
| `referenceHidden` | Require content access to disclose a reference; otherwise an authorised source may show the approved reference representation |
| `propagate` | Make ordinary incoming references acquire required target access obligations |

Settings contain `defaults`, `mandatory`, `allowOverrides` and `referenceLabel`.
Mandatory booleans combine with OR: an override cannot turn off a mandatory
restriction. Platform/project settings govern permitted document overrides.
The label is deliberately approved text, never an automatically copied private title.

A locked reference exposes only its resource identity and approved label in a
visible relationship. It contains no private title, body, revision metadata or
pinned-revision identifier. Quarantined targets remain undisclosed until the existing adoption controls admit
them. Hidden nodes and edges are omitted before paging;
document facets/counts include only readable current documents. An unavailable
response reveals no reason identifying a hidden dependency.

Managers administer policies in their own projects. Server administrators manage
platform, project and personal/document policies. Ownership or contributor access
alone does not grant policy authority. Administration does not bypass content
checks. Role rows are checked and locked inside the mutation transaction so a
concurrent demotion cannot authorise a later commit.

Policy initialisation may select a more permissive baseline only before affected
resources acquire reference history. Subsequent settings must not weaken an
established effective control. A widening request returns `policy-release-required`;
this version has no declassification/release workflow. Initialise the intended
project profile before adding relationships. Existing membership and content-sharing
grants remain live under their owning APIs; these controls do not freeze a historical
reader list. Mandatory `ownerOnly` restrictions still prevent a sharing change from
granting non-owner content access.

If A references protected B, required restrictions constrain A. Existing C -> A
references also acquire the obligation, even when A's own default says ordinary
references do not propagate. An intermediary cannot waive inherited restrictions.
Backlinks do not reverse this dependency. Unlinking retains incurred obligations;
actual copied/evidence/model-input dependencies always remain required.

Policy changes and relationship admission record the complete affected obligations
transactionally. Reads do not lazily discover them. Evaluation is cycle-aware and
fails closed beyond 1,024 revisions in a required-reference closure. Control
transitions refuse more than 10,000 affected documents. The retained relationship
graph is capped at 10,000 records, including removed edges; removal does not reclaim
that history budget. Oversized work is refused explicitly.

## Public operations and recovery

All operations use the existing authenticated WebSocket boundary. Use
`information.navigation.capabilities` before using this contract.

| Family | Operations |
| --- | --- |
| Current documents | `documents.list`, `documents.facets`, `documents.get` |
| Relationships | `relationships.list`, `relationships.create`, `relationships.remove` |
| Policy | `policy.get`, `policy.set` |
| Account paths | `views.list`, `views.get`, `views.save`, `views.delete` |

Prefix each name with `information.`. Every request supplies `scope`; the supporting
`content-types-v1` view is required when using type filters. Document selections
name `{ resource, revision? }`; omitting `revision` follows the current head.
`documents.get` returns a closed `readable` or `unavailable` result. Relationship
results may additionally contain a `locked` selection. Readable entries include
the scope needed to open their retained revision through existing content APIs.
SDK callers should supply the supporting `content-types-v1` view for those revision
reads, as Library does.

Mutations carry a caller-retained UUID `requestId`. Relationship UUIDs are
server-issued and distinct from account-owned request IDs. Creation fences the
source's displayed head with `expectedSourceRevision`; removal uses `expectedVersion`. Policy and
saved-view creation use expected version zero; later mutations use the returned
version. Changes, receipts and audit commit atomically. An expected-version conflict
requires an explicit refresh/review. A lost reply must not cause automatic replay;
repeat the exact request ID/payload explicitly to recover its durable receipt.

Saved paths contain 1–32 closed steps: one initial `scope`, followed by `document`,
`traverse` or `filter` steps. Filters retain the complete current facet selection;
breadcrumbs restore those selection snapshots. Paths contain typed IDs/criteria,
not SQL, scripts, route strings, captions or cached result lists. Reopening stops
when a previously selected document/relationship is no longer authorised. The
`otherResource` selector on relationship reads checks an exact saved-path neighbor
before paging; clients do not infer absence from an arbitrary first page.

Model `information_read` supports document list/get and relationship reads; actual
readable document metadata is recorded as consumed input. Approved locked references
do not count as body reads. Relationship results also retain their readable source
as an input; source revocation before ledger binding refuses the result.
`information_write` supports relationship creation and
removal under the bound run account/home. Models cannot administer policy or saved
views. Navigation controls have their own versioned receipts/audit; they do not
create body revisions or run document-processing/transition models. Existing
revision-processing hooks and accounting retain their own contracts.
