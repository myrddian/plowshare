# JDBC query analysis

Status: analysis only; no runtime, schema, cache or dispatch implementation.
Source checkout `7bc567c2282188925ac07c81e6fcae81b41a0a40`; runtime base
`fc36351f239b62984363ababaa1de1139402aafb`. This analysis follows the
[repository plans](../query-efficiency-plans/README.md) and tests their claims
against current source, callers, DTOs, migrations, indexes and actual read measurements.

## Results

Three delegated analyses cover **102 owners**: **49 with confirmed redundant work**,
**28 candidates**, and **25 with no established material waste** on inspected paths.
These are owner classifications, not independent root causes or claims that every
flagged owner is slow. A single statement can also be expensive internally.

The parser catalogued **1,327 direct JDBC invocation sites** in 101 SQL-bearing
files. `JdbcProjectDirectories` delegates to `ProjectIds` and has no direct site.
The [query-site catalogue](query-sites.json) retains source path, SHA256, method,
line, operation, SQL argument expression and lexical loop/lambda contexts.
A Java 21 parse-only AST selected calls on template-typed variable names; it did not
perform symbol attribution, execute SQL, expand callbacks, resolve all dynamic SQL,
or infer transitive calls. Site count is neither a runtime query count nor the
number of distinct SQL shapes. The owner reports expand actual caller/helper paths.

- [Discovery, Manual, documents, schedules and broker/delivery analysis](discovery.md): 33 owners.
- [Archive, Board, project and Relay-port/tool analysis](archive.md): 31 owners.
- [Authority, accounting, execution and runtime analysis](runtime.md): 38 owners.
- [Cross-cutting analysis and measured priorities](cross-cutting.md): request blocking,
  repeated authorization functions, client refresh, index evidence and cache feasibility.
- [Sanitized measurements and samples](measurements.json): exact deployed revision,
  PostgreSQL/schema versions, timing ranges, matched controls and function counts.
- [Structured owner index](owner-index.json): one entry per owner, evidence links,
  query-count model, uncertainty and cache verdict. Individual agent ledgers are
  [discovery](discovery-findings.json), [archive](archive-findings.json) and
  [runtime](runtime-findings.json).

## Findings that change the implementation order

| Finding | Evidence and implication |
| --- | --- |
| Facets repeat expensive authorized selection |10 ordinary /12 typed aggregate statements. Fresh typed facets took 1.10–1.17s globally and1.89–1.97s personally. Prioritize evaluated-candidate and permission-function reuse. |
| A shared socket holds fast reads behind facets | Isolated Relay reads 24–30ms became 1.84–2.07s behind facets; a separate-socket control stayed 22–53ms. Bounded read dispatch and independent client rendering matter alongside SQL. |
| Permission work is substantial even in one statement | Identical 37-chapter count SQL took 0.711–3.504ms without readability and 159.326–172.360ms with it. One event check invoked account_active 6547 times. Preserve authority while sharing evaluated facts. |
| Status reconstructs configuration inside its step loop | With configured dual embedding targets, S visible steps add 4S helper JDBC statements beyond the catalogue-owned budget. Compute one operation snapshot before adopting a shared cache. |
| Schedule reconciliation rehydrates whole collections | Unchanged N-file scan: 1+4N statements and N+2N² file rows under stated assumptions; current single-file keys already support narrow reads. Live 2-row schedule listing itself took 2.492ms. |
| N+1 reads occur outside information repositories | Acting token listing 1+2N (513 at 256); Board visible instance listing 1+4V (81 at 20). These are source-derived counts, not latency benchmarks. |
| Several additional reads are provably avoidable | Duplicate transcript authorization wrappers and a discarded initial tail page; per-call attempt lookups; pricing per-target lookups; Relay retry hydration and unused handler source. |
| Existing versions can miss display changes | Workspace publish preserves claim generation; outgoing cancel preserves worker revision; Relay UUID generations identify incarnations. Add correct change versions without weakening execution fences. |
| Some repeated reads are required | Coverage checks around provider IO, spent-refresh checks after locking, post-hook projection recapture and authoritative claims cannot be removed by resemblance alone. |

