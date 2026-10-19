# JdbcInferenceAdmissions

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/JdbcInferenceAdmissions.java#L33) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/OperationInference.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V160__operation_inference_claims.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`OperationInference` retains the operation-to-budget relation, checks retained identity, spends/accepts through `AllowanceRepository`, and inserts an admission. `require` verifies the exact immutable binding; `submitted` and `accounted` perform conditional lifecycle/correlation updates. The table joins and stored allowance functions enforce paid-work authority, so a previously loaded admission must not silently authorize another model call.

Trace the inference boundary to separate duplicate evidence validation from mandatory spend and submitted-state fences. Reuse the typed `InferenceBinding` across preparation/admission/accounting helpers; preserve admission verification after process recovery. Consider consolidating relation insertion/identity validation into one SQL result or bounded stored routine, retaining conflict rejection and transaction atomicity. V160's operation/budget and admission keys already cover these exact predicates; inspect inherited V173 allowance triggers before adding an index or moving logic.

Only immutable retained evidence may eventually use database/admission UUID/operation/budget/ordinal/ceiling keys under bounded LRU. Mutable submitted/accounting correlation and actual allowance spend stay uncached. Cached evidence is never an admission token for another invocation. Retain/submitted/accounted and operation/allowance lifecycle changes invalidate admission and operation/budget/report collections after commit. A durable generation is needed for mutable projections, and listener recovery must bypass them until validated. No shared state is published from a failed enclosing transaction.

Mock a single evidence load per boundary while retaining one authoritative spend and one submitted transition. Focused PostgreSQL tests apply to any combined routine, duplicate budget binding, single-winner submission and rollback across allowance acceptance.
