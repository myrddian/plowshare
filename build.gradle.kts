plugins {
    java
    id("com.diffplug.spotless") version "7.0.4"
}

allprojects {
    group = "io.aeyer"
    version = "0.1.0-SNAPSHOT"

    // A reused Gradle daemon can see the caller's updated PATH while native
    // process lookup still uses the PATH it started with. Resolve build tools
    // ourselves so installing or switching runtimes takes effect without
    // restarting Gradle. Check only when a task actually needs the tool.
    tasks.withType<Exec>().configureEach {
        doFirst {
            val tool = executable ?: return@doFirst
            if (tool in listOf("node", "pnpm", "npx", "python3")) {
                val path = providers.environmentVariable("PATH").orElse("").get()
                val resolved = path.split(File.pathSeparator)
                    .map { directory ->
                        val entry = File(directory.ifEmpty { "." })
                        File(if (entry.isAbsolute) entry else File(workingDir, entry.path), tool)
                    }
                    .firstOrNull { it.isFile && it.canExecute() }
                if (resolved == null) {
                    val remedy = when (tool) {
                        "node" -> "Install Node.js 22.12+ and put its bin directory on PATH."
                        "python3" -> "Install Python 3 and put its bin directory on PATH."
                        else -> "Install pnpm with npm install -g pnpm and put it on PATH."
                    }
                    throw GradleException("$tool was not found on this build's PATH. $remedy")
                }
                executable = resolved.absolutePath
            }
        }
    }
}

val serverLaunchTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks that server launches survive replacement of the build jar."
    inputs.files("bin/plowshare", "scripts/server-launch.test.mjs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "scripts/server-launch.test.mjs")
}
val personalStarterInstallTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks safe, repeatable installation of editable Personal starter files."
    inputs.files("scripts/install-personal-starter.mjs", "scripts/install-personal-starter.test.mjs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("plowshare-server/src/main/resources/personal-starter")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "scripts/install-personal-starter.test.mjs")
}
val clientManifestCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses stale WS, MCP and client capability inventory."
    workingDir = rootDir
    commandLine("python3", "test-support/contracts/build_manifest.py", "--check")
}
val manualCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates manual chapters and tests Library publication and receipt recovery over WS."
    dependsOn(":plowshare-client-node:nodeBuild", ":plowshare-cli:pnpmInstall")
    workingDir = rootDir
    inputs.files("scripts/install-manual.mjs", "scripts/install-manual.test.mjs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("docs") { include("*.md", "manual/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("plowshare-integrations/README.md", "plowshare-integration-home-assistant/README.md", "plowshare-hooks/README.md", "deploy/docker/README.md")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "scripts/install-manual.test.mjs")
}
val legacyCliAuditCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses missing or stale legacy CLI migration mappings."
    workingDir = rootDir
    commandLine("python3", "test-support/contracts/audit_legacy_cli.py", "--check")
}
tasks.named("check") { dependsOn(serverLaunchTest, personalStarterInstallTest, manualCheck, clientManifestCheck, legacyCliAuditCheck) }

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            // Format production, test and test-fixture Java, keeping archived
            // evidence and resource templates outside the source-code pass.
            target("src/*/java/**/*.java")
            googleJavaFormat("1.24.0")
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters keeps constructor parameter names in the class file, which
        // is what lets Jackson bind JSON onto records without @JsonProperty on
        // every component — the protocol module is nothing but records.
        // -Werror because a warning nobody fails on is a warning nobody reads;
        // -serial and -processing are the two that fire on correct code
        // (records are Serializable-adjacent, annotation processors are noisy).
        options.compilerArgs.addAll(
            listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror")
        )
    }

    // javac does not validate Javadoc links. Check main-source references as
    // part of verification, without requiring tags on every private helper.
    // Test-source comments and Markdown still need review.
    tasks.named("check") { dependsOn(tasks.named("javadoc")) }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:reference", "-quiet")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }

        // Pin a Docker API version accepted by current Docker Desktop while
        // using this Testcontainers release's docker-java client.
        systemProperty("api.version", "1.44")

        // Ryuk needs a socket path mountable inside its container. Docker
        // Desktop exposes /var/run/docker.sock there, even when the host client
        // uses another path. Respect explicit rootless Docker/Colima overrides.
        if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        }
    }
}
// Explicit apply/check entry points cover every module, even from the root.
// Verification never rewrites source; each module's check also runs Spotless.
tasks.register("format") {
    group = "formatting"
    description = "Formats all Java source and tests using Google Java Style."
    dependsOn(subprojects.map { "${it.path}:spotlessApply" })
}
val formatCheck by tasks.registering {
    group = "verification"
    description = "Checks Google Java formatting in every module without editing source."
    dependsOn(subprojects.map { "${it.path}:spotlessCheck" })
}
tasks.named("check") { dependsOn(formatCheck) }