Measurements use three samples for repeated comparisons, not p95 or load testing.
They include transport and queue time, not UI render time. Direct database SELECTs
were bounded and READ ONLY; no index/routine/cache change was benchmarked. The
running source was `b96f260203bc880441a0c7709918926e5e642f1d`; only PR72's
`Definitions.java` differs in production source from runtime base fc36351. Global
Manual results 0 versus personal 37 remain distinct publication/scope facts.

## Cache admission conclusions

| Verdict | Owners | Meaning |
| --- | --- | --- |
| `operation-local` | 39 | Reuse a typed snapshot in its current consistency boundary; no cross-operation authority lease. |
| `share-immutable` | 8 | Potential immutable content/descriptor candidate; shared foundation, deep immutability, retention and current disclosure still need proof. |
| `cache-blocked` | 30 | Current DTO/version/writer coverage is insufficient for safe shared admission. |
| `no-cache` | 25 | The analyzed path owns current authority, availability, claims, leases or execution eligibility. |

Each owner receives a primary cache verdict; a single owner can have both descriptive
content and uncached lifecycle paths. Both agent spellings shared-immutable and
share-immutable mean a candidate, not an implemented cache. Existing local caches
and snapshot fences are recorded rather than assumed absent. See the
[tracked DTO/LRU contract](../query-efficiency-and-cache-design.md) for the proposed
bounded mechanics and after-commit invalidation/recovery requirements.

## Owner-by-owner evidence

`Sites` is the static direct invocation count from the AST catalogue. Follow each
analysis link for branch-specific execution formulas, required reads, actual version
limitations and unmeasured assumptions. `No established waste` does not prove speed.

