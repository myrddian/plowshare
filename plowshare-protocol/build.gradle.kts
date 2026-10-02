plugins {
    `java-library`
}

val jacksonVersion: String by project

// Jackson *annotations* only, and nothing else on the main classpath. Both the
// server and the client depend on this module, so anything reachable from here
// is reachable from a stdio client process that is supposed to hold no durable
// state: a protocol module that can open a JDBC connection is one that
// eventually will. Serialisation lives in the modules that do I/O.
dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}
