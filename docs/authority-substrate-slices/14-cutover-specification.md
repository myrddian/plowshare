# Legacy cutover specification

Recorded before slice 14 enforcement changes, 18 October 2026. This document
retains the implemented subset's classification and compatibility boundary. The
subsequently agreed [completion plan](14-completion-plan.md) explicitly plans a
Global destination and document embedding on the existing processing budget. Its
implementation is not authorized; the original refusals below remain current.

## Activation boundary

The final cutover is blocked. Existing personal/project/shared selectors cannot
admit a source into the Global account. `scripts/install-manual.mjs` uploads to
the authenticated account's Personal knowledge home and can publish its audience;
that does not transfer its home to Global. No SQL adoption, Global login, new
selector or fabricated SDK operation will substitute for the missing route.
Embedding/search operation allowances and nested orchestration parent-budget
allocation remain separately documented gaps. Keep their existing refusals.

This implementation completes the feasible classification, retained-state
rehearsal and digest attribution correction. It must not claim the roadmap's
end-to-end Global outcome, remove compatibility readers/writers, or activate a
deployment. The implemented subset and outstanding acceptance gates are distinct.

## Classification and compatibility

| Existing meaning | Classification and action |
| --- | --- |
| `memories.project_id IS NULL` | Documented archive tier, already mapped by V154 to fixed Global knowledge scope `00000000-0000-0000-0000-000000000005`. Retain the legacy locator, memory IDs, formation evidence, tombstones and provenance links. |
| Ordinary project named `global` | Ordinary project with its own scope UUID; never the Global account. |
| Personal projects and account knowledge | Separate scopes. Source admission evidence supplies `created_by`; legacy custodian text cannot fabricate authorship. |
| Information resource without a classified home | Preserve null home and quarantine. Neither its bytes, Shared flag nor manual name establishes Global custody. |
| Consistent historical Shared revisions | Preserve human-only `LEGACY_SHARED`; current manager publication explicitly changes the root to `GLOBAL_READ`, including service readers. |
| Mixed Shared/private flags | V155 narrows to HOME with review or QUARANTINED. Never union historical audiences. Root policy applies to all revisions, with mandatory input/privacy ceilings. |
| Historical null jobs/conversations/logs | Preserve IDs, owner/provenance, spending, outcomes and receipts. Null does not establish Global execution authority. Do not reconstruct missing admissions or replay effects. |
| New project digest job | Persist the already selected project through `JobStore.submit(name, home, work)`. The previous closure-only selection loses attribution after restart. Global-tier jobs retain null compatibility storage. No new recovery/execution grant is introduced. |
| Deployment `global/` files | Mixed definition overrides, images and private exports; location grants no audience or SYSTEM authority. Retain layout/version and verify bytes across reinitialization. |
| SYSTEM processing | Existing intrinsic authority plus retained purpose, input/output validity and operation budget. Creator access loss is independent; withdrawal or lost SYSTEM authority still fences new effects. Private logs remain private. |

Use the existing scoped RBAC helpers for normal management. This cutover adds no
role, ACL editor, deny engine, API, inference allowance or automatic publication.

## Proof and rollback limits

Use a synthetic V151 database, migrate through the committed predecessor
migrations, and reopen repositories against retained state. Compare historical
rows and receipts rather than health alone; verify stable identities and current
SQL policy, then exercise existing share/unshare/read frame handlers over real
repositories. Include a service reader, rejected publication, creator access loss,
quarantine and changed audience. This proves project-held universal publication,
not admission into Global custody.

Selected existing tests additionally own real SYSTEM processing checkpoints,
immutable operation claims, creator-independent continuation and private logs.
Disk fixtures retain deployment definitions and private exports without renaming
or publishing them. Repository reconstruction is a restart simulation, not a
production process restart or live deployment proof.

Do not roll back to a pre-substrate binary: old writers omit retained authority
and some old audience writers are deliberately refused by database fences. The
digest attribution correction uses an already supported job column/overload; it
does not rewrite old jobs or add a schema version. Preserve all predecessor schema
and evidence. No reverse migration, backup restoration, old-binary compatibility
claim or live-data adoption is authorized or tested here.
