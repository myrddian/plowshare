# MCP connection management and native hosting workflows

Status: client workflow design for review, audited against main
`7443fea2981615846d2a21ea835d6b0841447e0e` on 13 October 2026.
Credentials and durable provider bindings are implemented. The screens, friendly
verbs and native MCP host described here are planned. This is not a live MCP or
deployed-client acceptance claim.

MCP consumes the existing SDK provider, scopes, catalogue leases, Relay invocation
record and exclusive provider journal. See the [provider design](mcp-client-design.md),
[bounded adapter profile](mcp-provider-profile.md), [credentials](provider-credentials.md)
and [hosted authority](hosted-tool-scopes.md). The outward-facing `plowshare-mcp`
continues to expose Plowshare operations, independently of consuming MCP tools.

## Current client seams

| Surface | Verified implementation | Remaining work |
| --- | --- | --- |
| CLI | [Parser](../sdk/typescript/src/operations/commands.ts), [catalogue](../sdk/typescript/src/operations/catalog.ts) and [runner](../plowshare-cli/src/run.ts) accept typed `provider connection …`, `provider credential …` and `tool scope host …` operations. `--payload -` accepts JSON from stdin. Ordinary commands close their socket after the result. | Safe credential entry, operation-specific recovery, friendly management and persistent MCP hosting. |
| TUI | [Session commands](../plowshare-tui/src/logic/session.ts) and [view dispatch](../plowshare-tui/src/view/main.ts) reuse SDK contracts on a persistent socket. Their current allowlists do not expose the new provider management families. | A management panel and approved slash verbs; explicit native host ownership. SDK catalogue presence alone does not expose a TUI command. |
| Desktop | [Client](../plowshare-desktop/src/client.ts) validates SDK operations; [preload](../plowshare-desktop/src/preload.ts) exposes typed IPC; [navigation](../plowshare-desktop/src/renderer/navigation.ts) preserves selected work. The current request union has no provider management action. | Typed management IPC, bounded display DTOs and panels; native hosting in the main process. |
| Console | [Shell](../plowshare-console/src/screens/shell.ts), [transport](../plowshare-console/src/transport.ts) and [events](../plowshare-console/src/events.ts) retain screens and share the tab socket. Supported historical route keys map to WebSocket operations. The [view contract](../test-support/contracts/console-views.json) has no provider screen. | A checked management capability on that same socket and a project-scoped panel. No browser subprocess or browser-hosted provider. |
| SDKs | Java, TypeScript/Node, Python, Go and .NET expose typed management/worker operations. [TypeScript/Node ToolProvider](../sdk/typescript/src/operations/relay-tools.ts) supports delegated worker ports and original-fence recovery. | One reusable Node MCP consumer outside core; other SDKs need not duplicate MCP transports. |

Comments describing old view counts, HTTP job reads or ephemeral login tokens are
historical. Implementation, contracts and the [current login manual](client-login.md)
determine the design.

## Location and identity matrix

Call the feature **Tool connections**. Keep Plowshare named server/account
connections under the existing selector; an MCP endpoint is a different connection.
Show the selected Plowshare origin/account and execution project throughout.

| Client and choice | Owner and credential location | Executor and transport | Assignment and lifetime | Actions |
| --- | --- | --- | --- | --- |
| CLI: server tools | Authenticated owner; encrypted server grant in an ordinary credential project | Operator-approved external worker; HTTP only | Durable execution-project binding, eligible Personal work included; explicit dynamic agents/grants | Inspect endpoint, authorize own grant, manage own binding and receipts. CLI exit does not stop the worker. |
| TUI: server tools | Same server-held owner grant, following the user between clients | Same worker; HTTP only | Same retained assignments | Same authorized management on the current socket; closing the TUI retains assignments. |
| Desktop: server tools | Same server-held owner grant | Same worker; HTTP only | Same retained assignments | Same actions through checked IPC; account switch discards the old management view/drafts. |
| Console: server tools | Same authenticated owner using the tab login | Same worker; HTTP only | Same retained assignments | Manage server tools and own authorization. No executable picker, environment editor or local host start. |
| CLI: tools on this instance | Authenticated owner; native configuration/secrets outside server configuration | Explicit foreground host; HTTP or STDIO | Scope on this event socket/session, with explicit dynamic agents/grants | Review, start, inspect and stop; exit removes availability and retains the SDK journal. |
| TUI / desktop: tools on this instance | Authenticated owner; private native configuration/secrets | Host owned by that client process; HTTP or STDIO | Scope on this event socket/session | Explicit start/stop. Disconnect, account switch and process exit stop intake and withdraw availability. |
| Console: native scope observed | Same owner can inspect its scope list; secrets/configuration remain native | Another native instance | Interactive scope is usable only in its owning session | Read-only indication: “Another active session; host details unavailable.” No adoption, remote start/stop or promise that browser work can use it. |

