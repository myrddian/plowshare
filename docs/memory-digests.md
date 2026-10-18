# Memory digests

The digest archive connects long-term summaries to learned memories and original
conversation spans. The learner still decides whether an experience warrants a
lesson. A fold enters the digest archive even when the learner extracts nothing.

## Shallow recall and automatic reminders

Existing `memory.recall`/HTTP recall, the `memory_recall` tool and eligible
automatic Reminder surveys use shared SYSTEM query embedding. The recipient still
needs current READ. Standalone permitted project reads can include Global;
newly admitted tools/reminders retain their home plus read-only Global knowledge.
Historical admissions keep their original home-only ceiling. Filtering applies before
ranking, missing-vector counts and use telemetry. Reminder surveys return index
lines without incrementing uses and retain their existing optional prompt behavior.

Spending follows the read service's `plowshare.embeddings.search-budget` policy:
unset/empty/`unlimited` is unlimited by default, `0` disables query inference, and
a positive integer limits calls per logical read. All admitted calls retain usage
and claim evidence. A parent conversation budget does not automatically apply.
These read permits do not authorize write/Scribe/curation, independent repair or
navigation/digest inference; those use their own operation bindings. See [slice 16](authority-substrate-slices/16-memory-query-embedding-and-recall.md)
for the supported consumer boundary.

## Memory filing and Scribe

Existing memory writes and the Learner's proposal-filing step use one admitted
SYSTEM operation for same-home candidate lookup, optional Scribe judgement and
write-time indexing. The caller needs WRITE for ordinary filing; SYSTEM processing
uses its own bounded authority. The Scribe remains a tool-free single consult.
Content, reason and learning provenance commit together. Indexing runs after that
transaction and can finish after creator access loss while its source and
operation remain valid. Changed, withdrawn or invalidated sources cannot receive
stale vectors.

`plowshare.archive.write-budget` (`PLOWSHARE_MEMORY_WRITE_BUDGET`) selects spending
for one filing operation. Omitted/empty/`unlimited` defaults to unlimited; `0`
disables its optional inference; a positive integer limits actual provider
requests across candidate embedding, the optional consult and indexing. Search
policy and nearby conversation budgets do not automatically apply. Learner's
preceding extraction call remains a separate existing operation.

Disabled or unavailable optional judgement files a valid proposal flat. Indexing
failure/exhaustion preserves committed content and may leave it unsearchable.
Authority, cancellation and stale-target failures cannot authorize flat filing.
A completed compatible legacy vector can be reused locally; dual DOCUMENT
vectors use their captured profiles/prefixes. Uncertain equivalent attempts are
not retried under another phase. Known retained invocations cannot obtain a new
source or duplicate the write. This adds no HTTP retry/idempotency API.

Old null-project conversation windows cannot silently become Global learning
publication. Curator/promotion, independent repair/reembed and digest/navigation
now use the shared foundation through their own bounded operations. Existing
human promotion acceptance still requires source MANAGER and Global ADMIN.
See [slice 18](authority-substrate-slices/18-deferred-scope-and-cutover.md) for
implementation scope, policy selection and verification.

## Use

- CLI: `plowshare memory navigate "why did we choose this?" [--project NAME]`.
- CLI: `plowshare memory digest [--project NAME]` starts a job; inspect it with the existing job tools.
- MCP: `memory_navigate(question, project?)` and `memory_digest(project?)`.
- Console: Memory → **navigate history** or **build digests**.
- WebSocket: `memory.navigate` with `{ "question": "…", "project": "…" }`,
  or `memory.digest` with `{ "project": "…" }`. Omit project for global.
  Current clients use these operations. The legacy HTTP endpoints under
  `/v1/memories/navigate` and `/v1/memories/digest` remain compatibility surfaces;
  clients do not silently fall back to them after a socket failure.

The interlocutor's internal `memory_navigate` tool accepts only a question. Its
run supplies the home; it searches that project before any captured Global
knowledge tier. GLOBAL_READ never permits historical NULL-home conversation
access. Navigation filters the underlying evidence before model input and disclosure. Navigation returns
text, source IDs, the level reached, completion status, and model-call count.
It can return a current lesson, original log entries, a surviving span summary,
or an explicitly incomplete higher digest. Retired memories are labelled as
historical. Partial source excerpts name the conversation and turn range needed
to read further.

