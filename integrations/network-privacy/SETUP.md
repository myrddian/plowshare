# Set up Network Privacy Watch

Run the guided `start` command on the machine that can reach your devices. It
prepares configuration if needed, installs the Application with its separate service
account, waits for readiness and opens an authenticated dashboard. Confirmed setup
is reused on later starts. You choose devices in the dashboard.

## Start and use the dashboard

After installing the Python package below, use this single command for a new setup:

```sh
build/privacy-python-env/bin/plowshare-privacy-bootstrap \
  --directory "$PRIVACY_PRIVATE" start --source "$PRIVACY_APP_SOURCE" \
  --bind "$PRIVACY_BIND"
```

Set `PRIVACY_PRIVATE` to an absolute private directory outside Git,
`PRIVACY_APP_SOURCE` to the Application folder, and `PRIVACY_BIND` to an explicit
loopback IP on the collector machine. For a **local-only dashboard**, an operator
can choose `127.0.0.1`. The operating system chooses an available port unless you
supply `--port`. The helper remembers the bind address and port in private
`dashboard.json`; subsequent starts need only `--directory ... start`.

For an already installed Application, omit `--source`. No deployment, account or
credential creation is repeated. If schedule registration is still pending, the
helper waits using read-only requests. An interrupted installation remains fenced
until its retained receipts are reconciled; see recovery below.

For new setup, the prompts ask for the server, collector name, separate service
account, human manager, deployment administrator and model binding. Device scope
is chosen in the dashboard; the guided flow uses bounded probe settings and a
30-day service credential. The staged `configure` command below exposes advanced
settings before installation.

The helper also creates **Start Network Privacy Watch.command** on macOS, or
**start-network-privacy-watch.sh** on other systems, in your private setup directory.
Open that launcher next time. It contains the interpreter and configuration path,
with no credentials. Double-clicking it while the updated collector is running
reopens that dashboard. Keep its Python environment installed. Closing the running
terminal stops the collector; the launcher is not a background-service installer.

In the dashboard:

1. Enter your private network subnet and the TCP ports you want to check, then
   choose **Find devices**. Previous local discovery results are displayed when available.
2. Select devices, check **Enable collection**, and choose **Save monitoring**.
   You can also enter explicit private IPv4 addresses. The scope is limited to
   32 devices and eight ports; discovery checks at most 256 addresses.
3. Choose **Request a scan**. Results refresh automatically as evidence is retained.

There is no dashboard-token copying. `start` generates a new dashboard credential
for this process and opens the browser with a fragment handoff, removed immediately
from history and exchanged for an HttpOnly, SameSite=Strict session cookie. Browser
reloads remain authenticated. The Plowshare service credential stays in Python. A private `dashboard-runtime.json`
contains only the local dashboard handoff while the process is running; normal
shutdown removes it, and restart verifies its process nonce before reopening it.
Automatic browser login is restricted to the explicitly configured loopback listener.
Use advanced `serve` mode below for a remotely hosted dashboard.

Monitoring changes apply immediately without restarting. Settled evidence and request
identities are preserved. In-progress scans, published requests awaiting collection,
and uncertain effects block changes until they finish or are reconciled. Every
collection retains its scope, and a changed scope begins a new comparison baseline.
No discovery or scope expansion is exposed as an agent tool.

A paused schedule may not have published its first event, so `schedule.due` may
not exist yet. The collector checks topic registration before intake, waits for
unregistered channels and continues processing dashboard scan requests. A registered
channel that refuses access still stops collection and shows the operation, refusal
code and available server reason in the private dashboard.

The packaged server schedule remains paused until you enable it as its administrator.
Agent investigations also need service-owned Relay processing as described below.
The dashboard can collect and retain evidence before either is enabled.

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
starts. External names belong in the manifest's provider scope permissions, not
in the agent's `tools` list. Permissions grant visibility and execution; loading
validates policy and each runtime call checks registration, permission and
availability. The model cannot select new scan targets or execute shell commands.
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
  all three agents and both orchestration scripts. This can use local inference.
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

## 2. Discover devices, or configure them later

You do not need a device inventory to deploy the Application. Run a discovery pass
on the collector machine when convenient:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap discover \
  --network "$PRIVACY_NETWORK" --ports "$PRIVACY_DISCOVERY_PORTS"