Discovery does not assign agents. Require explicit selection and exact grants or
“All tools from this provider, including future additions” for `['*']`.
`dynamic: true` is necessary but grants no authority itself. Applications retain
manifest identity and assignments. A human administrator cannot edit another
service principal's owner-only hosted binding by putting its handle in a payload.

### Roles and service identities

| Actor | Permitted server management |
| --- | --- |
| Active project viewer | Read shared endpoint settings; no authority over another subject's grant or binding. |
| Active contributor / Personal owner | Manage own eligible grant in an ordinary credential project and own eligible execution-project binding. Credentials cannot be stored in Personal. |
| Server administrator with credential-project manager access | Create/change shared definitions. Administrator status alone is insufficient. |
| Administrator selecting service credentials | Select an exact active `@service/<token UUID>` subject with contributor access and current token ceilings. Never another human's grant. Their own execution-project work authority still applies. |
| Configured executor | Use the finite worker facade for its binding/original invocations. Service-token login grants no general access to owners' secrets or Personal work. |

Show **binding owner**, **credential subject**, **executor** and **accounting owner**
separately. A service credential does not relabel a human caller's model accounting;
service-executed Applications keep their current accounting identity. Separate
enforced spending pools need an accounting decision; this increment has no pool
selector. Rotation preserving a token UUID retains identity; replacement does not.

Use authorized service-token metadata where available. There is no public approved
executor roster: require an exact operator-supplied principal, validated by the
server. Do not build an executor dropdown from all service accounts or embed an
operator configuration file in the client.

## Shared management capability and evidence

Build a narrow typed management capability on the existing checked transport.
CLI/TUI use their current socket; desktop main owns it behind typed IPC; console
adapts its tab socket with `checkedTransport`. Views consume typed results and
explicit refusal/uncertainty, never wire property bags. Desktop renderer receives
metadata, not Plowshare tokens or worker headers.

Each row has three independent facts: **saved configuration**, **your authorization**
and **availability in the latest server snapshot**. Read
`provider.connection.list/status` in the credential project,
`provider.credential.status` for the permitted subject, `tool.scope.host.list/status`
in the execution project, and `tool.scope.list` for current routes. Join a hosted
binding to its exact returned route. An enabled retained binding is not availability;
the active scope list checks hosted worker, credential and fresh catalogue. Its
snapshot cannot guarantee the next call will be admitted.

Refresh reads after a mutation, reconnect, selection change or explicit Refresh.
Bound visible-panel refresh, deduplicate concurrent reads and stop subscriptions/
timers on disposal. No provider management event subscription contract exists.
Mark cached values stale during disconnection; drop late replies from an old
account/project selection. Do not open another event socket.

