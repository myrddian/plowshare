# Network Privacy Watch

Network Privacy Watch helps an operator understand changes in their network,
retain the observations and investigate them with an LLM, including a local model.
Start with the [Application README](network-privacy-watch/README.md) for
the workflow and deployment checklist; this guide covers the Python integration,
tool configuration and recovery.

The Plowshare Application contains the manifest, agents, orchestrations and Relay
definitions that Plowshare runs. The collector and its web interface are an
external Python integration using the public SDK. Together they use Plowshare's
scheduler, Relay and project information store. The server can run locally or in the cloud; collection runs
where the operator has configured network access. No shared host or filesystem is
assumed, and no Java code is required to run the collector.

```text
Plowshare project schedule ── schedule.due ─┐
                                          ├─ SDK egress → Python collection
Python web UI ── SDK ingress → scan request ┘                  │
                                                             ↓
                                           project information.upload
                                                             │
                                    SDK ingress → scan completion
                                                             │
                                               Relay orchestration
                                                             │
                                         analyst → reviewer → draft report
                                                             │
                                        Python web UI ← project information
```

The agent is one part of the application. Python performs deterministic
collection and comparison. Plowshare retains evidence and coordinates the
investigation. An LLM interprets observations and explains uncertainty. A local
model pool can serve the same `reasoning` binding used by these definitions.

## What the collector observes

`tcp` performs bounded TCP connection probes against **explicit IP addresses and
ports** in private deployment configuration. It sends no application payloads,
requires no raw sockets or elevated scan privileges, and records open, refused,
timed-out and unreachable responses separately. There are at most 256 probes,
32 concurrent connections and a configured per-probe timeout. This is selected
service observation, not complete device discovery or vulnerability detection.

An optional `observationsFile` consumes a bounded, normalized export from another
system, such as a DNS monitor. Add `maxObservationAgeSeconds` when using that
export with TCP collection. It uses the [snapshot contract](examples/observations-before.json):
version, an offset-bearing `observed_at`, `tcp` and `dns` arrays. For a DNS-only
export, set `tcp` to `[]`. Each DNS row identifies the device, lowercase domain
and count of observed queries in the exporter’s window. Exporter identity and
attribution remain its responsibility; the collector does not parse Pi-hole or
router-specific files. A missing or stale export is an explicit evidence gap.
Malformed exports refuse collection before network probes. A DNS query is not
proof of a connection, encrypted payload content or malicious behavior.

`fixture` reads the same contract without probing a network. Configure only
`mode: "fixture"` and `observationsFile`; omit TCP settings. Synthetic mode is
retained in the evidence, completion event and dashboard. The two included
snapshots use documentation addresses and fictitious domains.

Comparison records changed observable TCP responses and newly observed DNS
destinations. It compares equal configured scopes and does not infer device
disappearance from missing data or compare counts across different export windows.
These are observations and hypotheses for an investigator, not vendor allegations.

## Guided setup

Use the [step-by-step setup walkthrough](SETUP.md) and installed
`plowshare-privacy-bootstrap` helper to configure a private collector, select a
server FileStore, deploy the paused Application, provision a separate service
account and redeploy with its execution principal. The guide explains each identity,
provider scope, Relay processing, Python startup and interrupted-install recovery.
The lower-level preparation and provisioning commands below remain available.

## Install the Python application

From the repository root, with Python 3.11+:

```sh
python3 -m venv build/privacy-python-env
build/privacy-python-env/bin/python -m pip install ./sdk/python ./integrations/network-privacy
```

The Python SDK has no published remote package yet; install the local SDK alongside
the integration. The collector uses only its public typed operations. For a remote
collector, build both wheels locally and transfer them using your normal deployment
workflow. Build the integration with `python -m build integrations/network-privacy`.
Its wheel includes the HTML, CSS and JavaScript interface. Application definitions
live in `network-privacy-watch/` and are deployed separately.

Copy [config.json](examples/config.json) into a private operator directory. Replace
the explicit server origin, project, collector identity, state directory, addresses
and ports. Supply `PLOWSHARE_TOKEN` through normal credential management, and a
separate web bearer of at least 24 non-whitespace characters in `PRIVACY_WEB_TOKEN`.
The config contains environment-variable names, never credentials. Create a private
state directory with permissions `0700`; its parent must exist. Keep it outside
tracked Application source. Do not share that directory between collectors.

