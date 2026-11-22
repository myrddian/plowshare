# Named Home Assistant read tools

`HomeAssistantReadTools` supplies four SDK declarations for one configured HA
resource binding. `HomeAssistantToolProvider` hosts them through the existing
public Java `RelayTools.Provider`; Plowshare continues to own agent execution,
invocations, scope permissions and durable Relay messages. This is a Java
composition API. The [outbound operator host](PROVIDER-HOST.md) packages that
composition behind explicit `--provider` mode; legacy manual-alias arguments remain
independent and compatible.

| Suffix after the configured prefix | Input | Result |
| --- | --- | --- |
| `search` | Optional text/domain/area filters, page limit and exact cursor | Permitted resource summaries and a source revision |
| `describe` | Alias, source revision, optional attribute offset | One permitted description and up to 16 attribute names |
| `read` | Source revision, up to 20 aliases and optional attribute names | One fresh batch; only requested, already-permitted primitive attributes |
| `list_actions` | Alias, revision, optional page offset/limit | Configured action summaries; no execution |

The prefix is mandatory, lower-case and ends in `_`; configure separate
non-overlapping prefixes for multiple bindings. Search and action pages contain
at most 20 results. The source revision includes the binding and its opaque
credential generation, policy, connection epoch, metadata and state versions.
Versions travel as canonical decimal strings, preserving counters outside
JavaScript's exact integer range. A foreign or stale revision is refused; it
does not trigger an implicit fresh read or broaden an entity selection.

SDK tool text has a 16,384-unit ceiling and its complete result envelope has a
32,768-unit ceiling. This adapter's JSON result profile reserves envelope space
and accepts at most 15,000 UTF-16 units. An oversized observation returns an
explicit rejection asking for a smaller page/alias/attribute selection. It is
never truncated, silently summarized or automatically fetched again. These are
protocol/data bounds, separate from model prose word targets.

The resource policy remains trusted host configuration. The model cannot supply
new HA entity IDs, credentials, attribute permissions or an action policy. An
explicit host resource policy may differ from the old manual alias attribute
selection, as described in [resource contracts](RESOURCE-CONTRACTS.md).
Attribute projection in a tool result does not grant access: the underlying
reader first applies that host ceiling to the single native HA state batch.

## Composition and grants

Supply explicit SDK/HA origins, credentials, project, authenticated provider
principal, prefix, policy revision and private journal directory. The host borrows
an already authenticated SDK connection; the caller owns its lifetime and the
configured HA adapter. The public composition uses existing capabilities:

```java
var adapter = new HomeAssistantAdapter(haSettings, haToken, requestTimeout);
adapter.start(observation -> {});
var source = adapter.resourceSource(bindingName);
var policy = HomeAssistantDiscoveryPolicy.fromSettings(source.binding(), policyRevision, haSettings);
var resources = new HomeAssistantResourceCatalog(source, policy, metadataMaxAge);
var settings = new HomeAssistantToolProvider.Configuration(
    new RelayTools.Binding(project, providerName, providerPrincipal), toolPrefix, leaseSeconds,
    requestTimeout, cleanupTimeout);
var handlers = new HomeAssistantReadTools(
    source.binding(), toolPrefix, resources, resources, requestTimeout);
var owner = HomeAssistantToolProvider.ownerFingerprint(
    serverOrigin, haSettings.endpoint(), haToken, policyRevision, settings.binding());
var provider = HomeAssistantToolProvider.open(settings, sdk, handlers, journalDirectory, owner);
provider.renewCatalogue();
provider.poll();
```

The variables above are validated application configuration, not built-in local
defaults. `requestTimeout` and `cleanupTimeout` default to two seconds through
the three-argument configuration constructor and may be explicitly configured
up to 30 seconds. Pass the same request timeout to the read declarations.
Connection startup/HA maintenance retain their own explicit budgets. A supervisor
owns bounded repeated polls and calls the existing adapter maintenance operation
for reconnect; this host neither reconnects nor replays failed mutations.

