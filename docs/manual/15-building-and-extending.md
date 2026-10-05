# Build and extend Plowshare from source

Use this chapter when you want a development checkout, a custom client/adapter,
or a runtime contribution. To use a server someone already operates, start with
[First steps](01-first-steps.md). To run a persistent container deployment, use
the [Docker guide](../../deploy/docker/README.md) with its separate state,
configuration and image workflow.

An agent definition, skill, hook or orchestration is usually the smallest way to
add behavior. Those use the existing server primitives and do not require a new
transport or database table. An SDK adapter exposes those capabilities to another
application. A server change is appropriate when a new durable capability or
operation contract is needed.

## Prepare the toolchain

Source builds require Java 21, Node.js 22.12 or newer, pnpm 10.34.5 and Python 3
on `PATH`. Use the committed Gradle wrapper. Initial builds download dependencies;
TypeScript module tasks install with their frozen lockfiles. Docker is needed for
database integration tests and container image builds, rather than for every
Markdown or TypeScript edit.

```sh
java -version
node --version
pnpm --version
python3 --version
./gradlew --version
```

Read [AGENTS.md](../../AGENTS.md) and the
[coding standards](../coding-standards.md) before changing implementation. Check
`git status` so local configuration, generated output and another person's work
are not accidentally included in the change. Runtime data and credentials stay
outside the contribution.

## Build the parts you need

Run these from the repository root:

| Goal | Gradle entry point |
| --- | --- |
| Server executable jar, including the console | `:plowshare-server:bootJar` |
| CLI and its client dependencies | `:plowshare-cli:assemble` |
| TUI type checking | `:plowshare-tui:pnpmTypecheck` |
| Desktop development build | `:plowshare-desktop:assemble` |
| Shared Node SDK | `:plowshare-client-node:nodeBuild` |
| A2A adapter distribution | `:plowshare-a2a:installDist` |
| Whole repository verification | `check` |

A useful initial build is:

```sh
./gradlew :plowshare-server:bootJar :plowshare-cli:assemble :plowshare-tui:pnpmTypecheck
```

The build follows declared dependencies, including the shared TypeScript client
and console where required. A successfully compiled jar does not establish a
working database, provider or authenticated server. SDK-only clients can be built
without starting Plowshare. Native client and desktop archives have separate
platform/toolchain and signing requirements in [Distributions](../distributions.md).

## Configure a development server

Prepare a PostgreSQL database with pgvector available. The shipped deployment uses
PostgreSQL 16; its application role needs migration authority, including permission
to install `vector` unless the extension is already installed. Flyway applies the
shipped migrations at server startup. Do not point a disposable development
experiment at your only production database.

Configure actual chat and embedding providers. Logical bindings such as `fast`
and `reasoning` select served model IDs; an agent's `model: fast` is not itself a
provider endpoint. The single-embedder compatibility example below uses the
legacy 768-dimensional columns. The [dual embedding runtime](../embedding-evolution.md)
supports independent code/prose models at 1–16,000 dimensions, controlled index
choices and a durable rebuild before activation. Configure both models explicitly
when enabling it; changing model width does not require another table migration.

The following loopback values illustrate configuration for services on the same
host. Replace model IDs and endpoints with what your development services serve:

```sh
export PLOWSHARE_DB_URL=jdbc:postgresql://localhost:5432/plowshare
export PLOWSHARE_DB_USER=plowshare
export LLM_BASE_URL=http://127.0.0.1:1234/v1
export LLM_PROVIDER=openai
export LLM_CHAT_MODEL=your-chat-model
export LLM_EMBEDDING_MODEL=your-embedding-model
export LLM_CHAT_SLOTS=2
export LLM_SWARM_SLOTS=1
export SYSTEM_MODEL=reasoning
```

For legacy embedding mode, provide the matching model's local `tokenizer.json`
and its SHA-256 in the private Spring overlay:

```yaml
plowshare:
  llm:
    embedding-tokenizer:
      file: ${LLM_EMBEDDING_TOKENIZER_FILE}
      sha256: ${LLM_EMBEDDING_TOKENIZER_SHA256}
```