| User action | Existing owning operation | Follow-up |
| --- | --- | --- |
| Inspect endpoint | `provider.connection.list/status` | Credential project, definition, revision, enabled/deleted state. |
| Configure / enable / disable / delete endpoint | `provider.connection.save/change` | Fresh request UUID/current revision; receipt/status. Every revision change revokes prior grants. |
| Check my authorization | `provider.credential.status`, explicit `subject: null` | State, generation, expiry; never secret material. |
| Authorize in browser | `provider.credential.authorize` | Deliberately open once-only URL; follow current status. Receipt remains the original PENDING snapshot. |
| Set API key / machine secret | `provider.credential.put` | Entry bypasses history/drafts/telemetry; clear after dispatch or refusal. |
| Obtain / refresh access token | `provider.credential.refresh` | Explicit action/current generation; inspect status, never replay an uncertain exchange. |
| Revoke / rekey | `provider.credential.revoke/rekey` | Fresh UUID/generation; receipt/status. Rekey requires READY and preserves generation. |
| Assign / edit server tools | `tool.scope.host.save` | Fresh binding UUID on creation; expected generation or explicit null; agents/grants/operator executor. Read status/receipt/active scopes. |
| Enable / disable / delete assignment | `tool.scope.host.change` | Fresh UUID/current generation. Withdraws worker registration; enable needs a fresh worker/catalogue. |
| Reconcile management | `provider.connection.receipt` or `tool.scope.host.receipt`, plus current status | Original project/request UUID. Missing receipt does not prove no mutation. |
| Start / stop native scope | `tool.scope.connect/disconnect` on the host socket | Returned routing binding feeds SDK ToolProvider; stop requires the owning interactive session. |
| Recover uncertain tool | Owning invocation read and SDK journal reconciliation; worker uses `tool.scope.worker.read` | Verified original result only; retain original route/fence, never resubmit handler. |

Worker operations belong to the trusted adapter. Management screens never call
worker secret release to “test a connection.” MCP compatibility checks perform
adapter discovery, not a model-selected tool call.

## Server tool flow and layout

1. Choose the credential project and shared endpoint, showing its authentication
   profile/destination. If absent, a permitted administrator may create it; other
   users see an instruction to contact a project manager who is a server administrator.
2. Inspect the permitted subject's grant. Default to **My authorization**; offer
   service-subject selection only with current authority. Show the execution
   project separately, especially for Personal work using ordinary-project credentials.
3. Authorize or supply the configured material. Plowshare login does not supply
   upstream tokens. Native OAuth opens a browser requiring a Plowshare login as
   the initiating actor; an administrator authorizing a service subject remains
   the callback actor. Use the existing landing page/authenticated completion POST
   HTTP exception, with no client loopback callback server.
4. Review owner, subject, executor, execution project, scope/prefix, agents and
   exact/wildcard grants. Save with the current generation. Saving does not start
   an executor or MCP connection; assignment may precede READY or available tools.
5. Refresh status/active scopes. Show tools only from authorized public evidence.
   The configured worker registers, discovers and publishes separately. A service
   credential cannot grant a missing Application assignment or nondynamic agent.

API-key READY means configured material. CLIENT_CREDENTIALS READY with no
`expiresAt` can mean a stored machine secret without an obtained access token;
show **Machine secret stored; obtain access token**. Display expiry independently.

```text
Tool connections                       Selected Plowshare server · account
Execution project [selection]          [Server tools] [On this instance]
Endpoint          Credential project   Authorization      Availability
[selection]       [ordinary project]   [current state]    [snapshot / stale]

Owner: me    Credential subject: me / permitted service token
Executor: configured service token     HTTP               Accounting: caller
Scope / prefix     Agents [selection]  Grants [exact names / provider wildcard]
[Refresh] [Authorize / Set credential] [Edit assignment] [Disable assignment]

Details: endpoint revision · binding generation · observed time
Last management action: request UUID · committed receipt / unknown
Compatibility: not reported / authorized adapter evidence when supported
```

Use labelled cards on narrow windows, retaining location/identity/authorization/
availability. Keyboard order follows details then actions; status uses text, not
color alone. A review dialog names the affected assignment/subject; dismissal
submits nothing and restores focus. Browser authorization follows a user-activated
link, never a popup from background refresh.

## Native host configuration and lifetime

Native hosting is opt-in. Configuration is separate from Plowshare named login
connections and server endpoints. Partition by canonical origin/account and an
explicit host-instance identity, then project/profile within that partition.
Clients may share login without sharing host secrets/runtime ownership. Rename
preserves identity; switching never adopts a foreign profile. Keep secrets and
journals outside synced projects, Application exports, browser storage and source.

Validate location, explicit protocol profile, HTTP destination/authentication or
STDIO executable/argument vector/working directory/selected environment, secret
references, scope/prefix, agents/grants, finite timeouts and journal location at
the adapter configuration boundary. Display a redacted launch summary. No shell
command string, implicit working directory, inherited secrets, model-selected
destination or silent profile/transport switch. Local OAuth and secure local
storage are adapter/client work; server grant APIs never export a user's grant
into a native profile. The bounded adapter profile owns limits/destination policy.

