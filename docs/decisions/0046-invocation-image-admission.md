# 0046: Invocation-bound image admission in the existing disk store

Date: 2026-10-22

Status: Implemented, with new admission disabled by default. Result/trajectory
consumers remain separate work.

## Decision

Extend `POST /v1/images` with `invocation-image/1` and add producer-only WebSocket
`artifact.admission.status`. Preserve ordinary upload fields, destinations, formats
and response. Reuse `ImageStore`, sidecars, layout and scoped `img_` identity.

Use narrow admission, authority, repository and snapshot interfaces. Derive
owner/project/executor from the retained Relay invocation and reuse current
registry/hosted-generation/credential/Application checks. Never impersonate the
owner or grant executor project membership. Admit project PNG only, sixteen slots
per invocation, with stable request IDs and immutable server-derived receipts.

Migration V224 adds invocation-owned attachment/hold rows. Existing owner/provider
provenance comes from the invocation; display metadata stays in sidecars. No blob,
new job, queue, worker or parallel execution ledger is introduced.

Inspect/finalize outside SQL. Recheck authority and lock the invocation before
receipt commit, serializing with settlement. Return the original identical receipt;
refuse conflicting reuse. Verify full digest and sidecar on status. All disk writers
share JVM/OS fencing, atomic publication and forced files/directory ancestry.
No mutable workspace source substitution or generic finalized-image collector.

Worker socket availability and the tool waiting deadline do not end captured-output
recovery. Current durable generation/credential/Application authority still applies;
settlement fences new slots. Admission never acquires an execution credential or
creates another external effect.

## Scope

Admission does not deliver media through tool results or model calls. Later
result/trajectory transactions must acquire their own retention links and enforce
source invocation identity. Binary reads, result envelopes, context admission and
native audio/video remain separate. Shared status contracts/generated SDKs advance
together; Java/Node binary helpers ship, with other native authoring deferred.
See the [operational contract](../invocation-image-admission.md) for configuration,
authority, filesystem durability and uncertain-delivery recovery.

## Verification

Focused tests cover producer/owner separation, strict HTTP/WS boundaries, legacy
uploads, identical/conflicting retries, missing/same-length-corrupted files,
workspace promotion, shared writers and hosted restart/late-output recovery.
Six PostgreSQL tests verify migration V224, constraints, row mapping, rollback and
settlement serialization. Only that selected database suite used `-PfullDb`.

`format`, `formatCheck check` and `sdkCheck` passed. Native conformance passed for
Node, Python, .NET and Go using existing local toolchains. These are local tests;
deployment and screenshot-to-active-model acceptance remain outside this slice.
