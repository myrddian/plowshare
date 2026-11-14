# A2A outbound dispatch over Relay

This SDK-only outbound adapter runs outside Plowshare core and uses the existing
Relay ports. The receiving bridge and dependent consumer replacements are covered
by the [review task graph](relay-review-task-graph.md); legacy API retirement has
its own [cutover and history contract](relay-legacy-cutover.md). The general native
receiver and requester-return design is recorded in
[ADR 0066](decisions/0066-relay-messaging-receiver-and-review-remediation.md).
ADR 0065 preserves the earlier capability review, before those replacements.

Build `:plowshare-a2a:installDist`; run its `bin/plowshare-a2a-relay CONFIG` launcher.
The `plowshare-a2a` launcher now composes the separate SDK-only receiving bridge;
see [receiving setup](a2a-receiving.md). Other consumer cutovers have their own gates. Java 21 is required.
No local Plowshare origin, human login, bind address or credential is inferred.
The replacement is an outbound worker and opens no HTTP listener.

## Deployment binding

Configure a durable private POSIX journal directory, explicit server origin,
service credential environment variable, project, request/reply topics, consumer
group and persistent consumer UUID. Configure the permitted producer publisher
identities and the adapter's expected SDK publisher identity. SDK publisher values
are `sdk:` followed by `RelayPort.hash(account)`. Use explicit known account
identities; do not trust a request body to select the publisher or reply topic.
Each topic set is an authority boundary: only give ingress access to producers
allowed to send/cancel work with these configured peer credentials.

The adapter account needs current project work access, an EGRESS port binding for
the request topic/group, an INGRESS binding for the reply topic, and public
`relay.log` access for reconciliation. Producers need their own request-topic
INGRESS grant, or the existing exact-topic `relay_publish` tool grant. A separate
reply consumer uses its own EGRESS grant/group. These are deployment configuration
and existing permissions, not additional server APIs.

Example configuration template; replace the directory, UUID and publisher values
with the actual binding before running:

```json
{
  "origin": "https://plowshare.example.invalid",
  "tokenEnv": "PLOWSHARE_SERVICE_TOKEN",
  "journal": "/REPLACE_WITH_A_DURABLE_PRIVATE_DIRECTORY",
  "project": "integration-project",
  "requests": "adapters.research.requests",
  "replies": "adapters.research.observations",
  "group": "research-outbound",
  "consumer": "11111111-1111-1111-1111-111111111111",
  "replyPublisher": "sdk:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "producers": ["sdk:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
  "maxDepth": 32,
  "replicaMode": "SHARED_JOURNAL_SINGLE_OWNER",
  "peers": {
    "research": {
      "endpoint": "https://research.example.invalid/rpc",
      "bearerEnv": "RESEARCH_PEER_TOKEN",
      "principal": "research-service-account",
      "timeoutMs": 20000
    }
  }
}
```

`agentCard` may explicitly select the peer's card URL. Otherwise discovery uses
that configured endpoint origin's well-known card path. `bearerEnv` is optional
for a public peer; configuring it requires the variable to exist and an explicit
`principal` label for that credential's remote account. This is an operator-owned
identity assertion, not remote account introspection. Peer timeouts
are 1..300000 milliseconds, defaulting to 20000. `maxDepth` must be explicitly
set to the deployment's applicable effect ceiling (1..32). Unknown settings,
coerced numbers, malformed addresses, missing credentials and duplicate JSON
fields fail before a connection or external dispatch.

One binding has one journal and one active process. Replacements reuse its durable
volume, consumer identity and exclusive file lock on a filesystem that supports
reliable POSIX locking, atomic rename and fsync. Shared-journal standby ownership
is the only supported replica arrangement. The optional `replicaMode` accepts only
`SHARED_JOURNAL_SINGLE_OWNER`; other modes fail configuration validation. Independent
journals for the same group are unsafe: broker batch fences cannot protect an
external effect after an old owner loses its lease. A local epoch cannot fix that.
The process cannot discover another host's private directory, so deployment must
also enforce this restriction. This experimental adapter makes no distributed
exactly-once claim. Separate bindings use disjoint topic/group sets and journals.

The journal pins origin, binding, producer/publisher identities, peer destinations
and stable peer principal labels. Routine timeout changes preserve ownership.
Secret changes require the explicit locked migration described below; changing an
endpoint or principal requires a separately reconciled new binding.

## Producer contract

Publish a TEXT value using `RelayClient.publish`, another SDK's existing Relay
publication operation, or the granted `relay_publish` tool. The adapter-owned
`RelayWork.Request` / `RelayWorkCodec` are Java helpers; no shared SDK operation
or Relay envelope change is introduced.

```json
{
  "schema": "plowshare-a2a-dispatch/1",
  "requestId": "22222222-2222-2222-2222-222222222222",
  "peer": "research",
  "action": "SEND",
  "message": {"parts": [{"text": "Investigate this question."}]},
  "task": null,
  "context": null
}
```

