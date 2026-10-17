# Inference authority and existing budget sources

Specification recorded before slice 07 enforcement, 18 October 2026.

Every production dispatcher request must carry explicit operation evidence, a
claimed call from its bound allowance, and a live authority callback. Usage
attribution, the model's `system.*` selector, queue timeouts and capacity pools
cannot supply any of these. Compatibility constructors remain for isolated tests;
production composition requires the gate.

## Existing sources and activation

| Family | Existing source | Authority / continuation |
| --- | --- | --- |
| Ordinary agent chat, fallback, native refusal | Shared `Budget`; conversation total/spent when conversational, admitted run otherwise | Slice 06 admission, exact supporting grants and actual executor; fallback retains the same operation/source. |
| Information preparation | Captured revision allowance and atomic stage spend | Explicit SYSTEM purpose and valid generation/lease/input bindings; activation in slice 08. |
| Conversation compaction | Conversation configured allowance (worker configuration alone is insufficient) | Separate bounded SYSTEM operation; activation in slice 08. |
| Scribe, Learner, digest model | Existing worker definition or explicit digest `Budget` | Separate bounded SYSTEM operation; activation in slice 08. |
| Advisor, call validator, command judge | Existing configured worker definition, one consult per existing invocation | Separate model-only SYSTEM purpose, never authority inferred from attribution; activation in slice 08. |
| Query/write/repair embedding | No configured operation call allowance exists; token/input and timeout limits are insufficient | Explicit bounded SYSTEM service purpose; disclosure/writes retain their owning resource gates; activation in slice 08. |
| Schedule reader and other helpers using JobRuntime | Explicit worker allowance | Classified SYSTEM purpose in slice 08; firing authority in slice 09. |

A new dispatch claim records the operation, immutable budget-source identity,
claim ordinal and the existing allowance snapshot before transport. Rechecking a
queued request does not claim again. The last permitted claim remains valid when
the remaining balance becomes zero. Later effects still check live authority.
Existing supported operator limit changes remain explicit changes to the same
source; they do not replace its identity. Child calls share their parent's
allowance. Generic restartable jobs are not introduced here.

Unclassified production inference refuses, including embedding and direct helper
calls, rather than assuming SYSTEM. The SYSTEM families above cross-activate with
slice 08; an intermediate slice 07 deployment is not a complete service cutover.
This sequencing follows the roadmap's internal-execution activation gate.

## Gaps and guarantees

Model-call allowances are not monetary reservations. Queue timeouts are not call
budgets. There is no new payer account, pool fallback or billing API. A durable
claim is evidence of permission to attempt a call, not evidence that a provider
accepted it or a replacement for durable accounting attempts/unknown outcomes.
Existing helper operations without restartable execution do not acquire restart
or replay support. Legacy work lacking retained evidence is held/refused; it is
not reconstructed from created_by or accounting metadata.

Dispatcher inventory: chat complete, stream, streamFold, streamOn and embedding;
direct owners are JobRuntime, Compaction (including skill handoff), Scribe, Learner, DigestModel, StuckTrapFactory,
ModelCallValidator, ModelCommandJudge, ScheduleReader, DispatchingEmbeddingClient and DispatchingDualEmbeddings (through EmbeddingDispatch). Token
counting is preflight metadata, not RUN_MODEL execution. Input/output security
filters must preserve evidence and cannot create authority.

The slice 14 [completion implementation](14-completion-plan.md) supplies document
chunk/summary embeddings from the existing retained revision allowance. The
following audit gap remains applicable to query and independent repair work,
including other unclassified archive embedding writes; those paths stay fenced.

The original source audit confirmed a specific missing mechanism: embedding query/write/repair
services have tokenizer/input bounds and queue deadlines, but no configured
operation call allowance. Those are not interchangeable. Production calls without
explicit evidence remain refused. A configured search/deployment budget mechanism
requires a subsequent explicit decision; this implementation adds no implicit
one-call allowance or SYSTEM wallet. This is a recorded activation gap, not a
claim that semantic search remains available during partial rollout.

The proposed [follow-on slice 15](15-semantic-query-budgets-and-read-authority.md)
now scopes shared SYSTEM embedding execution: preparation keeps its current
source, and initial search consumers supply a configured search-operation
allowance and retained query-purpose evidence. Scope/read/write policy and budget
ownership remain with each consumer; embedding adds no account role or separate
permission system. It is planning only; memory recall, ask/deliberation and
independent repair activation remain separate, and no query path is activated by
that document.

Slice 08 binds actual conversation allowances to repository-backed pre-dispatch
spend, including background compaction. Stale recovered counters cannot spend the
same ordinal. Source conversation identity is retained with the budget evidence;
processing retains its existing revision spend callback. Neither is a new payer.
