# Knowledge, evidence and research

## Use the right kind of retained knowledge

Plowshare offers several related stores with different purposes. Your Personal
wiki or project knowledge base can combine them without pretending they are one
interchangeable memory object.

| Kind | Best for | Identity and limits |
| --- | --- | --- |
| Workspace Markdown | Editable notes, plans and wiki pages | File path and file permissions; not automatically evidence |
| Information source | Retained reference text or acquired web content | Named resource and immutable revision |
| Evidence quotation | Exact support for a claim | Revision, retained UTF-16 offsets and quote |
| Research report | Structured findings and supporting inputs | Draft/final revision, evidence links and producer lineage |
| Memory | Learned lesson or explicit knowledge proposal | Provenance, tier, review and lifecycle |
| Conversation/digest | The history of how work and decisions developed | Original entries, source spans and bounded summaries |

The Personal starter structure gives ordinary files a useful home: incoming work,
outgoing artifacts, Resources, Archive, Planning and Bots. Starter skills can help
capture and maintain knowledge when explicitly invoked and granted. These
conventions are built on existing project and file primitives; they do not make
every note an automatically published source.

## Retain and prepare a source

Paste text through the Library or information upload operation. A public URL uses
durable acquisition and returns an acquisition ticket before the retained revision
is necessarily ready. Inspect the ticket or use information await with its actual
reference; a fetch receipt is not proof that extraction succeeded.

Processing has capability-specific stages: extraction, derivation, passage
embedding, summaries, summary embedding, automatic tags and tag groups.
Retained text may be readable before summaries are complete. A failed model stage
does not justify fetching or overwriting the source again. Retry/rebuild have
different scopes; inspect status and the captured allowance first.

A resource can have many revisions. Replacing text creates a new revision so old
citations keep their original coordinates. A source's bytes, text hash, media type,
converter and origin metadata explain what was retained. Unsupported or missing
extraction is visible rather than represented by a blank successful document.

## Read, retrieve and ask

Direct information reads return bounded windows with start/end offsets. Use those
offsets for the next window or exact evidence recording; bytes, Unicode code
points and UTF-16 offsets are not interchangeable units.

Lexical filtering searches retained text/name/title/tags without requiring a
semantic vector. Passage search and summary ranking have their own model and
projection requirements. Configuration fingerprints keep vectors from incompatible
embedding spaces out of semantic retrieval. A missing or incompatible projection
must remain a visible readiness limit.

Asking a document question invokes a bounded deliberation workflow using that
document's corpus. It consumes model work and returns a job/result. Reading its
answer is not independent factual verification; examine the cited retained
evidence and any explicit gaps.

## Evidence and report provenance

Evidence records require the actual retained quote at the supplied offsets. An
original URL, paragraph label or a claim written from memory is insufficient.
Evidence coordinates establish what text was quoted. They do not prove that the
source is reliable or that the quote supports the conclusion.

Reports retain their inputs, evidence citations and findings. Findings can be
`holds`, `weakened`, `refuted` or `not_checked`; missing assessment stays a gap.
The producing run's server identity and pinned workflow source provide lineage.
Models cannot forge the producer or silently share/finalise a draft.

The information policy records documents consumed by a run, including uncited
inputs, and propagates those dependencies into derived outputs. Access is
rechecked when reading, following or delivering that work. Removing a collection
link does not delete evidence or widen an audience.

## Share intentionally and preserve evidence

Personal, project and `global_read` are information selections. A project selection
requires current scoped READ. Personal and project searches include generally readable
knowledge by default; set `includeGlobal:false` to narrow to the selected home.
`global_read` selects only resources with the current GLOBAL_READ audience, wherever
they are held. The explicit Global account selection instead selects Global custody.

A resource's current root policy governs all revisions, including pinned history.
The existing share command lets a Manager choose GLOBAL_READ: every active
authenticated account, Application and service credential is eligible to read it.
Unshare removes that audience from every revision. Old Shared flags grant no access;
formerly compatible Shared roots narrow to HOME for deliberate review. Project
Contributors can write in their area but cannot change permissions. Tags and
finalisation do not grant an audience. Private inputs, retained references and
mandatory restrictions still constrain disclosure.

Service credentials use the same general-read defaults; they cannot select Personal.
Retained runs also intersect every read with their captured home/Global READ ceiling.
Global-account
policy management requires a human Global Admin. This does not add Global creation
or execution operations.

Exclusion removes a revision from normal discovery while preserving authorized
direct evidence reads. Withdrawal blocks content and dependent output reads.
Deletion retains appropriate metadata but removes content according to lifecycle
policy. Neither action is interchangeable with unsharing or unlinking.

The manual installer uses these ordinary operations. It does not introduce an
exception to document authorization. Its `--share` publishes intentionally
non-private manual chapters through the same root-audience checks; their home and
creator provenance stay separate from that audience.

## Deep research

`deep_research` is a shipped JavaScript orchestration. It prepares objectives,
decomposes questions, reviews typed queries, acquires evidence, runs analytical and
adversarial assessments, revises findings, expands accepted prose and retains a
cited draft report. Its detailed guide describes request shape, stages, repair
bounds, partial results and inspection.

Use a calling bot/agent actually granted the workflow. Give it an answerable topic,
scope and explicit expectations. Check search provider availability and budget
before starting large work. The source processing allowance and delegated research
allowance are separate owned work; do not assume one small caller budget caps all
downstream document preparation.

