# Slice 18: deferred scope and remaining cutover

Status: planned, documentation only, 18 October 2026. Source baseline:
`0b495f97eb9eaaadf58b42550b6281a2f02799b6` (slice 17 implementation).
Depends on implemented slices 01–17. No runtime changes, new APIs, deployment or
new capabilities are authorized by this document.

This is the single current inventory of deferred authority-substrate work and
one follow-on slice. The roadmap now contains **18 slices: 17 implemented for
their documented supported paths, and slice 18 planned**. Earlier slice documents
retain their historical findings and exclusions; this inventory supersedes their
remaining-work lists where later implementations have closed a gap.

The shared foundation and the selected preparation, search, recall and memory
write consumers work within their documented boundaries. They do not establish
complete migration of every existing consumer. A refused operation, a retained
record without an embedding and a deliberately unsupported product feature have
different consequences; they are distinguished below.

## Outcome and boundary

Complete the remaining mapping of **existing** consumers onto retained scoped
authority, operation-selected spending policy and fenced reads/publication, then
produce the missing cutover evidence. The work packages below belong to this
one slice; they are not additional numbered slices or a promise of one PR.

The optional decisions register is included so nothing disappears from the
proposal. Those entries are **not an instruction to build new features** and are
not prerequisites for the core migration. If closing a runtime gap requires a
new public capability, record the exact contract gap and revise the scope before
implementation. Keep the affected path refused or explicitly degraded meanwhile.

## Existing runtime gaps

These affect existing behavior and prevent a claim of complete consumer cutover.
Each package needs its own concrete operation binding; a common embedding client
does not provide one automatically.

