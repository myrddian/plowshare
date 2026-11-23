# HA Optional runtime dependency follow-up

Date: 24 November 2026. Implementer:
`/root/ha_capability_foundation_implementer`. Independent reviewer:
`/root/ha_capability_reviewer`.

The standalone HA distribution lacked Jackson's JDK8 datatype module. Unit tests
inherited that module from the server test classpath, masking failures when the
installed provider persisted nonempty `Optional` metadata. The HA application now
declares `jackson-datatype-jdk8` as its own runtime dependency using the existing
`jacksonVersion`. Existing mapper module discovery needs no code changes. SDK,
shared runtime, core, authority and disabled-control settings are unchanged.

A child-JVM regression uses only the owning application's `main.runtimeClasspath`
plus two copied plain consumer classes. It excludes the complete test classpath
and explicitly rejects JUnit/Spring contamination. The positive case persists and
reopens nonempty device/area/unit metadata, resolves/describes/reads capabilities,
and verifies Optional values encode as native JSON strings/numbers/nulls and ISO
timestamps. The negative case removes the JDK8 module and reproduces the actual
`InvalidDefinitionException`, proving the test detects the installed-runtime gap.

```sh
./gradlew :plowshare-integration-home-assistant:spotlessApply :plowshare-integration-home-assistant:test --tests 'io.aeyer.plowshare.integrations.ha.HomeAssistantInstalledRuntimeTest' :plowshare-integration-home-assistant:installDist --max-workers=2
```

Result: **PASS**, two consumer tests, zero failures/errors/skips; build completed
in four seconds. The installed distribution contains **58 files and 27 JARs**.
Both generated Unix and Windows launchers reference the added JDK8 module.
Focused log SHA-256:
`991bae8bfa6048e19bfaf4699de5150db26390dc3e15b9bf895b21a99d750f7e`.

| Source path | SHA-256 |
| --- | --- |
| `integrations/home-assistant/build.gradle.kts` | `c75bc4fe7317f17ed8023714dc3cbcbafe093a37378c949042e5a0fc63b54fb2` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantInstalledRuntimeConsumer.java` | `ef635d686178ce9c8ab33a35b2224e0d2474ce92cf0ace0d3f0694bf8fa11607` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantInstalledRuntimeTest.java` | `cf559b3519ed899fa9fbc317a363958e100196ab1e9e00cd899d30960e10ebd0` |

Final broad checks, independent review and the actual installed-artifact native
probe/rollout are coordinator-owned subsequent evidence. Prior immutable review
artifacts remain untouched; this report does not claim live validation.
