# CAP-02 independent review

Date: 24 November 2026. Implementer: `/root/ha_capability_tools_implementer`.
Reviewer: `/root/ha_capability_reviewer`. Verdict: **APPROVE** for source
implementation and owning verification. Publication, installation and live model/
device checks remain separate and are not established by this verdict.

The actual SDK facade, composition/lifecycle, profile grants, operator guidance
and native/public-SDK fixtures were reviewed. With discovery enabled the catalogue
publishes exactly `health`, `resolve`, `describe_fields` and `read_fields`. Legacy
data handlers cannot be called through that composition, even with a retained old
schema/grant. Discovery absent preserves the original six-tool path; generation
agents receive no tools and explicit control templates keep their separate legacy
mode. The new mode rejects control configuration and enables no actions.

SDK request schemas use supported existing features, closed objects and bounded
strings/arrays. Adapter-owned DTOs enforce the opaque reference/revision syntax
and total twenty-field read bound before native work; same-length non-hex negative
cases verify that removing unsupported schema `pattern` keywords did not weaken
validation. Tool results preserve full per-field type, quantity, role, unit,
source/time and availability. Whole-set ambiguity survives single-candidate pages.
Oversized results ask for a narrower selection and are never truncated or replayed.
A verified schema mismatch asks for fresh resolution, while generic IO reports
unknown cause. Neither path automatically resolves, repeats reads or submits work.

Host composition includes full discovery configuration in provider ownership,
retains the new identity close owner before later composition can fail, and
releases that owner only after borrowing SDK work physically settles. Startup
checks identity-only loss against retained receipt/owner markers before opening
transports. Actual SDK fixtures cover four-tool resolve/describe/read round trips,
no service calls or old read-tool intake, composition failure cleanup, retained
identity ownership through failed drains, and missing identity state with original
receipt bytes preserved and no new connection/publication/bootstrap.

Documentation explains current/target and duplicate-name ambiguity, known versus
unknown semantics, fixed/follow selection, exact grants, paired journals/backups
and the native read-admission boundary. It accurately states that HA policy and
SDK result publication are not atomic: subsequent changes stop new reads, while
already accepted immutable results retain their original reconciliation without
recollection, scrubbing or replay. Complete loss of both stores and all markers
cannot be distinguished locally from first setup. Examples contain configuration
placeholders and do not activate live scope or control.

Review/compiler findings are resolved: SDK-unsupported regex schema features were
removed with strict typed validation retained; unused test resources now assert
real state; macOS temporary paths are canonicalized in fixtures without weakening
production symlink guards; and source/schema failures remain explicit. Final
owning XML reports **250 passing HA Java tests across 30 suites**, including eight
facade tests and the native/public-SDK/lifecycle/profile cases. Check log SHA-256:
`5b676ccbaacc1a006741fc5822c480cc51131954cb5f4b5a1079d56b2535be77`.
The implementer confirmed completion, and the reviewer matched all twenty source
hashes to actual final bytes and the frozen source map. No open actionable finding
remains. CAP-03 separately binds the complete repository and package evidence.

Approved source SHA-256 values (repository-relative):

| Path | SHA-256 |
| --- | --- |
| `integrations/home-assistant/PROVIDER-HOST.md` | `8055692a7badf421747ef38f089d3718812a4a1108134aebca273ce452eeb4ae` |
| `integrations/home-assistant/README.md` | `40699f847cfb23c7099dda24ed6f65cc9f2496cd348023d8bad3f5d605eaaa9c` |
| `integrations/home-assistant/RELEASE-RUNBOOK.md` | `4d79c723491586c6fb105c681b9f4b5da622de6564e20bd209bab81b8ea3041e` |
| `integrations/home-assistant/RESOURCE-CONTRACTS.md` | `c18592e1a11b8fbf3e552c2d1615d0b8e31f4c1cd012812890a8ed9d8eb49f41` |
| `integrations/home-assistant/TOOL-PROVIDER.md` | `a484d41dddd449218dbad3005eb0d418c1a81081525eb9ec88a79bb04f6d1714` |
| `integrations/home-assistant/examples/application/README.md` | `243dd3902f3beb4603dfdd76db35576846bbbd68355fd9b9f8201cedf0cb5706` |
| `integrations/home-assistant/examples/application/profiles/readonly/agents/house_reader.md` | `942d74cd9548d946afbf5ca506ec294c448ca129df3f84b23e03b04f32e31084` |
| `integrations/home-assistant/examples/application/profiles/readonly/plowshare.json` | `b551fbd2dfccff29cc346a321d057785b750c97596ca3b63611f97d857086cdb` |
| `integrations/home-assistant/examples/outbound/README.md` | `dde47301373b703fa351ff8983af33b68900a88d9b6a420824e73f5f11271fe0` |
| `integrations/home-assistant/examples/outbound/readonly/application/agents/house_reader.md` | `a4acc44022dcf4ce09faff091faf2c93425fd4b583eb16b7613777ba4c515f1e` |
| `integrations/home-assistant/examples/outbound/readonly/application/plowshare.json` | `090b27dad6c322720adbdc107eb3c3aa90730709779282bc3b899eea047b667b` |
| `integrations/home-assistant/examples/outbound/readonly/provider-assist-exposed.json` | `84263cd7485b4f8ed7084dc40a68480b4b23e8e62e1e089da215119090d8ea8f` |
| `integrations/home-assistant/examples/outbound/readonly/provider.json` | `450511d5d8d3ca60656162195308e686783433e5c088bf7c5b6721fa6b29aea8` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityTools.java` | `3b0ca47724e456786b3968a86beb8719461ac85e553ae93926fa12c47d7df652` |
| `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHost.java` | `cc07635d378dcbf1e6ed9beb1f285a602f0ee62b9b500edb42ac5c6129db039a` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/ApplicationProfilesTest.java` | `ce485fd19a41ef05d8087100eee05d10b0dc39d6dccc94d5a05815e08b5d25a3` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantCapabilityToolsTest.java` | `1a6ec9852c376eaa3726107dd0929b879a71123e59b9dbcd313e03c5004720d3` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHostTest.java` | `557f29e1f711aac03165b713c19f356382737d1484391829391d07655f8f99a6` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProfilesTest.java` | `53b9467b161b067cb157f0374336c803125121def737662487c9e938ad457734` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/OutboundProviderSdkValidationTest.java` | `d25efc956132cef52bdaa65637333f390633a65ce04330bc74705602251ba40d` |
