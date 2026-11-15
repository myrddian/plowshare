# Working on migrations

Read [persistence and cache](../../../../../../.agents/guides/persistence-cache.md) before changing schema or SQL functions.
For permission predicates or grant semantics also read [AUTH](../../../../../../.agents/guides/auth.md).
Never edit shipped migrations. Check Flyway version collisions after integrating
main, even if Git reports a clean merge. PostgreSQL verification must target
the actual migration/SQL behavior under the root database-test policy.
