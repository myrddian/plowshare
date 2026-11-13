# Granted Relay publishing from agents and orchestrations

Status: implemented for Slice 6 of [Finishing Relay](../relay-finishing.md),
13 November 2026. This is a general platform capability. It does not implement
the deferred A2A rewrite or change Relay's envelope or `message.*`.

## Contract and authority

`relay_publish` is an ordinary definition-granted tool. Its complete input is
`{topic, text}`. The runtime supplies the account, execution home, definition,
session, accounting run, conversation, turn, step and call identity. Scripts use
the same tool through their existing journaled command path; no sandbox host
access or second publication API is introduced. Feature tools such as
`send_message` retain their owning semantics.

Two independent grants are required: the definition's `tools: [relay_publish]`
and an operator-installed exact `(project, definition, topic)` entry in
`plowshare.relay.publishing.grants`. There are no defaults or wildcards. A topic
grant does not grant another tool, another definition, another project, Relay
read access or downstream execution authority. It permits registration of that
exact TEXT topic using the standard initial policy; existing kind and retention
cannot be changed by the tool.

Each call checks current Contributor membership, Personal ownership, execution
project ID and the current caller-scoped definition's tool grant. Agent and
orchestration registries are selected explicitly by the harness so an identically
named definition in the other registry cannot supply a missing grant. Session
definition resolution runs outside the publication transaction. As with other
execution gates, an earlier check is not an authorization lease; revocation may
race an already admitted atomic local effect. Cancellation before submission
refuses publication; cancellation afterward does not retract it.

Strict boundary conversion rejects unknown fields, duplicate keys, trailing
values, coercion, nulls, invalid topic names and invalid text. Text is preserved
verbatim within Relay's portable and deployment UTF-8 limits. Model arguments
cannot choose a publisher, project, event ID, correlation or parent. The publisher
is the authenticated account, allowing existing receivers to apply their own
publisher checks. Publication acceptance does not assert downstream completion.

## Identity, ancestry and transactions

The specialist `RelayPublishingRepository` matches retained accounting ownership
by project, conversation, turn, account, run and definition before inheriting
the conversation's Relay causation. Accounting run IDs are not job IDs. Missing,
legacy or exhausted ancestry fails closed; the call consumes one effect hop
without creating a new root. Native scripts have durable run ownership even when
they make no model calls.

An event UUID is derived from the authenticated execution coordinates and harness
call ID. Topic and exact text are fingerprinted separately, so changing work under
the same call cannot acquire a fresh identity. V242 introduces a receipt ledger
independent of broker retention. In one local database transaction the repository
inserts the intent, registers the topic, publishes through Relay and saves the
position and publication timestamp. Same-call contenders serialize at the ledger
primary key before topic locking. Any failure rolls back all these changes.
There is no network or model call in this transaction.

A repeated call returns the original receipt without registering or publishing,
even if its publication has expired or the topic has been removed. Conflicting
reuse is refused. The ledger stores coordinates, fingerprint and receipt, not a
second retained message body. It survives conversation/topic cleanup and is
removed with its owning project. There is no automatic retry or reset operation.
The broker applies the deployment text allowance only to new publications; lowering
that allowance does not invalidate an existing receipt.

The success result is JSON containing `eventId`, `topic`, a decimal-string
`position`, and ISO-8601 `publishedAt`. A scripted command journals this result
through the existing started/executed/completed lifecycle. A cached raw receipt
can finish post-processing without invoking the tool. A command interrupted after
start and before its journal receipt remains uncertain and is refused automatic
replay, as other effectful commands are. Infrastructure failures propagate rather
than masquerading as successful empty results. Never issue a new call merely to
repeat uncertain work.

## Compatibility and verification

The new tool is registered but never added to existing definitions automatically.
The default grant list is empty. Existing scripts, messaging tools, SDK operations,
Relay envelopes and retention rules keep their contracts. The migration is
additive; rolling application code back leaves the receipt ledger unused.

Mocked tests cover strict conversion, isolated per-run call identity, current
grants, project/Personal fences, effect-budget exhaustion, cancellation and
infrastructure propagation. Harness tests exercise native script dispatch,
journaling, cached receipt recovery and uncertain-command refusal without model
inference. Targeted PostgreSQL verification covers migration, concurrent duplicate
calls, atomic rollback, policy preservation, provenance matching and receipts
after publication expiry/topic removal. These database assertions require real
PostgreSQL; mocked tests do not establish transaction correctness.

Validation completed: `formatCheck check` passed in default mode. The 20 focused
tests passed, including six selected `JdbcRelayPublishingRepositoryTest` cases
under `-PfullDb`. The isolated boot-context and image-reachability inventory tests
also passed. No live model inference or external adapter execution was required.