The offline check validates config and any supplied snapshot without tokens,
connections, scans or journal writes:

```sh
: "${PRIVACY_CONFIG:?Set the private configuration path}"
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" check
```

## Separate the human manager from the service account

Give Network Privacy Watch its own **service account**. Python collection, evidence
uploads, tool serving and agent investigations use its project-scoped credential.
The human user manages the Application through their own account. Do not run the
collector with an administrator's or user's login credential.

The Application's management group is its accounts with `MANAGER` grants in
`plowshare.json`, backed by matching server project membership. It is not a separate
named group. Here, “application admin” means `MANAGER`; it does not make the user a
server administrator. Both grants are required: the manifest caps membership and
cannot create or elevate it. The setup script writes one grant per account,
including when the human manager and deployment administrator are the same person.

| Identity | Application and server project role | Purpose |
| --- | --- | --- |
| Human manager | `MANAGER` | Inspect work, manage membership and agent definitions |
| Deployment administrator | `MANAGER`, plus existing server administration | Deploy reviewed source and create service credentials |
| Service account handle | `CONTRIBUTOR` | Account membership and manifest grant |
| Service token | `CONTRIBUTOR` ceiling for this project only | Python and agent execution under `@service/<token UUID>` |

The service account has no password login, server administrator role or Personal
space. Its **handle** goes in project membership and `plowshare.json`. Its token's
**principal**, returned by token creation, goes in root `plowshare.json` as
`executionAccount`, in `server/tools.json`,
`server/ports.json`, `server/relay-workers.json` and `--tool-account`. Those declarations check the authenticated
execution identity exactly; putting the account handle there will not authorize a
service token. Rotation preserves the principal, so it preserves retained work and
Relay identity. Creating another token changes the principal.

### Prepare and provision with Python

1. Install the SDK and Python integration as above. Prepare the private collector
   configuration first, including its explicit origin, project, collection scope
   and state directory. Keep the packaged schedule paused. If the human manager
   does not have an account, create one as a server administrator:

   ```sh
   bin/plowshare-cli admin account create '{"handle":"privacy-operator","serverAdmin":false}'
   ```

   The response contains a temporary password. Deliver it through your normal
   credential process; the user completes their first password change through CLI
   or Desktop login. Account creation alone gives no Application access.

2. Copy [setup.json](examples/setup.json) into your private operator directory.
   Fill its fields, or leave string fields empty for interactive prompts.
   `collectorConfig` is the absolute private collector configuration path;
   `applicationSource` is the absolute path to the supplied `network-privacy-watch`
   folder; `outputDirectory` is a **new** private directory outside that source with
   an existing parent. Set the service handle, human manager handle and existing
   deployment administrator handle. `tokenName` identifies this deployment's
   credential; `expiresInDays` is 1–365. Then run:

   ```sh
   python -m plowshare_privacy.setup --config "$PRIVACY_SETUP_CONFIG" prepare
   ```

   Use the installed virtual environment's Python. The installed
   `plowshare-privacy-setup` command is equivalent. Preparation reads the configuration,
   prompts for blanks and writes `outputDirectory/application` plus a filled
   `outputDirectory/setup.json`. It adds the human manager and deployment
   administrator as Application `MANAGER`s and the service handle as `CONTRIBUTOR`.
   It does not connect, create accounts, scan or overwrite existing output.

