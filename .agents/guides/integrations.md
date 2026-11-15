# Integrations and external Applications

Read this for adapters under `integrations/` and external capability services
under `extensions/`. Use the specific adapter README for deployment/protocol details.

## Mental model

An integration consumes public Plowshare SDK capabilities. Plowshare owns durable
platform work; the adapter owns its external protocol, credentials and bounded
local recovery state. External adapters belong under `integrations/`; extension
services such as search providers belong under `extensions/`.
Extension services implement their supported provider/counting protocol; they
need not be SDK clients. Any calls they make into Plowshare still use public
capabilities rather than internal services or database access.

## Start in code

- [Integration map](../../integrations/README.md) names modules, public entry
  points and their actual Gradle identities.
- [Shared runtime](../../integrations/runtime) owns common integration lifecycle
  and local journals. [A2A](../../integrations/a2a),
  [Home Assistant](../../integrations/home-assistant), [MCP](../../integrations/mcp)
  and [Discord](../../integrations/discord) own their external protocol adapters.
- [Network Privacy Watch](../../integrations/network-privacy) and
  [Excalibur](../../integrations/agile) are separate SDK-consuming Applications.
- [Integration boundary decision](../../docs/decisions/0001-integration-boundary.md)
  defines the platform boundary and the process for a missing capability.

## Rules

- Stay on the public SDK and supported operations. Do not import server
  internals, access its database or introduce a parallel durable platform runtime.
- An adapter gap is not permission to modify core APIs, tools, contracts or SQL.
  Report the missing public capability and continue independent adapter work.
  A core extension needs separately authorized platform scope and a design decision.
- Configure and validate server origin, bind address, advertised URL, credential
  source and journal location explicitly. Never infer that the server shares a
  host/container, or silently connect to a local fallback.
- Keep external authentication, native service identity, project grants and
  protocol permissions distinct. Manifest configuration is not a grant by itself.
- Persist operation intent before transmission where the adapter's recovery
  contract requires it. Preserve exact request identities and inspect receipts
  after uncertainty. Transport recovery must not repeat collection or model work.
- Bound queues, journals and retention. Preserve cancellation, external provenance
  and lifecycle ownership rather than turning an external request into ambient
  SYSTEM or administrator authority.
- Keep routine root-Application output in its project inbox, including older
  manifests with no inbox setting. Use supported
  attention publication for actionable output; adapters do not choose recipients
  by copying an installer account or inventing an external RBAC implementation.

## Bounded work

Apply the [bounded-work rule](../../docs/coding-standards.md#bound-active-work-in-every-subsystem):
short control/read/acceptance requests have a configurable two-second default
ceiling; external actions, transfers and scripts need their own explicit finite
budgets. Queueing, connection and execution consume remaining operation time.
Bound each poll/reconciliation pass and shutdown step of a long-lived adapter.
Cancel at the adapter/protocol owner where supported; a timeout is not proof that
a physical external action failed. Retain its original journaled intent and
uncertainty instead of replaying an action or collection under a new identity.

Current [integration runtime](../../integrations/runtime/README.md) and adapter
timeouts are operation-specific, not universal two-second cancellation. Inspect
the actual SDK/external-client/script owner and fencing before changing them.
Late completions must retain valid authority, generation and publication identity;
release physical work accounting only when it actually settles.

## Legacy traps

An old adapter using internal services is migration work, not a pattern to copy.
Wrapping internal access in an SDK method does not make it a public contract.
Replacing uncertain requests with new UUIDs creates duplicate work rather than recovery.

## Checks

Use the adapter's README and existing tests with fake SDK/external transports.
Exercise malformed input, scoped denial, restart, cancellation and uncertain
delivery. Check that no credentials/provider payloads enter errors or alerts.
An authenticated protocol round trip is separate evidence from a healthy container.
Core PostgreSQL tests are not an integration confidence check.
