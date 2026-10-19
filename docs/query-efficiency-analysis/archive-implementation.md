# Archive and navigation implementation ledger

Status: implemented; focused PostgreSQL verification passes. The passing repository-wide and native SDK gates are recorded in the [slice receipt](../query-efficiency-reuse-sweep.md#verification). This ledger covers the owners changed by the archive implementation agent in this slice. It records JDBC statement budgets, not measured latency. `N` is returned page cardinality, `R` is the number of deliveries whose state requires a running-job lookup, and `H` is one named-home lookup (zero for Global). Permission callbacks are counted separately where their owner can execute SQL.

Root ran the verification serially. The targeted PostgreSQL matrix passes 301 distinct cases across the slice, including the archive/navigation cases below. Focused operation-budget and invalidation tests pass; implementing agents did not run builds concurrently.

## JdbcInformationNavigationRepository — fixed; shared DTO adoption

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationNavigationRepository.java).

- `selected` now delegates selection/root/dependency authority to `InformationSelectionSql.navigation` and adds only the owning facet predicates. It does not repeat the old parent/readability/dependency checks around the set-based routine.
- `relationships` changes parent query + edge query + per-edge hydration into **one statement**. Previously a readable edge added one query; a locked edge added three; unavailable fallback added two. The statement materializes parent admission once, retains the original pre-page neighbor eligibility and ID ordering, and joins readable metadata or an allowed locked label after paging. V172's identical own/reference dependency checks become one evaluated dependency fact per candidate. Cross-resource pins cannot hydrate another resource's document. Locked results remove the pin, and unavailable results still drop after the same raw page.
- A scalar parent sentinel distinguishes unavailable parent from an empty page. A byte-size sentinel refuses the bounded page before title/tag/label mapping; fallback preserves full output. `Connections` is a deeply immutable graph and uses a full context/resource/direction/relation/other-resource/limit/offset key. DOCUMENTS and AUTHORITY durable versions fence reuse; LISTEN/NOTIFY is only a hint. The service still performs current account and selection admission. Historical/dirty transactions bypass through the coordinator.
- Documents/facets/relationships are single-projection reads; source/policy and other multi-query operations retain their stronger snapshot boundaries. No permission, mutation receipt, claim or lease is cached.

Tests: `InformationNavigationCacheTest`, `InformationConnectionsCacheTest`, `JdbcInformationFacetsTest`, service `DocumentNavigationReadsTest`; PostgreSQL `DocumentNavigationPersistenceTest.boundedConnectionsReuseWarmDtoButDurableRoleAndBoundaryFencesRemainCurrent` proves bounded SQL, warm reuse, separate ceiling identity and role revocation without hints. Existing direct graph tests cover locks, hidden references, pins and obligations.

## JdbcBoardMessagingRepository / BoardMessaging — fixed; no lifecycle cache

[Repository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/JdbcBoardMessagingRepository.java), [typed contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessagingRepository.java), [service](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardMessaging.java).

- Instance listings change **1 + 4N owner statements → 1**: bounded instances first, then pending/awaiting route aggregates and the latest unfinished started job. `InstanceListing` avoids calling instance-state, awaiting, pending-count and job repositories from each view mapper. The lookahead row and raw-before-visibility paging behavior are unchanged. Current log visibility and in-memory voice activity remain live.
- Delivery pages change **2 + 7N + R owner statements → 2** (participant lookup plus one page projection). If each visibility callback costs one statement, the complete model is **3 + 9N + R → 3 + 2N**. The final query batches route fields, both instances, message body/time, latest non-pending firing job, running existence and final reply. It preserves posted-time/message descending order and paging. Both participants must still belong to the caller; both current input-visibility checks remain after the metadata read, per returned delivery.
- Single-delivery reads and mutation/dispatch paths retain their existing fresh reads and locks. `InstanceListing` and `DeliveryListing` are read-only DTOs, but awaiting/running/pending/job/termination data are mutable execution state. They are deliberately not admitted to a shared lifecycle cache.

Tests: `BoardMessagingListingTest` covers repository budgets, states, lookahead, ownership and withheld inputs. PostgreSQL `BoardMessagingTest.delivery_page_batches_status_instances_jobs_and_final_reply_with_existing_paging` compares batched rows with the owning single-message projection, including running jobs, final reply and pagination.

## EntryStore / ConversationTrajectoryTool — fixed; scoped transcript adoption

[Store](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/EntryStore.java), [caller](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ConversationTrajectoryTool.java).

