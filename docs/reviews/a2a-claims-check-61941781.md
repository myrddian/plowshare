# Fact-check: decision 0065 (A2A Relay adapter contracts and capability gaps)

Checked against `origin/codex/relay-finishing` at `61941781`, in a detached worktree (since removed).
Base for the diff: `7ca27b48`. The range is **one commit** ("Implement Relay messaging, lifecycle
and SDK parity", 300 files, +27766/−9111), so no change can be attributed to a slice from git
history alone; attribution below rests on which decision record documents the change.

Path shorthand: `S/` = `plowshare-server/src/main/java/io/aeyer/plowshare/server/`,
`A/` = `integrations/a2a/src/main/java/io/aeyer/plowshare/a2a/`,
`AT/` = `integrations/a2a/src/test/java/io/aeyer/plowshare/a2a/`.

What I ran: `./gradlew :plowshare-a2a:test :plowshare-a2a:installDist --offline` in the review
worktree. 34 tests, 0 failures; both launchers were produced; `plowshare-a2a-relay` with no
arguments exits with the usage error. I did not run `format`, `formatCheck`, the repository
`check`, or `integrationBoundaryCheck`, and no database tests.

Totals: **47 TRUE, 10 PARTLY, 0 FALSE, 2 UNVERIFIABLE** (59 claims).

## Claims table

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Adapter owns translation, peer credentials, remote identities, dispatch evidence; Relay owns ordering and leases | TRUE | `A/relay/RelayAdapter.java:13-16`, `A/relay/DispatchJournal.java:8`, `A/relay/RelayConfiguration.java:19` |
| 2 | Row 1: `relay.publish/consume/ack/log` are available with explicit project/account port grants | TRUE | `S/relay/ProjectRelayPorts.java:95-118` (membership + `properties.permits(account, project, topic, direction, group)`); `A/relay/RelayMain.java:36-57`. Nuance: `relay.log` is authorized as project inspection (`S/ws/RelayFrames.java:83-86`), not by a port grant |
| 3 | Row 2: discovery/send/read/cancel are adapter-owned A2A 1.0 JSON-RPC with typed content codecs | TRUE | `A/A2aClient.java:114,181,198,202`; codecs `ExternalPayloadCodec`, `AgentCardCodec` come from the SDK |
| 4 | Row 3: durable intent, crash evidence and no repeated mutation without an outgoing core queue | TRUE | `A/relay/FileDispatchJournal.java:147-176`; `A/relay/RelayAdapter.java:144-177` |
| 5 | Row 4: publishing TEXT to a receiving agent topic through Relay is available | TRUE | `S/relay/ProjectRelayPorts.java:147-193`. Inbox name is `messaging.agent.<hash>.<hash>` (`S/messaging/RelayMessageInboxes.java:66-70`); no prefix is reserved, so an operator port grant on that name is accepted |
| 6 | Row 4: the inbox skips `sdk:<hash>` publications and admits only `messaging:<hash>` publications that match an existing messaging record/route | TRUE | `S/messaging/RelayMessagePublications.java:60` (skip), `S/messaging/RelayMessageInboxes.java:236-242, 259-277` (snapshot required and compared), `:292` (cursor advances past the skipped row) |
| 7 | Row 4 verdict: inbound delivery to a receiving agent topic is **Blocked** | PARTLY | Blocked only for a raw `relay.publish`. `incoming.receive` is a public SDK operation (`sdk/java/.../IncomingClient.java:37`) whose server path ends in `inboxes.publish` (`S/messaging/RelayMessaging.java:366-507`, `:742`), i.e. it already reaches the agent topic and is dispatched from there |
| 8 | Row 5: `incoming.*` is "today's adapter-specific implementation" | PARTLY | It works and is the current route. The code labels it "Protocol-neutral adapter ingress" (`S/ws/IncomingFrames.java:17`) and Discord uses it (`integrations/discord/src/gateway.ts:51-73`). Its `Source` record is A2A-shaped (`plowshare-protocol/.../Incoming.java`, `ROLE_USER`, `taskId`, `contextId`) |
| 9 | Row 5: `message.*` supplies instance inspection/lifecycle, not external message admission | TRUE | `S/ws/FrameTypes.java:927-935`; `sdk/java/.../MessagingClient.java:139-319` has no send or receive |
| 10 | Row 5: no independent public messaging admission operation binds an external sender/context to an instance, creates the record/route and returns a durable receipt | PARTLY | True only with `incoming.*` excluded by premise. `incoming.receive` does all four: client alias + context UUID, idempotency by request UUID, record/route creation, `Incoming.Task` receipt (`S/messaging/RelayMessaging.java:366-507`) |
| 11 | Row 6: `incoming.catalog` is the current served-agent/command projection | TRUE | `S/ws/IncomingFrames.java:58-95` |
| 12 | Row 6: general agent/run and Relay operations do not replace its served-command admission contract | PARTLY | `agent.list` already projects each served agent's commands from the same `CommandCatalog` (`S/ws/AgentListHandler.java:94`, `S/api/AgentRows.java:81-104`). Only the admission half (refusing an ungranted command at receipt, `S/ws/IncomingFrames.java:104-115`) has no other home |
| 13 | Row 7: Home Assistant/shared runtime, Discord and Network Privacy Watch are existing outgoing/incoming consumers | TRUE | `integrations/runtime/.../Gateway.java`, `IntegrationRuntime.java`; `integrations/discord/src/gateway.ts`; `integrations/network-privacy/src/plowshare_privacy/peer.py` |
| 14 | Row 8: scheduled agent work calls the native turn runtime directly and is already independent of `incoming.*` | PARTLY | True for agent/command targets (`S/events/AgentRunner.java`, `jobs.submitEvent` / `turns.speakForExecutor`). For `target.kind == "message"` it calls `messaging.receiveScheduled` (`AgentRunner.java:84`), which builds an `Incoming.Receive` and runs the same private `receive` path, external instance lifetime and external context/task tables (`S/messaging/RelayMessaging.java:342-363`). It avoids the WS operation, not the implementation that 0058 lists for removal |
| 15 | Evidence: `RelayMessagePublications.read` checks the exact server-derived `messaging:` publisher before decoding | TRUE | `S/messaging/RelayMessagePublications.java:60` precedes `codec.read` at `:66`; exact equality with `messaging:` + SHA-256(account), `:83-85` |
| 16 | Evidence: `ProjectRelayPorts.publish` derives the `sdk:` publisher | TRUE | `S/relay/ProjectRelayPorts.java:182`; `plowshare-protocol/.../RelayPort.java:27-29` |
| 17 | Evidence: `MessagePayload` addresses `ins_` instances; a retained body requires a messaging-owned record | TRUE | `S/messaging/MessagePayload.java` constructor (`identifier(sender, "ins_")`, `Retained` must name this message). Admission needs the record for inline bodies too (`RelayMessageInboxes.java:259-262`) |
| 18 | Evidence: `MessageInboxes.publish` accepts an authorized internal participant snapshot | TRUE | `S/messaging/MessageInboxes.java` (contract comment); `S/messaging/MessagePublications.java` `Binding` |
| 19 | Knowing an inbox topic or copying the JSON cannot confer authority | TRUE | Publisher equality plus record/route comparison (rows 6, 15) |
| 20 | `agent.run` lacks message receipt/routing and context admission semantics | TRUE | `S/api/RunAgentRequest.java:62-70`: task, project, session, conversation; no request identity, no client isolation |
| 21 | Calling `incoming.*` preserves the coupling being removed | TRUE | By definition; 0058 lists `incoming.*` as a removal candidate, not an authorized removal |
| 22 | Publishing to a generic Relay topic creates no authorized message and no receiving turn | PARTLY | True for messaging. It omits registered Relay receivers: an SDK publication to a topic routed to `board.open`/`board.post` already produces agent work with publisher attribution and a `reply_to` topic (`S/relay/BoardRelayReceiver.java`, `S/relay/RelayPublisherAccounts.java`, decision 0060). The review never considered that route or pattern |
| 23 | An orchestration wrapper would create a separate execution path without messaging reply ownership | UNVERIFIABLE | Design judgement; no code to test it against |
| 24 | A separate general platform task (external message admission + command projection/admission) is needed before inbound can ship | PARTLY | A core change is needed only if `incoming.*` must go. The task as specified restates `incoming.receive/status/cancel/catalog` (see assessment) |
| 25 | The document proposes no permission bypass, internal publisher selection, transport escape hatch or adapter-specific endpoint | TRUE | Nothing of the kind is proposed or present in the adapter |
| 26 | Outbound worker consumes one TEXT record at a time from its explicit request topic | TRUE | `A/relay/RelayAdapter.java:76-92` (batch bound 1), `:108` |
| 27 | `plowshare-a2a-dispatch/1` has every field; `requestId` canonical UUID; `peer` alias; `action` SEND/READ/CANCEL; nullable `message`, `task`, `context` | TRUE | `A/relay/RelayWorkCodec.java:36-46, 149-171`; `A/relay/RelayWork.java:28-55`. The required `schema` field is not in the document's list |
| 28 | SEND requires validated external content and null top-level task/context | TRUE | `A/relay/RelayWork.java:42-48`; `RelayWorkCodec.java:42-44` |
| 29 | READ and CANCEL require a previously observed task and context for that peer, and a null message | TRUE | `RelayWork.java:49-53`; `RelayAdapter.java:193-211` (also bound to the producer, and only to observations with a non-null result) |
| 30 | Send role and message identity are adapter-controlled | TRUE | `RelayWork.java:46-48`; `A/A2aClient.java:186-188` |
| 31 | Producer publisher allowlist is checked before parsing; a foreign publication is skipped with no external effect | TRUE | `RelayAdapter.java:94` precedes `dispatch`; the foreign record is acknowledged at `:95-103` |
| 32 | Unknown fields, malformed JSON, invalid content, unsupported carriers and retention gaps stop the worker without acknowledgement | TRUE | `RelayAdapter.java:88-89, 108-113`; `RelayWorkCodec.java:47-49`; test `foreign_publishers_are_skipped_and_invalid_own_requests_and_effect_depth_stop_before_io` |
| 33 | `plowshare-a2a-observation/1` carries request UUID, source event, authenticated producer publisher, peer and a validated observation | TRUE | `RelayWork.java:57-73, 139-142` |
| 34 | Reply destination comes only from configuration | TRUE | `RelayAdapter.java:213-223` |
| 35 | Replies carry remote provenance; they do not resume a local turn or claim remote usage/cost | TRUE | `A/relay/RemotePeer.java:28-30`; nothing in core consumes the reply topic |
| 36 | `INPUT_REQUIRED` and `AUTH_REQUIRED` remain explicit remote states | TRUE | `RemotePeer.java:32-42` |
| 37 | Journal binds origin, credential fingerprint, project, topics, group, consumer UUID, producers, peer endpoints/credential fingerprints; never stores credentials | TRUE | `A/relay/RelayConfiguration.java:58-93`; only the combined hash is stored (`FileDispatchJournal.java:68-73`) |
| 38 | Journal is single-owner | TRUE | `FileDispatchJournal.java:41-58`; test `journal_locks_owner_generation_and_corruption_fail_closed` |
| 39 | An intent is persisted and fsynced before external I/O | PARTLY | True for send/read/cancel (`RelayAdapter.java:157` then `:159-165`; `FileDispatchJournal.java:162-172`). Agent Card discovery, an authenticated HTTP GET, runs before the intent (`RelayAdapter.java:138-140`) |
| 40 | A recovered intent becomes `UNKNOWN`; neither send nor cancel is repeated | TRUE | `RelayAdapter.java:125-135`; tests `crash_after_intent_before_send…`, `restart_after_peer_effect_but_before_result_save…` |
| 41 | A retained task/context can be inspected by an explicit new READ for the same peer | TRUE | `RelayAdapter.java:193-211`. See overstated note 6 |
| 42 | A new operation UUID cannot claim another peer's or producer's observed remote task | TRUE | `RelayAdapter.java:199-210`; test `another_authorized_producer_cannot_adopt_observed_remote_tasks` |
| 43 | The journal never evicts; capacity exhaustion stops before another external effect | TRUE | `FileDispatchJournal.java:113-117, 149-154`; `RelayWork.java:160` |
| 44 | Observation and a stable reply publication intent are persisted before publishing | TRUE | `RelayAdapter.java:176-185`; `RelayWork.java:144-148` (name-based UUID) |
| 45 | After an uncertain reply publication, the worker stops instead of republishing | TRUE | `RelayAdapter.java:73-75` |
| 46 | Reconciliation accepts only an authorized typed `relay.log` page for the configured project/topic with the exact event/publisher/content/time/correlation | TRUE | `RelayAdapter.java:229-253`; `RelayMain.java:59-66` |
| 47 | Once confirmed, a replacement source lease can acknowledge the original request without repeating peer I/O | TRUE | `RelayAdapter.java:179` then `:95-103`; test `lost_ack_and_duplicate_source_never_repeat_external_effects` |
| 48 | Request UUID reuse with different content or source identity conflicts | TRUE | `RelayAdapter.java:121-124`; `FileDispatchJournal.java:118-129` |
| 49 | No redirects, automatic HTTP mutation retries, credential fallback or downgrades | TRUE | `A/A2aClient.java:97-106`; bearer sent to a card URL only on the RPC origin (`:125-129`) |
| 50 | Outbound worker is a separate process entry point; existing entry points stay | TRUE | `integrations/a2a/build.gradle.kts:11, 19-25`; `A/Main.java` unchanged |
| 51 | The SDK-only outbound portion is implemented in `integrations/a2a/.../relay` | TRUE | Nine new files under `A/relay/` |
| 52 | Focused tests cover dispatch-before-I/O persistence, interrupted-intent recovery, peer response loss, lost reply/source acks, receipt reconciliation, producer/peer isolation, UUID conflicts, JSON validation, journal locks/ownership, broker incarnation changes | TRUE | `AT/relay/RelayAdapterTest.java` (9 tests), `AT/relay/RelayBoundaryTest.java` (3 tests); all passed in my run |
| 53 | A real public SDK socket and an independent A2A HTTP fixture verify the adapter path without server imports | PARTLY | The SDK client is real; the broker is a `MockWebServer` WebSocket in `LEGACY` transport mode that delegates to the test's `FakeGateway` (`AT/relay/RelaySdkTest.java:83-124`). No Plowshare server, port grant, envelope or `relay.log` call is exercised |
| 54 | Both launch scripts build; the replacement loads its entry point and validates arguments | TRUE | Ran `installDist`; `bin/plowshare-a2a` and `bin/plowshare-a2a-relay` exist; the latter prints the usage error from `RelayMain.java:21` |
| 55 | Google formatting, `formatCheck`, repository `check` and `integrationBoundaryCheck` passed without database opt-in | UNVERIFIABLE | Not re-run. Only the A2A module tests were run (34/34 pass) |
| 56 | The module no longer uses server classes in its tests | TRUE | `integrations/a2a/build.gradle.kts` diff removes `testImplementation(project(":plowshare-server"))`; `AT/ApplicationExamplesTest.java` diff; no `plowshare.server` import under `integrations/a2a/src` |
| 57 | The server HTTP-client inventory now names the A2A HTTP boundary, previously hidden by a wildcard import | TRUE | `plowshare-server/src/test/.../InvariantsTest.java:368-370`; `A/A2aClient.java` diff replaces `import okhttp3.*;` |
| 58 | No SDK, shared protocol or core production implementation was extended for this adapter | PARTLY | No main-source change names A2A. But the adapter cannot run without `RelayPort.Batch.generation`, a protocol field added in this same commit (`plowshare-protocol/.../RelayPort.java` diff; `FileDispatchJournal.java:84-95` requires it non-null). 0062 documents that field for group retirement, so the attribution is plausible but cannot be shown from a single commit. See false/overstated note 1 |
| 59 | Platform gaps, consumer migrations and legacy core removal remain unfinished; Slice 5 is not complete | TRUE | Legacy `A/Receiver.java`, `A/Main.java`, `S/ws/IncomingFrames.java`, `outgoing.*` frames all remain |

## False or overstated claims

No claim is flatly false. Ten are overstated, and three of those carry the document's conclusion.

1. **"No SDK, shared protocol or core production implementation was extended for this adapter"
   (58).** Three qualifications.
   - The adapter hard-depends on `Batch.generation`, new in this commit. The claim holds only if
     that field is credited to Slice 4, which the single commit cannot prove.
   - The new messaging core written in this commit (`S/messaging/RelayMessaging.java:336-563`,
     `RelayMessageInboxes.java:168-169`) re-implements the external/incoming admission path,
     the `external` instance lifetime and the external context/task records. Nothing was
     *added* for A2A, but the adapter-shaped responsibility that 0058 says "entered Plowshare
     core" was carried forward into the rebuilt messaging layer rather than left behind.
   - The server's tests still import the adapter: `plowshare-server/build.gradle.kts:28`
     (`testImplementation(project(":plowshare-a2a"))`), `IncomingEndToEndTest.java:8-9`,
     `OutgoingEndToEndTest.java:6`. The document reports the adapter-to-server direction as
     fixed and is silent on the reverse.
2. **"Blocked" for inbound delivery (7, 10).** Inbound is not technically impossible through the
   public SDK. `incoming.receive` is public, authenticated, idempotent, context-binding, and
   after the Slice 3 cutover it already travels `messaging → Relay agent topic → admission →
   dispatch`. The block is a policy position: `incoming.*` is excluded by premise, and 0001
   forbids the core change that would replace it. The document presents this as a capability
   finding.
3. **"Scheduled agent work is already independent of `incoming.*`" (14).** Message-target
   schedules run through `receiveScheduled`, which constructs an `Incoming.Receive` and shares
   the private `receive` implementation and its external tables. Removing the incoming
   implementation as 0058 lists it would break those schedules. Only the WS operation is unused.
4. **Catalog gap (12).** `agent.list` already returns each served agent's command catalog from
   the same `CommandCatalog`. What is actually missing is narrower: a place to refuse an
   ungranted command at admission.
5. **Alternatives review (22).** It dismisses "a generic Relay topic" without mentioning the
   registered-receiver mechanism that Slice 2 built for exactly this shape of problem
   (authenticated SDK publication → owning API → receipt → optional reply topic).
6. **Outbound recovery (41, 43, 39, 53).** Each is true as worded and weaker than it reads.
   - After an `UNKNOWN` outcome on a *first* SEND there is no observed task or context, so no
     READ can ever be admitted for it; the remote effect stays permanently unreconciled.
   - "Never evicts" means a hard lifetime cap of 1024 requests per binding
     (`FileDispatchJournal.java:116`), after which the worker stops for good.
   - An allowlisted producer that reuses a request UUID halts the worker for everyone
     (`RelayAdapter.java:121-124`), with no acknowledgement and no skip.
   - Agent Card discovery is network I/O with the peer credential before any intent exists.
   - The "real SDK socket" test proves client framing against a fake broker, nothing about
     server authorization.
7. **Outbound is a half loop.** The table marks outbound "Available". The observation lands on
   a reply topic that no agent can read: agents have no Relay consume access, and messaging
   skips `sdk:` publications. An agent can cause a dispatch (through the `relay_publish` grant
   in 0063) and never learn the result. The inbound gap therefore also blocks outbound from
   being useful to agents; the document treats them as separate.

## Assessment of the gaps

**Is inbound delivery truly impossible through the public SDK?** No. Two different things are
being called impossible.
- Publishing directly onto an agent's inbox topic and having it admitted: correctly impossible,
  and it should stay that way. The inbox payload names `ins_`/`bdm_`/`cnv_` identifiers that
  only messaging can mint, and the publisher-before-decode check
  (`RelayMessagePublications.java:60`) is the right security boundary.
- Delivering an external message to an agent at all: possible today through `incoming.receive`.

**Is the proposed platform task the right general capability?** No, on three counts.
- It is `incoming.*` again. "Authenticated external message admission with explicit
  project/agent and external principal/context identity, idempotent receipts and authorized
  task inspection/cancellation" plus "a public current-grant command projection/admission
  contract" maps one-to-one onto `incoming.receive`, `incoming.status`, `incoming.cancel` and
  `incoming.catalog`. Building it would rename the existing surface and migrate four consumers
  for no structural gain.
- It is the wrong shape for the owner's model. It is a request/response operation family beside
  Relay. Under "send_message is a facade over Relay" and "an external is a Relay pub/sub
  participant", an external message should enter by `relay.publish` and leave by
  `relay.consume`. A second, non-Relay ingress door is what the Relay work set out to remove.
- It defers everything hard. Identity namespaces, principal isolation, reply routing, retention,
  cancellation and migrations are all left to the future task, so the record does not narrow
  the design.

**Is there a simpler structural answer?** Yes, and the pieces exist.
- Not "let an external publish to the agent's inbox topic". That would mean either weakening
  the publisher check or letting externals forge messaging identifiers.
- Instead, a messaging-owned Relay receiver on a *separate ingress topic*, the same pattern as
  the Relay → Board bridge in 0060: `RelayReceiver` (`S/relay/RelayReceiver.java`), the
  receiver registry (`S/relay/RegisteredRelayReceivers.java`), server-derived publisher
  provenance (`S/relay/RelayPublisherAccounts.java`), durable delivery intent and receipts
  (`S/relay/RelayDeliveries.java`). The receiver body is the admission logic that already
  exists in `RelayMessaging.receive`.

## Structural changes requested and required

| Change the document asks for | Explicit or implicit | Actually required? |
|---|---|---|
| New public "external message admission" operation (receive, idempotent receipt, inspect, cancel) | Explicit | No. Equivalent to existing `incoming.*`. Required instead: a messaging receiver on Relay, which adds no WS operation |
| New public "current-grant command projection/admission contract" | Explicit | Projection: no, `agent.list` already serves it. Admission check: yes, but it belongs inside the messaging receiver, not in a new contract |
| Removal of `incoming.*`, `outgoing.*`, the external instance/context/task tables and the outgoing package | Implicit (inherited from 0058, "blocked for removal") | Removal of the WS operations: yes, eventually, once consumers move. Removal of the admission *logic*: no. It must be kept and re-homed, and message-target schedules depend on it |
| Hold Home Assistant, Discord and Network Privacy Watch on their current implementations until replacement contracts exist | Explicit | Yes |
| Keep the legacy A2A launcher alongside the new one | Explicit | Yes, until inbound moves |
| A separately authorized platform task and design decision under 0001 | Explicit | Yes. Any answer touches messaging core. The scope should be the receiver below, not the operation family the document describes |
| Agents able to receive external observations | Not asked for; absent | Yes. Without it the outbound worker's replies reach no agent |
| Server tests no longer depending on the adapter module | Not asked for; absent | Yes, if the 0001 boundary is meant in both directions |
| Lifetime bound on the adapter journal (retention or operator compaction) | Not asked for; absent | Yes before production use; 1024 requests is a hard stop |

## Proposed solution

Treat external messaging as one more owning API behind a Relay receiver, exactly as the board
bridge was built, and make that the single authorized platform task.

1. **One messaging receiver, registered beside `board.open/post/close`.** Name it for what it
   does (`message.send`, with `message.cancel` as its second verb). An operator route binds an
   ingress topic in a project to the receiver and pins the destination agent; the payload cannot
   override it.
2. **External principals publish ordinary Relay TEXT** to that ingress topic under their existing
   INGRESS port grant, with a typed `plowshare-message-request/1` body: request UUID, client
   alias, optional context UUID, text, optional qualified command. No publisher, account,
   instance or topic is accepted from the body.
3. **Identity is the server-derived publisher.** The receiver resolves `sdk:<hash>` to its
   account through `RelayPublisherAccounts`, rechecks current project membership and the ingress
   grant at dispatch, and isolates contexts by account + client alias. This is the RBAC/ACL
   model with project-wide visibility: the ingress and reply topics are readable under Relay's
   existing project scope.
4. **The receiver body is today's `RelayMessaging.receive`**, moved behind the receiver and
   stripped of the `Incoming.*` transport DTOs: idempotency by delivery UUID, passive external
   sender, recipient resolution, command-grant check, then the normal `send` →
   `MessageInboxes.publish` → agent topic → admission → dispatch. The agent inbox, its
   publisher check and `MessagePayload` stay exactly as they are. `receiveScheduled` calls the
   same internal admission directly.
5. **Replies leave on Relay.** Messaging publishes the receipt, replies and terminal state as
   `messaging:<hash>` to the reply topic named in the route configuration. The external reads
   them with `relay.consume`; status is the reply topic's log; cancel is a second request on
   the ingress topic.
6. **Outbound closes the loop the same way.** The A2A worker's observation topic is routed to
   the same receiver, so a remote result re-enters messaging as a message to the requesting
   instance. Agents still never touch Relay directly.
7. **Clean break.** Once the receiver exists, migrate A2A inbound, Discord, the shared runtime,
   Home Assistant and Network Privacy Watch to publish/consume, then delete `incoming.*`,
   `outgoing.*`, their SDK clients and the server tests' dependency on the adapter in one cut.
   `agent.list` serves the Agent Card's command list.

This needs no new WS operation, no change to Relay's envelope, and no weakening of the inbox.
It is a core change and so needs its own authorization under 0001, but it is general: every
external uses it, and it is the pattern the repository already accepted in 0060.
