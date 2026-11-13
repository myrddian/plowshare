# Runtime and Home Assistant Relay cutover

Status: implemented for review task T22; final acceptance remains tracked in the
repository task graph. The runtime uses Relay requests and retains old outgoing
journal evidence read-only. This document does not claim a deployment or completed
live acceptance.

The runtime will consume integration requests through the public Relay SDK and
return results through the retained native requester capability authorized in
[ADR 0066](../../../docs/decisions/0066-relay-messaging-receiver-and-review-remediation.md).
Home Assistant continues to own its selected states and service calls. The runtime
continues to own script plans, observation continuity, physical effect receipts,
feedback suppression and orchestration starts. No new server operation, tool,
database table or adapter-specific runtime capability is part of this change.

## Request and return contracts

Each binding explicitly configures its project, request and return TEXT topics,
both topic generations, channel, permitted request publishers and the adapter's
SDK publisher. These values enter its durable configuration fingerprint. Different
bindings cannot adopt one another's request, cursor or outcome evidence. The
runtime reads bounded Relay log pages; it does not claim that a log position means
the external action completed.

A request is one strictly validated existing `plowshare-integration/1` DTO as a
TEXT value containing `schema`, `binding`, `operation` and typed `arguments`.
The legacy `parts` wrapper is omitted. The operations remain
`states.read` and `actions.execute`; existing adapter allowlists and parameter
validation apply before I/O. Unknown fields, duplicate keys, invalid identities,
unsupported operations and oversized requests are refused. No request chooses an
endpoint, credential, entity target, return destination or native participant.

The authenticated Relay event UUID identifies the exported request. A model body
cannot replace that identity. For native requester returns, the producer must use
an execution export explicitly configured by the server; ordinary unrelated SDK
publication does not manufacture a native requester binding.

The initial retained export contract supports exact TURN and MESSAGE sources.
A currently running ORCHESTRATION source is unsupported. Event conductors reuse
their supplied authorized input and normal completion lifecycle; they do not claim
a new fresh-read continuation. The direct question acceptance case separately
tests a supported fresh export and later correlated native reply.

Results retain the existing `COMPLETED`, `FAILED`, `REJECTED` and `UNKNOWN` states
and typed integration evidence. The body distinguishes delivery state from original effectState; an oversized
return is explicitly REJECTED and retains the actual external state separately.
Their text is carried by the generic
`plowshare-message-return/1` payload: stable return `requestId`, authoritative
`sourceEvent`, complete result `body` and `finalReply: true`. The native binding
determines the recipient. A result publication acknowledgement confirms broker
storage, not native execution, agent completion or physical verification. Native
body limits apply; oversized evidence is explicitly refused rather than truncated.

## Durable processing and recovery

The existing journal's single-owner lock covers the new typed request, cursor and
return records. Before advancing a request cursor, the runtime durably captures
every selected event, including rejected-event evidence. A matching repeated event
recovers that record; changed source coordinates or payload are conflicts.

Execution intent is durable before a read or action reaches Home Assistant.
Interrupted execution remains `UNKNOWN` and is never repeated automatically.
Result intent, stable publication UUID, exact text and authenticated source correlation are durable
before SDK publication. After an uncertain publication, recovery reads the exact
retained event and validates all source and body coordinates. Missing, expired or
mismatched evidence remains unresolved; it never authorizes a new action or result
publication. Binding failures stop that binding's intake while other independently
configured bindings continue bounded progress.

Existing completed outgoing journal entries remain inspectable historical
evidence. Any unfinished outgoing entry fences startup before adapter intake or
network effects. It is not converted to a Relay request, marked complete, dropped
or replayed. Operators must inspect and reconcile the original evidence explicitly.
The preflight and inspection commands remain available without opening transports.

Both topic incarnations are checked before external execution and result
publication. The current public `relay.publish` contract has no conditional
generation argument, so an SDK consumer cannot atomically exclude topic
replacement between that check and append. The native return receiver separately
checks its retained return generation and cannot deliver to a replacement binding.
The runtime must stop on detected incarnation change and must not infer native
acceptance from an append acknowledgement. Strict prevention of the broker append
race would require a separately authorized general public capability; it is not
implemented with a read-then-publish claim of atomicity.

## Acceptance evidence

The implementation must retain the existing script, threshold, missing-evidence,
alias restriction, action parameter, uncertain-delivery and feedback assertions.
Focused consumer tests must additionally prove:

- Exact project, publisher, topic generation and immutable source correlation;
  rejected spoofed publishers and body-selected destinations.
- Durable capture before cursor movement, restart after capture and interruption
  after external execution without repeating a service call.
- Exact result reconciliation after a lost SDK reply, and explicit unresolved
  state for absence, retention loss or conflicting retained evidence.
- One deferred binding does not starve another; bounded journals stop intake
  without dropping transitions or truncating results.
- Historical completed entries remain readable and every unfinished legacy state
  fences effects without requiring obsolete outgoing SDK operations.
- The HA example agent uses `relay_publish` with explicit configured topics and
  receives its retained native correlated result. Real harness coverage preserves
  missing/unavailable evidence and refused actions through public SDK boundaries.

The central validation owner runs formatting and the selected runtime, HA and
server integration tests after all Java editors pause. Source review alone does
not establish the live adapter loop.

## Deferred fresh orchestration reads

The user explicitly deferred this compatibility work during remediation. Native
execution exports currently accept exact TURN/MESSAGE sources. Event conductors
reuse the authorized reading already supplied by the runtime; a manually started
orchestration cannot use this initial path to fetch a new Home Assistant reading
while running. It must report missing evidence. Direct agent/message questions
retain fresh reads. A future SDK Relay tool-provider migration can address the
orchestration case without widening core source kinds; it is not implemented or
claimed as current equivalence. See deferred T22B in the task graph.
