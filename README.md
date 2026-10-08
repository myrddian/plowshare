# Plowshare

Read the [Plowshare manual](docs/manual/README.md) for everyday use, functional
guides and architecture. Its [Library installer](docs/manual/installation.md)
retains the Markdown chapters in the server's information system for users and agents.

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

See [server administration](docs/server-administration.md) for account creation, roles, password recovery, session revocation and audit history across the CLI, TUI and desktop.

## First server setup

The server's first start prints a temporary `admin` account and a random password.
That account can only complete setup. From a client machine, run:

```sh
plowshare-cli setup --url http://your-server:8091
```

Enter `admin` and the temporary password, then choose the first administrator's
handle and a unique password of at least 12 characters. Setup consumes the
previous account and saves the new login for the CLI, TUI and desktop. The
administrator and projects persist in the server database. Restarting an
unfinished setup rotates the temporary password; a completed setup never
creates another temporary account. Existing password accounts keep their
administrator role when upgrading.

A server-side project is a scope of work managed by Plowshare's agent framework.
It groups conversations, memory, agent and orchestration definitions, skills,
and retained information. A server workspace lets its agents create and update
files as part of that work. The project remains available after clients disconnect.

Integrations use the SDK to submit agentic or information tasks to the framework
in a project's scope. The [outbound A2A adapter](integrations/a2a/README.md) uses that
SDK. The [Home Assistant binding](integrations/home-assistant/README.md)
uses selected state observations and configured actions through the integration
runtime; a `home-assistant` project can hold its skills, generated files and ongoing tasks.

Project skills live in the server data tree at
`projects/<project-id>/skills/<skill-name>/SKILL.md`; see
[Skills and agent rules](docs/skills-and-agent-rules.md). Skills still require
explicit grants and invocation. Server schedules can drive ongoing work.

An administrator can provision server files with:

```sh
plowshare-cli project create '{"name":"home-assistant"}'
```

For a pipeline-managed checkout, use `type: "DISJOINT"`, an existing server
`workspace`, and explicit `writePaths`, such as `["generated", "reports"]`.
DISJOINT never syncs to clients and defaults to no server workspace writes.
The TUI supports `/project create`; the desktop offers **Add server project**.
`/admin status` shows the server role. Project membership controls who can use
the framework scope.

The `.plowshare/project` file identifies a checkout by project name. Clients
can recognize a checkout of a server project without moving its server files
or enabling sync. See [Project creation, permissions and checkout discovery](docs/projects.md)
for CLI/TUI/GUI instructions, pipeline ownership and writable-area examples.

Authentication and setup use HTTP before a WebSocket can be opened; the status-only
`/ready` probe is an operational HTTP exception. Project
administration uses the authenticated WebSocket. The search-provider helpers
use the saved administrator login for operational HTTP endpoints that have no
WebSocket contracts. Build the CLI before using `deploy/docker/register-search.mjs`; `bin/plowshare-searxng` only starts the adapter. First-run setup is enabled by
the server entrypoint (`plowshare.auth.first-run-setup`). It replaces the legacy
startup operator-token bypass. Deployments explicitly opting out retain the
legacy bootstrap mechanism; configured `PLOWSHARE_ADMIN_HANDLE` and
`PLOWSHARE_ADMIN_PASSWORD` still support initial provisioning with mandatory
password rotation.

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

**Message-board swarms.** Named definitions in an Application’s root `swarm/` select member agents and
a root model-call budget. Topics retain messages, member conversations, approved
subtopics and resolutions; a scheduler shares swarm slots across
accounts, topics and members. Each pool defaults to half its chat slots, rounded
down; an explicit `swarm` count overrides that default, and `swarm: 0` disables it.
Members run with their normal tools and grants,
including mutation, execution and delegation when permitted. The default limit per wake is 12 model steps, independently of each agent's
ordinary turn limit. In the desktop, a failed member offers **Retry member…** with
an editable step limit; it continues the same conversation within the shared
remaining budget. Board tools read the current topic with `board_read({})`;
explicit topic arguments use `bdt_` IDs. The
[bundled swarm](plowshare-server/src/main/resources/global/swarm/default.md) is an example.

**Agent and bot messaging.** `send_message` addresses a persistent instance or a
fresh task instance. The board transport durably queues recipient wakes, supplies
return addresses, and generates an outcome reply when an expected final reply is
missing. The harness controls visibility and retains document-input restrictions.
See [agent messaging](docs/agent-messaging.md).
For project-to-project delivery, route files and `Personal:<account-name>`
addresses, see the [Internal project messaging manual](docs/internal-messaging.md).
Routes can target an existing conversation with `conversation`, or create one
dedicated log on first use with `retainConversation: true`. Retained route logs
survive restarts; routes without either option keep the shared default behavior.

