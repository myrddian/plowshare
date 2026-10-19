# Run Excalibur as a separate Docker application

This deployment adds only the SDK consumer. It does not rebuild, restart or
migrate Plowshare, attach its database, or mount server-internal files. The
container calls the configured public server origin through the Python SDK.

## Provision authority and schemas

Follow the package README to deploy an Application with an explicit human
MANAGER. For a dedicated service identity, its root manifest additionally declares
`service: {"handle": "YOUR_UNUSED_SERVICE_HANDLE", "role": "CONTRIBUTOR"}`.
A server administrator deploys it and explicitly issues its credential through
`application.token.issue`; put only that issued Application bearer in the app's
private token file. Never run this web service with an administrator credential.
The human manager registers and activates the bundled schemas in that project's
scope. Keep their returned type/version UUIDs in the private TOML configuration.

Choose an unused project and FileStore destination for the first installation.
Retain request UUIDs and receipts for deployment, schema creation/activation and
token issuance. If credential issuance has an unknown outcome, reconcile the
public receipt before deciding whether explicit rotation is needed. A committed
credential is returned once; rerunning the installer cannot recover its secret.

The current server can refuse first-time `application.deploy` before project
creation with a project-access error. A supported alternative is the documented
`project.create` MANAGED lifecycle: create an isolated project, create a dedicated
service account, grant that account CONTRIBUTOR membership, and add the same
explicit CONTRIBUTOR grant to the root manifest through `application.file.read`
and revision-checked `application.file.save`. Register/activate the schemas as the
human Manager, then issue an ordinary service token scoped only to this project
at CONTRIBUTOR. Retain it privately. This is a manually managed service identity,
not an Application-owned token; its existing public service-token administration
operations own rotation/revocation. No direct filesystem write or SQL is needed.

The server must support manual document controls independently of background
processing leases. Live testing on revision
`1b9f8bbb550cff914da1957c3ea677f21c6deeca` exposed a server regression in
`information.tags`, affecting epic membership and topic saves. The separate
platform correction is described in
[Decision 0032](../../../docs/decisions/0032-document-control-hook-authority.md).
Excalibur continues to use that public operation; it preserves internal-error
intents for explicit recovery. Verify the corrected operation before accepting
the deployment. No alternate membership store or automatic processing is needed.

## Build an immutable image

Resolve `python:3.13-slim-bookworm` to its Linux/amd64 manifest digest and supply
that pinned reference as `PYTHON_IMAGE`. Build matching local SDK/app wheels:

```sh
python -m build --wheel --outdir build/furrow-wheels sdk/python
python -m build --wheel --outdir build/furrow-wheels integrations/agile
```

Prepare a new, empty build-context directory containing just `Dockerfile`,
`Dockerfile.dockerignore` from this directory and a `wheels/` directory. Download
the pinned dependencies into that wheelhouse (for a Linux/amd64 Python 3.13 host):

```sh
python -m pip download --only-binary=:all: --platform manylinux2014_x86_64 --python-version 313 --implementation cp --abi cp313 --dest "$CONTEXT/wheels" -r integrations/agile/deploy/requirements.txt
cp build/furrow-wheels/*.whl "$CONTEXT/wheels/"
docker build --platform linux/amd64 --build-arg PYTHON_IMAGE="$PYTHON_IMAGE" --build-arg SOURCE_REVISION="$SOURCE_REVISION" --build-arg SOURCE_DIGEST="$SOURCE_DIGEST" --iidfile "$IMAGE_ID_FILE" "$CONTEXT"
```

Record the exact base digest, wheel SHA-256 hashes, source revision, whether that
source has local changes, source-content digest, resulting image ID and platform.
The runtime install is offline. No credential or private configuration enters
the build context. For host-local testing, Compose can use that exact image ID.
A deployment to another host should publish and pin a registry digest instead.

## Configure and run

Create a private installation directory, configuration and secret files before
starting Compose. Directories are mode 700; files are mode 600, owned by the
configured runtime UID/GID. Set `state_directory = "/var/lib/furrow/requests"`
in the mounted TOML. Generate a distinct random web access key with at least
32 characters; it is independent of the Plowshare Application bearer.

Copy `environment.example` and replace every value. All listener, address, path,
runtime-user and image values are explicit. `AGILE_PUBLIC_ORIGIN` is what the
browser opens. `AGILE_HEALTH_ORIGIN` is the container's internal HTTP listener;
the health probe sends the public Host header. Configure a reverse proxy and
an HTTPS public origin when exposing the app beyond your trusted test network.
Both credentials are file-mounted read-only, not Docker environment secrets.

```sh
docker compose --env-file "$FURROW_ENV" -f integrations/agile/deploy/compose.yaml config --quiet
docker compose --env-file "$FURROW_ENV" -f integrations/agile/deploy/compose.yaml up -d --no-build
```

Open the configured browser origin and enter the web access key. It grants the
single maintainer the configured Application identity's authority. The container
has a read-only filesystem, an explicit runtime user, no Linux capabilities and
one persistent private request-journal mount. Authoritative tickets, epics,
schemas and membership remain in Plowshare.

Readiness checks use public schema/navigation reads, never ticket data. A lost
connection or authority causes the app to exit; `unless-stopped` starts a fresh
SDK connection. This does not replay writes. Sign in again and explicitly check
any retained pending request. Do not delete or share the private journal.

## Verify and retain a receipt

Check `/health`, sign in, create two epics and a ticket, link the ticket to both,
save a topic tag, edit original Markdown and change its status. Verify that the
intersection returns that one current ticket. Test a stale expected revision and
explicit recovery with its original request ID. Restart only this app, then
verify the same resource/revision, memberships and request-journal bytes remain.
Confirm the existing Plowshare services are still healthy. Fixture tests alone
do not establish this live acceptance result.

Save nonsecret receipts and runtime image identity outside the public source.
Back up the private configuration/journal and retain the previous image ID before
an upgrade. Switching an app image does not roll back authoritative documents.
Stop the separate Compose project with `down` to remove only the web container
and its private network; bind-mounted files and Plowshare data remain.
