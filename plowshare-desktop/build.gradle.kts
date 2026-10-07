// The native smoke test opens a window and is opt-in; normal check stays headless.
val pnpmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs desktop development dependencies from the lockfile."
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", ".npmrc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    // Gradle has no TTY for pnpm's prompt when an incompatible node_modules
    // layout needs rebuilding (for example, after moving the checkout).
    commandLine("pnpm", "install", "--frozen-lockfile", "--config.confirmModulesPurge=false")
}

fun Exec.desktopSources() {
    workingDir = projectDir
    inputs.file(rootProject.file("plowshare-console/src/screens/usage-panel.ts"))
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // All neutral request and response readers come from the shared package.
    dependsOn(":plowshare-client-ts:clientBuild", ":plowshare-client-node:nodeBuild")
    inputs.dir(rootProject.file("sdk/node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("sdk/node/package.json"))
    inputs.dir(rootProject.file("sdk/typescript/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/typescript/package.json"),
        rootProject.file("sdk/typescript/tsconfig.json"),
        rootProject.file("sdk/typescript/tsconfig.base.json"),
        rootProject.file("sdk/typescript/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    dependsOn(pnpmInstall)
}

val desktopTypecheck by tasks.registering(Exec::class) {
    group = "verification"
    desktopSources()
    commandLine("node", "node_modules/typescript/bin/tsc", "--noEmit")
}
val desktopTest by tasks.registering(Exec::class) {
    group = "verification"
    desktopSources()
    commandLine("node", "--experimental-strip-types", "--test", "src/filestores.test.ts", "src/application-files.test.ts", "src/personal.test.ts", "src/profile.test.ts", "src/client.test.ts", "src/job-store.test.ts", "src/operator.test.ts", "src/caps.test.ts", "src/activity.test.ts", "src/library.test.ts", "src/manual.test.ts", "src/sync.test.ts", "src/schedules.test.ts", "src/runs.test.ts", "src/workspace.test.ts", "src/project-config.test.ts", "src/connection-config.test.ts", "src/connections.test.ts", "src/files.test.ts", "src/board.test.ts", "src/renderer/markdown.test.ts")
}
val desktopBuild by tasks.registering(Exec::class) {
    group = "build"
    desktopSources()
    inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("assets/icons").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files("build/main.cjs", "build/preload.cjs")
    outputs.dir("build/renderer")
    outputs.dir("build/icons")
    dependsOn(desktopTypecheck)
    commandLine("node", "scripts/build.mjs")
}
tasks.named("check") { dependsOn(desktopTypecheck, desktopTest) }
tasks.named("assemble") { dependsOn(desktopBuild) }
