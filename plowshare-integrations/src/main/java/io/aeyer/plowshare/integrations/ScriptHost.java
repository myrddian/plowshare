package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

/** Wall/CPU deadline, child heap limit, bounded pipes and no inherited credentials. */
public final class ScriptHost {
  private final Duration deadline;

  public ScriptHost(Duration deadline) {
    if (deadline == null || deadline.isNegative() || deadline.isZero())
      throw new IllegalArgumentException("positive script deadline required");
    this.deadline = deadline;
  }

  public JsonNode evaluate(String source, String handler, JsonNode event, JsonNode context)
      throws IOException {
    var input = Json.object();
    input.put("source", source);
    input.put("handler", handler);
    input.set("event", event);
    input.set("context", context);
    byte[] bytes = Json.MAPPER.writeValueAsBytes(input);
    if (bytes.length > Json.MAX_MESSAGE) throw new IOException("script input exceeds limit");
    String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("integration.worker.classpath", System.getProperty("java.class.path"));
    ProcessBuilder builder =
        new ProcessBuilder(
            executable,
            "-Xmx128m",
            "-XX:MaxDirectMemorySize=16m",
            "-cp",
            classpath,
            ScriptWorker.class.getName());
    builder.environment().clear();
    Process process = builder.start();
    ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    Future<byte[]> out = readers.submit(() -> bounded(process.getInputStream(), process));
    Future<byte[]> err = readers.submit(() -> bounded(process.getErrorStream(), process));
    Future<?> writer =
        readers.submit(
            () -> {
              try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(bytes);
              }
              return null;
            });
    long end = System.nanoTime() + deadline.toNanos();
    try {
      while (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
        if (System.nanoTime() > end
            || process.info().totalCpuDuration().orElse(Duration.ZERO).compareTo(deadline) > 0)
          throw new IOException("script execution limit exceeded");
      }
      byte[] result = out.get(1, TimeUnit.SECONDS);
      writer.get(1, TimeUnit.SECONDS);
      err.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new IOException("script handler failed");
      JsonNode output = Json.parse(new String(result, java.nio.charset.StandardCharsets.UTF_8));
      if (!output.path("effects").isArray()
          || output.path("effects").size() > 32
          || !output.path("state").isObject()) throw new IOException("invalid script output");
      return output;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("script execution interrupted");
    } catch (ExecutionException | TimeoutException failed) {
      throw new IOException("script output unavailable");
    } finally {
      process.destroyForcibly();
      out.cancel(true);
      err.cancel(true);
      writer.cancel(true);
      readers.shutdownNow();
    }
  }

  private static byte[] bounded(InputStream input, Process process) throws IOException {
    byte[] bytes = input.readNBytes(Json.MAX_MESSAGE + 1);
    if (bytes.length > Json.MAX_MESSAGE) {
      process.destroyForcibly();
      throw new IOException("script output exceeds limit");
    }
    return bytes;
  }
}
