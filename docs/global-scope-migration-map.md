# Global scope migration map

Status: architecture proposal and source map, 17 October 2026. No implementation.
Read with [ADR 0030](decisions/0030-authority-substrate-and-explicit-scopes.md).

Source baseline: `efddb12a0cfd28c6aa1464f01b884d15597102d5`, merged main
containing account-delegation PR #73. Source links below identify current owners
and symbols at that revision; target behavior is proposed.

## Existing authority foundations

| Area | Current source and behavior | Proposed treatment |
| --- | --- | --- |
| Account identity and administration | [AdminStore](../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java) uses account handles and `server_admin`; [V111](../plowshare-server/src/main/resources/db/migration/V111__service_accounts.sql) admits USER, SERVICE and SERVICE_TOKEN account kinds | Add a real locked SYSTEM principal and explicit root/admin grants. Today's SYSTEM accounting status is not that account |
| Project RBAC | [ProjectRole](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectRole.java) has ordered VIEWER, CONTRIBUTOR and MANAGER; [ProjectMembers](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectMembers.java) owns live memberships and grant history | Reuse scoped membership contracts; define action bundles and grant provenance. READER/ADMIN/SYSTEM need explicit contract decisions |
| Administrative project access | [JdbcProjectMembers.role](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) grants server admins MANAGER for ordinary non-client/non-Personal projects; [ProjectAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java) has operation-specific admin rules | Replace ambient privileges with reviewed explicit administrative scope/action grants; preserve necessary operations without silently expanding data access |
| Personal isolation | [PersonalSpaces](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java) maps an account to a private project/union, enforces exact ownership and supplies human runnable defaults | Preserve private control and explicit scope. Do not grant an Application Personal membership to enable acting work |
| Tenant isolation | Relevant current account, project, Personal and information contracts use account/project identities rather than a first-class tenant identity | Specify tenant entities and mappings separately; do not declare each existing project a tenant by assumption |
| Delegated identity | [RequestAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/RequestAuthority.java) separates actor and subject; [LiveActingAuthorization](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/LiveActingAuthorization.java) intersects current roles, grants, consent and admitted ceilings | Preserve this intersection as an existing specialized policy. General grant provenance must not weaken its actor/subject restrictions |
| Retained execution | [RunAdmission](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAdmission.java), [RunAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RunAuthority.java), [PersonalRunAuthority](../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/PersonalRunAuthority.java) and [JobRuntime.forAdmittedTurn](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JobRuntime.java) retain bounded authority | Generalize coverage through existing consumers, with durable grant references. These contracts do not yet implement a universal grant substrate |
| Acting capability limits | [RetainedAgent](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedAgent.java) and [RetainedToolAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/RetainedToolAccess.java) refuse unsupported consumers and extract exact resource targets | Preserve refusals until each independent consumer supports retained authority; no account-only downgrade |
| Trusted definition classification | [DefinitionSource.Provenance](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionSource.java), [AgentRegistry.parseWith](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRegistry.java) and [AgentExposure](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentExposure.java) enforce bundled-only `is-system` and Application/acting exposure | Already implemented. Preserve provenance and aliases; classification is distinct from the proposed SYSTEM account and role |
| Explicit server channel scope | [Relay.Scope](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/Relay.java) distinguishes project scope from `SystemScope.SERVER`; [ScopedRelayInspection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/ScopedRelayInspection.java) requires admin authority for server inspection | Reuse the explicit distinction; later map it to durable scope identity without fabricating a project or weakening topic policy |
| Resource policy | [InformationAccess](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationAccess.java), [JdbcInformationCatalogueRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) and [ADR 0028](decisions/0028-document-navigation-and-reference-policy.md) enforce readability, derived-input and reference policies | Keep content access, reference disclosure, propagation and publication separate. These policies are not yet general resource ACLs |
| Accounting | [UsageAttribution.system](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageAttribution.java) uses `Status.SYSTEM`, a null account handle and an optional project | Preserve historical records. Future real executor identity, sponsoring account and usage attribution must be mapped independently |

## Verified Global paths and their replacements

