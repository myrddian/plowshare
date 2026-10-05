pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Provision Java 21 for fresh checkouts that lack a matching local JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "plowshare"

// Client modules participate in Gradle so check reaches their TypeScript
// tests/type checks and the server consumes a declared console bundle output.
// Java SDK support lives alongside the shared TypeScript WS client;
// CLI and MCP are TypeScript entry points. HTTP remains for protocol boundaries
// such as authentication, uploads and Git, not general operation dispatch.
include(
    "plowshare-protocol",
    "plowshare-server",
    "plowshare-client-ts",
    "plowshare-cli",
    "plowshare-console",
    "plowshare-tui",
    "plowshare-desktop"
)

// An extensions module is a real search provider: its own Spring Boot
// process, on its own port, speaking plowshare-protocol's search contract
// over HTTP rather than a Java interface. It is a Gradle project — rather
// than a directory some operator happens to run separately — for the same
// reason plowshare-console is one above: so its build and its tests are
// reachable from `./gradlew build` and `./gradlew check` under the same
// names as every other module, instead of a second toolchain nobody runs by
// default.
//
// Neither is on plowshare-server's classpath, and that omission is the
// whole point of the out-of-process design (spec §3), not an oversight to
// fix later. `RemoteSearchProvider` dials a registered `baseUrl` over HTTP;
// it has no compile-time dependency on any provider's code, and a provider
// crashing, hanging or leaking a vendor credential cannot do so inside the
// process holding the token ledger, `FileAccess` and the archive. Wiring
// either module onto plowshare-server's classpath would collapse that
// boundary back into the in-process design the spec explicitly rejects —
// see implementation rationale §3.
include(
    "extensions:search-searxng",
    "extensions:search-brave"
)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("plowshare-client-node", "plowshare-mcp")
include("plowshare-sdk", "plowshare-a2a")
include("plowshare-integrations", "plowshare-integration-home-assistant")

// Source layout is independent of published coordinates and Gradle task names.
project(":plowshare-sdk").projectDir = file("sdk/java")
project(":plowshare-client-ts").projectDir = file("sdk/typescript")
project(":plowshare-client-node").projectDir = file("sdk/node")
project(":plowshare-integrations").projectDir = file("integrations/runtime")
project(":plowshare-a2a").projectDir = file("integrations/a2a")
project(":plowshare-integration-home-assistant").projectDir = file("integrations/home-assistant")
