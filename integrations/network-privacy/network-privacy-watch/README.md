# Network Privacy Watch

Network Privacy Watch is a defensive network privacy Application. It combines a
Python collector and web dashboard with Plowshare's scheduler, Relay, project
information store and agents. It helps answer: **What changed on my network, what
evidence supports that, and what should I investigate?**

The Python service runs separately on a host with access to the configured
network. Plowshare can run locally or on a cloud provider. The connection uses
the public SDK; it needs no shared filesystem or Java code in the collector.
Agents can use a configured local model or another served model through the
Application's `reasoning` binding.

## How it works

1. A Plowshare schedule or a dashboard request makes a scan request available
   through project-scoped Relay.
2. Python probes explicitly configured IP addresses and TCP ports. It can also
   read Pi-hole v6 device associations and recent DNS queries, or a normalized DNS
   observation export from an external monitoring system.
3. The collector compares observations, uploads evidence to the project's
   information store and publishes a completion event with retained identities.
4. Relay starts an investigation for a baseline or changed observations when the
   collector's operator admission policy permits it. Coverage changes remain findings;
   unchanged gaps remain visible evidence. A coordinator works with an analyst and reviewer to retain a
   draft report linked to its source evidence.
5. The dashboard displays health, device history, expected findings, collection
   receipts, observations, gaps and related
   reports. Agents also get granted named tools for requesting a scan and reading
   its scope, status, evidence and DNS destinations. Operator labels and observed
   device identities accompany evidence, with explicit gaps for unavailable data.

Python handles collection and comparison. Plowshare owns scheduling, retained
evidence and investigation work. The LLM contributes interpretation and review.
A scan publication, completed collection and completed investigation are separate
outcomes.

## Application contents

This folder is the deployable Application root. It contains no `.plowshare/`
directory.

| Path | Purpose |
| --- | --- |
| `plowshare.json` | Application identity `network-privacy-watch`, account grants and limits |
| `agents/` | Built-in grants and dynamic opt-in; external access comes from manifest provider scopes |
| `hooks/` | Ask-agent keyword recommendations before model requests; existing reports, scan evidence and vendor references have separate read paths |
| `orchestrations/` | Deterministic schedule action, scan investigation, device profile research and per-device privacy assessment |
| `schedules/network_scan.json` | Packaged scan schedule, initially paused |
| `Relay/` | Project topics and the scan-completion route |
| `server/` | Project tool/provider catalogue, Relay ports and service-owned worker enrollment |

The coordinator sets `dynamic: true`; its `tools` field lists built-in grants.
The eight external network tools are granted through the `network_scanning` provider
scope in `plowshare.json`, without listing them again in the agent definition.
Analyst and reviewer opt into dynamic tools and receive the separate
`network_evidence_read` scope, which excludes scan submission.
Those permissions control visibility and execution. Python publishes the schemas
at runtime, and each call checks current registration, permission and availability.

The Python service, private configuration, credentials and collector journal
stay outside this folder. Deploying the Application does not start Python or
install its dependencies.

The Ask assistant retrieves existing reports and findings through the
`information-discovery` skill and reads the matching report, including its draft
and review status. Scan tools return collection evidence, not assessed reports.
The `hooks/20-read-routing.js` `prompt.pre` handler reinforces that distinction
with case-insensitive whole-word matches for reports/findings, documentation or
settings, scans and coverage flags. It recommends reads only for
`privacy_assistant`; unmatched questions and other agents get no addition.
Its temporary guidance never quotes user text, starts work or changes grants.
It is a keyword hint, not an intent classifier or a guarantee of model compliance.
Other hooks can handle the same event: distinct hook names run in filename order
and their prompt additions accumulate. See [hook authoring](../../../docs/manual/12-hooks.md).

Each specialist has a defined input and handoff; tool grants remain specific to
that role:

