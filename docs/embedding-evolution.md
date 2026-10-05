# Embedding spaces and re-embedding

Status: the two-slot schema, validated configuration, model dispatch, durable
all-store repair, index administration and retrieval cutover are implemented.
The transition is explicitly enabled with `plowshare.embeddings.enabled: true`;
legacy deployments remain opt-out, but require an explicit pinned tokenizer to embed or derive documents.
Applying migrations alone does not attribute or re-embed legacy vectors. Deployment status is recorded separately from this architecture document.

## Input tokenization and shared chunks

The chat context meter may estimate tokens. Embedding chunking and submission
must never use that estimate: a margin cannot turn an estimate into a hard bound.
Each named model requires `tokenizer.file`, an explicit absolute path to its
matching Hugging Face `tokenizer.json`, and `tokenizer.sha256`, the SHA-256 of
those exact bytes. Supply the artifact from the served model's pinned revision,
mount it read-only, and verify its behavior against the loaded encoder before
activation. The server loads local bytes only; it does not fetch models or
substitute another tokenizer when an artifact is absent or its checksum differs.
The native tokenizer runtime is packaged with the server dependency.

Counting explicitly disables padding and truncation, including truncation
settings embedded in the artifact, and includes the model's special tokens.
Each submission counts the complete query/document prefix plus source input
with the selected encoder. Unknown encoder revisions, estimated counts and
overflow are refused before dispatch. A valid output vector does not establish
that an endpoint processed the entire input; operators must match the served
encoder/tokenizer and configure its actual loaded input limit.

Documents retain their original source bytes. Prose splits at sentence boundaries
and splits oversized runs further; code keeps contiguous slices and prefers line
boundaries. The configured chunk target is soft for prose sentences, while the
maximum is always hard. Shared chunks count both complete document inputs and
use the smaller input allowance, so every chunk fits both encoders. This may be
conservative for models with very different limits, but preserves one chunk with
two independently identified vectors. Query limits remain model-specific.
Final slices are rechecked after boundary adjustment because token counts need
not increase monotonically with prefix length.

Legacy document ingestion, information-document processing and conversation
passage indexing use this same capability. Derivation and passage-generation
fingerprints include tokenizer checksums, document prefixes and input limits.
Existing information revisions preserve immutable derivations: changed chunking
requires new revisions from retained source, preserving historical citations.
An embedding-only rebuild does not silently re-chunk old revisions; submission
refuses old chunks that no longer fit the configured encoder. Conversation
passages are replaceable projections and rebuild under the changed generation.
Other embedding stores and oversized queries are refused explicitly; they are
not silently shortened or averaged into a different representation.

For a rolling encoder replacement, retain counters for old serving generations
using `plowshare.embeddings.retained-tokenizers`: entries contain `model-id`,
`model-revision` and the same `tokenizer.file`/`tokenizer.sha256` fields. Startup
requires a matching counter for any existing active space. Remove retained
artifacts only after the old serving space is no longer active.

Legacy mode requires `plowshare.llm.embedding-tokenizer.file` and `.sha256`.
A missing legacy counter permits server startup and non-embedding operations,
but refuses embedding/derivation with an actionable configuration error. It
never falls back to the chat ratio estimate. The dual configuration example is
[application-dual-embeddings.example.yml](../bin/application-dual-embeddings.example.yml).

## Implemented SQL foundation

All five embedding stores now have unconstrained `code_embedding` and
`prose_embedding` columns, with space IDs and source revisions. An immutable
registry fingerprints model ID/revision, dimensions, query/document prefixes,
pooling, normalization and reduction. It contains no model defaults, endpoints or
credentials. Code and prose slot pointers start unset. Legacy vectors and their
existing indexes remain intact and are not attributed to a new model. The legacy
passage vector becomes nullable, allowing a source to be committed before either
new embedding exists without supplying a dummy 768-dimensional vector.

The source guard assigns a new database-wide freshness token and clears both
slots when source content changes. Unrelated metadata and publication of one slot preserve the
other slot. Source edits and replacement publication must use separate writes;
combining them rejects the write rather than silently discarding a supplied
vector. New source creations and changes receive unique sequence tokens,
including deletion and recreation under the same ID. Existing rows retain zero
until a source edit, avoiding a full-table data rewrite during upgrade. Recreated
sources never receive zero. Sequence gaps from rollback are expected; tokens are
not edit counts. Vector/space/revision fields must be present together, match the
registered dimensions and current source revision, and satisfy the nonzero and
normalization contract. Document summary slots cannot be populated without a
summary. Memory body edits conservatively invalidate both representations even
though the legacy memory embedder currently consumes summary and scope.

