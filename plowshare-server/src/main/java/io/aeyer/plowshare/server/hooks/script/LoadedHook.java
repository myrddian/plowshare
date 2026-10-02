package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.Stage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * A hook module evaluated into its own context, with what it declared read off it.
 *
 * <p><b>JSON across the boundary, both ways.</b> The event goes in as a string
 * and is parsed inside the context; the decision comes out through
 * {@code JSON.stringify}. So a hook holds copies and never a host object, and the
 * decision is plain data {@link Decision} can check.
 *
 * <p><b>{@code handle} is called as a method of its stage object</b>, so a hook
 * that uses {@code this} inside its stage works as it would in Node.
 */
public final class LoadedHook implements PooledHook {

    // A hook is called synchronously and its result is read back the same tick —
    // nothing here ever awaits — so a handle that returns a thenable (an async
    // function, or one that returns a Promise itself) must fail loudly rather
    // than silently serialize to "{}" (a Promise has no enumerable properties of
    // its own, so JSON.stringify would otherwise produce that, indistinguishable
    // from a hook that meant to return an empty object).
    private static final String INVOKE =
            "(stage, json) => {"
            + " const result = stage.handle(JSON.parse(json));"
            + " if (result != null && typeof result.then === 'function') {"
            + "   throw new Error('a hook handle returned a Promise; hooks are called synchronously"
            + " and a promise is never awaited');"
            + " }"
            + " return JSON.stringify(result ?? null);"
            + "}";

    // Real import syntax only — a dynamic `import(`, a `from '...'` clause, or a
    // bare side-effecting `import '...'`. Deliberately not just the word
    // "import": a hook's own string or error message ("important", "import
    // failed") must not be mistaken for a module import.
    private static final Pattern IMPORT_SYNTAX = Pattern.compile(
            "\\bimport\\s*\\(|\\bimport\\b[^;\\n]*?\\bfrom\\b\\s*['\"]|\\bimport\\s*['\"]",
            Pattern.DOTALL);

    private final Context context;
    private final String name;
    private final Map<Stage, List<String>> stages;
    private final Map<Stage, Value> stageObjects;
    private final Value invoke;
    private volatile boolean alive = true;

    private LoadedHook(Context context, String name, Map<Stage, List<String>> stages,
            Map<Stage, Value> stageObjects, Value invoke) {
        this.context = context;
        this.name = name;
        this.stages = stages;
        this.stageObjects = stageObjects;
        this.invoke = invoke;
    }

    /**
     * Evaluates a hook module with no time limit. For a caller that already
     * trusts the source to finish — a test, a fixture; the server loads through
     * {@link #load(HookEngine, String, String, Duration, ScheduledExecutorService)}.
     */
    public static LoadedHook load(HookEngine engine, String fileName, String javascript)
            throws HookFailure {
        return evaluate(engine.newContext(), fileName, javascript);
    }

    /**
     * Evaluates a hook module, stopped at {@code limit}.
     *
     * <p><b>A module's top level is code too, and no call's limit covers it.</b>
     * {@code while (true) {}} outside {@code handle} runs during evaluation, and
     * GraalJS stops a runaway only by closing its context — so, exactly as
     * {@link ContextPool#call} does for a call, the timer closes the context at
     * the limit and one compare-and-set decides who won. A load the timer won
     * fails even if evaluation happened to return in the same instant: a context
     * mid-cancellation is not one a hook can be served from.
     *
     * @throws HookFailure {@code "<file> did not finish loading within <limit> ms"}
     *     when the limit was reached, otherwise whatever the evaluation itself
     *     failed with
     */
    public static LoadedHook load(HookEngine engine, String fileName, String javascript,
            Duration limit, ScheduledExecutorService timer) throws HookFailure {
        Context context = engine.newContext();
        AtomicBoolean settled = new AtomicBoolean();
        ScheduledFuture<?> stop;
        try {
            stop = timer.schedule(() -> {
                if (settled.compareAndSet(false, true)) {
                    closeQuietly(context);
                }
            }, Math.max(1, limit.toNanos()), TimeUnit.NANOSECONDS);
        } catch (RuntimeException unschedulable) {
            closeQuietly(context);
            throw new HookFailure(fileName + " could not be given a load time limit, so it was not"
                    + " loaded: " + unschedulable.getMessage(), unschedulable);
        }
        try {
            LoadedHook loaded = evaluate(context, fileName, javascript);
            if (!settled.compareAndSet(false, true)) {
                loaded.close();
                throw stoppedLoading(fileName, limit, null);
            }
            return loaded;
        } catch (HookFailure failed) {
            if (!settled.compareAndSet(false, true)) {
                throw stoppedLoading(fileName, limit, failed);
            }
            throw failed;
        } finally {
            stop.cancel(false);
        }
    }

