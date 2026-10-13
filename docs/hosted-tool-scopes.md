# Durable server-hosted provider scopes

Hosted scopes extend the existing tool-provider registry, scope policy and Relay
invocation ledger. A retained owner assignment survives client disconnects and
server restarts. Tools are available only while its configured executor has a live
registration, a fresh catalogue and a current credential grant. This foundation
supports external HTTP provider adapters; it does not start an MCP transport or
allocate spending pools.

Use the [client management controls](provider-management-clients.md) to inspect
and configure owner assignments and credentials through these existing operations.

## Three identities

The **owner** is the authenticated user or service principal saving the binding.
Their other Plowshare clients can use it with their own eligible agents. Another
user cannot adopt the binding or obtain its schemas through the scope operations.

The **credential subject** defaults to the owner. A server administrator may
explicitly select an exact service-token principal through the existing provider
credential policy. Membership, enabled accounts and connection revisions remain
mandatory. A token UUID is an identity, not permission to release its grant.

The **executor** is an operator-approved external worker principal. Configure
`plowshare.providers.hosting.executors` with the exact `@service/<token UUID>`
identities. An empty set disables hosting. The worker authenticates its WebSocket
with that service token; it receives neither project membership nor access to the
owner's Personal workspace. Trusted workers receive an upstream header for an
explicit discovery or execution purpose. Operators must trust these workers with
that material; this API cannot constrain what a worker does after receiving it.
Provider encryption keys, HTTPS endpoint allowlists and OAuth configuration are
still governed by [provider credentials](provider-credentials.md).

## Owner configuration

`tool.scope.host.save` accepts `project`, `binding`, `requestId`,
`expectedGeneration` and `definition`. Use fresh canonical UUIDs for a new binding
and mutation, and explicit `expectedGeneration: null` for creation. The definition
contains `scope`, logical `provider`, model-tool `prefix`, `grants`, `agents`,
`leaseSeconds` (1–300), ordinary `credentialProject`, connection UUID,
explicit nullable `subject`, and configured `executor` principal.

The execution project may be the owner's Personal project. Credentials must remain
in an ordinary configuration project where the owner and selected subject have the
required access. Agents must resolve on the server with `dynamic: true`. Application
bindings additionally require the existing manifest execution identity, matching
scope/provider grants and explicit agent assignments. Applications do not inherit
interactive policy.

`tool.scope.host.list` and `.status` return retained assignments, including disabled
and deleted records. `.change` requires the current generation, a fresh request
UUID and `ENABLE`, `DISABLE` or `DELETE`. Save/change advances generation once,
withdraws the worker fence and creates an isolated `host-…` Relay route. The prefix
and scope cannot overlap the owner's existing assignments, local connections,
built-ins or Application providers. Deleted UUIDs are permanent tombstones.

Save/change returns a redacted receipt. After uncertain delivery, inspect `.status`
and `.receipt` using the original request UUID. Never automatically replay a
mutation. Project deletion withdraws bindings and workers atomically and prevents a
new project with the same name from adopting old UUIDs.

## Worker lifecycle

`tool.scope.worker.register` accepts binding UUID, fresh request UUID and expected
binding generation. It returns the complete binding and registration fence. Only
one live worker owns a binding. Registration is bound to the authenticated account,
session, socket and server incarnation, with a renewable deadline. A lost reply can
be inspected using `.status` on the same socket; do not repeat registration blindly.
`.renew` extends the same live fence. Expired or disconnected registrations cannot
be renewed. Restart or reconnect requires an explicit fresh registration.

Publish a catalogue **after registration** through `.publish`. A retained Relay
position fences old catalogues even when timestamps match. Catalogue publication
must use the binding's exact `tool.<route>.catalog` topic, executor provenance,
permitted model-tool prefix, and an independent root. Existing bounded nested tool
schemas are unchanged. Expired catalogues, withdrawn grants, inactive principals,
changed assignments or missing workers remove availability. A renewed worker lease
does not renew its catalogue.

`.consume` and `.ack` wrap the existing Relay request/batch DTOs, with complete
nested fields and explicit nulls. They allow only the current binding's declared
request topics and `tool-provider` group. They do not authorize general Relay logs,
foreign topics or Personal/project reads.

## Exact credential release and recovery

`.credential` accepts binding and registration UUIDs, `purpose`, and explicit nullable
invocation UUID. `DISCOVERY` requires `invocation: null` and current owner, binding,
worker and grant authority. `EXECUTION` requires the exact unfinished retained
invocation. Admission stores binding generation, registration, owner, executor,
agent, connection revision, credential subject and grant generation on the owning
Relay intent. Release locks binding, connection, grant and invocation in that order,
then rechecks live authority, the current tool contract and deadline. Rotation,
revocation, a completed call or stale selection refuses release. It never refreshes
or sends an upstream request. The reply contains only the configured API-key header
or Bearer access header; refresh tokens, client secrets and PKCE verifiers stay sealed.
Do not log or persist the header value.

A worker's original journalled result can be published after a binding is disabled
or reconfigured. The result must match its original retained invocation, executor,
registration, topic, result UUID, correlation and causal parent. Changing current
configuration does not reinterpret the original call. Operator executor removal or
account deactivation still refuses worker operations.

`.read` accepts the original binding, registration and invocation UUIDs. It delegates
to the existing invocation reconciliation and returns `verified`, state and text.
Only `verified: true` proves an original result can settle a provider journal. An
absent or unverifiable publication stays uncertain; it never starts another effect.
Original owner/project read authority still applies. Reconciliation remains possible
after catalogue loss or credential withdrawal, without exposing another invocation.

## SDK use and verification

All thirteen operations are typed in Java, TypeScript, Node, Python, Go and .NET.
Java exposes `HostedToolScopeClient`; other SDKs use their existing typed request
facades and generated operation names. TypeScript/Node's existing `ToolProvider.create`
accepts the registration as its final optional argument. Pass its returned route as
the provider binding. It uses worker ports, retains the original worker fence in each
journal receipt and uses `.read` for uncertain result reconciliation. Manage worker
and catalogue renewal explicitly. Acquire execution credentials from an admitted
call's invocation UUID inside the external handler; never select a subject from
untrusted tool arguments.

Mocked tests cover owner and Personal fences, worker competition/restart, exact grant
selection, revocation, transport decoding and redacted secret release. Shared SDK
fixtures cover nested invalid inputs and foreign replies. The focused tagged
`HostedScopePersistenceTest` checks migration, CAS, receipt rollback, concurrent
registration, deletion tombstones and atomic hosted-selection/Relay publication.
`JdbcRelayToolRepositoryTest` preserves ordinary retained invocation behavior. These
require explicit `-PfullDb`; they do not prove a live MCP session or upstream OAuth
integration.
