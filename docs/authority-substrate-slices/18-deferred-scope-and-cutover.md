# Slice 18: deferred scope and remaining cutover

Status: existing-runtime implementation and local migration verification,
18 October 2026. Planning source baseline:
`0b495f97eb9eaaadf58b42550b6281a2f02799b6` (slice 17 implementation).
Depends on implemented slices 01–17. Existing runtime and local migration gaps
are authorized; no new public APIs/capabilities or live deployment are included.

This is the single current inventory of deferred authority-substrate work and
one follow-on slice. The roadmap contains **18 implemented slices for their
documented supported paths**; live deployment acceptance remains separate. Earlier slice documents
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

The compatibility/extension register distinguishes required existing behavior
from **new features that remain deferred**. Existing SDK/client adoption and
working agreed behavior are migration requirements. If closing a runtime gap requires a
new public capability, record the exact contract gap and revise the scope before
implementation. Keep the affected path refused or explicitly degraded meanwhile.

## Original runtime gap register

The following table records the source baseline before this implementation.
The closure record below supersedes its present-tense failure descriptions.
Each consumer needed its own binding; a common embedding client was insufficient.

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

## Required compatibility versus optional extensions

Reclassified under the implementation instruction, 18 October 2026. The original
D1–D12 labels mixed required behavior with possible extensions. Calling the
entire rows optional was too broad. Existing supported capabilities, agreed
permission semantics and affected client/SDK contracts must survive this migration.
Only the extension in the final column may remain deferred. This does not require
SDK feature parity or authorize new public operations.

| Item | Required in this slice / verification boundary | Extension still deferred and impact of not building it |
| --- | --- | --- |
| D1 — Tenant isolation | Preserve account/project isolation and deliberate deployment-wide authenticated GLOBAL_READ. Test private content and metadata isolation. | A new tenant model or tenant-specific Global. Deployment Global cannot hold tenant-confidential content; use existing restricted audiences. |
| D2 — Application defaults | Preserve human Personal, unambiguous Application self-routing and explicit targets. Refuse ambiguous/malformed targets without a first-project fallback. | Omitted-target convenience across several possible projects; those callers must select explicitly. |
| D3 — Search | Correct current-root visibility, result/count/paging privacy and existing project-plus-general discovery are required. Connect existing semantic consumers rather than masking failures as empty results. | New ranking/aggregation/presentation contracts, deduplication redesign or new UI. Existing result shapes remain. |
| D4 — Document types | Existing supported authored-document/type workflows must still work in their admitted homes. Global document custody must not break supported untyped/built-in-type admission/read workflows. | A new Global custom-type registry/authoring surface. Existing Personal/Project registries do not become a Global registry implicitly. |
| D5 — Curation | Restore the existing survey/promotion/approval pipeline with live source MANAGER and human Global ADMIN checks, provenance and safe publication (R1). Existing sharing changes audience without moving custody. | Generalizing publishing into a new grant/copy/transfer product. Existing share and promotion retain their defined outcomes. |
| D6 — Acting delegation | Preserve each existing on-behalf-of-user transport operation, identity/proof intersection, target ceiling, revocation and receipt behavior. Explicitly test the supported Personal flow. | New acting operations, general project execution or consent/client features. Unsupported targets remain refused; direct authorized service GLOBAL_READ is separate. |
| D7 — SYSTEM processing | Existing internal SYSTEM services must work independently of creator access while source/operation validity and sandbox fences hold. | Public SYSTEM login/takeover or public Global conversations. These remain unavailable by design. |
| D8 — SDK and clients | Preserve and verify the existing supported SDK/client workflows and changed scope contracts, including human ADMIN Global management and authenticated Global reads. Fix affected serializers, selectors or decoders when necessary; do not classify a broken existing workflow as optional. Record evidence per affected surface instead of assuming every SDK has a method. | Adding functionality a client/SDK never exposed or requiring universal feature parity. A genuinely absent method remains a separately scoped capability gap. |
| D9 — Lifecycle controls | Preserve existing job/orchestration/schedule controls under WRITE plus their lifecycle rules, and original recovery/receipt semantics (R5–R6). | New generic pause/resume/cancel operations, operation ACL registry or restartable closure runtime. WRITE grants no missing lifecycle capability. |
| D10 — RBAC and ACL | Preserve sensible scoped ADMIN/MANAGER/CONTRIBUTOR/READER helpers and existing resource/audience controls. GLOBAL_READ has no individual exclusions. | New deny engine, nested groups or ACL editor. Routine administration must not require assembling custom ACLs. |
| D11 — Spending | Every activated operation retains authority/accounting and a selected spending policy: unlimited by default, disabled/limited only where explicitly applicable. Synchronous delegates share the applicable allowance; independent orchestration runs select their own. | General shared pools, monetary quotas, allocation/reservation/settlement product under [ADR 0025](../decisions/0025-service-account-attribution-and-spending-pools.md). Existing operation policies must work without that product. |
| D12 — Administration | Existing policy configuration, inspection and human ADMIN management through supported APIs remain usable and documented. | New billing/policy UI. Operators continue using existing configuration and inspection paths. |

