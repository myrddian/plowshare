# Authored navigation and reference-policy implementation plan

Status: proposed next-milestone plan, 15 October 2026. The
[proposal](authored-knowledge-design.md) records the agreed experience and
[ADR 0026](decisions/0026-document-navigation-and-reference-policy.md) defines the
proposed contract. This plan does not implement new runtime behaviour. The local
schema milestone is `49fdab28834cdecf61b2f3cfe279ecdedce1247a`; fetched public main
is `a6d642a9c8a12d419e6bb3c680617c7e826cdc3e`. Recheck main before coding or publication.

## Agreed scope and recommendations to review

Deliver document navigation over retained resource identities, explicit live or
pinned relationships, policy-aware backlinks, project/document configuration,
administrator/manager policy controls, internal paths and saved selections.
Preserve tag-set semantics and existing source/report lifecycle and evidence.

The agreed decisions are: ordinary links open latest current revisions; explicit
revision references stay pinned; source connections belong to documents; policies
separate content access, reference disclosure and propagation; projects choose
defaults/constraints and permitted document overrides; only administrators and
project managers administer those policies within their authority.

Review these implementation recommendations together before beginning:

| Recommendation | Reason / consequence |
| --- | --- |
| Resolve the highest committed resource ordinal, then authorise | Deterministic current-document behaviour; no silent fallback to an older readable/matching revision |
| Default new reference handling to hidden/restrictive until explicitly configured | No accidental private metadata disclosure during rollout; existing records keep existing access |
| Initially disclose locked references through authorised source edges | Supports the requested locked-content behaviour without introducing global private-document enumeration; approved audiences/fields still need exact DTOs |
| Reject audience-widening updates until authorised release is implemented | Prevents unlinking or mode changes from silently declassifying retained content; managers cannot initially relax previously incurred obligations |
| Account-owned saved views first | Durable usable selections without adding project-wide view-editor permissions |
| No cross-request allow-decision cache initially | Keeps revocation/head-change semantics direct while correctness is established |

These are proposals, not assumptions hidden in implementation. If full policy
relaxation is required in this milestone, design the release workflow and durable
obligation history before estimating or implementing it.

## Verified owners and capability gaps

| Current owner | Source-grounded behaviour | Next-milestone change |
| --- | --- | --- |
| `InformationCatalogueRepository`, `JdbcInformationCatalogueRepository` | Revision-based list/inventory/read; admission matches namespace/source name; `information.link` links revisions to project collections | Separate current-document projection and resource/pinned resolver; preserve legacy collection-link meaning |
| `InformationFacets`, `InformationFacetSql`, `InformationSql` | Typed intersecting filters; permission predicates before counts/paging; counts are revisions | Document-set projection/counts and policy-aware discovery without reinterpreting existing results |
| `InformationAccess`, `InformationContext`, `ProjectMembers` | Current selected-scope membership/work checks; service tokens require project scope without shared | Scope-aware target evaluation, retaining execution/credential ceilings; no implicit access through traversal |
| V83 `information_readable`, `information_inputs`; `InformationJobs` | Recursive retained revision-input restrictions and rechecks for derived work | Central effective policy used by every existing surface; live resource references require separate semantics from actual inputs |
| `AdminStore`, `ProjectAuthorization`, `ProjectRole` | Live server-admin checks and ordered project roles | Dedicated administration classification; management does not imply body access |
| `DocumentPolicyAssignments` | Controlled migration/adoption of quarantined policies | Keep migration controls intact; new policy administration uses its own typed service/repository |
| `InformationTransitions`, `InformationWriteGates` | Typed prepared transitions, stable receipt identities, pre/post hooks | New bounded candidates for relationship/policy changes with optimistic versions and safe audit payloads |
| `InformationRequest`, `InformationResponse`, `InformationOperations`; SDK generators | Shared revision/schema contracts; no relationship/saved-navigation contract | Closed document/reference/path/policy DTOs and cross-SDK cases, registered with implemented operations |
| Desktop `renderer/information.ts`, `authored-information.ts` | Scoped Library, facets, typed metadata edits and processing controls | Document-level browse, paths/backlinks, locked-reference presentation, management controls and saved selections |

An Application can consume the resulting SDK operations, but cannot supply the
missing access invariant by wrapping existing catalogue calls. This work is an
explicit platform extension under ADR 0001, not an adapter workaround.

## Public contract work

Proposed operation families below describe contracts to implement, not current
registered methods. Final names, DTO fields and operation/error fixtures belong
to unit 1. Do not place speculative entries in generated catalogues.

