# Server administration

Server FileStore aliases are configured through `PLOWSHARE_FILESTORES_CONFIG_FILE`,
an absolute path to private `filestore.js`. Store roots and admitted writable-area
directories must exist on this server or in its container. Account grants for
direct browsing are independent of Application runtime writable areas. Missing
or invalid aliases deny access without using an old absolute path. See
[FileStore configuration and Application adoption](../projects.md#filestores-application-roots-and-writable-areas).

## Separate infrastructure from everyday use

The server is a persistent service. PostgreSQL/pgvector, provider access and durable
storage are operator responsibilities. A person using an already configured server
only needs a client, its address and an account. Keep those two setup journeys
separate in your deployment instructions.

The source repository supports a Java server distribution and Docker deployment
with PostgreSQL, separate search services and optional A2A overlay. Follow the
detailed distribution/deployment guides for the exact packaged commands and host
preparation; do not copy a private deployment's registry names or credentials into
the manual.

## Persistent state and configuration

| State | Why it must survive replacement |
| --- | --- |
| PostgreSQL | Accounts, projects, histories, receipts, information and workflow state |
| Server data directory | Files owned by the runtime, unions, artifacts and accounting journal |
| Server workspaces | Working files in their configured ownership mode |
| Private configuration/secrets | Model providers, database access, adapter credentials and deployment settings |
| Client credential/preferences directories | Saved sessions and local client state; outside installed binaries |

Mount persistent paths separately from images. Container paths used as server
workspaces must refer to mounts visible inside that container. Preserve ownership
and permissions required by the shipped service UID. Replacing an image should
not replace the data tree or erase a pipeline's independent checkout.

The top-level configuration uses `plowshare.*` properties and the documented
environment variables. Configure `PLOWSHARE_DB_URL`, `PLOWSHARE_DB_USER`,
`PLOWSHARE_DB_PASSWORD`, `PLOWSHARE_PORT`, `PLOWSHARE_BIND` and
`PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY` explicitly. The workspace directory must
be absolute. `PLOWSHARE_DATA_DIR` supplies persistent storage; the server chooses
no conventional data or credential path. If operator-token handoff is enabled,
configure `PLOWSHARE_AUTH_TOKEN_FILE` and `plowshare.auth.console-origin` together;
the protected file carries the bootstrap URL, and logs carry no credentials. The
database URL is PostgreSQL JDBC configuration, and provider URLs/model IDs must
match what the configured service actually serves. Private local overlays stay
outside tracked source and packaged images.

## Models, pools and budgets

Model classes such as `fast` or `reasoning` bind logical behavior to actual wire
models. Pools specify endpoints, models and capacities. Required internal agents
and model bindings are validated; optional invalid definitions can be disabled
with a reason instead of receiving an invented fallback model.

Spring list overlays replace the pool list, rather than merging one pool into the
shipped list. Include every pool that should remain available. Inspect effective
chat/embedding/swarm capacities; an omitted swarm capacity has its configured
default relationship to chat, while explicit zero disables that capacity.

Budgets count admitted model work in their owning lifecycle. They are not a fixed
time estimate or a guarantee of answer quality. Delegated work, independent
document preparation, maintenance and scheduled work can have different owners
and allowances. Keep cap changes explicit and inspect actual usage when tuning.

## Accounting and readiness

Durable accounting records calls, attempts, usage fields and price snapshots.
Provider omissions stay unknown; missing prices are not zero cost. Summed reports
distinguish known totals from incomplete observations and avoid counting inclusive
subtrees twice. Currency values remain separated.

The accounting journal bridges projection outages but has its own capacity,
writer lock and recovery semantics. A `WRITER_LOCKED` startup error means another
process owns that journal; identify and stop the intended owner gracefully. Do
not delete the lock file to let two servers write the same journal.

The status-only `/ready` probe is an operational HTTP exception. Verify more than
process liveness before calling a deployment healthy: authenticated WS reads,
database migrations, persistent state, configured provider behavior and relevant
external adapters must work. A green image build does not prove a live provider
or a restored backup.

## Accounts and project administration

Complete the first administrator setup and rotate any account requiring a password
change before using it in unattended adapters. Server administrator status is
explicit. Project roles govern ordinary server projects: Viewer reads,
Contributor also starts and updates work, and Manager also manages definitions
and membership. Personal remains account-owned. An adapter needs effective
Contributor-or-higher access in its target project, and its receiving definition
must expose the intended capabilities.

Use current admin/project operations and the client's role display to inspect
authority. Do not rely on the historical assumption that every password account
is a server administrator. Managing a project does not grant every private
document or other account's conversation to the administrator's model context.

Use **Server administration** in the desktop for accounts, role/enabled changes,
password recovery, session revocation and audit history. Use a project's **Access**
control for its grants. The [account administration guide](../server-administration.md)
includes complete CLI/TUI procedures and the last-administrator/Manager safeguards.

Scoped service accounts have no password login, administrator role or Personal
space. Their tokens have an expiry and fixed per-project role ceilings; effective
access is the lower of that ceiling and the account's current grant. Token
rotation keeps its execution principal and ownership while changing its secret.
These limits follow background work and internal routing. Service credentials
cannot read the shared information catalogue, so the current shared manual
installation does not supply documentation to those readers.

## Upgrade and rollback

1. Record the deployed immutable artifact and configuration versions.
2. Take a coherent backup of PostgreSQL, retained server state and workspaces.
3. Review new migrations and compatibility changes before starting the new server.
4. Stop or replace the old process so two journal writers do not race.
5. Verify startup, migrations, readiness, authenticated operations and state retention.
6. Check the adapters and a real bounded workflow relevant to the deployment.

Flyway migrations are append-only. An older binary with newer schema or journal
data is not automatically a valid rollback. Use a known compatible code/database/
journal backup set. Reclaimed acknowledged journal segments cannot reconstruct
a database restored to an earlier watermark.

## Install and update the manual

Run the Library installer from a checkout matching the server's intended edition.
Use one stable publishing account and `--share` for access by other accounts.
The installed chapters are ordinary information sources with retained revisions,
tags and explicit sharing. Updates create new revisions and exclude previous
installer-owned versions from discovery after the new version is usable; old
authorized evidence remains tied to its actual text.

The installer is not a server deployment, database migration or permission bypass.
Keep its journal outside source control and run it explicitly after deciding which
manual edition to publish. The installation guide explains dry runs, interrupted
delivery and model-processing costs.
