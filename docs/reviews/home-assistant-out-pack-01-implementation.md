# OUT-PACK-01 implementation

Implementer: `/root/ha_outbound_pack_implementer`. Baseline: main `6de685f6`.
The coordinator owns exact candidate freezing, serialized verification and
independent reviewer approval. This report is not an approval or deployment.

The existing HA distribution now selects `HomeAssistantMain`. Legacy arguments
delegate unchanged to the existing integration `Main`; only explicit
`--provider CONFIG` and `--provider --check CONFIG` select the new outbound host.
The distribution ships `PROVIDER-HOST.md` alongside its existing examples.
Installed example links resolve within that distribution; general platform
documentation uses canonical repository links.

`HomeAssistantProviderConfiguration` reads at most 256 KiB before strict UTF-8,
duplicate/trailing-token checks and complete typed conversion. Immutable records
validate explicit origins, identities, token environment references, timings,
configured action selections and normalized journal separation. JSON remains at
that configuration boundary. Provider mode refuses workflow fields, nonempty
subscriptions, inference controls and incompletely disclosed fixed action targets.
Offline check does not resolve credentials, open sockets or create/lock journals.
Actual startup also conservatively refuses existing symbolic-link components
before journal ownership is acquired. Existing journal locks and atomic-file
fences remain unchanged.

The first preflight proposed separate provider credentials, but independent plan
review found that current public Application scopes bind provider publication
and invocation to the execution principal. The accepted v2 contract therefore
uses one explicit account/token reference and one authenticated SDK connection
for both logical providers. Provider names, prefixes, grants and journals remain
separate. No platform/SDK change or alternate authority path was introduced.

`HomeAssistantProviderHost` composes the existing adapter, resource catalogue,
read tools and optional fixed action tools. Narrow injected provider and shared
maintenance interfaces own effects; concrete SDK/adapter selection occurs only
in composition. Startup reconciles the original retained intent before catalogue
publication; an unresolved intent still prevents renewal through the existing
journal. Normal cadence schedules new lease renewals and bounded polls. Any
lifecycle failure stops intake, skips further mutations and retains original
receipts. There is no SDK reconnect, automatic effect replay, hidden scope grant,
or competing runtime/journal implementation.

The host checks stop between each owner operation, allowing the current resource
pass to settle without starting a fresh cycle. Shutdown grace includes the
greater connection/provider active-pass budget, one normal withdrawal per
provider, and provider plus shared-resource cleanup budgets. Provider journal
cleanup must settle before shared SDK/HA close begins. Shared close has one
retained physical task and finite caller waits; a later join reuses that exact
task. Incomplete cleanup preserves its owner and may be explicitly rejoined,
without a new physical owner or a replacement connection. Interrupt state is
restored after cleanup joins.

The CLI registers its process hook before the resource factory runs. The factory
publishes its exact host owner before the first transport/journal effect and
checks stop between startup owners. Typed startup failure preserves that owner.
A hook-registration failure therefore opens nothing. Normal cleanup removes the
hook only after completion is observable. Incomplete startup/cleanup retains the
same hook/owner for bounded process-shutdown observation and cleanup joining;
operational errors do not forcibly terminate that physical owner with System.exit.
Safe bounded diagnostics do not echo dependency messages, credentials or paths.
Process termination is never evidence that a remote action aborted.

Coordinator preflight found a publication race in the first host draft: publishing
the owner before startup was enough for reachability, but a process hook could
close shared resources while the factory was still opening its next journal.
The final factory owns an explicit startup fence before publication; close and
run/cycle refuse while that owner remains active. It releases the fence only on
return or before its own failure cleanup. Shutdown grace reads immutable
configured-provider count rather than the still-growing startup list. The
regression stops the actual factory inside its owner callback, proves close/run
refusal and the fixed grace, and then closes that same owner after return without
opening any socket or journal. Unexpected runtime withdrawal/cleanup failures
also retain uncertainty, continue owned drain and cannot escape safe diagnostics.

## Authored files

- `integrations/home-assistant/build.gradle.kts`
- `integrations/home-assistant/README.md`
- `integrations/home-assistant/TOOL-PROVIDER.md`
- `integrations/home-assistant/PROVIDER-HOST.md`
- `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantMain.java`
- `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderConfiguration.java`
- `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHost.java`
- `integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderLifecycle.java`
- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantMainTest.java`
- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderConfigurationTest.java`
- `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHostTest.java`

The profiles implementer separately owns `examples/outbound/**` and
`OutboundProfilesTest.java`. Root owns the plan/ledger and all candidate manifests.
No existing adapter/provider lifecycle, core, SDK, migration or Assist source was
modified by this node. The README's existing harness command now includes its
required explicit `-PfullDb` flag; this correction does not run that harness.

## Verification and limits