| Path | Current behavior | Target responsibility |
| --- | --- | --- |
| Null scope representation | [Home.global](../plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/Home.java) is `Home(null)`; [ProjectIds](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectIds.java) maps Global to null database project IDs | Explicit scope UUIDs, distinct from account and role identities; unknown project continues to be distinct from server/shared scope |
| Omitted request scope | [RequestedHome.in](../plowshare-server/src/main/java/io/aeyer/plowshare/server/requests/RequestedHome.java) maps null to Global; [PersonalSpaces.home](../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/PersonalSpaces.java) supplies Personal for human runnable defaults | One documented selection contract per operation family, resolved before effects. Omission never selects server scope |
| Project memory recall and survey | Both `Archive.search` paths append Global hits and missing-embedding counts; recall also updates usage counters on returned memories in [Archive](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/Archive.java) | Query only the admitted scope and explicitly authorized published selections; preserve bounded result counts and usage accounting |
| Ordinary Application recall | [ProjectAuthorization.require](../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/ProjectAuthorization.java) refuses explicit Global service-token work; [MemoryRecallHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/MemoryRecallHandler.java) calls the archive, which adds Global internally | Apply scope policy to actual internal query targets. Retained acting currently does not support the `memory_recall` tool, so this finding is about the ordinary service-token path |
| Digest navigation | [Navigator.walk](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Navigator.java) explicitly tries the requested home then Global | No cross-scope retry; include other corpora only through an authorized explicit selection |
| Curation publication | [Passes.start](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Passes.java) requires a project; [Curator.promote](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/curator/Curator.java) approves confident rulings; [PromotionQueue.approve](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PromotionQueue.java) calls `Archive.promote`, which creates a Global memory and supersedes its origin | Preserve analysis/proposals, but require authorized source release and destination publication. Personal has no exclusion in the current promotion path |
| Curation trigger | Production callers of `Passes.start` are [AgentController](../plowshare-server/src/main/java/io/aeyer/plowshare/server/api/AgentController.java) and [AgentCurateHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/AgentCurateHandler.java); no automatic nightly registration was found | Treat the issue as publication authority exercised by an explicit request, not automatic leakage after every write |
| Digest job ownership | [Digests.start](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java) retains the requested home inside its closure but uses `JobStore.submit(name, work)`, which assigns Global | Persist the actual owning scope and admission. Normal successful digest outcomes contain counts/status; scope still governs visibility and cancellation |
| Definition failure recovery | [DefinitionResolver.forCaller/readProject](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionResolver.java) returns the boot set for missing projects and ordinary merge failures; [OrchestrationResolver.resolve](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/OrchestrationResolver.java) can recover to inherited definitions | Resolve intended identity first; refuse unknown/unavailable authoritative tiers. Preserve the existing Application definition merge refusal |
| Lenient project lookup | [RequestedProjectId.lenient/forListing](../plowshare-server/src/main/java/io/aeyer/plowshare/server/requests/RequestedProjectId.java) returns null for unresolved lookups; listing also absorbs archive outages | Typed absence/failure results cannot select another scope or registry. Note that resolvers may first map null IDs to caller Personal, rather than immediately returning boot definitions |
| Automatic definition/default inheritance | `DefinitionResolver.build` starts with inherited definitions; `defaultBot` searches nearer layers then Global/shipped; orchestration resolution also inherits | Explicit installed-catalogue availability, default bindings and imports, separate from worker registry lookup |
| Skill and prompt inheritance | [SkillResolver.forCaller](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillResolver.java) includes Global/shipped; [AgentRules.forPath](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentRules.java) appends Global instructions | Explicit instruction/import policies. Preserve skill grants and the existing refusal to fall through unreadable skill authority tiers |
| Shared information discovery | [InformationContext.Selection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationContext.java) defaults human Personal/project selections to `includeShared=true`; service selections must exclude it | Explicit audience and corpus selection. Preserve existing authenticated readability before pagination; shared inclusion is not permission to read unshared Personal content |
| Outgoing work | [OutgoingFrames](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/OutgoingFrames.java) passes omitted project through; [JdbcOutgoingRepository](../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java) stores null project and uses project key zero for account-owned peer registration | Explicit owning scope, retaining account-bound reads/claims and original request UUIDs. Current null scope does not demonstrate cross-account access |
| Public selection contracts | [CLI options](../plowshare-cli/src/options.ts), [SDK discovery](../sdk/typescript/src/operations/discovery.ts), [MCP tools](../plowshare-mcp/src/tools.json), [desktop scheduling](../plowshare-desktop/src/renderer/schedule-controls.ts) and [operator controls](../plowshare-desktop/src/renderer/operator.ts) encode Global | Update typed operations, generated catalogues, help and UI together; remove contradictory omission semantics |