**Relay.** The internal broker provides scoped publication logs, independent
subscriber positions, per-topic retention and explicit gaps. Project packages
separate pure JavaScript routing from optional durable handlers. Fan-out branches
pin input and source and dispatch asynchronously to agents, orchestrations or
same-project topic forwarding, with fenced intents and owning receipts.
Configuration supports server projects, owner-only Personal unions and
authenticated remote workspaces. Scoped WebSocket operations, SDK/CLI support and
the desktop log viewer expose policy, events, offsets, delivery status and owned
trajectories. Explicit server project/account bindings enable independent automatic
subscription workers with distributed leases; manual processing uses the same
ownership rules. Local lifecycle supervision has no central dispatcher. The scheduler publishes through
Relay with atomic firing admission. Manager controls expose explicit gap
acknowledgement, receipt reconciliation, abandonment and removal of inactive empty
metadata through the SDK, CLI and desktop log. Lifecycle publishers use a durable
outbox; messaging and Board wake availability use private topics and independent
conversation consumers. Their owning inboxes preserve continuations and receipts.
See the [Relay manual](docs/relay.md) and
design and migration plan.

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

Source builds require **Java 21**, **Node.js 22.13+** and **pnpm 10.34.5** on
`PATH`. Use the committed Gradle wrapper; Gradle installs each TypeScript module's
dependencies with its frozen lockfile. Initial builds need network access.

The server needs a PostgreSQL database with **pgvector available**; the Docker
deployment uses PostgreSQL 16. Provision a database and role named `plowshare`, or
supply your own connection settings. The role must be able to run the Flyway
migrations, including `CREATE EXTENSION vector` unless it is already installed.
Migrations run at server startup.

Configure chat and embedding endpoints before doing agent work. The packaged
configuration supports one generic endpoint serving both models, with explicit
model IDs and conservative capacity bounds. It assumes no hardware or model
family. `fast` and `reasoning` initially select the same chat model. Use an
external Spring overlay to place models on separate endpoints or add more pools.
This explicitly configured local development example assumes the services below
are running on your machine:

```sh
export PLOWSHARE_DB_URL=jdbc:postgresql://localhost:5432/plowshare
export PLOWSHARE_DB_USER=plowshare
export PLOWSHARE_PORT=8091
export PLOWSHARE_BIND=127.0.0.1
export PLOWSHARE_DATA_DIR="$PWD/data"
export PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY="$PWD/workspaces"

export LLM_BASE_URL=http://127.0.0.1:8000/v1
export LLM_PROVIDER=openai
export LLM_CHAT_MODEL=your-chat-model
export LLM_EMBEDDING_MODEL=your-embedding-model
# Set these for the actual service capacity; swarm work needs spare chat capacity.
export LLM_CHAT_SLOTS=2
export LLM_SWARM_SLOTS=1
```