Inspect the report and the orchestration audit. A draft can be readable without
being finalised or shared. Source failures, unavailable assessments and incomplete
editorial expansion should stay explicit in the report. A polished paragraph is
not a substitute for the audit or counter-evidence.

## Memory and history

Memory recall retrieves useful retained items; memory read fetches an actual item.
Reading a document does not disable memory writes. A run's memory proposals retain
an atomic snapshot of the document inputs available when they are filed. This
allows operational lessons to be saved without claiming that an agent can safely
declare its own output independent of those inputs. Later document reads do not
add restrictions to earlier memories; merging a proposal unions its dependencies.

Recall, index summaries and full reads require the reader's current access to all
retained source selections and dependencies, as well as access to the memory's
home. Revoking source access or withdrawing a source also blocks the dependent
memory. When an agent receives a memory, its restrictions enter that run's input
ledger before disclosure. Unbound digests and Global promotion cannot publish
source-restricted memories. SYSTEM indexing does not grant anyone read access.

Writing or resolving proposals and curating knowledge have their own ownership
and model-work contracts. Confident curator recommendations remain pending
proposals. Existing acceptance checks the authenticated caller's source Manager
release and human Global Admin destination authority; the supplied `by` label is
provenance. Memories carry formation provenance and lifecycle status. Historical or invalidated knowledge should be labelled rather than
returned as current advice.

Conversation retrieval searches retained history with bounded coverage. The agent's
`conversation_search` tool searches its current home by default. Set
`conversation: "current"` to search only the running conversation, or supply a
specific conversation ID in that home. The WebSocket `conversation.search` and
CLI `search` accept an explicit `conversation` ID alongside the project selector.
The filter applies before lexical statistics, semantic ranking and candidate limits.

Lexical search uses BM25 with term frequency saturation, document length
normalization and inverse document frequency from the authorized selection.
Repeated matching excerpts are grouped before pagination; semantic retrieval
also groups identical passages across entries and conversations before its source
limit. Whitespace differences are normalized for grouping; different wording is
not treated as a duplicate. A group retains its representative evidence address
and up to twenty `sources`, with conversation IDs, ordinals, revision hashes and
available tool-result handles. `total` counts groups. Lexical `sourceCount` counts
matching occurrences; both are lower bounds when the answer is marked truncated. Semantic
snapshots report the retained references that were revalidated, and mark coverage
incomplete when the reference list is capped.

Delegated research now separates task instructions from explicitly supplied context.
Default conversation search excludes that context from lexical statistics and
matching, and removes its semantic passages before ranking and candidate limits.
The complete input remains in the trajectory. This selection does not hide first
messages, harness deliveries or generated answers as a class. Historical inputs
without explicit provenance remain searchable and unclassified; text patterns are
not sufficient evidence for an automatic backfill.

Set `includeInputs: true` to search the full recorded inputs when debugging. Repeat
that selection on every snapshot page; a snapshot cannot switch selections. Each
source's optional `inputProvenance` identifies the supplied span using zero-based,
end-exclusive Unicode code-point offsets. Its `sources` contains at most one declared
evidence reference and `sourceCount` reports the full reference count retained with
the input. These references are navigation hints, not access grants or independent
corroboration. Read original sources through their authorized owning APIs.

```json
{"question":"capacity failure","mode":"hybrid","includeInputs":true}
```

`plowshare.retrieval.lexical-engine` selects the lexical conversation search: `passages`
(default), `passages-pruned`, or `legacy`, the original search, kept as a rollback; any
other value fails startup. `passages` gives exact `total` and `sourceCount` values for a
question of one plain lexeme, and for any other question unless more than 2,000 entries
match it; the answer then reports `retrieval.truncated: true` and counts the groups among
the 2,000 best, with stable paging up to that total. `passages-pruned` trades exact totals
for speed: a truncated total can be little more than the page. The MCP tool reports a
truncated total as "at least N"; Desktop still pages by `total`, so leave
`passages-pruned` off until it reads `retrieval.truncated`.

Conversation search uses `plowshare.retrieval.search-budget` (default `2s`, positive
and at most `1m`). The operation inherits an earlier cache/admission deadline; its
lexical, passage, coverage and source-refresh SQL share the remaining budget rather
than receiving a new allowance per query. Project selection is resolved once under that
deadline and reused within the operation, independently of current authority checks.
JDBC cancellation uses whole seconds,
rounded down; a new statement is refused if less than one cancellable second remains.
Expiry is unavailable, never an empty successful result or permission to replay work.
Query embedding/provider work retains its own configured finite transport budgets;
late results are withheld and cannot start another SQL stage after search expiry.

Use `mode: "semantic"` for meaning-based retrieval or `mode: "hybrid"` (the agent
tool's default) to combine BM25 and semantic rankings. Semantic/hybrid pagination
requires the first page's snapshot and the same conversation selection. It never
reuses a snapshot across homes, readers or queries. References are checked again
before disclosure; deleted, moved or newly unreadable sources are suppressed.
Embedding failures keep explicit lexical fallback in hybrid mode; semantic mode
reports unavailable. Source indexing is asynchronous, so inspect coverage before
claiming that no evidence exists.

```json
{"question":"why did the tool fail?","conversation":"current","mode":"semantic"}
```
 Digest
navigation walks summaries and source spans to explain why a decision was made.
A digest preserves lineage to the original history; it is not a second source
invented by paraphrasing. A fold reduces future context pressure while leaving
the original record available subject to its retention policy.