The coordinator's early formatter/compile run passed the 24 configuration and 12
parallel profile cases in eight seconds. The first combined default-HA/format/
install/boundary run then passed in 38 seconds before the startup-owner correction.
Its successor ran 163 tests with one fixture failure: macOS's noncanonical
temporary directory crossed the deliberately refused `/var` symbolic-link ancestor
before the startup assertion. Both actual startup fixtures now use the trusted
temporary directory's real path, while the explicit symlink-rejection test creates
its own refused alias. No production guard was weakened.

Final current-Java verification passed:

- `./gradlew format`: PASS, four seconds, 43 tasks.
- `./gradlew formatCheck :plowshare-integration-home-assistant:test
  :plowshare-integration-home-assistant:installDist integrationBoundaryCheck
  sdkContractCheck`: PASS, 33 seconds, 72 tasks; all 163 default non-database HA
  tests passed with zero failures/errors, including 24 configuration, nine host,
  eight CLI and 12 separately authored profile cases. Shared SDK contracts cover
  296 operations, 20 outcome codes and 1,012 canonical DTO cases.

Focused cases cover normal lifecycle ordering, scheduled renewal, uncertainty
without retry, retained provider/shared cleanup, preserved interruption, stop
during blocked maintenance, immutable startup ownership, offline check, invalid
input, partial startup, hook retention/removal and registration failure before
resource acquisition. The existing adapter suite remains included.

After those compiled checks, the coordinator requested a documentation-only
Java 21/external-host prerequisite clarification in `PROVIDER-HOST.md`. The exact
source manifest below includes that final guide. Root will refresh `installDist`
for those documentation bytes and bind the installed artifact separately before
candidate acceptance; this report does not substitute the earlier artifact.

Fixtures use fake effect/lifecycle boundaries and the existing isolated adapter
test suite. They do not prove authenticated live grants, real HA version/service
compatibility, physical device success, deployment or universal physical abort.
Filesystem reads are size-bounded and journals retain their existing private,
nonblocking exclusive-lock contract; a stalled kernel/filesystem operation still
requires supervised local storage and is not proven to terminate by these tests.

Provider mode deliberately does not merge named-control receipts into the legacy
workflow feedback index. It starts no workflow and rejects the configuration
that would imply that unsupported composition. Assist, Conversation, AI Task,
system-context, cache backend measurement, deployment and live-validation gates
remain unchanged. No real database, HA, model or device operation was performed.

## Exact checked source and evidence

```json
{
  "files": {
    "integrations/home-assistant/build.gradle.kts": "f6c26532ed02fe90c764c1ed95e238025a165c67ec2c840294979648251da267",
    "integrations/home-assistant/README.md": "3173eb536eea0a69a346ebc07ea09feff25fe5420320e6e746ce2454b25ed16e",
    "integrations/home-assistant/TOOL-PROVIDER.md": "3ca0f018f553c0412b9ea4aeb3ccdf6a2bf1ce164319caacc53eb8446e07fbd2",
    "integrations/home-assistant/PROVIDER-HOST.md": "1e593204fbec14ea4237e2318ea8e74807b98b231fd83fc45c80617d3d4acbcf",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantMain.java": "85d8767ecf393345bf5f887b5969dd8d64866da70ef8a70e60edc93293fcead2",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderConfiguration.java": "db8983c94a3e9811f721ceee1bb34bcb92fd0e004ff92183ec5bd4aa9f8f631b",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHost.java": "322cd7b24a3d47fd54747192d36ee27e4784c81fee2957c11aa0c9ab303a2cc1",
    "integrations/home-assistant/src/main/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderLifecycle.java": "85ce158535b4ae1b3682e22e49f6c8aa942005c4595b080e73960442b6090ca6",
    "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantMainTest.java": "d375a59a60cd1adf41ee9c0aa38daeeb0273901376a3e6a75b669bc9750114b8",
    "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderConfigurationTest.java": "135eba7cc8a02ede436128f2725f3d9e4a1ec50b3355455753d37891d12ad355",
    "integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantProviderHostTest.java": "ec828a3c9bc4c161d7f385e2438b1fabd0f88466cd3ddb386267a084bc62e270"
  },
  "coordinator_logs": {
    "/private/tmp/ha-outbound-pack-format-final.log": "f8dfde4ea435e7d1312b2566c16881ce3dc177d833d68ae4662d7b33b5a2065c",
    "/private/tmp/ha-outbound-pack-check-final.log": "bb2a764d751c9b55746b6b2d4f213f926ea487809d9cc8afb91364c5cc1af83e",
    "/private/tmp/ha-outbound-pack-check-v2.log": "f10ad524c1888c2ac98acdf4bc0d854c7c17ec5ae07fde22ca9fd0e56d0dd7e5"
  }
}
```
