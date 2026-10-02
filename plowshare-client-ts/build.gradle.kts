// Separate installs keep the headless client independent of Electron and Ink.
val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}

fun Exec.clientSources() {
    workingDir = projectDir
    dependsOn(pnpmInstall)
    inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json", "tsconfig.base.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val clientBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the neutral TypeScript client and checks its tests' types."
    clientSources()
    outputs.dir("build/binding")
    outputs.dir("build/binding-tests")
    outputs.dir("build/package-tests")
    outputs.dir("build/jobs")
    outputs.dir("build/jobs-tests")
    outputs.dir("build/operations")
    outputs.dir("build/operations-tests")
    commandLine("node", "node_modules/typescript/bin/tsc", "-b", "--force")
}

val clientTest by tasks.registering(Exec::class) {
    group = "verification"
    clientSources()
    dependsOn(clientBuild)
    // Contract tests read these external authorities; a changed manifest/DTO
    // must invalidate the test task even when the TS sources are unchanged.
    inputs.file(rootProject.file("test-support/contracts/client-capabilities.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("test-support/contracts/ws-retrieval-fixtures.json"), rootProject.file("test-support/contracts/ws-information-fixtures.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/api"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(listOf("archive/Archive", "archive/PromotionQueue", "archive/TocEntry", "agents/digests/Navigator")
        .map { rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/$it.java") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(listOf("Home", "Memory", "Provenance", "Invalidation", "WriteResult", "search/Hit", "search/SearchPage", "fetch/FetchWindow")
        .map { rootProject.file("plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/$it.java") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/ws"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/api/BoardController.java"),
        rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/api/SearchController.java"),
        rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/api/JobView.java"),
        rootProject.file("plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Pace.java"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("build/vitest")
    commandLine("node", "node_modules/vitest/vitest.mjs", "run", "--reporter=default",
        "--reporter=junit", "--outputFile.junit=build/vitest/junit.xml")
}

tasks.named("assemble") { dependsOn(clientBuild) }
tasks.named("check") { dependsOn(clientTest) }
