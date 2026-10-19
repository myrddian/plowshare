# JDBC query improvement plans

Status: proposed work; no query, cache, dispatch or database implementation is included.

Source baseline: `fc36351f239b62984363ababaa1de1139402aafb`, the merge of [PR #72](https://github.com/myrddian/plowshare/pull/72). This baseline contains current main at the start of planning. The merged tree matched the locally validated integration exactly; the non-database `./gradlew check`, focused PR tests, formatting and generated-contract checks passed before merging.

Read the [shared query and cache strategy](../query-efficiency-and-cache-design.md) and [cross-repository delivery plan](delivery.md) alongside each owner plan. The coding rules in [AGENTS.md](../../AGENTS.md) and [coding standards](../coding-standards.md#query-efficiency-and-reuse) apply now; the runtime mechanisms described here remain proposals.

The [shared readability function plan](readability-functions.md) traces the nested PostgreSQL work used by several owners and defines the authority invariants its optimization must preserve.

## Inventory and coverage

The inventory covers **102 persistence owners, adapters, SQL helpers and database probes** in the server. It includes legacy stores that own SQL, `RuntimeConfig` despite its name, the nested `TodoNotices.Jdbc`, the shared `ProjectIds` SQL helper and its `JdbcProjectDirectories` adapter. Each entry has an individual plan. Configuration that only constructs repositories, domain callers that mention JDBC in comments, and row-mapping/SQL-construction helpers without database execution are not separate persistence owners; their effects belong in the owning plans.

Discovery combined production Java references to Spring JDBC/data-source APIs, query-execution call sites, SQL-bearing classes and the delegated directory adapter. The [machine-readable inventory](inventory.json) records source paths and the baseline. All identified production JDBC query call sites were checked against it; `ArchiveConfig` only supplies `TransactionTemplate` transactions and `Archive` mentions a historical call in Javadoc.

Three delegated passes cover the discovery, archive and runtime groups below. Each plan identifies the owner's actual query families, proposed fixes, cache eligibility or exclusion, invalidation/consistency requirements and verification. The groups distribute planning work; they do not create new runtime boundaries.

Planning validation confirmed 102 distinct owner paths and 102 corresponding plans, proposal status and baseline in every plan, and 622 valid local file/heading/source-line references across the planning documents and changed rules. A separate delegated source review of the catalogue, documents, schedules, conversations, entries and Board messaging plans found no material inaccuracies. These documentation checks establish coverage and source grounding; the proposed performance changes still require implementation and measurement.

## Measured priorities

Earlier read-only measurements were taken from deployed revision `b96f260203bc880441a0c7709918926e5e642f1d`. They are historical observations, not new benchmarks of this planning checkout. PR #72 changes Application definition admission, so it does not establish that any performance issue has been fixed.

| Observation | Consequence for planning |
| --- | --- |
| Manual facets took approximately 1.1 seconds for global reads and 1.9 seconds for readable personal results. The catalogue issues 10 ordinary or 12 typed-view aggregate queries that repeat candidate selection. | Consolidate authorized candidate work and facets before considering cache hits. |
| A 37-chapter metadata selection took about 3 ms without the readability predicate and 171 ms with it. A profiled event readability root took about 738 ms with repeated permission-function calls. | Audit nested SQL functions and resolve invariant authority once per valid operation snapshot. Preserve current authorization. |
| Relay topic/message reads were roughly 25–70 ms in isolation, but about 1.9–2 seconds behind a facet request on the same connection. | Fix the serial dispatch and client loading dependency as well as repository SQL. |
| Revision event history returned 93 rows after discarding 52,588, about 2.93 ms at the observed size. | Verify a revision-and-sequence index against current migrations and representative growth; do not describe this observation as a second-long bottleneck. |
| Schedule definition application reads the source file collection repeatedly inside its per-file work. | Remove collection rehydration and quadratic work before adding a shared cache. |

The historical Manual account/scope mismatch and draft/publication mix need independent data provenance checks; they are not evidence that permissive scope changes would solve latency. No deployment data or permissions were changed for this plan.

## Documents, information, search and schedules

| Owner | Implementation | Individual plan |
| --- | --- | --- |
| `CitationStore` | [documents](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/CitationStore.java) | [Plan](documents-CitationStore.md) |
| `DocumentStore` | [documents](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentStore.java) | [Plan](documents-DocumentStore.md) |
| `JdbcEmbeddingSpaceRepository` | [embedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingSpaceRepository.java) | [Plan](embedding-JdbcEmbeddingSpaceRepository.md) |
| `JdbcEmbeddingWorkRepository` | [embedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingWorkRepository.java) | [Plan](embedding-JdbcEmbeddingWorkRepository.md) |
| `JdbcQueryEmbeddingRepository` | [embedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcQueryEmbeddingRepository.java) | [Plan](embedding-JdbcQueryEmbeddingRepository.md) |
| `JdbcFiringStore` | [events](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcFiringStore.java) | [Plan](events-JdbcFiringStore.md) |
| `JdbcInboxStore` | [events](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcInboxStore.java) | [Plan](events-JdbcInboxStore.md) |
| `JdbcScheduleDefinitionStore` | [events](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcScheduleDefinitionStore.java) | [Plan](events-JdbcScheduleDefinitionStore.md) |
| `JdbcScheduleStore` | [events](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcScheduleStore.java) | [Plan](events-JdbcScheduleStore.md) |
| `JdbcTriggerStore` | [events](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcTriggerStore.java) | [Plan](events-JdbcTriggerStore.md) |
| `FetchedPageStore` | [fetch](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/fetch/FetchedPageStore.java) | [Plan](fetch-FetchedPageStore.md) |
| `JdbcAcquisitionRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcAcquisitionRepository.java) | [Plan](information-JdbcAcquisitionRepository.md) |
| `JdbcDocumentPolicyRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcDocumentPolicyRepository.java) | [Plan](information-JdbcDocumentPolicyRepository.md) |
| `JdbcInformationCatalogueRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | [Plan](information-JdbcInformationCatalogueRepository.md) |
| `JdbcInformationContentRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationContentRepository.java) | [Plan](information-JdbcInformationContentRepository.md) |
| `JdbcInformationContentTypeRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationContentTypeRepository.java) | [Plan](information-JdbcInformationContentTypeRepository.md) |
| `JdbcInformationEventRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationEventRepository.java) | [Plan](information-JdbcInformationEventRepository.md) |
| `JdbcInformationGateRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationGateRepository.java) | [Plan](information-JdbcInformationGateRepository.md) |
| `JdbcInformationInterchangeRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationInterchangeRepository.java) | [Plan](information-JdbcInformationInterchangeRepository.md) |
| `JdbcInformationNavigationRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationNavigationRepository.java) | [Plan](information-JdbcInformationNavigationRepository.md) |
| `JdbcInformationProcessingRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProcessingRepository.java) | [Plan](information-JdbcInformationProcessingRepository.md) |
| `JdbcInformationProjectionRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationProjectionRepository.java) | [Plan](information-JdbcInformationProjectionRepository.md) |
| `JdbcInformationStageRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationStageRepository.java) | [Plan](information-JdbcInformationStageRepository.md) |
| `JdbcJobInformationRepository` | [information](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcJobInformationRepository.java) | [Plan](information-JdbcJobInformationRepository.md) |
| `ProviderStore` | [search](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/search/ProviderStore.java) | [Plan](search-ProviderStore.md) |
| `ResultSetStore` | [search](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/search/ResultSetStore.java) | [Plan](search-ResultSetStore.md) |

