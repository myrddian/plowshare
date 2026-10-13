// SDK-only Node adapter. No server or JVM runtime dependency.
val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}
val mcpIntegrationBuild by tasks.registering(Exec::class) {
    group = "build"
    workingDir = projectDir
    dependsOn(pnpmInstall, ":plowshare-client-node:nodeBuild")
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/typescript/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/node/package.json"), rootProject.file("sdk/typescript/package.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("build/mcp")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}
val mcpIntegrationTest by tasks.registering(Exec::class) {
    group = "verification"
    workingDir = projectDir
    dependsOn(mcpIntegrationBuild)
    inputs.dir("build/mcp").withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "build/mcp/codec.test.js", "build/mcp/session.test.js", "build/mcp/provider.test.js", "build/mcp/journal.test.js", "build/mcp/runner.test.js")
}
tasks.named("assemble") { dependsOn(mcpIntegrationBuild) }
tasks.named("check") { dependsOn(mcpIntegrationTest) }

tasks.register<Exec>("mcpIntegrationConsumerCheck") {
    group = "verification"
    description = "Compiles and tests a fresh consumer of packed public SDKs, without publishing."
    workingDir = rootProject.projectDir
    dependsOn(mcpIntegrationBuild)
    commandLine("python3", "integrations/mcp/scripts/check-consumer.py")
}
