# Set up Network Privacy Watch

The guided Python helper prepares a private configuration, deploys the Plowshare
Application and creates its separate execution account. It also starts the external
Python collector when you ask it to. Each stage has a separate command so you can
review the configuration before changing the server.

## Understand the two parts

| Part | Where it runs | What it does |
| --- | --- | --- |
| Plowshare Application | Your existing Plowshare server | Scheduling, Relay, retained evidence and agent investigations |
| Python collector and web interface | A machine that can reach the selected devices | Bounded TCP probes, evidence collection and dashboard |

Your Plowshare server can be local or hosted in the cloud. Only the collector
needs access to the device network. It connects outbound to your explicit server
origin through the public Python SDK. Deploying Application source does not start
Python on the server.

The Application assigns six network tools to `privacy_coordinator` through the
`network_scanning` provider scope. The analyst and reviewer retain narrower grants.
The coordinator opts into dynamic tools; Python publishes their schemas when it
starts. The model cannot select new scan targets or execute shell commands.
TCP results show service responses, not vulnerabilities or proof of data leaving
a device. DNS observations require an additional normalized export; TCP probes
alone do not reveal which remote destinations a TV contacts.

## 1. Prepare the prerequisites

You need Python 3.11+, this repository's SDK and integration source, and a reachable
Plowshare server supporting application-owned configuration and provider scopes.
Install the Python tools from the repository root:

```sh
python3 -m venv build/privacy-python-env
build/privacy-python-env/bin/python -m pip install ./sdk/python ./integrations/network-privacy
```

Before installing an Application, configure these server resources:

- An existing server administrator who can deploy source and issue service tokens.
- An enabled human account to manage the Application. It can be the administrator
  or another user. The helper gives this account Application `MANAGER` access;
  it does not create human login accounts or make them server administrators.
- A configured FileStore destination with `MANAGER` access for the deployment
  administrator. The helper lists eligible stores for selection. An Application
  cannot configure the server's underlying storage root for itself.
- A working server model binding. The helper asks for its name and writes it into
  all three agents and the investigation conductor. This can use local inference.
  The helper does not install a model or configure its provider.

