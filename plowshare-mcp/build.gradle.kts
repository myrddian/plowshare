val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}

fun Exec.mcpSources() {
    workingDir = projectDir
    dependsOn(pnpmInstall, ":plowshare-client-ts:clientBuild", ":plowshare-client-node:nodeBuild")
    inputs.dir(rootProject.file("sdk/node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/node/package.json"),
        rootProject.file("sdk/node/tsconfig.json"),
        rootProject.file("sdk/node/pnpm-lock.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("test-support/contracts/mcp-compatibility.json"))
    inputs.file(rootProject.file("test-support/contracts/ws-retrieval-fixtures.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json", "tsconfig.test.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/typescript/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/typescript/package.json"),
        rootProject.file("sdk/typescript/tsconfig.json"),
        rootProject.file("sdk/typescript/tsconfig.base.json"),
        rootProject.file("sdk/typescript/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val mcpBuild by tasks.registering(Exec::class) {
    group = "build"
    mcpSources()
    outputs.dir("build")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}
val mcpTestTypes by tasks.registering(Exec::class) {
    group = "verification"
    mcpSources()
    dependsOn(mcpBuild)
    outputs.dir("build-tests")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.test.json")
}
val mcpTest by tasks.registering(Exec::class) {
    group = "verification"
    mcpSources()
    dependsOn(mcpTestTypes)
    commandLine("node", "--test", "build-tests/compatibility.test.js", "build-tests/evidence.test.js", "build-tests/socket.test.js")
}
tasks.named("assemble") { dependsOn(mcpBuild) }
tasks.named("check") { dependsOn(mcpTest, ":plowshare-server:nodeSourceTest") }
tasks.named("jar") { dependsOn(mcpBuild) }
