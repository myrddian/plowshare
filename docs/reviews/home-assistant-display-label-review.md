# HA display metadata independent review

Reviewer: `/root/ha_capability_reviewer`, independent of the implementer and the
coordinator. Date: 24 November 2026. Decision: **APPROVE the source change and
focused automated validation**. Deployment and successful live reads require
separate operational evidence.

An isolated native candidate diagnostic located the second collection failure at
the selected state's friendly-name conversion. A human display label with boundary
whitespace was being validated as an identifier. The original failure and private
diagnostic were retained; neither completed model work nor an uncertain action
was replayed to discover the cause.

The actual two-file diff separates human label conversion from identity conversion.
Native friendly names, registry display names, device display names, area names and
aliases strip boundary whitespace. Blank labels use the next documented fallback;
blank aliases are skipped and normalized aliases are deduplicated. Interior spaces
remain unchanged. Raw input must satisfy the existing 256-character bound and
control/separator restrictions before normalization, so trimming cannot hide an
overlong value or remove an invalid newline or tab.

Entity locators, registry keys, selectors, units, device classes and semantic
capability identifiers continue using strict conversion. Stable resource identity,
selection policy, grants, metadata ownership, lifecycle and recovery are unchanged.
No SDK, core, shared-runtime or control capability changes are included.

| Reviewed source | SHA-256 |
| --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityWire.java` | `005227d9101e3cb0a5d1be78ed6cc1b1051e9e4afdfb177b5b965f427eb730d5` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityCatalogTest.java` | `c975e20ef05dc0747e928180b7c8add49618406c2b0fee3ca13a029f305f1123` |

Independent inspection confirmed the focused formatter/test command passed in two
seconds. Actual JUnit XML contains 36 tests across the catalog and native suites,
with zero failures, errors or skips. Log SHA-256:
`5f792c423f326f1c8205880eb8b98f002bf9ff5dda5215e8720b59dbfdbb4dcc`.

The new regressions verify normalized lookup metadata preserves the resource ID
and typed current-temperature read; fallback behavior and alias deduplication;
and nine invalid-input variants spanning raw controls, separators, overlong labels,
registry and entity identities, units and device classes. Each invalid variant
preserves the retained identity-store bytes. Fixtures contain synthetic metadata.

The prior message-budget source approval remains separate and unchanged. The
coordinator owns final combined module/package/repository validation, artifact
publication and deployment; this source review does not claim those outcomes.
