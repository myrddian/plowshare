# TODO: platform gaps from the November 2026 review

Status: deferred handoff, 12 November 2026. Documentation only; each item is a
separate task. Verified source baseline: `3d5ff95f7e2453d38b174225e6392aad0192e6fe`.

This list comes from a read-only review of context and memory, the information
system, Relay, orchestration, the WebSocket protocol and the client API. Each
item states whether its evidence was **verified** against the source at the
baseline or **reported** by the review and still needs confirming before work
starts. Line numbers refer to the baseline and will drift.

The WebSocket transport stays. Items below improve it; none replaces it with
HTTP. Sizes are rough: **S** ≈ a day or less, **M** ≈ several days, **L** ≈ a
multi-slice effort.

## Suggested order

1. Track A1–A2 (small, real defects).
2. A3, A5 (transport throughput and wasteful lookups), A11 and A13 (useful
   request concurrency), then B1 (REST removal). A12 follows once pool
   pressure is measured.
3. A6 (push envelope and sequence numbers), which unblocks resumable streams and
   B2 (REST/SSE proxy).
4. C1 (A2A rewrite), B3 (run durability), B6 (whole-run futures), B4 (MCP
   server).
5. Tracks D–G as capacity allows.

Items within a track are ordered; tracks are independent unless a **Depends on**
line says otherwise.

---

## Track A: WebSocket transport

### A1: TypeScript clients drop `usage.*` pushes (S)

- [ ] Accept usage pushes whose `id` is absent, and pin the real wire shape.

**Evidence (verified):** the server builds usage updates as `new Envelope(null, …)`
(`plowshare-server/.../ws/UsageSubscriptions.java:234-238`) and `Envelope` is
`@JsonInclude(NON_NULL)` (`plowshare-protocol/.../frames/Envelope.java:79`), so
`id` is omitted; `EventChannelTest` asserts `"id":` is absent. The TypeScript
connection routes a frame carrying `protocol_version` to the push path only when
`frame['id'] === null`; an absent id falls through to `typeof id !== 'string'`
and is discarded (`sdk/typescript/src/binding/connection.ts:409-420`).
`decodeServerPush` also requires `id === null` (`sdk/typescript/src/operations/push.ts:98`).
SDK fixtures use `id: null`, so tests never see the real shape.

**Scope and acceptance:** pick one wire rule (absent or explicit null) and make
the server and every SDK agree. Add a shared cross-language fixture captured from
real server output. Confirm usage subscriptions update in a live TypeScript
client (console or desktop).

### A2: segmented transfer reserves its declared size up front (S)

- [ ] Stop an authenticated client from reserving large buffers with one packet.

**Evidence (verified):** the first packet of a transfer calls
`reserve(packet.totalBytes())` and constructs its `Assembly` before any further
data arrives (`plowshare-protocol/.../transport/SegmentedMessages.java:194-205`).
**Reported:** the per-message ceiling is 320 MiB against a 2 GiB process budget,
so two concurrent transfers consume most of it, and trickled packets keep the
idle timer alive up to the 60 s transfer limit.

**Scope and acceptance:** reserve and grow as packets arrive, or cap declared
size per connection/account with a smaller default; count reservations per
principal; require prompt follow-up packets. Test: a declared-large transfer that
never sends data does not hold budget beyond a short deadline.

### A3: segmentation is stop-and-wait (M)

- [ ] Replace one-packet-in-flight credit with a window, and skip segmentation
  for small messages.

**Evidence (verified):** the sender holds a per-connection lock and waits for a
credit after each 64 KiB packet (`SegmentedMessages.java:95-146`); first-party
SDKs always negotiate segmentation. **Reported (arithmetic):** that limits each
direction to about one message per round trip, wraps every token delta in
Base64 plus SHA-256, overflows the 64-slot token queue above roughly 15 ms RTT,
and makes large messages fail the 60 s limit on slower links.

**Scope and acceptance:** allow N packets or bytes in flight (for example
1–4 MiB); send messages below one packet as plain frames inside the subprotocol;
reconsider whether the whole-message SHA-256 earns its cost over TLS. Revisit
ADR 0009's reasoning that WebSocket fragmentation is insufficient (Spring
supports partial messages). Measure token delivery and a large message at
1 ms, 30 ms and 100 ms added latency before and after.

### A4: replies share a bounded queue with droppable events (M)

- [ ] Give replies, lifecycle events and token deltas separate lanes, and let
  transfers interleave.

