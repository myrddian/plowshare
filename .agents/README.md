# Plowshare subsystem map

Use this as a wiki index, not a reading list. Start with the code being changed
and read only the matching guide. Scoped `AGENTS.md` files route work near its
owner; the root routing table covers tests and changes that cross directories.

| Working on | Guide |
| --- | --- |
| Permissions, identities, roles, ACLs, admission or disclosure | [AUTH](guides/auth.md) |
| JDBC, SQL, migrations, caches or read capacity | [Persistence and cache](guides/persistence-cache.md) |
| Backpressure, admission queues, saturation or capacity retries | [Backpressure](guides/backpressure.md) |
| Agent execution, conversation history/model context, inference or usage | [Runtime and inference](guides/runtime-and-inference.md) |
| Documents, knowledge, retrieval or processing | [Information](guides/information.md) |
| Memory storage, recall, embeddings or learning | [Memory](guides/memory.md) |
| Orchestration, schedules, events, swarm or attention delivery | [Workflows](guides/workflows.md) |
| Agent messaging, Relay, subscriptions or delivery recovery | [Messaging and Relay](guides/messaging-relay.md) |
| Application lifecycle, workspaces, files or Personal | [Applications and files](guides/applications-files.md) |
| WebSocket dispatch, shared contracts or SDKs | [Transport and SDKs](guides/transport-sdks.md) |
| CLI, TUI, console, Desktop or MCP client surfaces | [Clients](guides/clients.md) |
| External adapters and their application runtimes | [Integrations](guides/integrations.md) |
| Packaging, releases, live configuration or deployment | [Deployment and operations](guides/deployment-operations.md) |
| Coordinating a change with multiple deliverables | [Delivery and review](delivery-and-review.md) |

## How to use a page

Each guide gives a mental model, source entry points, non-obvious rules, legacy
traps and relevant checks. Follow additional links only when the task needs them.
The pages are engineering guidance; they are not Plowshare runtime agent
definitions, skill packages or an additional authorization mechanism.

Code and representative tests describe the implementation. These guides state
the current contract and explain constraints that code searches can miss. If
they disagree, identify the discrepancy against the owning implementation and
accepted decisions; do not copy a legacy path or silently invent a new policy.

ADRs retain decision history, including proposals and superseded sections.
Plans and rollout reports retain evidence for a particular revision. Neither is
automatically a statement of current behavior. Follow their current-contract
notices, and preserve still-applicable decisions when updating history links.

When changing a subsystem's contract, update its guide and relevant source
comments in the same change. Keep guides concise and link to existing standards
and manuals rather than duplicating them. Keep host details, credentials,
diagnostic dumps, historical test counts and rollout receipts out of these pages.
