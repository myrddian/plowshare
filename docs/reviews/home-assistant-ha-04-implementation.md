# HA-04 implementation candidate

Status: READY for independent review. Coordinator formatting and the focused checks below passed; this is not reviewer approval or delivery-node acceptance.

The candidate supplies a separate opt-in control provider, typed action executor,
revocable local control ceiling, exact profile schema/fingerprint and separate
state-predicate verifier. It reuses the accepted SDK provider/file journal and
native HA connection. [The action-provider contract](../../integrations/home-assistant/ACTION-PROVIDER.md)
records composition, authority boundaries, supported subset, receipt semantics and
limits.

The coordinator authorized additive action-only edits to the accepted HA-02
adapter, native codec and resource catalogue. Those paths need affected HA-02
reapproval before HA-04 closure. No HA-01 contract, HA-03 host, SDK, core runtime,
database or prompt-dependent feature is changed by this node. Existing manual
alias execution remains independent. All native calls in tests target isolated
mock transports; no live HA, model or device calls were made.

New production outputs:

- `ActionExecutor`, `ActionOfferValidator`, `ActionVerifier` typed capabilities.
- `HomeAssistantActionPolicy` explicit current host ceiling and revocation generation.
- `HomeAssistantActionProfiles` exact primitive schema/profile fingerprint codec.
- `HomeAssistantActionTools` separate describe/execute control declarations.
- `HomeAssistantActionVerifier` one fresh selected read and explicit predicate.
- Additive `HomeAssistantAdapter.actionExecutor` and current cached catalogue fence.

New focused fixtures:

- `HomeAssistantActionToolsTest`: complete bundle, parameter, schema, routing,
  binding, policy and queued-offer validation; distinct delivery/effect receipts.
- `HomeAssistantNativeActionsTest`: actual native WebSocket codec against a fake
  HA server, preserved identity/context and timeout/cancellation uncertainty.
- `HomeAssistantActionProviderTest`: actual SDK provider/exclusive file journal,
  intent before effect, original UUID preservation, duplicate/restart suppression,
  lost broker acknowledgement and read-only lost-result reconciliation.
- `HomeAssistantActionOfferFenceTest`: no-refresh current cached admission and
  metadata/epoch/state/policy/expiry fencing.
- `HomeAssistantActionVerifierTest`: separate fresh predicate, unavailable evidence
  and target-bundle selection checks.

## Coordinator verification

On 2026-11-17 the coordinator ran canonical `./gradlew format` successfully and
then a separate `./gradlew formatCheck` successfully. The separate check log is
`/private/tmp/core03a-v2-format-check.log` (BUILD SUCCESSFUL in 4s); its filename
reflects the other concurrent deliverable, and its output explicitly includes the
HA module and root `formatCheck`.

The focused `:plowshare-integration-home-assistant:test` selection passed in 14s:
**90 tests in 13 classes, zero failures/errors/skips**, including all 24 new action
tests. The exact log is `/private/tmp/ha04-focused-check.log`. The implementer
inspected the current JUnit XML and copied all 13 result files plus both logs to
`/private/tmp/ha04-verification-evidence/` so a later check cannot overwrite this
evidence.

| Test class | Tests |
| --- | ---: |
| HomeAssistantActionOfferFenceTest | 1 |
| HomeAssistantActionProviderTest | 4 |
| HomeAssistantActionToolsTest | 10 |
| HomeAssistantActionVerifierTest | 4 |
| HomeAssistantNativeActionsTest | 5 |
| HomeAssistantNativeResourcesTest | 9 |
| HomeAssistantObservationLifecycleTest | 9 |
| HomeAssistantReadToolsTest | 5 |
| HomeAssistantResourceCatalogTest | 8 |
| HomeAssistantResourceContractsTest | 11 |
| HomeAssistantToolJournalTest | 5 |
| HomeAssistantToolProviderTest | 17 |
| HomeAssistantToolTransportTest | 2 |

This selection does **not** include `HomeAssistantAdapterTest`, `PipelineTest` or
`HarnessPipelineTest`; it therefore does not establish current legacy manual
execution or full pipeline compatibility. Repository-wide `check`, affected
HA-02 reapproval and independent HA-04 approval remain required. No database,
live model/device, grant, deployment or integrated acceptance claim is made.
The implementer did not run Gradle or update the coordinator's delivery ledger.

## Exact post-format source hashes

These are the definitive source, test and contract-document hashes after the
successful checks. This report's own final hash is carried separately by the
coordinator candidate manifest; it cannot include itself recursively.


| Path | SHA-256 |
| --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/ActionExecutor.java` | `c861895a316e1f8803087ea9c22eff826397bd45032904aa568914cd131bfa6e` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/ActionOfferValidator.java` | `8397c83dc70604e7a076f7ba0167e70ea0b6336e4d4a18e8316f2e5f938004ef` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/ActionVerifier.java` | `b47fed524cae1924f28cac34edb3a5e9e4941bb2c86056c5da15d622f3621944` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionPolicy.java` | `d1b654579d3e5cb8fc68e27e41adfbcfdb8998691c9386416a052b69c23e1680` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionProfiles.java` | `9b6692f03e587019a38b985dda2c5dd92d4fcf103fe150a2cac741642124b0b4` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionTools.java` | `2c8d62d69b30ebf7c4cd39040c3f79cb30b25e493a0d96e737ca18ba19a7dc1e` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionVerifier.java` | `579e5c42c0996e20c9e829b60f08fd171356416186f017c719ad927e1eda861c` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java` | `24baa8a0c5116b71f989ff16903ba5ba134c312409d2f48b8a1bae7b1a1f21ce` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantWire.java` | `34477a58ce99edc6677c61e9079ae7468c88f85350bb38519a7d9e0c608504cb` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantResourceCatalog.java` | `186f5dddbb62622a3e57f192882a2acd3ca4f93cda66f5cd93abd7c6b1c46acb` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionToolsTest.java` | `bae50af73ad2297e028a9f3157dbd4aae241550ff0683d8fd26a4b1c44779487` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionVerifierTest.java` | `88d382e445909466ac29a0d131a8f0f5a3f95e3e9b26db787f8d04a7aa91fc9d` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantNativeActionsTest.java` | `5fe5c2d276c872a01c25fe9faf3f1927464be95e26a0499fd9d9de5f0ecddc01` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionProviderTest.java` | `adc9a1d4c8e108322f6efcaf07a8b12b0fd78c2a2c29703e44b4a844bd2eeade` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionOfferFenceTest.java` | `1ae2bda7b5f55f9cc0f01f94a6f8dc9c2511ac44d6a4a7fa836cbec5620adb25` |
| `integrations/home-assistant/ACTION-PROVIDER.md` | `eba98fe9641c532987e33d9d729ffae8c0fc4240f6503442acdb227e3af672f8` |

Evidence log hashes: focused check `9b3b741d2d686bd65b25c4f6f6a6024db12b3b053706f482df70b60d59a716e4`; separate format check `5328630a9154614956f2a4b3224ba0adea1fc89c1ec16b9dece4e45c87519d8a`.