**Evidence (reported):** lifecycle events and replies share one 256-slot
`pending` queue; when job events fill it, the next reply cannot be queued and the
socket closes with `SESSION_NOT_RELIABLE`, losing every in-flight call
(`plowshare-server/.../ws/EventChannelHandler.java` around 1703-1760). One drain
thread serves queues in a fixed priority, so a token queued before `ended` can
arrive after it, and a large reply blocks everything behind it.

**Scope and acceptance:** a noisy job can never close the socket or delay a
reply; ordering between a job's tokens and its `ended` event is guaranteed;
a large transfer does not block small frames.

### A5: authorization lookups per packet and per token (S–M)

- [ ] Check session currency per logical message and job-stream authority once
  per subscription.

**Evidence (reported):** `handleTextMessage` runs `socketAuthorization.current()`
for every packet, credits included; each token delta re-runs
`ProjectAuthorization.allowed("job.status")` on the model thread
(`EventChannelHandler.java` around 732 and 1142-1196). This is the repeated
permission resolution the query-efficiency rules forbid. An unhandled
`ReadUnavailableException` from `current()` may close the connection.

**Scope and acceptance:** rely on the existing one-second sweep and post-commit
revocation for currency; cache job-stream authority per subscription and
invalidate it on membership change. Revocation still closes the socket promptly;
a test counts lookups per streamed job.

### A6: one push envelope with sequence numbers (M)

- [ ] Unify the three push shapes and make loss detectable.

**Evidence (reported):** pushes arrive as bare `JobEvent`, bare account events,
or enveloped `usage.*`, told apart by heuristics (`EventChannelHandler.java`
around 97-108, `sdk/typescript/src/operations/push.ts:96-170`). `JobDelta` has
no offset (`plowshare-protocol/.../JobDelta.java`), so a dropped token silently
corrupts text.

**Scope and acceptance:** one shape such as `{type, sub, seq, payload}`; a
`gap`/`overrun` notice whenever anything is dropped; an offset on deltas so a
client can repair from the durable log. Shared operation contracts and every
SDK change together. **Unblocks:** resumable streams in A7 and `Last-Event-ID`
in B2.

### A7: first-class subscriptions (M–L)

- [ ] Subscribe to particular jobs and topics, resume after reconnect, and warn
  before shutdown.

**Evidence (reported):** `job.stream` toggles all job streaming for a session
(`plowshare-server/.../ws/JobStreamHandler.java:52-58`); only `usage.subscribe`
has subscription ids; nothing survives a reconnect; shutdown drains for 10 s
with no notice to clients. **Depends on:** A6.

**Scope and acceptance:** subscription ids for job, account and usage streams;
a resume token that replays from a short server buffer or reports a gap; a
"going away, retry after" push before drain.

### A8: richer outcome model (S)

- [ ] Add FORBIDDEN/UNAUTHENTICATED, a retryable flag or retry-after, and an
  optional idempotency key in the envelope for mutations.

**Evidence (reported):** outcomes are a flat enum (`plowshare-protocol/.../frames/Code.java`);
after `RESPONSE_LOST` a client cannot safely retry an arbitrary mutation because
idempotency exists only per operation. There is no transport-level cancel for an
in-flight request.

### A9: connection housekeeping (S each)

- [ ] Decide whether socket lifetime should follow token expiry (today it
  follows session version and account status) and document or change it.
- [ ] Add a test that a cross-origin cookie upgrade is refused, rather than
  relying on Spring's default origin interceptor.
- [ ] Align `/v1/files` (`FileRequest`/`FileReply`, 1 MiB, unsegmented) with the
  main protocol, or document why it differs.

### A10: contract checking covers payloads, not just names (M)

- [ ] Validate catalog payload schemas against the server's Java DTOs.

**Evidence (reported):** `scripts/generate-sdk-contracts.mjs` cross-checks
operation names with server constants; payload schemas are written by hand in
TypeScript, and server records are hand-written Java. A1 is the kind of drift
this would catch.

### A11: classify common mutations instead of treating them as barriers (M)

- [ ] Give frequently used mutations conflict keys so they stop serialising the
  whole connection.

**Evidence (verified):** `plowshare-server/.../ws/DispatchPolicy.java` `decode`
returns `barrier()` for any operation not listed as an independent read, a
resource lane or an observer. `agent.run`, `job.cancel`, `memory.write`,
`memory.recall` (it counts uses), `orchestration.start` and `message.send` are
all barriers: each waits for every earlier request on the connection and blocks
every later one. `job.cancel` therefore queues behind slow earlier work, and
`memory.recall` holds the barrier for its embedding call.