Both named embedding slots instead require their own pinned tokenizer files,
including when their dimensions are identical. Missing configuration refuses
embedding and derivation rather than using a character estimate. See the
[embedding input contract](../embedding-evolution.md#input-tokenization-and-shared-chunks).

Supply the database password and provider keys through your private environment
(`LLM_API_KEY` for the generic pool) or the launcher's mode-0600 `~/.config/plowshare/secrets.env`. That file is sourced
as shell assignments; it belongs outside the checkout. Keep authentication on
and complete normal first administrator setup. Use one separate development data
directory, and preserve it if you want to keep the work.

The packaged application settings and your private Spring overlay determine the
effective pool layout. The generic packaged pool uses explicit endpoint/model
values and maps both chat classes to the same model. Use
`bin/plowshare --config /absolute/path/application-local.yml` for a different topology; see the
[example overlay](../../bin/application-local.example.yml). A list overlay replaces
the whole pool list; retain every
pool you intend to serve. Pool capacity, model context and timeouts have different
purposes. Review [Server administration](09-server-administration.md) before
changing them.

## Start, connect and check one real path

After building, start the source launcher in one terminal:

```sh
bin/plowshare --no-build --data-dir /absolute/development/plowshare-data
```

Replace the absolute path with your private development storage. The launcher
defaults to `127.0.0.1:8091` and otherwise uses `plowshare-server/data` when no
data directory is selected. The browser console is served from the server root.
Only one process should own a particular accounting journal; stop the existing
owner gracefully before replacing it.

In a second terminal, complete setup on a fresh server, or log in to an existing
account:

```sh
bin/plowshare-cli setup --url http://127.0.0.1:8091
```

```sh
bin/plowshare-cli login --url http://127.0.0.1:8091
bin/plowshare-talk --url http://127.0.0.1:8091
```

Choose the setup or login path for the server's current state. Have one small
conversation in Personal, inspect the served roster, and check a bounded operation
relevant to your change. `/ready` is a status probe; also verify authentication,
WebSocket operations and persistence. Model-backed acceptance requires an actual
configured provider, while a fixture test can verify only its simulated contract.

For the development desktop:

```sh
./gradlew :plowshare-desktop:assemble
bin/plowshare-desktop
```

Select **Connect server** from the initial offline demo. CLI, TUI, MCP and desktop
share saved sessions for the same origin. They do not share a session across
different origins merely because those URLs point to the same host.

## Choose the extension boundary

| Change | Start with |
| --- | --- |
| New role, prompt or bounded tool combination | Agent/bot Markdown definition |
| Reusable explicitly invoked instructions | Skill package and its caller grants |
| Repeated staged procedure | Markdown or JavaScript orchestration |
| Lifecycle policy, validation or notification | Hook at the documented callback tier |
| Account-owned internal asynchronous work | Messaging instance and project route |
| External observations/actions | Integration runtime or a narrow SDK adapter |
| External agent interoperability | A2A adapter and incoming/outgoing contracts |
| New server-owned durable behavior | Owning server domain, typed operation and repository |

Use the full [orchestration](11-orchestration-authoring.md),
[hook](12-hooks.md), [routing](13-messaging-and-routing.md) and
[swarm](14-swarm-board.md) walkthroughs for deployable examples. Place definitions
in the documented resolver tier and inspect their effective roster in the same
project/session where they will run. A locally edited file is not automatically
the effective definition on a remote server.

For a new SDK connector, first choose the operations that implement one useful
user path: authenticate, select an authorized project, admit work, retain its
receipt, follow the durable outcome, and return it to the correct external user.
Keep the external identity mapping and reply destination explicit. Discord support
would still need that application-specific mapping, cancellation and permission
policy even though the SDK already supplies the server transport.

The [SDK guide](../sdks.md) describes each language's operation coverage and build
checks. Use the typed contract that exists. Operational requests use authenticated
WebSockets; HTTP authentication, uploads and external protocol boundaries retain
their own documented exceptions. Never recover an uncertain mutation by silently
switching transports or inventing a new request ID.

## Make a runtime contribution

Find the owning module through [Architecture](03-architecture.md). Define a narrow
interface for the capability, implement it in its domain, and inject that interface
into consumers. Specialist repositories own Spring JDBC queries, row mapping and
atomic persistence. Controllers, tools, adapters and orchestration code consume
typed domain operations rather than tables or a JDBC template.

Validate boundary inputs, length/count/range constraints and authenticated
ownership. Bind SQL values. A valid identifier does not prove access, and a client
scope assertion is not authentication. Use the existing project membership,
information audience, workspace fence and tool-grant checks appropriate to the
operation.

For durable work, specify admission, stable identity, cancellation, deadlines,
uncertain delivery and restart recovery before adding execution. Keep migrations
append-only. Distinguish an accepted request from a successful result; document
what can be repeated and what needs reconciliation. Comments should explain those
contracts and the transaction or concurrency invariant they protect.

Update operation contracts and affected clients together. Generated schemas and
catalogues come from their owning generator scripts. Read the module's build and
generator instructions before changing generated output; do not manually patch a
schema to hide a mismatch.

## Verify the change

Apply Google Java formatting after Java edits, then run the relevant tests:

```sh
./gradlew format
./gradlew formatCheck
./gradlew :plowshare-server:test --tests 'fully.qualified.RelevantTest'
```

Replace the final class name with the test owning your change. Java formatting
uses the default Google style with two-space indentation. It does not format
TypeScript, Python, SQL or Gradle Kotlin source. Readability, comments, interfaces
and repository boundaries still need review.

Database integration tests use the actual PostgreSQL dialect through Docker/
Testcontainers. Preserve that coverage for authorization, persistence and durable
state transitions; a fake transport or model does not prove a live provider works.
Client changes also need their module's type checks and relevant behavior tests.

The manual has its own bounded validation and installer recovery tests:

```sh
./gradlew manualCheck
node scripts/install-manual.mjs --dry-run
```

Before handing off a broad repository change:

```sh
./gradlew check
git diff --check
```

`check` includes Java checks, formatting and registered client/contract checks.
Native distributions and live adapter/provider acceptance are separate entry
points. Record what actually passed and explain any remaining failures; do not
describe a partial check as a successful full build. A formatter pass proves
layout, while test evidence and review establish the behavior and design.

## Package and retain a matching manual

Use [Distributions](../distributions.md) for archives and the
[Docker guide](../../deploy/docker/README.md) for container images. Retain the
source revision, toolchain/configuration versions and checks used to build the
artifact. Runtime databases, provider credentials, personal files and local
journals stay outside artifacts.

Run the [Library installer](installation.md) from the intended documentation
checkout. An installed manual is a retained snapshot, so build/deployment changes
do not silently update the chapters users and agents are reading. Verify the
published revision and its intended audience before treating it as the current
server manual.
