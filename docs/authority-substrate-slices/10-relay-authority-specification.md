# Message and Relay effect-family specification

Recorded before slice 10 enforcement, 18 October 2026.

| Existing family | Account/actions | Independent fences / receipts |
| --- | --- | --- |
| Project port publish | Authenticated publisher; WRITE/work access in exact registered project | Explicit ingress/provider eligibility, topic/causation/payload limits and request ID. Publication never grants receiver execution. |
| Consume / acknowledge | WRITE/work access: polling creates/renews leases and acknowledgements advance cursors | Exact configured egress port, account/group, consumer lease/fence and read-byte budget. READ alone is insufficient. |
| Operational controls | Existing manager plus action/lifecycle eligibility | Existing control receipt, inactivity proof and live source check; no new controls. |
| Agent/native receiver | Configured worker account, independently of publisher or message text | Existing two-sided route, project/session/tool fences; common JobStore retained actions and allowance, original execution intent and job receipt. |
| Private message turn | Bound participant's account, independently of sender and conversation creator | Existing routing, instance/lease/pot, approval/command and conversation lifecycle; common retained admission and budget. |
| Result / approval / board notification to a person | Explicit known recipient account; READ for disclosure and work actions for a model turn | Original source-log disclosure check, conversation lifecycle and current agent eligibility; common retained admission and conversation allowance. Missing recipient cannot execute. READ denial cannot copy the result into an inbox; READ without work permission retains the existing non-model inbox fallback. |
| Orchestration receiver | Owning start receipt and current route/grants remain authoritative for inspection | New retained lineage is slice 11; unsupported new consumer execution must remain explicitly fenced. Known receipts are read-only reconciled, never reissued. |
| Provider invocation | Existing provider binding/credential/host eligibility | Original invocation ID, immutable deadline, request/result correlation, accounting and UNKNOWN/no-replay behavior. Account RBAC cannot replace these capabilities. |

Recheck live eligibility before receipt disclosure and before effects, including
when source/parent reads intervene. A known result is not an authorization bypass.
Receiver account comes from the bound server context; event text, publisher labels,
requested models and SYSTEM identifiers cannot replace it.

This slice adds no ports, wildcard, broker, public API or tool-level RBAC. Existing
Relay/message persistence already retains effect, consumer, job and original
request identities. Missing authority/receipts are held or inspected, not replayed
under a new operation, executor, budget or request ID. Existing message restart
recovery reports interrupted paid turns without executing them again.

The shared person-delivery callback also serves orchestration results. Carrying
its already-known recipient is notification integration only; it does not admit
orchestration execution or provide slice 11's child authority lineage.
