/*
 * The TUI module: pnpm and vitest, wired into Gradle.
 *
 * THE SECOND TYPESCRIPT MODULE, AND IT IS MODELLED ON THE FIRST ON PURPOSE.
 * plowshare-console/build.gradle.kts argues every decision below at length and
 * those arguments apply here unchanged; what follows is the same shape, with
 * only the places this module genuinely differs written out again:
 *
 *   - `pnpm-lock.yaml` is committed and `--frozen-lockfile` is used, so a
 *     drifted lockfile fails the build rather than silently resolving something
 *     new.
 *   - `package.json` names `packageManager: pnpm@10.34.5`, the same release the
 *     console pins. pnpm reads that field and fetches that version if the one
 *     on the PATH is a different release — measured here, where pnpm 11.24.0 is
 *     installed and the install reported "Done in 821ms using pnpm v10.34.5".
 *     That is pnpm's own behaviour and not something this build arranges; it is
 *     written down because it is a network call nothing in this file makes.
 *   - every task declares its inputs and its outputs, so `UP-TO-DATE` means
 *     what it says. The console's file records a `check` reporting BUILD
 *     SUCCESSFUL with an invariant violated, because its inputs did not include
 *     the files its assertion read. `declareSources()` below is that lesson:
 *     the per-directory tsconfigs are inputs — reached through `inputs.dir`
 *     over `src`, so a fourth project added later is covered on the day it
 *     appears — and they are the files the module edge is actually written in.
 *   - a developer without pnpm gets a sentence naming pnpm and how to install
 *     it, not a `Cannot run program "pnpm"` stack trace.
 *
 * WHAT IS DIFFERENT FROM THE CONSOLE, in one place so it is not hunted for:
 *
 *   - there is no `pnpmBuild` and no `assemble` hook, because nothing bundles
 *     this module. The console's `dist/` is copied into the server jar by
 *     `:plowshare-server:processResources`; this module has zero runtime
 *     dependencies, no Vite, and no consumer that wants an artefact. What the
 *     console gets from `vite build` — a type error failing the build rather
 *     than waiting for a linter — is bought here by `pnpmTypecheck` hanging off
 *     `check` instead.
 *   - the type check is `tsc -b` and NOT `tsc --noEmit`. `tsconfig.json` here
 *     is a solution file: `files: []` plus four references. `tsc --noEmit`
 *     against it compiles no program and exits 0 whatever the sources say,
 *     which is stale-green of exactly the kind the paragraph above is about.
 *     `tsc -b` walks the references and is the only invocation that checks
 *     anything. Measured at task 1: with a `node:fs` import planted in
 *     `src/logic/wiring.ts`, `tsc -b --force` failed with TS2307 naming the
 *     file, and `tsc --noEmit` reported nothing at all.
 *
 *     THAT MEASUREMENT HAS SINCE HALF-EXPIRED AND IS KEPT BECAUSE IT IS STILL
 *     THE POINT. Task 7 installed @types/node for `view/`, so the planted
 *     `node:fs` now RESOLVES and `tsc -b --force` exits 0 over it — the guard
 *     for that one rule is `src/neutrality.test.ts` alone, which is what that
 *     file was written for and says so at length. What has not changed is the
 *     half this bullet is about: `--noEmit` still compiles no program here, so
 *     it would report nothing about any of the rules the compiler DOES still
 *     enforce (a widened `lib`, a cross-directory import, a type error in any
 *     of the four projects). Re-measure before trusting either sentence.
 *
 * The `java` plugin arrives from the root `subprojects` block and this module
 * has no Java in it. That is deliberate rather than tolerated, for the reason
 * the console's file gives: it buys the lifecycle — `check`, `assemble`,
 * `build` and `clean` — under the same names every other module uses, so
 * `./gradlew check` reaches the vitest suite without the root build having to
 * learn that this module is different. The java tasks themselves are NO-SOURCE
 * and cost nothing.
 */

/**
 * The pnpm executable on this build's PATH, or null.
 *
 * Resolved from `PATH` here rather than left to `ProcessBuilder`, so that the
 * check below and the process that runs are looking at the same file. The
 * console's copy of this function records the measurements behind it, including
 * that this reads the environment of the *client* that invoked the build and
 * that installing pnpm therefore takes effect on the next invocation, at the
 * price of one daemon start.
 */
fun pnpmExecutable(): File? =
    (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "pnpm") }
        .firstOrNull { it.isFile && it.canExecute() }

/**
 * `npx --yes pnpm`, used when nothing on PATH is called `pnpm`.
 *
 * The fallback runs the pnpm the lockfile was written by rather than whatever
 * is newest, because `packageManager` decides that and not npx. It costs a
 * download on first use and therefore a network; a global pnpm is still the
 * better arrangement and the message below still names it.
 */
fun npxExecutable(): File? =
    (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "npx") }
        .firstOrNull { it.isFile && it.canExecute() }