The required column is part of existing runtime/migration closure, not a new
capability list. M2–M3 still distinguish local migration proof from separately
authorized live deployment work. Missing existing behavior must be reported as
unfinished, even when a safe refusal prevents an authority bypass.

## Reuse and fixed architecture

| Reuse | Boundary to preserve |
| --- | --- |
| Scoped action admission, standard role helpers and current-root knowledge policy | RBAC is the routine management surface; ACL refinements do not require users to assemble ordinary roles manually. Created-by is provenance, not a mutation gate. GLOBAL_READ grants authenticated read eligibility, never custody, WRITE or execution. |
| SYSTEM operations and shared semantic embedding foundation | SYSTEM has intrinsic authority, bounded by the operation's inputs, tools, targets and validity. No ID-based bypass, assignable SYSTEM role, creator-access dependency or second model runtime. |
| Retained operation sources, claims and accounting | Spending defaults to unlimited. Disabled/limited spending applies only when the operation's selected policy says so. Unlimited still retains source identity, authority, claims, cancellation and accounting; a nearby Budget is not automatically applicable policy. |
| Existing read/disclosure and publication fences | Separate permission to request work, SYSTEM inference, result disclosure and content/vector publication. Preserve source/version/profile/generation and private-input obligations through late checks. |
| Existing schedules, Relay, acting proofs and orchestration receipts | Keep their distinct ownership and recovery contracts. Independent Relay roots are already supported. Unknown delivery remains reconciliation-only, never an automatic restart or mutation replay. Tool grants/provider capabilities remain additional bounds outside account RBAC. |

## Work order and verification

The original implementation order was to first revalidate the production call graph and bind the
independent existing consumers (R1–R4), sharing the existing foundation rather
than adding another permission system. R4 publication depends on the relevant
R3 repair/coverage contract. Resolve parent-source/recovery decisions before
enabling R5–R6. R7 required an explicit target decision, now recorded below;
it was not an automatic sandbox expansion. M1 cleanup follows proven consumer/writer coverage;
M2–M3 require review of actual deployment state and separate deployment authority.
Only the extension column of D1–D12 may stay deferred; required compatibility must be verified. Any required new contract must be made reviewable before
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

Implementation evidence and remaining limits are recorded below as verified.
Planning checks alone are not runtime verification evidence.

## Implementation closure, 18 October 2026

The authorized source changes close R1–R7 for existing supported workflows;
they do not introduce public operations or deploy/adopt live data. The original
register above remains the explanation of why each path was deferred.

