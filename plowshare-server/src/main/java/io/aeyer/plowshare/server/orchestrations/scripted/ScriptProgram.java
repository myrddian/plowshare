package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.hooks.script.ScriptLocation;
import io.aeyer.plowshare.server.llm.LlmJson;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Pure, bounded ESM evaluation. Only JSON crosses the sandbox, including the model JSON utility.
 */
public final class ScriptProgram {
  public static final String MARKER = "// plowshare-script v1";
  private static final String SOURCE_NAME = "orchestration.mjs";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HookEngine ENGINE = new HookEngine();

  private ScriptProgram() {}

  /** The pinned journal identity covers the complete UTF-8 source. */
  public static String sourceHash(String source) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public static boolean isScript(String source) {
    return source != null && source.stripLeading().startsWith(MARKER);
  }

  public static JsonNode manifest(String source) {
    return evaluate(source, null);
  }

  public static JsonNode step(String source, JsonNode input) {
    return evaluate(source, input);
  }

  /** Keep the real script leaf when retained provenance has one, otherwise use its definition. */
  public static String fileName(String definition, String origin) {
    if (origin != null) {
      String leaf =
          origin.substring(Math.max(origin.lastIndexOf('/'), origin.lastIndexOf('\\')) + 1);
      if (leaf.matches("[A-Za-z0-9_.-]+\\.(?:m?js|ts)")) return leaf;
    }
    return definition + ".js";
  }

  /** The sandbox position travels with the failure to the run that owns the execution context. */
  public static final class Failure extends IllegalStateException {
    private final transient Optional<ScriptLocation> location;

    private Failure(PolyglotException cause, Source module) {
      super(
          "orchestration script failed: "
              + (cause.isSyntaxError() ? "JavaScript syntax error" : cause.getMessage()),
          cause);
      location = ScriptLocation.in(cause, module);
    }

    public Optional<ScriptLocation> location() {
      return location;
    }
  }

  private static JsonNode evaluate(String source, JsonNode input) {
    if (source.length() > 524288)
      throw new IllegalStateException("orchestration script exceeds 512 KiB");
    var script =
        Source.newBuilder("js", source, SOURCE_NAME)
            .mimeType("application/javascript+module")
            .buildLiteral();
    try (Context context = ENGINE.newContext();
        var timer = Executors.newSingleThreadScheduledExecutor()) {
      var deadline = timer.schedule(() -> context.close(true), 2, TimeUnit.SECONDS);
      try {
        context.eval(
            "js",
            "globalThis.Date=undefined; Math.random=()=>{throw new Error(\"random is unavailable; use input.requestId\")};");
        installJsonUtility(context);
        var module = context.eval(script);
        if (!module.hasMember("manifest")
            || !module.hasMember("step")
            || !module.getMember("step").canExecute())
          throw new IllegalStateException("script must export manifest and step(input)");
        context.getBindings("js").putMember("__module", module);
        // Pass JSON as a primitive string, rather than compiling a new source containing
        // the complete journal state on every step. Only the fixed adapter is executable.
        var result =
            input == null
                ? context.eval("js", "JSON.stringify(__module.manifest)")
                : context
                    .eval("js", "encoded => JSON.stringify(__module.step(JSON.parse(encoded)))")
                    .execute(input.toString());
        if (!result.isString()) throw new IllegalStateException("script returned no JSON value");
        String encoded = result.asString();
        if (encoded.length() > 8388608)
          throw new IllegalStateException("script result exceeds 8 MiB");
        JsonNode decoded = JSON.readTree(encoded);
        if (!decoded.isObject()) throw new IllegalStateException("script result must be an object");
        return decoded;
      } finally {
        deadline.cancel(false);
      }
    } catch (PolyglotException failed) {
      throw new Failure(failed, script);
    } catch (Exception failed) {
      throw new IllegalStateException(
          "orchestration script failed: " + failed.getMessage(), failed);
    }
  }

  private static void installJsonUtility(Context context) {
    // A single bounded pure function, not Java object/class access. Return a primitive
    // JSON string so the guest receives its own plain objects, never Jackson host nodes.
    context
        .getBindings("js")
        .putMember(
            "__llmJsonParser",
            (ProxyExecutable)
                args -> {
                  if (args.length != 1 || !args[0].isString())
                    throw new IllegalArgumentException("llmJson.parse requires one string");
                  return LlmJson.parse(args[0].asString()).json().toString();
                });
    context.eval(
        "js",
        """
                (recover => {
                  Object.defineProperty(globalThis, 'llmJson', {
                    value: Object.freeze({parse: raw => {
                      if (typeof raw !== 'string') throw new TypeError('llmJson.parse requires a string');
                      return JSON.parse(recover(raw));
                    }})
                  });
                  delete globalThis.__llmJsonParser;
                })(__llmJsonParser);
                """);
  }
}
