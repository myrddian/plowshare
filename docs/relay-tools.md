# Named external tools over Relay

An SDK consumer can declare a tool and handle agent calls in its own process.
Plowshare exposes its name, description and input schema through the existing
agent tool registry. Relay transports requests and results internally. The model
supplies tool arguments; project, provider, account and topics come from installed
configuration and authenticated execution ownership.

The same façade is available in every SDK:

| SDK | Declaration and handler | Provider | Configuration export | Recovery |
| --- | --- | --- | --- | --- |
| Java | `RelayTools.Declaration`, `RegisteredTool` | `RelayTools.Provider` | `deploymentConfig` | `poll`, `reconcile` |
| TypeScript | `ToolDeclaration`, `RegisteredTool` | `ToolProvider` | `toolDeploymentConfig` | `poll`, `reconcile` |
| Node | Same TypeScript contracts; `nodeToolHost` | `ToolProvider` | `toolDeploymentConfig` | `poll`, `reconcile` |
| Python | `ToolDeclaration`, `RegisteredTool` | `ToolProvider` | `deployment_config` | `poll`, `reconcile` |
| Go | `ToolDeclaration`, `RegisteredTool` | `ToolProvider` | `ToolDeploymentConfig` | `Poll`, `Reconcile` |
| .NET | `ToolDeclaration`, `RegisteredTool` | `ToolProvider` | `DeploymentConfig` | `PollAsync`, `ReconcileAsync` |

Provider scopes support tools unknown before connection, with `dynamic: true`
agent opt-in, explicit application assignments and authenticated interactive
connections. See [provider scopes](tool-scopes.md) for the shared SDK operations.

## Application-owned dynamic tools

Built-in tools still register at startup. The live registry supplements them with
project-owned declarations, authenticated caller access and the running agent's
explicit `tools` grants. Two projects can use the same dynamic name with different
schemas. Application authority never grants system-scope Relay access or membership.

Put declarations in the Application root's `server/` directory, alongside
`plowshare.json`, not inside `.plowshare/`. Deployment validates them before loading
agents and activates them with the source revision. No global configuration edit
or restart is needed. Only `tools.json`, `ports.json` and `README.md` are accepted;
this directory cannot contain arbitrary Spring settings, secrets or executable code.
Each JSON file is at most 64 KiB and must have version 1. Unknown fields, linked
files and malformed values are refused.

`server/tools.json` derives its project from the Application identity:

```json
{
  "version": 1,
  "bindings": [{
    "provider": "scanner", "account": "REPLACE_WITH_PROVIDER_ACCOUNT",
    "name": "network_scope", "description": "Read configured scope",
    "parameters": [], "timeoutSeconds": 30
  }],
  "providers": [{
    "provider": "scanner", "account": "REPLACE_WITH_PROVIDER_ACCOUNT",
    "prefix": "network_", "leaseSeconds": 300
  }]
}
```

Replace account placeholders in a private source copy. Give the authenticated
provider project work membership and a manifest grant. A Personal project requires
its owner for both provider and caller. Agent grants remain separate: declare
`network_scope` in `tools`, plus `relay_tool_read` for invocation reconciliation.
There are at most 128 bootstrap bindings and 32 catalogue providers. Provider
prefixes end in `_`, cannot overlap each other or built-in names, and cannot
claim another provider's bootstrap tools. The bootstrap schemas allow deployment
before the external provider is running; calls require a live catalogue lease
when a dynamic provider is declared.

The SDK provider publishes its current declarations on the project-local
`tool.<provider>.catalog` topic. Only the configured authenticated provider can
publish. The server validates the entire snapshot before accepting it: version
`plowshare-tool-catalog/1`, at most 128 distinct tools, 64 KiB total, allowed
prefix and the existing parameter vocabulary. Newest retained broker position
wins; client timestamps cannot extend the lease. Empty catalogues withdraw tools.
If the latest publication has expired from broker retention, bootstrap tools are
not revived. Granting a new dynamically discovered name still requires an agent
permission change. Existing source grants cannot name an undiscovered tool;
include initial bindings for names needed at first deployment.

