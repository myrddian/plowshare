# Conversation search and memory navigation

These are explicit retrieval paths. Search finds retained historical text;
navigation follows digest summaries and provenance to lessons, source entries,
or surviving summaries. Retrieved history is evidence, never an instruction or
a new learned memory.

## Direct commands

```sh
bin/plowshare-talk conversation search --mode hybrid --project payments "earlier retry decision"
bin/plowshare-talk conversation search --mode lexical --global --json "E_BAD_TREE"
bin/plowshare-talk memory navigate --project payments "why did we change the retry policy?"
```

The terminal equivalents are `/conversation search ...` and `/memory navigate ...`.
Both use the same parser and typed WS operation handlers. TUI operations use the
signed-in connection and do not open/change a conversation or create an agent
turn. CLI command mode loads no Ink and needs no selected agent. Authentication
uses the existing `PLOWSHARE_HANDLE`, `PLOWSHARE_PASSWORD`, and `PLOWSHARE_URL`.
A password-change-required account must first finish that change interactively.

Scope defaults to `PLOWSHARE_PROJECT` in CLI, the current project in TUI, or global
when neither exists. `--global` overrides it; `--project NAME` selects one tier.
Search never merges global into a project. Navigation can fall back to global
and labels its actual tier. `--json` preserves the server result. Exit statuses:
0 complete result (including no matches), 1 service/authentication failure,
2 invalid command, 3 successful but incomplete retrieval.

Search modes are lexical, semantic, and hybrid. New commands and the internal
tool default to hybrid. HTTP, Java CLI/MCP and WS requests omitting `mode` keep
the original lexical matching, exact totals, scope, reach counts and response
shape. Explicit lexical mode adds retrieval metadata. Search never starts
model-funded navigation automatically.

For semantic/hybrid pages, reuse the returned `retrieval.snapshot`, the exact
question, mode and scope with `--offset N`. Snapshots last five minutes; a restart
or eviction from the 128-window cache requires a fresh first page. The qualified
result window has at most 200 entries; `total` counts original snapshot slots,
not every semantically related entry. Source removal/movement/revision suppresses
invalid slots without shifting the remaining pages and marks the result
incomplete. Coverage is current; candidate identities/order are frozen. Lexical
paging remains the existing exact offset paging.

Each hit includes its conversation, entry/turn ordinal, kind, source length,
recorded time, supersession and any historical tool handle. New modes also name
the contributing retrieval lists, matched passage position and source hash.
Open `/trajectory CONVERSATION` or call the existing trajectory operation.
An agent can call `conversation_trajectory` with `conversation` and `ordinal`
for that exact bounded entry, or `conversation` and `handle` for a complete
historical tool result. Its home check is authoritative. A current-conversation
`result_read` cannot redeem arbitrary historical handles.

## Ranking, coverage and cost

Semantic mode embeds the question and performs exact, home-filtered cosine
ranking. A similarity floor of 0.35 excludes weak candidates; it is an ordering
heuristic awaiting live-model calibration. The best passage is selected per
entry **before** the 201-candidate truncation check, so duplicate passages cannot
crowd out other entries. At most 200 lexical and 200 semantic entries enter
hybrid reciprocal rank fusion (`k=60`); the fused entry window is capped at 200
and ties use stable entry identity. Incomparable lexical/cosine scores are never
added. Snippets contain the matched passage, bounded to the existing 1,000
characters, even without shared query words. Scores are not probabilities.

`retrieval` reports requested/effective mode, bounded/exact total semantics,
truncation, completeness, generation, query embedding attempts, provenance and
fallback. `coverage` separates eligible nonblank retained entries, indexed
entries, published passages, pending entries, failed embeddings and stale digest seeds. Partial
passages are recorded progress but remain unsearchable until the source is
ready. Ejected and recorded-only exclusions remain in `reach`. An empty semantic
answer with an indexing backlog is incomplete. Semantic query embedding failure
is an explicit unavailability; hybrid can return lexical candidates with a
labelled incomplete fallback. Lexical search needs no inference service.