Each store has replacement staging with actual source foreign keys and keys
including source, slot, space and source revision. Stage publication locks the
slot target before its source, verifies both snapshots and validates the vector.
Source deletion cascades staging deletion; source edits may leave old staged
revisions for diagnosis, but those revisions cannot be published to active rows.
Configuration updates advance a version for compare-and-set writes. Triggers do
not activate slots: `JdbcEmbeddingWorkRepository` reconciles all five stores,
copies current staged vectors, builds the requested indexes and changes the
active pointer in one version-fenced transaction. `V116__embedding_runtime.sql`
adds permanent policy/input-bound/retry metadata and permits source-fenced
staging for either the current serving or replacement space.

The typed `EmbeddingSpaceRepository` registers exact descriptors idempotently;
SQL owns canonical SHA-256 generation and prevents descriptor mutation. Its
contract does not grant access to source content, bypass source ownership or
start embedding calls. Registry registration neither selects nor activates a
model. The focused `EmbeddingSchemaTest` upgrades a database containing legacy
vectors, exercises every store and tests source-write/publication locking.

## Legacy compatibility

The legacy runtime's default embedding width is 768. Changing `LLM_EMBEDDING_DIM`
does not migrate its stored vectors. Five shipped migrations fix the legacy width: memories in
`V1__memories.sql`, document chunks in `V18__documents.sql`, document summaries in
`V27__document_summary_embedding.sql`, memory digests in `V29__memory_digests.sql`
and retrieval passages in `V79__retrieval_passages.sql`. The opt-out passage path still validates exactly 768 dimensions. The dual path
commits passage text without a legacy vector, then repairs both named slots.
Shipped migrations remain immutable.

There is already partial model isolation. `InformationConfiguration.embedding`
fingerprints the model specifier and width, and information revision processing
records stage fingerprints. `DocumentStore.embeddingSpace` uses those
fingerprints when selecting compatible information-derived results. This is
useful infrastructure to reuse, but it is not a universal embedding-space
registry covering every stored vector and retrieval path. Passage retrieval also
uses a generation formed from the model specifier and an operator-controlled
`plowshare.retrieval.generation`; digest vectors retain `embedding_generation`.
These already prevent some stale-generation reuse and should be migrated rather
than discarded. A model specifier
alone also cannot identify changed weights served under the same name.

The opt-out `DispatchingEmbeddingClient` still snapshots one model/width.
`DispatchingDualEmbeddings` applies the explicitly configured query or document
prefix once, bounds the final input with the encoder's pinned, untruncated tokenizer and validates every
returned vector. It applies L2 normalization when declared. Pooling, weights and
reduction describe the operator's served encoder; the server never silently
truncates output or infers preprocessing from a model name. Endpoint-side
preprocessing has not been inspected. An endpoint alias such as `nomic-embed-text` does not establish which upstream
model revision is served. The operator must now select the model explicitly;
the packaged single-pool configuration supplies no default model ID.

The problem is embedding-space identity and a safe transition between spaces,
not merely a larger numeric dimension. Equal dimensions do not make vectors
from different models or preprocessing conventions comparable.

Source references:

- [Embedding configuration](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/LlmProperties.java)
- [Embedding dispatch](../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/DispatchingEmbeddingClient.java)
- [Information fingerprints](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationConfiguration.java)
- [Document compatibility selection](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/DocumentStore.java)
- [Retrieval generation configuration](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/RetrievalIndexConfig.java)
- [Passage indexing](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/PassageIndex.java)
- [Passage persistence](../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcPassageRepository.java)

## Runtime contracts

Support exactly two active embedding slots: `code` and `prose`. Every unit stored
for embedding receives both vectors, irrespective of whether its content is
code, prose or a mixture. This applies to every existing embedding store:
memories, memory digests, document chunks, document summaries and retrieval
passages. Code stored in the document store uses the same contract as any other
document; content classification does not determine which vectors are created.

Each slot is a typed pair of embedding-space identity and vector, also bound to
the source revision/hash. Track readiness and failures independently for each
slot. Missing or failed vectors do not discard the source or count as successful
coverage. Creating or editing content schedules both slots; repairing one slot
does not recompute the other when its space and source revision still match.
Budget embedding work, storage and index maintenance for both representations.

