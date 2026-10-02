# Memory digests

The digest archive connects long-term summaries to learned memories and original
conversation spans. The learner still decides whether an experience warrants a
lesson. A fold enters the digest archive even when the learner extracts nothing.

## Use

- CLI: `plowshare memory navigate "why did we choose this?" [--project NAME]`.
- CLI: `plowshare memory digest [--project NAME]` starts a job; inspect it with the existing job tools.
- MCP: `memory_navigate(question, project?)` and `memory_digest(project?)`.
- Console: Memory → **navigate history** or **build digests**.
- HTTP: `POST /v1/memories/navigate` with `{ "question": "…", "project": "…" }`,
  or `POST /v1/memories/digest` with `{ "project": "…" }`. Omit project for global.

The interlocutor's internal `memory_navigate` tool accepts only a question. Its
run supplies the home; it searches that project before global. Navigation returns
text, source IDs, the level reached, completion status, and model-call count.
It can return a current lesson, original log entries, a surviving span summary,
or an explicitly incomplete higher digest. Retired memories are labelled as
historical. Partial source excerpts name the conversation and turn range needed
to read further.

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
| `plowshare.memory.navigation-budget` | `32` model calls |
| `plowshare.memory.digest-budget` | `100` model calls |
| `plowshare.llm.system-overrides.memory` | `fast` |

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
