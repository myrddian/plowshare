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
| `orchestrations/` | Deterministic schedule action and agent investigation workflow |
| `schedules/network_scan.json` | Packaged scan schedule, initially paused |
| `Relay/` | Project topics and the scan-completion route |
| `server/` | Project tool/provider catalogue, Relay ports and service-owned worker enrollment |

The coordinator sets `dynamic: true`; its `tools` field lists built-in grants.
The six external network tools are granted through the `network_scanning` provider
scope in `plowshare.json`, without listing them again in the agent definition.
Those permissions control visibility and execution. Python publishes the schemas
at runtime, and each call checks current registration, permission and availability.

The Python service, private configuration, credentials and collector journal
stay outside this folder. Deploying the Application does not start Python or
install its dependencies.

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
exporter and do not reveal encrypted payloads or prove malicious behavior.

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
