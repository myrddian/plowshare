// Reusable public-SDK provider. Native helpers and browser binaries are explicit
// operator prerequisites, never implicit downloads during ordinary checks.
val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("node_modules/.modules.yaml")
    commandLine("pnpm", "install", "--frozen-lockfile")
}
val computerControlBuild by tasks.registering(Exec::class) {
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
    outputs.dir("build/computer")
    commandLine("node", "node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
}
val computerControlTest by tasks.registering(Exec::class) {
    group = "verification"
    workingDir = projectDir
    dependsOn(computerControlBuild)
    inputs.dir("build/computer").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("scripts/test.mjs")
    commandLine("node", "scripts/test.mjs")
}
tasks.named("assemble") { dependsOn(computerControlBuild) }
tasks.named("check") { dependsOn(computerControlTest) }

tasks.register<Exec>("computerControlConsumerCheck") {
    group = "verification"
    description = "Compiles the reusable provider against packed public SDKs in a fresh consumer."
    workingDir = rootProject.projectDir
    dependsOn(computerControlBuild)
    commandLine("python3", "integrations/computer-control/scripts/check-consumer.py")
}