3. Review model bindings and the prepared grants. Deploy that private Application
   using the [deployment walkthrough](#deploy-the-plowshare-application), as the
   configured administrator. Keep Python stopped and the schedule paused. This
   first deployment creates the project needed for token scopes. Do not call
   `project create` first: its provisioned source prevents first source deployment.
   The temporary provider declarations name the service handle until provisioning
   replaces them with the token principal; they are not ready for service traffic.

4. Provision using the **filled** setup configuration:

   ```sh
   python -m plowshare_privacy.setup --config "$PRIVACY_PRIVATE/setup.json" --login provision
   ```

   `PRIVACY_PRIVATE` is the prepared output directory. `--login` prompts invisibly
   for the configured administrator's password, uses the public HTTP login boundary,
   performs administrative work through typed SDK WebSocket operations and revokes
   that temporary session afterward. It does not read or rotate Desktop/CLI saved
   credentials. Alternatively omit `--login` and inject an existing human
   administrator bearer through `PLOWSHARE_SETUP_TOKEN`; the script does not revoke
   an injected session. Use your configured HTTPS origin for remote password login.

   Provisioning creates or uses the enabled service account, grants the human
   `MANAGER` membership and service `CONTRIBUTOR` membership, and issues a named
   credential with only this project's `CONTRIBUTOR` scope. It refuses an existing
   token with that name. The credential goes in `service-token` with POSIX mode
   `0600`; `identity.json` retains its handle, token UUID, principal and expiry.
   The output directory is mode `0700`. The script prints no credential or password.
   On other operating systems, restrict the directory/file ACLs yourself.

5. The script fills the private `server/` declarations with the returned principal.
   Deploy this updated copy with a **new request UUID** and the current active
   revision as `expectedRevision`. Verify the retained deployment receipt. Set
   `PRIVACY_TOOL_ACCOUNT` to the `principal` in `identity.json` and
   `PRIVACY_TOOL_PROVIDER` to `privacy-scanner`. Inject `service-token` into the
   collector's configured `tokenEnvironment`, then start Python as described below.
   Human administrator credentials never go into the collector or dashboard.

### Run investigations as the service identity

Source deployment currently enrolls the packaged schedule under the deploying
administrator. The setup script **does not transfer schedule ownership**. Its
scheduled `privacy_tick` action is deterministic and invokes no model; the service
credential owns Python uploads and tools. To make the completion-triggered agents
run under that service identity, process the project's Relay subscriptions using
the service bearer:

```sh
export PLOWSHARE_TOKEN="$(cat "$PRIVACY_PRIVATE/service-token")"
bin/plowshare-cli --server "$PLOWSHARE_ORIGIN" relay process '{"project":"network-privacy-watch","limit":32}'
```

Set `PLOWSHARE_ORIGIN` explicitly to the collector's configured origin. Clear any
named human connection selection for this command. Supply credentials through your
process secret manager in production; the `cat` command illustrates private-file
loading without printing the token. Inspect the result and retained Relay/job state
after a disconnect; do not blindly retry an uncertain processing call.

For unattended operation, the Application's `server/relay-workers.json` explicitly
enrolls the [automatic Relay worker](../../docs/relay.md#automatic-subscription-workers).
The setup helper fills its account with the returned service token **principal**,
together with the tool/port declarations. Deploy that prepared private Application;
the running server discovers it within its Relay configuration interval (30 seconds
by default), without a restart or a human-account worker binding. If upgrading an
existing private source copy, add `server/relay-workers.json` with `version: 1` and
`account` set to the same service principal, then redeploy through the documented
revision check. Keep its owning service account a CONTRIBUTOR. Removing the declaration
pauses automatic processing; it preserves retained scan input, offsets and jobs.
Without enrollment or an explicit processing pass, completed scans remain retained
but do not launch an investigation. Contributor processing preserves the broker's current
topic policies, using the standard default for a new topic. Declared retention in
`Relay/topics.json` is applied only by a manager processing pass; it does not grant
policy-write access or prevent this service account from processing investigations.
The service token cannot manage or assume the administrator-owned packaged schedule.
Separating schedule ownership needs a platform lifecycle feature; this setup does not
claim to provide it.

### Rotation and interrupted setup

Use [service token administration](../../docs/server-administration.md#service-accounts-and-scoped-tokens)
to rotate the retained token UUID before expiry and replace the injected credential.
Keep the same token identity and collector journal. Rotation keeps all `server/`
bindings valid. Removing membership, revoking the token or disabling the service
account removes its access to subsequent work.

Before its first mutation, provisioning flushes `provisioning-intent.json`. Any
later rerun is refused, including after successful setup; `provisioning-completed`
marks confirmed completion. After a failed attempt, inspect service accounts,
project access, named token metadata and audit history as the administrator.
Do not delete the intent and retry blindly. If a token exists but its credential
reply was lost, rotate that **existing token UUID** explicitly; the server cannot
recover its old secret. Save the replacement privately and use its retained
principal when completing the private declarations. Review and deploy the corrected
source before starting Python. If even token creation is unconfirmed, retain the
intent and reconcile it first. The script never reconnects or replays mutations.

## Install the Plowshare Application

1. Prepare a private copy of [network-privacy-watch](network-privacy-watch). Its
   valid root `plowshare.json` identifies `network-privacy-watch`. Add explicit manifest
   account grants and matching server project membership for the operator and
   collector. The supplied manifest has **no grants**. Use a project-scoped service credential;
   the collector needs work access for evidence uploads and reading for reports.
2. Replace provider account placeholders with the authenticated execution identity
   (the service token principal, not its handle) in the private Application root
   `server/tools.json` and `server/ports.json`, matching the manifest account
   grant and project membership. Configure a server FileStore and grant the deploying administrator MANAGER
   access to its destination. Read `application.deployment.status`, then use
   `application deploy ./private-application '<deployment JSON>'` or Desktop
   **Applications → Deploy**. See the [deployment walkthrough](#deploy-the-plowshare-application).
3. Deployed agents, orchestrations and schedules load from root `agents/`,
   `orchestrations/` and `schedules/`; named swarms
   load from `swarm/` and Relay from root `Relay/`. They need no desktop file
   session. Verify effective rosters after deploying the Application-owned tools below.
4. Before deployment, replace provider account placeholders in the private
   Application's [`server/tools.json`](network-privacy-watch/server/tools.json)
   and [`server/ports.json`](network-privacy-watch/server/ports.json). Match the
   authenticated Python principal; its owning service handle needs the manifest grant
   and project membership. These
   declarations deploy with the Application; no global server edit or restart
   is needed. The collector receives EGRESS on `schedule.due` and the request
   topic, and INGRESS on requests/completions. It initiates an authenticated
   outbound WebSocket to the explicit Plowshare origin. Explicit provider scope assignments
   validate the coordinator before Python starts; the provider publishes and
   renews its live catalogue while serving.
5. The package contains a paused server schedule named `network_scan`. After deployment
   and source reconciliation, inspect `schedule.files` for its actual internal name
   and set `config.schedule` to that name. Review timing and resume it through the
   Schedule UI or `schedule.pause`. Change packaged timing through a new Application
   deployment. The separate `install-schedule` SDK command remains useful for a
   manually managed source tier; deployment-managed source refuses direct edits.
   Server schedules retain the authenticated installer's project permissions.

The native schedule action is `privacy_tick`, a deterministic JavaScript
orchestration that records occurrence availability without calling a model.
Python independently consumes that exact schedule from the project's
`schedule.due` stream. Other schedules are ignored. Legacy global schedules are
not used: SDK ports do not expose system scope.

The result route starts `investigate_network` under `privacy_coordinator` for the
baseline or a changed observation/coverage gap. Unchanged collections are retained
without launching another model investigation. The
analyst and reviewer have narrower tools, and the conductor retains a draft
report with source dependencies. Memory supplies previously established operator
context where permitted. Document-derived evidence and conclusions stay in the
information store; the Application does not copy restricted evidence into memory.
No shell, firewall or device-changing tools are granted to these agents.

## Deploy the Plowshare Application

The Application is [network-privacy-watch](network-privacy-watch). Its root manifest
and resources run inside Plowshare; the Python collector and web UI are deployed
separately on a host with access to the configured network.

Prepare a private copy with explicit account grants, configured models and named-tool
declarations in `server/`. As a server administrator, read status and deploy
that copy using your configured server FileStore alias:

```sh
bin/plowshare-cli application deployment status '{"project":"network-privacy-watch"}'
bin/plowshare-cli application deploy ./private-application '{"project":"network-privacy-watch","requestId":"11111111-1111-1111-1111-111111111111","expectedRevision":null,"destination":{"store":"applications","path":"network-privacy-watch"},"writableAreas":[]}'
```

Use a fresh retained request UUID; the value above is illustrative. For updates,
use the active revision from status. Read the retained receipt after uncertain
delivery. Desktop exposes the same folder deployment and retained revision
activation. No copy into an unrelated definitions directory is needed. The
[Application deployment manual](../../docs/projects.md#deploy-update-and-activate-an-application)
covers limits, authorization, identity preservation, rollback and recovery.

Deployment enrolls the packaged paused schedule for server reconciliation. It
does not launch the Python service or configure global model bindings. Its
`server/` declarations provide the scoped tool/provider and Relay port authority.
Keep those explicit configuration steps above and verify them before resuming
collection. Deployment does not execute Python, change a device or modify a
firewall. DISJOINT adoption remains available for a separately managed pipeline.

## Run the Python web interface

Configure the Application-owned native tool declarations described below and set the explicit provider
name/account variables before enabling agent calls.

Provide the web listener explicitly:

```sh
: "${PRIVACY_BIND:?Set the collector web bind address}"
: "${PRIVACY_PORT:?Set the collector web port}"
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" --tool-provider "$PRIVACY_TOOL_PROVIDER" --tool-account "$PRIVACY_TOOL_ACCOUNT" serve --bind "$PRIVACY_BIND" --port "$PRIVACY_PORT"
```

Open the listener in a browser and enter the **web** bearer. It stays in tab memory
and is separate from the Plowshare credential, which never enters the browser.
Use HTTPS at your deployment proxy for a remote interface. The browser's request
identity uses `crypto.randomUUID`, so scan submission needs HTTPS or a loopback
development browser context. Static assets contain no household evidence; APIs
require the bearer and mutations require JSON. The interface has no external CDN,
analytics, cross-origin grants or arbitrary command/target input.

The dashboard shows collection state, recent scan receipts, source evidence,
comparison gaps and related project reports. **Refresh** obtains current status;
**Request a scan** publishes a new request through the SDK and Relay. It does not
call a scanner directly or bypass grants. A published scan is distinct from a
completed agent investigation. Draft/final report status comes from the project
information store. The dashboard shows the latest 50 scan receipts and the first
100 accessible project reports that include a retained scan input.

For headless operation, `once` performs one intake/collection pass. An operator
process supervisor can run/restart the collector; Plowshare remains the scheduler
and durable investigation runtime.

## Python scanning and named tools

For an immediate diagnostic scan on the collector host:

```sh
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" scan
```

This runs the same configured, bounded collector without connecting to Plowshare
or requiring credentials. It prints observations labelled `retained: false`; it
does not upload evidence, alter the durable journal or start an investigation.
Its targets and ports still come exclusively from private operator configuration.

The running Python web service exposes a bearer-protected tool catalogue at
`GET /api/tools`. Each entry declares its exact input JSON Schema, rejects extra
properties and identifies read versus Relay submission effects. Invoke it with
`POST /api/tools/<name>` and a JSON object matching that schema:

| Tool | Input | Result |
| --- | --- | --- |
| `network.scope` | `{}` | Configured targets, ports, limits and collection mode |
| `network.scan` | `{"request_id":"<caller-retained UUID>"}` | Relay publication receipt; not collection completion |
| `network.scan_status` | `{"request_id":"<same UUID>"}` | Local publication state and collection receipt, when available |
| `network.scan_list` | `{}` | Latest 50 local collection summaries |
| `network.evidence` | `{"scan_id":"<scan UUID>"}` | Observations, changes, gaps and retained revision, when available |
| `network.destinations` | `{"scan_id":"<scan UUID>"}` | Observed DNS destinations, source time, fixture label and gaps |

All calls require the configured web bearer. Scan calls retain the caller's
request identity before SDK publication and use the same Relay pipeline as the
dashboard. Reusing an identity never resubmits an uncertain publication. Unknown
identities fail explicitly rather than returning an empty successful scan. Read
tools inspect the collector's receipts; they do not assert that an LLM report has
finished. No tool accepts a model-selected network scope, shell command or path.

Plowshare agents reach these capabilities through normal named tools using the
[Relay tool façade](../../docs/relay-tools.md). HTTP names keep their dotted form;
model tool names use underscores. Relay also remains the scheduling and collection
path.

## Agent tools through the SDK

The coordinator sets `dynamic: true` and receives `network_scope`, `network_scan`,
`network_scan_status`, `network_scan_list`, `network_evidence` and
`network_destinations` through the `network_scanning` provider scope explicitly
assigned in root `plowshare.json`. These external names are absent from its `tools`
field. That scope grants both visibility and execution, limited to these six tools
and the configured execution principal. `relay_tool_read` retains its ordinary
named built-in grant in `tools`. Loading validates the policy; discovery and
per-call permission/availability checks happen at runtime.
The SDK exposes a declaration and async Python handler for each capability; Plowshare installs the façade into its
normal tool registry. The model never constructs a Relay envelope or selects a
provider or topic.

For legacy server startup bindings, the SDK can still export matching declarations
**offline**, using the actual project and provider execution identity from private configuration:

```sh
: "${PRIVACY_TOOL_PROVIDER:?Set the tool provider name}"
: "${PRIVACY_TOOL_ACCOUNT:?Set the authenticated execution identity; service tokens use their principal}"
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" --tool-provider "$PRIVACY_TOOL_PROVIDER" --tool-account "$PRIVACY_TOOL_ACCOUNT" tool-bindings
```

That exporter remains available for legacy startup bindings. For this Application,
use its root `server/tools.json`: `bindings` is empty and the provider declaration
fixes the execution account, name prefix and catalogue lease. No tool schema needs
to be known at deployment. Root `plowshare.json` supplies `executionAccount`,
`toolScopes` and `toolGrants`; only `privacy_coordinator` owns the network scope.
The setup script fills the execution principal in the manifest and server files.
For manual setup, replace it in all three files before deploying and keep
`provider` equal to the configured Python provider name. Global tool/port fragments
are unnecessary. Agents can deploy before the collector publishes its catalogue;
network tools appear when that publication arrives.

The collector publishes `plowshare-tool-catalog/1` through the SDK and renews it
while polling (100 seconds by default for the packaged 300-second lease).
`--tool-catalog-renew-seconds` must be less than the configured lease. UUIDs and
publication states are retained in private `tool-catalogue.jsonl`. A failed
publication stops intake with an attention state; restart publishes fresh current
metadata and never replays a tool effect. Inspect the recorded UUID through
Relay logs if its publication outcome is uncertain.
The binding derives only provider request egress and result ingress; ordinary
collection topics retain their separate port grants. Declaring handlers does not
authorize catalogue publication or give agents tool grants; deployed provider
authority enables dynamic catalogue updates. All SDK languages offer the
same [façade](../../docs/relay-tools.md#equivalent-examples); Python implements this collector.

Start `serve`, `once` or `reconcile` with those two flags before the subcommand.
The SDK's exclusive SQLite tool journal lives under the configured private state
directory. It records execution before request acknowledgement, converts an
interrupted handler to UNKNOWN, and reconciles a lost result reply by reading the
exact retained event. It never repeats an ambiguous handler or result publication.
The state directory's collector lock also excludes competing collectors.

`network_scan` accepts `{}`. The harness invocation UUID becomes the scan request
UUID, so the model cannot invent another request ID after uncertainty. Its
scan publication retains the native tool request as its parent; collection and
subsequent investigation inherit the same Relay effect budget. Its
COMPLETED result confirms scan request publication; use `network_scan_status` with
that retained request UUID to follow collection, and inspect project information
for the investigation. The other read tools have the same inputs as their HTTP
counterparts. No model tool accepts targets, ports, a shell command or a path.
If the native tool call itself returns UNKNOWN, use `relay_tool_read` with its
invocation UUID before considering any new operation.

The optional existing outgoing compatibility peer remains available through
`outgoingPeer` in private configuration. It uses the same public SDK contract as
Home Assistant and its separate outgoing receipts. Enable either that peer or the
native tool provider for a collector. The packaged agent definitions use named
native tools; choosing outgoing compatibility requires corresponding outgoing
grants/instructions. Neither path deploys the Python service.

## Receipts, disconnects and recovery

The collector locks one adapter-owned journal. It copies selected Relay events
durably **before acknowledging their batch**, then performs scans outside the
30-second delivery lease. Acknowledgement means copied availability, not finished
collection. Independent groups have independent cursors; do not deploy different
collector configurations competing in the same group.

Stable scan, upload and completion identities survive redelivery. Collected
evidence is retained before the completion event, whose parent preserves the
original scheduled/manual event. A process interrupted before retaining a scan
may repeat that read-only collection. An interrupted/uncertain upload or publication
stays pending and is never automatically replayed. Retained source names and exact
text, or retained Relay event identities and text, can positively reconcile it:

```sh
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" --tool-provider "$PRIVACY_TOOL_PROVIDER" --tool-account "$PRIVACY_TOOL_ACCOUNT" reconcile
```

Stop the web process first to release its journal lock. Then reconcile and restart.
Absence after retention, mismatched text, or missing evidence leaves the outcome
unresolved. Retention gaps require explicit operator inspection and acknowledgement
of the exact loss; the worker does not skip them. An expired parent cannot be
replaced with a fresh root to bypass causation. SDK disconnection stops collection;
the dashboard remains available with an attention state. Reconnect by restarting
after inspecting the recorded outcomes.

Journal identity includes deployment scope and collection configuration. A foreign
journal, competing local owner, excessive queue or corrupt source refuses work.
State is bounded at 1 MiB and 1,000 receipts/requests each, without automatic
pruning. For longer operation, archive settled state only after
confirming durable evidence and the consumer cursor; choose a new state directory
for a deliberate scope change. Do not delete uncertain receipts to force retries.

## Verify without a database or live household data

Install check tools into the isolated interpreter:

```sh
build/privacy-python-env/bin/python -m pip install -e ./sdk/python -e './integrations/network-privacy[check]'
PLOWSHARE_PRIVACY_PYTHON="$PWD/build/privacy-python-env/bin/python" ./gradlew :plowshare-network-privacy:pythonCheck
./gradlew :plowshare-network-privacy:test
./gradlew check formatCheck
```

The Python gate checks strict types and formatting, actual TCP/HTTP listeners on
ephemeral fixture ports, authentication, scope/invalid inputs, journal handoff,
redelivery, gaps, unknown outcomes, native tool declarations/handlers, outgoing compatibility operations and public SDK WebSocket transport. The Gradle
project is **test-only**: it checks the Application using real definition loaders,
the Relay routing sandbox and the deterministic schedule script. It ships no Java
collector. Neither suite starts PostgreSQL or an inference provider.

For a synthetic browser preview, run the explicitly local fixture with your chosen
listener values:

```sh
build/privacy-python-env/bin/python -B integrations/network-privacy/tests/preview.py --bind "$PRIVACY_BIND" --port "$PRIVACY_PORT"
```

Use the printed fixture token. The preview labels its observations and report as
synthetic, does not contact Plowshare and does not run an LLM. Production uses the
SDK connection, not this fixture.

Live acceptance still requires your configured server, collector scope and model:
verify one scheduled scan and one dashboard request; read their retained evidence;
inspect the actual Relay branch and orchestration/job receipts; confirm a related
report appears with its source dependencies. Exercise collector/server disconnects
and a missing DNS export. Confirm a local model binding from actual request/model
logs if local inference is the deployment goal. Fixtures do not prove those live
outcomes. Traffic capture, router-specific exporters and approved remediation
integrations are natural additions using the existing public SDK boundary.

## Interactive connections use the same registry

An interactive CLI/TUI/desktop session can connect a provider with
`tool scope connect` using the shared command interface:

```text
tool scope connect {"project":"coding-project","scope":"linear","provider":"linear","prefix":"linear_","grants":["*"],"agents":["ticketer"],"leaseSeconds":300}
```

The target agent must declare `dynamic: true`. The authenticated user owns this
connection and its grants; no server JSON catalogue is required. The reply includes
an isolated `provider` routing name and `account`. Bind the SDK tool provider to
those returned values, publish its discovered catalogue and serve calls on the
same authenticated event socket. `tool scope list` inspects current connections;
`tool scope disconnect` removes one. New connections receive a fresh namespace.
Socket loss stops tool availability; neither reconnection nor UNKNOWN effects are
replayed automatically. A one-shot CLI connect closes with the command, so active
providers use a persistent SDK or interactive client connection. These commands do
not launch the external provider.

Applications use the same operations under their declared service execution
principal, but their requested provider, tools and agents must match the explicit
manifest assignments. The packaged collector uses its deployed provider authority
rather than the interactive connection command. See [provider scopes](../../docs/tool-scopes.md)
for equivalent SDK operations and the present MCP transport/schema limits.
