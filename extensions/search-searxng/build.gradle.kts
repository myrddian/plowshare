plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

val okhttpVersion: String by project
val jacksonVersion: String by project

dependencies {
    implementation(project(":plowshare-protocol"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    // OkHttp, on plowshare-server's own reasoning for RemoteSearchProvider and
    // SearchRegistrar: a per-call timeout through Call.timeout() rather than a
    // client rebuilt per request, and no second HTTP client shape to reason
    // about across this repository.
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // A real HTTP server on a loopback port, on plowshare-server's own
    // reasoning in RemoteSearchProviderTest: the thing under test is what
    // this module does with an HTTP request and response, and a loopback
    // server is the only double that is also a real socket.
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