**Scope and acceptance:** `job.cancel` gets its own lane or bypasses the queue so
a cancel is never delayed by unrelated work. `agent.run` and `message.send` use
RESOURCE lanes keyed by conversation or target instance; memory writes and
recalls key on their home scope. Each new classification is audited the way
`docs/dispatcher-read-audit.md` audits reads (effects, authority, ordering). A
test shows a cancel completing while an earlier slow request on the same
connection is still running.

### A12: raise per-connection concurrency once pool pressure is admitted (M)

- [ ] Express database pool starvation as `CAPACITY_EXHAUSTED`, then revisit the
  per-connection defaults.

**Evidence (verified):** `EventChannelConfig` defaults are
`plowshare.transport.request-per-connection: 2` executing and
`plowshare.transport.request-depth: 8` outstanding. Its comment says the 2 was
inherited and is now the only transport execution bound, and that raising it
would pressure the connection pool until starvation surfaces as
`CAPACITY_EXHAUSTED`, which the code does not do yet.

**Scope and acceptance:** pool exhaustion during admission or execution returns
`CAPACITY_EXHAUSTED` (nothing started) rather than blocking or failing late; then
choose new defaults from measurement. Load-test many concurrent finite reads on
one connection and across connections before and after.

### A13: every SDK waits at the advertised depth (S–M)

- [ ] Make the Java, Go, Python and .NET SDKs queue locally at the depth bound
  instead of failing.

**Evidence (verified):** the `EventChannelConfig` comment records that those four
SDKs adopt the advertised depth and fail the next request with `NOT_SUBMITTED`
(the 9th at the default depth of 8); only the TypeScript SDK waits. External
consumers issuing many futures therefore see refusals rather than backpressure.

**Scope and acceptance:** each SDK holds excess calls in a bounded local queue,
honours cancellation (context, token or future cancel) while queued, and
optionally offers a non-blocking mode that keeps today's behaviour. Shared
conformance fixtures cover queueing, cancellation while queued and the
non-blocking mode.

---

## Track B: client API

### B1: retire the legacy REST controllers (M)

- [ ] Remove HTTP endpoints that duplicate WebSocket operations.

**Evidence (verified):** `plowshare-server/.../api/` still registers live
`@RestController` classes under `/v1/**`, for example `POST /v1/agents/{name}/runs`
and `POST /v1/memories/recall`. No first-party client calls them; the console's
`/v1/...` paths are UI keys mapped to WebSocket operations
(`plowshare-console/src/transport.ts`). Removal is not a plain delete: WebSocket
handlers reuse request records nested in the controllers (for example
`SearchQueryHandler` parses `SearchController.SearchRequest`, and several
handlers reference `AgentController` and `DigestController` types), and HTTP
tests exercise these endpoints.

**Scope and acceptance:**

1. Move the nested request/response records to the WebSocket or protocol layer.
2. Delete duplicating controllers: Agent, Conversation, Memory, Digest, Project,
   Proposal, Search, Fetch, Board, BufferPurge, Retention, OrchestrationRecord,
   and Document except its multipart upload.
3. Port their HTTP tests to frame tests.
4. Keep the documented HTTP boundaries: auth, `/ready`, runtime config, image
   upload, Relay blobs, document upload, provider OAuth callback and the
   operational search-provider endpoints.

Verify with `./gradlew check`; confirm the remaining HTTP surface matches the
boundary list in `docs/sdks.md`.

### B2: REST/SSE proxy as an SDK integration (M)

- [ ] Offer curl-friendly access without changing the server transport.

**Shape:** an integration under `integrations/`, built only on the public SDK.

- Generate routes from `test-support/contracts/sdk-protocol.json`:
  `POST /v1/ops/{type}` returns the single reply as JSON, and publishes an
  OpenAPI document from the same catalog.
- `Accept: text/event-stream` on the same route streams that call's job events
  with the reply as the final event.
- `GET /v1/stream` carries account and job pushes as SSE.

**Rules:** one upstream socket per user, never shared, since authority is held per
socket; a fresh envelope `id` per HTTP call; `RESPONSE_LOST` maps to 502 with an
outcome-unknown header and is never retried; `CAPACITY_EXHAUSTED` maps to 429;
segmentation is handled by the Node SDK.

**Depends on:** A1, A3 (otherwise the proxy inherits the round-trip ceiling); A6
for `Last-Event-ID` resume. Any missing public capability is reported as a gap,
not patched into core (see `docs/decisions/0001-integration-boundary.md`).

### B3: plain runs across a server restart (M)

- [ ] Make a client's job id still resolve after a restart, and decide whether
  interrupted plain runs continue automatically.