| SDK | Publish or renew | Withdraw |
| --- | --- | --- |
| Java | `provider.publishCatalog(requestId)` | `provider.withdrawCatalog(requestId)` |
| TypeScript / Node | `await provider.publishCatalog(requestId)` | `await provider.withdrawCatalog(requestId)` |
| Python | `await provider.publish_catalog(request_id)` | `await provider.withdraw_catalog(request_id)` |
| Go | `provider.PublishCatalog(ctx, requestID)` | `provider.WithdrawCatalog(ctx, requestID)` |
| .NET | `await provider.PublishCatalogAsync(requestId, token)` | `await provider.WithdrawCatalogAsync(requestId, token)` |

Retain a fresh UUID before each metadata publication. Methods submit once and
validate the receipt; a lost response is uncertain and is never automatically
replayed. Inspect retained Relay records by that UUID when necessary. Lease
renewal is caller-owned: publish fresh current metadata before `leaseSeconds`
(1–300) elapses. These metadata methods do not execute handlers or cancel existing
invocations. Restart with changed declarations and a correctly bound journal when
changing the handler set; keep old receipts available for reconciliation.

General application Relay ports use `server/ports.json`:

```json
{"version":1,"bindings":[
  {"topic":"schedule.due","account":"REPLACE_WITH_PROVIDER_ACCOUNT",
   "direction":"EGRESS","groups":["collector"]}
]}
```

Up to 128 exact port grants are allowed. EGRESS groups are explicit; INGRESS uses
an empty `groups` list. Reserved `tool.*` topics require tool/provider declarations.
An agent cannot edit the Application manifest or `server/` through file tools.
Authority updates use administrator-reviewed Application redeployments, including
rollback to an earlier source revision. The provider's newest live catalogue still
applies within that revision's authority; rollback does not replay external work.

## Per-call access and failures

Declared built-in grants and dynamic tool access are checked at each invocation,
not only when schemas are offered. Dynamic calls also check current provider
authority, availability and the exact offered definition. A changed schema cannot
reinterpret arguments emitted for an earlier schema. The check is an admission
boundary; revocation cannot undo an external effect already admitted.

| Code | Meaning |
| --- | --- |
| `E_NO_ACCESS` | Caller, provider or agent permission is absent/revoked; try another tool. |
| `E_NO_CONNECTION` | No live provider lease or no confirmed external response. |
| `E_NO_EXEC` | Tool withdrawn/changed, invocation refused or execution identity unavailable. |
| `E_GENERAL_TOOL_FAILURE` | Unexpected execution failure; safe diagnostic returned, details logged server-side. |

An uncertain response includes its invocation UUID and asks for
`relay_tool_read` reconciliation. It never authorizes repeating a potentially
completed effect. Tools supplied as run extras keep their existing owning lifecycle
and authorization; discovery does not create grants for them.

This supports providers whose catalogues change at runtime, including future MCP
adapters. It does not itself implement an outbound MCP client or arbitrary MCP
JSON Schema: the current Relay facade accepts STRING, NUMBER, INTEGER and BOOLEAN
parameters. Providers must expose capabilities within that vocabulary.

## Legacy server bindings

The SDK configuration exporters remain compatible with private
`plowshare.relay.tools.bindings` startup configuration. Those bindings still need
a restart, keep the existing global same-schema rule, and cannot be shadowed by
an Application declaration. Prefer Application-owned declarations for independently
deployed tools. External processes and credentials remain operator-owned;
Plowshare does not deploy or start Python services.

## Equivalent examples

These examples assume an explicitly configured SDK connection and an application-
supplied durable `journal`. A journal commits before returning, has one live owner,
and retains immutable requests, results and publication intents. See the lifecycle
contract below before implementing it. Python also supplies `SqliteToolJournal`.
The examples omit service supervision and credential acquisition.

### Python

