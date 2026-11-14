# Persistent Docker deployment

This package runs Plowshare and PostgreSQL 16 with pgvector on one Docker host.
The SearXNG adapter and an optional SearXNG engine are separate layers. The
default server publishes only on loopback; select the host LAN address in your
deployment environment for remote clients. Authentication remains enabled.

Requirements: Docker Engine, Compose 2.30 or newer, and a deployment user with
UID 1000 (the UID used by both Java images). Prepare the host using
[Docker's installation instructions](https://docs.docker.com/engine/install/);
host provisioning belongs to your installation tooling. See [distribution instructions](../../docs/distributions.md).

## A2A general-purpose receiver

Add `compose.a2a.yaml` to run the separate A2A adapter beside the server. This
requires the general messaging Relay receivers and their migrations, plus explicit
command/return topics, grants and route configuration. Select one existing
project and one exported agent. `interlocutor` is the shipped general-purpose
entry point: its grants determine the skills, workflows and specialists it can
use. The public Agent Card advertises its granted skills and workflows; ordinary
messages are handled by that agent within the configured project.

Create an Application whose root is reachable **inside the server container**.
The overlay mounts a private host workspace at `/workspaces/a2a` in the server.
Create `${PLOWSHARE_STATE_ROOT}/workspaces/a2a` owned by UID 1000 before starting,
or set `PLOWSHARE_A2A_WORKSPACE_HOST` to an existing dedicated directory. Keep
workspaces outside the server-owned data/config trees, which file tools exclude.
Install the [A2A Application manifest](../../integrations/a2a/examples/application/plowshare.json)
as `/workspaces/a2a/plowshare.json`, preserving an existing registration's name.
Add explicit account grants and matching server membership; the template has no
implicit access. Register or adopt this root with a server FileStore alias whose
location is inside the container. Do not use the host's `/srv/plowshare/...` path
as the container location. Install agents/skills through the normal Plowshare
tools. See [receiving and Application setup](../../docs/a2a-receiving.md).

Store the following in `/srv/plowshare/config/a2a.json`, mode 0600, with your own
project and external address:

```json
{
  "origin": "http://server:8091",
  "tokenEnv": "PLOWSHARE_TOKEN",
  "principal": "REPLACE_WITH_VERIFIED_NATIVE_PRINCIPAL",
  "journal": "/var/lib/plowshare-a2a",
  "project": "a2a",
  "requests": "a2a.receive.commands",
  "replies": "a2a.receive.tasks",
  "group": "a2a-receiving-view",
  "consumer": "11111111-1111-1111-1111-111111111111",
  "replyPublisher": "messaging:ingress",
  "receive": {
    "bind": "0.0.0.0",
    "port": 8093,
    "publicUrl": "https://agent.example.invalid/rpc",
    "agent": "interlocutor",
    "waitMs": 30000,
    "clients": { "remote": { "bearerEnv": "A2A_CALLER_TOKEN" } }
  }
}
```

Create a service credential for the intended native principal and store it in a
private file; the sidecar does not perform account/password login. Put a distinct
external caller bearer in a second private file. Both files must be readable by
UID1000 and excluded from source. Clients receive only the caller bearer.

Set `PLOWSHARE_A2A_SERVICE_TOKEN_FILE`, `PLOWSHARE_A2A_CALLER_TOKEN_FILE`,
`PLOWSHARE_A2A_CONFIG_FILE` and `PLOWSHARE_A2A_JOURNAL_HOST` to the explicit host
files/durable directory. Create the journal directory mode0700 owned by UID1000;
the receiver's container path above matches the overlay's writable volume. Its
other filesystem remains read-only. The directory contains retained operation
receipts and immutable external provenance/reply bodies. Back up the complete
volume; deleting it can destroy uncertainty and ownership evidence.

Also set `PLOWSHARE_A2A_LISTEN_ADDRESS`, `PLOWSHARE_A2A_PUBLISHED_PORT` and
`PLOWSHARE_A2A_PORT`; the last must match `receive.port`. Set
`PLOWSHARE_A2A_HEALTH_URL` to the full HTTP(S) Agent Card URL reachable inside the
adapter container. Addresses and ports have no deployment defaults. Use your TLS proxy
for external access. Forward `/rpc`, `/.well-known/agent-card.json`, `Authorization`,
`A2A-Version`, and `A2A-Extensions`. The configured public URL must match the
address used by clients, including its port.

Build `:plowshare-a2a:installDist`, then the `a2a.Dockerfile` image, or use
`build-images.sh`. Set `PLOWSHARE_A2A_IMAGE` to a verified immutable image for a
release deployment. Add this overlay to the Compose files already in use:

```sh
docker compose --env-file /srv/plowshare/deployment/deployment.env \
  -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml \
  -f deploy/docker/compose.a2a.yaml config --quiet
```

Keep your existing immutable server/search image overlay in the deployment
command too. Use `up -d --no-deps a2a-receiver` after the receiver-capable server
is healthy; this does not replace other services. The sidecar reads its configuration/credentials and writes only its private
journal volume. Tasks and contexts remain authoritative in Plowshare's database.
Configure the [native route](../../docs/a2a-receiving.md#configure-the-native-relay-route)
with one shared command topic/subscription/channel for receive/cancel/inspect;
agent.list supplies its public command catalog. Existing historical contexts need
the server's explicit channel-adoption mapping rather than automatic rebinding.
After startup, validate discovery and an authenticated message round trip with
an independent client, such as Google ADK; a healthy container alone is not proof
of agent execution. The initial protocol subset is text, polling and cancellation.

The overlay uses bounded `on-failure:5` restart. Docker cannot exclude exit78
(operator action) like systemd can, so repeated configuration/storage failures stop
after that bound and need investigation. Receiving transport restarts reuse the
same journal and inspect uncertain requests; they never resubmit them blindly.
Secret rotation requires stopping the sole journal owner and running its explicit
`owner`/`rotate-config` CLI with matching principal/channel identity before restart.
See the [receiving manual](../../docs/a2a-receiving.md) for recovery, capacity and
supported response limits. No deployment or live credential rotation is performed
by these examples.

## Build

From a checkout with Java 21, Node 22.12+, pnpm 10.34.5 and Docker:

```sh
sh deploy/docker/build-images.sh
```

This is also the container build entry point for a CI runner. It builds the
console into the server jar, builds the search adapter jar and A2A distribution,
then creates three native images with an embedded Java 21 runtime. Dockerfile-specific context allowlists
include only the jar and launcher files; deployment configuration, keys, project
data and source files are excluded. The JRE and PostgreSQL images are pinned by
OCI digest. Application images carry the source revision as an OCI label.
Release builds should use a clean checkout.

When building directly on a Docker host without Java/Node, copy the two compiled
jars into their normal `build/libs` paths, the A2A distribution into
`integrations/a2a/build/install/plowshare-a2a`, and the `deploy/docker` directory,
preserving their checkout-relative layout. Set `PLOWSHARE_SOURCE_REVISION` to the
revision that produced those jars, then use `docker compose ... build` below.
There is no Gradle, Node or Java installation required on the runtime host.
The live deployment is validated on Linux amd64; Linux arm64 remains a separate
acceptance run. Registry publication and multi-host Swarm deployment are later
pipeline steps; these files target Docker Compose.

## Monitoring

The shipped telemetry is opt-in and has no default addresses.

- **PostgreSQL** always preloads `pg_stat_statements` and `auto_explain` (a restart is
  needed to change this). `pg_stat_statements` is optional: migration V259 creates its
  views only when the migration role is a superuser (the extension is not trusted) and
  otherwise logs a notice and continues. Only the exporter and
  `deploy/monitoring/pg-stat-snapshot.sh` read it; the server does not need it. Logging of
  statements slower than `PLOWSHARE_PG_SLOW_MS` and plans slower than
  `PLOWSHARE_PG_AUTO_EXPLAIN_MS` is **off by default** (-1). Enabling either writes
  SQL statement text to the PostgreSQL container log; bind parameters are never
  logged. PostgreSQL's default `log_min_error_statement=error` still logs the SQL text
  of failing statements, never their parameters (`log_parameter_max_length_on_error=0`).
- **Server metrics**: set `PLOWSHARE_MANAGEMENT_PORT` and `PLOWSHARE_MANAGEMENT_BIND`
  together in the server environment (an overlay adds them to the `server` service)
  to serve `/actuator/prometheus` on a separate listener; the entrypoint refuses one
  without the other. Compose does not publish that port. The endpoint has no
  authentication, so make it reachable only by Prometheus (shared network or an
  overlay `ports` entry bound to a private address).
- **PostgreSQL metrics**: `docker compose --profile monitoring up -d postgres-exporter`
  starts the pinned exporter. It publishes no host port and connects as its own
  role, not the owner. Before starting it, create the role once as the database
  owner (placeholder password only) and put the same password in
  `secrets/monitoring-database-password` under the state root:
  `CREATE ROLE plowshare_monitor LOGIN PASSWORD '<from the secret>'; GRANT pg_monitor TO plowshare_monitor;`
  (set `PLOWSHARE_MONITORING_DB_USER` to use another role name); Prometheus reaches it on the compose network at port 9187, or an
  operator overlay adds `ports`. Its custom queries export `queryid` (never statement
  text), counts, time, rows and buffer blocks for the top 50 statements by total
  time, plus table scan counters.
- **Flight recording**: set `PLOWSHARE_JFR_DIR` to a writable mounted directory to
  record continuously (6 hours or 512 MB, dumped on exit) with the settings in
  `deploy/monitoring/plowshare.jfc`, which add `plowshare.JdbcExecute` stack traces.
  `plowshare.jfc` was derived from the JDK 17 `profile.jfc` and can be regenerated
  from a Java 21 JDK.
- **Files**: [scrape example](../monitoring/prometheus-scrape.example.yaml),
  [Grafana dashboard](../monitoring/grafana-plowshare.json) (import into Grafana and
  select the Prometheus data source) and `deploy/monitoring/pg-stat-snapshot.sh`
  (`snapshot DIR` writes a CSV, `delta BEFORE AFTER` compares two by `queryid`).

## Prepare state and providers

Run as the deployment user:

```sh
sh deploy/docker/init-state.sh /srv/plowshare
cp deploy/docker/deployment.env.example /srv/plowshare/deployment/deployment.env
chmod 600 /srv/plowshare/deployment/deployment.env
```

Edit `deployment.env` for your host. Put model endpoints and API keys in
`/srv/plowshare/config/models.env`, using one literal `NAME=value` per line
(without shell quoting or `export`). Compose reads it in raw mode, preserving
dollar signs in keys. For the generic single endpoint, set `LLM_BASE_URL`,
`LLM_CHAT_MODEL`, `LLM_EMBEDDING_MODEL`, `LLM_API_KEY` and `LLM_PROVIDER`.
Set `LLM_CHAT_SLOTS`, `LLM_EMBEDDING_SLOTS` and `LLM_SWARM_SLOTS` for your
service capacity; the conservative defaults disable swarm work. No second
inference host is required. For any other topology, save a complete Spring
overlay as `/srv/plowshare/config/application.yml`; the
[minimal example](../../bin/application-local.example.yml) uses one pool. Put
its `LLM_*` variables in `models.env`. Add other pools only when required. Keep both files mode
600, owned by the deployment user. Existing deployments must move their full
pool declarations, model IDs, capabilities and capacity settings into this
private overlay. The server reads the overlay on top of its packaged settings; the
Docker-specific database/data settings live in Compose.
Review any paths in an imported overlay for the container's filesystem.

### Default Application FileStore

The server image initializes an `applications` FileStore on first start, including
when an installation overlay supplies its own Compose file. It derives the storage
root from the configured `PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY`, creating its
`applications/` child outside private server data. The registry is retained at
`$PLOWSHARE_DATA_DIR/filestore.js`, mode 0600. Both parent directories must already
exist and be writable by the server user. The standard Compose mounts provide them.

On first initialization, `PLOWSHARE_FILESTORES_MANAGER_HANDLE` selects the one
account granted MANAGER access to this store. When omitted, the explicitly
configured `PLOWSHARE_ADMIN_HANDLE` supplies that grant. If neither is configured,
the registry starts with no grants and startup reports that a MANAGER grant is
required. After first-run account setup, add the actual administrator handle to
the retained registry before deploying an Application. There are no wildcard
grants; Application manifest grants and project membership remain separate.

An explicit `PLOWSHARE_FILESTORES_CONFIG_FILE` or private Spring
`plowshare.filestores.config-file` setting takes precedence. Existing registries,
grants, modes and ownership are never replaced on restart or upgrade. With the
standard container paths, the default registry is `/var/lib/plowshare/filestore.js`
and Application storage is `/var/lib/plowshare-workspaces/applications`. Back up
both mounts. Do not place Application source in the private data/config directories.

Use `applications` as the FileStore alias for CLI or Desktop deployment. Source
launches still require an explicit registry configuration; the default is supplied
by the image entrypoint. See [Application deployment](../../docs/projects.md#deploy-update-and-activate-an-application).

State stays on plain host disk:

| Host path | Contents |
| --- | --- |
| `/srv/plowshare/data` | Projects, agents, synced files, images and exports |
| `/srv/plowshare/postgres` | PostgreSQL database files, initialized by PostgreSQL |
| `/srv/plowshare/config` | External provider environment and Spring overlay |
| `/srv/plowshare/secrets` | Database and initial admin passwords |
| `/srv/plowshare/deployment` | Compose/Docker build files and deployment environment |

`init-state.sh` generates unique database and optional provisioning passwords
only when absent. Re-running it preserves them. Leave `PLOWSHARE_ADMIN_HANDLE`
blank for first-run setup: the server prints a temporary `admin` account and a
random password. Run `plowshare-cli setup --url <server-origin>` to create the
first administrator. The temporary account only permits setup; pending setup
gets a new password after a restart. Completed setup never resets the administrator.

An explicitly configured `PLOWSHARE_ADMIN_HANDLE` retains environment provisioning
using `/srv/plowshare/secrets/admin-password`, with mandatory first-login password
rotation. Container health uses the status-only `/ready` probe, available after
startup runners finish and while the database is reachable; it does not need an
operator token. Clients save their normal account sessions.

The server has a 3 GiB JVM heap and 5 GiB container limit; PostgreSQL has a 3 GiB
limit, and the adapter has a 768 MiB limit. These are ceilings, not reserved
allocations. Log files are bounded. Managed server workspaces are mounted separately from private server data at
`/var/lib/plowshare-workspaces` (`workspaces/` under the deployment state root).
Include that directory in backups. Database and project directories survive
container replacement. Back up the database with `pg_dump` and the file/config
trees together; for a consistent complete backup, stop the server first and
restart it after the backup. A live copy of PostgreSQL's raw files is not a
database backup. Keep the prior application images for rollback, and pair
rollback across incompatible migrations with a matching database backup.

## Start with the existing SearXNG service

Set `SEARXNG_BASE_URL` in your deployment environment to the existing engine's
base URL, without a browser fragment. It must support JSON searches. Select
`PLOWSHARE_SEARCH_LADDER=searxng` for this deployment.

Keep these settings in `deployment.env`:

```dotenv
SEARXNG_TIMEOUT_MS=15000
PLOWSHARE_SEARCH_TIMEOUT=PT20S
```

The adapter has 15 seconds to query SearXNG; the server allows 20 seconds for
the provider call. Both Compose layers supply these defaults.

From the checkout or its preserved build layout:

```sh
docker compose --env-file /srv/plowshare/deployment/deployment.env \
  -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml config --quiet
docker compose --env-file /srv/plowshare/deployment/deployment.env \
  -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml build
docker compose --env-file /srv/plowshare/deployment/deployment.env \
  -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml \
  up -d --wait --wait-timeout 180
```

These files start exactly three services: `postgres`, `server` and
`searxng-provider`. The adapter connects to your existing engine. PostgreSQL and
the adapter have no published host ports. The server uses the existing event
and file WebSocket channels on port 8091, including streamed server-side
conversion; this deployment does not change that protocol.

After the server and adapter are healthy, register the adapter:

```sh
# Change this URL if the published address/port differs from loopback.
PLOWSHARE_URL=http://127.0.0.1:8091 \
  PLOWSHARE_SEARCH_PROVIDER_URL=http://searxng-provider:8086 \
  sh deploy/docker/register-search.sh
```

Build the CLI and complete `plowshare-cli setup` (or `login`) for the same
server first. Registration uses that administrator's saved session, renewing it
through the shared private credential store, and registers
the explicitly supplied provider URL on the private Compose network. Operator
verification may instead set `PLOWSHARE_TOKEN_FILE` to an existing private token
file; the helper reads its first line without printing it. This operational
registration remains HTTP because it has no WebSocket contract; it does not
fall back or replay a failed mutation. Re-registering updates the provider row.
Normal search calls stay on their existing WS/tool surfaces.

To run without search, select only `compose.yaml` and leave
`PLOWSHARE_SEARCH_LADDER` empty. `compose.search.yaml` also supports deploying
only the adapter while preparing a server.

## Optional local SearXNG engine

Only for installations without an existing service, additionally select
`compose.search-local.yaml`. Choose `SEARXNG_IMAGE` as a pinned official image
(including its digest). Prepare `/srv/plowshare/searxng/config/settings.yml`
with a unique `server.secret_key` and JSON in `search.formats`, following
[SearXNG settings](https://docs.searxng.org/admin/settings/settings_search.html)
and [container installation](https://docs.searxng.org/admin/installation-docker.html).
The engine's configuration and cache are persistent bind mounts. It has no
published port in this overlay.

```sh
docker compose --env-file /srv/plowshare/deployment/deployment.env \
  -f deploy/docker/compose.yaml -f deploy/docker/compose.search.yaml \
  -f deploy/docker/compose.search-local.yaml up -d --wait --wait-timeout 180
```

The overlay directs the adapter to `http://searxng:8080` and explicitly adds a
fourth service. Compose files merge in command-line order; see
[Docker's merge rules](https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/).
This local-engine path has configuration validation only; live acceptance uses
the external service.