| Owner | Sites | Source verdict | Cache verdict | Analysis |
| --- | ---: | --- | --- | --- |
| `JdbcIdentityRepository` | 7 | No established waste | `operation-local` | [Evidence](runtime.md#r01--jdbcidentityrepository) |
| `JdbcOperationRecords` | 11 | Confirmed waste | `operation-local` | [Evidence](runtime.md#r02--jdbcoperationrecords) |
| `JdbcResourceScopeRepository` | 3 | Candidate | `operation-local` | [Evidence](runtime.md#r03--jdbcresourcescoperepository) |
| `JdbcScopedGrantRepository` | 17 | Candidate | `no-cache` | [Evidence](runtime.md#r04--jdbcscopedgrantrepository) |
| `JdbcSystemOperationRecords` | 2 | No established waste | `operation-local` | [Evidence](runtime.md#r05--jdbcsystemoperationrecords) |
| `JdbcActingRepository` | 25 | Confirmed waste | `no-cache` | [Evidence](runtime.md#r06--jdbcactingrepository) |
| `JdbcActingWorkRepository` | 10 | Candidate | `operation-local` | [Evidence](runtime.md#r07--jdbcactingworkrepository) |
| `JdbcCommandInvocationsRepository` | 6 | Confirmed waste | `operation-local` | [Evidence](runtime.md#r08--jdbccommandinvocationsrepository) |
| `JdbcSkillExecutionsRepository` | 8 | No established waste | `operation-local` | [Evidence](runtime.md#r09--jdbcskillexecutionsrepository) |
| `JdbcAllowanceRepository` | 7 | Candidate | `no-cache` | [Evidence](runtime.md#r10--jdbcallowancerepository) |
| `JdbcApplicationIdentityRepository` | 10 | Candidate | `no-cache` | [Evidence](runtime.md#r11--jdbcapplicationidentityrepository) |
| `JdbcSuspendedRunCalls` | 7 | No established waste | `operation-local` | [Evidence](runtime.md#r12--jdbcsuspendedruncalls) |
| `RunApprovalStore` | 16 | Confirmed waste | `operation-local` | [Evidence](runtime.md#r13--runapprovalstore) |
| `CompactionStore` | 3 | Candidate | `share-immutable` | [Evidence](archive.md#a16--compactionstore) |
| `ConversationStore` | 20 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a11--conversationstore) |
| `DigestStore` | 28 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a02--digeststore) |
| `EntryStore` | 27 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a15--entrystore) |
| `JdbcApplicationDeploymentStore` | 8 | Confirmed waste | `share-immutable` | [Evidence](archive.md#a07--jdbcapplicationdeploymentstore) |
| `JdbcApplicationRegistrations` | 2 | Candidate | `operation-local` | [Evidence](archive.md#a17--jdbcapplicationregistrations) |
| `JdbcConversationContextRepository` | 1 | No established waste | `operation-local` | [Evidence](archive.md#a18--jdbcconversationcontextrepository) |
| `JdbcJobLog` | 5 | Candidate | `cache-blocked` | [Evidence](archive.md#a19--jdbcjoblog) |
| `JdbcPassageRepository` | 16 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a08--jdbcpassagerepository) |
| `JdbcProjectDirectories` | 0 | No established waste | `operation-local` | [Evidence](archive.md#a20--jdbcprojectdirectories) |
| `JdbcProjectMembers` | 26 | Confirmed waste | `operation-local` | [Evidence](archive.md#a04--jdbcprojectmembers) |
| `JdbcPromotionSourceRepository` | 1 | No established waste | `operation-local` | [Evidence](archive.md#a21--jdbcpromotionsourcerepository) |
| `JdbcReasonLog` | 2 | Candidate | `operation-local` | [Evidence](archive.md#a22--jdbcreasonlog) |
| `LocalHookSetStore` | 2 | No established waste | `share-immutable` | [Evidence](archive.md#a23--localhooksetstore) |
| `MemoryStore` | 32 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a03--memorystore) |
| `ProjectIds` | 2 | No established waste | `operation-local` | [Evidence](archive.md#a24--projectids) |
| `ProjectStore` | 25 | Confirmed waste | `cache-blocked` | [Evidence](archive.md#a10--projectstore) |
| `ProposalStore` | 9 | Confirmed waste | `operation-local` | [Evidence](archive.md#a12--proposalstore) |
| `TurnStore` | 5 | No established waste | `share-immutable` | [Evidence](archive.md#a25--turnstore) |
| `AdminStore` | 14 | Candidate | `no-cache` | [Evidence](runtime.md#r14--adminstore) |
| `JdbcAccountAdministrationRepository` | 12 | Candidate | `operation-local` | [Evidence](runtime.md#r15--jdbcaccountadministrationrepository) |
| `JdbcDatabaseReadiness` | 1 | No established waste | `no-cache` | [Evidence](runtime.md#r16--jdbcdatabasereadiness) |
| `JdbcDurableSessions` | 14 | Candidate | `no-cache` | [Evidence](runtime.md#r17--jdbcdurablesessions) |
| `JdbcServiceAccountRepository` | 19 | Confirmed waste | `no-cache` | [Evidence](runtime.md#r18--jdbcserviceaccountrepository) |
| `JdbcServiceCredentialsRepository` | 1 | No established waste | `no-cache` | [Evidence](runtime.md#r19--jdbcservicecredentialsrepository) |
| `BoardStore` | 54 | Confirmed waste | `operation-local` | [Evidence](archive.md#a09--boardstore) |
| `JdbcBoardMessagingRepository` | 53 | Confirmed waste | `operation-local` | [Evidence](archive.md#a01--jdbcboardmessagingrepository) |
| `JdbcBoardPostRepository` | 3 | No established waste | `no-cache` | [Evidence](archive.md#a26--jdbcboardpostrepository) |
| `RuntimeConfig` | 3 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r20--runtimeconfig) |
| `CitationStore` | 6 | Confirmed waste | `operation-local` | [Evidence](discovery.md#citationstore) |
| `DocumentStore` | 15 | Candidate | `cache-blocked` | [Evidence](discovery.md#documentstore) |
| `JdbcEmbeddingSpaceRepository` | 3 | No established waste | `share-immutable` | [Evidence](discovery.md#jdbcembeddingspacerepository) |
| `JdbcEmbeddingWorkRepository` | 23 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcembeddingworkrepository) |
| `JdbcQueryEmbeddingRepository` | 10 | Candidate | `no-cache` | [Evidence](discovery.md#jdbcqueryembeddingrepository) |
| `JdbcFiringStore` | 21 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcfiringstore) |
| `JdbcInboxStore` | 13 | Candidate | `cache-blocked` | [Evidence](discovery.md#jdbcinboxstore) |
| `JdbcScheduleDefinitionStore` | 13 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcscheduledefinitionstore) |
| `JdbcScheduleStore` | 9 | No established waste | `no-cache` | [Evidence](discovery.md#jdbcschedulestore) |
| `JdbcTriggerStore` | 6 | No established waste | `cache-blocked` | [Evidence](discovery.md#jdbctriggerstore) |
| `FetchedPageStore` | 4 | Candidate | `cache-blocked` | [Evidence](discovery.md#fetchedpagestore) |
| `JdbcCodeWorkspaceStore` | 22 | Candidate | `cache-blocked` | [Evidence](runtime.md#r21--jdbccodeworkspacestore) |
| `JdbcAcquisitionRepository` | 18 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcacquisitionrepository) |
| `JdbcDocumentPolicyRepository` | 17 | No established waste | `no-cache` | [Evidence](discovery.md#jdbcdocumentpolicyrepository) |
| `JdbcInformationCatalogueRepository` | 118 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcinformationcataloguerepository) |
| `JdbcInformationContentRepository` | 11 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcinformationcontentrepository) |
| `JdbcInformationContentTypeRepository` | 12 | Confirmed waste | `share-immutable` | [Evidence](discovery.md#jdbcinformationcontenttyperepository) |
| `JdbcInformationEventRepository` | 6 | Candidate | `no-cache` | [Evidence](discovery.md#jdbcinformationeventrepository) |
| `JdbcInformationGateRepository` | 14 | Confirmed waste | `no-cache` | [Evidence](discovery.md#jdbcinformationgaterepository) |
| `JdbcInformationInterchangeRepository` | 6 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcinformationinterchangerepository) |
| `JdbcInformationNavigationRepository` | 47 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcinformationnavigationrepository) |
| `JdbcInformationProcessingRepository` | 27 | Confirmed waste | `no-cache` | [Evidence](discovery.md#jdbcinformationprocessingrepository) |
| `JdbcInformationProjectionRepository` | 37 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcinformationprojectionrepository) |
| `JdbcInformationStageRepository` | 8 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcinformationstagerepository) |
| `JdbcJobInformationRepository` | 7 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcjobinformationrepository) |
| `AccountingStore` | 23 | Candidate | `operation-local` | [Evidence](runtime.md#r22--accountingstore) |
| `JdbcInferenceAdmissions` | 6 | Candidate | `no-cache` | [Evidence](runtime.md#r23--jdbcinferenceadmissions) |
| `JdbcPricingRepository` | 5 | Confirmed waste | `share-immutable` | [Evidence](runtime.md#r24--jdbcpricingrepository) |
| `JdbcUsageReportRepository` | 20 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r25--jdbcusagereportrepository) |
| `JdbcUsageScopeRepository` | 2 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r26--jdbcusagescoperepository) |
| `UsageExecutionStore` | 8 | Confirmed waste | `operation-local` | [Evidence](runtime.md#r27--usageexecutionstore) |
| `JdbcOrchestrationAcceptance` | 6 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r28--jdbcorchestrationacceptance) |
| `JdbcOrchestrationChecks` | 3 | No established waste | `operation-local` | [Evidence](runtime.md#r29--jdbcorchestrationchecks) |
| `JdbcOrchestrationRecovery` | 4 | No established waste | `no-cache` | [Evidence](runtime.md#r30--jdbcorchestrationrecovery) |
| `OrchestrationConcerns` | 3 | Candidate | `cache-blocked` | [Evidence](runtime.md#r31--orchestrationconcerns) |
| `OrchestrationStore` | 63 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r32--orchestrationstore) |
| `RecordStore` | 10 | No established waste | `operation-local` | [Evidence](runtime.md#r33--recordstore) |
| `JdbcScriptStore` | 11 | Confirmed waste | `operation-local` | [Evidence](runtime.md#r34--jdbcscriptstore) |
| `JdbcOutgoingRepository` | 13 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r35--jdbcoutgoingrepository) |
| `JdbcPersonalSpaceRepository` | 9 | Candidate | `operation-local` | [Evidence](archive.md#a14--jdbcpersonalspacerepository) |
| `JdbcProcRepository` | 8 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r36--jdbcprocrepository) |
| `JdbcProviderCredentialRepository` | 12 | No established waste | `no-cache` | [Evidence](runtime.md#r37--jdbcprovidercredentialrepository) |
| `JdbcRelayApprovalInboxRepository` | 5 | Candidate | `no-cache` | [Evidence](archive.md#a28--jdbcrelayapprovalinboxrepository) |
| `JdbcRelayConsumerRepository` | 4 | No established waste | `no-cache` | [Evidence](discovery.md#jdbcrelayconsumerrepository) |
| `JdbcRelayDeliveryRepository` | 18 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcrelaydeliveryrepository) |
| `JdbcRelayExecutions` | 4 | No established waste | `no-cache` | [Evidence](discovery.md#jdbcrelayexecutions) |
| `JdbcRelayForwardingHistory` | 1 | Candidate | `operation-local` | [Evidence](archive.md#a29--jdbcrelayforwardinghistory) |
| `JdbcRelayLogRepository` | 8 | Confirmed waste | `share-immutable` | [Evidence](archive.md#a30--jdbcrelaylogrepository) |
| `JdbcRelayNativeRepository` | 10 | Candidate | `no-cache` | [Evidence](discovery.md#jdbcrelaynativerepository) |
| `JdbcRelayOperationRepository` | 15 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#jdbcrelayoperationrepository) |
| `JdbcRelayPortRepository` | 5 | Confirmed waste | `no-cache` | [Evidence](archive.md#a05--jdbcrelayportrepository) |
| `JdbcRelayRepository` | 15 | Confirmed waste | `operation-local` | [Evidence](discovery.md#jdbcrelayrepository) |
| `JdbcRelaySourceRepository` | 6 | Candidate | `no-cache` | [Evidence](discovery.md#jdbcrelaysourcerepository) |
| `JdbcHostedToolScopeRepository` | 12 | Confirmed waste | `operation-local` | [Evidence](archive.md#a06--jdbchostedtoolscoperepository) |
| `JdbcRelayToolRepository` | 5 | Candidate | `operation-local` | [Evidence](archive.md#a31--jdbcrelaytoolrepository) |
| `ProviderStore` | 5 | Confirmed waste | `cache-blocked` | [Evidence](discovery.md#providerstore) |
| `ResultSetStore` | 3 | No established waste | `cache-blocked` | [Evidence](discovery.md#resultsetstore) |
| `TodoNotices.Jdbc` | 3 | No established waste | `operation-local` | [Evidence](archive.md#a27--todonoticesjdbc) |
| `TodoStore` | 3 | Candidate | `operation-local` | [Evidence](archive.md#a13--todostore) |
| `UnionStore` | 9 | Confirmed waste | `cache-blocked` | [Evidence](runtime.md#r38--unionstore) |

## Verification and remaining limits

Coverage is reconciled one-to-one against the 102-owner inventory and 1,327-site
catalogue. Final checks validated 102 source hashes, site line bounds and 1,159 local
file/heading/source references, with no errors. Independent review checked high-impact count models and corrected
configuration helper amplification and transcript text-size assumptions. Historical
commits are identified where supported; an earliest public snapshot is not proof
of the original introducing commit. Query counts exclude transaction/driver/function
internals as stated; JDBC batching is not assumed to turn N logical writes into
one SQL statement or network exchange.

No database test suite or build was run for these analysis documents. Read-only
measurements do not prove migration, transaction, authorization, recall or cache
concurrency correctness. Implementation still needs the focused verification in
its owner plan and the [shared delivery sequence](../query-efficiency-plans/delivery.md).
