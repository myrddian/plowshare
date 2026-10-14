# Catalogue payload capacity is independent of transport chunks

Status: accepted; the user explicitly authorized expanding or removing the 64 KiB catalogue ceiling.

## Platform need and alternatives

Live catalogues describe a complete provider snapshot. Multiple valid schemas or
one larger schema can exceed a transport chunk. The segmented SDK protocol already
reassembles 64 KiB ranges into a larger complete message. A chunk size does not
justify a separate catalogue admission ceiling. This is general platform work
under ADR 0001, separately authorized from implementation of the MCP adapter.

An SDK-only increase fails while server admission retains the old ceiling.
Splitting one catalogue into independent publications fails because the newest
snapshot replaces the whole previous catalogue; merging fragments would require
another catalogue protocol and lifecycle. Reuse the existing complete publication.

## Decision

Remove the 64 KiB live-catalogue guards across server and all SDKs. Relay TEXT owns
the complete payload limit: 50 MiB portable UTF-8 ceiling and a deployment admission
policy defaulting to 5 MiB. Segments remain 64 KiB and complete encoded SDK messages
remain bounded at 320 MiB. JSON escaping and envelope bytes count independently.
Align standalone Go/.NET schema decoding and legacy schema/binding configuration
with the Relay payload ceiling so one supported schema may exceed one chunk.
Schema structural budgets and field constraints still apply.

MCP catalogue publication uses a configurable UTF-8 budget defaulting to 1 MiB,
with a ceiling inherited from Relay TEXT. Operators must also fit deployment Relay
policy and private recovery-file budgets. Catalogue count, grants, schema profile,
results, requests and authoring-source files retain their independent contracts.

## Authority, compatibility and verification

Publication remains atomic and authenticated to the same provider/project route.
The same whole-catalogue validation, newest-position selection, exact grants,
leases, fingerprints, receipts, accounting and uncertain-delivery rules apply.
No truncation, fragment-level catalogue admission or automatic replay is added.
New migration V142 widens the retained invocation binding constraint to the
existing Relay encoded-payload ceiling (6 * 50 MiB + 1 KiB), with matching typed
repository preconditions. This reserves JSON/routing headroom without a one-chunk
storage ceiling. Old catalogues and retained invocation rows remain
valid; older SDKs/runtimes can refuse newly published larger catalogues. Resolved
MCP budgets are part of offered identities: the new default creates a new contract
name where the operator has not explicitly retained the old budget. Existing
receipts remain pinned and read-only reconciled; exact grants require deliberate
updates for changed names.

Verify larger-than-chunk catalogues through server admission and every SDK facade,
including withdrawal, lost-publication handling and absence of handler execution.
A common larger schema fixture verifies parsing, argument checks, round trips and
fingerprints across SDKs. Existing segmented transport tests verify delivery.
Reject malformed/duplicate declarations and operator-budget overflow. Run mock
repository checks, native SDK conformance and installed-consumer verification.
A focused PostgreSQL test verifies migration preservation, large retained schema
row mapping, idempotent submission, completion/recovery and ownership fences.

## Schema fingerprint compatibility

Capacity verification exposed the Java canonical-key ordering mismatch introduced
by `5a36835`. The separately authorized [fingerprint correction](0023-schema-fingerprint-key-ordering.md)
aligns new hashes with UTF-8 key ordering and validates historical Java hashes
without rewriting retained requests or replaying uncertain work. The capacity
fixture now uses ordinary field identifiers rather than the temporary fixed-width
workaround, so it also covers the prefix-key regression.