```

Set `PRIVACY_NETWORK` to an explicit private IPv4 CIDR reachable from that machine
(for example, an operator-selected `/24`), and `PRIVACY_DISCOVERY_PORTS` to a
comma-separated list of TCP ports. Discovery requires no Plowshare login, private
setup directory, administrator privileges or external scanner installation. It
checks at most 256 addresses and eight ports, with at most 32 concurrent connections.
It sends no application payloads. Output lists responding IPs with open or refused
ports; filtered/silent devices may be missed. Ports do not establish vendor or
device identity. Retain discovery output outside source if you want to keep it.

Discovery does not automatically change the monitored scope or give an agent
permission to scan a subnet. Choose monitored devices from the results when ready.
You can also defer that choice and prepare the Application now.

### Configure and review offline

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" configure --source "$PRIVACY_APP_SOURCE"
```

The prompts ask for:

| Input | How to choose it |
| --- | --- |
| Server origin | Your actual HTTP(S) origin, including a port if needed; no API path |
| Collector identifier | A stable name for this collector and its Relay consumer group |
| IP addresses and TCP ports | Enter device addresses and ports, or press Enter at the device prompt to configure collection later |
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
      orchestrations/            # Configured schedule and investigation scripts
      schedules/                 # network_scan remains paused
      Relay/
      server/                    # Tools/provider, ports and service-owned Relay worker
```

If you defer device selection, `collector.json` contains `collection.enabled: false`
and empty target/port lists. Installation still works. The dashboard explains that
collection needs configuration, rejects scan requests and leaves Relay scan intake
untouched. Keep the schedule paused.

To enable collection later, stop the Python process, edit `collector.json`, set
`collection.targets` and `collection.ports`, and change `collection.enabled` to
`true`. Restart the collector. Its scope tool reports whether collection is enabled.
If you are changing a scope that already has retained work, preserve its journals
and follow the scope-change/recovery instructions instead of deleting state.

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
   manifest, provider/port declarations and Relay worker enrollment.
5. Deploys the updated source using a fresh request UUID and the confirmed first
   revision as `expectedRevision`. It verifies the active revision.
6. Reads the deployed schedule's internal name into `collector.json` when server
   reconciliation has made it available. The schedule remains paused.

The helper writes the private directory with POSIX mode `0700` and credentials,
intents and receipts with `0600`. On Windows, restrict the directory's ACLs.
It never prints the administrator password, service token or dashboard bearer.
It does not scan during installation. The prepared `server/relay-workers.json`
enrolls the automatic Relay worker under the issued service principal when the
updated Application is deployed; no whole-server configuration edit is needed.

The `start` flow waits for schedule registration automatically. For a separate read-only diagnostic, run:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" status
```

`status` reads server state and retained receipts, and updates only the local
collector configuration with the discovered schedule identity. It creates no
server work. It is safe to use again after reconciliation.

## 4. Enable investigation processing

The service account owns collector tools, evidence uploads and completion-triggered
agent work. Collector information requests explicitly exclude shared information;
the service credential reads and writes only its granted project scope. The **account that processes the Relay subscriptions** owns those
investigations, so use the service credential for that processing.

The helper fills `server/relay-workers.json` with the principal in
`deployment/identity.json` and includes it in the confirmed identity deployment.
The server discovers this Application-owned enrollment within its Relay
configuration interval (30 seconds by default), without restarting. Keep the
worker bound to the service principal so investigations use the same identity as
the collector. If upgrading an older private source copy, follow
[Run investigations as the service identity](README.md#run-investigations-as-the-service-identity)
to add and deploy the declaration, or supervise explicit Relay processing under
the credential in `deployment/service-token`. Without enrollment or an explicit
processing pass, scan evidence can be retained while its investigation has not started.

Contributor processing uses the broker's stored topic policy, or the standard
four-day default when registering a new topic. Declared values in `Relay/topics.json`
are applied only by a manager processing pass. The service account can process
investigations without acquiring policy-write authority; setup keeps its CONTRIBUTOR
role. Inspect `relay.log` for the effective stored policy.

Older servers refused contributor processing whenever source declared topic policies.
If you receive `Changing Relay topic policies requires project manager access`, update
the server with the Relay processing permission fix. Using a human manager instead
changes investigation ownership and does not verify service-account processing.

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
The guided `start` and `serve` commands require its completion marker; manually recovered
installations can use the documented lower-level collector command instead.

For updates, edit the private Application copy and use the normal deployment
workflow with a new request UUID and the reviewed active revision. This first-install
helper does not silently upgrade existing installations. Rotate service credentials
before expiry while keeping the same token UUID, principal and collector journals.
