# MCP client design and implementation plan

Status: proposed MCP design for review, audited against public main
`e0886dd0b6472849dee1f4408a158f2556b02816` on 12 October 2026.
This plan covers nested provider schemas, server-held
per-user MCP credentials, service-account bindings, and native client hosting.
It does not describe an implemented MCP client.

MCP consumption uses the existing SDK tool provider mechanism demonstrated by
`integrations/network-privacy`. Plowshare continues to own tool visibility,
authorization, jobs, accounting, invocation receipts and recovery. The MCP
provider owns upstream discovery, authentication and protocol translation.

The shared tool schema and SDK foundation is merged. MCP transport, server
credential storage, service-account connection bindings and client hosting remain
planned. [ADR 0019](decisions/0019-mcp-provider-hosting.md) traces current source,
records the precise platform gaps and defines the ownership decision. The
[bounded MCP profile](mcp-provider-profile.md) pins protocol/SDK revisions, discovery,
results and unsupported capabilities, with an independent reproducible design probe.

## Existing provider mechanism

The reference implementation is
[native_tools.py](../integrations/network-privacy/src/plowshare_privacy/native_tools.py).
It creates SDK `ToolDeclaration` and `RegisteredTool` values. The
[CLI composition](../integrations/network-privacy/src/plowshare_privacy/cli.py)
constructs `ToolProvider` with the SDK's exclusive SQLite tool journal and wraps
it in `CataloguedProvider` for catalogue publication and lease renewal.

The Application's
[manifest](../integrations/network-privacy/network-privacy-watch/plowshare.json)
declares `executionAccount`, `toolScopes` and `toolGrants`. Its
[provider configuration](../integrations/network-privacy/network-privacy-watch/server/tools.json)
declares provider account, prefix and lease, with empty bootstrap bindings.
Agents can deploy before a catalogue exists; tools become available when the
authorized provider publishes them. Interactive hosts use the existing
[`tool.scope.connect/list/disconnect` operations](tool-scopes.md).

The MCP path is:

```text
MCP tools/list -> supported SDK declarations -> existing provider catalogue
agent call -> existing authorization and Relay invocation -> SDK handler
SDK handler -> MCP tools/call -> existing ToolResult and invocation receipt
```

Reuse the live registry, namespaces, scope assignments, catalogue leases,
invocation IDs, authenticated publisher checks, journal and read-only recovery.
Do not add an MCP-specific tool registry, job runtime or effect ledger. Keep
`plowshare-mcp`, which exposes Plowshare outward, separate from this consumer.
The consumer belongs under `integrations/` and uses public SDK contracts.

## Nested schemas

The original friction was the scalar provider contract, rather than Relay.
The schema foundation now shares the model-facing vocabulary with protocol and
SDK providers and carries immutable recursive values through the existing
catalogue/invocation mechanism. See [Shared tool schemas](tool-schemas.md) and
[the platform decision](decisions/0018-shared-tool-schemas.md) for the implemented
bounded profile, unsupported keywords, canonical fingerprints and versioned
compatibility. MCP connection and credential support remains planned work.

An MCP adapter must validate discovered schemas against that profile before
publishing them. Unsupported keywords or dialects produce visible compatibility
diagnostics; constraints cannot be discarded or nested arguments stringified.
The adapter consumes the SDK facade and preserves provider admission, schema
pinning, journal intent and unknown-effect recovery. Broader JSON Schema coverage
requires extending the shared profile and its cross-language fixtures first.

## Hosting and credential ownership

| Location | Upstream transport | Ownership and availability |
| --- | --- | --- |
| Server-hosted MCP provider | Streamable HTTP | Server retains scoped credentials; availability follows the provider host and lease. |
| TUI, desktop GUI or CLI instance | Streamable HTTP and STDIO | That instance owns connection configuration, secrets, subprocesses and presence. |
| Browser console | Server-hosted providers; explicitly connected native hosts where applicable | Browser does not launch STDIO processes. |

Use the explicit `2026-07-28` profile or `2025-11-25` compatibility profile and
configured endpoints. The former uses per-request metadata and discovery without
an initialization/session assumption; the latter retains the legacy handshake.
The initial upstream SDK pin is official TypeScript client/core 2.3.1, separately
from the existing outward adapter. Server hosting
never launches MCP subprocesses. Native hosts use configured executable and
argument vectors, working directory and selected environment credentials, without
shell interpolation. Closing an instance removes its local availability. A CLI
provider needs a persistent hosting mode; a one-shot connection command cannot
keep a provider alive. HTTP refers to
[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http),
with HTTPS for authenticated remote deployment. Legacy HTTP+SSE requires an
explicit separately tested compatibility profile and is outside the first release.

