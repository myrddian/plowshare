import java.util.zip.ZipFile

plugins {
    java
    id("com.diffplug.spotless") version "7.0.4"
}

// Database coverage is an explicit run mode, including in CI. Keep the value
// in test task inputs so switching modes cannot restore the other mode's cache.
val fullDb = providers.gradleProperty("fullDb").map { value ->
    when (value) {
        "", "true" -> true
        "false" -> false
        else -> throw GradleException("Use -PfullDb, -PfullDb=true or -PfullDb=false.")
    }
}.orElse(false).get()

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
    description = "Checks explicit server configuration and immutable launch snapshots."
    inputs.files(
        "bin/plowshare", "bin/plowshare-deployment", "bin/plowshare-talk",
        "bin/plowshare-searxng", "scripts/server-launch.test.mjs",
        "scripts/launcher-clients.test.mjs"
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "scripts/server-launch.test.mjs", "scripts/launcher-clients.test.mjs")
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
    inputs.files("integrations/runtime/README.md", "integrations/home-assistant/README.md", "plowshare-hooks/README.md", "deploy/docker/README.md")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "scripts/install-manual.test.mjs")
}
val legacyCliAuditCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses missing or stale legacy CLI migration mappings."
    workingDir = rootDir
    commandLine("python3", "test-support/contracts/audit_legacy_cli.py", "--check")
}
val databaseTestBoundaryCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses Docker test fixtures without the full-db opt-in tag."
    workingDir = rootDir
    inputs.file("scripts/check-database-tests.py")
    inputs.files(fileTree(rootDir) { include("*/src/test/java/**/*.java", "sdk/*/src/test/java/**/*.java", "integrations/*/src/test/java/**/*.java") })
    commandLine("python3", "scripts/check-database-tests.py")
}
tasks.named("check") { dependsOn(serverLaunchTest, personalStarterInstallTest, manualCheck, clientManifestCheck, legacyCliAuditCheck, databaseTestBoundaryCheck) }

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
        dependsOn(rootProject.tasks.named("databaseTestBoundaryCheck"))
        inputs.property("fullDb", fullDb)
        useJUnitPlatform {
            if (!fullDb) excludeTags("full-db")
        }
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
// TypeScript tools live in a private root package so client runtime dependencies
// and independent package installs remain separate from repository verification.
val typescriptModules = listOf("plowshare-client-ts", "plowshare-client-node", "plowshare-cli",
    "plowshare-mcp", "plowshare-tui", "plowshare-console", "plowshare-desktop")
