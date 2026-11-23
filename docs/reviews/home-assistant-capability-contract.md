# HA resource and field discovery contract

Authorized: 24 November 2026. Coordinator: `/root`. Baseline: main
`f33a10daeaf09f84189edfba1039ee98c0b8ec9e` plus the independently reviewed dirty
diagnostics candidate `ha-diagnostics-v1`, evidence SHA-256
`262389b13889a7e40129d4274b16926384451ed566ba5bbe8eb8846445bbcd69`.
Those prior review artifacts remain immutable historical evidence. The new
candidate revalidates affected source and records its own identities.

## Deliverables and owners

| Node | Deliverable | Implementer | Independent reviewer |
| --- | --- | --- | --- |
| CAP-PLAN | Design, typed boundary and executable graph | `/root` | `/root/ha_capability_reviewer` |
| CAP-EXPOSURE | Effective HA conversation-exposure command | `/root` | `/root/ha_capability_reviewer` |
| CAP-01 | Resource identity, policies, typed fields, native source and catalogue | `/root/ha_capability_foundation_implementer` | `/root/ha_capability_reviewer` |
| CAP-02 | SDK tools, composition, exact grants and agent guidance | `/root/ha_capability_tools_implementer` | `/root/ha_capability_reviewer` |
| CAP-03 | Integrated verification and packages | `/root` | `/root/ha_capability_reviewer` |

CAP-EXPOSURE and CAP-01 follow CAP-PLAN. CAP-02 follows the approved typed
CAP-01 interface; independent files may proceed against that interface before
foundation acceptance. CAP-03 follows all source nodes. Each node needs its
implementer's evidence and independent reviewer approval before acceptance.
Merged, deployed and live-validated milestones remain separate and false for
this amendment. No live configuration change, restart or model/device test is
part of this source request.

## Ownership and authority

Everything belongs to the HA adapter. No Plowshare core, SDK, shared integration
runtime or schema extension is authorized. Existing public SDK tool-provider
catalogue, invocation, grant and receipt contracts remain authoritative. No
database tests are needed. Control remains disabled. Capability mode must not
reinterpret dynamic discoveries as fixed control targets.

New discovery configuration is explicit and strictly validated. Absent configuration
preserves existing six-tool behavior and frozen entity/attribute semantics. The
optional configuration defines frozen versus follow-Assist selection, exclusions,
standard-measurement versus explicit field permissions, and private bounded
identity storage. Defaults never activate broader access. The configuration digest
must enter the provider owner fingerprint; changing it cannot adopt old receipts.
Schema changes/declarations require the existing drain/reconcile/new-owner process.

## Resource identity and metadata

Expose opaque handles, not raw registry IDs/unique IDs, credentials or paths.
Where available, anchor identity to HA registry identity plus the explicit source
instance binding. Persist configured-selector anchors so a rename remains the same
resource across process restart, while another registry identity reusing a locator
does not inherit it. Credential/policy ownership and connection epochs fence
authority without making a per-connection random epoch the resource identity.
If stable evidence is absent or contradictory, report that limitation and never
guess continuity. Bounded stores are private, single-owned, atomically committed,
and fail explicitly on corruption/owner mismatch rather than reset.

Names, aliases, current entity locator, domain, device and area are metadata.
Only selected metadata enters the agent catalogue. Filter policy before search,
paging, ambiguity counts, schema projection and reads. Prefer one bounded native
collection per operation over per-resource requests; reuse authorized typed
snapshots, with explicit freshness reasons for additional exposure/schema checks.

## Fields, resolution and reads

Use adapter-local immutable typed DTOs and narrow source/catalogue interfaces.
Field descriptions include handle/key, primitive type, known quantity/capability,
measurement role, unit and support status. Map HA domain/device-class semantics
where established; arbitrary custom fields retain unknown meaning. Do not infer
room, quantity or physical cause from an arbitrary string. Standard measurement
profiles expose known relevant state/attribute readings on selected resources;
explicit additional fields retain strict bounds. No arbitrary JSON-path evaluator
or unrestricted attribute dump is introduced.

Capability mode advertises four policy-aware read tools: `health`, `resolve`,
`describe_fields`, and `read_fields`. It does not expose legacy data handlers that
would bypass dynamic selection or field exclusions. Existing six declarations
remain unchanged when capability configuration is absent. Exact grants are explicit,
never wildcard-derived. Closed inline schemas, input validation and the existing
15,000-unit result profile apply. Oversized results ask for a narrower page.

Resolution accepts name/text, area/domain and capability/role filters. Its candidate
unit is a resource plus matching field. Compute RESOLVED/AMBIGUOUS/NO_MATCH across
all permitted matches before paging. A climate entity with current and target
temperature can be ambiguous even when it is the only matching resource. Availability
does not break identity ties. Return alternatives; the agent may ask or report
source-qualified readings and must not average or silently pick the first match.

Describe/read accept an exact source/schema revision and opaque references. Reads
contain at most 20 fields total across at most 20 resources; field description pages
contain at most 16 fields and resolution pages at most 20 candidates. A
read returns typed values with separate availability, source and observation
timestamps, stale status, unit, role and source provenance. Missing, unknown,
unavailable, invalid, unsupported and policy-denied observations are distinct
where disclosure is authorized; hidden references reveal no existence. Preserve
established field shape through temporary missing/unavailable values. Renames,
field additions/removals/type/meaning changes and source reconnects invalidate
the appropriate revisions; ordinary changing readings do not constantly change
schema identity. Failures are sanitized and do not invent transport/auth causes.

## Effective exposure endpoint

The HA companion registers admin-only native WebSocket command
`plowshare/discovery/selection`, schema version 1. The request has no assistant,
target or configuration mutation argument. The response is exactly version,
assistant `conversation`, and a bounded list of effective entity IDs. Use HA's
canonical `async_should_expose` rules for registered and legacy entities; it can
materialize default preferences through normal HA storage behavior. Tests and
documentation state that behavior. No service call, model run, Plowshare job,
correlation mutation or new device/control grant occurs.

Target HA 2026.9.4/Python 3.14 APIs. Register the command once at successful HA
component setup, independently of gateway/config-entry availability; failures must not expose
an unbounded or partially selected catalogue. Follow mode checks actual selection
at disclosure and does not substitute cached positive preferences or all entities.
Unknown/denied/malformed versions fail, with no stock exposure-list fallback.

## Acceptance evidence

Cover duplicate friendly names, current-versus-target ambiguity, capability-only
search, readable climate temperature with unavailable sensor, fields absent from
primary state, standard/explicit profile exclusions, schema/type changes, no stable
registry identity, rename and replacement across restart, missing/corrupt/wrong-owner
identity stores, reconnect/epoch changes, hidden resources, pagination and bounded
failure. Exposure tests use actual HA rules including defaults, explicit false,
legacy entities, authorization and unavailable component state. Native/SDK fake
peers prove the optional four-tool provider path, original six-tool compatibility,
disabled control, cancellation/recovery and no uncertain-work replay.

Run relevant non-database tests, Java format/formatCheck, repository check,
Assist Python checks and both package checks on the final integrated source.
Record new source/artifact hashes and independent reviews. Offline acceptance
does not establish a live deployment or current provider health.
