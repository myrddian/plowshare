pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Without a toolchain resolver, `JavaLanguageVersion.of(21)` only works on a
// machine that already happens to have a JDK 21 lying around — this one builds
// only because an earlier Anchor build cached Temurin 21 under ~/.gradle/jdks.
// Anchor omits this and gets away with it; a fresh checkout there fails with a
// toolchain error that names no remedy. foojay provisions one instead.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "plowshare"

// plowshare-console is a TypeScript module and not a Java one. It is a Gradle
// project anyway, rather than a directory some script happens to run pnpm in,
// so that its build and its tests are reachable from `./gradlew build` and
// `./gradlew check` under the same names as everything else — and so that the
// bundle it produces is a declared output that `:plowshare-server:processResources`
// can depend on instead of a path somebody remembers to rebuild.
//
// plowshare-tui is the second TypeScript module and it is a Gradle project for
// exactly the reason above: so `./gradlew check` reaches its vitest suite and
// its type check under the names every other module uses. It produces no bundle
// and nothing on this list depends on it, which is the one way it differs — see
// plowshare-tui/build.gradle.kts.
//
// WHICH CLIENT IT IS, because two now exist and the names do not say it.
// plowshare-client is the JAVA client -- `Capabilities`, `ServerClient`,
// `HttpServerClient`, the CLI and the MCP adapter -- and it talks HTTP.
// plowshare-tui is the TERMINAL client: TypeScript on node, speaking WebSocket
// frames and nothing else, with the one HTTP call it makes being auth, before
// the socket exists. Neither is built from the other and neither is a front end
// for the other. See implementation rationale
include(
    "plowshare-protocol",
    "plowshare-server",
    "plowshare-client",
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
