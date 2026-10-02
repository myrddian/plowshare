plugins {
    `java-library`
}

val okhttpVersion: String by project
val jacksonVersion: String by project
val logbackVersion: String by project
val pdfboxVersion: String by project

// The client is an ephemeral stdio process: it speaks JSON-RPC on stdin/stdout
// and HTTP to the server, and that is the whole of its I/O. No JDBC driver, no
// embedding library — anything durable or inferential belongs to the server.
//
// PDFBox is the one library here that is not about talking to something. It is
// here because THE FILES ARE HERE: the adaptor in front of file_read converts a
// format on the side that holds it, so the library has to be on the side that
// holds it too. It reads bytes off a local disk and returns text; nothing about
// it is durable and nothing about it is inferential, so the rule above is
// unchanged rather than excepted. `files.Conversions` carries the argument.
dependencies {
    api(project(":plowshare-protocol"))

    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:2.0.16")

    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion") {
        // THIS EXCLUSION IS ABOUT STDOUT, and it is the reason the dependency is
        // safe to have in an MCP server at all.
        //
        // PDFBox logs through commons-logging, and it is chatty: a font it
        // cannot map or a stream that ends a byte early is a WARN per
        // occurrence. Commons-logging has no configuration of its own — it
        // DISCOVERS a backend at runtime, in a fixed order, and whatever it
        // finds decides where those lines land. Measured on this JDK with the
        // real jar present: it picks java.util.logging, whose default
        // ConsoleHandler writes to System.err. That is the right answer by
        // accident, arrived at through a discovery order that no file in this
        // repository controls and that a fourth jar on the class path can
        // change — and on the day it picks something else, the failure is a
        // non-JSON-RPC line on the protocol channel, which desynchronises the
        // harness for the whole session and never looks like a logging problem.
        //
        // So the real jar is excluded and jcl-over-slf4j is put in its place.
        // The bridge implements the same package and hands every one of those
        // lines to slf4j, which is logback, which is logback.xml, which is
        // stderr — the one path LoggingConfigTest already holds down.
        // ConvertedTextStaysOffStdoutTest asserts the substitution actually
        // happened rather than trusting this comment.
        exclude(group = "commons-logging", module = "commons-logging")
    }
    // runtimeOnly, and the version is slf4j-api's above: nothing in this module
    // compiles against commons-logging, and a bridge on the compile path would
    // be an invitation to.
    runtimeOnly("org.slf4j:jcl-over-slf4j:2.0.16")

    // A binding, and not just the API, because slf4j with no provider drops
    // every log line — a client that failed to reach the server would then fail
    // silently, and stdout is not available to say so. It is `runtimeOnly` so
    // that nothing in this module can compile against logback directly, and it
    // is safe only in company with src/main/resources/logback.xml: logback's
    // default appender targets System.out, which is the protocol channel.
    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    // On the test compile path too, so LoggingConfigTest can assert what the
    // appenders actually resolved to rather than re-reading the XML.
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
    // Same reason, one layer down: ConvertedTextStaysOffStdoutTest asks
    // commons-logging what it resolved to, which means naming its types.
    testImplementation("org.slf4j:jcl-over-slf4j:2.0.16")
}

// The version StdioTransport reports in `initialize`, which is what a harness
// shows when someone asks which build of the server they are talking to.
// Without this attribute Package.getImplementationVersion() is null even from a
// packaged jar, and every deployment introduces itself as "0.0.0-dev".
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