| Package | Implemented behavior and retained limits |
| --- | --- |
| R1 | Curator's exact candidate query uses a SYSTEM operation and the selected whole-pass policy. Candidate/neighbour snapshots, cancellation and live source checks bound the judge. Existing human acceptance still requires source MANAGER plus Global ADMIN; copy-and-retire/proposal receipts are unchanged. Promotion indexes the committed destination through independent repair authority, with failed indexing reported without undoing committed content or replaying promotion. |
| R2 | Document ask's internal query and model stages share the ask operation's selected policy. They retain exact document/hierarchy/input context, source/disclosure checks, cancellation and SYSTEM purpose admission. An unrelated conversation budget is not the ask policy. Small finite/disabled policies are valid choices; no minimum-size boot refusal. |
| R3 | Existing explicit reembed and legacy/dual background repair use exact committed source versions, live profile/generation and publication fences. Maintenance selects its own policy. Automatic queue claims persist an in-flight marker before provider dispatch; uncertain outcomes stay held across repository reconstruction and are not retried automatically. A changed source or encoder is a new operation. Existing explicit operator repair remains deliberate work, not an automatic replay. |
| R4 | Digest/navigation model and embedding calls use the same selected operation source. Digest vectors publish against the committed digest version. Navigation retains structural fallback/coverage reporting, with current source checks. Conversation semantic search was another affected existing consumer found during implementation; its query now uses the existing query admission/disclosure lease. |
| R5 | Caller-backed slash/model/nested orchestration starts derive exact parent authority. The original slice 18 implementation incorrectly shared the caller's allowance. The [19 October correction](../standalone-allowance-and-recovery-plan.md#orchestration-contract-correction-19-october-2026) restores each new orchestration's own selected allowance and lifecycle; `agent_run` shares its caller's source. Immutable allowance IDs, atomic claims and existing receipt/recovery rules remain; uncertain starts are not replayed. |
| R6 | Existing board wake continuations retain the original board-pot source, with fresh individually charged leases. Retained admission/source checks accept another lease from that same pot and reject replacement pots. No general restartable-closure facility was added. |
| R7 | Explicitly settled by the user: newly admitted ordinary runs capture read-only Global knowledge eligibility alongside their home. Descendants can only retain that captured ceiling; historical admissions default false. Recall, Reminder, explicit memory reads and navigation enforce it alongside live account, tool, token and acting bounds. Other projects, Global writes/execution and historical NULL-home conversations remain outside it. Global account semantic search/rank use the existing account READ gate; the TypeScript command validator accepts those same existing operations. |

### M1: retained compatibility inventory

Physical legacy locators are retained where existing stores require them. They
are not authority and are not removed just to eliminate NULL values:

- `ProjectIds`, `MemoryStore`, `DigestStore`, `PassageRepository` and historical
  conversation/proposal rows retain project-ID/NULL storage. Memory knowledge
  resolves to the fixed Global knowledge scope; an unknown project resolves to
  an empty selector, never Global. NULL conversation history does not admit work.
- `JdbcIdentityRepository` owns fixed UUID mappings. Existing identity/scope and
  policy migrations classify resources conservatively. `InformationAccess`,
  account knowledge permissions and current-root policies govern public reads;
  a directory, creator, old Shared bit or record location is not a grant.
- `ConversationStore`, retained admissions, SYSTEM purpose records and new
  allowance identities govern writes/inference. Old authority-free execution
  cannot acquire fresh permissions by being reopened. Historical inference
  receipts, provenance and payloads are preserved; new columns add evidence.
- New Flyway migrations V168–V171 add closed internal purposes, retained shared
  conductor constraints, immutable Global-read ceilings and durable allowance
  identities. They neither fabricate prior admissions nor replay effects.

M2 (reviewed live-data/manual adoption) and M3 (deployment restart/backup/restore
and compatible forward recovery) remain **required deployment acceptance** under
separate deployment authorization. They are not optional features and have not
been performed by this source implementation. No blanket live SQL transfer,
old-binary rollback guarantee or production verification is implied.

### Policy and client compatibility

