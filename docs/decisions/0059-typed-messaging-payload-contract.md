# Typed messaging content inside Relay TEXT

Status: implemented as the internal contract and publication boundary for Slice 1
of [Finishing Relay](../relay-finishing.md), 13 November 2026. The live sender is
still board-backed. Topic creation, consumer dispatch, message migration and
production wiring belong to Slice 3; no dual publication is introduced here.

Follows [0057](0057-board-forum-and-relay-messaging-substrate.md). Relay's envelope,
kind set, schema and operations are unchanged, as are `message.*`, `send_message`
and SDK contracts. This is general platform messaging work, independently of the
later A2A rewrite.

## Contract

`messaging/MessagePayload` is an immutable validated DTO. Its codec owns a
`plowshare-message/1` JSON object carried in the existing `RelayPayload.Text`.
All fields below must occur exactly once, including nullable `reply_to`:

| Wire field | Type and invariant |
| --- | --- |
| `schema` | Exactly `plowshare-message/1`; a codec version, not a domain field |
| `message` | Existing `bdm_` message identity |
| `sender`, `recipient` | Resolved `ins_` instance addresses, never definition selectors or topic names |
| `body` | Exactly one of the two body objects below |
| `reply_to` | Original `bdm_` request identity or explicit JSON null; cannot name this message itself |
| `conversation` | Recipient's resolved `cnv_` conversation identity; a consistency check, not an inspection grant |
| `reply_expected` | Required JSON boolean; no coercion or default |
| `final` | Required JSON boolean; true requires a non-null `reply_to` |

Identifiers are at most 256 characters, retain their case and require their
stated prefix followed by nonempty ASCII alphanumerics. Existing minted IDs use
the sortable hexadecimal `MemoryIds` scheme, not UUIDs. Padding, controls, path
syntax and Unicode in these address suffixes are refused, not normalized.

Bodies have a closed typed union:

- `{"kind":"inline","text":"..."}` preserves nonblank text verbatim. Strict
  UTF-8 validation rejects NUL and unpaired surrogates. The codec's explicitly
  supplied messaging byte allowance is enforced on reads and writes; newlines,
  quotes and surrounding whitespace around content are preserved.
- `{"kind":"retained","message":"bdm_..."}` references this same message's
  already retained body. It cannot name another record or carry inline text.
  The owning messaging transaction must have materialized that body before
  publication. The consumer resolves it through messaging's authorized record
  contract; Relay and the codec do not hydrate it or grant access. This permits
  required harness outcomes to preserve their exemption from authored-text
  limits without weakening inline admission.

Unknown, missing, duplicate, mistyped or unsupported nested fields are refused.
The parser also rejects trailing input, excessive nesting and oversized input
before returning a DTO. The wire-size allowance covers worst-case JSON escaping
plus bounded metadata and is capped by Relay TEXT's portable raw-byte ceiling.
That is separate from the inline body limit and later deployment admission.
Diagnostics contain neither input excerpts nor parser causes that could disclose
message content. JSON stays inside `MessagePayloadCodec`.

## Authority and publication boundary

Consumers of the capability depend on `MessagePublications`;
`RelayMessagePublications` implements it using the existing `Relay` interface and
the codec. It neither imports board implementation types nor executes SQL.

The existing harness remains responsible for authenticated sender binding,
project membership, two-sided routing policy, active-instance/lifecycle checks
and the call-ID send identity. A `Binding` is the already resolved internal
participant snapshot from that path, not a wire DTO or a substitute for those
checks. The publication boundary must never accept an externally manufactured
binding as authority. Reuse the authorized snapshot; do not repeat participant
or permission queries merely to encode a publication.

`Binding` requires same-account participants and a project-scoped inbox matching
the resolved recipient's project ID. For replies, it carries the retained
original request route and requires the original recipient to send back to the
original sender. The payload's sender, recipient, destination conversation and
reply identity must exactly match that binding before anything is published.
Fresh routing policy is not reapplied to an already authorized correlated reply;
the existing send path still owns other lifecycle and input-validity fences.

The publisher is server-derived `messaging:<SHA-256(account)>`. Public SDK
publishers use their existing disjoint `sdk:<hash>` namespace; no public operation
lets a caller select the internal messaging publisher. A read checks the exact
expected account publisher before inspecting either carrier kind or JSON.
Foreign rows return an explicit empty projection, even if malformed. The future
consumer advances its own leased cursor past them. A malformed row from the
expected publisher fails visibly. Its event ID must equal the decoded message ID.
Recipient/inbox verification and turn dispatch remain the owning consumer's job.

## Identity, transactions and uncertainty

The message ID is the Relay event ID. Encoding uses a fixed field order, and the
owning message intent supplies a retained occurrence time and causation. Equal
reuse therefore reaches Relay as equal content; changing body, flags or binding
under that identity reaches Relay's existing conflicting-reuse refusal. No new
idempotency table, ordering log or call-ID scheme is introduced.

Reply-to and destination conversation stay inside the payload. The publication
boundary adds no messaging values to Relay's envelope correlation/causation ID
fields; the existing typed runtime causation is passed through unchanged.

The owning cutover must retain the message/body and publish in one transaction.
This boundary registers no topic, grants no allowance, starts no turn and moves no
cursor. Validation finishes before its single publication attempt. An uncertain
failure propagates; `retained` performs read-only receipt reconciliation against
the same retained send intent, including publisher, encoded content, occurrence
time and causation. A conflicting receipt fails rather than being mistaken for
success. An empty receipt is not proof of non-delivery or permission to resend automatically.
Topic expiry still ends Relay's deduplication window; durable messaging identity
remains above the broker.

## Verification and scope

Codec tests pin the wire shape, inline/reference round trips, UTF-8 bounds,
strict field/type/version handling and content-free failures. Mocked publication
tests cover sender/route binding, cross-account and wrong-scope refusal, reply
ownership, foreign-publisher skip-before-decode, own-publisher corruption, stable
repeat/conflict behavior and read-only reconciliation without a hidden resend.
The existing Relay contract tests continue to pin its six general kinds.

These tests require no database. They verify the new boundary's use of Relay's
existing contract, not PostgreSQL deduplication, transaction rollback, live
subscriber behavior or the future messaging migration.