| ID / item | Why deferred | Current behavior and impact if left undone | Smallest closure / remaining decision |
| --- | --- | --- | --- |
| R1 — Curator survey and existing promotion pipeline | Slices 16–17 admitted recall and same-home writes, not a project-to-Global curation operation. Its candidate reads, human release, source mutation and destination publication cross that boundary. | `Curator` still calls the unbound Global survey. Production `Archive` refuses that helper, and Curator stops the pass as unavailable rather than treating failure as no neighbours. Existing SYSTEM judge authorization does not fix survey admission. The existing proposal/approval pipeline is not a fully migrated curation path; automatic candidate review cannot complete through this route. | Bind the existing survey to a valid curation operation and its permitted inputs/Global reads. Map the existing human acceptance and source/destination effects, including write-time embedding and late revocation/commit checks. Preserve source MANAGER plus human Global ADMIN checks, proposal claiming/release and existing promotion semantics. A general grant/copy/transfer redesign remains D5 below. |
| R2 — Document ask/deliberation | Slice 15 deliberately excluded internal `within` retrieval: a standalone search allowance would give an ask job a second unrelated source. Ask also has several model stages, result/input obligations and its own lifecycle. | `Deliberation` still reaches `RetrievalService.within` without the admitted query origin required by the shared query path. That retrieval refuses when reached in production; the selected ordinary search operations being functional does not prove ask works. The full inference/authority chain still needs mapping. | Carry the existing ask operation, actual executor, read selection, validity and applicable spending policy through retrieval and all inference stages. Preserve one owning operation's accounting and disclosure/input fences. Do not mint an ambient search source inside the job or add a new ask API. |
| R3 — Independent embedding repair, explicit reembed and rebuild | Preparation leases and memory-write admissions authorize their own output versions. Neither is authority for an independent scan or maintenance publication. Slices 14–17 kept those purposes separate. | Unbound maintenance lacks a supported inference claim; `DispatchingDualEmbeddings.tick` also rejects borrowing a bound consumer permit. `Archive.reembed` retains per-memory success/failure reporting, but attribution alone cannot repair vectors. Stored content can remain unsearchable after an indexing failure, and replacement-profile coverage/activation can remain incomplete. Successful newly admitted writes/preparation are unaffected. | Define a retained maintenance source with current content/profile/generation fences, bounded target selection and operation validity. Reuse existing pending/retry/publication/activation ownership. Choose the applicable spending policy explicitly, unlimited by default; do not borrow a preparation lease or write/query permit. Prove repair and activation across the existing stores before claiming rebuild support. |
| R4 — Digest and deeper navigation embeddings | Slices 16–17 covered recall/Reminder and write/Scribe consumers, not the digest tree's query, summary-vector and maintenance paths. Digest chat already has SYSTEM admission; it still needs an end-to-end binding review. | `Navigator` attempts an unbound query capture and falls back to bounded tree navigation when embedding is unavailable. `Digester` can retain a digest without a vector; dual vectors are left to repair. Structural navigation and existing digest chat are not wholly absent, but semantic seeding/coverage is degraded, and repair remains dependent on R3. | Bind existing navigation query and digest-vector work to their actual operation/source, selected policy, live inputs and output versions. Preserve structural fallback and accurate coverage reporting. Audit existing digest chat validity and parent sandbox alongside embeddings; do not infer authority from its usage attribution or empty validity callback. |
| R5 — Nested/model-triggered and caller-conversation orchestration starts | Existing nested starts create fresh independent allowances, which do not satisfy retained parent lineage. Caller-conversation launch shapes do not yet distinguish the required budget relationship. This was explicitly fenced in slice 11. | Those starts refuse before conversation creation, including affected bound/slash launch shapes. Independent user/event/Relay roots continue to work. Increasing an allowance or selecting SYSTEM does not repair the missing relationship. | Decide how the existing child uses the parent's applicable spending policy/source, and how launch shapes identify that relationship. Preserve parent authority, cancellation, original start keys and receipts. Unlimited policy must still retain lineage; finite sharing needs no duplicated allowance. Do not introduce subdivision/reservation APIs just to bypass the refusal. |
| R6 — Non-conversation child continuation | A synchronous child can share its parent's live Budget object, but that object is not a durable non-conversation recovery lease. Slice 11 did not add a resumable closure runtime. | Such a child cannot resume under a replacement lease/object after approval or restart. Original receipts remain inspectable; ordinary supported conversation-backed continuations are a separate path. Interrupted work in this shape remains unavailable rather than silently receiving a new source or replaying effects. | Determine whether the existing continuation can retain and validate its original source/lease using current lifecycle storage. If a new recovery capability is required, report it separately instead of silently creating one. Keep unsupported resume refused and evidence read-only until that contract exists. |
| R7 — Broader retained read targets and explicit Global semantic selection | The ordinary retained operation envelope admits only its exact execution home. GLOBAL_READ is resource-read eligibility, not permission to enlarge a sandbox. Slice 14 added only bounded Global document destination/read routes; slice 15 did not add account-target semantic queries. | Explicit Global/cross-project model-tool references outside that home refuse. Retained recall/Reminder exclude implicit Global tiers from results, ranking, counts and telemetry. Standalone authorized tier reads still work. `BoundQueryEmbeddings` rejects account selections; Global-held documents may be discoverable through supported Shared/includeShared reads, but direct account-target semantic search is unsupported. A librarian running inside a narrow application sandbox therefore cannot automatically read every globally eligible resource. | Decide whether the supported operation actually needs additional read targets and how current contracts retain them without adding WRITE/execution authority. Keep token/acting/tool/input restrictions intersected. Identify any missing public selector separately; do not widen all sandboxes or add account-query APIs implicitly. This is a decision-dependent extension of the existing target contract, not a reason to weaken the current fence. |

### Current source owners