Replace the example endpoints, paths and model IDs for your deployment. Required
connection and listener values have no production fallback. Use
`LLM_PROVIDER=lmstudio` only for an LM Studio endpoint. The single-embedder
compatibility example uses 768-dimensional legacy vectors. For independent code
and prose embedders, use the [dual embedding configuration](docs/embedding-evolution.md#enabling-the-two-slots)
and [optional single-pool dual-embedding example](bin/application-dual-embeddings.example.yml).
Both slots support 1–16,000 dimensions in the same permanent layout, with seven
fixed retrieval modes and background re-embedding when their model changes.

Supply `PLOWSHARE_DB_PASSWORD` and any
`LLM_API_KEY` (or API-key variables named by your overlay) through your deployment environment or a protected external configuration file. The public launcher does not discover
or execute environment/credential files. Leave the admin seed
variables unset to use first-run CLI setup. Explicit environment provisioning
with `PLOWSHARE_ADMIN_HANDLE` and `PLOWSHARE_ADMIN_PASSWORD` remains available;
those accounts must change their password on first login.

The [shipped application configuration](plowshare-server/src/main/resources/application.yml)
is the source of truth for defaults. The
[minimal configuration example](bin/application-local.example.yml) uses one pool
for chat and embeddings. Launch it with
`bin/plowshare --config /absolute/path/application.yml`. Different model endpoints
or capacities belong in your own configuration. Spring replaces pool lists, so a
custom list must contain every pool you intend to serve. Multi-pool configuration
is optional and is never chosen by a shell script. Installation-specific
configuration discovery, credential-file loading and host provisioning belong
in your deployment tooling or private overlay.

To tune prompt latency for your hardware, set `max-context-lengths` inside each
pool, keyed by its served wire model name. For example, add this to a pool that
serves `openai/gpt-oss-120b`:

```yaml
max-context-lengths:
  "[openai/gpt-oss-120b]": 65536
```

The effective context is the smaller of the model's reported or configured
capacity and this maximum. When capacity is unknown, the maximum caps
`default-context-length`. Folding and the clients' context meters use that same
ceiling. Smaller budgets fold earlier; the full recorded history stays available.
This is a working-context budget: a large new message or tool result can overshoot
before the next fold boundary. `context-lengths` still declares capacity for
endpoints that cannot report it; changing `default-context-length` alone does not
cap a model with a known capacity. Spring replaces the whole pool list in an
overlay, so retain every pool you want to serve.

`plowshare.llm.prompt-timeout` sets one wall-clock chat prompt timeout across all
pools, for both blocking and streaming calls, including silent prefill. Leave it
unset to retain the existing pool limits. `plowshare.llm.fold-timeout` is also
unset by default, so folds use the existing streaming limits: five minutes of
inactivity and ten minutes total, unless the pool configures different values.
An explicit fold timeout uses the larger of its value and the general prompt
timeout; a smaller fold value emits a prominent startup warning. With no global
prompt override, the comparison uses the folding pool's `max-stream-duration`.
Optional environment overrides are:

```sh
export PLOWSHARE_LLM_PROMPTTIMEOUT=120s
export PLOWSHARE_LLM_FOLDTIMEOUT=10m
```

These are inference budgets; the pool's `submit-timeout` separately bounds its
queue wait. Configuration changes take effect at server restart.

### 2. Build and start the server

Run from the repository root, with the configuration above in your environment:

```sh
./gradlew :plowshare-server:bootJar :plowshare-cli:assemble :plowshare-tui:pnpmTypecheck
bin/plowshare --no-build
```

The server binds to `127.0.0.1:8091` with authentication enabled. The web console
is included in the server jar and served at `http://127.0.0.1:8091/`. The launcher
uses the explicitly configured `PLOWSHARE_DATA_DIR` for persistent filesystem state. PostgreSQL holds the
durable database state. Preserve both when upgrading.

`bin/plowshare --help` lists launcher options. Use `--` to pass Spring arguments.
The `bin/plowshare-deployment` name is a compatibility alias; it does not load
a personal deployment file. Remote clients need a reachable bind
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

To build and open the development desktop, run these from the repository root:

```sh
./gradlew :plowshare-desktop:assemble
bin/plowshare-desktop
```

The build installs desktop and shared SDK dependencies automatically. Its pnpm
install can recreate an incompatible `node_modules` directory without a terminal
prompt, using the committed lockfile. Choose **Connect server**; the app starts
with an offline demo. See the [desktop guide](plowshare-desktop/README.md). For an
MCP host, follow the [MCP adapter guide](plowshare-mcp/README.md).

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

## Personal space

Each account has a private Personal space, separate from Projects. New conversations
and standalone agent/orchestration runs without an explicit project use Personal.
Global remains the shared setup and resource scope; global conversation history is
read-only. On upgrade, owned global conversations move to their owner's Personal
space without changing their ids or logs.

Personal is a server-created union. The desktop mounts it automatically at
`~/.plowshare/personal`; the TUI mounts it when started outside a project. Its desktop
navigation has **In**, **Out**, **Resources**, **Archive**, **Planning**, and **Bots**.
The files can be edited locally or through the normal project file tools.

Put reusable skills in `Resources/skills/<name>/SKILL.md`, agents in
`Resources/agents`, orchestrations in `Resources/orchestrations`, hooks in
`Resources/hooks`, and bots/default-bot selection in `Bots`. These definitions
follow the account into its projects. A project's definitions and rooted session
definitions take precedence over Personal, then global and shipped definitions.
Personal hooks run before the project's and session's hooks; ordinary capability
and execution grants still apply.

See [the Personal space contract](docs/personal-space.md)
for storage, ownership, migration and synchronization details.

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
[plowshare-client-ts](sdk/typescript/README.md) (shared transport and operation
contracts), [plowshare-client-node](sdk/node/README.md) (credentials,
files and platform services), and the client modules. All SDK variants live in
[sdk/](sdk/README.md). A2A, Home Assistant and their shared runtime live in
[integrations/](integrations/README.md). Services that extend Plowshare, including
search providers, live in `extensions/`.

Read [AGENTS.md](AGENTS.md) and the [coding standards](docs/coding-standards.md)
before contributing. System boundaries use interfaces, specialist repositories
own JDBC access, and comments explain contracts and non-obvious invariants.
Run `./gradlew format` to apply Google Java Style, or `./gradlew formatCheck` to
check formatting without editing files.

Run `./gradlew check` for the repository's Java and TypeScript checks, including
Java formatting. Ordinary `test` and `check` runs exclude database tests and do
not require Docker. Use `./gradlew check -PfullDb` to include PostgreSQL tests;
these use Testcontainers and require Docker. Contract checks require Python 3.
See the [build guide](docs/manual/15-building-and-extending.md) for focused commands.
Native distribution checks are opt-in and documented separately.
Changes to capabilities should update the shared contracts and relevant clients.


## License

Plowshare is licensed under the [Apache License 2.0](LICENSE).

Language integrations use the [SDKs and roadmap](docs/sdks.md): JS/TS, Java/JVM, Python, C# and Go. Messaging and Skills are implemented; the [A2A receiving manual](docs/a2a-receiving.md) configures inbound text messages and durable tasks.

Java integrations use [plowshare-sdk](sdk/java/README.md). The [A2A adapter](integrations/a2a/README.md) runs on that SDK and communicates with Plowshare over WebSocket.

Use the [A2A sending manual](docs/a2a-sending.md) to configure a remote peer, send from the CLI, SDK or an agent, follow results and request cancellation.
