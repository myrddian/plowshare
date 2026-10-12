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
terminal stops a foreground collector. To keep collecting after closing the terminal,
enroll the optional [background service](OPERATIONS.md#keep-the-collector-running-in-the-background).
Once enrolled, the launcher opens the running service dashboard.

In the dashboard:

1. Enter your private network subnet and the TCP ports you want to check, then
   choose **Find devices**. Previous local discovery results are displayed when available.
2. Select devices, check **Enable collection**, and choose **Save monitoring**.
   You can also enter explicit private IPv4 addresses. The scope is limited to
   32 devices and eight ports; discovery checks at most 256 addresses.
3. Choose **Request a scan**. Results refresh automatically as evidence is retained.
4. Use the health checklist, optional Pi-hole connection, investigation preferences
   and reviewed schedule controls to [finish setup in the dashboard](OPERATIONS.md#finish-setup-in-the-dashboard).

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
The dashboard provides a reviewed Application revision for frequency and pause changes;
see [schedule controls](OPERATIONS.md#finish-setup-in-the-dashboard).
Agent investigations also need service-owned Relay processing as described below.
The dashboard can collect and retain evidence before either is enabled.

## Connect Pi-hole v6

The dashboard can [test and save Pi-hole while the collector is idle](OPERATIONS.md#finish-setup-in-the-dashboard).
The following offline alternative remains available.

After the first guided setup, add Pi-hole to the collector on the machine that
can reach its API. You can also use device names without Pi-hole: enter one
selected `address=name` per line in the dashboard's **Device names** field.

1. In Pi-hole's web interface, create an **application password** for the API.
   Follow the [official authentication guide](https://docs.pi-hole.net/api/auth/).
   This is separate from both your Plowshare service credential and dashboard login.
2. Stop the Python collector by closing its running terminal or pressing Ctrl-C.
   Wait for scans to finish first. The helper refuses an active collector or
   unsettled receipts; it does not abandon pending work.
3. Set `PIHOLE_ORIGIN` to Pi-hole's actual HTTP(S) origin, including its port if
   needed, with no `/admin` or `/api` path. Prefer an HTTPS endpoint with a certificate
   trusted by the collector. Run:

   ```sh
   build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
     --directory "$PRIVACY_PRIVATE" connect-pihole --origin "$PIHOLE_ORIGIN"
   ```

   Enter the application password at the hidden prompt. The helper stores it in
   a fresh `pihole-password-<UUID>` file with private permissions and writes its
   absolute path to `collector.json`. Configuration is offline: no login, probe,
   Plowshare deployment or Pi-hole change is performed.
4. Run the existing launcher or `--directory "$PRIVACY_PRIVATE" start`, then choose
   **Request a scan**. Inspect **Observed devices**, the DNS window and **Coverage &
   gaps** in its evidence. No Plowshare server restart is required. The source
   change begins a new baseline; previous scans remain readable.

The connector reads only the selected addresses' query samples and returns fresh,
unambiguous device associations. If Pi-hole is unreachable, refuses authentication,
or has insufficient retained history, the scan retains TCP results with explicit
DNS/identity gaps. DNS observations do not establish payload contents or malicious
behavior. Router-proxied clients and DNS that bypasses Pi-hole may not be attributable.

For deployments using a secret manager, supply an environment variable instead of
running the helper. Add this object to `collection` in private `collector.json`:

```json
"pihole": {
  "origin": "https://pihole.example.invalid",
  "passwordEnvironment": "PIHOLE_APP_PASSWORD",
  "lookbackSeconds": 3600,
  "queryLimit": 64,
  "maxIdentityAgeSeconds": 86400,
  "timeoutSeconds": 5
}
```

Use exactly one of `passwordEnvironment` or `passwordFile`; never put a password
in JSON or a command argument. Supply the variable to every collector process.
Manual source edits are suitable before the first run. Once a receipt journal
exists, use `connect-pihole` for initial enrollment so scope transitions are fenced.
Existing source changes need deliberate journal reconciliation; do not delete
history to bypass a refusal. To rotate the same file-backed password, stop the
collector, replace the existing secret file contents preserving `0600` permissions,
and restart. This changes no collection scope or service identity.

Pi-hole exposes documentation for its installed version at `/api/docs`. The
[connector details](README.md#pi-hole-v6-and-device-names) explain sample limits,
freshness, optional evidence fields and attribution limits.

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
a device. DNS observations require the optional Pi-hole v6 connector or a normalized export;
TCP probes alone do not reveal which remote destinations a TV contacts.

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
no `.plowshare/` directory. New guided source declares `service: {handle, role}`
with CONTRIBUTOR access and explicit human managers. Deployment resolves the
stable token principal and inherited provider/port/worker accounts on the server.
No principal copying or second source deployment is needed. Previously prepared
manual source retains its supported deploy/provision/redeploy workflow.

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

Installation for new guided source:

1. Verifies administrator, human manager, empty deployment history and paused schedule.
2. Retains its request UUID and deploys the reviewed Application once. The server
   reserves its unused service identity as a dormant token. Source activation
   commits initial project scope and service-owned schedule enrollment together.
3. Confirms or explicitly establishes the configured human MANAGER membership.
4. Explicitly issues the reserved token's credential using a retained UUID. Saves
   the secret in a private artifact linked to that receipt, then publishes local
   `service-token` and `identity.json` runtime views. The secret is never printed.
5. Reads the project-scoped schedule identity after reconciliation. It stays paused.

The helper writes the private directory with POSIX mode `0700` and credentials,
intents and receipts with `0600`. On Windows, restrict the directory's ACLs.
It never prints the administrator password, service token or dashboard bearer.
It does not scan during installation. The prepared `server/relay-workers.json`
enrolls the automatic Relay worker under the issued service principal when the
Application is activated; no whole-server configuration edit is needed.

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

For a new Application-owned installation, the first deployment enrolls the
packaged schedule under the retained service principal. Its `privacy_tick` action
uses the native-only runtime, with no model binding and an immutable zero-call
budget. A project manager controls schedule source and pause/resume; the service
account can inspect its own project schedule. Existing manual installations retain
their historical schedule owner; upgrading does not transfer that ownership.

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

For Application-owned guided setup, run:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" recover
```

Recovery reads the original deployment and credential UUIDs. It confirms the active
head and human manager, then restores local runtime files only from a saved secret
whose receipt is still the latest issuance and whose credential remains valid.
It never repeats deployment or an
uncertain credential mutation. If a confirmed issuance lost its secret, run
`recover --rotate` to explicitly issue a new secret for the same token UUID.
An absent receipt is not proof of non-delivery: inspect that original request.
No recovery command starts a collector, scan, investigation or schedule.

Private evidence includes `bootstrap-intent.json`/`bootstrap-receipt.json`,
`credential-current.json`, the selected credential intent, request-linked credential
and metadata artifacts, `identity.json`, `service-token` and `installation-completed`.
Keep this directory private. Do not delete intents to bypass an uncertain result.
Manual older packages retain their `provisioning-*` and `identity-*` receipts;
follow [manual provisioning recovery](README.md#manual-rotation-and-interrupted-setup)
instead of converting their identity automatically.

## Upgrade the Application and collector

Stop the external collector first, including its enrolled background service.
Wait for active work to settle. Keep the Application schedule paused while the
collector is stopped; stopping Python does not pause a server schedule.
The helper preserves private configuration, credentials, local evidence and journals.

Prepare a reviewed Application upgrade from your new checkout:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" upgrade --source "$PRIVACY_APP_SOURCE"
```

It snapshots the active revision/destination, backs up private source/configuration,
preserves the installed identity and grants, and stages candidate source under
`upgrades/<request UUID>/prepared/application`. Local additions remain; edited
files conflicting with the new package are retained and listed for review. Review
the candidate against its backup. After resolving listed conflicts or making
candidate edits, use `upgrade --review` to record their hashes, then:

```sh
build/privacy-python-env/bin/python -m plowshare_privacy.bootstrap \
  --directory "$PRIVACY_PRIVATE" upgrade --apply
```

Apply sends one deployment with the retained UUID and reviewed expected revision.
It refuses changed candidate/private source or a moved server head. Unknown delivery
requires `upgrade --recover`, which reads the original receipt and completes the
private source swap without another deployment. Pending plans fence new plans.
Recovery never activates an old release over a newer head.

For a collector package upgrade, build matching collector and SDK wheels first.
Supply their absolute paths to preparation with `--collector-wheel` and `--sdk-wheel`.
Use the same dedicated virtual environment for prepare/apply. The helper backs up
that stopped environment, verifies the reviewed wheel hashes, uses offline pip
with no dependency resolution and verifies installed package bytes. Same-version
wheels are reinstalled explicitly. Existing dependencies must already be installed.
No global Python environment or private runtime directory is upgraded as a package.

An interrupted package installation is reconciled by comparing installed files.
If they differ, inspect the preserved environment backup, then explicitly use
`upgrade --repair-collector` to reinstall the retained reviewed wheels. This reads
the deployment receipt and does not redeploy source. Neither apply nor recovery
restarts the collector. Start the existing launcher when ready; then inspect health
and deliberately resume the schedule. Renew expiring owned credentials through
explicit Application token rotation, preserving token UUID, principal and journals.
