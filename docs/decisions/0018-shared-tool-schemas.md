# Shared schemas and recursive provider arguments

Status: accepted for the schema foundation; MCP hosting remains a separate change.

## General platform need

Model dispatch already had a typed nested schema vocabulary, while public SDK
providers offered a flat scalar parameter list. Discovery alone could describe
nested inputs, but invocation and handler codecs could not carry or validate
objects, arrays or explicit null. This affects any external provider requiring
structured input, independently of MCP.

Under [ADR 0001](0001-integration-boundary.md), this is a separately authorized
platform capability. An SDK-only adapter cannot extend the admitted server schema
or scalar invocation contract. Flattening inputs or encoding an object as a string
changes its public meaning. Integration code continues to consume public SDKs.

## Decision

Move `SchemaDefinition` and its pure structural `SchemaCodec` into
`plowshare-protocol`; model dispatch and Java SDKs consume the same type. Generate
native typed contracts from the Java keyword inventory, with a checked field-kind
mapping. Keep schema vocabulary separate from an explicitly documented bounded
executable profile. The profile is owned and maintained with the protocol and
cross-language fixtures; it implements only its declared JSON Schema 2020-12
keywords, and does not claim a general-purpose validator. Patterns and external
resource/reference semantics are refused; format remains an annotation.

Add immutable recursive `ToolValue` inputs and optional `inputSchema` declarations
to the existing Relay tool facade. Keep scalar convenience constructors and
version-one declarations/requests unchanged. `IntegrationPayload.Parameter`
remains scalar: using its existing scalar variants does not widen integration/1.

Use catalogue/2 for schema-bearing snapshots and tool/2 for their requests, with
a canonical schema fingerprint. Preserve the independent tool/1 text result
profile. Retain exact schema, version, routing and request in the existing invocation
record; no new registry, runtime, durable ledger or database migration is needed.
Server offering/call checks and SDK handler admission validate the complete typed
contract. A changed declaration refuses the call before external execution.

Spring deployment configuration uses a narrow JSON-text-to-schema converter;
dynamic catalogues and Application settings retain schema objects. This exception
belongs at the configuration boundary and never changes handler argument types.
Provider construction snapshots caller-owned schema trees. Unsupported keywords,
malformed values, cycles and exhausted budgets fail before effects.

## Authority and lifecycle

Existing provider account bindings, project membership, scope connections, grants,
approvals, leases, accounting ownership, Relay ancestry and invocation identity
remain authoritative. Schema validation confers no authority. Exclusive provider
journals still record execution intent before the handler, and recovery never
reexecutes uncertain work. Per-user MCP credentials and MCP service-account
connection bindings are not introduced by this foundation.

## Compatibility and verification

Scalar providers, including network privacy, retain their catalogue and receipt
formats. Java convenience constructors and optional fields in TypeScript, Python
and .NET preserve scalar callers. Go keyed literals are compatible; unkeyed
`ToolDeclaration` literals need the added optional field. Public SDKs depend on
protocol contracts, never server implementation classes.

Shared nested/scalar fixtures verify decoding, explicit null, constraints,
compositions, references, unsupported features, portability, bounds and canonical
fingerprints. Provider fixtures verify unchanged nested arguments, changed-schema
refusal and duplicate/uncertain-effect behavior in each SDK. Existing registry,
invocation and network-privacy checks cover the reused mechanism. Run formatting,
repository checks, SDK conformance and packaged SDK checks. Persistence behavior
is unchanged, so mock repositories suffice and PostgreSQL is not enabled.

Roll out supporting servers and SDKs before publishing nested tools. Rollback
requires withdrawing nested catalogues and reconciling retained v2 work with a
supporting SDK; an older implementation must refuse rather than reinterpret it.
Model backend schema limitations remain explicit dispatch concerns.

The exact supported profile, limits and migration details are in
[Shared tool schemas](../tool-schemas.md).
