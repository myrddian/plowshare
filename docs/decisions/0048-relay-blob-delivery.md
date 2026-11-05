# 0048: Relay BLOB delivery over shared binary storage

Status: implemented in the current worktree following explicit user authorization,
22 October 2026; not deployed. Trajectory content remains outside this slice.

Relay gains project-scoped BLOB topics alongside existing fixed-family TEXT
topics. Explicit authenticated multipart publication and byte download complement
the existing WebSocket consume/ack operations. Log inspection carries bounded
descriptors, never inline bytes, paths, tokens or public URLs. Existing text
publication, subscriptions, leases, acknowledgement, expiry and gap rules remain.

This is a platform capability independently of any adapter. Generic binary
delivery cannot be supplied by an SDK-only wrapper around text without server
authorization, durable ownership and binary retention. Tool result envelopes,
model observations and Conversation/Trajectory claims remain separate work.

Use `BinaryBlobStorage` for bounded ingestion, full-digest deduplication and reads.
An image-first object remains in its existing image location; a BLOB-first object
uses a private hash-named file under the configured project's `blobs/` directory.
The image service can subsequently bind its existing image ID and sidecar to that
same canonical file. Do not write a second compatibility byte copy. Data layout
7 reserves the new directories and moves no existing image file. Shared scope
fences serialize image/blob placement and cleanup; older writers must not run
against the upgraded layout.

Finalize bytes before the publication transaction. Prepared but unpublished
objects have zero claims and the storage recovery grace; a failed attachment
cannot acknowledge a publication. Append, publication claim and relational
binding commit together. A cascade-safe trigger releases only that publication
claim on prune/project deletion. Acknowledgement does not release it. Other image
or invocation claims retain the same bytes independently.

Every upload/download uses existing project membership and explicit deployment
port grants. BLOB access cannot borrow dynamic tool-provider grants or publish to
reserved `tool.*` topics. Current egress permission and a retained exact BLOB
publication authorize reads; a hash or old batch token alone does not. After
expiry, download fails even if another owner still retains the bytes. Generic
MIME declarations are delivery metadata, not media validation or model capability.

Binary publication is disabled by default and has bounded bytes and concurrency.
Java and Node receive single-attempt HTTP helpers with explicit origin/bearer,
redirect refusal and integrity/correlation checks. All SDKs gain validated BLOB
descriptor reads through generated shared contracts. Native-language binary HTTP
authoring parity, internal filter/receiver BLOB processing and media viewers are
not advertised by this slice.

Verified with 32 focused PostgreSQL tests: `RelayBlobPersistenceTest` (9),
`JdbcRelayRepositoryTest` (17, existing text/lifecycle behavior) and
`JdbcBlobRepositoryTest` (6). These exercise upgrade from migration 182 with an
existing TEXT publication, binary round-trip/consume/ack, conflicting and concurrent
retries, publication/claim rollback, prune/gap and project-cascade release, both
image/BLOB arrival orders and collection winning before attachment.

`./gradlew format formatCheck check` passed, including the endpoint inventory,
image compatibility, authority and binary helper tests. `sdkCheck` passed with 618 shared DTO cases and Node, Python, .NET and Go
WebSocket conformance. Java/Node binary HTTP helpers have focused receipt, digest,
size and no-replay tests. Default tests cover current authority/revocation, project
replacement, reentrant capacity, malformed multipart metadata and legacy text
serialization. This establishes repository behavior, not a live deployment or
screenshot-to-model observation.