See [server administration](../../docs/server-administration.md),
[FileStores](../../docs/projects.md#filestores-application-roots-and-writable-areas) and the
[Application deployment manual](../../docs/projects.md#deploy-update-and-activate-an-application).
Use your HTTPS origin for remote administrator login. If the administrator needs
an initial password change, complete that through the CLI or Desktop first.

Choose a **new absolute private directory outside Git**, with an existing parent.
Use a directory on the collector machine, or transfer the completed private setup
securely to it and deliberately adjust its absolute paths. Do not commit this
folder: it will contain the service credential and operational state.

```sh
: "${PRIVACY_PRIVATE:?Set a new absolute private directory outside the checkout}"
: "${PRIVACY_APP_SOURCE:?Set the absolute path to integrations/network-privacy/network-privacy-watch}"
```

## 2. Configure and review offline

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" configure --source "$PRIVACY_APP_SOURCE"
```

The prompts ask for:

| Input | How to choose it |
| --- | --- |
| Server origin | Your actual HTTP(S) origin, including a port if needed; no API path |
| Collector identifier | A stable name for this collector and its Relay consumer group |
| IP addresses and TCP ports | Comma-separated explicit device addresses and port numbers; no CIDR sweep or hostnames |
| Timeout and concurrency | Probe timeout 0.1–5 seconds and 1–32 concurrent probes |
| Service handle | A separate service account, distinct from both human accounts |
| Human manager | An existing enabled account that should manage this Application |
| Deployment administrator | An existing server administrator; Enter accepts the manager handle if appropriate |
| Model binding | An existing server binding, such as your configured local model binding |
| Token lifetime | 1–365 days; rotate the same token UUID before expiry |
| Destination path | A relative directory within the FileStore you will select during installation |

The collector supports up to 32 unique IPs, 16 unique ports and 256 address/port
pairs. The Application project is `network-privacy-watch`. This helper installs
that Application; the lower-level setup helper supports separately authored
configuration.

Configuration performs no login, probes or server mutations. It creates:

```text
private-directory/
  collector.json                 # Origin, collection scope, schedule and environment names
  destination.json               # Relative server FileStore destination
  setup-input.json               # Inputs to the lower-level preparation helper
  state/                         # Private collector journals; never Application source
  deployment/
    setup.json                   # Filled account/provisioning configuration
    application/
      plowshare.json             # Human MANAGERs, service CONTRIBUTOR and explicit tool scopes
      agents/                    # Configured model binding and tool requests
      orchestrations/            # Configured investigation conductor
      schedules/                 # network_scan remains paused
      Relay/
      server/                    # Application-owned tools/provider and port authority
```

Review `collector.json` and `deployment/application` before continuing. There is
no `.plowshare/` directory. The first manifest's `executionAccount` temporarily
names the service handle. Installation replaces it and the `server/` declarations
with the token's authenticated `@service/<UUID>` principal before Python starts.
The manifest's membership grants continue to use account handles.

You can edit `collector.json` to add a normalized DNS export using the
[collector configuration guide](README.md#what-the-collector-observes). Keep its
project, topics and provider authority consistent with the supplied Application.
The helper preserves those explicit authority declarations.

## 3. Install under the administrator

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" install
```

Enter the deployment administrator's password at the hidden prompt. The helper
uses a temporary login session, never reads saved CLI/Desktop credentials and
revokes the temporary session afterward. Choose a **number from the server's
FileStore list**; stores without `MANAGER` access are excluded.

Installation:

1. Verifies the administrator, human manager, empty deployment history and
   paused packaged schedule. It refuses to overwrite an existing Application.
2. Deploys the private Application once, creating the project needed for a scoped
   service token. It retains the request UUID before sending it.
3. Creates or uses the enabled service account, establishes manager/service
   project membership and issues a named token with only this project's
   `CONTRIBUTOR` scope. It refuses a token that already has that name.
4. Saves the token privately and substitutes its principal into the Application
   manifest and provider/port declarations.
5. Deploys the updated source using a fresh request UUID and the confirmed first
   revision as `expectedRevision`. It verifies the active revision.
6. Reads the deployed schedule's internal name into `collector.json` when server
   reconciliation has made it available. The schedule remains paused.

The helper writes the private directory with POSIX mode `0700` and credentials,
intents and receipts with `0600`. On Windows, restrict the directory's ACLs.
It never prints the administrator password, service token or dashboard bearer.
It does not scan or configure an automatic Relay worker during installation.

If schedule discovery is still pending, run:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" status
```

`status` reads server state and retained receipts, and updates only the local
collector configuration with the discovered schedule identity. It creates no
server work. It is safe to use again after reconciliation.

## 4. Enable investigation processing

The service account owns collector tools, evidence uploads and completion-triggered
agent work. The **account that processes the Relay subscriptions** owns those
investigations, so use the service credential for that processing.

Follow [Run investigations as the service identity](README.md#run-investigations-as-the-service-identity)
to configure the existing automatic Relay worker using the principal in
`deployment/identity.json`, or supervise explicit Relay processing under the
credential in `deployment/service-token`. The helper does not edit a whole server
configuration to install a worker. Without a worker or explicit processing pass,
scan evidence can be retained while its investigation has not started.

The packaged schedule is still owned by the deploying administrator. Its
`privacy_tick` action invokes no model. Setup does not transfer that schedule's
ownership; the service token cannot manage it. Keep the administrator identity
for schedule administration and the service identity for collection/investigation.

## 5. Start the separately operated Python service

Set an explicit bind address and port for the collector's web listener:

```sh
: "${PRIVACY_BIND:?Set the dashboard bind address on the collector machine}"
: "${PRIVACY_PORT:?Set the dashboard listener port}"
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" serve \
  --bind "$PRIVACY_BIND" --port "$PRIVACY_PORT"
```

Provide a separate dashboard bearer of at least 24 non-whitespace characters at
the hidden prompt, then enter that same bearer in the browser. For unattended
operation, inject it through `PRIVACY_WEB_TOKEN` using your secret manager.
The helper loads `deployment/service-token` into this collector process and sets
the provider account from `identity.json`; you do not have to copy a service
principal or put a Plowshare credential into the browser. Environment values are
restored when the collector stops. Use HTTPS at your proxy for a remote dashboard.

This command is the external Python process. Run it on the machine that reaches
your selected devices and supervise it through your normal service manager.
Plowshare source deployment does not install Python packages, open the dashboard
listener or start this process.

Request one scan from the dashboard. Confirm its collection receipt, retained
evidence and subsequent investigation/report through Plowshare. A tool's scan
publication receipt confirms submission, not a finished scan or report. Once
that path works, review the schedule's timing and resume it using the
administrator's Schedule UI or `schedule.pause`. The helper deliberately leaves
this operator-controlled activation step visible.

## Interrupted installation and updates

**Do not run `install` again after a deployment or provisioning intent exists.**
The helper sends each mutation once and blocks re-entry after an attempted install.
A disconnect is not evidence that the server rejected a mutation.

Use `status` and inspect these private files:

| File | What it establishes |
| --- | --- |
| `deployment/bootstrap-intent.json` | First request UUID, expected revision and selected destination |
| `deployment/bootstrap-receipt.json` | Confirmed first retained release |
| `deployment/provisioning-intent.json` | Account/token provisioning was attempted |
| `deployment/service-token` and `identity.json` | Credential received and its principal retained |
| `deployment/provisioning-completed` | Account provisioning completed |
| `deployment/identity-intent.json` and `identity-receipt.json` | Second request identity and confirmed release |
| `deployment/installation-completed` | Both source deployments and provisioning were confirmed |

If `status` cannot fetch a receipt, inspect that exact UUID through
`application.deployment.receipt` as the same deployment administrator. A missing
or refused receipt does not authorize a fresh deployment. See the
[deployment recovery manual](../../docs/projects.md#deploy-update-and-activate-an-application).
If token creation succeeded but its secret reply was lost, inspect token metadata
and rotate that existing token UUID explicitly; do not create another token to
retry. Follow [interrupted provisioning](README.md#rotation-and-interrupted-setup).

There is no automatic rollback or retry of an uncertain install. Finish a reconciled
partial install using the lower-level setup and deployment commands, retain all
intents, and verify the final principal and active source before starting Python.
The guided `serve` command requires its completion marker; manually recovered
installations can use the documented lower-level collector command instead.

For updates, edit the private Application copy and use the normal deployment
workflow with a new request UUID and the reviewed active revision. This first-install
helper does not silently upgrade existing installations. Rotate service credentials
before expiry while keeping the same token UUID, principal and collector journals.
