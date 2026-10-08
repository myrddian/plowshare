# Network privacy: a Plowshare Application with a Python integration

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

## What the first example collects

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
attribution remain its responsibility; this example does not parse Pi-hole or
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

## Install the Python application

From the repository root, with Python 3.11+:

```sh
python3 -m venv build/privacy-python-env
build/privacy-python-env/bin/python -m pip install ./sdk/python ./integrations/network-privacy
```

The Python SDK has no published remote package yet; install the local SDK alongside
the integration. This example uses only its public typed operations. For a remote
collector, build both wheels locally and transfer them using your normal deployment
workflow. Build the integration with `python -m build integrations/network-privacy`.
Its wheel includes the HTML, CSS and JavaScript interface. Application definitions
and deployment examples remain in this directory and are installed separately.

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

## Install the Plowshare Application

1. Prepare a private copy of [examples/application](examples/application). Its
   valid root `plowshare.json` identifies `network-privacy`. Add explicit manifest
   account grants and matching server project membership for the operator and
   collector. Templates have **no grants**. Use a project-scoped service credential;
   the collector needs work access for evidence uploads and reading for reports.
2. Configure a server FileStore and grant the deploying administrator MANAGER
   access to its destination. Read `application.deployment.status`, then use
   `application deploy ./private-application '<deployment JSON>'` or Desktop
   **Applications → Deploy**. See the [deployment walkthrough](#deploy-the-plowshare-application).
3. Deployed agents, orchestrations and schedules load from root `agents/`,
   `orchestrations/` and `schedules/`; named swarms
   load from `swarm/` and Relay from root `Relay/`. They need no desktop file
   session. Verify effective rosters after configuring the server tools below.
4. Merge [server-ports.json](examples/server-ports.json) into private **server
   deployment configuration**, replace its account/project/groups, and load it
   using the documented [JSON/YAML deployment configuration](../../docs/message-filtering.md#configuration-format).
   Install the named-tool bindings described below alongside these ports, then
   restart the server to apply both and verify rosters, model bindings and routes. Project Relay files alone do not grant
   SDK ingress/egress. The collector receives EGRESS on `schedule.due` and the
   request topic, and INGRESS on requests and completions. It initiates an
   authenticated outbound WebSocket to the explicit Plowshare origin.
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
information store; this example does not copy restricted evidence into memory.
No shell, firewall or device-changing tools are granted to these agents.

## Deploy the Plowshare Application

The Application is [examples/application](examples/application). Its root manifest
and resources run inside Plowshare; the Python collector and web UI are deployed
separately on a host with access to the configured network.

Prepare a private copy with explicit account grants, configured models, named-tool
bindings and Relay port grants. As a server administrator, read status and deploy
that copy using your configured server FileStore alias:

```sh
bin/plowshare-cli application deployment status '{"project":"network-privacy"}'
bin/plowshare-cli application deploy ./private-application '{"project":"network-privacy","requestId":"11111111-1111-1111-1111-111111111111","expectedRevision":null,"destination":{"store":"applications","path":"network-privacy"},"writableAreas":[]}'
```

Use a fresh retained request UUID; the value above is illustrative. For updates,
use the active revision from status. Read the retained receipt after uncertain
delivery. Desktop exposes the same folder deployment and retained revision
activation. No copy into an unrelated definitions directory is needed. The
[Application deployment manual](../../docs/projects.md#deploy-update-and-activate-an-application)
covers limits, authorization, identity preservation, rollback and recovery.

Deployment enrolls the packaged paused schedule for server reconciliation. It
does not configure global model/tool bindings or Relay ingress/egress grants.
Keep those explicit configuration steps above and verify them before resuming
collection. Deployment does not execute Python, change a device or modify a
firewall. DISJOINT adoption remains available for a separately managed pipeline.

## Run the Python web interface

Install the native tool bindings described below and set the explicit provider
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
information store. This slice shows the latest 50 scan receipts and the first
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

The coordinator explicitly grants `network_scope`, `network_scan`,
`network_scan_status`, `network_scan_list`, `network_evidence`,
`network_destinations` and `relay_tool_read`. The SDK exposes a declaration and
async Python handler for each capability; Plowshare installs the façade into its
normal tool registry. The model never constructs a Relay envelope or selects a
provider or topic.

Export the matching server bindings **offline**, using the actual project and
provider account from private configuration:

```sh
: "${PRIVACY_TOOL_PROVIDER:?Set the tool provider name}"
: "${PRIVACY_TOOL_ACCOUNT:?Set the credential's owning account}"
build/privacy-python-env/bin/plowshare-privacy --config "$PRIVACY_CONFIG" --tool-provider "$PRIVACY_TOOL_PROVIDER" --tool-account "$PRIVACY_TOOL_ACCOUNT" tool-bindings
```

Merge that output into private server deployment configuration, alongside the
schedule/scan [ports](examples/server-ports.json), and restart the server. A
[placeholder export](examples/server-tool-bindings.json) shows the six declarations.
The binding derives only provider request egress and result ingress; ordinary
collection topics retain their separate port grants. Declaring handlers does not
register them dynamically or give agents tool grants. All SDK languages offer the
same [façade](../../docs/relay-tools.md#equivalent-examples); Python is this example's
implementation language.

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
pruning. For this example's longer operation, archive settled state only after
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
