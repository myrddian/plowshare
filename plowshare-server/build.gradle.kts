plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

val pgvectorJdbcVersion: String by project
val okhttpVersion: String by project
val jacksonVersion: String by project
val flywayVersion: String by project
val testcontainersVersion: String by project
val pdfboxVersion: String by project
val jsoupVersion: String by project
val jgitVersion: String by project
val graalPolyglotVersion: String by project
val swc4jVersion: String by project

dependencies {
    testImplementation(project(":plowshare-a2a"))
    implementation(project(":plowshare-protocol"))

    // Syntax-only code navigation on the server's Java 21 runtime. These JNI
    // artifacts bundle macOS/Linux arm64 and x86_64 libraries (also Windows).
    // Grammar versions are independent of the binding version; setLanguage
    // verifies their ABI. No compiler, project build or language server runs.
    implementation("io.github.bonede:tree-sitter:0.26.6")
    implementation("io.github.bonede:tree-sitter-java:0.23.5")
    implementation("io.github.bonede:tree-sitter-javascript:0.25.0")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
    implementation("io.github.bonede:tree-sitter-tsx:0.23.2")
    implementation("io.github.bonede:tree-sitter-python:0.25.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    // The file channel, and the server half only. A closed socket is an *event*,
    // which is the whole reason this is not polling: every file request
    // outstanding on a session fails the moment the session goes, rather than
    // waiting out a deadline that exists for a different failure. The client
    // half of the same socket is okhttp's, because plowshare-client is
    // deliberately Spring-free and okhttp ships a WebSocket client — see that
    // module's build file, which has no Spring in it and must not gain any.
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // starter-jdbc, not starter-data-jpa: the archive is a handful of hand-written
    // statements over one table, and the vector column is a type JPA has no
    // mapping for anyway.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    // Flyway 10 moved Postgres support out of core; without this the migration
    // fails at startup with "Unsupported Database: PostgreSQL 16", not with a
    // missing-dependency error.
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("org.postgresql:postgresql")
    implementation("com.pgvector:pgvector:$pgvectorJdbcVersion")

    // The password hash for the one admin this server now has. Argon2id and
    // not bcrypt: bcrypt is only SLOW, tunable in one dimension, and GPU and
    // ASIC hardware has caught up to that dimension for years. Argon2id is
    // MEMORY-HARD as well as slow, so the same attacker also has to buy the RAM
    // a real login pays, and that is what a password hash chosen today is
    // expected to cost.
    //
    // NOT spring-security-crypto's Argon2PasswordEncoder, which was the first
    // candidate precisely because this is already a Spring Boot server and it
    // would have added no new groupId at all. It pulls in BouncyCastle for the
    // primitive, and BouncyCastle is refused here on operator experience: a
    // provider that registers itself into the JCE and changes what
    // `Cipher.getInstance` and friends resolve to elsewhere in a process is a
    // correctness hazard disproportionate to what one password hash needs.
    // spring-security-crypto's BCryptPasswordEncoder carries no such cost and
    // remains the fallback if argon2-jvm ever causes trouble on a platform this
    // server ships to.
    //
    // THE COST THIS DOES CARRY: argon2-jvm is a JNA binding, and the artifact
    // below is not the Java API at all — it is a bag of precompiled
    // `libargon2` builds, one per platform, that the API jar (pulled in
    // transitively as `argon2-jvm-nolibs`) loads through JNA at runtime. So the
    // dependency this server ships is not "portable bytecode plus a native
    // call"; it is native code for every platform this artifact happened to be
    // built for, and a platform this server actually runs on has to be verified
    // as being in that bag rather than assumed.
    //
    // MEASURED on this tree, by unzipping the resolved `argon2-jvm-2.11.jar`
    // (Gradle module metadata substitutes it for the plain POM's declared
    // dependency on `argon2-jvm-nolibs` alone, so the jar that actually lands
    // on the classpath is the natives-only one): 19 entries, 8 platform
    // directories, one `.so`/`.dylib`/`.dll` each. Both platforms this project
    // runs on are present — `darwin-aarch64/libargon2.dylib` (76 798 bytes) and
    // `linux-x86-64/libargon2.so` (194 040 bytes) — alongside linux-aarch64,
    // linux-arm, linux-x86, win32-x86-64 and win32-x86. Had either been
    // missing, this comment would say so instead of the version number below.
    implementation("de.mkammerer:argon2-jvm:2.11")

    // OkHttp and not Spring's RestClient: Anchor's LMStudioClient is OkHttp, the
    // two projects point at one LM Studio, and a second HTTP shape for the same
    // endpoint would be a second set of timeout and retry behaviours to reason
    // about when it misbehaves.
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    // Server-sent events, for streaming chat. Anchor's LMStudioClient uses the
    // same module against the same endpoint; hand-rolling an SSE frame parser
    // for a wire format that already has one here would be a second thing to
    // get wrong about `data: [DONE]`.
    implementation("com.squareup.okhttp3:okhttp-sse:$okhttpVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // The one library here that reads somebody's file rather than talking to a
    // service. Anchor's corpus is PDFs of academic papers and this pipeline
    // takes multipart bytes on a route the client's converter cannot reach, so
    // the extractor has to be on this side too.
    //
    // MEASURED on this tree, from a resolved `:plowshare-server:runtimeClasspath`
    // rather than copied from the client's figure, because the two graphs
    // differ: 55 modules / 25.59 MiB before, 58 / 29.17 MiB after. THREE modules
    // -- pdfbox, fontbox, pdfbox-io -- and 3,759,710 bytes, 3.59 MiB. The same
    // three jars the client resolved, which is the one part of its measurement
    // that did carry over. The alternative, tika-parsers-standard-package,
    // brings EIGHTY.
    //
    // THE EXCLUSION IS NOT A SIZE DECISION, and on this side it is not the
    // client's argument either. commons-logging is 74 KiB; what it is, is a
    // SECOND JAR PROVIDING org.apache.commons.logging. spring-jcl is already on
    // this classpath -- spring-core depends on it -- and it provides that exact
    // package as a bridge onto slf4j, which is logback, which is this server's
    // logging configuration. Two jars owning one package is resolved by class
    // path order, which no file in this repository states; on the day the real
    // jar wins, PDFBox's warnings (a font it cannot map, a stream a byte short,
    // one per occurrence, on reads that SUCCEED) stop being subject to any log
    // level or appender this server sets, because commons-logging DISCOVERS its
    // own backend and would land them in java.util.logging instead.
    //
    // So the real jar goes and spring-jcl is left as the only provider. Note
    // what is NOT here: jcl-over-slf4j, which the client adds. Adding it would
    // recreate the same two-providers-one-package problem it was excluded to
    // avoid, because spring-jcl already is that bridge.
    //
    // MEASURED, and it caught a wrong assertion: the TEST class path does have
    // both, because testImplementation(project(":plowshare-client")) below
    // carries that module's runtimeOnly jcl-over-slf4j across, and it is what
    // answers LogFactory there. Both route to slf4j, so the guarded property
    // holds either way -- but it is why PdfWarningsGoThroughLogbackTest asks
    // whether the resolution routes to slf4j rather than which jar answered.
    // That class asserts the resolution rather than trusting this comment.
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion") {
        exclude(group = "commons-logging", module = "commons-logging")
    }

    // The one HTML parser in this repository — gradle.properties argues why it
    // exists at all (regex over markup is the canonical wrong answer, and the
    // JDK ships nothing that parses real-world tag soup). This is the module
    // that fetches a page and reduces it to prose, so this is where the parser
    // has to live.
    //
    // The cost, stated rather than left implicit: a third-party parser now sits
    // on the path that ingests untrusted markup fetched from the open web,
    // which PDFBox above does not — that library reads a file an operator chose
    // to hand this server, not bytes a remote page served on request.
    implementation("org.jsoup:jsoup:$jsoupVersion")

    // The union project's hub: a bare git repo per project and the server's
    // working copy of it, plus the smart-HTTP servlet a client's `git push`
    // and `git fetch` talk to. Pure Java, so the server needs no git binary.
    // Spec 2026-09-14-a-project-can-be-a-union.
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.http.server:$jgitVersion")
    // Hooks: a project's TypeScript, run in host-less GraalJS contexts. The
    // interpreter is the default; see implementation rationale §7.1
    // for the measurement and for why the compiler is a deployment option.
    implementation("org.graalvm.polyglot:polyglot:$graalPolyglotVersion")
    implementation("org.graalvm.polyglot:js-community:$graalPolyglotVersion")
    // swc4j strips a hook's types with the same SWC engine Node's own type
    // stripping wraps, so a hook strips identically on the server and in the TUI.
    // Native per platform, used at load time only.
    implementation("com.caoccao.javet:swc4j:$swc4jVersion")
    runtimeOnly("com.caoccao.javet:swc4j-macos-arm64:$swc4jVersion")
    runtimeOnly("com.caoccao.javet:swc4j-macos-x86_64:$swc4jVersion")
    runtimeOnly("com.caoccao.javet:swc4j-linux-x86_64:$swc4jVersion")
    runtimeOnly("com.caoccao.javet:swc4j-linux-arm64:$swc4jVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // A real HTTP server on a loopback port: the transport is the one part of
    // this slice that needs an endpoint, and it must never be the reference
    // inference box. Its address is deliberately not written here — the rule is
    // checked by grepping the tracked tree for that literal, and a comment
    // stating the rule that then trips the check is the same fault one level up.
    // Note that mockwebserver 4.x pulls JUnit 4 in transitively for its
    // ExternalResource rule; that is expected and harmless — the tests here are
    // JUnit 5 and use it as a plain Closeable.
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // The end-to-end test drives the real MCP client against a real server, so
    // it needs both halves on one classpath. Test-only, and in this direction
    // only: nothing in this module's src/main can reach the client, so the
    // compiler still enforces that the server knows nothing about MCP — and the
    // client still never sees Spring Boot or a JDBC driver.
    testImplementation(project(":plowshare-client"))
}

// InvariantsTest asserts over the whole repository, and until this block existed
// Gradle had no idea that was its subject. Measured, with a violating comment
// sitting in plowshare-client/src/main/.../Schemas.java and nothing else changed:
//
//     > Task :plowshare-server:test UP-TO-DATE
//     BUILD SUCCESSFUL in 1s
//
// InvariantsTest did not appear in the output at all. The guard re-ran only by
// classpath accident — when a violation happened to change bytecode — and that
// accident does not happen for three of the four invariants in their most likely
// shape. `org.springframework` cannot reach plowshare-client/src/main as a real
// import at all, because the module has no Spring on its compile classpath, so
// the ONLY possible violation there is a comment. The reference box's address
// arrives in a build file, a shell script, a .gitignore or a README outside
// docs/, none of which is on any test's classpath. And a pasted API key most
// plausibly lands in a comment or a resource, which is the paste tripwire the
// invariant exists to be. Only the OkHttpClient import reliably moves bytecode.
//
// So the tree is declared as the guard's input instead of being inferred from
// the compile classpath.
//
// WHY ITS OWN TASK, rather than inputs.files on `test`. Measured: `test` starts
// thirteen Testcontainers Postgres instances and the full server suite takes
// ~50s.
// The edits this guard must react to — a comment, a plan, a resource — are the
// cheapest and most frequent edits in this repository, and javadoc-heavy ones at
// that. Hanging the whole suite off them trades a silent guard for a suite that
// effectively never caches. This task forks a JVM for five assertions and costs
// about a second, which is what makes including docs/ below affordable.
val invariants = tasks.register<Test>("invariants") {
    group = "verification"
    description = "Runs the repository containment invariants (InvariantsTest)."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Named rather than pattern-matched, so that renaming the class away fails
    // this task with "no tests found" instead of passing over nothing — which is
    // the exact failure mode the class itself was written about.
    filter { includeTestsMatching("io.aeyer.plowshare.server.InvariantsTest") }

    // Everything, minus what is named below. That is the right default for a
    // guard: an undeclared input is a blind spot by construction, and an
    // allow-list would mean a new top-level file joins the repository and is
    // silently not watched.
    //
    // docs/ remains part of the tracked-tree input used by scope checks.
    // Sensitive-marker positive controls use temporary files instead of requiring
    // real deployment identities or key prefixes in repository documentation.
    //
    // The exclusions, and why each is safe. .git/ is history, not the working
    // tree the scans read. build/ and .gradle/ are build outputs — and this
    // task's own results are written under build/, so including them would make
    // it permanently out of date. .idea/ and .DS_Store are untracked (they are
    // in .gitignore) and every scan here reads tracked files only.
    //
    // The one residual blind spot, stated rather than papered over: the inputs
    // are the working tree, while the scan is the git index. `git add` of an
    // already-present untracked file changes what the scan sees without changing
    // any file, so it does not re-run this task. The .git/index is deliberately
    // not wired in as an input; it churns on ordinary read-only git commands.
    // TypeScript test emit directories are ignored generated outputs, like build/.
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            exclude(
                ".git/**", "**/build/**", "**/build-tests/**", "**/.gradle/**", "**/.idea/**", "**/.DS_Store",
                // The console's installed dependencies and its bundle, added
                // with the console module. Both are in .gitignore, so neither
                // can ever be tracked — and every scan in InvariantsTest is fed
                // by `git ls-files`. That is the whole argument for why these
                // two exclusions cannot make the guard stale, and it is the
                // argument the .idea/ exclusion above already rests on.
                //
                // Why they have to go: node_modules is a few thousand files
                // built out of symlinks into .pnpm, which this fileTree would
                // walk on every up-to-date check of a task that costs about a
                // second to run. dist/ is Vite's output and lives beside the
                // sources rather than under build/, so the pattern above does
                // not already cover it.
                "**/node_modules/**", "plowshare-console/dist/**",
                // CLI/MCP TypeScript test outputs are ignored generated files, just like build/.
                // Reading them here also creates undeclared dependencies on their typecheck tasks.
                "plowshare-cli/build-tests/**", "plowshare-mcp/build-tests/**"
            )
        }
    )
        .withPropertyName("repositoryTree")
        // RELATIVE, not NAME_ONLY: every scope in that class is a path pattern,
        // so the same file under plowshare-client/src/main and under
        // plowshare-server/src/main are different answers. Not ABSOLUTE, which
        // would defeat the build cache across checkouts and buy nothing.
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Reachable from `check`, because a guard nobody runs is the fault being fixed
// here one level up. Run it alone with `./gradlew :plowshare-server:invariants`.
tasks.named("check") { dependsOn(invariants) }

// And not run twice. Without this the class executes in both tasks, and every
// XML tally of the suite counts its five assertions and its class twice.
tasks.named<Test>("test") {
    filter { excludeTestsMatching("io.aeyer.plowshare.server.InvariantsTest") }
    // ScriptDocumentationTest executes the copyable example outside the source set.
    inputs.file(rootProject.file("docs/examples/scripted-orchestrations/catalogue_inventory.js"))
    useJUnitPlatform { excludeTags("node-source") }
}

// Cross-runtime file-channel acceptance belongs to the TS adapter check. Keep
// the ordinary Java server test task usable without a Node installation.
val nodeSourceTest by tasks.registering(Test::class) {
    group = "verification"
    dependsOn(tasks.named("testClasses"), ":plowshare-client-node:nodeBuild")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("node-source") }
    include("**/FileChannelTest.class")
    systemProperty("plowshare.node.project", rootProject.file("plowshare-client-node").absolutePath)
    inputs.dir(rootProject.file("plowshare-client-node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-client-ts/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(listOf("plowshare-client-node", "plowshare-client-ts").flatMap { project ->
        listOf("package.json", "tsconfig.json", "pnpm-lock.yaml").map { rootProject.file("$project/$it") }
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    shouldRunAfter(tasks.named("test"))
}

// ModelSurfaceTest's regeneration switch, forwarded into the test JVM.
//
// A `-Dplowshare.surface.write=true` on the command line reaches the Gradle
// daemon's JVM and stops there; the test worker is a separate process with its
// own properties, and Gradle passes none of the daemon's down. Measured on this
// tree rather than assumed: without this line the property is null inside the
// test and the files are never rewritten, whatever the command line said.
//
// An environment variable was the other option and it WOULD have worked; the
// choice is a preference and not a constraint, which is worth saying because the
// first version of this comment claimed otherwise. Measured, against a daemon
// that was already running: `PLOWSHARE_X=true ./gradlew ...` is visible both to
// the build script and inside the test worker — Gradle carries the client's
// environment across to the daemon, and the worker is forked from it. What
// decided it was the shape already in this build: `api.version` above is a
// system property forwarded the same way, and one convention for "configuration
// this build hands to a test JVM" is worth more than the choice between the two.
//
// Declared unconditionally, with "false" when the property is absent, so that
// the value is part of the test task's input fingerprint. That is what makes a
// regeneration actually run: `test` is cacheable and `org.gradle.caching=true`,
// so a switch the build did not know about would be served from the cache and
// write nothing.
tasks.withType<Test>().configureEach {
    systemProperty(
        "plowshare.surface.write",
        System.getProperty("plowshare.surface.write") ?: "false"
    )
}

// The console's bundle, into the jar under static/.
//
// `from(a task)` and not `from("../plowshare-console/dist")`: a task in a copy
// spec resolves to that task's declared outputs AND carries the dependency on
// it, so `processResources` builds the console before copying it and re-runs
// when the bundle changes. A hard-coded path would do neither, and the symptom
// of that is a jar carrying yesterday's console with nothing in the build
// output to say so.
//
// `into("static")` because Spring Boot serves classpath:/static as the document
// root, so `dist/index.html` becomes `GET /` on the server's own origin. That
// is the whole of what makes the page and the WebSocket same-origin, which is
// what lets `EventChannelConfig` keep omitting `setAllowedOrigins` — see the
// dev proxy in plowshare-console/vite.config.ts for the development half of the
// same argument, and for what happens to anyone who reaches for
// setAllowedOrigins("*") instead.
//
// Everything outside /v1 is unauthenticated by design: these bytes are the same
// for every install and `AuthFilter` gates /v1. The credential is minted by
// POST /v1/auth, which this page calls with the token from its own URL.
tasks.named<ProcessResources>("processResources") {
    from(project(":plowshare-console").tasks.named("pnpmBuild")) {
        into("static")
    }
}