/**
 * Fail with a remedy instead of with a stack trace.
 *
 * In `doFirst` rather than at configuration time on purpose: a developer who
 * only wants `:plowshare-server:test` should not be stopped by a tool that task
 * does not use.
 */
fun Exec.requirePnpm() {
    doFirst {
        val direct = pnpmExecutable()
        if (direct != null) {
            executable = direct.absolutePath
            return@doFirst
        }
        val npx = npxExecutable()
        if (npx != null) {
            executable = npx.absolutePath
            args = listOf("--yes", "pnpm") + (args ?: emptyList())
            return@doFirst
        }
        throw GradleException(
                """
                |Neither pnpm nor npx was found on this build's PATH, and the
                |plowshare-tui module cannot be built without one of them.
                |
                |  Install it:  npm install -g pnpm      (or: brew install pnpm)
                |  Then:        ./gradlew build
                |
                |plowshare-tui is the TERMINAL client and not plowshare-client, which is
                |the Java one and needs only a JDK. This module is TypeScript, like
                |plowshare-console; that module's build file says why the second ecosystem
                |was accepted into a build that used to need only a JDK.
                |
                |PATH as this build saw it:
                |  ${(System.getenv("PATH") ?: "<unset>").replace(File.pathSeparator, "\n  ")}
                """.trimMargin()
            )
    }
}

/**
 * `pnpm install --frozen-lockfile`.
 *
 * The output is `node_modules/.modules.yaml` and not the whole `node_modules`
 * tree. The console's file carries the measurement behind that choice — ~2,600
 * files and ~230 symlinks under `.pnpm`, walked on every up-to-date check —
 * along with what it gives up: deleting a package by hand while leaving
 * `.modules.yaml` alone leaves this task UP-TO-DATE over a tree that is now
 * wrong, and the remedy is `pnpm install` or deleting `node_modules`.
 */
val pnpmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the TUI's dependencies from the committed lockfile."

    workingDir = projectDir
    inputs.files("package.json", "pnpm-lock.yaml")
        .withPropertyName("manifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(layout.projectDirectory.file("node_modules/.modules.yaml"))
        .withPropertyName("installedModules")

    requirePnpm()
    // --frozen-lockfile: a package.json that has drifted from the lockfile is a
    // failure and not an invitation to resolve something new. It is pnpm's
    // default in CI and explicitly not its default anywhere else, so it is
    // written out.
    args("install", "--frozen-lockfile")
}

/**
 * Everything the type check and the vitest run both read.
 *
 * THE FOUR TSCONFIGS ARE INPUTS AND THAT IS THE WHOLE POINT OF THIS FUNCTION.
 * The module edge — `logic/` sees no Node types, no DOM lib and neither of the
 * other two directories — is written in `src/logic/tsconfig.json` and its two
 * siblings and nowhere else. A task that type-checked those rules without
 * declaring the files stating them would go UP-TO-DATE across an edit that
 * removed one, and report BUILD SUCCESSFUL over a module edge that no longer
 * exists. The `src` tree covers three of the four because they live beside the
 * sources; the root solution file does not, so it is named.
 *
 * A NOTE ON HOW THIS COMMENT IS SPELLED, because it cost an hour and the next
 * person to paste a glob into a build file will pay it again. KOTLIN BLOCK
 * COMMENTS NEST. An `src` followed by a slash and two stars, written inside
 * this comment as a glob, opens a nested comment; the closing marker below then
 * closes only that one, and EVERYTHING AFTER IT IS STILL COMMENT. The file
 * compiles, Gradle reports BUILD SUCCESSFUL, and the two tasks declared further
 * down simply do not exist — `./gradlew :plowshare-tui:check` was green and ran
 * neither the type check nor the suite, which is why it was found by
 * `:plowshare-tui:tasks --all` listing one task where three were written. Say
 * "the src tree" in prose here, or spell the glob in code where it is a string.
 */
