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
    inputs.dir(rootProject.file("plowshare-client-ts/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-client-ts/package.json"),
        rootProject.file("plowshare-client-ts/tsconfig.json"),
        rootProject.file("plowshare-client-ts/tsconfig.base.json"),
        rootProject.file("plowshare-client-ts/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val nodeBuild by tasks.registering(Exec::class) {
    group = "build"
    nodeSources()
    outputs.dir("build")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}

tasks.named("assemble") { dependsOn(nodeBuild) }
tasks.named("check") { dependsOn(nodeBuild) }
tasks.named("jar") { dependsOn(nodeBuild) }
