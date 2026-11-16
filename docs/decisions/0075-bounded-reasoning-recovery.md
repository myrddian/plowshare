# 0075 — Bounded reasoning and lower-effort recovery

Implemented as an opt-in capability; no live rollout is implied.

## Problem

A reasoning model can stream novel or repeated analysis until a provider deadline
without producing an answer. Restarting the entire task repeats completed research;
raising the deadline merely allows more generation. A total output-token limit can
also expire inside the reasoning block, leaving no answer.

## Decision

Add an explicit local vLLM provider over the existing OpenAI-compatible HTTP
transport. Only this dialect advertises/sends `thinking_token_budget`. Do not infer
vendor extensions from a URL or model name. No new HTTP client, alternate protocol,
automatic serving probe or transport replay is introduced.

A model sampling profile may declare typed `reasoning` limits. Its token budget
must leave room within the configured total output limit. vLLM may force an
end-of-reasoning marker at that boundary and continue the same generation. The
profile also bounds reasoning-only elapsed time and repeated text. Detection uses
a bounded suffix and accumulated text across stream chunks. It does not interpret
the semantic value of thoughts or equate a lack of tools with failure.

When this monitor stops an attempt before any answer/tool fragment is observed,
the transport cancels that stream and raises a distinct failure. The agent runtime
may make one separately accounted generation at an explicitly configured lower
effort, consuming the original shared allowance and step cap. It retains completed
tool results, preserves the original completion deadline and inserts an explicit
harness convergence notice. No failed reasoning is promoted into instructions or
an answer, and no tool fragment is executed. Generic network failures and uncertain
tool effects retain their existing recovery owners; they do not enter this path.

The monitor's reasoning timeout starts at the first thought. Its outer completion
deadline starts before scheduling/queueing and survives the lower-effort attempt.
Existing shorter provider limits still apply. Expiry or cancellation prevents new
work. Local stream cancellation is not proof of immediate remote GPU termination;
the existing provider cleanup/capacity contract remains in force.

The one-recovery limit is per agent activation. Recovery cannot recursively grant
another recovery. Explicit later user continuations retain their existing lifecycle;
this feature does not automatically resume failed jobs or alter retained-command
reconciliation. Mandatory reviews and stage acceptance gates remain mandatory.
Reasoning recovery does not extend the existing one-call, tool-free response
regeneration. Input filters cannot remove or replace the admitted reasoning
limits/deadline.

## Compatibility and evidence

Existing profiles and OpenAI/LM Studio pools are unchanged. Unsupported providers
refuse reasoning limits rather than silently dropping them. Profile loading rejects
unknown fields, invalid budgets, missing answer capacity and non-descending effort.
Configuration is deployment-owned and requires the normal server/profile rollout.

Validation uses mock/local HTTP streams, a fake monitor clock and an in-memory
runtime. It covers serialization, chunk-independent repetition, quiet reasoning,
partial-output exclusion, one recovery, deadline retention, allowance/cancellation,
and completed-tool preservation. No schema or SQL change is needed. Backend
support for a field is not proof of end-to-end enforcement for every parser or
speculative-decoding configuration; a bounded serving experiment is required before
enabling it in a deployment.

Preserved-thinking projection, sampling-penalty changes and non-thinking fallback
are separate experiments. They are not enabled by this decision.
