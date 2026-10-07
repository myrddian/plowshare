/*
 * The console module: pnpm and Vite, wired into Gradle.
 *
 * THIS IS THE SECOND ECOSYSTEM IN A BUILD THAT PREVIOUSLY NEEDED ONLY A JDK,
 * and that cost is accepted rather than hidden. Everything below exists to make
 * it cost as little as possible and to fail legibly when it cannot:
 *
 *   - `pnpm-lock.yaml` is committed and `--frozen-lockfile` is used, so a
 *     drifted lockfile fails the build rather than silently resolving something
 *     new into the jar.
 *   - `package.json` names `packageManager: pnpm@<version>`. pnpm reads that
 *     field and runs that version, fetching it if the one on the PATH is a
 *     different release. That is pnpm's own behaviour and not something this
 *     build arranges; it is written down here because it is a network call
 *     nothing in this file makes.
 *   - every task declares its inputs and its outputs, so `UP-TO-DATE` means
 *     what it says. Slice 3c measured a `check` task reporting BUILD SUCCESSFUL
 *     with an invariant violated, because its inputs did not include the files
 *     its assertion read.
 *   - a developer without pnpm gets a sentence naming pnpm and how to install
 *     it, not a `Cannot run program "pnpm"` stack trace.
 *
 * The `java` plugin arrives from the root `subprojects` block and this module
 * has no Java in it. That is deliberate rather than tolerated: what it buys is
 * the lifecycle — `check`, `assemble`, `build` and `clean` — under the same
 * names every other module uses, so `./gradlew check` reaches the Vitest suite
 * without the root build having to learn that this module is different. The
 * java tasks themselves are NO-SOURCE and cost nothing.
 */

/**
 * The pnpm executable on this build's PATH, or null.
 *
 * Resolved from `PATH` here rather than left to `ProcessBuilder`, so that the
 * check below and the process that runs are looking at the same file: a message
 * saying pnpm is missing, from a build that then finds one somewhere else,
 * would be worse than no message.
 *
 * MEASURED, because this build script runs inside a long-lived daemon and the
 * question of whose environment it sees is exactly the kind of thing this
 * repository has been wrong about. Run once with pnpm on the PATH and then as
 * `PATH=/usr/bin:/bin ./gradlew :plowshare-console:pnpmBuild --rerun-tasks`:
 * the first built, the second failed with the message below and printed exactly
 * those two directories. So this reads the PATH of the *client* that invoked
 * the build.
 *
 * What it does NOT read is a daemon's frozen environment, and the observed
 * reason is worth writing down because it costs a few seconds: Gradle does not
 * hand a changed environment to an existing daemon, it starts another one --
 * `1 incompatible Daemon could not be reused` is in the output of the second
 * run above. Installing pnpm therefore takes effect on the next invocation,
 * with no `./gradlew --stop` needed, at the price of one daemon start.
 */
fun pnpmExecutable(): File? =
    (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "pnpm") }
        .firstOrNull { it.isFile && it.canExecute() }

/**
 * Fail with a remedy instead of with a stack trace.
 *
 * In `doFirst` rather than at configuration time on purpose: a developer who
 * only wants `:plowshare-server:test` should not be stopped by a tool that
 * task does not use.
 */
/**
 * `npx --yes pnpm`, used when nothing on PATH is called `pnpm`.
 *
 * Measured on this machine, which has node and npm from Homebrew and neither
 * pnpm nor corepack: `npx --yes pnpm --version` answers, and `npx --yes pnpm
 * install --frozen-lockfile` against the committed lockfile reports <b>"Done in
 * 237ms using pnpm v10.34.5"</b> — the version `package.json` pins, not the one
 * npx first fetched. So the fallback runs the same pnpm the lockfile was written
 * by, rather than whatever is newest.
 *
 * It costs a download on first use and therefore a network. A global pnpm is
 * still the better arrangement and the message below still names it; this exists
 * so that a checkout on a machine with node is not stopped by a tool it can
 * fetch for itself.
 */