- `pageOfLog` no longer checks the same permission again before delegating to `pageOfLogAfter`: ordinary scoped forward pages **5 → 4** statements; unscoped pages remain three.
- `tailOfLog` derives the final offset from its own reached count. The tool no longer fetches and discards a complete first page: ordinary scoped tails **10 → 4**, unscoped **6 → 3**. Reach remains read first and bounds both count and page, preserving concurrent append semantics.
- `useTranscriptCache` is copied by `forAccount`. Only account-scoped pages of at most 100 rows are eligible. Identity includes account, conversation, side/ordinal, narrowing, ordered kinds, ordering/direction, offset/limit, retained reach and tail mode. SQL measures the bounded page including full utterance/answer/refusal/summary text and tool-call JSON; a scalar-only sentinel prevents oversized hydration. Fallback never truncates the ordinary result.
- Configured cold fills and fallback recheck `information_log_readable` before returning/publishing their DTO. This is necessary freshness: revocation can commit after the public preflight but before version capture. Coordinator post-load validation then fences later changes. A configured successful cold fill costs **5** statements; a warm hit costs **1 live admission** plus version probes. Oversize fallback costs eight owner statements and is never retained. Unconfigured direct behavior stays four/three as above.
- TRANSCRIPTS, DOCUMENTS and AUTHORITY cover entries, conversation child-opening metadata, log input/provenance/visibility dependencies and authority. Unscoped internal execution reads bypass caching. No mutable Budget, claims, cancellation state or cursors enter the cached `EntryPage` graph.

Tests: `EntryPageCacheTest`, `ConversationTrajectoryToolTest`; parent-owned PostgreSQL transcript bounded-projection and transcript-domain version cases verify SQL and durable invalidation. Mock races explicitly deny a post-preflight fill and an oversized fallback before publication.

## JdbcActingRepository — fixed; no authorization cache

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingRepository.java).

`tokens(subject)` changes ID listing plus token/project hydration **1 + 2N → 1**. Direct token and proof lookup **2 → 1**. An explicit safe projection excludes proof digest and external subject; an ordered correlated scope array hydrates each token in the same statement. The array is capped at 257 to detect a corrupt scope over the 256-project contract, fails closed and is freed after decoding. The page retains 256-token bound and created-time/ID order. Subject/proof validation, current subject/account checks at callers and live revocation remain authoritative. Token scope and revocation are authority inputs, so no shared token cache is added.

Tests: `JdbcActingTokenReadsTest` checks one statement, exact subject/proof binding, safe columns, immutable scopes, excess-scope refusal and SQL error non-replay; existing acting PostgreSQL tests remain the mapping/constraint proof.

## BoardStore — fixed; required allowance reads retained

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/BoardStore.java).

`post` returns the inserted `BoardMessage` using `RETURNING`: **2 → 1** statements without a reply target, **3 → 2** with the required same-topic reply check. The existing mapper reads the returned projection. `topup` reuses the `BoardTopic` already returned by `lockTopic`, removing one duplicate topic projection under that same retained lock. Conditional allowance/lifecycle validation and the canonical allowance owner remain unchanged. Root/child topic creation and locks are not converted into speculative cached counters; the allowance-state view represents mutable canonical spending.

Tests: `BoardStoreReuseTest` checks the returned mapper/bindings and retained locked object. Existing `BoardStoreTest`/messaging persistence tests cover PostgreSQL write constraints and behavior.

## JdbcApplicationDeploymentStore — fixed; immutable operation-local reuse

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcApplicationDeploymentStore.java).

A replacement activation reads and decodes its locked active `Retained` release once, then uses that exact placement for validation and `projects.activateDeployment`: normal update **9 → 8 local statements**, excluding delegated owners. `release` selects only revision/digest/file count/destination/placement. The project row lock, receipt/fingerprint/expected-head checks and rollback-target validation remain necessary. There is no provider/file I/O between these duplicated active-release reads; the resolved value is reused only within that transaction. No head/status/receipt cache is added, and retained FileStore access is not treated as a durable authority grant.

Tests: `ArchiveOperationReuseTest` verifies one old-release read and exact placement passed to its owner; existing deployment PostgreSQL cases cover source and receipt transactions.

## JdbcProjectMembers — fixed; operation-local authority only

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcProjectMembers.java).

`access` and `managementAccess` now select one management branch rather than call one another and repeat management authority. Ordinary access resolves `role` once rather than `requireRole` followed by another `role`. An ordinary VIEWER read is **5 → 4** statements; manager/history reads **6 → 5**. The admin branch changes **4 → 3** (management check plus member/history projections). `managementAccess` falling through to ordinary access also removes the repeated management check. Each new public operation resolves current authority again; member/history hydration performs no provider work and grants no authority. Mutation-time current grants, project locks and last-manager checks remain unchanged. No role, membership or management result is shared across requests.

