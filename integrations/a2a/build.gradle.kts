plugins { `java-library`; application }
val okhttpVersion: String by project
dependencies {
    api(project(":plowshare-sdk"))
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    // Validate the bundled Application with the current manifest loader, without shipping core.
    testImplementation(project(":plowshare-server")) { isTransitive = false }
    testImplementation(files(rootProject.project(":plowshare-server").configurations.named("runtimeClasspath")))
}
application { mainClass.set("io.aeyer.plowshare.a2a.Main") }
distributions { main { contents { from("examples") { into("examples") } } } }
tasks.test {
    inputs.dir("examples").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("a2a.examples", file("examples").absolutePath)
}