| Family | Required capability |
| --- | --- |
| Navigation capability probe | Advertise implemented document/reference variants and supported policy/path contract version |
| Document list/facets/get | One current resource entry; exact type/tag intersections; current or explicitly pinned target; no fallback |
| Relationship list/create/remove | Outgoing/backlink reads; resource identities and optional pinned revision; stable request ID and expected control version for mutations |
| Effective policy read/set | Platform/project/document scope, inherited/effective values, mandatory constraints, permitted overrides, expected policy version and human authority |
| Saved-view list/get/save/delete | Account-owned typed path, stable identity/control version and idempotent mutation receipt |

Result variants must distinguish readable documents, locked references containing
only permitted fields and ordinary unavailable outcomes. Never use nullable
success fields or a partially redacted existing revision DTO to conceal a denial.
Administrative diagnostics may explain constraints only to authorised managers.
Normal discovery cannot disclose a hidden target or hidden dependency chain.

## Delivery units

1. **Contract and policy matrix.** Finalise all review recommendations, policy
   combinations and override ordering. Specify source/target reference authority,
   disclosure audiences/fields, current-head rules and stable errors. Define
   typed policy evaluator inputs/results and path steps before implementations.
   Test pure combinations with mocked authorities, including non-propagating
   visible references, required propagation, role demotion, unknown policy and
   conflicting project/document constraints. Completion: every field and decision
   has a closed contract and acceptance case; no capability is advertised.
2. **Policy persistence and obligations.** Add specialist repositories for policy
   versions, overrides, relationship/control records, durable obligations, receipts
   and audit. Record relationship/source authority independently of body revision
   ownership. Design restrictive-target policy transitions and audience widening
   so no affected source is missed between transitions; do not rely on reads to
   discover obligations. Select new Flyway numbers only after a current-main
   migration audit. Completion: focused PostgreSQL constraints, concurrency,
   atomic rollback and obligation-retention tests pass.
3. **Central read enforcement.** Compose the effective-policy interface into
   catalogue/document reads, search/rank and facets before filtering/paging, model
   tools, evidence, jobs/results, inventory/events and history. Recheck policies
   on retained corpus outputs and existing grants. Current/pinned references,
   cross-project targets and credential ceilings use authoritative durable scope.
   Completion: a caller cannot bypass a restrictive policy through any old API,
   client, projection, sharing operation or cached result. Write gates remain
   disabled until this is true.
4. **Current documents and relationships.** Implement bounded head resolution,
   document-set counts, relationship mutations and filtered inverse reads. Use
   fresh consistent policy/head snapshots and commit-time control fences. A
   body edit does not erase links; an unlink cannot erase retained obligations.
   Do not accidentally make reference-visible/non-propagating targets real model
   inputs through gate plumbing. Completion: concurrent head/policy/link changes
   produce a complete authorised result or explicit conflict/unavailable outcome,
   never mixed policy snapshots or a replayed mutation.
5. **SDKs and tool policy.** Register implemented operations and update protocol,
   Java, TypeScript/Node and generated Python/Go/.NET DTOs together. Add absence,
   unknown-field, bound, control-version and private-output fixtures. Keep policy
   writes out of model menus. Relationship tools use the bound owner identity and
   current reference authority; actual document reads retain existing input
   dependency/accounting behaviour. Completion: shared conformance and legacy
   compatibility checks pass without widening any service-token ceiling.
6. **Paths, saved views and Library.** Persist bounded typed selections in an
   account-owned repository. Add breadcrumbs, relation traversal/backlinks,
   document-level facet counts, history/pinned display and locked/unavailable
   variants. Managers/admins can inspect effective policy and apply permitted
   version-fenced changes; ordinary owners cannot edit policy. No HTTP URL,
   executable path expression, directory move or form-designer framework is
   required. Completion: the human acceptance journey below passes with the
   real SDK boundary and an isolated UI fixture, plus server persistence tests.
7. **Release gate.** Re-integrate current main, audit new migrations, regenerate
   all operation/SDK/menu manifests, run format/check/SDK gates and focused
   persistence tests. Review denied-route coverage against the complete public
   read inventory. Enable the new capability only after all units pass. Disabling
   writes must leave policy enforcement active; no merge/deployment is implied
   by a local test pass or a draft PR.

Interfaces should be narrow domain contracts for effective policy, relationships,
document navigation and saved selections. Services receive interfaces through
constructors. JDBC stays in owning repositories; raw JSON stays at transport,
configuration and persistence boundaries. Reuse existing receipt/gate and access
primitives rather than building a parallel durable runtime or generic graph engine.

