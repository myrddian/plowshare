# HA native registry frame budget follow-up

Date: 24 November 2026. Implementer:
`/root/ha_capability_foundation_implementer`. Independent reviewer:
`/root/ha_capability_reviewer`.

The native HA transport previously closed its connection when a legitimate whole
registry response exceeded 1 MiB, before selection and field projection could run.
The adapter now accepts native messages up to **4 MiB of UTF-8 bytes**. An initial
character-count check avoids allocating a UTF-8 projection for obvious excess.
This finite native budget remains independent of registry row/selection bounds,
operation deadlines and the existing SDK tool-result envelope. No SDK, core,
shared-runtime, action/control or disclosure policy changes are included.

Two native WebSocket regressions use synthetic metadata only:

- A registry above 1 MiB and below 4 MiB resolves the admitted resource and returns
  its typed current temperature without exposing unselected metadata.
- A response above 4 MiB in UTF-8 but below 4 MiB in character count is refused.
  The connection is fenced, retained identity bytes remain unchanged, a later
  call makes no automatic network retry, and no device service is called.

Focused command:

```sh
./gradlew :plowshare-integration-home-assistant:spotlessApply :plowshare-integration-home-assistant:test --tests 'io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityNativeTest' --max-workers=2
```

Result: **PASS**, all 11 tests, zero failures/errors/skips; build completed in five
seconds. Focused log SHA-256:
`54d6be0440286638de8e6a808d7f9729635f5eaf2d0dedcdd78bb36a04b0c13f`.

| Reviewed source path | SHA-256 |
| --- | --- |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantAdapter.java` | `3fbf08a067a9052b20934c583d48ef0961bd7a20e2512305679adbe9a5ab92db` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityNativeTest.java` | `8908ffd87afb21fe3978ae8da8aab6fb531ae9956e6cdd978ae3ebf5b6e58a1c` |

The independent reviewer approved these source bytes and focused evidence. Broader
repository/package verification and any operational rollout are coordinator-owned
subsequent evidence. This report does not claim deployment or live validation and
does not alter the earlier immutable capability review artifacts.
