# Global knowledge read contract

Implemented contract for ordinary knowledge reads. This closes the document/memory
read-selection inconsistency recorded after slice 18. It does not change accounting,
delegated runtime composition, execution homes or knowledge WRITE permissions.

## Authority and selection

Every active authenticated human, Application and service credential is eligible
for GLOBAL_READ. Eligibility grants READ only on resources whose current root policy
selects that audience. Global account custody is a separate concept. Scoped
ADMIN/MANAGER/CONTRIBUTOR/READER roles remain the normal management surface; existing
mandatory resource restrictions refine them. Created-by and old visibility flags
do not grant access.

The authenticated adapter supplies identity, the request supplies a selection, and
the server supplies any retained operation ceiling. Repositories apply all three
before ranking, counts, facets, paging or disclosure. Current live grants, root
policy, input dependencies and reference obligations remain mandatory. Revocation
and audience changes apply to historical revisions too.

| Existing API selection | Meaning |
| --- | --- |
| `{"kind":"personal"}` | Own account knowledge plus generally readable resources |
| `{"kind":"project","project":"research"}` | Selected project plus generally readable resources |
| Personal/project with `"includeGlobal":false` | Narrow discovery to the selected home |
| `{"kind":"global_read"}` | Only resources with current GLOBAL_READ audience, regardless of custody |
| `{"kind":"account","account":"00000000-0000-0000-0000-000000000004"}` | Existing explicit Global account destination/read selection |

Selection does not grant project membership. General READ does not grant Global
WRITE, audience administration or execution. Existing share/unshare commands set
the root audience; they are not an independent legacy permission system. Personal
selection remains unavailable to service tokens. Token ceilings, tool restrictions,
acting authority and mandatory privacy constraints still intersect access.

## System interfaces

Live authority evaluation reads the existing universal GLOBAL_READ eligibility
from the role repository, matching SQL. It is not an issuable supporting grant
and cannot authorize `allowsUsing`, project membership, WRITE or execution.

`KnowledgeReadScope` is the internal retained home/Global-read contract shared by
memory and document readers. It grants nothing independently. Ordinary runs use
their captured admission; missing historical Global eligibility remains false.
Confined operation tools cannot expand it. Memory uses it to choose readable
home/Global tiers; documents intersect it across the consumed input/reference
closure. Memory publication and document root audiences retain their existing
resource-specific contracts.

`InformationAccess` admits selectors and carries the server-owned ceiling.
`InformationNavigationService` accepts that ceiling through an internal overload;
it is not a wire field. `InformationSql` encodes the common repository predicate.
The database owns live scoped READ and current-root/dependency decisions. This
keeps navigation, direct reads and discovery on the same authorization boundary.
No client can supply an execution ceiling in an information payload.

Default standalone human and service searches include generally readable knowledge.
A retained run additionally intersects that selection with its captured ceiling.
Explicit project-only selection narrows discovery. A reference may navigate to a
resource where the caller has current READ, subject to the retained ceiling;
approved locked labels are a separate, explicit reference-policy disclosure,
never body READ. Retained ceilings also bound those references.

## API cutover and migration

This is a deliberate breaking selector change on existing operations:

- `scope.kind:"shared"` becomes `scope.kind:"global_read"`.
- `includeShared` becomes `includeGlobal` on Personal/project selectors.
- `global_read` accepts no project, account or includeGlobal field.
- Old selector names are rejected, without aliases or silent fallback.
- No new operation is added. Publication commands and existing response/event
  names such as share/unshare remain unchanged.

TypeScript contracts generate the SDK operation graph and native SDK contracts;
repository clients and integration consumers move together. Externally installed
clients must update their selectors. Existing account-target operation support is
unchanged; this does not promise every SDK exposes every client feature.

V169 replaces the active read decision chain with substrate checks. Rootless
compatibility rows must be admitted through the supported knowledge lifecycle;
assigning an old revision policy alone cannot make them readable. LEGACY_SHARED
root policies narrow to HOME, require review, and record the change in immutable
history. Managers may deliberately publish
those resources through share. Old revision visibility flags and historical policy
records remain evidence, never alternate grants. Historical stored Shared
selectors do not acquire new general-read authority.

The existing SQL `include_shared` parameter/ledger column names remain storage
encoding only. They no longer select a legacy audience. Shipped migrations are
unchanged. The dependency traversal is cycle-safe and fails closed above 1024
revisions. No live corpus transfer or publication is performed by this code change.

## Verification

Mocked tests cover strict selectors, retained ceiling propagation, service/human
parity and runtime tool binding. Focused PostgreSQL coverage verifies V169,
current-root publication/revocation, explicit Global custody, project/general
selection, retained ceilings before counts/navigation, and denial of rootless
legacy sharing. SDK generation and repository checks verify the wire cutover.

Verification on 19 October 2026:

- `format formatCheck` and `check sdkCheck` passed. The default server suite ran
  5,898 tests with zero failures, errors or skips; database tests were not enabled
  by the default gate.
- The focused PostgreSQL run passed 34 cases across current-root policy, migration,
  navigation, cutover rehearsal and the real retained Global-read admission/tool
  path. No whole database suite was run.
- SDK generation verified 276 existing operations and 483 canonical DTO cases.
  Java/shared contracts and Node/Python/.NET/Go WebSocket conformance passed, with
  native compiler, Python typing/lint and Go race checks using installed toolchains.
- The affected Python integration passed 214 tests; the manual installer passed
  15 public-contract tests, including its local WebSocket fixture.
- Whitespace, new documentation links and migration-version uniqueness passed.

This verifies source and isolated test runtimes. No deployment, live corpus
publication/adoption or old-binary rollback was performed.