### Current omitted scope behavior

These are production human defaults at the baseline, not one consistent Global
selection contract. Service-token authorization separately requires permitted
project/resource scope and refuses Global work.

| Operation family | Current omission | Proposed selection rule |
| --- | --- | --- |
| Conversation open, ordinary agent run, direct orchestration start | Personal through production `PersonalSpaces` wiring | Resolve the authenticated account's explicit Personal scope where this default is supported |
| Conversation list/latest/search | Global through `RequestedHome` | Use the documented authorized selection, never an omitted server/shared scope |
| Memory index/recall/write, proposal listing, digest/navigation | Global through `RequestedHome` | Resolve a permitted explicit work/corpus scope; do not append shared data automatically |
| Information/document selection | Personal plus shared for human defaults | Select Personal and explicitly requested published audiences independently |
| Outgoing send/advertise/claim | Account-bound null project | Resolve an explicit owning scope and retain account ownership |
| Retained acting turn | Explicit acting context and subject Personal authority | Preserve explicit actor/subject admission and supported capability limits |

The precise list/selection defaults require review before implementation. A read
may offer an explicit authorized aggregate selection without adopting the same
default as a mutation. The common invariant is that omission cannot select server
authority, published content or a fallback registry.

## Preserve the boundaries already working

Global conversation resume is refused by
[Callers.requireConversationProject](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Callers.java)
and [ConversationResumeHandler](../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ConversationResumeHandler.java).
Board opening, messaging and commands require project context. A Global local
filesystem is refused; the legacy remote-provider path checks the submitting
owner's session in [AgentsConfig](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentsConfig.java).
The proposal does not create a server-wide agent filesystem.

[FileScheduleDefinitions](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/FileScheduleDefinitions.java)
requires administration for Global schedule sources and refuses unknown named
projects. [WorkRelayReceiver](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/WorkRelayReceiver.java)
refuses unavailable destinations. Keep these failures explicit while replacing
their underlying scope representation.

[ReloadingGlobalAgents](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ReloadingGlobalAgents.java)
and `GlobalAgentDefinitions` provide a server registry independent of caller
project overrides. Scribe, Curator, Learner and document workers still need trusted
internal role resolution. Preserve worker execution while separating it from
user catalogue availability and published data.

The audit's hardcoded `daedalus` assertion is obsolete at this baseline.
[AgentExposure.operatorOnly](../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/AgentExposure.java)
uses `definition.isSystem()`. The bundled diagnostic is now the private
`conversation_diagnostician`, reached through the `diagnose-conversation` skill;
an authored namesake does not acquire its classification. Do not schedule an
already completed provenance change as migration work.

## Legacy classification and durable identity

Replacing every null project with the SYSTEM scope would put user history,
published knowledge and administration into one new container. Classify by owning
record and retained provenance instead:

| Existing record | Migration rule |
| --- | --- |
| Project and Personal resources | Map to the continuing explicit scope; preserve resource/revision identities, membership and history |
| Proven server configuration and internal channel state | Map to server-administration scope under explicit SYSTEM control |
| Proven deliberately shared memories/documents | Record an explicit legacy publication scope/audience; do not make them internal SYSTEM resources or automatically import them into every project |
| Null-project conversations and jobs | Preserve their IDs and content with restricted legacy scope; recover a narrower owning scope only from authoritative records |
| Misclassified digest jobs | Use durable original project evidence where available; do not infer ownership from a job name or an in-memory closure that no longer exists |
| Null-project outgoing rows | Preserve account ownership and original receipt/request identities; select an explicit migrated scope without resending work |
| Historical model usage | Retain original attribution and billing facts. Add linkage/mapping where justified, never relabel historical SYSTEM or unattributed usage as a new account |
| Uncertain origin, audience or tenant | Restrict access pending operator classification; record the decision rather than guessing or inheriting a broader audience |

