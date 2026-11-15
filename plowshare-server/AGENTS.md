# Working in the server

Use the relevant source and tests first. Read only the guide matching the change;
the paths below include tests and resources for the same responsibility.

| Area under `src/*/java/io/aeyer/plowshare/server/` | Guide |
| --- | --- |
| `access`, `auth`, `acting`, `security`, `session`, or permission/admission/disclosure changes anywhere | [AUTH](../.agents/guides/auth.md) |
| `cache`, `pool`, `reads`, `data`, any repository SQL or `src/main/resources/db/migration` | [Persistence and cache](../.agents/guides/persistence-cache.md) |
| `agents`, `llm`, `allowances`, `harness`, `images`, `media`, `telemetry` runtime/accounting behavior; conversation log/entry/turn responsibilities in `archive` | [Runtime and inference](../.agents/guides/runtime-and-inference.md) |
| `information`, `documents`, `blobs`, `fetch`, `search` | [Information](../.agents/guides/information.md) |
| `embedding`, memory/recall/learning responsibilities in `archive` and `agents` | [Memory](../.agents/guides/memory.md) |
| `orchestrations`, `events`, `swarm`, `board`, `hooks`, `approvals`, `delivery`, `todos`, `lifecycle` | [Workflows](../.agents/guides/workflows.md) |
| `messaging`, `relay` | [Messaging and Relay](../.agents/guides/messaging-relay.md) |
| `applications`, `files`, `union`, `personal`, project/workspace responsibilities in `archive` | [Applications and files](../.agents/guides/applications-files.md) |
| `ws`, `api`, `requests`, request DTOs and transport routing | [Transport and SDKs](../.agents/guides/transport-sdks.md) |

Package location does not override ownership: e.g. memory persistence in `archive`
uses the memory contract as well as the repository rules. Internal adapters and
controllers must use the owning typed capability rather than execute SQL.