Search spends a system query embedding and no conversation/model-descent
allowance. Reusing a snapshot makes no query embedding call. Background repair
also spends embeddings through the configured dispatcher/token ledger.
Navigation spends at most one query embedding plus descent model calls under
its existing independent system allowance, with `modelCalls` and embedding
attempts returned separately. Attempt counts do not imply that a failed request
was billed. These operations are not advertised as free.

## Index lifecycle and operator repair

`V79__retrieval_passages.sql` creates a durable committed-source queue and derived
passages. Transactional triggers enqueue new eligible entries/digests and
invalidate passages on payload changes, ejection, deletion, digest revisions or
staleness. Supersession alone preserves historical text. Digest summaries remain
durable after original payload retention. Home joins are rechecked at retrieval;
source/provenance IDs never grant cross-home access.

The background worker starts after the application lifecycle starts and polls
with a two-second delay. Each pass handles at most eight sources and at most
16 passages per source, under configured token bounds and the snippet character
bound. Network calls hold no source transaction. Passage position, hash,
generation and progress survive restart. Publication locks the source then the
queue and checks revision/progress again. A delayed call cannot resurrect an
ejected/deleted source or overwrite another worker's progress. Transient failures
are recorded and retry after 30 seconds, exponentially backing off to one hour;
ordinary conversation writes never wait for embeddings.

Properties:

```yaml
plowshare:
  retrieval:
    generation: "1"
    worker-enabled: true
```

The effective generation includes `plowshare.llm.embedding-model`. **Stop older
workers, increment `plowshare.retrieval.generation`, and restart** when a model
behind an alias changes or passage/tokenizer rules change. Width checks are not
model identity. Old spaces are excluded immediately and the worker rebuilds
sources in resumable batches. The schema is fixed at 768 dimensions.

Inspect progress/failure reasons with:

```sql
SELECT source_type, status, generation, count(*) FROM retrieval_sources
GROUP BY source_type, status, generation;
SELECT source_type, source_id, next_position, attempts, retry_at, last_error
FROM retrieval_sources WHERE status <> 'ready' ORDER BY retry_at LIMIT 50;
```

After correcting an outage/misconfiguration, expedite retries:

```sql
UPDATE retrieval_sources SET retry_at=CURRENT_TIMESTAMP WHERE status='failed';
```

The queue backfills all existing eligible sources at migration and automatically
repairs missing digest vectors, including summaries that historically exceeded
byte/token limits. Newly written digest vectors carry their generation and can
be reused when the whole summary fits one bounded passage. Historical untagged
vectors are rebuilt rather than guessed compatible. Longer summaries are
indexed in passages. Stale/missing vectors retain structural navigation.

Exact ranking intentionally uses no ANN index: predicates precede ranking and
filtered recall is preserved. A controlled 10,000-passage/2,000-entry fixture
returned all 201 qualified scoped entry candidates in 48–49 ms on the test host.
This measures plumbing and scope, not real semantic quality or production SLA.
Larger deployments should measure their volume before introducing an ANN path.

## Navigation and grants

The navigator tries at most three unique, revision-checked semantic digest seeds
per tier, including descendants below roots, then at most three structural root
windows of eight nodes. All alternatives share the same allowance. Selection
accepts only IDs in the offered database membership and requires decreasing
depth; revision changes, cancellation, oversized fanout and exhausted allowance
return explicit incomplete/deepest-reached results. Coverage and fallback never
silently hide a missing-vector branch. Existing provenance and source limits
remain (40 entries, bounded source text). The distinction between learned
memory, verbatim historical entries and surviving summaries is preserved.

Search, navigation and historical evidence reading are explicitly granted to
interlocutor, Farnsworth and Daedalus. Interlocutor needs earlier discussion;
Farnsworth needs prior engineering evidence; Daedalus needs historical runs for
diagnosis. Other agents do not receive automatic grants. Runtime schema/grant
checks and an actual scripted runtime loop validate availability and source use.

Daedalus's independent diagnosis verifier also has scoped `conversation_trajectory`
reading so it can check cited historical entries without a workspace. It receives
neither search nor memory-navigation grants.

