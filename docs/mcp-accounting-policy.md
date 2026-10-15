# MCP identities and accounting

The proposed policy is [ADR 0025](decisions/0025-service-account-attribution-and-spending-pools.md).
Its spending-pool capability is a proposed general platform decision; enforcement
is not implemented by the MCP client work.

A Plowshare model call belongs to the admitted execution account. Choosing a
service-owned MCP authorization changes the identity presented to that MCP server;
it does not transfer the Plowshare job, model charges, Personal access or tool grants.
The provider executor receives only its authorized connection/invocation context.

For service-owned work, use the actual `@service/<token UUID>` execution principal
and an authorized Application/schedule/admission. A named service owner is not that
principal. Rotation keeps the token UUID, retires old sessions and preserves usage
identity; creating another token gives another principal. Application-owned
credentials rotate through `application.token.issue` and original receipts.

Server authorization may follow its owner between clients. Native HTTP/STDIO
credentials stay with their client instance. Calling a native MCP tool directly
creates no server model usage. Explicit sharing does not change the admitted server
job's identity or manufacture cross-account authority.

## What is available now

- Per-execution model-call budgets, model capacity pools and durable usage/cost
  metadata are implemented. They have different owners and purposes.
- Server MCP credentials and bindings retain exact owner, subject, executor and
  invocation authority. Secrets and worker tokens do not select a budget owner.
- Prices and observations can be unknown. Tool execution receipts and vendor
  annotations are not reported monetary charges or model-token usage.
- Separate enforced account spending pools are **not implemented**. Do not promise
  a service-account money cap from a credential binding or `usage.pools` report.

## Proposed direction

Build one general server-owned spending-pool capability with explicit principal
membership and conservative per-attempt reservations. Share a pool between service
principals only by authorized configuration; a new token receives no inherited
membership by matching its service-owner name. Preserve original model attribution,
price snapshots, retries, unresolved liabilities and historical records.

The implementation must cover atomic concurrency, current authority, delegated
and internal paid work, cancellation before/after dispatch, restart/late settlement,
policy revision/periods and role-limited reporting. A model spend allowance cannot
bound external MCP vendor invoices without a trustworthy separately reviewed meter.
See the ADR for required refusal behavior and the separately scoped delivery gates.
The acting-grant proposal remains a separate unimplemented decision.

## Source and test map

| Policy boundary | Existing implementation / behavior fixture |
| --- | --- |
| Rotation preserves principal; another token has a new identity | [`ServiceAccountIdentityPolicyTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/auth/ServiceAccountIdentityPolicyTest.java), against real `ServiceAccounts` and mocked repositories |
| Sharing capacity does not combine human/service/SYSTEM owners; unpriced is not zero | [`ServiceUsageOwnershipTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/ServiceUsageOwnershipTest.java), capturing real `DurableInferenceAccounting` admission metadata |
| Authorized service subject does not replace caller or provider worker | [`OwnedHostedToolScopesTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/relay/tools/OwnedHostedToolScopesTest.java) |
| Selecting another subject requires administrator and live subject/project authority | [`ProviderBoundariesTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/providers/ProviderBoundariesTest.java), [`CurrentProviderCredentialGrantsTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/providers/CurrentProviderCredentialGrantsTest.java) |
| Call/attempt distinction, unknown observations, metadata recovery and no inference replay | [`AccountingRecorderTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/llm/accounting/AccountingRecorderTest.java) |
| Shared execution-tree call limits and durable claim failure | [`BudgetTest`](../plowshare-server/src/test/java/io/aeyer/plowshare/server/agents/BudgetTest.java) |

These are mocked/in-memory/local-journal behavior tests, not PostgreSQL reservation
or real organization/OAuth acceptance. No reservation schema or dispatch behavior is
changed by this policy task. Future persistence implementation needs its own focused
SQL/locking verification; broad database runs are not a prerequisite for this decision.