**Evidence (verified):** orchestrations restart at boot
(`plowshare-server/.../orchestrations/OrchestrationsConfig.java`, `recover`),
message wakes are re-delivered (`events/EventsConfig.java`, `eventsAtBoot`) and
approvals reconcile. A plain `agent.run` turn is abandoned with "the server
restarted during this run", and `JobStore.get` refuses an id it never registered
(`agents/JobStore.java` javadoc), so a polling client gets a refusal. The
conversation log itself is intact. `agent.run` has no idempotency key.

**Scope and acceptance:** `job.status` for a pre-restart id returns its durable
ending and conversation; `agent.run` accepts a `requestId` with a receipt like the
other receipt-bearing operations; optional policy to continue interrupted plain
turns at a clean boundary, never replaying a dangling call.

### B4: MCP server protocol and coverage (M)

- [ ] Move `plowshare-mcp` off protocol `2024-11-05` (`plowshare-mcp/src/protocol.ts:55`)
  and generate its tools from the catalog rather than a hand-curated list.

### B5: SDK parity and publishing (M)

- [ ] Bring the Java SDK to catalog parity, or correct `docs/sdks.md`'s parity
  claim.
- [ ] Publish SDK packages and give the Go module a hosted path.

### B6: a future for a whole run (S–M)

- [ ] Add an SDK helper that resolves when a run ends, not when it is accepted.

**Evidence (verified):** request futures resolve with the reply. For `agent.run`
that reply is the job handle; the answer arrives later through job events and
account pushes, or by polling `job.status`. No SDK offers a call whose promise or
future resolves with the run's final outcome.

**Scope and acceptance:** a helper such as `run(...).result()` in each SDK that
starts the run, waits for its ending event, then reads the durable outcome and
answer. It must not depend on streamed events alone, since they can be dropped
(A4, A6): after a reconnect, missed event or timeout it falls back to a durable
read. Endings that are not success (capped, cancelled, failed, interrupted) are
returned as typed outcomes, not thrown as transport errors, and cancelling the
local future never cancels server work unless the caller asks for `job.cancel`.
Benefits from B3 so the job id still resolves after a server restart.

### B7: a small core API over the catalog (M)

- [ ] Offer a thread/run/stream layer for newcomers on top of the full catalog.

**Evidence (verified):** the catalog has about 300 fine-grained operations, and a
first program has to learn projects, conversations, agent definitions, jobs and
push decoding before it gets an answer. There is no small set of calls for the
common case.

**Scope and acceptance:** a documented core in each SDK, for example
`threads.create`, `threads.send` (returning a B6 run future), `runs.stream`
(typed events, using A6 sequence numbers once they exist) and `runs.cancel`,
built from existing operations with no new server contract. The B2 proxy exposes
the same core as its first documented routes. A "hello world" in the manual uses
only the core. Gaps that need server changes are reported, not hidden in the
helper.

---

## Track C: integrations

### C1: rewrite the A2A adapter (M)

- [ ] Rebuild `integrations/a2a` on the official A2A Java SDK.

**Evidence (verified):** the adapter is a small hand-written JSON-RPC client and
receiver over OkHttp. Inbound supports text-only `SendMessage`, `GetTask` and
`CancelTask`; streaming, subscriptions, push notifications, task listing and
artifacts are unsupported. Its tests depend on `:plowshare-server` internals.

**Scope and acceptance:** keep the existing mapping (`contextId` to a persistent
instance and conversation, task to an incoming receipt); add streaming, artifacts,
file parts and task follow-ups as the public SDK allows; test against the public
SDK only. Report gaps the SDK cannot cover instead of changing core. Move outbound
work onto Relay once D1 lands.

---

## Track D: messaging and orchestration

### D1: finish the move from board messaging to Relay (L)

- [ ] Carry agent mail, delegation and outgoing work on Relay v1.

**Evidence (reported):** ADR 0040 records the migration as started; message
bodies, delegation and `outgoing_work` still use board tables, giving several
delivery mechanisms. Blocked on an audience for account-private content
(ADR 0037/0039).

### D2: orchestration parent wake on Relay (M)

- [ ] Replace the in-process `Delivery` → `ParentVoice` path and its in-memory
  in-flight set, and give `orchestration.child.completed` a consumer.

### D3: parallel commands in scripted orchestrations (M)

- [ ] Add a journaled `parallel: [...]` command so a script can fan out without
  child orchestrations or a swarm; each branch gets its own journal checkpoints.

### D4: script authoring helpers (S–M)

- [ ] Provide a small helper library or generator-style wrapper over `step()`
  that handles stage todos, `pending` continuations and judged-result parsing.

### D5: run timeline view (M)

