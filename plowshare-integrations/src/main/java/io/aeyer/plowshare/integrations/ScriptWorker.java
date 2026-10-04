package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;

/** One credential-free subprocess. It evaluates pure handlers and returns plans. */
public final class ScriptWorker {
  private ScriptWorker() {}

  public static void main(String[] args) throws Exception {
    try {
      byte[] bytes = System.in.readNBytes(Json.MAX_MESSAGE + 1);
      if (bytes.length > Json.MAX_MESSAGE) throw new IllegalArgumentException("input limit");
      JsonNode request = Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      try (Engine engine =
              Engine.newBuilder("js")
                  .option("engine.WarnInterpreterOnly", "false")
                  .out(OutputStream.nullOutputStream())
                  .err(OutputStream.nullOutputStream())
                  .build();
          Context context =
              Context.newBuilder("js")
                  .engine(engine)
                  .allowHostAccess(HostAccess.NONE)
                  .allowHostClassLookup(name -> false)
                  .allowIO(IOAccess.NONE)
                  .allowCreateThread(false)
                  .allowCreateProcess(false)
                  .allowNativeAccess(false)
                  .allowEnvironmentAccess(EnvironmentAccess.NONE)
                  .allowPolyglotAccess(PolyglotAccess.NONE)
                  .allowExperimentalOptions(true)
                  .option("js.esm-eval-returns-exports", "true")
                  .option("js.load", "false")
                  .option("js.graal-builtin", "false")
                  .option("js.java-package-globals", "false")
                  .build()) {
        Value module =
            context.eval(
                Source.newBuilder("js", Json.text(request, "source"), "mapping.mjs")
                    .mimeType("application/javascript+module")
                    .build());
        Value handlers = module.getMember("default");
        String handlerName = request.path("handler").asText();
        Value handler =
            handlers == null || handlerName.isBlank() ? null : handlers.getMember(handlerName);
        if (handlers == null || !handlers.hasMembers())
          throw new IllegalArgumentException("default handler object required");
        if (handler != null && !handler.canExecute())
          throw new IllegalArgumentException("invalid handler");
        Value input =
            context.eval(
                "js",
                "JSON.parse("
                    + Json.MAPPER.writeValueAsString(request.path("context").toString())
                    + ")");
        Value event =
            context.eval(
                "js",
                "JSON.parse("
                    + Json.MAPPER.writeValueAsString(request.path("event").toString())
                    + ")");
        Value make =
            context.eval(
                "js",
                """
(input) => {
  const freeze = x => { if (x && typeof x === 'object') { Object.values(x).forEach(freeze); Object.freeze(x); } return x; };
  const states=freeze(input.states ?? {}), runs=freeze(input.runs ?? {});
  return Object.freeze({state:input.state ?? {},
    readStates:(binding,aliases) => Object.fromEntries(aliases.map(a=>[a,states[binding]?.[a] ?? {availability:'missing'}])),
    getRunStatus:id => runs[id] ?? null,
    startPipeline:(route,input,options) => ({kind:'pipeline.start',route,input,key:options?.key}),
    executeAction:(binding,action,parameters,options) => ({kind:'action',binding,action,parameters,key:options?.key}),
    read:(binding,aliases,options) => ({kind:'read',binding,entities:aliases,key:options?.key})});
}
""");
        Value ctx = make.execute(input);
        Value effects = handler == null ? context.eval("js", "[]") : handler.execute(event, ctx);
        Value serialize = context.eval("js", "(effects,state)=>JSON.stringify({effects,state})");
        String result = serialize.execute(effects, ctx.getMember("state")).asString();
        JsonNode output = Json.parse(result);
        if (!output.path("effects").isArray()
            || output.path("effects").size() > 32
            || !output.path("state").isObject())
          throw new IllegalArgumentException("invalid handler output");
        // The pipe protocol is UTF-8 even when the credential-free process has no locale
        // and its console PrintStream defaults to ASCII. Write bytes without console encoding.
        System.out.write(Json.MAPPER.writeValueAsBytes(output));
      }
    } catch (Throwable failed) {
      System.err.print("integration handler failed");
      System.exit(1);
    }
  }
}
