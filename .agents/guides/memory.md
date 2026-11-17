# Memory and embeddings

Read this for recall/reminders, filing, Scribe/Learner/Curator, digest navigation,
semantic indexing or embedding repair. Read [AUTH](auth.md) when changing acting
identity, homes, retained input/output authority, grant checks or disclosure.

## Mental model

Memory combines durable lessons and conversation-derived digests with semantic
indexes that can be incomplete or rebuilt. Recall, filing and background learning
share storage and embedding capabilities while retaining distinct authority,
source evidence and inference budgets.

## Start in code

- [MemoryQueries](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryQueries.java) and
  [MemoryWrites](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/MemoryWrites.java) are the admitted read/write contracts;
  their repository interfaces also live in `archive/`.
- [BoundMemoryQueries](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundMemoryQueries.java) and
  [BoundMemoryWrites](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundMemoryWrites.java) bind caller scope,
  operation lifetime, optional inference and final reads/mutations.
- [DualEmbeddings](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/DualEmbeddings.java) and
  [SemanticEmbedding](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/SemanticEmbedding.java) own model/profile-aware work;
  [QuerySources](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/QuerySources.java) retains operation evidence.
- [Learner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/learner/Learner.java),
  [Scribe](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/scribe/Scribe.java) and
  [Digests](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/digests/Digests.java) own distinct processing stages.

## Rules that are easy to miss

`acting` names the authority subject, not accounting or creator provenance.
Contained work uses its own principal and home; the caller's current scope and
SYSTEM processing authority answer different questions. Read admission does not
authorize filing, curation, navigation or independent repair.

Provider IO precedes final short read/publication transactions. Recheck retained
sources and targets before disclosure or vector publication. Pin exact embedding
profiles, input prefixes and source versions; a vector from a different space is
not interchangeable just because its dimension matches.

Filing content and provenance commits before indexing. Optional inference being
disabled or exhausted can leave valid content flat or unsearchable; authority,
cancellation or stale-source failures cannot authorize that fallback. Keep
uncertain equivalent attempts from silently retrying under a different phase.

Document reads do not disable explicit memory writes. Filing snapshots the run's
current input selections in the same transaction as the memory and its reason;
later document reads do not alter an earlier snapshot. Merges union dependencies.
The source log and receiving log are server-selected, not model-declared relevance
or independence. Authenticated recall, index and body reads require current access
to the retained inputs and propagate their restrictions into receiving runs before
disclosure. Unbound digests and Global promotion remain closed to restricted
memories. SYSTEM indexing validates the committed output separately and grants no
recipient read access. Legacy provenance remains fenced until completely captured.

Recall, filing, navigation/digests and repair have separate operation bindings and
budgets. A nearby conversation allowance does not automatically fund them. Count
actual requests under the owning policy and preserve retained usage evidence.

Conversation search uses [ConversationSearchRepository](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ConversationSearchRepository.java)
for authorized BM25 statistics, grouped lexical matches and batch refresh of retained
source references. The home remains an authority ceiling; an optional conversation
only narrows it. The agent tool resolves `conversation: "current"` from its run.
`PassageIndex.inConversation` carries that selection into ranking and coverage SQL.
Identical matching excerpts/passages (with whitespace normalized) group before
pagination/source limits. Semantic-only search reads reach counts without running
BM25. Exact ranking omits the redundant global distance sort; grouping numbers
occurrences once and aggregates only the representative and at most twenty
references rather than building unbounded duplicate arrays. Semantic entries honor
the active embedding profile's index policy. ANN reads
select a bounded authorized passage-key window in one query, then batch text
loading and full-precision scoring in a second query under the same deadline. Entry
ANN materializes eligible source IDs once per statement and hashes membership
inside the ordered vector scan. Only accepted keys join source hashes, avoiding
source/conversation PK probes for every rejected neighbor. This adds no cache and
retains the same candidate window and predicates.
Retained source revisions fence intervening edits or delete/reinsert; bounded in-memory stages choose one passage per source,
group excerpts and apply the result limit. Iterative scans retain scope/visibility
filters before the SQL candidate limit. Approximate recall is explicit and never
claims completeness; a filled passage window is truncated even when duplicates
leave an under-full result. Exact profiles retain exact ranking. ANN reads scope
iterative scans, `enable_sort=off` (revision-column equality is underestimated) and
`jit=off` to the short transaction, restoring caller settings on success and rolling
back on failure. Each group exposes at most twenty authorized source references. Snapshot refresh rechecks all retained revisions
in a batch and suppresses changed/moved/hidden references without filling later slots.
Offsets retain original slots; `total` extends through the last currently visible
slot (zero if none remain). Clients advance by `offset + limit` through sparse pages,
not by the number of returned hits.
New delegated research inputs carry explicit supplied-context spans and declared
source references. Default lexical statistics/snippets use the stored evidence
projection; semantic passage provenance is filtered before ranking and limits.
`includeInputs` opts into complete-input search and is part of snapshot identity.
Unclassified historical inputs remain searchable. Search source metadata caps
declared context references at one, with the complete count retained; provenance
never grants access to the original source. Metadata changes invalidate revisions
and the derived passage queue atomically. Skill handoffs retain typed provenance
and exclude derived search columns.

