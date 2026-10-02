val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}

fun Exec.cliSources() {
    workingDir = projectDir
    dependsOn(pnpmInstall, ":plowshare-client-ts:clientBuild", ":plowshare-client-node:nodeBuild")
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("test-support/contracts/ws-retrieval-fixtures.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json", "tsconfig.test.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-client-ts/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-client-node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-client-node/package.json"),
        rootProject.file("plowshare-client-node/tsconfig.json"),
        rootProject.file("plowshare-client-node/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-client-ts/package.json"),
        rootProject.file("plowshare-client-ts/tsconfig.json"),
        rootProject.file("plowshare-client-ts/tsconfig.base.json"),
        rootProject.file("plowshare-client-ts/pnpm-lock.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val cliBuild by tasks.registering(Exec::class) {
    group = "build"
    cliSources()
    for (name in listOf("main", "run", "platform", "options", "login")) {
        outputs.files("build/$name.js", "build/$name.d.ts")
    }
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}
val cliTestTypes by tasks.registering(Exec::class) {
    group = "verification"
    cliSources()
    dependsOn(cliBuild)
    outputs.dir("build-tests")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.test.json")
}
val cliTest by tasks.registering(Exec::class) {
    group = "verification"
    cliSources()
    dependsOn(cliTestTypes)
    commandLine("node", "--test", "build-tests/options.test.js", "build-tests/socket.test.js", "build-tests/presence.test.js", "build-tests/login.test.js")
}
tasks.named("assemble") { dependsOn(cliBuild) }
tasks.named("check") { dependsOn(cliTest) }
