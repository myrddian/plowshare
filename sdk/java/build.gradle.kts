plugins { `java-library`; `maven-publish` }
val okhttpVersion: String by project
val jacksonVersion: String by project
dependencies {
    api(project(":plowshare-protocol"))
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
java { withSourcesJar(); withJavadocJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }

// Shared schema fixtures are test authority, including changes outside this source set.
tasks.test {
    inputs.files(rootProject.file("test-support/contracts/tool-schemas.json"),
        rootProject.file("test-support/contracts/relay-tools.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Runtime classpaths let the cross-language fixture spawn a real JVM. Installed
// consumers compile the same public-only driver against packaged jars instead.
val authFixtureClasspath by tasks.registering {
    dependsOn(tasks.testClasses)
    inputs.files(sourceSets.test.get().runtimeClasspath).withPathSensitivity(PathSensitivity.RELATIVE)
    val output = layout.buildDirectory.file("auth-fixture-classpath.txt")
    val dependencies = layout.buildDirectory.file("auth-dependencies-classpath.txt")
    outputs.files(output, dependencies)
    doLast {
        output.get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath)
        dependencies.get().asFile.writeText(configurations.runtimeClasspath.get().filter {
            !it.toPath().startsWith(rootProject.projectDir.toPath())
        }.joinToString(System.getProperty("path.separator")))
    }
}
