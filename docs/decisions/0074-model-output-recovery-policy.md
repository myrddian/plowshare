# 0074 — Bounded model-output recovery and diagnostic ledger

Accepted for implementation; deployment is on hold.

## Problem and scope

Local models vary in formatting and verbosity. A completed, useful model answer
must not require repeating its research because one field exceeds a presentation
budget. Raising limits alone does not establish reliable output handling.

This is a general platform capability, separately authorized from the Network
Privacy Watch adapter work. A collector-only solution cannot expose a common
sandbox policy or write roleless diagnostics into the owning execution ledger.
Existing `llmJson.parse` and ordinary journaled delegation provide the necessary
building blocks; no database access, provider-specific fallback or new retry queue
is needed in an adapter.

## Policy

Workflows declare an explicit output contract. Version one supports objects with
named string fields, word budgets and required/nonempty values. It does not
infer a contract from arbitrary prose. Domain validators continue to own factual
claims, allowed evidence, references, judgments and acceptance.

1. Preserve the original command result and attempt bounded local JSON recovery.
2. Accept valid output without a model call, recording that decision.
3. If only word budgets fail, request targeted summarisation of those fields.
   Code merges the result with unchanged fields; the worker cannot replace them.
4. For syntax or structural errors, permit one narrow correction. Existing valid
   fields must remain unchanged. Ambiguous duplicate keys, tool markup, recognized
   host failures and excessive response sizes do not trigger a model repair.
5. Revalidate the result. Preserve identifiers, CVEs, URLs, versions, uncertainty
   and negative qualifiers with conservative checks. These checks are not proof
   of semantic equivalence; the owning domain must still validate the result.
6. After one model attempt, invalid output requires review. No missing fact,
   approval or successful effect is invented to make a workflow finish.

The policy retains a fingerprinted checkpoint in normal script state. Changing
its contract cannot reset a retained attempt. Response bytes, parser work and
field counts remain independently bounded; word limits do not allow an
unbounded single word.

## Execution, summarisation and authority

The policy returns an ordinary `agent_run` command with `recovery: true`. Its
worker must be explicitly declared in `calls`, be delegable, and have one model
turn with no tools, calls, scopes, skills, dynamic capabilities, review or native
script. The worker's configured model can use LM Studio or another supported
provider. Summarisation is a task on that model-only worker, not a dependency on
vLLM and not a call into SYSTEM document ingestion/compaction.

Delegation retains the caller's account, home, allowance, cancellation and
configured inference deadlines. There is no independent recovery allowance,
automatic model escalation or retry of a provider failure. A one-call worker
consumes the same root allowance as its parent. Recovery-tagged calls cannot use
the retained-child failure-retry path to add another attempt. Ordinary explicit
delegation retries keep their existing lifecycle. This capability adds no new
end-to-end workflow deadline; existing provider/queue deadline enforcement limits
still apply. Hooks may refuse a recovery command but cannot rewrite it into
research or change its retained input. Post-hook uncertainty retains the existing
raw receipt and reconciliation fence.

Preparation, dispatch, results and interruptions use the existing script journal.
Completed research, scans and writes are never repeated to repair output.
An interrupted command without a positive result remains fenced by the existing
recovery lifecycle. An already-failed, source-pinned run is not automatically
resumed by installing a new policy or definition.

## Ledger and model projection

The owning runtime appends `DIAGNOSTIC` entries for contract decisions and for
shared scripted JSON parsing. Versioned decision metadata includes the policy,
contract, preceding/decision command, script hash, original/result hashes, word
counts, limits, parser pass, attempt and chosen action/reason. Dispatch and return
diagnostics include the worker, source call and calls consumed; an exhausted
allowance is recorded without dispatch. The original response stays in its
command receipt rather than being duplicated into diagnostics. The preceding
command is an execution anchor, not proof that it produced the examined text:
scripts can also examine retained state or initial input. Response hashes identify
the exact examined text without making a false source attribution.

These entries remain visible through authorized trajectory/log reads. Their kind
has no model role; projection, context construction and compaction cannot turn
them into model instructions. The host observer is excluded from serialized
script input and is never exposed to the guest. Even a subsequent script error
does not erase a validation decision already recorded.

Worker tasks/results remain ordinary execution records. A repair worker receives
the explicit recovery task, not the diagnostic ledger or unrelated caller handoff
notes. Its task treats the original response as untrusted data.

## Adoption and compatibility

`modelOutput.recover(raw, contract, checkpoint)` exposes the shared policy to
scripts; Java callers use `ModelOutputRecovery`. Existing scripted
`llmJson.parse` calls also emit syntax diagnostics, while their existing semantic
validators and domain-specific repair stages remain authoritative. Complex
non-string contracts are not silently weakened or coerced into this first API.

The device-profile workflow is the first full consumer. It stores the returned
checkpoint, executes the returned command once, and uses `orchestration_ask` for
review instead of claiming an invalid draft is complete. A missing server helper
is refused before research begins. Deploy the server capability before the new
Application source. No deployment or live run continuation is part of this change.

## Validation

Tests use fake inference and mocked/in-memory persistence. They cover word
budgets, local syntax recovery, partial-field summaries, preserved references and
qualifiers, rejected capabilities, shared allowance/cancellation, checkpoint
reuse, review exhaustion, and diagnostic exclusion from projection and actual
compaction input. The persistence schema and SQL are unchanged, so PostgreSQL
tests are not required for this policy.
