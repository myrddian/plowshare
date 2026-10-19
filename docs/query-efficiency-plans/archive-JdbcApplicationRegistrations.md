# JdbcApplicationRegistrations

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[This implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcApplicationRegistrations.java#L15) reads the `projects.application_boundary` flag by unique project name and sets it true, checking that exactly one project survived. Application resource/boundary composition should reuse a flag already present in an authorized project snapshot. A flag query is narrow already; neither a new index nor a stored procedure is justified without evidence of excessive caller frequency. Preserve a missing project answering false for the read and refusal on the write.

Treat this flag as enforcement state, not a reusable authorization decision. Initially keep reads operation-local. A later immutable boundary projection needs keys containing database, project identity and durable project/boundary version; the current Boolean has no version. Invalidate on `require`, project creation/removal and every deployment/project writer affecting boundary state. Invalidation must occur after commit and invalidate negative results when a project appears. Never allow a cached false to bypass a newly required Application boundary; an authoritative fence is required independently of notification delivery. Reconnect must bypass this projection until its baseline is restored.

Implement in two steps: eliminate duplicate boundary reads in resource resolution; only then consider tracking this projection in the shared bounded LRU if counters show benefit. Mock the one-read budget and missing-to-present invalidation. A focused PostgreSQL case is warranted only for a changed version/trigger contract, including rollback and concurrent boundary adoption.
