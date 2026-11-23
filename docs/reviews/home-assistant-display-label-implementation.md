# HA native display-label codec follow-up

Date: 24 November 2026. Implementer:
`/root/ha_capability_foundation_implementer`. Independent reviewer:
`/root/ha_capability_reviewer`.

Human display labels previously used the strict identity parser. A harmless
trailing space in one selected friendly name could therefore stop the entire
catalogue. The native codec now distinguishes friendly names, registry display
names, device/area labels and native aliases from identifiers.

Only these human labels normalize surrounding whitespace. Blank labels follow
the next existing display fallback; blank aliases are skipped and normalized
aliases are deduplicated. Interior spaces remain intact. Raw 256-unit bounds,
ISO control characters and Unicode line/paragraph separators are checked before
normalization, so stripping cannot conceal malformed input. Entity IDs, registry
identities, configured selectors, units and semantic capability identifiers keep
their original strict parsing. No shared runtime, SDK, core, policy or control
change is included.

Focused verification passed **36 tests**: 25 catalogue tests, including 11 new
normalization/fallback/negative cases, plus all 11 native transport cases. The new
tests preserve opaque IDs and typed reads across padded human labels, exercise
blank fallbacks and alias deduplication, and reject malformed identities, controls
and overlong raw labels without changing retained anchor bytes.

```sh
./gradlew :plowshare-integration-home-assistant:spotlessApply :plowshare-integration-home-assistant:test --tests 'io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityCatalogTest' --tests 'io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityNativeTest' --max-workers=2
```

Build result: PASS in two seconds; zero failures/errors/skips. Focused log SHA-256:
`5f792c423f326f1c8205880eb8b98f002bf9ff5dda5215e8720b59dbfdbb4dcc`.

| Source path | SHA-256 |
| --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityWire.java` | `005227d9101e3cb0a5d1be78ed6cc1b1051e9e4afdfb177b5b965f427eb730d5` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityCatalogTest.java` | `c975e20ef05dc0747e928180b7c8add49618406c2b0fee3ca13a029f305f1123` |

Broader checks, final independent review and any live candidate probe/rollout are
coordinator-owned subsequent evidence. Earlier frame/capability evidence remains
unchanged; this report does not claim a live result.