| Agent | Input and workflow | Output and owner of the next step |
| --- | --- | --- |
| `privacy_assistant` | Household question → existing report discovery, device/evidence reads, or an explicit queued request | Sourced prose or a retained request receipt; specialist replies arrive later |
| `privacy_coordinator` | Classify read/new-work requests → submit a requested scan once → reuse collection/workflow handles | Actual collection/investigation state; scripts own analysis, review and report writes |
| `privacy_analyst` | Supplied scan texts → scope/coverage checks → missing operator context only when needed | Markdown assessment passed by `investigate_network` to the reviewer |
| `privacy_reviewer` | Actual assessment plus scan texts or one dossier → check evidence → use the caller's network/device response schema | Review verdict and corrections; the script owns correction rounds and report retention |
| `privacy_device_profiler` | Collector observations, confirmed-model context and NVD leads → optional bounded profile reads | Six-field JSON draft; the operator reviews it before saving a profile |
| `privacy_device_assessor` | One retained dossier → relevant pinned context → structured concerns → one correction if requested | Assessment JSON; `assess_network_device` validates, reviews and renders the report |

The shared `information-discovery` skill runs the read-only librarian with its
own discovery tools. Specialists without that skill read only their allowed
known revisions or explicitly documented run-home catalogue pages. A tool
description never adds a grant.

## Set up a deployment

