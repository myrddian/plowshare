# Plowshare A2A adapter

A2A remains a separate Java process using the public Plowshare SDK. The rewrite
uses existing Relay TEXT ports and native messaging receivers; external HTTP,
peer credentials and A2A provenance remain in the adapter.

- `plowshare-a2a CONFIG` runs the receiving HTTP listener. Follow the
  [receiving manual](../../docs/a2a-receiving.md) for explicit service credentials,
  durable journal, native routes, client aliases, discovery, context and recovery.
- `plowshare-a2a-relay CONFIG` dispatches configured remote SEND/READ/CANCEL work.
  Follow the [outbound manual](../../docs/a2a-relay-outbound.md) for producer grants,
  journal ownership, native requester returns and uncertain publication recovery.

Build with `./gradlew :plowshare-a2a:installDist`. Java 21 is required. Bind addresses,
server origins, public URLs, journal paths and credentials are explicit deployment
configuration. There is no human-login fallback or assumed colocated server.

One active process owns each private durable POSIX journal. Independent journals
cannot fence duplicate external effects. Retained mutation receipts and immutable
reply bodies grow until deliberate binding retirement; monitor and back up the
whole volume. A restart inspects unknown native admission and never blindly
repeats a peer mutation. See each manual for distinct recovery and exit statuses.

The receiver preserves the supported A2A 1.0 JSON-RPC text subset: Agent Cards,
SendMessage, GetTask, CancelTask, authenticated client/context isolation and the
explicit granted-command extension. Approval remains a native operator action.
HistoryLength is 0..200 and HTTP results remain bounded to 1 MiB; oversized data is
refused explicitly without changing or truncating retained body evidence.

The [Application template](examples/application/plowshare.json) remains an
operator-owned root manifest. Supply explicit access grants and server membership
before registering it. A source directory alone grants no account or runtime
filesystem access. [Integration boundary ADR 0001](../../docs/decisions/0001-integration-boundary.md)
and [native messaging receiver design](../../docs/decisions/0066-relay-messaging-receiver-and-review-remediation.md)
separate platform admission from adapter translation. Consumer migration and
legacy surface retirement are reviewed independently; this README is not a claim
that every dependent client has completed cutover.

Historical contexts require explicit native channel adoption and the private
[provenance migration recipe](migration/README.md). Database identity mapping alone
does not reconstruct A2A history, and an unknown original request is never resent.

A2A peer DTOs and strict codecs are adapter-owned in
`io.aeyer.plowshare.a2a.model`. They are independent from the platform protocol and
Java SDK. Moving Java packages preserves supported A2A JSON and retained Relay
journal shapes; existing journal identities are never recomputed from class names.
The live adapter uses general Relay and catalog capabilities. Legacy
`incoming.*`/`outgoing.*` operations and their old Java worker are retired; the
separate historical exporter recipe deliberately targets an archived pre-cutover
SDK and must run before upgrading away from those operations.
