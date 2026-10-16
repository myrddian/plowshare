# MCP interoperability and acceptance

Server tools use the reusable [outbound MCP integration](../integrations/mcp/README.md)
and existing public ToolProvider, hosted authority, Relay and receipt contracts.
Server hosting accepts HTTP(S), with the server-held credential profile requiring
HTTPS. Native CLI/TUI/desktop hosts also accept STDIO and call MCP directly in
process. Browser clients manage server tools; they never launch an MCP process.
The outward-facing `plowshare-mcp` is a separate server.

## Configuration and authority

1. Enable [server credentials](provider-credentials.md#operator-configuration)
   with an explicit encryption key, HTTPS origin allowlist and callback URL.
   Configure exact trusted `@service/<token UUID>` executor principals under
   `plowshare.providers.hosting.executors`. Retain keys across restart.
2. Create the shared provider connection in an ordinary credential project and
   authorize each user's own grant. Plowshare login is not upstream MCP login.
   API-key material is a complete configured header value; OAuth exchanges are
   deliberate management actions, never execution retries after a challenge.
3. Save each owner's [hosted binding](hosted-tool-scopes.md), selecting credential
   project/connection, subject, executor, prefix, agents and grants. The execution
   owner, upstream subject and worker are distinct. Service-token project ceilings
   also apply to credential storage: adding service membership alone cannot widen
   an existing token's scopes.
4. Run the adapter with the private [configuration reference](../integrations/mcp/README.md#configure-and-start).
   Every timeout is explicitly configured. Review its current catalogue/lease
   and scope availability through the [client controls](provider-management-clients.md).
   A saved enabled binding is not proof of a connected, authorized provider.
5. For an Application, explicitly deploy its service identity, manifest tool scopes
   and agent assignments, issue its token once and use its exact principal.
   Keep credential storage inside that token's allowed projects. A human selecting
   a service credential retains human model accounting. MCP adds no spending pool.

`2026-07-28` uses read-only discovery and per-request metadata. `2025-11-25`
uses initialization and a private session. Select the revision explicitly; there
is no protocol fallback. The official client/core packages are pinned to 2.3.1.
Use the [profile](mcp-provider-profile.md) for version behavior and refused features,
[schemas](tool-schemas.md) for the common recursive profile, and
[recovery](mcp-recovery.md) for original-invocation inspection.

Input and output schemas retain supported constraints exactly. Unsupported schema
keywords, required capabilities and malformed pages refuse coherent discovery;
there is no partial publication. Accepted tools preserve nested objects/arrays.
Invalid nested arguments cause no MCP effect. Text and portable structured output
are retained in the existing result envelope. Received `isError` is `COMPLETED`;
unverified dispatched effects are `UNKNOWN`. Auth refusal, redirects, malformed
output or input continuation after dispatch never trigger another effect.

## Independent fixtures and installed SDK consumers

```sh
./gradlew :plowshare-mcp-integration:check
./gradlew :plowshare-mcp-integration:mcpIntegrationConsumerCheck
./gradlew formatCheck check
```

The HTTP fixtures speak JSON-RPC independently of the official MCP server SDK.
Both revisions cover JSON/SSE, nested calls, credentials/session isolation,
pagination and changed catalogues, later-page refusal, authentication challenges,
credential/connection changes, malformed output, deadlines and cancellation.
Hosted HTTP tests retain TLS verification using an isolated public test certificate;
the test launcher trusts it only in its child process. It is not a deployment key
or trust configuration. The packed-consumer check repeats tests against installed
public SDK artifacts, without importing server code.

Server behavior tests use mocked repositories or in-memory fakes. Relevant suites
include `HostedMcpGrantTest`, `OwnedHostedToolScopesTest`, `ProviderBoundariesTest`,
`CurrentProviderCredentialGrantsTest`, `ApplicationToolRegistryTest`,
`ServiceUsageOwnershipTest` and `ProjectRelayToolInvocationsTest`. They verify
current authority, attribution admission and original-fence recovery; they do not
establish PostgreSQL transactions or organization OAuth interoperability.

## A separate local test instance

Use the [test-instance recipe](../test-support/mcp/README.md) to start a fresh,
loopback-only authenticated server with its own PostgreSQL container, private
state, synthetic model and independent TLS MCP endpoint. It supplies two users,
an Application-owned service identity and a separate provider executor through
public SDK/auth APIs. It never reads installed Plowshare configuration or uses a
real model-provider credential. There is no real inference bill.

The explicit configured acceptance pass checks each identity through two independent
SDK connections, its own credentials and server-held binding, then submits a real
server agent job and observes exactly one upstream MCP effect for that subject.
It reads original jobs through public SDK operations, attempts the original
accounting reads and checks cross-user binding refusal plus SDK rejection of a
foreign human credential selector. An accounting refusal or invalid reply is an
explicit blocked dimension, not a passed attribution check. This is real server/database/public-SDK integration
against controlled upstream services. It is not a live organization MCP/OAuth run,
a UI interaction test or separate-host deployment acceptance.

Test state includes private credentials and journals; retain it outside source.
The redacted report names the tested environment and omitted acceptance cases.
A failed or interrupted provision/exercise pass refuses automatic restart. Inspect
its original intents, jobs and receipts; use a new disposable instance for a new
acceptance run rather than replaying uncertain work.

## Organization and deployed-host acceptance

Run these checks only against explicitly selected test accounts and tools. Record
server and adapter revisions, selected protocol, authentication profile, client
builds and host separation, exact principal roles, original job/invocation IDs,
credential/binding generations and redacted outcomes. Keep endpoint addresses,
secrets, session IDs and private payloads outside public evidence.

| Case | Required observation |
| --- | --- |
| Two organization users | Same endpoint, each user's own tools/grant; no session/cache/credential or private tool leakage. Repeat from a second client without transferring upstream secrets. |
| Service Application | Manifest grants and exact token principal authorize the job. The upstream sees the configured service subject. Original job/model accounting belongs to the admitted execution owner, without a new spending pool. |
| OAuth expiration/rotation | Deliberate refresh/replacement changes the generation. The old context refuses execution; a new reviewed context gets distinct offered identities. A post-dispatch challenge causes one attempt and UNKNOWN. |
| Revocation/disablement | Revoke a grant, disable an account/connection or remove an agent assignment before dispatch; stale proxies cannot send an effect. Already admitted effects retain their original evidence. |
| Schema/metadata change | No partial renewal or reuse of the old offered name. Review a new catalogue and deliberately update exact grants. |
| Loss, deadline, cancellation | Retain original invocation, SDK receipt and publication fence. Cancellation does not prove rollback. Read-only reconciliation never resends the effect. |
| Restart/late completion | Restart the same retained state and inspect the original job/receipt. Verify an available matching late result without creating another invocation; retained UNKNOWN is not permission to retry. |
| Different hosts/containers | Configure distinct origins, bind addresses and trust. Prove authenticated SDK traffic and MCP transport without a shared-host assumption. |

Fresh external-tool human approval, immediate server cancellation advisories to a
worker and delivery-only publication for retired ready journals are public
capability gaps described in the authority/recovery manuals. Do not emulate them
with another ledger, foreign-job polling or a retired-provider intake pass.
Organization OAuth, UI and separate-host checks remain distinct acceptance gates;
a healthy local server or synthetic pass cannot mark those cases passed.

## Recorded local acceptance limits

The configured local run completed one MCP effect per human/service identity and
read the three original jobs from a second SDK client without another effect.
Accounting acceptance remains blocked: human `usage.calls` results omit nullable
fields required by the public SDK, and the service token's accounting read is
refused by the current operation policy. The conversation accounting read also
fails SDK validation. No raw response or internal database read substitutes for
those failures. See the [redacted acceptance record](../test-support/mcp/acceptance-evidence.md).

The test setup uses explicit interface proxies to avoid a final-repository default
startup failure. This configuration does not prove the default launch path works.
Both problems need separately scoped general platform fixes; this adapter change
does not alter core, authorization or accounting contracts.