Use the [guided startup and dashboard](../SETUP.md#start-and-use-the-dashboard).
The `start` command installs new setup once, waits for readiness and opens an
authenticated dashboard. Select devices and save monitoring there. Later starts
use the generated launcher in the private setup folder; no dashboard-token copying
is required. The [integration guide](../README.md) covers configuration and recovery.

### Manual deployment (advanced)

Start with [service account and human manager setup](../README.md#separate-the-human-manager-from-the-service-account).
The Python setup script reads configuration, prompts for blanks, prepares private
source and provisions a project-scoped service credential after the first paused
deployment. The human user gets Application `MANAGER` access; the service handle
gets `CONTRIBUTOR`. The management group is the manifest's `MANAGER` accounts with
matching server membership. It does not require giving the user server admin.
Relay provider/port declarations use the service token's `@service/<UUID>` principal.
The manifest uses that principal for `executionAccount` and its owning account
handle for membership grants. The guide also explains how to
run investigations as that principal and the current administrator-owned schedule
limit; setup does not transfer ownership of a deployed schedule.

1. Install the Python SDK and collector. Copy the supplied
   [configuration](../examples/config.json) into a private operator directory and set the
   server origin, `network-privacy-watch` project, collector scope and private
   state directory. Supply separate Plowshare and dashboard credentials through
   the configured environment variables.
2. Make a private copy of this Application folder. Add explicit manifest account
   grants and matching server membership; the supplied manifest grants no
   accounts. Replace provider account placeholders with the service token principal in `server/tools.json` and
   `server/ports.json` and `server/relay-workers.json`; configure the agents' model binding and review their tool grants.
3. Configure a server FileStore destination. As a server administrator with
   MANAGER access to that destination, deploy the private folder using CLI
   `application deploy` or Desktop **Applications → Deploy**. Retain the request
   UUID and inspect its receipt after uncertain delivery.
4. Verify effective tool grants and Relay routes. The `server/` declarations
   activate with the source revision; no global server configuration edit or
   restart is required. Python will publish its live catalogue when it starts.
5. Read `schedule.files` for the deployed schedule's actual internal name and set
   it in the collector configuration. Start the Python web service with explicit
   provider, account, bind address and port values. Connect the dashboard using
   its separate web bearer.
6. Request a scan and inspect its retained evidence and related report. Then
   review and resume the paused schedule. Use a new Application revision for
   packaged source changes.

For a first look without a server or network probes, use the
[synthetic browser preview](../README.md#verify-without-a-database-or-live-household-data).
It uses fixture observations and a fixture report; it does not run an LLM.

## What the evidence means

Collection is bounded to operator-configured targets and ports. Agents cannot
choose arbitrary targets, execute a shell, change devices or modify a firewall.
TCP probes observe selected service responses; they do not provide complete
device discovery or vulnerability detection. DNS observations need an external
exporter and do not reveal encrypted payloads or prove malicious behaviour.

Missing and stale sources remain visible as evidence gaps. Reports are drafts
for operator review. The collector records uncertain uploads and publications
for read-only reconciliation instead of automatically repeating them. See the
[recovery guide](../README.md#receipts-disconnects-and-recovery) before
restarting a collector with an unresolved outcome.

## Application-owned tools and ports

Before deploying your private copy, configure [`server/`](server/README.md)
with the external collector's authenticated execution identity (the service token
principal when using a service account). Its provider scope assignments and
catalogue authority are validated with the agent definitions; its
Relay port grants activate with this Application revision. No global server
configuration edit or restart is required. The Python process remains separately
operated and renews its live tool catalogue lease. See the
[integration guide](../README.md#agent-tools-through-the-sdk) for startup.

Investigation uses `orchestrations/investigate_network.js`. Plowshare journals its
commands and state; code passes the complete analyst response and retained source
text to the reviewer and assembles the draft from the actual review. Agents supply
judgments within the shared allowance. Missing assessments, unavailable evidence
and invalid tool results stop work explicitly. The collector remains an external
Python integration.

Per-device privacy assessment uses `orchestrations/assess_network_device.js`.
The collector starts it for one device with a retained dossier revision
(`network-privacy-dossier/<device_id>.json`); scan completions never start it.
The script reads the complete dossier, asks `privacy_device_assessor` for JSON
and validates every field: no HTML, tables, Markdown structure or identifiers in
agent text, only known levels and purposes, and only signals present in the
dossier. `privacy_reviewer` returns a verdict per concern. `needs_corrections`
allows one correction round, after which code removes refuted concerns and
lowers weakened potential breaches to worth checking. Model responses use the
shared bounded LLM JSON parser, including recovery of common formatting errors.
After decoding, tabs and paragraph breaks in prose become spaces; markup,
identifiers and other control characters are still rejected. Reviewer concern
notes are limited to 400 characters each; top-level notes allow one string of up
to 2000 characters or 1 to 8 strings of up to 1000 characters each. Strings must
be nonempty, and limits apply after whitespace normalization. Invalid agent
responses share one repair attempt with the field name and applicable limit in
length errors. A second invalid response or `not_checked` fails the run and
writes nothing. Both agents receive field limits in their initial handoff and
must keep the complete JSON response within 32768 characters, including syntax
and formatting whitespace. Code renders the report
(plain findings first, identifiers only in its Evidence appendix) and writes it
as `Network privacy assessment / <device_id>`, a `feedback` revision of the
previous assessment, with the dossier, profile and issue revisions as inputs and
the per-concern findings and review as report metadata. The run cannot finalise
it.

Operator admission preferences and acknowledgements live in the external collector,
separately from evidence. New completion decisions are immutable once retained;
withheld changes never remove original observations. See the
[operator guide](../OPERATIONS.md) for background operation, reviewed Application
schedule updates, explicit investigation receipts and recovery.

Operator device profiles are project Information sources named
`network-privacy-device/<UUID>.md`. Existing Information grants let agents read
relevant current revisions as context; agents do not edit those profiles. The
external dashboard creates/revises them through the public scoped SDK. See the
[operator guide](../OPERATIONS.md#device-knowledge-base) and [capability gaps](../GAPS.md).

Shared privacy issues use `network-privacy-issue/<UUID>.md` Markdown resources in
project Information. Profiles pin reviewed issue revisions and retain per-device
applicability, mitigation progress and supporting evidence. Existing read grants
let agents use that context without adding model stages or enforcement privileges.
Plans remain advisory. See [the mitigation guide](../OPERATIONS.md#privacy-issues-and-network-mitigations).

## Subnet collection

The external collector accepts up to 256 selected addresses, including all 254
host addresses of a private IPv4 `/24`. It probes and enriches batches of 32 devices,
then retains one combined scan revision and publishes one completion. It does not
start an investigation for each batch. Agents inspect large scans through explicit
bounded context projections and read-only pages with total counts and cursors.
MAC identities depend on fresh Pi-hole associations; configured IPs and names
remain available without Pi-hole. Use the device search for larger networks.
Existing deployments need the [reviewed subnet upgrade](../SETUP.md#upgrade-for-subnet-monitoring)
before widening their scope; Python is still started separately.
