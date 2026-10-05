# Home Assistant integration

This adapter supplies selected HA states, configured service actions and selected
state_changed observations to the [external integration runtime](../runtime/README.md).
Home Assistant owns devices and immediate automation. A Plowshare project owns
agents, skills, orchestration and investigation. The adapter uses the existing
[HA WebSocket API](https://developers.home-assistant.io/docs/api/websocket/);
Plowshare requests use its existing WebSocket SDK. There is no HTTP fallback or
core integration subsystem.

## Build and configure

```sh
./gradlew :plowshare-integration-home-assistant:installDist
```

The executable is
integrations/home-assistant/build/install/plowshare-integration-home-assistant/bin/plowshare-integration-home-assistant.
Copy [examples/config.json](examples/config.json) into a private operator directory,
then replace the example origins, project, entity and action mappings. Set the
PLOWSHARE_TOKEN and HA_TOKEN environment variables using your normal secret
management. Configuration stores environment-variable names, never tokens.
Both endpoints are HTTP(S) origins; HA connects to /api/websocket and Plowshare to
/v1/events. The configured Plowshare account needs Contributor access to the selected project.
Use a [Plowshare service account and project-scoped token](../../docs/server-administration.md#service-accounts-and-scoped-tokens) for this adapter. Its `pss_` credential belongs to Plowshare; the Home Assistant token remains a separate credential. Inject both through the configured environment variables. Rotate the same Plowshare token to retain integration work identity.

Check the file without credentials, network connections or journal creation:

```sh
plowshare-integration-home-assistant --check /path/to/config.json
```

Launch with the same executable and config path, without --check. Only one process
may own the journal. Keep private config and journal data outside tracked source.
Check validates installed adapters, adapter schemas, route read/action aliases and
registered script syntax/module exports; it does not prove that mapped devices,
services or project definitions exist on the live systems.

## Mapping capabilities

entities maps public aliases to fixed HA entity IDs and an attributes allowlist.
unit_of_measurement is retained for numeric routing. Selected attributes must be
strings, bounded numbers or booleans; null attributes provide no evidence and are
omitted. Selected object/array attributes require a registered DTO and are
refused. Action parameters use the same primitive contract, with strings bounded
to 4096 characters and binding-specific type, enum and numeric checks. Unselected states/attributes
and HA user IDs are not sent to Plowshare. A missing, unknown or unavailable state
stays explicit; connection loss marks cached states stale.

subscriptions lists readable aliases. The first release subscribes to the HA
state_changed stream and locally emits selected aliases. It subscribes before
reading the snapshot, reconciles bounded buffered updates by source time, then
emits resync observations. Startup and reconnect snapshots establish threshold
state without launching historical crossings. Live low-to-high readings can start
the configured route after its cooldown. There is no reconstruction of missed
events, subscribe_trigger or arbitrary custom-event support
in this first slice.

Set `holdSeconds` on a threshold route to require an observed interval above the
threshold before investigation. The shared runtime checks the current selected
reading at expiry and supplies a `threshold.held` callback for optional JS routing.
Startup/reconnect snapshots suppress historical starts; gaps, restart and
unavailable evidence cancel pending intervals. See the
[held-threshold contract](../runtime/README.md#held-thresholds).

For frequent sensors, configure the shared runtime's optional
[retention and queue policies](../runtime/README.md#journal-and-recovery).
`same-state` coalescing combines only equivalent queued readings; threshold changes,
connection gaps and resync observations remain separate. Configure the retained
duplicate-ID window for the expected event rate and replay delay. Intake stops at
the queue or journal bounds instead of discarding transitions.

actions fixes service names, entity targets, parameter schemas and required fields.
Parameters support string, number, integer and boolean plus enum/numeric/string
bounds. The caller cannot override entity_id, device_id, area_id or target. Scenes
and notifications use the same call_service capability. The current typed result contract supports action receipts; `returnResponse: true`
is refused before connection until a service-specific response DTO is registered. A successful service call
returns acknowledged true and verification not_observed; a lost acknowledgment is
UNKNOWN and is never resent automatically.

To prevent an acknowledged action's state changes from starting another investigation,
enable the binding's optional
[causal feedback policy](../runtime/README.md#causal-feedback-policy),
including an optional bounded depth for observed automation ancestry.
It matches selected event context IDs or configured observed parent chains against
recorded action acknowledgments, with a configured depth and expiry. Matching
events remain available to JS for verification; their pipeline starts and held intervals are suppressed. Missing
or ambiguous context does not justify a match. Context correlation and physical
verification remain separate evidence.

Model calls use this existing outgoing message:

```json
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"actions.execute","arguments":{"action":"evening_scene","parameters":{}}}}]}
```

Retain each intended request UUID and work ID. Follow existing work using
outgoing_read/outgoing.status. Replacing an uncertain action with a fresh UUID can
repeat a physical effect. No HA remote task is invented to bypass claim fencing.

## Project and pipeline

The example declarative route starts investigate_office_heat through the configured
house_coordinator agent and sends its completed result through send_report. Adapt
the [optional project examples](examples/project) to your existing resource tiers:

- Agent and orchestration definitions under .plowshare/agents and
  .plowshare/orchestrations for a connected project, or the corresponding server
  project's agents/orchestrations directories.
- The skill package under .plowshare/skills/house-evidence, or the server project's
  skills directory. Skill execution remains an explicit user action.

Use the existing project orchestration validation/install workflow when installing
definitions. These examples request selected reads and grant pipeline starts.
Actions available through the outbox are constrained by
the operator's selected binding policy. The orchestration returns its report and
the runtime delivers the notification.

For unattended event starts, install the resources in the server's project tier
or an account Personal tier visible to the SDK caller. This headless SDK session
does not serve a client filesystem: definitions available only through another
client's .plowshare directory are not automatically visible to it. Use those files
as drafts for the existing validation/install workflow.

## First installation and live acceptance

1. Create or choose a server project through project administration. The sample
   uses `home-assistant`. Install the agent, orchestration and skill through the
   existing project resource workflow before starting the external runtime.
2. Copy [the project JSON example](examples/project/plowshare) into the registered
   workspace's selected identity file, preserving its existing project name.
   If a higher-priority marker already exists, update that marker instead of
   creating a shadowed root file. This holds execution caps, command policy and
   skill visibility. Set `caps.autoIncrease` to `true` when this project should
   approve additional finite execution chunks for long-lived work. See
   [project settings](../../docs/projects.md#project-runtime-configuration).
3. Copy the connection config into a private operator directory. Match the project
   name, select real entity aliases and configure the notification action. Supply
   its token environment variables, then run `--check`. The checked-in example
   origins intentionally cannot connect to a real deployment.
4. Launch the runtime and ask the project agent for selected office evidence.
   Confirm that the reply distinguishes available, missing and unavailable
   readings. Reuse recorded work IDs to inspect pending work.
5. With your chosen reversible action, verify its acknowledgment and a subsequent
   physical state observation separately. Exercise the configured threshold
   crossing and confirm one investigation and one delivered report. Repeat with
   the optional JS handler if it will be used. These are live acceptance steps;
   a passing `--check` or fixture does not substitute for them.

The project JSON holds project runtime settings. Connection mappings, credential
references and the integration journal stay in the private adapter deployment.
There is no default live connection or preinstalled HA project.

For custom routing, copy [office.mjs](examples/office.mjs) beside the private config
and add "script": "office.mjs" to the house binding. It replaces the declarative
handlers, including completion delivery, while retaining route policies. The
example JS bounds notification text to the configured parameter size. Without JS,
an oversized report fails validation visibly instead of silently truncating it.

## Validation

The protocol fixtures cover both WebSocket transports, scripted event-to-pipeline
delivery, outbox reads, reconnect snapshots, policy refusal and lost acknowledgment.
`HarnessPipelineTest` also boots the actual Plowshare server against migrated
PostgreSQL, installs the project examples, and uses authenticated SDK sessions and
the real model/tool harness. A deterministic model transport issues `outgoing_peers`,
`outgoing_send` and `outgoing_read`; the returned HA evidence drives the report.
Available and unavailable sensors remain distinct. Declarative and JS event routes
each run the real conductor, finish its stages and send one configured notification.
Only HA and inference are simulated in these harness tests; they use no live devices.

Run the real-server acceptance fixtures with Docker available:

```sh
./gradlew :plowshare-integration-home-assistant:test --tests '*HarnessPipelineTest'
```

Server/Spring/PostgreSQL test dependencies are confined to the test classpath. The
packaged integration process still uses only the external runtime and SDK.
Live reads, a reversible action with observed outcome, and an actual investigation
and phone notification still need validation against your chosen mappings.

History/statistics, media/cameras, HA configuration editing and HA MCP consumption
remain later capabilities. HA MCP can complement tool access; event subscriptions
use WebSocket in this implementation.
