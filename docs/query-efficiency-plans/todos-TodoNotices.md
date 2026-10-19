# TodoNotices.Jdbc

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[The nested Jdbc implementation](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/todos/TodoNotices.java#L48) reads one conversation's rendered-list hash/compaction reach, upserts that acknowledgement and deletes it on forget. TodoBoard.noticeFor first loads/renders the list, resolves current compaction, then compares Seen; noticed acknowledges only after notice delivery. [V47](../../plowshare-server/src/main/resources/db/migration/V47__todo_notices.sql#L11) makes the conversation row unique. No missing index or need for a stored routine is established.

First reuse the already loaded todo list and compaction snapshot during prompt composition. Avoid calling noticeFor twice for the same valid assembly. `remember` is not an eager write-through cache of the desired notice: the nonempty path must not become acknowledged before actual delivery. An unchanged acknowledgement may avoid a no-op update if semantics for noticed-at are explicitly preserved; do not infer that timestamp is unused without checking callers.

Initially keep Seen operation-local because stale acknowledgement can suppress a notice. Shared tracking needs database/conversation/projection and durable notice generation, not list hash alone: forget followed by the same hash must become visible. Dependencies include notice-row existence, todo-list generation and compaction reach. Every remember/forget, todo/stage mutation and compaction writer must invalidate dependent assembled views after commit. Negative reads must be invalidated on insertion. Current conversation access gates disclosure; listener delay cannot authorize protected context. On reconnect bypass until the notice/list/compaction baseline recovers. Use the shared bounded LRU and coalesce identical loads.

Mock delayed acknowledgement, explicit forget, unchanged content with new compaction and one-read composition budgets. Only changed persistence/notification behavior requires focused PostgreSQL upsert/delete, rollback and missed-invalidation recovery tests. No automatic notice-delivery replay should follow an uncertain response.
