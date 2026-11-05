# Decision 0043: Model media declarations extend disk-loaded sampling profiles

Status: authorized first implementation slice, 21 October 2026.

## Platform need and authorization

The user authorized starting a slice of the reviewed multimodal plan. This slice
implements deployment-owned model format declarations only. A model's accepted
inputs and possible output combinations are platform configuration, independently
of any browser, MCP bridge or other adapter.

The server already loads sampling profiles and wire-model mappings from an
operator-owned directory over shipped defaults. Reuse that loader and its startup
lifecycle. An SDK-only registry cannot establish which configuration the server
loaded, and duplicating formats in adapters or pool lists creates competing model
definitions. No adapter or client changes are needed for this internal configuration
extension.

## Decision and scope

Version 2 profiles retain sampling modes/intents and optionally add typed native
input MIME types and named output format combinations. `SamplingProfiles.Resolved`
exposes both sampling and an optional immutable media declaration. A missing
declaration remains unknown; image-only outputs need not include text. Output
combinations express permitted formats, not a guarantee that all occur.

Preserve exact/longest-prefix model resolution, vendor-prefix handling, external
whole-profile replacement and model-mapping merge. Reject malformed versioned
profiles, duplicate keys/files, unknown versioned fields, invalid MIME types and
missing mapped version-2 modes at startup. Legacy valid sampling profiles and
their missing-mode warning/fallback remain supported. Reject unversioned `media`
instead of silently ignoring an operator's declaration.

This does not enable multimodal inference, change model selection, add image
token estimates, import Tika, or alter tool results, Relay, storage or trajectory.
A later router must intersect declarations with actual transport support and
enforce current execution authority and context budgets. No Flyway, billing
journal, public operation or SDK schema changes occur here.

## Deployment, compatibility and verification

Profiles remain read at startup through `plowshare.llm.sampling-directory`, behind
the existing workspace exclusions. Operators can change external YAML/JSON files
and restart the same server binary. There is no resource rebuild or live reload.
An absent default directory retains shipped-only behavior; requiring a mounted
media directory belongs to later capability enablement.

Older binaries can ignore extra top-level fields, so a version-2 file is not a
safe way to enable media on an older server. Shipped model files retain their
legacy schema and gain no inferred format support. Removing external version-2
files restores legacy configuration; there is no persisted data to migrate.

Tests cover independent disk configuration across Spring context startups,
sampling compatibility, output combinations, deterministic replacement/resolution,
immutable values and configuration refusal. These require no database or model
endpoint and do not establish screenshot-to-model acceptance.

Implementation validation passed on 21 October 2026: 165 focused tests across
`ModelMediaProfilesTest`, `SamplingProfilesTest`, `LlmConfigTest` and
`AgentsConfigTest`, followed by `./gradlew format` and
`./gradlew formatCheck check`. The default checks ran without the full-db opt-in.
The new coverage includes 30 cases for media profiles and startup loading.