Define an immutable embedding-space descriptor with a stable identifier,
model identity and revision/weights identity when available, output dimension,
query and document preprocessing, normalization convention and any supported
output-dimension reduction. A friendly endpoint model alias is not sufficient
provenance. Do not include credentials or deployment URLs in space identity.

Separate query and document embedding purposes in a typed capability. Apply
model-specific preprocessing once at that boundary. Bound the final transformed
input against the model's input allowance and validate width and finite values
before persistence. Never infer a prefix from a substring in the model's name.
Keep chunking/tokenization and extraction fingerprints as derivation identities
rather than conflating all processing configuration with vector-space identity.

Every stored vector must carry a space identity. Inventory memories, digests,
passages, chunks and summaries, including legacy rows without information
revision metadata. Every vector comparison must use the same selected space as
the query vector. Authorization, project/account fences and source visibility
continue to apply independently of space selection.

Search contracts carry an explicit typed slot choice: `code` or `prose`. A code
search embeds its query using the active code space and compares only the code
column; a prose search uses the active prose space and only the prose column.
This remains true for natural-language questions about code and for mixed
documents. Do not classify the query to choose a slot or automatically search
and merge both slots. The calling capability selects the slot explicitly.

Active space selection is configured per named slot and shared across all owning
stores. A query captures the selected slot's active space for its whole
operation; an activation racing that query must not switch its query model or
candidate vectors halfway through. Missing coverage must be explicit. Preserve
documented lexical retrieval where applicable, but never silently substitute
the other slot or compare vectors from another space. Repository SQL chooses
column identifiers through a code-owned allowlist for the typed slot.

## Storage and indexes

### Fixed layout and configurable retrieval menu

The permanent layout is two unconstrained, full-precision `vector` columns with
space IDs and source tokens, plus the existing staging tables. Any integer width
from 1 to 16,000 is valid; widths are not restricted to a list of popular models
and vectors are never padded to a common size. Changing models, widths, prefixes,
pooling or normalization registers a new space and rebuilds the affected slot,
without changing table columns or adding a model-specific Flyway migration.

Keep index policy separate from model-space identity. Each slot can choose one
of the following fixed modes, validated by `EmbeddingSearchPolicy`:

| Mode | Maximum dense-source dimensions | Candidate representation |
| --- | ---: | --- |
| `exact` | 16,000 | Original full-precision vector |
| `hnsw_vector` | 2,000 | Full precision |
| `hnsw_half` | 4,000 | Half precision |
| `hnsw_binary` | 16,000 | Binary quantization, Hamming distance |
| `ivfflat_vector` | 2,000 | Full precision |
| `ivfflat_half` | 4,000 | Half precision |
| `ivfflat_binary` | 16,000 | Binary quantization, Hamming distance |