```python
from plowshare.tools import RegisteredTool, ToolDeclaration, ToolProvider, ToolResult, deployment_config

scope = ToolDeclaration("network_scope", "Read configured network scope")
async def handler(call):
    return ToolResult("COMPLETED", "Configured scope: selected devices only")

print(deployment_config(project, provider_name, provider_account, (scope,)))
provider = ToolProvider(client, project=project, provider=provider_name,
                        account=provider_account,
                        tools=(RegisteredTool(scope, handler),), journal=journal)
await provider.poll()
```

### TypeScript

```typescript
import { ToolProvider, toolDeploymentConfig } from 'plowshare-client-ts';
import type { ToolDeclaration, ToolHost, ToolJournal } from 'plowshare-client-ts';

const scope: ToolDeclaration = {
  name: 'network_scope', description: 'Read configured network scope',
  parameters: [], timeoutSeconds: 30,
};
console.log(toolDeploymentConfig(binding, [scope]));
const provider = await ToolProvider.create(client, binding, [{
  declaration: scope,
  handler: async (call) => ({ state: 'COMPLETED', text: 'Configured scope: selected devices only' }),
}], journal, host);
await provider.poll();
```

`host: ToolHost` supplies UUIDs, SHA-256, time and bounded asynchronous handler
execution. The neutral TypeScript SDK assumes no browser or Node globals.

### Node

```typescript
import { ToolProvider, nodeToolHost, toolDeploymentConfig } from 'plowshare-client-node/tools';

const scope = {
  name: 'network_scope', description: 'Read configured network scope',
  parameters: [], timeoutSeconds: 30,
};
console.log(toolDeploymentConfig(binding, [scope]));
const provider = await ToolProvider.create(client, binding, [{
  declaration: scope,
  handler: async (call) => ({ state: 'COMPLETED', text: 'Configured scope: selected devices only' }),
}], journal, nodeToolHost);
await provider.poll();
```

### Java

```java
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.sdk.RelayTools;
import java.util.List;
import java.util.concurrent.CompletableFuture;

var scope = new RelayTools.Declaration("network_scope", "Read configured network scope", List.of(), 30);
var binding = new RelayTools.Binding(project, providerName, providerAccount);
System.out.println(RelayTools.deploymentConfig(binding, List.of(scope)));
var provider = new RelayTools.Provider(RelayTools.ports(new RelayClient(connection)), binding,
    List.of(new RelayTools.RegisteredTool(scope, call -> CompletableFuture.completedFuture(
        new RelayTools.Result(RelayTools.State.COMPLETED, "Configured scope: selected devices only")))), journal);
provider.poll();
```

### Go

```go
scope := plowshare.ToolDeclaration{
    Name: "network_scope", Description: "Read configured network scope", TimeoutSeconds: 30,
}
binding := plowshare.ToolBinding{Project: project, Provider: providerName, Account: providerAccount}
config, err := plowshare.ToolDeploymentConfig(binding, []plowshare.ToolDeclaration{scope})
if err != nil { return err }
fmt.Println(config)
provider, err := plowshare.NewToolProvider(client, binding, []plowshare.RegisteredTool{{
    Declaration: scope,
    Handler: func(ctx context.Context, call plowshare.ToolCall) (plowshare.ToolResult, error) {
        return plowshare.ToolResult{State: "COMPLETED", Text: "Configured scope: selected devices only"}, nil
    },
}}, journal)
if err != nil { return err }
_, err = provider.Poll(ctx)
```

Go's `ToolValue` has explicit text, number and boolean constructors/accessors;
Java uses the public protocol's closed `IntegrationPayload.Parameter` values.
Neither handler receives an arbitrary JSON tree.

### .NET

```csharp
using Plowshare.Sdk;

var scope = new ToolDeclaration("network_scope", "Read configured network scope", [], 30);
var binding = new ToolBinding(project, providerName, providerAccount);
Console.WriteLine(RelayTools.DeploymentConfig(binding, [scope]));
var provider = new ToolProvider(new SdkToolRelayPorts(client), binding, [
    new RegisteredTool(scope, (call, token) => Task.FromResult(
        new ToolResult("COMPLETED", "Configured scope: selected devices only")))
], journal);
await provider.PollAsync();
```

