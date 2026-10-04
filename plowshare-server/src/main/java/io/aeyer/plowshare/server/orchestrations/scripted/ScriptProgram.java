package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.llm.LlmJson;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Pure, bounded ESM evaluation. Only JSON crosses the sandbox, including the model JSON utility.
 */
public final class ScriptProgram {
  public static final String MARKER = "// plowshare-script v1";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HookEngine ENGINE = new HookEngine();

  private ScriptProgram() {}

  public static boolean isScript(String source) {
    return source.stripLeading().startsWith(MARKER);
  }

  public static JsonNode manifest(String source) {
    return evaluate(source, null);
  }

  public static JsonNode step(String source, JsonNode input) {
    return evaluate(source, input);
  }

  private static JsonNode evaluate(String source, JsonNode input) {
    if (source.length() > 524288)
      throw new IllegalStateException("orchestration script exceeds 512 KiB");
    try (Context context = ENGINE.newContext();
        var timer = Executors.newSingleThreadScheduledExecutor()) {
      var deadline = timer.schedule(() -> context.close(true), 2, TimeUnit.SECONDS);
      try {
        context.eval(
            "js",
            "globalThis.Date=undefined; Math.random=()=>{throw new Error(\"random is unavailable; use input.requestId\")};");
        installJsonUtility(context);
        var module =
            context.eval(
                Source.newBuilder("js", source, "orchestration.mjs")
                    .mimeType("application/javascript+module")
                    .buildLiteral());
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
