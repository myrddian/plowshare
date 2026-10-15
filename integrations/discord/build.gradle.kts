// Standalone public-SDK consumer; no server/protocol production classpath.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Real manifest/definition loaders validate the receiving template only; never shipped.
    testImplementation(project(":plowshare-server")) { isTransitive = false }
    testImplementation(files(rootProject.project(":plowshare-server").configurations.named("runtimeClasspath")))
}
tasks.test {
    inputs.dir("application").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("config.template.json")
    systemProperty("integration.directory", projectDir.absolutePath)
}
val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}
val discordBuild by tasks.registering(Exec::class) {
    group = "build"
    workingDir = projectDir
    dependsOn(pnpmInstall, ":plowshare-client-node:nodeBuild")
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("package.json", "pnpm-lock.yaml", "tsconfig.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/typescript/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("sdk/node/package.json"), rootProject.file("sdk/typescript/package.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("build/discord")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}
val discordTest by tasks.registering(Exec::class) {
    group = "verification"
    workingDir = projectDir
    dependsOn(discordBuild)
    inputs.dir("build/discord").withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("node", "--test", "build/discord/adapter.test.js", "build/discord/config.test.js", "build/discord/state.test.js", "build/discord/gateway.test.js", "build/discord/discord.test.js", "build/discord/setup.test.js", "build/discord/setup-gateway.test.js")
}
tasks.named("assemble") { dependsOn(discordBuild) }
tasks.named("check") { dependsOn(discordTest) }
tasks.register<Exec>("discordConsumerCheck") {
    group = "verification"
    description = "Compiles and tests an isolated consumer of packed public SDKs."
    workingDir = rootProject.projectDir
    dependsOn(discordBuild)
    commandLine("python3", "integrations/discord/scripts/check-consumer.py")
}