fun Exec.declareSources() {
    inputs.file(rootProject.file("test-support/contracts/ws-retrieval-fixtures.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The public package's build must exist before resolving its runtime/types exports.
    dependsOn(":plowshare-client-ts:clientBuild", ":plowshare-client-node:nodeBuild")
    inputs.dir(rootProject.file("plowshare-client-ts/src"))
        .withPropertyName("sharedClientSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-client-ts/package.json"),
        rootProject.file("plowshare-client-ts/tsconfig.json"),
        rootProject.file("plowshare-client-ts/tsconfig.base.json"),
        rootProject.file("plowshare-client-ts/pnpm-lock.yaml"))
        .withPropertyName("sharedClientConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-client-node/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("plowshare-client-node/package.json"),
        rootProject.file("plowshare-client-node/tsconfig.json"),
        rootProject.file("plowshare-client-node/pnpm-lock.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src")
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("tsconfig.json", "tsconfig.base.json", "package.json")
        .withPropertyName("configuration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The lockfile stands in for node_modules, for the reason pnpmInstall's
    // note gives. It is the identity of the installed tree: a dependency that
    // changes changes this file.
    inputs.files("pnpm-lock.yaml")
        .withPropertyName("dependencies")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * The Java that `src/mirrors-the-server.test.ts` reads, declared as an input to
 * the task that runs it.
 *
 * <b>This is the header's own warning, applied to the one test in this module
 * that asserts over files outside it.</b> That test reads `Code.java`,
 * `Envelope.java` and `Outcome.java` from `plowshare-protocol`, and — since
 * task 5 — `AuthController.java`, `AuthProperties.java` and `AuthFilter.java`
 * from the server's auth package, so that a constant renamed on that side fails
 * the TypeScript suite the same day. Without these two lines that promise holds
 * only when something else already invalidated this task: rename
 * `TOKEN_DELIVERY_HEADER` and touch nothing under `plowshare-tui/`, and
 * `./gradlew check` answers UP-TO-DATE over a mirror that no longer mirrors.
 * That is the same shape as the `check` that was green while running neither
 * pnpm task, and as the Java invariant
 * `the_guard_can_see_the_files_it_asserts_over`.
 *
 * Only `pnpmTest` declares them. `tsc` does not read a line of Java, and an
 * input a task does not consume is a re-run nobody can explain.
 */
fun Exec.mirroredSources() {
    inputs.dir(rootProject.layout.projectDirectory.dir(
        "plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames"))
        .withPropertyName("mirroredFrames")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir(
        "plowshare-server/src/main/java/io/aeyer/plowshare/server/auth"))
        .withPropertyName("mirroredAuth")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * `tsc -b`, which is this module's `-Werror`.
 *
 * It is on `check` rather than on `assemble` because there is nothing here to
 * assemble; see the header. What it produces is the declaration files the three
 * projects hand each other, under `build/types/`, and those are declared as the
 * output so that `UP-TO-DATE` is answerable.
 *
 * `--force` IS PASSED, AND IT IS THE SAME ARGUMENT AS THE ONE ABOUT DECLARED
 * INPUTS. `tsc -b` keeps its own incremental state in .tsbuildinfo and decides
 * for itself whether to do anything; Gradle keeps a second, independent record
 * and decides the same question. Two staleness oracles over one task is one
 * more than can be right, and the way they disagree is not symmetrical: the
 * failure is `./gradlew :plowshare-tui:pnpmTypecheck --rerun-tasks` running tsc,
 * tsc reading a .tsbuildinfo that says there is nothing to do, and the build
 * going green having checked no file. That is a verification command reporting
 * success over work it did not do, which is the fault this whole file is
 * shaped around.
 *
 * So Gradle owns the question. When it decides this task must run, tsc does the
 * whole job — measured at about a second on this tree — and when it does not,
 * the task does not start at all.
 */
val pnpmTypecheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-checks the TUI's three projects and the edges between them."

    dependsOn(pnpmInstall)
    workingDir = projectDir
    declareSources()
    outputs.dir(layout.buildDirectory.dir("types")).withPropertyName("declarations")

    requirePnpm()
    args("run", "typecheck")
}

/**
 * `vitest run`, reporting JUnit XML so the task has a real output.
 *
 * A task with no declared output is a task Gradle re-runs every time, and a
 * test task that always re-runs is the one people start passing `-x` to. The
 * XML is a genuine artefact rather than a marker file invented to satisfy the
 * up-to-date check — it is the same format the Java suites produce, in this
 * module's own build directory.
 *
 * There is no vitest config file in this module and that is deliberate. The
 * console needs one for `environment: 'jsdom'`, because its code reads
 * `location` and calls `history.replaceState`. Vitest's default environment is
 * `node`, which is what this module wants, and its default `include` already
 * finds every `.test.ts` file under the src tree. A config file here would
 * restate two defaults and add a fourth file to type-check.
 */
val pnpmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the TUI's vitest suite."

    dependsOn(pnpmInstall)
    workingDir = projectDir
    declareSources()
    mirroredSources()
    outputs.dir(layout.buildDirectory.dir("vitest")).withPropertyName("testResults")

    requirePnpm()
    args("run", "test:ci")
}

// Both run in `check`, which is what makes `./gradlew check` and `./gradlew
// build` cover this module the same way they cover the Java ones. A suite
// nobody runs is the fault this repository has already paid for once — and a
// type check nobody runs would be the same fault in a second ecosystem.
tasks.named("check") {
    dependsOn(pnpmTypecheck)
    dependsOn(pnpmTest)
}

// There is deliberately no `tasks.named("clean")` hook. The console needs one
// because Vite writes `dist/` beside the sources, outside anything `clean`
// reaches by default. Everything this module generates is already under
// `build/` — the JUnit XML, the .d.ts files and the .tsbuildinfo — which the
// base plugin's `clean` deletes on its own. `node_modules/` is not in that set
// on purpose: it is a dependency cache, and deleting it on `clean` would make
// every clean build a network operation.