Final dense ranking supports cosine, L2, inner product and L1. Dense IVFFlat
modes reject L1, which their pgvector operator classes do not support. Half and
binary modes must rerank candidates with the original full-precision vector and
selected dense metric. Reranking improves candidate ordering; it cannot recover
a relevant record the approximate candidate search omitted. Half conversion
must also reject values that cannot be represented; preserving the original
vector does not make every finite float32 value representable as float16.
[pgvector indexing and quantization](https://github.com/pgvector/pgvector)

For example, 768-, 1,024-, 1,536-, 2,048-, 3,072-, 4,096-, 8,192- and
16,000-dimensional models all fit the same tables. A 4,096-dimensional model can
use exact search or a binary candidate index at full output width; it cannot
use a full-width half-precision ANN index. Binary index limits reach 64,000
bits, but this menu is bounded by the retained dense vector's 16,000 dimensions.
No mode silently selects another mode, truncates output or changes model identity.

Stable tables do not mean zero database DDL. Approximate expression/partial
indexes must be created or rebuilt for the selected model, width and metric.
The repository-owned index manager performs that controlled operation
from the fixed allowlist; it must not require hand-written table migrations or
accept caller-provided SQL. Exact search requires no new ANN index. Changing
index policy rebuilds indexes, not embeddings; changing model-space identity
requires re-embedding. Index readiness and failure remain explicit during a
policy transition; queries must never silently fall back to another mode.

The fixed menu, configuration binding, index administration and query paths are
implemented. `EmbeddingSchemaTest` builds each representation at its maximum
supported dense width and checks planner/index compatibility. The focused
`EmbeddingRuntimeRepositoryTest` exercises all 26 supported mode/metric
combinations, code/prose selection, private-resource filtering, full-precision
reranking, source edits, restart/backoff and activation fencing. ANN candidates
are bounded to `min(2000, max(100, 10 * requested))`; final ranking uses original
vectors. Exact passage search deduplicates sources before its result bound.
IVFFlat lists are selected from current corpus size (at least one), and training
occurs after target publication. A focused test also forces index use after the
first insert into a store whose IVFFlat index was created while empty. Tests use
pgvector 0.8.7 on PostgreSQL 16.
Approximate filtering can reduce recall; authorization still applies before the
SQL result limit. Distance and `1 - distance` are ordering aids, not calibrated
probabilities; stance comparisons retain their explicitly cosine-based meaning.

### Physical storage and index constraints

Use explicit `code_embedding` / `code_space_id` and `prose_embedding` /
`prose_space_id` columns for the active representations in each owning store,
with source-revision binding and per-slot processing state. Both space IDs refer
to one shared immutable registry. Keep source ownership in its existing store;
do not create a second document/archive ownership system or five disconnected
registries. The slots may have different dimensions and preprocessing contracts.

Replacement generations require staging storage keyed by owning source,
revision, slot and target space. Keeping staged vectors separate from active
columns allows a background rebuild without partially overwriting the serving
generation. Retained generations support rollback; they are not additional
query slots. Final staging and atomic activation mechanics require verification
against the size and concurrency of all affected stores.

An unconstrained `vector` column stores the full model output up to pgvector's
current **16,000-dimensional storage limit**. This is a maximum, not padding:
each space declares the exact expected width, and shorter/longer outputs are
rejected. V115 uses this layout in both slots and all staging tables, with space
filter indexes. It does not create new ANN indexes or truncate stored vectors.
Per-space expression/partial ANN indexes are a supported pgvector option.
Queries must filter the indexed space and apply the
same dimension cast; repository-owned, allowlisted index definitions must never
interpolate caller SQL. Query text and vectors remain bound values; partial-index
predicates use only validated registry fingerprints so the planner can match
the selected index. Verify query plans and authorization predicates against the
deployed pgvector version before deployment. [pgvector documentation](https://github.com/pgvector/pgvector)

The current HNSW/IVFFlat limits are 2,000 dimensions for `vector` and 4,000 for
`halfvec`. Full 4,096-dimensional Qwen3-Embedding-8B output therefore does not fit
a `halfvec` ANN index. Its model card supports smaller outputs; reduced output,
subvector candidate retrieval with full-vector reranking, or exact search need
separate evaluation. Half precision is a storage/index option, not a guarantee
of acceptable recall. [pgvector](https://github.com/pgvector/pgvector),
[Qwen model card](https://huggingface.co/Qwen/Qwen3-Embedding-8B)

Truncation is permitted only for models explicitly supporting variable outputs
and their documented normalization procedure. Nomic v1.5 supports Matryoshka
representations and requires retrieval task prefixes. Do not silently truncate
arbitrary embeddings or assume all models served under a Nomic alias share that
contract. [Nomic model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5)

## Initial code-model recommendation

Evaluate `nomic-ai/CodeRankEmbed` first for the code slot. Its published model
card describes a 137M-parameter code retrieval encoder with an 8,192-token
context, MIT licensing and a required natural-language query instruction. Its
published pooling configuration specifies 768-dimensional CLS pooling. This
makes it a small initial candidate when every source receives both slots, not
proof that it is the best model for Plowshare's corpus.
[Model card](https://huggingface.co/nomic-ai/CodeRankEmbed),
[pooling configuration](https://huggingface.co/nomic-ai/CodeRankEmbed/blob/main/1_Pooling/config.json)

Record the exact query prefix, document preprocessing, pooling and normalization
in the code-space descriptor. Validate the selected serving engine against that
contract before loading production data. Do not silently treat an endpoint that
returns vectors of the right width as equivalent to the published encoder.
For CodeRankEmbed, the documented query prefix is
`Represent this query for searching relevant code: `; its usage example embeds
document/code inputs without that query prefix. Apply it once at the typed query
boundary and record that distinction in the space identity.
Benchmark Java/TypeScript code, prose and mixed documents with natural-language
queries because both representations cover all sources. Include latency and
batch throughput as well as retrieval quality.

`Qwen/Qwen3-Embedding-0.6B` is an alternative to evaluate: its card describes
code retrieval support, Apache-2.0 licensing, a 32K context and outputs from
32 to 1,024 dimensions. `jinaai/jina-code-embeddings-1.5b` is a specialized
alternative with 1,536-dimensional output and task-specific prefixes, but its
published weights use CC-BY-NC-4.0. These are explicit deployment choices, not
packaged model defaults. Neither changing a model name nor matching 768
dimensions makes existing vectors compatible; adoption requires the space and
re-embedding transition above.
[Qwen model card](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B),
[Jina model card](https://huggingface.co/jinaai/jina-code-embeddings-1.5b)

## Enabling the two slots

Configure both complete declarations below in the server's external configuration.
Supply the actual served model names/revisions and model-specific fields; the
placeholders have no fallback. Declare both names in the existing LLM pool's
`models` list, alongside chat/reasoning models. Provider URLs and lane sizes stay
in that pool configuration. In dual mode the legacy single-embedder keys are not
required; composition derives its common chunk/input ceiling from both models.
Input checks include task prefixes and never truncate an oversized existing source.

```yaml
plowshare:
  embeddings:
    enabled: true
    paused: false
    code:
      model-id: ${CODE_EMBEDDING_MODEL}
      model-revision: ${CODE_EMBEDDING_REVISION}
      dimensions: ${CODE_EMBEDDING_DIMENSIONS}
      query-prefix: ${CODE_EMBEDDING_QUERY_PREFIX}
      document-prefix: ${CODE_EMBEDDING_DOCUMENT_PREFIX}
      pooling: ${CODE_EMBEDDING_POOLING}
      normalization: ${CODE_EMBEDDING_NORMALIZATION}
      reduction: ${CODE_EMBEDDING_REDUCTION}
      max-input-tokens: ${CODE_EMBEDDING_MAX_INPUT_TOKENS}
      search-mode: ${CODE_EMBEDDING_SEARCH_MODE}
      distance: ${CODE_EMBEDDING_DISTANCE}
    prose:
      model-id: ${PROSE_EMBEDDING_MODEL}
      model-revision: ${PROSE_EMBEDDING_REVISION}
      dimensions: ${PROSE_EMBEDDING_DIMENSIONS}
      query-prefix: ${PROSE_EMBEDDING_QUERY_PREFIX}
      document-prefix: ${PROSE_EMBEDDING_DOCUMENT_PREFIX}
      pooling: ${PROSE_EMBEDDING_POOLING}
      normalization: ${PROSE_EMBEDDING_NORMALIZATION}
      reduction: ${PROSE_EMBEDDING_REDUCTION}
      max-input-tokens: ${PROSE_EMBEDDING_MAX_INPUT_TOKENS}
      search-mode: ${PROSE_EMBEDDING_SEARCH_MODE}
      distance: ${PROSE_EMBEDDING_DISTANCE}
```

Prefixes can explicitly be empty. Normalization is `none` or `l2`; distance is
`cosine`, `l2`, `inner_product` or `l1`. Configuration binds strictly and rejects
unknown fields, unsupported widths and incompatible mode/metric combinations.
The settings are startup configuration, not live runtime-config keys. Once a
slot has activated, startup refuses turning dual mode off: silently reading the
unidentified legacy columns would miss newer writes and source edits.

The first configured rebuild starts with no active slots: semantic queries report
that state explicitly until activation, while committed text remains readable.
Existing public operations remain unchanged. Code corpus capabilities choose
`code`; document, memory, digest and conversation capabilities choose `prose`.
Lexical/hybrid behavior remains under its existing explicit operation contracts.
During later rebuilds the previous descriptor/index policy keeps serving. Queries
embed outside a transaction, then share-lock/recheck their captured slot for the
short database read. An activation that won the race causes an explicit retryable
search failure, rather than mixing spaces or transparently resubmitting a request.

Keep the old served model name available during a weights/output transition.
A changed revision/output cannot reuse the active name: that would send old-space
queries to new weights. Prefix/client normalization changes can reuse the same
encoder. Inspect `embedding_slots`, `*_embedding_staging` and
`*_embedding_failures` for target/progress/retry state; sources missing either
current target representation remain repairable after restart.
Activation briefly excludes writes to all owning tables while checking coverage,
copying vectors and building indexes; schedule large transitions appropriately.
Old staged generations and their descriptors remain available for a configured
rollback, which must again satisfy current-source coverage. They are not relabelled.

## Transition and recovery

Changing a slot's configured model/space starts an explicit durable background
rebuild for that slot across every embedding store. Normal operation may
continue while rebuilding; searches do not synchronously repair vectors.
Mismatch detection on writes or reads can enqueue repair, but a complete
inventory and durable catch-up must also find records that searches never
return. The other slot remains independently usable and is not rebuilt solely
because this slot changes.

Repair follows the existing committed-source polling pattern used by passage
indexing. Slot targets, per-source staged checkpoints and per-store failures are
durable database state, rather than an agent conversation/job loop. The worker
submits at most four missing sources per store and generation in a pass; it
reuses the dispatcher, pool admission and durable inference accounting, with a
system maintenance owner in the source's project/global scope. Owned ingest and
information-processing batches retain their original accounting owner. Their
batch results report dispatch submissions and never fabricate embeddings.
Model calls run outside transactions. Publication rechecks source and slot
versions, and successful work in one slot survives failure in the other. Failure
categories are bounded; retry starts at 30 seconds and backs off to one hour.
Worker interruption stops between sources; already committed work survives a
restart. `paused: true` disables automatic repair/activation on the next startup.
User-owned ingest/processing continues to honor its existing cancellation checks.
A public administrator rebuild job/allowance/status API is not introduced here;
that would require its own shared operation contract and SDK work.

Keep the old space active while rebuilding the new one. Track eligible, complete,
failed, unavailable and stale sources separately. Capture source changes during
the rebuild through a durable delta/checkpoint strategy; a completed initial
scan is not sufficient evidence that the target is current. Activation requires
an explicit coverage policy and an atomic, version-fenced transition after
reconciling concurrent changes. Retain the old space for rollback until its
retirement policy is satisfied. Prevent reads and writers from mixing spaces
across an activation. Activation covers the selected slot across all stores;
independent per-table switches must not expose inconsistent generations to a
search spanning stores. New and edited sources receive both serving-generation repair and replacement
catch-up under the same source revision rules. Already current representations
are skipped; index-only changes do not submit additional embedding requests.

There are two permanent active slots. Continuous availability during a rebuild
requires access to the old and replacement models for the changing slot until
activation, in addition to the unchanged slot's model. If deployment strictly
permits only two loaded models, explicitly accept reduced availability or a
maintenance stage for the changing slot; never query old vectors with its new
model. Retaining old vectors alone does not preserve search without the old
query embedder.

Reconstruction must preserve source text, durable history and existing ownership.
Do not assume every historical source is still extractable or retained: inventory
unavailable rows and report gaps without manufacturing successful coverage.
Cancellation/restart must preserve already committed vectors and accounting;
retry only with the existing dispatch rules for uncertain model delivery.

## Migration and verification requirements

The implementation uses new Flyway migrations and typed repository contracts.
Shared operation DTOs will be required if public rebuild administration is added.
Do not edit shipped migrations or add adapter-specific schema/API behavior.

Historical provenance cannot be inferred from the current configuration. Accept
legacy-space attribution only when historical evidence establishes it, with
recorded operator attestation where appropriate. Otherwise mark provenance
unknown and rebuild from retained source; never relabel old vectors as the
currently configured model simply because their dimensions match.

Verify on restored database copies before deployment. Use mocks for lifecycle,
allowances, cancellation and invalid input. Focused opt-in PostgreSQL tests are
required for new vector constraints, casts/index plans, cross-space isolation,
atomic activation, concurrent changes, rollback and restart checkpoints. Confirm
that all five stores receive both slots, searches use only their selected slot,
and changing one slot preserves valid vectors in the other. Confirm that text,
history, memberships and accounting survive migration. Journal
compatibility for integrations remains a separate adapter-owned verification.
Focused PostgreSQL tests have run against fixtures, including legacy-data upgrade
and real index builds. Restored production data, integration journals and a live
model deployment have not been verified in this batch.

## Other feedback to sequence separately

The README already identifies people building their own assistants and developers
building workflows as its audience; positioning can make durable ownership,
remote clients, configurable model pools and explicit grants clearer near the
start. That does not justify calling every subsystem event-sourced.

A simple Compose onboarding path should take an explicitly configured model
endpoint and document that endpoint's chat/embedding capabilities. Existing
Compose deployment is not evidence of a one-command cold-start experience, and
a local example must not become a silent production connection default.

Optional OS/container command isolation, native SDK CI, Java persistence/DTO
architecture enforcement, publication of the pending cleanup and restored-data
verification are separate follow-up tasks. This proposal does not claim they
are implemented. ADR 0001 records the integration/core boundary now; future
platform changes should record their own decisions and consequences.
