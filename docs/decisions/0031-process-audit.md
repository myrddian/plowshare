# ADR 0031: Read-only process and conversation audit

Status: accepted for implementation.

Operators must be able to explain background model traffic and follow all
non-archived conversation trees after individual jobs complete. Chat listings omit
internal logs, live job handles disappear at restart, and usage totals alone do
not explain processing queues or causal feedback.

Provide `proc.list`, `proc.inspect`, `proc.calls`, `proc.tools` and `proc.queues` through the
existing authenticated WebSocket. The server combines typed read-only repository
projections with separately identified local runtime evidence. The existing
conversation, job, processing, accounting and transcript owners remain authoritative.
No second scheduler, effect journal or durable client runtime is introduced.

Ordinary visibility retains account custody and current membership. An explicit,
rechecked server-administrator selection authorizes fleet metadata inspection;
content, trajectory and source reads retain their own checks. Cursor positions
never bypass access or archive filtering. SYSTEM execution and admission custody
remain separate fields, and historical accounting is not rewritten.

Use current-generation processing states and committed iteration checkpoints for
queue counts, and share admission predicates for eligible, not-yet-enqueued sweep
work. Show full-selection totals independently of page limits. Distinguish stages,
source items, pool occupancy, calls and attempts. Unknown item totals, waiting age,
upstream queues and liveness remain unknown. Repetition signals are review evidence,
not authority to cancel or replay work.

Desktop and Console share the audit panel. TUI and CLI consume the same typed
operations. Initial GUI updates are bounded replacement reads, with visible stale
states, response-generation fences and cleanup on navigation. Dedicated push
subscriptions can be introduced later without changing ownership or read semantics.

See [Processes and conversation audit](../proc-audit.md) for usage and coverage.
