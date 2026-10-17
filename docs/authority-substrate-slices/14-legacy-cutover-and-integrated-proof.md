# Slice 14: Legacy classification, cutover and integrated migration proof

Status: bounded slice 14 implementation completed, 18 October 2026. The initial
classification/rehearsal subset and subsequent
[Global destination and preparation-budget completion](14-completion-plan.md)
are implemented. Deferred consumers and broader rollout remain fenced.

Part of the [14-slice roadmap](../authority-substrate-implementation-plan.md).
Policy comes from [ADR 0030](../decisions/0030-authority-substrate-and-explicit-scopes.md);
the [impact report](../authority-substrate-impact-report.md) separates verified
source evidence from target behavior. The shared roadmap records source baseline,
test policy and cross-slice activation rules.

## Outcome and dependencies

Complete the bounded Global document path with reviewed data classification,
compatible enforcement and integrated migration/recovery proof, preserving durable
identities and private data. Deferred consumers remain fenced; this outcome does
not claim unrestricted platform-wide cutover.

**Depends on:** [13 — Existing API, scope-selection and client contract cutover](13-existing-contract-and-client-cutover.md).

Dependency completion means its relevant interfaces, migrations and verification
are available; it does not grant authority to a caller or enable an unmigrated path.

## Changes

- Consolidate per-slice migration evidence and classify remaining null-Global database/job/log/disk meanings. Preserve restricted unknowns; deployment files and private SYSTEM logs never become public knowledge merely by directory/home.
- Review legacy Shared audience expansion, manual home/control adoption and old admin/role mappings. Apply new constraints/remove obsolete compatibility paths only after classification and supported-consumer proof.
- Audit existing memory recall/navigation, digest scopes and curation promotion for hidden broadening. Keep assessment/proposals; refuse unauthorized existing release/transfer without inventing a new curation mode or approval UI.
- Rehearse the supported Global ADMIN admission, bounded SYSTEM preparation, configured operation budget and ordinary authenticated reader path. Validate current root permissions, creator-independent continuation and private result/log boundaries.

## Reuse and likely source owners

These files identify current owning contracts, not a claim that they already
implement the proposed behavior. Keep services on typed interfaces and persistence
inside specialist repositories.