## Acceptance journey and security matrix

The primary human journey is:

```text
Operations → Billing service → Runbooks → Refund recovery
```

Create ordinary retained service/runbook documents, establish an explicit
relationship, narrow related documents by type, open the runbook, return through
breadcrumbs and save/reopen the selection. Reach that same resource through a
tag-set intersection. No model or document processing is needed to navigate.

| Case | Required result |
| --- | --- |
| Runbook gets another body revision | Existing unpinned paths/links open the new head; explicit pinned links/history remain exact |
| Latest head is unavailable or no longer matches filters | No older matching/readable revision is silently substituted |
| Two revisions share the selected tag | Document view counts one resource; legacy revision counts retain their existing semantics |
| Locked body with permitted non-propagating reference | Source remains readable; approved reference fields render; body/private title/extraction remain inaccessible |
| Hidden target | No unapproved edge, label, preview, facet value, identity or count is disclosed |
| A -> B requires propagation; C -> A | B's required restrictions constrain A and C; automatic backlinks do not restrict B by A |
| Same tag on unrelated public/private documents | No viral policy dependency is created; aggregates apply their discovery policy |
| Source project allows references; target project requires protection | Target requirements still apply; neither project membership nor an existing grant is invented |
| Manager demoted or removed while session remains open | Next policy mutation fails; commit recheck prevents a concurrent authority change from taking effect as an authorised update |
| Owner edits document but has no management role | Ordinary permitted edits remain possible; policy-changing commands are denied |
| Relationship deletion or policy widening follows a restrictive interval | Durable obligations and actual inputs survive; widening requires the designed release contract or fails explicitly |
| New target policy changes between read/prepare and mutation commit | Candidate is rechecked; an incompatible change conflicts without applying another plan |
| Cross-project service token or guessed UUID | Existing identity/scope ceilings remain; hidden and absent targets have indistinguishable refusal |
| Cyclic or oversized dependency/path graph | Evaluation terminates within bounds, checking all required authority; incomplete evaluation grants nothing |
| Saved view reopened after policy or membership change | Current policy governs; old rendered labels/results and cached allows are not reused as authority |
| Rollback/write-disable after protected records exist | Existing reads still enforce policies; no legacy API becomes a bypass |

Also test malformed relation names, malformed path steps, explicit null versus
absence, foreign pinned revisions, stale versions, changed request payload under
the same request ID, mixed-profile overrides, unsafe labels and output escaping.

## Verification and completion checklist

Pure policy/path tests use mock repository/authority interfaces by default.
PostgreSQL is justified only for actual migrations, policy/relationship row
mapping, transaction rollback, concurrent changes and obligation persistence.
Keep those tests under `@Tag("full-db")`; state the database-specific assertion
and use the smallest `-PfullDb --tests` selection. Do not run the whole database
suite or Docker smoke as general confidence checks.

After Java edits run `format` and `formatCheck`; before a repository-wide handoff
run `check`, owning generation checks and `sdkCheck`. UI smoke must inspect locked
reference and hidden-node/count behaviour, manager-only policy controls, current
versus pinned opening, breadcrumb restoration and revocation on saved-view reopen.
Record exact passed commands and distinguish mocked UI transport from real
persistence and live deployment evidence.

- [ ] Review decisions and exact policy/DTO/error matrix approved.
- [ ] Durable obligation/release behaviour is complete; no read-triggered gap.
- [ ] New policy administration is correctly scoped, audited and version-fenced.
- [ ] Every existing public read has effective-policy coverage before disclosure.
- [ ] Resource heads, tag sets, relationships and backlinks preserve agreed meaning.
- [ ] All SDKs and model/human operation classifications match the server contracts.
- [ ] Library and durable saved paths pass the complete acceptance journey.
- [ ] Legacy catalogues, input restrictions, service ceilings and collection links remain compatible.
- [ ] Required format, default checks, shared SDK and focused database gates pass.
- [ ] Capability activation and rollback retain enforcement under all tested modes.

## Scope limits

This plan does not add HTTP navigation URLs, directories, automatic relationship
inference, arbitrary metadata querying, a relation-type registry, global private
document enumeration, real-time collaboration, new editor grants, portable bundle
export/import or a general licence/classification engine. Existing or future
exports must enforce policy; a complete interchange feature remains separate.
The current schema milestone still needs its own review/integration/publication.
