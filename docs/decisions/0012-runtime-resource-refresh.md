# Runtime definition refresh and Relay worker enrollment

Status: accepted

## Platform need

Deploying an Application changes registered server resources while the server stays
running. Resume and inspection must resolve in the same account/project as execution.
New Application Relay subscriptions also need a processing identity without an
operator adding every deployment to a server startup list. This is a platform
lifecycle capability shared by all SDK consumers; a collector-specific dispatcher
or REST fallback would duplicate durable processing and permission rules.

## Decision

Conversation resume, projection and cost preview use one typed conversation-definition
contract backed by Callers. Scope comes from the retained conversation home and owner.
WebSocket inspection may use its eligible session; resume uses the explicitly requested
session. Sessionless REST inspection never acquires a client's file-channel resources.
Read authorization remains at the owning Information boundary; resume rechecks current
work/session authority and exported admission before Turn starts work. Delegated
orchestration continuations use current scoped delegate admission, preserving the
existing rule that a granted delegate can be unexported. The eligible retained
orchestration session reaches both definition resolution and the resumed job. A vanished conversation
project refuses instead of becoming a global lookup. Refused Application overrides
mask ordinary same-named globals; an unreadable Application tier refuses execution.

Packaged classpath definitions remain immutable release resources. Operator-owned
global agents/bots and orchestrations are read into immutable content snapshots on
resolution. Changed content replaces the snapshot; scoped caches also compare their
inherited global snapshot. Orchestrations refresh when their agent dependency graph
changes. Malformed global overrides mask packaged definitions; unreadable or ambiguous
global tiers refuse without serving old authority. Startup still validates required
system definitions. Lazy global consumers (including compaction, validators and
information processing) consult the current snapshot before their next admission.
Already admitted runs and procedures keep their existing definition/source pinning;
this does not mutate a running workflow or reload Spring/model-pool configuration.

An Application can explicitly enroll background Relay processing with root
`server/relay-workers.json`:

```json
{"version": 1, "account": "application-service-principal"}
```

Discovery uses registered server project records and the Application resource fence,
never an arbitrary disk scan. The declaration must name an identity with current
CONTRIBUTOR work authority; it grants neither membership nor topic policy authority.
Service tokens use their returned principal. No manager/user identity is inferred.
Existing startup bindings remain supported for other projects. A conflicting startup
identity and Application declaration refuses enrollment rather than picking a winner.

A bounded supervisor reconciles at the existing configuration interval (30 seconds by
default): it starts added bindings, interrupts removed/revoked/unavailable bindings,
and replaces changed identities. At most 32 projects and the existing worker/pass
limits apply. Binding-store failure stops enrolled watchers until authority returns.
Each processing pass still checks current Relay authority and distributed lease fences.
Accepted work, durable offsets, pinned branches, cancellation and uncertain effects
remain owned by Relay; enrollment never resets cursors or replays receiver calls.

## Compatibility and verification

No new SDK operation, transport fallback or schema migration is required. Every
language observes the same existing operations. Successful global authoring answers
a resolved view with the retained compatibility `restartRequired` field false.
Applications without the declaration are not implicitly enrolled. The Network Privacy
Watch setup helper explicitly binds its declaration to the provisioned service principal.

Mocked repository tests cover scoped HTTP/WebSocket resolution, permission revocation,
invalid Application replacement, retained historical system blocks, global content
changes with unchanged timestamps, immutable old snapshots, dependency invalidation,
strict declarations and live worker add/change/remove/failure/recovery. Existing Relay
persistence tests own cursor/lease behavior; SQL and migrations are unchanged, so no
PostgreSQL tests are needed for this change.