| Existing source | Reuse and boundary |
| --- | --- |
| [DataLayout.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/data/DataLayout.java) | Existing physical global directory contains mixed public/private/configuration data; no wholesale rename/publication. |
| [Archive.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | Current recall/curation paths need intentional selection and release authority. |
| [Digests.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java) | Existing durable-scope mismatch cannot be reconstructed from a vanished closure. |
| [Curator.java](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) | Existing proposal/settlement receipts remain authoritative. |
| [install-manual.mjs](../../scripts/install-manual.mjs) | Existing installer/public operation path; no SQL import or Global impersonation workaround. |

## Exclusions and capability boundaries

No production deployment by implication, token replacement campaign, broad disk rename, tenant rollout, new curation/type features, arbitrary ACL engine or new controls/SDK parity.

Standard scoped RBAC helpers remain the normal management path. Finer ACL needs
must use the same substrate; this slice does not silently add an ACL editor,
deny engine, SDK feature parity or an unsupported operation. Record a missing
capability as a gap before expanding implementation scope.

## Verification and completion

- Use synthetic legacy fixtures and a protected migration rehearsal to prove classification completeness, stable IDs, creator provenance, FK constraints, retained jobs/receipts and unchanged historical costs.
- Selected PostgreSQL tests prove schema/policy/cutover transactions. Rehearse restart and any changed disk layout against retained state; health alone is insufficient.
- Through verified existing public APIs, demonstrate Global admin write, SYSTEM operation bounded to inputs/outputs, continuation after creator access removal, and authenticated human/service read without private-source/log leakage.
- Remove/expire authority or invalidate the document/operation: new effects/disclosure refuse and uncertain effects are only reconciled. Do not demonstrate absent interfaces by building them.

Existing regression owners to inspect and extend, selecting methods/tags that
match the eventual change:

- [DataLayoutTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/data/DataLayoutTest.java)
- [DocumentPoliciesTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/DocumentPoliciesTest.java)
- [ServiceInformationProcessingTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/ServiceInformationProcessingTest.java)
- [AuthoredInformationPersistenceTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/information/AuthoredInformationPersistenceTest.java)
- [MemoryStoreTest.java](../../plowshare-server/src/test/java/io/aeyer/plowshare/server/archive/MemoryStoreTest.java)

**PostgreSQL justification:** Required for final migration/constraint and real policy SQL proof. Select the relevant tagged tests and a controlled fixture/rehearsal; whole-suite DB or live deployment needs its own breadth justification/authorization.

These are acceptance requirements, with performed checks recorded below. Add mocked
boundary/authorization tests for new behavior; existing suite links do not imply
every test is non-database. Follow the shared roadmap's focused-test, formatting
and check requirements. Completion requires the outcome and negative/recovery
cases above, with unsupported paths still explicit and no uncertain-effect replay.

## Specification required before implementation

All predecessor slices need evidence for their affected paths; no final rehearsal substitutes for their authorization tests. Unresolved classification/unsupported critical routes block cutover instead of receiving permissive defaults.

## Rollout and rollback

Specify tested reader/writer compatibility and safe refusal before removing adapters. Preserve new grants, receipts, policy/provenance and costs; never restore a binary/state combination that broadens access. This plan does not authorize backup restoration or deployment.


## Implementation record, 18 October 2026

This section records the initial subset. The [cutover specification](14-cutover-specification.md)
was recorded before changing enforcement. At that initial subset, final cutover was **incomplete**: no existing upload selector
admits a document into Global account custody. The manual installer can publish
Personal-held chapters through existing share operations, but this is not Global
home adoption. No missing operation, SDK method, Global impersonation, permissive
default or production migration was introduced. No new Flyway version or disk
layout was needed for the implemented subset; predecessor migrations remain
append-only and compatibility locators remain in place.

### Implemented changes and consolidated classification

- Digest jobs now use the existing `submit(name, home, work)` overload. The old
  call retained the project only inside its worker closure; the mismatch is
  present in the earliest available snapshot, `cd7f68a`. New project jobs retain
  their actual home; old null jobs are preserved without guessing authority.
- DataLayout's contract now distinguishes physical `global/` deployment files
  from Global account custody, public audience and SYSTEM execution. A retained
  tree test proves definition, private export, project export and marker bytes
  survive reinitialization without a rename or new account directory.
- A synthetic V151-to-current migration rehearsal retains historical jobs,
  conversations, source bytes, request receipts, formation evidence and monetary
  accounting rows. It verifies fixed built-ins, an ordinary project named
  `global`, restricted orphan scopes, creator evidence, explicit memory home,
  tombstones, root policies and database refusal of invalid home/provenance/history
  rewrites. Reopening repositories and rerunning Flyway preserve the evidence.
- The same rehearsal exercises existing authenticated-context frame handlers with
  real repositories and policy SQL: a contributor cannot publish; a manager can
  publish/unpublish all revisions; a service principal reads only the explicitly
  published audience; recovered mutation receipts do not repeat the transition.
  Creator membership loss does not rewrite custody or recover control. A malformed
  Global upload selector refuses before creating any resource, including for ADMIN.
  This is in-process public-handler coverage, not a live SDK/session acceptance run.

The classification preserves unknown manual/legacy homes as quarantined, consistent
historical Shared as human-only LEGACY_SHARED, mixed audiences as HOME with review,
and private roots under their retained privacy ceiling. No historical Shared flag
or deployment path automatically becomes GLOBAL_READ. Existing raw corpus rows
without logical roots retain their human-only legacy policy; no home adoption or
universal service publication is inferred.

### Reuse and audit findings

V152–V159 already own identity/grant mappings, provenance, root audience history,
operation claims, SYSTEM purpose evidence and child lineage. They were exercised
rather than replaced by another migration registry. Normal management still uses
scoped RBAC helpers; no ACL editor or new permission is added.

Memory recall and digest source safety reuse `information_memory_safe`,
`information_digest_safe` and production `protectInformation` wiring. Private
input logs cannot reappear through archive search, digest roots or source spans.
The selected policy tests also verify private-input inheritance and the existing
migration release audit. These safety predicates deliberately narrow archive
availability; they do not grant a generic cross-project information selection.

Curation keeps model proposals, explicit source MANAGER release plus human Global
ADMIN destination checks, and the existing copy/retire receipt flow. A model's
provenance label cannot supply release authority. The scoped admission tests and
queue regressions cover refusals and settlement ordering; they do not constitute
new proof of concurrent grant revocation versus promotion commit. Do not remove
that path's compatibility checks or claim full cutover on this evidence.

### Verification record

- New `AuthorityCutoverRehearsalTest`: synthetic upgrade, real policy SQL, public
  handler refusals/publication, retained evidence and repository reconstruction.
- New `DurableJobsTest.digest_job_keeps_its_selected_home_after_the_worker_closure_is_gone`:
  real job persistence distinguishes project/ordinary `global`/Global-tier homes;
  reopened runtime has no old handle and does not repeat the worker.
- Eighteen selected PostgreSQL cases passed across ServiceInformationProcessing,
  KnowledgeScopePersistence, RootDocumentPolicyMigration, MemoryStore,
  AuthoredInformationPersistence, OperationRecordsPersistence and
  InferenceAdmissionPersistence. They cover creator-independent SYSTEM renewal,
  private logs, invalidated inputs/operations, immutable scopes/budgets, stale
  claims, concurrent single submission and known receipt recovery.
- Three selected DocumentPolicies PostgreSQL cases passed for restricted archive
  and digest sources, audited migration release, and inherited private log inputs.
- `./gradlew format formatCheck check` passed after the final Java/test changes: 5838 server tests, zero failures/errors/skips, plus the root client,
  architecture, Javadoc and contract checks. Documentation links and diff whitespace
  passed. SDK protocols/catalogues were unchanged; no regeneration was needed.

PostgreSQL was required for migration/constraint/policy SQL, retained accounting,
job reconstruction and claim concurrency. Only the named tagged tests ran with
`-PfullDb`; no full database suite, dockerSmoke, production data, tokens, model
provider, SDK network acceptance or deployment was exercised. Repository/tree
reconstruction is a retained-state simulation, not a live process rollout.

### Remaining cutover and rollback gates

These gates record the implementation state at `007c832`. The completion plan
below records the subsequently implemented follow-on scope. The first and third
gates below are historical; deferred budgets and rollout gates remain current.

1. A separately authorized existing-contract decision is needed for Global-owned
   document admission/manual adoption. The current API cannot express it. This
   slice does not add that capability or silently rehome a published manual.
2. Semantic embedding/search operation budgets, parent-conversation orchestration
   budget allocation and restart of transient child budgets retain their documented
   refusals. No fresh allowance is substituted for retained work.
3. Full Global ADMIN admission → bounded SYSTEM preparation → configured inference
   allowance → human/service read acceptance cannot be claimed without the first
   gate and its negative/recovery proof. Exact consumer and revocation/commit proof
   remains required before removing any compatibility adapter.
4. No live tenant inventory, legacy manual custody decision, old-binary rollout or
   production process restart was performed. Do not deploy a pre-substrate binary
   onto this schema: old writers can omit required evidence or meet intentional
   write fences. Preserve grants, receipts, policies, creator evidence and costs;
   no reverse migration or restore is authorized by this implementation.


## Completion implementation, 18 October 2026

The [completion record](14-completion-plan.md) implements two changes:

1. An explicit Global account-knowledge destination on existing information
   operations, with scoped human ADMIN WRITE, separate actor/creator provenance,
   transactional Java/SQL enforcement and destination-bound installer receipts.
2. Document chunk and summary embedding authorization and claims against the
   existing retained processing allowance, including both applicable embedding
   implementations, stable source/claim identity and generation/lease/output fences.

The destination extends the existing public operation contract without adding an
endpoint. Shared remains a read selection. No existing
Personal/manual resources are silently moved or reattributed. Model-call limits
cover all preparation inference, so the same total may exhaust earlier.

Semantic-query embedding, independent background repair/rebuild, nested or
caller-conversation orchestration budgets, and transient-child budget recovery
remain deferred with their current refusals. They are not prerequisites for the
bounded Global document acceptance path. This does not remove curation,
compatibility-removal or broader rollout gates or certify those consumers.

The completion record covers Global ADMIN admission → bounded SYSTEM preparation using
its retained processing budget → human/service read/list, including creator access
loss, rejected callers, exhaustion, invalidation and retained-state recovery without
uncertain replay or private-log leakage. Prior test results above do not verify
this behavior; the completion record supplies the new verification separately.
No production deployment or data adoption was performed.