// Installable artifacts are opt-in; ordinary check does not open Electron windows.
val clientDistributions by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundles CLI/MCP/TUI and the macOS Apple Silicon desktop."
    dependsOn(":plowshare-desktop:desktopBuild", ":plowshare-cli:cliBuild", ":plowshare-mcp:mcpBuild", ":plowshare-tui:pnpmTypecheck")
    workingDir = rootDir
    inputs.files("scripts/distributions.mjs", "scripts/distribution-archive.py", "scripts/distribution-talk.mjs", "scripts/distribution-native.mjs", "scripts/distribution-runtime.mjs", "scripts/distribution-runtime.json", "scripts/distribution-entitlements.plist", "docs/distributions.md")
    inputs.dir("plowshare-desktop/assets/icons").withPathSensitivity(PathSensitivity.RELATIVE)
    for (module in listOf("plowshare-cli", "plowshare-mcp", "plowshare-tui", "plowshare-desktop", "plowshare-client-ts", "plowshare-client-node")) {
        inputs.dir("$module/src")
        inputs.files("$module/package.json", "$module/pnpm-lock.yaml")
    }
    // Build provenance changes even when the compiled payload does not.
    outputs.upToDateWhen { false }
    outputs.files("build/distributions/plowshare-clients-0.1.0-darwin-arm64.tar.gz", "build/distributions/plowshare-0.1.0-darwin-arm64", "build/distributions/plowshare-desktop-0.1.0-darwin-arm64.tar.gz")
    commandLine("node", "scripts/distributions.mjs", "clients")
}
val serverDistribution by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages the executable server jar with an external-config launcher."
    dependsOn(":plowshare-server:bootJar")
    workingDir = rootDir
    inputs.files("scripts/distributions.mjs", "scripts/distribution-archive.py", "scripts/distribution-server", "docs/distributions.md")
    inputs.dir("plowshare-server/build/libs")
    outputs.upToDateWhen { false }
    outputs.file("build/distributions/plowshare-server-0.1.0.tar.gz")
    commandLine("node", "scripts/distributions.mjs", "server")
}
tasks.register<Exec>("dockerSmoke") {
    group = "verification"
    description = "Checks prebuilt Linux server/adapter images in an isolated disposable Compose project."
    dependsOn(":plowshare-client-ts:clientBuild")
    workingDir = rootDir
    commandLine("bash", "scripts/ci/docker-smoke.sh")
}
tasks.register<Exec>("distributionCheck") {
    group = "verification"
    description = "Exercises freshly unpacked headless and server distributions."
    dependsOn(clientDistributions, serverDistribution)
    workingDir = rootDir
    commandLine("node", "--experimental-strip-types", "--test", "scripts/distributions.test.mjs")
}
tasks.register<Exec>("desktopDistributionCheck") {
    group = "verification"
    description = "Opens a freshly unpacked native desktop against the local WS fixture."
    dependsOn(clientDistributions)
    workingDir = rootDir
    commandLine("node", "--experimental-strip-types", "scripts/desktop-distribution-check.mjs")
}

// Native SDK toolchains are an explicit verification entry point. They aren't
// dependencies of server/client check or the server image build.
val sdkContractCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks shared operation/code catalogs for all SDK languages (requires Go/gofmt)."
    dependsOn(":plowshare-client-ts:clientBuild")
    workingDir = rootDir
    commandLine("node", "scripts/generate-sdk-contracts.mjs", "--check")
}
val sdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds native Python/.NET/Go SDKs; requires their documented toolchains."
    dependsOn(sdkContractCheck, ":plowshare-client-node:nodeBuild")
    workingDir = rootDir
    commandLine("python3", "scripts/sdk-build.py", "build")
}
val sdkCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests Node/Python/C#/Go SDKs against one real WebSocket conformance fixture."
    dependsOn(sdkBuild, ":plowshare-client-ts:clientTest", ":plowshare-sdk:check")
    workingDir = rootDir
    commandLine("node", "scripts/sdk-conformance.mjs")
}
val sdkDistributions by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds local npm/wheel/NuGet/Go SDK packages and Java SDK Maven artifacts."
    dependsOn(sdkCheck, ":plowshare-sdk:assemble", ":plowshare-sdk:generatePomFileForSdkPublication",
        ":plowshare-protocol:assemble", ":plowshare-protocol:generatePomFileForProtocolPublication")
    workingDir = rootDir
    commandLine("python3", "scripts/sdk-build.py", "distributions")
}
val sdkPackageCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Installs the local SDK packages into fresh consumers and repeats WS conformance."
    dependsOn(sdkDistributions)
    workingDir = rootDir
    commandLine("python3", "scripts/sdk-package-check.py")
}
