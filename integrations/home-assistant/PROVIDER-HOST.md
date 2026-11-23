# Outbound Home Assistant provider host

The HA distribution can host named discovery/read tools and optional fixed device
controls through the public Plowshare SDK. It does not install an HA component,
start an agent or workflow, or install the separate Assist/AI Task component.
Use the independently packaged [SDK-based HA component](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/assist/README.md)
for those entities.

Build with `./gradlew :plowshare-integration-home-assistant:installDist`. The
installed `bin/plowshare-integration-home-assistant` keeps its existing name.
The distribution includes this guide and the
[outbound examples](examples/outbound/README.md).
Run it on a host with a Java 21 runtime and network access to both explicitly
configured origins. The distribution does not bundle a JRE and does not need to
run inside Home Assistant.

```sh
plowshare-integration-home-assistant --provider --check /path/to/private/provider.json
plowshare-integration-home-assistant --provider /path/to/private/provider.json
```

Only `--provider` selects named tools. Existing configuration and legacy options,
including `--check CONFIG`, still delegate to the existing manual-alias runtime.
Provider flags are strict; malformed flags never fall back to that runtime.

## Configuration and authority

Copy the [read-only profile](examples/outbound/readonly/provider.json) or the
[control profile](examples/outbound/control/provider.json) into private operator
storage. Replace both explicit HTTP(S) origins, entity/action selections,
Application project and execution principal. Examples use nonconnecting example
origins and principal placeholders. Supply credentials through the configured
environment-variable names; never put tokens in JSON, agent prose or source.
The HA credential and Plowshare service credential have different owners.

The provider configuration requires version 1, `plowshare`, `binding`,
`policyRevision`, `homeAssistant`, `read` and `timing`. `control` is optional.
Every shown scalar/object field in the examples is required. Configuration is
bounded to 256 KiB UTF-8; malformed encoding, duplicate/unknown keys, trailing
tokens and explicit null are refused before connections or journals open.
`--provider --check` performs this validation without looking up credentials,
creating/locking journals, registering a scope or opening a socket.

Both logical providers in a host use the same explicit account and token
environment reference, matching the Application's `executionAccount`. They share
one authenticated SDK connection because the current public Application contract
binds provider publication and invocation scope to that execution principal.
Read-only and control Applications may use different principals from each other.
Within a control Application, the read and control logical provider names,
nonoverlapping tool prefixes, exact grants and private journals remain separate.
Discovery and catalogue publication grant no tool authority.