Tests: `ArchiveOperationReuseTest` verifies one management predicate and one role query for ordinary access, and no ordinary-role query for administrative access. Existing permission PostgreSQL tests retain revocation and mutation coverage.

## ProjectStore — fixed; output projection only

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectStore.java).

`place`, `activateDeployment` and `attachClient` return the guarded UPDATE's `ProjectRecord` instead of refetching it: **UPDATE + final row read (2) → UPDATE RETURNING (1)** per successful write. The prior current-source/expected-placement reads, creator project lock, current FileStore permission/configuration checks and membership write remain. Server placement results still pass `resolved` for actual source/writable-directory validation; client attachment retains its prior raw client projection semantics. Empty writes fail rather than return nullable success. `ProjectRecord` has host resolution/fence dependencies and mutable lifecycle/configuration writers, so no shared placement cache is added.

Tests: `ArchiveOperationReuseTest` checks client attachment has one projection statement; the selected `ApplicationDeploymentPersistenceTest` and `ProjectStoreTest` PostgreSQL cases pass, covering returned placement, update/rollback/restart and client presence.

## ProposalStore — fixed; transition diagnostics retained

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProposalStore.java).

`propose` and successful `resolve` change **2 → 1** statements. `note` and `release` retain their necessary prior snapshot for refusal/compensation races, but return their conditional UPDATE's DTO: **3 → 2**. The data-modifying CTE joins the winning proposal row to the memory's current home in that same statement; Global remains a LEFT-joined absent project. Failed transitions still read to explain unknown/already-settled state; note/release still detect a concurrent return-to-pending and preserve duplicate pending-place handling. There is no shared pending/claim/receipt cache.

Tests: `ArchiveOperationReuseTest` covers budgets and failed-transition diagnostics. All 61 `ProposalStoreTest` PostgreSQL cases pass, including the overridden pre-read release races and unique pending constraint.

## DigestStore — fixed; fresh phase retained; cache blocked

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/DigestStore.java).

- `captureUnfolded` obtains eligible turns and excerpts in bounded cohorts of 64 and resolves its home once. With `C` conversations, `U` eligible turns and `B` nonblank excerpts, previous **H + 1 + C + U + (2 + H)B → H + Q + 2B**, where `Q=floor(U/64)+1` (including the terminal empty cohort for an exact multiple of 64). Source eligibility, protected log check, completed turn join, uncovered span predicate and ordered excerpt aggregation stay in the cohort statement.
- `covered(conversation)` captures only that conversation instead of every conversation in its home. Its complete budget is **H + Q + 2B + 2**, including home discovery and final coverage query, independent of sibling count.
- `group` retains every sorted row lock and then obtains a **fresh post-wait** cohort projection of ID/revision/staleness. It does not combine that freshness phase with lock acquisition. For `M` members, previous **3M + HM + 2H + 2 → 2M + ceil(M/64) + H + 1**, including child writes and the returned inserted parent. Parent INSERT uses `RETURNING`; fresh membership scope is joined in each cohort, and large summary/embedding fields are not fetched merely to validate members. Node browsing selects its actual five fields rather than `d.*`/embeddings.
- Refresh still needs its current locked source; its version/invalidation contract is not widened in this slice. `Node.revision` does not yet prove all summary/staleness mutations, so shared digest admission remains blocked. Provenance and tree lifecycle are not cached.

Tests: `DigestStoreReuseTest` covers source/query budgets, conversation-bounded capture, stable lock-before-fresh-read ordering and stale/missing rejection before writes. The complete `DigestStoreTest` PostgreSQL class passes, covering aggregation, range membership, retention and transaction behavior.

## ConversationStore — fixed identity waste; post-write freshness necessary

[Implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationStore.java).

`turnEnded` compares `allowance_id` with the supplied budget UUID in its guarded UPDATE, avoiding an allowance identity query plus a whole mutable Budget restoration merely to compare identity. Success changes **3 owner statements + allowance restore → 2 owner statements**, while retaining final attachment work. The source FK proves a matching durable allowance exists. A declined write still resolves the retained source to distinguish missing and mismatched identity.

`moveTo` deliberately retains its separate final `find`. Both successful methods can wait for a conversation row lock while another transaction commits allowance claims. Joining the allowance view inside an UPDATE CTE would return counters from the earlier statement snapshot. The separate post-write projection preserves current allowance visibility; it is a documented freshness reason, not discarded-object waste. Lifecycle/root/live-orchestration conditional predicates remain unchanged. `ConversationRecord` embeds mutable Budget/TurnCap and is not admitted to a shared immutable snapshot cache.

Tests: `ConversationCompletionReuseTest` covers guarded identity, absence of success-path restore, preserved post-write order and mismatch diagnostics; existing conversation PostgreSQL counter/lifecycle tests remain necessary.
