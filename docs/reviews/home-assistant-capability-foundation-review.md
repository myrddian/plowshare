# CAP-01 independent review

Date: 24 November 2026. Implementer: `/root/ha_capability_foundation_implementer`.
Reviewer: `/root/ha_capability_reviewer`. Verdict: **APPROVE** for source
implementation and owning verification. Source acceptance does not establish
publication, deployment or live device/provider availability.

The actual typed contracts, catalogue, native codec/connection seam, strict
configuration and private identity stores were reviewed against the owning HA
2026.9.4 APIs and existing SDK boundaries. The implementation keeps names and
entity locators separate from opaque registry-backed handles; persistent anchors
retain verified renames across restart and block replacements under old fixed
references. Locator-only handles are epoch-scoped. Configured custom-field
permissions and exclusions follow verified identities, including replacement and
subsequent rename cases, without granting new control authority.

Discovery filters resources and fields before metadata, ambiguity, pagination or
values. Whole-result ambiguity counts resource/field pairs, including current and
target fields on one resource; availability cannot choose an identity. Field
shape, unit, role and provenance remain typed and bounded. Ordinary values and
temporary missing/unavailable values preserve established schema; genuine shape,
policy or connection changes invalidate old revisions. A known mismatch raises
adapter-local `SchemaChangedException`; generic IO remains unclassified. Empty
text values remain legitimate data, while malformed source times produce invalid
observations without fabricated values.

The native source uses one bounded collection with selected alias projection,
pre/post effective selection and identity checks, and a post-value unit projection
check. The extra reads have explicit identity/alias/unit freshness purposes and
are not per-entity requests. Actual HA bulk registry aliases are supplied through
one selected `get_entries` batch. Epoch/metadata fences include dynamic resources;
canonical helper materialization establishes the initial observation baseline.
Version overflow, denied/malformed exposure and wrong source settings fail
without broader fallback or private server text.

The specialist identity store uses bounded typed snapshots, strict deserialization,
private permissions, exclusive ownership and atomic fsync commits. Retained SDK
journal markers guard against identity-only directory loss before live startup
creates files or connections. Corrupt, missing retained, mismatched-owner and
symlink state fail rather than silently reset. If both stores and all independent
markers are erased, local bootstrap cannot prove earlier identity; the runbook
explicitly requires paired backups instead of claiming recovery from erased
history. The adapter-owned journal metadata probe does not inspect/replay receipts.

All review findings are resolved: dynamic exclusions and explicit fields survive
verified renames; temporary missing fields retain their descriptors; alias lookup
uses the actual baseline API; unit changes cannot mislabel the collected values;
source origin/settings are validated before store creation; malformed version and
value/timestamp boundaries are strict; and the private-state loss guard is wired
before transport startup. No core, SDK, shared-runtime, database or control change
is included. Fresh native read admission is a point-in-time boundary, not an atomic
transaction with later SDK result publication. Accepted immutable SDK results
retain their original reconciliation and are never recollected or scrubbed.

Evidence inspected: final HA Java XML reports **250 tests, zero failures/errors/
skips across 30 suites**. The five foundation suites contribute **38 passing
cases** (DTO 2, catalogue 14, identity 5, configuration 8, native 9). The actual
public SDK/native and lifecycle regressions are included in the same owning run.
Owning check log SHA-256:
`5b676ccbaacc1a006741fc5822c480cc51131954cb5f4b5a1079d56b2535be77`.
The implementer confirmed completion and the reviewer matched these bytes against
the final frozen source map. No open actionable finding remains. Full combined
artifact acceptance is separately recorded under CAP-03.

Approved source SHA-256 values (repository-relative):

| Path | SHA-256 |
| --- | --- |
| `docs/reviews/home-assistant-capability-interface.md` | `251ef5b1a94d21714a2a7076ddc8ca9fcef731e15c1e524b6465f58d90f74846` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java` | `566c73a0ac1851c266431abfa0dd9000182df679373e438372d57c28bee03dae` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilities.java` | `f06b6489c96fde959e261cd0bec855d696f208f605966a00b5344a2d39b7b768` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityCatalog.java` | `b4d1791ff06cf9512032f99658b07f3c3c8f429f30acd6000c3d61bd8494c7e0` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityIdentityStore.java` | `eea7a6eb6e57ce133008930e0611f1f255e636803f36c031bf50a8863e251c88` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityProfiles.java` | `014163a60fcc848eec39969da0cdd2a41cac1e1ff1e15bec87fcf1af832dfec1` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilitySource.java` | `dbf11f6509b189889039bcf4486954bd7bd688c86e006cd20f2ebcbec8bfbce8` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityWire.java` | `38bc642b35db7b464a94bb530515c615ff29ffe26ebbb6140da93809f923b583` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderConfiguration.java` | `839f770d33d13b591725bc108957775365b71a6c41608536d5beeeb80fa4be3f` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCapabilities.java` | `53644477abe7816f3e86810d513de8e76c08aa79a2855754b97dbf54b90128c3` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolJournal.java` | `28e0909c94de43818f6cab6de23cb2440c7cdedd715be7191314241de07fdca4` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilitiesTest.java` | `61043d8c3c3b0cd91d5e154d7265ee1e04559f43c4e70102abcb5f0226f68f1d` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityCatalogTest.java` | `c903b86538f569411c56a2652c26f3fdea2a59ae1467e20ecd50dd536e131fd6` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityConfigurationTest.java` | `9bab9898b609a75b4a321d59e4a2e5b0233693e6b63af76df9f19c838528b31b` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityIdentityStoreTest.java` | `9ede5a2a9ec8851cbd85bd21b847349793a0b8f2a814f25a1bdf616999f9871e` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityNativeTest.java` | `71d57d534ebd1bb638d2801965b427e3e0d0b454c41e9762cd6750a9e00caac0` |
