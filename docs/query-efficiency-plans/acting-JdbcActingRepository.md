# JdbcActingRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/JdbcActingRepository.java#L276) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/acting/OwnedActingTokens.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V149__acting_grants_and_tokens.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

The acting owner checks active accounts, Application heads/declarations, eligibility, proofs, consent tokens and mutation receipts under the shared account-lifecycle advisory lock. Confirmed N+1: `tokens(subject)` selects up to 256 IDs, then `token` loads each row and its project set: up to 513 queries. `proof` also resolves digest to ID before the same two reads. Eligibility replacement and activation loop over row inserts.

Implement one bounded token-plus-project-set projection, preserving deterministic subject ordering and complete empty project sets. Resolve a proof directly to that typed projection. Batch declaration/eligibility/token-project writes under the existing transaction, preserving validation, uniqueness and audit atomicity. V149 already supplies subject/created-at ordering and digest uniqueness; measure joins and eligible-project membership paths before indexes. A table-returning routine is reasonable for a cohesive proof snapshot, but live policy checks remain distinct and current.

Do not globally cache proof digests, token credentials or live consent eligibility. Reuse decoded typed policy/token DTOs only inside a valid authorization transaction. Non-secret deployed grant descriptors may eventually use database/Application/revision/grant keys under bounded LRU, with live active-head and authority fences. Activation, eligibility changes, token revoke/expiry, account lifecycle and membership changes invalidate appropriate descriptor and subject/Application collections after commit, including deployment writers. Durable generations and reconnect bypass precede shared admission.

Mock a constant query budget for 0/1/256 tokens, proof refusal and no leaked subject on denial. Focused PostgreSQL tests cover batched projection equivalence and atomic audit/rollback only when implemented.
