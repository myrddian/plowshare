plugins { `java-library`; application }
val graalPolyglotVersion: String by project
dependencies {
    api(project(":plowshare-sdk"))
    implementation("org.graalvm.polyglot:polyglot:$graalPolyglotVersion")
    runtimeOnly("org.graalvm.polyglot:js-community:$graalPolyglotVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:${project.property("okhttpVersion")}")
}
application { mainClass.set("io.aeyer.plowshare.integrations.Main") }
tasks.test { systemProperty("integration.worker.classpath", sourceSets.main.get().runtimeClasspath.asPath) }