    private static HookFailure stoppedLoading(String fileName, Duration limit, Throwable cause) {
        return new HookFailure(fileName + " did not finish loading within " + limit.toMillis()
                + " ms", cause);
    }

    private static void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (IllegalStateException alreadyClosing) {
            // closed or cancelled from another thread first; nothing left to close
        }
    }

    private static LoadedHook evaluate(Context context, String fileName, String javascript)
            throws HookFailure {
        try {
            Source source = Source.newBuilder("js", javascript, fileName.replaceAll("\\.ts$", "") + ".mjs")
                    .mimeType("application/javascript+module")
                    .build();
            Value exports = context.eval(source);
            Value hook = exports.getMember("default");
            if (hook == null || hook.isNull() || !hook.hasMembers()) {
                throw new HookFailure(fileName + " has no default export describing a hook");
            }
            Value named = hook.getMember("name");
            if (named == null || !named.isString() || named.asString().isBlank()) {
                throw new HookFailure(fileName + " declares no name; a hook's name is what the log records");
            }
            Value declared = hook.getMember("stages");
            // hasMembers() asks whether member access is supported at all, not
            // whether any member is actually present — an empty object answers
            // true — so `stages: {}` needs its own, separate check.
            if (declared == null || !declared.hasMembers() || declared.getMemberKeys().isEmpty()) {
                throw new HookFailure(fileName + " declares no stages");
            }
            Map<Stage, List<String>> stages = new EnumMap<>(Stage.class);
            Map<Stage, Value> objects = new EnumMap<>(Stage.class);
            for (String key : declared.getMemberKeys()) {
                Stage stage;
                try {
                    stage = Stage.of(key);
                } catch (IllegalArgumentException unknown) {
                    throw new HookFailure(fileName + ": " + unknown.getMessage());
                }
                Value object = declared.getMember(key);
                if (object == null || !object.hasMembers()) {
                    throw new HookFailure(fileName + ": its " + key + " stage is not an object");
                }
                Value handle = object.getMember("handle");
                if (handle == null || !handle.canExecute()) {
                    throw new HookFailure(fileName + ": its " + key + " stage has no handle function");
                }
                Value listedTools = object.getMember("tools");
                Value listedOrigins = object.getMember("origins");
                List<String> filter = List.of();
                if (stage.onALog()) {
                    // Spec 2026-09-28-hooks-reach-the-log decision 6: log stages use origins:,
                    // tool stages keep tools:, and the loader refuses the other one.
                    if (listedTools != null && !listedTools.isNull()) {
                        throw new HookFailure(fileName + ": its " + key + " stage names tools, and a"
                                + " log stage is filtered by origins");
                    }
                    filter = origins(fileName, key, listedOrigins);
                } else {
                    if (listedOrigins != null && !listedOrigins.isNull()) {
                        throw new HookFailure(fileName + ": its " + key + " stage names origins, and"
                                + " only a log stage is filtered by origin");
                    }
                    if (stage == Stage.TOOL_PRE || stage == Stage.TOOL_POST) {
                        if (listedTools == null || !listedTools.hasArrayElements()
                                || listedTools.getArraySize() == 0) {
                            throw new HookFailure(fileName + ": its " + key + " stage names no tools");
                        }
                        List<String> names = new ArrayList<>();
                        for (long i = 0; i < listedTools.getArraySize(); i++) {
                            Value tool = listedTools.getArrayElement(i);
                            if (!tool.isString()) {
                                throw new HookFailure(fileName + ": its " + key + " tools must be names");
                            }
                            names.add(tool.asString());
                        }
                        filter = List.copyOf(names);
                    }
                }
                stages.put(stage, filter);
                objects.put(stage, object);
            }
            Value invoke = context.eval("js", INVOKE);
            return new LoadedHook(context, named.asString(), Map.copyOf(stages), objects, invoke);
        } catch (HookFailure failed) {
            closeQuietly(context);
            throw failed;
        } catch (PolyglotException unloadable) {
            closeQuietly(context);
            String said = unloadable.getMessage();
            String lower = said == null ? "" : said.toLowerCase();
            // A value import resolves through the same file access an ordinary
            // read would use, and IOAccess.NONE denies it — measured on 23.1.12,
            // for both a relative and a bare specifier, as "Error: Operation is
            // not allowed for: <specifier>", which never contains the word
            // "import" itself. That wording alone is not enough to convict,
            // though: a hook that just throws its own Error can carry that same
            // substring by accident ("important", "import failed"), and neither
            // is an import. So both signals are required — the denial's wording,
            // AND the source actually containing import syntax — before this is
            // reported as "imports something" rather than a plain evaluation
            // failure.
            if (lower.contains("is not allowed") && IMPORT_SYNTAX.matcher(javascript).find()) {
                throw new HookFailure(fileName + " imports something, and a server hook cannot:"
                        + " only `import type`, which is erased, is possible — " + said, unloadable);
            }
            throw new HookFailure(fileName + " could not be evaluated: " + said, unloadable);
        } catch (IOException impossible) {
            closeQuietly(context);
            throw new HookFailure(fileName + " could not be read as a source", impossible);
        } catch (RuntimeException broken) {
            closeQuietly(context);
            throw new HookFailure(fileName + " could not be loaded: " + broken.getMessage(), broken);
        }
    }

    /**
     * A log stage's {@code origins:}: absent is every origin, and each named one must be an origin
     * this server writes (spec 2026-09-28-hooks-reach-the-log decision 6). An empty list or an
     * unknown name is a load failure, not a stage that silently never fires.
     */
    private static List<String> origins(String fileName, String key, Value listed)
            throws HookFailure {
        if (listed == null || listed.isNull()) {
            return List.of();
        }
        String known = Arrays.stream(Origin.values()).map(Origin::wireName)
                .collect(Collectors.joining(", "));
        if (!listed.hasArrayElements()) {
            throw new HookFailure(fileName + ": its " + key + " origins must be a list of names"
                    + " from " + known + ", not a single value");
        }
        if (listed.getArraySize() == 0) {
            throw new HookFailure(fileName + ": its " + key + " origins must name at least one of "
                    + known + "; leave origins out to fire for every origin");
        }
        List<String> names = new ArrayList<>();
        for (long i = 0; i < listed.getArraySize(); i++) {
            Value origin = listed.getArrayElement(i);
            if (!origin.isString()) {
                throw new HookFailure(fileName + ": its " + key + " origins must be names");
            }
            String name = origin.asString();
            if (Arrays.stream(Origin.values()).noneMatch(o -> o.wireName().equals(name))) {
                throw new HookFailure(fileName + ": its " + key + " stage names '" + name
                        + "', which is not an origin; the origins are " + known);
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    public String name() {
        return name;
    }

    public Map<Stage, List<String>> stages() {
        return stages;
    }

    @Override
    public String call(Stage stage, String eventJson) throws HookFailure {
        Value object = stageObjects.get(stage);
        if (object == null) {
            throw new HookFailure("the hook '" + name + "' has no " + stage.wireName() + " stage");
        }
        try {
            Value result = invoke.execute(object, eventJson);
            // INVOKE's `?? null` catches a handle that returns null or undefined,
            // but not one that returns a function or a Symbol — neither is
            // nullish, so JSON.stringify (correctly) produces the JS value
            // undefined instead of a string. Value.asString() on that is not an
            // error by Graal's own contract; it silently answers Java null, which
            // would otherwise reach the caller indistinguishably from a genuine
            // "null" JSON string (Decision.Nothing) rather than as the hook bug
            // it is.
            if (!result.isString()) {
                throw new HookFailure("the hook '" + name + "' returned something JSON cannot carry");
            }
            return result.asString();
        } catch (PolyglotException failed) {
            if (failed.isCancelled() || failed.isInterrupted()) {
                alive = false;
                throw new HookFailure("the hook '" + name + "' was stopped at its time limit", failed);
            }
            throw new HookFailure("the hook '" + name + "' threw: " + failed.getMessage(), failed);
        } catch (IllegalStateException closed) {
            alive = false;
            throw new HookFailure("the hook '" + name + "' was stopped at its time limit", closed);
        }
    }

    /**
     * Stops a running call by closing the context. Runs on a timer thread, where
     * an exception would be swallowed by the executor and seen by nobody, and it
     * can race the caller's own {@link #close} — so it tolerates a concurrent
     * close exactly as {@code close} tolerates a concurrent cancel.
     */
    @Override
    public void cancel() {
        alive = false;
        closeQuietly(context);
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void close() {
        alive = false;
        closeQuietly(context);
    }
}
