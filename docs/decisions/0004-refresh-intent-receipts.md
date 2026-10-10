# Bounded receipts for duplicate refresh delivery

Status: implemented for review.

An abrupt connection-close browser fixture observed multiple physical refresh
arrivals for one instrumented fetch. The existing single-use refresh rule rotated
on the first arrival and revoked the session on the next. Client locks and a
no-retry policy cannot enforce exactly-once HTTP delivery. This is a general
authentication transport fault, independent of work or integration APIs.

`POST /v1/auth/refresh` now accepts optional `X-Plowshare-Refresh-Intent`, exactly
one canonical lowercase random UUID (version 4, RFC variant). Malformed or repeated
headers receive 400 before any credential is spent. Absence retains the legacy
single-use contract. The browser generates one intent per new fetch; the neutral
TypeScript SDK accepts an optional validated intent from its caller, which owns
platform cryptography. Legacy SDK callers remain compatible. Callers do not
save or deliberately resubmit the intent after
uncertain delivery. HTTP redirects remain refused by the console.

A first delivery atomically spends the parent, issues a successor pair and records
reconstruction metadata under the existing chain lock. An identical parent and
intent can return that same pair for a fixed maximum of thirty seconds, shortened
by either token lifetime. A duplicate never issues new grants, extends the chain
or extends cookie expiry. PostgreSQL receipts survive restart and serialize
across instances. Ephemeral bootstrap sessions use the same semantics under the
existing in-process rotation lock.

Before returning a receipt, the store checks the live chain, current enabled
account/session version and an unspent, unexpired successor refresh grant with
its matching access grant. Logout, password reset/account revocation, a later
rotation, a different/missing intent or the receipt deadline prevent recovery.
Unexpired spent-token reuse outside the identical-intent exception still revokes
the chain. Old clients continue to use that original rule. This does not provide
application mutation replay or a general session-reconciliation endpoint.

The receipt stores the parent digest, intent, a random server-only 192-bit nonce,
issuance time and fixed deadline. Successor tokens are 192-bit truncated
HMAC-SHA256 outputs, keyed by the presented parent secret with versioned,
domain-separated access/refresh labels, the intent and nonce as input. Only
successor digests are retained as grants. Neither stored metadata alone nor the
parent secret and public intent alone reconstructs a successor. Metadata is never
returned or logged. Expired receipts are inaccessible immediately, pruned on the
chain's next rotation and cascaded when their parent grant/chain is removed;
in-memory cleanup uses the existing bounded sweep budget.

A captured *entire* parent-and-intent request can obtain the same live pair within
this window. The server cannot distinguish that replay from physical duplicate
delivery. This narrow, non-sliding exception is the explicit security tradeoff;
it is not a broad spent-token grace interval. TLS and protected cookie/token
handling remain necessary. A silent broad reuse allowance, plaintext response
cache, browser mutation retry and speculative work-runtime API were rejected.

Verification covers simultaneous deliveries across server instances, restart,
original cookie expiry, differing/absent/expired intents, successor spending,
logout and account version/disable fences, and atomic rollback of failed receipt
persistence. The PostgreSQL tests are required for migration, row locking and
transaction behavior; ordinary behavior remains covered without a database.