Desktop main or the TUI process owns upstream clients/subprocesses. Views request
typed start/stop actions. Validate and obtain explicit host consent before
launch/discovery, since STDIO startup itself can have effects. Complete bounded
discovery, then review supported tools/diagnostics before enabling chosen agents.
Unsupported tools are visibly refused, without stripped constraints. Existing
SDK catalogues/scopes carry them; host consent does not authorize an agent call.

Automatic start on login/restart is off initially. Polling and catalogue renewal
belong to an explicitly started host lifetime. Socket loss stops intake/renewal
and removes the old scope. Reconnect reconciles original receipts; deliberate
Start creates a new scope/route and journal generation. Preserve old uncertain
journals; a changed profile cannot reinterpret an admitted call. Another device
never serves as fallback.

### Proposed native verbs and terminal layout

These friendly verbs are design names, not installed commands. Existing typed
operation names remain available for scripts. Implementation must document accepted
configuration DTOs/flags before publishing runnable host examples.

| Proposed verb | Behavior |
| --- | --- |
| `mcp local inspect PROFILE` | Redacted configuration/journal summary; no launch. |
| `mcp local start PROFILE` | Foreground CLI host until Stop, signal, deadline or connection loss. No one-shot background success. |
| `mcp local reconcile PROFILE` | Original receipt reads; never run an uncertain handler or launch STDIO just to inspect a journal. |
| `/tools` in TUI | Current-socket management panel with Server / This instance sections. |
| Start / Stop in desktop or TUI | This process's host only, not a global stop based solely on scope name. |

```text
Tool connections · execution project · account         [Server | This instance]
Profile: selected native profile    Transport: HTTP / STDIO
Host: this TUI                      State: stopped / serving / lost
Agents: selected names              Grants: exact names / provider wildcard
Compatibility: supported snapshot / refused tools / not checked
[Inspect] [Review and Start] [Stop] [Read original receipts]
No host on this process: show configuration; another session is not local.
```

CLI emits bounded status/receipt metadata, NDJSON under `--json`. Ctrl-C/SIGTERM
and UI Stop first stop intake/renewal, withdraw the owned scope where possible,
request upstream cancellation and close owned process trees within the adapter's
finite grace deadline. Retain intent/results/uncertainty. This does not cancel a
server job, prove upstream rollback or delete receipts. If withdrawal fails,
close the host socket and let server liveness/lease fences remove availability.
Per-call refusals are events; uncertain effects remain visible in the foreground
host's final summary and later inspection.

## Console placement and lifecycle

Work overview, inbox, jobs, results and approvals remain the first console
implementation priority. Management is a later project slice via the existing
Projects surface, with a proposed **Tool connections** action. Do not reorder the
work rail or replace the shell. General URL/history changes belong to navigation
design, not this panel.

```text
Work: inbox · chat · jobs · results / approvals
System: projects → selected project → Tool connections
          Server tools · Your authorization · Your assignments
          Native scope: another active session (read-only, when observed)
```

Retain the panel within the shell's screen lifecycle. Leaving does not close the
shared socket or stop a worker. Revisit/reconnect performs bounded reads, retaining
stale indicators until success. Clear credential forms on submit, close, selection
change or logout. Secrets/OAuth URLs never enter saved screen state, URLs, telemetry
or dumps. Browser role presentation is not server authorization. Later real-browser
acceptance covers cookies, OAuth completion, screen switching on one socket,
reconnect, focus and narrow layouts.

## Refusal, uncertainty and recovery

