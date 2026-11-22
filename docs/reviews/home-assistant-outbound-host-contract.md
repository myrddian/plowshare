# Outbound provider host contract — 22 November 2026

Owner: OUT-PLAN-01, implementer `/root`; independent review required before
OUT-PACK-01/OUT-PROFILE-01 source dispatch. Baseline main `6de685f6`.
This contract uses existing public SDK capabilities only. No core/shared SDK,
Assist, AI Task, system-context, inference endpoint or live-operation change.

## CLI and ownership

The existing HA distribution keeps its executable name. HA-specific
`HomeAssistantMain` delegates unchanged legacy arguments to integration `Main`.
Only `--provider CONFIG` and `--provider --check CONFIG` choose the new provider
mode. Malformed provider flags fail visibly rather than falling back to legacy.
`--check` parses and validates without looking up credentials, opening any socket,
creating/locking journals or publishing/connecting scopes. Print bounded safe
status/errors without tokens, provider payloads or private paths.

The provider host is not IntegrationRuntime. It starts no workflows and refuses
routes, scripts, feedback fields and nonempty HA subscriptions. No shared feedback
index is promised. Named controls continue refusing conversation.process and
ai_task generation endpoints through their existing owning policy. The legacy
manual-alias executable's behavior remains compatible.

## Strict configuration

All shown scalar/object fields are required unless marked optional; explicit null,
unknown fields, duplicate keys, trailing tokens, malformed UTF-8 and over-limit
input fail before effects. Maximum file size is 256 KiB, matching the existing
integration JSON boundary; bound reading before decoding. Convert at that boundary
to immutable typed records before lifecycle logic. No arbitrary JSON/maps cross
into host contracts. Existing HA fixed entity/attribute/action policies retain
their existing strict domain rules.

```json
{
  "version": 1,
  "plowshare": "https://plowshare.example.invalid",
  "binding": "house",
  "policyRevision": "outbound-v1",
  "homeAssistant": {
    "endpoint": "https://home-assistant.example.invalid",
    "tokenEnv": "HA_TOKEN",
    "entities": {
      "lamp": {"entity": "light.example_lamp", "attributes": []}
    },
    "actions": {
      "lamp_on": {
        "service": "light.turn_on",
        "targets": ["light.example_lamp"],
        "parameters": {},
        "required": []
      }
    },
    "subscriptions": []
  },
  "read": {
    "project": "home-assistant-control",
    "provider": "ha-read",
    "account": "REPLACE_WITH_EXECUTION_PRINCIPAL",
    "prefix": "ha_read_",
    "tokenEnv": "PLOWSHARE_TOKEN",
    "journal": "private/read",
    "leaseSeconds": 120
  },
  "control": {
    "project": "home-assistant-control",
    "provider": "ha-control",
    "account": "REPLACE_WITH_EXECUTION_PRINCIPAL",
    "prefix": "ha_control_",
    "tokenEnv": "PLOWSHARE_TOKEN",
    "journal": "private/control",
    "leaseSeconds": 120,
    "allowedActions": ["lamp_on"]
  },
  "timing": {
    "connectTimeoutMillis": 20000,
    "requestTimeoutMillis": 2000,
    "cleanupTimeoutMillis": 2000,
    "pollIntervalMillis": 500,
    "renewEveryMillis": 10000,
    "metadataMaxAgeMillis": 30000
  }
}
```

`control` is optional and absent in read-only configuration. `allowedActions` is
required only in control, a nonempty duplicate-free subset of the configured
fixed HA actions. Read/control providers share one authenticated SDK connection, explicit
Application execution principal and credential environment reference. Current
ApplicationToolRegistry/ToolScopes require that same principal on the caller,
provider binding and Application executionAccount. Require the same project,
account and tokenEnv in both provider blocks, distinct logical provider names,
nonoverlapping prefixes and nonidentical/nonnested normalized absolute journal
paths. Separate readonly/control Applications may use different execution
principals from each other; one Application cannot invent a cross-account tool
route to bypass the current server policy. Relative journal paths resolve against the configuration's parent.
Treat symlink/real-path aliases conservatively at actual journal opening; do not
silently adopt another provider's receipts. Each example has empty `access.accounts` and explicit nonempty exact
`toolScopes`/`toolGrants` declarations. Those declarations do not issue live
membership/grants; principal placeholders must be replaced and current authority
provisioned separately. Static Application provider declarations and exact
manifest tool scopes are provisioned separately; the host never grants itself
access or invents tool.scope.connect mutations.

