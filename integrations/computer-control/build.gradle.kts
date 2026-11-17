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

val computerControlDistributionTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests offline package bounds, verification, process cleanup and exclusive installation."
    workingDir = projectDir
    inputs.files("scripts/distribution.py", "scripts/build-distribution.py", "scripts/distribution-test.py")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    commandLine("python3", "scripts/distribution-test.py")
}
tasks.named("check") { dependsOn(computerControlDistributionTest) }

tasks.register<Exec>("computerControlDistribution") {
    group = "distribution"
    description = "Builds an offline package at an explicit unused destination; requires -PcomputerDistributionOutput and -PcomputerDistributionNode."
    workingDir = projectDir
    dependsOn(computerControlBuild)
    doFirst {
        val output = providers.gradleProperty("computerDistributionOutput").orNull
            ?: throw GradleException("Set computerDistributionOutput to an explicit unused absolute directory.")
        val node = providers.gradleProperty("computerDistributionNode").orNull
            ?: throw GradleException("Set computerDistributionNode to an explicit absolute Node executable.")
        commandLine("python3", "scripts/build-distribution.py", "--output", output, "--node", node)
    }
}

tasks.register<Exec>("computerControlConsumerCheck") {
    group = "verification"
    description = "Verifies offline provider/public SDK installation, runtime import, CLI and typed consumer."
    workingDir = rootProject.projectDir
    dependsOn(computerControlBuild)
    commandLine("python3", "integrations/computer-control/scripts/check-consumer.py")
}
