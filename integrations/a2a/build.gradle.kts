plugins { `java-library`; application }
val okhttpVersion: String by project
dependencies {
    api(project(":plowshare-sdk"))
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")

}
application { mainClass.set("io.aeyer.plowshare.a2a.Main") }
distributions { main { contents { from("examples") { into("examples") } } } }
tasks.test {
    inputs.dir("examples").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("a2a.examples", file("examples").absolutePath)
}

// The legacy receiver remains a separate entry point until its public admission gap is resolved.
val relayStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "plowshare-a2a-relay"
    mainClass.set("io.aeyer.plowshare.a2a.relay.RelayMain")
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    outputDir = layout.buildDirectory.dir("relay-scripts").get().asFile
}
distributions { main { contents { from(relayStartScripts) { into("bin"); filePermissions { unix("rwxr-xr-x") } } } } }
