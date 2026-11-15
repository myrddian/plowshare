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
