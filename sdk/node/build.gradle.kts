val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}

fun Exec.nodeSources() {
    workingDir = projectDir
    dependsOn(pnpmInstall, ":plowshare-client-ts:clientBuild")
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "tsconfig.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/typescript/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/typescript/package.json"),
        rootProject.file("sdk/typescript/tsconfig.json"),
        rootProject.file("sdk/typescript/tsconfig.base.json"),
        rootProject.file("sdk/typescript/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val nodeBuild by tasks.registering(Exec::class) {
    group = "build"
    nodeSources()
    outputs.dir("build")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}

tasks.named("assemble") { dependsOn(nodeBuild) }
val nodeManagementTest by tasks.registering(Exec::class) {
    group = "verification"
    nodeSources()
    dependsOn(nodeBuild)
    commandLine("node", "--experimental-strip-types", "--test", "src/provider-management.test.ts", "src/credentials.test.ts", "src/workspace-watch.test.ts")
}
tasks.named("check") { dependsOn(nodeBuild, nodeManagementTest) }
tasks.named("jar") { dependsOn(nodeBuild) }