An explicit legacy publication audience is not a permanent Global fallback.
It records prior intended sharing for review and explicit selection. New policy
must never widen the established audience merely to complete a migration.

Use new Flyway migrations, classification reports, backups and scoped migration
receipts. Rehearse against a restored copy. A completed authority cutover must
reject active rows/work with missing scope or unresolved grant provenance;
quarantined historical records remain explicitly marked and unavailable to normal
execution. Coordinate readers/writers so a mixed-version deployment cannot
interpret the same null as both unrestricted legacy work and a refused scope.

## Proposed implementation sequence

These slices describe dependencies and review boundaries, not authorization to
implement them. Early scope/control repairs can be separately authorized while
the complete substrate remains under design.

1. **Settle the authority contract.** Approve tenant/Personal boundaries, the
   root identity registry, role action sets, grant delegation, publication audiences
   and admission invariants in ADR 0030. Record changes to ADRs 0025/0026/0028
   explicitly rather than silently superseding them.
2. **Bound the immediate exposure paths.** Give digest jobs their real scope;
   separate curation assessment from publication authority; stop hidden Global
   query/navigation expansion and refuse failed authoritative definition tiers.
   Keep internal workers functioning. Each behavioral change needs a reviewed
   public selection/default contract before client cutover.
3. **Introduce explicit identities and root grants.** Add durable scope mappings,
   the locked SYSTEM account/role, administrative grants and auditable grant
   provenance. Preserve existing IDs and last-manager/recovery invariants.
4. **Carry bounded execution authority through consumers.** Extend the existing
   retained-authority model across ordinary jobs, schedules, orchestration,
   messaging, Relay, approvals and internal workers. Migrate a consumer only when
   its live checks, durable ceiling, output visibility and recovery are complete.
5. **Separate catalogues and publication.** Make user definition availability,
   defaults, instruction imports, resource ACL grants and published audiences
   explicit. Keep the internal worker registry independent of project overrides.
6. **Classify legacy rows and complete contract cutover.** Backfill proven scope,
   preserve restricted unknowns, remove null-as-Global paths and update every
   affected SDK, generated catalogue, client selector and manual. Enable database
   constraints only after the classification preflight succeeds.

## Acceptance criteria for future implementation

Default mocked/fake tests should cover:

- SYSTEM execution inside one admitted scope, refusing unrelated resources and
  publication even when the root role permits the action in principle.
- ADMIN management without implicit Personal/content access; role bundles versus
  resource ACL grants; delegation allowed only within the issuing authority.
- Expired/revoked parents, grant cycles, unknown provenance and attempted widening
  of scope, action, time, recipient or delegation rights.
- Same-project/different-account, different-project/same-tenant, cross-tenant,
  Personal, service-token and acting-context matrices.
- Omitted, null, blank, unknown, deleted, offline and malformed scope selections;
  invalid overrides must not recover to SYSTEM, legacy shared or shipped content.
- No hidden expansion in recall, survey, navigation, prompts, information selection,
  counts, events or pagination; authorized explicit imports still work.
- Scope and authority retained through queues, children, cancellation, approvals,
  restarts and receipt reads; uncertain effects are never replayed.
- Curation analysis remains usable while unauthorized publication refuses; derived
  output policies and source/destination audiences are checked independently.
- Trusted classification/aliases remain protected; authored namesakes remain
  ordinary; internal workers and ordinary explicitly available shipped bots work.
- Account-owned outgoing receipts and claims remain isolated during scope migration.

Focused `full-db` tests are justified later only for migrations, UUID/FK/constraint
enforcement, scope-filtered row mapping, grant/effect transaction races, locking,
rollback and retained receipts. Follow the repository's explicit `-PfullDb` policy
and state the specific database assertion before running them. Mocked behavior
tests cannot establish those persistence properties.

The source audit was checked with 380 passing cases across 23 focused suites at
the baseline above, including acting authority, retained execution, classification,
definition resolution, skills, curation and digest submission. The console asset
build was excluded after an unrelated TypeScript dependency failure. No database
tests ran. This is baseline evidence for existing behavior, not validation of the
proposed substrate or a successful legacy-data migration.
