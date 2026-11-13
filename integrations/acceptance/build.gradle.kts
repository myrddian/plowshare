plugins { java }

val testcontainersVersion: String by project
val okhttpVersion: String by project

dependencies {
    // This project ships no runtime. Only acceptance fixtures compose the two applications.
    testImplementation(project(":plowshare-a2a"))
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    testImplementation(project(":plowshare-server")) { isTransitive = false }
    testImplementation(files(rootProject.project(":plowshare-server").configurations.named("runtimeClasspath")))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.5")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
}
