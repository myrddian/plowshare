# OrchestrationConcerns

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/OrchestrationConcerns.java#L29) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/AcceptanceChecker.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V77__the_acceptance_checker.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`AcceptanceChecker` and gate services list per-run concerns ordered by creation time and numeric suffix, raise concerns using `MAX(substring(id FROM 2)::int)+1 ... RETURNING`, and update answer/resolution/check fields by run/ID. Raising already returns the typed result rather than refetching. Numeric ID allocation and expression ordering may scan a growing concern set, but current cardinality is unmeasured.

Reuse the returned `Concern` and previously loaded list through one checker operation. Measure list length, suffix parsing and concurrent raise retries. If growth matters, introduce an explicit numeric ordinal/sequence allocated under the owning run transaction, preserving public `cN` identity and ordering. Compare an orchestration/ordinal index after inspecting V77's composite key; do not add an expression index before representative plans. Keep updates typed/allowlisted and consider `RETURNING` only for actual caller reloads. A cohesive raise routine may be useful if it owns safe ordinal allocation and result mapping.

Cache immutable display snapshots of concern sets keyed by database/run/generation/account/project scope, not unresolved/accepted status as acceptance authority. Every raise/answered/resolved/askedAgain/forThePerson/checked/personAnswered mutation and run deletion invalidates concern and run-set dependencies after commit. There is no uniform version today: durable per-run set generation and recovery are prerequisites for bounded LRU. Current acceptance and person-consent decisions remain authoritative. Collection invalidation must cover newly added concerns, including empty cached lists.

Mock one set load per checker action and returned DTO reuse. Focused PostgreSQL tests cover concurrent ordinal allocation, expression/index equivalence and rollback if SQL changes.
