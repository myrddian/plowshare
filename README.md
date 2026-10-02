# Plowshare

Plowshare is a general-purpose agent framework with a Java server and shared
TypeScript clients. It brings conversations, scoped agents, durable memory,
documents and coordinated workflows into one system. The server owns the work
and its history; people connect through a terminal, desktop app, web console,
command-line client or MCP host.

It is intended for people building their own assistants and workflows, and for
developers who want to extend an agent system they can run themselves. Models,
tools, prompts and orchestration are configurable. The bundled bots, research
pipeline, coding workflows and swarm members are examples you can adapt.

**Status:** active development, currently `0.1.0-SNAPSHOT`. The repository has
automated runtime and client checks, but live model quality, research cost and
some end-to-end workflow acceptance remain open. Treat it as a developing
framework rather than a production-ready service.

## What you can build

- A research assistant that reads a document collection, investigates a question,
  retains supporting and contrary evidence, and produces a report for review.
- A project assistant that remembers decisions, retrieves earlier conversations
  and documents, and keeps recurring work connected to the same project.
- A group of specialist agents that discuss a question on a message board,
  request subtopics and return a cited resolution under a shared allowance.
- A coding assistant that inspects a workspace, drafts specifications, delegates
  reviews, edits files and runs commands under configured execution policy.
- A custom workflow in which JavaScript controls the sequence and agents provide
  bounded judgments, or a Markdown orchestration guides a model through stages.

These uses share the same definitions, permissions, history and model dispatcher.
They require the appropriate tools, providers and grants to be configured.

## How it fits together

```text
Terminal / Desktop / Web console / CLI / MCP host
                     |
           shared TypeScript client
                     |
          authenticated WebSocket operations
                     |
              Java / Spring Boot server
               /          |          \
     PostgreSQL +     model pools     workspace providers
       pgvector       chat + embeds   server or connected client
```

The system is remote-first: the server, model endpoints and client workspace can
live on different machines. Clients do not launch a server or database. A
connected client can explicitly lend a local directory through a separate file
channel; the server also supports server-side workspaces and optional Git-backed
synchronization. Authentication, binary upload and Git object transfer retain
their documented HTTP paths. A Java client is also retained in the repository.

An **agent** is a Markdown definition with a prompt, model selection, declared
tools, file scopes, delegation grants and limits. A **bot** is the human-facing
definition you converse with; it can delegate to specialists or start workflows
when granted those capabilities. A **project** supplies a workspace, membership
and a home for definitions, conversations and memory. Agents and bots can resolve
from shipped, server, project or connected-session definitions.

## Capabilities

**Durable conversations and memory.** Conversations retain messages, model calls,
tool activity and fold summaries. Trajectory views expose the recorded work;
lexical, semantic and hybrid search retrieve retained history. Learned memories
and digest navigation connect lessons to their source spans, with explicit
coverage and incomplete-result reporting. See [conversation retrieval](docs/conversation-retrieval.md)
and [memory digests](docs/memory-digests.md).

**Information and documents.** Sources have retained bytes, immutable revisions,
extracted text and exact evidence references. The library supports personal,
project and explicitly shared information, collections, retrieval, document
questions and draft reports. Processing, retry and rebuild are durable; report
access follows its inputs. Finalization, sharing and deletion are owner actions.
See the [information-system guide](docs/information-system.md).

**Deep research.** The bundled `deep_research` JavaScript workflow proposes
objectives for human review, retrieves and retains sources, develops findings,
seeks counter-evidence, records adjudications and assembles a draft report.
Feedback produces a new report revision. It requires a working search provider
and configured model/embedding services. Recorded judgments are reviewable
assessments, not guarantees of factual correctness. See [scripted research](docs/scripted-research.md).

**User-defined orchestration.** Markdown definitions describe stages, tools,
delegates, checks and allowances. JavaScript definitions implement a durable
`step(input)` state machine in the server sandbox: code chooses the next command,
and delegated agents can supply model judgments. Both use the existing grants,
hooks and execution budgets. Scripts have no Node.js, filesystem or direct
network APIs. See the [JavaScript orchestration guide](docs/scripted-orchestrations.md)
and [bundled definitions](plowshare-server/src/main/resources/orchestrations).

**Message-board swarms.** A project or server `swarm.md` selects member agents and
a root model-call budget. Topics retain messages, member conversations, approved
subtopics and resolutions; a scheduler shares configured swarm slots across
accounts, topics and members. Current members can read, research and post board
messages/documents. Definitions with file mutation, command execution, memory
writes or agent delegation are refused as swarm members. The
[bundled swarm](plowshare-server/src/main/resources/global/swarm.md) is an example.

**Execution and automation.** Jobs have inspectable status, cancellation and
bounded model-call allowances. Schedules emit events; triggers start configured
work and deliver results through conversations or an account inbox. Model pools,
system work and usage accounting are server-owned. Project and local hooks can
gate work. File access is fenced on both sides of the file channel; command
execution has separate environment policy and approval controls. An allowed
command runs with its process's OS privileges: the path fence is not an OS
sandbox.

## Quick start from a checkout

If you already have a server, skip server setup and connect a client. For native
macOS Apple Silicon client bundles and the separate server archive, use
[distribution instructions](docs/distributions.md). For a persistent Linux
server with PostgreSQL and optional search, use the
[Docker deployment guide](deploy/docker/README.md).

### 1. Prepare dependencies and configuration

Source builds require **Java 21**, **Node.js 22.12+** and **pnpm 10.34.5** on
`PATH`. Use the committed Gradle wrapper; Gradle installs each TypeScript module's
dependencies with its frozen lockfile. Initial builds need network access.