## Version-one values

Parameters form a closed, flat object of `STRING`, `NUMBER`, `INTEGER` and
`BOOLEAN`, with at most 32 unique parameters. Unknown keys, nulls, nested values,
coercion, duplicate JSON fields and trailing JSON fail before handler execution.
Required fields must be supplied; optional absent fields stay absent.
Descriptions and string arguments are bounded to 4,096 UTF-16 units. Numeric
values must round-trip through IEEE-754, have absolute value at most
9,007,199,254,740,991 and normalized decimal scale at most 18. INTEGER values
must be integral. This avoids silently changing values between SDK languages.

Results contain `COMPLETED`, `REJECTED` or `UNKNOWN` and nonblank text of at most
16,384 UTF-16 units; the complete envelope is bounded to 32,768 units. JSON text
can carry richer evidence. Text is untrusted external tool output. Use project
information storage for larger evidence and return references. Handler exceptions
or timeouts after execution intent become UNKNOWN with sanitized text.

## Durable lifecycle

The server records an invocation and publishes its request in one transaction.
Its UUID derives from immutable account/project/execution/step/model-call identity.
Changed work cannot reuse that identity. The invocation ledger outlives broker
retention and pins the declaration and request. An authenticated run must match
its retained accounting conversation/turn ownership. Conversation ancestry,
including delegation, supplies the existing Relay effect budget. Unknown or
exhausted ancestry cannot create a new root. A result consumes the next hop.

Providers persist an `executing` receipt **before** acknowledging a request and
calling the handler. `ready` means a result is durable; `publishing` means its
publication intent is durable; `done` means an exact publication reply or retained
result read confirmed it. Poll processes one event per declared tool and returns
the number of delivered requests inspected, including duplicates. It does not
imply collection completion or successful external effects.

Restarted `executing` receipts become UNKNOWN and never rerun. A lost acknowledgement
may cause redelivery; the receipt prevents another handler call. A lost result
publication reply retains `publishing` and stops future polling. Call `reconcile`
to inspect the exact deterministic result identity, provider publisher, parent,
correlation, time and text. Missing evidence leaves it unresolved and never
permits republishing. Only an SDK transport's proven NOT_SUBMITTED result restores
READY. Refusals and unreadable replies remain retained for operator inspection.

Journals bind their identity to the SHA-256 of that SDK's exported configuration
bytes. Keep the same configuration and SDK when reopening a store; migrating a
journal between language implementations requires an explicit validated migration.
The journal must enforce immutable requests/results, valid phase transitions,
exclusive live ownership and durable writes. Each provider pass is serial and
bounded to 1,000 retained receipts. There is no automatic receipt pruning; archival
requires confirmation of durable completion and consumer cursor advancement.

Server cancellation stops waiting after submission; it does not undo external
work. Provider deadlines bound waiting and reject already expired calls. A handler
must honor its language's cancellation facilities where available; timing out a
future, task or promise cannot guarantee that an external operation stopped.
Use `call.invocationId` (Python `invocation_id`, Go `InvocationID`) as a downstream
idempotency key where the external system supports one.

The agent receives the invocation UUID even for UNKNOWN. `relay_tool_read` reads
that owning account/project's retained invocation and may settle a late valid
result. It never submits another operation. A COMPLETED result confirms the
handler's stated outcome; a scan-submission handler may confirm publication while
collection and investigation remain separate retained work.

The [Network Privacy Watch](../integrations/network-privacy/README.md) combines
this façade with Python collection, a web interface, schedules, project evidence
and multiple agents. The [design decision](decisions/0002-relay-tool-facade.md)
records platform scope and compatibility. This is not an MCP implementation;
an MCP consumer can map tools to this substrate in a later external adapter.