A project digest job records its selected project on its durable job row. After
restart, that row retains attribution and counters; it does not recreate the
worker or grant permission to resume. Older null-project jobs retain their
original evidence because their lost closures cannot establish a project. The
archive Global tier is distinct from an ordinary project named `global` and from
the deployment directory.

## Fold and retention

Migration V29 installs an entry trigger: inserting a fold summary writes its
archive leaf and exact conversation/turn-span pointer in the same transaction.
A failed fold therefore leaves neither half committed. The existing fold hook
runs learning and then a digest pass, including when learning fails. Parent
summary or embedding failures leave the durable leaf intact for a later pass.
There is no scheduler.

Completed turns that have not folded are captured as labelled utterance/answer
excerpts with pointers to their original entries. Navigation, explicit builds,
and the retention coverage check capture these short conversations. Such leaves
may overlap a later fold; membership and source pointers are retained rather
than rewritten. Curator and memory-operation traces are excluded from this raw
turn capture to prevent the archive feeding on its own navigation transcripts.
Retention refuses to eject a conversation tree if any fold lacks its durable
archive copy. Current retention ejects tool-result payloads; utterances, answers,
and fold summaries remain readable. The digest copy also protects fold summaries
if that retention policy is expanded later. Missing legacy bytes cannot be
reconstructed by the migration.

Learner filing records source turn ordinals inside the same transaction as the
memory and its filing reason. Navigation does not increment use counters while
browsing summaries or log spans. Only the memory finally returned is read through
`Archive.read`, counting one use. Existing reminder injection, shallow recall,
survey, and memory eviction behaviour stay independent.

## Bounds and tree maintenance

Every operation has an independent, logged `memory` origin and owned model-call
budget. It is not a child delegation and never spends the caller's remainder.
Each navigation decision gets a fresh context containing the question and at
most eight branch summaries. The engine validates the selected ID and strictly
decreasing depth. Invalid output, cancellation, unavailable inference, or an
exhausted allowance returns an explicitly incomplete result. The navigator has
no document-search or other tools.

Configuration properties (restart required):

| Property | Default |
| --- | --- |
| `plowshare.memory.navigation-budget` | `unlimited` |
| `plowshare.memory.digest-budget` | `unlimited` |
| `plowshare.llm.system-overrides.memory` | `fast` |

The spending keys accept `unlimited`, `0` (disabled), or a positive finite limit.
Query embedding, branch decisions and digest vector work spend the owning
navigation/digest policy. An unrelated conversation cap does not apply.
Independent reembed/maintenance uses `plowshare.embeddings.maintenance-budget`,
also unlimited by default. Curator uses `plowshare.agents.curator-budget` with
the same syntax unless its caller explicitly selects a finite request cap.

Memory is harness work and is bound with the rest of it. The digester and the
navigator run on `plowshare.llm.system-overrides.memory`, which falls back to
the plain `plowshare.llm.system` binding when it is unset; it ships pointed at
`fast` because the shipped memory prompts measure badly on the large reasoning
model that `system` defaults to. `plowshare.memory.model` is retired: setting it now fails startup with a
message naming its replacement, rather than binding a key nothing reads.

Grouping uses deterministic ID order, up to eight children per parent, carrying
singletons without paraphrasing. Only child summaries enter parent prompts.
Membership is immutable, permits mixed depths, and never crosses a home. Memory
changes mark only that leaf and its ancestors stale. A pass repairs bottom-up,
retaining previous summaries in `digest_revisions`; revision checks prevent a
concurrent change from being cleared by an outdated repair.

This first implementation does not balance incremental trees or cluster by
semantic similarity. Repeated small updates can deepen the tree; the independent
allowance still bounds navigation and reports where it stopped. The navigator now tries up to three semantic seeds, then three bounded windows
of eight uncombined roots, and reports any truncation/build-needed condition.
Generation-tagged digest vectors are consumed by a shared passage index; historic
missing/untagged vectors and long summaries are repaired in resumable batches.
Structural navigation remains available with labelled reduced seed coverage.
See [conversation retrieval](conversation-retrieval.md) for direct TypeScript
commands, cost, coverage and operator repair.

## Verification

Database tests exercise migration, fold rollback, provenance rollback, stale
ancestors, concurrent repair protection, home boundaries, arbitrary depth,
short conversations, retention refusal, ejected-source fallback, cancellation,
and accounting. Model-adapter tests check independent traces and fresh contexts;
client and console tests cover the new interfaces. Tests use controlled model
responses and disposable PostgreSQL/pgvector containers. Retrieval quality and
latency with a live model have not been benchmarked.
