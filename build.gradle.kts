plugins {
    java
}

allprojects {
    group = "io.aeyer"
    version = "0.1.0-SNAPSHOT"

    // A reused Gradle daemon can see the caller's updated PATH while native
    // process lookup still uses the PATH it started with. Resolve JS tools
    // ourselves so installing Node or switching versions takes effect without
    // restarting Gradle. Check only when a task actually needs the tool.
    tasks.withType<Exec>().configureEach {
        doFirst {
            val tool = executable ?: return@doFirst
            if (tool in listOf("node", "pnpm", "npx")) {
                val path = providers.environmentVariable("PATH").orElse("").get()
                val resolved = path.split(File.pathSeparator)
                    .map { directory ->
                        val entry = File(directory.ifEmpty { "." })
                        File(if (entry.isAbsolute) entry else File(workingDir, entry.path), tool)
                    }
                    .firstOrNull { it.isFile && it.canExecute() }
                if (resolved == null) {
                    val remedy = if (tool == "node")
                        "Install Node.js 22.12+ and put its bin directory on PATH."
                    else
                        "Install pnpm with npm install -g pnpm and put it on PATH."
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
val clientManifestCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses stale WS, MCP and client capability inventory."
    workingDir = rootDir
    commandLine("python3", "test-support/contracts/build_manifest.py", "--check")
}
val legacyCliAuditCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuses missing or stale legacy CLI migration mappings."
    workingDir = rootDir
    commandLine("python3", "test-support/contracts/audit_legacy_cli.py", "--check")
}
tasks.named("check") { dependsOn(serverLaunchTest, clientManifestCheck, legacyCliAuditCheck) }

subprojects {
    apply(plugin = "java")

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

    // A dangling {@link} is a compile-clean lie about another class, and until
    // now nothing in this build caught one. Measured, on this tree: a
    // deliberately broken {@link Foo#noSuchMethod} and a broken {@link
    // NoSuchClass} both compile BUILD SUCCESSFUL under -Xlint:all -Werror,
    // because reference checking belongs to doclint and lives in the javadoc
    // tool rather than in javac. So {@code Foo.bar} and {@link Foo#bar} were
    // equally unchecked, and this slice paid for it: a dangling {@link
    // LocalProvider} sat in FileTools from task 4 until task 9's sweep found it,
    // and ScribeTest named an EndToEndTest method that had been renamed away.
    //
    // Wiring it in means running the task, and the task was red: 12 doclint
    // heading-order errors across FileAccess, Archive, ProposalStore and
    // PromotionQueue, in four other tasks' files. `Xdoclint:reference` is why
    // none of them had to be touched — it turns on the group that catches the
    // real defect and leaves the accessibility and missing-tag groups off. The
    // tree is green under it today with no source edits at all, so the guard
    // arrives switched on rather than as twelve edits somebody has to make
    // first.
    //
    // What it does NOT cover, measured rather than assumed: test sources, which
    // the javadoc task does not read — a dangling reference planted in
    // ProjectControllerTest left `check` green — and Markdown, which no doclint
    // reads. Half the places this repository restates a fact are therefore
    // still unguarded, and that is the honest size of this guard.
    tasks.named("check") { dependsOn(tasks.named("javadoc")) }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:reference", "-quiet")
    }

    // WHICH JVM THIS BUILD RUNS ON, because three slices of prose named the
    // wrong one. `JavaLanguageVersion.of(21)` resolves, on this machine, to
    // JetBrains Runtime 21.0.8+9-b1038.68 at ~/.sdkman/candidates/java/21.0.8-jbr
    // -- measured from the Test task's own javaLauncher, and visible as
    // `java.base@21.0.8` frames in every stack trace this build produces. The
    // Eclipse Temurin 21.0.11 that slice 1's plan named, and that later comments
    // copied, is still in ~/.gradle/jdks/ and `javaToolchains` still lists it;
    // Gradle simply prefers the SDKMAN installation that arrived after that plan
    // was written. Both are language level 21 and both were re-probed when this
    // was found: they agree on every fact this repository depends on -- no
    // SecureDirectoryStream, non-null fileKey on APFS, no attribute accessor on
    // FileChannelImpl. The version is recorded because an artefact whose frames
    // disagree with its prose costs a reviewer an hour, not because it changes
    // an answer.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }

        // Testcontainers bundles a docker-java that asks for Docker API 1.32
        // unless told otherwise. Docker Desktop 29 rejects anything below 1.40,
        // and docker-java reports that rejection as "Could not find a valid
        // Docker environment" — which reads as "Docker isn't running" and sends
        // you off restarting a daemon that is fine. 1.44 is Docker 25 and up.
        // `api.version` is a system property and not an env var: docker-java
        // reads DOCKER_HOST and friends from the environment but this one only
        // from properties, and the Gradle daemon would swallow an exported var
        // anyway, since it captures its environment when the daemon starts.
        systemProperty("api.version", "1.44")

        // Ryuk, the container Testcontainers uses to reap the others, bind-mounts
        // the Docker socket by whatever path the client connected on. On this
        // machine ~/.testcontainers.properties still names Docker Desktop's old
        // ~/Library/Containers/…/docker.raw.sock, which connects fine but cannot
        // be mounted ("error while creating mount source path … operation not
        // supported"). /var/run/docker.sock is the path Docker Desktop shares.
        // An operator on rootless Docker or Colima sets the var themselves; we
        // only fill in the default.
        //
        // THIS LINE IS WHY RYUK WORKS, and slice 3c measured what happens
        // without it, because three slices of briefs asserted the opposite —
        // "Ryuk is absent on this host", propagated from plan to plan without
        // once being run. It is false, and has been false since 2e8ab21, the
        // commit that introduced this block on the repository's first day.
        //
        // Measured on this host (macOS 26.6.2, Docker Desktop), with a probe
        // that starts one PostgreSQLContainer under exactly the configuration
        // below and is then SIGKILLed, which is the killed-Gradle-worker case
        // the leak claim was about:
        //
        //   - with the override: "Ryuk started - will monitor and terminate
        //     Testcontainers containers on JVM exit", and the Postgres and Ryuk
        //     itself both gone afterwards -- 9.4s on Adoptium 21.0.11, 10.8s on
        //     the JetBrains Runtime 21.0.8 the build actually uses (see below).
        //     Nothing leaked.
        //   - without it: Ryuk cannot start at all — the socket it is told to
        //     bind-mount is the un-mountable raw.sock — and Testcontainers
        //     fails the run outright with ContainerLaunchException rather than
        //     carrying on unreaped. So the pre-override world did not leak
        //     either; it simply did not run.
        //
        // The other half of that claim was a count. `test` starts THIRTEEN
        // Postgres containers, not three — every class with a static
        // `@Container`. They are not concurrent: there is no maxParallelForks
        // and no forkEvery here, all thirteen classes live in one module, so
        // one worker JVM runs them in sequence. Measured from `docker events`
        // over a full `check`: 13 containers created, 30.7s of total alive
        // time, one Ryuk session, and a maximum of ONE Postgres alive at any
        // instant. A killed worker can orphan one container, and Ryuk takes
        // that one.
        //
        // What a shared singleton would buy is therefore speed and not safety,
        // and the size of it is ~0.9s of container lifecycle per class (a warm
        // start-to-destroy measured alone at 0.82s) — about 11s of a 54s
        // `check`. It was declined; see slice 3c task 5's annotation for the
        // isolation those thirteen fixtures would have to give up to collect
        // it.
        if (System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") == null) {
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        }
    }
}
// Installable artifacts are opt-in; ordinary check does not open Electron windows.
val clientDistributions by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundles CLI/MCP/TUI and the macOS Apple Silicon desktop."
    dependsOn(":plowshare-desktop:desktopBuild", ":plowshare-cli:cliBuild", ":plowshare-mcp:mcpBuild", ":plowshare-tui:pnpmTypecheck")
    workingDir = rootDir
    inputs.files("scripts/distributions.mjs", "scripts/distribution-archive.py", "scripts/distribution-talk.mjs", "scripts/distribution-native.mjs", "scripts/distribution-runtime.mjs", "scripts/distribution-runtime.json", "scripts/distribution-entitlements.plist", "docs/distributions.md")
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
