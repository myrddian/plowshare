# JdbcConversationContextRepository

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[`ownership`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcConversationContextRepository.java#L19) already joins conversations to projects once and returns typed `Ownership` containing owner, registered project identity/name and personal status. Context authorization consumes the interface; this is a useful existing combined projection, not an N+1 implementation. Conversation/project primary keys support the join. No index or stored routine is indicated.

First thread this snapshot through the same context authorization operation instead of asking `ConversationStore.ownerOf`, resolving the home, and hydrating it again. Preserve the distinction between missing ownership and a global home. Do not replace live membership, account or grant checks with ownership alone.

Cross-operation caching is lower priority because ownership participates in access control. Any tracked immutable `Ownership` must be keyed by database, conversation, projection and source ownership/project generation; these version fields must be returned by the same SQL before shared admission. Depend on conversation owner/home and project personal identity/name. Cover conversation creation and all owner/home/project writers with after-commit invalidation and invalidate prior absence on creation. A current authoritative ownership/authority fence remains required for protected context; asynchronous notifications cannot grant a revocation lease. On reconnect bypass until recovery completes. The shared bounded LRU should host this only if it avoids meaningful hydration work.

Mock that one ownership result is reused throughout a valid operation and that revoked access is still refused. Only a changed SQL/version contract needs focused PostgreSQL join, null-home and notification/rollback tests.
