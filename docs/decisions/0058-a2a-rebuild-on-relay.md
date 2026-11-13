# Rebuild A2A as a public-SDK consumer of Relay

Status: accepted replacement direction for Slice 5 of
[Finishing Relay](../relay-finishing.md), recorded during Slice 0.
[0065](0065-a2a-relay-adapter-contracts-and-capability-gaps.md) now records the
SDK-only outbound implementation and the reviewed public capability gaps blocking
the inbound/core cutover. The full rewrite and core removal have not shipped.

Supersedes [0008](0008-relay-external-work-and-a2a-mapping.md). Its EW01–EW08 plan
is retired; its observed behavior, identity mappings, crash-window analysis and
test requirements are inputs to a fresh requirements review, not a baseline
that requires preserving adapter-specific core APIs.

## Decision and boundary

A2A remains a platform feature. Its implementation must be rewritten because
adapter responsibilities entered Plowshare core. The rewrite corrects that
boundary; retiring 0008's plan does not retire the feature.

Follow [0057](0057-board-forum-and-relay-messaging-substrate.md): Relay is the
delivery substrate, messaging dispatches from agent topics, and the board stays
an independent forum. A2A is rebuilt around that platform direction rather than
moving today's outgoing queue unchanged behind a new transport.

Adapters are external public-SDK consumers of Relay ports. Several adapters may
have separate topic sets behind one API. Inbound work reaches the receiving
agent topic through the messaging payload contract; replies return on the
adapter's topic. Protocol translation, peer credentials and external connections
belong to adapters. The outbound path now consumes public SDK ports. Inbound agent admission remains
a target requirement: 0065 verifies that SDK-published TEXT cannot enter trusted
agent inbox admission.

An integration that needs core changes is a failed integration task under
[0001](0001-integration-boundary.md). A missing public capability must be named
and assessed against SDK-only alternatives. Any general platform extension needs
its own authorized platform task and design decision covering authority,
lifecycle, compatibility and verification. Adapter tests use the public SDK;
neither production code nor adapter acceptance tests reach into server internals.

## Requirements retained for the review

Use the existing behavior as evidence for these requirements, without assuming
the current implementation or tables must survive:

- Context continuity across tasks, idempotent receipt by message identity, and
  isolation by authenticated client alias and peer.
- Task read/cancel/state mapping, stable text artifacts, Agent Cards from current
  grants, and explicit command-extension behavior.
- Approvals remain operator decisions. External identity and a message body
  never acquire the sender's internal tools or authority.
- Commit dispatch intent before external effects. After uncertain delivery,
  reconcile retained identity and status without repeating an external mutation.

The fresh design must enumerate which existing requirements the public SDK can
express and report each gap before implementation. This record chooses no new
core API or compatibility shim for those gaps.

## Dependencies and affected consumers

Slice 5 depends on Slices 1–3. Current `incoming.*`, `outgoing.*`, the external
instance lifetime/context/task tables and the outgoing package are candidates
for removal, not removals authorized by this documentation change. The Slice 5 review confirms that current scheduled agent work already enters
the native turn runtime through `events/AgentRunner`; it does not depend on
`incoming.*`.

Keep and adapt Home Assistant and the shared integration runtime through the
public SDK; move the runtime's current `OutgoingClient` use to the rewritten
outbound path. Discord's `incoming.*` use and Network Privacy Watch's outgoing
peer use must be accounted for in the same cutover plan. Whether each migration
ships within Slice 5 or holds removal until its own migration is still the open
question recorded in Finishing Relay. Do not remove a surface with an unmigrated
consumer or treat this direction as proof of a safe cutover.

Acceptance requires public-SDK adapter tests, crash-window and no-replay checks,
and an independent A2A client. The separate Relay outbound entry point can run through its existing SDK grants.
Current incoming/outgoing consumers remain available until replacement platform
capabilities are separately authorized, implemented and verified.
