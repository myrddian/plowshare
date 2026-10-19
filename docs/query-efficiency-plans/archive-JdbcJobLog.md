# JdbcJobLog

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[JobLog](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcJobLog.java#L112) writes submission and ending, reads one job or all jobs in ID order, and prunes by `started_at`. JobStore owns runtime lifecycle and tolerates unavailable logging; a missing ending after restart must not be converted into an invented RUNNING state. The project left join intentionally preserves historical Global jobs. [V24](../../plowshare-server/src/main/resources/db/migration/V24__jobs.sql#L189) already supplies the age index; the ID primary key supplies listing order.

First retain the submission/result DTO through JobStore rather than reloading for fields already known. Add bounded cursor pages to any active caller of unbounded `all`, preserving mint-order semantics. If ending needs an updated response, return a typed `UPDATE ... RETURNING` projection rather than update plus fetch; do not widen the write merely for a hypothetical caller. A stored procedure is unnecessary for these single statements. Measure listing size/frequency before claiming a bottleneck.

Eligible shared values are immutable JobRecord snapshots keyed by database, job ID, projection and durable job generation; an unfinished record is not immutable just because its Java type is a record. Ended-at is not sufficient to identify every rewrite unless the write contract makes endings final. Invalidate on started/ended, pruning, project name changes and job-list membership. Cover all writers after commit, including retention/maintenance. Never admit failed logging or cache missing jobs indefinitely. Current account/conversation/project access must fence disclosure. On reconnect clear/bypass uncertain mutable records and list pages until durable recovery. Use the shared bounded LRU, with entry/byte admission for history pages.

Mock retained-result reuse and no refetch for simple status composition. Changed SQL requires focused PostgreSQL null-project mapping, prune boundaries, ending/prune races and rollback/invalidation tests. Keep historical logging separate from authoritative active-job state.