fun npxExecutable(): File? =
    (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "npx") }
        .firstOrNull { it.isFile && it.canExecute() }

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
                |plowshare-console module cannot be built without one of them.
                |
                |  Install it:  npm install -g pnpm      (or: brew install pnpm)
                |  Then:        ./gradlew build
                |
                |This build needed only a JDK until the console module arrived; it now also
                |needs pnpm and a populated node_modules. plowshare-console/build.gradle.kts
                |says why that cost was accepted.
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
 * tree, and the reason is a measurement rather than a preference. That tree is
 * ~2 600 files and ~230 symlinks under `.pnpm`, and pnpm's layout points
 * symlinks at sibling directories inside it; declaring the directory makes
 * every up-to-date check on this module walk all of it, twice over if a second
 * task declares it as an input. `.modules.yaml` is the file pnpm writes to
 * record what it installed and against which lockfile, so it changes exactly
 * when an install has done something.
 *
 * WHAT THAT GIVES UP, said rather than left to be discovered: deleting a
 * package out of `node_modules` by hand while leaving `.modules.yaml` alone
 * leaves this task UP-TO-DATE over a tree that is now wrong. The remedy is
 * `pnpm install` or deleting `node_modules`, and the alternative — a
 * multi-thousand-file snapshot on every build — was judged the worse trade for
 * a failure mode nobody reaches by accident.
 */
val pnpmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the console's dependencies from the committed lockfile."

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

/** Everything the TypeScript build and the Vitest run both read. */
fun Exec.declareSources() {
    // Vite copies these assets and the style checks read the shared palette.
    inputs.dir(rootProject.file("client-assets"))
        .withPropertyName("appearanceAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("sdk/typescript/src/operations")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src")
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("index.html", "vite.config.ts", "tsconfig.json", "package.json")
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
 * `tsc --noEmit && vite build` into `dist/`.
 *
 * The type check is part of the build and not a separate task, so a type error
 * fails `assemble` rather than waiting for somebody to run a linter. It is the
 * nearest thing this module has to the `-Werror` the Java modules compile
 * under.
 */
val pnpmBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Type-checks the console and builds it into dist/."

    // Imported TUI logic resolves the shared client through the TUI dependency links.
    dependsOn(pnpmInstall, ":plowshare-client-ts:clientBuild", ":plowshare-tui:pnpmInstall")
    inputs.dir(rootProject.file("sdk/typescript/build/operations"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-tui/src/logic"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = projectDir
    declareSources()
    outputs.dir(layout.projectDirectory.dir("dist")).withPropertyName("bundle")

    requirePnpm()
    args("run", "build")
}

/**
 * `vitest run`, reporting JUnit XML so the task has a real output.
 *
 * A task with no declared output is a task Gradle re-runs every time, and a
 * test task that always re-runs is the one people start passing `-x` to. The
 * XML is a genuine artefact rather than a marker file invented to satisfy the
 * up-to-date check — it is the same format the Java suites produce, in this
 * module's own build directory.
 */
val pnpmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the console's Vitest suite."

    // Imported TUI logic resolves the shared client through the TUI dependency links.
    dependsOn(pnpmInstall, ":plowshare-client-ts:clientBuild", ":plowshare-tui:pnpmInstall")
    inputs.dir(rootProject.file("sdk/typescript/build/operations"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("plowshare-tui/src/logic"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = projectDir
    declareSources()
    outputs.dir(layout.buildDirectory.dir("vitest")).withPropertyName("testResults")

    requirePnpm()
    args("run", "test:ci")
}

// The console's tests run in `check`, which is what makes `./gradlew check` and
// `./gradlew build` cover this module the same way they cover the Java ones. A
// suite nobody runs is the fault this repository has already paid for once.
tasks.named("check") { dependsOn(pnpmTest) }

// And `assemble` builds the bundle, so `./gradlew build` produces the thing the
// server jar embeds even when nothing asked for the jar.
tasks.named("assemble") { dependsOn(pnpmBuild) }

// `clean` reaches dist/, which lives beside the sources rather than under
// build/ because that is where Vite's own conventions put it and where anyone
// running `pnpm build` by hand will look for it. Without this line the two ways
// of cleaning this module disagree.
tasks.named<Delete>("clean") { delete(layout.projectDirectory.dir("dist")) }