## Archive, projects, conversations and Board

| Owner | Implementation | Individual plan |
| --- | --- | --- |
| `CompactionStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/CompactionStore.java) | [Plan](archive-CompactionStore.md) |
| `ConversationStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationStore.java) | [Plan](archive-ConversationStore.md) |
| `DigestStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/DigestStore.java) | [Plan](archive-DigestStore.md) |
| `EntryStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryStore.java) | [Plan](archive-EntryStore.md) |
| `JdbcApplicationDeploymentStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcApplicationDeploymentStore.java) | [Plan](archive-JdbcApplicationDeploymentStore.md) |
| `JdbcApplicationRegistrations` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcApplicationRegistrations.java) | [Plan](archive-JdbcApplicationRegistrations.md) |
| `JdbcConversationContextRepository` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcConversationContextRepository.java) | [Plan](archive-JdbcConversationContextRepository.md) |
| `JdbcJobLog` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcJobLog.java) | [Plan](archive-JdbcJobLog.md) |
| `JdbcPassageRepository` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcPassageRepository.java) | [Plan](archive-JdbcPassageRepository.md) |
| `JdbcProjectDirectories` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectDirectories.java) | [Plan](archive-JdbcProjectDirectories.md) |
| `JdbcProjectMembers` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java) | [Plan](archive-JdbcProjectMembers.md) |
| `JdbcPromotionSourceRepository` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcPromotionSourceRepository.java) | [Plan](archive-JdbcPromotionSourceRepository.md) |
| `JdbcReasonLog` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcReasonLog.java) | [Plan](archive-JdbcReasonLog.md) |
| `LocalHookSetStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/LocalHookSetStore.java) | [Plan](archive-LocalHookSetStore.md) |
| `MemoryStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryStore.java) | [Plan](archive-MemoryStore.md) |
| `ProjectIds` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectIds.java) | [Plan](archive-ProjectIds.md) |
| `ProjectStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectStore.java) | [Plan](archive-ProjectStore.md) |
| `ProposalStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProposalStore.java) | [Plan](archive-ProposalStore.md) |
| `TurnStore` | [archive](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/TurnStore.java) | [Plan](archive-TurnStore.md) |
| `BoardStore` | [board](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardStore.java) | [Plan](board-BoardStore.md) |
| `JdbcBoardMessagingRepository` | [board](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/JdbcBoardMessagingRepository.java) | [Plan](board-JdbcBoardMessagingRepository.md) |
| `JdbcBoardPostRepository` | [board](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/JdbcBoardPostRepository.java) | [Plan](board-JdbcBoardPostRepository.md) |
| `JdbcPersonalSpaceRepository` | [personal](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/personal/JdbcPersonalSpaceRepository.java) | [Plan](personal-JdbcPersonalSpaceRepository.md) |
| `TodoNotices.Jdbc` | [todos](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/todos/TodoNotices.java) | [Plan](todos-TodoNotices.md) |
| `TodoStore` | [todos](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/todos/TodoStore.java) | [Plan](todos-TodoStore.md) |

## Relay, authority, runtime and execution

| Owner | Implementation | Individual plan |
| --- | --- | --- |
| `JdbcIdentityRepository` | [access](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcIdentityRepository.java) | [Plan](access-JdbcIdentityRepository.md) |
| `JdbcOperationRecords` | [access](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcOperationRecords.java) | [Plan](access-JdbcOperationRecords.md) |
| `JdbcResourceScopeRepository` | [access](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcResourceScopeRepository.java) | [Plan](access-JdbcResourceScopeRepository.md) |
| `JdbcScopedGrantRepository` | [access](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcScopedGrantRepository.java) | [Plan](access-JdbcScopedGrantRepository.md) |
| `JdbcSystemOperationRecords` | [access](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcSystemOperationRecords.java) | [Plan](access-JdbcSystemOperationRecords.md) |
| `JdbcActingRepository` | [acting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingRepository.java) | [Plan](acting-JdbcActingRepository.md) |
| `JdbcActingWorkRepository` | [acting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingWorkRepository.java) | [Plan](acting-JdbcActingWorkRepository.md) |
| `JdbcCommandInvocationsRepository` | [agents](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcCommandInvocationsRepository.java) | [Plan](agents-JdbcCommandInvocationsRepository.md) |
| `JdbcSkillExecutionsRepository` | [agents](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcSkillExecutionsRepository.java) | [Plan](agents-JdbcSkillExecutionsRepository.md) |
| `JdbcAllowanceRepository` | [allowances](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/allowances/JdbcAllowanceRepository.java) | [Plan](allowances-JdbcAllowanceRepository.md) |
| `JdbcApplicationIdentityRepository` | [applications](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/JdbcApplicationIdentityRepository.java) | [Plan](applications-JdbcApplicationIdentityRepository.md) |
| `JdbcSuspendedRunCalls` | [approvals](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/JdbcSuspendedRunCalls.java) | [Plan](approvals-JdbcSuspendedRunCalls.md) |
| `RunApprovalStore` | [approvals](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/approvals/RunApprovalStore.java) | [Plan](approvals-RunApprovalStore.md) |
| `AdminStore` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/AdminStore.java) | [Plan](auth-AdminStore.md) |
| `JdbcAccountAdministrationRepository` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcAccountAdministrationRepository.java) | [Plan](auth-JdbcAccountAdministrationRepository.md) |
| `JdbcDatabaseReadiness` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDatabaseReadiness.java) | [Plan](auth-JdbcDatabaseReadiness.md) |
| `JdbcDurableSessions` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcDurableSessions.java) | [Plan](auth-JdbcDurableSessions.md) |
| `JdbcServiceAccountRepository` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceAccountRepository.java) | [Plan](auth-JdbcServiceAccountRepository.md) |
| `JdbcServiceCredentialsRepository` | [auth](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/auth/JdbcServiceCredentialsRepository.java) | [Plan](auth-JdbcServiceCredentialsRepository.md) |
| `RuntimeConfig` | [config](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/config/RuntimeConfig.java) | [Plan](config-RuntimeConfig.md) |
| `JdbcCodeWorkspaceStore` | [files](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/JdbcCodeWorkspaceStore.java) | [Plan](files-JdbcCodeWorkspaceStore.md) |
| `AccountingStore` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingStore.java) | [Plan](llm-accounting-AccountingStore.md) |
| `JdbcInferenceAdmissions` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java) | [Plan](llm-accounting-JdbcInferenceAdmissions.md) |
| `JdbcPricingRepository` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcPricingRepository.java) | [Plan](llm-accounting-JdbcPricingRepository.md) |
| `JdbcUsageReportRepository` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageReportRepository.java) | [Plan](llm-accounting-JdbcUsageReportRepository.md) |
| `JdbcUsageScopeRepository` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcUsageScopeRepository.java) | [Plan](llm-accounting-JdbcUsageScopeRepository.md) |
| `UsageExecutionStore` | [llm/accounting](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/UsageExecutionStore.java) | [Plan](llm-accounting-UsageExecutionStore.md) |
| `JdbcOrchestrationAcceptance` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationAcceptance.java) | [Plan](orchestrations-JdbcOrchestrationAcceptance.md) |
| `JdbcOrchestrationChecks` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationChecks.java) | [Plan](orchestrations-JdbcOrchestrationChecks.md) |
| `JdbcOrchestrationRecovery` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/JdbcOrchestrationRecovery.java) | [Plan](orchestrations-JdbcOrchestrationRecovery.md) |
| `OrchestrationConcerns` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationConcerns.java) | [Plan](orchestrations-OrchestrationConcerns.md) |
| `OrchestrationStore` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationStore.java) | [Plan](orchestrations-OrchestrationStore.md) |
| `RecordStore` | [orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/RecordStore.java) | [Plan](orchestrations-RecordStore.md) |
| `JdbcScriptStore` | [orchestrations/scripted](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/JdbcScriptStore.java) | [Plan](orchestrations-scripted-JdbcScriptStore.md) |
| `JdbcOutgoingRepository` | [outgoing](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/outgoing/JdbcOutgoingRepository.java) | [Plan](outgoing-JdbcOutgoingRepository.md) |
| `JdbcProcRepository` | [proc](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/proc/JdbcProcRepository.java) | [Plan](proc-JdbcProcRepository.md) |
| `JdbcProviderCredentialRepository` | [providers](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/providers/JdbcProviderCredentialRepository.java) | [Plan](providers-JdbcProviderCredentialRepository.md) |
| `JdbcRelayApprovalInboxRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayApprovalInboxRepository.java) | [Plan](relay-JdbcRelayApprovalInboxRepository.md) |
| `JdbcRelayConsumerRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayConsumerRepository.java) | [Plan](relay-JdbcRelayConsumerRepository.md) |
| `JdbcRelayDeliveryRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayDeliveryRepository.java) | [Plan](relay-JdbcRelayDeliveryRepository.md) |
| `JdbcRelayExecutions` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayExecutions.java) | [Plan](relay-JdbcRelayExecutions.md) |
| `JdbcRelayForwardingHistory` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayForwardingHistory.java) | [Plan](relay-JdbcRelayForwardingHistory.md) |
| `JdbcRelayLogRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayLogRepository.java) | [Plan](relay-JdbcRelayLogRepository.md) |
| `JdbcRelayNativeRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayNativeRepository.java) | [Plan](relay-JdbcRelayNativeRepository.md) |
| `JdbcRelayOperationRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayOperationRepository.java) | [Plan](relay-JdbcRelayOperationRepository.md) |
| `JdbcRelayPortRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayPortRepository.java) | [Plan](relay-JdbcRelayPortRepository.md) |
| `JdbcRelayRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelayRepository.java) | [Plan](relay-JdbcRelayRepository.md) |
| `JdbcRelaySourceRepository` | [relay](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelaySourceRepository.java) | [Plan](relay-JdbcRelaySourceRepository.md) |
| `JdbcHostedToolScopeRepository` | [relay/tools](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/JdbcHostedToolScopeRepository.java) | [Plan](relay-tools-JdbcHostedToolScopeRepository.md) |
| `JdbcRelayToolRepository` | [relay/tools](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/JdbcRelayToolRepository.java) | [Plan](relay-tools-JdbcRelayToolRepository.md) |
| `UnionStore` | [union](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/union/UnionStore.java) | [Plan](union-UnionStore.md) |
