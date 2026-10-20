# Query efficiency deployment follow-ups

These changes address findings from the deployment of `b586dc3a`. They cover SQL,
provider dispatch, client authentication, worker shutdown and Manual publication.
The cache remains an additional layer; it cannot repair an expensive cold query,
an unnecessary provider call or an incorrectly published source.

## Legacy document and retrieval reads

`DocumentStore` collection reads now use the same statement-local
`knowledge_readable_selection` relation as the catalogue. Permission selection
precedes counting, ranking and pagination. Typed-content, exclusion, report,
account, scope, retained-authority and vector-generation predicates remain in the
query. Exact document/source-name reads retain narrow permission checks.

`RetrievalService.retrieve`, used by `information.search`, checks for an eligible
passage before submitting an embedding. The gate uses the actual document
selection and, with dual embeddings, one captured profile. An empty gate passes
through the current disclosure/profile fences without creating an inference
source or spending a claim. A nonempty gate grants no read lease: provider
dispatch, accounting and the final authorization fence still run normally.

A bounded, read-only PostgreSQL comparison against the deployed data found the
following warm execution times. These are query measurements, not client or
provider latency guarantees:

| Query shape | Per-row authority | Batched authority |
| --- | ---: | ---: |
| Document total | 666 ms | 6.2 ms |
| Retrieval existence gate | 657 ms | 6.6 ms |

The total query matches the legacy list count. The existence comparison applies
the old and new authority predicates to the proposed gate; it does not represent
the previous complete ranked search. Shared buffer hits fell from 36,478 to 978
for both shapes. Plans, account parameters and operational dumps remain private.
Nonempty search can still require provider work and must be measured separately.

## Reconnect and rotating credentials

Socket and file-channel bootstrap request a ticket using the saved access token.
Only an explicit ticket HTTP 401 permits one coordinated renewal. The credential
owner reloads under its existing account/server lock and reuses a newer stored
pair when another client already renewed the rejected access token.

The native authentication transport refuses redirects and never retries. It can
attest that DNS resolution or connection refusal prevented submission, allowing
the credential owner to retain its saved pair. A timeout, reset, abort, malformed
response or arbitrary custom transport error preserves the interrupted-renewal
fence and requires sign-in. An uncertain rotating refresh is never replayed.

## Shutdown ordering and recovery

Information workers drain in Spring's lifecycle stop phase before datasource
destruction. Information, embedding and retrieval workers stop concurrently, each
allowing ten seconds for its current pass and five seconds after forced
cancellation. Terminal close prevents a destruction fallback from repeating that
deadline. Concurrent lifecycle callbacks share the owning drain's completion;
workers remain running for Spring's purposes until that drain finishes. Failures
to terminate remain visible; durable leases and outboxes own recovery after restart.

File-channel session-close listeners are captured after singleton initialization.
Socket cleanup uses the immutable snapshot instead of resolving Spring beans
while the application context is closing.

## Manual publication

The installer distinguishes private Personal installation from generally
available publication. A Global or explicitly shared installation must verify
chapter text through `global_read` before marking it complete or excluding its
previous edition. A successful administrative account read alone is insufficient.
Private installation no longer reports that Help is ready. Existing Personal
sources are not automatically shared; publish the intended checked-in chapters
through the installer's documented Global/shared workflow.

## Regression provenance and validation

The per-row document guard and unconditional retrieval dispatch originate in
`d38d55f49`; `f78e4f048` added the dual-provider branch and unconditional connection
refresh. Worker interruption and lazy close-listener resolution originate in
`cd7f68ab`. The unconditional shared-Help success message was introduced in
`dc9db60e1`.

Focused tests cover query budgets, profile changes, scope/withdrawal parity,
provider accounting after withdrawal, credential concurrency and uncertain
delivery, worker drain ordering, close callbacks and resumable Manual publication.
PostgreSQL tests are required for actual permission SQL, paging, vector-generation
matching and durable claim behavior. Client fixtures retain mutation counts and
no-replay assertions. A source test result does not establish deployed reconnect,
shutdown or Help behavior; those workflows require a subsequent live check.
