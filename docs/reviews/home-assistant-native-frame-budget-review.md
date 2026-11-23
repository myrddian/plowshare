# HA native message budget independent review

Reviewer: `/root/ha_capability_reviewer`, independent of the implementer and the
coordinator. Date: 24 November 2026. Decision: **APPROVE the source change and
its automated validation**. This approval does not assert a successful production
temperature read or approve any additional codec change.

The actual native registry observation was a valid successful response containing
1,746 entries and 1,066,732 UTF-8 bytes, exceeding the old 1 MiB native message
limit by 18,156 bytes. Independent inspection of the private response and the
exact-artifact probe stack confirmed failure at the first bulk registry query.
This is evidence for an adapter transport budget failure, independent of model
prompts, device availability and ambiguous display names. Private payloads and
operational identifiers are excluded from this report.

The actual two-file diff increases the finite complete-message budget to 4 MiB
and checks character length before allocating the UTF-8 projection. The existing
UTF-8 byte check, connection fencing and failure behavior remain. No SDK, core,
shared runtime, result envelope, registry row limit, resource selection, grants,
action/control contract or retry behavior changes. The library supplies complete
WebSocket messages; the adapter does not implement a new fragment accumulator.

The new real WebSocket regressions verify a synthetic 1,746-row response above
1 MiB can resolve the admitted resource and read its typed current temperature
without disclosing unselected metadata. A response above 4 MiB of UTF-8 bytes but
below 4 MiB of characters fences the connection, preserves retained identity
bytes and makes no automatic command retry or device service call.

| Reviewed source | SHA-256 |
| --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java` | `3fbf08a067a9052b20934c583d48ef0961bd7a20e2512305679adbe9a5ab92db` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityNativeTest.java` | `8908ffd87afb21fe3978ae8da8aab6fb531ae9956e6cdd978ae3ebf5b6e58a1c` |

Independent evidence inspection:

- Focused native suite: 11 tests, zero failures, errors or skips; successful
  five-second build. Log SHA-256:
  `54d6be0440286638de8e6a808d7f9729635f5eaf2d0dedcdd78bb36a04b0c13f`.
- Full HA module: actual JUnit XML contains 252 tests across 30 suites, zero
  failures, errors or skips.
- Broader module/package/format/repository validation: successful build in
  3 minutes 44 seconds, 144 tasks. Log SHA-256:
  `72866c8f071a045f66fb8db54c257d4946b9cb356dff11392970b95e84536f80`.
  No real database tests were requested or enabled.

A separate isolated candidate native read reported another failure after the
larger response was admitted. Its original result is retained for investigation;
this review does not claim the budget increase alone completed live acceptance.
The previous merged capability evidence and review artifacts remain unchanged.
Production source hashes, deployment, journal preservation and actual Assist
read outcomes require their separate operational evidence.