For an Application, serialize `HomeAssistantToolProvider.installation(settings)`
into its private `server/tools.json`. It declares `bindings: []` and one provider
with account/prefix/lease. Keep real account values out of tracked examples.
Separately assign an Application tool scope to the intended `dynamic: true`
agent, granting exactly the four configured read tool names. Provider discovery
and a successful catalogue publication do not create grants. An exact read scope
does not automatically grant future action tools. See
[provider scopes](../../docs/tool-scopes.md) for server-owned Application and
interactive connection policy.

Interactive SDK clients first use the public `ToolScopeClient` with an explicit
agent list/grants/prefix and retain the reply's fresh routing provider/account.
Use that reply as the `RelayTools.Binding` on the same SDK connection. A timed-out
scope-connect mutation must be inspected using its owning public scope APIs;
the HA host does not retry it or invent another principal.

Permission/agent visibility is the server's responsibility at each invocation.
The SDK again validates broker publisher, binding, exact offered schema and input
values before persisting execution intent or calling these handlers. Read tools
also validate the complete typed revision and frozen schema. This adapter adds no
replacement authorization policy and obtains no server implementation dependency.

## Budgets and physical ownership

One serial physical provider pass owns the configured monotonic budget across
SDK calls, journal operations and resource reads. Public async Relay methods use
the remaining deadline, explicit `NON_BLOCKING` dispatch and a caller cancellation
signal. There is no SDK capacity backoff/replay. At a fresh invocation, the host
derives an earlier `OperationBudget.capped` from its admitted deadline and carries
that same instance through intent, acknowledgement, resource work and result
publication. The next invocation still shares the original pass ceiling.

The public SDK provider has no pre-ack deadline hook. The host therefore decodes
the bounded request once at the transport boundary to select this earlier budget;
the SDK retains its independent full authority/schema/identity validation.
Already expired calls still receive bounded rejection/duplicate acknowledgement
without resource execution. Retained recovery uses a new bounded inspection pass,
never a renewed deadline for original external work.

A timed-out/interrupted caller fences its physical owner, schedules cancellation
on one bounded cancellation owner and returns an explicit error. Interruption can
run an NIO interruptor that blocks during physical cleanup; it therefore never
runs on the expired caller or under the admission lock. The pass remains occupied
until both its local SDK/filesystem/resource callback and cancellation settle.
New polls/publications refuse while that slot is occupied. No replacement worker
or journal owner runs around an uncertain effect. SDK cancellation detaches the
local wait; it does not cancel remote work or establish that an SDK-owned network
transmission has ended. The borrowed connection retains its own transport
lifecycle. An uncooperative local callback can remain physically active beyond
the logical deadline, retaining that bounded slot.

Cleanup has a separate finite budget. It rejects new work, fences the current
pass and releases the journal lock only after the physical pass and cancellation
settle. Worker/cancellation joins share that single cleanup deadline. An
incomplete cleanup reports failure and retains ownership; do not install another
host over that journal or close a shared underlying SDK connection as a substitute
for settlement. Reentrant passes and cleanup from the worker itself fail promptly.

## Journal and catalogue lifecycle

The private journal uses a nonblocking exclusive file lock, strict typed snapshots,
atomic replacement, file fsync and directory fsync. Snapshot version 2 retains at
most 32 exceptional/incomplete SDK receipts within 16 MiB, reserving space for
every receipt's maximum result, plus at most 128 per-topic replay fences. It
compacts settled successful/rejected reads only with independent durable broker
acknowledgement and result-publication proof, as described below. UNKNOWN,
unfinished and unproved receipts never expire by age. At exceptional capacity,
new work refuses and retains every remaining fence for explicit investigation.
Symlink journal targets are refused. POSIX permissions are private where supported;
the exclusive lock remains mandatory on other filesystems. Journal opening is a
bounded-size, nonblocking-lock operation owned by configuration startup; the host
cannot guarantee a wall-clock bound for a filesystem whose kernel I/O stalls.
Deploy its private directory on supervised local storage.

