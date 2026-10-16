# ADR 0019: MCP providers with explicit credential and executor ownership

Status: accepted. The reusable [outbound HTTP adapter](../../integrations/mcp/README.md)
implements the existing provider mechanism with separate connection and credential
contexts. [Native instance hosting](../native-mcp-hosting.md) is implemented
separately; service-account accounting pools remain proposed. [ADR 0025](0025-service-account-attribution-and-spending-pools.md)
proposes the attribution and general spending-pool policy; it implements no monetary
enforcement. The source audit below records the original design baseline.
Audited against main `e0886dd0b6472849dee1f4408a158f2556b02816` on 12 October 2026.

## Context and current source

The shared schema foundation is merged in [ADR 0018](0018-shared-tool-schemas.md).
MCP is another external SDK provider. The current execution path is:

| Responsibility | Current owner |
| --- | --- |
| Declarations, handlers and catalogue renewal | Network privacy [native tools](../../integrations/network-privacy/src/plowshare_privacy/native_tools.py) and [CLI composition](../../integrations/network-privacy/src/plowshare_privacy/cli.py); public SDK `ToolDeclaration`, `RegisteredTool`, `ToolProvider` |
| Offering and per-call admission | [`ApplicationToolRegistry`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ApplicationToolRegistry.java), including current whole-definition equality, grants and catalogue leases |
| Application identity and assignments | [`ApplicationToolScopes`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationToolScopes.java) and [owned identity lifecycle](0017-application-owned-service-provisioning.md) |
| Instance attachment | [`SessionToolScopes`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/SessionToolScopes.java), authenticated event socket and session |
| Durable invocation and result recovery | [`ProjectRelayToolInvocations`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/tools/ProjectRelayToolInvocations.java), `RelayToolRepository`, existing Relay records |
| Exclusive execution intent and publication recovery | [TypeScript provider](../../sdk/typescript/src/operations/relay-tools.ts), [Python provider](../../sdk/python/src/plowshare/tools.py) and native SDK equivalents |

This already supplies tool visibility, agent opt-in, permissions, schema pinning,
causal identity, invocation receipts and unknown-effect recovery. It needs no new
MCP registry, job runtime or effect ledger. Remote metadata confers no authority.

The original design identified these three public capability gaps; server-held
credentials and delegated hosting now implement the first two. The general acting
grant remains separate proposed platform work:

1. There is no server-held external credential lifecycle or invocation-bound
   credential access operation. Application token issuance authenticates to
   Plowshare; it does not store upstream grants.
2. `ToolScopeConnections` attachments are bounded in-memory socket/session state.
   They do not follow a human across clients or survive server restart.
3. Application calls require caller/provider execution identity equality. Personal
   access fences both identities to the owner. A separate executor cannot bypass
   these checks by impersonation or broad project membership.

## Decision

Build an SDK-only adapter under `integrations/`, using the public Plowshare Node
SDK and official TypeScript MCP client/core **2.3.1** as the initial upstream pin.
One implementation serves configured server-side Node hosts and native Node-based
clients. Other Plowshare SDKs expose equivalent platform management operations;
they need not grow separate MCP transports. Production locks/package verification
ship with the adapter task. The outward-facing `plowshare-mcp` remains independent.

Server hosting accepts HTTP only, in a managed adapter process outside the Java
runtime. That process may run on another configured host/container. CLI/TUI/desktop
instances support HTTP or STDIO; the browser manages public server capabilities
and never launches a process. Deployment origins, endpoint, executor registration,
state paths and native executable/environment are explicit configuration.

Select protocol **2026-07-28** explicitly, with **2025-11-25** as an explicit
compatibility profile. Discovery/session/cancellation behavior differs between
these revisions. Discover before accepting work; disable automatic era/transport
switching, auth retry, stream reconnection and multi-round-trip continuation on
tool execution. A reconnect, SDK retry default, read-only hint or MCP request ID
cannot authorize repeating an unknown invocation. See the
[bounded profiles and probe](../mcp-provider-profile.md).

Use the shared typed schema profile and immutable recursive arguments. Unsupported
tools get visible diagnostics, including `x-mcp-header` declarations that the
shared profile cannot carry yet. Never strip constraints or claim complete MCP
conformance. Contract-derived names pin upstream names, protocol and input/output
semantics. Changed contracts change the offered name; old invocations retain their
mapping and journal generation. The existing input fingerprint and whole-definition
registry check still apply. Final upstream tool errors remain received results;
loss or invalid output after dispatch is UNKNOWN.

Separately deliver the smallest general platform contracts under
[ADR 0001](0001-integration-boundary.md):

- Typed external connection and credential services/repositories with authenticated
  owner status/mutations, encrypted secrets, explicit OAuth HTTP callbacks,
  serialized refresh and uncertain-rotation recovery. Typed SDK management
  operations ship with the server contract.
- A durable hosting mode in existing provider scopes retaining owner execution
  principal, project, connection, credential subject, logical provider, prefix,
  agent/tool assignments, configured executor and monotonic generation. Keep
  existing instance attachments ephemeral. Require a live fenced worker registration
  and fresh catalogue lease for availability; prevent competing executors on a route.
