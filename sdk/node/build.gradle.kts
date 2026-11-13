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
    inputs.file(rootProject.file("scripts/build-javascript-sdk.mjs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
    commandLine("node", rootProject.file("scripts/build-javascript-sdk.mjs"), "node")
}

tasks.named("assemble") { dependsOn(nodeBuild) }
val nodeManagementTest by tasks.registering(Exec::class) {
    group = "verification"
    nodeSources()
    dependsOn(nodeBuild)
    commandLine("node", "--experimental-strip-types", "--test", "src/provider-management.test.ts", "src/credentials.test.ts", "src/workspace-watch.test.ts")
}
tasks.named("check") { dependsOn(nodeBuild, nodeManagementTest) }
val nodeBinaryTransferTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks explicit binary HTTP helpers, receipt integrity and single-attempt delivery."
    nodeSources()
    dependsOn(nodeBuild)
    commandLine("node", "--experimental-strip-types", "--test", "src/image-admissions.test.ts", "src/relay-blobs.test.ts", "src/tool-content.test.ts")
}
tasks.named("check") { dependsOn(nodeBinaryTransferTest) }
tasks.named("jar") { dependsOn(nodeBuild) }