| Gap | Source and existing scope evidence |
| --- | --- |
| R1 | [Curator](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java), [Archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java), [PromotionQueue](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PromotionQueue.java); [slice 17 exclusions](17-memory-writes-and-scribe.md). Promotion can commit content before indexing; a missing vector must not be reported as a rolled-back promotion or cause a replay. |
| R2 | [Deliberation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/Deliberation.java), [RetrievalService](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/RetrievalService.java); [slice 15 consumer boundary](15-semantic-query-budgets-and-read-authority.md#initial-consumer-boundary). Preparation's already admitted Summariser is a separate consumer. |
| R3 | [DispatchingDualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DispatchingDualEmbeddings.java), [Archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java), [EmbeddingWorkRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/EmbeddingWorkRepository.java); [slice 14 completion](14-completion-plan.md). |
| R4 | [Navigator](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Navigator.java), [Digester](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digester.java), [DigestModel](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/DigestModel.java), [PassageIndex](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PassageIndex.java); [slice 16](16-memory-query-embedding-and-recall.md). |
| R5–R6 | [Orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java), [CallerOrchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/CallerOrchestrations.java), [OperationExecution](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OperationExecution.java); [slice 11 retained gaps](11-orchestration-and-agent-lineage.md). |
| R7 | [PinnedOperationAuthority.requireTargets](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java), [BoundQueryEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundQueryEmbeddings.java); [slice 13 selectors](13-existing-contract-and-client-cutover.md), [slice 15](15-semantic-query-budgets-and-read-authority.md), [slice 16 sandbox rules](16-memory-query-embedding-and-recall.md). |

These are source findings at the stated baseline, not live deployment evidence or
an exhaustive claim that every optional route has been exercised. Revalidate the
actual production composition before changing each consumer.

## Migration and acceptance gaps

These are different from adding consumer bindings. Existing rehearsals remain
useful evidence, but do not prove a real deployment's records or rollback path.

| ID / item | Why deferred | Impact if left undone | Closure required |
| --- | --- | --- | --- |
| M1 — Legacy reader/writer and locator removal | The migration deliberately retained legacy locators/adapters while explicit scopes and audience/operation evidence were introduced. Removing them before all writers/readers are proven would invalidate compatibility. | Mixed representations remain, and the platform cannot claim removal of NULL-as-Global compatibility storage. A NULL historical conversation still grants no Global execution authority. Removing adapters prematurely risks unreadable data, incorrect classification or bypasses. | Inventory remaining readers/writers against current code. Prove stable IDs, current-root audience filtering, late revocation/commit behavior and rejection of old authority-free writers before removing each adapter. Use new migrations only; preserve historical evidence. |
| M2 — Real-data classification and manual/deployment adoption | Slice 14 proved a synthetic migration/repository-reopen path and later enabled explicit Global document admission. It did not authorize adoption of live data or convert the manual installer into a custody-transfer tool. Deployment directories contain mixed-trust definitions and private exports. | Existing Personal/Project or legacy Shared content does not become Global-owned or service-readable automatically. Quarantined/unclassified resources remain restricted. A deployment may still have its manual under Personal custody; location/name/Shared flags do not establish Global custody. | Review actual records and deployment files, preserve provenance/IDs/bytes, and choose deliberate publication versus explicit Global admission through supported operations. Keep human-only legacy Shared until deliberate manager publication. No blanket SQL transfer, republishing, inferred creator or directory-based SYSTEM assignment. |
| M3 — Deployment restart, restore and rollback acceptance | Repository reconstruction and synthetic fixtures are not a real process restart, restored production backup or mixed-version rollout. Earlier work explicitly did not establish old-binary compatibility. | Source tests cannot establish safe deployment adoption/recovery. A pre-substrate binary can omit required evidence and its writes may be rejected; rolling it back is not a supported recovery claim. | Under separate deployment authorization, rehearse backup/restore and compatible forward recovery, real process restart, retained receipts, authenticated supported reads/writes and unchanged private deployment files. Define a compatible binary/schema recovery target. Do not infer a reverse migration or replay uncertain work. |

The [slice 14 cutover specification](14-cutover-specification.md) owns the original
classification and compatibility rules; the
[completion record](14-completion-plan.md) supersedes its historical missing
Global-destination/preparation-embedding boundary. Neither selected query budgets
nor Global admission remain blanket missing features after slices 14–17.
An intentionally retained compatibility locator is not itself a permission gap:
prove that explicit scope/authority governs it. M1 does not require rewriting
historical rows merely to eliminate every physical NULL.

## Optional capabilities and unresolved product decisions

These can remain deferred without preventing the **supported** substrate from
working. Their absence must remain visible to callers and operators. They are
included in this single inventory, not silently added to slice 18 implementation.

| ID / item | Why deferred | Impact if not done / decision still needed |
| --- | --- | --- |
| D1 — First-class tenant rollout or tenant-specific Global | The agreed Global audience is deployment-wide; no new tenant model was scoped. Existing account/project isolation is distinct. | Every authenticated principal can read deliberately GLOBAL_READ resources, across tenant groupings on the same deployment. That is intentional, so this audience cannot carry tenant-confidential content. Restricted audiences use existing scoped permissions. A tenant-specific Global would require a different product decision; project names cannot stand in for tenants. |
| D2 — Omitted-target convenience for generic multi-project Applications | Current defaults are safe only where the admitted context is unambiguous. Choosing a token's first project invents authority/routing. | Such clients must supply an explicit supported target; ambiguous omission refuses. Human Personal and unambiguous Application defaults still work. Decide a trusted selection contract only if the convenience is wanted. |
| D3 — Search aggregation/presentation redesign | Permission migration retained existing result contracts rather than redesigning paging, ranking, coverage and aggregation. | Existing duplicate/current-revision, reference-only-hit and project-plus-general presentation constraints remain. Authorization and hidden counts still need to be correct; redesign is not a substitute for those checks. Decide result semantics before changing public contracts. |
| D4 — Global document-type/schema authoring | Existing type management is Personal/Project-scoped; Global custody did not imply a new registry. | Admins cannot assume a Global schema/type authoring surface exists. Supported document admission remains bounded by existing type support. A registry and its lifecycle/API need separate scope if required. |
| D5 — General curation grant versus copy versus home/control transfer | Those outcomes have different IDs, supersession, provenance, approval and audience consequences. Migrating the existing promotion pipeline does not settle a general publishing product. | Existing supported sharing and promotion keep their own semantics; a user cannot assume every form of “publish” transfers custody or grants GLOBAL_READ. Decide the intended outcome before extending curation. Resource MANAGER audience authority and human ADMIN control of Global remain settled. |
| D6 — General project acting runtime and wider acting operations/targets | Slice 12 deliberately retained the existing Personal acting flow and five-operation whitelist. More authority on the subject is insufficient without the actor's connection-bound proof and operation/target ceiling. | A service can use current supported on-behalf-of-user operations, but arbitrary project work, background/admin/tool operations or newly selected Global sources remain unsupported under acting. Direct supported service reads with GLOBAL_READ are a different path. Expansion needs its own consent, manifest, target, lifecycle and revocation contract. |
| D7 — Public SYSTEM-work continuation | SYSTEM is reserved and nonassignable. Internal bounded processing does not require a public SYSTEM login or public Global conversation create/resume surface. | Ordinary clients cannot take over SYSTEM work or run conversations in Global. Internal valid processing continues under its own authority. No change is needed unless a distinct operator-facing continuation capability is explicitly requested. |
| D8 — SDK/client feature parity | Permissions depend on principal, role and scope, not client labels. The proposal never required every SDK to expose every CLI/TUI/GUI capability. | Each client exposes its existing supported subset. Human ADMIN can manage Global through supported existing APIs, but this does not promise a matching method in every SDK. Inventory a concrete missing method before proposing any extension. |
| D9 — New generic operation controls or restartable closure runtime | WRITE and existing lifecycle rules govern controls that actually exist. No EXECUTE_JOB or CONTROL_EXECUTION permission, generic pause/resume/cancel service or operation ACL registry was requested. | Missing controls stay unavailable; WRITE cannot manufacture a lifecycle capability. R6 remains visibly refused where existing recovery cannot support it. Scope any new lifecycle capability separately instead of describing it as a permission fix. |
| D10 — Arbitrary ACL denies, nested groups and ACL management UI | The substrate permits scoped resource refinements, but routine management should use standard RBAC helpers. A new ACL engine/editor was never required for this migration. | ADMIN/MANAGER/CONTRIBUTOR/READER remain the normal path, with supported finer controls only. Arbitrary deny/group expressions and new editors cannot be assumed. GLOBAL_READ still has no individual exclusions; use a restricted scope/audience instead. |
| D11 — Detailed spending pools, quotas and reservation/settlement mechanics | Operation sponsorship and policy applicability are settled; a general pool allocation/settlement system was kept separate under [ADR 0025](../decisions/0025-service-account-attribution-and-spending-pools.md). | Existing configured operation policies work without a new shared quota/pool product. There is no implied general cross-operation reservation, allocation or settlement guarantee. A required finite parent/child relationship in R5 still needs a correct supported contract; this deferral cannot justify duplicated allowances. |
| D12 — New billing/policy administration UI | Existing configuration and operation accounting do not imply a new management interface. | Operators use supported configuration and inspection paths. Missing UI convenience does not disable unlimited/limited/disabled policies or authorize a new API. Scope it only if requested. |

## Reuse and fixed architecture

| Reuse | Boundary to preserve |
| --- | --- |
| Scoped action admission, standard role helpers and current-root knowledge policy | RBAC is the routine management surface; ACL refinements do not require users to assemble ordinary roles manually. Created-by is provenance, not a mutation gate. GLOBAL_READ grants authenticated read eligibility, never custody, WRITE or execution. |
| SYSTEM operations and shared semantic embedding foundation | SYSTEM has intrinsic authority, bounded by the operation's inputs, tools, targets and validity. No ID-based bypass, assignable SYSTEM role, creator-access dependency or second model runtime. |
| Retained operation sources, claims and accounting | Spending defaults to unlimited. Disabled/limited spending applies only when the operation's selected policy says so. Unlimited still retains source identity, authority, claims, cancellation and accounting; a nearby Budget is not automatically applicable policy. |
| Existing read/disclosure and publication fences | Separate permission to request work, SYSTEM inference, result disclosure and content/vector publication. Preserve source/version/profile/generation and private-input obligations through late checks. |
| Existing schedules, Relay, acting proofs and orchestration receipts | Keep their distinct ownership and recovery contracts. Independent Relay roots are already supported. Unknown delivery remains reconciliation-only, never an automatic restart or mutation replay. Tool grants/provider capabilities remain additional bounds outside account RBAC. |

## Work order and verification

Within this one slice, first revalidate the production call graph and bind the
independent existing consumers (R1–R4), sharing the existing foundation rather
than adding another permission system. R4 publication depends on the relevant
R3 repair/coverage contract. Resolve parent-source/recovery decisions before
enabling R5–R6. R7 requires an explicit target decision; it must not become an
automatic sandbox expansion. M1 cleanup follows proven consumer/writer coverage;
M2–M3 require review of actual deployment state and separate deployment authority.
D1–D12 may stay deferred. Any required new contract must be made reviewable before
implementation, not invented while activating a consumer.

For each implemented package:

1. Exercise its actual production composition and supported entry point. Check
   both success and the currently refused/degraded case; a fallback or stored
   record is not proof that inference/indexing ran.
2. Verify account/role/scope admission, exact operation inputs/targets and current
   authority before dispatch, disclosure and commit. Include creator access loss
   for SYSTEM work, operation invalidation, cancellation, root audience change,
   unsupported targets and private input/log protection.
3. Verify unlimited default plus explicitly applicable disabled/limited policy,
   exact retained source/claim identity and no duplicate submission. Nearby finite
   budgets must not accidentally cap an unrelated operation. Test retries,
   interrupted work and partial publication without replaying uncertain effects.
4. Preserve existing public payloads, supported selectors, result/error shapes,
   proposal receipts and fallback/coverage behavior. Run the smallest relevant
   mocked service/runtime tests first. Use explicit focused full-DB tests only
   where new persistence, locking, constraints, migration or rollback assertions
   actually require PostgreSQL; state the reason before running them.
5. For code changes, run required formatting and affected checks. Record the
   concrete activated consumers and unresolved rows here and in the impact report.
   Claim full cutover only after all affected existing consumers and migration
   gates have evidence; intentionally unsupported optional features can remain
   unsupported. Local tests never constitute deployment acceptance.

This planning change runs documentation/link and whitespace checks only. It adds
no runtime verification evidence and authorizes no execution of slice 18.
