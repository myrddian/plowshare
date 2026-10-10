# Relay processing and topic-policy authority

Status: accepted following explicit authorization of this platform permission change.

## Need and boundary

Routine Relay processing required contributor access, but the presence of any
`Relay/topics.json` declaration also required manager access before discovery or
consumption. Least-privilege service accounts and background workers were refused
even when they requested no policy change. This affects every project using explicit
retention policies, independently of any integration or external tool provider.

The public SDK cannot separate these internal authorization paths. Granting manager
access to every processor broadens authority, and omitting policy files removes the
operator's declarations. Neither is an appropriate SDK-only substitute. External
collectors and adapters remain public SDK consumers; no adapter imports runtime
services or assumes the server's filesystem.

## Decision

Source validation and current contributor/source access determine whether a processor
may discover and consume an active subscription. Policy declarations alone add no
manager requirement. Discovery remains free of broker effects.

During processing, a current manager may apply the explicit policy for an active
subscription topic through `Relay.configureTopic`. Other processors use
`Relay.registerTopic` with the standard default: registration preserves an existing
policy and introduces a new topic with the platform default. They never pass a
source-controlled policy to broker registration or configuration. Edited declarations
remain unapplied until a manager pass; `relay.log` reports effective stored policy.
Inactive packages and policies for unsubscribed topics cause no writes.

This separates processing authority from policy-write authority without adding a
new capability, HTTP path, request field, or client-specific permission workaround.
Membership and source access remain fresh per pass; manager authority is checked
at policy-write admission. Processing retains the authenticated account rather than
assuming a human manager or SYSTEM identity. Revocation prevents subsequent admission,
not already admitted effects.

Distributed consumer leases, branch claims, pinned source, cursor advancement,
retention gaps, cancellation and uncertain-effect reconciliation keep their owning
contracts. No mutation is replayed, no migration is needed, and persistence continues
to use the existing broker repository operations.

## Compatibility and verification

The same `relay.process` operation and worker interface are shared by all clients and
SDKs. Managers retain explicit policy behavior. Contributor passes now run when source
contains policies, while their existing/default broker policy remains authoritative.
Ordinary file edits do not acquire manager authority.

Mocked tests cover discovery and processing as a contributor, changed declarations
without policy writes, manager writes before consumption, manager/contributor
revocation, inactive subscriptions, lease fencing, retained work and gaps. Normal
repository checks remain required. No real database test is needed because SQL,
transactions and broker registration semantics are unchanged.
