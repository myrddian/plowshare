# HA Optional runtime dependency independent review

Reviewer: `/root/ha_capability_reviewer`, independent of the implementer and the
coordinator. Date: 24 November 2026. Decision: **APPROVE the source change and
focused runtime-consumer/package evidence**. Live deployment and successful Assist
reads require their separate operational evidence.

The isolated candidate failed while persisting a populated capability anchor:
Jackson could not encode its Optional device metadata. The ordinary module tests
include server runtime dependencies, masking the missing dependency in the owning
application's installed runtime. The exact candidate failure was retained before
any live binary replacement.

The adapter now declares `jackson-datatype-jdk8` as its own runtime dependency,
using the existing Jackson version property. Its existing module-discovery codecs
remain unchanged. No SDK, shared-runtime, core, identity-store format, grants,
control or retry behavior changes are included.

The actual three-file diff includes a plain child-JVM consumer and its process
harness. The child receives only the owning application's main runtime classpath
and two copied consumer classes. It rejects JUnit/server-runtime contamination.
It persists and reopens nonempty Optional device, area and unit metadata, resolves
and describes the resource, reads a typed current temperature, and checks public
JSON values, nulls and source timestamps. A counterfactual child omits only the
Optional module and reproduces `InvalidDefinitionException`. This verifies the
runtime dependency closure rather than borrowing the test runtime classpath.

| Reviewed source | SHA-256 |
| --- | --- |
| `integrations/home-assistant/build.gradle.kts` | `c75bc4fe7317f17ed8023714dc3cbcbafe093a37378c949042e5a0fc63b54fb2` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantInstalledRuntimeConsumer.java` | `ef635d686178ce9c8ab33a35b2224e0d2474ce92cf0ace0d3f0694bf8fa11607` |
| `integrations/home-assistant/src/test/java/io/aeyer/plowshare/integrations/ha/HomeAssistantInstalledRuntimeTest.java` | `cf559b3519ed899fa9fbc317a363958e100196ab1e9e00cd899d30960e10ebd0` |

Independent inspection confirmed both child-JVM cases passed, zero failures,
errors or skips; the focused formatter/test/installDist build succeeded in four
seconds. Log SHA-256:
`991bae8bfa6048e19bfaf4699de5150db26390dc3e15b9bf895b21a99d750f7e`.

The freshly installed distribution contains 58 files and 27 JARs. Both generated
Unix and Windows launchers reference `jackson-datatype-jdk8-2.17.2.jar`. The exact
package delta therefore includes both launcher files as well as the added module
and the previously reviewed HA adapter JAR. Operator scripts must preserve the
complete manifest and executable launcher permissions when replacing artifacts.

The earlier message-budget and display-label reviews remain unchanged. Combined
repository checks, final artifact bindings, isolated native acceptance and live
Assist outcomes remain coordinator-owned subsequent evidence.