Every outer field is required, including nulls. `requestId` is a retained
canonical UUID. A SEND message uses the existing validated external-content DTO;
its message ID and role must be omitted, because the adapter sets them from the
request identity and `ROLE_USER`. Optional remote context/task identities inside
that message must already have been observed for that peer. Explicit command
metadata retains the existing A2A command-extension negotiation; it never grants
local tools or approves an operator decision.

READ and CANCEL carry null `message`, plus the observed remote `task` and
`context`, and use a fresh operation UUID. The adapter binds remote identities to
the configured peer and authenticated producer publisher; another peer or producer
cannot adopt them. The producer must retain
both the operation UUID and original Relay publication identity/content/time.
Reusing an operation UUID with a different source publication or intent is a
conflict, not permission to send the external message again.

The adapter validates the complete request before peer dispatch and refuses
unknown fields. Foreign publishers are acknowledged without decoding their
content. Malformed authorized requests, unsupported carriers, exhausted/missing effect
ancestry, unknown peers, UUID conflicts and unobserved remote identities receive a
durable `REJECTED` observation before the source is acknowledged. Rejection uses a
source-derived diagnostic UUID, including the authenticated publisher, source event
and content hash; malformed input need not supply a trustworthy request UUID. It
does not reserve the producer's claimed UUID or issue a peer operation. The typed
report retains source correlation. Negative diagnostics are published as independent
Relay roots so missing/exhausted ancestry cannot poison the queue; they authorize no
external effect. Consumers must correlate these diagnostics by `sourceEvent`, and
must never execute them as dispatch requests.

Read-only Agent Card discovery gets three attempts with 250/500 ms backoff. Its
bounded exhaustion becomes `PEER_DISCOVERY_UNAVAILABLE`, allowing the next request,
including another peer, to progress. A producer can deliberately submit fresh work
later. Send/read/cancel operations receive no automatic retry. Journal/storage
failures, source retention gaps and uncertain reply publication remain hard stops.
Foreign publications increment one saturating aggregate counter and log the first
and every thousandth skip. There are no per-publisher labels or raw request bodies;
`diagnostics()` exposes bounded aggregate rejection/discovery/foreign counters.

## Observations, crash recovery and reconciliation

The configured reply topic receives a `plowshare-a2a-observation/1` TEXT record
with `requestId`, `sourceEvent`, authenticated `producer`, `peer` and typed
`observation`. Remote task states,
text artifacts and remote provenance are retained. `INPUT_REQUIRED` and
`AUTH_REQUIRED` require an explicit producer/operator response; they do not
approve work or resume a local turn. External usage/cost is not invented.

A locked journal commits and fsyncs intent before send/read/cancel. Restart after
that marker cannot prove whether I/O occurred, so it emits UNKNOWN without
repeating the operation. A new explicit READ may inspect a previously observed
remote task/context. No automatic HTTP mutation retry, redirect, reconnect,
credential renewal or protocol downgrade occurs.

Observations and stable reply publication intent are also persisted before the
SDK publish. Retained occurrence times are normalized to microseconds, matching
the broker's durable timestamp precision, including when older journals load.
Only the SDK's explicit `Delivery.NOT_SUBMITTED` result permits another publication
attempt. That positive proof is journaled; another attempt uses the saved result
and repeats no peer I/O. The launcher permits five consecutive publication attempts
with bounded exponential backoff, then exits temporarily. Any unknown delivery,
including a lost acknowledgement, leaves `REPLY_INTENT` and stops the worker.

Inspect the durable state and scan the retained reply log using:

```sh
plowshare-a2a-relay CONFIG pending
plowshare-a2a-relay CONFIG pending AFTER_UUID
plowshare-a2a-relay CONFIG reconcile AFTER [PAGES]
```

`pending` lists at most 100 unsettled IDs/phases/source IDs and a continuation UUID,
without result bodies. `AFTER` is a Relay decimal cursor (`0` starts the retained
log). Reconciliation reads 100 rows per page, at most 100 pages by default or an
explicit 1..1000. It scans toward the initially captured head, checks topic
incarnation and cursor progress, and reports continuation/head/unresolved counts.
It confirms only exact retained publisher, event ID, content, occurrence time,
correlation and parent-source evidence. It never republishes. A gap, absent row,
expired receipt or completed scan without a match leaves delivery unknown.

After positive confirmation, restart normally to acknowledge the source without
repeating peer I/O. Lost source acknowledgements likewise preserve deduplication.
If delivery cannot be proven, an operator can explicitly choose to lose the reply:

```sh
plowshare-a2a-relay CONFIG abandon-reply REQUEST_UUID "Operator decision and independent evidence; delivery remains unconfirmed"
```

This records bounded operator-supplied evidence and an `ABANDONED` disposition,
never `CONFIRMED`, and performs neither publication nor peer I/O. Absence alone is
not evidence of non-delivery. Restart can acknowledge that source; downstream work
might lose an observation. A first-send UNKNOWN without a remote ID stays explicit:
the adapter cannot fabricate a recoverable task or authorize READ/CANCEL against a
guessed remote identity.

