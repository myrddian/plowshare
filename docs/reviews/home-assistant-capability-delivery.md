# Generic Home Assistant read discovery delivery

This adapter-only amendment implements the reviewed
[design and contract](home-assistant-capability-contract.md) and
[typed Java seam](home-assistant-capability-interface.md). The
[delivery ledger](../home-assistant-delivery-state.json) owns actual independent
acceptance and separate publication/deployment/live milestones.

Capability mode exposes `health`, `resolve`, `describe_fields` and `read_fields`.
It does not publish legacy data handlers that could bypass current field or
selection policy. Without discovery configuration, the existing six tools and
frozen behavior remain unchanged. Exact grants and matching provider modes are
explicit in the read-only templates; control/task templates retain their earlier
separate capabilities. No control is enabled or live scope changed by this work.

The capability catalogue resolves permitted resource/field pairs using known
quantity, current/target/status role, names, aliases, domain and location.
Ambiguity is calculated before paging; availability cannot identify the intended
device. Reads preserve per-field type, unit, source and observation times and
explicit unavailable/unknown/missing/invalid/unsupported states. Names and entity
locators are metadata around opaque resource identities. Registry anchors and
field-shape history are bounded, privately owned and retained across restart;
replacement identity and locator-only uncertainty remain explicit.

Standard measurement profiles replace per-entity field exceptions for supported
HA capabilities. Additional explicit fields retain unknown semantics when no
domain rule establishes their meaning. Metadata and field values are filtered
before candidate counts, paging or disclosure. Native aliases use one bounded
selected registry batch, avoiding per-entity metadata requests.

Optional follow-Assist mode uses the companion's admin-only versioned effective
selection command rather than equating stored exposure preferences with effective
policy. HA's canonical helper can materialize its own default preferences. The
command performs no device/service/model/correlation operation. Fresh selection
and identity checks define a bounded native read-admission/observation boundary;
they cannot form an atomic revocation transaction with the Plowshare broker.
Later policy changes block new reads. Already accepted immutable SDK results keep
their original receipt/reconciliation without recollection or retroactive scrubbing.

All platform operations use the existing public SDK. No core, SDK, shared-runtime
or database changes are included. Existing diagnostic review artifacts remain
immutable historical evidence; this amendment supplies a new integrated candidate
and revalidates affected behavior. No HA/server restart, live provider/model call,
deployment or device action is part of this source delivery.

The [integrated evidence](home-assistant-capability-evidence.json) binds the final
source inputs, check logs and package contents. CAP-PLAN v2 and CAP-EXPOSURE have
independent approval; foundation/tool/integrated approval is recorded in their
review reports and the ledger. Acceptance requires those independent approvals,
not merely a passing build.

| Verification | Actual result |
| --- | --- |
| Full HA Java suite | 250 tests across 30 suites; zero failures, errors or skips |
| HA Python checks | Ruff check/format and 159 tests passed against HA 2026.9.4/Python 3.14; nine exposure cases independently rerun |
| Java formatting | Full `format` plus final module `spotlessApply`; final repository `formatCheck` passed |
| Repository and standalone build | `./gradlew formatCheck check :plowshare-integration-home-assistant:installDist --max-workers=2` passed in 52 seconds, 144 tasks; no full-db |
| Installed outbound package | All eight offline checks passed; 57 artifact files |
| Assist archive | Version 0.2.0; all 54 entries accounted for; complete contents manifest verified; all 19 bundled public SDK files match unchanged source |

The public SDK/native fixture resolves duplicate Office resources, describes
current-versus-target fields and reads the climate temperature without exposing
legacy data handlers in capability mode. Tests also establish absence of fallback
on denied/unsupported effective exposure, before/after identity and unit guards,
canonical-default materialization, alias batching, partial identity-state loss,
store lock ownership, startup cleanup and retained provider recovery.

Initial verification caught an unused test resource warning, unsupported SDK
schema `pattern` keywords and noncanonical macOS temporary fixture paths. These
were corrected without SDK changes or weakening typed validation/private path
guards. Two native refusal cases then exposed a missing sanitized error message;
that boundary is corrected and the final 250-test suite passed.

Locator-only references remain epoch-scoped. Primitive fields with unfamiliar
semantics retain unknown meaning. The private store refuses overflow at its
documented bounds rather than forget identities. Retained provider state blocks
fresh identity bootstrap after partial loss; if both stores and all ownership
markers are completely erased there is no local proof of earlier installation.
Preserve paired backups and accepted receipts. No test establishes live download
discovery, installed artifact identity, voice/model behavior or device health.