- Narrow delegated authority in the existing provider/Relay path, including
  discovery grants, exact-invocation credential access and result provenance.
  Personal tools remain owned by their human. The executor receives only its
  provider route, never Personal membership, general topic access or other subjects.

Discovery access checks the live binding, subject and executor without an invocation.
Execution access additionally loads the exact retained invocation and matches caller,
project, route, contract, credential generation and deadline. Recheck current
account/token status, membership, agent opt-in and binding authority at offering,
admission and credential release. Worker-supplied owner/invocation IDs are lookup
keys, not grants. A machine login never authorizes access to all users' credentials.

Retain the binding, credential subject and generation reference with the original
owning invocation intent at admission, atomically with that intent. Later connection
changes cannot reinterpret the retained request's credential selection. Extend
that owning record as needed; do not create a second MCP invocation ledger.

Only the configured trusted executor receives the minimum upstream secret needed
for dispatch through an explicit redacted secret-bearing SDK response. Ordinary
metadata/events expose status, not secrets. A bearer token cannot cryptographically
limit a trusted worker to one call: worker isolation and endpoint/scope restrictions
are part of the deployment trust model. Do not promise stronger upstream enforcement.

Define typed connection/status, credential authorization, redacted binding/availability
and discovery diagnostic DTOs with explicit lifecycle mutations. Owner identity
derives from authentication; service-subject selection requires separate authority.
Use expected revisions and retained mutation UUIDs/receipts. Final operation names
and generated DTOs belong to the management contract task; this decision does not
register server operations.

Credential records distinguish connection, owner, issuer/resource and scopes.
Encrypt with externally configured rotatable keys. Validate protected-resource
and issuer discovery, PKCE, single-use owner/connection/issuer/callback-bound state,
issuer response and resource/audience. Initial policy uses configured client
registration; other registration mechanisms need explicit validated policy.
OAuth redirects/callbacks are an HTTP boundary; supported management uses WebSockets.
Plowshare login is not an upstream grant and its bearer token is never forwarded.

Refresh uses a serialized credential generation without keeping a database
transaction open across network IO. Persist exchange intent, then compare-and-set
returned rotation. A lost response after rotating refresh requires truthful uncertain
status/reauthorization; never repeat the old refresh token. Revocation blocks new
dispatch and secret release. Retained receipt reads and journalled result reconciliation
must preserve original authorized provenance without starting another execution.

Keep initiating human, execution principal, executor, upstream subject and accounting
owner distinct. Bind upstream service credential owners explicitly to the existing
`@service/<token UUID>` principal. Application-owned identities support rotation
without changing that UUID; replacement does change identity. Human-owned work
using an authorized upstream service credential keeps its model-usage attribution.
Separate enforced spending pools remain a general accounting decision covering
admission/reservations/concurrency/delegation/release. Preserve booked history and
unknown external costs; do not create another MCP billing ledger.

## Alternatives, compatibility and consequences

Current SDK-only local hosting and same-principal Application providers remain
useful. They cannot supply server-held cross-client human credential ownership.
Keeping those tokens in a sidecar moves secret ownership out of the server.
Impersonation and adding a worker to Personal space widen authority. Embedding
MCP transport into core duplicates working provider behavior. These alternatives
do not safely supply the requested capability through current public contracts.

This design identifies platform gaps; it does not authorize adapter tasks to patch
core. Deliver the separately scoped general capability first, then consume public
SDK operations. No runtime API, migration or production dependency changes accompany
this decision. Broader schema annotations need their own shared-contract decision.

Preserve scalar/v1 receipts, ephemeral scope meaning, grants, accounting and journals.
Future persistence migrations are additive. Older clients refuse unsupported
management/profile versions. Rollback disables/withdraws new bindings, preserves
ciphertext/key versions and journals, and reconciles with a supporting executor.
Never revive revoked generations or resubmit unknown effects. Job cancellation
currently stops server waiting after submission; immediate upstream propagation
needs an exact-invocation provider advisory. Deadlines, cancellation and process
shutdown cannot prove an external effect was rolled back.

## Verification and remaining gates

Source inspection traced the provider path and concrete authority gaps above.
The independent wire probe passed the pinned official client over HTTP/STDIO for
both revisions, plus one-send behavior after response loss, static-token 401 and
unsupported multi-round-trip response. This is design evidence, not an integrated
provider or deployed OAuth test. No upstream account or Plowshare server was used.

Implementation requires cross-user/Personal fences, spoofed executor IDs, stale
generations, simultaneous refresh, lost rotation replies, output-contract changes,
restart and result publication recovery fixtures. Mock interfaces by default;
opt into focused PostgreSQL tests only for introduced persistence invariants.
Real org authorization, two-client isolation, service-bound Applications, SSE
cancellation and native process/distribution behavior remain acceptance gates.

See the [implementation plan](../mcp-client-design.md),
[official SDK release](https://github.com/modelcontextprotocol/typescript-sdk/releases/tag/v2.3.1),
[versioning](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning),
[tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) and
[authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
for the audited contracts.
