// No JVM collector is built or shipped. Java is only a test harness for Application contracts.
plugins { `java-library` }
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":plowshare-server")) { isTransitive = false }
    testImplementation(files(rootProject.project(":plowshare-server").configurations.named("runtimeClasspath")))
}
tasks.test {
    inputs.dir("network-privacy-watch").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("examples/server-tool-bindings.json").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("privacy.application", file("network-privacy-watch").absolutePath)
}

tasks.register<Exec>("pythonCheck") {
    group = "verification"
    description = "Checks the Python collector, HTTP interface and real SDK WebSocket fixture (no database)."
    workingDir = rootProject.projectDir
    commandLine(
        providers.environmentVariable("PLOWSHARE_PRIVACY_PYTHON").getOrElse("python3"),
        "integrations/network-privacy/scripts/check.py"
    )
}
