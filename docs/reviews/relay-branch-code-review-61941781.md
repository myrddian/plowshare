# Code review: `origin/codex/relay-finishing` @ 61941781

"Implement Relay messaging, lifecycle and SDK parity". Base: main 7ca27b48 (#229, `docs/relay-finishing.md`).
Reviewed in a detached worktree. Paths are relative to the repository root. `S/` means
`plowshare-server/src/main/java/io/aeyer/plowshare/server/`, and `A/` means
`integrations/a2a/src/main/java/io/aeyer/plowshare/a2a/relay/`.

Scope actually read: all of `A/*` and its tests, migrations V238–V243, the messaging package
(`RelayMessageInboxes`, `RelayMessagePublications`, `MessageInboxWorker`, `JdbcMessageInboxRepository`,
and the send path in `RelayMessaging`), the board bridge (`BoardRelayReceiver`, `ForumBoardBridge`,
`JdbcBoardBridgeRepository`, `RelayBoardReplies`, `ProjectBoardClosing`, `BoardCloseFrames`), granted
publishing (`GrantedRelayPublishing`, `JdbcRelayPublishingRepository`, `RelayPublishTool`), child
completion (`RelayChildCompletions`), retirement (`JdbcRelayRetirementRepository`), `BoardConfig`
wiring, and ADRs 0060 and 0065. `Turn`/`Compaction` concurrency and the SDK regeneration were skimmed
only (see "What you could not verify").

---

## Strengths

- **The A2A outbound worker is SDK-only.** `integrations/a2a` no longer depends on `:plowshare-server`
  (`build.gradle.kts` drops both test dependencies), and grepping the main and test sources finds no
  `io.aeyer.plowshare.server` import. It uses only `plowshare-protocol` DTOs and the Java SDK
  (`RelayClient`, `ExternalPayloadCodec`, `AgentCardCodec`). The test fixture `RelaySdkTest` drives a
  real SDK socket against an independent A2A HTTP fixture.
- **Intent comes before external I/O, and nothing is replayed.** `RelayAdapter.dispatch` saves an
  fsynced INTENT entry before `peer.send/status/cancel` (`A/RelayAdapter.java:144-157`). A recovered
  INTENT becomes `UNKNOWN` without being resent (`:125-135`). Peer errors map to `UNKNOWN` (`:166-174`).
  The OkHttp interceptor still refuses a second network attempt (`A2aClient`).
- **The journal is careful.** It writes the temp file, calls `force(true)`, does an atomic rename and
  fsyncs the directory (`A/FileDispatchJournal.java:147-176`). It takes an exclusive `tryLock` and
  refuses a symlink. If the lock file is present but the journal is missing, it refuses to start
  (`:71-72`). It binds the journal to a configuration fingerprint and pins the group generation
  (`:84-95`). It checks phase transitions monotonically (`:118-129`), and a failed commit poisons the
  instance (`:141-144`).
- **Validation is strict throughout.** Duplicate keys, trailing tokens, exact field sets and byte
  bounds are all refused in `RelayWorkCodec`. Every configuration value is explicit, credentials come
  from named environment variables, and `toString` is redacted (`RelayConfiguration`). The producer
  allowlist is checked before the payload is parsed. A remote task or context can only be reused by
  the same producer and peer (`A/RelayAdapter.java:193-211`).
- **No shipped migration was edited.** V238–V243 are all new files, and main's last migration is V237.
- **Messaging follows the owner's model in the main.** There is one Relay TEXT topic per
  (account, project, definition): `messaging.agent.<sha(account)>.<sha(agent)>`. A versioned, typed
  `MessagePayload` is carried in `RelayPayload.Text`, and Relay's envelope is unchanged. The event ID
  is the message ID. A leased `messaging.inbox` group checks the `messaging:<sha(account)>` publisher
  before decoding. Each publication is checked against the retained route before it is admitted
  (`S/messaging/RelayMessageInboxes.java:236-293`). The send writes the record, the route and the
  Relay publication in one transaction.
- **The board is decoupled from messaging storage.** V239 renames
  `board_message_*`→`message_*`, copies the bodies into `message_records`, publishes every retained
  thread with a body reference, and deletes the private board topics, seats and messages. It does not
  keep a compatibility view or a dual sender, so the migration is a clean break.
- **Close is RBAC-based.** `ProjectBoardClosing` requires `members.mayWork` and the personal-project
  owner, and the opener seat gets no special right (`S/board/ProjectBoardClosing.java:86-93`). The new
  WebSocket `board.close` checks the caller. The model `board_close` tool is now offered to member
  seats and checks the bound account (`Board.closeForAccount`).
- **The bridge's receipts are idempotent.** The board request ID is the delivery UUID. The forum
  mutation, the receipt and the reply binding commit together. An uncertain dispatch is only reconciled
  by reading the receipt (`S/relay/BoardRelayReceiver.java:50-76`).
- **Child completion is consumed under one leased group** with renewal. The in-process path is removed
  (`S/orchestrations/Delivery.java:31`), and V243 backfills the pending terminals.
- **Granted publishing is idempotent.** The event UUID derives from the harness call identity, the
  receipt is stored independently of Relay retention, and reusing a fingerprint conflicts
  (`S/relay/JdbcRelayPublishingRepository.java`).

---

## Issues

### Critical

**C1. A `board.post` bridge route can write into any project's forum without destination permission.**
- `S/board/ForumBoardBridge.java:148-171` resolves the route's `bdt_` topic by ID alone. For
  `board.post` (`:89-107`) nothing compares `topic.project()` with the route's source project, and
  nothing checks the publishing account's role in the topic's project. `Board.post` does no
  membership check either (`S/board/Board.java:275-300`).
- The WebSocket `board.post` requires the topic to belong to the selected project and requires
  `members.mayWork(project, account)` (`S/ws/BoardPostingFrames.java:194-202`). The bridge skips both
  checks. ADR 0060:25-26 makes this explicit: "a configured post/close may target an existing forum
  topic in another project".
- **Consequences:**
  - A manager of project A who knows a topic ID in project B can route any A-side publisher into B. A
    personal project is included, because the personal-owner check exists only on the close path.
  - The post appears in B as a person-authored post by an account that may not be a member of B.
  - The post wakes B's swarm, which spends B's pot.
  - Replies to the bridged thread are copied back into project A's Relay topic (V238 triggers +
    `RelayBoardReplies`), where every A member can read them.
