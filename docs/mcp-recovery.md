# MCP cancellation and recovery

MCP uses the existing server invocation and SDK provider journal. A protocol request
ID is correlation, not proof of idempotency. Cancellation, timeout, reconnect or a
new credential must never turn an uncertain effect into another `tools/call`.
Known local cancellation and process interruption are distinguished in safe receipt
text while retaining the existing `UNKNOWN` state and original invocation identity.

## Outcomes and cancellation

| Observation | Retained meaning |
| --- | --- |
| Refused before MCP dispatch | `REJECTED`; no upstream call was sent. Examples include invalid ownership/schema, unavailable credentials, expired deadlines and an already-aborted caller signal. |
| Valid final response, including upstream `isError` | `COMPLETED`; the response was received. An upstream tool error is not a local pre-send refusal or proof of rollback. |
| Submitted call loses its response, is aborted, times out or cannot validate its response | `UNKNOWN`; effects may have happened. Keep the original receipt and do not replay. |
| Server run stops waiting after submitting a provider invocation | Its wait ends as unknown. The original worker may still deliver a valid result, which the owning read can reconcile. |

Direct native callers can supply an abort signal. Closing a host/client also stops
intake and aborts its active request. HTTP closes the active request/response stream;
the pinned STDIO client sends `notifications/cancelled` for that exact MCP request.
These are supported cancellation mechanisms, not acknowledgements of termination
or evidence that an upstream mutation was undone. Native shutdown also closes the
owned process tree using the configured grace period and platform policy.

The direct client combines caller cancellation, its own lifetime and known
transport closure. A closed transport refuses new effects. Failed, aborted or
changed discovery retires the context; a late discovery page cannot renew it.
New availability requires a new explicit context, without adopting or replaying
old invocations. Received valid results retain their actual outcome; cancellation
does not relabel an already received tool error as a pre-send refusal.

**Immediate server-run cancellation is not forwarded to the MCP worker.** The
current public provider contract stops the server's wait but supplies no
exact-invocation cancellation advisory to the worker. Its existing execution
deadline and host/process shutdown still apply. Polling another account's job or
inventing an internal endpoint is not an authorized substitute. A general worker
advisory requires separate platform design and authorization.

## SDK receipt recovery

The original request, input fingerprint, invocation identity and hosted worker fence
stay in the SDK receipt. Private arguments/results may contain sensitive content;
credentials and upstream session tokens are not recovery metadata. Retain the
configured journal directory and its immutable catalogue archive.

| SDK phase | Recovery under the existing contracts |
| --- | --- |
| `executing` | Durable intent exists, but outcome is unknown after the former process exits. Under the exclusive lock, validate its owner/contract and durably retain `ready` with an `UNKNOWN` result before opening new MCP sessions. Preserve the original request and worker fence. |
| `ready` | The outcome is already retained. Keep it unchanged. An active matching provider's normal SDK pass can submit that original result without running its handler again. |
| `publishing` | Delivery may have succeeded. Read the original owning result through the SDK; only a verified matching result settles the receipt as `done`. Do not repeat publication after uncertainty. |
| `done` | Keep the settled outcome. Repeated recovery has no new effect. |

Server-hosted startup acquires its existing binding lock, validates archived
catalogues and performs server-side read-only reconciliation. Local journal writes
record interrupted work as UNKNOWN; they do not create execution or publication
authority. Native startup similarly validates ownership under its exclusive host
lock before recording interruption. A retained UUID cannot be reused with changed
arguments, deadline, catalogue or credential context.

Recovery never polls a retired catalogue, opens MCP on its behalf or deletes its
unknown receipts. The current SDK's `reconcile()` handles uncertain publication;
`poll()` also admits new requests. There is **no delivery-only SDK pass** for a
retired `ready` result. Such results, including recovered UNKNOWN, remain retained
for inspection rather than calling `poll()` on retired tools or duplicating the
SDK publication state machine in the adapter. This general SDK capability needs
separate design/authorization.

Pending registration/catalogue metadata has no public owning receipt read after a
socket is lost. Its retained intent blocks automatic restart. Inspect the original
UUID, current server state and lease expiry before an operator deliberately clears
that metadata intent. Do not remove invocation journals to bypass this refusal.
A leftover exclusive lock likewise needs proof that its owning process is gone;
restarting while another process holds it is not recovery.

## Late results and operator inspection

Stopping a server run's wait does not commit a fictitious provider result. A valid
late response published under the original invocation/worker coordinates can still
be retained and read without resubmitting work. Lost publication replies reconcile
against the original result identity and exact outcome, retaining its occurrence
time. Conflicting results remain errors; a completed owning result is not silently
overwritten.

An MCP response that arrives only after its transport has been aborted may not be
available to this synchronous tools profile. Keep UNKNOWN when the result cannot
be verified. Read-only observation is distinct from another attempt at the tool.
Inspect the original server invocation and private SDK receipt, including their
owner, connection/generation, catalogue and worker fence. A successful login,
credential refresh, new session or renewed catalogue does not settle that receipt.

For direct native calls, an explicit restart with the same retained request returns
its known result or UNKNOWN, without another effect. A request from a different
catalogue cannot adopt that UUID. Do not change the request or mint a replacement
UUID as a recovery shortcut.

## Verification boundaries

Independent HTTP/STDIO fixtures exercise pre-send abort, in-flight cancellation,
host close, exact STDIO request cancellation, one attempted effect and explicit
restart without replay for both supported protocol profiles. Journal fixtures cover
interrupted retired contexts, uncertain publication, late verified completion,
immutable request/fence retention, repeated reconciliation and SDK-valid Unicode
envelopes/configured metadata. Existing server invocation tests verify cancellation
after submission and late-result read without resubmission.

These tests use public SDKs, mocked repositories and isolated protocol/process
fixtures. They do not prove deployed organization OAuth, PostgreSQL transaction
behavior, immediate server-to-worker cancellation or retired result delivery.
