# MCP tool grants, hooks and invocation evidence

MCP uses the existing provider catalogue, scope assignments, invocation record and
SDK journal. Discovery describes available capabilities; it grants no authority.
Server hosting uses HTTP. Native clients call HTTP/STDIO directly in process;
only explicit server sharing crosses the provider/Relay boundary.

## Authority before an external effect

| Boundary | Required authority or contract |
| --- | --- |
| Server connection and credential | An enabled connection and the selected subject's current grant, connection revision and credential generation. Plowshare login is distinct from upstream MCP authorization. |
| Offering tools | Current owner/project access, an assigned agent with `dynamic: true`, permitted scope/provider/tool names, live worker/socket and unexpired catalogue. Application work also needs its execution account, manifest scopes and agent grants. |
| Model invocation | The current live registry rechecks those grants and the whole offered definition. A withdrawn tool, expired lease, changed input schema or revoked assignment cannot use a previously offered proxy. |
| Hosted handler | The call must match the admitted owner, exact project/provider route, offered name, nested schema version/fingerprint and arguments. Then the public worker credential operation authorizes the exact unfinished invocation under its retained worker fence. |
| Native sharing handler | The call must match the owning account, exact shared route, offered name and nested contract. The original socket scope and SDK journal remain required; a handler is not a standalone admission API. |

The upstream name never selects a Plowshare built-in. Each MCP offered name hashes
its immutable execution context and tool metadata, including input/output schemas.
An exact name grant does not automatically follow a changed contract. An owner
must deliberately update it. A wildcard follows valid tools only within its
existing provider, owner, project, agent and scope fences; it cannot acquire
built-ins or another provider's tools. Unsupported schemas still refuse discovery.

Bindings distinguish the admitted caller, configured worker and upstream credential
subject. A worker credential or service-owned MCP secret does not turn a human run
into a service-owned run. A service-executed Application retains its exact service
principal and current token ceilings; Personal access is never manufactured for
that principal. See [accounting policy](mcp-accounting-policy.md) and
[hosted scope authority](hosted-tool-scopes.md).

## Hooks and approvals

The runtime applies `tool.pre` and `tool.post` to dynamically offered provider tools
as it does to other tools. A pre-hook denial prevents invocation. Rewritten
arguments must still pass the tool's current permission/schema checks; a rewrite
cannot supply another credential subject. Post-hook redaction determines what the
model sees and records the hook decision through the existing transcript.

MCP descriptions, annotations and results are untrusted external data. A
`readOnlyHint`, `destructiveHint` or `idempotentHint` neither grants execution nor
waives a hook, permission check or approval. Metadata participates in the offered
identity but is not copied into Plowshare permission or approval settings. Remote
prompts/resources, sampling, elicitation and automatic input continuation are not
supported by this tools profile.

**Per-call human approval for external tools is not implemented.** Existing durable
approval questions and their `once`/standing-command decisions belong to the
`run` command gate. The public SDK can inspect/answer/revoke those command
approvals; it cannot request or bind one to arbitrary MCP arguments and their
schema. A `tool.pre` hook's `ask` is consumed by the command gate, not by an
external provider tool. Do not present it as a pending MCP approval or assume it
prevents dispatch.

If an external operation requires a fresh human decision, withhold its grant or
deny it with trusted policy until a separately reviewed general approval capability
exists. A native host's explicit launch/share consent is also distinct from per-call
approval. An adapter cannot safely emulate durable suspension, one-time consumption,
revocation and restart by inventing its own approval record or reusing a command
approval UUID. Extending that contract requires separate platform scope under
[ADR 0001](decisions/0001-integration-boundary.md); this integration does not change
the command approval lifecycle.

## Owning evidence and safe recovery

The retained server invocation owns the original caller, agent, project/run/call,
tool definition and input fingerprint, arguments, deadline and causal ancestry.
Hosted selection adds binding/generation, executor, registration, credential
project/connection/revision, subject and grant generation. These coordinates remain
with the original invocation after configuration changes. The catalogue hash is
an identity fence, not a new authorization or billing ledger.

The SDK saves execution intent and the original request before entering a handler,
then retains its outcome and publication state. Hosted receipts retain the original
worker fence. Private arguments/results may contain sensitive user content; they
are not public diagnostics. Credentials and upstream session tokens are never
added to receipt metadata. Tool output is not an invoice or model-token report.

A refusal before upstream dispatch is `REJECTED`. A verified received result,
including upstream `isError`, is `COMPLETED`. An unverifiable dispatched effect is
`UNKNOWN`. Results and reconciliation require the original invocation, publication
identity and fence. A model instruction, new credential, catalogue refresh or
approval cannot authorize repeating an uncertain effect. Use read-only owning
receipt reconciliation; never resubmit to recover a missing response.

## Verification boundaries

- `integrations/mcp/src/provider.test.ts` covers wrong callers/routes/contracts,
  denied credential release despite upstream hints, and original SDK receipt
  identity without persisting released credentials.
- `integrations/mcp/src/native-bridge.test.ts` covers the shared native handler's
  owner/route/contract fence through an independent STDIO fixture. Direct native
  host tests continue to require no Plowshare/Relay connection.
- `HostedMcpGrantTest` composes real hosted authority and the live registry with
  mocked repositories and a synthetic nested catalogue. It covers exact grants,
  owner/agent isolation, changed schemas and caller/worker provenance.
- `HookedRunTest` exercises denial/redaction through the real dynamic-tool run
  loop. Existing Application, hosted credential, provider-boundary and invocation
  suites cover manifest/token/Personal fences, revocation, leases and recovery.

These are behavior/fixture tests. They do not prove deployed organization OAuth,
PostgreSQL transaction behavior or a general external-tool human approval flow.