Ask, Curator, navigation, digest and independent embedding maintenance default to
`unlimited`. Their existing configuration keys accept `unlimited`, `0` (disabled)
or a positive finite limit; an explicitly supplied request cap remains an
explicit finite selection. Each operation pays its own selected policy. Digest
repair shares that digest pass's allowance; standalone repairs use maintenance
policy. Claim/source identity and accounting remain present without a ceiling.

D1–D12 were all reviewed: the required column above is migration scope, while
only its extension column remains deferred. D8 specifically is not a blanket
optional feature. The TS/Node SDK and CLI/TUI shared command contract carry the
explicit Global account selector; native SDK selector contracts remain as
implemented in slice 13. Existing Desktop/manual general discovery reads visible
Global resources through current-root audience filtering. The manual installer
already supports `--global`, a destination-bound journal and readiness checks.
The existing collection UI still offers Personal/Project/Shared selections;
a new Global-management UI or identical methods in every SDK were never required.
The [post–slice 18 TODO list](../post-slice-18-follow-up-todos.md) records the
remaining Desktop Global account administration workflow and delegated runtime
capability repair. The later read cutover renamed the Shared UI selection to
“Available to everyone”; it did not add the Global custody destination.
Human ADMIN authorization is enforced by existing server operations, independent
of client identity. No new server API or universal SDK parity was added.


### Verification record

- `./gradlew format formatCheck` passed after the final Java/TypeScript edits.
- Final `./gradlew check` passed: **5,896 server tests**, zero failures/errors/skips,
  plus the repository's SDK/client, type, lint, generated-contract and architecture
  gates. Default checks did not opt into PostgreSQL or deployment tests.
- Focused `-PfullDb` coverage exercised `OperationRecordsPersistenceTest`,
  `EmbeddingRuntimeRepositoryTest` and `AuthorityCutoverRehearsalTest`: **48 test
  cases** across shared authority/allowance constraints, atomic charges, immutable
  Global-read inheritance, migration preservation, current-source publication,
  repair claim reconstruction and cross-store profile/index activation. The one
  final repair assertion used an incorrect column name; after correction its
  focused rerun passed. The strengthened caller-backed start regression also
  passed separately. An additional PostgreSQL privacy regression passed for
  filtering historical Global conversation trees before ranking and coverage.
  No whole database suite was run.
- `node --test scripts/install-manual.test.mjs`: **15 passed**, including an
  ephemeral mock WebSocket server exercising the public Node SDK and destination-
  bound Global receipts. This is contract evidence, not a live installation.
- Checked whitespace, local documentation links and unique migration versions.

No live provider, deployment data adoption, process restart, production backup/
restore or old-binary rollback was verified. M2–M3 remain the required deployment
acceptance work. Async orchestration starts formerly depended on a durable
caller-owned conversation allowance. The follow-up below removes that storage assumption; the 19 October
contract correction restores independently funded orchestration starts, including
from board seats. General closure reconstruction remains outside this work.

## Follow-up: consistent Global knowledge reads

[Global knowledge read contract](../global-knowledge-read-contract.md) supersedes
the remaining Shared/includeShared read compatibility described above. The active
read chain uses current-root GLOBAL_READ and live scoped READ for humans and service
accounts, intersected with retained operation ceilings across memory and documents.
The API selectors are `global_read` and `includeGlobal`; legacy Shared roots narrow
to HOME pending deliberate publication. This does not close the separate
[delegated runtime composition TODO](../delegated-runtime-authority-todo.md).


## Follow-up: standalone allowances and recoverable exhaustion

The [standalone allowance follow-up](../standalone-allowance-and-recovery-plan.md)
supersedes the conversation-owned allowance storage assumption above. Supported
orchestration starts retain a typed canonical allowance reference; a valid source
no longer needs a conversation-owned counter. Board seat/delegate references
retain their root source and scheduling leases. New exhaustion is recoverable
through existing scoped top-up/retry controls; explicit historical closure stays
terminal. See that document for migration, local evidence and deployment limits.
