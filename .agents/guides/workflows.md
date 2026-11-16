# Workflows, schedules and coordination

Read this for orchestration lifecycle, native scripts, scheduling, stage/hooks,
swarm/board coordination or inbox delivery. Read [AUTH](auth.md) when changing
caller lineage, retained authority, execution accounts, approvals or audiences.

## Mental model

Orchestrations coordinate durable conductor state, schedules admit repeatable
occurrences, and swarm/board components coordinate their own work. Notifications
and inboxes expose outcomes and questions; their delivery state is separate from
the execution state and authority that produced them.

## Start in code

- [OrchestrationStarts](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationStarts.java) owns idempotent
  starts; [Orchestrations](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Orchestrations.java) owns transitions;
  [OrchestrationStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationStore.java) owns persistence fences.
- [ScriptProgram](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptProgram.java) and
  [ScriptCommands](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptCommands.java) define native script execution.
- [ScheduleDefinitions](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ScheduleDefinitions.java) owns desired definitions
  and controls; [FiringStore](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/FiringStore.java) owns occurrence persistence.
- [SwarmScheduling](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/swarm/SwarmScheduling.java) owns swarm scheduling;
  [ApplicationInboxes](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/ApplicationInboxes.java) owns application output routing.

## Rules that are easy to miss

The durable row and compare-and-set transition decide what an ending means.
A conductor's turn ending is not necessarily an orchestration ending; waiting,
question, cap, cancellation and delivered-result state must agree with the row.
Retained start IDs must not launch another first turn on reconciliation.

Desired schedule files and runtime projections are separate. Definition save
writes desired state before reconciliation; unavailable/incomplete source scans
must not become deletion. Mutable-source control and deployed application runtime
overrides have different ownership. Preserve occurrence identity and current
execution/control authority independently of historical registration.

Native scripts emit bounded typed commands through their owning runtime. They do
not acquire arbitrary host IO or model-selected authority. Keep stage checkpoints,
child completion delivery and effect replay rules intact across interruption.

For model-produced string-field objects, use the shared
[model-output recovery policy](../../docs/decisions/0074-model-output-recovery-policy.md)
with an explicit contract. Persist its checkpoint in script state and execute its
ordinary returned delegation command. Review exhaustion must not complete an
unvalidated stage. Domain-specific semantic checks remain mandatory. Existing
script JSON parsing and contract decisions are recorded as diagnostics hidden
from model context; installing this policy never resumes source-pinned old runs.
Model prose uses word targets with documented tolerance (25% by default), not exact
character cutoffs. For nested outputs, recover only selected prose through the
shared string-field policy while retaining domain-owned citations, URLs and
verdicts. Keep completed research on review exhaustion; do not rerun it to shorten
a response. See [model prose budgets](../../docs/coding-standards.md#model-prose-budgets-and-recovery).

Application inbox routing and human attention ownership are distinct. Root
applications retain background output in the project inbox without manifest opt-in.
Successful schedule receipts and pinned native-only completions are routine by
default; other successful workflows notify Managers unless the explicit policy
selects a different success-attention set. Failures, caps, approvals and questions
still require attention.
Current human Managers are resolved for attention copies; the registrar/executor
is not an implicit recipient. Project Viewers can inspect shared output, Managers
acknowledge it, and neither publication grants access to private execution logs.

## Agent budget approvals

Budget requests are a typed optional variant of the existing approval record.
`approval.list` displays exact additional calls/steps; `approval.answer` accepts
`once` or `deny`. Approval atomically adds to the canonical allowance once;
command prefixes and standing conversation/project grants cannot approve it.
The original execution owner continues the retained stack after approval,
including after exhaustion. Consent grants budget, never additional tool or
resource authority. See [ADR 0073](../../docs/decisions/0073-agent-budget-consent-and-continuation.md).

## Bounded work

Required contract: active start/control/stage/occurrence work needs configured
positive finite deadlines covering queueing, waits, IO and result delivery. Short reads,
controls and acceptance use a 2-second default ceiling; normal work is much faster.
Durable workflow steps and model calls need appropriate configured deadlines,
leases and budgets, not a 2-second cap on the whole workflow. Parked human waits
hold no active resource; long-lived schedulers use bounded scans and renewals.
Retries and nested commands inherit the earlier deadline without resetting it.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop new commands/effects, request owned cancellation and reject late
results under current transition/lease/authority fences. Keep physical capacity
accounted until work ends; unknown mutations keep original intent/receipts, no replay.
Current: [ScriptProgram](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/scripted/ScriptProgram.java) bounds a sandbox step;
this is not an end-to-end deadline across scheduling, child work and delivery.

## Legacy traps

Omitting application `inbox` uses default attention selection, not account-owned
routine delivery. Explicit foreground conversations keep their path. Do not treat
an External project as a root Application. Application and personal read states
are separate. Scheduler wakeups, Relay availability and job progress do not prove
successful handling or replace durable state transitions.

## Checks

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.orchestrations.CallerOrchestrationsTest' --tests 'io.aeyer.plowshare.server.orchestrations.OrchestrationResumptionTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.events.ScheduleActionAuthorityTest' --tests 'io.aeyer.plowshare.server.events.ApplicationInboxesTest' --tests 'io.aeyer.plowshare.server.swarm.SwarmSchedulingOwnershipTest'
```

These default tests do not need PostgreSQL. Select full-db only for actual
compare-and-set, locking or durable occurrence assertions. Read the
[authoring manual](../../docs/manual/11-orchestration-authoring.md),
[native scripts](../../docs/scripted-orchestrations.md) or
[application inbox decision](../../docs/decisions/0072-application-inbox-ownership-and-attention.md)
for the relevant change.
