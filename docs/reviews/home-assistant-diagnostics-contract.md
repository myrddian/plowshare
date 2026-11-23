# Home Assistant diagnostics contract

Date: 24 November 2026. Baseline: `f33a10daeaf09f84189edfba1039ee98c0b8ec9e`.
This is an adapter-only amendment. Existing SDK operations, grants, journals and
resource authority remain authoritative. No core, SDK or shared-runtime extension
is part of this work. Control stays disabled; selected entities and readable
attributes do not change.

| Node | Deliverable | Implementer | Independent reviewer |
| --- | --- | --- | --- |
| DIAG-01 | Provider health and admitted-resource diagnostic tools | `/root/ha_diagnostics_provider_implementer` | `/root/ha_diagnostics_reviewer` |
| DIAG-02 | Native HA config-entry diagnostics download | `/root/ha_diagnostics_download_implementer` | `/root/ha_diagnostics_reviewer` |
| DIAG-03 | Integrated validation, packaging and delivery records | `/root` | `/root/ha_diagnostics_reviewer` |

DIAG-01 and DIAG-02 are independent. DIAG-03 depends on both. A node is accepted
only after its implementer supplies evidence and its independent reviewer approves
the actual candidate. Merged, installed and live-validated milestones remain
separate. Historical accepted candidates and their evidence are immutable.

The configurable read-tool prefix adds `health` and `diagnose`. Health reports
bounded local observations of HA connectivity and Plowshare/provider lifecycle.
Every claim distinguishes confirmed observation from unknown/not observed and
retains meaningful observation age. A socket or catalogue lease does not prove a
successful end-to-end round trip. A provider-hosted tool cannot report on a host
that cannot receive the call; the independent HA download covers that boundary.

Resource diagnosis uses the same admitted references, revision fences, discovery
policy and bounded fresh-read contract as existing reads. It distinguishes
unavailable, missing, invalid and stale data, and explains permitted or unexposed
requested attributes without reading or disclosing those attributes. Unselected
entities are denied without revealing their existence. An entity may be
unavailable even when the tool completed successfully. A physical cause is
unknown unless independently established; diagnostics never infer batteries or
network hardware from an unavailable state.

Failures retain only categories supported by actual evidence, such as invalid
arguments, disconnected transport, refusal or uncertain delivery. Generic IO
failures cannot be relabelled authentication failures without evidence. Diagnostic
reads neither submit model work nor replay jobs, actions or uncertain catalogue or
result publications. Existing operation budgets and physical ownership remain.

The integration-owned data section of the HA download uses the normal
`async_get_config_entry_diagnostics` interface and a bounded explicit allowlist.
It works with absent/failed runtime and performs
no network requests. It may include safe versions, feature flags, lifecycle facts,
typed outcome categories and aggregate pending/uncertain counts. It excludes
credentials, private origins and paths, raw account/project/user/session/job and
conversation identifiers, prompts, model/tool results and journal/config dumps.
HA adds its own native envelope (system/integration metadata and issues), and
may include the entry identifier in the download filename; those framework-owned
fields are outside this integration's redacted data section.
Unavailable provider observations are reported as unknown, never fabricated.

Tests cover unavailable-but-completed reads, denied/unselected input, stale or
invalid data, malformed arguments, disconnected/uncertain boundaries, secret
exclusion, absent runtime and the absence of diagnostic side effects. Public SDK
fake peers validate declarations/lifecycle where applicable. Python checks use
the installed HA test runtime. Java formatting, repository checks, Assist archive
and outbound package checks bind the final integrated candidate. No database,
live model or device mutation is needed for source acceptance.

New tools require explicit exact grants in applicable read profiles; discovery
alone grants nothing. Changing declarations changes catalogue journal identity.
A rollout must drain/reconcile the old owner, preserve its journals and receipts,
and use a new correctly owned journal when identities differ. This source task
does not silently activate the separately proposed climate temperature attribute,
restart HA or replace the deployed provider.