Passage SQL consumes the same injected `InformationLogVisibility` capability as
lexical/reach/source-refresh reads. Reuse its complete version-fenced denied-log
snapshot before candidate limits and coverage counts; do not recompute the graph
inside each passage query or create a second cache. Dirty/historical/oversized
snapshots retain the authoritative SQL predicate. A raced global version fence
after a successful cached callback discards its tentative result and permits one
pure authoritative query under its own SQL-unit budget, narrowed by any active
transaction deadline. SQL errors,
cancellations, expired work and mutations are not replayed. Callers that become
unsafe for sharing still fail closed; transaction holders never wait for an
authority seat while retaining embedding/disclosure locks.

`plowshare.retrieval.search-budget` defaults to 2s for individual queries and the
actual disclosure/profile/ranking transaction. Its nested statements share that
transaction's remaining budget. Independent auto-commit phases and provider
embedding do not share a hard search-workflow timer. Resolve the project once
within each SQL phase; resolve again after provider IO for freshness. Source
revision and final disclosure fences remain current. pgJDBC cancellation uses
the earlier of two seconds, an actual transaction's remaining budget and any
shorter configured timeout. Slow overall search/cache work produces only a
bounded singleton performance warning, with no job cancellation.

SQL query cancellation is `ReadFailedException.QUERY_CANCELLED`, not a lost
archive or capacity failure. Search maps it only after the owning transaction
unwinds. This preserves PostgreSQL rollback,
deadline cancellation, telemetry and earlier completed tool work without ending the
agent run over that cancelled search. It returns no partial window and does not
retry SQL, embeddings or previous tools. Genuine archive outages and uncertain
archive writes retain their existing failure contracts.

Lexical totals count groups; semantic totals describe a bounded snapshot. Inspect
coverage, fallback and capped references before claiming completeness.

## Bounded work

Required contract: each active recall, filing, indexing or repair unit needs a
configured positive finite deadline including queueing, waits, IO and result delivery.
Short read/control/acceptance phases use a 2-second default ceiling and normally
finish much sooner. Embedding, Scribe and digest work need their own appropriate
configured deadlines and call budgets, not a 2-second limit on the entire operation.
Retries and nested phases use the earlier remaining deadline, never a fresh deadline.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop new inference/effects, request owned cancellation and reject late
reads/vectors under current source, profile and operation fences. Keep physical
capacity accounted until work actually ends; retain already committed content.
Unknown writes keep original intent/receipt reconciliation, never automatic replay.
Current: [BoundMemoryQueries](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/BoundMemoryQueries.java) uses validity fences and call budgets;
these do not guarantee elapsed-time completion across provider IO and disclosure.

## Legacy traps

A null-project historical conversation is not Global learning authority. Global
knowledge, an ordinary project named `global` and a deployment directory differ.
Reminder surveys and navigation do not automatically increment memory-use counts;
follow the owning read path. A durable digest leaf can survive a parent summary
failure; do not discard it or claim restart recreates a lost worker closure.

## Checks

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.embedding.BoundMemoryQueriesTest' --tests 'io.aeyer.plowshare.server.embedding.BoundMemoryWritesTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.archive.MemoryQueryEntryPointsTest' --tests 'io.aeyer.plowshare.server.archive.MemoryWriteEntryPointsTest'
```

Use `archive/MemoryActingAxisTest` and the contained-memory gate tests for identity
changes. Keep repository behavior mocked unless SQL/locking/vector publication
requires PostgreSQL. [Memory digests](../../docs/memory-digests.md) explains the
separate budgets, fold archive, incomplete results and retention behavior.
