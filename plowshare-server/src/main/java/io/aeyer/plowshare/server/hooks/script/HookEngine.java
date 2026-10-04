package io.aeyer.plowshare.server.hooks.script;

import java.io.OutputStream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.io.IOAccess;

/**
 * One GraalJS engine for the server, and contexts built on it that can reach nothing.
 *
 * <p><b>Shared</b> because a context on a shared engine reuses its parsed code: measured 1–3 ms for
 * a fresh context against ~180 ms for the first engine (spec §7.1). <b>Lazy</b> because that first
 * engine belongs to the first hook fired, not to server boot.
 *
 * <p><b>Every access denied, one by one.</b> A server hook is a pure function of its event: no host
 * objects, no filesystem or network, no threads, no processes, no environment, no native code, no
 * other languages. Anything more is the sandbox's future business.
 */
public final class HookEngine implements AutoCloseable {

  private volatile Engine engine;

  public Context newContext() {
    return Context.newBuilder("js")
        .engine(engine())
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup(className -> false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        // Verified on 23.1.12: this option is still marked experimental,
        // so it is rejected with an IllegalArgumentException without this
        // flag. The fallback described for "option does not exist at all"
        // was not needed.
        .allowExperimentalOptions(true)
        .option("js.esm-eval-returns-exports", "true")
        // `load` reads and evaluates an arbitrary file as script — not
        // gated by allowIO at all, a separate hole. Stable on 23.1.12
        // (accepted without allowExperimentalOptions).
        .option("js.load", "false")
        // Strips the Graal-internal debugging/introspection builtins a
        // guest could otherwise reach. Stable on 23.1.12.
        .option("js.graal-builtin", "false")
        // The `Packages`/`java`/`javafx`/… globals that expose Java
        // packages by name even when host class lookup is denied.
        // Experimental on 23.1.12 (rejected without
        // allowExperimentalOptions, verified).
        .option("js.java-package-globals", "false")
        .build();
  }

  private Engine engine() {
    Engine made = engine;
    if (made == null) {
      synchronized (this) {
        made = engine;
        if (made == null) {
          made =
              Engine.newBuilder("js")
                  // The interpreter is the default and this warning would
                  // print on every boot of a server that did not add JVM
                  // flags on purpose.
                  .option("engine.WarnInterpreterOnly", "false")
                  // console.log/console.error/print are defined by default
                  // and, unconfigured, write to whatever System.out/err was
                  // when this engine was built — a hook's own output has no
                  // business on the server's console. Set once, here, on the
                  // shared engine: every context built on it inherits these
                  // streams unless a context overrides them, and none does.
                  .out(OutputStream.nullOutputStream())
                  .err(OutputStream.nullOutputStream())
                  .build();
          engine = made;
        }
      }
    }
    return made;
  }

  @Override
  public void close() {
    Engine made = engine;
    if (made != null) {
      made.close(true);
    }
  }
}