- This bypasses the two-sided `sendTo`/`acceptFrom` consent that cross-project messaging requires. It
  also contradicts "Keep authentication, project membership … explicit" (AGENTS.md).
- **Fix:**
  - Pin `board.post` and `board.close` to topics in the source project (`selected.project()` equal to
    the route's project) and refuse otherwise.
  - If cross-project bridging is wanted, gate it on the destination's explicit consent, using the
    existing routing `acceptFrom` or a destination-side route. Also require the publishing account's
    `mayWork` on the destination, the same rule as WebSocket `board.post`.
  - Add a mocked test for a foreign-project topic and a personal-project topic.
  - Amend ADR 0060.

### Important

**I1. Agent publications are indistinguishable from the account's external SDK client.**
- `S/relay/JdbcRelayPublishingRepository.java:90` publishes `relay_publish` work with
  `publisher = RelayPort.publisher(owner.accountHandle())`, which is `sdk:<sha(account)>`. That is the
  same identity that ports derive for the person's own SDK clients.
- **Consequences:**
  - (a) `BoardRelayReceiver` resolves that publisher through `relay_port_publishers`
    (`S/relay/JdbcRelayPortRepository.java:49-59`). Once the account has published through a port, an
    agent granted a bridge-routed topic posts and closes board topics as a `BY_PERSON` post by the human.
  - (b) Every consumer that verifies publishers, including the A2A adapter's `producers` allowlist,
    cannot tell a model from an external client.
- The owner's model is that agents have no direct Relay access, and Slice 6 at most adds a granted
  publish tool. Either way, consumers must be able to distinguish the provenance.
- **Fix:**
  - Give execution publications their own server-owned publisher namespace that SDK ports cannot
    produce, for example `execution:<sha(account)>:<sha(agent)>`, as messaging does with `messaging:`.
  - Have the bridge refuse it, or attribute it as `BY_MEMBER`/harness and never as a person.
  - Document the namespace so adapters can allowlist it deliberately.

**I2. The A2A worker stops on any bad request, and every later request waits behind it.**
- These cases throw before `acknowledge`, so the same event is consumed again on every restart:
  - an invalid payload, a non-TEXT carrier or causation depth at the limit (`A/RelayAdapter.java:107-112`);
  - a codec failure (`:113`);
  - an unconfigured peer (`:114-115`);
  - a request UUID reused with a different source (`:121-124`);
  - an unobserved task or context (`:198-210`).
- An unavailable Agent Card (`:140`) does the same, so one peer outage halts every other peer.
- ADR 0065 calls this "stop the worker", but in practice one malformed publication from an allowlisted
  producer stops the adapter permanently until an operator intervenes. That operator action is
  undocumented, and the queue offers no skip operation.
- **Fix:**
  - Treat definite, pre-I/O refusals (codec, peer, depth, ownership) as journaled rejections. Publish
  a `REJECTED` observation (a reply ID derived from the source event when no request UUID parses), then
  acknowledge. Do this with no external I/O.
  - Keep the hard stop only for uncertainty: a journal failure, a gap, a generation change or an
    unconfirmed reply.
  - Treat an Agent Card fetch failure as a retryable condition that does not consume the request and
    backs off, instead of exiting the process.

**I3. Reply reconciliation will report conflicting evidence wherever `Instant.now()` has sub-microsecond precision.**
- The intent stores `occurredAt = clock.get()` at full `Instant` precision (`A/RelayAdapter.java:156`),
  and the journal round-trips that precision.
- The server normalises `occurredAt` to microseconds (`S/relay/RelayValues.java:88-95`, "JDBC/PostgreSQL
  stores microseconds").
- `reconcile` requires `event.occurredAt().equals(expected.occurredAt())` (`A/RelayAdapter.java:245`).
  On JDK 21/Linux `Instant.now()` normally carries nanoseconds, so every genuine retained reply would be
  rejected as "Conflicting A2A reply evidence". The worker is then wedged for good, because `tick()`
  refuses to run while any REPLY_INTENT exists (`:73-75`).
- Tests use a fixed whole-second `TIME`, so this is not caught.
- **Fix:**
  - Truncate the intent time to `ChronoUnit.MICROS` when it is created, or compare with the truncated
    value.
  - Add a test with a nanosecond clock.

**I4. An uncertain reply that was never actually published cannot be recovered.**
- After a failed `relay.publish` (`A/RelayAdapter.java:183-190`), the only way forward is `reconcile`,
  which confirms only events found in a log page (`:229-253`).
- If the publication definitively never happened, nothing moves the entry out of REPLY_INTENT, so the
  worker never runs again. The CLI also reads only one page per invocation (`A/RelayMain.java:59-66`)
  and cannot run while the worker holds the lock.
- **Fix:** Add an explicit operator path that proves absence. Read `relay.log` through the topic head,
  or retry the identical publish. Relay already deduplicates an equal event ID with equal content, so
  resubmitting the same draft with the same ID is a reconciliation, not a new effect. Also make
  reconcile page until the head.

**I5. The A2A journal has a hard 1,024-entry lifetime cap, and rotating any credential orphans it.**
- `FileDispatchJournal.save` refuses new intents at 1,024 entries (`A/FileDispatchJournal.java:114-117`),
  and "No automatic eviction" (`:21-23`) offers no manual eviction either. After 1,024 dispatches the
  worker stops for good.
- The journal owner is a fingerprint that includes the hashes of the Plowshare token and every peer
  bearer, plus the peer timeouts (`A/RelayConfiguration.java:57-93`). Routine token rotation or a
  timeout tweak makes the journal "belong to a different adapter binding" (`:68-69`). The only way out
  is a new journal, which throws away the dispatch evidence and the task-ownership state.
- **Fix:**
  - Bind the journal to destinations and identities (origin, project, topics, group, consumer,
    producers, peer endpoints), not to secrets or timeouts.
  - Add bounded retention for CONFIRMED entries. Keep their task/context ownership in a compact index,
    or require an explicit, journaled archive command.

**I6. Message admission now waits for a 5-second poll, and the send-side wake does nothing.**
- `RelayMessaging.sendIntent` still calls `drain.accept(target)` after commit
  (`S/messaging/RelayMessaging.java:755-760`). The firing is only created later, when
  `RelayMessageInboxes.pass` admits the publication. That happens on a fixed-delay poll with the
  default `idleInterval` of 5 seconds (`S/messaging/MessageInboxWorker.java:37-49`,
  `S/relay/RelayWorkerProperties.java:14`).
- Every message, and every hop of an agent conversation, therefore gains up to 5 seconds of latency
  compared with main. The send-side drain is dead code.
- **Fix:** After commit, nudge admission for that one inbox: a `poll(inbox)` on the worker's executor.
  Keep the periodic poll as recovery. Also remove the stale drain, or redirect it.

**I7. Board→messaging coupling remains in board persistence and in V239 triggers, as dead checks.**
- V239 deletes every former messaging board topic, but the board still queries messaging's table on
  hot paths:
  - `BoardStore.messagingTopic` (`S/board/BoardStore.java:161-167`);
  - topic listings and allowance views (`:180`, `:213`, `:359`);
  - `Board.retry`/`topup` (`S/board/Board.java:408`, `:1012`);
  - the bridge and closing code (`ForumBoardBridge.java:169`, `ProjectBoardClosing.java:98`);
  - the rewritten capture triggers, which run on every board insert and update
    (`V239__messaging_records_and_agent_inboxes.sql:121`, `:141`).
- Since `former_topic` can no longer match a live board topic, these checks are a pure cross-system
  dependency that costs a query each time.
- **Fix:** In a new migration, drop `former_topic` from the triggers. Remove `messagingTopic` from
  `BoardRecords`/`BoardStore` and from its callers. Keep `former_topic` in messaging only as history.

**I8. Board replies cannot reach an agent's inbox, and `reply_to` lets a publisher make the server publish into any TEXT topic.**
- **Inbox gap:** `RelayBoardReplies` publishes as `board:bridge` (`S/board/RelayBoardReplies.java:55-66`),
  but the messaging inbox consumer silently skips any publisher other than `messaging:<sha(account)>`
  (`S/messaging/RelayMessagePublications.java:58-60`). The owner's model, "replies land in the
  requester's inbox", is therefore impossible for agent requesters.
- **Confused deputy:** `ForumBoardBridge.replyTopic` only checks that the destination is an existing
  TEXT topic (`S/board/ForumBoardBridge.java:182-189`). A publisher can name a topic it has no right to
  publish to, such as another adapter's request topic, and the server will publish full board post
  bodies there.
- **Fix:**
  - Require the publishing account's publish grant on `reply_to` (`ports.authorizePublisher`, as the
    source is checked).
  - For agent requesters, route board replies through messaging: a messaging-owned board-reply payload
    admitted by the inbox, instead of a foreign publisher.

**I9. The `incoming.*` ingress and the `outgoing/` queue remain in core next to the new outbound worker.**
- A2A inbound is still `RelayMessaging.incoming…` (`S/messaging/RelayMessaging.java:~370-610`, moved
  from `BoardMessaging`).
- The legacy `outgoing/` queue and launcher stay alongside `plowshare-a2a-relay`, so there are now two
  outbound mechanisms. That is the parallel-queue drift that #229 identified.
- ADR 0065 is honest that Slice 5 is partial and names the public admission gap. Merging as-is still
  leaves A2A's core surface in place, with no removal plan dated or gated.
- **Fix:** See the structural items below. At minimum, mark `outgoing/` and `incoming.*` deprecated in
  code, and gate the new worker as experimental, so that no deployment runs both outbound paths for the
  same peer.

### Minor

- **M1.** `S/messaging/RelayMessagePublications.java:11-15`. The javadoc says the board-backed sender
  "is not wired to it and therefore never dual-publishes". It is now the sole sender, so the comment is
  stale.
- **M2.** Messaging still uses board types:
  - `Board.Refused` throughout `RelayMessaging` (`:25`, and around 40 throws) and in `MessageService.java:17`;
  - `MessagingProperties` lives in `board/`;
  - message logs open with `Origin.BOARD` (`RelayMessaging.java:320`, `:764`, `:1305`);
  - `MessageMaintenance`, `ProjectMessageRouting` and `RoutingConfiguration` remain in `board/`.
  See structural item S1.
- **M3.** `S/relay/JdbcChildCompletions.java` runs SQL on `orchestrations` from the `relay` package. The
  repository belongs to orchestrations (it implements `orchestrations.ChildCompletions`). Move it to
  that package.
- **M4.** `JdbcBoardBridgeRepository.pending()` (`:57-70`) scans every `board_relay_requests` row ever
  bound and runs a lateral subquery for each, every idle interval. Requests are never retired, because
  only outbox rows are deleted. Drive the scan from `board_relay_outbox`, which has an index on
  `request_id`, or delete a request after its terminal reply is published.
- **M5.** `JdbcMessageInboxRepository.ready/drained` order by and filter on the computed
  `project_id::text||'/'||topic`, which no index covers, so every poll does a full scan. Use a
  tuple keyset, `(project_id, topic) > (?, ?)`.
- **M6.** `MessageInboxWorker` (`:43-45`) and `BoardReplyWorker` (`:48-52`) log a bare warning with no
  inbox or request identifier. A wedged inbox, for example one with a retention gap, cannot be
  diagnosed. Log the topic or request key, but not the body.
- **M7.** `RelayBoardReplies` never gives up on a deleted reply topic: it retries every 30 seconds
  indefinitely. Add a terminal "destination gone" state.
- **M8.** V239 `ALTER COLUMN allowance_id SET NOT NULL` assumes every legacy messaging board topic had
  an `allowance_id`. `board_topics.allowance_id` is nullable (V173:34). A route whose recipient's
  project row no longer exists is dropped by the `projects` join, but is still marked `published`. The
  migration has no preflight check that fails with a clear message.
- **M9.** `RelayAdapter.tick` acknowledges foreign-publisher events silently (`:94-104`). That is
  correct, but add a counter or log line so that a misconfigured allowlist is visible.
- **M10.** `RelayMain` exits on the first exception and has no supervisor or backoff. Combined with
  I2, a transient peer failure needs an external restart. Document the expected supervisor
  (systemd/k8s) and its restart policy.
- **M11.** `RelayAdapter.Binding` permits only one consumer per journal. A second host with the same
  configuration, but its own journal directory, would duplicate external sends after a lease expires.
  ADR 0065 documents this. Consider a lease-epoch check stored in the journal so that a second journal
  refuses at startup.

---

## Structural changes required

1. **S1. Move messaging composition out of `board/` (board→messaging leak).**
   - `BoardConfig` constructs `RelayMessaging` (`S/board/BoardConfig.java:113-266`).
   - The board `Dispatcher.Wakes` multiplexes `messaging.owns(wake)` (`:384-392`).
   - The swarm scheduler asks `messaging.share` (`:435-440`).
   - `BoardRunExtras` injects `send_message` (`S/board/BoardRunExtras.java:48-93`).
   - Routing, maintenance and properties classes still live in `board/` (M2).

   Messaging needs its own configuration and its own wake-owner registration in the events
   `Dispatcher`. `send_message` should be offered by an agents-level extras provider, not by the board.
   The board should not import `server.messaging` at all. (I7 is the persistence half of this item.)
2. **S2. Pin bridge destinations to the source project (C1),** or put cross-project bridging behind
   destination consent. This changes ADR 0060's contract, so it must land before merge.
3. **S3. Give execution publishers their own provenance namespace (I1).** This is a contract decision
   for Relay consumers and adapters. Changing it after release breaks every allowlist.
4. **S4. Settle A2A inbound before calling this branch's A2A work done (I9).** The owner's model has
   inbound work land on the agent topic as a `MessagePayload`, but the implemented inbox refuses every
   non-`messaging:` publisher by design (ADR 0065).
   - Either authorize the separate platform task ADR 0065 describes (authenticated external message
     admission and a public command catalog), or explicitly record that `incoming.*` remains the inbound
     contract for now.
   - Also schedule removal of `outgoing/` once the Relay outbound worker is accepted, so there are not
     two outbound queues.
   - Either way, the A2A work on this branch did **not** modify core to suit the adapter. Its one
     protocol change (`RelayPort.Batch.generation`) and the `relay.group.inspect/delete` SDK calls belong
     to Slice 4 group retirement and are general-purpose.

---

## Proposed solution and next steps

1. Fix C1 before merge. Pin the route project in `ForumBoardBridge.selected`, and add the destination
   `mayWork` check plus the personal-owner check for posts. Amend ADR 0060 and add tests for the
   foreign-project and personal-project cases.
2. Introduce the execution publisher namespace (I1/S3) in a new migration and the publishing
   repository. Make the bridge refuse it, or attribute it as non-person.
3. Fix the A2A worker's failure modes (I2–I5). The changes are adapter-only and need no core change:
   - truncate time to microseconds;
   - journal pre-I/O refusals as `REJECTED` and acknowledge them;
   - add an absence-proving or idempotent-republish recovery;
   - remove secrets from the fingerprint;
   - add bounded retention.
4. Restore immediate admission (I6) with a per-inbox nudge after commit.
5. Split messaging out of `board/` (S1 and M2) and remove the dead `former_topic` checks (I7) in a
   follow-up migration. This can be a separate PR if C1 to I6 land first, but it is needed to meet the
   owner's "remove board-messaging coupling" statement.
6. Decide on A2A inbound and the removal of `outgoing/` (S4) as a separately authorized platform task.
   Until then, document that both outbound paths exist and must not serve the same peer.
7. Run the `full-db` subset for V239–V243 (`MessageCutoverDatabaseTest`, `RelayBoardBridgeDatabaseTest`,
   `RelayChildCompletionDatabaseTest`). The migrations need it, so this is required and not optional.
   Also do a dry run of V239 on a copy of PROD/BETA data to check M8.

---

## What you could not verify

- **Real database behaviour.** No `-PfullDb` run, by instruction. Unverified: whether V238–V243 apply,
  the trigger behaviour, the V239 cutover on real data (M8), the locking order between `retain`,
  `retire` and `cleanupRetired`, and concurrent final replies.
- **Concurrent turns sharing one conversation.** V240 and `Turn.messageRuns` now let two definitions
  run turns on the same conversation at once. Ordinal reservation is handled, but I did not verify that
  `Compaction`, entry projection and context assembly are safe under two concurrent writers on one
  conversation. This needs a targeted review and test.
- **`Instant.now()` precision on the deployment JVM (I3).** This Mac yields microseconds. The Linux
  nanosecond behaviour is from JDK 15+ documentation, not observed here.
- **The rest of the diff.** SDK regeneration (Python/Go/.NET/TS contracts, blob helpers, Java
  Board/Swarm/Messaging clients), `JobRuntime`/`Budget`/`AutomaticLimits` changes, the
  `.githooks/pre-push` script and the docs (beyond ADRs 0060 and 0065) were not reviewed line by line.
- **Live deployment.** No inbound or outbound end-to-end test against a running server or peer.
- **Test results.** `./gradlew :plowshare-a2a:test` passed: 34 tests, 0 failures. Focused server tests:
  11 classes (RelayMessageInboxes, ForumBoardBridge, BoardRelayReceiver, GrantedRelayPublishing, RelayChildCompletions, ProjectBoardClosing, TurnEligibility, RelayPublishTool, RelayBoardReplies, MessagePayloadCodec, BoardCloseFrames) passed, 59 tests and 0 failures. The console pnpm build was skipped because offline dependencies are missing in a fresh worktree. Mocked tests do not prove a live integration.

---

## Verdict

**Not ready to merge.**
- C1 is a cross-project write and disclosure path past the board's own membership check.
- I1 lets agents be attributed as people on the bridge.
- I3/I4 can leave the new A2A worker unrecoverable after its first uncertain reply.

The messaging-on-Relay core is close to the owner's model, A2A outbound is properly SDK-only, and no
shipped migration was edited. Merge after:
- C1, I1 and I2–I6 are fixed;
- the `full-db` migration subset passes;
- the board→messaging composition (S1) is scheduled.
