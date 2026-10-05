# ADR 0001: Integrations consume the platform without extending core

Status: accepted. This records the user's explicit architecture requirement.

## Decision

An integration that requires core changes to make it work is a failed
integration task. Integration authorization covers adapter-owned translation,
configuration, mappings and state using existing public SDK/server contracts.
It does not authorize changes to server runtime, APIs, tools, persistence,
authorization or shared protocol/SDK contracts.

A2A, Home Assistant and future external adapters run outside core. Production
adapter and Java SDK classpaths must not depend on the server, directly or
transitively. Core server and shared-protocol production classpaths must not depend on integration modules.
Test-only dependencies may verify examples against real server loaders; shipped
adapter distributions must remain independent of those test dependencies.

## Handling a capability gap

Describe the exact unavailable public operation and affected adapter behavior.
Complete independent authorized adapter work and report the blocked capability.
Do not invent an internal endpoint, SQL access, protocol escape hatch, reflective
server access or SDK wrapper around internal services. Renaming an adapter-specific
workaround as a generic API does not establish a platform need.

A proposed exception is a separate platform task, authorized explicitly by the
user before changing core. Record a design decision with:

- The general platform problem, independently of a particular adapter.
- Existing SDK contracts considered and why adapter-owned alternatives fail.
- The smallest proposed shared contract change and affected clients.
- Authorization, ownership, idempotency, accounting, cancellation and recovery impact.
- Migration/rollback, historical-data compatibility and verification requirements.
- The user authorization and the decision's consequences.

Convenience, a deadline, a failing adapter test or a model's recommendation is
insufficient justification. An integration completion claim must identify any
remaining capability gap instead of concealing it through a core patch.

## Enforcement and limits

Run `./gradlew integrationBoundaryCheck` or `./gradlew check`. The dependency
check inspects production compile and runtime resolution, including transitive
server dependencies and server classes in file dependencies, while allowing test-only verification dependencies.
Review must additionally inspect the diff for changes to core and shared
contracts and check separately authorized platform decisions. Dependency
isolation cannot prove the intent or legitimacy of a source change.

See [coding standards](../coding-standards.md), [SDKs](../../sdk/README.md) and
[integrations](../../integrations/README.md).