An organization connection separates its shared endpoint configuration from each
user's upstream authorization. Login to Plowshare establishes the caller; it does
not automatically provide an MCP token. The user authorizes the upstream service,
possibly through the organization's existing SSO session. Server-held access and
refresh credentials then follow that user across Plowshare clients. API-key or
provisioned machine authentication is available where the upstream supports it.

Credential records are keyed by connection, owning human or service account,
issuer/resource identity and granted scopes. Server credentials are encrypted
with externally configured key material, support key rotation, and never appear
in catalogues, prompts, ordinary metadata responses, manifests or journals.
Account/project identity is derived from authenticated context. Metadata and
credential mutations use typed authenticated public operations; OAuth redirects
and callbacks are an explicit HTTP boundary. Use PKCE, single-use state bound to
the initiating owner and connection, resource-bound tokens, bounded discovery,
serialized refresh/rotation, revocation and an actionable reauthorization state.
See the [MCP authorization specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
and [ADR 0019](decisions/0019-mcp-provider-hosting.md) for refresh exchange intent,
uncertain rotating-token recovery and the executor trust boundary.

The bound executor gets credential access only for the validated connection and
invocation it is authorized to execute. The execution grant is checked against
the existing server invocation record, caller, project, provider and credential
binding; a worker-supplied account or invocation ID is insufficient. A provider
worker's Plowshare service credential does not authorize access to every user's
upstream secrets. Discovery sessions and schema caches are also scoped by the
upstream credential subject because different users may discover different tools.

Persisting credentials alone does not make server tools follow users today:
interactive scopes are tied to an event socket and session. Extend the existing
provider scope contract with a durable server-hosted binding mode, while keeping
instance-local bindings ephemeral. The durable mode records the owning caller,
authorized executor, project and agent assignments and rechecks them at every
call. It must support an owner's tools across their authenticated client sessions
without widening project membership or another user's access. Personal scope
retains its owner fence; using an unrelated service worker requires an explicit
execution grant, never service-account membership in Personal space.

## Service accounts and accounting

A connection can bind to a service account's upstream credentials. Distinguish
the initiating user, Plowshare execution principal, provider worker principal,
upstream credential subject and accounting owner. Record their relevant identities
in invocation evidence without recording secrets. Users cannot select a service
credential by sending an arbitrary account handle in tool arguments.

Applications use their existing execution identity and scope-to-agent grants,
as network privacy does. Durable server bindings reuse that authority rather than
installing tools globally. The existing Application check requires caller and
provider execution identities to match; any executor separation must be an
explicit extension to that check, with compatibility coverage. Account disablement,
token revocation, changed membership and withdrawn assignments refuse later calls.

Plowshare service tokens execute as `@service/<token UUID>`; rotate a token to
preserve its identity and retained ownership. A new token has a different execution
identity. A stable service-account credential owner and token execution principal
therefore need an explicit binding, not a guessed identity substitution.

Work running under a service execution identity retains its existing accounting
attribution. Selecting an upstream service credential for a human-owned call does
not relabel the human's model usage. Separate enforced spending pools, if required,
are a general accounting policy task: define admission, reservations, concurrency,
delegation and release of unused allowance. MCP does not create another billing
ledger, and unknown upstream costs remain unknown. Model capacity pools are
distinct from accounting ownership.

## Platform scope and implementation sequence

Recursive provider values are now shared platform contracts. Existing SDK-only
alternatives support nested tools and instance-local hosting, but cannot provide
server-owned credentials and durable availability bindings across client sessions.
Credential ownership and delegated hosting are general platform capabilities;
plan these as separate platform changes under
[ADR 0001](decisions/0001-integration-boundary.md), followed by the SDK-only MCP
consumer. This request authorizes the design and implementation plan; runtime
changes remain a subsequent implementation task.

1. **Shared tool schema and SDK extension: delivered.** The standalone foundation
   includes server integration, all SDK façades and existing provider compatibility.
   MCP and other integrations consume it. Its acceptance contract is below.
2. **Review the MCP-specific decision and profile.** Resolve the precise credential,
   delegated executor and accounting policy boundaries in ADR 0019. The probe is
   design evidence; it does not assert shipped MCP support.
3. **Add server credential ownership and durable binding support.** Define narrow
   credential service/repository contracts and typed public operations. Evolve
   existing provider scopes to distinguish durable server hosting from local
   presence. Add only required additive persistence migrations and retain existing
   invocation records as execution authority. Document secret/key rollback and
   revoked/uncertain refresh recovery without replaying credential mutations.
4. **Build the MCP provider.** Implement profile-specific discovery/initialization,
   bounded pagination, catalogue renewal/withdrawal, collision-safe name mapping and `tools/call`
   handlers under `integrations/`. Pin original upstream names, schemas and
   credential subjects. Server hosting accepts HTTP only; native hosting adds
   STDIO. Reuse SDK journals and exact invocation identities. Reconnecting or
   refreshing metadata never resubmits an uncertain call.
5. **Expose connection management in clients.** Provide server-held user
   authorization, authorized service-account selection, agent assignments,
   compatibility diagnostics and instance-local hosting in CLI/TUI/desktop.
   Browser management operates through typed server capabilities. Reuse shared
   client operations and show which host executes a tool.
6. **Verify interoperability and lifecycle.** Exercise an organization endpoint
   with two users, a service-bound Application, and native HTTP/STDIO hosts.
   Validate cross-client use, instance isolation, revocation, expiry, restart,
   cancellation, schema changes and lost responses against retained receipts.

## Delivered schema foundation and acceptance contract

Establish one canonical schema vocabulary at the shared protocol/SDK boundary,
starting from the existing typed model-facing representation. Move or extract
the reusable contract into its proper shared owner and adapt model dispatch and
provider declarations to consume it. Server-specific composition and execution
stay in the server; SDKs must not import server implementations. Language-specific
types and codecs implement the same semantics and shared compatibility fixtures.
Avoid independently evolving model schemas and provider schemas.

Deliver the foundation in these steps:

1. Define the supported schema vocabulary, immutable recursive argument values,
   schema validation and argument validation contracts. Document unsupported
   keywords and limits, including absent versus null and portable numbers.
2. Integrate that shared vocabulary with existing model tool declarations,
   dynamic provider catalogues, offered-schema fingerprints and invocation codecs.
   Preserve registry, permission, Relay, receipt and recovery ownership.
3. Update Java, TypeScript/Node, Python, Go and .NET tool declarations and handlers
   together. Regenerate derived contracts through the owning scripts, retain the
   scalar convenience API, and add compatible envelope/version handling.
4. Verify the general capability through a small SDK provider fixture, without an
   MCP dependency, then run existing network-privacy provider coverage and the
   required platform/SDK checks.

Acceptance requires a nested schema to survive declaration, catalogue publication,
model offering, invocation and SDK handler decoding without flattening or changing
its constraints. Valid nested values reach the handler unchanged. Malformed
nested values, unsupported schemas and excessive depth/size fail before an
external effect. Shared fixtures must establish consistent behavior in every SDK.

Existing scalar providers retain their declarations, grants, results and journal
recovery. Historical v1 receipts remain readable and are never reexecuted. Changed
schemas cannot reinterpret an offered call, and extending the argument vocabulary
does not widen authorization or change accounting attribution.

This first delivery is complete when `formatCheck`, `check` and `sdkCheck` pass
alongside the focused provider/schema tests. Run `format` for Java changes. No
database test opt-in is needed unless the implementation introduces a specific
PostgreSQL-dependent assertion. MCP transports, OAuth, credential persistence,
durable hosting bindings and connection-management UI belong to the later phases.

The [MCP result profile](mcp-provider-profile.md#results-and-capabilities) accepts
bounded text and structured MCP data rendered faithfully into the existing text
result contract. Validate `structuredContent`
against a supported `outputSchema` when supplied. Define `isError` mapping explicitly:
a returned `isError` tool result is recorded as COMPLETED receipt delivery with its
error flag preserved, while transport loss after submission remains UNKNOWN.
Unsupported media, task-required tools,
sampling, elicitation, prompts and resource operations receive explicit capability
diagnostics; this tools-first scope does not imply those capabilities are enabled.

Default tests mock repository and upstream transport boundaries. Shared fixtures
cover valid nesting, missing versus null, invalid nested inputs, unsupported
keywords, depth/size limits, name collisions, v1 compatibility and schema changes
between offering and invocation. Security tests cover user isolation, spoofed
credential subjects, Personal scope, executor grants, service-token rotation and
accounting attribution. Restart and lost-response tests prove no ambiguous tool
execution or result publication is repeated.

Platform/SDK changes require `format`, `formatCheck`, `check` and `sdkCheck`;
distribution changes also require `sdkPackageCheck`. Use the smallest explicitly
tagged `-PfullDb --tests` selection only for added migrations, credential row
mapping, locking/refresh concurrency or transactional binding persistence that
requires PostgreSQL. Mocked behavior tests do not prove live MCP interoperability.

See [server-held provider credentials](provider-credentials.md) for the implemented
connection and grant management contract, SDK operations and OAuth callback boundary.
