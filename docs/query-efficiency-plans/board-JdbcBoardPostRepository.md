# JdbcBoardPostRepository

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[`lockAndRead`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/board/JdbcBoardPostRepository.java#L33) takes a transaction advisory lock derived from account/request UUID, compares JSONB action identity in `board_post_receipts`, and returns the previous message or rejects payload mismatch. `save` inserts the receipt; board posting must keep message, wake obligations and receipt in one transaction. [V89](../../plowshare-server/src/main/resources/db/migration/V89__board_post_receipts.sql#L2) supplies the exact account/request primary key.

Retain the authoritative locking read even if a receipt DTO is already in RAM: it establishes idempotency against concurrent servers. A completed receipt may be reused within the same locked operation, but cache absence must never authorize another post. There is no demonstrated index gap. Consider one typed stored routine for lock-and-compare only if round trips are material; it must use the existing transaction and preserve mismatch behavior, not own a separate commit.

Initially exclude this mutation-admission path from shared caching. A read-only completed-receipt projection could later use database/account/request/action-fingerprint/projection keys and immutable message identity, after commit, with current board access checked separately. Cover receipt insertion/deletion and message/topic visibility changes in after-commit invalidation; negative entries are especially unsafe. Reconnect bypasses uncertain receipt state. The shared bounded LRU must never replace advisory locks or definitive receipt reconciliation after an uncertain send.

Mock same-request reuse and mismatch behavior. Only changed persistence requires focused full-db two-writer, hash-lock collision safety, transaction rollback and exactly-one-message tests. A hit-rate benchmark cannot establish idempotency correctness.