- [ ] Render `orchestration record` as a timeline in desktop/console.

### D6: Relay consumers: lease renewal and poison records (M)

- [ ] Let SDK consumers renew a batch lease, and stop one failing record from
  blocking its group forever.

**Evidence (verified):** internal deliveries can renew their claims
(`relay/DurableRelayDeliveries.java:51`), but SDK `relay.consume` batches carry a
fixed lease, and `docs/relay.md:574-575` states there is no lease renewal for
external consumers ("Bound handler work to the lease"). The operation catalog
has `relay.consume`/`relay.ack` and no renew. **Reported:** there is no
dead-letter path; because consumption is ordered and reading never advances the
cursor, a record a consumer cannot handle is redelivered until an operator uses
`relay.operate` (for example `ABANDON`).

**Scope and acceptance:** a `relay.renew` (or a heartbeat on the batch token)
bound to the existing fence; a delivery-attempt count per record; an optional
per-subscription policy that parks a record after N attempts on a derived
parked topic with an audited receipt, leaving the default as today's strict
behaviour. Tests cover renewal by the live owner only, renewal refused after
expiry, and parking preserving order for the remaining records.

---

## Track E: knowledge

### E1: memory recall quality (S–M)

- [ ] Add lexical/hybrid recall for memories (today vector-only) and remove
  duplicates between project and Global results, which are concatenated.

### E2: ingestion formats (M)

- [ ] Extract DOCX, PPTX and HTML files; add OCR for scanned PDFs; consider image
  captioning for indexing. Today only UTF-8 text and PDF are ingested.
- [ ] Extract tables and figures as structured content rather than flattened
  text, so retrieval and evidence offsets can address a table cell or caption.

### E3: source connectors (M–L)

- [ ] Add one or two continuously synced connectors, as SDK integrations.

### E4: retrieval ranking (M)

- [ ] Evaluate a reranker model and non-English lexical search (today
  `websearch_to_tsquery('english')` without IDF).

### E5: group the digest tree by topic (M)

- [ ] Build digest parents from related nodes rather than consecutive ones.

**Evidence (verified):** `agents/digests/Digester.java` groups roots `FANOUT = 8`
at a time in list order (`:43`, `:127-129`), so a parent summarises whatever
eight nodes happen to be adjacent. The navigator's descent then chooses between
summaries that mix unrelated conversations.

**Scope and acceptance:** cluster candidates by embedding similarity (or by
conversation and project) before grouping, keep provenance to exact spans, and
keep the repair-stale-ancestors pass bounded. Measure navigation success on a
fixed question set before and after.

### E6: memory recall without table locks (M)

- [ ] Remove the table-level locks taken on every recall.

**Evidence (verified):** `archive/MemoryStore.java` executes
`LOCK TABLE memories IN SHARE ROW EXCLUSIVE MODE` and `SHARE` locks on provenance
and information tables in both its read and write admission paths (`:181-183`,
`:321-325`). `SHARE ROW EXCLUSIVE` conflicts with itself, so concurrent recalls
serialise, and they also block memory writes.

**Scope and acceptance:** keep the guarantee the locks provide (admission and
disclosure decided against one consistent state) with row-level locks, an
advisory lock per home scope, or a snapshot/version check. A concurrency test
shows parallel recalls overlapping and writes not blocked by reads, without
losing the "outside live read authority" refusal.

---

## Track F: published evidence

### F1: prompt-cache and latency report (S)

- [ ] Report cache hit rate and time-to-first-token per agent and per turn from
  `inference_attempts.cache_read_tokens` and `first_output_millis`, joined to
  `inference_calls`. vLLM reports cached tokens only with
  `--enable-prompt-tokens-details`.

### F2: memory benchmark (M)

- [ ] Run LongMemEval or LoCoMo and publish the method with the result.

### F3: retrieval benchmark (M)

- [ ] Publish a document retrieval and citation-accuracy benchmark.

---

## Project risk (not a task)

The codebase is large for its maintainer count. The approachable core API (B7),
the proxy (B2), published SDKs (B5) and reduced documentation drift (Track G) also
lower the cost for a new contributor to make a first change, which is the main
mitigation available.

---

## Track G: documentation drift

- [ ] ADR 0040: payload families now include BLOB; `lockMailbox` is `lockAddress`.
- [ ] ADR 0038: status says proposed, but the read tier is implemented.
- [ ] `Compaction` javadoc says there is no tokenizer; the in-turn path uses one.
- [ ] `RetrievalService` comments about no document filter and no project column.
- [ ] V83 stage list versus the seven stages in current code and docs.