Provision the matching static `server/tools.json`, exact manifest tool scopes,
dynamic agent definitions and normal membership/access grants through the
Application's supported operator workflow. The host creates no grants or
`tool.scope.connect` mutations. Example `access.accounts` lists are empty, so the
templates grant no account access by themselves. See the public
[provider-scope guide](https://github.com/myrddian/plowshare/blob/main/docs/tool-scopes.md).

Without `discovery`, the legacy read catalogue exposes six configured-prefix tools:
`search`, `describe`, `read`, `list_actions`, `health` and `diagnose`. With explicit
`discovery`, the read catalogue instead exposes `health`, `resolve`,
`describe_fields` and `read_fields`. Legacy alias-reading declarations are absent
in that mode so they cannot bypass its selection or field policy. Optional
legacy control exposes only its separate prefix's
`describe_action` and `execute_action`. Its nonempty `allowedActions` must select
configured fixed action profiles whose complete targets are disclosed by the
same HA entity policy. It cannot select arbitrary services/targets or inference
endpoints such as `conversation.process` and AI Task generation. An action receipt
is an acknowledgement, not proof of physical success. Exact physical verification
remains the existing separate Java API, not a new model-facing tool.

Read-tool revision fields are explicit nested object schemas, including the
search cursor. Pass the revision object returned by discovery directly; a
JSON-encoded string is invalid. Inlining lets model/provider templates see the
object shape without resolving schema references. It does not relax
revision validation or change the public SDK or server schema handling.

Journal paths resolve against the configuration's parent. Read and control paths
must be distinct and nonnested after normalization. Actual startup conservatively
refuses existing symbolic-link components before either journal opens; the owning
journal retains its own lock and atomic-file defenses. Preserve the configured
owner, credentials/policy identity and journal during rotation/reconfiguration.
Do not rename a journal or delete uncertain receipts to make startup pass.

## Lifecycle and budgets

The supervisor shares the configured adapter/resource catalogue. It reconciles
retained original publications/results before publishing a new catalogue, then
performs explicit scheduled renewals and serial bounded polling. A missing
reconciliation result is not permission to resend; the existing journal refuses
renewal while its original catalogue intent remains unresolved. Any lifecycle
failure stops intake. There is no generic mutation retry, SDK reconnect, new UUID
replacement, journal reset or re-execution of an UNKNOWN action.

The existing HA adapter may maintain its native connection and mark cached
evidence stale across gaps; this does not repeat device actions. Each SDK/provider
pass retains its existing request budget. Connection, metadata age and cleanup
have separate explicit finite settings. These resource budgets do not impose a
two-second lifetime on an accepted Plowshare agent job.

The `timing` fields are explicit integer milliseconds: connect/request/cleanup
100–30000, poll interval 10–30000, metadata age 1–300000, and positive renewal
interval. Poll interval must be below renewal interval, and renewal/request
budgets below half the minimum 1–300-second provider lease. Slow work may lose
its lease and fail explicitly; configuration does not promise uninterrupted
catalogue availability.

Stop requests wake an idle supervisor without interrupting a native effect.
The host checks stop between owner operations. Normal shutdown withdraws known
settled catalogues and drains provider owners before closing shared SDK/HA
resources. Failure shutdown does not publish a withdrawal to conceal an uncertain
catalogue or result. Caller interruption is preserved after owned cleanup.

The shutdown observation grace includes the greater connection/provider active
pass budget, one withdrawal pass per provider, and each provider plus shared
resource cleanup budget. Cleanup that exceeds its caller wait retains the exact
owner and journal. Shared SDK/HA cleanup has one retained physical task; a later
join never creates a replacement task. The process keeps a shutdown hook for an
incomplete owner, including partial startup. A process stop observes/rejoins that
same owner within finite budgets. If it still cannot settle, the error remains
explicit: process termination never proves remote abort. Preserve journals; a new
owner must reconcile uncertainty before intake. Do not run a second host around an
occupied journal or treat a caller timeout as permission for another device action.

### Automatic recovery for a read-only container

The native HA connection already reconnects through its adapter owner. A lost
Plowshare connection stops this process; an external container restart policy can
start a new process with the **same** configuration, credentials, image and durable
journal mounts. For a read-only deployment with `control` absent and `actions`
empty, Docker's [`unless-stopped` policy](https://docs.docker.com/engine/containers/start-containers-automatically/)
provides process-failure and Docker/host-start recovery while respecting an
operator's deliberate stop. Configure the existing container by its verified
identity; do not create a second provider, use `--rm`, replace its journal or
combine Docker restarts with another process manager. Docker must start at boot.
Its restart delay backs off to one minute; the provider's finite connection,
request and cleanup budgets still apply to every process attempt.

A new process acquires the original journal exclusively and uses existing SDK
reconciliation before intake. A retained execution intent becomes an UNKNOWN
result rather than another HA read; a completed result is reused. An uncertain
publication is inspected under its original identity and is never resent because
its receipt is absent. Catalogue/result uncertainty, a broker-generation gap,
invalid credentials or a corrupt/mismatched journal can therefore keep the
provider unavailable while preserving its evidence. Automatic process recovery
does not authorize automatic repair, credential fallback or work replay. Inspect
those failures; do not delete state to make restarting succeed.

Verify recovery with the provider idle: observe a fresh process in the same
container after an application exit and after a provider-only connection outage,
then confirm the original mounts, identity and replay fences, settled catalogue,
and one fresh permitted read. Do not reboot the shared host or restart Docker as
a routine check. Persisted restart policy and boot-enabled Docker establish the
configured boot behavior; an actual host reboot is separate evidence.

## Supported composition and acceptance

Provider mode rejects nonempty HA subscriptions and every workflow route, script
and feedback field. It never constructs the legacy integration workflow runtime.
Named-control receipts are not automatically added to that runtime's causal
feedback index. Use the modes independently; no cross-host loop suppression or
shared history is promised. See the
[observation composition limits](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/OBSERVATION-ROUTES.md)
and [action contract](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/ACTION-PROVIDER.md).

Offline validation proves schema/policy compatibility, not HA version, existing
devices/services, actual grants or physical action success. Check the intended HA
version and supported capabilities before a separately authorized deployment.
Live acceptance should begin with scoped discovery/read, then only an explicitly
authorized reversible action with a separate state observation. Keep its original
receipt identity after a timeout or restart. This package performs no live
acceptance, backend cache measurement or automatic HA update.

## Updating a catalogue for diagnostics

The two read-only diagnostic declarations require exact server tool-scope grants;
old four-tool grants do not authorize them. Updating profile files does not update a
running Application. Keep control absent/disabled and preserve the existing entity
and attribute ceiling when applying those two grants.

Adding declarations changes the durable catalogue identity. Drain the old provider,
reconcile all original publication/result receipts, and confirm catalogue withdrawal
before opening a new private journal for the changed declaration set. Preserve the
old journal/configuration for audit and unresolved receipts; never edit its identity,
delete receipts, or point the new process at the incompatible old journal. If any
receipt remains uncertain, retain that owner and stop the rollout until read-only
reconciliation establishes its outcome. See [tool diagnostics](https://github.com/myrddian/plowshare/blob/main/integrations/home-assistant/TOOL-PROVIDER.md#diagnostic-evidence-and-limits)
for what each observation can and cannot establish.

## Capability-based read discovery

The read-only example enables an explicit `discovery` configuration:

```json
"discovery": {
  "mode": "fixed",
  "profile": "standard_measurements",
  "excludedEntities": [],
  "excludedFields": [],
  "identityJournal": "private/identities"
}
```

The `fixed` mode begins with `homeAssistant.entities` and pins available registry
identities in its private identity store. A verified rename can retain its opaque
resource reference across restart; another entity reusing its locator cannot
inherit that reference. Missing or contradictory identity remains explicit.
Resources without registry identity have connection-scoped locator references;
they do not establish continuity through a restart or reconnect.

The `standard_measurements` profile includes known measurement fields on selected
resources, including climate current temperature separately from its target
temperature. Explicitly selected attributes remain bounded additions. The
`explicit` profile restricts fields to the configured state/attribute selection.
Exclusions win in either profile. Discovery returns typed fields with capability,
measurement role, unit and support status; unfamiliar meaning is `unknown`, and
nested unsupported values are not exported as generic objects. No field discovery
grants control or changes Plowshare tool authority.

`resolve` matches names, aliases, locations and field capabilities, returning all
matching resource/field candidates through bounded pages. `AMBIGUOUS` describes
the complete permitted match set, even on a one-candidate page. Availability never
breaks a name tie. Request `CURRENT` for measured temperature and `TARGET` for a
setpoint. Use exact returned references and revisions with `describe_fields` and
`read_fields`; each returned value preserves its own source, time and unit.

The separately provided `examples/outbound/readonly/provider-assist-exposed.json`
selects `assist_exposed` mode. It requires the matching HA companion and its
admin-only version-1 `plowshare/discovery/selection` command. Effective conversation
exposure is checked at the bounded native read-admission/observation boundary
using HA's canonical rules, including its
normal materialization of default preferences. Missing, denied or malformed
support fails explicitly, without a stock-list or all-entities fallback. The
configuration is an example, not activation of live exposure tracking.

HA selection and SDK result publication do not share an atomic transaction.
A later exposure change prevents new reads after the adapter observes it; it
does not rewrite an already accepted immutable SDK result. Retained results
reconcile under their original identities without recollection, scrubbing or
replay. Observation and publication timestamps remain distinct evidence.

Capability discovery requires a read-only configuration with no control block.
Its four exact grants replace the legacy six grants in the read-only templates;
control/task templates retain their separate legacy/no-tool assignments. The
identity journal is a private, bounded, exclusively owned store, separate from the
SDK invocation journal. Its path resolves beside the configuration. Preserve both
journals and their matching configuration during drain/reconciliation and rollback.
Corruption or owner mismatch does not authorize resetting identities. Selection,
profile and exclusions contribute to provider ownership, so configuration changes
cannot adopt earlier receipt authority merely by retaining a policy label.

Live startup checks retained SDK receipt files/ownership markers before opening
connections. If they remain but the identity snapshot is missing, startup refuses
to bootstrap replacement identity state. The offline configuration check still
does not inspect or create private journals. Loss of both stores and all markers
cannot be distinguished from first installation; keep consistent backups of both
and their configuration. This guard does not reconstruct lost receipt history.
