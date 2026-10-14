# Align schema fingerprints and preserve historical requests

Status: accepted; the user authorized correcting the shared schema identity mismatch.

## Platform need and decision

Commit `5a36835` introduced Java schema hashing that sorted complete string tokens,
including their trailing semicolon. Other SDKs sorted UTF-8 key bytes, as the shared
contract requires. Prefix-related property or definition keys could therefore
produce different hashes for identical schemas and refuse a valid provider call.
This affects every nested-schema provider, independently of MCP. An adapter-only
workaround cannot correct server emission or other SDK admission; renaming schema
properties would change the public input contract.

Sort Java object keys by lowercase UTF-8 hex without token delimiters, recursively.
This matches unsigned UTF-8 byte order, including prefix keys and supplementary
Unicode characters. Keep every other canonical token and SHA-256 behavior unchanged.
New server requests and public fingerprint generators emit only the corrected hash.

All SDK provider validators accept either the canonical hash or the historical
Java hash computed from the exact offered schema. Historical hashing is a
validation-only compatibility path, not a second emission format. Both identities
include all constraints and annotations; accepting historical ordering does not
permit a changed schema, an arbitrary hash or a malformed argument tree. No wire
version, authority grant, public operation or persistence migration is added.

## Retained work and rollout

The schema hash is distinct from the durable invocation's fingerprint, which hashes
its execution coordinates, serialized binding and arguments. That definition and
its JSON serialization remain unchanged, so duplicate submissions still recover
the existing owning record. The original retained request, deadline, hash, ancestry,
account and journal receipt remain authoritative and are never rewritten.

Providers still compare complete request text for a duplicate invocation. Historical
and canonical requests are not interchangeable for an already journalled identity.
Interrupted execution becomes UNKNOWN; accepting its schema hash never authorizes
handler execution, reconnect replay or a new publication. Result reconciliation
retains its existing owning identities, grants and hosted executor fences.

Upgrade provider SDKs before the server starts emitting corrected hashes. Updated
SDKs support both old servers and new servers. Old Java SDKs can refuse corrected
hashes for affected schemas; they must be upgraded before those calls resume.
Unchanged schemas without the ordering discrepancy retain their existing hash.
Rollback does not rewrite receipts or authorize retries of unresolved calls.

## Verification

Shared fixtures pin canonical and historical hashes for prefix keys in properties,
references and nested objects, and Unicode keys whose UTF-8 and UTF-16 order differ.
Every SDK rejects unrelated hashes and changed-schema historical hashes. The larger
schema fixture again uses ordinary unpadded field names, removing the temporary
capacity-test workaround.

Provider tests cover canonical calls, historical calls, duplicate delivery and
historical interrupted receipts. Assert exact request preservation, changed-schema
refusal and no handler replay. Mocked server repository tests verify corrected
emission and unchanged read/duplicate-submission recovery of historical requests.
Run formatting, repository checks, native SDK and packaged-consumer checks.
Persistence schemas and SQL are unchanged; PostgreSQL tests are unnecessary.