The server needs a PostgreSQL database with **pgvector available**; the Docker
deployment uses PostgreSQL 16. Provision a database and role named `plowshare`, or
supply your own connection settings. The role must be able to run the Flyway
migrations, including `CREATE EXTENSION vector` unless it is already installed.
Migrations run at server startup.

Configure chat and embedding endpoints before doing agent work. The shipped
configuration has a `studio` pool for fast chat and embeddings and a `spark` pool
for reasoning chat and swarm work. Supply the actual model IDs served by your
endpoints; model classes and pool listings must agree. For example:

```sh
export PLOWSHARE_DB_URL=jdbc:postgresql://localhost:5432/plowshare
export PLOWSHARE_DB_USER=plowshare

export LLM_BASE_URL=http://127.0.0.1:1234/v1
export LLM_PROVIDER=openai
export LLM_CHAT_MODEL=your-fast-model
export LLM_EMBEDDING_MODEL=your-embedding-model

export SPARK_BASE_URL=http://127.0.0.1:8000/v1
export SPARK_CHAT_MODEL=your-reasoning-model
export SYSTEM_MODEL=reasoning
export PLOWSHARE_ADMIN_HANDLE=admin
```

Replace the example endpoints and model IDs. Use `LLM_PROVIDER=lmstudio` for an
LM Studio endpoint. The shipped database vectors are 768-dimensional; use a
compatible embedding model and review its input limits. The two chat classes
can use the same model service, with pool capacity configured for that service.

Supply `PLOWSHARE_DB_PASSWORD`, an initial `PLOWSHARE_ADMIN_PASSWORD`, and any
`LM_STUDIO_API_KEY` / `SPARK_API_KEY` through your deployment environment or the
launcher's mode-0600 `~/.config/plowshare/secrets.env` file. This file is sourced
as shell assignments and belongs outside the checkout. The initial admin password
must be nonblank and pass the server's placeholder checks. First login requires
a password change; later boots preserve the account.

The [shipped application configuration](plowshare-server/src/main/resources/application.yml)
is the source of truth for defaults. Use a Spring configuration overlay for a
different pool layout or advanced settings; the
[example overlay](bin/application-local.example.yml) demonstrates its structure.

### 2. Build and start the server

Run from the repository root, with the configuration above in your environment:

```sh
./gradlew :plowshare-server:bootJar :plowshare-cli:assemble :plowshare-tui:pnpmTypecheck
bin/plowshare --no-build
```

The server binds to `127.0.0.1:8091` with authentication enabled. The web console
is included in the server jar and served at `http://127.0.0.1:8091/`. The launcher
uses `plowshare-server/data` for persistent filesystem state by default; use
`--data-dir /absolute/path` to choose another location. PostgreSQL holds the
durable database state. Preserve both when upgrading.

`bin/plowshare --help` lists server flags. Remote clients need a reachable bind
address and a secured deployment endpoint; use the deployment guide to configure
that installation.

### 3. Sign in and connect

In another terminal, from the repository root:

```sh
bin/plowshare-cli --url http://127.0.0.1:8091 login
bin/plowshare-talk --url http://127.0.0.1:8091
```

Login prompts for the account and password and handles the required initial
password change. CLI, TUI, MCP and desktop share saved sessions for the same server
origin; passwords are not saved. See [shared login](docs/client-login.md).
The terminal talks to the configured default bot; `--agent NAME` selects another
served definition, and `/help` lists terminal commands.

To open the development desktop, run `./gradlew :plowshare-desktop:assemble`,
then `bin/plowshare-desktop`. Choose **Connect server**; it starts with an offline
demo. See the [desktop guide](plowshare-desktop/README.md). For an MCP host, follow
the [MCP adapter guide](plowshare-mcp/README.md).

### 4. Work in a project

A project can hold research or documents as well as source code. To define one
with a directory that already exists **on the server**, then converse in it:

```sh
bin/plowshare-cli project define '{"name":"research","workspace":"/absolute/server/workspace"}'
bin/plowshare-talk --project research
```

The workspace path is resolved by the server. To lend a directory on a client
machine instead, use an explicit rooted connection for an existing project:

```sh
bin/plowshare-cli --project research --root /absolute/client/workspace \
  --timeout-ms 3600000 client root
```

Keep that process connected while the server uses its files. Headless local
commands default to off. The [CLI guide](plowshare-cli/README.md) covers project
administration, rooting, synchronization, job observation and offline `--validate`.
Use `/board` and `/swarm` in the TUI to inspect a project's discussion and members.

## Customize and contribute

Start with a [bundled agent](plowshare-server/src/main/resources/agents),
[bot](plowshare-server/src/main/resources/bots) or orchestration. Server definitions
live under `<data-dir>/global`; project definitions under
`<data-dir>/projects/<numeric-project-id>`. Connected workspaces can supply
eligible session definitions under `.plowshare/`. Declare the tools, delegates
and orchestrations you want to grant explicitly; installing a definition does
not grant every caller permission to use it. The
[complete script example](docs/examples/scripted-orchestrations/catalogue_inventory.js)
is a small starting point for code-controlled orchestration.

The main implementation areas are `plowshare-server` (runtime and services),
`plowshare-protocol` (Java contracts),
[plowshare-client-ts](plowshare-client-ts/README.md) (shared transport and operation
contracts), [plowshare-client-node](plowshare-client-node/README.md) (credentials,
files and platform services), and the client modules. Search adapters live in
`extensions/` as separate services.

Run `./gradlew check` for the repository's Java and TypeScript checks. Database
tests use Testcontainers and require Docker; contract checks also require
Python 3. Native distribution checks are opt-in and documented separately.
Changes to capabilities should update the shared contracts and relevant clients.


## License

Plowshare is licensed under the [Apache License 2.0](LICENSE).
