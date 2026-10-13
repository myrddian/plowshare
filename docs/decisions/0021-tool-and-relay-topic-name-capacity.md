# Tool and Relay topic name capacity

Status: accepted; explicitly requested as platform work alongside the MCP adapter.

## General platform need and authorization

The user explicitly requested Plowshare tool names up to 256 characters and Relay
topic names up to 1,024 characters for longer hierarchical paths. These are shared
platform contracts for all providers and Relay users, independently of MCP. This
is separately authorized platform scope under ADR 0001, not an adapter exception
inferred from convenience or a failing integration test.

## Decision and alternatives

Expand tool declarations, invocation decoding, exact grants and dynamic agent
configuration consistently across server and SDKs. Expand topic names in public
operations, inspection/control, configured routes, repository values and SQL
constraints. Retain the existing ASCII naming grammar and separator semantics.
Parameter names, provider names, accounts, agents, subscriber/group names, catalogue
counts, byte envelopes and schema/value budgets retain their own contracts.

An SDK-only workaround cannot change server admission or SQL checks. Truncating
names or hashing topic segments would introduce a second identity/mapping rule,
change readable routes and risk mismatched exact grants. Longer existing-format
routes preserve the original name end to end. Adapter recommendations remain
configurable policy: the default upstream MCP name budget is 128, with larger
values supported within the shared portable metadata profile. The offered-name
budget defaults to the platform's 256-character ceiling and may be tightened by
operators for downstream consumers with smaller name budgets.

## Authority, compatibility and verification

Names remain identifiers, never permission. Membership, owner/executor fences,
exact grants, fingerprints, leases, approvals, accounting and original invocation
receipts are unchanged. Existing names/topics and routing retain their exact values;
no automatic retry, alias grant or new execution ledger is introduced. Old journal
generations remain readable and read-only reconciled.

Use a new Flyway migration to widen topic and publication-target constraints;
never edit shipped migrations. Existing rows remain valid without rewriting data.
An older runtime/SDK may refuse newly admitted long names. Rolling back requires
checking retained long-name data first; widening cannot safely be undone by simply
restoring the old constraints.

Verify 256/257 tool-name and 1,024/1,025 topic-name boundaries, exact grants,
invocation and receipt routing across Java/TypeScript/native SDKs. A focused
PostgreSQL test is required for the widened migration constraints and long-topic
publication, consumption and inspection. Use mocks for runtime behavior and run
repository, SDK conformance and installed-consumer checks.