The SDK's exact declaration fingerprint and a separate owner fingerprint bind the
journal to its project/provider/account, prefix, lease/budget configuration and
actual HA credential/policy configuration. The private fingerprint includes the
HA credential without storing it; it is never exposed as a resource reference or
metric label. A changed configuration cannot silently adopt old receipts. Retain
the old private owner/configuration and journal for explicit reconciliation before
switching schemas, accounts, credentials or routing bindings.

Receipts retain the SDK's EXECUTING/READY/PUBLISHING/DONE lifecycle. Intent commits
before acknowledgement/handler execution. Restarted EXECUTING work becomes
UNKNOWN and is not executed again. Result publication uncertainty blocks another
poll until `reconcile()` confirms the original immutable result identity and text.
A failed atomic replacement/fsync latches journal uncertainty, so the process
cannot continue from a potentially stale in-memory snapshot; reopen retained state.

For each SDK-validated request, the host first captures its broker topic, cursor
generation and issued event position. These coordinates commit with the retained
intent before acknowledgement. The exact ACK request must match the captured
project/topic/group/consumer/batch/fence. Its successful reply must echo that
batch and its complete issued position, without a retention gap; only then does
the journal record known cursor advancement. DONE separately confirms the exact
deterministic result publication, either from the original reply or read-only
reconciliation. A DONE receipt with a known ACK and a COMPLETED/REJECTED result
may atomically become a replay watermark in the same journal replacement that
removes its full receipt. UNKNOWN remains an exception even with both receipts.

This relies on the existing public [ordered Relay contract](../../docs/relay.md):
within one cursor incarnation, acknowledged batches advance monotonically and
retained equal UUIDs keep their original position. The durable
[tool invocation ledger](../../docs/relay-tools.md#durable-lifecycle) creates a
request only for its first immutable invocation; retirement does not authorize
resubmission at a new broker position. At or below a retired watermark, an incoming
request without its exact exceptional retained receipt is refused before SDK
intent/handler execution. A different/missing cursor generation cannot inherit a
watermark. Missing generation on an older Plowshare server permits no retirement:
such receipts remain bounded and the operator must update that server for sustained
named-provider service. This is independent of the HA version. The host does not
delete/recreate cursor groups or reset their positions. Snapshot version 1 was an
unreleased candidate; this implementation deliberately refuses it instead of
inventing missing delivery/ACK evidence.

Catalogue publication/renewal and withdrawal each get a fresh UUID. The host
intercepts and durably stores the SDK's exact publication (text/time/identity)
before transmission. A confirmed server receipt sets local lease availability
using its broker `publishedAt`. Withdrawal confirms an empty catalogue; expiry
stops polling locally, while the server remains authoritative for its own lease.
Renewal is an explicit caller operation, not an automatic retry.

After a lost catalogue reply, `reconcile()` reads the retained original event,
matching publisher/project/topic/identity/time/payload. A missing record does not
authorize republishing; it remains unresolved. The Java SDK exposes generic
`IOException` for some server refusals, so the host cannot safely infer a definitive
non-submission from error text. Only its structured `TransportException` with
`NOT_SUBMITTED` clears an unsent intent when the same operation budget still
permits committing that evidence. Other or late failures conservatively retain it.
This is a public-capability limitation, not a reason to inspect server internals.

Call `withdrawCatalogue()` explicitly before normal shutdown and confirm/reconcile
its outcome. Closing the host alone does not claim a successful withdrawal;
existing server availability expires under its finite lease. Neither operation
cancels accepted agent jobs or erases pending/uncertain receipts.

Focused fixtures use fake public SDK transports, actual SDK provider execution and
temporary local journals. They establish codec/lifecycle behavior and refusal
before handlers; they do not claim an authenticated live grant/deployment check or
physical HA device behavior. No action execution is added by this read-tool slice.
