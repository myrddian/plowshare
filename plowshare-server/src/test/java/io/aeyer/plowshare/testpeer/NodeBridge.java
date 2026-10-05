package io.aeyer.plowshare.testpeer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bounded stdin/stdout bridge to the real TypeScript fixtures. Credentials never enter arguments.
 */
public final class NodeBridge {
  private NodeBridge() {}

  public static String exchange(String input) throws IOException {
    Path script = Path.of("src/test/node/peer.mjs").toAbsolutePath();
    Process process =
        new ProcessBuilder("node", script.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    // Drain concurrently: file replies can exceed a pipe buffer, so waiting for exit first
    // deadlocks.
    var output =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            });
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(input.getBytes(StandardCharsets.UTF_8));
      }
      if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS))
        throw new IOException("Node fixture deadline expired");
      if (process.exitValue() != 0) throw new IOException("Node fixture failed");
      return output.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new IOException("Node fixture interrupted", stopped);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      throw new IOException("Node fixture output failed", failure);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
