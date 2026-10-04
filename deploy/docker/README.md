# Persistent Docker deployment

This package runs Plowshare and PostgreSQL 16 with pgvector on one Docker host.
The SearXNG adapter and an optional SearXNG engine are separate layers. The
default server publishes only on loopback; select the host LAN address in your
deployment environment for remote clients. Authentication remains enabled.

Requirements: Docker Engine, Compose 2.30 or newer, and a deployment user with
UID 1000 (the UID used by both Java images). `bootstrap-host.sh USER` prepares a
new Debian 12/13 host. See [distribution instructions](../../docs/distributions.md).

## A2A general-purpose receiver

Add `compose.a2a.yaml` to run the separate A2A adapter beside the server. This
requires a receiver-capable server, including migration V108. Select one existing
project and one exported agent. `interlocutor` is the shipped general-purpose
entry point: its grants determine the skills, workflows and specialists it can
use. The public Agent Card advertises its granted skills and workflows; ordinary
messages are handled by that agent within the configured project.

Create a project whose workspace is reachable **inside the server container**.
The overlay mounts a private host workspace at `/workspaces/a2a` in the server.
Create `${PLOWSHARE_STATE_ROOT}/workspaces/a2a` owned by UID 1000 before starting,
or set `PLOWSHARE_A2A_WORKSPACE_HOST` to an existing dedicated directory. Keep
workspaces outside the server-owned data/config trees, which file tools exclude.
Define `/workspaces/a2a` as the project workspace. Do not use the host's
`/srv/plowshare/...` path as a container workspace. Grant account membership and configure agents/skills through
the normal Plowshare tools. See [receiving and command semantics](../../docs/a2a-receiving.md).

Store the following in `/srv/plowshare/config/a2a.json`, mode 0600, with your own
project and external address:

```json
{
  "plowshare": "http://server:8091",
  "project": "a2a",
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

Put the existing account's current password in
`/srv/plowshare/secrets/a2a-account-password` and a newly generated, separate caller
bearer in `/srv/plowshare/secrets/a2a-caller-token`. Keep both mode 0600, owned by
UID 1000. Complete the existing account's first password change before using it;
the operator bootstrap token has no account and cannot receive A2A messages.
Clients get the caller token; the adapter's account password stays on the host.
The adapter logs in on each container start and uses the returned token for WS.
Rotating a mounted secret requires recreating/restarting the adapter.

Set `PLOWSHARE_A2A_HANDLE` in the private deployment environment. The listener
defaults to publishing on host loopback; set `PLOWSHARE_A2A_LISTEN_ADDRESS` and
`PLOWSHARE_A2A_PUBLISHED_PORT` for your intended network access. Use your TLS proxy
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
is healthy; this does not replace other services. The sidecar reads only its
configuration and credentials. Tasks and contexts persist in Plowshare's database.
After startup, validate discovery and an authenticated message round trip with
an independent client, such as Google ADK; a healthy container alone is not proof
of agent execution. The initial protocol subset is text, polling and cancellation.

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
`plowshare-a2a/build/install/plowshare-a2a`, and the `deploy/docker` directory,
preserving their checkout-relative layout. Set `PLOWSHARE_SOURCE_REVISION` to the
revision that produced those jars, then use `docker compose ... build` below.
There is no Gradle, Node or Java installation required on the runtime host.
The live deployment is validated on Linux amd64; Linux arm64 remains a separate
acceptance run. Registry publication and multi-host Swarm deployment are later
pipeline steps; these files target Docker Compose.

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
dollar signs in keys. Use the server's existing `LLM_*`, `LM_STUDIO_*`,
`SPARK_*` and `FALLBACK_*` environment names. An existing Spring provider overlay
can be saved as `/srv/plowshare/config/application.yml`. Keep both files mode
600, owned by the deployment user. The server reads the overlay on top of its
packaged settings; the Docker-specific database/data settings live in Compose.
Review any paths in an imported overlay for the container's filesystem.

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
PLOWSHARE_URL=http://127.0.0.1:8091 sh deploy/docker/register-search.sh
```

Build the CLI and complete `plowshare-cli setup` (or `login`) for the same
server first. Registration uses that administrator's saved session, renewing it
through the shared private credential store, and registers
`http://searxng-provider:8086` on the private Compose network. This operational
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
