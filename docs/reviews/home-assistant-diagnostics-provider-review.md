# DIAG-01 independent review

Date: 24 November 2026. Reviewer: `/root/ha_diagnostics_reviewer`.
Implementer: `/root/ha_diagnostics_provider_implementer`.
Baseline: `f33a10daeaf09f84189edfba1039ee98c0b8ec9e`.
Verdict: **APPROVE** for source implementation and owning test verification.
This does not establish publication, installation or live provider health.

The actual Java diff, public SDK provider ownership, catalogue/journal lifecycle,
resource catalogue/policy projection, tool schemas and exact profile grants were
reviewed. Health is a memory-only observation with historical SDK evidence and
an explicit untested end-to-end state. Diagnosis passes the same admitted alias
and revision through discovery and the fresh reader, never probes hidden aliases,
never returns state/attribute values, and separates configured attribute policy
from absent metadata and unsupported values. Generic IO errors remain an unknown
observation failure. Schema/typed request boundaries reject invalid input before
native reads. Existing inline revision schemas, operation budgets, delivery fences
and uncertainty behavior remain intact. No control/default/read scope expansion
or core/SDK/shared-runtime change occurs.

The actionable communication-timestamp finding is resolved: a no-op reconciliation
no longer records an SDK communication. Only confirmed connect, successful poll
(which consumes each declared tool), catalogue publication and withdrawal update
that observation. Additional availability/freshness and invalid-attribute tests
cover the boundary. Active six-tool documentation and the installed-distribution
canonical documentation link are corrected. No unresolved actionable finding
remains.

Evidence inspected: final owning HA XML reports 200 tests and zero failures across
24 suites, including actual public SDK/native WebSocket diagnostics and recovery
fixtures. The initial old four-tool assertion failed and was corrected to the new
six-tool declaration count; the final owning suite is green. Full repository and
packaging acceptance belongs to DIAG-03, not this source-node verdict. No live
model, HA/device mutation or PostgreSQL test establishes these results.

Approved source SHA-256 values (paths relative to `integrations/home-assistant/`):

| Path | SHA-256 |
| --- | --- |
| `ACTION-PROVIDER.md` | `77fe587c8ecdf020de6a69e2c399e7082711904908a5cb8a5e395156d53a6a20` |
| `PROVIDER-HOST.md` | `3838641f1ec6064088755b4a3e9e172e76f83b854fd1cc2d62eb27743c106695` |
| `TOOL-PROVIDER.md` | `da1866dab736e610cce95746b68357a6371676cd58ef1b9b7ed4d9f035ce1871` |
| `assist/PACKAGE-PROFILES.md` | `b1167bf1a8cfaca90bd7ddedef283f9993ebef60d190569ca0b82999aa097e76` |
| `examples/application/README.md` | `31d763d7e65371296d0d1cb942c2e535577cf43f8b46eb494ef3d9acd6ac9092` |
| `examples/application/profiles/control/agents/house_controller.md` | `b52a4607ef6885e4a1eef1dcf8d3346925b55fd5fd249a681f6df2949bc7a7ca` |
| `examples/application/profiles/control/agents/house_reader.md` | `8a3e2daa446ae30fc5860805a48da496d0f2274cb3c33bd0ebadb8a5af5e8ec6` |
| `examples/application/profiles/control/plowshare.json` | `401fb5d60217d00260c758471f8de29d1219804c15bac8de7df5703f7624bcb0` |
| `examples/application/profiles/readonly/agents/house_reader.md` | `8a3e2daa446ae30fc5860805a48da496d0f2274cb3c33bd0ebadb8a5af5e8ec6` |
| `examples/application/profiles/readonly/plowshare.json` | `47df000af98e7cc6933c7bfba32af102e3b9f41b3991bf5111e383adf1b9cf8a` |
| `examples/outbound/README.md` | `7b56fd2d07382551fb42e86c83602188daa121b48c5fb628bcc8a32f804353b3` |
| `examples/outbound/control/application/agents/house_controller.md` | `24d3a5734b6189c044739be6ac54cc2aa3284c85ce7a35bc05601e41d82c1e2f` |
| `examples/outbound/control/application/plowshare.json` | `ccce2a7bc5f01af7c0ca3c71db4253ce679e5887a93a7e2421d1eebebc5b7e4a` |
| `examples/outbound/readonly/application/agents/house_reader.md` | `5753e4997525297e14b9cc32bdf8c5be4f0bc992d1d8934c2849cf3350e5c748` |
| `examples/outbound/readonly/application/plowshare.json` | `e1daf4fc03d1a7c5cba39e7c3914468ecd61dd0f36545acf3b2d8ae551f4bd5b` |
| `src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderDiagnostics.java` | `49c391e7b7d38724badf1741b9bbe18e2e4b8cfbcc28283577a4c2f606d0e619` |
| `src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHost.java` | `508e84f2a7381631ffc81ceb6a48d6cee28a8d38dc48e14b4da2c68a8d77fc03` |
| `src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantReadDiagnostics.java` | `41c49be63b62ec99c661a21ced4bcf04eb475d7550354cd9d3fe6ca9f6b29738` |
| `src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantReadTools.java` | `fe4bf133ed1e6fd4611e639455b19d2f131e6b00f11794d0d1b270db1a3fcd24` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/ApplicationProfilesTest.java` | `2bacbb67567e389f2699a4071ac416afd904eb128ee6533f97e0cbdf260a090f` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantActionToolsTest.java` | `472c4101d5276f35d439cf012764b4924c0dab8adcb1f0090aa67798c98c9673` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderDiagnosticsTest.java` | `62ac9ef265c80c99cf90823978c3afa5e3181dfe017e36b90f3d887591cfd28a` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantReadToolsTest.java` | `9b7606077c37cee927660ac4817dffb57823e0860de6e985926d242cbc0a252f` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantToolProviderTest.java` | `1065a986562afab15974d36cf107e6f5cd41cb7d285a4bc64d9f472e143cb375` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProfilesTest.java` | `1391bfcf550401960bddf58b6c4d57696b367a54f77d4352ecf27ee79881a938` |
| `src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProviderSdkValidationTest.java` | `005ec3d8e6110448f80934709bbff7a2b6fe3d3fb1704c05241c3fa0921acb03` |