val typescriptTools by tasks.registering(Exec::class) {
    workingDir = rootDir
    inputs.files("package.json", "pnpm-lock.yaml")
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}
fun Exec.typescriptSources() {
    workingDir = rootDir
    dependsOn(typescriptTools)
    inputs.files("package.json", "pnpm-lock.yaml", "eslint.config.mjs", ".prettierrc.json",
        ".prettierignore", "tsconfig.strict.json")
    inputs.files(fileTree(rootDir) {
        include("sdk/*/src/**/*.ts", "sdk/*/**/tsconfig*.json", "plowshare-*/src/**/*.ts", "plowshare-*/**/tsconfig*.json", "plowshare-*/vite.config.ts")
        exclude("**/node_modules/**", "**/build/**", "**/build-tests/**", "**/dist/**")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
}
val typescriptFormat by tasks.registering(Exec::class) {
    group = "formatting"
    typescriptSources()
    commandLine("node", "node_modules/prettier/bin/prettier.cjs", "--write", "sdk/*/src/**/*.ts", "plowshare-*/src/**/*.ts", "plowshare-*/vite.config.ts")
}
val typescriptFormatCheck by tasks.registering(Exec::class) {
    group = "verification"
    typescriptSources()
    commandLine("node", "node_modules/prettier/bin/prettier.cjs", "--check", "sdk/*/src/**/*.ts", "plowshare-*/src/**/*.ts", "plowshare-*/vite.config.ts")
}
val typescriptLint by tasks.registering(Exec::class) {
    group = "verification"
    typescriptSources()
    // Typed linting consumes package declarations; never lint stale SDK builds.
    dependsOn(":plowshare-client-ts:clientBuild", ":plowshare-client-node:nodeBuild",
        ":plowshare-mcp:mcpBuild")
    // Every lint project resolves its own test/framework types. The corresponding
    // check tasks may run concurrently, so their installs cannot supply this ordering.
    dependsOn(typescriptModules.map { ":$it:pnpmInstall" })
    commandLine("node", "node_modules/eslint/bin/eslint.js", "--max-warnings=0")
}
val typescriptPolicyTest by tasks.registering(Exec::class) {
    group = "verification"
    workingDir = rootDir
    dependsOn(typescriptTools)
    inputs.files("scripts/check-typescript-policy.mjs", "scripts/check-typescript-policy.test.mjs")
    commandLine("node", "--test", "scripts/check-typescript-policy.test.mjs")
}
val typescriptPolicyCheck by tasks.registering(Exec::class) {
    group = "verification"
    typescriptSources()
    inputs.files("scripts/check-typescript-policy.mjs", "sdk/typescript/scripts/generate-operation-schemas.mjs")
    commandLine("node", "scripts/check-typescript-policy.mjs")
}
val typescriptAdditionalTypes by tasks.registering(Exec::class) {
    group = "verification"
    typescriptSources()
    // The extra TUI project resolves its own Node types. Its install must complete before
    // this gate runs; another check's install is not an ordering dependency on a clean runner.
    dependsOn(":plowshare-client-node:nodeBuild", ":plowshare-mcp:mcpBuild", ":plowshare-tui:pnpmInstall")
    // These test/lint projects cover source files outside the emitted solutions.
    commandLine("node", "scripts/check-typescript-policy.mjs", "--compile-additional")
}
for (module in typescriptModules) {
    project(":$module").tasks.named("check") {
        dependsOn(typescriptFormatCheck, typescriptLint, typescriptPolicyCheck, typescriptPolicyTest, typescriptAdditionalTypes)
    }
    project(":$module").tasks.withType<Exec>().configureEach {
        inputs.file(rootProject.file("tsconfig.strict.json"))
    }
}

// Explicit apply/check entry points cover every module, even from the root.
// Verification never rewrites source; each module's check also runs Spotless.
tasks.register("format") {
    group = "formatting"
    description = "Formats Java with Google Java Style and TypeScript with Prettier."
    dependsOn(typescriptFormat)
    dependsOn(subprojects.map { "${it.path}:spotlessApply" })
}
val formatCheck by tasks.registering {
    group = "verification"
    description = "Checks Java and TypeScript formatting without editing source."
    dependsOn(typescriptFormatCheck)
    dependsOn(subprojects.map { "${it.path}:spotlessCheck" })
}
tasks.named("check") { dependsOn(formatCheck, typescriptLint, typescriptPolicyCheck, typescriptPolicyTest, typescriptAdditionalTypes) }

// Installable artifacts are opt-in; ordinary check does not open Electron windows.
val clientDistributions by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundles CLI/MCP/TUI and the macOS Apple Silicon desktop."
    dependsOn(":plowshare-desktop:desktopBuild", ":plowshare-cli:cliBuild", ":plowshare-mcp:mcpBuild", ":plowshare-tui:pnpmTypecheck")
    workingDir = rootDir
    inputs.files("scripts/distributions.mjs", "scripts/distribution-archive.py", "scripts/distribution-talk.mjs", "scripts/distribution-native.mjs", "scripts/distribution-runtime.mjs", "scripts/distribution-runtime.json", "scripts/distribution-entitlements.plist", "docs/distributions.md")
    inputs.dir("plowshare-desktop/assets/icons").withPathSensitivity(PathSensitivity.RELATIVE)
    for (module in listOf("plowshare-cli", "plowshare-mcp", "plowshare-tui", "plowshare-desktop", "plowshare-client-ts", "plowshare-client-node")) {
        inputs.dir(project(":$module").file("src"))
        inputs.files(project(":$module").file("package.json"), project(":$module").file("pnpm-lock.yaml"))
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
    doFirst {
        if (!fullDb) throw GradleException("Database smoke tests require -PfullDb: ./gradlew dockerSmoke -PfullDb")
    }
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

val nativeSdkPolicyCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Guards public native SDK boundaries without native toolchains."
    workingDir = rootDir
    commandLine("python3", "scripts/check-native-sdk-policy.py")
}
tasks.named("check") { dependsOn(nativeSdkPolicyCheck) }

// Native SDK toolchains are an explicit verification entry point. They aren't
// dependencies of server/client check or the server image build.
val sdkContractCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks generated operation/DTO catalogs for all SDK languages (requires Go/gofmt)."
    dependsOn(":plowshare-client-ts:clientBuild")
    workingDir = rootDir
    commandLine("node", "scripts/generate-sdk-contracts.mjs", "--check")
    doLast {
        project.exec { commandLine("node", "scripts/sdk-dto-fixtures.mjs", "--check") }
    }
}
val sdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds native Python/.NET/Go SDKs; requires their documented toolchains."
    dependsOn(sdkContractCheck, nativeSdkPolicyCheck, ":plowshare-client-node:nodeBuild")
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

// Adapters are public SDK consumers, never alternate compositions of the server.
// Inspect resolved production graphs so a transitive dependency cannot hide a
// core dependency. Test-only server loaders are intentionally outside this gate.
val integrationBoundaryCheck by tasks.registering {
    group = "verification"
    description = "Enforces production SDK/integration isolation from core."
    doLast {
        val adapters = subprojects.filter {
            it.projectDir.toPath().startsWith(rootDir.resolve("integrations").toPath())
        }
        val sdkProjects = subprojects.filter {
            it.projectDir.toPath().startsWith(rootDir.resolve("sdk").toPath())
        }
        val server = project(":plowshare-server")
        val coreProjects = listOf(server, project(":plowshare-protocol"))
        val consumers = adapters + sdkProjects + coreProjects
        for (consumer in consumers) {
            val forbidden = if (consumer in coreProjects) adapters.map { it.path }.toSet()
                else setOf(server.path)
            for (scope in listOf("compileClasspath", "runtimeClasspath")) {
                val graph = consumer.configurations.findByName(scope) ?: continue
                val violations = graph.incoming.resolutionResult.allComponents.mapNotNull { component ->
                    when (val id = component.id) {
                        is org.gradle.api.artifacts.component.ProjectComponentIdentifier ->
                            id.projectPath.takeIf { it in forbidden }
                        is org.gradle.api.artifacts.component.ModuleComponentIdentifier ->
                            id.displayName.takeIf { id.group == "io.aeyer" &&
                                id.module in forbidden.map { it.removePrefix(":") } }
                        else -> null
                    }
                }
                // File dependencies or repackaged jars must not smuggle server
                // classes past project/module identity checks.
                val forbiddenPackages = if (consumer in coreProjects)
                    listOf("io/aeyer/plowshare/a2a/", "io/aeyer/plowshare/integrations/")
                    else listOf("io/aeyer/plowshare/server/")
                val fileViolations = if (violations.isNotEmpty()) emptyList<File>() else graph.files.filter { entry ->
                    when {
                        entry.isDirectory -> forbiddenPackages.any { entry.resolve(it).exists() }
                        entry.isFile && entry.extension == "jar" -> ZipFile(entry).use { jar ->
                            jar.entries().asSequence().any { item -> forbiddenPackages.any { prefix ->
                                item.name.startsWith(prefix) || item.name.startsWith("BOOT-INF/classes/$prefix")
                            } }
                        }
                        else -> false
                    }
                }
                if (violations.isNotEmpty() || fileViolations.isNotEmpty()) throw GradleException(
                    "${consumer.path} $scope violates the integration/core boundary: $violations $fileViolations. " +
                    "Use public SDK contracts; core extensions require a separately authorized platform task. " +
                    "See docs/decisions/0001-integration-boundary.md."
                )
            }
        }
    }
}
tasks.named("check") { dependsOn(integrationBoundaryCheck) }