## Returning observations to a native requester

Set `replyFormat` to `MESSAGE_RETURN` for a binding configured as a native execution
export. Configure the matching [messaging export policy and return receiver](messaging-relay-exports.md),
including the adapter account's exact return-topic ingress grant. The producer
allowlist can contain the server-owned `execution:<account-hash>:<definition-hash>`
identity; never substitute a person's SDK publisher or a wildcard.

The adapter emits `plowshare-message-return/1` with its stable reply UUID, the
original exported Relay **event** UUID, and the complete typed observation encoded
as text. It asserts no instance, account, agent or run ending. `finalReply: true`
settles this single dispatch-observation request; the body may still accurately
report remote WORKING or UNKNOWN. It does not assert remote task completion or
resume/approve the source run. Remote task state, provenance and artifact text
remain present without truncation. Subsequent READ/CANCEL are explicit operations.

Generic return bodies are bounded to 1 MiB. Native `plowshare.messaging.body-bytes`
and Relay text allowances must be explicitly configured to accept the intended
report size; the native default is 65,536 bytes. A larger legitimate observation
can therefore be refused by the default deployment. Complete observations stay in
the private journal; the adapter never truncates them to make a return fit and
never labels broker acceptance as successful native delivery. The return receiver
uses its retained export ancestry, so this mode supplies no parent lookup that
would depend on the original broker row remaining retained.

The default `A2A_OBSERVATION` format still serves explicit SDK observation-topic
bindings. Format is part of stable journal identity. Use a deliberately separate
native export binding; do not reinterpret pending legacy publications by changing
format in an existing journal.

## Journal capacity and credential migration

The active snapshot is bounded to 1024 unsettled/full records and 64 MiB; space
for results is reserved before external dispatch. Before adding work at 768
records, or when a new intent reservation encounters byte pressure, the journal
compacts settled records automatically. Operators can also
run `plowshare-a2a-relay CONFIG archive`. Compact UUID receipts retain the original
producer/source/content ownership; indexed peer/producer task and context proofs
preserve follow-up authorization. Lookup uses direct bounded files, without reading
all historical results. Immutable receipts/proofs are fsynced before removing full
records, so an interrupted compaction can retain both forms and recover safely.

Compact metadata is retained for the binding's entire lifetime, with no replacement
count cap. Disk usage therefore grows with distinct operations and remote identities;
monitor storage, back up the entire directory, and retire a binding deliberately
when its lifetime storage budget is exhausted. Full result bodies are discarded
only after confirmed publication or explicit abandonment. Never delete receipts,
proofs or reset a journal to resume an existing group. Retire/reconcile the old
binding and use a new topic/group/journal together. Broker group reincarnation
changes remain an operator stop.

To rotate secrets, stop the owner, retain the full journal backup and first read
its digest with `plowshare-a2a-relay OLD_CONFIG owner`. Independently verify that
replacement credentials identify the same Plowshare publisher and declared remote
principals, then run:

```sh
plowshare-a2a-relay NEW_CONFIG rotate-config PREVIOUS_OWNER_DIGEST
```

The exclusive-lock migration requires the exact old digest and unchanged stable
identity/destination descriptor, then records previous/current credential digests
and migration time. The launcher verifies the authenticated Plowshare
`admin.status.handle` equals the configured publisher before running or rotating.
It neither replays work nor changes observations. External peer principal labels
remain operator assertions; deliberately lying about a replacement peer credential
is outside this guarantee. A v1 journal stored only a combined digest: open it once
with its matching old credentials, destinations and timeouts plus the new stable
peer principal labels to establish v2 ownership before rotation. If old matching
configuration is unavailable, automatic migration is refused.

## Supervision and restart safety

The launcher exits 75 for classified transient SDK transport failure with no
uncertain reply, or exhausted positive NOT_SUBMITTED publication retries. Reuse the
same journal after bounded supervisor backoff. It exits 78 for uncertain reply
publication, corrupted/changed storage or configuration, retention gaps and other
unclassified I/O failures. Those stops require explicit inspection/reconciliation;
a supervisor must not endlessly restart them. Peer discovery outages are handled
within the bounded per-request policy above.

For a systemd deployment, supply the launcher/config paths and environment through
operator-owned configuration and use, for example:

```ini
[Unit]
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Restart=on-failure
RestartSec=10
RestartPreventExitStatus=78
```

The executable owns no deployment defaults or service installation. Supervision is
availability plumbing around the retained evidence; it does not prove successful
external delivery. Recovery after an INTENT marker emits UNKNOWN and never repeats
send/cancel, even when the process may have died before actually sending.

The focused tests use a real public SDK WebSocket, an independent A2A HTTP
fixture and durable temporary journals. They establish protocol/dispatch behavior
and crash handling without server internals or PostgreSQL. They do not establish
a live deployment, inbound agent delivery or removal of the old core surfaces.
