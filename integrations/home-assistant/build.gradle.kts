plugins { `java-library`; application }
val okhttpVersion: String by project
dependencies {
    api(project(":plowshare-integrations"))
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    // Validate bundled Application examples with current loaders; never ship server code.
    testImplementation(project(":plowshare-server")) { isTransitive = false }
    testImplementation(files(rootProject.project(":plowshare-server").configurations.named("runtimeClasspath")))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.5")
    testImplementation("org.testcontainers:junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:postgresql:${project.property("testcontainersVersion")}")
}
application { mainClass.set("io.aeyer.plowshare.integrations.Main") }
distributions { main { contents { from("examples") { into("examples") } } } }
tasks.test {
    inputs.dir("examples").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("integration.worker.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("integration.examples", file("examples").absolutePath)
}
