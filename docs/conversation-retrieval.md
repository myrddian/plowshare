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
tool default to hybrid. HTTP and WS requests omitting `mode` are lexical requests with
the original matching syntax, scope, reach counts and response shape, and carry no
`retrieval` object while their total is exact. Explicit lexical mode always adds
retrieval metadata. When the lexical engine did not count every match (see
[stored lexical passages](#stored-lexical-passages-and-postings)), a lexical answer,
default or explicit, carries `retrieval` with `truncated: true`, `complete: false` and a
`totalMeaning` that says the total is a lower bound; the page, its order and its ranks
are still exact. With the default engine that happens only when more than 2,000 entries
match a question that is not one plain lexeme, and `total` is then the number of groups
among the 2,000 best, the same on every page. The MCP `conversation_search` tool reports
such a total as "at least N". Search never starts model-funded navigation automatically.

For semantic/hybrid pages, reuse the returned `retrieval.snapshot`, the exact
question, mode and scope with `--offset N`. Snapshots last five minutes; a restart
or eviction from the 128-window cache requires a fresh first page. The qualified
result window has at most 200 entries; `total` is one past the last currently
visible original snapshot slot, or zero if all results were withdrawn. It does
not count every semantically related entry. Source removal/movement/revision
suppresses invalid slots without shifting the remaining pages and marks the
result incomplete. Advance by the returned `offset + limit`, including after a
short or empty interior page, until that offset reaches `total`. Clear retained
hits if `total` becomes zero. Coverage is current; candidate identities/order are frozen.
Lexical paging remains offset paging without a snapshot. With the default engine, pages up to
`total` are stable even when the answer is marked truncated; matches ranked below the
2,000-entry grouping window are not reachable by paging. With `passages-pruned`, a truncated
`total` can be little more than the current page and is not the end of the results: keep
advancing the offset until a page comes back empty or short.

For example, the `conversation_search` tool pages the same hybrid snapshot with:

```json
{"question":"earlier retry decision","mode":"hybrid","offset":0,"limit":20}
{"question":"earlier retry decision","mode":"hybrid","snapshot":"<retrieval.snapshot from the first reply>","offset":20,"limit":20}
```

Keep the question, mode, home, conversation selector and `includeInputs` selection
the same. The tool allows up to twenty groups per page. Later pages recheck retained
source visibility/revisions and current coverage, but make no query embedding or
ANN candidate-selection call. Offsets paginate the bounded 200-group snapshot;
reaching its end returns an empty page. A truncated candidate window remains
labelled truncated on every page and does not promise exhaustive corpus pagination.

Each hit includes its conversation, entry/turn ordinal, kind, source length,
recorded time, supersession and any historical tool handle. New modes also name
the contributing retrieval lists, matched passage position and source hash.
Open `/trajectory CONVERSATION` or call the existing trajectory operation.
An agent can call `conversation_trajectory` with `conversation` and `ordinal`
for that exact bounded entry, or `conversation` and `handle` for a complete
historical tool result. Its home check is authoritative. A current-conversation
`result_read` cannot redeem arbitrary historical handles.

## Ranking, coverage and cost

Semantic mode embeds the question and uses the active embedding profile's search
policy. Indexed profiles select at most `min(2000, max(100, 10 * requested))`
authorized passage candidates, using the index's distance operator and representation.
Home, conversation, visibility, readiness, current-vector and supplied-context
filters apply before that candidate limit. Iterative ANN scans continue past
filtered rows within pgvector's configured scan bounds and the shared SQL deadline.

Passage ranking and coverage reuse the existing account-specific, version-fenced
log-visibility facts used by lexical/reach reads. A cache miss resolves the complete
denied-log graph once for that account/version. Ineligible, dirty, historical or
oversized snapshots use the authoritative SQL predicate. If a global visibility
version changes after a successful cached query, its tentative result is discarded
and one pure authoritative query may run under its own SQL-unit budget, narrowed
by any active caller transaction deadline.
SQL failures/cancellations are not retried; callers that become unsafe for sharing
still fail closed. Authorization still precedes the
candidate limit, and no result-window or scoring bounds are reduced.

For entry ANN search, the first statement materializes authorized source IDs once
and hashes membership inside the ordered vector scan. Source/hash joins then
apply only to accepted keys, avoiding repeated home/visibility joins for every
rejected neighbor. This is statement-local work, not another cache, and retains
the same candidate limit and visibility predicates.

Conversation entry search with `hnsw_vector` builds that same prefix in at most
four 500-key pages. `OFFSET` belongs inside the ordered candidate CTE. Each page
reacquires the captured profile, read disclosure and current project selection in
a fresh SQL transaction, and releases the connection before starting the next.
Existing caller transactions, direct ranking callers and other index policies
retain their single-window path and earlier transaction deadline. The surrounding
multi-transaction search has no two-second lifetime; slow workflows only warn.

Within each unit, the first SQL query returns only passage keys and source
revisions. A second, batched SQL query loads text and scores original vectors for
those keys, rechecking the source revision so an intervening edit or delete/reinsert
cannot reuse a stale candidate. Both queries share their actual ranking transaction's
remaining deadline. Bounded in-memory stages run once over all collected pages,
select the best passage per entry, collapse whitespace-equivalent excerpts with at
most twenty source references, and then limit the result groups.
Identical source/position keys count once. Conflicting source hashes or conflicting
revision tokens for the same source/position invalidate the whole result; distinct
positions have independent revision tokens. After the candidate transactions end,
a final guarded metadata-only transaction revalidates every retained key against
current source hashes, revisions, generation, profile, home and visibility. This
freshness read neither reruns ANN nor refetches text/vectors. A failed unit returns
no partial snapshot and triggers no retry or provider call. Snapshot hydration
still applies its separate current source fence before disclosure.

Cosine ranking retains the similarity floor of 0.35; other configured metrics do
not inherit a cosine threshold. ANN recall is approximate and duplicate-heavy
windows can contain fewer distinct groups. Source counts describe the retained
candidate window, not all matching history. Approximate searches report
`complete: false`; a filled passage window also reports `truncated: true`, even
when deduplication leaves fewer than 200 groups. Exact profiles retain exact
filtered ranking and grouping. No index policy or embedding model changes implicitly.

At most 200 lexical and 200 semantic groups enter
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

A cancelled search rolls back its active database transaction and returns a
recoverable tool failure instead of ending the agent run as an unreachable archive.
The model can continue with other retained evidence. The server returns no partial
search results and does not automatically retry SQL, embeddings or completed tools.
Deadline cancellation and diagnostics remain enabled; an actual archive connection
failure retains the existing fatal dependency policy.

Search spends a system query embedding and no conversation/model-descent
allowance. Reusing a snapshot makes no query embedding call. Background repair
also spends embeddings through the configured dispatcher/token ledger.
Navigation spends at most one query embedding plus descent model calls under
its existing independent system allowance, with `modelCalls` and embedding
attempts returned separately. Attempt counts do not imply that a failed request
was billed. These operations are not advertised as free.

## Stored lexical passages and postings

The legacy lexical search groups hits by a `ts_headline` excerpt, scores each match by
unpacking all of its lexemes, and reads one row per visible entry for corpus statistics. Its
cost therefore grows with the total size of matching entries, the number of matches and the
size of the archive. Two migrations add provider-free projections that remove those costs,
and `EntryStore.searchPassages` reads them. It is the served lexical engine: default and
explicit lexical requests and the BM25 half of hybrid search reach it through
`ConversationSearchRepository.searchBounded`.

`plowshare.retrieval.lexical-engine` selects the engine at startup. Any other value fails
startup; a change needs a restart and no schema change, and the projections stay maintained
under every value.

- `passages` (the default) counts every match: it takes the exhaustive form of each route
  below. A question that parses to one plain lexeme (one unquoted word, not repeated and
  not negated, that PostgreSQL stores as a single lexeme) has no window and is always
  exact. Any other question, including the same word twice (`common common`) and a
  compound token stored as several lexemes (`up-to-date`), is exact unless more than 2,000
  entries match it; then the answer is marked truncated and `total` and `sourceCount`
  count only the 2,000 best-scoring entries. That window is chosen by rank alone, so the
  total is the same on every page and offset pages up to it are consecutive,
  non-overlapping slices of one order.
- `passages-pruned` lets the search skip matches that cannot reach the requested page. It
  is faster on common words, and a truncated `total` is then a lower bound that can be
  little more than the page. Do not enable it until every client that pages by `total`
  reads `retrieval.truncated`. The MCP `conversation_search` tool does: it reports a
  truncated total as "at least N" and does not present it as the end of the results.
  Desktop's library search still stops at `total` and would end results early.
- `legacy` restores the original search, its headline grouping and its exact totals for
  every lexical request. It is the rollback lever if the passage search misbehaves on a
  deployment.

Full-input search (`includeInputs: true`) always
uses the legacy query, because stored passages are cut from evidence text and do not hold
supplied context. Semantic mode runs no lexical search.

`V277__entry_search_passages.sql`:

- A trigger on `entries` cuts each searchable entry's evidence text into consecutive
  passages of about 900 characters (at most 1,900) in the writing transaction. It stores
  character offsets, lexemes and a whitespace-insensitive key, not a copy of the text.
- A passage only chooses the excerpt and the duplicate group for an entry that is already
  authorized and matched. An entry without passage rows falls back to a whole-entry excerpt.
- Passages follow an entry whose key changes, by foreign key.

`V278__entry_search_terms.sql`:

- `entry_search_terms` holds one posting per distinct lexeme per searchable entry: its
  frequency, the entry's length and the passage that holds it most often. No score is
  stored, because BM25 statistics belong to the reader. The index orders one word's
  postings by frequency then length, the order every reader's score follows.
- `entry_search_conversations` gives each conversation a short number for its postings and
  holds its searched, ejected and recorded-only counts and total length. Corpus statistics
  and reach are sums of these rows over the visible conversations. The row is created with
  the conversation.
- `entry_search_term_counts` holds, for each lexeme and conversation, how many of that
  conversation's searchable entries contain it: the number of its postings there. A question
  word's document frequency is the sum of these rows over the visible conversations, which is
  exactly the count of its visible postings without reading them. The counts are per
  conversation, never global, so a statistic covers only what the reader may see.

How they are maintained:

- **Postings** are rewritten by a row trigger in the statement that writes the entry. They
  belong to one entry and are protected by that entry's row lock. The passage and posting
  triggers are fired only by an `UPDATE` whose `SET` list names content, role or provenance
  (or, for postings, the key), so a learning mark or a supersession does not run them and
  does not read the entry's text. No `BEFORE UPDATE` trigger on `entries` assigns those
  columns; one that did would have to be added to the lists, and a test checks the catalog
  for it.
- **Statistics and lexeme counts** are staged per statement and applied per commit. When a
  statement on `entries` ends, a statement trigger adds up what it changed for each
  conversation and leaves one row in `entry_search_pending`. At commit a deferred trigger
  takes everything the transaction has pending and applies it once per conversation, in
  ascending conversation number and, within a conversation, in lexeme order. A transaction
  therefore takes every entry row lock before its first statistics row lock, and every
  commit takes statistics rows in the same order. Updating the statistics inside the entry
  write deadlocked a fold (insert a summary, then update older entries) against a concurrent
  ejection; settling conversations in the order their statements ran deadlocked two
  transactions that had written the same two conversations in opposite orders, which they
  did not do before these tables existed (50 such deadlocks among 3,200 mixed transactions
  from eight sessions over three conversations, none once the order was fixed). The work at
  commit is proportional to the conversations and distinct lexemes a transaction touched,
  not to its entries. No application path writes entries of two conversations in one
  transaction today.
- Inside the writing transaction the postings are current and the counts are not; both
  become visible together at commit. `SET CONSTRAINTS ALL IMMEDIATE` keeps the numbers right
  and gives up the lock order for that transaction, as does deleting a conversation: either
  can still deadlock against a commit.
- A lexeme count that disagrees with the postings, or a statistic that would go negative,
  refuses the commit. A count that is too high is only found by a recount.
- What is pending belongs to the conversation's statistics row. A conversation deleted and
  created again under the same id in one transaction starts from nothing.
- `TRUNCATE entries CASCADE` empties the projections by `DELETE`, which leaves their pages
  to vacuum, and is allowed in a transaction that has written entries. A `TRUNCATE` that
  names or cascades to `conversations` is not: PostgreSQL refuses it with `cannot TRUNCATE
  "entry_search_pending" because it has pending trigger events` until the transaction has
  settled (`SET CONSTRAINTS ALL IMMEDIATE`). A transaction that has written no entries may
  truncate both.
- A single-word search reads its matches from the postings, so they are relied upon, not
  advisory.

The search takes one of three routes, and each returns exactly the page an exhaustive
search returns. The thresholds and pruning described here apply under `passages-pruned`;
under `passages` the first two routes read every posting and match instead:

- **One plain word.** It reads the shortest few postings of each frequency, scores them, and
  uses the score of the last group the page needs as a threshold. It then reads, as index
  ranges, the postings that contribute at least that threshold. When those few postings hold
  fewer groups than the page needs (a page deeper than the word's distinct groups, or a word
  whose entries are mostly copies) there is no threshold and every posting of the word is
  read: 541 ms for 150,000 postings, so the two-second budget is met near half a million.
- **Several words, pruned.** It reads the shortest postings of each word (a thirty-second of
  each word's postings per frequency, index-only), scores the best entries it finds exactly,
  checks them against the whole question and takes the score of the last group the page
  needs as a threshold. For a plain conjunction it then reads, for every word, the postings
  that could belong to an entry reaching the threshold and keeps entries found in all of
  them. For any other shape it reads, for each word, the postings contributing at least that
  word's proportional share of the threshold; an entry reaching the threshold must meet the
  share for at least one word. Candidates are scored from postings and those below the
  threshold dropped before any entry is read.
- **Several words, exhaustive.** Matches come from the GIN index, every match is scored from
  postings, and the best 2,000 choose a passage and are grouped. Used when pruning is not
  expected to pay: when few entries can match, when the window ends beyond 500 groups, for a
  conjunction of more than three words, when no threshold could be estimated, and for a
  negation combined with OR, parentheses or a phrase.

`BoundedLogSearch.truncated` is true when postings were skipped, candidates were dropped
below the threshold or matches fell outside the grouping window: under `passages-pruned`,
in practice whenever pruning engaged; under `passages`, only for the grouping window. The
total then counts groups among the entries read, which for a pruned search is little more
than the page, and a group's source count and references omit copies that were not read.
A phrase cut by a passage boundary still matches; its excerpt shows one side of the cut. A
window far beyond the answer is an empty page.

A truncated lexical answer says so on the wire. `retrieval.truncated` is true,
`retrieval.complete` is false and `retrieval.totalMeaning` names the total a lower bound,
including on a default request that would otherwise carry no `retrieval`. In hybrid mode the
flag joins the other reasons a candidate window is marked truncated.

Where an answer differs from the legacy search:

- **Grouping.** The legacy search groups hits whose 60-word headline reads the same; the
  passage search groups hits whose stored passage does. Entries that differ outside the
  headline are one group there and several here, so pages can differ even for short
  entries: on 3,000 short random entries a review found different pages for 54 of 600
  questions.
- **Ranks** of the same entry agree to nine decimal places, not bit for bit.
- **Totals.** The legacy search counts every matching group. The passage search counts the
  groups among at most the 2,000 best-scoring matching entries of a question that is not
  one plain lexeme, and under `passages-pruned` only among the entries it read; either way
  it marks the answer truncated.
- **Compound lexemes.** For a lexeme such as `up-to-date` the legacy query derives document
  frequency by parsing the lexeme again, which splits it and finds nothing; the passage
  search reads the stored count. The entries and their order were the same where measured,
  and the
  ranks lower.

The statement runs in a short read-only transaction with `SET LOCAL jit = off`. Its plan is
priced including the routes it does not take, so a long question (16 words at 30,000
entries) or a large archive (most questions at 200,000) crosses PostgreSQL's default
`jit_above_cost`, and compiling it cost about 100 ms for a statement that executes in tens
(124 ms against 16 ms). The setting ends with the transaction. The legacy search is not
changed.

Visibility is decided once per search and before anything is scored. One step joins the
conversations the reader may see (home, optional conversation, information policy) to their
statistics rows and reduces them to a single row: the sums that corpus statistics and reach
need, and a bitmap with one bit per conversation number. Every later visibility test reads
one bit at a row's conversation number. A hidden conversation has no bit, so it reaches no
statistic, threshold, count or page. Hidden postings are discarded where postings are read
and again where a candidate becomes a match; either check alone keeps hidden entries off
the page. The test is an expression over a value built once, so its cost and the plan do
not depend on how many conversations are visible or on what the planner estimates: an
earlier form (`IN` over the visible set) was only hashed while the set was estimated to fit
in `work_mem`, about 262,000 conversations, and beyond that scanned the set once per row.

### Measured, synthetic

400 conversations, entries of about fifty distinct words, two words in a half and a third of
all entries; first page of 20, median of nine runs through `EntryStore.searchPassages`,
PostgreSQL 16 in a container with default memory settings:

| Entries | One word | Two words AND | Two words OR | Three words AND | Three words OR |
| --- | --- | --- | --- | --- | --- |
| 25,000 | 16 ms | 22 ms | 23 ms | 34 ms | 55 ms |
| 100,000 | 18 ms | 33 ms | 36 ms | 72 ms | 276 ms |
| 200,000 | 19 ms | 43 ms | 46 ms | 116 ms | 483 ms |
| 200,000, exhaustive | 438 ms | 289 ms | 546 ms | 142 ms | 742 ms |

Visible conversations. 60,000 entries in five conversations (a skewed 300-word vocabulary,
entries of one to five hundred words), plus empty conversations to make 5, 10,000 and
300,000 visible to the reader; first page of 10, median of five executions of the statement
in `psql` with a custom plan and JIT off, same container. "Before" is the statement as
first written, which tested visibility with `IN` over the visible set; "after" builds the
set once as a bitmap:

| Visible conversations | Reader | One word, before / after | Two words AND, before / after | Two words OR, before / after |
| --- | --- | --- | --- | --- |
| 5 | global | 3.5 / 3.4 ms | 25 / 23 ms | 35 / 34 ms |
| 5 | project | 3.5 / 3.5 ms | 25 / 24 ms | 36 / 35 ms |
| 10,000 | global | 22 / 7.7 ms | 43 / 26 ms | 52 / 37 ms |
| 10,000 | project | 11 / 8.6 ms | 38 / 28 ms | 46 / 39 ms |
| 300,000 | global | 719 / 82 ms | 836 / 110 ms | 840 / 120 ms |
| 300,000 | project | 3,726 / 78 ms | cancelled at 40 s / 94 ms | cancelled at 40 s / 108 ms |

The project reader at 300,000 is where the planner estimated the set correctly and stopped
hashing it; the global reader's set was estimated at one row and stayed hashed by accident.

Known costs, none of which changes a result:

- **Visible conversations.** A cost linear in the conversations the reader may see remains,
  because corpus statistics, reach and the bitmap are one pass over one stored row per
  visible conversation and visibility is decided per search. In the table it is about
  0.26 ms per thousand visible conversations (78 to 82 ms at 300,000 against 3.4 ms at
  five), with PostgreSQL running that pass on two parallel workers; with parallel workers
  off the same search took 209 to 213 ms at 300,000, about 0.7 ms per thousand. A generic
  plan took 143 to 146 ms at 300,000, of which about 31 ms is a hash of every conversation's
  mapping row to translate hit numbers back to ids, which the planner chooses when it
  expects many hits. The bitmap itself is 125 kB per million conversation numbers, and it
  is as long as the highest visible number, not the count of visible conversations: numbers
  are global across homes, never reused and consumed by rolled-back inserts. Building it
  measured about 0.65 ms per million numbers (3.5 ms with the highest visible number at one
  million, 8.5 ms at ten million, about 70 ms at a hundred million, about 630 ms at a
  billion), and a reader narrowed to one recent conversation pays the same.
- **Several common words.** The sample that finds a threshold is a fixed share of each
  word's postings, so multi-word cost still grows with the archive, at about a tenth of a
  microsecond per posting. Three common words in a conjunction read most of their postings
  and were barely faster than the exhaustive route; three OR-ed were about a third faster.
  A conjunction of four was slower pruned (242 ms against 187 ms) and a conjunction at
  offset 5,000 much slower (479 ms against 184 ms), which is why both now take the
  exhaustive route.
- **Copies.** Grouping is exact, so every entry that ties with the last group on the page is
  read and joined to its passage: 100,000 identical top-scoring entries took about 470 ms.
- **Narrow readers.** Posting ranges and lexeme counts hold every conversation's rows; those
  the reader may not see are read and discarded. Nothing hidden reaches a statistic or a
  hit, but a reader scoped to a few conversations of very many pays for the rest.
- **Planner statistics.** With stale statistics the planner answered a probe for one
  entry's posting from the best-first index, walking every posting of the word per probe:
  4 to 15 seconds at 60,000 entries. The two indexes of the postings table are now built so
  that each read has one index that answers it through an index condition (the best-first
  index is partial and keyed under a different collation). What that enforces is that no
  other index can answer the probe; a sequential or bitmap scan of the postings remains
  legal and was not observed. The same searches take 3 to 56 ms in every planner state
  tested: statistics from when the archive held three entries, the new tables believed
  empty, no statistics, fresh statistics and a generic plan. Both migrations end with
  `ANALYZE`, and the new tables are analysed after 2% of their rows change, not 10%. One
  lookup is not protected this way: an entry by its key, because `entries` has other
  indexes that begin with the conversation. If the planner believes `entries` itself empty
  it may read a conversation's every entry through one of them; the legacy search has the
  same exposure.

Costs to check before adoption:

- **Size.** The postings are the largest object this search owns: 1.7 GB for 9.7 million
  postings at 200,000 of those entries, beside 330 MB of entries. The lexeme counts were 2.0
  million rows and 236 MB there. Their size depends on how many distinct words each
  conversation uses, so measure both on real text.
- **Writes.** With 128,000 short entries (ten to twenty words), writing an entry cost
  0.31 to 0.35 ms in bulk and 0.97 to 1.00 ms as a single-row statement in its own
  transaction, all triggers included; before the lexeme counts existed the single-row
  figure was 0.76 to 0.79 ms. An append adjusts one count row per distinct lexeme of the
  entry; the count is kept out of every index so most of those are heap-only updates.
  Entries of about fifty distinct words cost about 0.8 ms each in bulk.
- **Large entries.** The figures above are for short entries; the cost grows with the
  entry. A 463 kB tool result with 15,000 distinct lexemes took 269 to 281 ms to insert as a
  single-row statement against 60 ms with the three search triggers disabled: about 210 ms
  added, of which the posting trigger was about 100 ms, the passage trigger 71 to 75 ms and
  staging 8 ms inside the statement, the rest being the commit that adjusts 15,000 count
  rows.
- **Updates search does not read** (a learning mark, supersession). The two row triggers do
  not fire. The statement trigger still runs and compares each updated row's lexemes, old
  against new: about 0.04 ms per single-row statement and 0.001 ms per row in bulk for
  entries of fifteen words, and 0.34 ms per row for the 463 kB entries. Before the triggers
  were given column lists all three compared the stored text and the same update cost
  1.5 ms per row on those entries (0.002 ms per row on the short ones). An earlier figure
  here of 0.01 ms was measured on short entries only.
- **Bulk statements and long transactions.** One `INSERT ... SELECT` into one conversation
  took 1.5, 2.1, 4.4 and 8.7 seconds for 5,000, 10,000, 20,000 and 40,000 short entries
  (when counts were applied per entry it was 1.6, 5.0, 17.7 and 68.6 seconds), and 300,000
  entries across 40 conversations took 74 seconds. Twenty thousand single-row statements in
  one transaction committed in 0.2 seconds. A session that starts loading into empty tables
  keeps the plans its triggers made while they were empty until the tables are next
  analysed; one such load of 20,000 single-row statements took three times as long to write.
- **Migration.** Each migration loads bare tables and builds keys, indexes and foreign keys
  afterwards, holding a lock that blocks writes to `entries` (and, in V278, new
  conversations) until it commits. `statement_timeout` (15 and 30 minutes) bounds each
  statement, not the migration: V277 has two statements that grow with the archive and V278
  nine, so the permitted worst case is 30 minutes and four and a half hours. Rehearse both
  on a restored copy first and schedule the measured time as an outage for writes.

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

ANN index lifecycle remains owned by the embedding configuration and activation
fences. Conversation entries no longer override an indexed profile with exact
ranking. Query shape keeps the indexed operator as the candidate ordering, with
batched text loading, full-precision reranking and deduplication after the candidate bound.
See [pgvector's filtering and iterative-scan contract](https://github.com/pgvector/pgvector#iterative-index-scans).

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
interlocutor, Farnsworth and the conversation diagnostician. Interlocutor needs earlier discussion;
Farnsworth needs prior engineering evidence; the diagnostician needs historical runs for
diagnosis. Other agents do not receive automatic grants. Runtime schema/grant
checks and an actual scripted runtime loop validate availability and source use.

The diagnostician's independent diagnosis verifier also has scoped `conversation_trajectory`
reading so it can check cited historical entries without a workspace. It receives
neither search nor memory-navigation grants.


The two-second default is an SQL query/transaction limit, not a whole search
workflow lifetime. Provider embedding and query-owner cleanup execute outside
newly installed SQL scopes. Separate auto-commit phases resolve the home again
for freshness after provider IO. Slow overall searches warn through the bounded
performance observer and keep running.
