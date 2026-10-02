# Persistent Docker deployment

This package runs Plowshare and PostgreSQL 16 with pgvector on one Docker host.
The SearXNG adapter and an optional SearXNG engine are separate layers. The
default server publishes only on loopback; select the host LAN address in your
deployment environment for remote clients. Authentication remains enabled.

Requirements: Docker Engine, Compose 2.30 or newer, and a deployment user with
UID 1000 (the UID used by both Java images). `bootstrap-host.sh USER` prepares a
new Debian 12/13 host. See [distribution instructions](../../docs/distributions.md).

## Build

From a checkout with Java 21, Node 22.12+, pnpm 10.34.5 and Docker:

```sh
sh deploy/docker/build-images.sh
```

This is also the container build entry point for a CI runner. It builds the
console into the server jar, builds the adapter jar, then creates two native
images with an embedded Java 21 runtime. Dockerfile-specific context allowlists
include only the jar and launcher files; deployment configuration, keys, project
data and source files are excluded. The JRE and PostgreSQL images are pinned by
OCI digest. Application images carry the source revision as an OCI label.
Release builds should use a clean checkout.

When building directly on a Docker host without Java/Node, copy the two compiled
jars into their normal `build/libs` paths and the `deploy/docker` directory,
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
| `/srv/plowshare/data` | Projects, agents, synced files, images, exports and operator token |
| `/srv/plowshare/postgres` | PostgreSQL database files, initialized by PostgreSQL |
| `/srv/plowshare/config` | External provider environment and Spring overlay |
| `/srv/plowshare/secrets` | Database and initial admin passwords |
| `/srv/plowshare/deployment` | Compose/Docker build files and deployment environment |

`init-state.sh` generates unique passwords only when absent. Re-running it
preserves them. The default account handle is `admin`; its initial password is
stored in `/srv/plowshare/secrets/admin-password` and must be changed at first
login. Set `PLOWSHARE_ADMIN_HANDLE` before first boot to choose another handle.
The existing server seeding logic preserves the account across restarts;
editing the seed file later does not reset its password. Clients then save
their normal login tokens. No shared default password is shipped.

The server has a 3 GiB JVM heap and 5 GiB container limit; PostgreSQL has a 3 GiB
limit, and the adapter has a 768 MiB limit. These are ceilings, not reserved
allocations. Log files are bounded. Database and project directories survive
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

This uses the existing operator registration bootstrap, reads the token from
the persistent token file, and registers `http://searxng-provider:8086` on the
private Compose network. Re-registering updates the existing provider row.
Normal search calls from clients and models remain on their existing WS/tool
surfaces. Never publish or paste the operator token or console URL from logs.

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