Origins are explicit HTTP(S) origins without credentials/path/query/fragment or
local fallback. Required environment names follow existing uppercase identifier
rules. Reuse existing SDK/binding/project/alias validators. Finite scalar timing
bounds: connect/request/cleanup 100..30000 ms, poll 10..30000 ms, metadata age
1..300000 ms. Lease 1..300 seconds; renew interval positive and below half the
minimum lease; poll interval below renew interval and request budget below half
the minimum lease. Validate arithmetic without overflow. These are host resource
budgets, not a two-second accepted-agent workflow lifetime. A slow/unavailable
provider may lose its lease; it must fail explicitly, never execute under guessed
catalogue authority or promise uninterrupted availability.

## Lifecycle and uncertainty

Compose a configured HA adapter/resource catalogue and one read provider, plus
optional separate control provider. Narrow injected lifecycle interfaces own SDK
and HA effects; construction/composition alone selects concrete implementations.
Keep existing host, credential, policy, schema and broker receipt fences.

Startup opens validated owners and reconciles original retained uncertainty
before new catalogue publication. A supervisor performs finite maintenance,
explicit scheduled renewals and bounded polling through existing owner APIs.
No generic retry after IOException, SDK auto-reconnect, new UUID replacement of
uncertain work or journal reset. Unresolved errors terminate intake and preserve
original receipts/physical ownership for operator inspection. Legitimate later
scheduled lease renewal is distinct from replay of a failed publication. Do not
renew while unresolved original catalogue intent remains.

Normal shutdown stops intake, withdraws only known settled catalogues and closes
owned resources within explicit cleanup budgets. Shared SDK/HA resources may close
only after both provider owners have physically settled; a failed provider cleanup
retains those shared resources and journals for explicit inspection. Failure/uncertainty shutdown
must not send a new withdrawal to conceal unknown publication/effects. Preserve
caller interruption and SDK/HA physical ownership; if cleanup cannot settle,
report failure and retain the bounded owner/journal rather than creating a new
worker or reconnect around it. Reverse-order partial startup cleanup cannot leak
journals/threads or erase uncertain records. Do not claim that caller cancellation
proves remote abort. Installation/source changes do not imply live deployment.

## Parallel file ownership and acceptance

OUT-PACK-01 owns new main/configuration/host/lifecycle Java and host tests,
HA build.gradle.kts, PROVIDER-HOST.md, and narrow parent README changes.
OUT-PROFILE-01 owns examples/outbound/** and OutboundProfilesTest.java.
Profile roots: readonly/application (`home-assistant-readonly`, `house_reader`)
and control/application (`home-assistant-control`, `house_controller`). Each has
plowshare.json, agents, static server/tools.json and matching sibling provider.json.
Use explicit principal placeholders/empty access grants, dynamic agents and exact
read four-tool names plus separately granted control two-tool names. No built-ins,
wildcards, Assist, task provider, workflow starts or shared history by default.

Root serializes Gradle and generated-artifact work. Freeze Java during compiled
checks. Independent reviewers bind exact source/hash/ledger snapshots; no agent
reviews its own authored feature. OUT-VERIFY-01 starts after both feature approvals
and authors only cross-boundary fixtures/evidence. Mock repositories and local
public SDK/HA transport fixtures by default; no real PostgreSQL without a precise
actual-database assertion. Record source/installed artifact identities, all checks
and missing proof honestly. No original gated node is closed by outbound-only
validation; all eleven and all four capability/operational gates remain unchanged.
