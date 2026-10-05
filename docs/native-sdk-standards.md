# Native SDK standard

Python, Go and .NET follow [coding standards](coding-standards.md). Their public
contracts are generated from the same request, response and notification graph as
the TypeScript SDK. `scripts/generate-sdk-contracts.mjs` owns generation; never
edit generated DTOs, catalogs or schema resources by hand.

## Public contracts

Every registered operation pairs a concrete request DTO with its result DTO.
Python accepts generated `Request[T]` instances and returns `Reply[T]`. Go exposes
an operation method taking its request struct and returning `Reply[Result]`.
.NET exposes an operation extension taking its request record and returning
`Reply<Result>`. Public operation and notification APIs must not expose JSON DOMs,
raw bytes, arbitrary dictionaries, `Any`/`object` payloads or a string-operation
escape hatch. Named, recursively typed dictionaries remain appropriate for
protocol extension data and scalar integration parameters.

Use explicit nested models and closed unions. Python uses frozen dataclasses,
immutable tuples and checked mappings. Go unions select exactly one typed variant;
.NET unions use typed factories. An omitted field differs from explicit null:
Python uses `UNSET`, Go uses an omitted pointer or a selected null union variant,
and .NET uses `Optional<T>` or a selected null union variant. A valid nullable
result and an operation without a result body remain valid successes.

Raw JSON is private to the owning codec and transport. Before a write, validate
the complete request, including nested fields, required fields, enums, bounds,
identifiers and selector relationships. Refuse unknown input fields. Decode and
project successful responses before returning them; tolerate additional response
fields for forward compatibility while exposing only the declared DTO fields.
Keep refusal codes and text explicit; `require_payload` / `RequirePayload` raises
a refusal instead of returning an unvalidated diagnostic object. Do not expose a
payload that callers can read around that check.

Portable boundary constraints live in `scripts/native-sdk-constraints.mjs`.
Update constraints, shared fixtures and all three owning codec implementations
together. Validation errors identify the contract violation without logging
credentials or complete payloads. Preserve free-form text; never repair malformed
inputs by coercion, trimming or stripping. Server authorization, workspace fences,
cron interpretation and durable state transitions remain server responsibilities.

## Transport ownership

Require an explicit HTTP(S) origin and caller-managed bearer. Reject authenticated
upgrade redirects. Bound pending requests, frames, notification queues and waiting
time. Validate notification variants and count queue overflow; malformed hints
must never reach application consumers. Closure must wake notification readers.
Notifications are hints; reconcile completion and dropped hints with durable reads.

A request timeout or cancellation after submission has unknown delivery. Never
reconnect or replay a mutation automatically. Keep `NOT_SUBMITTED`, `UNKNOWN` and
`INVALID_RESPONSE` distinguishable, including cancellation races. Caller
cancellation does not cancel server work; use the explicit cancellation operation.
Retain caller-owned receipt UUIDs before submitting durable work.

## Compiler checks and tests

Ordinary `check` runs public-boundary guards without native toolchains.
`sdkCheck` is the explicit mandatory native SDK gate. It checks generated files,
Python strict mypy and Ruff across source and tests, .NET nullable/warnings-as-errors
compilation, Go vet and race-enabled tests, shared DTO boundary cases and real
WebSocket transport conformance. These tests require no database. Ordinary server
`check` remains usable without installing Go and .NET. Any SDK or shared-contract
change must run both `check` and `sdkCheck`; packaging changes must also run
`sdkPackageCheck` against fresh installed consumers. See [SDK setup](sdks.md).

Do not weaken compiler checks, add blanket suppressions or retain a raw API to
satisfy old tests. Shared fixtures must represent actual operation contracts;
malformed wire data belongs in boundary tests. Test nested invalid fields, absent
versus null, refusal outcomes, ignored future output fields, invalid notifications,
redirect refusal, cancellation, closure and absence of automatic replay. Compiler
checks do not prove a deployed integration works.
