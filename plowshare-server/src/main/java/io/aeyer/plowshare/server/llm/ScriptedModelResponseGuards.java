package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.llm.dispatch.ModelResponseGuards;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/** Loads the JavaScript selected by deployment-owned profiles into the existing no-I/O sandbox. */
final class ScriptedModelResponseGuards implements ModelResponseGuards, AutoCloseable {
  private static final int MAX_SCRIPT_BYTES = 64 * 1024;
  private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

  private final SamplingProfiles profiles;
  private final HookEngine engine = new HookEngine();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("model-response-guard").factory());
  private final Map<String, Guard> guards;

  ScriptedModelResponseGuards(SamplingProfiles profiles, Path directory) {
    this.profiles = profiles;
    Map<String, Guard> loaded = new LinkedHashMap<>();
    try {
      for (String name : profiles.responseGuardNames()) {
        Source source = load(directory, name);
        evaluate(source, "");
        loaded.put(name, new Guard(name, content -> evaluate(source, content)));
      }
      guards = Map.copyOf(loaded);
    } catch (RuntimeException | Error failed) {
      close();
      throw failed;
    }
  }

  @Override
  public Optional<Guard> forModel(String wireModel) {
    return profiles.responseGuardFor(wireModel).map(guards::get);
  }

  private static Source load(Path directory, String name) {
    String file = name + ".mjs";
    Path overrides = directory == null ? null : directory.resolve("response-guards");
    if (overrides != null && Files.isSymbolicLink(overrides))
      throw new IllegalStateException("response-guards must be a directory, not a symlink");
    Path override = overrides == null ? null : overrides.resolve(file);
    try (InputStream in =
        override != null && Files.exists(override, LinkOption.NOFOLLOW_LINKS)
            ? Files.newInputStream(override, LinkOption.NOFOLLOW_LINKS)
            : ScriptedModelResponseGuards.class.getResourceAsStream(
                "/sampling/response-guards/" + file)) {
      if (in == null) throw new IllegalStateException("Unknown model response guard " + name);
      byte[] bytes = in.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length > MAX_SCRIPT_BYTES)
        throw new IllegalStateException("Model response guard exceeds 64 KiB: " + name);
      String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      return Source.newBuilder("js", source, file)
          .mimeType("application/javascript+module")
          .buildLiteral();
    } catch (IOException failed) {
      throw new IllegalStateException("Cannot load model response guard " + name, failed);
    }
  }

  private boolean evaluate(Source source, String content) {
    if (content.length() > MAX_RESPONSE_CHARS)
      throw new IllegalStateException("Model response exceeds the guard's input bound");
    // Each response gets a clean context. Profiles cannot leave state for another account's call.
    try (Context context = engine.newContext()) {
      var deadline = timer.schedule(() -> context.close(true), 2, TimeUnit.SECONDS);
      try {
        var check = context.eval(source).getMember("reject");
        if (check == null || !check.canExecute())
          throw new IllegalStateException("guard must export reject(text)");
        var result = check.execute(content);
        if (!result.isBoolean()) throw new IllegalStateException("guard must return a boolean");
        return result.asBoolean();
      } finally {
        deadline.cancel(false);
      }
    } catch (RuntimeException failed) {
      // Do not propagate guest error messages, which can echo model output.
      throw new IllegalStateException("Model response guard failed: " + source.getName());
    }
  }

  @Override
  public void close() {
    timer.shutdownNow();
    engine.close();
  }
}