| Observed state | Display and next action |
| --- | --- |
| Missing operation on older server | **Management unavailable on this server.** Bounded read probes, never mutation probes. A correlated NOT_FOUND can also mean a missing resource; one absent UUID does not establish server age. No HTTP fallback. |
| Malformed/foreign success | **Reply unreadable; completion unknown** for a submitted mutation. Last valid view stays stale; inspect original receipt/current status. Never install partial DTOs or retry the mutation. |
| Authentication lost | **Sign into this Plowshare account again.** Disable writes/native intake; preserve redacted coordinates/journals. |
| Role/account/token revoked | Safe server refusal. No membership broadening, alternate subject/executor or cached-control authority. |
| Enabled binding absent from active scopes | **Unavailable in latest server snapshot; reason not reported.** Do not infer disconnect versus credential/catalogue failure; refresh reads or contact the operator. |
| NONE / REVOKED | **Authorization required** / **Authorization revoked**. Deliberately authorize or replace the configured API/machine material, using current status and a fresh request UUID. |
| PENDING | **Awaiting upstream authorization.** Inspect status. Lost URL is not recoverable from the receipt; inspect before deliberately starting a fresh authorization. |
| REFRESHING | **Token exchange in progress.** Status can reconcile an expired intent; client sends no second exchange. |
| UNCERTAIN | **Reauthorization required**. Material was discarded; deliberately replace API/machine material or begin a new user authorization after inspection, with a fresh UUID. Never refresh the uncertain generation. |
| READY with expired access | **Access expired**. Explicitly refresh/obtain a token from the current READY generation. User OAuth may lack a refresh grant; preserve that refusal and authorize again deliberately. Expiry alone is not an uncertain exchange. |
| Lost management reply | **Outcome unknown**, showing operation/project/resource/request UUID. Receipt is original evidence; status is present state. Missing receipt permits no resubmission. |
| Native host lost/stopped | **This instance is unavailable; admitted effects may remain unknown.** Preserve receipts; explicit Start; no other-instance fallback. |
| Admitted tool | Disablement, refresh or closure cannot prove no effect. Read owning invocation/journal; cancellation is a request and verified original completion settles the result. |

For a lost **Disable assignment** reply, show its original UUID, **Outcome unknown**
and **Inspect receipt**. A receipt at generation G+1 proves that change committed
even if status is now G+2. Never render the old receipt as current state or offer
**Retry disable**. After native socket loss, **Read original receipts** and
**Review and Start** are independent actions, never automatic continuations.

Before sending, persist only a redacted management intent: server/account, project,
resource ID, operation, request UUID and expected revision/generation. This recovery
bookmark is not another execution ledger. Persist no secret request body, secret
hash or authorization URL. Existing CLI `--validate` prints the input payload;
until dedicated redaction exists, never validate real secret-bearing input or paste
it into terminal/composer history. Safe entry/output handling is required before
friendly credential commands ship.

## Evidence limits and delivery order

Owner reads expose saved bindings, permitted grants and active scope snapshots,
not worker session/lease details, approved executor rosters, per-tool MCP diagnostics
or last upstream failures. Worker status is executor-only. Native hosts can show
their own validated diagnostics; server views say **Not reported** until an
authorized public capability exists. Report that gap separately under the
integration-boundary decision before promising details. No core/internal-service
or generic Relay-log queries to manufacture UI evidence.

1. Review this matrix/layouts against merged contracts and console work/navigation
   priorities. The credential/hosted foundations are prerequisites; downstream
   MCP session tests must not block building the provider that owns those tests.
2. Add shared typed management, safe entry/redaction, receipt recovery and older
   server refusals. Audit mutation classification for ephemeral connect/disconnect
   as well as hosted/credential operations. Wire CLI/TUI/desktop; console follows
   the approved work/navigation sequence. Test roles, stale generations, selection
   switches and unreadable replies without replay.
3. Build the SDK-only HTTP adapter with subject-isolated discovery/sessions and
   bounded diagnostics. Spending-pool policy and missing public diagnostics need
   separately scoped decisions.
4. Add native HTTP/STDIO configuration, secret handling and process/foreground
   lifetime using that adapter and existing scopes/journals.
5. Verify two organization users, same-owner cross-client server tools, service-bound
   Applications and native instance/session isolation. Inject lost replies, expiry,
   revocation, schema changes, restart and cancellation. Real browser/native
   distribution acceptance is required before claiming shipped UX.

This design changes no runtime API, persistence, schema, tool grants, dependency
or navigation catalogue. Verification is source/contract review, documentation
links and repository checks; implementation and live acceptance are later increments.
